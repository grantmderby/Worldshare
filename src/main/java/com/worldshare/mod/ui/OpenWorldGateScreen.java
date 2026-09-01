package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.sync.SyncActivity;
import com.worldshare.mod.util.WorldSharePaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown when someone opens a shared world from the vanilla Singleplayer list.
 *
 * <p>That list gives no hint that a world is shared, so opening one there is easy
 * to do by accident - and depending on the lock, it can mean a session whose
 * changes never reach Drive, or one that competes with an upload for the same
 * files. WorldShare used to warn in chat once the world had already loaded, which
 * is both too late to act on and easy to scroll past.
 *
 * <p><b>The recovery is a button, not an instruction.</b> "Save and quit, then open
 * via Contributor Worlds" asks someone to undo what they just did, so the screen
 * offers to do it for them. That is the difference between a warning people read
 * and a warning people dismiss.
 *
 * <p>Constructed by {@code WorldListEntryMixin}, which intercepts every route into
 * the world list's open action.
 */
public final class OpenWorldGateScreen extends Screen {

    /** What the local state alone can tell us, before any Drive call. */
    private enum Verdict {
        /** A sync for this world is in flight. No way through. */
        UPLOADING,
        /** Linked, but this session holds no lock on it. */
        UNLOCKED
    }

    private final Screen parent;
    private final String levelId;
    private final String displayName;
    private final RemoteFileSet remote;
    private final Verdict verdict;

    /**
     * Refined lock state from Drive, once it arrives. Null until then.
     *
     * <p>Fetched after the screen is already up rather than before it, because this
     * is built on the render thread in response to a click - blocking there to make
     * a network call would freeze the game on every world open.
     */
    private volatile LockManager.LockStatus driveStatus = null;
    private volatile boolean checkingDrive = false;
    private volatile boolean driveChecked = false;

    private OpenWorldGateScreen(final Screen parent, final String levelId,
                                final String displayName, final RemoteFileSet remote,
                                final Verdict verdict) {
        super(Component.literal("Shared World"));
        this.parent = parent;
        this.levelId = levelId;
        this.displayName = displayName;
        this.remote = remote;
        this.verdict = verdict;
    }

