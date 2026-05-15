package com.worldshare.mod.ui;

import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.SessionLock;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.config.WorldSubscription;
import com.worldshare.mod.relay.PresenceFile;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.sync.WorldFileScanner;
import com.worldshare.mod.sync.WorldManifest;
import com.worldshare.mod.util.WorldSharePaths;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the current state of each subscribed world by reading Drive.
 *
 * <p>Called once when {@link ContributorWorldsScreen} opens. All Drive reads
 * happen on the cloud executor; the result is a list of {@link ResolvedWorld}
 * objects that the screen renders without further I/O.
 *
 * <p>State classification:
 * <ul>
 *   <li>{@link State#LIVE} — presence.json is fresh. [Join] + [Download disabled]</li>
 *   <li>{@link State#AVAILABLE} — no lock. [Open] (or [Download] if no local copy)</li>
 *   <li>{@link State#LOCKED_BY_OTHER} — valid lock held by someone else. [Wait]</li>
 *   <li>{@link State#STALE_LOCK_CLEAN} — stale lock, but manifest is newer than lock:
 *       previous session pushed before going offline. [Open with 1-click override]</li>
 *   <li>{@link State#STALE_LOCK_CONFLICT} — stale lock AND local files are newer than
 *       manifest: previous holder played offline changes that were never pushed.
 *       [Resolve] — shows full conflict UI.</li>
 *   <li>{@link State#LOCKED_BY_US} — we hold the lock (probably crashed last session).
 *       [Resume] or [Force-release + Open]</li>
 *   <li>{@link State#NOT_DOWNLOADED} — no local folder. [Download]</li>
 *   <li>{@link State#ERROR} — Drive unreachable or parse error. Shows error message.</li>
 * </ul>
 */
public final class WorldStateResolver {

    private WorldStateResolver() {}

    /** All possible states a subscribed world can be in. */
    public enum State {
        LIVE,
        AVAILABLE,
        LOCKED_BY_OTHER,
        STALE_LOCK_CLEAN,
        STALE_LOCK_CONFLICT,
        LOCKED_BY_US,
        NOT_DOWNLOADED,
        ERROR
    }

    /**
     * Fully resolved state for one subscribed world, ready for rendering.
     */
    public static final class ResolvedWorld {
        public final WorldSubscription subscription;
        public final State state;

        /** The parsed lock, or null if no lock / not applicable. */
        public final SessionLock lock;

        /** The drive manifest, or null if unavailable. */
        public final WorldManifest driveManifest;

        /** Active presence (host online), or null if not live. */
        public final PresenceFile presence;

        /** Non-null only when state == ERROR. */
        public final String errorMessage;

        private ResolvedWorld(final WorldSubscription subscription,
                              final State state,
                              final SessionLock lock,
                              final WorldManifest driveManifest,
                              final PresenceFile presence,
                              final String errorMessage) {
            this.subscription = subscription;
            this.state = state;
            this.lock = lock;
            this.driveManifest = driveManifest;
            this.presence = presence;
            this.errorMessage = errorMessage;
        }

        public static ResolvedWorld of(final WorldSubscription sub,
                                       final State state,
                                       final SessionLock lock,
                                       final WorldManifest manifest,
                                       final PresenceFile presence) {
            return new ResolvedWorld(sub, state, lock, manifest, presence, null);
        }

        public static ResolvedWorld error(final WorldSubscription sub,
                                          final String message) {
            return new ResolvedWorld(sub, State.ERROR, null, null, null, message);
        }

        /** @return true if the world can be opened or downloaded on click. */
        public boolean isActionable() {
            return state != State.LOCKED_BY_OTHER && state != State.ERROR;
        }

        public String displayName() {
            return subscription.displayName != null
                    ? subscription.displayName
                    : subscription.driveFolderId;
        }
    }

    /**
     * Resolve all subscribed worlds. Blocks on Drive I/O — run on cloud executor.
     *
     * @param client authenticated Drive client
     * @return one ResolvedWorld per subscription, in subscription-list order
     */
    public static List<ResolvedWorld> resolveAll(final DriveClient client, final UUID ownUuid) {
        final List<WorldSubscription> subs = SubscriptionStore.get().all();
        if (subs.isEmpty()) return List.of();
        if (subs.size() == 1) return List.of(resolveOne(client, subs.get(0), ownUuid));

        final int poolSize = Math.min(6, subs.size());
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(poolSize, r -> {
                    final Thread t = new Thread(r, "WorldShare-Resolver");
                    t.setDaemon(true);
                    return t;
                });
        try {
            final List<java.util.concurrent.Future<ResolvedWorld>> futures = new java.util.ArrayList<>();
            for (final WorldSubscription sub : subs) {
                futures.add(pool.submit(() -> resolveOne(client, sub, ownUuid)));
            }
            final List<ResolvedWorld> result = new java.util.ArrayList<>(subs.size());
            for (final java.util.concurrent.Future<ResolvedWorld> f : futures) {
                try {
                    result.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
                } catch (final Throwable t) {
                    WorldShareMod.LOGGER.warn(
                            "WorldStateResolver: subscription resolve failed: {}", t.getMessage());
                }
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private static ResolvedWorld resolveOne(final DriveClient client,
                                            final WorldSubscription sub,
                                            final UUID playerUuid) {
        final String folderId = sub.driveFolderId;
        try {
            // 1. Check for live session (presence.json).
            final String presenceFileId = client.findFileByName(
                    PresenceFile.FILENAME, folderId);
            if (presenceFileId != null) {
                final PresenceFile presence = PresenceFile.fromJson(
                        client.readText(presenceFileId));
                if (!presence.isStale()) {
                    return ResolvedWorld.of(sub, State.LIVE, null, null, presence);
                }
                // Stale presence = fall through to lock check (treat as offline)
            }

            // 2. Read manifest (needed for conflict detection).
            final WorldManifest driveManifest = readManifest(client, folderId);

            // 3. No local folder yet.
            if (!sub.hasLocalFolder()) {
                return ResolvedWorld.of(sub, State.NOT_DOWNLOADED, null, driveManifest, null);
            }
            final Path localWorld = savesDir().resolve(sub.localFolderName);
            if (!Files.isDirectory(localWorld)) {
                // Folder name recorded but directory doesn't exist (renamed/deleted locally).
                return ResolvedWorld.of(sub, State.NOT_DOWNLOADED, null, driveManifest, null);
            }

            // 4. Read lock.
            final LockManager.LockStatus status = LockManager.readStatus(folderId);

            switch (status.state) {
                case FREE:
                    return ResolvedWorld.of(sub, State.AVAILABLE, null, driveManifest, null);

                case HELD_BY_US:
                case HELD_BY_US_EXPIRED:
                    return ResolvedWorld.of(sub, State.LOCKED_BY_US,
                            status.lock, driveManifest, null);

                case HELD_BY_OTHER:
                    return ResolvedWorld.of(sub, State.LOCKED_BY_OTHER,
                            status.lock, driveManifest, null);

                case STALE: {
                    // Stale lock — check if the previous holder pushed before going offline.
                    // If manifest.generatedAt > lock.acquiredAt, they pushed. Clean to override.
                    // If manifest.generatedAt < lock.acquiredAt, they DIDN'T push. Conflict.
                    final boolean holderPushed = holderPushedBeforeGoingOffline(
                            status.lock, driveManifest);

                    if (holderPushed) {
                        // Also check if WE have local changes newer than the manifest.
                        final boolean localIsNewer = localIsNewerThanManifest(
                                localWorld, driveManifest, playerUuid);
                        if (localIsNewer) {
                            // Our local work vs. fresh Drive. That's a conflict too.
                            return ResolvedWorld.of(sub, State.STALE_LOCK_CONFLICT,
                                    status.lock, driveManifest, null);
                        }
                        return ResolvedWorld.of(sub, State.STALE_LOCK_CLEAN,
                                status.lock, driveManifest, null);
                    } else {
                        // Previous holder had offline work that was never pushed.
                        return ResolvedWorld.of(sub, State.STALE_LOCK_CONFLICT,
                                status.lock, driveManifest, null);
                    }
                }

                default:
                    return ResolvedWorld.of(sub, State.AVAILABLE, null, driveManifest, null);
            }
        } catch (final IOException e) {
            WorldShareMod.LOGGER.warn("WorldStateResolver: failed to resolve '{}': {}",
                    sub.displayName, e.getMessage());
            return ResolvedWorld.error(sub, "Drive error: " + e.getMessage());
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.error("WorldStateResolver: unexpected error for '{}'",
                    sub.displayName, t);
            return ResolvedWorld.error(sub, "Unexpected error: " + t.getMessage());
        }
    }

    /**
     * @return true if the stale lock holder pushed their work to Drive before
     *         going offline. Detection: manifest.generatedAt is AFTER
     *         lock.acquiredAt. If generatedAt is before acquiredAt, the lock
     *         was acquired AFTER the last push — so the holder played offline.
     */
    private static boolean holderPushedBeforeGoingOffline(final SessionLock lock,
                                                           final WorldManifest manifest) {
        if (manifest == null || manifest.generatedAt == null) return false;
        try {
            final Instant manifestTime = Instant.parse(manifest.generatedAt);
            final Instant lockAcquired = lock.lockedAtInstant();
            // Manifest was written AFTER the lock was acquired → they pushed.
            return manifestTime.isAfter(lockAcquired);
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * @return true if any local tracked file is newer than what the Drive
     *         manifest records. Uses SHA-256 comparison (not mtime) since
     *         mtime is unreliable across machines and OS moves.
     *
     *         A fast approximation: compare the drive manifest file count vs
     *         local file count, and check if any local file's hash differs
     *         from the drive manifest. We reuse WorldFileScanner for this.
     */
    private static boolean localIsNewerThanManifest(final Path localWorld,
                                                     final WorldManifest driveManifest,
                                                     final UUID playerUuid) {
        if (driveManifest == null) return true; // no drive manifest = local is definitely newer
        try {
            final WorldManifest local = WorldFileScanner.scan(localWorld, playerUuid);
            // Any file that differs in hash = local is newer (or different).
            for (final var entry : local.files().entrySet()) {
                final WorldManifest.Entry driveEntry = driveManifest.get(entry.getKey());
                if (driveEntry == null) return true; // local-only file
                if (!entry.getValue().sha256.equals(driveEntry.sha256)) return true;
            }
            return false;
        } catch (final Exception e) {
            // If we can't scan, assume dirty — better safe than sorry.
            return true;
        }
    }

    private static WorldManifest readManifest(final DriveClient client,
                                              final String folderId) {
        try {
            final String manifestId = client.findFileByName(
                    SyncEngine.MANIFEST_FILENAME, folderId);
            if (manifestId == null) return null;
            final String json = client.readText(manifestId);
            return WorldManifest.fromJson(json);
        } catch (final Exception e) {
            WorldShareMod.LOGGER.debug("WorldStateResolver: couldn't read manifest: {}",
                    e.getMessage());
            return null;
        }
    }

    private static Path savesDir() {
        return WorldSharePaths.gameDir().resolve("saves");
    }
}
