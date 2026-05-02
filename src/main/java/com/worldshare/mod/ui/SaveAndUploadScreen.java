package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.sync.OnlineChecker;
import com.worldshare.mod.sync.AutoSyncListener;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.sync.SyncProgress;
import com.worldshare.mod.sync.WorldContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Full-screen UI that runs after the user clicks "Save and Upload" from the
 * pause menu. Shows progress as the world is pushed to Drive, then returns
 * to the title screen.
 *
 * <p>Flow:
 * <ol>
 *   <li>Online check (10s timeout)</li>
 *   <li>Wait for Minecraft to finish saving (handled by closing world before us)</li>
 *   <li>Push world to Drive with live progress bar</li>
 *   <li>Release session lock</li>
 *   <li>Return to title screen</li>
 * </ol>
 *
 * <p>After 30 seconds, a "Continue in Background" button appears. If clicked,
 * the user returns to the title screen while the push continues in the
 * background — we'll write a chat-style toast when done.
 */
public final class SaveAndUploadScreen extends Screen {

    private static final long BACKGROUND_BUTTON_DELAY_MS = 30_000L;

    /** Cached state for thread-safe display. Updated from CloudModule executor. */
    private volatile String stage = "Checking connection to Drive...";
    private volatile int filesDone = 0;
    private volatile int totalFiles = 0;
    private volatile long bytesDone = 0L;
    private volatile long totalBytes = 0L;
    private volatile boolean done = false;
    private volatile boolean errored = false;
    private volatile String errorMessage = null;
    /** Set when {@link #done} or {@link #errored} becomes true; used to delay returnToTitle so the user sees the result. */
    private volatile long finishTime = 0L;

    /** Set when the user clicks "Continue in Background" so we don't double-handle exit. */
    private final AtomicBoolean userContinuedInBackground = new AtomicBoolean(false);

    /** When the screen first opened, in millis. Used to gate the bg button. */
    private final long openTime = System.currentTimeMillis();

    /** Set true the first time we kick off the orchestrator. Prevents re-launch on window resize. */
    private final AtomicBoolean syncStarted = new AtomicBoolean(false);

    /** The world we're syncing. Captured at construction time before the world unloads. */
    private final Path worldRoot;
    private final UUID playerUuid;
    private final String worldName;

    private Button backgroundButton;
    private Button cancelButton;
    /** Shown only on error - lets user dismiss the error screen on their own time. */
    private Button returnButton;

    public SaveAndUploadScreen(final Path worldRoot, final UUID uuid, final String name) {
        super(Component.literal("Save and Upload to Drive"));
        this.worldRoot = worldRoot;
        this.playerUuid = uuid;
        this.worldName = name == null ? "(world)" : name;
    }

