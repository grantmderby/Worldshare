package com.worldshare.mod.sync;

import com.google.gson.JsonSyntaxException;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.util.MachineId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import com.worldshare.mod.util.SHA256Util;

/**
 * Pushes a local world to Drive, pulls a Drive world to local, and computes
 * dry-run status reports.
 *
 * <p>Key design points:
 * <ul>
 *   <li><b>Atomic commits.</b> A push uploads files first, then writes a
 *       new manifest as {@code manifest_pending.json}, then renames it to
 *       {@code manifest.json}. If anything fails before the rename, the old
 *       manifest is unchanged — a future pull still sees a consistent world.</li>
 *   <li><b>Session-baseline protection.</b> Before each push we record what
 *       Drive looked like when we started the session. We only upload files
 *       where (a) our local copy differs from Drive AND (b) we actually
 *       modified the file this session (its local hash differs from baseline).
 *       Files that match the baseline but differ from Drive mean someone
 *       else updated them while we were playing — we don't clobber those.</li>
 *   <li><b>World/ subfolder layout.</b> All world files on Drive live under
 *       a single {@code world/} subfolder of the configured root, so the
 *       session.lock and manifest.json sit alongside but separate.</li>
 * </ul>
 *
 * <p>All methods on this class block on network. Dispatch via
 * {@link CloudModule#executor()}.
 */
public final class SyncEngine {

    /** File name of the canonical manifest on Drive. */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Temporary name during atomic commit. */
    public static final String MANIFEST_PENDING_FILENAME = "manifest_pending.json";

    /** Name of the Drive subfolder containing the actual world files. */
    public static final String WORLD_SUBFOLDER = "world";

    private SyncEngine() {
        // utility class
    }

    // -------------------------------------------------------------------
    // STATUS — dry run, no I/O changes
    // -------------------------------------------------------------------

    /**
     * Compare local world to Drive manifest without making any changes.
     *
     * @param worldRoot     local world folder (e.g. {@code .minecraft/saves/MyWorld})
     * @param driveFolderId Drive folder ID (parent of {@code manifest.json})
     * @param ownUuid       local player UUID for per-UUID file filtering
     * @return diff between local and Drive
     */
    public static SyncDiff status(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid) throws IOException {
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final WorldManifest drive = readDriveManifest(driveFolderId);
        return SyncDiff.compute(local, drive);
    }

    // -------------------------------------------------------------------
    // PULL — Drive → local
    // -------------------------------------------------------------------

    /**
     * Download Drive's version of every changed/missing file into the local
     * world folder. Local-only files are NOT touched (they may be new local
     * work the user hasn't pushed yet, or stale leftovers).
     *
     * @return summary of what was actually downloaded
     */
    public static PullResult pull(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid) throws IOException {
        Files.createDirectories(worldRoot);

        final DriveClient client = CloudModule.driveClient();
        final WorldManifest driveManifest = readDriveManifest(driveFolderId);

        if (driveManifest == null) {
            WorldShareMod.LOGGER.info("pull: no Drive manifest yet (first sync); nothing to pull");
            return new PullResult(0, 0, 0L);
        }

        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final SyncDiff diff = SyncDiff.compute(local, driveManifest);

        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, false);
        if (driveWorldFolderId == null) {
            WorldShareMod.LOGGER.warn(
                    "pull: Drive manifest exists but no world/ subfolder found; nothing to pull");
            return new PullResult(0, 0, 0L);
        }

        int downloaded = 0;
        int failed = 0;
        long bytes = 0L;

        // Download files that are missing or different.
        final java.util.List<String> toDownload = new java.util.ArrayList<>();
        toDownload.addAll(diff.onlyOnDrive);
        toDownload.addAll(diff.different);

        for (final String relPath : toDownload) {
            final WorldManifest.Entry expected = driveManifest.get(relPath);
            try {
                downloadOne(client, driveWorldFolderId, relPath, worldRoot, expected);
                downloaded++;
                bytes += (expected == null ? 0L : expected.size);
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error(
                        "pull: failed to download {} (will be retried on next pull): {}",
                        relPath, e.getMessage());
                failed++;
            }
        }

