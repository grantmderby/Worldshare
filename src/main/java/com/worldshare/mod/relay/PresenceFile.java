package com.worldshare.mod.relay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.sync.BucketLayout;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * POJO for {@code presence.json} on Drive.
 *
 * <p>Written by the host after e4mc assigns a relay domain, and read by the guest
 * on title screen load.
 *
 * <p><b>Never deleted.</b> Stopping a session {@linkplain #clear clears} the file's
 * contents instead. Under the {@code drive.file} scope the other player holds a
 * grant on this exact Drive file ID; deleting it and creating a replacement would
 * mint a new ID their grant doesn't cover, and they'd stop seeing live sessions
 * with no visible error.
 *
 * <p>Considered stale if {@code started_at} is older than 2 minutes —
 * the host refreshes it every 60 seconds, so a 2-minute-old file means
 * the host has gone offline without cleaning up.
 */
public final class PresenceFile {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Legacy name, from when presence was found by searching a shared folder.
     * The live filename is {@link com.worldshare.mod.sync.BucketLayout#PRESENCE_FILENAME};
     * this constant remains only because old worlds on Drive still carry it.
     */
    public static final String FILENAME = "presence.json";

    private static final long STALE_MINUTES = 2L;

    /** Display name of the hosting player. */
    public String host;

    /** e4mc relay address, e.g. {@code grant-abc123.e4mc.link:12345}. */
    public String e4mc_link;

    /** ISO-8601 timestamp of when this file was last written. */
    public String started_at;

    /** Deserialize from JSON string. */
    public static PresenceFile fromJson(final String json) {
        return GSON.fromJson(json, PresenceFile.class);
    }

    /** Serialize to JSON string. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * @return true if the file is older than {@code STALE_MINUTES} or malformed.
     *         Stale = host went offline without cleaning up.
     */
    public boolean isStale() {
        if (started_at == null || started_at.isBlank()) return true;
        try {
            final Instant written = Instant.parse(started_at);
            return Instant.now().isAfter(written.plus(STALE_MINUTES, ChronoUnit.MINUTES));
        } catch (final Exception e) {
            return true;
        }
    }

    /** Factory — creates a fresh presence with current timestamp. */
    public static PresenceFile create(final String host, final String e4mcLink) {
        final PresenceFile p = new PresenceFile();
        p.host = host;
        p.e4mc_link = e4mcLink;
        p.started_at = Instant.now().toString();
        return p;
    }

    // ----- Drive I/O -----

    /**
     * Read a world's presence file.
     *
     * @return the parsed presence, or null if nobody is hosting - which covers an
     *         empty placeholder, a cleared file, and unparseable content alike.
     *         Callers should still check {@link #isStale()}: a host that crashed
     *         leaves a valid-looking record behind.
     */
    public static PresenceFile read(final RemoteFileSet remote) throws IOException {
        if (remote == null || remote.presenceFileId == null) return null;
        final String json = CloudModule.driveClient().readText(remote.presenceFileId);
        if (json == null || json.isBlank()) return null;
        try {
            return fromJson(json);
        } catch (final Exception e) {
            WorldShareMod.LOGGER.debug("PresenceFile: unparseable presence, treating as offline");
            return null;
        }
    }

    /** Overwrite a world's presence file in place. */
    public static void write(final RemoteFileSet remote, final PresenceFile presence)
            throws IOException {
        if (remote == null || remote.presenceFileId == null) {
            throw new IOException("This world has no presence file; re-run WorldShare setup.");
        }
        CloudModule.driveClient().writeText(
                remote.presenceFileId, BucketLayout.PRESENCE_FILENAME, null,
                presence.toJson(), DriveClient.MIME_TYPE_JSON);
    }

    /**
     * Mark a world as having nobody online, by writing an empty record.
     *
     * <p>This is the replacement for deleting the file - see the class note. An
     * all-null record reads back as {@link #isStale()}, which is exactly how a
     * guest already interprets "no live session".
     */
    public static void clear(final RemoteFileSet remote) throws IOException {
        if (remote == null || remote.presenceFileId == null) return;
        CloudModule.driveClient().writeText(
                remote.presenceFileId, BucketLayout.PRESENCE_FILENAME, null,
                new PresenceFile().toJson(), DriveClient.MIME_TYPE_JSON);
        WorldShareMod.LOGGER.debug("PresenceFile: cleared presence for {}", remote.controlFileId);
    }
}
