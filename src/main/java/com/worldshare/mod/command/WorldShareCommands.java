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
import com.worldshare.mod.config.WorldShareConfig;
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
 *   <li>M1: {@code test}, {@code signout} — Drive round-trip + auth</li>
 *   <li>M2: {@code setfolder}, {@code lock}, {@code unlock}, {@code lockinfo},
 *       {@code heartbeat} — session lock management</li>
 * </ul>
 *
 * <p>Commands are registered in {@link com.worldshare.mod.WorldShareMod} on
 * {@code RegisterCommandsEvent}.
 */
public final class WorldShareCommands {

    private static final String TEST_FILE_NAME = "worldshare-m1-test.txt";

    private WorldShareCommands() {
        // utility class
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("worldshare")
                        .requires(src -> src.hasPermission(0)) // all players allowed
                        .then(Commands.literal("test")
                                .executes(ctx -> runDriveTest(ctx.getSource())))
                        .then(Commands.literal("signout")
                                .executes(ctx -> runSignOut(ctx.getSource())))
                        .then(Commands.literal("setfolder")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(ctx -> runSetFolder(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
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
        );
        WorldShareMod.LOGGER.info("Registered /worldshare commands");
    }

    // -----------------------------------------------------------------

    private static int runDriveTest(final CommandSourceStack source) {
        sendFeedback(source, "Starting Google Drive round-trip test.", ChatFormatting.YELLOW);

        CloudModule.executor().submit(() -> {
            try {
                doRoundTrip(source);
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("Drive round-trip test failed", t);
                sendClientMessage("§c[WorldShare] Test failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void doRoundTrip(final CommandSourceStack source) throws IOException {
        sendClientMessage("§7[WorldShare] Authenticating with Google...");
        // Pass a URL presenter that posts a clickable link to chat instead of
        // attempting the system-browser open (which fails inside Minecraft on
        // many setups because AWT and LWJGL don't cooperate). If a stored
        // credential already exists, this presenter is never invoked.
        final DriveClient client = CloudModule.driveClient(WorldShareCommands::postClickableAuthLink);

        sendClientMessage("§7[WorldShare] Writing local test file...");
        final Path localUpload = Files.createTempFile("worldshare-test-upload-", ".txt");
        final String content = "WorldShare Milestone 1 round-trip test.\n"
                + "timestamp=" + System.currentTimeMillis() + "\n"
                + "random=" + java.util.UUID.randomUUID() + "\n";
        Files.writeString(localUpload, content, StandardCharsets.UTF_8);
        final String localHash = SHA256Util.hashFile(localUpload);
        sendClientMessage("§7         local hash: " + localHash.substring(0, 16) + "...");

        sendClientMessage("§7[WorldShare] Uploading to Drive...");
        final String fileId = client.uploadFile(localUpload, TEST_FILE_NAME, null);
        sendClientMessage("§7         drive file id: " + fileId);

        sendClientMessage("§7[WorldShare] Downloading back from Drive...");
        final Path localDownload = Files.createTempFile("worldshare-test-download-", ".txt");
        client.downloadFile(fileId, localDownload);
        final String downloadHash = SHA256Util.hashFile(localDownload);
        sendClientMessage("§7         downloaded hash: " + downloadHash.substring(0, 16) + "...");

        sendClientMessage("§7[WorldShare] Cleaning up (deleting drive file + local temps)...");
        try {
            client.deleteFile(fileId);
        } catch (final IOException delEx) {
            WorldShareMod.LOGGER.warn("Failed to delete test drive file {}", fileId, delEx);
        }
        Files.deleteIfExists(localUpload);
        Files.deleteIfExists(localDownload);

        if (localHash.equals(downloadHash)) {
            sendClientMessage("§a[WorldShare] ✅ Round-trip successful! Hashes match.");
        } else {
            sendClientMessage("§c[WorldShare] ❌ HASH MISMATCH - something is wrong.");
            sendClientMessage("§c  local    = " + localHash);
            sendClientMessage("§c  download = " + downloadHash);
        }
    }

    /**
     * Post the OAuth authorization URL to chat as a clickable link. Called by
     * {@link OAuthHelper} (via {@link CloudModule#driveClient(java.util.function.Consumer)})
     * exactly once when a new authorization is needed.
     *
     * <p>Minecraft's chat supports {@link ClickEvent.Action#OPEN_URL} natively -
     * the user just clicks, and Minecraft launches the URL via Prism /
     * BrowserControl, which correctly goes through the normal shell handoff
     * (unlike Java's {@code Desktop.browse}, which struggles from inside a
     * fullscreen LWJGL app). The user sees a normal confirmation dialog that
     * Minecraft shows for all external links.
     */
    private static void postClickableAuthLink(final String url) {
        WorldShareMod.LOGGER.info("Posting OAuth link to chat: {}", url);
        Minecraft.getInstance().execute(() -> {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                // Can't post to chat without a player - fall back to log.
                // This shouldn't happen since /worldshare test requires an active world.
                WorldShareMod.LOGGER.warn("No player available; paste this URL manually: {}", url);
                return;
            }

            // Instruction line (non-clickable).
            mc.player.displayClientMessage(
                    Component.literal("§e[WorldShare] §fClick the link below to authorize "
                            + "with Google. (Opens in your default browser.)"),
                    false);

            // The clickable link itself. Styled blue/underlined like vanilla does for URLs.
            final Style linkStyle = Style.EMPTY
                    .withColor(ChatFormatting.BLUE)
                    .withUnderlined(Boolean.TRUE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .withHoverEvent(new HoverEvent(
                            HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Open authorization URL\n" + url)));

            final MutableComponent link = Component.literal("[Click here to sign in]")
                    .withStyle(linkStyle);
            mc.player.displayClientMessage(link, false);

            mc.player.displayClientMessage(
                    Component.literal("§7[WorldShare] §7After approving, you'll be redirected "
                            + "to a local URL showing a success page. This tab will stop loading - "
                            + "that's expected. Return to Minecraft."),
                    false);
        });
    }

    private static int runSignOut(final CommandSourceStack source) {
        CloudModule.executor().submit(() -> {
            try {
                OAuthHelper.forgetStoredCredential();
                CloudModule.resetDriveClient();
                sendClientMessage("§a[WorldShare] Signed out. Next Drive operation will prompt to sign in again.");
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error("Sign out failed", e);
                sendClientMessage("§c[WorldShare] Sign out failed: " + e.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- M2: Session Lock commands -----

    private static int runSetFolder(final CommandSourceStack source, final String id) {
        // Be lenient with input. Users often paste a full Drive URL like
        // "https://drive.google.com/drive/folders/1ABC...?usp=sharing"
        // or include trailing whitespace/newlines.
        final String extracted = extractFolderId(id);
        if (extracted == null) {
            sendFeedback(source,
                    "Couldn't parse a Drive folder ID from that input.",
                    ChatFormatting.RED);
            return 0;
        }

        sendFeedback(source,
                "Verifying folder is accessible (this requires sign-in if you haven't already)...",
                ChatFormatting.GRAY);

        CloudModule.executor().submit(() -> {
            try {
                final DriveClient client = CloudModule.driveClient(WorldShareCommands::postClickableAuthLink);
                final com.google.api.services.drive.model.File meta = client.getFileMeta(extracted);
                if (meta == null) {
                    sendClientMessage("§c[WorldShare] Folder not found or not accessible. "
                            + "Check the ID and that the folder is shared with your signed-in account.");
                    return;
                }
                if (!DriveClient.MIME_TYPE_FOLDER.equals(meta.getMimeType())) {
                    sendClientMessage("§c[WorldShare] That ID points to a file, not a folder. "
                            + "(mime: " + meta.getMimeType() + ")");
                    return;
                }
                WorldShareConfig.get().driveFolderId.set(extracted);
                WorldShareConfig.get().driveFolderId.save();
                sendClientMessage("§a[WorldShare] ✅ Folder saved: " + meta.getName()
                        + " (id " + extracted + ")");
                WorldShareMod.LOGGER.info("Drive folder ID set to: {} (name: {})",
                        extracted, meta.getName());
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("setfolder failed", t);
                sendClientMessage("§c[WorldShare] setfolder failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Permissively extract a Drive folder ID from user input. Accepts:
     * <ul>
     *   <li>A bare ID like {@code 1ABC...xyz}</li>
     *   <li>A folder URL like {@code https://drive.google.com/drive/folders/1ABC...xyz}</li>
     *   <li>A folder URL with query params like {@code .../folders/1ABC...xyz?usp=sharing}</li>
     * </ul>
     */
    private static String extractFolderId(final String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // If it's a Drive URL, pull out the segment after /folders/
        final int idx = s.indexOf("/folders/");
        if (idx >= 0) {
            s = s.substring(idx + "/folders/".length());
        }

        // Strip query string and fragment if present
        final int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        final int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);

        // Strip trailing slashes
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        // Sanity check: Drive IDs are URL-safe base64 - alphanumerics, _, -.
        if (!s.matches("[A-Za-z0-9_\\-]+")) return null;
        if (s.length() < 10) return null;  // real IDs are 28+ chars
        return s;
    }

    private static int runLockInfo(final CommandSourceStack source) {
        final String folderId = requireFolderId(source);
        if (folderId == null) {
            return 0;
        }

        CloudModule.executor().submit(() -> {
            try {
                sendClientMessage("§7[WorldShare] Reading session.lock from Drive...");
                final LockManager.LockStatus status = LockManager.readStatus(folderId);

                switch (status.state) {
                    case FREE:
                        sendClientMessage("§a[WorldShare] 🔓 No lock. World is available.");
                        break;
                    case HELD_BY_US:
                        sendClientMessage("§a[WorldShare] 🔒 Lock held by us (machine "
                                + shortId(MachineId.get()) + ")");
                        printLockDetails(status.lock);
                        break;
                    case HELD_BY_US_EXPIRED:
                        sendClientMessage("§e[WorldShare] ⏰ Lock held by us but EXPIRED.");
                        sendClientMessage("§e         Probably crashed last session. Safe to acquire again.");
                        printLockDetails(status.lock);
                        break;
                    case HELD_BY_OTHER:
                        sendClientMessage("§c[WorldShare] 🔒 Lock held by §f"
                                + status.lock.holderName + "§c (machine "
                                + shortId(status.lock.machineId) + ")");
                        sendClientMessage("§c         Not expired. Wait until they release.");
                        printLockDetails(status.lock);
                        break;
                    case STALE:
                        sendClientMessage("§e[WorldShare] ⚠ STALE lock from §f"
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
        final String folderId = requireFolderId(source);
        if (folderId == null) {
            return 0;
        }

        CloudModule.executor().submit(() -> {
            try {
                // Look before we leap: check who has it.
                final LockManager.LockStatus status = LockManager.readStatus(folderId);
                if (status.state == LockManager.LockState.HELD_BY_OTHER) {
                    sendClientMessage("§c[WorldShare] Can't acquire - lock held by §f"
                            + status.lock.holderName + "§c. Try /worldshare lockinfo for details.");
                    return;
                }

                if (status.state == LockManager.LockState.STALE) {
                    sendClientMessage("§e[WorldShare] Overriding stale lock from §f"
                            + status.lock.holderName + "§e...");
                } else if (status.state == LockManager.LockState.HELD_BY_US_EXPIRED) {
                    sendClientMessage("§e[WorldShare] Resuming expired lock from previous session...");
                } else if (status.state == LockManager.LockState.HELD_BY_US) {
                    sendClientMessage("§a[WorldShare] Already hold this lock. No-op.");
                    return;
                }

                final SessionLock ours = LockManager.acquire(folderId);
                sendClientMessage("§a[WorldShare] 🔒 Lock acquired as §f"
                        + ours.holderName + "§a, expires "
                        + humanizeDuration(Duration.between(Instant.now(),
                                ours.expiresAtInstant())) + " from now.");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("lock failed", t);
                sendClientMessage("§c[WorldShare] lock failed: " + t.getMessage());
            }
        });
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
                sendClientMessage("§a[WorldShare] 🔓 Lock released.");
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
                    "We don't currently hold a lock - nothing to heartbeat. Run /worldshare lock first.",
                    ChatFormatting.YELLOW);
            return Command.SINGLE_SUCCESS;
        }

        CloudModule.executor().submit(() -> {
            try {
                LockManager.heartbeat();
                sendClientMessage("§a[WorldShare] 💓 Manual heartbeat sent. Check Drive: expires_at should be refreshed.");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("heartbeat failed", t);
                sendClientMessage("§c[WorldShare] heartbeat failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- M3: Sync commands -----

    private static int runStatus(final CommandSourceStack source) {
        final String folderId = requireFolderId(source);
        if (folderId == null) return 0;

        final java.util.Optional<com.worldshare.mod.sync.WorldContext.CurrentWorld> ctx
                = com.worldshare.mod.sync.WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source,
                    "No world is currently loaded. Open a world first.",
                    ChatFormatting.RED);
            return 0;
        }
        final com.worldshare.mod.sync.WorldContext.CurrentWorld world = ctx.get();

        sendFeedback(source,
                "Computing sync status for '" + world.name + "'...",
                ChatFormatting.GRAY);
        CloudModule.executor().submit(() -> {
            try {
                final com.worldshare.mod.sync.SyncDiff diff =
                        com.worldshare.mod.sync.SyncEngine.status(world.worldRoot, folderId, world.playerUuid);
                if (diff.isEmpty()) {
                    sendClientMessage("§a[WorldShare] ✅ World is in sync with Drive. "
                            + diff.identical.size() + " files identical.");
                } else {
                    sendClientMessage("§e[WorldShare] Sync diff for '" + world.name + "':");
                    sendClientMessage("§7  " + diff.onlyLocal.size() + " files only local (would push)");
                    sendClientMessage("§7  " + diff.onlyOnDrive.size() + " files only on Drive (would pull)");
                    sendClientMessage("§7  " + diff.different.size() + " files differ on both sides");
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
        final String folderId = requireFolderId(source);
        if (folderId == null) return 0;

        final java.util.Optional<com.worldshare.mod.sync.WorldContext.CurrentWorld> ctx
                = com.worldshare.mod.sync.WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source,
                    "No world is currently loaded. Open a world first.",
                    ChatFormatting.RED);
            return 0;
        }
        final com.worldshare.mod.sync.WorldContext.CurrentWorld world = ctx.get();

        sendFeedback(source,
                "Starting push of '" + world.name + "' to Drive...",
                ChatFormatting.YELLOW);
        sendFeedback(source,
                "Tip: For a fully consistent sync, use Save and Quit instead - auto-push "
                + "runs after Minecraft finishes saving. Manual push uses file snapshots so "
                + "it's safe, but Minecraft may keep writing the world causing repeated diffs.",
                ChatFormatting.GRAY);

        final com.worldshare.mod.sync.SyncProgress chatProgress = newChatProgressReporter();

        // Run the online check on a NEW thread (not the cloud executor) because
        // OnlineChecker itself submits to the cloud executor and waits for it,
        // which would self-deadlock if we ran on that same single-threaded pool.
        // Once online is confirmed, dispatch the push onto the cloud executor.
        final Thread precheck = new Thread(() -> {
            final com.worldshare.mod.sync.OnlineChecker.Result online =
                    com.worldshare.mod.sync.OnlineChecker.check(folderId);
            if (online == com.worldshare.mod.sync.OnlineChecker.Result.OFFLINE) {
                sendClientMessage("§c[WorldShare] Drive is unreachable. Check your "
                        + "internet connection and try again. Local changes are preserved.");
                return;
            }
            if (online == com.worldshare.mod.sync.OnlineChecker.Result.NOT_AUTHENTICATED) {
                sendClientMessage("§c[WorldShare] Not signed in to Drive. "
                        + "Run /worldshare test to authenticate.");
                return;
            }

            CloudModule.executor().submit(() -> {
                try {
                    final com.worldshare.mod.sync.SyncEngine.PushResult result =
                            com.worldshare.mod.sync.SyncEngine.push(
                                    world.worldRoot, folderId, world.playerUuid, null, chatProgress);
                    sendClientMessage("§a[WorldShare] Push complete:");
                    sendClientMessage("§a  uploaded: " + result.uploaded + " files");
                    sendClientMessage("§7  skipped (someone else's edits): " + result.skippedSomeoneElsesEdit);
                    sendClientMessage("§7  failed: " + result.failed);
                    sendClientMessage("§7  bytes: " + result.bytes + " (" + (result.bytes / (1024 * 1024)) + " MB)");
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

    /**
     * Returns a SyncProgress that posts rate-limited progress updates to chat.
     * Updates appear at most every 2 seconds OR every 25% of progress —
     * whichever comes first — to avoid spamming chat for small worlds while
     * still giving frequent feedback for large ones.
     */
    private static com.worldshare.mod.sync.SyncProgress newChatProgressReporter() {
        return new com.worldshare.mod.sync.SyncProgress() {
            // State accessible to all callbacks.
            long lastUpdateMs = 0L;
            int lastReportedPercent = -1;
            int totalFiles = 0;
            long totalBytes = 0L;

            @Override
            public void onStart(final int total, final long bytes) {
                totalFiles = total;
                totalBytes = bytes;
                if (total == 0) {
                    sendClientMessage("§7[WorldShare] Nothing to upload.");
                    return;
                }
                final long mb = bytes / (1024 * 1024);
                sendClientMessage("§e[WorldShare] Uploading " + total + " files ("
                        + mb + " MB)...");
            }

            @Override
            public void onFileProgress(final int filesDone,
                                       final int total,
                                       final long bytesDone,
                                       final long bytesTotal,
                                       final String currentFile) {
                if (total == 0) return;
                final long now = System.currentTimeMillis();
                final int percent = bytesTotal > 0
                        ? (int) (100L * bytesDone / bytesTotal)
                        : (int) (100L * filesDone / total);

                // Rate-limit: at most one update every 2 seconds, OR if percent
                // has crossed a 25% boundary since last update.
                final boolean dueByTime = now - lastUpdateMs >= 2000L;
                final boolean dueByPercent = percent / 25 > Math.max(0, lastReportedPercent) / 25
                        && percent > lastReportedPercent;
                if (!dueByTime && !dueByPercent) return;

                lastUpdateMs = now;
                lastReportedPercent = percent;

                final long mbDone = bytesDone / (1024 * 1024);
                final long mbTotal = bytesTotal / (1024 * 1024);
                sendClientMessage(String.format(
                        "§7[WorldShare] §f%d%%§7 - %d/%d files, %d/%d MB",
                        percent, filesDone, total, mbDone, mbTotal));
            }

            @Override
            public void onComplete() {
                // Final summary is posted by the caller after this returns.
            }

            @Override
            public void onError(final Throwable error) {
                sendClientMessage("§c[WorldShare] Sync error: " + error.getMessage());
            }
        };
    }

    private static int runPull(final CommandSourceStack source) {
        final String folderId = requireFolderId(source);
        if (folderId == null) return 0;

        final java.util.Optional<com.worldshare.mod.sync.WorldContext.CurrentWorld> ctx
                = com.worldshare.mod.sync.WorldContext.current();
        if (ctx.isEmpty()) {
            sendFeedback(source,
                    "No world is currently loaded. Open a world first.",
                    ChatFormatting.RED);
            return 0;
        }
        final com.worldshare.mod.sync.WorldContext.CurrentWorld world = ctx.get();

        sendFeedback(source,
                "WARNING: Pulling while a world is loaded may corrupt the open world. "
                + "Save and Quit first, then run /worldshare pull from a different world.",
                ChatFormatting.RED);
        sendFeedback(source,
                "Proceeding anyway...",
                ChatFormatting.GRAY);

        CloudModule.executor().submit(() -> {
            try {
                final com.worldshare.mod.sync.SyncEngine.PullResult result =
                        com.worldshare.mod.sync.SyncEngine.pull(
                                world.worldRoot, folderId, world.playerUuid);
                sendClientMessage("§a[WorldShare] Pull complete:");
                sendClientMessage("§a  downloaded: " + result.downloaded + " files");
                sendClientMessage("§7  failed: " + result.failed);
                sendClientMessage("§7  bytes: " + result.bytes + " (" + (result.bytes / (1024 * 1024)) + " MB)");
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("pull failed", t);
                sendClientMessage("§c[WorldShare] pull failed: " + t.getMessage());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ----- lock command helpers -----

    /**
     * @return the configured Drive folder ID, or null if unconfigured. In the
     *         null case, a red help message is already printed to the user
     *         and the caller should return without doing further work.
     */
    private static String requireFolderId(final CommandSourceStack source) {
        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            sendFeedback(source,
                    "No Drive folder configured. Run /worldshare setfolder <id> first.",
                    ChatFormatting.RED);
            sendFeedback(source,
                    "Get the ID from the Drive URL: drive.google.com/drive/folders/<THIS PART>",
                    ChatFormatting.GRAY);
            return null;
        }
        return folderId;
    }

    private static void printLockDetails(final SessionLock lock) {
        if (lock == null) return;
        sendClientMessage("§7         holder:      " + lock.holderName);
        sendClientMessage("§7         locked_at:   " + lock.lockedAt);
        sendClientMessage("§7         expires_at:  " + lock.expiresAt);
        sendClientMessage("§7         heartbeat:   " + lock.lastHeartbeatAt);
        sendClientMessage("§7         players:     " + lock.playersOnline()
                + " (cap " + lock.playerCap + ")");
        if (lock.relayAddress != null) {
            sendClientMessage("§7         relay:       " + lock.relayAddress);
        }
    }

    /** Compact display of a machine ID (first 8 chars). */
    private static String shortId(final String fullId) {
        if (fullId == null || fullId.length() < 8) return String.valueOf(fullId);
        return fullId.substring(0, 8);
    }

    /** Human-friendly duration formatting: "2h 15m" etc. */
    private static String humanizeDuration(final Duration d) {
        long s = Math.abs(d.getSeconds());
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        long remM = m - h * 60;
        return h + "h " + remM + "m";
    }

    // -----------------------------------------------------------------

    /** Synchronous command-feedback; safe because the command is still on the server thread. */
    private static void sendFeedback(final CommandSourceStack source,
                                     final String text,
                                     final ChatFormatting color) {
        source.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    /**
     * Send a chat message from an arbitrary background thread. Routes onto the
     * Minecraft client thread because player.displayClientMessage isn't thread-safe.
     *
     * <p>Uses {@link Minecraft#execute} which queues the runnable for the next
     * client tick.
     */
    private static void sendClientMessage(final String rawText) {
        Minecraft.getInstance().execute(() -> {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(rawText), false);
            } else {
                // No player in scope — probably at title screen. Fall back to log only.
                WorldShareMod.LOGGER.info("[test] {}", rawText);
            }
        });
    }
}
