package com.worldshare.mod.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.sync.BucketLayout;
import com.worldshare.mod.cloud.WorldSetup;
import com.worldshare.mod.cloud.RemoteFileSet;
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
 * <p>There is deliberately no {@code pull} command. Pulling rewrites world files
 * underneath whatever has them open, and the command could only ever run with a
 * world loaded - so its only reachable use was the unsafe one. It previously
 * warned that it might corrupt the world and then did it anyway. Pulling happens
 * through the Contributor Worlds screen, which pulls before the world is opened.
 *
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
                        .then(Commands.literal("setup")
                                .executes(ctx -> runSetup(ctx.getSource())))
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
                        .then(Commands.literal("doctor")
                                .executes(ctx -> runDoctor(ctx.getSource(), false))
                                .then(Commands.literal("full")
                                        .executes(ctx -> runDoctor(ctx.getSource(), true))))
                        .then(Commands.literal("push")
                                .executes(ctx -> runPush(ctx.getSource())))
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

    /**
     * Post the invite link as one clickable line that copies to the clipboard.
     *
     * <p>Printing the raw URL was worse than merely untidy: a Drive folder URL is
     * about sixty characters, so it wrapped across three chat lines and could not
     * reliably be copied out of the game at all - and copying it is the entire
     * point, since it has to reach the other player.
     */
    private static void postCopyableInviteLink(final String folderId) {
        final String url = "https://drive.google.com/drive/folders/" + folderId;
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            final MutableComponent link = Component.literal("  [Copy invite link]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.COPY_TO_CLIPBOARD, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy:\n" + url
                                            + "\n\nSend this to the person you're sharing with."))));
            if (mc.player != null) {
                mc.player.displayClientMessage(link, false);
            }
        });
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
     * Set this world up for sharing: pick a Drive folder, create the fixed remote
     * file set inside it, and record the resulting file IDs locally.
     *
     * <p>Replaces the old {@code setDriveLink <url>} command, which took a pasted
     * folder ID. That approach can't work any more - under the {@code drive.file}
     * scope a folder ID the user typed grants nothing, because access comes from
     * having gone through Google's Picker, not from knowing an identifier. So the
     * command now takes no argument and opens the consent screen instead.
     */
    private static int runSetup(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source,
                    "You must be in a world to run /worldshare setup. "
                    + "Open a singleplayer world first.",
                    ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();

        final RemoteFileSet already = WorldLink.readRemote(world.worldRoot);
        if (already != null) {
            sendFeedback(source,
                    "This world is already set up for sharing. "
                    + "Run /worldshare clearDriveLink first if you want to redo it.",
                    ChatFormatting.YELLOW);
            return 0;
        }

        final int bucketCount = BucketLayout.DEFAULT_BUCKET_COUNT;
        sendFeedback(source,
                "Opening Google sign-in. Pick (or create) a Drive folder to keep this world in.",
                ChatFormatting.GRAY);

        CloudModule.executor().submit(() -> {
            try {
                final RemoteFileSet remote = WorldSetup.createNewWorld(
                        WorldShareCommands::postClickableAuthLink, bucketCount, world.name);

                final String localFolder = world.worldRoot.getFileName().toString();
                SubscriptionStore.get().linkWorldToRemote(
                        world.worldRoot, localFolder, remote, world.name);

                // Three lines, deliberately. This used to print sixteen, and Minecraft
                // shows about ten - so the success line and the first two invite steps
                // scrolled off before the player could read them, making a successful
                // setup look like it had done nothing. The step-by-step for the person
                // being invited belongs in the README, not in the host's chat log.
                sendClientMessage("§a\u2705 '" + world.name + "' is ready to share \u2014 "
                        + remote.layout().remoteFileCount() + " files created in Drive.");
                postCopyableInviteLink(remote.driveFolderId);
                sendClientMessage("§7Share that Drive folder with them as Editor, "
                        + "then send them the link.");

                WorldShareMod.LOGGER.info("setup: '{}' (local: '{}') -> control file {}",
                        world.name, localFolder, remote.controlFileId);

                // Publish the mod list so guests know what they need. Non-fatal:
                // a world that syncs but doesn't advertise its mods is still usable.
                try {
                    sendClientMessage("§7[WorldShare] Publishing mod list for guests...");
                    final com.worldshare.mod.modmanager.ModManagerModule.GenerateResult modResult =
                            com.worldshare.mod.modmanager.ModManagerModule.generateAndUpload(remote);
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
                            "setup: modpack generate failed (non-fatal): {}", modErr.getMessage());
                    sendClientMessage("§e[WorldShare] Mod list publish failed - "
                            + "run /worldshare modpack generate manually.");
                }
                sendClientMessage("§8Not locked for syncing yet \u2014 open this world from "
                        + "Contributor Worlds to play with Drive sync.");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("setup failed", t);
                sendClientMessage("§c[WorldShare] Setup failed: " + t.getMessage());
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

        // Read the file set BEFORE deleting the link file - we need it to unsubscribe
        // and to stand the presence file down.
        final RemoteFileSet remote = WorldLink.readRemote(worldRoot);
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

        // Stand our presence down before dropping the link that lets us reach it.
        // Note this clears the file's contents rather than deleting it: the other
        // player holds a grant on that exact Drive file ID, and a recreated file
        // would come back with an ID their grant doesn't cover.
        if (remote != null) {
            CloudModule.executor().submit(() -> {
                try {
                    E4mcCoordinator.stopHostingIfActive();
                    com.worldshare.mod.relay.PresenceFile.clear(remote);
                    WorldShareMod.LOGGER.info("clearDriveLink: presence stood down");
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
        if (remote != null) {
            SubscriptionStore.get().unsubscribe(remote.controlFileId);
            WorldShareMod.LOGGER.info("clearDriveLink: unsubscribed world {}", remote.controlFileId);
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
        final RemoteFileSet remote = requireRemoteForCurrentWorld(source);
        if (remote == null) return 0;

        CloudModule.executor().submit(() -> {
            try {
                sendClientMessage("§7[WorldShare] Reading session.lock from Drive...");
                final LockManager.LockStatus status = LockManager.readStatus(remote);
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
        final RemoteFileSet remote = requireRemoteForCurrentWorld(source);
        if (remote == null) return 0;

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
        final RemoteFileSet remote = requireRemoteForCurrentWorld(source);
        if (remote == null) return 0;

        sendFeedback(source,
                "Computing sync status for '" + world.name + "'...", ChatFormatting.GRAY);
        CloudModule.executor().submit(() -> {
            try {
                final SyncDiff diff =
                        SyncEngine.status(world.worldRoot, remote, world.playerUuid);
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
        final RemoteFileSet remote = requireRemoteForCurrentWorld(source);
        if (remote == null) return 0;

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
            final OnlineChecker.Result online = OnlineChecker.check(remote);
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
                            world.worldRoot, remote, world.playerUuid, chatProgress);
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
        if (!E4mcCoordinator.isAvailable()) {
            sendFeedback(source,
                    "Live co-op needs the e4mc mod, which isn't installed.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Install it from modrinth.com/mod/e4mc and restart. "
                    + "Drive sync works fine without it.",
                    ChatFormatting.GRAY);
            return 0;
        }
        sendFeedback(source,
                "Opening world to LAN via e4mc... waiting for relay domain.",
                ChatFormatting.GREEN);
        E4mcCoordinator.startHosting();
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Dump everything known about the current world's sync state, in one place.
     *
     * <p>Exists because diagnosing a sync problem otherwise means correlating half
     * a dozen log lines across two machines. Every value here is already available
     * through some other command or buried in a log; the point is having them
     * together, in an order that makes the usual failures obvious, and phrased so
     * the output can be pasted at somebody rather than summarised.
     *
     * <p>Read-only. It never writes to Drive or to the world.
     *
     * @param full also fetch per-bucket metadata from Drive and scan the world for
     *             a local-vs-remote comparison. Costs a full world scan plus one
     *             API call per bucket, hence opt-in.
     */
    private static int runDoctor(final CommandSourceStack source, final boolean full) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return 0;
        }
        final WorldContext.CurrentWorld world = ctx.get();

        sendFeedback(source, "Collecting diagnostics" + (full ? " (full scan)" : "") + "...",
                ChatFormatting.GRAY);

        CloudModule.executor().submit(() -> {
            final java.util.List<String> report = new java.util.ArrayList<>();
            final java.util.List<String> problems = new java.util.ArrayList<>();
            try {
                buildDoctorReport(report, problems, world, full);
            } catch (final Throwable t) {
                problems.add("Diagnostics aborted: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                WorldShareMod.LOGGER.error("doctor: failed", t);
            }

            // Chat gets a verdict; the file gets everything.
            //
            // The full report runs to a dozen-plus lines and Minecraft shows about
            // ten, so printing it all guaranteed the top scrolled away - and chat
            // text can't be copied out of the game anyway, which is what you want
            // to do with a diagnostic. The file is plain, selectable text.
            final java.nio.file.Path out = writeDoctorReport(report, world);

            if (problems.isEmpty()) {
                sendClientMessage("§a\u2705 WorldShare looks healthy for '" + world.name + "'.");
            } else {
                sendClientMessage("§c\u26a0 " + problems.size() + " problem(s) with '"
                        + world.name + "':");
                for (final String problem : problems) {
                    sendClientMessage("§c  \u2022 " + problem);
                }
            }
            if (out != null) {
                postCopyableReportPath(out);
            } else {
                sendClientMessage("§7Full report is in the log.");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Write the full diagnostic somewhere it can be read and pasted.
     *
     * <p>Goes next to the game directory rather than into the log, because
     * latest.log is enormous and finding the report inside it is its own chore.
     *
     * @return the file written, or null if it couldn't be (in which case the
     *         caller falls back to the log)
     */
    private static java.nio.file.Path writeDoctorReport(final java.util.List<String> report,
                                                        final WorldContext.CurrentWorld world) {
        final java.nio.file.Path out = com.worldshare.mod.util.WorldSharePaths.gameDir()
                .resolve("worldshare-doctor.txt");
        final StringBuilder text = new StringBuilder();
        text.append("WorldShare diagnostic report\n")
                .append("Generated: ").append(java.time.Instant.now()).append('\n')
                .append("World: ").append(world.name).append('\n')
                .append("Path:  ").append(world.worldRoot).append('\n')
                .append("----------------------------------------\n");
        for (final String line : report) {
            // Strip Minecraft's colour codes; they're noise in a text file.
            text.append(line.replaceAll("\u00a7.", "")).append('\n');
        }

        WorldShareMod.LOGGER.info("=== /worldshare doctor ===\n{}", text);
        try {
            java.nio.file.Files.writeString(out, text.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return out;
        } catch (final Exception e) {
            WorldShareMod.LOGGER.warn("doctor: couldn't write {}: {}", out, e.getMessage());
            return null;
        }
    }

    /** One clickable line that copies the report's path, since chat text can't be selected. */
    private static void postCopyableReportPath(final java.nio.file.Path path) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            final MutableComponent link = Component.literal("  [Copy full report path]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,
                                    path.toAbsolutePath().toString()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal(path.toAbsolutePath().toString()
                                            + "\n\nOpen this to read or paste the full report."))));
            if (mc.player != null) {
                mc.player.displayClientMessage(link, false);
            }
        });
    }

    private static void buildDoctorReport(final java.util.List<String> out,
                                          final java.util.List<String> problems,
                                          final WorldContext.CurrentWorld world,
                                          final boolean full) {
        out.add("§b=== WorldShare doctor ===");
        out.add("§7World: §f" + world.name);

        // ---- local link ----
        final WorldLink link = WorldLink.read(world.worldRoot);
        if (link == null) {
            out.add("§eNot set up for sharing. Run /worldshare setup.");
            problems.add("Not set up for sharing - run /worldshare setup");
            return;
        }
        if (link.isLegacy()) {
            out.add("§eLinked under the old full-Drive scope (folder "
                    + link.driveFolderId + ").");
            out.add("§eRun /worldshare setup again to pick this world's files.");
            problems.add("Linked under the old full-Drive scope - re-run /worldshare setup");
            return;
        }
        final RemoteFileSet remote = link.remote;
        if (remote == null) {
            out.add("§cLink file exists but names no Drive files. Re-run setup.");
            problems.add("Link file names no Drive files - re-run setup");
            return;
        }
        if (!remote.isComplete()) {
            out.add("§cSetup incomplete - still missing: §f"
                    + WorldSetup.describeMissing(remote));
            out.add("§7Re-run Add World and select the missing files.");
            problems.add("Setup incomplete, missing: " + WorldSetup.describeMissing(remote));
            return;
        }
        out.add("§aLink OK §7- " + remote.bucketCount + " buckets, "
                + remote.layout().remoteFileCount() + " remote files");
        if (remote.driveFolderId != null) {
            out.add("§7  invite link: §fhttps://drive.google.com/drive/folders/"
                    + remote.driveFolderId);
        }
        out.add("§7  control:  §f" + remote.controlFileId);
        out.add("§7  presence: §f" + remote.presenceFileId);

        // ---- remote control file ----
        final com.worldshare.mod.cloud.ControlFile control;
        try {
            control = com.worldshare.mod.cloud.ControlFileClient.read(remote.controlFileId);
        } catch (final Exception e) {
            out.add("§cControl file unreadable: §f" + e.getMessage());
            out.add("§7Check the folder is still shared with this Google account.");
            problems.add("Control file unreadable - is the folder still shared with you?");
            return;
        }
        if (control == null) {
            out.add("§eControl file is still an empty placeholder - nobody has pushed yet.");
        } else {
            out.add("§aControl file OK §7- schema v" + control.schemaVersion
                    + ", " + control.bucketCount + " buckets, "
                    + control.manifestOrEmpty().size() + " files, "
                    + (control.manifestOrEmpty().totalBytes() / (1024 * 1024)) + " MB");
            if (control.bucketCount != remote.bucketCount) {
                out.add("§c!! Bucket layout mismatch: local " + remote.bucketCount
                        + " vs Drive " + control.bucketCount + ". Syncing is blocked.");
                problems.add("Bucket layout mismatch: local " + remote.bucketCount
                        + " vs Drive " + control.bucketCount);
            }
            if (control.modpack != null) {
                out.add("§7  modpack published: §fyes");
            }
        }

        // ---- lock ----
        try {
            final LockManager.LockStatus status = LockManager.readStatus(remote);
            final SessionLock lock = status.lock;
            final StringBuilder line = new StringBuilder("§7Lock: §f" + status.state);
            if (lock != null && !lock.isUnlocked()) {
                line.append(" §7held by §f").append(lock.holderName);
                final java.time.Duration left = java.time.Duration.between(
                        java.time.Instant.now(), lock.expiresAtInstant());
                line.append(left.isNegative()
                        ? " §c(expired)"
                        : " §7(expires in " + left.toHours() + "h" + (left.toMinutes() % 60) + "m)");
            }
            out.add(line.toString());
        } catch (final Exception e) {
            out.add("§cCouldn't read the lock: §f" + e.getMessage());
        }
        out.add("§7This machine holds the lock: §f" + LockManager.weHoldLock()
                + " §8(machine " + MachineId.get() + ")");

        // ---- presence ----
        try {
            final com.worldshare.mod.relay.PresenceFile presence =
                    com.worldshare.mod.relay.PresenceFile.read(remote);
            if (presence == null || presence.isStale()) {
                out.add("§7Live session: §fnone");
            } else {
                out.add("§aLive session: §f" + presence.host + " §7at " + presence.e4mc_link);
            }
        } catch (final Exception e) {
            out.add("§7Live session: §funknown (" + e.getMessage() + ")");
        }
        out.add("§7e4mc installed: §f" + E4mcCoordinator.isAvailable());

        if (!full) {
            out.add("§8Run /worldshare doctor full for bucket sizes and a local diff.");
            return;
        }

        // ---- per-bucket metadata ----
        try {
            final DriveClient client = CloudModule.driveClient();
            long total = 0;
            int empty = 0;
            int largestIndex = -1;
            long largest = -1;
            for (int i = 0; i < remote.bucketCount; i++) {
                final com.google.api.services.drive.model.File meta =
                        client.getFileMeta(remote.bucketFileId(i));
                final long size = (meta == null || meta.getSize() == null) ? 0L : meta.getSize();
                total += size;
                if (size == 0L) empty++;
                if (size > largest) { largest = size; largestIndex = i; }
            }
            out.add("§7Buckets: §f" + (remote.bucketCount - empty) + "/" + remote.bucketCount
                    + " with content§7, total §f" + (total / (1024 * 1024)) + " MB");
            if (largestIndex >= 0) {
                out.add("§7  largest: §f" + BucketLayout.bucketFilename(largestIndex)
                        + " (" + (largest / (1024 * 1024)) + " MB)");
            }
            if (empty > 0) {
                out.add("§7  " + empty + " empty placeholder(s) - normal before a first push");
            }
        } catch (final Exception e) {
            out.add("§cCouldn't read bucket metadata: §f" + e.getMessage());
        }

        // ---- local vs remote ----
        try {
            final SyncDiff diff = SyncEngine.status(world.worldRoot, remote, world.playerUuid);
            if (diff.isEmpty()) {
                out.add("§aLocal matches Drive §7(" + diff.identical.size() + " files)");
            } else {
                out.add("§eLocal differs from Drive:");
                out.add("§7  identical §f" + diff.identical.size()
                        + " §7| changed §f" + diff.different.size()
                        + " §7| only here §f" + diff.onlyLocal.size()
                        + " §7| only on Drive §f" + diff.onlyOnDrive.size());
            }
        } catch (final Exception e) {
            out.add("§cCouldn't compare local to Drive: §f" + e.getMessage());
        }
    }

    private static int runModpackGenerate(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return 0;
        }
        final RemoteFileSet remote = WorldLink.readRemote(ctx.get().worldRoot);
        if (remote == null) {
            sendFeedback(source,
                    "This world is not set up for sharing. Run /worldshare setup first.",
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
                        com.worldshare.mod.modmanager.ModManagerModule.generateAndUpload(remote);
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
    private static RemoteFileSet requireRemoteForCurrentWorld(final CommandSourceStack source) {
        final java.util.Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source, "No world is currently loaded.", ChatFormatting.RED);
            return null;
        }
        final RemoteFileSet remote = WorldLink.readRemote(ctx.get().worldRoot);
        if (remote == null) {
            sendFeedback(source,
                    "This world is not set up for sharing. "
                    + "Run /worldshare setup to link it to Drive.",
                    ChatFormatting.RED);
            return null;
        }
        return remote;
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

}
