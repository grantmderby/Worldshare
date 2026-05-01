package com.worldshare.mod.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * WorldShare configuration.
 *
 * <p>Backed by Forge's {@link ModConfigSpec}, which writes a TOML file at
 * {@code config/worldshare-client.toml} in the user's Minecraft directory.
 *
 * <p>Used by:
 * <ul>
 *   <li>M1: {@code OAuthHelper} reads {@link #playerDisplayName} for the holder name</li>
 *   <li>M2: {@code LockManager} reads {@link #lockExpiryMinutes} and {@link #playerCap}</li>
 *   <li>M2: Commands read/write {@link #driveFolderId}</li>
 * </ul>
 *
 * <p>Note: OAuth tokens are NOT stored in this TOML — they live as a
 * separate FileDataStore at {@code config/worldshare/tokens/StoredCredential}
 * because TOML is not a safe place for refresh tokens.
 */
public final class WorldShareConfig {

    public static final ModConfigSpec SPEC;
    private static final WorldShareConfig INSTANCE;

    static {
        final Pair<WorldShareConfig, ModConfigSpec> pair =
                new ModConfigSpec.Builder().configure(WorldShareConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    /** A friendly display name shown to other contributors when this user holds the lock. */
    public final ModConfigSpec.ConfigValue<String> playerDisplayName;

    /**
     * The ID of the shared Google Drive folder that contains the world(s).
     * Empty by default - the user sets this in the settings screen after OAuth.
     */
    public final ModConfigSpec.ConfigValue<String> driveFolderId;

    /**
     * How long a lock is valid (without heartbeat refresh) before it's stale.
     * Stored as minutes to allow testing the stale-lock UX in seconds rather
     * than hours. Default of 480 minutes = 8 hours.
     */
    public final ModConfigSpec.IntValue lockExpiryMinutes;

    /** Soft cap on simultaneous players when hosting. */
    public final ModConfigSpec.IntValue playerCap;

    /** If true, detailed sync logs are written to worldshare.log. */
    public final ModConfigSpec.BooleanValue verboseLogging;

    private WorldShareConfig(final ModConfigSpec.Builder builder) {
        builder.comment("WorldShare settings")
                .push("general");

        playerDisplayName = builder
                .comment("Display name shown to other contributors. Leave blank to use your Minecraft username.")
                .define("playerDisplayName", "");

        driveFolderId = builder
                .comment("Google Drive folder ID for the shared world. Set via in-game settings after OAuth.")
                .define("driveFolderId", "");

        lockExpiryMinutes = builder
                .comment("Minutes before an unheartbeated session lock is considered stale. "
                        + "Default 480 = 8 hours. Set as low as 1 for testing the stale-lock UX.")
                .defineInRange("lockExpiryMinutes", 480, 1, 7 * 24 * 60);

        playerCap = builder
                .comment("Soft cap on simultaneous players when hosting.")
                .defineInRange("playerCap", 5, 1, 20);

        verboseLogging = builder
                .comment("Write detailed sync logs to worldshare.log for debugging.")
                .define("verboseLogging", false);

        builder.pop();
    }

    public static WorldShareConfig get() {
        return INSTANCE;
    }
}
