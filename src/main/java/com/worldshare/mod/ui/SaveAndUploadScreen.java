package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.sync.AutoSyncListener;
import com.worldshare.mod.sync.OnlineChecker;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.sync.SyncProgress;
import com.worldshare.mod.sync.WorldContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen UI that runs after the user clicks "Save and Upload" from the
 * pause menu. Shows progress as the world is pushed to Drive, then returns
 * to the title screen.
 *
 * <p>Flow:
 * <ol>
 *   <li>Wait for Minecraft to finish saving ({@link AutoSyncListener#serverHasStopped()})</li>
 *   <li>Online check (Drive reachability)</li>
 *   <li>Push world to Drive with live progress bar</li>
 *   <li>Release session lock</li>
 *   <li>Return to title screen</li>
 * </ol>
 *
 * <p>After 30 seconds, a "Continue in Background" button appears.
 *
 * <p><b>M5 change:</b> The Drive folder ID is now read from the world's
 * {@code worldshare-link.json} file rather than the global config. This
 * supports multiple worlds each linked to their own Drive folder.
 */
public final class SaveAndUploadScreen extends Screen {

    private static final long BACKGROUND_BUTTON_DELAY_MS = 30_000L;

    private volatile String stage = "Checking connection to Drive...";
    private volatile int filesDone = 0;
    private volatile int totalFiles = 0;
    private volatile long bytesDone = 0L;
    private volatile long totalBytes = 0L;
    private volatile boolean done = false;
    private volatile boolean errored = false;
    private volatile String errorMessage = null;
    private volatile long finishTime = 0L;

    private final AtomicBoolean userContinuedInBackground = new AtomicBoolean(false);
    private final long openTime = System.currentTimeMillis();
    private final AtomicBoolean syncStarted = new AtomicBoolean(false);

    private final Path worldRoot;
    private final UUID playerUuid;
    private final String worldName;

    private Button backgroundButton;
    private Button returnButton;

    public SaveAndUploadScreen(final Path worldRoot, final UUID uuid, final String name) {
        super(Component.literal("Save and Upload to Drive"));
        this.worldRoot = worldRoot;
        this.playerUuid = uuid;
        this.worldName = name == null ? "(world)" : name;
    }

    @Override
    protected void init() {

        backgroundButton = Button.builder(
                Component.literal("Continue in Background"),
                btn -> onContinueInBackground())
                .bounds(this.width / 2 - 130, this.height - 75, 260, 20)
                .build();
        backgroundButton.visible = false;
        this.addRenderableWidget(backgroundButton);

        returnButton = Button.builder(
                Component.literal("Return to Title"),
                btn -> returnToTitle())
                .bounds(this.width / 2 - 130, this.height - 50, 260, 20)
                .build();
        returnButton.visible = false;
        this.addRenderableWidget(returnButton);

        if (errored) {
            if (backgroundButton != null) backgroundButton.visible = false;
            returnButton.visible = true;
        }

        if (syncStarted.compareAndSet(false, true)) {
            startSyncFlow();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!done && !errored && !backgroundButton.visible
                && System.currentTimeMillis() - openTime > BACKGROUND_BUTTON_DELAY_MS) {
            backgroundButton.visible = true;
        }

        if (done || errored) {
            if (finishTime == 0L) {
                finishTime = System.currentTimeMillis();
                if (errored) {
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
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        this.renderBackground(gfx, mouseX, mouseY, partial);

        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;

        gfx.drawCenteredString(this.font,
                Component.literal("Save and Upload - '" + worldName + "'")
                        .withStyle(ChatFormatting.YELLOW),
                cx, 40, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal(stage),
                cx, 80, errored ? 0xFF5555 : 0xFFFFFF);

        if (totalFiles > 0) {
            final int barW = 300;
            final int barH = 20;
            final int barX = (this.width - barW) / 2;
            final int barY = 120;
            gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
            gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            final int fillPx = totalBytes > 0
                    ? (int) (barW * Math.min(1.0, (double) bytesDone / totalBytes))
                    : (int) (barW * Math.min(1.0, (double) filesDone / totalFiles));
            gfx.fill(barX, barY, barX + fillPx, barY + barH,
                    errored ? 0xFFAA3333 : 0xFF44AA44);

            final int pct = totalBytes > 0
                    ? (int) (100L * bytesDone / Math.max(1L, totalBytes))
                    : (int) (100L * filesDone / Math.max(1, totalFiles));
            gfx.drawCenteredString(this.font,
                    Component.literal(pct + "%"),
                    cx, barY + (barH - this.font.lineHeight) / 2, 0xFFFFFF);

            final long mbDone = bytesDone / (1024 * 1024);
            final long mbTotal = totalBytes / (1024 * 1024);
            gfx.drawCenteredString(this.font,
                    Component.literal(filesDone + " / " + totalFiles + " files  -  "
                            + mbDone + " / " + mbTotal + " MB"),
                    cx, barY + barH + 10, 0xCCCCCC);
        }

        if (errored && errorMessage != null) {
            final int maxWidth = (int) (this.width * 0.8);
            final Component errComponent =
                    Component.literal(errorMessage).withStyle(ChatFormatting.RED);
            final java.util.List<net.minecraft.util.FormattedCharSequence> lines =
                    this.font.split(errComponent, maxWidth);
            int y = 180;
            for (final net.minecraft.util.FormattedCharSequence line : lines) {
                gfx.drawCenteredString(this.font, line, cx, y, 0xFFAAAA);
                y += this.font.lineHeight + 2;
            }
        }
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {
        // Disable blur so the progress bar is readable.
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ---- Flow orchestration ----

    private void startSyncFlow() {
        // M5: read Drive folder from the world's link file, not the global config.
        // This supports multiple worlds each linked to their own Drive folder.
        final String folderId = WorldLink.readFolderId(worldRoot);
        if (folderId == null || folderId.isBlank()) {
            stage = "(world is not linked to Drive - skipping upload)";
            done = true;
            return;
        }

        if (!LockManager.weHoldLock()) {
            stage = "[!] No session lock held - upload blocked.";
            errorMessage = "You must hold the session lock to upload. "
                    + "Run /worldshare lock first, or open this world "
                    + "via Contributor Worlds for automatic lock management.";
            errored = true;
            return;
        }

        stage = "Waiting for Minecraft to finish saving...";

        final Thread orchestrator = new Thread(() -> {
            try {
                if (!waitForServerStopped()) {
                    errorMessage = "Server didn't stop within 30 seconds. "
                            + "Local changes are preserved; retry with /worldshare push.";
                    errored = true;
                    return;
                }

                stage = "Checking connection to Drive...";

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
                    @Override public void onComplete() {}
                    @Override public void onError(final Throwable error) {}
                };

                stage = "Uploading world to Drive...";
                final java.util.concurrent.CompletableFuture<SyncEngine.PushResult> pushFuture
                        = new java.util.concurrent.CompletableFuture<>();
                CloudModule.executor().submit(() -> {
                    try {
                        final SyncEngine.PushResult r = SyncEngine.push(
                                worldRoot, folderId, playerUuid, null, prog);
                        pushFuture.complete(r);
                    } catch (final Throwable t) {
                        pushFuture.completeExceptionally(t);
                    }
                });
                final SyncEngine.PushResult result = pushFuture.get();

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

                if (result.failed == 0) {
                    stage = "\u2705 Done! " + result.uploaded + " files synced.";
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

    private boolean waitForServerStopped() throws InterruptedException {
        WorldShareMod.LOGGER.info(
                "SaveAndUploadScreen: waitForServerStopped starting (initial flag={})",
                AutoSyncListener.serverHasStopped());
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (AutoSyncListener.serverHasStopped()) {
                WorldShareMod.LOGGER.info(
                        "SaveAndUploadScreen: detected serverHasStopped = true, proceeding");
                return true;
            }
            Thread.sleep(100L);
        }
        WorldShareMod.LOGGER.warn(
                "SaveAndUploadScreen: waitForServerStopped TIMED OUT (flag still={})",
                AutoSyncListener.serverHasStopped());
        return false;
    }

    private void onContinueInBackground() {
        userContinuedInBackground.set(true);
        returnToTitle();
    }

    private void returnToTitle() {
        AutoSyncListener.clearSuppressionToken();
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new TitleScreen()));
    }

    /**
     * Static helper called from {@link PauseMenuHijacker}. Disconnects from
     * the world and opens this screen to handle the sync.
     *
     * <p>Uses the vanilla PauseScreen disconnect sequence:
     * {@code mc.level.disconnect()} + {@code mc.disconnect(GenericMessageScreen)}
     * for singleplayer. The Screen-arg overload blocks the render thread until
     * the integrated server stops, which is what we want — ServerStopped fires
     * inside that call, setting {@link AutoSyncListener#serverHasStopped()} to
     * true before we return.
     */
    public static void launchFromPauseMenu() {
        final Minecraft mc = Minecraft.getInstance();
        final WorldContext.CurrentWorld current = WorldContext.current().orElse(null);

        if (current == null) {
            // No world context — fall back to vanilla behavior.
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

        // ⚠️ MUST set suppression token BEFORE disconnect. ServerStoppedEvent fires
        // inside mc.disconnect(Screen), and AutoSyncListener checks the token then.
        // Token set after disconnect = race condition = double push.
        // CRITICAL: Set suppression token BEFORE disconnect. ServerStoppedEvent fires
// inside mc.disconnect(Screen), and AutoSyncListener checks the token there.
// Token set after disconnect = race condition = double push.
// The token is cleared by returnToTitle() when the screen finishes — DO NOT
// wrap this in a local try/finally, the work that needs the token set
// happens in SaveAndUploadScreen AFTER this method returns.
        final Object token = new Object();
        AutoSyncListener.setSuppressionToken(token);

        mc.level.disconnect();

        // ⚠️ Use the Screen overload for singleplayer — this is the path that
        // stops the integrated server and fires ServerStopping/ServerStopped.
        // mc.disconnect() no-arg is the multiplayer path and does NOT stop the
        // integrated server — waitForServerStopped() would time out.
        if (mc.isLocalServer()) {
            mc.disconnect(new GenericMessageScreen(
                    Component.translatable("menu.savingLevel")));
        } else {
            mc.disconnect();
        }

        // disconnect(Screen) has returned — server is stopped — serverHasStopped = true.
        mc.setScreen(new SaveAndUploadScreen(
                current.worldRoot, current.playerUuid, current.name));
    }
}
