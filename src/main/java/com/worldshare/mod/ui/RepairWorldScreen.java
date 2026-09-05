package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.util.PlayerNotice;
import com.worldshare.mod.util.WorldBackup;
import com.worldshare.mod.util.WorldSharePaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Offers to make an inconsistent Drive world consistent again from the local copy.
 *
 * <p><b>The situation this exists for.</b> A push uploads bucket archives and
 * commits the manifest describing them last, so an interrupted push leaves Drive
 * holding archives the manifest does not describe. Everyone else's pull then fails
 * verification on the affected bucket. That is normally fine - the interrupted
 * player keeps the lock and finishes the push when they come back - but if they
 * never do, the world is stuck for everybody: the stale-lock override path is
 * acquire, pull, open, and the pull fails, so it never reaches open and nobody can
 * get in to fix it.
 *
 * <p><b>Why this is a screen and not a command.</b> {@code /worldshare repair}
 * would be a smaller change, but WorldShare's commands need a loaded world and the
 * failure here is precisely not being able to load the world. The way out has to be
 * reachable from Contributor Worlds.
 *
 * <p><b>What it costs.</b> {@link SyncEngine#repair} republishes every bucket, so
 * this uploads the whole world. It also makes this player's copy authoritative,
 * discarding whatever the interrupted player never managed to publish - which is
 * the same bargain the stale-lock override already makes, and is why this is only
 * offered once their lock has gone stale.
 */
public final class RepairWorldScreen extends Screen {

    private final ContributorWorldsScreen parent;
    private final WorldStateResolver.ResolvedWorld world;
    private final UUID playerUuid;
    private final String failureDetail;

    private volatile String status = null;
    private volatile boolean working = false;
    private volatile boolean finished = false;

    public RepairWorldScreen(final ContributorWorldsScreen parent,
                             final WorldStateResolver.ResolvedWorld world,
                             final UUID playerUuid,
                             final String failureDetail) {
        super(Component.literal("Repair Shared World?"));
        this.parent = parent;
        this.world = world;
        this.playerUuid = playerUuid;
        this.failureDetail = failureDetail;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int buttonY = this.height - 55;

        if (working) return;   // no way out mid-upload; the world is being rewritten

        if (finished) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("Return to Contributor Worlds"),
                            btn -> {
                                parent.triggerRefresh();
                                Minecraft.getInstance().setScreen(parent);
                            })
                    .bounds(cx - 100, buttonY, 200, 20).build());
            return;
        }

        // Safe option on the left, destructive on the right - same arrangement as
        // ConfirmStaleLockOverrideScreen, so the muscle memory carries over.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel - wait for them"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 180, buttonY, 170, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Repair from my copy"),
                        btn -> startRepair())
                .bounds(cx + 10, buttonY, 170, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        int y = 40;

        gfx.drawCenteredString(this.font,
                Component.literal("⚠ This world's files on Drive don't match each other.")
                        .withStyle(ChatFormatting.YELLOW),
                cx, y, 0xFFFFFF);
        y += 24;

        if (status != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal(status).withStyle(
                            finished ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                    cx, y, 0xFFFFFF);
            return;
        }

        final String[] body = {
                "Someone's upload was interrupted, so part of the world on Drive",
                "is newer than the index that describes it. Until that's fixed,",
                "nobody can download this world.",
                "",
                "Repairing uploads YOUR copy of the entire world and rebuilds",
                "the index to match it.",
                "",
                "Anything " + holderName() + " never finished uploading will be lost.",
                "Your local copy is backed up first.",
        };

        int blockWidth = 0;
        for (final String line : body) {
            blockWidth = Math.max(blockWidth, this.font.width(line));
        }

        for (final String line : body) {
            if (!line.isEmpty()) {
                gfx.drawCenteredString(this.font, Component.literal(line), cx, y, 0xCCCCCC);
            }
            y += this.font.lineHeight + 2;
        }

        if (failureDetail != null) {
            // Wrapped to the block above rather than to the window. Wrapping at a
            // fraction of the screen let this run to a single line several times
            // wider than the hand-wrapped text it sits under, which on a wide
            // monitor reads as a stray sentence rather than part of the same
            // message. Grey made it worse; it is the one line naming the actual
            // problem, so it gets the same weight as everything else.
            y += 8;
            for (final net.minecraft.util.FormattedCharSequence line
                    : this.font.split(Component.literal(failureDetail), blockWidth)) {
                gfx.drawCenteredString(this.font, line, cx, y, 0xCCCCCC);
                y += this.font.lineHeight + 2;
            }
        }
    }

    private String holderName() {
        return (world.lock != null && world.lock.holderName != null)
                ? world.lock.holderName : "the other player";
    }

    private void startRepair() {
        working = true;
        status = "Backing up your copy...";
        Minecraft.getInstance().execute(() -> {
            this.clearWidgets();
            this.init();
        });

        final RemoteFileSet remote = world.subscription.remote;
        final Path localWorld = WorldSharePaths.gameDir()
                .resolve("saves").resolve(world.subscription.localFolderName);

        CloudModule.executor().submit(() -> {
            try {
                WorldBackup.create(localWorld);

                status = "Taking the session lock...";
                // Override: repairing is an explicit decision to republish over whatever is there.
                LockManager.acquire(remote, true);

                status = "Uploading your copy of the world...";
                final SyncEngine.PushResult result =
                        SyncEngine.repair(localWorld, remote, playerUuid, statusProgress());

                LockManager.release();

                status = "✅ Repaired - " + result.bucketsUploaded
                        + " bucket(s) republished. Anyone can open this world now.";
                WorldShareMod.LOGGER.info("RepairWorldScreen: repair complete, {} bucket(s)",
                        result.bucketsUploaded);
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("RepairWorldScreen: repair failed", t);
                status = "❌ Repair failed: " + t.getMessage();
                PlayerNotice.error("§c[WorldShare] Repair failed: " + t.getMessage());
                // The lock is deliberately not released here. A repair that failed
                // partway has left Drive no better than it found it, and handing the
                // lock on would invite the next player into the same broken state.
            } finally {
                working = false;
                finished = true;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            }
        });
    }

    /** Progress straight into the status line; there's no bar on this screen. */
    private com.worldshare.mod.sync.SyncProgress statusProgress() {
        return new com.worldshare.mod.sync.SyncProgress() {
            @Override public void onStart(final int totalFiles, final long totalBytes) {
                status = "Uploading " + totalFiles + " files ("
                        + (totalBytes / (1024 * 1024)) + " MB)...";
            }
            @Override public void onFileProgress(final int filesDone, final int totalFiles,
                                                 final long bytesDone, final long totalBytes,
                                                 final String currentFile) {
                status = "Uploading " + currentFile + " - " + filesDone + " / " + totalFiles
                        + " files";
            }
            @Override public void onComplete() {}
            @Override public void onError(final Throwable error) {}
        };
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !working;
    }
}
