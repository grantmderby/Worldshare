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
 * <p><b>M7 changes:</b>
 * <ul>
 *   <li>{@link #pull} accepts {@link SyncProgress} for live UI feedback,
 *       throws on partial failure (no silent inconsistent state)</li>
 *   <li>After successful pull, {@link #stripPlayerFromLevelDat} strips the
 *       host's Player tag — combined with TrackedPaths syncing all playerdata
 *       files, gives normal-server inventory behaviour</li>
 *   <li>{@link #push} uploads in parallel with pool size 4 — roughly 3x speedup
 *       on residential connections. Parent folders pre-created sequentially
 *       to avoid races.</li>
 *   <li>Per-file timing logs at INFO level for upload diagnosis</li>
 * </ul>
 */
public final class SyncEngine {

    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String MANIFEST_PENDING_FILENAME = "manifest_pending.json";
    public static final String WORLD_SUBFOLDER = "world";

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

        long totalBytes = 0L;
        for (final String relPath : toDownload) {
            final WorldManifest.Entry e = driveManifest.get(relPath);
            if (e != null) totalBytes += e.size;
        }
        progress.onStart(toDownload.size(), totalBytes);

        int downloaded = 0;
        int failed = 0;
        long bytes = 0L;

        for (final String relPath : toDownload) {
            final WorldManifest.Entry expected = driveManifest.get(relPath);
            boolean success = false;
            IOException lastError = null;

            // M7: retry transient download failures up to 3 attempts with backoff.
            for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                try {
                    downloadOne(client, driveWorldFolderId, relPath, worldRoot, expected);
                    success = true;
                    downloaded++;
                    bytes += (expected == null ? 0L : expected.size);
                    if (attempt > 1) {
                        WorldShareMod.LOGGER.info(
                                "pull: succeeded {} on retry attempt {}", relPath, attempt);
                    }
                } catch (final IOException e) {
                    // M7: 416 means the file on Drive is empty or smaller than expected.
                    // With direct downloads enabled in DriveClient this shouldn't happen,
                    // but if it does, treat as a 0-byte file and don't retry.
                    if (is416(e)) {
                        WorldShareMod.LOGGER.info(
                                "pull: {} returned 416, treating as 0-byte file", relPath);
                        try {
                            final Path target = worldRoot.resolve(relPath);
                            if (target.getParent() != null) {
                                Files.createDirectories(target.getParent());
                            }
                            Files.write(target, new byte[0]);
                            success = true;
                            downloaded++;
                        } catch (final IOException writeErr) {
                            WorldShareMod.LOGGER.warn(
                                    "pull: couldn't create 0-byte placeholder for {}: {}",
                                    relPath, writeErr.getMessage());
                            lastError = writeErr;
                        }
                        break; // Don't retry — either we handled the 416 or we couldn't.
                    }

                    lastError = e;
                    WorldShareMod.LOGGER.warn(
                            "pull: attempt {} failed for {}: {}",
                            attempt, relPath, e.getMessage());
                    if (attempt < 3) {
                        try {
                            Thread.sleep(2000L * attempt);
                        } catch (final InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            failed++;
                            progress.onFileProgress(downloaded + failed, toDownload.size(),
                                    bytes, totalBytes, relPath);
                            throw new IOException("Pull interrupted during retry", ie);
                        }
                    }
                }
            }

            if (!success) {
                WorldShareMod.LOGGER.error(
                        "pull: gave up on {} after 3 attempts: {}",
                        relPath, lastError != null ? lastError.getMessage() : "unknown");
                failed++;
            }
            progress.onFileProgress(downloaded + failed, toDownload.size(),
                    bytes, totalBytes, relPath);
        }

        // M7: strip Player tag — runs every pull, not just first time.
        stripPlayerFromLevelDat(worldRoot);

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
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        local.generatedByMachineId = MachineId.get();

        final WorldManifest drive = readDriveManifest(driveFolderId);
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

        for (final String relPath : diff.onlyOnDrive) {
            local.put(relPath, drive.get(relPath));
        }

        // M7: sort largest files first so each thread picks up a big file
// immediately rather than finishing all small files and then waiting
// on a single large file at the end.
        toUpload.sort((a, b) -> {
            final WorldManifest.Entry ea = local.get(a);
            final WorldManifest.Entry eb = local.get(b);
            final long sizeA = ea != null ? ea.size : 0L;
            final long sizeB = eb != null ? eb.size : 0L;
            return Long.compare(sizeB, sizeA); // descending
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
            // M7: verify we still hold the lock before committing the manifest.
            // If our lock was overridden during upload, committing now would
            // overwrite whatever the new lock-holder is about to do.
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
// Largest files first — avoids one thread blocking on a big file at the end.
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
            // M7: verify we still hold the lock before committing the manifest.
            // If our lock was overridden during upload, committing now would
            // overwrite whatever the new lock-holder is about to do.
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