    @Override
    protected void init() {
        // Cancel button (always visible) - returns to title without uploading.
        // The lock is still released and local files are preserved.
        cancelButton = Button.builder(
                Component.literal("Cancel — return to title (don't upload)"),
                btn -> onCancel())
                .bounds(this.width / 2 - 130, this.height - 50, 260, 20)
                .build();
        this.addRenderableWidget(cancelButton);

        // "Continue in Background" button - hidden initially, shown after delay.
        backgroundButton = Button.builder(
                Component.literal("Continue in Background"),
                btn -> onContinueInBackground())
                .bounds(this.width / 2 - 130, this.height - 75, 260, 20)
                .build();
        backgroundButton.visible = false;
        this.addRenderableWidget(backgroundButton);

        // "Return to Title" button - shown only on error so the user can dismiss
        // when they're ready (instead of being kicked out by an auto-timeout).
        returnButton = Button.builder(
                Component.literal("Return to Title"),
                btn -> returnToTitle())
                .bounds(this.width / 2 - 130, this.height - 50, 260, 20)
                .build();
        returnButton.visible = false;
        this.addRenderableWidget(returnButton);

        // If error already happened (e.g. quick-fail before init ran), show button now.
        if (errored) {
            if (cancelButton != null) cancelButton.visible = false;
            if (backgroundButton != null) backgroundButton.visible = false;
            returnButton.visible = true;
        }

        // Kick off the actual work the first time init runs. init() can be
        // called again on window resize, so we use an atomic guard to ensure
        // the orchestrator thread is only spawned once.
        if (syncStarted.compareAndSet(false, true)) {
            startSyncFlow();
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Reveal the "Continue in Background" button after the delay,
        // but only if push is actually still running.
        if (!done && !errored && !backgroundButton.visible
                && System.currentTimeMillis() - openTime > BACKGROUND_BUTTON_DELAY_MS) {
            backgroundButton.visible = true;
        }

        // Auto-return to title once done or errored.
        // - Success: wait 1.5s so the user sees "Done!"
        // - Error:   wait 6s so the user has time to read the message,
        //            and reveal a "Return to Title" button so they can dismiss earlier
        //            (or stay longer, if they need to copy the error)
        if (done || errored) {
            if (finishTime == 0L) {
                finishTime = System.currentTimeMillis();
                // On error, swap the button labels: hide Cancel/Background, show Return to Title.
                if (errored) {
                    if (cancelButton != null) cancelButton.visible = false;
                    if (backgroundButton != null) backgroundButton.visible = false;
                    if (returnButton != null) returnButton.visible = true;
                }
            }
            final long autoReturnDelayMs = errored ? 6000L : 1500L;
            if (!userContinuedInBackground.get()
                    && System.currentTimeMillis() - finishTime > autoReturnDelayMs) {
                returnToTitle();
            }
        }
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY, final float partial) {
        gfx.fill(0, 0, this.width, this.height, 0xFF1a1a1a);


        // Everything drawn AFTER super.render() is above the blur layer
        // Draw solid backdrop panel
        final int panelX = this.width / 2 - 200;
        final int panelY = 25;
        final int panelW = 400;
        final int panelH = 175;
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC000000);

        // Title
        gfx.drawCenteredString(
                this.font,
                Component.literal("Save and Upload — '" + worldName + "'")
                        .withStyle(ChatFormatting.YELLOW),
                this.width / 2, 40, 0xFFFFFF);

        // Stage text.
        gfx.drawCenteredString(
                this.font,
                Component.literal(stage),
                this.width / 2,
                80,
                errored ? 0xFF5555 : 0xFFFFFF);

        // Progress bar (only when we have data to show).
        if (totalFiles > 0) {
            final int barW = 300;
            final int barH = 20;
            final int barX = (this.width - barW) / 2;
            final int barY = 120;
            // Background (dark gray)
            gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
            gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            // Fill (green)
            final int fillPx = totalBytes > 0
                    ? (int) (barW * Math.min(1.0, (double) bytesDone / totalBytes))
                    : (int) (barW * Math.min(1.0, (double) filesDone / totalFiles));
            gfx.fill(barX, barY, barX + fillPx, barY + barH, errored ? 0xFFAA3333 : 0xFF44AA44);

            // Percentage.
            final int pct = totalBytes > 0
                    ? (int) (100L * bytesDone / Math.max(1L, totalBytes))
                    : (int) (100L * filesDone / Math.max(1, totalFiles));
            gfx.drawCenteredString(
                    this.font,
                    Component.literal(pct + "%"),
                    this.width / 2,
                    barY + (barH - this.font.lineHeight) / 2,
                    0xFFFFFF);

            // Detail line.
            final long mbDone = bytesDone / (1024 * 1024);
            final long mbTotal = totalBytes / (1024 * 1024);
            gfx.drawCenteredString(
                    this.font,
                    Component.literal(filesDone + " / " + totalFiles + " files  ·  "
                            + mbDone + " / " + mbTotal + " MB"),
                    this.width / 2,
                    barY + barH + 10,
                    0xCCCCCC);
        }

        if (errored && errorMessage != null) {
            // Wrap the error message into multiple lines so long messages
            // don't trail off the edges of the screen.
            final int maxWidth = (int) (this.width * 0.8);
            final net.minecraft.network.chat.Component errComponent =
                    Component.literal(errorMessage).withStyle(ChatFormatting.RED);
            final java.util.List<net.minecraft.util.FormattedCharSequence> lines =
                    this.font.split(errComponent, maxWidth);
            int y = 180;
            for (final net.minecraft.util.FormattedCharSequence line : lines) {
                gfx.drawCenteredString(this.font, line, this.width / 2, y, 0xFFAAAA);
                y += this.font.lineHeight + 2;
            }
        }

        super.render(gfx, mouseX, mouseY, partial);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Don't let escape kill the upload silently — user must explicitly
        // hit Cancel or Continue in Background.
        return false;
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Don't blur — we want our progress UI to be readable
    }

