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
 *   <li>M2: {@code LockManager} reads {@link #lockExpiryMinutes}</li>
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
     * than hours. Default of 1440 minutes = 24 hours.
     *
     * <p>A day is deliberately generous. The heartbeat refreshes this every 15
     * minutes while someone is playing, so a live session never approaches the
     * limit and the lock clears on save/upload regardless - the only thing the
     * expiry governs is how long a player who dropped offline keeps their claim.
     * The failure it guards against is severe and asymmetric: someone loses power
     * mid-session and returns to find their world overwritten and hours of
     * building gone. Waiting longer to override a genuinely dead session is much
     * the cheaper mistake.
     */
    public final ModConfigSpec.IntValue lockExpiryMinutes;


    /** If true, detailed sync logs are written to worldshare.log. */
    public final ModConfigSpec.BooleanValue verboseLogging;

    /**
     * Whether opening a shared world automatically starts an e4mc live session.
     *
     * <p>Off by default, and that default is the point. This used to happen
     * unconditionally whenever you held the lock, so anyone who installed e4mc for
     * a single session was publishing a public relay address every time they played
     * afterwards - without asking, and without WorldShare ever mentioning it.
     * Hosting is now something you choose, with /worldshare host.
     */
    public final ModConfigSpec.BooleanValue autoHostOnOpen;

    /**
     * Seconds before "Continue in Background" appears on the save-and-upload screen.
     *
     * <p>Three seconds, down from a hardcoded thirty. That thirty was chosen when a
     * push meant re-uploading most of the world; bucket tiling and dirty tracking
     * cut a typical save to about fifteen seconds, at which point the button was
     * arriving with a third of the upload left and the paths behind it - the
     * background toast among them - had never been exercised at all.
     *
     * <p>It is a reasonable preference in its own right: on a slow connection you
     * may want the button immediately.
     */
    public final ModConfigSpec.IntValue backgroundButtonDelaySeconds;

    /**
     * Whether the developer subcommands appear at all.
     *
     * <p>Off by default, and two of them are the reason: {@code /worldshare push}
     * uploads a world that is still open, so what reaches Drive is missing whatever
     * the session hasn't written to disk; and {@code lock}/{@code unlock} change the
     * Drive lock without the running client knowing, producing exactly the
     * disagreement between local and remote lock state that several bugs here came
     * from. The rest are diagnostics that {@code /worldshare doctor} already covers.
     */
    public final ModConfigSpec.BooleanValue devCommands;

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
                        + "Default 1440 = 24 hours. Set as low as 1 for testing the stale-lock UX.")
                .defineInRange("lockExpiryMinutes", 1440, 1, 7 * 24 * 60);

        verboseLogging = builder
                .comment("Write detailed sync logs to worldshare.log for debugging.")
                .define("verboseLogging", false);

        autoHostOnOpen = builder
                .comment("Automatically start an e4mc live session when you open a shared "
                        + "world you hold the lock on. Off by default: hosting publishes a "
                        + "public relay address, which should be a choice rather than a "
                        + "side effect. Use /worldshare host to start one on demand.")
                .define("autoHostOnOpen", false);

        backgroundButtonDelaySeconds = builder
                .comment("Seconds before the save-and-upload screen offers "
                        + "\"Continue in Background\". 0 shows it immediately. "
                        + "Default 3.")
                .defineInRange("backgroundButtonDelaySeconds", 3, 0, 300);

        devCommands = builder
                .comment("Expose the developer subcommands (push, lock, unlock, "
                        + "heartbeat, lockinfo, test, modpack). Off by default: several "
                        + "of them can damage a shared world if used at the wrong "
                        + "moment. /worldshare doctor covers the diagnostics.")
                .define("devCommands", false);

        builder.pop();
    }

    public static WorldShareConfig get() {
        return INSTANCE;
    }
}
