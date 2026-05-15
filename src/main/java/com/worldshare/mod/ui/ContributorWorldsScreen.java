package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.LockManager;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldLink;
import com.worldshare.mod.config.WorldSubscription;
import com.worldshare.mod.sync.SyncEngine;
import com.worldshare.mod.sync.SyncProgress;
import com.worldshare.mod.util.WorldSharePaths;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Contributor Worlds screen — the heart of M5.
 *
 * <p>Lists all subscribed worlds with their current Drive state. Provides
 * the appropriate action button for each state, plus a small Remove button
 * on the LEFT side of each entry (away from the action button) to unsubscribe.
 *
 * <p><b>M7:</b>
 * <ul>
 *   <li>Remove button per entry, with confirmation dialog</li>
 *   <li>Crash recovery: when state is LOCKED_BY_US, skip the pull step
 *       (local files are authoritative since no one else could have
 *       uploaded while we held the lock)</li>
 *   <li>Download progress bar wired through SyncProgress</li>
 *   <li>403 / 404 error message helper</li>
 * </ul>
 */
public final class ContributorWorldsScreen extends Screen {

    private enum LoadState { IDLE, LOADING, DONE, ERROR }

    private volatile LoadState loadState = LoadState.IDLE;
    private volatile String loadError = null;
    private volatile int dlFilesDone = 0;
    private volatile int dlTotalFiles = 0;
    private volatile long dlBytesDone = 0L;
    private volatile long dlTotalBytes = 0L;
    private volatile boolean dlActive = false;
    private volatile boolean downloadInProgress = false;
    private final AtomicBoolean refreshRequested = new AtomicBoolean(false);

    private volatile List<WorldStateResolver.ResolvedWorld> worlds = List.of();

    private UUID playerUuid;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    private static final int ENTRY_HEIGHT = 36;
    private static final int LIST_TOP = 60;
    private static final int LIST_BOTTOM_MARGIN = 90;

    // Button geometry constants.
    private static final int ACTION_BTN_W = 90;
    private static final int ACTION_BTN_H = 14;
    private static final int REMOVE_BTN_W = 24;
    private static final int REMOVE_BTN_H = 14;
    private static final int REMOVE_BTN_LEFT_MARGIN = 6;

    private Button addButton;
    private Button refreshButton;
    private Button backButton;

    public ContributorWorldsScreen() {
        super(Component.literal("Contributor Worlds"));
    }

    @Override
    protected void init() {
        playerUuid = resolvePlayerUuid();

        if (!downloadInProgress) {
            final int bY = this.height - 52;
            addButton = this.addRenderableWidget(Button.builder(
                            Component.literal("+ Add World"),
                            btn -> Minecraft.getInstance().setScreen(new AddSubscriptionScreen(this)))
                    .bounds(this.width / 2 - 155, bY, 100, 20).build());

            refreshButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Refresh"),
                            btn -> triggerRefresh())
                    .bounds(this.width / 2 - 50, bY, 100, 20).build());