    // ---- Flow orchestration ----

    private void startSyncFlow() {
        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            // Not a WorldShare world. Just go back to title.
            stage = "(no Drive folder configured — skipping upload)";
            done = true;
            return;
        }

        stage = "Waiting for Minecraft to finish saving...";
        // Spawn a worker thread (NOT the cloud executor — see comment below) that
        // will wait for ServerStopped, do the online check, then submit the actual
        // upload to the cloud executor.
        //
        // We can't use CloudModule.executor() for the orchestration because:
        //   1. OnlineChecker submits to that executor and waits for the result
        //      via future.get(). If the orchestrator runs on the same single
        //      executor, it deadlocks itself.
        //   2. We need to wait (potentially seconds) for MC's save to complete,
        //      and we don't want to monopolize the cloud executor for that.
        final Thread orchestrator = new Thread(() -> {
            try {
                // 1. Wait for the integrated server to fully stop. AutoSyncListener
                //    sets a flag in onServerStopped. If the user closed via X button
                //    before reaching us, the flag may already be set.
                if (!waitForServerStopped()) {
                    errorMessage = "Server didn't stop within 30 seconds. "
                            + "Local changes are preserved; retry with /worldshare push.";
                    errored = true;
                    return;
                }

                stage = "Checking connection to Drive...";

                // 2. Online check (this internally uses CloudModule.executor()).
                final OnlineChecker.Result online = OnlineChecker.check(folderId);
                if (online == OnlineChecker.Result.OFFLINE) {
                    errorMessage = "Drive is unreachable. Local changes are preserved; "
                            + "they'll upload next time you have internet.";
                    errored = true;
                    return;
                }
                if (online == OnlineChecker.Result.NOT_AUTHENTICATED) {
                    errorMessage = "Not signed in to Drive. Run /worldshare test on next launch.";
                    errored = true;
                    return;
                }

                // 3. Build the progress callback.
                final SyncProgress prog = new SyncProgress() {
                    @Override
                    public void onStart(final int totalF, final long totalB) {
                        totalFiles = totalF;
                        totalBytes = totalB;
                    }
                    @Override
                    public void onFileProgress(final int fd, final int tf,
                                               final long bd, final long tb,
                                               final String currentFile) {
                        filesDone = fd;
                        totalFiles = tf;
                        bytesDone = bd;
                        totalBytes = tb;
                        stage = "Uploading: " + currentFile;
                    }
                    @Override
                    public void onComplete() {}
                    @Override
                    public void onError(final Throwable error) {}
                };

                // 4. Submit the actual push to the cloud executor (and wait).
                stage = "Uploading world to Drive...";
                final java.util.concurrent.CompletableFuture<SyncEngine.PushResult> pushFuture
                        = new java.util.concurrent.CompletableFuture<>();
                CloudModule.executor().submit(() -> {
                    try {
                        final SyncEngine.PushResult r = SyncEngine.push(
                                worldRoot, folderId, playerUuid, /*baseline*/ null, prog);
                        pushFuture.complete(r);
                    } catch (final Throwable t) {
                        pushFuture.completeExceptionally(t);
                    }
                });
                final SyncEngine.PushResult result = pushFuture.get();

                // 5. Release lock (also via cloud executor for serialization).
                stage = "Releasing session lock...";
                final java.util.concurrent.CompletableFuture<Void> releaseFuture
                        = new java.util.concurrent.CompletableFuture<>();
                CloudModule.executor().submit(() -> {
                    try {
                        if (LockManager.weHoldLock()) {
                            LockManager.release();
                        }
                        releaseFuture.complete(null);
                    } catch (final Throwable t) {
                        releaseFuture.completeExceptionally(t);
                    }
                });
                releaseFuture.get();

                // 6. Done.
                if (result.failed == 0) {
                    stage = "✅ Done! " + result.uploaded + " files synced.";
                    done = true;
                } else {
                    errorMessage = result.failed + " files failed to upload. "
                            + "Local changes are preserved.";
                    errored = true;
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("SaveAndUpload flow failed", t);
                errorMessage = "Upload failed: " + t.getMessage();
                errored = true;
            }
        }, "WorldShare-SaveAndUpload");
        orchestrator.setDaemon(true);
        orchestrator.start();
    }

