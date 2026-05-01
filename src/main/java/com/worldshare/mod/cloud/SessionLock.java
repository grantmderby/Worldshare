package com.worldshare.mod.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSON representation of a {@code session.lock} file on Drive.
 *
 * <p>Intentionally a plain data class. All state mutations happen by
 * constructing new instances rather than mutating existing ones; this makes
 * it easy to reason about what's being written to Drive at any moment.
 *
 * <p><b>Forward compatibility:</b> {@link #schemaVersion} lets future mod
 * versions detect older formats and migrate or reject them. Unknown fields
 * in newer lock files are silently dropped by Gson, which is the correct
 * behavior - an older client can still read a newer lock file.
 */
public final class SessionLock {

    /** Bump whenever we make a backwards-incompatible change to the schema. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** JSON status values. Kept as strings for forward-compat with new states. */
    public static final String STATUS_HOSTING = "hosting";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_SYNCING = "syncing";

    public int schemaVersion;
    public String holderName;
    public String machineId;
    public String status;
    /** e4mc relay address, or null if holder is offline-only. Populated in M4. */
    public String relayAddress;
    /** ISO-8601 UTC instant when the lock was first acquired. */
    public String lockedAt;
    /** ISO-8601 UTC instant when this lock should be considered stale. */
    public String expiresAt;
    /** ISO-8601 UTC instant of the most recent heartbeat. */
    public String lastHeartbeatAt;
    /** Display names of players currently in the session. Host is always first. */
    public List<String> playersOnline;
    /** Soft cap on players; respected by the relay module in M4. */
    public int playerCap;

    /** No-arg constructor required by Gson. */
    public SessionLock() {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.playersOnline = new ArrayList<>();
    }

    /**
     * Factory: build a freshly-acquired lock held by the given machine.
     *
     * @param holderName     display name shown to other contributors
     * @param machineId      stable per-machine identifier (see {@code MachineId})
     * @param now            current UTC instant
     * @param expiresAfter   how long from {@code now} until the lock is stale
     * @param playerCap      soft cap on simultaneous players
     */
    public static SessionLock newAcquired(final String holderName,
                                          final String machineId,
                                          final Instant now,
                                          final java.time.Duration expiresAfter,
                                          final int playerCap) {
        final SessionLock lock = new SessionLock();
        lock.holderName = holderName;
        lock.machineId = machineId;
        lock.status = STATUS_HOSTING;
        lock.relayAddress = null;
        lock.lockedAt = now.toString();
        lock.expiresAt = now.plus(expiresAfter).toString();
        lock.lastHeartbeatAt = now.toString();
        lock.playersOnline = new ArrayList<>();
        lock.playersOnline.add(holderName);
        lock.playerCap = playerCap;
        return lock;
    }

    /**
     * @return {@link #expiresAt} parsed to an Instant, or {@code Instant.MIN}
     *         if the field is null/unparseable. MIN ensures "invalid = stale"
     *         rather than "invalid = never expires".
     */
    public Instant expiresAtInstant() {
        return parseInstant(expiresAt, Instant.MIN);
    }

    /**
     * @return {@link #lockedAt} parsed to an Instant, or {@code Instant.EPOCH}
     *         if the field is null/unparseable.
     */
    public Instant lockedAtInstant() {
        return parseInstant(lockedAt, Instant.EPOCH);
    }

    /**
     * @return {@link #lastHeartbeatAt} parsed to an Instant, or {@link #lockedAtInstant()}
     *         as a sensible fallback if heartbeat was never set.
     */
    public Instant lastHeartbeatInstant() {
        return parseInstant(lastHeartbeatAt, lockedAtInstant());
    }

    /** @return true if {@code now} is at or past {@link #expiresAt}. */
    public boolean isExpired(final Instant now) {
        return !now.isBefore(expiresAtInstant());
    }

    /** @return true if this lock was created by the given machine ID. */
    public boolean isOwnedBy(final String ourMachineId) {
        return machineId != null && machineId.equals(ourMachineId);
    }

    /** @return a defensive copy of players online. */
    public List<String> playersOnline() {
        return playersOnline == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(playersOnline));
    }

    // ----- JSON -----

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Parse a lock JSON string.
     *
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static SessionLock fromJson(final String json) {
        final SessionLock parsed = GSON.fromJson(json, SessionLock.class);
        if (parsed.playersOnline == null) {
            parsed.playersOnline = new ArrayList<>();
        }
        return parsed;
    }

    // ----- helpers -----

    private static Instant parseInstant(final String text, final Instant fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return Instant.parse(text);
        } catch (final java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }
}
