package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.ControlFile;
import com.worldshare.mod.cloud.ControlFileClient;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.util.MachineId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushes and pulls a world to and from Drive, one bucket archive at a time.
 *
 * <p><b>What changed and why.</b> This engine used to mirror the world folder onto
 * Drive file-for-file: walk the save, upload each changed {@code .mca} as its own
 * Drive object, keep a {@code manifest.json} beside them. That design is
 * incompatible with the {@code drive.file} OAuth scope the mod now uses, because a
 * file the mod creates on one machine is invisible to the other player until they
 * personally select it in Google's Picker - and new region files appear constantly
 * as players explore. The remote side is therefore a fixed set of files, chosen
 * once at setup: a control document plus N bucket archives (see
 * {@link BucketLayout} for how files are assigned to buckets, and
 * {@code docs/CLOUD_BACKEND_DECISION.md} for the testing that ruled out the
 * alternatives).
 *
 * <p><b>What that costs.</b> Sync granularity is now the bucket, not the file. A
 * single changed chunk forces its whole bucket archive back across the network.
 * The per-file scan machinery below - the mtime cache, the dirty-region tracker,
 * the manifest diff - all survives and still matters, because it's what decides
 * <em>which</em> buckets are dirty. It just no longer decides what gets uploaded
 * byte-for-byte.
 *
 * <p><b>Threading:</b> every method here blocks on network and must not be called
 * on the Minecraft main thread.
 */
public final class SyncEngine {

    /**
     * Local scan cache filename - stored in the world folder, excluded from sync.
     * Persists SHA-256/size/mtime for files scanned on the last successful push so
     * subsequent scans can skip SHA-256 for unchanged files.
     */
    static final String SCAN_CACHE_FILENAME = "worldshare-scan-cache.json";

    /**
     * How many bucket archives to transfer at once.
     *
     * <p>Lower than the old per-file pool of 4. Each unit of work is now a whole
     * archive rather than one region file, so the same concurrency would mean
     * several hundred megabytes in flight at once on a home connection, and each
     * worker also needs scratch disk for its archive.
     */
    private static final int TRANSFER_THREADS = 2;

    private SyncEngine() {}

    // ---- STATUS ----

    /**
     * Compare the local world against what the control file says is on Drive.
     * Reads no bucket archives, so it's cheap enough for a status command.
     */
    public static SyncDiff status(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid) throws IOException {
        final ControlFile control = ControlFileClient.read(requireComplete(remote).controlFileId);
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final WorldManifest driveManifest =
                (control == null) ? new WorldManifest() : control.manifestOrEmpty();
        return SyncDiff.compute(local, driveManifest);
    }

    // ---- PULL ----

    public static PullResult pull(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid) throws IOException {
        return pull(worldRoot, remote, ownUuid, SyncProgress.NOOP);
    }

