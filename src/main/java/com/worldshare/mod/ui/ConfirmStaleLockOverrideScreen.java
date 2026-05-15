package com.worldshare.mod.ui;

import com.worldshare.mod.cloud.SessionLock;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Two-click confirmation before overriding a stale lock. Stops accidental
 * overrides — the user must explicitly acknowledge that the previous holder
 * may not have had a chance to upload their work.
 */
public final class ConfirmStaleLockOverrideScreen extends Screen {

    private final ContributorWorldsScreen parent;
    private final WorldStateResolver.ResolvedWorld world;
    private final Consumer<WorldStateResolver.ResolvedWorld> onConfirm;

    public ConfirmStaleLockOverrideScreen(final ContributorWorldsScreen parent,
                                          final WorldStateResolver.ResolvedWorld world,
                                          final Consumer<WorldStateResolver.ResolvedWorld> onConfirm) {
        super(Component.literal("Override Stale Lock?"));
        this.parent = parent;
        this.world = world;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int midY = this.height / 2;

        // No (default-safe) on the LEFT, Yes (destructive) on the RIGHT.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel - try again later"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 180, midY + 50, 170, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Override anyway"),
                        btn -> {
                            // Run action FIRST so it sets loadState=LOADING. Parent's init()
                            // will then leave the load alone instead of triggering a duplicate.
                            onConfirm.accept(world);
                            Minecraft.getInstance().setScreen(parent);
                        })
                .bounds(cx + 10, midY + 50, 170, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        final int midY = this.height / 2;

        gfx.drawCenteredString(this.font,
                Component.literal("[!] Stale Lock Detected").withStyle(ChatFormatting.YELLOW),
                cx, midY - 70, 0xFFFFFF);

        final SessionLock lock = world.lock;
        final String holder = lock != null && lock.holderName != null ? lock.holderName : "Someone";

        gfx.drawCenteredString(this.font,
                Component.literal(holder + "'s session ended without proper cleanup."),
                cx, midY - 40, 0xFFFFFF);

        if (lock != null) {
            final String timeAgo = formatAgo(lock.lockedAtInstant());
            gfx.drawCenteredString(this.font,
                    Component.literal("Lock acquired: " + timeAgo)
                            .withStyle(ChatFormatting.GRAY),
                    cx, midY - 25, 0xCCCCCC);
        }

        gfx.drawCenteredString(this.font,
                Component.literal("They may have offline changes that aren't on Drive yet.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 5, 0xCCCCCC);
        gfx.drawCenteredString(this.font,
                Component.literal("Overriding now means those changes will be lost.")
                        .withStyle(ChatFormatting.RED),
                cx, midY + 8, 0xFF8888);
        gfx.drawCenteredString(this.font,
                Component.literal("Contact " + holder + " before proceeding.")
                        .withStyle(ChatFormatting.YELLOW),
                cx, midY + 25, 0xFFFFAA);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {}

    private static String formatAgo(final Instant when) {
        if (when == null) return "unknown";
        final Duration d = Duration.between(when, Instant.now());
        if (d.toMinutes() < 60) return d.toMinutes() + " min ago";
        if (d.toHours() < 24) return d.toHours() + " hour(s) ago";
        return d.toDays() + " day(s) ago";
    }
}