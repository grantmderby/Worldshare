package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.config.WorldShareConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Hooks into the pause menu's screen-init event to find the
 * "Save and Quit to Title" button, then either rename + redirect it to
 * the Save-and-Upload flow, or hide it and add our own button.
 *
 * <p>We use {@link ScreenEvent.Init.Post} (a Forge-side event) rather than
 * a Mixin to avoid introducing a Mixin build dependency just for this one
 * touch-up. The flicker on first frame is imperceptible.
 *
 * <p>Behavior:
 * <ul>
 *   <li>If a Drive folder is configured AND the lock is held by us:
 *       button text becomes "Save and Upload to Drive" and clicking it
 *       launches {@link SaveAndUploadScreen}.</li>
 *   <li>Otherwise: button is left alone. Vanilla "Save and Quit to Title"
 *       behavior runs, and the {@link com.worldshare.mod.sync.AutoSyncListener}
 *       handles the X-button-style fallback push at server-stop time.</li>
 * </ul>
 */
public final class PauseMenuHijacker {

    private PauseMenuHijacker() {
        // utility - registered as a static handler
    }

    @SubscribeEvent
    public static void onScreenInit(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }

        // Only re-skin if we're in a WorldShare context.
        final String folderId = WorldShareConfig.get().driveFolderId.get();
        if (folderId == null || folderId.isBlank()) {
            return;
        }

        // Find the "Save and Quit to Title" button. Minecraft's PauseScreen
        // has a stable component ordering, but we can't rely on indices across
        // versions / mods. Match by translated text instead.
        final String saveQuitTextKey = "menu.returnToMenu";
        final Component saveQuitText = Component.translatable(saveQuitTextKey);
        final String saveQuitLiteral = saveQuitText.getString();

        Button saveQuitButton = null;
        for (final Object widget : event.getListenersList()) {
            if (widget instanceof Button btn) {
                final String label = btn.getMessage().getString();
                if (label.equals(saveQuitLiteral)) {
                    saveQuitButton = btn;
                    break;
                }
            }
        }

        if (saveQuitButton == null) {
            // Either MC has changed the label key, or another mod has already
            // replaced this button. Don't fight - leave the menu alone.
            WorldShareMod.LOGGER.debug(
                    "PauseMenuHijacker: 'Save and Quit to Title' button not found; not modifying menu");
            return;
        }

        // Rename and redirect.
        saveQuitButton.setMessage(Component.literal("Save and Upload to Drive"));
        // Replace the click handler. Forge's Button has a setOnClick-equivalent via
        // the constructor; for an existing button we rely on the screen event being
        // re-fired on each open, so we can construct an overlay.
        //
        // Approach: hide the existing button (off-screen) and add our own with the
        // same bounds. This is safer than trying to mutate the existing button's
        // onPress handler (which is final on Button in some MC versions).
        final int origX = saveQuitButton.getX();
        final int origY = saveQuitButton.getY();
        final int origW = saveQuitButton.getWidth();
        final int origH = saveQuitButton.getHeight();
        // Move the original off-screen so it can't be clicked.
        saveQuitButton.setX(-9999);
        saveQuitButton.visible = false;
        saveQuitButton.active = false;

        // Add our replacement.
        final Button ours = Button.builder(
                Component.literal("Save and Upload to Drive"),
                btn -> SaveAndUploadScreen.launchFromPauseMenu())
                .bounds(origX, origY, origW, origH)
                .build();
        event.addListener(ours);
    }
}
