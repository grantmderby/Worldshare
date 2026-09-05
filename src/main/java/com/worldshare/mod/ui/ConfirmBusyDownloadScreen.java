package com.worldshare.mod.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asked before downloading a world somebody else is currently playing.
 *
 * <p>The download is allowed, and that is deliberate. This only ever appears for
 * a world with no local copy, so it is almost always somebody fetching the whole
 * thing for the first time — and the pull after the holder finishes takes only
 * the buckets that changed, a small fraction of a mature world. Blocking would
 * throw that saving away to avoid a confusion a sentence can fix.
 *
 * <p>What was actually wrong is that nothing said so. The row offered a plain
 * Download, the transfer succeeded, and the next refresh replaced the button
 * with a greyed-out "Locked" — the player learning only afterwards that they
 * could not open what they had just waited for.
 */
public final class ConfirmBusyDownloadScreen extends Screen {

    private final Screen parent;
    private final String worldName;
    private final String holderName;
    private final Runnable onConfirm;

    public ConfirmBusyDownloadScreen(final Screen parent,
                                     final String worldName,
                                     final String holderName,
                                     final Runnable onConfirm) {
        super(Component.literal("Download while in use?"));
        this.parent = parent;
        this.worldName = worldName;
        this.holderName = holderName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int y = this.height / 2 + 40;

        // Downloading on the left, matching the other confirm screens: the option
        // that goes ahead sits where the cursor already is, and here going ahead
        // is the harmless one.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Download now"),
                        btn -> {
                            Minecraft.getInstance().setScreen(parent);
                            onConfirm.run();
                        })
                .bounds(cx - 160, y, 150, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Back"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx + 10, y, 150, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        int y = this.height / 2 - 50;

        gfx.drawCenteredString(this.font,
                Component.literal(holderName + " is playing this world right now")
                        .withStyle(ChatFormatting.YELLOW),
                cx, y, 0xFFFFFF);
        y += this.font.lineHeight + 12;

        final String[] body = {
                "You can download it now so it's ready, but you won't",
                "be able to open it until they've finished.",
                "",
                "Downloading now still saves time - when they finish,",
                "you'll only download the parts that changed.",
        };
        for (final String line : body) {
            if (!line.isEmpty()) {
                gfx.drawCenteredString(this.font, Component.literal(line), cx, y, 0xCCCCCC);
            }
            y += this.font.lineHeight + 2;
        }

        gfx.drawCenteredString(this.font,
                Component.literal(worldName).withStyle(ChatFormatting.DARK_GRAY),
                cx, this.height / 2 + 22, 0xFFFFFF);
    }
}
