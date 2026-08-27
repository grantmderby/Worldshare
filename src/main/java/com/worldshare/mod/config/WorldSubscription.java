package com.worldshare.mod.config;

import com.worldshare.mod.cloud.RemoteFileSet;

/**
 * One entry in the user's subscribed-worlds list.
 *
 * <p>Persisted in {@code config/worldshare/subscriptions.json} via
 * {@link SubscriptionStore}. Each entry maps a shared world to a human-readable
 * display name and (optionally) the local saves folder that world lives in on
 * this machine.
 *
 * <p><b>Identity.</b> A world used to be identified by its Drive folder ID. That
 * can't work under the {@code drive.file} scope, because only the player who
 * created the world ever holds a grant on the folder - everyone else picked the
 * individual files and has no folder ID at all. The stable identifier is now the
 * <b>control file ID</b> ({@link #controlFileId()}), which every participant has
 * by definition, since picking it is what setup does.
 *
 * <p>The {@code localFolderName} field is set when:
 * <ul>
 *   <li>The host runs world setup inside an open world (host flow)</li>
 *   <li>The guest downloads the world for the first time via the Contributor
 *       Worlds screen (guest flow)</li>
 * </ul>
 * It is null for newly-subscribed worlds that have never been opened locally.
 */
public final class WorldSubscription {

    /**
     * Drive file IDs for this world. Null only for a legacy entry carried over
     * from before the {@code drive.file} migration - see {@link #isLegacy()}.
     */
    public RemoteFileSet remote;

    /**
     * Display name shown in the Contributor Worlds tab. Set when first
     * subscribed; editable by the user later.
     */
    public String displayName;

    /**
     * Name of the local saves folder for this world on this machine, or null
     * if the world has never been opened here.
     */
    public String localFolderName;

    /**
     * The Drive folder ID this subscription used before the migration.
     *
     * <p>Kept so an old {@code subscriptions.json} can be read without data loss
     * and the affected worlds flagged in the UI. Not usable for reaching anything.
     */
    public String driveFolderId;

    /** No-arg constructor required by Gson. */
    public WorldSubscription() {}

    public WorldSubscription(final RemoteFileSet remote,
                             final String displayName,
                             final String localFolderName) {
        this.remote = remote;
        this.displayName = displayName != null ? displayName : "Shared World";
        this.localFolderName = localFolderName;
    }

    /** Legacy constructor - builds a pre-migration entry that needs re-setup. */
    static WorldSubscription legacy(final String driveFolderId,
                                    final String displayName,
                                    final String localFolderName) {
        final WorldSubscription sub = new WorldSubscription();
        sub.driveFolderId = driveFolderId;
        sub.displayName = displayName != null ? displayName : "Shared World";
        sub.localFolderName = localFolderName;
        return sub;
    }

    /**
     * This world's stable identifier.
     *
     * @return the control file's Drive ID, or null for a legacy entry
     */
    public String controlFileId() {
        return remote == null ? null : remote.controlFileId;
    }

    /**
     * @return true if this entry predates the {@code drive.file} migration and
     *         can't be synced until the player re-runs setup
     */
    public boolean isLegacy() {
        return remote == null;
    }

    /** @return true if this world has a complete file set and can actually sync. */
    public boolean isUsable() {
        return remote != null && remote.isComplete();
    }

    /**
     * @return true if this world has been opened/downloaded on this machine
     *         and a local folder exists.
     */
    public boolean hasLocalFolder() {
        return localFolderName != null && !localFolderName.isBlank();
    }

    @Override
    public String toString() {
        return "WorldSubscription{" + displayName
                + ", control=" + controlFileId()
                + ", local=" + localFolderName + "}";
    }
}
