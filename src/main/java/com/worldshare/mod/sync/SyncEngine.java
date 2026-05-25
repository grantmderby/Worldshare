package com.worldshare.mod.sync;

import com.google.gson.JsonSyntaxException;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.util.MachineId;
import com.worldshare.mod.util.SHA256Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes/pulls a world to/from Drive.
 *
 * <p><b>M7:</b> pull accepts SyncProgress; strips level.dat Player tag every pull;
 * push uploads in parallel (pool 4); per-file timing logs.
 *
 * <p><b>M8:</b>
 * <ul>
 *   <li>push uses {@link DirtyRegionTracker} to skip .mca files not written this session,
 *       plus a local scan cache ({@value SCAN_CACHE_FILENAME}) for mtime pre-checks.</li>
 *   <li>pull invalidates the scan cache and resets dirty tracking after download.</li>
 *   <li>pull downloads in parallel (pool 4, largest-first) — mirrors push semantics.
 *       Retry + 416 handling moved inside each worker.</li>
 * </ul>
 */
public final class SyncEngine {

    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String MANIFEST_PENDING_FILENAME = "manifest_pending.json";
    public static final String WORLD_SUBFOLDER = "world";

    /**
     * Local scan cache filename — stored in the world folder, excluded from sync.
     * Persists SHA-256/size/mtime for files scanned on the last successful push so
     * subsequent scans can skip SHA-256 for unchanged files.
     */
    static final String SCAN_CACHE_FILENAME = "worldshare-scan-cache.json";

    private SyncEngine() {}

    // ---- STATUS ----

    public static SyncDiff status(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid) throws IOException {
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final WorldManifest drive = readDriveManifest(driveFolderId);
        return SyncDiff.compute(local, drive);
    }

    // ---- PULL ----

    public static PullResult pull(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid) throws IOException {
        return pull(worldRoot, driveFolderId, ownUuid, SyncProgress.NOOP);
    }

    public static PullResult pull(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid,
                                  final SyncProgress progress) throws IOException {
        Files.createDirectories(worldRoot);

        final DriveClient client = CloudModule.driveClient();
        final WorldManifest driveManifest = readDriveManifest(driveFolderId);

        if (driveManifest == null) {
            WorldShareMod.LOGGER.info("pull: no Drive manifest yet (first sync); nothing to pull");
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PullResult(0, 0, 0L);
        }

        // Full scan for pull comparison — no dirty filter (we need all local hashes).
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final SyncDiff diff = SyncDiff.compute(local, driveManifest);

        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, false);
        if (driveWorldFolderId == null) {
            WorldShareMod.LOGGER.warn(
                    "pull: Drive manifest exists but no world/ subfolder; nothing to pull");
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PullResult(0, 0, 0L);
        }

        final List<String> toDownload = new ArrayList<>();
        toDownload.addAll(diff.onlyOnDrive);
        toDownload.addAll(diff.different);

        // M8: largest first — each worker grabs a big file ASAP rather than finishing
        // small files and waiting on a single large file at the end.
        toDownload.sort((a, b) -> {
            final WorldManifest.Entry ea = driveManifest.get(a);
            final WorldManifest.Entry eb = driveManifest.get(b);
            final long sizeA = ea != null ? ea.size : 0L;
            final long sizeB = eb != null ? eb.size : 0L;
            return Long.compare(sizeB, sizeA); // descending
        });

        long totalBytes = 0L;
        for (final String relPath : toDownload) {
            final WorldManifest.Entry e = driveManifest.get(relPath);
            if (e != null) totalBytes += e.size;
        }
        progress.onStart(toDownload.size(), totalBytes);

        final AtomicInteger downloadedRef = new AtomicInteger(0);
        final AtomicInteger failedRef = new AtomicInteger(0);
        final AtomicLong bytesRef = new AtomicLong(0);

        if (!toDownload.isEmpty()) {
            parallelDownload(client, driveWorldFolderId, toDownload, worldRoot,
                    driveManifest, progress, totalBytes,
                    downloadedRef, failedRef, bytesRef);
        }

        final int downloaded = downloadedRef.get();
        final int failed = failedRef.get();
        final long bytes = bytesRef.get();