        WorldShareMod.LOGGER.info(
                "pull complete: {} downloaded, {} failed, {} bytes ({} MB), {} unchanged",
                downloaded, failed, bytes, bytes / (1024 * 1024), diff.identical.size());
        return new PullResult(downloaded, failed, bytes);
    }

    // -------------------------------------------------------------------
    // PUSH — local → Drive
    // -------------------------------------------------------------------

    /**
     * Upload local files that have changed since the session started, with
     * stale-pull protection: files we did NOT modify are never uploaded,
     * even if they differ from Drive.
     *
     * @param baseline Manifest captured at session start (before any local
     *                 edits). Used to identify which files were actually
     *                 modified during this session. Pass null for "I don't
     *                 know what the baseline was, push everything different"
     *                 — only do that for the very first push of a new world.
     */
    public static PushResult push(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid,
                                  final WorldManifest baseline) throws IOException {
        return push(worldRoot, driveFolderId, ownUuid, baseline, SyncProgress.NOOP);
    }

    /**
     * Same as {@link #push(Path, String, UUID, WorldManifest)} but with a
     * {@link SyncProgress} callback for UI updates. The progress callback is
     * always called from the {@link CloudModule#executor()} thread, not the
     * Minecraft main thread.
     */
    public static PushResult push(final Path worldRoot,
                                  final String driveFolderId,
                                  final UUID ownUuid,
                                  final WorldManifest baseline,
                                  final SyncProgress progress) throws IOException {
        final DriveClient client = CloudModule.driveClient();

        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        local.generatedByMachineId = MachineId.get();

        final WorldManifest drive = readDriveManifest(driveFolderId);
        // No Drive manifest = first sync. Push everything as-is.
        if (drive == null) {
            return pushFirstTime(client, worldRoot, driveFolderId, local, progress);
        }

        final SyncDiff diff = SyncDiff.compute(local, drive);

        // Determine which files we actually want to upload.
        final java.util.List<String> toUpload = new java.util.ArrayList<>();
        int skippedStale = 0;

        for (final String relPath : diff.different) {
            // Stale-pull protection: only upload files we changed this session.
            // Compare local hash to baseline hash. If they match, we didn't
            // modify this file - someone else did, since we pulled.
            if (baseline != null) {
                final WorldManifest.Entry baseEntry = baseline.get(relPath);
                final WorldManifest.Entry localEntry = local.get(relPath);
                if (baseEntry != null
                        && baseEntry.sha256 != null
                        && baseEntry.sha256.equals(localEntry.sha256)) {
                    WorldShareMod.LOGGER.info(
                            "push: skipping {} (we didn't modify it; updated externally)",
                            relPath);
                    skippedStale++;
                    // CRITICAL: replace the local manifest entry with Drive's entry.
                    // Otherwise the manifest we commit would claim hash X for a file
                    // that's still hash Y on Drive, and next pull would re-download
                    // and overwrite the someone-else's edit we're trying to preserve.
                    local.put(relPath, drive.get(relPath));
                    continue;
                }
            }
            toUpload.add(relPath);
        }
        // Also push files that exist locally but not on Drive.
        toUpload.addAll(diff.onlyLocal);

        // Files on Drive but not local: someone else's contribution, OR we deleted
        // them. We CANNOT distinguish, so default to "preserve" - keep them in the
        // new manifest so brother's playerdata isn't accidentally erased when we push.
        // The new manifest we write will include those entries unchanged.
        for (final String relPath : diff.onlyOnDrive) {
            local.put(relPath, drive.get(relPath));
            // (No upload — we don't have the file locally to upload.)
        }

        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, true);

        // Pre-compute totals for progress reporting.
        long totalBytes = 0L;
        for (final String relPath : toUpload) {
            final WorldManifest.Entry entry = local.get(relPath);
            if (entry != null) totalBytes += entry.size;
        }
        progress.onStart(toUpload.size(), totalBytes);

        int uploaded = 0;
        int failed = 0;
        long bytes = 0L;

        for (final String relPath : toUpload) {
            try {
                final UploadResult upRes = uploadOne(client, driveWorldFolderId, relPath, worldRoot);
                uploaded++;
                bytes += upRes.size;
                // CRITICAL: replace the manifest entry with what we ACTUALLY uploaded.
                // The hash from WorldFileScanner.scan() may have been a different
                // mid-write state if Minecraft was modifying the file mid-scan.
                // The hash from uploadOne() is guaranteed to match the bytes on Drive.
                final WorldManifest.Entry actual = new WorldManifest.Entry(
                        upRes.sha256, upRes.size, java.time.Instant.now().toString());
                local.put(relPath, actual);
                progress.onFileProgress(uploaded + failed, toUpload.size(), bytes, totalBytes, relPath);
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error(
                        "push: failed to upload {}: {}", relPath, e.getMessage());
                failed++;
                progress.onFileProgress(uploaded + failed, toUpload.size(), bytes, totalBytes, relPath);
            }
        }

        // Atomic manifest commit — only if no upload failed (otherwise the manifest
        // would claim files exist on Drive that don't).
        if (failed == 0) {
            commitManifest(client, driveFolderId, local);
            progress.onComplete();
        } else {
            WorldShareMod.LOGGER.warn(
                    "push: {} upload(s) failed; manifest NOT updated (next push will retry)",
                    failed);
            progress.onError(new IOException(failed + " file upload(s) failed; see log for details"));
        }

        WorldShareMod.LOGGER.info(
                "push complete: {} uploaded, {} skipped (someone else's), {} failed, {} bytes",
                uploaded, skippedStale, failed, bytes);
        return new PushResult(uploaded, skippedStale, failed, bytes);
    }

    /**
     * First-ever push: no existing Drive manifest, no diff to compute, no
     * stale protection needed. Just upload everything in the local manifest.
     */
    private static PushResult pushFirstTime(final DriveClient client,
                                            final Path worldRoot,
                                            final String driveFolderId,
                                            final WorldManifest local,
                                            final SyncProgress progress) throws IOException {
        WorldShareMod.LOGGER.info(
                "push (first time): no existing Drive manifest, uploading all {} files",
                local.size());

        final String driveWorldFolderId = ensureWorldSubfolder(driveFolderId, client, true);

        // Pre-compute totals.
        final java.util.List<String> toUpload = new java.util.ArrayList<>(local.files().keySet());
        long totalBytes = 0L;
        for (final String relPath : toUpload) {
            final WorldManifest.Entry entry = local.get(relPath);
            if (entry != null) totalBytes += entry.size;
        }
        progress.onStart(toUpload.size(), totalBytes);

        int uploaded = 0;
        int failed = 0;
        long bytes = 0L;

        for (final String relPath : toUpload) {
            try {
                final UploadResult upRes = uploadOne(client, driveWorldFolderId, relPath, worldRoot);
                uploaded++;
                bytes += upRes.size;
                // Replace the entry with the actual uploaded bytes' hash. See comment in push().
                local.put(relPath, new WorldManifest.Entry(
                        upRes.sha256, upRes.size, java.time.Instant.now().toString()));
                progress.onFileProgress(uploaded + failed, toUpload.size(), bytes, totalBytes, relPath);
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error(
                        "push (first): failed to upload {}: {}", relPath, e.getMessage());
                failed++;
                progress.onFileProgress(uploaded + failed, toUpload.size(), bytes, totalBytes, relPath);
            }
        }

        if (failed == 0) {
            commitManifest(client, driveFolderId, local);
            progress.onComplete();
        } else {
            progress.onError(new IOException(failed + " file upload(s) failed; see log for details"));
        }
        return new PushResult(uploaded, 0, failed, bytes);
    }

    // -------------------------------------------------------------------
    // Drive helpers
    // -------------------------------------------------------------------

    /**
     * @return the parsed manifest from Drive, or null if no manifest.json exists yet
     */
    private static WorldManifest readDriveManifest(final String driveFolderId)
            throws IOException {
        final DriveClient client = CloudModule.driveClient();
        final String manifestId = client.findFileByName(MANIFEST_FILENAME, driveFolderId);
        if (manifestId == null) return null;
        try {
            return WorldManifest.fromJson(client.readText(manifestId));
        } catch (final JsonSyntaxException e) {
            throw new IOException("manifest.json on Drive is malformed: " + e.getMessage(), e);
        }
    }

    /**
     * Find or create the {@code world/} subfolder inside the configured Drive folder.
     *
     * @param create if true, create the subfolder when missing; if false, return null
     */
    private static String ensureWorldSubfolder(final String parentFolderId,
                                               final DriveClient client,
                                               final boolean create) throws IOException {
        final String existing = client.findFileByName(WORLD_SUBFOLDER, parentFolderId);
        if (existing != null) return existing;
        if (!create) return null;
        return client.createFolder(WORLD_SUBFOLDER, parentFolderId);
    }

    /**
     * Upload a single file.
     *
     * <p>This method takes a snapshot of the file BEFORE hashing or uploading, so
     * even if Minecraft writes to the original mid-operation, the bytes we hash
     * and the bytes we upload are guaranteed identical. Returns the SHA-256 of
     * what we actually uploaded — callers should use this to update the manifest
     * entry, replacing whatever {@link WorldFileScanner} hashed earlier (which
     * may have been a different mid-write state).
     *
     * <p>Path components are mirrored as Drive subfolders. If a file with the
     * same name+path already exists, it's updated in place.
     *
     * @return the SHA-256 hash of the bytes we uploaded, and the size we uploaded
     */
    private static UploadResult uploadOne(final DriveClient client,
                                          final String driveWorldFolderId,
                                          final String relPath,
                                          final Path worldRoot) throws IOException {
        final Path local = worldRoot.resolve(relPath);
        if (!Files.isRegularFile(local)) {
            throw new IOException("Local file missing: " + local);
        }

        // Snapshot the file to a temp path. After this point, even if Minecraft
        // re-writes the original, our snapshot is stable. Files.copy is OS-level
        // atomic-ish; we may copy a half-written state, but the same half-written
        // state is what we upload AND hash, so the manifest stays consistent.
        final Path snapshot = Files.createTempFile("worldshare-upload-", ".snap");
        try {
            Files.copy(local, snapshot, StandardCopyOption.REPLACE_EXISTING);

            final long size = Files.size(snapshot);
            final String sha256 = SHA256Util.hashFile(snapshot);

            // Walk Drive folder tree, creating subfolders as needed.
            final String[] parts = relPath.split("/");
            String currentFolder = driveWorldFolderId;
            for (int i = 0; i < parts.length - 1; i++) {
                final String segment = parts[i];
                String child = client.findFileByName(segment, currentFolder);
                if (child == null) {
                    child = client.createFolder(segment, currentFolder);
                }
                currentFolder = child;
            }
            final String fileName = parts[parts.length - 1];

            // Upload the SNAPSHOT, not the original.
            final String existingId = client.findFileByName(fileName, currentFolder);
            if (existingId != null) {
                client.updateFile(existingId, snapshot);
            } else {
                client.uploadFile(snapshot, fileName, currentFolder);
            }
            return new UploadResult(sha256, size);
        } finally {
            try { Files.deleteIfExists(snapshot); } catch (final IOException ignored) {}
        }
    }

    /** Result of a single uploadOne(): tracks bytes-as-uploaded so the caller can update the manifest. */
    private static final class UploadResult {
        final String sha256;
        final long size;
        UploadResult(final String sha256, final long size) {
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /**
     * Download a single file. Mirrors path components into local subfolders.
     */
    private static void downloadOne(final DriveClient client,
                                    final String driveWorldFolderId,
                                    final String relPath,
                                    final Path worldRoot,
                                    final WorldManifest.Entry expected) throws IOException {
        final String[] parts = relPath.split("/");
        String currentFolder = driveWorldFolderId;
        for (int i = 0; i < parts.length - 1; i++) {
            final String childId = client.findFileByName(parts[i], currentFolder);
            if (childId == null) {
                throw new IOException(
                        "Drive folder structure missing: " + relPath
                        + " (segment '" + parts[i] + "' not found)");
            }
            currentFolder = childId;
        }
        final String fileName = parts[parts.length - 1];
        final String fileId = client.findFileByName(fileName, currentFolder);
        if (fileId == null) {
            throw new IOException("Drive file missing for relPath: " + relPath);
        }

        final Path destination = worldRoot.resolve(relPath);
        Files.createDirectories(destination.getParent());

        // Download to a temp file, then move into place. We prefer ATOMIC_MOVE
        // (so a crash during the move doesn't leave a partial file at the dest)
        // but Windows doesn't support atomic move when REPLACE_EXISTING is needed
        // and the target exists. Fall back to a regular move in that case.
        final Path tmp = destination.resolveSibling(destination.getFileName() + ".worldshare-tmp");
        try {
            client.downloadFile(fileId, tmp);
            try {
                Files.move(tmp, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException ame) {
                // Windows path: do it in two steps. Slight risk of a half-second
                // window where the file is missing if we crash mid-move, but the
                // download will be retried on the next pull.
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            try { Files.deleteIfExists(tmp); } catch (final IOException ignored) {}
            throw e;
        }
    }

    /**
     * Atomically replace {@code manifest.json} on Drive.
     *
     * <p>This is called only AFTER all file uploads succeeded. If the world
     * had any state on Drive before, that state remains valid (and consistent
     * with the OLD manifest) until this method's single Drive API call returns
     * — so a crash here leaves the world in a consistent state matching either
     * the old manifest or the new one, never an in-between.
     *
     * <p>Note: we used to use a {@code manifest_pending → rename} two-step
     * pattern, but Drive's "rename" is not an atomic move (it's a property
     * update that can leave duplicate names) so the pattern offered no real
     * atomicity benefit while adding failure modes. A single update call is
     * cleaner and equally safe.
     */
    private static void commitManifest(final DriveClient client,
                                       final String driveFolderId,
                                       final WorldManifest manifest) throws IOException {
        manifest.generatedAt = Instant.now().toString();
        if (manifest.generatedByMachineId == null) {
            manifest.generatedByMachineId = MachineId.get();
        }
        final String json = manifest.toJson();

        final String existingId = client.findFileByName(MANIFEST_FILENAME, driveFolderId);
        client.writeText(
                existingId,                  // null = create, else update
                MANIFEST_FILENAME,
                driveFolderId,
                json,
                DriveClient.MIME_TYPE_JSON);

        // Best-effort cleanup: if a stale manifest_pending.json was left around
        // by an older version of this code (or a failed earlier run), delete it.
        final String stalePendingId = client.findFileByName(
                MANIFEST_PENDING_FILENAME, driveFolderId);
        if (stalePendingId != null) {
            try {
                client.deleteFile(stalePendingId);
            } catch (final IOException ignore) {
                // Not fatal - just leftover file in Drive.
            }
        }

        WorldShareMod.LOGGER.info("commitManifest: wrote {} entries to manifest.json",
                manifest.size());
    }

    // -------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------

    public static final class PushResult {
        public final int uploaded;
        public final int skippedSomeoneElsesEdit;
        public final int failed;
        public final long bytes;

        PushResult(final int uploaded, final int skipped, final int failed, final long bytes) {
            this.uploaded = uploaded;
            this.skippedSomeoneElsesEdit = skipped;
            this.failed = failed;
            this.bytes = bytes;
        }
    }

    public static final class PullResult {
        public final int downloaded;
        public final int failed;
        public final long bytes;

        PullResult(final int downloaded, final int failed, final long bytes) {
            this.downloaded = downloaded;
            this.failed = failed;
            this.bytes = bytes;
        }
    }
}
