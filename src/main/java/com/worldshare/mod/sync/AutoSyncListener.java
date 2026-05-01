package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldShareConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Listens for "Save and Quit to Title" events and triggers an automatic push
 * of the world to Drive once Minecraft has finished saving it.
 *
 * <p>Two events are involved:
 * <ul>
 *   <li>{@link ServerStoppingEvent}: fires while saves are still in progress.
 *       We capture the world path here while the server still knows it.</li>
 *   <li>{@link ServerStoppedEvent}: fires AFTER all final saves complete and
 *       the integrated server is shut down. This is the safe moment to push -
 *       Minecraft is no longer writing to the world files.</li>
 * </ul>
 *
 * <p>Pushing on {@code ServerStoppedEvent} guarantees a stable world snapshot
 * even without the file-snapshot fix in {@link SyncEngine}. The combination
 * of both gives belt-and-suspenders robustness.
 */
public final class AutoSyncListener {

    private static volatile Path capturedWorldRoot;
    private static volatile UUID capturedPlayerUuid;
    private static volatile String capturedWorldName;

    /**
     * Set true between ServerStoppedEvent and the next ServerStoppingEvent.
     * Used by SaveAndUploadScreen to know when it's safe to begin sync —
     * before this is true, Minecraft is still saving the world.
     */
    private static volatile boolean serverHasStopped = false;

    /**
     * If non-null when ServerStoppedEvent fires, AutoSyncListener will NOT
     * trigger its own push — it assumes the holder of the suppression token
     * (e.g. SaveAndUploadScreen) is going to handle the sync. Set BEFORE
     * the player exits the world (e.g. when the user clicks the Save and
     * Upload button in the pause menu).
     */
    private static volatile Object suppressionToken = null;

    private AutoSyncListener() {
        // utility class — registered as a static handler on the Forge event bus
    }

    /**
     * Called from {@link com.worldshare.mod.ui.SaveAndUploadScreen#launchFromPauseMenu()}
     * just before disconnecting the world. AutoSyncListener will skip its
     * own auto-push when the suppression token is set, leaving the screen
     * to do the push with progress UI.
     */
    public static void setSuppressionToken(final Object token) {
        suppressionToken = token;
    }

    public static void clearSuppressionToken() {
        suppressionToken = null;
    }

    /** @return true once ServerStoppedEvent has fired since the last ServerStopping. */
    public static boolean serverHasStopped() {
        return serverHasStopped;
    }

    /**
     * On ServerStopping, capture the world path before the server shuts down.
     * After ServerStoppedEvent fires, the server reference is gone so we
     * can't ask it for paths anymore.
     */
    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        WorldShareMod.LOGGER.info("AutoSync: onServerStopping fired");
        // Reset the stopped flag - we're now in the saving phase, not yet stopped.
        serverHasStopped = false;
        try {
            final MinecraftServer server = event.getServer();
            final Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
                    .toAbsolutePath().normalize();
            final Path worldRoot = levelDat.getParent();
            if (worldRoot == null) return;

            // Capture the player UUID while the client is still in scope.
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

    /**
     * On ServerStopped, the world is fully saved and no longer being written.
     * Either trigger our own push (X-button case) or signal the SaveAndUploadScreen
     * to do its push (Save-and-Upload case).
     */
    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        WorldShareMod.LOGGER.info("AutoSync: onServerStopped fired");
        // Mark ready BEFORE doing anything else, so any waiting screen can proceed.
        serverHasStopped = true;

        final Path worldRoot = capturedWorldRoot;
        final UUID uuid = capturedPlayerUuid;
        final String worldName = capturedWorldName;
        // Reset so a subsequent world doesn't accidentally reuse this state.
        capturedWorldRoot = null;
        capturedPlayerUuid = null;
        capturedWorldName = null;

        if (worldRoot == null) return;

        // If a screen has taken responsibility for the sync, don't double-push.
        if (suppressionToken != null) {
            WorldShareMod.LOGGER.info(
                    "AutoSync: skipping auto-push for '{}' — screen-driven sync in progress",
                    worldName);
            return;
        }

        // Only auto-push if the user has configured a Drive folder. If they're
        // playing a regular non-WorldShare world, this is a no-op.
        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            WorldShareMod.LOGGER.debug(
                    "AutoSync: no Drive folder configured; skipping auto-push of '{}'",
                    worldName);
            return;
        }

        WorldShareMod.LOGGER.info("AutoSync: starting auto-push of '{}' to Drive", worldName);
        notifyClientChat("§e[WorldShare] §fSaving '" + worldName + "' to Drive...");

        // Submit the push on the cloud executor. The user has already exited the world
        // and is back at the title screen; chat messages won't reach them, but log
        // and completion notifications will.
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

                // Auto-release the lock if we held one. The session is over.
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

    /**
     * Send a message to the client's chat from any thread. Safe even after
     * the world has unloaded — it will appear on the title screen as a toast
     * if no chat is available, or be logged otherwise.
     */
    private static void notifyClientChat(final String message) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    } else {
                        // No active player (we're at title screen). Use a toast notification.
                        // For now, just log - a proper toast UI is a bigger task for M5.
                        WorldShareMod.LOGGER.info("[chat-while-no-player] {}", message);
                    }
                });
            }
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("notifyClientChat failed silently", t);
        }
    }
}
