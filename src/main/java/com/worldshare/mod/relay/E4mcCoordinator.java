package com.worldshare.mod.relay;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.config.WorldSubscription;
import com.worldshare.mod.sync.WorldContext;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates the live co-op (e4mc) flow for both host and guest.
 *
 * <p><b>Host side:</b> {@code /worldshare invite} calls {@link #startHosting()}.
 * A Log4j2 appender watches e4mc's logger for "Domain assigned: X", extracts
 * the relay domain, and writes {@code presence.json} to the world's Drive folder.
 * Presence is refreshed every 60s and deleted on server stop.
 *
 * <p><b>Guest side:</b> When the title screen opens, we poll ALL subscribed
 * Drive folders for {@code presence.json}. If any are live, we show
 * {@link JoinPromptScreen} for the first one found.
 *
 * <p><b>M5 changes:</b>
 * <ul>
 *   <li>Host: Drive folder ID now read from the current world's
 *       {@code worldshare-link.json} (not global config)</li>
 *   <li>Guest: polls all subscribed worlds, not just the global folder</li>
 * </ul>
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} from {@code UiModule.init()}.
 */
public final class E4mcCoordinator {

    private static final long PRESENCE_REFRESH_SECONDS = 60L;

    // ---- Host state ----
    private static volatile boolean isHosting = false;
    private static volatile String presenceFileId = null;
    private static volatile String hostingFolderId = null;   // M5: explicit, not from global config
    private static volatile String currentDomain = null;
    private static volatile ScheduledExecutorService refreshExecutor = null;
    private static volatile ScheduledFuture<?> refreshTask = null;
    private static volatile AbstractAppender logAppender = null;

    // ---- Guest state ----
    private static final AtomicBoolean promptShownThisSession = new AtomicBoolean(false);

    private E4mcCoordinator() {}

    public static void init() {
        WorldShareMod.LOGGER.info("E4mcCoordinator initialized.");
    }

    // ---- Host API ----