    public static PullResult pull(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid,
                                  final SyncProgress progress) throws IOException {
        Files.createDirectories(worldRoot);
        requireComplete(remote);

        final ControlFile control = ControlFileClient.read(remote.controlFileId);
        if (control == null) {
            WorldShareMod.LOGGER.info("pull: control file is empty (first sync); nothing to pull");
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PullResult(0, 0, 0L);
        }
        final BucketLayout layout = requireMatchingLayout(control, remote);

        // Full scan - no dirty filter, because pull needs every local hash to know
        // what it can safely leave alone.
        final WorldManifest local = WorldFileScanner.scan(worldRoot, ownUuid);
        final WorldManifest driveManifest = control.manifestOrEmpty();
        final SyncDiff diff = SyncDiff.compute(local, driveManifest);

        final Set<String> wantedPaths = new LinkedHashSet<>();
        wantedPaths.addAll(diff.onlyOnDrive);
        wantedPaths.addAll(diff.different);

        if (wantedPaths.isEmpty()) {
            WorldShareMod.LOGGER.info("pull: already up to date ({} files match)",
                    diff.identical.size());
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PullResult(0, 0, 0L);
        }

        // Group the files we need by the bucket that holds them, so each archive is
        // fetched exactly once no matter how many wanted files live inside it.
        final Map<Integer, Set<String>> pathsByBucket =
                groupByBucket(layout, wantedPaths);

        long totalBytes = 0L;
        for (final String relPath : wantedPaths) {
            final WorldManifest.Entry e = driveManifest.get(relPath);
            if (e != null) totalBytes += e.size;
        }

        WorldShareMod.LOGGER.info("pull: {} file(s) across {} bucket(s), {} MB",
                wantedPaths.size(), pathsByBucket.size(), totalBytes / (1024 * 1024));
        progress.onStart(wantedPaths.size(), totalBytes);

        final TransferResult transfer = transferBuckets(
                pathsByBucket, remote, worldRoot, driveManifest, false, progress,
                wantedPaths.size(), totalBytes);

        final int downloaded = transfer.filesOk;
        final int failed = transfer.bucketsFailed;
        final long bytes = transfer.bytesMoved;

        stripPlayerFromLevelDat(worldRoot);

        // Local now matches Drive - invalidate the scan cache and reset dirty
        // tracking. Downloaded files carry fresh mtimes that would otherwise look
        // like cache hits on the next scan.
        try {
            Files.deleteIfExists(worldRoot.resolve(SCAN_CACHE_FILENAME));
        } catch (final IOException ignored) {}
        DirtyRegionTracker.resetAfterPull();

        if (failed > 0) {
            progress.onError(new IOException(failed + " bucket(s) failed to download"));
            WorldShareMod.LOGGER.error("pull: {} files restored, {} bucket(s) FAILED, {} bytes",
                    downloaded, failed, bytes);
            throw new IOException(failed + " bucket(s) failed to download. Retry pull.");
        }

        progress.onComplete();
        WorldShareMod.LOGGER.info("pull complete: {} file(s) restored, {} bytes ({} MB)",
                downloaded, bytes, bytes / (1024 * 1024));
        return new PullResult(downloaded, failed, bytes);
    }

    // ---- PUSH ----

    public static PushResult push(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid,
                                  final WorldManifest baseline) throws IOException {
        return push(worldRoot, remote, ownUuid, baseline, SyncProgress.NOOP);
    }

