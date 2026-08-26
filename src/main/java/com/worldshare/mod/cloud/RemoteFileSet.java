package com.worldshare.mod.cloud;

import com.worldshare.mod.sync.BucketLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Drive file IDs a single world syncs through: one control file plus a fixed
 * array of bucket archives.
 *
 * <p>This is the mod's address book for the remote side, and under the
 * {@code drive.file} scope it is the <em>only</em> way to reach anything. The old
 * design could look a file up by name inside a shared folder whenever it needed
 * it; that isn't available to us any more, because a narrow-scope token can't
 * list a folder's contents. Every ID here was handed to us once, by the user,
 * through the Picker, and if we lose one the only recovery is to walk them
 * through picking it again.
 *
 * <p>Persisted as part of {@code WorldLink} in the world folder, so it survives
 * restarts. It is machine-local: two players syncing the same world hold the same
 * file IDs, but each arrived at them through their own consent flow.
 *
 * <p><b>Not thread-safe.</b> Treat an instance as immutable once
 * {@link #isComplete()} returns true; the setup flow is the only thing that
 * should be mutating one.
 */
public final class RemoteFileSet {

    /** Bumped if the persisted shape changes incompatibly. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /**
     * How many buckets this world uses. Frozen when the world is first set up:
     * changing it would re-shuffle which bucket every file belongs to, so a
     * mismatch between two players silently corrupts the world rather than
     * failing loudly. {@link #matchesLayout} is the guard against that.
     */
    public int bucketCount;

    /** Drive file ID of the control file, or null if setup never completed. */
    public String controlFileId;

    /**
     * Drive file IDs of the bucket archives, indexed by bucket number. Slots may
     * be null while setup is partway through; a complete set has none.
     */
    public List<String> bucketFileIds = new ArrayList<>();

    /**
     * Drive folder the files live in, when we know it.
     *
     * <p>Only the player who originally created the world has this - they picked
     * the folder in order to create files inside it. A player who joined by
     * picking the individual files has no folder grant and leaves this null. It
     * exists purely so the creator can add more buckets later if the layout ever
     * grows; nothing in the normal sync path may depend on it being present.
     */
    public String driveFolderId;

    /** No-arg constructor required by Gson. */
    public RemoteFileSet() {}

    /**
     * Build an empty set sized for a layout, with every bucket slot unfilled.
     */
    public static RemoteFileSet empty(final int bucketCount) {
        final RemoteFileSet set = new RemoteFileSet();
        set.bucketCount = bucketCount;
        set.bucketFileIds = new ArrayList<>(Collections.nCopies(bucketCount, null));
        return set;
    }

    // ----- Access -----

    /**
     * Drive file ID for a bucket index.
     *
     * @return the ID, or null if that slot was never filled
     * @throws IndexOutOfBoundsException if the index isn't valid for this set
     */
    public String bucketFileId(final int index) {
        if (index < 0 || index >= bucketCount) {
            throw new IndexOutOfBoundsException(
                    "bucket index " + index + " outside [0, " + bucketCount + ")");
        }
        return (bucketFileIds == null || index >= bucketFileIds.size())
                ? null
                : bucketFileIds.get(index);
    }

    /** Assign the Drive file ID for a bucket index, growing the backing list if needed. */
    public void setBucketFileId(final int index, final String fileId) {
        if (index < 0 || index >= bucketCount) {
            throw new IndexOutOfBoundsException(
                    "bucket index " + index + " outside [0, " + bucketCount + ")");
        }
        if (bucketFileIds == null) {
            bucketFileIds = new ArrayList<>(Collections.nCopies(bucketCount, null));
        }
        while (bucketFileIds.size() < bucketCount) {
            bucketFileIds.add(null);
        }
        bucketFileIds.set(index, fileId);
    }

    /**
     * @return true if the control file and every bucket slot has an ID, i.e. this
     *         world is actually syncable
     */
    public boolean isComplete() {
        if (controlFileId == null || controlFileId.isBlank()) {
            return false;
        }
        if (bucketFileIds == null || bucketFileIds.size() < bucketCount) {
            return false;
        }
        for (int i = 0; i < bucketCount; i++) {
            final String id = bucketFileIds.get(i);
            if (id == null || id.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bucket indices with no file ID yet. Used by the setup flow to tell the user
     * precisely what still needs picking, rather than a bare "setup incomplete".
     */
    public List<Integer> missingBucketIndices() {
        final List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            final String id = (bucketFileIds != null && i < bucketFileIds.size())
                    ? bucketFileIds.get(i)
                    : null;
            if (id == null || id.isBlank()) {
                missing.add(i);
            }
        }
        return missing;
    }

    /**
     * Remote filenames this set is still missing, ready to show a player who needs
     * to re-run the Picker.
     */
    public List<String> missingFilenames() {
        final List<String> names = new ArrayList<>();
        if (controlFileId == null || controlFileId.isBlank()) {
            names.add(BucketLayout.CONTROL_FILENAME);
        }
        for (final int index : missingBucketIndices()) {
            names.add(BucketLayout.bucketFilename(index));
        }
        return names;
    }

    /**
     * Guard against two players running different bucket counts against the same
     * world - which would put the same file in different buckets on each side and
     * corrupt the world without any obvious error.
     *
     * @return true if this set was built for the given layout
     */
    public boolean matchesLayout(final BucketLayout layout) {
        return layout != null && layout.bucketCount() == bucketCount;
    }

    /** A {@link BucketLayout} matching this set's frozen bucket count. */
    public BucketLayout layout() {
        return new BucketLayout(bucketCount);
    }

    /**
     * Every known Drive file ID keyed by its remote filename. Handy for logging
     * and for the "what does this world actually point at" diagnostic command.
     */
    public Map<String, String> asNameToIdMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put(BucketLayout.CONTROL_FILENAME, controlFileId);
        for (int i = 0; i < bucketCount; i++) {
            map.put(BucketLayout.bucketFilename(i), bucketFileId(i));
        }
        return map;
    }

    /**
     * Fill in whichever slots the given filename-to-ID pairs correspond to,
     * ignoring anything that isn't part of this layout.
     *
     * <p>This is how a joining player's Picker selection gets turned into a usable
     * set: they hand back a pile of file IDs in arbitrary order, and the caller
     * resolves each one's name from Drive before passing it here.
     *
     * @return how many slots this call actually filled
     */
    public int acceptPicked(final Map<String, String> filenameToId) {
        if (filenameToId == null || filenameToId.isEmpty()) {
            return 0;
        }
        final BucketLayout layout = layout();
        int filled = 0;
        for (final Map.Entry<String, String> entry : filenameToId.entrySet()) {
            final String name = entry.getKey();
            final String id = entry.getValue();
            if (name == null || id == null || id.isBlank()) {
                continue;
            }
            if (BucketLayout.CONTROL_FILENAME.equals(name)) {
                controlFileId = id;
                filled++;
                continue;
            }
            final int index = layout.indexFromFilename(name);
            if (index >= 0) {
                setBucketFileId(index, id);
                filled++;
            }
        }
        return filled;
    }

    @Override
    public String toString() {
        return "RemoteFileSet{buckets=" + bucketCount
                + ", complete=" + isComplete()
                + ", control=" + (controlFileId == null ? "unset" : "set") + "}";
    }
}
