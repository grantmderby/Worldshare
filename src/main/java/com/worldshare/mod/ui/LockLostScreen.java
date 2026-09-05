package com.worldshare.mod.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown when this world's session lock has moved to somebody else mid-session.
 *
 * <p>From that moment nothing the player does here can be uploaded — the push
 * will refuse, correctly, rather than publish over the other player's work. That
 * was already reported in chat and as a toast, and both are easy to play
 * straight past: chat scrolls, toasts fade, and the cost of missing it is every
 * block placed for the rest of the session.
 *
 * <p>So this interrupts instead. It offers one way out, which is also the right
 * one: save and leave. Local files are kept, and reopening from Contributor
 * Worlds afterwards pulls the other player's changes and hands the world back
 * properly.
 *
 * <p>{@link #shouldCloseOnEsc()} is false deliberately. Escape here would dismiss
 * the only warning the player gets about a session that can no longer be saved.
 */
public final class LockLostScreen extends Screen {

    private final String holderName;

    public LockLostScreen(final String holderName) {
        super(Component.literal("Session lock lost"));
        this.holderName = holderName;
    }

    /** Show it, from whatever thread noticed. */
    public static void show(final String holderName) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            // Never over the top of another WorldShare screen mid-operation - an
            // upload in progress has its own reporting, and stealing the screen
            // from it would hide the thing actually happening.
            if (mc.screen instanceof LockLostScreen) return;
            mc.setScreen(new LockLostScreen(holderName));
        });
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(
                        Component.literal("Save and exit to title"),
                        btn -> {
                            final Minecraft mc = Minecraft.getInstance();
                            if (mc.level != null) {
                                mc.level.disconnect();
                            }
                            mc.disconnect();
                            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                        })
                .bounds(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        int y = this.height / 2 - 50;

        gfx.drawCenteredString(this.font,
                Component.literal(holderName + " has taken this world")
                        .withStyle(ChatFormatting.RED),
                cx, y, 0xFFFFFF);
        y += this.font.lineHeight + 12;

        final String[] body = {
                "Anything you do from here will not be uploaded - they hold",
                "the session lock now, and saving over their work would lose it.",
                "",
                "Your local copy is safe. Save and leave, then reopen from",
                "Contributor Worlds to get their changes and take a turn.",
        };
        for (final String line : body) {
            if (!line.isEmpty()) {
                gfx.drawCenteredString(this.font, Component.literal(line), cx, y, 0xCCCCCC);
            }
            y += this.font.lineHeight + 2;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
