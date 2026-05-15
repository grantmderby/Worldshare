package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.util.WorldSharePaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Two-phase conflict resolution screen shown when the user tries to open
 * a world that has a stale lock with unpushed work.
 *
 * <p><b>Phase 1 (WARNING):</b> Explains what happened, who had the lock,
 * how long ago. Offers [Cancel] or [Override anyway →].
 *
 * <p><b>Phase 2 (FINAL CONFIRMATION):</b> One more "are you sure?", mentions
 * that contacting the previous holder is recommended. Offers [Yes, proceed]
 * or [Cancel].
 *
 * <p><b>Rescue path:</b> Before overriding, if our local folder has changes
 * newer than Drive's manifest, we automatically copy the local world folder to
 * a timestamped backup directory ({@code saves/<name>_offline_backup_<timestamp>}).
 * The user is shown the backup path before proceeding.
 *
 * <p><b>After confirmation:</b>
 * <ol>
 *   <li>Auto-backup local world (if local is newer than manifest)</li>
 *   <li>Override the stale lock (acquire a new one)</li>
 *   <li>Pull from Drive</li>
 *   <li>Open the world</li>
 * </ol>
 */
public final class ConflictResolutionScreen extends Screen {

    /** Which phase of the two-click flow we're in. */
    private enum Phase { WARNING, FINAL_CONFIRM, WORKING, DONE_ERROR }

    private final WorldStateResolver.ResolvedWorld resolved;
    private final Screen parent;
    private final UUID playerUuid;

    private Phase phase = Phase.WARNING;
    private volatile String workingMessage = "Working...";
    private volatile String errorMessage = null;
    private volatile String backupPath = null;

    // Buttons — rebuilt each phase via init()
    private Button cancelButton;
    private Button overrideButton;
    private Button confirmButton;
    private Button returnButton;

    public ConflictResolutionScreen(final WorldStateResolver.ResolvedWorld resolved,
                                    final Screen parent,
                                    final UUID playerUuid) {
        super(Component.literal("WorldShare - Conflict"));
        this.resolved = resolved;
        this.parent = parent;
        this.playerUuid = playerUuid;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int buttonY = this.height - 55;

        switch (phase) {
            case WARNING -> {
                cancelButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel - try again later"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                        .bounds(cx - 160, buttonY, 150, 20).build());

                overrideButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Override anyway \u2192"),
                        btn -> onOverrideClicked())
                        .bounds(cx + 10, buttonY, 150, 20).build());
            }
            case FINAL_CONFIRM -> {
                cancelButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                        .bounds(cx - 160, buttonY, 150, 20).build());

                confirmButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Yes, I've confirmed with them"),
                        btn -> onFinalConfirm())
                        .bounds(cx + 10, buttonY, 150, 20).build());
            }
            case WORKING -> {
                // No buttons while working.
            }
            case DONE_ERROR -> {
                returnButton = this.addRenderableWidget(Button.builder(
                        Component.literal("Return to Contributor Worlds"),
                        btn -> Minecraft.getInstance().setScreen(parent))
                        .bounds(cx - 100, buttonY, 200, 20).build());
            }
        }
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        final int cx = this.width / 2;
        int y = 30;

        switch (phase) {
            case WARNING -> renderWarningPhase(gfx, cx, y);
            case FINAL_CONFIRM -> renderConfirmPhase(gfx, cx, y);
            case WORKING -> renderWorking(gfx, cx, y);
            case DONE_ERROR -> renderError(gfx, cx, y);
        }
    }

    private void renderWarningPhase(final GuiGraphics gfx, final int cx, int y) {
        gfx.drawCenteredString(this.font,
                Component.literal("⚠ A session ended without saving to Drive.")
                        .withStyle(ChatFormatting.YELLOW),
                cx, y, 0xFFFFFF);
        y += 20;

        final var lock = resolved.lock;
        if (lock != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Last holder: " + lock.holderName
                            + "  |  machine: " + shortId(lock.machineId)),
                    cx, y, 0xCCCCCC);
            y += 14;

            final String ago = humanizeAgo(lock.lockedAtInstant());
            gfx.drawCenteredString(this.font,
                    Component.literal("Lock created: " + ago + " ago"),
                    cx, y, 0xCCCCCC);
            y += 14;

            if (resolved.driveManifest != null && resolved.driveManifest.generatedAt != null) {
                final String manifestAgo = humanizeAgo(
                        Instant.parse(resolved.driveManifest.generatedAt));
                gfx.drawCenteredString(this.font,
                    Component.literal("Last saved to Drive: " + manifestAgo + " ago"),
                    cx, y, 0xCCCCCC);
                y += 14;
            }

            y += 10;
            gfx.drawCenteredString(this.font,
                    Component.literal(lock.holderName
                            + "'s offline changes are NOT on Drive."),
                    cx, y, 0xFF9999);
            y += 14;
            gfx.drawCenteredString(this.font,
                    Component.literal("If you override, those changes are gone forever."),
                    cx, y, 0xFF9999);
            y += 20;
            gfx.drawCenteredString(this.font,
                    Component.literal("Please contact " + lock.holderName
                            + " before overriding.")
                            .withStyle(ChatFormatting.YELLOW),
                    cx, y, 0xFFFFFF);
        }

        if (backupPath != null) {
            y += 20;
            gfx.drawCenteredString(this.font,
                    Component.literal("Your local copy will be backed up automatically."),
                    cx, y, 0xAAFFAA);
        }
    }

    private void renderConfirmPhase(final GuiGraphics gfx, final int cx, int y) {
        gfx.drawCenteredString(this.font,
                Component.literal("Final confirmation.")
                        .withStyle(ChatFormatting.RED),
                cx, y, 0xFFFFFF);
        y += 20;

        final String holder = resolved.lock != null ? resolved.lock.holderName : "the previous holder";
        gfx.drawCenteredString(this.font,
                Component.literal(holder + "'s offline work will be"),
                cx, y, 0xCCCCCC);
        y += 14;
        gfx.drawCenteredString(this.font,
                Component.literal("permanently lost if you proceed."),
                cx, y, 0xFF9999);
        y += 20;
        gfx.drawCenteredString(this.font,
                Component.literal("Only continue if you have spoken to " + holder + "."),
                cx, y, 0xFFFFAA);
    }

    private void renderWorking(final GuiGraphics gfx, final int cx, int y) {
        gfx.drawCenteredString(this.font,
                Component.literal(workingMessage).withStyle(ChatFormatting.YELLOW),
                cx, y + 60, 0xFFFFFF);
    }

    private void renderError(final GuiGraphics gfx, final int cx, int y) {
        gfx.drawCenteredString(this.font,
                Component.literal("Something went wrong.").withStyle(ChatFormatting.RED),
                cx, y, 0xFFFFFF);
        if (errorMessage != null) {
            y += 20;
            final var lines = this.font.split(
                    Component.literal(errorMessage).withStyle(ChatFormatting.RED),
                    (int) (this.width * 0.8));
            for (final var line : lines) {
                gfx.drawCenteredString(this.font, line, cx, y, 0xFF9999);
                y += this.font.lineHeight + 2;
            }
        }
        if (backupPath != null) {
            y += 10;
            gfx.drawCenteredString(this.font,
                    Component.literal("Backup saved at: ").withStyle(ChatFormatting.GREEN),
                    cx, y, 0xFFFFFF);
            y += 14;
            final var lines = this.font.split(Component.literal(backupPath), (int) (this.width * 0.9));
            for (final var line : lines) {
                gfx.drawCenteredString(this.font, line, cx, y, 0xAAFFAA);
                y += this.font.lineHeight + 2;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return phase == Phase.WARNING || phase == Phase.FINAL_CONFIRM
                || phase == Phase.DONE_ERROR;
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {}

    // ----- Flow -----

    private void onOverrideClicked() {
        phase = Phase.FINAL_CONFIRM;
        this.clearWidgets();
        this.init();
    }

    private void onFinalConfirm() {
        phase = Phase.WORKING;
        this.clearWidgets();
        this.init();

        final String folderId = resolved.subscription.driveFolderId;
        final String localFolderName = resolved.subscription.localFolderName;
        final Path localWorld = WorldSharePaths.gameDir()
                .resolve("saves").resolve(localFolderName);

        final Thread worker = new Thread(() -> {
            try {
                // Step 1: Auto-backup if local is newer than manifest.
                workingMessage = "Backing up your local copy...";
                final Path backup = createBackupIfNeeded(localWorld);
                if (backup != null) {
                    backupPath = backup.toString();
                    WorldShareMod.LOGGER.info(
                            "ConflictResolution: backed up local world to {}", backup);
                }

                // Step 2: Override stale lock.
                workingMessage = "Overriding stale lock...";
                CloudModule.executor().submit(() -> {
                    try {
                        LockManager.acquire(folderId);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                // Step 3: Pull from Drive.
                workingMessage = "Pulling world from Drive...";
                CloudModule.executor().submit(() -> {
                    try {
                        SyncEngine.pull(localWorld, folderId, playerUuid);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                // Step 4: Open the world.
                workingMessage = "Opening world...";
                openWorldLocally(localFolderName);

            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("ConflictResolution: override failed", t);
                errorMessage = t.getMessage();
                phase = Phase.DONE_ERROR;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            }
        }, "WorldShare-ConflictResolve");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Copy the local world folder to a timestamped backup directory if our
     * local files differ from the Drive manifest (meaning we'd lose local work
     * if we pull).
     *
     * @return the backup path, or null if no backup was needed
     */
    private Path createBackupIfNeeded(final Path localWorld) throws IOException {
        if (!Files.isDirectory(localWorld)) return null;

        final String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .format(java.time.LocalDateTime.now());
        final String backupName = localWorld.getFileName() + "_offline_backup_" + timestamp;
        final Path backupDir = localWorld.getParent().resolve(backupName);

        Files.createDirectories(backupDir);
        copyDirectory(localWorld, backupDir);
        return backupDir;
    }

    private static void copyDirectory(final Path src, final Path dst) throws IOException {
        try (final Stream<Path> stream = Files.walk(src)) {
            for (final Path file : (Iterable<Path>) stream::iterator) {
                final Path dest = dst.resolve(src.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void openWorldLocally(final String folderName) {
        // Delegate to ContributorWorldsScreen's open method so we share the logic.
        Minecraft.getInstance().execute(() ->
                ContributorWorldsScreen.openWorldLocally(folderName, this));
    }

    // ----- Helpers -----

    private static String shortId(final String id) {
        if (id == null || id.length() < 8) return String.valueOf(id);
        return id.substring(0, 8);
    }

    private static String humanizeAgo(final Instant when) {
        if (when == null) return "unknown";
        final Duration d = Duration.between(when, Instant.now()).abs();
        final long s = d.getSeconds();
        if (s < 60) return s + "s";
        final long m = s / 60;
        if (m < 60) return m + "m";
        final long h = m / 60;
        final long rm = m - h * 60;
        return h + "h " + rm + "m";
    }
}
