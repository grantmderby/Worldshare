package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.relay.E4mcCoordinator;
import net.neoforged.neoforge.common.NeoForge;

/**
 * UI module — owns custom screens and pause-menu integration.
 *
 * <p>Components by milestone:
 * <ul>
 *   <li>M3: {@link PauseMenuHijacker} — replaces "Save and Quit" with "Save and Upload"
 *       for WorldShare-linked worlds only (M5 update)</li>
 *   <li>M4: {@link E4mcCoordinator} — title-screen presence poll, host domain capture</li>
 *   <li>M5: {@link TitleScreenButtonInjector} — "Contributor Worlds" button on title screen</li>
 *   <li>M5: {@link SelectWorldGuard} — warns when WorldShare worlds opened from vanilla
 *       Singleplayer, bypassing lock/pull flow</li>
 * </ul>
 *
 * <p>All registrations are client-only. {@link WorldShareMod}
 * only calls {@link #init()} from {@code FMLClientSetupEvent}.
 */
public final class UiModule {

    private UiModule() {}

    public static void init() {
        NeoForge.EVENT_BUS.register(PauseMenuHijacker.class);
        NeoForge.EVENT_BUS.register(E4mcCoordinator.class);
        NeoForge.EVENT_BUS.register(TitleScreenButtonInjector.class);

        WorldShareMod.LOGGER.info(
                "UiModule initialized: registered PauseMenuHijacker, E4mcCoordinator, "
                + "TitleScreenButtonInjector, SelectWorldGuard.");
    }
}
