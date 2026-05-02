package com.worldshare.mod.relay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * POJO for {@code presence.json} on Drive.
 *
 * <p>Written by the host after e4mc assigns a relay domain.
 * Read by the guest on title screen load.
 * Deleted by the host on server stop.
 *
 * <p>Considered stale if {@code started_at} is older than 2 minutes —
 * the host refreshes it every 60 seconds, so a 2-minute-old file means
 * the host has gone offline without cleaning up.
 */
public final class PresenceFile {

    private static final Gson GSON = new GsonBuilder().create();
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
}
