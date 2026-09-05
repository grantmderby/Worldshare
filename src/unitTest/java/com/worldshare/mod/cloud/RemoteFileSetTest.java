package com.worldshare.mod.cloud;

import com.worldshare.mod.sync.BucketLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under {@code drive.file} this is the only way to reach anything on Drive — a
 * file the user handed us once, by ID. Two failure modes matter: accepting an
 * incomplete set (a world that silently can't sync), and accepting one built for
 * a different bucket count (a world that syncs into archives nobody reads back).
 */
class RemoteFileSetTest {

    private static RemoteFileSet complete(final int buckets) {
        final RemoteFileSet set = RemoteFileSet.empty(buckets);
        set.controlFileId = "control-id";
        set.presenceFileId = "presence-id";
        for (int i = 0; i < buckets; i++) {
            set.setBucketFileId(i, "bucket-" + i);
        }
        return set;
    }

    // ---------------------------------------------------------------- completeness

    @Test
    @DisplayName("a fully populated set is complete")
    void completeSetIsComplete() {
        assertTrue(complete(16).isComplete());
        assertTrue(complete(16).missingFilenames().isEmpty());
    }

    @Test
    @DisplayName("a fresh set is incomplete and lists everything as missing")
    void emptySetIsIncomplete() {
        final RemoteFileSet set = RemoteFileSet.empty(8);
        assertFalse(set.isComplete());
        // control + presence + 8 buckets
        assertEquals(10, set.missingFilenames().size());
    }

    @Test
    @DisplayName("a missing control file alone makes the set unusable")
    void missingControlFileIsIncomplete() {
        final RemoteFileSet set = complete(4);
        set.controlFileId = null;
        assertFalse(set.isComplete());
        assertTrue(set.missingFilenames().contains(BucketLayout.CONTROL_FILENAME));
    }

    @Test
    @DisplayName("a missing presence file alone makes the set unusable")
    void missingPresenceFileIsIncomplete() {
        final RemoteFileSet set = complete(4);
        set.presenceFileId = null;
        assertFalse(set.isComplete());
        assertTrue(set.missingFilenames().contains(BucketLayout.PRESENCE_FILENAME));
    }

    @Test
    @DisplayName("one missing bucket is named specifically, not just counted")
    void missingBucketIsNamed() {
        // The join screen shows these to the user, so they need to identify the
        // exact file to go back and pick.
        final RemoteFileSet set = complete(8);
        set.setBucketFileId(5, null);

        assertFalse(set.isComplete());
        assertEquals(java.util.List.of(5), set.missingBucketIndices());
        assertEquals(java.util.List.of(BucketLayout.bucketFilename(5)), set.missingFilenames());
    }

    @Test
    @DisplayName("a blank ID counts as missing, not present")
    void blankIdIsMissing() {
        final RemoteFileSet set = complete(4);
        set.controlFileId = "   ";
        assertFalse(set.isComplete());
    }

    // ---------------------------------------------------------------- picking

    @Test
    @DisplayName("picked files are matched to slots by filename")
    void acceptPickedMatchesByName() {
        final RemoteFileSet set = RemoteFileSet.empty(4);
        final Map<String, String> picked = new LinkedHashMap<>();
        picked.put(BucketLayout.CONTROL_FILENAME, "c-id");
        picked.put(BucketLayout.PRESENCE_FILENAME, "p-id");
        for (int i = 0; i < 4; i++) {
            picked.put(BucketLayout.bucketFilename(i), "b" + i);
        }

        assertEquals(6, set.acceptPicked(picked));
        assertTrue(set.isComplete());
        assertEquals("c-id", set.controlFileId);
        assertEquals("b2", set.bucketFileId(2));
    }

    @Test
    @DisplayName("unrelated files in the picked set are ignored, not fatal")
    void acceptPickedIgnoresStrangers() {
        // Shift-selecting a stray file in the shared folder shouldn't fail setup.
        final RemoteFileSet set = RemoteFileSet.empty(2);
        final Map<String, String> picked = new LinkedHashMap<>();
        picked.put(BucketLayout.CONTROL_FILENAME, "c");
        picked.put("holiday-photo.jpg", "junk");
        picked.put("worldshare-bucket_99.zip", "out-of-range");

        assertEquals(1, set.acceptPicked(picked));
        assertEquals("c", set.controlFileId);
    }

    @Test
    @DisplayName("picking nothing changes nothing")
    void acceptPickedHandlesEmpty() {
        final RemoteFileSet set = RemoteFileSet.empty(4);
        assertEquals(0, set.acceptPicked(null));
        assertEquals(0, set.acceptPicked(new LinkedHashMap<>()));
        assertFalse(set.isComplete());
    }

    // ---------------------------------------------------------------- layout guard

    @Test
    @DisplayName("a set only matches a layout with the same bucket count")
    void layoutMismatchIsDetected() {
        // The one error that corrupts silently rather than failing: the same path
        // hashes to a different bucket under a different count, so syncing across a
        // mismatch scatters files into archives nobody reads back.
        final RemoteFileSet set = complete(8);
        assertTrue(set.matchesLayout(new BucketLayout(8)));
        assertFalse(set.matchesLayout(new BucketLayout(16)));
        assertFalse(set.matchesLayout(null));
        assertEquals(8, set.layout().bucketCount());
    }

    // ---------------------------------------------------------------- bounds

    @Test
    @DisplayName("bucket indices outside the set are rejected loudly")
    void outOfRangeIndicesThrow() {
        final RemoteFileSet set = complete(4);
        assertThrows(IndexOutOfBoundsException.class, () -> set.bucketFileId(4));
        assertThrows(IndexOutOfBoundsException.class, () -> set.bucketFileId(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> set.setBucketFileId(9, "x"));
    }

    @Test
    @DisplayName("an unfilled bucket slot reads as null rather than throwing")
    void unfilledSlotIsNull() {
        assertNull(RemoteFileSet.empty(4).bucketFileId(0));
    }

    @Test
    @DisplayName("the name-to-ID map covers every remote file")
    void nameToIdMapIsComplete() {
        final Map<String, String> map = complete(8).asNameToIdMap();
        assertEquals(10, map.size());
        assertEquals("control-id", map.get(BucketLayout.CONTROL_FILENAME));
        assertEquals("presence-id", map.get(BucketLayout.PRESENCE_FILENAME));
        assertEquals("bucket-7", map.get(BucketLayout.bucketFilename(7)));
    }
}
