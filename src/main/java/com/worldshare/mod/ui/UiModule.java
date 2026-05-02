package com.worldshare.mod.ui;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.relay.E4mcCoordinator;
import net.neoforged.neoforge.common.NeoForge;

/**
 * UI module - owns custom screens and pause-menu integration.
 *
 * <p>Components by milestone:
 * <ul>
 *   <li>M3 Phase 2b: {@link PauseMenuHijacker}, {@link SaveAndUploadScreen} —
 *       intercept the "Save and Quit to Title" button and run the upload flow.</li>
 *   <li>M4: registers {@link E4mcCoordinator} for client-side e4mc events
 *       (title-screen presence poll). The class is registered here rather
 *       than in the WorldShareMod constructor because its handlers are
 *       client-only and must register after Minecraft is initialized.</li>
 *   <li>M5 (planned): Contributor Worlds tab on the title screen, settings screen.</li>
 * </ul>
 *
 * <p>Note: this module is client-only. {@link WorldShareMod}
 * only invokes {@link #init()} from {@code FMLClientSetupEvent}, never from
 * common setup.
 */
public final class UiModule {

    private UiModule() {
        // utility / module holder class
    }

    public static void init() {
        // PauseMenuHijacker uses static @SubscribeEvent methods. Register the class.
        NeoForge.EVENT_BUS.register(PauseMenuHijacker.class);

        // E4mcCoordinator also uses static @SubscribeEvent methods.
        // Registered here (not in WorldShareMod constructor) because its
        // handlers are client-only. Server-side cleanup (presence.json delete)
        // is triggered from AutoSyncListener.onServerStopping instead.
        NeoForge.EVENT_BUS.register(E4mcCoordinator.class);

        WorldShareMod.LOGGER.info(
                "UiModule initialized: registered PauseMenuHijacker and E4mcCoordinator.");
    }
}