            backButton = this.addRenderableWidget(Button.builder(
                            Component.literal("Back"),
                            btn -> Minecraft.getInstance().setScreen(new TitleScreen()))
                    .bounds(this.width / 2 + 55, bY, 100, 20).build());
        }

        if (loadState == LoadState.IDLE || refreshRequested.compareAndSet(true, false)) {
            startLoading();
        }
    }

    public void triggerRefresh() {
        downloadInProgress = false;
        refreshRequested.set(true);
        loadState = LoadState.IDLE;
        startLoading();
    }

    private void startLoading() {
        loadState = LoadState.LOADING;
        loadError = null;
        worlds = List.of();
        selectedIndex = -1;

        if (SubscriptionStore.get().isEmpty()) {
            loadState = LoadState.DONE;
            return;
        }

        final UUID uuid = playerUuid;
        CloudModule.executor().submit(() -> {
            try {
                final com.worldshare.mod.cloud.DriveClient client = CloudModule.driveClient();
                final List<WorldStateResolver.ResolvedWorld> result =
                        WorldStateResolver.resolveAll(client, uuid);
                worlds = result;
                loadState = LoadState.DONE;
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("ContributorWorldsScreen: load failed", t);
                loadError = formatDriveError(t, "Could not reach Drive");
                loadState = LoadState.ERROR;
            }
        });
    }

    @Override
    public void render(final GuiGraphics gfx, final int mouseX, final int mouseY,
                       final float partial) {
        renderBackground(gfx, mouseX, mouseY, partial);
        super.render(gfx, mouseX, mouseY, partial);

        gfx.drawCenteredString(this.font,
                Component.literal("Contributor Worlds").withStyle(ChatFormatting.YELLOW),
                this.width / 2, 15, 0xFFFFFF);

        switch (loadState) {
            case LOADING -> renderLoading(gfx);
            case ERROR -> renderLoadError(gfx);
            case DONE -> renderWorldList(gfx, mouseX, mouseY);
            case IDLE -> {}
        }
    }

    private void renderLoading(final GuiGraphics gfx) {
        final int cx = this.width / 2;
        if (!dlActive || dlTotalFiles == 0) {
            gfx.drawCenteredString(this.font,
                    Component.literal("Checking Drive...").withStyle(ChatFormatting.GRAY),
                    cx, this.height / 2, 0xFFFFFF);
            return;
        }
        gfx.drawCenteredString(this.font,
                Component.literal("Downloading from Drive...")
                        .withStyle(ChatFormatting.YELLOW),
                cx, this.height / 2 - 30, 0xFFFFFF);
        final int barW = 300, barH = 16;
        final int barX = (this.width - barW) / 2;
        final int barY = this.height / 2 - 8;
        gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
        gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
        final int fillPx = dlTotalBytes > 0
                ? (int) (barW * Math.min(1.0, (double) dlBytesDone / dlTotalBytes))
                : (int) (barW * Math.min(1.0, (double) dlFilesDone / dlTotalFiles));
        gfx.fill(barX, barY, barX + fillPx, barY + barH, 0xFF44AA44);
        final int pct = dlTotalBytes > 0
                ? (int) (100L * dlBytesDone / Math.max(1L, dlTotalBytes))
                : (int) (100L * dlFilesDone / Math.max(1, dlTotalFiles));
        gfx.drawCenteredString(this.font,
                Component.literal(pct + "%  -  " + dlFilesDone + "/" + dlTotalFiles
                        + " files  -  " + (dlBytesDone / (1024 * 1024)) + " / "
                        + (dlTotalBytes / (1024 * 1024)) + " MB"),
                cx, barY + barH + 8, 0xCCCCCC);
    }

    private void renderLoadError(final GuiGraphics gfx) {
        gfx.drawCenteredString(this.font,
                Component.literal("Could not reach Drive.").withStyle(ChatFormatting.RED),
                this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        if (loadError != null) {
            gfx.drawCenteredString(this.font,
                    Component.literal(loadError).withStyle(ChatFormatting.GRAY),
                    this.width / 2, this.height / 2, 0xCCCCCC);
        }
        gfx.drawCenteredString(this.font,
                Component.literal("Use [Refresh] to try again."),
                this.width / 2, this.height / 2 + 20, 0xAAAAAA);
    }

    private void renderWorldList(final GuiGraphics gfx, final int mouseX, final int mouseY) {
        final List<WorldStateResolver.ResolvedWorld> list = worlds;

        if (list.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal(
                            "No worlds yet. Click [+ Add World] to subscribe to a Drive folder."),
                    this.width / 2, this.height / 2, 0xAAAAAA);
            return;
        }

        final int listBottom = this.height - LIST_BOTTOM_MARGIN;
        final int visibleHeight = listBottom - LIST_TOP;
        final int maxScroll = Math.max(0, list.size() * ENTRY_HEIGHT - visibleHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        gfx.enableScissor(0, LIST_TOP, this.width, listBottom);

        int y = LIST_TOP - scrollOffset;
        for (int i = 0; i < list.size(); i++) {
            renderWorldEntry(gfx, list.get(i), y, i == selectedIndex, mouseX, mouseY);
            y += ENTRY_HEIGHT;
        }

        gfx.disableScissor();

        if (maxScroll > 0) {
            final int scrollbarX = this.width - 8;
            final int trackH = listBottom - LIST_TOP;
            final int thumbH = Math.max(20, trackH * visibleHeight / (list.size() * ENTRY_HEIGHT));
            final int thumbY = LIST_TOP + (int) ((long) scrollOffset * (trackH - thumbH) / maxScroll);
            gfx.fill(scrollbarX, LIST_TOP, scrollbarX + 6, listBottom, 0x44FFFFFF);
            gfx.fill(scrollbarX, thumbY, scrollbarX + 6, thumbY + thumbH, 0xAAFFFFFF);
        }
    }

    private void renderWorldEntry(final GuiGraphics gfx,
                                  final WorldStateResolver.ResolvedWorld world,
                                  final int y, final boolean selected,
                                  final int mouseX, final int mouseY) {
        // Leave room on the LEFT for the Remove button.
        final int leftPadding = REMOVE_BTN_LEFT_MARGIN + REMOVE_BTN_W + 8;
        final int x = 10;
        final int w = this.width - 20;
        final int h = ENTRY_HEIGHT - 2;

        gfx.fill(x, y, x + w, y + h, selected ? 0x88444466 : 0x44222222);

        // Remove button (LEFT side)
        renderRemoveButton(gfx, y);

        // Badge + title (offset to leave room for Remove)
        gfx.drawString(this.font, badge(world.state),
                x + leftPadding, y + 4, badgeColor(world.state));
        gfx.drawString(this.font,
                Component.literal(world.displayName()).withStyle(ChatFormatting.WHITE),
                x + leftPadding, y + 16, 0xFFFFFF);

        final String subtitle = subtitle(world);
        if (subtitle != null) {
            gfx.drawString(this.font,
                    Component.literal(subtitle).withStyle(ChatFormatting.GRAY),
                    x + leftPadding + 90, y + 4, 0x888888);
        }

        renderActionButton(gfx, world, y);
    }

    private void renderRemoveButton(final GuiGraphics gfx, final int entryY) {
        final int btnX = 10 + REMOVE_BTN_LEFT_MARGIN;
        final int btnY = entryY + (ENTRY_HEIGHT - 2 - REMOVE_BTN_H) / 2;
        // Reddish hue, kept small and visually distinct from the green action button.
        gfx.fill(btnX, btnY, btnX + REMOVE_BTN_W, btnY + REMOVE_BTN_H, 0x88662222);
        gfx.drawCenteredString(this.font,
                Component.literal("X"),
                btnX + REMOVE_BTN_W / 2, btnY + 3,
                0xFFAAAA);
    }

    private void renderActionButton(final GuiGraphics gfx,
                                    final WorldStateResolver.ResolvedWorld world,
                                    final int y) {
        final int btnX = this.width - 10 - ACTION_BTN_W;
        final int btnY = y + (ENTRY_HEIGHT - 2 - ACTION_BTN_H) / 2;
        final boolean enabled = isActionEnabled(world);
        gfx.fill(btnX, btnY, btnX + ACTION_BTN_W, btnY + ACTION_BTN_H,
                enabled ? 0x88224422 : 0x44333333);
        gfx.drawCenteredString(this.font,
                actionLabel(world), btnX + ACTION_BTN_W / 2, btnY + 3,
                enabled ? 0xAAFFAA : 0x888888);
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (loadState != LoadState.DONE) return super.mouseClicked(mouseX, mouseY, button);

        final List<WorldStateResolver.ResolvedWorld> list = worlds;
        final int listBottom = this.height - LIST_BOTTOM_MARGIN;

        if (mouseY >= LIST_TOP && mouseY < listBottom) {
            final int clickedIndex = ((int) mouseY - LIST_TOP + scrollOffset) / ENTRY_HEIGHT;
            if (clickedIndex >= 0 && clickedIndex < list.size()) {
                selectedIndex = clickedIndex;
                final WorldStateResolver.ResolvedWorld world = list.get(clickedIndex);

                // Remove button hit-test (LEFT side)
                final int rmBtnX = 10 + REMOVE_BTN_LEFT_MARGIN;
                final int rmBtnW = REMOVE_BTN_W;
                if (mouseX >= rmBtnX && mouseX <= rmBtnX + rmBtnW) {
                    Minecraft.getInstance().setScreen(
                            new ConfirmRemoveSubscriptionScreen(this, world.subscription));
                    return true;
                }

                // Action button hit-test (RIGHT side)
                final int actBtnX = this.width - 10 - ACTION_BTN_W;
                if (mouseX >= actBtnX && mouseX <= actBtnX + ACTION_BTN_W
                        && isActionEnabled(world)) {
                    handleAction(world);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double deltaX, final double deltaY) {
        scrollOffset = Math.max(0, scrollOffset - (int) (deltaY * ENTRY_HEIGHT));
        return true;
    }

    private void handleAction(final WorldStateResolver.ResolvedWorld world) {
        switch (world.state) {
            case LIVE -> onJoin(world);
            case AVAILABLE -> onOpen(world);
            case LOCKED_BY_US -> onResumeOurLock(world);
            case STALE_LOCK_CLEAN -> onOpenWithStaleLockOverride(world);
            case STALE_LOCK_CONFLICT -> onResolveConflict(world);
            case NOT_DOWNLOADED -> onDownload(world);
            default -> {}
        }
    }

    private void onJoin(final WorldStateResolver.ResolvedWorld world) {
        if (world.presence == null || world.presence.e4mc_link == null) return;
        final Minecraft mc = Minecraft.getInstance();
        mc.prepareForMultiplayer();
        final ServerAddress address = ServerAddress.parseString(world.presence.e4mc_link);
        final ServerData serverData = new ServerData(
                "WorldShare - " + world.presence.host,
                world.presence.e4mc_link,
                ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, serverData, false, null);
    }

    private void onOpen(final WorldStateResolver.ResolvedWorld world) {
        if (!world.subscription.hasLocalFolder()) {
            onDownload(world);
            return;
        }
        acquireLockThenPullThenOpen(world, false);
    }

    /**
     * Resume our own crashed session. Crash recovery — skip the pull step
     * because no one else could have uploaded while we held the lock, and
     * our local files contain unsaved progress from before the crash.
     */
    private void onResumeOurLock(final WorldStateResolver.ResolvedWorld world) {
        acquireLockThenPullThenOpen(world, true);
    }

    private void onOpenWithStaleLockOverride(final WorldStateResolver.ResolvedWorld world) {
        Minecraft.getInstance().setScreen(new ConfirmStaleLockOverrideScreen(
                this, world,
                confirmed -> acquireLockThenPullThenOpen(confirmed, false)));
    }

    private void onResolveConflict(final WorldStateResolver.ResolvedWorld world) {
        Minecraft.getInstance().setScreen(
                new ConflictResolutionScreen(world, this, playerUuid));
    }

    /**
     * First-time download flow with folder collision detection.
     */
    private void onDownload(final WorldStateResolver.ResolvedWorld world) {
        final String folderId = world.subscription.driveFolderId;
        final String displayName = world.displayName();
        final String preferred = sanitizeFolderName(displayName);

        final String folderName = resolveDownloadFolderName(preferred, folderId);
        final Path localWorld = WorldSharePaths.gameDir().resolve("saves").resolve(folderName);

        final boolean renamedFromPreferred = !folderName.equals(preferred);
        if (renamedFromPreferred) {
            WorldShareMod.LOGGER.info(
                    "ContributorWorlds: '{}' already exists, downloading as '{}'",
                    preferred, folderName);
        }

        loadState = LoadState.LOADING;
        downloadInProgress = true;
        Minecraft.getInstance().execute(() -> {
            this.clearWidgets();
            this.init();
        });

        CloudModule.executor().submit(() -> {
            try {
                Files.createDirectories(localWorld);
                SubscriptionStore.get().linkWorldToFolder(
                        localWorld, folderName, folderId, displayName);
                SyncEngine.pull(localWorld, folderId, playerUuid, makeProgress());
                if (renamedFromPreferred) {
                    WorldShareMod.LOGGER.info(
                            "ContributorWorlds: downloaded as '{}' to avoid collision",
                            folderName);
                }
                Minecraft.getInstance().execute(this::triggerRefresh);
            } catch (final Throwable t) {
                downloadInProgress = false;  // M7: re-show nav buttons on error
                WorldShareMod.LOGGER.error("ContributorWorlds: download failed", t);
                loadError = formatDriveError(t, "Download failed");
                loadState = LoadState.ERROR;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            }
        });
    }

    /**
     * Acquire the lock, optionally pull, then open the world. Common path
     * for AVAILABLE, LOCKED_BY_US (crash recovery), STALE_LOCK_CLEAN.
     *
     * @param skipPull if true, skip the pull step entirely (used for crash
     *                 recovery where local files are authoritative)
     */
    private void acquireLockThenPullThenOpen(final WorldStateResolver.ResolvedWorld world,
                                             final boolean skipPull) {
        final String folderId = world.subscription.driveFolderId;
        final String localFolderName = world.subscription.localFolderName;
        if (localFolderName == null) {
            onDownload(world);
            return;
        }
        final Path localWorld = WorldSharePaths.gameDir()
                .resolve("saves").resolve(localFolderName);

        loadState = LoadState.LOADING;

        CloudModule.executor().submit(() -> {
            boolean lockAcquired = false;
            try {
                // Modpack check before lock or pull.
                final com.worldshare.mod.modmanager.ModManagerModule.ModCheckResult modCheck =
                        com.worldshare.mod.modmanager.ModManagerModule
                                .checkGuestMissingMods(folderId);
                if (modCheck.hasIssues()) {
                    WorldShareMod.LOGGER.info(
                            "ContributorWorlds: modpack issues for '{}': "
                                    + "{} missing, {} wrong version",
                            world.displayName(),
                            modCheck.missing.size(),
                            modCheck.wrongVersion.size());
                    loadState = LoadState.DONE;
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(
                                    new com.worldshare.mod.ui.ModpackSyncScreen(
                                            world.displayName(), modCheck)));
                    return;
                }

                LockManager.acquire(folderId);
                lockAcquired = true;

                if (skipPull) {
                    WorldShareMod.LOGGER.info(
                            "ContributorWorlds: crash recovery for '{}' - "
                                    + "skipping pull, local files are authoritative",
                            world.displayName());
                } else {
                    SyncEngine.pull(localWorld, folderId, playerUuid, makeProgress());
                }
                openWorldLocally(localFolderName);
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.error("ContributorWorlds: open failed", t);

                // M7: release lock if we acquired it but couldn't proceed to open.
                // ONLY when skipPull is false (normal Open path). For crash recovery
                // (skipPull=true), keep the lock so the user can retry resume.
                if (lockAcquired && !skipPull) {
                    try {
                        LockManager.release();
                        WorldShareMod.LOGGER.info(
                                "ContributorWorlds: released lock after open failure");
                    } catch (final Throwable releaseErr) {
                        WorldShareMod.LOGGER.warn(
                                "ContributorWorlds: lock release failed: {}",
                                releaseErr.getMessage());
                    }
                }

                loadError = formatDriveError(t, "Could not open world");
                loadState = LoadState.ERROR;
                Minecraft.getInstance().execute(() -> {
                    this.clearWidgets();
                    this.init();
                });
            }
        });
    }

    /**
     * Build a SyncProgress that updates this screen's download progress fields.
     */
    private SyncProgress makeProgress() {
        return new SyncProgress() {
            @Override
            public void onStart(final int totalFiles, final long totalBytes) {
                dlActive = true;
                dlTotalFiles = totalFiles;
                dlTotalBytes = totalBytes;
                dlFilesDone = 0;
                dlBytesDone = 0L;
            }

            @Override
            public void onFileProgress(final int filesDone, final int totalFiles,
                                       final long bytesDone, final long totalBytes,
                                       final String currentFile) {
                dlFilesDone = filesDone;
                dlBytesDone = bytesDone;
            }

            @Override
            public void onComplete() {
                dlActive = false;
            }

            @Override
            public void onError(final Throwable error) {
                dlActive = false;
            }
        };
    }

    /**
     * Open a local world by its saves folder name on the Minecraft main thread.
     */
    public static void openWorldLocally(final String folderName, final Screen... ignored) {
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                mc.createWorldOpenFlows().openWorld(folderName, () -> {});
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.warn(
                        "openWorldLocally: failed for '{}': {} - returning to title",
                        folderName, t.getMessage());
                mc.setScreen(new TitleScreen());
            }
        });
    }

    // ---- Collision detection helpers ----

    private static String sanitizeFolderName(final String displayName) {
        final String sanitized = displayName
                .replaceAll("[^a-zA-Z0-9 _\\-]", "")
                .trim()
                .replace(' ', '_');
        return sanitized.isEmpty() ? "WorldShare_World" : sanitized;
    }

    private static String resolveDownloadFolderName(final String preferred,
                                                    final String driveFolderId) {
        final Path savesDir = WorldSharePaths.gameDir().resolve("saves");

        if (!Files.exists(savesDir.resolve(preferred))) return preferred;

        final WorldLink existingLink = WorldLink.read(savesDir.resolve(preferred));
        if (existingLink != null && driveFolderId.equals(existingLink.driveFolderId)) {
            return preferred;
        }

        for (int i = 2; i <= 99; i++) {
            final String candidate = preferred + "_" + i;
            if (!Files.exists(savesDir.resolve(candidate))) return candidate;
        }
        return preferred + "_" + System.currentTimeMillis();
    }

    // ---- Label/color helpers ----

    private static String badge(final WorldStateResolver.State state) {
        return switch (state) {
            case LIVE -> "- LIVE";
            case AVAILABLE -> "- Available";
            case LOCKED_BY_OTHER -> "- Locked";
            case STALE_LOCK_CLEAN -> "- Stale Lock";
            case STALE_LOCK_CONFLICT -> "- Conflict!";
            case LOCKED_BY_US -> "- Yours";
            case NOT_DOWNLOADED -> "- Not Downloaded";
            case ERROR -> "- Error";
        };
    }

    private static int badgeColor(final WorldStateResolver.State state) {
        return switch (state) {
            case LIVE -> 0x55FF55;
            case AVAILABLE -> 0xAAFFAA;
            case LOCKED_BY_OTHER -> 0xFF5555;
            case STALE_LOCK_CLEAN -> 0xFFAA55;
            case STALE_LOCK_CONFLICT -> 0xFF4444;
            case LOCKED_BY_US -> 0x55AAFF;
            case NOT_DOWNLOADED -> 0xAAAAAA;
            case ERROR -> 0xFF4444;
        };
    }

    private static String subtitle(final WorldStateResolver.ResolvedWorld world) {
        return switch (world.state) {
            case LIVE -> world.presence != null
                    ? "Hosted by " + world.presence.host : null;
            case LOCKED_BY_OTHER -> world.lock != null
                    ? "Locked by " + world.lock.holderName : null;
            case LOCKED_BY_US -> "Your last session";
            case STALE_LOCK_CLEAN, STALE_LOCK_CONFLICT -> world.lock != null
                    ? world.lock.holderName + " (offline)" : null;
            default -> null;
        };
    }

    private static String actionLabel(final WorldStateResolver.ResolvedWorld world) {
        return switch (world.state) {
            case LIVE -> "Join";
            case AVAILABLE -> world.subscription.hasLocalFolder() ? "Open" : "Download";
            case LOCKED_BY_OTHER -> "Locked";
            case STALE_LOCK_CLEAN -> "Open";
            case STALE_LOCK_CONFLICT -> "Resolve...";
            case LOCKED_BY_US -> "Resume";
            case NOT_DOWNLOADED -> "Download";
            case ERROR -> "Error";
        };
    }

    private static boolean isActionEnabled(final WorldStateResolver.ResolvedWorld world) {
        return switch (world.state) {
            case LOCKED_BY_OTHER, ERROR -> false;
            default -> true;
        };
    }

    private static UUID resolvePlayerUuid() {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.getUser() != null) {
                final UUID id = mc.getUser().getProfileId();
                if (id != null) return id;
                return UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + mc.getUser().getName())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (final Exception ignored) {}
        return UUID.randomUUID();
    }

    private static String formatDriveError(final Throwable t, final String defaultPrefix) {
        final String msg = t.getMessage() != null ? t.getMessage() : "";
        if (msg.contains("403")) {
            return "Permission denied. Ask the world owner to share the Drive folder with Editor (not Viewer) access.";
        }
        if (msg.contains("404") || msg.toLowerCase().contains("not found")) {
            return "Drive folder not found - it may have been deleted or moved. "
                    + "Run /worldshare clearDriveLink in your world, then re-add via Contributor Worlds.";
        }
        return defaultPrefix + ": " + (msg.isEmpty() ? t.getClass().getSimpleName() : msg);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // M7: prevent closing the screen during an active download.
        return !downloadInProgress;
    }

    @Override
    public void removed() {
        // If a download is in flight, leave the flag set; the worker is still
        // running and triggerRefresh will reset it. But if we get here cleanly
        // (idle), make sure state doesn't persist staleness.
        if (loadState == LoadState.ERROR) {
            loadState = LoadState.IDLE;
        }
    }

    @Override
    protected void renderBlurredBackground(final float partialTick) {}
}