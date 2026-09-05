package com.worldshare.mod.sync;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Decides which files inside a Minecraft world folder are subject to sync.
 *
 * <p><b>This is a denylist, and it used to be an allowlist.</b> The old rule named
 * the paths it recognised - {@code region/}, {@code playerdata/}, {@code data/} and
 * a handful more - and silently dropped everything else. No error, no warning; a
 * file simply never left the machine.
 *
 * <p>That default is wrong because the failure is asymmetric. Over-syncing costs
 * bandwidth. Under-syncing costs somebody's work, and costs it quietly, which is
 * the worst way to lose anything. It had already happened twice: oversized
 * {@code .mcc} chunks went unsynced for months, and a stock NeoForge world contains
 * {@code serverconfig/} - per-world mod settings - which never synced either, so two
 * players could run one world under different gameplay rules with nothing to tell
 * them. Any mod keeping its own folder for contraptions, teams or machine networks
 * sat in exactly the same blind spot.
 *
 * <p>So the question is no longer "do we recognise this?" but "is there a specific
 * reason not to send it?" - and the list of such reasons is short and knowable, in
 * a way that the list of things mods might write never was.
 *
 * <p><b>All playerdata, stats and advancements sync, regardless of UUID.</b>
 * Combined with stripping the Player tag from level.dat on pull, this gives
 * dedicated-server behaviour: each player's character lives in their own
 * playerdata file and follows them between machines.
 */
public final class TrackedPaths {

    private TrackedPaths() {}

    /**
     * Exact world-relative paths that never sync.
     *
     * <p>{@code session.lock} is Minecraft's own in-use marker, so syncing it would
     * import another machine's idea of whether the world is open. The rest are ours
     * or regenerated: the link file and scan cache are per-installation, the doctor
     * report is a diagnostic, and {@code icon.png} is rewritten on every save and
     * means nothing to anybody else.
     */
    private static final List<String> EXCLUDED_EXACT = List.of(
            "session.lock",
            "worldshare-link.json",
            "worldshare-scan-cache.json",
            "worldshare-doctor.txt",
            "icon.png");

    /**
     * Suffixes that never sync.
     *
     * <p>The SQLite trio is Distant Horizons' LOD cache and anything else
     * SQLite-backed - large, regenerable from the world itself, and specific to one
     * machine's render distance. It lives inside {@code data/}, which is otherwise
     * fully synced, so it has to be named rather than skipped by folder.
     *
     * <p>The rest are transient or operating-system litter that no mod means to
     * keep: half-written files, and the index files Windows and macOS leave behind.
     */
    private static final List<String> EXCLUDED_SUFFIXES = List.of(
            ".sqlite", ".sqlite-wal", ".sqlite-shm",
            ".log", ".tmp", ".part",
            ".ds_store", "thumbs.db");

    /** Folders whose entire contents never sync. */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "crash-reports/");

    public static boolean isTracked(final Path worldRoot, final Path file) {
        if (worldRoot == null || file == null) return false;
        if (!file.startsWith(worldRoot)) return false;

        final String rel = worldRoot.relativize(file).toString().replace('\\', '/');
        if (rel.isEmpty()) return false;

        return isTracked(rel);
    }

    /**
     * The rule itself, on an already-normalised world-relative path.
     *
     * <p>Separate from the {@link Path} form so it can be tested without a
     * filesystem, and so the scanner can ask about a path it has already
     * relativised.
     */
    static boolean isTracked(final String rel) {
        final String lower = rel.toLowerCase(Locale.ROOT);

        for (final String exact : EXCLUDED_EXACT) {
            if (lower.equals(exact)) return false;
        }
        for (final String suffix : EXCLUDED_SUFFIXES) {
            if (lower.endsWith(suffix)) return false;
        }
        for (final String prefix : EXCLUDED_PREFIXES) {
            if (lower.startsWith(prefix) || lower.contains("/" + prefix)) return false;
        }

        // Whatever the player or their mods added on top. Deliberately last, so a
        // config exclude can remove something this class would otherwise send, but
        // never add something it deliberately refuses.
        return !matchesConfiguredExclude(lower);
    }

    /**
     * Extra excludes from the config, for the mod we didn't anticipate.
     *
     * <p>A mod update can turn a small folder into a huge one, and waiting for a
     * WorldShare release is a poor answer to a world that has become unsyncable.
     * Entries ending in {@code /} match a folder; entries starting with {@code *}
     * match a suffix; anything else matches an exact path.
     *
     * <p>Read defensively because this runs inside a file walk on paths the player
     * controls: a malformed entry should cost that one pattern, not the scan.
     */
    private static boolean matchesConfiguredExclude(final String lower) {
        final List<? extends String> patterns;
        try {
            patterns = com.worldshare.mod.config.WorldShareConfig.get()
                    .extraSyncExcludes.get();
        } catch (final Throwable t) {
            return false;   // config not loaded (tests, early startup)
        }
        if (patterns == null || patterns.isEmpty()) return false;

        for (final String raw : patterns) {
            if (raw == null || raw.isBlank()) continue;
            final String p = raw.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
            if (p.startsWith("*")) {
                if (lower.endsWith(p.substring(1))) return true;
            } else if (p.endsWith("/")) {
                if (lower.startsWith(p) || lower.contains("/" + p)) return true;
            } else if (lower.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the dirty-region filter may skip this file.
     *
     * <p>Only {@code .mca}, deliberately. An oversized-chunk {@code .mcc} is not
     * filterable: the tracker records dirty regions, and a chunk spilling out of
     * one - or shrinking back into it - is not a change the tracker sees. Leaving
     * {@code .mcc} always-scanned costs a hash on a file that barely ever exists,
     * and skipping one would drop a chunk from the world.
     */
    static boolean isMcaFile(final String rel) {
        return rel.endsWith(".mca");
    }
}
