package com.worldshare.mod.cloud;

import com.google.api.client.auth.oauth2.Credential;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Entry point for the cloud module.
 *
 * <p>Owns two pieces of shared state that are expensive to create and therefore
 * lazily constructed on first use:
 * <ul>
 *   <li>The {@link DriveClient} - requires OAuth, which may open a browser</li>
 *   <li>The background {@link ExecutorService} for all network calls</li>
 * </ul>
 *
 * <p>Anything that wants to call Drive should:
 * <pre>
 *   CloudModule.executor().submit(() -&gt; {
 *       DriveClient client = CloudModule.driveClient();  // may block
 *       client.uploadFile(...);
 *   });
 * </pre>
 *
 * <p>Never call {@link #driveClient()} directly from the Minecraft main thread
 * — if this is the first call of the session, it will block on OAuth for as long
 * as the user takes to click through the browser, which freezes rendering.
 */
public final class CloudModule {

    private static final Object DRIVE_LOCK = new Object();
    private static volatile DriveClient driveClient;

    private static final ExecutorService EXECUTOR = createExecutor();

    private CloudModule() {
        // utility / module holder class
    }

    /** Called once during common setup. Does NOT trigger OAuth. */
    public static void init() {
        // Register a JVM shutdown hook so a graceful Minecraft exit (or even a
        // process kill in many cases) releases our lock. This is a safety net;
        // normal flow releases via ServerStoppingEvent in later milestones.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (LockManager.weHoldLock()) {
                    WorldShareMod.LOGGER.info(
                            "Shutdown hook: releasing held session.lock...");
                    LockManager.release();
                }
            } catch (final Throwable t) {
                // Can't do much here - the JVM is going down. Just log.
                WorldShareMod.LOGGER.warn("Shutdown hook lock release failed", t);
            }
        }, "WorldShare-Shutdown-Hook"));

        WorldShareMod.LOGGER.info(
                "CloudModule initialized. DriveClient will be lazily created on first use.");
    }

    /**
     * @return the shared executor for all Drive / network operations. Single-threaded,
     *         FIFO — this serialization also means we can't accidentally race on the
     *         Drive API from multiple threads.
     */
    public static ExecutorService executor() {
        return EXECUTOR;
    }

    /**
     * Get a ready-to-use DriveClient, creating one (and triggering OAuth) if needed.
     *
     * <p>Safe to call concurrently; the first caller will do the OAuth, others
     * will block on the monitor until it's done, then all see the same instance.
     *
     * <p><b>Do not call on the Minecraft main thread</b>. Always dispatch via
     * {@link #executor()}.
     *
     * <p>This overload uses the default URL presenter (system browser, with a
     * log-warn fallback). For Minecraft chat integration, use
     * {@link #driveClient(Consumer)}.
     */
    public static DriveClient driveClient() throws IOException {
        return driveClient(null);
    }

    /**
     * Get a ready-to-use DriveClient using a custom URL presenter for the
     * OAuth consent URL.
     *
     * @param urlPresenter receives the authorization URL on first-time auth,
     *                     or null to use the default system-browser opener.
     *                     Ignored entirely if a stored credential already exists.
     */
    public static DriveClient driveClient(final Consumer<String> urlPresenter) throws IOException {
        DriveClient local = driveClient;
        if (local != null) {
            return local;
        }
        synchronized (DRIVE_LOCK) {
            local = driveClient;
            if (local != null) {
                return local;
            }
            try {
                final Credential credential = urlPresenter == null
                        ? OAuthHelper.authorize()
                        : OAuthHelper.authorize(urlPresenter);
                local = DriveClient.fromCredential(credential);
            } catch (final GeneralSecurityException e) {
                throw new IOException("Failed to build trusted HTTP transport for Drive", e);
            }
            driveClient = local;
            return local;
        }
    }

    /**
     * Drop the cached DriveClient. The next call to {@link #driveClient()} will
     * re-authenticate. Used when the user explicitly signs out or when we detect
     * a token has been revoked.
     */
    public static void resetDriveClient() {
        synchronized (DRIVE_LOCK) {
            driveClient = null;
        }
        WorldShareMod.LOGGER.info("DriveClient reset; next use will re-authenticate.");
    }

    /** Called from mod shutdown to let background threads exit cleanly. */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }

    private static ExecutorService createExecutor() {
        final AtomicInteger counter = new AtomicInteger();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "WorldShare-Cloud-" + counter.incrementAndGet());
            t.setDaemon(true);
            // Lower-than-default priority: we don't want cloud IO to compete
            // with the MC render / tick threads for scheduling.
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
