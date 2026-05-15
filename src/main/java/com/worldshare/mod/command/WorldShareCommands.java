package com.worldshare.mod.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.OAuthHelper;
import com.worldshare.mod.cloud.SessionLock;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.config.WorldSubscription;
import com.worldshare.mod.relay.E4mcCoordinator;
import com.worldshare.mod.sync.OnlineChecker;
import com.worldshare.mod.sync.SyncDiff;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.sync.SyncProgress;
import com.worldshare.mod.sync.WorldContext;
import com.worldshare.mod.util.MachineId;
import com.worldshare.mod.util.SHA256Util;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Registers the {@code /worldshare ...} command tree.
 *
 * <p>Subcommands by milestone:
 * <ul>
 *   <li>M1: {@code test}, {@code signout}</li>
 *   <li>M2: {@code setDriveLink}, {@code clearDriveLink}, {@code lock}, {@code unlock},
 *       {@code lockinfo}, {@code heartbeat}</li>
 *   <li>M3: {@code push}, {@code pull}, {@code status}</li>
 *   <li>M4: {@code invite}</li>
 * </ul>
 *
 * <p><b>M5 changes:</b>
 * <ul>
 *   <li>{@code setfolder} renamed to {@code setDriveLink}; also writes
 *       {@code worldshare-link.json} and registers in the subscription store</li>
 *   <li>{@code clearfolder} renamed to {@code clearDriveLink}; also unsubscribes
 *       from the subscription store so the world disappears from Contributor Worlds</li>
 *   <li>{@code lock} now checks if the local copy is behind Drive before acquiring;
 *       if behind, refuses the lock with a clear message explaining how to fix it</li>
 *   <li>All Drive folder ID lookups now use {@link WorldLink} (per-world link file)
 *       rather than the legacy global config value</li>
 * </ul>
 */
public final class WorldShareCommands {

    private static final String TEST_FILE_NAME = "worldshare-m1-test.txt";