    public static void startHosting() {
        if (isHosting) {
            WorldShareMod.LOGGER.warn("E4mcCoordinator: startHosting() called but already hosting");
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: startHosting() - no singleplayer server running");
            return;
        }

        // M5: read folder ID from the current world's link file.
        final WorldContext.CurrentWorld current = WorldContext.current().orElse(null);
        if (current == null) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: startHosting() - no world context available");
            return;
        }
        final String folderId = WorldLink.readFolderId(current.worldRoot);
        if (folderId == null) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: startHosting() - world '{}' has no Drive link; "
                    + "run /worldshare setfolder first", current.name);
            return;
        }

        isHosting = true;
        currentDomain = null;
        presenceFileId = null;
        hostingFolderId = folderId;

        attachLogAppender();

        mc.execute(() -> {
            final boolean ok = mc.getSingleplayerServer().publishServer(null, false, 0);
            WorldShareMod.LOGGER.info(
                    "E4mcCoordinator: publishServer() = {} - waiting for domain...", ok);
        });
    }

    /**
     * Called from {@code AutoSyncListener.onServerStopping}. Detaches the log
     * appender, cancels the refresh scheduler, and deletes presence.json.
     */
    public static void stopHostingIfActive() {
        if (!isHosting) return;

        // Detach first to stop any in-flight domain capture.
        detachLogAppender();

        isHosting = false;
        final String fileId = presenceFileId;
        presenceFileId = null;
        currentDomain = null;
        hostingFolderId = null;
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

    // ---- Guest-side event handler ----

    /**
     * M5: On title screen, poll ALL subscribed worlds for a live presence.
     * Shows JoinPromptScreen for the first active session found.
     */
    @SubscribeEvent
    public static void onTitleScreen(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        if (promptShownThisSession.get()) return;
        if (SubscriptionStore.get().isEmpty()) return;

        final Thread poller = new Thread(() -> {
            try {
                final DriveClient client = CloudModule.driveClient();
                final List<WorldSubscription> subs = SubscriptionStore.get().all();

                for (final WorldSubscription sub : subs) {
                    if (promptShownThisSession.get()) break;

                    // First: check for live presence
                    final String fileId = client.findFileByName(
                            PresenceFile.FILENAME, sub.driveFolderId);
                    if (fileId != null) {
                        final PresenceFile presence = PresenceFile.fromJson(
                                client.readText(fileId));
                        if (!presence.isStale()) {
                            if (promptShownThisSession.compareAndSet(false, true)) {
                                WorldShareMod.LOGGER.info(
                                        "E4mcCoordinator: live session in '{}' (host: {}, link: {})",
                                        sub.displayName, presence.host, presence.e4mc_link);
                                Minecraft.getInstance().execute(() ->
                                        Minecraft.getInstance().setScreen(
                                                new JoinPromptScreen(presence)));
                                break;
                            }
                        }
                    }
                }
            } catch (final IOException e) {
                WorldShareMod.LOGGER.debug(
                        "E4mcCoordinator: presence poll failed (offline?): {}",
                        e.getMessage());
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn(
                        "E4mcCoordinator: unexpected error during presence poll", t);
            }
        }, "WorldShare-PresencePoller");
        poller.setDaemon(true);
        poller.start();
    }

    // ---- Log appender (domain capture) ----

    private static synchronized void attachLogAppender() {
        if (logAppender != null) return;
        try {
            final AbstractAppender appender = new AbstractAppender(
                    "WorldShareE4mcDomainCapture",
                    null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(final LogEvent event) {
                    try {
                        if (!isHosting || currentDomain != null) return;
                        final String msg = event.getMessage().getFormattedMessage();
                        if (msg == null) return;
                        final int idx = msg.indexOf("Domain assigned:");
                        if (idx < 0) return;
                        final String domain =
                                msg.substring(idx + "Domain assigned:".length()).trim();
                        if (domain.isBlank() || !domain.contains(".e4mc.link")) return;

                        currentDomain = domain;
                        WorldShareMod.LOGGER.info(
                                "E4mcCoordinator: domain captured via log appender - {}", domain);

                        final String folderId = hostingFolderId;
                        if (folderId != null) {
                            CloudModule.executor().submit(
                                    () -> writeOrRefreshPresence(domain, folderId));
                            startRefreshScheduler(domain, folderId);
                        }
                    } catch (final Throwable t) {
                        System.err.println("E4mcCoordinator log appender error: " + t);
                    }
                }
            };
            appender.start();
            final Logger e4mcLogger = (Logger) LogManager.getLogger("e4mc");
            e4mcLogger.addAppender(appender);
            logAppender = appender;
            WorldShareMod.LOGGER.info(
                    "E4mcCoordinator: log appender attached to 'e4mc' logger");
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.error(
                    "E4mcCoordinator: failed to attach log appender", t);
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
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: error detaching log appender", t);
        }
        logAppender = null;
        WorldShareMod.LOGGER.info("E4mcCoordinator: log appender detached");
    }

    // ---- Presence write/refresh ----

    private static void writeOrRefreshPresence(final String domain,
                                               final String folderId) {
        try {
            final DriveClient client = CloudModule.driveClient();
            final String rawName = com.worldshare.mod.config.WorldShareConfig
                    .get().playerDisplayName.get();
            final String hostName = (rawName != null && !rawName.isBlank())
                    ? rawName : "Host";
            final PresenceFile presence = PresenceFile.create(hostName, domain);

            final String existingId = (presenceFileId != null)
                    ? presenceFileId
                    : client.findFileByName(PresenceFile.FILENAME, folderId);

            final String fileId = client.writeText(
                    existingId, PresenceFile.FILENAME, folderId,
                    presence.toJson(), DriveClient.MIME_TYPE_JSON);

            presenceFileId = fileId;
            WorldShareMod.LOGGER.debug(
                    "E4mcCoordinator: wrote presence.json (id {})", fileId);
        } catch (final IOException e) {
            WorldShareMod.LOGGER.warn(
                    "E4mcCoordinator: failed to write presence.json: {}", e.getMessage());
        }
    }

    private static void startRefreshScheduler(final String domain,
                                              final String folderId) {
        stopRefreshScheduler();
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "WorldShare-PresenceRefresh");
            t.setDaemon(true);
            return t;
        });
        refreshTask = refreshExecutor.scheduleAtFixedRate(
                () -> CloudModule.executor().submit(
                        () -> writeOrRefreshPresence(domain, folderId)),
                PRESENCE_REFRESH_SECONDS,
                PRESENCE_REFRESH_SECONDS,
                TimeUnit.SECONDS);
        WorldShareMod.LOGGER.info(
                "E4mcCoordinator: presence refresh scheduled every {}s",
                PRESENCE_REFRESH_SECONDS);
    }

    private static void stopRefreshScheduler() {
        if (refreshTask != null) { refreshTask.cancel(false); refreshTask = null; }
        if (refreshExecutor != null) { refreshExecutor.shutdownNow(); refreshExecutor = null; }
    }
}
