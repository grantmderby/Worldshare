package com.worldshare.mod.relay;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.ui.JoinPromptScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates the live co-op (e4mc) flow for both the host and guest.
 *
 * <p><b>Host side:</b>
 * <ol>
 *   <li>{@code /worldshare invite} calls {@link #startHosting()}</li>
 *   <li>A Log4j2 appender is attached to e4mc's logger to capture the
 *       relay domain when e4mc logs "Domain assigned: ..."</li>
 *   <li>{@code publishServer()} opens the world to LAN; e4mc picks it up</li>
 *   <li>When the appender catches the domain, presence.json is written to Drive</li>
 *   <li>Presence is refreshed every 60 seconds until server stops</li>
 *   <li>{@link #stopHostingIfActive} (called from AutoSyncListener.onServerStopping)
 *       deletes presence.json and detaches the appender</li>
 * </ol>
 *
 * <p><b>Guest side:</b>
 * <ol>
 *   <li>{@link #onTitleScreen} fires when TitleScreen is shown</li>
 *   <li>Polls Drive once for {@code presence.json}</li>
 *   <li>If found and not stale, shows {@link JoinPromptScreen}</li>
 * </ol>
 *
 * <p><b>Why a log appender for domain capture?</b> e4mc only exposes the
 * assigned relay domain via its log output - it's not stored in any field
 * on QuiclimeSession, and ClientChatReceivedEvent (and its System subtype)
 * don't fire for the chat message in NeoForge 1.21.1. The log appender is
 * the most reliable capture point since e4mc's "Domain assigned: X" log
 * line is part of their stable output.
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} from {@code UiModule.init()}
 * during client setup (so the registration happens on the client side only,
 * after Minecraft is fully initialized).
 */
public final class E4mcCoordinator {

    private static final long PRESENCE_REFRESH_SECONDS = 60L;

    // ---- Host state ----
    private static volatile boolean isHosting = false;
    private static volatile String presenceFileId = null;
    private static volatile String currentDomain = null;
    private static volatile ScheduledExecutorService refreshExecutor = null;
    private static volatile ScheduledFuture<?> refreshTask = null;
    private static volatile AbstractAppender logAppender = null;

    // ---- Guest state ----
    /** Prevents showing the join prompt more than once per game session. */
    private static final AtomicBoolean promptShownThisSession = new AtomicBoolean(false);

    private E4mcCoordinator() {}

    public static void init() {
        WorldShareMod.LOGGER.info("E4mcCoordinator initialized.");
    }

    // ---- Host API ----

    /**
     * Called from {@code /worldshare invite}. Attaches a log appender to
     * e4mc's logger, then opens the world to LAN. The domain arrives
     * asynchronously when e4mc's "Domain assigned: ..." log line is captured.
     */
    public static void startHosting() {
        if (isHosting) {
            WorldShareMod.LOGGER.warn("E4mcCoordinator: startHosting() called but already hosting");
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            WorldShareMod.LOGGER.warn("E4mcCoordinator: startHosting() - no singleplayer server running");
            return;
        }

        isHosting = true;
        currentDomain = null;
        presenceFileId = null;

        // Attach the log appender BEFORE publishServer so we don't miss the
        // "Domain assigned:" log line that fires within milliseconds.
        attachLogAppender();

        // publishServer must run on the main thread.
        // Port 0 = OS picks a port; e4mc replaces it with the relay address anyway.
        mc.execute(() -> {
            final boolean ok = mc.getSingleplayerServer().publishServer(null, false, 0);
            WorldShareMod.LOGGER.info(
                    "E4mcCoordinator: publishServer() = {} - waiting for e4mc domain via log appender...", ok);
        });
    }

    /**
     * Cleans up presence.json and detaches the log appender when the server
     * stops. Called from {@code AutoSyncListener.onServerStopping} since
     * server-side events register more reliably from that path.
     *
     * <p>Order matters: the log appender is detached FIRST so an in-flight
     * "Domain assigned" callback can't fire after we've started cleanup
     * (which would leak a presence.json on Drive).
     */
    public static void stopHostingIfActive() {
        if (!isHosting) return;

        // Detach the appender first so it can't capture a domain after this point.
        // The appender's own check (`if (!isHosting || currentDomain != null) return;`)
        // is belt-and-suspenders for any callback already in flight.
        detachLogAppender();

        isHosting = false;
        final String fileId = presenceFileId;
        presenceFileId = null;
        currentDomain = null;
        stopRefreshScheduler();

        if (fileId == null) return;

        CloudModule.executor().submit(() -> {
            try {
                CloudModule.driveClient().deleteFile(fileId);
                WorldShareMod.LOGGER.info(
                        "E4mcCoordinator: deleted presence.json (id {})", fileId);
            } catch (final IOException e) {
                WorldShareMod.LOGGER.warn(
                        "E4mcCoordinator: failed to delete presence.json: {}", e.getMessage());
            }
        });
    }

    // ---- Event handlers (static — registered as class on NeoForge.EVENT_BUS) ----

    /**
     * Guest-side: when the title screen opens, poll Drive once for presence.json.
     * If found and fresh, show {@link JoinPromptScreen}.
     */
    @SubscribeEvent
    public static void onTitleScreen(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        if (promptShownThisSession.get()) return;

        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) return;

        final Thread poller = new Thread(() -> {
            try {
                final DriveClient client = CloudModule.driveClient();
                final String fileId = client.findFileByName(PresenceFile.FILENAME, folderId);
                if (fileId == null) {
                    WorldShareMod.LOGGER.debug("E4mcCoordinator: no presence.json on Drive");
                    return;
                }

                final PresenceFile presence = PresenceFile.fromJson(client.readText(fileId));

                if (presence.isStale()) {
                    WorldShareMod.LOGGER.info("E4mcCoordinator: presence.json is stale - ignoring");
                    return;
                }

                if (promptShownThisSession.compareAndSet(false, true)) {
                    WorldShareMod.LOGGER.info(
                            "E4mcCoordinator: active session found (host: {}, link: {}) - showing prompt",
                            presence.host, presence.e4mc_link);
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new JoinPromptScreen(presence)));
                }
            } catch (final IOException e) {
                WorldShareMod.LOGGER.debug(
                        "E4mcCoordinator: presence poll failed (offline?): {}", e.getMessage());
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn(
                        "E4mcCoordinator: unexpected error during presence poll", t);
            }
        }, "WorldShare-PresencePoller");
        poller.setDaemon(true);
        poller.start();
    }

    // ---- Log appender (domain capture) ----

    /**
     * Attaches a Log4j2 appender that watches e4mc's logger for the
     * "Domain assigned: X" message.
     */
    private static synchronized void attachLogAppender() {
        if (logAppender != null) return;

        try {
            final AbstractAppender appender = new AbstractAppender(
                    "WorldShareE4mcDomainCapture",
                    null,
                    null,
                    false,
                    Property.EMPTY_ARRAY) {
                @Override
                public void append(final LogEvent event) {
                    try {
                        // Belt-and-suspenders: if stopHostingIfActive has run,
                        // isHosting is false — don't capture.
                        if (!isHosting || currentDomain != null) return;
                        final String msg = event.getMessage().getFormattedMessage();
                        if (msg == null) return;

                        // e4mc logs: "Domain assigned: antivirus-nuttiness.na.e4mc.link"
                        final int idx = msg.indexOf("Domain assigned:");
                        if (idx < 0) return;

                        final String domain = msg.substring(idx + "Domain assigned:".length()).trim();
                        if (domain.isBlank() || !domain.contains(".e4mc.link")) return;

                        currentDomain = domain;
                        WorldShareMod.LOGGER.info(
                                "E4mcCoordinator: domain captured via log appender - {}", domain);

                        CloudModule.executor().submit(() -> writeOrRefreshPresence(domain));
                        startRefreshScheduler(domain);
                    } catch (final Throwable t) {
                        // NEVER let an appender throw - it would break logging globally.
                        // Use stderr so we don't recurse through the logger.
                        System.err.println("E4mcCoordinator log appender error: " + t);
                    }
                }
            };
            appender.start();

            final Logger e4mcLogger = (Logger) LogManager.getLogger("e4mc");
            e4mcLogger.addAppender(appender);

            logAppender = appender;
            WorldShareMod.LOGGER.info("E4mcCoordinator: log appender attached to 'e4mc' logger");
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.error("E4mcCoordinator: failed to attach log appender", t);
            logAppender = null;
        }
    }

    private static synchronized void detachLogAppender() {
        if (logAppender == null) return;
        try {
            final Logger e4mcLogger = (Logger) LogManager.getLogger("e4mc");
            e4mcLogger.removeAppender(logAppender);
            logAppender.stop();
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.warn("E4mcCoordinator: error detaching log appender", t);
        }
        logAppender = null;
        WorldShareMod.LOGGER.info("E4mcCoordinator: log appender detached");
    }

    // ---- Internal: presence write/refresh ----

    private static void writeOrRefreshPresence(final String domain) {
        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: no folder configured - can't write presence.json");
            return;
        }

        try {
            final DriveClient client = CloudModule.driveClient();
            final String rawName = WorldShareConfig.get().playerDisplayName.get();
            final String hostName = (rawName != null && !rawName.isBlank()) ? rawName : "Host";
            final PresenceFile presence = PresenceFile.create(hostName, domain);

            // Reuse the existing Drive file ID if we already wrote one this session.
            final String existingId = (presenceFileId != null)
                    ? presenceFileId
                    : client.findFileByName(PresenceFile.FILENAME, folderId);

            final String fileId = client.writeText(
                    existingId,
                    PresenceFile.FILENAME,
                    folderId,
                    presence.toJson(),
                    DriveClient.MIME_TYPE_JSON);

            presenceFileId = fileId;
            WorldShareMod.LOGGER.debug("E4mcCoordinator: wrote presence.json (id {})", fileId);
        } catch (final IOException e) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: failed to write presence.json: {}", e.getMessage());
        }
    }

    private static void startRefreshScheduler(final String domain) {
        stopRefreshScheduler();
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "WorldShare-PresenceRefresh");
            t.setDaemon(true);
            return t;
        });
        refreshTask = refreshExecutor.scheduleAtFixedRate(
                () -> CloudModule.executor().submit(() -> writeOrRefreshPresence(domain)),
                PRESENCE_REFRESH_SECONDS,
                PRESENCE_REFRESH_SECONDS,
                TimeUnit.SECONDS);
        WorldShareMod.LOGGER.info("E4mcCoordinator: presence refresh scheduled every {}s",
                PRESENCE_REFRESH_SECONDS);
    }

    private static void stopRefreshScheduler() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
            refreshExecutor = null;
        }
    }
}
