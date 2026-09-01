package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
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
    private final Verdict verdict;

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;

    private OpenWorldGateScreen(final Screen parent, final String levelId,
                                final String displayName, final Verdict verdict) {
        super(Component.literal("Shared World"));
        this.parent = parent;
        this.levelId = levelId;
        this.displayName = displayName;
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
                return new OpenWorldGateScreen(parent, levelId, name, Verdict.UPLOADING);
            }
            if (remote != null && LockManager.weHoldLock(remote)) {
                // We hold this world's lock in this session, so it was opened
                // through Contributor Worlds and nobody else can have pushed.
                // Opening it again is the same session continuing.
                return null;
            }
            return new OpenWorldGateScreen(parent, levelId, name, Verdict.UNLOCKED);
        } catch (final Throwable t) {
            // Never let this stop somebody opening a world. Failing open is the
            // right direction: the worst case is the old behaviour.
            WorldShareMod.LOGGER.warn("OpenWorldGateScreen: couldn't evaluate '{}'", levelId, t);
            return null;
        }
    }

    @Override
    protected void init() {
        // One column, one width. Three buttons of three sizes read as three
        // unrelated controls; stacking them makes it obvious they are alternatives
        // to the same question, and puts the safe choice under the cursor first.
        final int cx = this.width / 2;
        final int w = BUTTON_WIDTH;
        int y = this.height - 24 - BUTTON_HEIGHT;

        if (verdict == Verdict.UPLOADING) {
            // The only hard stop. Opening now would have Minecraft rewriting chunks
            // underneath the pack; the push detects it and refuses, but the player
            // loses the upload for no reason.
            this.addRenderableWidget(Button.builder(
                            Component.literal("Back"),
                            b -> Minecraft.getInstance().setScreen(parent))
                    .bounds(cx - w / 2, y, w, BUTTON_HEIGHT).build());
            return;
        }

        this.addRenderableWidget(Button.builder(
                        Component.literal("Back"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - w / 2, y, w, BUTTON_HEIGHT).build());
        y -= BUTTON_HEIGHT + 4;

        this.addRenderableWidget(Button.builder(
                        Component.literal("Play offline anyway"),
                        b -> openAnyway())
                .bounds(cx - w / 2, y, w, BUTTON_HEIGHT).build());
        y -= BUTTON_HEIGHT + 4;

        // Always "open it properly", never anything more specific.
        //
        // This used to ask Drive who held the lock so it could say "Dev is playing
        // right now" rather than a generic warning. Dropped, because the distinction
        // changed nothing the player would do: opening from here doesn't sync either
        // way, so the advice is identical whoever holds the lock. What it cost was
        // real - a network call inside a click, a message that rewrote itself a
        // second later, and a line that hung there whenever Drive was slow.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Open via Contributor Worlds"),
                        b -> Minecraft.getInstance().setScreen(new ContributorWorldsScreen()))
                .bounds(cx - w / 2, y, w, BUTTON_HEIGHT).build());
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
            lines.add("The world is still uploading to Drive.");
            lines.add("");
            lines.add("Please wait for it to finish, then try again.");
            return lines;
        }

        lines.add("Changes made here will NOT be saved to Drive,");
        lines.add("and your copy may already be out of date.");
        lines.add("");
        lines.add("Next time you open it properly, whatever is on Drive");
        lines.add("wins and anything you did here is gone.");
        lines.add("");
        lines.add("Open it through Contributor Worlds to sync properly.");
        return lines;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
