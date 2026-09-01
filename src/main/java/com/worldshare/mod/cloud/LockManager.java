package com.worldshare.mod.cloud;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.util.MachineId;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the session lock for one shared world.
 *
 * <p>The lock says "someone is using this world right now, don't load it." It
 * used to be its own Drive file, {@code session.lock}, whose mere presence meant
 * "taken" and whose deletion meant "free". Both halves of that had to change
 * under the {@code drive.file} scope:
 *
 * <ul>
 *   <li><b>It can't be found by name.</b> A narrow-scope token can't list a
 *       folder, so the lock is now a field inside the world's
 *       {@link ControlFile}, reached by the Drive file ID the user picked.</li>
 *   <li><b>It can't be deleted.</b> Deleting and recreating would mint a new
 *       Drive file ID that the other player's grant doesn't cover, silently
 *       cutting them off from the world. So releasing writes
 *       {@link SessionLock#STATUS_UNLOCKED} instead - absence is no longer a
 *       state the schema can express.</li>
 * </ul>
 *
 * <p><b>Typical flow for a session:</b>
 * <pre>
 *   LockManager.LockStatus status = LockManager.readStatus(remote);
 *   if (status.canAcquire()) {
 *       SessionLock ours = LockManager.acquire(remote);
 *       // ... play the world ...
 *       LockManager.release();
 *   } else {
 *       // someone else has it - prompt "Wait &amp; Retry"
 *   }
 * </pre>
 *
 * <p><b>Threading:</b> All methods block on network. They must not be called on
 * the Minecraft main thread. Dispatch via {@link CloudModule#executor()}.
 *
 * <p>The heartbeat runs on a private scheduled executor; it is started by
 * {@link #acquire} and stopped by {@link #release}.
 */
public final class LockManager {

    /** How often we refresh the lock's expiry while we hold it. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(15);

    /**
     * Number of consecutive heartbeat failures before we post a chat warning
     * to the user. With 15-minute intervals, 2 failures means we've been
     * offline for ~30 minutes.
     */
    private static final int OFFLINE_WARNING_THRESHOLD = 2;

    /**
     * After the initial offline warning, repeat the warning every Nth heartbeat
     * cycle while still offline. With 15-min interval and N=2, that's every 30 min.
     */
    private static final int OFFLINE_REWARN_EVERY = 2;

    /** Heartbeat-failure counter. Reset to 0 on successful heartbeat. */
    private static volatile int consecutiveHeartbeatFailures = 0;

    /** Tracks whether we've shown the user the offline warning at least once during current outage. */
    private static volatile boolean offlineWarningShown = false;

    /** ScheduledExecutor for heartbeats. Lazily created on first acquire. */
    private static final Object HEARTBEAT_LOCK = new Object();
    private static ScheduledExecutorService heartbeatExecutor;
    private static ScheduledFuture<?> activeHeartbeat;

    /**
     * The world whose lock we currently hold, or null if we hold none.
     *
     * <p>Replaces the old pair of lock-file ID and folder ID. We keep the whole
     * {@link RemoteFileSet} because the heartbeat needs both the control file ID
     * and the bucket count to write through {@link ControlFileClient#update}.
     */
    private static volatile RemoteFileSet heldWorld;

    private LockManager() {
        // utility class
    }

    // ----- Public API -----

    /**
     * Query the current lock state of a shared world without modifying it.
     *
     * @param remote the world's Drive file set
     * @return the current {@link LockStatus}
     */
    public static LockStatus readStatus(final RemoteFileSet remote) throws IOException {
        final SessionLock lock = readLock(remote);
        if (lock == null || lock.isUnlocked()) {
            return new LockStatus(LockState.FREE, lock);
        }

        final Instant now = Instant.now();
        final boolean expired = lock.isExpired(now);
        final boolean ours = lock.isOwnedBy(MachineId.get());

        final LockState state;
        if (ours) {
            state = expired ? LockState.HELD_BY_US_EXPIRED : LockState.HELD_BY_US;
        } else {
            state = expired ? LockState.STALE : LockState.HELD_BY_OTHER;
        }
        return new LockStatus(state, lock);
    }

    /**
     * Read the lock out of the world's control file.
     *
     * @return the SessionLock, or null if nobody has pushed to this world yet
     *         (the control file is still an empty placeholder)
     */
    public static SessionLock readLock(final RemoteFileSet remote) throws IOException {
        Objects.requireNonNull(remote, "remote");
        final ControlFile control = ControlFileClient.read(remote.controlFileId);
        return (control == null) ? null : control.lockOrUnlocked();
    }

    /**
     * Acquire the lock for the given world, overwriting any existing lock.
     *
     * <p>The caller is responsible for checking {@link #readStatus} first
     * and deciding if overwriting is appropriate (e.g. only when the lock
     * is FREE, STALE, or HELD_BY_US_EXPIRED - not when HELD_BY_OTHER).
     *
     * <p>Also starts the heartbeat thread. Make sure to call {@link #release}
     * before the JVM exits.
     *
     * @return the SessionLock we just wrote
     */
    public static SessionLock acquire(final RemoteFileSet remote) throws IOException {
        Objects.requireNonNull(remote, "remote");

        final String holderName = resolveHolderName();
        final String machineId = MachineId.get();
        final Instant now = Instant.now();
        final Duration expiresAfter = Duration.ofMinutes(
                WorldShareConfig.get().lockExpiryMinutes.get());
        final SessionLock lock = SessionLock.newAcquired(
                holderName, machineId, now, expiresAfter);

        // Writes through the same monitor the heartbeat and the sync commit use, so
        // taking the lock can't race with a manifest write already in flight.
        ControlFileClient.update(remote.controlFileId, remote.bucketCount,
                control -> control.lock = lock);

        heldWorld = remote;
        // Reset offline tracking for the new session.
        consecutiveHeartbeatFailures = 0;
        offlineWarningShown = false;
        startHeartbeat();

        WorldShareMod.LOGGER.info("Acquired session lock in control file {} as '{}' on machine {}",
                remote.controlFileId, holderName, machineId);
        return lock;
    }

    /**
     * Release the lock we hold: stop the heartbeat and mark the world unlocked.
     * No-op if we don't currently hold one.
     *
     * <p>Note this <em>writes</em> rather than deletes. See the class note - a
     * deleted control file would come back with a Drive ID nobody else can reach.
     */
    public static void release() throws IOException {
        stopHeartbeat();

        final RemoteFileSet remote = heldWorld;
        heldWorld = null;

        if (remote == null) {
            WorldShareMod.LOGGER.debug("release() called but we don't hold a lock");
            return;
        }

        try {
            ControlFileClient.update(remote.controlFileId, remote.bucketCount, control -> {
                // Only stand down if it's still ours. Somebody may have overridden a
                // lock they believed was stale; stamping "unlocked" over their live
                // session would hand the world to a third party mid-play.
                final SessionLock current = control.lockOrUnlocked();
                if (current.isUnlocked() || current.isOwnedBy(MachineId.get())) {
                    control.lock = SessionLock.unlocked(Instant.now());
                } else {
                    WorldShareMod.LOGGER.warn(
                            "release: lock now held by '{}', leaving it alone", current.holderName);
                }
            });
            WorldShareMod.LOGGER.info("Released session lock in control file {}",
                    remote.controlFileId);
        } catch (final IOException e) {
            // Losing the release is survivable: the lock carries an expiry, and the
            // other player's staleness check will free it. Worth a warning, not a throw.
            WorldShareMod.LOGGER.warn("Could not release session lock in control file {}: {}",
                    remote.controlFileId, e.getMessage());
        }
    }

    /**
     * @return true if we hold a lock on <em>some</em> world.
     *
     * <p>Prefer {@link #weHoldLock(RemoteFileSet)} anywhere a particular world is
     * in question. This answers "is a lock held by this game", which is a different
     * and usually less useful question - see that method for what went wrong when
     * the two were treated as the same.
     */
    public static boolean weHoldLock() {
        return heldWorld != null;
    }

    /**
     * @return true if the lock we hold is <em>this</em> world's
     *
     * <p>{@link #heldWorld} is a single static, so the argument-less check cannot
     * tell one world from another. That mattered as soon as it became possible to
     * reach the title screen still holding a lock - by backgrounding an upload, or
     * by a failed push deliberately keeping it. Opening a second shared world then
     * found {@code weHoldLock()} true, suppressed its "no lock held, changes will
     * not be saved" warning, and let the session run under an assumption that was
     * false. Nothing was corrupted, because push re-reads the lock from Drive before
     * writing, but the player only discovered it when their save was refused.
     */
    public static boolean weHoldLock(final RemoteFileSet remote) {
        final RemoteFileSet held = heldWorld;
        return held != null
                && remote != null
                && held.controlFileId != null
                && held.controlFileId.equals(remote.controlFileId);
    }

    /** The control file of whatever world we hold a lock on, or null. */
    public static String heldControlFileId() {
        final RemoteFileSet held = heldWorld;
        return held == null ? null : held.controlFileId;
    }

    // ----- Heartbeat -----

    /**
     * Refresh the lock's expiry and heartbeat timestamps in the control file.
     * Called automatically by the heartbeat scheduler; exposed for tests and the
     * {@code /worldshare heartbeat} debug command.
     */
    public static void heartbeat() throws IOException {
        final RemoteFileSet remote = heldWorld;
        if (remote == null) {
            return;
        }

        // Set from inside the mutator when we discover the lock isn't ours any more.
        // The mutator can't abort the write on its own, so it signals out instead and
        // leaves the other player's lock untouched.
        final AtomicBoolean lostIt = new AtomicBoolean(false);
        final String[] stealer = new String[1];

        ControlFileClient.update(remote.controlFileId, remote.bucketCount, control -> {
            final SessionLock current = control.lockOrUnlocked();
            if (!current.isOwnedBy(MachineId.get())) {
                lostIt.set(true);
                stealer[0] = current.holderName;
                return; // leave control.lock exactly as read
            }

            final Instant now = Instant.now();
            final Duration expiresAfter = Duration.ofMinutes(
                    WorldShareConfig.get().lockExpiryMinutes.get());
            current.lastHeartbeatAt = now.toString();
            current.expiresAt = now.plus(expiresAfter).toString();
            control.lock = current;
        });

        if (lostIt.get()) {
            final String who = stealer[0] != null ? stealer[0] : "another player";
            WorldShareMod.LOGGER.warn(
                    "Heartbeat: lock no longer owned by us (current holder: {}). Stopping heartbeat.",
                    who);
            postChatMessage("§c[WorldShare] [!] Your session lock was overridden by " + who + ".");
            postChatMessage("§c Your changes from this point on will NOT be saved to Drive.");
            postChatMessage("§7 Save and quit to exit cleanly. Local files preserved.");
            // And a toast, because chat scrolls. From here nothing this player does
            // can be saved, so the cost of missing the message is the whole session.
            com.worldshare.mod.util.PlayerNotice.alsoToast(
                    who + " took over this world's session lock. Your changes can no "
                            + "longer be saved to Drive - save and quit.");
            stopHeartbeat();
            heldWorld = null;
            return;
        }

        WorldShareMod.LOGGER.debug("Heartbeat refreshed session lock in control file {}",
                remote.controlFileId);
    }

    private static void startHeartbeat() {
        synchronized (HEARTBEAT_LOCK) {
            if (activeHeartbeat != null && !activeHeartbeat.isDone()) {
                return;
            }
            if (heartbeatExecutor == null) {
                heartbeatExecutor = createHeartbeatExecutor();
            }
            // First heartbeat runs after one interval (not immediately - we just wrote
            // the lock in acquire(), no need to update it right away).
            activeHeartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    LockManager::runHeartbeatSafely,
                    HEARTBEAT_INTERVAL.toMinutes(),
                    HEARTBEAT_INTERVAL.toMinutes(),
                    TimeUnit.MINUTES);
            WorldShareMod.LOGGER.info("Started lock heartbeat every {} minutes",
                    HEARTBEAT_INTERVAL.toMinutes());
        }
    }

    private static void stopHeartbeat() {
        synchronized (HEARTBEAT_LOCK) {
            if (activeHeartbeat != null) {
                activeHeartbeat.cancel(false);
                activeHeartbeat = null;
                WorldShareMod.LOGGER.info("Stopped lock heartbeat");
            }
        }
    }

    private static void runHeartbeatSafely() {
        try {
            heartbeat();
            // Success path. Reset failure tracking, and if we previously warned the user
            // they were offline, tell them we recovered.
            final int prevFailures = consecutiveHeartbeatFailures;
            consecutiveHeartbeatFailures = 0;
            if (offlineWarningShown && prevFailures > 0) {
                offlineWarningShown = false;
                WorldShareMod.LOGGER.info(
                        "Heartbeat recovered after {} consecutive failures", prevFailures);
                postChatMessage("§a[WorldShare] [OK] Reconnected to Drive. "
                        + "Your changes will sync at session end.");
                com.worldshare.mod.util.PlayerNotice.alsoToast(
                        "Reconnected to Drive. Your changes will sync at session end.");
            }
        } catch (final Throwable t) {
            // Never let a heartbeat exception propagate - it would kill the scheduler.
            consecutiveHeartbeatFailures++;
            WorldShareMod.LOGGER.error(
                    "Heartbeat failed (consecutive failure #{}); will retry at next interval",
                    consecutiveHeartbeatFailures, t);

            // Say something on the FIRST failure, not the second.
            //
            // Waiting for two meant half an hour of play before the player learned
            // their session might not be syncable. One heartbeat interval of doubt is
            // a far better trade than a second interval of false confidence, and a
            // heartbeat that fails is nearly always just a dropped connection - worth
            // saying plainly rather than dressing up.
            if (consecutiveHeartbeatFailures == 1) {
                com.worldshare.mod.util.PlayerNotice.alsoToast(
                        "Drive heartbeat failed - connection lost. Your changes are "
                                + "still saved locally.");
            }

            // Surface to the user once we cross the threshold, then re-warn periodically.
            if (consecutiveHeartbeatFailures == OFFLINE_WARNING_THRESHOLD) {
                offlineWarningShown = true;
                postChatMessage("§e[WorldShare] [!] Can't reach Drive. Your changes will "
                        + "sync when you reconnect.");
            } else if (consecutiveHeartbeatFailures > OFFLINE_WARNING_THRESHOLD
                    && (consecutiveHeartbeatFailures - OFFLINE_WARNING_THRESHOLD)
                    % OFFLINE_REWARN_EVERY == 0) {
                postChatMessage("§e[WorldShare] [!] Still offline. "
                        + "Your changes will sync when you reconnect.");
            }
        }
    }

    /**
     * Post a chat message to the local player. Best-effort: if no player is in
     * scope (e.g. we're at the title screen), this falls back to log only.
     * Marshals onto the render thread because chat operations require it.
     */
    private static void postChatMessage(final String text) {
        try {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                final var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
                } else {
                    WorldShareMod.LOGGER.info("(chat) {}", text);
                }
            });
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.warn("postChatMessage failed (logging instead): {}", text, t);
        }
    }

    private static ScheduledExecutorService createHeartbeatExecutor() {
        final AtomicInteger counter = new AtomicInteger();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "WorldShare-Heartbeat-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        final ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, factory);
        // If JVM shuts down, cancel pending tasks instead of waiting.
        exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return exec;
    }

    // ----- Helpers -----

    private static String resolveHolderName() {
        final String configured = WorldShareConfig.get().playerDisplayName.get();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        // Fall back to the signed-in Minecraft username, if available.
        try {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getUser() != null && mc.getUser().getName() != null) {
                return mc.getUser().getName();
            }
        } catch (final Throwable t) {
            // Not in a client context - that's fine.
        }
        return "Unknown Player";
    }

    // ----- Types -----

    /** Discrete states a lock can be in from our perspective. */
    public enum LockState {
        /** Nobody holds the lock. World is available. */
        FREE,
        /** Lock held by us and not expired. Normal case when we're playing. */
        HELD_BY_US,
        /** Lock held by us but expired. We probably crashed - resume. */
        HELD_BY_US_EXPIRED,
        /** Lock held by another machine, still valid. Block loading. */
        HELD_BY_OTHER,
        /** Lock held by another machine but expired. Offer override to user. */
        STALE
    }

    /** Combined state + data, returned by {@link #readStatus}. */
    public static final class LockStatus {
        public final LockState state;
        /**
         * The parsed lock. Unlike the old two-file layout this is usually non-null
         * even when the state is {@link LockState#FREE}, since "free" is now an
         * explicit {@code unlocked} record rather than a missing file. It is still
         * null for a world nobody has ever pushed to.
         */
        public final SessionLock lock;

        LockStatus(final LockState state, final SessionLock lock) {
            this.state = state;
            this.lock = lock;
        }

        public boolean isHeldByOther() {
            return state == LockState.HELD_BY_OTHER || state == LockState.STALE;
        }

        public boolean canAcquire() {
            return state == LockState.FREE
                    || state == LockState.STALE
                    || state == LockState.HELD_BY_US_EXPIRED;
        }
    }
}
