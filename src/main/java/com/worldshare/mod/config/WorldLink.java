package com.worldshare.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.RemoteFileSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny JSON file stored at {@code <worldRoot>/worldshare-link.json} that binds a
 * local saves folder to the Drive files it syncs through.
 *
 * <p>It used to hold a single Drive <em>folder</em> ID, and everything the mod
 * needed was found by name inside that folder. Under the {@code drive.file} scope
 * that no longer works: a narrow-scope token can't list a folder's contents, and
 * only files the user personally picked are reachable at all. So the link now
 * carries a {@link RemoteFileSet} - the explicit Drive file IDs for the control
 * document and every bucket archive.
 *
 * <p>All code that needs to reach a world's Drive data should:
 * <ol>
 *   <li>Read the {@code WorldContext.CurrentWorld.worldRoot}</li>
 *   <li>Call {@link #readRemote(Path)} for that world</li>
 *   <li>Fail loudly with a "re-run setup" message if it comes back null</li>
 * </ol>
 *
 * <p>Written by the world-setup flow (host creating a world) and by
 * {@link SubscriptionStore#linkWorldToRemote} (guest joining one).
 *
 * <p>The file is excluded from Drive sync by {@code TrackedPaths} since it is
 * machine-specific metadata, not world data.
 */
public final class WorldLink {

    public static final String FILENAME = "worldshare-link.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Drive file IDs this world syncs through. Null for a link file written by a
     * pre-{@code drive.file} version of the mod - see {@link #isLegacy()}.
     */
    public RemoteFileSet remote;

    /**
     * Display name for this world. Matches the entry in the subscription
     * store. May differ from the local folder name (e.g., the world was
     * renamed locally after downloading).
     */
    public String displayName;

    /**
     * The Drive folder ID this world used before the {@code drive.file} migration.
     *
     * <p>Retained only so an old link file can be <em>recognised</em> as old and
     * the player pointed at setup. It is deliberately not used to reach anything:
     * a folder ID is useless under the narrow scope, which is the whole reason for
     * the migration. Once {@link #remote} is populated this field is dead weight
     * and may be dropped in a future version.
     */
    public String driveFolderId;

    /** No-arg constructor for Gson. */
    public WorldLink() {}

    public WorldLink(final RemoteFileSet remote, final String displayName) {
        this.remote = remote;
        this.displayName = displayName;
    }

    /**
     * @return true if this link predates the {@code drive.file} migration: it names
     *         a Drive folder but has no picked file IDs, so the world can't be
     *         synced until the player re-runs setup
     */
    public boolean isLegacy() {
        return remote == null && driveFolderId != null && !driveFolderId.isBlank();
    }

    /** @return true if this link can actually be used to sync right now. */
    public boolean isUsable() {
        return remote != null && remote.isComplete();
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
     * The Drive file set for the world at {@code worldRoot}.
     *
     * <p>This is the accessor essentially every caller wants. It returns null in
     * three distinct situations that all mean the same thing operationally - "this
     * world isn't ready to sync" - but which are logged differently so the reason
     * is recoverable from a support log:
     * <ul>
     *   <li>no link file at all: the world was never set up here</li>
     *   <li>a legacy link: set up under the old full-Drive scope, needs re-picking</li>
     *   <li>an incomplete set: setup was started but the player didn't pick everything</li>
     * </ul>
     *
     * @return a complete {@link RemoteFileSet}, or null if the world can't be synced
     */
    public static RemoteFileSet readRemote(final Path worldRoot) {
        final WorldLink link = read(worldRoot);
        if (link == null) {
            return null;
        }
        if (link.isLegacy()) {
            WorldShareMod.LOGGER.warn(
                    "WorldLink: '{}' was linked under the old full-Drive scope (folder {}). "
                            + "It needs WorldShare setup re-run to pick its files.",
                    worldRoot.getFileName(), link.driveFolderId);
            return null;
        }
        if (link.remote == null) {
            return null;
        }
        if (!link.remote.isComplete()) {
            WorldShareMod.LOGGER.warn(
                    "WorldLink: '{}' has an incomplete Drive file set; still missing {}",
                    worldRoot.getFileName(), link.remote.missingFilenames());
            return null;
        }
        return link.remote;
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
        WorldShareMod.LOGGER.info("WorldLink: wrote {} -> control file {}",
                linkFile.getFileName(),
                remote == null ? "(none)" : remote.controlFileId);
    }

    /**
     * Convenience - write a link file with the given file set and name directly
     * into {@code worldRoot}.
     */
    public static void write(final Path worldRoot,
                             final RemoteFileSet remote,
                             final String displayName) throws IOException {
        new WorldLink(remote, displayName).write(worldRoot);
    }
}
