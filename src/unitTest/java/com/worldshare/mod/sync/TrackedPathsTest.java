package com.worldshare.mod.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What does and doesn't get synced.
 *
 * <p>This class had no tests, which is how {@code .mcc} went unnoticed: the rules
 * are a hardcoded allowlist, and anything the list forgets is not an error - it is
 * a file that quietly never leaves the machine. The cases below are therefore
 * mostly about what must be <em>included</em>.
 *
 * <p>{@link TrackedPaths#isTracked} does string work on paths and never touches the
 * filesystem, so these paths don't need to exist.
 */
class TrackedPathsTest {

    private static final Path WORLD = Path.of("C:", "saves", "MyWorld");

    private static boolean tracked(final String relPath) {
        return TrackedPaths.isTracked(WORLD, WORLD.resolve(relPath.replace('/', java.io.File.separatorChar)));
    }

    // ------------------------------------------------------------ chunk storage

    @Test
    @DisplayName("region, entities and poi files sync in every dimension")
    void chunkStorageIsTrackedEverywhere() {
        for (final String folder : new String[] {"region", "entities", "poi"}) {
            assertTrue(tracked(folder + "/r.0.0.mca"), "overworld " + folder);
            assertTrue(tracked("DIM-1/" + folder + "/r.0.0.mca"), "nether " + folder);
            assertTrue(tracked("DIM1/" + folder + "/r.0.0.mca"), "end " + folder);
            assertTrue(tracked("dimensions/twilightforest/twilight_forest/"
                    + folder + "/r.0.0.mca"), "modded " + folder);
        }
    }

    @Test
    @DisplayName("oversized chunk spill files sync too")
    void externalChunkFilesAreTracked() {
        // The bug this exists for: Minecraft moves a chunk too big for its region
        // file into c.<x>.<z>.mcc and leaves a pointer behind. Syncing the pointer
        // without its target hands the other player a region referencing a chunk
        // that isn't there, and nothing reports it until they walk into it.
        assertTrue(tracked("region/c.100.200.mcc"));
        assertTrue(tracked("DIM-1/region/c.-1.-1.mcc"));
        assertTrue(tracked("entities/c.5.5.mcc"));
        assertTrue(tracked("dimensions/mod/dim/region/c.0.0.mcc"));
    }

    @Test
    @DisplayName("the dirty filter may skip .mca but never .mcc")
    void spillFilesAreNotDirtyFilterable() {
        // The tracker records dirty regions. A chunk spilling out of a region - or
        // shrinking back into it - isn't a change it observes, so letting the filter
        // skip .mcc would drop a chunk. Always-scanned costs one hash on a file that
        // hardly ever exists.
        assertTrue(TrackedPaths.isMcaFile("region/r.0.0.mca"));
        assertFalse(TrackedPaths.isMcaFile("region/c.0.0.mcc"));
    }

    // --------------------------------------------------------------- world state

    @Test
    @DisplayName("level data and per-player state sync")
    void worldStateIsTracked() {
        assertTrue(tracked("level.dat"));
        assertTrue(tracked("level.dat_old"));
        assertTrue(tracked("playerdata/aed5efd4-551b-3965-bc28-ae21aa072a66.dat"));
        assertTrue(tracked("stats/aed5efd4-551b-3965-bc28-ae21aa072a66.json"));
        assertTrue(tracked("advancements/aed5efd4-551b-3965-bc28-ae21aa072a66.json"));
        assertTrue(tracked("data/raids.dat"));
        assertTrue(tracked("datapacks/My Pack/pack.mcmeta"));
    }

    // ----------------------------------------------------------------- excluded

    @Test
    @DisplayName("per-machine and regenerable files stay local")
    void localOnlyFilesAreExcluded() {
        // session.lock is Minecraft's own in-use marker, and the link file and scan
        // cache are ours. Syncing any of them would mean one machine's state landing
        // on another's disk.
        assertFalse(tracked("session.lock"));
        assertFalse(tracked("worldshare-link.json"));
        assertFalse(tracked("worldshare-scan-cache.json"));
        assertFalse(tracked("icon.png"));
    }

    @Test
    @DisplayName("Distant Horizons caches stay local despite living under data/")
    void distantHorizonsCachesAreExcluded() {
        // data/ is otherwise tracked wholesale, so these need naming explicitly.
        // They are rendering caches, they are large, and they are per-machine.
        assertFalse(tracked("data/DistantHorizons.sqlite"));
        assertFalse(tracked("data/DistantHorizons.sqlite-wal"));
        assertFalse(tracked("data/DistantHorizons.sqlite-shm"));
    }

    @Test
    @DisplayName("logs, crash reports and paths outside the world are excluded")
    void noiseIsExcluded() {
        assertFalse(tracked("something.log"));
        assertFalse(tracked("crash-reports/crash-2026-01-01.txt"));
        assertFalse(TrackedPaths.isTracked(WORLD, Path.of("C:", "saves", "OtherWorld", "level.dat")));
        assertFalse(TrackedPaths.isTracked(WORLD, WORLD));
    }
}
