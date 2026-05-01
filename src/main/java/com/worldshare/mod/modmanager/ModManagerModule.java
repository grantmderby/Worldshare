package com.worldshare.mod.modmanager;

import com.worldshare.mod.WorldShareMod;

/**
 * Mod Manager module - owns the "one-click modpack install" flow.
 *
 * <p>Planned sub-components (Milestone 6):
 * <ul>
 *   <li>{@code ModpackManifest} - the JSON schema for a shareable modpack snapshot</li>
 *   <li>{@code ModrinthClient} - resolves project/version IDs to download URLs</li>
 *   <li>{@code LocalModScanner} - hashes the contents of the {@code mods/} folder</li>
 *   <li>{@code ModpackSyncUi} - the "Install All Missing Mods" dialog</li>
 * </ul>
 *
 * <p>Milestone 0: init does nothing but log.
 */
public final class ModManagerModule {

    private ModManagerModule() {
        // utility / module holder class
    }

    public static void init() {
        WorldShareMod.LOGGER.info("ModManagerModule initialized (stub - no modpack sync yet).");
    }
}
