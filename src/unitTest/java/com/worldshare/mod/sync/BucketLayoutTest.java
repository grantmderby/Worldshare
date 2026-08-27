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
