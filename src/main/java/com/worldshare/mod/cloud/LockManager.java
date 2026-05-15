package com.worldshare.mod.cloud;

import com.google.gson.JsonSyntaxException;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the {@code session.lock} file on Drive for one shared world.
 *
 * <p>The lock file lives at {@code <driveFolder>/session.lock}. Its presence
 * indicates "someone is using this world right now, don't load it." Its
 * absence means "world is free."
 *
 * <p><b>Typical flow for a session:</b>
 * <pre>
 *   LockManager.LockStatus status = LockManager.readStatus(folderId);
 *   if (status.canAcquire()) {
 *       SessionLock ours = LockManager.acquire(folderId);
 *       // ... play the world ...
 *       LockManager.release();
 *   } else {
 *       // someone else has it - prompt "Wait & Retry"
 *   }
 * </pre>
 *
 * <p><b>Threading:</b> All methods block on network. They must not be called
 * on the Minecraft main thread. Dispatch via {@link CloudModule#executor()}.
 *
 * <p>The heartbeat runs on a private scheduled executor; it is started by
 * {@link #acquire} and stopped by {@link #release}.
 */
public final class LockManager {

    /** Filename used on Drive for the lock. */
    public static final String LOCK_FILENAME = "session.lock";

    /** How often we refresh the lock's expires_at field while we hold it. */
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
     * Cached state: Drive file ID of the lock we currently hold (or null if
     * we don't hold one). We track this so the heartbeat and release don't
     * need to do another findFileByName() call each time.
     */
    private static volatile String heldLockFileId;
    private static volatile String heldLockFolderId;

    private LockManager() {
        // utility class
    }

    // ----- Public API -----

    /**
     * Query the current lock state of a shared world without modifying it.
     *
     * @param driveFolderId Drive folder ID of the shared world
     * @return the current {@link LockStatus}
     */
    public static LockStatus readStatus(final String driveFolderId) throws IOException {
        final SessionLock lock = readLock(driveFolderId);
        if (lock == null) {
            return new LockStatus(LockState.FREE, null);
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
     * Read the lock file, returning its parsed contents or null if it doesn't exist.
     *
     * @return the SessionLock, or null if no lock file is present on Drive
     */
    public static SessionLock readLock(final String driveFolderId) throws IOException {
        Objects.requireNonNull(driveFolderId, "driveFolderId");
        final DriveClient client = CloudModule.driveClient();
        final String fileId = client.findFileByName(LOCK_FILENAME, driveFolderId);
        if (fileId == null) {
            return null;
        }
        final String json = client.readText(fileId);
        try {
            return SessionLock.fromJson(json);
        } catch (final JsonSyntaxException e) {
            throw new IOException("session.lock on Drive is malformed: " + e.getMessage(), e);
        }
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
    public static SessionLock acquire(final String driveFolderId) throws IOException {
        Objects.requireNonNull(driveFolderId, "driveFolderId");
        final DriveClient client = CloudModule.driveClient();

        final String holderName = resolveHolderName();
        final String machineId = MachineId.get();
        final Instant now = Instant.now();
        final Duration expiresAfter = Duration.ofMinutes(
                WorldShareConfig.get().lockExpiryMinutes.get());
        final int cap = WorldShareConfig.get().playerCap.get();

        final SessionLock lock = SessionLock.newAcquired(
                holderName, machineId, now, expiresAfter, cap);

        // Overwrite existing lock file if present, else create a new one.
        final String existingId = client.findFileByName(LOCK_FILENAME, driveFolderId);
        final String fileId = client.writeText(
                existingId,
                LOCK_FILENAME,
                driveFolderId,
                lock.toJson(),
                DriveClient.MIME_TYPE_JSON);

        heldLockFileId = fileId;
        heldLockFolderId = driveFolderId;
        // Reset offline tracking for the new session.
        consecutiveHeartbeatFailures = 0;
        offlineWarningShown = false;
        startHeartbeat();

        WorldShareMod.LOGGER.info("Acquired session.lock (drive id {}) as '{}' on machine {}",
                fileId, holderName, machineId);
        return lock;
    }

    /**
     * Release the lock we hold. Stops the heartbeat and deletes the lock
     * file from Drive. No-op if we don't currently hold one.
     */
    public static void release() throws IOException {
        stopHeartbeat();

        final String fileId = heldLockFileId;
        final String folderId = heldLockFolderId;
        heldLockFileId = null;
        heldLockFolderId = null;

        if (fileId == null) {
            WorldShareMod.LOGGER.debug("release() called but we don't hold a lock");
            return;
        }

        final DriveClient client = CloudModule.driveClient();
        try {
            client.deleteFile(fileId);
            WorldShareMod.LOGGER.info("Released session.lock (drive id {}) from folder {}",
                    fileId, folderId);
        } catch (final IOException e) {
            // The file might have been deleted by someone overriding a stale lock.
            // Log and swallow - from our perspective, the lock is gone either way.
            WorldShareMod.LOGGER.warn(
                    "Could not delete session.lock (drive id {}) during release: {}",
                    fileId, e.getMessage());
        }
    }

    /** @return true if we currently hold a lock. */
    public static boolean weHoldLock() {
        return heldLockFileId != null;
    }

    // ----- Heartbeat -----

    /**
     * Refresh {@code expires_at} and {@code last_heartbeat_at} on the Drive lock.
     * Called automatically by the heartbeat scheduler; exposed for tests and the
     * {@code /worldshare heartbeat} debug command.
     */
    public static void heartbeat() throws IOException {
        final String folderId = heldLockFolderId;
        final String fileId = heldLockFileId;
        if (folderId == null || fileId == null) {
            return;
        }

        final DriveClient client = CloudModule.driveClient();
        // Re-read to preserve any fields we don't manage (e.g. relay_address, players_online)
        // that may have been written by other modules (M4).
        final SessionLock current;
        try {
            final String json = client.readText(fileId);
            current = SessionLock.fromJson(json);
        } catch (final IOException | JsonSyntaxException e) {
            WorldShareMod.LOGGER.warn("Heartbeat: couldn't re-read lock file; skipping", e);
            return;
        }

        // Safety: if the lock has been taken over by someone else while we thought we
        // held it, don't clobber their lock. Log and stop the heartbeat.
        if (!current.isOwnedBy(MachineId.get())) {
            WorldShareMod.LOGGER.warn("Heartbeat: lock no longer owned by us "
                    + "(current holder: {}). Stopping heartbeat.", current.holderName);
            final String stealer = current.holderName != null ? current.holderName : "another player";
            postChatMessage("§c[WorldShare] [!] Your session lock was overridden by " + stealer + ".");
            postChatMessage("§c Your changes from this point on will NOT be saved to Drive.");
            postChatMessage("§7 Save and quit to exit cleanly. Local files preserved.");
            stopHeartbeat();
            heldLockFileId = null;
            heldLockFolderId = null;
            return;
        }

        final Instant now = Instant.now();
        final Duration expiresAfter = Duration.ofMinutes(
                WorldShareConfig.get().lockExpiryMinutes.get());
        current.lastHeartbeatAt = now.toString();
        current.expiresAt = now.plus(expiresAfter).toString();

        client.writeText(fileId, LOCK_FILENAME, folderId, current.toJson(),
                DriveClient.MIME_TYPE_JSON);
        WorldShareMod.LOGGER.debug("Heartbeat refreshed session.lock expires_at -> {}",
                current.expiresAt);
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
            }
        } catch (final Throwable t) {
            // Never let a heartbeat exception propagate - it would kill the scheduler.
            consecutiveHeartbeatFailures++;
            WorldShareMod.LOGGER.error(
                    "Heartbeat failed (consecutive failure #{}); will retry at next interval",
                    consecutiveHeartbeatFailures, t);

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
        /** No lock file exists. World is available. */
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
        /** The parsed lock, or null if state is FREE. */
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
