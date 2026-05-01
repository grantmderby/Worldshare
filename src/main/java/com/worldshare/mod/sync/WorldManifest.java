package com.worldshare.mod.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of every tracked file in a world: relative path → SHA-256 hash + size + mtime.
 *
 * <p>Two manifests are central to the sync engine:
 * <ul>
 *   <li><b>Drive manifest</b>: lives at {@code <driveFolder>/manifest.json}, represents
 *       the canonical state of the world on Drive. Updated atomically at the end of
 *       a successful push (via {@code manifest_pending.json} → rename pattern).</li>
 *   <li><b>Local manifest</b>: computed by walking the world folder.
 *       Compared to the Drive manifest to determine which files differ.</li>
 * </ul>
 *
 * <p>Use {@link #put} when constructing; use {@link #files()} for read-only access.
 */
public final class WorldManifest {

    /** Bumped if we change the JSON format incompatibly. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    /** ISO-8601 instant when this manifest was generated. */
    public String generatedAt;
    /** UUID of the player whose machine produced this manifest (informational only). */
    public String generatedByMachineId;
    /**
     * file path (forward-slash relative path inside the world) → file entry.
     * LinkedHashMap to preserve insertion order so diffs and JSON output are stable.
     */
    public Map<String, Entry> files = new LinkedHashMap<>();

    /** No-arg constructor required by Gson. */
    public WorldManifest() {}

    public void put(final String relPath, final Entry entry) {
        files.put(Objects.requireNonNull(relPath, "relPath"),
                Objects.requireNonNull(entry, "entry"));
    }

    public Entry get(final String relPath) {
        return files.get(relPath);
    }

    public Map<String, Entry> files() {
        return files;
    }

    public int size() {
        return files.size();
    }

    /** Sum of all entry sizes in bytes. Useful for "X MB to upload" UX. */
    public long totalBytes() {
        long total = 0L;
        for (final Entry e : files.values()) {
            total += Math.max(0L, e.size);
        }
        return total;
    }

    // ----- Per-file entry -----

    public static final class Entry {
        /** Lowercase hex SHA-256 of the file contents. */
        public String sha256;
        /** File size in bytes. */
        public long size;
        /** Last modified time as ISO-8601 instant (advisory, not used for diffing). */
        public String mtime;

        public Entry() {} // for Gson

        public Entry(final String sha256, final long size, final String mtime) {
            this.sha256 = sha256;
            this.size = size;
            this.mtime = mtime;
        }
    }

    // ----- JSON -----

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static WorldManifest fromJson(final String json) {
        final WorldManifest parsed = GSON.fromJson(json, WorldManifest.class);
        if (parsed.files == null) {
            parsed.files = new LinkedHashMap<>();
        }
        return parsed;
    }
}
