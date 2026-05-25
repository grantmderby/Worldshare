package com.worldshare.mod.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>M8: {@link #loadFromDisk} / {@link #saveToDisk} support the local scan cache
 * ({@code worldshare-scan-cache.json}), which lets WorldFileScanner skip SHA-256 for
 * files whose mtime + size haven't changed since the last push.
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
        /** Last modified time as ISO-8601 instant. Used by scan cache for mtime pre-check. */
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

    // ----- Disk helpers (for local scan cache) -----

    /**
     * Load a manifest from a local file. Returns null if the file doesn't exist or
     * is malformed — callers treat null as "no cache available".
     */
    public static WorldManifest loadFromDisk(final Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            return fromJson(Files.readString(path));
        } catch (final Exception e) {
            // Malformed or unreadable — not fatal, just means no cache this scan.
            return null;
        }
    }

    /**
     * Save a manifest to a local file. Failures are non-fatal — next scan will just
     * re-hash everything without a cache hit.
     */
    public static void saveToDisk(final WorldManifest manifest, final Path path) {
        if (manifest == null || path == null) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, manifest.toJson());
        } catch (final IOException ignored) {
            // Not fatal.
        }
    }
}