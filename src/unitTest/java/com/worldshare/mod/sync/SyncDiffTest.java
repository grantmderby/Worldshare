package com.worldshare.mod.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every sync decision starts here: what the diff calls "different" becomes what
 * gets uploaded, which buckets are dirty, and — since the audit — whether a push
 * is refused for carrying someone else's newer work. Getting a file into the
 * wrong category doesn't fail loudly, it moves the wrong bytes.
 */
class SyncDiffTest {

    private static WorldManifest manifestOf(final String... pathThenHash) {
        final WorldManifest m = new WorldManifest();
        for (int i = 0; i < pathThenHash.length; i += 2) {
            m.put(pathThenHash[i], new WorldManifest.Entry(pathThenHash[i + 1], 100L, null));
        }
        return m;
    }

    @Test
    @DisplayName("files are sorted into identical, different, local-only and drive-only")
    void partitionsCorrectly() {
        final WorldManifest local = manifestOf(
                "same.dat", "aaa",
                "changed.dat", "local-hash",
                "mine-only.dat", "ccc");
        final WorldManifest drive = manifestOf(
                "same.dat", "aaa",
                "changed.dat", "drive-hash",
                "theirs-only.dat", "ddd");

        final SyncDiff diff = SyncDiff.compute(local, drive);

        assertEquals(java.util.List.of("same.dat"), diff.identical);
        assertEquals(java.util.List.of("changed.dat"), diff.different);
        assertEquals(java.util.List.of("mine-only.dat"), diff.onlyLocal);
        assertEquals(java.util.List.of("theirs-only.dat"), diff.onlyOnDrive);
    }

    @Test
    @DisplayName("two identical manifests produce no work")
    void identicalManifestsAreEmpty() {
        final WorldManifest m = manifestOf("a.dat", "h1", "b.dat", "h2");
        final SyncDiff diff = SyncDiff.compute(m, manifestOf("a.dat", "h1", "b.dat", "h2"));

        assertTrue(diff.isEmpty());
        assertEquals(0, diff.totalDiverging());
        assertEquals(2, diff.identical.size());
    }

    @Test
    @DisplayName("a null drive manifest makes everything local-only, not different")
    void nullDriveManifestIsFirstPush() {
        // This is the first-push shape. Treating these as "different" instead of
        // "only local" would still upload them, but the distinction drives the
        // stale-push check, which only looks at `different`.
        final SyncDiff diff = SyncDiff.compute(manifestOf("a.dat", "h1", "b.dat", "h2"), null);

        assertEquals(2, diff.onlyLocal.size());
        assertTrue(diff.different.isEmpty());
        assertTrue(diff.identical.isEmpty());
        assertFalse(diff.isEmpty());
    }

    @Test
    @DisplayName("a null local manifest makes everything drive-only")
    void nullLocalManifestIsFirstPull() {
        final SyncDiff diff = SyncDiff.compute(null, manifestOf("a.dat", "h1"));

        assertEquals(1, diff.onlyOnDrive.size());
        assertTrue(diff.onlyLocal.isEmpty());
    }

    @Test
    @DisplayName("both null is empty rather than an exception")
    void bothNullIsEmpty() {
        final SyncDiff diff = SyncDiff.compute(null, null);
        assertTrue(diff.isEmpty());
    }

    @Test
    @DisplayName("a null hash on one side counts as different, not identical")
    void nullHashIsNotAMatch() {
        // A manifest entry with no hash means we never computed one. Treating that
        // as a match would skip syncing a file we know nothing about.
        final WorldManifest local = new WorldManifest();
        local.put("x.dat", new WorldManifest.Entry(null, 10L, null));

        final SyncDiff diff = SyncDiff.compute(local, manifestOf("x.dat", "real-hash"));
        assertEquals(java.util.List.of("x.dat"), diff.different);
    }

    @Test
    @DisplayName("totalDiverging counts every category needing work")
    void totalDivergingCountsWork() {
        final SyncDiff diff = SyncDiff.compute(
                manifestOf("same.dat", "a", "changed.dat", "x", "mine.dat", "m"),
                manifestOf("same.dat", "a", "changed.dat", "y", "theirs.dat", "t"));

        // changed + mine + theirs = 3; the identical one isn't work.
        assertEquals(3, diff.totalDiverging());
    }
}
