package com.worldshare.mod.relay;

import com.worldshare.mod.WorldShareMod;

/**
 * Relay module — owns the live co-op path via e4mc.
 *
 * <p>M4: initializes {@link E4mcCoordinator}, which handles:
 * <ul>
 *   <li>Host side: log appender domain capture, presence.json write/refresh/delete</li>
 *   <li>Guest side: title-screen presence poll, JoinPromptScreen display</li>
 * </ul>
 *
 * <p>The actual EVENT_BUS registration of E4mcCoordinator happens in
 * {@code UiModule.init()} during client setup, since its handlers are
 * client-only.
 */
public final class RelayModule {

    private RelayModule() {}

    public static void init() {
        WorldShareMod.LOGGER.info("RelayModule initialized.");
        E4mcCoordinator.init();
    }
}
