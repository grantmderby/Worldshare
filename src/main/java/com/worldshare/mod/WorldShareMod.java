package com.worldshare.mod;

import com.mojang.logging.LogUtils;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.command.WorldShareCommands;
import com.worldshare.mod.config.SubscriptionStore;
import com.worldshare.mod.config.WorldShareConfig;
import com.worldshare.mod.modmanager.ModManagerModule;
import com.worldshare.mod.relay.RelayModule;
import com.worldshare.mod.sync.AutoSyncListener;
import com.worldshare.mod.ui.UiModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Main entry point for the WorldShare mod.
 *
 * <p>Wires up:
 * <ul>
 *   <li>Config registration via injected {@link ModContainer}</li>
 *   <li>{@link SubscriptionStore} initialisation (M5) — loads persisted
 *       world subscriptions and migrates the legacy single-folder config</li>
 *   <li>Lifecycle dispatch to cloud / relay / ui / modmanager modules</li>
 *   <li>{@code /worldshare} command tree</li>
 *   <li>{@link AutoSyncListener} for auto-push on quit</li>
 * </ul>
 *
 * <p>{@code E4mcCoordinator} and {@code TitleScreenButtonInjector} are
 * registered in {@link UiModule#init()} during client setup because they
 * are client-only event handlers.
 */
@Mod(WorldShareMod.MOD_ID)
public final class WorldShareMod {

    public static final String MOD_ID = "worldshare";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldShareMod(final IEventBus modBus, final ModContainer container) {
        LOGGER.info("WorldShare mod constructing...");

        container.registerConfig(
                ModConfig.Type.CLIENT,
                WorldShareConfig.SPEC,
                "worldshare-client.toml"
        );

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);

        NeoForge.EVENT_BUS.register(this);

        // ⚠️ CRITICAL — DO NOT REMOVE.
        // AutoSyncListener uses STATIC @SubscribeEvent methods.
        // Must register the CLASS, not an instance.
        NeoForge.EVENT_BUS.register(AutoSyncListener.class);

        LOGGER.info("WorldShare: registered AutoSyncListener on EVENT_BUS");
        LOGGER.info("WorldShare mod constructor complete.");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("WorldShare common setup starting...");
        event.enqueueWork(() -> {
            CloudModule.init();
            RelayModule.init();
            ModManagerModule.init();

            // M5: load subscription store, migrating legacy driveFolderId if present.
            final String legacyFolder = WorldShareConfig.get().driveFolderId.get();
            SubscriptionStore.get().load(legacyFolder);

            LOGGER.info("WorldShare common setup complete.");
        });
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("WorldShare client setup starting...");
        event.enqueueWork(() -> {
            UiModule.init();
            LOGGER.info("WorldShare client setup complete.");
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        WorldShareCommands.register(event.getDispatcher());
    }
}
