package com.worldshare.mod.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.worldshare.mod.sync.BucketLayout;
import com.worldshare.mod.sync.WorldManifest;

import java.time.Instant;

/**
 * The single always-present remote document describing a shared world: what files
 * it contains, and who currently holds the session lock.
 *
 * <p>This replaces the old {@code manifest.json} + {@code session.lock} pair, and
 * the merge is not cosmetic. Under {@code drive.file} the mod can only touch files
 * a user personally picked, so every extra remote file is one more thing each
 * player must select during setup and one more thing that can go missing. Folding
 * both documents into one halves that surface. It also makes a push atomic in the
 * way that actually matters: the manifest and the lock state that produced it are
 * written in the same {@code files.update()} call, so another player can never
 * observe a new manifest alongside a stale lock.
 *
 * <p><b>This file is updated in place, forever, and never deleted.</b> Deleting and
 * recreating it would mint a new Drive file ID, which every other player's grant
 * would not cover - they would silently lose access until they re-picked it. That
 * is why releasing the lock writes {@link SessionLock#STATUS_UNLOCKED} instead of
 * removing anything.
 */
public final class ControlFile {

    /** Bumped if we change the JSON format incompatibly. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** ISO-8601 instant this control file was last written. Diagnostic only. */
    public String updatedAt;

    /**
     * Number of bucket archives this world is laid out across.
     *
     * <p>Written by whoever created the world and treated as authoritative by
     * everyone else. A client whose local {@link BucketLayout} disagrees must
     * refuse to sync rather than guess: the same path hashes to a different
     * bucket under a different count, so proceeding would scatter the world
     * across archives that nobody reads back.
     */
    public int bucketCount = BucketLayout.DEFAULT_BUCKET_COUNT;

    /**
     * Which revision of the path-to-bucket mapping this world was written with.
     *
     * <p>Separate from {@link #schemaVersion}, which describes the JSON document.
     * They answer different questions and conflating them would make every future
     * format tweak force a full world re-upload, while a mapping change that left
     * the format alone would slip through unnoticed.
     *
     * <p>That second failure is the dangerous one. A push only rewrites buckets
     * whose <em>contents</em> changed, so after a mapping change an untouched file
     * stays in the archive it was already in while the new mapping says it lives
     * somewhere else. Nothing looks wrong until someone pulls and the file simply
     * isn't there. A version mismatch therefore has to refuse the sync outright and
     * send the player to a repair, which republishes every bucket and is exactly
     * what a migration needs.
     *
     * <p>Absent from control files written before this existed, where Gson leaves
     * it 0 - which is the correct answer for them.
     */
    public int layoutVersion = BucketLayout.LAYOUT_VERSION;

    /**
     * Canonical per-file state of the world: relative path to hash/size/mtime.
     * Compared against a local scan to decide what to move.
     */
    public WorldManifest manifest;

    /**
     * Current session-lock state. Never null in a well-formed control file - an
     * unheld lock is represented by a {@link SessionLock} with status
     * {@link SessionLock#STATUS_UNLOCKED}, not by absence.
     */
    public SessionLock lock;

    /**
     * The world's modpack manifest, or null if the host never published one.
     *
     * <p>Held as a raw {@link JsonElement} rather than a typed
     * {@code ModpackManifest} on purpose: this package sits underneath the mod
     * manager, and giving the cloud layer a compile-time dependency on it just to
     * carry the field through would invert that. The mod manager owns the schema
     * and does the parsing; the control file only ferries it.
     *
     * <p>It lives here rather than in its own picked file because it changes
     * rarely - only when the host's mod list does - so the cost of rewriting the
     * manifest alongside it is negligible. Presence, which changes every minute,
     * gets its own file for exactly the opposite reason.
     */
    public JsonElement modpack;

    /** No-arg constructor required by Gson. */
    public ControlFile() {}

    /**
     * Build the control file for a brand new world: an empty manifest and an
     * explicitly unlocked session.
     */
    public static ControlFile initial(final int bucketCount, final Instant now) {
        final ControlFile control = new ControlFile();
        control.schemaVersion = CURRENT_SCHEMA_VERSION;
        control.bucketCount = bucketCount;
        control.updatedAt = now.toString();
        control.manifest = new WorldManifest();
        control.lock = SessionLock.unlocked(now);
        return control;
    }

    /**
     * @return the manifest, never null - a control file that somehow arrived
     *         without one is treated as describing an empty world rather than
     *         throwing deep inside the sync engine
     */
    public WorldManifest manifestOrEmpty() {
        if (manifest == null) {
            manifest = new WorldManifest();
        }
        return manifest;
    }

    /**
     * @return the lock, never null - a missing lock is read as "nobody holds it",
     *         which is the safe interpretation: worst case somebody acquires a
     *         lock that was already free
     */
    public SessionLock lockOrUnlocked() {
        if (lock == null) {
            lock = SessionLock.unlocked(Instant.now());
        }
        return lock;
    }

    /** Stamp {@link #updatedAt} to now. Called on every write path. */
    public ControlFile touch(final Instant now) {
        this.updatedAt = now.toString();
        return this;
    }

    /**
     * Whether this control file's layout agrees with what the local client expects.
     *
     * @see #bucketCount
     */
    public boolean matchesLayout(final BucketLayout layout) {
        return layout != null && layout.bucketCount() == bucketCount;
    }

    /** A {@link BucketLayout} for the count this control file declares. */
    public BucketLayout layout() {
        return new BucketLayout(bucketCount);
    }

    // ----- JSON -----

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Parse a control file.
     *
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static ControlFile fromJson(final String json) {
        final ControlFile parsed = GSON.fromJson(json, ControlFile.class);
        if (parsed == null) {
            throw new JsonSyntaxException("control file parsed to null");
        }
        parsed.manifestOrEmpty();
        parsed.lockOrUnlocked();
        if (parsed.bucketCount <= 0) {
            // A control file predating the field, or hand-edited. Assume the
            // default rather than dividing by zero later.
            parsed.bucketCount = BucketLayout.DEFAULT_BUCKET_COUNT;
        }
        return parsed;
    }

    @Override
    public String toString() {
        return "ControlFile{buckets=" + bucketCount
                + ", files=" + manifestOrEmpty().size()
                + ", lock=" + lockOrUnlocked().status + "}";
    }
}
