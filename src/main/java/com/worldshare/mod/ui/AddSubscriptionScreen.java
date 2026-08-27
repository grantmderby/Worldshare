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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen for joining a world someone else has shared with you.
 *
 * <p>There is a text box here again, and it looks like the old "paste a Drive
 * folder URL" input, but what it does is completely different. It used to be the
 * thing that granted access. Under {@code drive.file} an identifier grants
 * nothing at all - only selecting files in Google's own picker does - so the
 * pasted folder is now a <em>hint to the picker</em>: it scopes the picker to
 * that one folder, so the player sees their world's files immediately instead of
 * hunting through their whole Drive.
 *
 * <p>Leaving it blank is legitimate. The picker then opens on the user's whole
 * Drive and they navigate to the shared folder themselves. That path is slower
 * but it works, which matters when an invite has been lost.
 *
 * <p>On confirm:
 * <ol>
 *   <li>Opens Google sign-in with the picker scoped to the pasted folder</li>
 *   <li>Resolves whatever came back into the world's fixed file set</li>
 *   <li>Reports precisely which files are still missing, if any</li>
 *   <li>Subscribes and returns to {@link ContributorWorldsScreen}</li>
 * </ol>
 */
public final class AddSubscriptionScreen extends Screen {

    private final ContributorWorldsScreen parent;

    private EditBox inviteBox;
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

        inviteBox = new EditBox(this.font, cx - 150, midY - 4, 300, 20,
                Component.literal("Drive folder link from the host"));
        inviteBox.setHint(Component.literal("Paste the Drive folder link (optional)")
                .withStyle(ChatFormatting.DARK_GRAY));
        // Drive URLs are long; the default cap would silently truncate one.
        inviteBox.setMaxLength(512);
        this.addRenderableWidget(inviteBox);
        this.setInitialFocus(inviteBox);

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
                cx, midY - 74, 0xFFFFFF);

        g.drawCenteredString(this.font,
                Component.literal("The host must first share their Drive folder with you")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 54, 0xAAAAAA);
        g.drawCenteredString(this.font,
                Component.literal("as an Editor, then send you its link.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY - 42, 0xAAAAAA);

        g.drawCenteredString(this.font,
                Component.literal("Google will then ask which files WorldShare may use.")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY + 22, 0xAAAAAA);

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
        } else {
            g.drawCenteredString(this.font,
                    Component.literal("No link? Leave it blank and find the folder yourself.")
                            .withStyle(ChatFormatting.DARK_GRAY),
                    cx, midY + 84, 0x777777);
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
        final String raw = inviteBox.getValue();
        final String folderId = WorldSetup.extractFolderId(raw);

        // Only complain if they typed something unusable. Blank is a supported
        // choice, not an omission.
        if (!raw.isBlank() && folderId == null) {
            setStatus("That doesn't look like a Drive folder link. "
                    + "Leave it blank to browse instead.", true);
            return;
        }

        working = true;
        statusMessage = null;
        confirmButton.active = false;

        CloudModule.executor().submit(() -> {
            try {
                final RemoteFileSet remote =
                        WorldSetup.joinExistingWorld(BrowserOpener::open, folderId);

                if (!remote.isComplete()) {
                    // Partial selections are the most likely way this goes wrong, so
                    // name what's missing rather than saying "setup failed". The world
                    // is deliberately not added: a half-linked world would fail later,
                    // further from the cause.
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
                    setStatus("Permission denied. Ask the host for Editor access.", true);
                } else if (msg.contains("404")) {
                    setStatus("Those files aren't reachable. Check the folder is shared "
                            + "with this Google account.", true);
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
