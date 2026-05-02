package com.worldshare.mod.ui;

import com.worldshare.mod.relay.PresenceFile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Title-screen modal shown to the guest when an active {@code presence.json}
 * is found on Drive.
 *
 * <p>Presents two options:
 * <ul>
 *   <li>[Join] — connects to the host's e4mc relay address</li>
 *   <li>[Cancel] — returns to the title screen</li>
 * </ul>
 *
 * <p>There is deliberately no "play solo" option. Playing solo while the host
 * is writing the same world files would cause corruption. This gap is enforced
 * more strongly in M5 via the world selection tab.
 *
 * <p>Escape is disabled to force an explicit choice.
 */
public final class JoinPromptScreen extends Screen {

    private final PresenceFile presence;

    public JoinPromptScreen(final PresenceFile presence) {
        super(Component.literal("WorldShare - Live Session"));
        this.presence = presence;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int buttonY = this.height / 2 + 20;

        this.addRenderableWidget(Button.builder(
                Component.literal("Join " + presence.host + "'s world"),
                btn -> onJoin())
                .bounds(cx - 155, buttonY, 150, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel - back to title"),
                btn -> onCancel())
                .bounds(cx + 5, buttonY, 150, 20)
                .build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        this.renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        final int midY = this.height / 2;

        gfx.drawCenteredString(this.font,
                Component.literal(presence.host + " is playing right now!")
                        .withStyle(ChatFormatting.YELLOW),
                cx, midY - 40, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal("Join the live session or head back to the title screen."),
                cx, midY - 20, 0xCCCCCC);

        gfx.drawCenteredString(this.font,
                Component.literal(presence.e4mc_link).withStyle(ChatFormatting.GRAY),
                cx, midY, 0x888888);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Force explicit choice - escape would silently dismiss the prompt.
        return false;
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {
        // No blur - keep UI readable.
    }

    private void onJoin() {
        if (presence.e4mc_link == null || presence.e4mc_link.isBlank()) {
            Minecraft.getInstance().setScreen(new TitleScreen());
            return;
        }

        final Minecraft mc = Minecraft.getInstance();

        // Minecraft requires the multiplayer terms to have been accepted before
        // ConnectScreen.startConnecting() will work. Normally this happens when
        // the user opens the Multiplayer screen. We trigger the same check here
        // by calling prepareForMultiplayer() first, then connecting directly.
        // If the user hasn't accepted yet, this will show the agreement screen
        // and the connection will proceed after they accept.
        mc.prepareForMultiplayer();

        final ServerAddress address = ServerAddress.parseString(presence.e4mc_link);
        final ServerData serverData = new ServerData(
                "WorldShare - " + presence.host,
                presence.e4mc_link,
                ServerData.Type.OTHER);

        ConnectScreen.startConnecting(new TitleScreen(), mc, address, serverData, false, null);
    }

    private void onCancel() {
        Minecraft.getInstance().setScreen(new TitleScreen());
    }
}
