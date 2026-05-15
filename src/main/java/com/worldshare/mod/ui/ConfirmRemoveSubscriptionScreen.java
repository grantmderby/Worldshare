package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldSubscription;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Confirmation modal shown when the user clicks "Remove" on a world entry
 * in the Contributor Worlds tab.
 *
 * <p>Removing only unsubscribes from the Drive folder — local world files are
 * NOT deleted. The user can re-subscribe later to recover the world.
 */
public final class ConfirmRemoveSubscriptionScreen extends Screen {

    private final ContributorWorldsScreen parent;
    private final WorldSubscription subscription;

    public ConfirmRemoveSubscriptionScreen(final ContributorWorldsScreen parent,
                                           final WorldSubscription subscription) {
        super(Component.literal("Remove Subscription"));
        this.parent = parent;
        this.subscription = subscription;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int midY = this.height / 2;

        // No (default) on the LEFT, Yes on the RIGHT — keeps destructive
        // action away from the position users instinctively click first.
        this.addRenderableWidget(Button.builder(
                        Component.literal("No, keep subscription"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 160, midY + 30, 150, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Yes, remove"),
                        btn -> doRemove())
                .bounds(cx + 10, midY + 30, 150, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        final int midY = this.height / 2;

        gfx.drawCenteredString(this.font,
                Component.literal("Remove Subscription").withStyle(ChatFormatting.YELLOW),
                cx, midY - 50, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal("Stop syncing '" + subscription.displayName + "'?"),
                cx, midY - 20, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal("Your local world files will NOT be deleted.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY, 0xCCCCCC);

        gfx.drawCenteredString(this.font,
                Component.literal("You can re-subscribe later from Add World.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY + 12, 0xCCCCCC);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {}

    private void doRemove() {
        final boolean ok = SubscriptionStore.get().unsubscribe(subscription.driveFolderId);
        if (ok) {
            WorldShareMod.LOGGER.info(
                    "ConfirmRemoveSubscription: removed '{}'", subscription.displayName);

            // M7: also delete worldshare-link.json from the local world folder
            // so opening that world via Singleplayer doesn't trigger the
            // "WorldShare world without lock" warning.
            if (subscription.localFolderName != null) {
                try {
                    final java.nio.file.Path linkFile = com.worldshare.mod.util.WorldSharePaths
                            .gameDir()
                            .resolve("saves")
                            .resolve(subscription.localFolderName)
                            .resolve("worldshare-link.json");
                    if (java.nio.file.Files.exists(linkFile)) {
                        java.nio.file.Files.delete(linkFile);
                        WorldShareMod.LOGGER.info(
                                "ConfirmRemoveSubscription: deleted worldshare-link.json from '{}'",
                                subscription.localFolderName);
                    }
                } catch (final java.io.IOException ioErr) {
                    WorldShareMod.LOGGER.warn(
                            "ConfirmRemoveSubscription: couldn't delete link file (non-fatal): {}",
                            ioErr.getMessage());
                }
            }
        }
        Minecraft.getInstance().setScreen(parent);
        parent.triggerRefresh();
    }
}