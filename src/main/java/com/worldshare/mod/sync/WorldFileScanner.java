package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.util.SHA256Util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Walks a Minecraft world folder, applies the {@link TrackedPaths} filter,
 * and produces a {@link WorldManifest} of every tracked file with its
 * SHA-256 hash, size, and mtime.
 *
 * <p><b>M8 optimizations:</b>
 * <ul>
 *   <li><b>mtime + size pre-check</b>: if a file's mtime and size exactly match the
 *       local scan cache from the previous push, the cached hash is reused without
 *       reading the file. Typical speedup: 5-10x for sessions where most files are
 *       unchanged. NTFS millisecond resolution makes exact mtime comparison safe on
 *       Windows.</li>
 *   <li><b>Dirty region filter</b>: if {@link DirtyRegionTracker} is active, only
 *       .mca files that MC actually wrote this session are included in the scan.
 *       Non-.mca files (level.dat, playerdata, etc.) are always included.
 *       Typical impact: 50+ region files → 2-5, depending on where you built.</li>
 * </ul>
 *
 * <p>Note: this is NOT thread-safe per instance, but each call to
 * {@link #scan} is independent — call from any thread, just not concurrently
 * with itself on the same world root.
 */
public final class WorldFileScanner {

    private WorldFileScanner() {}

    /**
     * Full scan — no mtime cache, no dirty filtering. Used for pull() comparisons
     * and first-time pushes where all files must be considered.
     */
    public static WorldManifest scan(final Path worldRoot, final UUID ownPlayerUuid)
            throws IOException {
        return scan(worldRoot, ownPlayerUuid, null, null, false);
    }

    /**
     * Optimized scan with optional mtime pre-check and dirty-region filtering.
     *
     * @param worldRoot         absolute path to the world folder
     * @param ownPlayerUuid     retained for callers' convenience; no longer used for
     *                          filtering, since every player's data syncs
     * @param scanCache         local scan cache from previous push; null = always hash every file.
     *                          When provided, files whose mtime+size match the cache reuse the
     *                          cached hash without reading the file.
     * @param dirtyRegionPaths  set of .mca rel paths known to be dirty; ignored when
     *                          {@code filterRegions} is false
     * @param filterRegions     true only when dirty tracking is active and no unknown-dim changes
     *                          have occurred (see {@link DirtyRegionTracker#shouldFilterRegions()}).
     *                          When true, .mca files NOT in dirtyRegionPaths are skipped entirely.
     * @return manifest with one entry per tracked file
     */
    public static WorldManifest scan(final Path worldRoot,
                                     final UUID ownPlayerUuid,
                                     final WorldManifest scanCache,
                                     final Set<String> dirtyRegionPaths,
                                     final boolean filterRegions) throws IOException {
        if (!Files.isDirectory(worldRoot)) {
            throw new IOException("Not a directory: " + worldRoot);
        }

        final WorldManifest manifest = new WorldManifest();
        manifest.generatedAt = Instant.now().toString();

        final List<Path> trackedFiles = collectTrackedFiles(
                worldRoot, ownPlayerUuid, dirtyRegionPaths, filterRegions);

        WorldShareMod.LOGGER.info(
                "WorldFileScanner: {} tracked files in {} [filter={}, cache={}]",
                trackedFiles.size(), worldRoot.getFileName(),
                filterRegions ? "ON" : "OFF",
                scanCache != null ? scanCache.size() + " entries" : "none");

        int cacheHits = 0;
        long totalBytes = 0L;

        for (final Path file : trackedFiles) {
            try {
                final long size = Files.size(file);
                final long mtimeMs = Files.getLastModifiedTime(file).toMillis();
                final String mtime = Instant.ofEpochMilli(mtimeMs).toString();
                final String relPath = relativeForwardSlash(worldRoot, file);

                // M8: mtime + size pre-check. If both match the cache, reuse the stored hash.
                // NTFS has 100ns resolution — millisecond equality comparison is safe on Windows.
                // Inner try-catch: Instant.parse() throws DateTimeParseException (RuntimeException)
                // on a malformed cache entry, which wouldn't be caught by the outer IOException
                // catch. Treat any parse failure as a cache miss and fall through to SHA-256.
                if (scanCache != null) {
                    final WorldManifest.Entry cached = scanCache.get(relPath);
                    if (cached != null && cached.sha256 != null
                            && cached.size == size && cached.mtime != null) {
                        try {
                            if (mtimeMs == Instant.parse(cached.mtime).toEpochMilli()) {
                                manifest.put(relPath,
                                        new WorldManifest.Entry(cached.sha256, size, mtime));
                                totalBytes += size;
                                cacheHits++;
                                continue;
                            }
                        } catch (final Exception ignored) {
                            // Malformed mtime in cache — fall through to full SHA-256.
                        }
                    }
                }

                // Cache miss (or no cache) — full SHA-256.
                final String sha256 = SHA256Util.hashFile(file);
                manifest.put(relPath, new WorldManifest.Entry(sha256, size, mtime));
                totalBytes += size;

            } catch (final IOException e) {
                // One bad file shouldn't kill the whole scan.
                WorldShareMod.LOGGER.warn("WorldFileScanner: failed to hash {}, skipping", file, e);
            }
        }

        WorldShareMod.LOGGER.info(
                "WorldFileScanner: {} files, {} MB, {} mtime cache hits, {} hashed",
                manifest.size(),
                totalBytes / (1024 * 1024),
                cacheHits,
                trackedFiles.size() - cacheHits);
        reportUnfamiliarContent(manifest);
        return manifest;
    }

    /**
     * Top-level entries Minecraft itself is known to write.
     *
     * <p>Only used for reporting. Nothing here affects what syncs - the point is to
     * be able to say "this came from a mod" without pretending to know which.
     */
    private static final java.util.Set<String> FAMILIAR_TOP_LEVEL = java.util.Set.of(
            "region", "entities", "poi", "playerdata", "stats", "advancements",
            "data", "resources", "datapacks", "v_data", "serverconfig",
            "dim-1", "dim1", "dimensions", "level.dat", "level.dat_old");

    /** A single unfamiliar entry big enough to be worth a warning: 128 MB. */
    private static final long LARGE_UNFAMILIAR_BYTES = 128L * 1024 * 1024;

    /**
     * Say what we're carrying that we don't recognise.
     *
     * <p>Syncing everything by default is the right trade - a file that silently
     * never syncs costs somebody their work - but it must not become a different
     * kind of silence, where a mod parks a large cache in the world folder and every
     * push quietly carries it forever.
     *
     * <p>So the log names every unfamiliar top-level entry with its size, which is
     * also how we find out what mods actually write rather than guessing.
     *
     * <p>Log only. Telling the player lives in the push, where it can be said
     * truthfully: a scan sees a large folder whether or not this sync will carry
     * it, and warning here claimed a 225 MB folder would "sync every time" on every
     * world open, when in fact it uploads only when it changes. A warning that
     * cries wolf on every save teaches people to ignore it.
     */
    private static void reportUnfamiliarContent(final WorldManifest manifest) {
        final java.util.Map<String, Long> byTopLevel = new java.util.TreeMap<>();
        for (final java.util.Map.Entry<String, WorldManifest.Entry> e
                : manifest.files().entrySet()) {
            final String rel = e.getKey();
            final int slash = rel.indexOf('/');
            final String top = slash > 0 ? rel.substring(0, slash) : rel;
            if (FAMILIAR_TOP_LEVEL.contains(top.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            byTopLevel.merge(top, e.getValue() == null ? 0L : e.getValue().size, Long::sum);
        }
        if (byTopLevel.isEmpty()) return;

        for (final java.util.Map.Entry<String, Long> e : byTopLevel.entrySet()) {
            WorldShareMod.LOGGER.info("WorldFileScanner: also syncing '{}' ({} KB)",
                    e.getKey(), e.getValue() / 1024);
            if (e.getValue() >= LARGE_UNFAMILIAR_BYTES) {
                WorldShareMod.LOGGER.warn("WorldFileScanner: '{}' is {} MB",
                        e.getKey(), e.getValue() / (1024 * 1024));
            }
        }
    }

    /**
     * Collects tracked files under worldRoot, applying the dirty-region filter
     * if {@code filterRegions} is true.
     */
    private static List<Path> collectTrackedFiles(final Path worldRoot,
                                                  final UUID ownUuid,
                                                  final Set<String> dirtyRegionPaths,
                                                  final boolean filterRegions) throws IOException {
        final List<Path> tracked = new ArrayList<>();

        Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                if (!TrackedPaths.isTracked(worldRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }

                // M8: dirty region filter — skip .mca files not in the dirty set.
                // Non-.mca files are always included regardless.
                if (filterRegions) {
                    final String rel = relativeForwardSlash(worldRoot, file);
                    if (TrackedPaths.isMcaFile(rel)) {
                        if (dirtyRegionPaths == null || !dirtyRegionPaths.contains(rel)) {
                            return FileVisitResult.CONTINUE; // not dirty — skip
                        }
                    }
                }

                tracked.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                WorldShareMod.LOGGER.warn("WorldFileScanner: walk error at {}: {}",
                        file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        // Sort by relative path for deterministic manifest output.
        tracked.sort(Comparator.comparing(p -> relativeForwardSlash(worldRoot, p)));
        return tracked;
    }

    /** Forward-slash relative path inside the world, regardless of host OS. */
    static String relativeForwardSlash(final Path worldRoot, final Path file) {
        return worldRoot.relativize(file).toString().replace('\\', '/');
    }
}