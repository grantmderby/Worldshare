package com.worldshare.mod;

import com.mojang.logging.LogUtils;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.command.WorldShareCommands;
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
 *   <li>The {@link WorldShareConfig} TOML config on CLIENT side</li>
 *   <li>Lifecycle dispatch to the four functional modules (cloud / relay / ui / modmanager)</li>
 *   <li>The {@code /worldshare} command tree, registered on every world load</li>
 *   <li>The {@link AutoSyncListener} for auto-push on Save and Quit</li>
 * </ul>
 *
 * <p>Note: {@code E4mcCoordinator} is NOT registered here. Its handlers are
 * client-only and must register from {@code FMLClientSetupEvent}, which is
 * done by {@link UiModule#init()}.
 *
 * <p>NeoForge 1.21.1 injects {@link ModContainer} into the constructor; we
 * use it for config registration. The older {@code ModLoadingContext.get()
 * .registerConfig(...)} pattern was removed in 1.21.1.
 */
@Mod(WorldShareMod.MOD_ID)
public final class WorldShareMod {

    public static final String MOD_ID = "worldshare";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldShareMod(final IEventBus modBus, final ModContainer container) {
        LOGGER.info("WorldShare mod constructing...");

        // NeoForge 1.21.1: register config via the injected ModContainer.
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
        // Removing this line silently breaks all auto-push functionality.
        // This was discovered after hours of debugging (events fired, nothing received).
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
