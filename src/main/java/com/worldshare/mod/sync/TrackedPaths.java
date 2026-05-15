package com.worldshare.mod.sync;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Decides which files inside a Minecraft world folder are subject to sync.
 *
 * <p><b>M7:</b> All playerdata, stats, and advancements files sync regardless
 * of UUID. Combined with stripping level.dat Player on pull, this gives
 * dedicated-server-style behaviour where each player's character lives in
 * their own playerdata file and follows them between machines.
 */
public final class TrackedPaths {

    private TrackedPaths() {}

    public static boolean isTracked(final Path worldRoot,
                                    final Path file,
                                    final UUID ownPlayerUuid) {
        if (worldRoot == null || file == null) return false;
        if (!file.startsWith(worldRoot)) return false;

        final String rel = worldRoot.relativize(file).toString().replace('\\', '/');
        if (rel.isEmpty()) return false;

        // Skip Minecraft's per-save session lock — local-only state.
        if (rel.equals("session.lock")) return false;
        // Skip WorldShare's own per-world link file — per-machine config.
        if (rel.equals("worldshare-link.json")) return false;
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