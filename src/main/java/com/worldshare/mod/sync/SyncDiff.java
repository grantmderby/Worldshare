package com.worldshare.mod.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of comparing a local manifest to a Drive manifest.
 *
 * <p>Used by {@link SyncEngine} to plan a push or pull. The same diff
 * structure works for both directions; the caller decides interpretation:
 * <ul>
 *   <li>For a <b>pull</b>: {@link #onlyOnDrive} files need to be downloaded;
 *       {@link #different} files need to be downloaded and overwritten;
 *       {@link #onlyLocal} files exist only locally — pull leaves them alone
 *       (they're either new local work, or stale from a previous session).</li>
 *   <li>For a <b>push</b>: {@link #onlyLocal} files need to be uploaded;
 *       {@link #different} files need to be re-uploaded; {@link #onlyOnDrive}
 *       files are someone else's work or files we deleted (push leaves them).</li>
 * </ul>
 */
public final class SyncDiff {

    /** Relative paths that exist only in the local manifest. */
    public final List<String> onlyLocal = new ArrayList<>();

    /** Relative paths that exist only in the Drive manifest. */
    public final List<String> onlyOnDrive = new ArrayList<>();

    /** Relative paths whose hash differs between local and Drive. */
    public final List<String> different = new ArrayList<>();

    /** Relative paths whose hash matches on both sides (no work needed). */
    public final List<String> identical = new ArrayList<>();

    /** @return true if no syncing work is needed in either direction. */
    public boolean isEmpty() {
        return onlyLocal.isEmpty() && onlyOnDrive.isEmpty() && different.isEmpty();
    }

    public int totalDiverging() {
        return onlyLocal.size() + onlyOnDrive.size() + different.size();
    }

    /**
     * Compute a diff between two manifests. Pure function, safe to call anywhere.
     *
     * @param local snapshot of the local world folder (or null = empty / no local state)
     * @param drive snapshot from Drive (or null = no Drive manifest yet)
     */
    public static SyncDiff compute(final WorldManifest local, final WorldManifest drive) {
        final SyncDiff diff = new SyncDiff();
        final WorldManifest l = (local == null) ? new WorldManifest() : local;
        final WorldManifest d = (drive == null) ? new WorldManifest() : drive;

        // Walk local entries
        for (final String path : l.files().keySet()) {
            final WorldManifest.Entry localEntry = l.get(path);
            final WorldManifest.Entry driveEntry = d.get(path);
            if (driveEntry == null) {
                diff.onlyLocal.add(path);
            } else if (!safeEquals(localEntry.sha256, driveEntry.sha256)) {
                diff.different.add(path);
            } else {
                diff.identical.add(path);
            }
        }

        // Walk drive entries to find drive-only ones
        for (final String path : d.files().keySet()) {
            if (!l.files().containsKey(path)) {
                diff.onlyOnDrive.add(path);
            }
        }

        return diff;
    }

    private static boolean safeEquals(final String a, final String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
