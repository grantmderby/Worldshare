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
import java.util.UUID;

/**
 * Walks a Minecraft world folder, applies the {@link TrackedPaths} filter,
 * and produces a {@link WorldManifest} of every tracked file with its
 * SHA-256 hash, size, and mtime.
 *
 * <p>For a typical pre-generated world (~64 region files of ~5MB each), this
 * takes a few seconds on first run. The hashing is the bottleneck; we run
 * sequentially to keep memory pressure low and Drive API calls in order.
 *
 * <p>Note: this is NOT thread-safe per instance, but each call to
 * {@link #scan(Path, UUID)} is independent — call from any thread, just not
 * concurrently with itself on the same instance.
 */
public final class WorldFileScanner {

    private WorldFileScanner() {
        // utility class
    }

    /**
     * Walk {@code worldRoot} and produce a manifest of tracked files.
     *
     * @param worldRoot     absolute path to the world folder
     * @param ownPlayerUuid UUID of the local player; used to filter per-UUID files
     * @return a manifest with one entry per tracked file
     */
    public static WorldManifest scan(final Path worldRoot, final UUID ownPlayerUuid)
            throws IOException {
        if (!Files.isDirectory(worldRoot)) {
            throw new IOException("Not a directory: " + worldRoot);
        }

        final WorldManifest manifest = new WorldManifest();
        manifest.generatedAt = Instant.now().toString();
        // generatedByMachineId is set by the SyncEngine, which has access to MachineId.
        // (Keeping this class free of dependencies on MachineId for testability.)

        final List<Path> trackedFiles = collectTrackedFiles(worldRoot, ownPlayerUuid);
        WorldShareMod.LOGGER.info("WorldFileScanner: {} tracked files in {}",
                trackedFiles.size(), worldRoot.getFileName());

        long totalBytes = 0L;
        for (final Path file : trackedFiles) {
            try {
                final long size = Files.size(file);
                final String mtime = Files.getLastModifiedTime(file).toInstant().toString();
                final String sha256 = SHA256Util.hashFile(file);
                final String relPath = relativeForwardSlash(worldRoot, file);
                manifest.put(relPath, new WorldManifest.Entry(sha256, size, mtime));
                totalBytes += size;
            } catch (final IOException e) {
                // One bad file shouldn't kill the whole scan. Log and continue.
                // The push that follows will be missing this file — better than no
                // manifest at all.
                WorldShareMod.LOGGER.warn("WorldFileScanner: failed to hash {}, skipping",
                        file, e);
            }
        }

        WorldShareMod.LOGGER.info(
                "WorldFileScanner: scanned {} files totaling {} bytes ({} MB)",
                manifest.size(), totalBytes, totalBytes / (1024 * 1024));
        return manifest;
    }

    /**
     * @return a sorted list of all tracked files under {@code worldRoot}.
     *         Sorted to give deterministic manifest output across machines.
     */
    private static List<Path> collectTrackedFiles(final Path worldRoot,
                                                  final UUID ownUuid) throws IOException {
        final List<Path> tracked = new ArrayList<>();
        Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                if (TrackedPaths.isTracked(worldRoot, file, ownUuid)) {
                    tracked.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                // Log and continue. A missing file or permission error on one entry
                // shouldn't kill the whole walk.
                WorldShareMod.LOGGER.warn("WorldFileScanner: walk error at {}: {}",
                        file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        // Sort by relative path for determinism.
        tracked.sort(Comparator.comparing(p -> relativeForwardSlash(worldRoot, p)));
        return tracked;
    }

    /** Forward-slash relative path inside the world, regardless of host OS. */
    static String relativeForwardSlash(final Path worldRoot, final Path file) {
        return worldRoot.relativize(file).toString().replace('\\', '/');
    }
}