    private WorldShareCommands() {}

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("worldshare")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.literal("test")
                                .executes(ctx -> runDriveTest(ctx.getSource())))
                        .then(Commands.literal("signout")
                                .executes(ctx -> runSignOut(ctx.getSource())))
                        .then(Commands.literal("setDriveLink")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(ctx -> runSetDriveLink(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("clearDriveLink")
                                .executes(ctx -> runClearDriveLink(ctx.getSource())))
                        .then(Commands.literal("lock")
                                .executes(ctx -> runLock(ctx.getSource())))
                        .then(Commands.literal("unlock")
                                .executes(ctx -> runUnlock(ctx.getSource())))
                        .then(Commands.literal("lockinfo")
                                .executes(ctx -> runLockInfo(ctx.getSource())))
                        .then(Commands.literal("heartbeat")
                                .executes(ctx -> runHeartbeat(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> runStatus(ctx.getSource())))
                        .then(Commands.literal("push")
                                .executes(ctx -> runPush(ctx.getSource())))
                        .then(Commands.literal("pull")
                                .executes(ctx -> runPull(ctx.getSource())))
                        .then(Commands.literal("invite")
                                .executes(ctx -> runInvite(ctx.getSource())))
                        .then(Commands.literal("modpack")
                                .then(Commands.literal("generate")
                                        .executes(ctx -> runModpackGenerate(ctx.getSource()))))
        );
        WorldShareMod.LOGGER.info("Registered /worldshare commands");
    }

    // ----- M1 -----

    private static int runDriveTest(final CommandSourceStack source) {
        sendFeedback(source, "Starting Google Drive round-trip test.", ChatFormatting.GRAY);
        CloudModule.executor().submit(() -> {
            try {
                final DriveClient client = CloudModule.driveClient(
                        WorldShareCommands::postClickableAuthLink);
                sendClientMessage("§7[WorldShare] Authenticating with Google...");
                final Path tmp = Files.createTempFile("worldshare-test-", ".txt");
                final String content = "WorldShare round-trip test - " + Instant.now();
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                final String localHash = SHA256Util.hashFile(tmp);
                sendClientMessage("[WorldShare] Writing local test file...");
                sendClientMessage("         local hash: " + localHash.substring(0, 16) + "...");
                sendClientMessage("[WorldShare] Uploading to Drive...");
                final String fileId = client.uploadFile(tmp, TEST_FILE_NAME, null);
                sendClientMessage("         drive file id: " + fileId);
                sendClientMessage("[WorldShare] Downloading back from Drive...");
                final Path downloaded = Files.createTempFile("worldshare-dl-", ".txt");
                client.downloadFile(fileId, downloaded);
                final String dlHash = SHA256Util.hashFile(downloaded);
                sendClientMessage("         downloaded hash: " + dlHash.substring(0, 16) + "...");
                sendClientMessage("[WorldShare] Cleaning up...");
                client.deleteFile(fileId);
                Files.deleteIfExists(tmp);
                Files.deleteIfExists(downloaded);
                if (localHash.equals(dlHash)) {
                    sendClientMessage("§a[WorldShare] \u2705 Round-trip successful! Hashes match.");
                } else {
                    sendClientMessage("§c[WorldShare] \u274c Hash mismatch!");
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("Drive test failed", t);
                sendClientMessage("§c[WorldShare] Drive test failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void postClickableAuthLink(final String url) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            final MutableComponent link = Component.literal("[Click here to authorize]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Opens Google OAuth in your browser.\n"
                                            + "Return to Minecraft after authorizing."))));
            if (mc.player != null) {
                mc.player.displayClientMessage(link, false);
            }
        });
    }

    private static int runSignOut(final CommandSourceStack source) {
        CloudModule.executor().submit(() -> {
            try {
                OAuthHelper.forgetStoredCredential();
                CloudModule.resetDriveClient();
                sendClientMessage("§a[WorldShare] Signed out. "
                        + "Next Drive operation will prompt to sign in.");
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error("Sign out failed", e);
                sendClientMessage("§c[WorldShare] Sign out failed: " + e.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- M2 -----

    /**
     * M5: renamed from {@code setfolder}. Now also writes a
     * {@code worldshare-link.json} into the world folder and registers the
     * world in the subscription store. This binds the open world to its Drive
     * folder permanently on this machine.
     */
    private static int runSetDriveLink(final CommandSourceStack source, final String id) {
        final String extracted = extractFolderId(id);



        if (extracted == null) {
            sendFeedback(source,
                    "Couldn't parse a Drive folder ID from that input.", ChatFormatting.RED);
            return 0;
        }

        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source,
                    "You must be in a world to use /worldshare setDriveLink. "
                    + "Open a singleplayer world first.",
                    ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();

        sendFeedback(source, "Verifying folder with Drive...", ChatFormatting.GRAY);


        final String localFolderName = world.worldRoot.getFileName().toString();
        final WorldSubscription claimed = SubscriptionStore.get().findByFolderId(extracted);
        if (claimed != null && claimed.localFolderName != null
                && !claimed.localFolderName.equals(localFolderName)) {
            sendFeedback(source,
                    "This Drive folder is already linked to local world '"
                            + claimed.localFolderName + "'.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Two local worlds cannot share one Drive folder.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Open '" + claimed.localFolderName
                            + "' and run /worldshare clearDriveLink first if you want to move the link.",
                    ChatFormatting.GRAY);
            return 0;
        }

        CloudModule.executor().submit(() -> {
            try {
                final DriveClient client = CloudModule.driveClient(
                        WorldShareCommands::postClickableAuthLink);
                final com.google.api.services.drive.model.File meta =
                        client.getFileMeta(extracted);
                if (meta == null) {
                    sendClientMessage("§c[WorldShare] Folder not found or not accessible.");
                    return;
                }
                if (!DriveClient.MIME_TYPE_FOLDER.equals(meta.getMimeType())) {
                    sendClientMessage(
                            "§c[WorldShare] That ID is a file, not a folder.");
                    return;
                }

                final String folderName = meta.getName() != null ? meta.getName() : extracted;
                final String localFolder = world.worldRoot.getFileName().toString();

                // Write link file + subscribe in store.
                SubscriptionStore.get().linkWorldToFolder(
                        world.worldRoot, localFolder, extracted, folderName);

                // Keep legacy global config in sync (for any remaining M4 reads).
                WorldShareConfig.get().driveFolderId.set(extracted);
                WorldShareConfig.get().driveFolderId.save();

                sendClientMessage("§a[WorldShare] \u2705 Drive link set: '"
                        + folderName + "' (" + extracted + ")");
                sendClientMessage("§7 World '" + world.name
                        + "' will now sync to this Drive folder.");
                sendClientMessage("§a[WorldShare] Drive link set: '" + folderName + "'");
                sendClientMessage("§7 World '" + world.name + "' will now sync to this Drive folder.");
// ADD THESE THREE LINES:
                sendClientMessage("§e ");
                sendClientMessage("§e[WorldShare] Note: this world is NOT yet locked for syncing.");
                sendClientMessage("§e To play with Drive sync, save and quit, then open via");
                sendClientMessage("§e Contributor Worlds tab. Running from vanilla Singleplayer");
                sendClientMessage("§7 will not save changes to Drive.");
                WorldShareMod.LOGGER.info(
                        "setDriveLink: linked '{}' (local: '{}') -> Drive '{}'",
                        world.name, localFolder, extracted);

                // Auto-generate modpack.json so guests immediately know
                // which mods are required. Runs on same executor thread.
                try {
                    sendClientMessage(
                            "§7[WorldShare] Generating modpack.json for guests...");
                    final com.worldshare.mod.modmanager.ModManagerModule.GenerateResult modResult =
                            com.worldshare.mod.modmanager.ModManagerModule
                                    .generateAndUpload(extracted);
                    if (modResult.total > 0) {
                        sendClientMessage("§7[WorldShare] Modpack published: "
                                + modResult.total + " mods ("
                                + modResult.autoInstallable + " auto-installable, "
                                + modResult.manualInstall + " manual).");
                    } else {
                        sendClientMessage(
                                "§7[WorldShare] No mods published (dev environment?).");
                    }
                } catch (final Throwable modErr) {
                    WorldShareMod.LOGGER.warn(
                            "setDriveLink: modpack generate failed (non-fatal): {}",
                            modErr.getMessage());
                    sendClientMessage(
                            "§e[WorldShare] modpack.json generation failed - "
                                    + "run /worldshare modpack generate manually.");
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("setDriveLink failed", t);
                sendClientMessage("§c[WorldShare] setDriveLink failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * M5: renamed from {@code clearfolder}. Now also removes the world from
     * the subscription store so it disappears from the Contributor Worlds tab.
     */
    private static int runClearDriveLink(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return 0;
        }
        final Path worldRoot = ctx.get().worldRoot;

        // Read folder ID BEFORE deleting the link file — we need it to unsubscribe.
        final String folderId = WorldLink.readFolderId(worldRoot);
        // M7: release lock first to avoid orphan on Drive.
        if (LockManager.weHoldLock()) {
            sendFeedback(source, "Releasing session lock first...", ChatFormatting.GRAY);
            CloudModule.executor().submit(() -> {
                try {
                    LockManager.release();
                    sendClientMessage("§a[WorldShare] Lock released.");
                } catch (final IOException e) {
                    WorldShareMod.LOGGER.warn("clearDriveLink: lock release failed", e);
                    sendClientMessage("§e[WorldShare] Warning: lock release failed: " + e.getMessage());
                }
            });
        }

        // M7: explicitly clean up Drive artifacts using the folderId we just read,
// before we delete the link file that gives us access to it.
        final String folderIdToClean = folderId;
        if (folderIdToClean != null) {
            CloudModule.executor().submit(() -> {
                try {
                    // Stop active hosting if we're currently the host.
                    E4mcCoordinator.stopHostingIfActive();
                    // Also explicitly find-and-delete presence.json in case
                    // presenceFileId was null (e.g. after a restart).
                    final DriveClient client = CloudModule.driveClient();
                    final String presId = client.findFileByName(
                            com.worldshare.mod.relay.PresenceFile.FILENAME, folderIdToClean);
                    if (presId != null) {
                        client.deleteFile(presId);
                        WorldShareMod.LOGGER.info(
                                "clearDriveLink: deleted presence.json from Drive");
                    }
                } catch (final Throwable t) {
                    WorldShareMod.LOGGER.warn(
                            "clearDriveLink: presence cleanup failed (non-fatal): {}",
                            t.getMessage());
                }
            });
        }

        // Delete link file.
        final Path linkFile = worldRoot.resolve(WorldLink.FILENAME);
        try {
            Files.deleteIfExists(linkFile);
        } catch (final IOException e) {
            WorldShareMod.LOGGER.warn("clearDriveLink: couldn't delete link file: {}",
                    e.getMessage());
        }

        // Remove from subscription store so it disappears from Contributor Worlds tab.
        if (folderId != null) {
            SubscriptionStore.get().unsubscribe(folderId);
            WorldShareMod.LOGGER.info("clearDriveLink: unsubscribed folder {}", folderId);
        }

        // Clear legacy global config.
        WorldShareConfig.get().driveFolderId.set("");
        WorldShareConfig.get().driveFolderId.save();

        sendFeedback(source,
                "Drive link cleared. World will no longer sync and has been removed from "
                + "Contributor Worlds. Your local files are untouched.",
                ChatFormatting.YELLOW);
        return Command.SINGLE_SUCCESS;
    }

    private static int runLockInfo(final CommandSourceStack source) {
        final String folderId = requireFolderIdForCurrentWorld(source);
        if (folderId == null) return 0;

        CloudModule.executor().submit(() -> {
            try {
                sendClientMessage("§7[WorldShare] Reading session.lock from Drive...");
                final LockManager.LockStatus status = LockManager.readStatus(folderId);
                switch (status.state) {
                    case FREE:
                        sendClientMessage("§a[WorldShare] \uD83D\uDD13 No lock. World is available.");
                        break;
                    case HELD_BY_US:
                        sendClientMessage("§a[WorldShare] \uD83D\uDD12 Lock held by us (machine "
                                + shortId(MachineId.get()) + ")");
                        printLockDetails(status.lock);
                        break;
                    case HELD_BY_US_EXPIRED:
                        sendClientMessage("§e[WorldShare] \u23F0 Lock held by us but EXPIRED.");
                        sendClientMessage("§e         Probably crashed. Safe to acquire again.");
                        printLockDetails(status.lock);
                        break;
                    case HELD_BY_OTHER:
                        sendClientMessage("§c[WorldShare] \uD83D\uDD12 Lock held by §f"
                                + status.lock.holderName + "§c (machine "
                                + shortId(status.lock.machineId) + ")");
                        sendClientMessage("§c         Not expired. Wait until they release.");
                        printLockDetails(status.lock);
                        break;
                    case STALE:
                        sendClientMessage("§e[WorldShare] \u26A0 STALE lock from §f"
                                + status.lock.holderName + "§e (machine "
                                + shortId(status.lock.machineId) + ")");
                        sendClientMessage("§e         Expired "
                                + humanizeDuration(Duration.between(
                                        status.lock.expiresAtInstant(), Instant.now()))
                                + " ago. Override with /worldshare lock.");
                        printLockDetails(status.lock);
                        break;
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("lockinfo failed", t);
                sendClientMessage("§c[WorldShare] lockinfo failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int runLock(final CommandSourceStack source) {
        final String folderId = requireFolderIdForCurrentWorld(source);
        if (folderId == null) return 0;

        // M7: /worldshare lock is disabled when the world is opened from vanilla
        // Singleplayer. The Contributor Worlds tab acquires the lock BEFORE
        // opening the world, so when the user is in-world via that path,
        // weHoldLock() is true. If we don't hold a lock at this point, the user
        // came from Singleplayer — refuse the command.
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "Lock cannot be acquired from vanilla Singleplayer.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Save and quit, then open via Contributor Worlds tab.",
                    ChatFormatting.YELLOW);
            sendFeedback(source,
                    "The tab pulls the latest changes and acquires the lock automatically.",
                    ChatFormatting.GRAY);
            return 0;
        }

        sendFeedback(source,
                "Lock already held - acquired via Contributor Worlds tab.",
                ChatFormatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int runUnlock(final CommandSourceStack source) {
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "We don't currently hold a lock. Nothing to release.",
                    ChatFormatting.YELLOW);
            return Command.SINGLE_SUCCESS;
        }
        CloudModule.executor().submit(() -> {
            try {
                LockManager.release();
                sendClientMessage("§a[WorldShare] \uD83D\uDD13 Lock released.");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("unlock failed", t);
                sendClientMessage("§c[WorldShare] unlock failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int runHeartbeat(final CommandSourceStack source) {
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "We don't currently hold a lock. Run /worldshare lock first.",
                    ChatFormatting.YELLOW);
            return Command.SINGLE_SUCCESS;
        }
        CloudModule.executor().submit(() -> {
            try {
                LockManager.heartbeat();
                sendClientMessage(
                        "§a[WorldShare] \uD83D\uDC93 Heartbeat sent. expires_at refreshed.");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("heartbeat failed", t);
                sendClientMessage("§c[WorldShare] heartbeat failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- M3 -----

    private static int runStatus(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world loaded.", ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();
        final String folderId = requireFolderIdForCurrentWorld(source);
        if (folderId == null) return 0;

        sendFeedback(source,
                "Computing sync status for '" + world.name + "'...", ChatFormatting.GRAY);
        CloudModule.executor().submit(() -> {
            try {
                final SyncDiff diff =
                        SyncEngine.status(world.worldRoot, folderId, world.playerUuid);
                if (diff.isEmpty()) {
                    sendClientMessage("§a[WorldShare] \u2705 In sync. "
                            + diff.identical.size() + " files identical.");
                } else {
                    sendClientMessage("§e[WorldShare] Sync diff for '" + world.name + "':");
                    sendClientMessage("§7  " + diff.onlyLocal.size() + " files only local");
                    sendClientMessage("§7  " + diff.onlyOnDrive.size() + " files only on Drive");
                    sendClientMessage("§7  " + diff.different.size() + " files differ");
                    sendClientMessage("§7  " + diff.identical.size() + " files identical");
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("status failed", t);
                sendClientMessage("§c[WorldShare] status failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int runPush(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world loaded.", ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();
        final String folderId = requireFolderIdForCurrentWorld(source);
        if (folderId == null) return 0;

        // M7: refuse push if no lock held (Singleplayer protection).
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "Cannot push without holding the session lock.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Save and quit, then open via Contributor Worlds tab to sync properly.",
                    ChatFormatting.YELLOW);
            return 0;
        }

        sendFeedback(source,
                "Starting push of '" + world.name + "' to Drive...", ChatFormatting.YELLOW);

        final SyncProgress chatProgress = newChatProgressReporter();
        final Thread precheck = new Thread(() -> {
            final OnlineChecker.Result online = OnlineChecker.check(folderId);
            if (online == OnlineChecker.Result.OFFLINE) {
                sendClientMessage(
                        "§c[WorldShare] Drive unreachable. Local changes preserved.");
                return;
            }
            if (online == OnlineChecker.Result.NOT_AUTHENTICATED) {
                sendClientMessage(
                        "§c[WorldShare] Not signed in. Run /worldshare test first.");
                return;
            }
            CloudModule.executor().submit(() -> {
                try {
                    final SyncEngine.PushResult result = SyncEngine.push(
                            world.worldRoot, folderId, world.playerUuid, null, chatProgress);
                    sendClientMessage("§a[WorldShare] Push complete:");
                    sendClientMessage("§a  uploaded: " + result.uploaded + " files");
                    sendClientMessage("§7  skipped (someone else's edits): "
                            + result.skippedSomeoneElsesEdit);
                    sendClientMessage("§7  failed: " + result.failed);
                    sendClientMessage("§7  bytes: " + result.bytes
                            + " (" + (result.bytes / (1024 * 1024)) + " MB)");
                } catch (final Throwable t) {
                    WorldShareMod.LOGGER.error("push failed", t);
                    sendClientMessage("§c[WorldShare] push failed: " + t.getMessage());
                }
            });
        }, "WorldShare-PushPrecheck");
        precheck.setDaemon(true);
        precheck.start();
        return Command.SINGLE_SUCCESS;
    }

    private static SyncProgress newChatProgressReporter() {
        return new SyncProgress() {
            long lastUpdateMs = 0L;
            int lastReportedPercent = -1;

            @Override
            public void onStart(final int total, final long bytes) {
                if (total == 0) {
                    sendClientMessage("§7[WorldShare] Nothing to upload.");
                    return;
                }
                sendClientMessage("§e[WorldShare] Uploading " + total
                        + " files (" + (bytes / (1024 * 1024)) + " MB)...");
            }

            @Override
            public void onFileProgress(final int filesDone, final int total,
                                       final long bytesDone, final long bytesTotal,
                                       final String currentFile) {
                if (total == 0) return;
                final long now = System.currentTimeMillis();
                final int percent = bytesTotal > 0
                        ? (int) (100L * bytesDone / bytesTotal)
                        : (int) (100L * filesDone / total);
                final boolean dueByTime = now - lastUpdateMs >= 2000L;
                final boolean dueByPercent = percent / 25 > Math.max(0, lastReportedPercent) / 25
                        && percent > lastReportedPercent;
                if (!dueByTime && !dueByPercent) return;
                lastUpdateMs = now;
                lastReportedPercent = percent;
                sendClientMessage(String.format(
                        "§7[WorldShare] §f%d%%§7 - %d/%d files, %d/%d MB",
                        percent, filesDone, total,
                        bytesDone / (1024 * 1024), bytesTotal / (1024 * 1024)));
            }

            @Override public void onComplete() {}
            @Override public void onError(final Throwable error) {
                sendClientMessage("§c[WorldShare] Sync error: " + error.getMessage());
            }
        };
    }

    private static int runPull(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world loaded.", ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();
        final String folderId = requireFolderIdForCurrentWorld(source);
        if (folderId == null) return 0;

        sendFeedback(source,
                "WARNING: Pulling while a world is loaded may corrupt it. "
                + "Save and Quit first.",
                ChatFormatting.RED);
        CloudModule.executor().submit(() -> {
            try {
                final SyncEngine.PullResult result =
                        SyncEngine.pull(world.worldRoot, folderId, world.playerUuid);
                sendClientMessage("§a[WorldShare] Pull complete:");
                sendClientMessage("§a  downloaded: " + result.downloaded + " files");
                sendClientMessage("§7  failed: " + result.failed);
                sendClientMessage("§7  bytes: " + result.bytes
                        + " (" + (result.bytes / (1024 * 1024)) + " MB)");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("pull failed", t);
                sendClientMessage("§c[WorldShare] pull failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- M4 -----

    private static int runInvite(final CommandSourceStack source) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            sendFeedback(source,
                    "You must be in a singleplayer world to use /worldshare invite.",
                    ChatFormatting.RED);
            return 0;
        }
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "Acquire the session lock first with /worldshare lock.",
                    ChatFormatting.RED);
            return 0;
        }
        sendFeedback(source,
                "Opening world to LAN via e4mc... waiting for relay domain.",
                ChatFormatting.GREEN);
        E4mcCoordinator.startHosting();
        return Command.SINGLE_SUCCESS;
    }

    private static int runModpackGenerate(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return 0;
        }
        final String folderId = WorldLink.readFolderId(ctx.get().worldRoot);
        if (folderId == null) {
            sendFeedback(source,
                    "This world is not linked to Drive. Run /worldshare setDriveLink first.",
                    ChatFormatting.RED);
            return 0;
        }

        // NEW: require lock
        if (!LockManager.weHoldLock()) {
            sendFeedback(source,
                    "You must hold the session lock to generate a modpack. "
                            + "Run /worldshare lock first.",
                    ChatFormatting.RED);
            return 0;
        }

        sendFeedback(source, "Scanning mods and resolving Modrinth URLs...",
                ChatFormatting.GRAY);

        sendFeedback(source, "Scanning mods and resolving Modrinth URLs...",
                ChatFormatting.GRAY);

        CloudModule.executor().submit(() -> {
            try {
                final com.worldshare.mod.modmanager.ModManagerModule.GenerateResult result =
                        com.worldshare.mod.modmanager.ModManagerModule.generateAndUpload(folderId);
                sendClientMessage("§a[WorldShare] \u2705 modpack.json published to Drive:");
                sendClientMessage("§a  total mods: " + result.total);
                sendClientMessage("§7  auto-installable (on Modrinth): " + result.autoInstallable);
                sendClientMessage("§7  manual install required: " + result.manualInstall);
                if (result.total == 0) {
                    sendClientMessage(
                            "§e Note: no mods were published. This is expected in the");
                    sendClientMessage(
                            "§e dev environment - generate only works in production installs.");
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("modpack generate failed", t);
                sendClientMessage("§c[WorldShare] modpack generate failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- Helpers -----

    /**
     * Gets the Drive folder ID for the currently-loaded world from its
     * {@code worldshare-link.json}. If no world is loaded or no link exists,
     * prints a helpful error and returns null.
     */
    private static String requireFolderIdForCurrentWorld(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return null;
        }
        final String folderId = WorldLink.readFolderId(ctx.get().worldRoot);
        if (folderId == null) {
            sendFeedback(source,
                    "This world is not linked to a Drive folder. "
                    + "Run /worldshare setDriveLink <url-or-id> to link it.",
                    ChatFormatting.RED);
            return null;
        }
        return folderId;
    }

    private static void printLockDetails(final SessionLock lock) {
        if (lock == null) return;
        sendClientMessage("§7         holder:    " + lock.holderName);
        sendClientMessage("§7         locked_at: " + lock.lockedAt);
        sendClientMessage("§7         expires:   " + lock.expiresAt);
        sendClientMessage("§7         heartbeat: " + lock.lastHeartbeatAt);
        if (lock.relayAddress != null) {
            sendClientMessage("§7         relay:     " + lock.relayAddress);
        }
    }

    private static String shortId(final String fullId) {
        if (fullId == null || fullId.length() < 8) return String.valueOf(fullId);
        return fullId.substring(0, 8);
    }

    private static String humanizeDuration(final Duration d) {
        long s = Math.abs(d.getSeconds());
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        return h + "h " + (m - h * 60) + "m";
    }

    private static void sendFeedback(final CommandSourceStack source,
                                     final String text,
                                     final ChatFormatting color) {
        source.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static void sendClientMessage(final String rawMessage) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(rawMessage), false);
            }
        });
    }

    private static String extractFolderId(final String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        final int idx = s.indexOf("/folders/");
        if (idx >= 0) s = s.substring(idx + "/folders/".length());
        final int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        final int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("[A-Za-z0-9_\\-]+")) return null;
        if (s.length() < 10) return null;
        return s;
    }
}
