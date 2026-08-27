package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.cloud.WorldSetup;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.util.BrowserOpener;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen for joining a world someone else has shared with you.
 *
 * <p>This used to ask for a pasted Drive folder URL. That input is gone, and its
 * absence is the point: under the {@code drive.file} scope, knowing a folder's ID
 * grants nothing at all. Access comes only from the user selecting files in
 * Google's own Picker during the consent screen, so the flow is now a single
 * button that opens that consent screen and reads back whatever they chose.
 *
 * <p>On confirm:
 * <ol>
 *   <li>Opens Google sign-in with the Picker enabled</li>
 *   <li>Resolves whatever came back into the world's fixed file set</li>
 *   <li>Reports precisely which files are still missing, if any</li>
 *   <li>Subscribes and returns to {@link ContributorWorldsScreen}</li>
 * </ol>
 */
public final class AddSubscriptionScreen extends Screen {

    private final ContributorWorldsScreen parent;

    private Button confirmButton;
    private Button cancelButton;

    private volatile boolean working = false;
    private volatile String statusMessage = null;
    private volatile boolean statusIsError = false;

    public AddSubscriptionScreen(final ContributorWorldsScreen parent) {
        super(Component.literal("Add Contributor World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int midY = this.height / 2;

        confirmButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Sign in and pick world files"),
                        b -> onConfirm())
                .bounds(cx - 130, midY + 30, 260, 20)
                .build());

        cancelButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 60, midY + 56, 120, 20)
                .build());
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        super.render(g, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        final int midY = this.height / 2;

        g.drawCenteredString(this.font,
                Component.literal("Join a Shared World").withStyle(ChatFormatting.WHITE),
                cx, midY - 70, 0xFFFFFF);

        g.drawCenteredString(this.font,
                Component.literal("Before you start, the world's owner must share their")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 48, 0xAAAAAA);
        g.drawCenteredString(this.font,
                Component.literal("Drive folder with you as an Editor.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 36, 0xAAAAAA);

        // The right instruction differs by who's looking, so say both rather than
        // one blanket rule. Picking the folder works only for files this Google
        // account created; for a world someone else shared, it silently grants
        // nothing and the files have to be selected one by one.
        g.drawCenteredString(this.font,
                Component.literal("Google will ask you to choose files. Open the shared")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 14, 0xAAAAAA);
        g.drawCenteredString(this.font,
                Component.literal("folder and select every worldshare-* file inside it.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 2, 0xAAAAAA);
        g.drawCenteredString(this.font,
                Component.literal("Picking the folder alone only works for a world you")
                        .withStyle(ChatFormatting.YELLOW),
                cx, midY + 14, 0xFFFF55);
        g.drawCenteredString(this.font,
                Component.literal("created yourself on this Google account.")
                        .withStyle(ChatFormatting.YELLOW),
                cx, midY + 26, 0xFFFF55);

        if (statusMessage != null) {
            g.drawCenteredString(this.font,
                    Component.literal(statusMessage)
                            .withStyle(statusIsError ? ChatFormatting.RED : ChatFormatting.GREEN),
                    cx, midY + 84, statusIsError ? 0xFF5555 : 0x55FF55);
        } else if (working) {
            g.drawCenteredString(this.font,
                    Component.literal("Waiting for you to finish in your browser...")
                            .withStyle(ChatFormatting.YELLOW),
                    cx, midY + 84, 0xFFFF55);
        }
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == 257 && !working) { // Enter
            onConfirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onConfirm() {
        working = true;
        statusMessage = null;
        confirmButton.active = false;

        CloudModule.executor().submit(() -> {
            try {
                final RemoteFileSet remote =
                        WorldSetup.joinExistingWorld(BrowserOpener::open);

                if (!remote.isComplete()) {
                    // Partial selections are the most likely way this goes wrong, so
                    // name what's missing rather than saying "setup failed".
                    setStatus("Missing " + remote.missingFilenames().size()
                            + " file(s): " + WorldSetup.describeMissing(remote), true);
                    WorldShareMod.LOGGER.warn(
                            "AddSubscription: incomplete pick, missing {}",
                            remote.missingFilenames());
                    return;
                }

                final String displayName = "Shared World";
                SubscriptionStore.get().subscribe(remote, displayName);
                WorldShareMod.LOGGER.info(
                        "AddSubscription: subscribed to world {}", remote.controlFileId);

                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().setScreen(parent);
                    parent.triggerRefresh();
                });

            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("AddSubscription: join failed", t);
                final String msg = t.getMessage() != null
                        ? t.getMessage() : t.getClass().getSimpleName();
                if (msg.contains("403")) {
                    setStatus("Permission denied. Ask the owner for Editor access.", true);
                } else if (msg.contains("404")) {
                    setStatus("Those files aren't reachable. Check the folder is shared.", true);
                } else {
                    setStatus(msg, true);
                }
            } finally {
                working = false;
                Minecraft.getInstance().execute(() -> confirmButton.active = true);
            }
        });
    }

    private void setStatus(final String msg, final boolean isError) {
        statusMessage = msg;
        statusIsError = isError;
    }
}
