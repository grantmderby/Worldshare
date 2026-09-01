package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Injects a "Contributor Worlds" button onto the vanilla title screen by
 * hooking {@link ScreenEvent.Init.Post}.
 *
 * <p>Positioning strategy: we look for the Singleplayer button (translation
 * key {@code menu.singleplayer}) and place our button directly below it,
 * reusing the same width. This keeps us aligned with vanilla layout regardless
 * of resolution, and degrades gracefully if another mod has already moved the
 * Singleplayer button — we'll just appear below wherever it ended up.
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} from {@link UiModule#init()}.
 */
public final class TitleScreenButtonInjector {

    private TitleScreenButtonInjector() {}

    /**
     * Warn when the world list opens while an upload is still finishing.
     *
     * <p>Vanilla Singleplayer is the one route into a world that does not queue
     * behind the Drive executor, so it is the only way to have Minecraft writing a
     * world's chunks while WorldShare is packing them. The push refuses in that
     * case rather than publishing an archive its manifest doesn't describe, but a
     * refused save is a poor way to learn this - better to say so before the click.
     *
     * <p>Best-effort by design: it informs, it cannot prevent. The guard that
     * actually holds is in the push itself.
     */
    @SubscribeEvent
    public static void onWorldSelectInit(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen()
                instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen)) {
            return;
        }
        if (!com.worldshare.mod.sync.SyncActivity.isSyncing()) return;

        com.worldshare.mod.util.PlayerNotice.info(
                "An upload is still finishing. Opening that world now will cancel its save.");
    }

    @SubscribeEvent
    public static void onTitleScreenInit(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;

        final String realmsLabel = Component.translatable("menu.online").getString();

        // Find the Realms button — we'll use its position as our anchor.
        Button realmsButton = null;
        Button modsButton = null;

        for (final Object widget : event.getListenersList()) {
            if (!(widget instanceof Button btn)) continue;
            final String label = btn.getMessage().getString();
            if (label.equals(realmsLabel)) {
                realmsButton = btn;
            } else if (label.equalsIgnoreCase("Mods")) {
                modsButton = btn;
            }
        }

        if (realmsButton == null) {
            WorldShareMod.LOGGER.debug(
                    "TitleScreenButtonInjector: Realms button not found; skipping injection");
            return;
        }

        final int origX = realmsButton.getX();
        final int origY = realmsButton.getY();
        final int origW = realmsButton.getWidth();
        final int origH = realmsButton.getHeight();

        // Insert Contributor Worlds at Realms' original position (full width).
        event.addListener(Button.builder(
                        Component.literal("Contributor Worlds"),
                        btn -> openContributorWorlds())
                .bounds(origX, origY, origW, origH)
                .build());

        // Shift Realms down one row.
        final int newY = origY + 24;
        realmsButton.setY(newY);

        // If Mods button exists, pair it with Realms side by side.
        // Each gets roughly half the original width with a small gap.
        if (modsButton != null) {
            final int halfW = (origW - 4) / 2;
            realmsButton.setWidth(halfW);
            realmsButton.setX(origX);
            modsButton.setX(origX + halfW + 4);
            modsButton.setY(newY);
            modsButton.setWidth(halfW);
        }

        WorldShareMod.LOGGER.debug(
                "TitleScreenButtonInjector: injected Contributor Worlds at ({}, {})", origX, origY);
    }

    private static void openContributorWorlds() {
        net.minecraft.client.Minecraft.getInstance()
                .setScreen(new ContributorWorldsScreen());
    }
}