    /**
     * @return a gate screen, or null if this world should just open normally -
     *         either it isn't a WorldShare world, or this session already holds its
     *         lock, which means it was opened through the proper flow and nobody
     *         else can have pushed since
     */
    public static OpenWorldGateScreen forWorld(final String levelId, final Screen parent) {
        try {
            final Path worldRoot = WorldSharePaths.gameDir().resolve("saves").resolve(levelId);
            final WorldLink link = WorldLink.read(worldRoot);
            if (link == null) {
                return null;   // not a shared world
            }
            final RemoteFileSet remote = link.remote;
            final String name = link.displayName == null ? levelId : link.displayName;

            if (SyncActivity.isSyncing()) {
                return new OpenWorldGateScreen(parent, levelId, name, remote, Verdict.UPLOADING);
            }
            if (remote != null && LockManager.weHoldLock(remote)) {
                // We hold this world's lock in this session, so it was opened
                // through Contributor Worlds and nobody else can have pushed.
                // Opening it again is the same session continuing.
                return null;
            }
            return new OpenWorldGateScreen(parent, levelId, name, remote, Verdict.UNLOCKED);
        } catch (final Throwable t) {
            // Never let this stop somebody opening a world. Failing open is the
            // right direction: the worst case is the old behaviour.
            WorldShareMod.LOGGER.warn("OpenWorldGateScreen: couldn't evaluate '{}'", levelId, t);
            return null;
        }
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int buttonY = this.height - 60;

        if (verdict == Verdict.UPLOADING) {
            // The only hard stop. Opening now would have Minecraft rewriting chunks
            // underneath the pack; the push detects it and refuses, but the player
            // loses the upload for no reason.
            this.addRenderableWidget(Button.builder(
                            Component.literal("Back"),
                            b -> Minecraft.getInstance().setScreen(parent))
                    .bounds(cx - 100, buttonY, 200, 20).build());
            return;
        }

        // Ours on Drive but not in this session - the restart case. machine_id is on
        // disk so Drive still says the lock is ours, but the in-memory record went
        // with the last process, so nothing here would sync.
        final boolean resumable = driveStatus != null
                && (driveStatus.state == LockManager.LockState.HELD_BY_US
                    || driveStatus.state == LockManager.LockState.HELD_BY_US_EXPIRED);

        this.addRenderableWidget(Button.builder(
                        Component.literal(resumable
                                ? "Resume this session properly"
                                : "Open via Contributor Worlds"),
                        b -> Minecraft.getInstance().setScreen(new ContributorWorldsScreen()))
                .bounds(cx - 155, buttonY, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Play offline"),
                        b -> openAnyway())
                .bounds(cx + 55, buttonY, 100, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Back"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 100, buttonY + 24, 200, 20).build());

        if (!checkingDrive && !driveChecked && remote != null) {
            startDriveCheck();
        }
    }

    /**
     * Ask Drive who holds the lock, then rebuild with a more specific message.
     *
     * <p>Optional refinement, not a gate. The screen is already usable without it;
     * this only turns "nobody has a lock here as far as this game knows" into
     * naming the player who does.
     */
    private void startDriveCheck() {
        checkingDrive = true;
        CloudModule.executor().submit(() -> {
            try {
                driveStatus = LockManager.readStatus(remote);
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.debug("OpenWorldGateScreen: lock check failed", t);
            } finally {
                driveChecked = true;
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen == this) {
                        this.clearWidgets();
                        this.init();
                    }
                });
            }
        });
    }

    private void openAnyway() {
        // Deliberately reachable. Vanilla access is how someone rescues a world
        // whose sync is broken, so this must not become a door that only locks.
        WorldShareMod.LOGGER.info(
                "OpenWorldGateScreen: player chose to open '{}' without syncing", levelId);
        Minecraft.getInstance().setScreen(parent);
        ContributorWorldsScreen.openWorldLocally(levelId, parent);
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        int y = 50;

        gfx.drawCenteredString(this.font,
                Component.literal("'" + displayName + "' is a shared world")
                        .withStyle(ChatFormatting.YELLOW),
                cx, y, 0xFFFFFF);
        y += 26;

        for (final String line : bodyLines()) {
            if (!line.isEmpty()) {
                gfx.drawCenteredString(this.font, Component.literal(line), cx, y, 0xCCCCCC);
            }
            y += this.font.lineHeight + 3;
        }
    }

    private List<String> bodyLines() {
        final List<String> lines = new ArrayList<>();
        if (verdict == Verdict.UPLOADING) {
            lines.add("It's still uploading to Drive.");
            lines.add("");
            lines.add("Opening it now would interrupt the upload, and the save");
            lines.add("would be refused rather than reaching the other players.");
            lines.add("");
            lines.add("Wait for it to finish, then open it again.");
            return lines;
        }

        if (!driveChecked) {
            lines.add("Checking who has it open...");
            lines.add("");
        } else if (driveStatus != null) {
            switch (driveStatus.state) {
                case HELD_BY_US, HELD_BY_US_EXPIRED -> {
                    lines.add("WorldShare has you down as mid-session here, from before");
                    lines.add("the game was last closed.");
                    lines.add("");
                    lines.add("Resuming keeps your progress and finishes the sync.");
                    lines.add("Playing offline leaves it unsynced.");
                    return lines;
                }
                case HELD_BY_OTHER -> {
                    final String who = driveStatus.lock != null
                            && driveStatus.lock.holderName != null
                            ? driveStatus.lock.holderName : "Someone else";
                    lines.add(who + " is playing this world right now.");
                    lines.add("");
                    lines.add("Anything you do here will be lost the next time you");
                    lines.add("open it properly - their save is the one that counts.");
                    return lines;
                }
                default -> { }
            }
        }

        lines.add("Changes made here will NOT be saved to Drive,");
        lines.add("and your copy may already be out of date.");
        lines.add("");
        lines.add("Open it through Contributor Worlds to sync properly.");
        return lines;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
