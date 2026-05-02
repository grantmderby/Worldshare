package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.relay.E4mcCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Listens for "Save and Quit to Title" events and triggers an automatic push
 * of the world to Drive once Minecraft has finished saving it.
 *
 * <p>Two events are involved:
 * <ul>
 *   <li>{@link ServerStoppingEvent}: fires while saves are still in progress.
 *       We capture the world path here while the server still knows it, and
 *       also notify {@link E4mcCoordinator} to clean up presence.json if it
 *       was hosting.</li>
 *   <li>{@link ServerStoppedEvent}: fires AFTER all final saves complete and
 *       the integrated server is shut down. This is the safe moment to push -
 *       Minecraft is no longer writing to the world files.</li>
 * </ul>
 */
public final class AutoSyncListener {

    private static volatile Path capturedWorldRoot;
    private static volatile UUID capturedPlayerUuid;
    private static volatile String capturedWorldName;
    private static volatile boolean serverHasStopped = false;
    private static volatile Object suppressionToken = null;

    private AutoSyncListener() {}

    public static void setSuppressionToken(final Object token) {
        suppressionToken = token;
    }

    public static void clearSuppressionToken() {
        suppressionToken = null;
    }

    public static boolean serverHasStopped() {
        return serverHasStopped;
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        WorldShareMod.LOGGER.info("AutoSync: onServerStopping fired");
        serverHasStopped = false;

        // Clean up e4mc presence if host was active. Done from this listener
        // (not E4mcCoordinator directly) because E4mcCoordinator is registered
        // during client setup, where ServerStoppingEvent doesn't reliably reach.
        E4mcCoordinator.stopHostingIfActive();

        try {
            final MinecraftServer server = event.getServer();
            final Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
                    .toAbsolutePath().normalize();
            final Path worldRoot = levelDat.getParent();
            if (worldRoot == null) return;

            UUID uuid = null;
            try {
                final Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getUser() != null) {
                    uuid = mc.getUser().getProfileId();
                    if (uuid == null && mc.getUser().getName() != null) {
                        uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + mc.getUser().getName())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn(
                        "AutoSync: couldn't determine player UUID; per-UUID files won't sync", t);
            }

            capturedWorldRoot = worldRoot;
            capturedPlayerUuid = uuid;
            capturedWorldName = worldRoot.getFileName() == null
                    ? "(unnamed)" : worldRoot.getFileName().toString();
            WorldShareMod.LOGGER.info(
                    "AutoSync: captured world path on ServerStopping: {} (uuid={})",
                    worldRoot, uuid);
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.warn("AutoSync onServerStopping failed", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        WorldShareMod.LOGGER.info("AutoSync: onServerStopped fired");
        serverHasStopped = true;

        final Path worldRoot = capturedWorldRoot;
        final UUID uuid = capturedPlayerUuid;
        final String worldName = capturedWorldName;
        capturedWorldRoot = null;
        capturedPlayerUuid = null;
        capturedWorldName = null;

        if (worldRoot == null) return;

        if (suppressionToken != null) {
            WorldShareMod.LOGGER.info(
                    "AutoSync: skipping auto-push for '{}' — screen-driven sync in progress",
                    worldName);
            return;
        }

        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            WorldShareMod.LOGGER.debug(
                    "AutoSync: no Drive folder configured; skipping auto-push of '{}'",
                    worldName);
            return;
        }

        WorldShareMod.LOGGER.info("AutoSync: starting auto-push of '{}' to Drive", worldName);
        notifyClientChat("§e[WorldShare] §fSaving '" + worldName + "' to Drive...");

        CloudModule.executor().submit(() -> {
            try {
                final SyncEngine.PushResult result = SyncEngine.push(
                        worldRoot, folderId, uuid, /*baseline*/ null);
                if (result.failed == 0) {
                    WorldShareMod.LOGGER.info(
                            "AutoSync: push complete for '{}': {} files, {} bytes",
                            worldName, result.uploaded, result.bytes);
                    notifyClientChat("§a[WorldShare] §f'" + worldName
                            + "' synced to Drive: " + result.uploaded + " files ("
                            + (result.bytes / (1024 * 1024)) + " MB).");
                } else {
                    WorldShareMod.LOGGER.warn(
                            "AutoSync: push had {} failures for '{}'; manifest not updated",
                            result.failed, worldName);
                    notifyClientChat("§c[WorldShare] §f" + result.failed
                            + " files failed to upload. Drive manifest NOT updated. "
                            + "Run /worldshare push next time you load this world.");
                }

                if (LockManager.weHoldLock()) {
                    LockManager.release();
                    WorldShareMod.LOGGER.info("AutoSync: released lock after push");
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error(
                        "AutoSync: push failed for '{}'; local files preserved",
                        worldName, t);
                notifyClientChat("§c[WorldShare] Auto-push failed: " + t.getMessage()
                        + ". Your local changes are preserved; retry with /worldshare push.");
            }
        });
    }

    private static void notifyClientChat(final String message) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    } else {
                        WorldShareMod.LOGGER.info("[chat-while-no-player] {}", message);
                    }
                });
            }
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("notifyClientChat failed silently", t);
        }
    }
}
