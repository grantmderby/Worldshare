package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.ControlFile;
import com.worldshare.mod.cloud.ControlFileClient;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.util.MachineId;
import com.worldshare.mod.util.SHA256Util;
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

    /**
     * How many times to attempt a single archive transfer before giving up.
     *
     * <p>Uploading tens of megabytes over a home connection fails occasionally -
     * "Error writing request body to server" is Drive's side of a dropped
     * connection, and it happened three times in one afternoon of testing. Without
     * a retry, one blip on one bucket failed the entire push: the manifest was not
     * committed, the lock was kept, and the player had to notice and Resume.
     *
     * <p>Three attempts turns a transient network hiccup back into what it is,
     * rather than an event the player has to handle.
     */
    private static final int TRANSFER_ATTEMPTS = 3;

    /** Backoff before retrying a failed transfer; doubles each attempt. */
    private static final long RETRY_BASE_DELAY_MS = 1500L;

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

    /**
     * Check this client can speak to the world at all, before opening it.
     *
     * <p>The layout check otherwise lives inside push and pull, which misses the
     * one path that does neither: resuming a session skips the pull, because the
     * local copy is authoritative when we already hold the lock. A world written
     * under an older bucket mapping would then open fine, get played, and only
     * refuse at save-and-quit - after the session, which is the worst moment to
     * learn the world needs republishing.
     *
     * <p>One control-file read, on a path that already contacts Drive to take the
     * lock.
     *
     * @throws ManifestMismatchException if the world needs a repair first
     */
    public static void requireCompatibleLayout(final RemoteFileSet remote) throws IOException {
        final ControlFile control = ControlFileClient.read(requireComplete(remote).controlFileId);
        if (control == null) {
            return;   // nobody has pushed yet; nothing to be incompatible with
        }
        requireMatchingLayout(control, remote);
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
        SyncActivity.begin();
        try {
            return pullInternal(worldRoot, remote, ownUuid, progress);
        } finally {
            SyncActivity.end();
        }
    }

    private static PullResult pullInternal(final Path worldRoot,
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

        // Don't download what this installation has chosen not to sync.
        //
        // Excluding a folder used to be one-directional: the local scan skipped it,
        // so it vanished from the local manifest, so the diff called it Drive-only
        // and downloaded it straight back - overwriting the local copy with somebody
        // else's, having just declined to upload our own. Exclusion has to mean the
        // same thing in both directions or it means nothing.
        //
        // Note this leaves the files on Drive untouched: pushing carries Drive-only
        // entries forward into the new manifest, so excluding a folder never deletes
        // it for anybody else.
        final int wantedBefore = wantedPaths.size();
        wantedPaths.removeIf(relPath -> !TrackedPaths.isTracked(relPath));
        if (wantedPaths.size() != wantedBefore) {
            WorldShareMod.LOGGER.info(
                    "pull: skipping {} file(s) this installation excludes from sync",
                    wantedBefore - wantedPaths.size());
        }

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

        stripPlayerFromLevelData(worldRoot);

        // Local now matches Drive - invalidate the scan cache and reset dirty
        // tracking. Downloaded files carry fresh mtimes that would otherwise look
        // like cache hits on the next scan.
        try {
            Files.deleteIfExists(worldRoot.resolve(SCAN_CACHE_FILENAME));
        } catch (final IOException ignored) {}
        DirtyRegionTracker.resetAfterPull();

        if (failed > 0) {
            // Report the cause, not the count. The per-bucket message already says
            // which file disagreed with the manifest and that the other player needs
            // to push again; discarding it left the screen advising a retry, which
            // for a verification failure can only fail again.
            final boolean mismatch = transfer.firstError != null
                    && transfer.firstError.contains(MISMATCH_MARKER);
            final String detail = playerFacingFailure(transfer.firstError, control, failed);
            // Typed, so callers don't have to recognise it by its wording. The
            // technical form was already logged per-bucket by transferBuckets.
            final IOException failure = mismatch
                    ? new ManifestMismatchException(detail)
                    : new IOException(detail);
            progress.onError(failure);
            WorldShareMod.LOGGER.error("pull: {} files restored, {} bucket(s) FAILED, {} bytes",
                    downloaded, failed, bytes);
            throw failure;
        }

        progress.onComplete();
        WorldShareMod.LOGGER.info("pull complete: {} file(s) restored, {} bytes ({} MB)",
                downloaded, bytes, bytes / (1024 * 1024));
        return new PullResult(downloaded, failed, bytes);
    }

    // ---- PUSH ----

    public static PushResult push(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid) throws IOException {
        return push(worldRoot, remote, ownUuid, SyncProgress.NOOP);
    }

    public static PushResult push(final Path worldRoot,
                                  final RemoteFileSet remote,
                                  final UUID ownUuid,
                                  final SyncProgress progress) throws IOException {
        SyncActivity.begin();
        try {
            return pushInternal(worldRoot, remote, ownUuid, progress, false);
        } finally {
            SyncActivity.end();
        }
    }

    /**
     * Republish the whole world, making Drive internally consistent again.
     *
     * <p>For one situation: an interrupted push has left bucket archives on Drive
     * that the published manifest does not describe, and the player who could
     * finish it is not coming back. Everyone else's pull then fails verification
     * on the affected bucket, and the override path is acquire, pull, open - so it
     * never reaches open and nobody can get in to fix it.
     *
     * <p><b>An ordinary push cannot do this job.</b> It works out which buckets are
     * dirty by diffing local files against the manifest, and in this failure the
     * manifest is the stale half - a repairing player's copy matches it exactly, so
     * the diff is empty and the push returns having done nothing. Forcing every
     * bucket to be repacked and the manifest republished is what makes archives and
     * manifest agree, and it agrees by construction rather than by comparison.
     *
     * <p>Repacking only the bucket that failed verification would be cheaper and
     * wrong: a pull only checks the buckets it needed, so others may disagree
     * without anyone having noticed.
     *
     * <p>Costs a full world upload. That is acceptable for a recovery that should
     * happen approximately never, and the caller is expected to have said so
     * plainly and taken a backup first.
     */
    public static PushResult repair(final Path worldRoot,
                                    final RemoteFileSet remote,
                                    final UUID ownUuid,
                                    final SyncProgress progress) throws IOException {
        SyncActivity.begin();
        try {
            WorldShareMod.LOGGER.warn(
                    "repair: republishing every bucket of '{}' from the local copy",
                    worldRoot.getFileName());
            return pushInternal(worldRoot, remote, ownUuid, progress, true);
        } finally {
            SyncActivity.end();
        }
    }

    /**
     * @param forceAllBuckets repack and upload every bucket regardless of what
     *                        changed, and republish the manifest even if the local
     *                        world matches it. See {@link #repair}.
     */
    private static PushResult pushInternal(final Path worldRoot,
                                           final RemoteFileSet remote,
                                           final UUID ownUuid,
                                           final SyncProgress progress,
                                           final boolean forceAllBuckets) throws IOException {
        requireComplete(remote);

        final ControlFile control =
                ControlFileClient.readOrInitial(remote.controlFileId, remote.bucketCount);
        final BucketLayout layout = requireMatchingLayout(control, remote);
        final WorldManifest driveManifest = control.manifestOrEmpty();
        final boolean firstPush = driveManifest.files().isEmpty();

        final WorldManifest local;
        final WorldManifest scanCache;

        if (firstPush || forceAllBuckets) {
            // Nothing on Drive yet, or a repair: scan everything. Skipping any file
            // here would leave a bucket permanently missing content nobody
            // re-dirties - and a repair in particular must not trust the scan cache
            // or the dirty filter, since prior state is exactly what is in doubt.
            WorldShareMod.LOGGER.info(firstPush
                    ? "push: control file has no manifest - first-time push, full scan"
                    : "repair: full scan, ignoring the scan cache and dirty filter");
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
        final Set<String> changedPaths = new LinkedHashSet<>(diff.different);
        changedPaths.addAll(diff.onlyLocal);

        // Files present on Drive but absent from our scan. Either somebody else's
        // work, or - far more commonly - a region file the dirty filter skipped
        // scanning. Carry the Drive entry forward so the new manifest doesn't drop it.
        for (final String relPath : diff.onlyOnDrive) {
            local.put(relPath, driveManifest.get(relPath));
        }

        // Refuse to publish a copy of somebody else's newer work.
        //
        // A dirty bucket is repacked in full from local disk, so a push rewrites
        // every file in it - including files this player never touched. If our copy
        // of one of those differs from what Drive has, ours is the older one (we
        // didn't change it, so the difference is theirs) and packing it would revert
        // their progress.
        //
        // With the lock verified against Drive before uploading, this should never
        // fire in normal use: holding a valid lock means nobody else can push. It is
        // here to catch states where something has already gone wrong - a pull that
        // failed partway and left a mixture on disk, or some future path that takes
        // a lock without pulling first. Refusing is deliberate: merging would mean
        // writing world files underneath a running game.
        final List<String> staleHere = new ArrayList<>();
        for (final String relPath : diff.different) {
            if (!changedPaths.contains(relPath)) {
                staleHere.add(relPath);
            }
        }
        if (!staleHere.isEmpty()) {
            throw new IOException(describeStalePush(staleHere));
        }

        if (changedPaths.isEmpty() && !forceAllBuckets) {
            WorldShareMod.LOGGER.info("push: nothing changed");
            progress.onStart(0, 0L);
            progress.onComplete();
            return new PushResult(0, 0, 0, 0, 0L);
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
        if (forceAllBuckets) {
            // Every bucket that holds anything gets rewritten, so the archives and
            // the manifest published alongside them agree by construction. Empty
            // buckets are left alone: their placeholder is already correct, and
            // uploading an empty archive over it would achieve nothing.
            dirtyBuckets.addAll(membersByBucket.keySet());
        }

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

        // Count the files being repacked, not the files that changed.
        //
        // These are different numbers - a dirty bucket is rebuilt whole, so 15
        // changed files can mean 25 files written - and progress has to use the
        // repacked one, because that is what transferBuckets counts off as each
        // archive lands. Reporting changed-file count as the denominator produced
        // "25 / 15 files" on screen. totalBytes was already measured over full
        // bucket membership, so this also puts the two halves of the progress
        // report back into agreement with each other.
        long totalBytes = 0L;
        int repackedFiles = 0;
        for (final int bucket : dirtyBuckets) {
            for (final String relPath : membersByBucket.getOrDefault(bucket, Set.of())) {
                repackedFiles++;
                final WorldManifest.Entry entry = local.get(relPath);
                if (entry != null) totalBytes += entry.size;
            }
        }

        WorldShareMod.LOGGER.info(
                "push: {} changed file(s) dirty {} of {} bucket(s); repacking {} file(s), {} MB",
                changedPaths.size(), dirtyBuckets.size(), layout.bucketCount(), repackedFiles,
                totalBytes / (1024 * 1024));

        // Confirm the lock is still ours BEFORE overwriting anything.
        //
        // This has to be read from Drive rather than trusting weHoldLock(), which is
        // a local flag only refreshed when the heartbeat happens to run - up to 15
        // minutes stale. Checking it afterwards, as this used to, is too late: by
        // then the archives have already been replaced, and since a bucket is
        // repacked whole that includes files belonging to whoever took the lock over.
        requireLockStillOurs(remote, "uploading");

        progress.onStart(repackedFiles, totalBytes);

        final Map<Integer, Set<String>> toUpload = new TreeMap<>();
        for (final int bucket : dirtyBuckets) {
            toUpload.put(bucket, membersByBucket.getOrDefault(bucket, Set.of()));
        }

        warnAboutLargeUnfamiliarUploads(changedPaths, local);

        // From here until commitControl, Drive may hold archives the published
        // manifest doesn't describe. Nobody else may be given the lock in between.
        SyncActivity.markRemoteAheadOfManifest();

        final TransferResult transfer = transferBuckets(
                toUpload, remote, worldRoot, local, true, progress,
                repackedFiles, totalBytes);

        final int failed = transfer.bucketsFailed;
        final long bytes = transfer.bytesMoved;

        if (failed > 0) {
            progress.onError(new IOException(
                    failed + " bucket upload(s) failed; the world's control file was NOT updated"));
            WorldShareMod.LOGGER.error("push: {} bucket(s) failed; control file not committed", failed);
            return new PushResult(transfer.bucketsOk, transfer.filesOk, 0, failed, bytes);
        }

        // Only now, with every dirty archive safely on Drive, does the manifest that
        // describes them become the published truth. Re-checked because a long upload
        // leaves a window after the pre-upload check.
        if (!LockManager.weHoldLock(remote)) {
            WorldShareMod.LOGGER.error(
                    "push: lock lost during upload, aborting control-file commit. "
                            + "{} bucket(s) were uploaded but the manifest is unchanged.",
                    transfer.bucketsOk);
            progress.onError(new IOException(
                    "Your session lock was taken over while the upload was running. "
                            + "The manifest was NOT updated, so the world still describes "
                            + "the other player's state. Your changes are still saved "
                            + "locally. Coordinate with them before retrying."));
            return new PushResult(transfer.bucketsOk, transfer.filesOk, 0, 0, bytes);
        }

        commitControl(remote, local, worldRoot);
        // The manifest now describes what the archives actually hold.
        SyncActivity.clearRemoteAheadOfManifest();
        saveScanCache(local, scanCache, worldRoot);
        DirtyRegionTracker.resetAfterPush();
        progress.onComplete();

        WorldShareMod.LOGGER.info("push complete: {} bucket(s) uploaded, {} bytes",
                transfer.bucketsOk, bytes);
        return new PushResult(transfer.bucketsOk, transfer.filesOk, 0, 0, bytes);
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
            return new TransferResult(0, 0, 0, 0L, null);
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
                            ? uploadBucket(remote, bucket, worldRoot, paths, manifestForSizes)
                            : downloadBucket(remote, bucket, worldRoot, paths, manifestForSizes);
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
        String firstError = null;
        // Progress is accounted separately from bytesMoved, and the distinction is
        // the whole point: bytesMoved is what actually crossed the wire (compressed
        // archive bytes), while totalBytes is the uncompressed size of the world
        // files being transferred. Reporting one against the other made the bar
        // stall at whatever the world's compression ratio happened to be - around
        // 62% in testing - and never reach 100%. Summing the same uncompressed
        // sizes here keeps both sides of the fraction in the same unit.
        long bytesAccounted = 0L;
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
                    if (firstError == null) {
                        firstError = res.error.getMessage() == null
                                ? res.error.getClass().getSimpleName()
                                : res.error.getMessage();
                    }
                    WorldShareMod.LOGGER.error("{}: failed {}: {}",
                            uploading ? "push" : "pull", archiveName, res.error.getMessage());
                }
                // Report after either outcome so the bar still advances when a bucket
                // fails; filesOk deliberately counts only work that actually landed.
                bytesAccounted += bytesOf(pathsByBucket.get(res.bucketIndex), manifestForSizes);
                progress.onFileProgress(filesOk, totalFiles, bytesAccounted, totalBytes, archiveName);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("Sync interrupted", e);
        } catch (final ExecutionException e) {
            // Workers catch Throwable, so this is unreachable. Defensive.
            throw new IOException("Unexpected bucket transfer failure", e.getCause());
        }

        return new TransferResult(bucketsOk, bucketsFailed, filesOk, bytesMoved, firstError);
    }

    /**
     * Pack a bucket's files and replace its archive on Drive.
     *
     * @return bytes uploaded (the archive's compressed size)
     */
    private static long uploadBucket(final RemoteFileSet remote,
                                     final int bucketIndex,
                                     final Path worldRoot,
                                     final Set<String> paths,
                                     final WorldManifest expected) throws IOException {
        final Path archive = Files.createTempFile(
                "worldshare-bucket-" + bucketIndex + "-", ".zip");
        try {
            final Map<String, String> packed = BucketArchive.build(worldRoot, paths, archive);
            verifyPacked(packed, paths, expected, bucketIndex);
            final long size = Files.size(archive);

            final String fileId = remote.bucketFileId(bucketIndex);
            if (fileId == null) {
                throw new IOException("No Drive file ID for "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + " - this world's setup is incomplete.");
            }

            // Only the network call retries. Packing and verification are
            // deterministic - repeating them would produce the same bytes and the
            // same verdict, so a retry there would just be slower.
            withRetry("upload " + BucketLayout.bucketFilename(bucketIndex),
                    () -> CloudModule.driveClient().updateFile(fileId, archive));
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
                                       final Set<String> wantedPaths,
                                       final WorldManifest expected) throws IOException {
        final Path archive = Files.createTempFile(
                "worldshare-bucket-" + bucketIndex + "-", ".zip");
        try {
            final String fileId = remote.bucketFileId(bucketIndex);
            if (fileId == null) {
                throw new IOException("No Drive file ID for "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + " - this world's setup is incomplete.");
            }
            withRetry("download " + BucketLayout.bucketFilename(bucketIndex),
                    () -> CloudModule.driveClient().downloadFile(fileId, archive));
            final long size = Files.size(archive);
            if (size == 0L) {
                // A placeholder nobody has pushed to yet. Nothing to extract, and
                // definitely not an error worth failing the whole pull over.
                WorldShareMod.LOGGER.debug("pull: {} is an empty placeholder, skipping",
                        BucketLayout.bucketFilename(bucketIndex));
                return 0L;
            }
            // Check the archive before a single byte of it reaches the world.
            // Extraction used to come first and verification second, which made
            // this guard a detector rather than a preventer - mismatched content
            // landed in the world and was then reported.
            verifyArchive(archive, wantedPaths, expected, bucketIndex);

            final List<String> extracted =
                    BucketArchive.extract(archive, worldRoot, wantedPaths);
            // Kept as a post-check. It costs little and catches something the
            // pre-check cannot: content that changed between being verified and
            // being written.
            verifyExtracted(worldRoot, extracted, expected, bucketIndex);
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
                                      final WorldManifest manifest,
                                      final Path worldRoot) throws IOException {
        manifest.generatedAt = Instant.now().toString();
        if (manifest.generatedByMachineId == null) {
            manifest.generatedByMachineId = MachineId.get();
        }

        ControlFileClient.update(remote.controlFileId, remote.bucketCount, control -> {
            control.manifest = manifest;
            control.bucketCount = remote.bucketCount;
            // Carry the world's name so joiners have something to call it. Only the
            // host knows it - a joiner sees file IDs and numbered archives.
            if (worldRoot != null && worldRoot.getFileName() != null) {
                control.worldName = worldRoot.getFileName().toString();
            }
            // The only write entitled to claim a layout version: this manifest
            // describes archives this client packed under the current mapping. Any
            // other write - taking the lock, a heartbeat - would be asserting
            // something about bytes it never touched.
            control.layoutVersion = BucketLayout.LAYOUT_VERSION;
        });

        WorldShareMod.LOGGER.info("commitControl: published manifest with {} entries", manifest.size());
    }

    /**
     * Abort unless Drive still says this machine holds the world's lock.
     *
     * <p>Deliberately reads the control file rather than consulting
     * {@link LockManager#weHoldLock()}, which is a cached local flag updated only
     * by the 15-minute heartbeat and so can be that far out of date.
     */
    private static void requireLockStillOurs(final RemoteFileSet remote, final String about)
            throws IOException {
        final LockManager.LockStatus status = LockManager.readStatus(remote);
        if (status.state == LockManager.LockState.HELD_BY_US) {
            return;
        }
        final String holder = (status.lock != null && status.lock.holderName != null)
                ? status.lock.holderName : "someone else";
        WorldShareMod.LOGGER.error("push: refusing to continue {} - lock state is {}",
                about, status.state);
        throw new IOException(
                "This world's session lock is no longer yours (" + holder + " holds it now), "
                        + "so " + about + " would overwrite their work. Your changes are "
                        + "still saved locally. Coordinate with them, then reopen the world "
                        + "from Contributor Worlds to get their changes before retrying.");
    }

    /** Wording for a push that would publish somebody else's older data. */
    private static String describeStalePush(final List<String> stale) {
        final int shown = Math.min(4, stale.size());
        final String names = String.join(", ", stale.subList(0, shown));
        return "Someone else has newer versions of " + stale.size() + " file(s) that this "
                + "copy hasn't caught up with (" + names
                + (stale.size() > shown ? ", ..." : "") + "). Pushing now would replace their "
                + "work with older data. Save and quit, then reopen this world from "
                + "Contributor Worlds to pull their changes first.";
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
        if (control.layoutVersion != BucketLayout.LAYOUT_VERSION) {
            // Refuse rather than sync half under each mapping. A push only rewrites
            // the buckets whose contents changed, so proceeding would leave every
            // untouched file in the archive the old mapping put it in while this one
            // looks for it elsewhere - a loss that surfaces as a missing file long
            // after the push that caused it.
            throw new ManifestMismatchException(String.format(
                    "This world was uploaded with an older version of WorldShare "
                            + "(file layout v%d, this version uses v%d). It needs to be "
                            + "republished once before it can sync again.",
                    control.layoutVersion, BucketLayout.LAYOUT_VERSION));
        }
        return control.layout();
    }

    // ---- LEVEL.DAT PLAYER STRIP ----

    /**
     * Remove the singleplayer host's character from the level data files.
     *
     * <p>In a singleplayer or LAN world the host's inventory lives in
     * {@code level.dat} under {@code Data.Player}, not in {@code playerdata/}.
     * Left in place, whoever opens the world next would load the previous host's
     * character. Stripping it forces Minecraft to fall back to
     * {@code playerdata/<uuid>.dat}, which is per-player and syncs on its own -
     * giving dedicated-server behaviour where your character follows you.
     *
     * <p><b>Both files matter.</b> {@code level.dat_old} is Minecraft's rollback
     * copy and is synced too. If that fallback ever fires it restores a level.dat
     * carrying the previous host's Player tag - reintroducing exactly this bug at
     * the moment the player is already recovering from a problem.
     */
    private static void stripPlayerFromLevelData(final Path worldRoot) {
        for (final String name : new String[]{"level.dat", "level.dat_old"}) {
            stripPlayerFrom(worldRoot.resolve(name), worldRoot);
        }
    }

    private static void stripPlayerFrom(final Path levelFile, final Path worldRoot) {
        if (!Files.isRegularFile(levelFile)) return;
        try {
            final CompoundTag root = NbtIo.readCompressed(levelFile, NbtAccounter.unlimitedHeap());
            final CompoundTag data = root.getCompound("Data");
            if (!data.contains("Player")) return;
            data.remove("Player");
            NbtIo.writeCompressed(root, levelFile);
            WorldShareMod.LOGGER.info("pull: stripped Player tag from {} in '{}'",
                    levelFile.getFileName(), worldRoot.getFileName());
        } catch (final Throwable t) {
            // Non-fatal: a level.dat_old we can't parse is a rollback copy we
            // simply won't have cleaned, not a reason to fail an otherwise good pull.
            WorldShareMod.LOGGER.warn("pull: failed to strip Player from {} (non-fatal): {}",
                    levelFile.getFileName(), t.getMessage());
        }
    }

    /**
     * Confirm extracted files match the hashes the manifest promised.
     *
     * <p>An archive and the manifest describing it are separate Drive objects, so
     * they can drift - a push interrupted between uploading buckets and committing
     * the manifest leaves exactly that. Nothing used to notice: pull extracted
     * whatever the archive held and trusted it. Checking here turns a silent wrong
     * -content bug into a clear failure naming the file.
     */
    /** Unfamiliar content past this size is worth mentioning when it uploads: 128 MB. */
    private static final long LARGE_UNFAMILIAR_BYTES = 128L * 1024 * 1024;

    private static final Set<String> FAMILIAR_TOP_LEVEL = Set.of(
            "region", "entities", "poi", "playerdata", "stats", "advancements",
            "data", "resources", "datapacks", "v_data", "serverconfig",
            "dim-1", "dim1", "dimensions", "level.dat", "level.dat_old");

    /**
     * Tell the player when this push is carrying a lot of something we don't
     * recognise.
     *
     * <p>Said here rather than during the scan because here it is true. The scan
     * sees a large mod folder on every world open, whether or not anything in it
     * changed - warning there claimed a 225 MB folder would "sync every time", when
     * it in fact uploads only when it changes, which for a static folder is once.
     * A warning that fires when nothing is happening is one people learn to dismiss.
     */
    private static void warnAboutLargeUnfamiliarUploads(final Set<String> changedPaths,
                                                        final WorldManifest local) {
        final Map<String, Long> byTopLevel = new TreeMap<>();
        for (final String relPath : changedPaths) {
            final int slash = relPath.indexOf('/');
            final String top = slash > 0 ? relPath.substring(0, slash) : relPath;
            if (FAMILIAR_TOP_LEVEL.contains(top.toLowerCase(java.util.Locale.ROOT))) continue;
            final WorldManifest.Entry entry = local.get(relPath);
            byTopLevel.merge(top, entry == null ? 0L : entry.size, Long::sum);
        }

        for (final Map.Entry<String, Long> e : byTopLevel.entrySet()) {
            if (e.getValue() < LARGE_UNFAMILIAR_BYTES) continue;
            final long mb = e.getValue() / (1024 * 1024);
            WorldShareMod.LOGGER.warn("push: uploading {} MB of '{}'", mb, e.getKey());
            com.worldshare.mod.util.PlayerNotice.info(
                    "§e[WorldShare] Uploading " + mb + " MB of '" + e.getKey()
                            + "'. It re-uploads whenever it changes - if that's a cache "
                            + "you don't need shared, run /worldshare exclude " + e.getKey() + "/");
        }
    }

    /** A transfer step that can fail with an IOException. */
    @FunctionalInterface
    private interface TransferStep {
        void run() throws IOException;
    }

    /**
     * Run a network transfer, retrying a few times before giving up.
     *
     * <p>Wraps only the call that crosses the network. Everything around it -
     * packing, hashing, extraction, verification - is deterministic, so retrying
     * those would repeat the same work to reach the same answer.
     *
     * <p>An interrupt is not a failure to retry through: it means the game is
     * shutting down, and the right response is to stop promptly.
     */
    private static void withRetry(final String what, final TransferStep step) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= TRANSFER_ATTEMPTS; attempt++) {
            try {
                step.run();
                if (attempt > 1) {
                    WorldShareMod.LOGGER.info("{} succeeded on attempt {}", what, attempt);
                }
                return;
            } catch (final IOException e) {
                last = e;
                if (attempt == TRANSFER_ATTEMPTS) break;
                final long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                WorldShareMod.LOGGER.warn(
                        "{} failed on attempt {} of {} ({}); retrying in {} ms",
                        what, attempt, TRANSFER_ATTEMPTS, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(what + " interrupted during retry backoff", ie);
                }
            }
        }
        throw new IOException(what + " failed after " + TRANSFER_ATTEMPTS
                + " attempts: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Check what we just packed still matches the manifest we are about to publish.
     *
     * <p>The manifest is produced by a scan that finishes before any archive is
     * built, and the world is not frozen in between. The gap is small but reachable:
     * "Continue in Background" leaves an upload running while the player is free to
     * reopen that same world from vanilla Singleplayer, which is the one route into
     * a world that doesn't queue behind {@code CloudModule.executor()}. Minecraft
     * then rewrites chunks underneath the pack.
     *
     * <p>Without this, the push would succeed and publish a manifest describing
     * content the archives no longer hold - discovered later by the other player's
     * pull failing verification, which blames the wrong person at the worst moment.
     * Failing here instead costs one upload, and the existing machinery does the
     * rest: the manifest is never committed and the lock stays held.
     *
     * <p>An absent path is the other case in the same family - the world folder
     * deleted mid-push - and is caught by the same check.
     */
    private static void verifyPacked(final Map<String, String> packed,
                                     final Set<String> requested,
                                     final WorldManifest expected,
                                     final int bucketIndex) throws IOException {
        if (expected == null) return;
        for (final String relPath : requested) {
            final WorldManifest.Entry entry = expected.get(relPath);
            if (entry == null || entry.sha256 == null) continue;

            final String actual = packed.get(relPath);
            if (actual == null) {
                throw new IOException("'" + relPath + "' disappeared while packing "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + ". The world changed mid-upload - is it open in another "
                        + "window? Nothing was uploaded; try saving again.");
            }
            if (!entry.sha256.equals(actual)) {
                throw new IOException("'" + relPath + "' changed while packing "
                        + BucketLayout.bucketFilename(bucketIndex)
                        + ". The world changed mid-upload - is it open in another "
                        + "window? Nothing was uploaded; try saving again.");
            }
        }
    }

    /**
     * Check a downloaded archive against the manifest without writing anything.
     *
     * <p>Runs before extraction, so a bucket whose contents disagree with the
     * published manifest is refused rather than applied and then complained about.
     */
    private static void verifyArchive(final Path archive,
                                      final Set<String> wantedPaths,
                                      final WorldManifest expected,
                                      final int bucketIndex) throws IOException {
        if (expected == null) return;
        for (final Map.Entry<String, String> hashed
                : BucketArchive.hashEntries(archive, wantedPaths).entrySet()) {
            final WorldManifest.Entry entry = expected.get(hashed.getKey());
            // No manifest entry means the manifest doesn't claim anything about this
            // file, so there is nothing to disagree with.
            if (entry == null || entry.sha256 == null) continue;
            if (!entry.sha256.equals(hashed.getValue())) {
                throw new IOException(manifestMismatch(hashed.getKey(), bucketIndex));
            }
        }
    }

    private static void verifyExtracted(final Path worldRoot,
                                        final List<String> extracted,
                                        final WorldManifest expected,
                                        final int bucketIndex) throws IOException {
        if (expected == null) return;
        for (final String relPath : extracted) {
            final WorldManifest.Entry entry = expected.get(relPath);
            if (entry == null || entry.sha256 == null) continue;
            final String actual = SHA256Util.hashFile(worldRoot.resolve(relPath));
            if (!entry.sha256.equals(actual)) {
                throw new IOException(manifestMismatch(relPath, bucketIndex));
            }
        }
    }

    /**
     * The message both verification passes raise.
     *
     * <p>Shared so they stay identical - the UI decides whether to offer a retry by
     * matching on {@link #MISMATCH_MARKER}, and two near-copies would drift apart.
     *
     * <p>Deliberately technical: this is what lands in the log and in a bug report.
     * {@link #playerFacingFailure} turns it into something a player can act on.
     */
    private static String manifestMismatch(final String relPath, final int bucketIndex) {
        return "'" + relPath + "' came out of "
                + BucketLayout.bucketFilename(bucketIndex)
                + MISMATCH_MARKER + " The archive and manifest on Drive disagree, "
                + "which usually means a push was interrupted.";
    }

    /** Substring the UI matches on to recognise a content failure. */
    static final String MISMATCH_MARKER =
            " with different content than the world's manifest describes.";

    /**
     * Turn a transfer failure into a sentence aimed at whoever is looking at it.
     *
     * <p>The raw verification message names a region file and an archive, which is
     * the right level of detail for a log and the wrong one for a player - it reads
     * as though they have done something wrong, when the fix is entirely in someone
     * else's hands. Naming the lock holder is what makes it actionable, and the
     * control file already knows who that is.
     *
     * <p>The technical form is not lost; it is logged by the caller either way.
     */
    private static String playerFacingFailure(final String firstError,
                                              final ControlFile control,
                                              final int failed) {
        if (firstError == null) {
            return failed + " bucket(s) failed to download.";
        }
        if (!firstError.contains(MISMATCH_MARKER)) {
            return firstError;
        }
        final String who = (control != null && control.lock != null
                && control.lock.holderName != null && !control.lock.holderName.isBlank())
                ? control.lock.holderName
                : "The last player in this world";
        return who + "'s last upload didn't finish, so this world's files on Drive "
                + "don't match each other. Ask them to open the world and save again.";
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
        /**
         * Why the first failing bucket failed, or null if none did.
         *
         * <p>Carried rather than only logged. "1 bucket(s) failed to download"
         * tells a player nothing they can act on, while the underlying message
         * usually names the file and the fix - and a verification failure in
         * particular needs the other player to push again, not a retry.
         */
        final String firstError;

        TransferResult(final int bucketsOk, final int bucketsFailed,
                       final int filesOk, final long bytesMoved,
                       final String firstError) {
            this.bucketsOk = bucketsOk;
            this.bucketsFailed = bucketsFailed;
            this.filesOk = filesOk;
            this.firstError = firstError;
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

    /**
     * Outcome of a push.
     *
     * <p>Bucket and file counts are both here, and both named, because there used
     * to be one field called {@code uploaded} holding the bucket count that every
     * caller printed as "N files synced" - reporting 4 when 14 files had gone up.
     * A bucket is repacked whole, so the two numbers are never equal and neither is
     * derivable from the other.
     */
    public static final class PushResult {
        /** Number of bucket archives rewritten on Drive. */
        public final int bucketsUploaded;
        /** Number of world files those archives carried. */
        public final int filesUploaded;
        public final int skippedSomeoneElsesEdit;
        public final int failed;
        public final long bytes;

        PushResult(int buckets, int files, int s, int f, long b) {
            this.bucketsUploaded = buckets; this.filesUploaded = files;
            this.skippedSomeoneElsesEdit = s;
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