    public static PushResult push(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid,
                                  final WorldManifest baseline,
                                  final SyncProgress progress) throws IOException {
        requireComplete(remote);

        final ControlFile control =
                ControlFileClient.readOrInitial(remote.controlFileId, remote.bucketCount);
        final BucketLayout layout = requireMatchingLayout(control, remote);
        final WorldManifest driveManifest = control.manifestOrEmpty();
        final boolean firstPush = driveManifest.files().isEmpty();

        final WorldManifest local;
        final WorldManifest scanCache;

        if (firstPush) {
            // Nothing on Drive yet: scan everything. Skipping any file here would
            // leave a bucket permanently missing content nobody re-dirties.
            WorldShareMod.LOGGER.info("push: control file has no manifest - first-time push, full scan");
            local = WorldFileScanner.scan(worldRoot, ownUuid);
            scanCache = null;
        } else {
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

        final SyncDiff diff = SyncDiff.compute(local, driveManifest);

        // Paths whose bytes actually need to reach Drive.
        final Set<String> changedPaths = new LinkedHashSet<>();
        int skippedStale = 0;

        for (final String relPath : diff.different) {
            if (baseline != null) {
                // The file differs from Drive, but our copy is byte-identical to what
                // we started the session with - so the difference is the *other*
                // player's newer work, not ours. Keep theirs.
                final WorldManifest.Entry baseEntry = baseline.get(relPath);
                final WorldManifest.Entry localEntry = local.get(relPath);
                if (baseEntry != null && baseEntry.sha256 != null
                        && localEntry != null
                        && baseEntry.sha256.equals(localEntry.sha256)) {
                    skippedStale++;
                    local.put(relPath, driveManifest.get(relPath));
                    continue;
                }
            }
            changedPaths.add(relPath);
        }
        changedPaths.addAll(diff.onlyLocal);

        // Files present on Drive but absent from our scan. Either somebody else's
        // work, or - far more commonly - a region file the dirty filter skipped
        // scanning. Carry the Drive entry forward so the new manifest doesn't drop it.
        for (final String relPath : diff.onlyOnDrive) {
            local.put(relPath, driveManifest.get(relPath));
        }

        if (changedPaths.isEmpty()) {
            WorldShareMod.LOGGER.info("push: nothing changed ({} file(s) left to the other player)",
                    skippedStale);
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PushResult(0, skippedStale, 0, 0L);
        }

        // A bucket is dirty if any of its files changed. Rebuilding it means packing
        // *every* file assigned to it, not just the changed ones, because the upload
        // replaces the archive wholesale.
        final Set<Integer> dirtyBuckets = new TreeSet<>();
        for (final String relPath : changedPaths) {
            dirtyBuckets.add(layout.indexFor(relPath));
        }
        final Map<Integer, Set<String>> membersByBucket =
                groupByBucket(layout, local.files().keySet());

        // Reconcile the manifest against what's actually on disk, for the buckets
        // about to be rewritten.
        //
        // The diff deliberately carries Drive-only entries forward (see above) so a
        // dirty-filtered scan doesn't drop files it never looked at. But that also
        // preserves entries for files genuinely deleted locally. Repacking their
        // bucket would then silently omit them - BucketArchive.build skips paths
        // that aren't on disk - while the manifest went on claiming they exist.
        //
        // The next player to pull would diff those files as missing, download the
        // bucket, find no such entries in it, be told the pull succeeded, and still
        // not have the files. Every subsequent pull would repeat that forever. The
        // old per-file layout couldn't produce this: a manifest entry named a real
        // Drive object or a 404.
        //
        // Only dirty buckets need checking. Clean ones aren't rewritten, so their
        // existing archives still match their manifest entries.
        int vanished = 0;
        for (final int bucket : dirtyBuckets) {
            final Set<String> members = membersByBucket.get(bucket);
            if (members == null) continue;
            final Iterator<String> it = members.iterator();
            while (it.hasNext()) {
                final String relPath = it.next();
                if (!Files.isRegularFile(worldRoot.resolve(relPath))) {
                    it.remove();
                    local.files().remove(relPath);
                    vanished++;
                }
            }
        }
        if (vanished > 0) {
            WorldShareMod.LOGGER.info(
                    "push: {} file(s) in dirty buckets no longer exist locally; "
                    + "dropping them from the manifest so it matches the archives",
                    vanished);
        }

        long totalBytes = 0L;
        for (final int bucket : dirtyBuckets) {
            for (final String relPath : membersByBucket.getOrDefault(bucket, Set.of())) {
                final WorldManifest.Entry entry = local.get(relPath);
                if (entry != null) totalBytes += entry.size;
            }
        }

        WorldShareMod.LOGGER.info(
                "push: {} changed file(s) dirty {} of {} bucket(s); repacking {} MB",
                changedPaths.size(), dirtyBuckets.size(), layout.bucketCount(),
                totalBytes / (1024 * 1024));
        progress.onStart(changedPaths.size(), totalBytes);

        final Map<Integer, Set<String>> toUpload = new TreeMap<>();
        for (final int bucket : dirtyBuckets) {
            toUpload.put(bucket, membersByBucket.getOrDefault(bucket, Set.of()));
        }

        final TransferResult transfer = transferBuckets(
                toUpload, remote, worldRoot, local, true, progress,
                changedPaths.size(), totalBytes);

        final int failed = transfer.bucketsFailed;
        final long bytes = transfer.bytesMoved;

        if (failed > 0) {
            progress.onError(new IOException(
                    failed + " bucket upload(s) failed; the world's control file was NOT updated"));
            WorldShareMod.LOGGER.error("push: {} bucket(s) failed; control file not committed", failed);
            return new PushResult(transfer.bucketsOk, skippedStale, failed, bytes);
        }

        // Only now, with every dirty archive safely on Drive, does the manifest that
        // describes them become the published truth.
        if (!LockManager.weHoldLock()) {
            WorldShareMod.LOGGER.error(
                    "push: lock no longer ours, aborting control-file commit. "
                            + "{} bucket(s) were uploaded but the manifest is unchanged.",
                    transfer.bucketsOk);
            progress.onError(new IOException(
                    "Your session lock was overridden during upload. "
                            + "Bucket archives were uploaded but the manifest was NOT updated. "
                            + "Your changes are still saved locally. "
                            + "Coordinate with the other player and retry."));
            return new PushResult(transfer.bucketsOk, skippedStale, 0, bytes);
        }

        commitControl(remote, local);
        saveScanCache(local, scanCache, worldRoot);
        DirtyRegionTracker.resetAfterPush();
        progress.onComplete();

        WorldShareMod.LOGGER.info(
                "push complete: {} bucket(s) uploaded, {} file(s) left to other player, {} bytes",
                transfer.bucketsOk, skippedStale, bytes);
        return new PushResult(transfer.bucketsOk, skippedStale, 0, bytes);
    }

    // ---- BUCKET TRANSFER ----

    /**
     * Upload or download a set of buckets in parallel.
     *
     * <p>Push and pull differ only in what happens to each archive once a worker
     * has it - pack-and-upload versus download-and-unpack - so the pool, the
     * largest-first ordering and the progress accounting are shared here and the
     * direction arrives as an explicit flag rather than being inferred.
     *
     * @param manifestForSizes the manifest to read entry sizes from when ordering
     *                         work and reporting progress: the local one on push,
     *                         the remote one on pull
     * @param uploading        true to pack and upload, false to download and unpack
     */
    private static TransferResult transferBuckets(final Map<Integer, Set<String>> pathsByBucket,
                                                  final RemoteFileSet remote,
                                                  final Path worldRoot,
                                                  final WorldManifest manifestForSizes,
                                                  final boolean uploading,
                                                  final SyncProgress progress,
                                                  final int totalFiles,
                                                  final long totalBytes) throws IOException {
        if (pathsByBucket.isEmpty()) {
            return new TransferResult(0, 0, 0, 0L);
        }

        final ExecutorService pool = Executors.newFixedThreadPool(TRANSFER_THREADS, r -> {
            final Thread t = new Thread(r, "WorldShare-Bucket");
            t.setDaemon(true);
            return t;
        });
        final CompletionService<BucketTaskResult> completion = new ExecutorCompletionService<>(pool);

        // Biggest buckets first, so a worker grabs the long job immediately instead
        // of finishing small ones and then waiting on it alone at the end.
        final List<Integer> order = new ArrayList<>(pathsByBucket.keySet());
        order.sort(Comparator.comparingLong(
                (Integer b) -> bytesOf(pathsByBucket.get(b), manifestForSizes)).reversed());

        for (final int bucket : order) {
            final Set<String> paths = pathsByBucket.get(bucket);
            completion.submit(() -> {
                final long start = System.currentTimeMillis();
                try {
                    final long moved = uploading
                            ? uploadBucket(remote, bucket, worldRoot, paths)
                            : downloadBucket(remote, bucket, worldRoot, paths);
                    // A pull that found an empty placeholder moved no bytes and
                    // restored nothing; counting its expected files as restored
                    // would report a success that didn't happen.
                    final int handled = (!uploading && moved == 0L) ? 0 : paths.size();
                    return new BucketTaskResult(bucket, handled, moved,
                            System.currentTimeMillis() - start, null);
                } catch (final Throwable t) {
                    return new BucketTaskResult(bucket, paths.size(), 0L,
                            System.currentTimeMillis() - start, t);
                }
            });
        }
        pool.shutdown();

        int bucketsOk = 0;
        int bucketsFailed = 0;
        int filesOk = 0;
        long bytesMoved = 0L;
        try {
            for (int i = 0; i < order.size(); i++) {
                final BucketTaskResult res = completion.take().get();
                final String archiveName = BucketLayout.bucketFilename(res.bucketIndex);
                if (res.error == null) {
                    bucketsOk++;
                    filesOk += res.fileCount;
                    bytesMoved += res.bytesMoved;
                    WorldShareMod.LOGGER.info("{}: {} | {} file(s) | {} bytes | {}ms",
                            uploading ? "push" : "pull", archiveName,
                            res.fileCount, res.bytesMoved, res.elapsedMs);
                } else {
                    bucketsFailed++;
                    WorldShareMod.LOGGER.error("{}: failed {}: {}",
                            uploading ? "push" : "pull", archiveName, res.error.getMessage());
                }
                // Report after either outcome so the bar still advances when a bucket
                // fails; filesOk deliberately counts only work that actually landed.
                progress.onFileProgress(filesOk, totalFiles, bytesMoved, totalBytes, archiveName);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("Sync interrupted", e);
        } catch (final ExecutionException e) {
            // Workers catch Throwable, so this is unreachable. Defensive.
            throw new IOException("Unexpected bucket transfer failure", e.getCause());
        }

        return new TransferResult(bucketsOk, bucketsFailed, filesOk, bytesMoved);
    }

    /**
     * Pack a bucket's files and replace its archive on Drive.
     *
     * @return bytes uploaded (the archive's compressed size)
     */
    private static long uploadBucket(final RemoteFileSet remote,
                                     final int bucketIndex,
                                     final Path worldRoot,
                                     final Set<String> paths) throws IOException {
        final Path archive = Files.createTempFile(
                "worldshare-bucket-" + bucketIndex + "-", ".zip");
        try {
            BucketArchive.build(worldRoot, paths, archive);
            final long size = Files.size(archive);

            final String fileId = remote.bucketFileId(bucketIndex);
            if (fileId == null) {
                throw new IOException("No Drive file ID for "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + " - this world's setup is incomplete.");
            }
            CloudModule.driveClient().updateFile(fileId, archive);
            return size;
        } finally {
            try { Files.deleteIfExists(archive); } catch (final IOException ignored) {}
        }
    }

    /**
     * Fetch a bucket's archive and extract only the files we actually need.
     *
     * @return bytes downloaded (the archive's compressed size)
     */
    private static long downloadBucket(final RemoteFileSet remote,
                                       final int bucketIndex,
                                       final Path worldRoot,
                                       final Set<String> wantedPaths) throws IOException {
        final Path archive = Files.createTempFile(
                "worldshare-bucket-" + bucketIndex + "-", ".zip");
        try {
            final String fileId = remote.bucketFileId(bucketIndex);
            if (fileId == null) {
                throw new IOException("No Drive file ID for "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + " - this world's setup is incomplete.");
            }
            CloudModule.driveClient().downloadFile(fileId, archive);
            final long size = Files.size(archive);
            if (size == 0L) {
                // A placeholder nobody has pushed to yet. Nothing to extract, and
                // definitely not an error worth failing the whole pull over.
                WorldShareMod.LOGGER.debug("pull: {} is an empty placeholder, skipping",
                        BucketLayout.bucketFilename(bucketIndex));
                return 0L;
            }
            BucketArchive.extract(archive, worldRoot, wantedPaths);
            return size;
        } finally {
            try { Files.deleteIfExists(archive); } catch (final IOException ignored) {}
        }
    }

    // ---- HELPERS ----

    /** Partition relative paths by the bucket they belong to. */
    private static Map<Integer, Set<String>> groupByBucket(final BucketLayout layout,
                                                           final Set<String> paths) {
        final Map<Integer, Set<String>> byBucket = new TreeMap<>();
        for (final String relPath : paths) {
            byBucket.computeIfAbsent(layout.indexFor(relPath), k -> new LinkedHashSet<>())
                    .add(relPath);
        }
        return byBucket;
    }

    private static long bytesOf(final Set<String> paths, final WorldManifest manifest) {
        long total = 0L;
        for (final String relPath : paths) {
            final WorldManifest.Entry entry = manifest.get(relPath);
            if (entry != null) total += entry.size;
        }
        return total;
    }

    /**
     * Publish the new manifest to the control file.
     *
     * <p>Goes through {@link ControlFileClient#update} rather than a plain write so
     * the read-modify-write happens under the same monitor the heartbeat uses. That
     * matters for a reason specific to this design: the lock now lives in the same
     * document as the manifest, and the push may have taken minutes, during which
     * the heartbeat will have refreshed the lock's expiry. Re-reading inside the
     * monitor and replacing only the manifest keeps that refresh instead of
     * stamping a minutes-old lock back over it - which would make our own live
     * session look expired to the other player.
     */
    private static void commitControl(final RemoteFileSet remote,
                                      final WorldManifest manifest) throws IOException {
        manifest.generatedAt = Instant.now().toString();
        if (manifest.generatedByMachineId == null) {
            manifest.generatedByMachineId = MachineId.get();
        }

        ControlFileClient.update(remote.controlFileId, remote.bucketCount, control -> {
            control.manifest = manifest;
            control.bucketCount = remote.bucketCount;
        });

        WorldShareMod.LOGGER.info("commitControl: published manifest with {} entries", manifest.size());
    }

    private static RemoteFileSet requireComplete(final RemoteFileSet remote) throws IOException {
        if (remote == null) {
            throw new IOException("This world isn't linked to Drive yet. Run WorldShare setup for it.");
        }
        if (!remote.isComplete()) {
            final List<String> missing = remote.missingFilenames();
            throw new IOException("This world's Drive setup is incomplete - "
                    + missing.size() + " file(s) still need to be picked: "
                    + String.join(", ", missing.subList(0, Math.min(4, missing.size())))
                    + (missing.size() > 4 ? ", ..." : "")
                    + ". Re-run WorldShare setup to select them.");
        }
        return remote;
    }

    /**
     * Refuse to sync when the remote layout and ours disagree on bucket count.
     *
     * <p>This is the one mismatch that must never be papered over. The same path
     * hashes to a different bucket under a different count, so proceeding would
     * scatter files into archives the other player never reads back - a silent,
     * gradual corruption rather than an error anyone would notice.
     */
    private static BucketLayout requireMatchingLayout(final ControlFile control,
                                                      final RemoteFileSet remote)
            throws IOException {
        if (control.bucketCount != remote.bucketCount) {
            throw new IOException(String.format(
                    "Bucket layout mismatch: this world is set up locally for %d bucket(s) "
                            + "but Drive says %d. Syncing anyway would corrupt the world. "
                            + "Re-run WorldShare setup for this world to match.",
                    remote.bucketCount, control.bucketCount));
        }
        return control.layout();
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
     * Saves the scan cache after a successful push. Merges the old cache (which
     * still holds mtimes for non-dirty files this push never re-scanned) with the
     * fresh scan; fresh entries win.
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

    // ---- VALUE TYPES ----

    /** Tally of one push or pull's bucket transfers. */
    private static final class TransferResult {
        /** Bucket archives that transferred successfully. */
        final int bucketsOk;
        /** Bucket archives that failed. */
        final int bucketsFailed;
        /** World files covered by the successful archives. */
        final int filesOk;
        /** Archive bytes actually moved over the network. */
        final long bytesMoved;

        TransferResult(final int bucketsOk, final int bucketsFailed,
                       final int filesOk, final long bytesMoved) {
            this.bucketsOk = bucketsOk;
            this.bucketsFailed = bucketsFailed;
            this.filesOk = filesOk;
            this.bytesMoved = bytesMoved;
        }
    }

    private static final class BucketTaskResult {
        final int bucketIndex;
        final int fileCount;
        final long bytesMoved;
        final long elapsedMs;
        final Throwable error;

        BucketTaskResult(final int bucketIndex, final int fileCount, final long bytesMoved,
                         final long elapsedMs, final Throwable error) {
            this.bucketIndex = bucketIndex;
            this.fileCount = fileCount;
            this.bytesMoved = bytesMoved;
            this.elapsedMs = elapsedMs;
            this.error = error;
        }
    }

    public static final class PushResult {
        /** Number of bucket archives rewritten on Drive. */
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
        /** Number of world files restored from bucket archives. */
        public final int downloaded;
        public final int failed;
        public final long bytes;

        PullResult(int d, int f, long b) { this.downloaded = d; this.failed = f; this.bytes = b; }
    }
}
