package com.worldshare.mod.sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks whether a push or pull is currently in flight.
 *
 * <p>Exists for one reason: the JVM shutdown hook in
 * {@code CloudModule} releases our session lock on the way out, and doing that
 * while a push is half-finished is actively harmful. {@code SyncEngine.push}
 * uploads every dirty bucket archive and only then commits the manifest, so a
 * process that dies in between leaves Drive holding archives whose contents the
 * published manifest does not describe. Releasing the lock at that moment invites
 * the other player straight into that state, where their pull fails verification.
 *
 * <p>Leaving the lock held instead makes the situation self-healing: other players
 * see {@code LOCKED_BY_OTHER} and stay out, and when the interrupted player reopens
 * the world they hold the lock ({@code LOCKED_BY_US} - "Resume"), push again, and
 * the commit that never happened happens.
 *
 * <p>A counter rather than a flag because push and pull are separate entry points
 * and nothing prevents a future caller from nesting or overlapping them; a boolean
 * would let the inner one's completion clear the outer one's guard.
 *
 * <p>Deliberately Minecraft-free and dependency-free, so it can be read from the
 * shutdown hook - which runs while the game is already tearing down and cannot
 * safely touch much else.
 */
public final class SyncActivity {

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /**
     * True once a push has started replacing bucket archives and before it has
     * published the matching manifest.
     *
     * <p>Separate from {@link #isSyncing()} because the damage outlives the
     * transfer. A push that uploads three archives and then fails on the fourth
     * stops being "in flight" immediately, but Drive is left holding archives whose
     * contents the published manifest does not describe - and releasing the lock in
     * that state is exactly as harmful as releasing it mid-upload.
     *
     * <p>Session-scoped on purpose. It is only consulted alongside
     * {@code LockManager.weHoldLock()}, which is itself in-memory and false after a
     * restart, so a stale value cannot outlive the lock it guards.
     */
    private static volatile boolean remoteAheadOfManifest = false;

    private SyncActivity() {}

    /** Call on entry to a push or pull. Always pair with {@link #end()} in a finally. */
    public static void begin() {
        IN_FLIGHT.incrementAndGet();
    }

    /** Call on exit from a push or pull, success or failure. */
    public static void end() {
        IN_FLIGHT.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    /** @return true if at least one push or pull has not finished. */
    public static boolean isSyncing() {
        return IN_FLIGHT.get() > 0;
    }

    /** Call before the first bucket archive of a push is replaced on Drive. */
    public static void markRemoteAheadOfManifest() {
        remoteAheadOfManifest = true;
    }

    /** Call once the manifest describing those archives has been published. */
    public static void clearRemoteAheadOfManifest() {
        remoteAheadOfManifest = false;
    }

    /**
     * @return true if Drive holds archives the published manifest doesn't describe,
     *         meaning the lock must not be handed to anyone else yet
     */
    public static boolean remoteAheadOfManifest() {
        return remoteAheadOfManifest;
    }

    /**
     * @return true if releasing the session lock right now would expose another
     *         player to a half-finished push
     */
    public static boolean lockMustBeHeld() {
        return isSyncing() || remoteAheadOfManifest;
    }
}
