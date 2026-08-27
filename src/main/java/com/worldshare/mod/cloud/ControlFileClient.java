package com.worldshare.mod.cloud;

import com.google.gson.JsonSyntaxException;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Reads and writes a world's {@link ControlFile} on Drive.
 *
 * <p>Deliberately tiny. It exists because two very different parts of the mod -
 * the sync engine and the lock manager - both need to round-trip the same remote
 * document, and having each roll its own read/parse/write meant two places to get
 * the "never delete, always update in place" rule wrong.
 *
 * <p><b>The rule, stated once:</b> a control file is only ever created during
 * world setup. Every later write is an in-place
 * {@code files.update()} against the same Drive file ID. Creating a replacement
 * would mint a new ID that no other player has been granted under
 * {@code drive.file}, silently cutting them off from the world.
 */
public final class ControlFileClient {

    private ControlFileClient() {
        // utility class
    }

    /**
     * Fetch and parse a world's control file.
     *
     * @param controlFileId Drive file ID from the world's {@link RemoteFileSet}
     * @return the parsed control file, or null if the remote file is still empty
     *         (a freshly created placeholder that nobody has pushed to yet)
     * @throws IOException if the download fails, or the content isn't valid JSON
     */
    public static ControlFile read(final String controlFileId) throws IOException {
        requireId(controlFileId);
        final DriveClient client = CloudModule.driveClient();
        final String json = client.readText(controlFileId);

        if (json == null || json.isBlank()) {
            // Setup creates the file as a zero-byte placeholder so it can be picked
            // before any world data exists. Not an error - just "nothing pushed yet".
            WorldShareMod.LOGGER.debug("ControlFileClient: control file {} is empty", controlFileId);
            return null;
        }

        try {
            return ControlFile.fromJson(json);
        } catch (final JsonSyntaxException e) {
            throw new IOException(
                    "The world's control file on Drive is malformed. It may have been edited "
                            + "or damaged; the other player may need to re-push. Details: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Overwrite a world's control file in place.
     *
     * <p>Stamps {@link ControlFile#updatedAt} as a side effect, so callers don't
     * each have to remember to.
     */
    public static void write(final String controlFileId, final ControlFile control)
            throws IOException {
        requireId(controlFileId);
        if (control == null) {
            throw new IllegalArgumentException("refusing to write a null control file");
        }

        control.touch(Instant.now());
        final DriveClient client = CloudModule.driveClient();

        // Non-null fileId means files.update() - see the class note on why this must
        // never fall through to a create.
        client.writeText(controlFileId, BUCKET_CONTROL_NAME_FOR_LOGS, null,
                control.toJson(), DriveClient.MIME_TYPE_JSON);

        WorldShareMod.LOGGER.debug("ControlFileClient: wrote control file {} ({} files, lock={})",
                controlFileId, control.manifestOrEmpty().size(), control.lockOrUnlocked().status);
    }

    /**
     * Read the control file, falling back to a fresh initial one if the remote is
     * still an empty placeholder.
     *
     * <p>Used by the write paths, which need something to modify regardless of
     * whether anyone has pushed yet.
     *
     * @param bucketCount layout to assume if we have to synthesise a control file
     */
    public static ControlFile readOrInitial(final String controlFileId, final int bucketCount)
            throws IOException {
        final ControlFile existing = read(controlFileId);
        return existing != null ? existing : ControlFile.initial(bucketCount, Instant.now());
    }

    /**
     * Read the control file, apply a change to it, and write it back - with no
     * other thread in this JVM able to interleave its own read-modify-write.
     *
     * <p><b>Why this exists.</b> Folding the manifest and the session lock into one
     * remote document bought atomicity between them, but it created a hazard the
     * old two-file layout didn't have: the heartbeat thread and the sync thread now
     * write the <em>same</em> file. A heartbeat that read the control file just
     * before a push committed would write its stale copy back afterwards and
     * silently erase the manifest that push had just published - losing the record
     * of every bucket it uploaded.
     *
     * <p>A single JVM-wide monitor closes that window, and it is sufficient rather
     * than merely convenient: the only writers of a given world's control file are
     * the machine holding its session lock. The other player cannot push without
     * the lock, and cannot heartbeat a lock they do not hold. Cross-machine
     * conflicts still resolve the way they always have - last writer wins, with the
     * heartbeat's ownership check catching a takeover after the fact.
     *
     * @param controlFileId Drive file ID of the control file
     * @param bucketCount   layout to assume if the remote file is still an empty
     *                      placeholder
     * @param mutator       applied to the freshly-read control file; whatever it
     *                      leaves behind is what gets written
     * @return the control file as written
     */
    public static ControlFile update(final String controlFileId,
                                     final int bucketCount,
                                     final Consumer<ControlFile> mutator) throws IOException {
        requireId(controlFileId);
        synchronized (WRITE_MONITOR) {
            final ControlFile control = readOrInitial(controlFileId, bucketCount);
            mutator.accept(control);
            write(controlFileId, control);
            return control;
        }
    }

    // -----------------------------------------------------------------

    /**
     * Guards every read-modify-write cycle on a control file. See {@link #update}
     * for why one process-wide monitor is the right granularity here.
     */
    private static final Object WRITE_MONITOR = new Object();

    /**
     * Name passed to {@code writeText} on the update path. Drive ignores it when a
     * file ID is supplied - it's here so a log or a stack trace mentions something
     * meaningful rather than a bare null.
     */
    private static final String BUCKET_CONTROL_NAME_FOR_LOGS =
            com.worldshare.mod.sync.BucketLayout.CONTROL_FILENAME;

    private static void requireId(final String controlFileId) throws IOException {
        if (controlFileId == null || controlFileId.isBlank()) {
            throw new IOException(
                    "This world has no control file yet. Run WorldShare setup for it first - "
                            + "under the drive.file scope the mod can only reach files you "
                            + "picked yourself.");
        }
    }
}
