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

    /**
     * Nobody holds the lock.
     *
     * <p>Under the old full-Drive design, releasing meant deleting
     * {@code session.lock} outright and letting the next acquirer create a fresh
     * one. That is no longer possible: the lock now lives inside the control file,
     * and deleting a file under {@code drive.file} means the replacement gets a new
     * Drive ID that nobody else has been granted. So release is a state change, not
     * a deletion, and "unlocked" had to become a value the schema can express.
     */
    public static final String STATUS_UNLOCKED = "unlocked";

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
     */
    public static SessionLock newAcquired(final String holderName,
                                          final String machineId,
                                          final Instant now,
                                          final java.time.Duration expiresAfter) {
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
        return lock;
    }

    /**
     * Factory: the "nobody is holding this world" state.
     *
     * <p>Holder fields are left null deliberately rather than blanked to empty
     * strings, so that {@link #isOwnedBy} can never accidentally match a real
     * machine ID against a released lock.
     */
    public static SessionLock unlocked(final Instant now) {
        final SessionLock lock = new SessionLock();
        lock.holderName = null;
        lock.machineId = null;
        lock.status = STATUS_UNLOCKED;
        lock.relayAddress = null;
        lock.lockedAt = null;
        // An unlocked session is expired by definition: EPOCH is safely in the past,
        // so every "is this stale?" check treats it as free without special-casing.
        lock.expiresAt = Instant.EPOCH.toString();
        lock.lastHeartbeatAt = now.toString();
        lock.playersOnline = new ArrayList<>();
        return lock;
    }

    /** @return true if this lock is explicitly released (nobody holds it). */
    public boolean isUnlocked() {
        return STATUS_UNLOCKED.equals(status) || status == null;
    }

    /**
     * @return true if the lock is free to take: either explicitly released, or
     *         held but stale past its expiry
     */
    public boolean isAvailable(final Instant now) {
        return isUnlocked() || isExpired(now);
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
