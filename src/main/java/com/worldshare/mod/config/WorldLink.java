package com.worldshare.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny JSON file stored at {@code <worldRoot>/worldshare-link.json} that
 * binds a local saves folder to its corresponding Google Drive folder.
 *
 * <p>This is the M5 replacement for the global {@code driveFolderId} config
 * value. All code that previously read the global config to find the Drive
 * folder should now:
 * <ol>
 *   <li>Read the {@code WorldContext.CurrentWorld.worldRoot}</li>
 *   <li>Call {@link #read(Path)} to get the link for that world</li>
 *   <li>Use {@link #driveFolderId} from the result</li>
 * </ol>
 *
 * <p>Written by:
 * <ul>
 *   <li>{@code /worldshare setfolder} — host binds currently-open world to Drive</li>
 *   <li>{@link SubscriptionStore#linkWorldToFolder} — guest downloads world for first time</li>
 * </ul>
 *
 * <p>The file is excluded from Drive sync by {@code TrackedPaths} since it is
 * machine-specific metadata, not world data.
 */
public final class WorldLink {

    public static final String FILENAME = "worldshare-link.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Drive folder ID this world syncs to/from. */
    public String driveFolderId;

    /**
     * Display name for this world. Matches the entry in the subscription
     * store. May differ from the local folder name (e.g., the world was
     * renamed locally after downloading).
     */
    public String displayName;

    /** No-arg constructor for Gson. */
    public WorldLink() {}

    public WorldLink(final String driveFolderId, final String displayName) {
        this.driveFolderId = driveFolderId;
        this.displayName = displayName;
    }

    // ----- I/O -----

    /**
     * Read the link file from a world's root folder.
     *
     * @param worldRoot absolute path to the local world folder
     *                  (the one containing {@code level.dat})
     * @return the link, or {@code null} if no link file exists
     */
    public static WorldLink read(final Path worldRoot) {
        final Path linkFile = worldRoot.resolve(FILENAME);
        if (!Files.exists(linkFile)) return null;
        try {
            final String json = Files.readString(linkFile, StandardCharsets.UTF_8);
            return GSON.fromJson(json, WorldLink.class);
        } catch (final Exception e) {
            WorldShareMod.LOGGER.warn("WorldLink: failed to read {}: {}", linkFile, e.getMessage());
            return null;
        }
    }

    /**
     * Write the link file into a world's root folder, creating the folder
     * if it doesn't exist yet.
     *
     * @param worldRoot absolute path to the local world folder
     * @throws IOException if the write fails
     */
    public void write(final Path worldRoot) throws IOException {
        Files.createDirectories(worldRoot);
        final Path linkFile = worldRoot.resolve(FILENAME);
        Files.writeString(linkFile, GSON.toJson(this), StandardCharsets.UTF_8);
        WorldShareMod.LOGGER.info("WorldLink: wrote {} -> folder {}",
                linkFile.getFileName(), driveFolderId);
    }

    /**
     * Convenience — write a link file with the given folder ID and name
     * directly into {@code worldRoot}.
     */
    public static void write(final Path worldRoot,
                             final String driveFolderId,
                             final String displayName) throws IOException {
        new WorldLink(driveFolderId, displayName).write(worldRoot);
    }

    /**
     * @return the Drive folder ID for the world at {@code worldRoot}, or
     *         {@code null} if no link file exists or it is malformed.
     */
    public static String readFolderId(final Path worldRoot) {
        final WorldLink link = read(worldRoot);
        return (link == null) ? null : link.driveFolderId;
    }
}
