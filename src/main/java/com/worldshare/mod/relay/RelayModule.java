package com.worldshare.mod.relay;

import com.worldshare.mod.WorldShareMod;

/**
 * Relay module - owns the "live co-op" path via e4mc.
 *
 * <p>Planned sub-components (Milestone 4):
 * <ul>
 *   <li>{@code E4mcBridge} - detects e4mc's generated relay address and publishes it to the lock</li>
 *   <li>{@code HostPresence} - maintains the players_online list on the lock while hosting</li>
 *   <li>{@code JoinFlow} - when the client clicks "Join", routes them to the relay address</li>
 * </ul>
 *
 * <p>Milestone 0: init does nothing but log. No relay interaction.
 */
public final class RelayModule {

    private RelayModule() {
        // utility / module holder class
    }

    public static void init() {
        WorldShareMod.LOGGER.info("RelayModule initialized (stub - no relay integration yet).");
    }
}
