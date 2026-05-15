package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.config.SubscriptionStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A simple modal screen where the guest pastes a Google Drive folder URL
 * or ID to subscribe to a shared world.
 *
 * <p>On confirm:
 * <ol>
 *   <li>Extracts and validates the folder ID</li>
 *   <li>Checks for duplicate subscription</li>
 *   <li>Makes a Drive API call to verify the folder exists and is accessible</li>
 *   <li>Adds it to the subscription store</li>
 *   <li>Returns to {@link ContributorWorldsScreen} with a refresh</li>
 * </ol>
 */
public final class AddSubscriptionScreen extends Screen {

    private final ContributorWorldsScreen parent;

    private EditBox inputBox;
    private Button confirmButton;
    private Button cancelButton;

    private volatile boolean validating = false;
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

        inputBox = new EditBox(this.font,
                cx - 180, midY - 10, 360, 20,
                Component.literal("Drive folder URL or ID"));
        inputBox.setHint(Component.literal("Paste Drive folder URL or bare ID here")
                .withStyle(ChatFormatting.DARK_GRAY));
        inputBox.setMaxLength(512);
        inputBox.setFocused(true);
        this.addWidget(inputBox);

        confirmButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Add World"),
                        btn -> onConfirm())
                .bounds(cx - 160, midY + 20, 150, 20).build());

        cancelButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx + 10, midY + 20, 150, 20).build());
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        final int midY = this.height / 2;

        gfx.drawCenteredString(this.font,
                Component.literal("Add a Contributor World")
                        .withStyle(ChatFormatting.YELLOW),
                cx, midY - 50, 0xFFFFFF);

        gfx.drawCenteredString(this.font,
                Component.literal("Paste the Drive folder URL shared by the world owner."),
                cx, midY - 30, 0xCCCCCC);

        gfx.drawCenteredString(this.font,
                Component.literal("Note: ask the world owner for Editor access (not just Viewer).")
                        .withStyle(ChatFormatting.GRAY),
                cx, midY + 50, 0xAAAAAA);

        inputBox.render(gfx, mouseX, mouseY, partial);

        if (statusMessage != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal(statusMessage)
                            .withStyle(statusIsError ? ChatFormatting.RED : ChatFormatting.GREEN),
                    cx, midY + 70, 0xFFFFFF);
        }

        if (validating) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Verifying with Drive...")
                            .withStyle(ChatFormatting.GRAY),
                    cx, midY + 70, 0xFFFFFF);
        }

        super.render(gfx, mouseX, mouseY, partial);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !validating;
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {}

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        // Enter key confirms.
        if (keyCode == 257 && !validating) {
            onConfirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onConfirm() {
        final String raw = inputBox.getValue();
        final String folderId = extractFolderId(raw);
        if (folderId == null) {
            statusMessage = "That doesn't look like a valid Drive folder URL or ID.";
            statusIsError = true;
            return;
        }

        // Duplicate check before hitting Drive.
        if (SubscriptionStore.get().findByFolderId(folderId) != null) {
            statusMessage = "You're already subscribed to this world.";
            statusIsError = true;
            return;
        }

        validating = true;
        statusMessage = null;
        confirmButton.active = false;

        CloudModule.executor().submit(() -> {
            try {
                final DriveClient client = CloudModule.driveClient();
                final com.google.api.services.drive.model.File meta =
                        client.getFileMeta(folderId);

                if (meta == null) {
                    setStatus("Folder not found. Check the ID and sharing settings.", true);
                    return;
                }
                if (!DriveClient.MIME_TYPE_FOLDER.equals(meta.getMimeType())) {
                    setStatus("That ID is a file, not a folder.", true);
                    return;
                }

                final String folderName = meta.getName() != null ? meta.getName() : folderId;
                SubscriptionStore.get().subscribe(folderId, folderName);
                WorldShareMod.LOGGER.info(
                        "AddSubscription: subscribed to '{}' ({})", folderName, folderId);

                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().setScreen(parent);
                    parent.triggerRefresh();
                });

            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("AddSubscription: verification failed", t);
                final String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                if (msg.contains("403")) {
                    setStatus("Permission denied. Ask the owner for Editor access.", true);
                } else if (msg.contains("404")) {
                    setStatus("Drive folder not found. Check the URL.", true);
                } else {
                    setStatus("Drive error: " + msg, true);
                }
            } finally {
                validating = false;
                Minecraft.getInstance().execute(() -> confirmButton.active = true);
            }
        });
    }

    private void setStatus(final String msg, final boolean isError) {
        statusMessage = msg;
        statusIsError = isError;
    }

    /**
     * Extract a bare Drive folder ID from user input. Accepts:
     * - Bare ID: {@code 1AbC...xyz}
     * - Full URL: {@code https://drive.google.com/drive/folders/1AbC...xyz?usp=sharing}
     */
    private static String extractFolderId(final String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        final int idx = s.indexOf("/folders/");
        if (idx >= 0) s = s.substring(idx + "/folders/".length());
        final int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        final int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("[A-Za-z0-9_\\-]+")) return null;
        if (s.length() < 10) return null;
        return s;
    }
}