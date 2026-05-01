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
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import net.neoforged.fml.ModContainer;

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
 * <p>NeoForge 1.21.1 differences from the Forge 1.20.1 version:
 * <ul>
 *   <li>{@code MinecraftForge.EVENT_BUS} → {@code NeoForge.EVENT_BUS}</li>
 *   <li>{@code FMLJavaModLoadingContext.get().getModEventBus()} → {@code IEventBus} parameter
 *       in the constructor</li>
 *   <li>All {@code net.minecraftforge.*} imports → {@code net.neoforged.*}</li>
 * </ul>
 */
@Mod(WorldShareMod.MOD_ID)
public final class WorldShareMod {

    /** The mod id. Must match the value in gradle.properties. */
    public static final String MOD_ID = "worldshare";

    /** Shared SLF4J logger. All mod code should use this rather than System.out. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * NeoForge passes the mod's event bus directly to our constructor (along with
     * the mod container, FML context, etc - all optional). This is one of the
     * cleaner NeoForge improvements: no more {@code FMLJavaModLoadingContext.get()}.
     */
    public WorldShareMod(final IEventBus modBus, final ModContainer container) {
        LOGGER.info("WorldShare mod constructing...");

        container.registerConfig(
                ModConfig.Type.CLIENT,
                WorldShareConfig.SPEC,
                "worldshare-client.toml"
        );

        // Lifecycle hooks on the mod event bus.
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);

        // Register ourselves on the NeoForge event bus so our @SubscribeEvent handlers
        // below (RegisterCommandsEvent, etc) are picked up.
        NeoForge.EVENT_BUS.register(this);

        // AutoSyncListener uses static @SubscribeEvent methods, so register the class.
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

    /**
     * Registers our /worldshare commands. Fired once on every world load
     * (integrated server start on singleplayer), which is the correct place
     * per NeoForge's documentation.
     */
    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        WorldShareCommands.register(event.getDispatcher());
    }
}
