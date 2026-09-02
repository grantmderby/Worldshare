package com.worldshare.mod.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows what {@code /worldshare setup} is doing while it does it.
 *
 * <p>Setup creates twenty-six files in Drive one at a time, which is about half a
 * minute. In chat that reads as nothing happening: the command appears to have
 * been swallowed, and the obvious response - run it again - used to create a
 * second Drive folder and a second set of files.
 *
 * <p>So this is not only a progress indicator. Being a screen is what stops the
 * player typing the command a second time, which is the failure it exists to
 * prevent. {@link #shouldCloseOnEsc()} stays true regardless: work continues in
 * the background either way, and a screen that cannot be dismissed is worse than
 * one that can if anything ever hangs.
 *
 * <p>Opened only once file creation starts, never around the sign-in step. The
 * authorization link is posted to chat, and a screen over the top of it would
 * leave the player nothing to click.
 */
public final class SetupProgressScreen extends Screen {

    private final String worldName;

    private volatile int done;
    private volatile int total;
    private volatile int created;
    private volatile String error;

    public SetupProgressScreen(final String worldName, final int total) {
        super(Component.literal("Setting up " + worldName));
        this.worldName = worldName;
        this.total = total;
    }

    /** Called off-thread as each remote file is created or found already present. */
    public void update(final int filesDone, final int filesTotal, final int filesCreated) {
        this.done = filesDone;
        this.total = filesTotal;
        this.created = filesCreated;
    }

    /** Close the screen and hand the player back to their world. */
    public void finish() {
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen == this) {
                mc.setScreen(null);
            }
        });
    }

    /**
     * Report a failure and leave the screen up with a way out.
     *
     * <p>Kept on screen rather than closed, because a failure here is the case
     * where the player most needs to be told something: setup can be re-run
     * safely, and it will pick up the files that already exist rather than
     * starting a second world beside the first.
     */
    public void fail(final String message) {
        this.error = message;
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen == this) {
                this.clearWidgets();
                this.init();
            }
        });
    }

    @Override
    protected void init() {
        if (error != null) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("Back to world"),
                            btn -> Minecraft.getInstance().setScreen(null))
                    .bounds(this.width / 2 - 75, this.height / 2 + 40, 150, 20).build());
        }
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;

        if (error != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Setup failed").withStyle(ChatFormatting.RED),
                    cx, this.height / 2 - 40, 0xFFFFFF);
            for (final var line : this.font.split(
                    Component.literal(error).withStyle(ChatFormatting.GRAY), 320)) {
                gfx.drawCenteredString(this.font, line, cx, this.height / 2 - 20, 0xFFFFFF);
            }
            gfx.drawCenteredString(this.font,
                    Component.literal("Safe to run /worldshare setup again - it picks up "
                                    + "where this left off.")
                            .withStyle(ChatFormatting.YELLOW),
                    cx, this.height / 2 + 10, 0xFFFFFF);
            return;
        }

        gfx.drawCenteredString(this.font,
                Component.literal("Setting up '" + worldName + "' for sharing")
                        .withStyle(ChatFormatting.YELLOW),
                cx, this.height / 2 - 40, 0xFFFFFF);

        final int barW = 300, barH = 16;
        final int barX = (this.width - barW) / 2;
        final int barY = this.height / 2 - 8;
        gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
        gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
        final int safeTotal = Math.max(1, total);
        final int fillPx = (int) (barW * Math.min(1.0, (double) done / safeTotal));
        gfx.fill(barX, barY, barX + fillPx, barY + barH, 0xFF44AA44);

        // "Checking" when nothing has needed creating, which is what a resumed
        // or adopted setup looks like. Saying "Creating" through a pure adoption
        // made a reused folder indistinguishable from a fresh one.
        gfx.drawCenteredString(this.font,
                Component.literal((created > 0 ? "Creating" : "Checking")
                        + " files in Drive  -  " + done + " / " + total),
                cx, barY + 4, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal("This takes about half a minute.")
                        .withStyle(ChatFormatting.GRAY),
                cx, barY + barH + 12, 0xFFFFFF);
    }
}
