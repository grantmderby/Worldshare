package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.sync.WorldContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Optional;

/**
 * Hooks into the pause menu to replace the "Save and Quit to Title" button
 * with "Save and Upload to Drive" — but only for worlds that are linked to
 * a Drive folder via a {@code worldshare-link.json} file.
 *
 * <p><b>M5 change:</b> Previously checked the global {@code driveFolderId}
 * config value. Now reads from the world's local {@link WorldLink} file.
 * This means the button only appears for worlds that have been registered
 * with WorldShare (either by the host via {@code /worldshare setfolder}, or
 * by the guest via the Contributor Worlds download flow).
 *
 * <p>Worlds opened directly from vanilla Singleplayer without a link file
 * will see the vanilla "Save and Quit to Title" button unchanged.
 */
public final class PauseMenuHijacker {

    private PauseMenuHijacker() {}

    @SubscribeEvent
    public static void onScreenInit(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)) return;

        // Only intercept if the current world has a WorldShare link.
        final Optional<WorldContext.CurrentWorld> ctx = WorldContext.current();
        if (ctx.isEmpty()) return;

        final WorldLink link = WorldLink.read(ctx.get().worldRoot);
        if (link == null || link.driveFolderId == null || link.driveFolderId.isBlank()) {
            // Not a WorldShare world — leave vanilla button alone.
            return;
        }

// M5 safety: if no lock is held (e.g. opened from vanilla Singleplayer),
// leave the vanilla "Save and Quit" button alone. AutoSyncListener will
// also skip the auto-push since no lock is held.
        if (!LockManager.weHoldLock()) {
            return;
        }

        // Find the "Save and Quit to Title" button by its translated label.
        final String saveQuitLiteral = Component.translatable("menu.returnToMenu").getString();
        Button saveQuitButton = null;
        for (final Object widget : event.getListenersList()) {
            if (widget instanceof Button btn
                    && btn.getMessage().getString().equals(saveQuitLiteral)) {
                saveQuitButton = btn;
                break;
            }
        }

        if (saveQuitButton == null) {
            WorldShareMod.LOGGER.debug(
                    "PauseMenuHijacker: 'Save and Quit to Title' button not found; "
                    + "not modifying menu");
            return;
        }

        // Capture position before hiding original.
        final int origX = saveQuitButton.getX();
        final int origY = saveQuitButton.getY();
        final int origW = saveQuitButton.getWidth();
        final int origH = saveQuitButton.getHeight();

        // Move original off-screen — set position, visibility, and active state
        // so it can't be clicked or tabbed to.
        saveQuitButton.setX(-9999);
        saveQuitButton.visible = false;
        saveQuitButton.active = false;

        // Add our replacement button at the same position.
        event.addListener(Button.builder(
                Component.literal("Save and Upload to Drive"),
                btn -> SaveAndUploadScreen.launchFromPauseMenu())
                .bounds(origX, origY, origW, origH)
                .build());
    }
}
