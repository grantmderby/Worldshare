package com.worldshare.mod.config;

import java.util.Objects;

/**
 * One entry in the user's subscribed-worlds list.
 *
 * <p>Persisted in {@code config/worldshare/subscriptions.json} via
 * {@link SubscriptionStore}. Each entry maps a Drive folder ID to a
 * human-readable display name and (optionally) a local saves folder name
 * for the world that corresponds to this Drive folder on this machine.
 *
 * <p>The {@code localFolderName} field is set when:
 * <ul>
 *   <li>The host runs {@code /worldshare setfolder} inside a world (host flow)</li>
 *   <li>The guest downloads the world for the first time via the Contributor
 *       Worlds screen (guest flow)</li>
 * </ul>
 * It is null for newly-subscribed worlds that have never been opened locally.
 */
public final class WorldSubscription {

    /** Google Drive folder ID — the stable identifier for this world. */
    public String driveFolderId;

    /**
     * Display name shown in the Contributor Worlds tab. Set to the Drive
     * folder name when first subscribed; editable by the user later.
     */
    public String displayName;

    /**
     * Name of the local saves folder for this world on this machine, or null
     * if the world has never been opened here.
     *
     * <p>For the host: set by {@code /worldshare setfolder} to the currently
     * open world's folder name.
     * For the guest: set when the world is first downloaded from Drive.
     */
    public String localFolderName;

    /** No-arg constructor required by Gson. */
    public WorldSubscription() {}

    public WorldSubscription(final String driveFolderId,
                             final String displayName,
                             final String localFolderName) {
        this.driveFolderId = Objects.requireNonNull(driveFolderId);
        this.displayName = displayName != null ? displayName : driveFolderId;
        this.localFolderName = localFolderName;
    }

    /**
     * @return true if this world has been opened/downloaded on this machine
     *         and a local folder exists.
     */
    public boolean hasLocalFolder() {
        return localFolderName != null && !localFolderName.isBlank();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldSubscription)) return false;
        return Objects.equals(driveFolderId, ((WorldSubscription) o).driveFolderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(driveFolderId);
    }

    @Override
    public String toString() {
        return "WorldSubscription{folderId=" + driveFolderId
                + ", displayName=" + displayName
                + ", localFolder=" + localFolderName + "}";
    }
}
