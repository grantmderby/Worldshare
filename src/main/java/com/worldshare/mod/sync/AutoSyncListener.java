package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.relay.E4mcCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Listens for server lifecycle events and triggers automatic world sync.
 *
 * <p><b>M5 change:</b> The Drive folder ID is read from the world's
 * {@code worldshare-link.json} file (written by {@code /worldshare setDriveLink}
 * or by the Contributor Worlds download flow). Worlds without a link file are
 * silently skipped — they are not WorldShare worlds.
 *
 * <p><b>M5 addition:</b> {@link #onPlayerLoggedIn} fires when a world finishes
 * loading and warns the player if they are in a WorldShare-linked world without
 * holding the session lock. This catches the common case of a player opening a
 * WorldShare world from vanilla Singleplayer, bypassing the Contributor tab's
 * lock-then-pull flow.
 */
public final class AutoSyncListener {

    private static volatile Path capturedWorldRoot;
    private static volatile UUID capturedPlayerUuid;
    private static volatile String capturedWorldName;
    /**
     * The world's Drive file set, captured while the server is still stopping.
     *
     * <p>Captured rather than re-read on {@code onServerStopped} because by then
     * the world context is gone and the link file may no longer be resolvable.
     */
    private static volatile RemoteFileSet capturedRemote;
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

    /**
     * Fires when the integrated server finishes starting (world is loaded and
     * player is about to join). Warns the player if:
     * <ol>
     *   <li>The world has a {@code worldshare-link.json} (it's a WorldShare world)</li>
     *   <li>We do NOT currently hold the session lock</li>
     * </ol>
     *
     * <p>This catches the vanilla Singleplayer bypass case. If the player opened
     * via the Contributor Worlds tab, the tab acquires the lock before loading,
     * so {@link LockManager#weHoldLock()} will be true and the warning is skipped.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(final net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        // Only fire for the local singleplayer player, not for remote players
        // joining a hosted session.
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        final net.minecraft.server.MinecraftServer server = sp.getServer();
        if (server == null || !server.isSingleplayer()) return;

        try {
            final Path levelDat = server.getWorldPath(
                            net.minecraft.world.level.storage.LevelResource.LEVEL_DATA_FILE)
                    .toAbsolutePath().normalize();
            final Path worldRoot = levelDat.getParent();
            if (worldRoot == null) return;

            final WorldLink link = WorldLink.read(worldRoot);
            if (link == null) return; // Not a WorldShare world

            if (!LockManager.weHoldLock()) {
                // No lock — warn the player.
                WorldShareMod.LOGGER.warn(
                        "AutoSync: WorldShare world loaded without session lock (world: '{}')",
                        worldRoot.getFileName());
                notifyClientChat("§e[WorldShare] [!] No session lock held.");
                notifyClientChat("§e Changes made here will NOT be saved to Drive.");
                notifyClientChat("§c Your local copy may also be out of date with Drive.");
                notifyClientChat("§c Locking from here is blocked if Drive has newer changes.");
                notifyClientChat("§7 Save and quit, then open via Contributor Worlds for proper sync.");
                return;
            }

            // Lock is held — auto-open to LAN via e4mc so guests can join
            // without needing /worldshare invite.
            WorldShareMod.LOGGER.info(
                    "AutoSync: lock held, auto-opening world to LAN via e4mc");
            final Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                new Thread(() -> {
                    try { Thread.sleep(1500); } catch (final InterruptedException ignored) {}
                    mc.execute(() -> {
                        if (mc.getSingleplayerServer() != null) {
                            E4mcCoordinator.startHosting();
                        }
                    });
                }, "WorldShare-AutoInvite").start();
            });
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("AutoSync onPlayerLoggedIn check failed silently", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        WorldShareMod.LOGGER.info("AutoSync: onServerStopping fired");
        serverHasStopped = false;

        // Clean up e4mc presence if host was active.
        E4mcCoordinator.stopHostingIfActive();

        try {
            final MinecraftServer server = event.getServer();
            final Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
                    .toAbsolutePath().normalize();
            final Path worldRoot = levelDat.getParent();
            if (worldRoot == null) return;

            // Read the Drive file set from the world's own link file.
            final WorldLink link = WorldLink.read(worldRoot);
            final RemoteFileSet remote = (link != null) ? link.remote : null;

            if (remote == null) {
                WorldShareMod.LOGGER.debug(
                        "AutoSync: no usable WorldLink for '{}'; auto-push disabled for this world",
                        worldRoot.getFileName());
            }

            UUID uuid = null;
            try {
                final Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getUser() != null) {
                    uuid = mc.getUser().getProfileId();
                    if (uuid == null && mc.getUser().getName() != null) {
                        uuid = UUID.nameUUIDFromBytes(
                                ("OfflinePlayer:" + mc.getUser().getName())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn(
                        "AutoSync: couldn't determine player UUID; per-UUID files won't sync", t);
            }

            capturedWorldRoot = worldRoot;
            capturedPlayerUuid = uuid;
            capturedRemote = remote;
            capturedWorldName = worldRoot.getFileName() == null
                    ? "(unnamed)" : worldRoot.getFileName().toString();

            WorldShareMod.LOGGER.info(
                    "AutoSync: captured world path on ServerStopping: {} (uuid={}, world={})",
                    worldRoot, uuid,
                    remote != null ? remote.controlFileId : "not set up");

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
        final RemoteFileSet remote = capturedRemote;
        capturedWorldRoot = null;
        capturedPlayerUuid = null;
        capturedWorldName = null;
        capturedRemote = null;

        if (worldRoot == null) return;

        if (suppressionToken != null) {
            WorldShareMod.LOGGER.info(
                    "AutoSync: skipping auto-push for '{}' - screen-driven sync in progress",
                    worldName);
            return;
        }

        if (remote == null) {
            WorldShareMod.LOGGER.debug(
                    "AutoSync: '{}' isn't set up for sharing; skipping auto-push", worldName);
            return;
        }

// M5 safety: if no lock was held during this session (e.g. opened from
// vanilla Singleplayer), don't auto-push. The local copy may be out of
// date with Drive, and uploading it would risk overwriting another
// player's work.
        if (!LockManager.weHoldLock()) {
            WorldShareMod.LOGGER.info(
                    "AutoSync: no session lock held for '{}'; skipping auto-push "
                            + "(world was likely opened from Singleplayer)", worldName);
            notifyClientChat("§7[WorldShare] §fNo lock held for '" + worldName
                    + "' - changes were not uploaded to Drive.");
            return;
        }

        WorldShareMod.LOGGER.info("AutoSync: starting auto-push of '{}' to Drive", worldName);
        notifyClientChat("§e[WorldShare] §fSaving '" + worldName + "' to Drive...");

        CloudModule.executor().submit(() -> {
            try {
                final SyncEngine.PushResult result = SyncEngine.push(
                        worldRoot, remote, uuid);
                if (result.failed == 0) {
                    WorldShareMod.LOGGER.info(
                            "AutoSync: push complete for '{}': {} files, {} bytes",
                            worldName, result.uploaded, result.bytes);
                    // M7: refresh modpack.json if mod list changed.
                    try {
                        com.worldshare.mod.modmanager.ModManagerModule.generateAndUpload(remote);
                        WorldShareMod.LOGGER.info("AutoSync: modpack refreshed");
                    } catch (final Throwable modErr) {
                        WorldShareMod.LOGGER.warn(
                                "AutoSync: modpack refresh failed (non-fatal): {}", modErr.getMessage());
                    }
                    notifyClientChat("§a[WorldShare] §f'" + worldName
                            + "' synced to Drive: " + result.uploaded + " files ("
                            + (result.bytes / (1024 * 1024)) + " MB).");
                } else {
                    WorldShareMod.LOGGER.warn(
                            "AutoSync: push had {} failures for '{}'", result.failed, worldName);
                    notifyClientChat("§c[WorldShare] §f" + result.failed
                            + " files failed to upload. Run /worldshare push to retry.");
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
                        + ". Local changes preserved. Retry with /worldshare push.");
            }
        });
    }

    private static void notifyClientChat(final String message) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.literal(message), false);
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
