package com.worldshare.mod.util;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Central place for all filesystem paths WorldShare uses.
 *
 * <p>Built on top of Forge's {@link FMLPaths}, which gives us the Minecraft game
 * directory regardless of where the user actually installed their launcher or what
 * OS they're on. Every path this class returns is somewhere under the game directory.
 *
 * <p>Layout:
 * <pre>
 *   &lt;gamedir&gt;/
 *     config/
 *       worldshare-client.toml              ← ModConfigSpec-managed TOML (not here, handled by Forge)
 *       worldshare/
 *         tokens/StoredCredential           ← Google OAuth persisted refresh tokens
 *         client_secret.json                ← (optional override) OAuth app credentials
 *     logs/
 *       worldshare.log                      ← our log file (written in M7)
 * </pre>
 */
public final class WorldSharePaths {

    private static final String WORLDSHARE_DIR = "worldshare";
    private static final String TOKENS_DIR = "tokens";
    private static final String CLIENT_SECRET_OVERRIDE = "client_secret.json";

    private WorldSharePaths() {
        // utility class
    }

    /**
     * @return the Minecraft game directory (parent of config/, saves/, mods/, etc).
     */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * @return the config directory. Forge writes its TOML configs here; we put a
     *         WorldShare-specific subdirectory underneath for things that don't fit
     *         in a TOML (like OAuth tokens).
     */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /**
     * @return the WorldShare-specific config subdirectory:
     *         {@code <gamedir>/config/worldshare/}
     */
    public static Path worldshareConfigDir() {
        return configDir().resolve(WORLDSHARE_DIR);
    }

    /**
     * @return where we store Google's FileDataStore - the refresh token lives here:
     *         {@code <gamedir>/config/worldshare/tokens/}
     */
    public static Path tokensDir() {
        return worldshareConfigDir().resolve(TOKENS_DIR);
    }

    /**
     * @return an optional override path for {@code client_secret.json}:
     *         {@code <gamedir>/config/worldshare/client_secret.json}.
     *         If this file exists, it will be used INSTEAD of the one bundled in the mod jar.
     *         Useful for development / swapping credentials without rebuilding.
     */
    public static Path clientSecretOverride() {
        return worldshareConfigDir().resolve(CLIENT_SECRET_OVERRIDE);
    }
}