    /**
     * Block until {@link com.worldshare.mod.sync.AutoSyncListener#serverHasStopped()}
     * reports the integrated server has finished. Returns true if it stopped within
     * the timeout, false otherwise.
     */
    private boolean waitForServerStopped() throws InterruptedException {
        com.worldshare.mod.WorldShareMod.LOGGER.info(
                "SaveAndUploadScreen: waitForServerStopped starting (initial flag={})",
                com.worldshare.mod.sync.AutoSyncListener.serverHasStopped());
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (com.worldshare.mod.sync.AutoSyncListener.serverHasStopped()) {
                com.worldshare.mod.WorldShareMod.LOGGER.info(
                        "SaveAndUploadScreen: detected serverHasStopped = true, proceeding");
                return true;
            }
            Thread.sleep(100L);
        }
        com.worldshare.mod.WorldShareMod.LOGGER.warn(
                "SaveAndUploadScreen: waitForServerStopped TIMED OUT (flag still={})",
                com.worldshare.mod.sync.AutoSyncListener.serverHasStopped());
        return false;
    }

    private void onCancel() {
        // User explicitly canceled. Local changes are preserved on disk.
        // Release the lock on a NEW thread (not the cloud executor) so it
        // happens immediately rather than queuing behind any in-flight push.
        new Thread(() -> {
            try {
                if (LockManager.weHoldLock()) {
                    LockManager.release();
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn("Cancel: lock release failed", t);
            }
        }, "WorldShare-CancelRelease").start();
        returnToTitle();
    }

    private void onContinueInBackground() {
        userContinuedInBackground.set(true);
        // The push is already running on the executor — it will finish on its own.
        // AutoSyncListener-style notification is built into SyncEngine paths;
        // we just leave the screen.
        returnToTitle();
    }

    private void returnToTitle() {
        // Clear the suppression token — if we're handed off to AutoSyncListener
        // for any future world, it should run normally.
        com.worldshare.mod.sync.AutoSyncListener.clearSuppressionToken();
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
    }

    /**
     * Static helper for the pause-menu button click handler. Unloads the world,
     * waits for the integrated server to fully stop, then opens this screen.
     */
    public static void launchFromPauseMenu() {
        final Minecraft mc = Minecraft.getInstance();
        final WorldContext.CurrentWorld current = WorldContext.current().orElse(null);

        if (current == null) {
            mc.level.disconnect();
            if (mc.isLocalServer()) {
                mc.disconnect(new GenericMessageScreen(
                        Component.translatable("menu.savingLevel")));
            } else {
                mc.disconnect();
            }
            mc.setScreen(new TitleScreen());
            return;
        }

        // ⚠️ MUST set token BEFORE disconnect. ServerStoppedEvent fires
        // INSIDE mc.disconnect(Screen), and AutoSyncListener checks the token then.
        final Object token = new Object();
        AutoSyncListener.setSuppressionToken(token);

        mc.level.disconnect();

        // ⚠️ MUST use the Screen overload for singleplayer.
        // mc.disconnect(Screen) BLOCKS the render thread until the integrated
        // server is fully stopped. ServerStopping and ServerStopped fire inside
        // this call. mc.disconnect() no-arg is the multiplayer path and does NOT
        // stop the integrated server — waitForServerStopped() will time out.
        if (mc.isLocalServer()) {
            mc.disconnect(new GenericMessageScreen(
                    Component.translatable("menu.savingLevel")));
        } else {
            mc.disconnect();
        }

        // disconnect(Screen) returned = server is stopped = serverHasStopped = true
        mc.setScreen(new SaveAndUploadScreen(
                current.worldRoot, current.playerUuid, current.name));
    }
}
