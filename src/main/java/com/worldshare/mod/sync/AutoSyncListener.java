package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.relay.E4mcCoordinator;
import com.worldshare.mod.util.PlayerNotice;
import net.minecraft.client.Minecraft;
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

            // An upload from a previous world may still be running - that is what
            // "Continue in Background" is for. Say so, because the two worlds share
            // one Drive connection and one lock slot, and the second world's session
            // will behave oddly until the first finishes.
            if (SyncActivity.isSyncing() && !LockManager.weHoldLock(link.remote)) {
                notifyClientChat("§e[WorldShare] An upload for another world is still "
                        + "running in the background.");
                notifyClientChat("§7 Let it finish before saving here, or this world's "
                        + "sync may be refused.");
            }

            // World-aware, because the lock we hold might be a different world's.
            // Reaching the title screen while still holding one is possible - a
            // backgrounded upload, or a failed push that deliberately kept it - and
            // the argument-less check would then report true here and silently
            // suppress the warning below for a world we hold nothing on.
            if (!LockManager.weHoldLock(link.remote)) {
                // No lock — warn the player.
                WorldShareMod.LOGGER.warn(
                        "AutoSync: WorldShare world loaded without its session lock "
                                + "(world: '{}', lock held on: {})",
                        worldRoot.getFileName(), LockManager.heldControlFileId());
                notifyClientChat("§e[WorldShare] [!] No session lock held.");
                notifyClientChat("§e Changes made here will NOT be saved to Drive.");
                notifyClientChat("§c Your local copy may also be out of date with Drive.");
                notifyClientChat("§c Locking from here is blocked if Drive has newer changes.");
                notifyClientChat("§7 Save and quit, then open via Contributor Worlds for proper sync.");
                if (LockManager.weHoldLock()) {
                    notifyClientChat("§7 (You still hold a lock on a different world - "
                            + "finish that upload first.)");
                }
                return;
            }

            // Lock is held. Hosting is opt-in: it publishes a public relay address,
            // and doing that as a side effect of opening a world means anyone who
            // installed e4mc once is broadcasting every session thereafter.
            if (!com.worldshare.mod.config.WorldShareConfig.get().autoHostOnOpen.get()) {
                WorldShareMod.LOGGER.debug(
                        "AutoSync: lock held, but autoHostOnOpen is off - "
                                + "use /worldshare host to go live");
                return;
            }
            WorldShareMod.LOGGER.info(
                    "AutoSync: lock held and autoHostOnOpen is on, opening world to LAN via e4mc");
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
        if (!LockManager.weHoldLock(remote)) {
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
                            "AutoSync: push complete for '{}': {} file(s) in {} bucket(s), {} bytes",
                            worldName, result.filesUploaded, result.bucketsUploaded, result.bytes);
                    // M7: refresh modpack.json if mod list changed.
                    try {
                        com.worldshare.mod.modmanager.ModManagerModule.generateAndUpload(remote);
                        WorldShareMod.LOGGER.info("AutoSync: modpack refreshed");
                    } catch (final Throwable modErr) {
                        WorldShareMod.LOGGER.warn(
                                "AutoSync: modpack refresh failed (non-fatal): {}", modErr.getMessage());
                    }
                    notifyClientChat("§a[WorldShare] §f'" + worldName
                            + "' synced to Drive: " + result.filesUploaded + " files ("
                            + (result.bytes / (1024 * 1024)) + " MB).");
                } else {
                    WorldShareMod.LOGGER.warn(
                            "AutoSync: push had {} failures for '{}'", result.failed, worldName);
                    notifyClientError("§c[WorldShare] §f" + result.failed
                            + " bucket(s) failed to upload. Run /worldshare push to retry.");
                }

                // Only on success - see the same reasoning in SaveAndUploadScreen.
                // A failed push leaves archives on Drive that the published manifest
                // does not describe, and the lock is what keeps anyone from pulling
                // them.
                if (result.failed == 0) {
                    if (LockManager.weHoldLock(remote)) {
                        LockManager.release();
                        WorldShareMod.LOGGER.info("AutoSync: released lock after push");
                    }
                } else {
                    WorldShareMod.LOGGER.warn(
                            "AutoSync: {} bucket(s) failed; keeping the session lock",
                            result.failed);
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error(
                        "AutoSync: push failed for '{}'; local files preserved",
                        worldName, t);
                notifyClientError("§c[WorldShare] Auto-push failed: " + t.getMessage()
                        + ". Local changes preserved. Retry with /worldshare push.");
            }
        });
    }

    /**
     * Tell the player something about the auto-sync.
     *
     * <p>Delegates to {@link PlayerNotice} rather than writing to chat directly,
     * because this class runs on {@code ServerStopping} - the player is already
     * gone by the time any of these messages exist, so chat alone dropped every
     * one of them into the log.
     */
    private static void notifyClientChat(final String message) {
        PlayerNotice.info(message);
    }

    /** As above, for outcomes that need the player to do something about them. */
    private static void notifyClientError(final String message) {
        PlayerNotice.error(message);
    }
}