        // M7: strip Player tag — runs every pull, not just first time.
        stripPlayerFromLevelDat(worldRoot);

        // M8: after pull, local matches Drive — invalidate scan cache and reset tracker.
        // Downloaded files have current-time mtimes which would cause false cache hits.
        try {
            Files.deleteIfExists(worldRoot.resolve(SCAN_CACHE_FILENAME));
        } catch (final IOException ignored) {}
        DirtyRegionTracker.resetAfterPull();

        if (failed > 0) {
            progress.onError(new IOException(failed + " file(s) failed to download"));
            WorldShareMod.LOGGER.error(
                    "pull: {} downloaded, {} FAILED, {} bytes", downloaded, failed, bytes);
            throw new IOException(failed + " file(s) failed to download. Retry pull.");
        }

        progress.onComplete();
        WorldShareMod.LOGGER.info(
                "pull complete: {} downloaded, {} bytes ({} MB), {} unchanged",
                downloaded, bytes, bytes / (1024 * 1024), diff.identical.size());
        return new PullResult(downloaded, failed, bytes);
    }

    // ---- PARALLEL DOWNLOAD HELPER (M8) ----

    private static void parallelDownload(final DriveClient client,
                                         final String driveWorldFolderId,
                                         final List<String> toDownload,
                                         final Path worldRoot,
                                         final WorldManifest driveManifest,
                                         final SyncProgress progress,
                                         final long totalBytes,
                                         final AtomicInteger downloadedRef,
                                         final AtomicInteger failedRef,
                                         final AtomicLong bytesRef) throws IOException {
        // M8: pool size 4 — matches upload pool, residential-friendly, Drive-polite.
        final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "WorldShare-Download");
            t.setDaemon(true);
            return t;
        });
        final CompletionService<DownloadTaskResult> completion =
                new ExecutorCompletionService<>(pool);

        for (final String relPath : toDownload) {
            final WorldManifest.Entry expected = driveManifest.get(relPath);
            completion.submit(() -> {
                // downloadWithRetry handles IOExceptions internally and returns a result.
                // Outer Throwable catch matches parallelUpload — any unexpected RuntimeException
                // or Error from downloadOne becomes a failed result rather than poisoning
                // the CompletionService with an ExecutionException.
                try {
                    return downloadWithRetry(client, driveWorldFolderId,
                            relPath, worldRoot, expected);
                } catch (final Throwable t) {
                    return new DownloadTaskResult(relPath, false, 0L,
                            new IOException("Unexpected error: " + t.getMessage(), t));
                }
            });
        }
        pool.shutdown();

        try {
            for (int i = 0; i < toDownload.size(); i++) {
                final DownloadTaskResult res = completion.take().get();
                if (res.success) {
                    final int done = downloadedRef.incrementAndGet();
                    final long bytes = bytesRef.addAndGet(res.sizeDownloaded);
                    progress.onFileProgress(done + failedRef.get(), toDownload.size(),
                            bytes, totalBytes, res.relPath);
                } else {
                    final int failed = failedRef.incrementAndGet();
                    WorldShareMod.LOGGER.error(
                            "pull: gave up on {}: {}", res.relPath,
                            res.error != null ? res.error.getMessage() : "unknown");
                    progress.onFileProgress(downloadedRef.get() + failed, toDownload.size(),
                            bytesRef.get(), totalBytes, res.relPath);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("Pull interrupted", e);
        } catch (final ExecutionException e) {
            // Workers catch Throwable, so this is unreachable. Defensive.
            throw new IOException("Unexpected download failure", e.getCause());
        }
    }

    /**
     * Downloads a single file with 3-attempt retry and 416 handling. Runs on a worker
     * thread. Never throws checked exceptions — all IOExceptions are captured in the
     * returned {@link DownloadTaskResult}. Only unchecked Throwables can propagate,
     * which the submit lambda's outer catch handles.
     */
    private static DownloadTaskResult downloadWithRetry(final DriveClient client,
                                                        final String driveWorldFolderId,
                                                        final String relPath,
                                                        final Path worldRoot,
                                                        final WorldManifest.Entry expected) {
        IOException lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                downloadOne(client, driveWorldFolderId, relPath, worldRoot, expected);
                if (attempt > 1) {
                    WorldShareMod.LOGGER.info(
                            "pull: succeeded {} on retry attempt {}", relPath, attempt);
                }
                return new DownloadTaskResult(relPath, true,
                        expected == null ? 0L : expected.size, null);
            } catch (final IOException e) {
                // M7: 416 = empty file on Drive (or smaller than expected).
                // Direct downloads should prevent this, but if it slips through, treat as
                // a 0-byte file and stop retrying — retries on this won't help.
                if (is416(e)) {
                    WorldShareMod.LOGGER.info(
                            "pull: {} returned 416, treating as 0-byte file", relPath);
                    try {
                        final Path target = worldRoot.resolve(relPath);
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.write(target, new byte[0]);
                        return new DownloadTaskResult(relPath, true, 0L, null);
                    } catch (final IOException writeErr) {
                        WorldShareMod.LOGGER.warn(
                                "pull: couldn't create 0-byte placeholder for {}: {}",
                                relPath, writeErr.getMessage());
                        return new DownloadTaskResult(relPath, false, 0L, writeErr);
                    }
                }

                lastError = e;
                WorldShareMod.LOGGER.warn(
                        "pull: attempt {} failed for {}: {}",
                        attempt, relPath, e.getMessage());

                if (attempt < 3) {
                    try {
                        // Backoff inside the worker — only blocks this thread.
                        // Other workers continue downloading.
                        Thread.sleep(2000L * attempt);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new DownloadTaskResult(relPath, false, 0L,
                                new IOException("Interrupted during retry", ie));
                    }
                }
            }
        }
        return new DownloadTaskResult(relPath, false, 0L, lastError);
    }

    // ---- PUSH ----

    public static PushResult push(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid,
                                  final WorldManifest baseline) throws IOException {
        return push(worldRoot, driveFolderId, ownUuid, baseline, SyncProgress.NOOP);
    }

    public static PushResult push(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid,
                                  final WorldManifest baseline,
                                  final SyncProgress progress) throws IOException {
        final DriveClient client = CloudModule.driveClient();

        // Read Drive manifest first so we know whether this is first-time or incremental.
        final WorldManifest drive = readDriveManifest(driveFolderId);

        final WorldManifest local;
        final WorldManifest scanCache; // kept for scan cache save after commit

        if (drive == null) {
            // First-time push — full scan, no dirty filter or mtime cache.
            // We must upload everything; skipping any file leaves Drive incomplete.
            WorldShareMod.LOGGER.info("push: no Drive manifest — first-time push, full scan");
            local = WorldFileScanner.scan(worldRoot, ownUuid);
            scanCache = null;
        } else {
            // Incremental push — dirty filter + mtime cache where available.
            scanCache = WorldManifest.loadFromDisk(worldRoot.resolve(SCAN_CACHE_FILENAME));
            final boolean filterRegions = DirtyRegionTracker.shouldFilterRegions();
            final Set<String> dirtyPaths = filterRegions
                    ? DirtyRegionTracker.getDirtyPaths()
                    : null;

            WorldShareMod.LOGGER.info(
                    "push: incremental scan [dirtyFilter={}, dirtyPaths={}, cache={}]",
                    filterRegions,
                    dirtyPaths != null ? dirtyPaths.size() + " files" : "n/a",
                    scanCache != null ? scanCache.size() + " entries" : "none");

            local = WorldFileScanner.scan(worldRoot, ownUuid, scanCache, dirtyPaths, filterRegions);
        }

        local.generatedByMachineId = MachineId.get();

        if (drive == null) {
            return pushFirstTime(client, worldRoot, driveFolderId, local, progress);
        }

        final SyncDiff diff = SyncDiff.compute(local, drive);
        final List<String> toUpload = new ArrayList<>();
        int skippedStale = 0;

        for (final String relPath : diff.different) {
            if (baseline != null) {
                final WorldManifest.Entry baseEntry = baseline.get(relPath);
                final WorldManifest.Entry localEntry = local.get(relPath);
                if (baseEntry != null && baseEntry.sha256 != null
                        && baseEntry.sha256.equals(localEntry.sha256)) {
                    skippedStale++;
                    local.put(relPath, drive.get(relPath));
                    continue;
                }
            }
            toUpload.add(relPath);
        }
        toUpload.addAll(diff.onlyLocal);

        // Files only on Drive (not in local scan) — preserve their Drive entries in
        // the manifest so we don't delete them. Includes non-dirty .mca files that
        // were filtered out of the local scan.
        for (final String relPath : diff.onlyOnDrive) {
            local.put(relPath, drive.get(relPath));
        }

        toUpload.sort((a, b) -> {
            final WorldManifest.Entry ea = local.get(a);
            final WorldManifest.Entry eb = local.get(b);
            final long sizeA = ea != null ? ea.size : 0L;
            final long sizeB = eb != null ? eb.size : 0L;
            return Long.compare(sizeB, sizeA);
        });

        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, true);

        long totalBytes = 0L;
        for (final String relPath : toUpload) {
            final WorldManifest.Entry entry = local.get(relPath);
            if (entry != null) totalBytes += entry.size;
        }
        progress.onStart(toUpload.size(), totalBytes);

        final AtomicInteger uploadedRef = new AtomicInteger(0);
        final AtomicInteger failedRef = new AtomicInteger(0);
        final AtomicLong bytesRef = new AtomicLong(0);

        parallelUpload(client, driveWorldFolderId, toUpload, worldRoot, local,
                progress, totalBytes, uploadedRef, failedRef, bytesRef);

        final int uploaded = uploadedRef.get();
        final int failed = failedRef.get();
        final long bytes = bytesRef.get();

        if (failed == 0) {
            if (!com.worldshare.mod.cloud.LockManager.weHoldLock()) {
                WorldShareMod.LOGGER.error(
                        "push: lock no longer ours, aborting manifest commit. "
                                + "{} files were uploaded but manifest is unchanged.",
                        uploaded);
                progress.onError(new IOException(
                        "Your session lock was overridden during upload. "
                                + "Files were uploaded but the manifest was NOT updated. "
                                + "Your changes are still saved locally. "
                                + "Coordinate with the other player and retry."));
            } else {
                commitManifest(client, driveFolderId, local);
                // M8: save scan cache after successful commit. Merge with old cache so
                // non-dirty .mca files (not re-scanned this push) keep their local mtime.
                saveScanCache(local, scanCache, worldRoot);
                DirtyRegionTracker.resetAfterPush();
                progress.onComplete();
            }
        } else {
            progress.onError(new IOException(failed + " upload(s) failed; manifest not updated"));
        }

        WorldShareMod.LOGGER.info(
                "push complete: {} uploaded, {} skipped, {} failed, {} bytes",
                uploaded, skippedStale, failed, bytes);
        return new PushResult(uploaded, skippedStale, failed, bytes);
    }

    private static PushResult pushFirstTime(final DriveClient client,
                                            final Path worldRoot,
                                            final String driveFolderId,
                                            final WorldManifest local,
                                            final SyncProgress progress) throws IOException {
        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, true);
        final List<String> toUpload = new ArrayList<>(local.files().keySet());
        toUpload.sort((a, b) -> {
            final WorldManifest.Entry ea = local.get(a);
            final WorldManifest.Entry eb = local.get(b);
            final long sizeA = ea != null ? ea.size : 0L;
            final long sizeB = eb != null ? eb.size : 0L;
            return Long.compare(sizeB, sizeA);
        });

        long totalBytes = 0L;
        for (final String relPath : toUpload) {
            final WorldManifest.Entry e = local.get(relPath);
            if (e != null) totalBytes += e.size;
        }
        progress.onStart(toUpload.size(), totalBytes);

        final AtomicInteger uploadedRef = new AtomicInteger(0);
        final AtomicInteger failedRef = new AtomicInteger(0);
        final AtomicLong bytesRef = new AtomicLong(0);

        parallelUpload(client, driveWorldFolderId, toUpload, worldRoot, local,
                progress, totalBytes, uploadedRef, failedRef, bytesRef);

        if (failedRef.get() == 0) {
            if (!com.worldshare.mod.cloud.LockManager.weHoldLock()) {
                WorldShareMod.LOGGER.error(
                        "push: lock no longer ours, aborting manifest commit. "
                                + "{} files were uploaded but manifest is unchanged.",
                        uploadedRef.get());
                progress.onError(new IOException(
                        "Your session lock was overridden during upload. "
                                + "Files were uploaded but the manifest was NOT updated. "
                                + "Your changes are still saved locally. "
                                + "Coordinate with the other player and retry."));
            } else {
                commitManifest(client, driveFolderId, local);
                // First-time push: save the full scan as cache (no old cache to merge).
                WorldManifest.saveToDisk(local, worldRoot.resolve(SCAN_CACHE_FILENAME));
                DirtyRegionTracker.resetAfterPush();
                progress.onComplete();
            }
        } else {
            progress.onError(new IOException(failedRef.get() + " upload(s) failed; manifest not updated"));
        }
        return new PushResult(uploadedRef.get(), 0, failedRef.get(), bytesRef.get());
    }

    // ---- PARALLEL UPLOAD HELPER ----

    private static void parallelUpload(final DriveClient client,
                                       final String driveWorldFolderId,
                                       final List<String> toUpload,
                                       final Path worldRoot,
                                       final WorldManifest local,
                                       final SyncProgress progress,
                                       final long totalBytes,
                                       final AtomicInteger uploadedRef,
                                       final AtomicInteger failedRef,
                                       final AtomicLong bytesRef) throws IOException {
        if (toUpload.isEmpty()) return;

        // Phase 1: pre-create all parent folders sequentially.
        final Map<String, String> folderIdCache = new ConcurrentHashMap<>();
        folderIdCache.put("", driveWorldFolderId);

        final Set<String> parentPaths = new LinkedHashSet<>();
        for (final String relPath : toUpload) {
            final String[] parts = relPath.split("/");
            final StringBuilder pathSoFar = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) pathSoFar.append("/");
                pathSoFar.append(parts[i]);
                parentPaths.add(pathSoFar.toString());
            }
        }
        final List<String> sortedParents = new ArrayList<>(parentPaths);
        sortedParents.sort(Comparator.comparingInt(s -> s.split("/").length));

        for (final String parentPath : sortedParents) {
            final int lastSlash = parentPath.lastIndexOf('/');
            final String parent = lastSlash >= 0 ? parentPath.substring(0, lastSlash) : "";
            final String name = lastSlash >= 0 ? parentPath.substring(lastSlash + 1) : parentPath;
            final String parentId = folderIdCache.get(parent);
            String childId = client.findFileByName(name, parentId);
            if (childId == null) childId = client.createFolder(name, parentId);
            folderIdCache.put(parentPath, childId);
        }

        // Phase 2: parallel uploads.
        final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "WorldShare-Upload");
            t.setDaemon(true);
            return t;
        });
        final CompletionService<UploadTaskResult> completion = new ExecutorCompletionService<>(pool);

        for (final String relPath : toUpload) {
            final int lastSlash = relPath.lastIndexOf('/');
            final String parentPath = lastSlash >= 0 ? relPath.substring(0, lastSlash) : "";
            final String fileName = lastSlash >= 0 ? relPath.substring(lastSlash + 1) : relPath;
            final String parentFolderId = folderIdCache.get(parentPath);

            completion.submit(() -> {
                final long start = System.currentTimeMillis();
                try {
                    final UploadResult upRes = uploadOneToFolder(
                            client, parentFolderId, fileName, worldRoot.resolve(relPath));
                    return new UploadTaskResult(relPath, upRes,
                            System.currentTimeMillis() - start, null);
                } catch (final Throwable t) {
                    return new UploadTaskResult(relPath, null,
                            System.currentTimeMillis() - start, t);
                }
            });
        }
        pool.shutdown();

        try {
            for (int i = 0; i < toUpload.size(); i++) {
                final UploadTaskResult res = completion.take().get();
                if (res.error == null) {
                    final int done = uploadedRef.incrementAndGet();
                    final long bytes = bytesRef.addAndGet(res.upResult.size);
                    local.put(res.relPath, new WorldManifest.Entry(
                            res.upResult.sha256, res.upResult.size, Instant.now().toString()));
                    WorldShareMod.LOGGER.info(
                            "push: uploaded {} | {} bytes | {}ms",
                            res.relPath, res.upResult.size, res.elapsedMs);
                    progress.onFileProgress(done + failedRef.get(), toUpload.size(),
                            bytes, totalBytes, res.relPath);
                } else {
                    final int failed = failedRef.incrementAndGet();
                    WorldShareMod.LOGGER.error(
                            "push: failed {}: {}", res.relPath, res.error.getMessage());
                    progress.onFileProgress(uploadedRef.get() + failed, toUpload.size(),
                            bytesRef.get(), totalBytes, res.relPath);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("Upload interrupted", e);
        } catch (final ExecutionException e) {
            throw new IOException("Unexpected upload failure", e.getCause());
        }
    }

    // ---- LEVEL.DAT PLAYER STRIP ----

    private static void stripPlayerFromLevelDat(final Path worldRoot) {
        final Path levelDat = worldRoot.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) return;
        try {
            final CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            final CompoundTag data = root.getCompound("Data");
            if (!data.contains("Player")) return;
            data.remove("Player");
            NbtIo.writeCompressed(root, levelDat);
            WorldShareMod.LOGGER.info(
                    "pull: stripped Player tag from level.dat in '{}'",
                    worldRoot.getFileName());
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.warn(
                    "pull: failed to strip Player from level.dat (non-fatal): {}",
                    t.getMessage());
        }
    }

    // ---- SCAN CACHE HELPERS ----

    /**
     * Saves the scan cache after a successful incremental push.
     * Merges old cache (mtime for non-dirty .mca files not re-scanned) with fresh
     * scan entries (mtime for everything scanned). Fresh entries win on conflict.
     * For first-time push, oldCache is null and scannedLocal is saved directly.
     */
    private static void saveScanCache(final WorldManifest scannedLocal,
                                      final WorldManifest oldCache,
                                      final Path worldRoot) {
        final WorldManifest cacheToSave;
        if (oldCache != null && !oldCache.files().isEmpty()) {
            cacheToSave = new WorldManifest();
            cacheToSave.files.putAll(oldCache.files());
            cacheToSave.files.putAll(scannedLocal.files());
        } else {
            cacheToSave = scannedLocal;
        }
        WorldManifest.saveToDisk(cacheToSave, worldRoot.resolve(SCAN_CACHE_FILENAME));
        WorldShareMod.LOGGER.debug(
                "SyncEngine: saved scan cache ({} entries)", cacheToSave.size());
    }

    // ---- DRIVE / FILE HELPERS ----

    private static WorldManifest readDriveManifest(final String driveFolderId) throws IOException {
        final DriveClient client = CloudModule.driveClient();
        final String manifestId = client.findFileByName(MANIFEST_FILENAME, driveFolderId);
        if (manifestId == null) return null;
        try {
            return WorldManifest.fromJson(client.readText(manifestId));
        } catch (final JsonSyntaxException e) {
            throw new IOException("manifest.json on Drive is malformed: " + e.getMessage(), e);
        }
    }

    private static String ensureWorldSubfolder(final String parentFolderId,
                                               final DriveClient client,
                                               final boolean create) throws IOException {
        final String existing = client.findFileByName(WORLD_SUBFOLDER, parentFolderId);
        if (existing != null) return existing;
        if (!create) return null;
        return client.createFolder(WORLD_SUBFOLDER, parentFolderId);
    }

    private static UploadResult uploadOneToFolder(final DriveClient client,
                                                  final String parentFolderId,
                                                  final String fileName,
                                                  final Path local) throws IOException {
        if (!Files.isRegularFile(local)) {
            throw new IOException("Local file missing: " + local);
        }
        final Path snapshot = Files.createTempFile("worldshare-upload-", ".snap");
        try {
            Files.copy(local, snapshot, StandardCopyOption.REPLACE_EXISTING);
            final long size = Files.size(snapshot);
            final String sha256 = SHA256Util.hashFile(snapshot);
            final String existingId = client.findFileByName(fileName, parentFolderId);
            if (existingId != null) client.updateFile(existingId, snapshot);
            else client.uploadFile(snapshot, fileName, parentFolderId);
            return new UploadResult(sha256, size);
        } finally {
            try { Files.deleteIfExists(snapshot); } catch (final IOException ignored) {}
        }
    }

    private static void downloadOne(final DriveClient client,
                                    final String driveWorldFolderId,
                                    final String relPath,
                                    final Path worldRoot,
                                    final WorldManifest.Entry expected) throws IOException {
        final String[] parts = relPath.split("/");
        String currentFolder = driveWorldFolderId;
        for (int i = 0; i < parts.length - 1; i++) {
            final String childId = client.findFileByName(parts[i], currentFolder);
            if (childId == null) throw new IOException("Drive folder structure missing: " + relPath);
            currentFolder = childId;
        }
        final String fileName = parts[parts.length - 1];
        final String fileId = client.findFileByName(fileName, currentFolder);
        if (fileId == null) throw new IOException("Drive file missing: " + relPath);

        final Path destination = worldRoot.resolve(relPath);
        Files.createDirectories(destination.getParent());
        final Path tmp = destination.resolveSibling(destination.getFileName() + ".worldshare-tmp");
        try {
            client.downloadFile(fileId, tmp);
            try {
                Files.move(tmp, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException ame) {
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            try { Files.deleteIfExists(tmp); } catch (final IOException ignored) {}
            throw e;
        }
    }

    private static void commitManifest(final DriveClient client,
                                       final String driveFolderId,
                                       final WorldManifest manifest) throws IOException {
        manifest.generatedAt = Instant.now().toString();
        if (manifest.generatedByMachineId == null) {
            manifest.generatedByMachineId = MachineId.get();
        }
        final String json = manifest.toJson();
        final String existingId = client.findFileByName(MANIFEST_FILENAME, driveFolderId);
        client.writeText(existingId, MANIFEST_FILENAME, driveFolderId, json,
                DriveClient.MIME_TYPE_JSON);
        final String stalePending = client.findFileByName(MANIFEST_PENDING_FILENAME, driveFolderId);
        if (stalePending != null) {
            try { client.deleteFile(stalePending); } catch (final IOException ignored) {}
        }
        WorldShareMod.LOGGER.info("commitManifest: wrote {} entries", manifest.size());
    }

    // ---- VALUE TYPES ----

    private static final class UploadResult {
        final String sha256;
        final long size;
        UploadResult(String s, long sz) { this.sha256 = s; this.size = sz; }
    }

    private static final class UploadTaskResult {
        final String relPath;
        final UploadResult upResult;
        final long elapsedMs;
        final Throwable error;
        UploadTaskResult(String r, UploadResult u, long ms, Throwable e) {
            this.relPath = r; this.upResult = u; this.elapsedMs = ms; this.error = e;
        }
    }

    /** M8: result of a parallel download worker. Mirrors UploadTaskResult. */
    private static final class DownloadTaskResult {
        final String relPath;
        final boolean success;
        final long sizeDownloaded;
        final IOException error;
        DownloadTaskResult(final String relPath, final boolean success,
                           final long sizeDownloaded, final IOException error) {
            this.relPath = relPath;
            this.success = success;
            this.sizeDownloaded = sizeDownloaded;
            this.error = error;
        }
    }

    public static final class PushResult {
        public final int uploaded;
        public final int skippedSomeoneElsesEdit;
        public final int failed;
        public final long bytes;
        PushResult(int u, int s, int f, long b) {
            this.uploaded = u; this.skippedSomeoneElsesEdit = s;
            this.failed = f; this.bytes = b;
        }
    }

    public static final class PullResult {
        public final int downloaded;
        public final int failed;
        public final long bytes;
        PullResult(int d, int f, long b) { this.downloaded = d; this.failed = f; this.bytes = b; }
    }

    /**
     * Detect HTTP 416 "Requested Range Not Satisfiable" errors. Walks the
     * exception cause chain checking both the exception type and the message.
     */
    private static boolean is416(final Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof com.google.api.client.http.HttpResponseException hre) {
                if (hre.getStatusCode() == 416) return true;
            }
            final String msg = cur.getMessage();
            if (msg != null && msg.contains("Range Not Satisfiable")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}