package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.modmanager.ModpackManifest;
import com.worldshare.mod.modmanager.ModrinthClient;
import com.worldshare.mod.util.WorldSharePaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown when the guest opens the title screen and a Drive subscription has
 * a {@code modpack.json} listing mods they don't have locally.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Show diff: missing mods (auto-installable) and missing mods (manual)</li>
 *   <li>User clicks Install → download all auto-installable jars to mods/</li>
 *   <li>Show "Quit and Restart" button → calls {@code System.exit(0)}</li>
 * </ol>
 *
 * <p>Only handles NEW mods (mods not present locally). Version mismatches
 * where the guest has an outdated jar are shown as warnings with a link, not
 * auto-installed (Windows file locking prevents replacing in-use jars).
 */
public final class ModpackSyncScreen extends Screen {

    private enum Phase { READY, DOWNLOADING, DONE, ERROR }

    private final List<ModpackManifest.ModEntry> autoInstallable;
    private final List<ModpackManifest.ModEntry> manualInstall;
    private final String worldDisplayName;

    private volatile Phase phase = Phase.READY;
    private volatile int filesDone = 0;
    private volatile String currentFile = "";
    private volatile String errorMessage = null;
    private volatile boolean cancelRequested = false;
    private Button cancelDuringDownloadButton;
    private Button installButton;
    private Button skipButton;
    private Button restartButton;

    // Add this field alongside the existing ones at the top of the class:
    private final List<ModpackManifest.ModEntry> wrongVersion;

    public ModpackSyncScreen(final String worldDisplayName,
                             final com.worldshare.mod.modmanager.ModManagerModule.ModCheckResult modCheck) {
        super(Component.literal("Modpack Sync"));
        this.worldDisplayName = worldDisplayName;
        this.autoInstallable = new ArrayList<>();
        this.manualInstall = new ArrayList<>();
        this.wrongVersion = new ArrayList<>(modCheck.wrongVersion);
        for (final ModpackManifest.ModEntry m : modCheck.missing) {
            if (m.download_url != null && !m.download_url.isBlank()) {
                autoInstallable.add(m);
            } else {
                manualInstall.add(m);
            }
        }
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int bY = this.height - 50;

        if (phase == Phase.READY) {
            installButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Install " + autoInstallable.size() + " mod(s)"),
                            btn -> startDownload())
                    .bounds(cx - 160, bY, 150, 20).build());
            installButton.active = !autoInstallable.isEmpty();

            skipButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Skip - return to title"),
                            btn -> Minecraft.getInstance().setScreen(new TitleScreen()))
                    .bounds(cx + 10, bY, 150, 20).build());
        } else if (phase == Phase.DONE) {
            restartButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Quit and Restart Minecraft"),
                            btn -> System.exit(0))
                    .bounds(cx - 110, bY, 220, 20).build());
        } else if (phase == Phase.ERROR) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("Return to Title"),
                            btn -> Minecraft.getInstance().setScreen(new TitleScreen()))
                    .bounds(cx - 100, bY, 200, 20).build());
        } else if (phase == Phase.DOWNLOADING) {
            cancelDuringDownloadButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Cancel Download"),
                            btn -> {
                                cancelRequested = true;
                                btn.active = false;
                                btn.setMessage(Component.literal("Cancelling..."));
                            })
                    .bounds(this.width / 2 - 100, this.height - 50, 200, 20)
                    .build());
        }
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;

        gfx.drawCenteredString(this.font,
                Component.literal("Mods needed for '" + worldDisplayName + "'")
                        .withStyle(ChatFormatting.YELLOW),
                cx, 20, 0xFFFFFF);

        switch (phase) {
            case READY -> renderReady(gfx, cx);
            case DOWNLOADING -> renderDownloading(gfx, cx);
            case DONE -> renderDone(gfx, cx);
            case ERROR -> renderError(gfx, cx);
        }
    }

    private void renderReady(final GuiGraphics gfx, final int cx) {
        int y = 50;
        if (!autoInstallable.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Will be installed (" + autoInstallable.size() + "):")
                            .withStyle(ChatFormatting.GREEN),
                    cx, y, 0xFFFFFF);
            y += 14;
            for (int i = 0; i < Math.min(8, autoInstallable.size()); i++) {
                final ModpackManifest.ModEntry m = autoInstallable.get(i);
                gfx.drawCenteredString(this.font,
                        Component.literal("- " + m.display_name + " " + m.version),
                        cx, y, 0xCCCCCC);
                y += 11;
            }
            if (autoInstallable.size() > 8) {
                gfx.drawCenteredString(this.font,
                        Component.literal("... and " + (autoInstallable.size() - 8) + " more"),
                        cx, y, 0x888888);
                y += 11;
            }
            y += 6;
        }
        if (!manualInstall.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Manual install needed (not on Modrinth):")
                            .withStyle(ChatFormatting.GOLD),
                    cx, y, 0xFFFFFF);
            y += 14;
            for (int i = 0; i < Math.min(5, manualInstall.size()); i++) {
                final ModpackManifest.ModEntry m = manualInstall.get(i);
                gfx.drawCenteredString(this.font,
                        Component.literal("- " + m.display_name + " " + m.version
                                + " (ask the world owner)"),
                        cx, y, 0xCCCCAA);
                y += 11;
            }
            y += 6;
        }

        if (!wrongVersion.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Wrong version installed:")
                            .withStyle(ChatFormatting.RED),
                    cx, y, 0xFFFFFF);
            y += 14;
            for (int i = 0; i < Math.min(5, wrongVersion.size()); i++) {
                final ModpackManifest.ModEntry m = wrongVersion.get(i);
                // Show what version is needed and where to get it.
                final String urlHint = (m.download_url != null)
                        ? " - download v" + m.version + " from Modrinth"
                        : " - ask the world owner for v" + m.version;
                gfx.drawCenteredString(this.font,
                        Component.literal("- " + m.display_name + urlHint),
                        cx, y, 0xFF9999);
                y += 11;
            }
            if (wrongVersion.size() > 5) {
                gfx.drawCenteredString(this.font,
                        Component.literal("... and " + (wrongVersion.size() - 5) + " more"),
                        cx, y, 0x888888);
            }
        }
    }

    private void renderDownloading(final GuiGraphics gfx, final int cx) {
        gfx.drawCenteredString(this.font,
                Component.literal("Downloading mods..."),
                cx, this.height / 2 - 30, 0xFFFFFF);
        gfx.drawCenteredString(this.font,
                Component.literal(filesDone + " / " + autoInstallable.size()),
                cx, this.height / 2 - 10, 0xCCCCCC);
        if (!currentFile.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal(currentFile).withStyle(ChatFormatting.GRAY),
                    cx, this.height / 2 + 10, 0xAAAAAA);
        }
    }

    private void renderDone(final GuiGraphics gfx, final int cx) {
        gfx.drawCenteredString(this.font,
                Component.literal("\u2705 " + filesDone + " mod(s) installed!")
                        .withStyle(ChatFormatting.GREEN),
                cx, this.height / 2 - 20, 0xFFFFFF);
        gfx.drawCenteredString(this.font,
                Component.literal("Restart Minecraft to load the new mods."),
                cx, this.height / 2, 0xCCCCCC);
        if (!manualInstall.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Note: " + manualInstall.size()
                            + " mod(s) still need manual install (see chat earlier)."),
                    cx, this.height / 2 + 20, 0xCCCCAA);
        }
    }

    private void renderError(final GuiGraphics gfx, final int cx) {
        gfx.drawCenteredString(this.font,
                Component.literal("Download failed.")
                        .withStyle(ChatFormatting.RED),
                cx, this.height / 2 - 20, 0xFFFFFF);
        if (errorMessage != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal(errorMessage).withStyle(ChatFormatting.RED),
                    cx, this.height / 2, 0xFF9999);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (phase == Phase.DOWNLOADING) {
            cancelRequested = true;
            return false;
        }
        return phase == Phase.READY || phase == Phase.ERROR;
    }
    @Override
    protected void renderBlurredBackground(final float partialTick) {}

    private void startDownload() {
        phase = Phase.DOWNLOADING;
        this.clearWidgets();
        this.init();

        final Thread worker = new Thread(() -> {
            final Path modsDir = FMLPaths.MODSDIR.get();
            try {
                for (final ModpackManifest.ModEntry mod : autoInstallable) {
                    if (cancelRequested) {
                        errorMessage = "Cancelled. " + filesDone + " mod(s) installed before cancel; "
                                + "the rest were not. Retry to complete.";
                        phase = Phase.ERROR;
                        Minecraft.getInstance().execute(() -> { this.clearWidgets(); this.init(); });
                        return;
                    }

                    currentFile = mod.display_name + " " + mod.version;
                    final Path target = modsDir.resolve(mod.filename);
                    if (java.nio.file.Files.exists(target)) {
                        WorldShareMod.LOGGER.info(
                                "ModpackSync: '{}' already exists in mods/ - skipping",
                                mod.filename);
                    } else {
                        WorldShareMod.LOGGER.info(
                                "ModpackSync: downloading '{}' from {}",
                                mod.filename, mod.download_url);
                        ModrinthClient.downloadFile(mod.download_url, target);
                    }
                    filesDone++;
                }
                phase = Phase.DONE;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("ModpackSync: download failed", t);
                errorMessage = t.getMessage();
                phase = Phase.ERROR;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            }
        }, "WorldShare-ModpackDownload");
        worker.setDaemon(true);
        worker.start();
    }
}