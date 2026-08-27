package com.worldshare.mod.sync;

import java.nio.file.Path;

/**
 * Decides which files inside a Minecraft world folder are subject to sync.
 *
 * <p><b>All playerdata, stats and advancements sync, regardless of UUID.</b>
 * Combined with stripping the Player tag from level.dat on pull, this gives
 * dedicated-server behaviour: each player's character lives in their own
 * playerdata file and follows them between machines.
 *
 * <p>This method used to take the local player's UUID to filter by. It doesn't
 * any more, and the parameter has been removed rather than left in place looking
 * meaningful - restoring per-UUID filtering would stop other players' characters
 * syncing at all, which is the opposite of what this design needs.
 *
 * <p><b>M8:</b> {@link #isMcaFile(String)} exposed for {@link WorldFileScanner}
 * dirty-region filtering. {@code worldshare-scan-cache.json} excluded from sync
 * (per-machine optimization file, not world state).
 */
public final class TrackedPaths {

    private TrackedPaths() {}

    public static boolean isTracked(final Path worldRoot, final Path file) {
        if (worldRoot == null || file == null) return false;
        if (!file.startsWith(worldRoot)) return false;

        final String rel = worldRoot.relativize(file).toString().replace('\\', '/');
        if (rel.isEmpty()) return false;

        // ---- EXCLUDES ----

        // Skip Distant Horizons LOD cache — client-side rendering data, not world state.
        // Lives inside data/ which is otherwise fully tracked, so must be explicitly excluded.
        // Exclude all three SQLite files (main db, write-ahead log, shared memory).
        if (rel.endsWith(".sqlite")) return false;
        if (rel.endsWith(".sqlite-wal")) return false;
        if (rel.endsWith(".sqlite-shm")) return false;

        // Skip Minecraft's per-save session lock — local-only state.
        if (rel.equals("session.lock")) return false;
        // Skip WorldShare's own per-world link file — per-machine config.
        if (rel.equals("worldshare-link.json")) return false;
        // Skip WorldShare's local scan cache — per-machine optimization, not world state.
        if (rel.equals("worldshare-scan-cache.json")) return false;
        // Skip auto-generated world icon (regenerated each save).
        if (rel.equals("icon.png")) return false;
        // Skip log and crash files.
        if (rel.endsWith(".log")) return false;
        if (rel.contains("crash-reports/")) return false;

        // ---- INCLUDES ----

        if (rel.equals("level.dat") || rel.equals("level.dat_old")) return true;

        if (matchesRegion(rel)) return true;
        if (matchesEntities(rel)) return true;
        if (matchesPoi(rel)) return true;

        // M7: all player data syncs (server-style behaviour).
        if (rel.startsWith("playerdata/")
                && (rel.endsWith(".dat") || rel.endsWith(".dat_old"))) return true;
        if (rel.startsWith("stats/") && rel.endsWith(".json")) return true;
        if (rel.startsWith("advancements/") && rel.endsWith(".json")) return true;

        if (rel.startsWith("data/")) return true;
        if (rel.startsWith("resources/")) return true;
        if (rel.startsWith("datapacks/")) return true;
        if (rel.startsWith("v_data/")) return true;

        return false;
    }

    /**
     * Returns true if the relative path is an .mca file (region, entities, or poi).
     * Used by WorldFileScanner to apply the dirty-region filter only to .mca files —
     * non-.mca tracked files are always included in the scan regardless of dirty state.
     */
    static boolean isMcaFile(final String rel) {
        return rel.endsWith(".mca");
    }

    private static boolean matchesRegion(final String rel) {
        return (rel.endsWith(".mca") && rel.contains("/region/"))
                || (rel.startsWith("region/") && rel.endsWith(".mca"));
    }

    private static boolean matchesEntities(final String rel) {
        return (rel.endsWith(".mca") && rel.contains("/entities/"))
                || (rel.startsWith("entities/") && rel.endsWith(".mca"));
    }

    private static boolean matchesPoi(final String rel) {
        return (rel.endsWith(".mca") && rel.contains("/poi/"))
                || (rel.startsWith("poi/") && rel.endsWith(".mca"));
    }
}