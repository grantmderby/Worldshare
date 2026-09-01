package com.worldshare.mod.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bucket assignment is the one thing in this design that cannot be allowed to
 * drift. A path must map to the same bucket forever, on every machine, across
 * every version - break that and a file lives in two archives at once and the
 * diff logic corrupts the world without any error.
 *
 * <p>Two bugs got through code review here and were only caught by measuring
 * aggregate behaviour rather than individual assignments, so both now have tests:
 * small always-changing files scattering across buckets, and neighbouring regions
 * deliberately not sharing one.
 */
class BucketLayoutTest {

    // ---------------------------------------------------------------- invariants

    @Test
    @DisplayName("the same path always maps to the same bucket")
    void assignmentIsDeterministic() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        final int first = layout.indexFor("region/r.7.9.mca");
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, layout.indexFor("region/r.7.9.mca"),
                    "assignment must never vary between calls");
        }
    }

    @Test
    @DisplayName("every path lands inside the bucket range, including negative coordinates")
    void assignmentStaysInRange() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        for (int x = -200; x <= 200; x += 3) {
            for (int z = -200; z <= 200; z += 3) {
                final int b = layout.indexFor("region/r." + x + "." + z + ".mca");
                assertTrue(b >= 0 && b < layout.bucketCount(),
                        "r." + x + "." + z + " landed outside [0, " + layout.bucketCount() + ")");
            }
        }
    }

    @Test
    @DisplayName("path separators and case do not change the bucket")
    void assignmentIsNormalised() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        final int expected = layout.indexFor("region/r.3.-1.mca");
        assertEquals(expected, layout.indexFor("region" + ((char) 92) + "r.3.-1.mca"),
                "a backslash separator must normalise to a forward slash");
        assertEquals(expected, layout.indexFor("REGION/R.3.-1.MCA"),
                "case must not affect assignment");
    }

    // ---------------------------------------------------------------- the hot bucket

    @Test
    @DisplayName("files Minecraft rewrites every session all share the hot bucket")
    void housekeepingFilesShareOneBucket() {
        // Regression test. These were hashed by path, which scattered them across
        // six of eight buckets before the player had moved a single block - each one
        // dragging its bucket's worth of region files across the network with it.
        final BucketLayout layout = BucketLayout.defaultLayout();
        final String[] rewrittenEverySession = {
                "level.dat",
                "level.dat_old",
                "playerdata/1a2b-uuid.dat",
                "playerdata/1a2b-uuid.json",
                "stats/1a2b-uuid.json",
                "advancements/1a2b-uuid.json",
                "data/raids.dat",
                "data/villages.dat",
                "data/scoreboard.dat",
                "DIM-1/data/raids.dat",
        };

        final Set<Integer> touched = new TreeSet<>();
        for (final String path : rewrittenEverySession) {
            touched.add(layout.indexFor(path));
        }

        assertEquals(Set.of(BucketLayout.HOT_BUCKET), touched,
                "housekeeping files must all land in the hot bucket, not scatter");
    }

    @Test
    @DisplayName("no region file ever lands in the hot bucket")
    void regionFilesAvoidTheHotBucket() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                assertNotEquals(BucketLayout.HOT_BUCKET,
                        layout.indexFor("region/r." + x + "." + z + ".mca"),
                        "region r." + x + "." + z + " leaked into the hot bucket");
            }
        }
    }

    // ------------------------------------------------------------ opaque paths

    @Test
    @DisplayName("everything that synced before still maps where it did")
    void existingAssignmentsAreUnchanged() {
        final BucketLayout layout = BucketLayout.defaultLayout();

        // This is the assertion behind shipping the denylist without a layout
        // version bump. Adding files is additive and safe; moving an existing one
        // is the silent-loss case, because a push only rewrites buckets whose
        // contents changed - an untouched file would stay in its old archive while
        // the new mapping looked somewhere else.
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("level.dat"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("level.dat_old"));
        assertEquals(BucketLayout.HOT_BUCKET,
                layout.indexFor("playerdata/aed5efd4-551b-3965-bc28-ae21aa072a66.dat"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("stats/abc.json"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("advancements/abc.json"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("data/raids.dat"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("resources/pack.png"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("datapacks/p/pack.mcmeta"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("v_data/anything.dat"));
    }

    @Test
    @DisplayName("a mod's folder gets one bucket of its own, away from the hot one")
    void modFoldersGroupOutsideTheHotBucket() {
        final BucketLayout layout = BucketLayout.defaultLayout();

        final int bucket = layout.indexFor("create_aeronautics/contraptions.dat");
        assertEquals(bucket, layout.indexFor("create_aeronautics/other.dat"),
                "one mod's data belongs in one archive");
        assertEquals(bucket, layout.indexFor("create_aeronautics/nested/deep/file.bin"),
                "however deep it nests");

        // Not the hot bucket, which is repacked on essentially every push because
        // level.dat and stats always change. A mod folder there would be
        // re-uploaded every session whether or not it changed.
        org.junit.jupiter.api.Assertions.assertNotEquals(
                BucketLayout.HOT_BUCKET, bucket);
    }

    @Test
    @DisplayName("different mod folders generally land in different buckets")
    void modFoldersSpread() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        final java.util.Set<Integer> buckets = new java.util.HashSet<>();
        for (final String mod : new String[] {
                "create_aeronautics", "ftbteams", "computercraft", "ae2",
                "mekanism", "botania", "thermal", "immersiveengineering"}) {
            buckets.add(layout.indexFor(mod + "/data.dat"));
        }
        assertTrue(buckets.size() >= 4,
                "eight mod folders should not all collide; got " + buckets.size());
    }

    @Test
    @DisplayName("a mod's own data/ folder is not mistaken for Minecraft's")
    void modDataFoldersAreNotHot() {
        final BucketLayout layout = BucketLayout.defaultLayout();

        // Minecraft keeps a data/ folder per dimension, so DIM-1/data/raids.dat is
        // housekeeping and belongs in the hot bucket. A mod folder that happens to
        // contain a data/ subfolder is not, and putting it in the hot bucket would
        // re-upload it on every save - which is the cost this rule exists to avoid.
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("dim-1/data/raids.dat"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("dim1/data/raids.dat"));
        assertEquals(BucketLayout.HOT_BUCKET,
                layout.indexFor("dimensions/twilightforest/twilight_forest/data/x.dat"));

        org.junit.jupiter.api.Assertions.assertNotEquals(
                BucketLayout.HOT_BUCKET, layout.indexFor("create_aeronautics/data/big.bin"),
                "a mod's data folder gets its own bucket, not the hot one");
    }

    @Test
    @DisplayName("a loose file at the world root goes to the hot bucket")
    void looseRootFilesAreHot() {
        // No folder to group by, and near certainly small.
        assertEquals(BucketLayout.HOT_BUCKET,
                BucketLayout.defaultLayout().indexFor("notes.txt"));
    }

    // ---------------------------------------------------------------- locality

    @Test
    @DisplayName("region, entities and poi for one map square share a bucket")
    void parallelViewsOfOneSquareGroupTogether() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        final int region = layout.indexFor("region/r.3.-1.mca");
        assertEquals(region, layout.indexFor("entities/r.3.-1.mca"));
        assertEquals(region, layout.indexFor("poi/r.3.-1.mca"));
    }

    @Test
    @DisplayName("an oversized chunk rides with the region that points at it")
    void externalChunksTravelWithTheirRegion() {
        final BucketLayout layout = BucketLayout.defaultLayout();

        // c.<x>.<z>.mcc carries CHUNK coordinates; r.<x>.<z>.mca carries REGION
        // coordinates, and there are 32 chunks to a region on each axis. Chunk
        // (100, 200) therefore lives in region (3, 6). Reading the numbers as if
        // they were the same unit puts the spill file 32 regions away from its
        // own region, in some other bucket - which is a chunk that silently stops
        // existing for whoever pulls next.
        assertEquals(layout.indexFor("region/r.3.6.mca"),
                layout.indexFor("region/c.100.200.mcc"),
                "chunk 100,200 belongs to region 3,6");

        // Negative coordinates use arithmetic shift, so -1 >> 5 is -1, not 0.
        assertEquals(layout.indexFor("region/r.-1.-1.mca"),
                layout.indexFor("region/c.-1.-1.mcc"),
                "chunk -1,-1 belongs to region -1,-1");

        assertEquals(layout.indexFor("dim-1/region/r.0.0.mca"),
                layout.indexFor("dim-1/region/c.5.5.mcc"),
                "and the dimension has to match too");
    }

    @Test
    @DisplayName("the same coordinates in different dimensions land in different buckets")
    void dimensionsDoNotCollide() {
        final BucketLayout layout = BucketLayout.defaultLayout();

        // Every dimension's r.0.0 used to hash identically, because only the
        // filename was hashed. That put the Overworld, Nether, End and every modded
        // dimension's spawn region in one bucket - the one bucket everybody is
        // guaranteed to dirty.
        final int overworld = layout.indexFor("region/r.0.0.mca");
        final int nether = layout.indexFor("dim-1/region/r.0.0.mca");
        final int end = layout.indexFor("dim1/region/r.0.0.mca");
        final int modded =
                layout.indexFor("dimensions/twilightforest/twilight_forest/region/r.0.0.mca");

        assertEquals(4, java.util.Set.of(overworld, nether, end, modded).size(),
                "all four spawn regions should be in different buckets, got "
                        + java.util.List.of(overworld, nether, end, modded));
    }

    @Test
    @DisplayName("grouping within a dimension survives the dimension being hashed")
    void dimensionsStillTileInternally() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        // The property Part B must not break: neighbours inside one dimension still
        // share a bucket, or the fix for cross-dimension collisions would have
        // traded away the locality tiling exists for.
        final int nether = layout.indexFor("dim-1/region/r.0.0.mca");
        assertEquals(nether, layout.indexFor("dim-1/region/r.1.1.mca"));
        assertEquals(nether, layout.indexFor("dim-1/entities/r.2.2.mca"));
        assertEquals(nether, layout.indexFor("dim-1/poi/r.3.3.mca"));
    }

    @Test
    @DisplayName("a session in one area dirties few buckets, not most of them")
    void nearbyRegionsGroupTogether() {
        // Regression test. Regions were hashed individually and neighbours
        // deliberately scattered - right for a load balancer, backwards here, where
        // the goal is for one session's changes to concentrate. A 3x3-region session
        // was dirtying 6.2 of 8 buckets on average and re-uploading three quarters of
        // the world.
        final BucketLayout layout = BucketLayout.defaultLayout();

        double total = 0;
        int samples = 0;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                final Set<Integer> dirty = new HashSet<>();
                dirty.add(BucketLayout.HOT_BUCKET);   // always dirty; level.dat changes
                for (int i = x; i < x + 3; i++) {
                    for (int j = z; j < z + 3; j++) {
                        dirty.add(layout.indexFor("region/r." + i + "." + j + ".mca"));
                    }
                }
                total += dirty.size();
                samples++;
            }
        }

        final double average = total / samples;
        assertTrue(average < 4.0,
                "a 3x3-region session should dirty well under 4 buckets on average, got " + average);
    }

    @Test
    @DisplayName("region files spread reasonably evenly across the non-hot buckets")
    void regionsAreNotAllInOneBucket() {
        // Guards the opposite failure from the one above: grouping so aggressively
        // that one archive ends up holding most of the world.
        final BucketLayout layout = BucketLayout.defaultLayout();
        final int[] histogram = new int[layout.bucketCount()];
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                histogram[layout.indexFor("region/r." + x + "." + z + ".mca")]++;
            }
        }

        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int i = 1; i < histogram.length; i++) {   // skip the hot bucket
            min = Math.min(min, histogram[i]);
            max = Math.max(max, histogram[i]);
        }
        assertTrue(min > 0, "every region bucket should hold something");
        assertTrue((double) max / min < 8.0,
                "region distribution too lopsided: max/min was " + ((double) max / min));
    }

    // ---------------------------------------------------------------- filenames

    @Test
    @DisplayName("bucket filenames round-trip back to their index")
    void filenamesRoundTrip() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        for (int i = 0; i < layout.bucketCount(); i++) {
            assertEquals(i, layout.indexFromFilename(BucketLayout.bucketFilename(i)));
        }
    }

    @Test
    @DisplayName("foreign and out-of-range filenames are rejected")
    void foreignFilenamesRejected() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        assertEquals(-1, layout.indexFromFilename("something-else.zip"));
        assertEquals(-1, layout.indexFromFilename("worldshare-bucket_99.zip"));
        assertEquals(-1, layout.indexFromFilename(null));
        assertEquals(-1, layout.indexFromFilename(BucketLayout.CONTROL_FILENAME));
    }

    @Test
    @DisplayName("the remote file list is control + presence + every bucket")
    void remoteFileListIsComplete() {
        final BucketLayout layout = BucketLayout.defaultLayout();
        final String[] names = layout.allRemoteFilenames();
        assertEquals(layout.bucketCount() + 2, names.length);
        assertEquals(layout.remoteFileCount(), names.length);
        assertEquals(BucketLayout.CONTROL_FILENAME, names[0]);
        assertEquals(BucketLayout.PRESENCE_FILENAME, names[1]);
    }

    // ---------------------------------------------------------------- edge counts

    @Test
    @DisplayName("a single bucket puts everything in one archive")
    void singleBucketDegradesCleanly() {
        final BucketLayout layout = new BucketLayout(1);
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("level.dat"));
        assertEquals(BucketLayout.HOT_BUCKET, layout.indexFor("region/r.5.5.mca"));
    }

    @Test
    @DisplayName("two buckets means one hot and one for every region")
    void twoBucketsSplitHotFromRegions() {
        // Worth pinning: 2 is the worst possible count, not a middle ground - it
        // leaves exactly one region bucket, so every session re-uploads all of it.
        final BucketLayout layout = new BucketLayout(2);
        assertEquals(0, layout.indexFor("level.dat"));
        assertEquals(1, layout.indexFor("region/r.5.5.mca"));
        assertEquals(1, layout.indexFor("region/r.-40.17.mca"));
    }

    @Test
    @DisplayName("bucket counts outside the permitted range are refused")
    void invalidCountsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BucketLayout(0));
        assertThrows(IllegalArgumentException.class, () -> new BucketLayout(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new BucketLayout(BucketLayout.MAX_BUCKET_COUNT + 1));
    }
}
