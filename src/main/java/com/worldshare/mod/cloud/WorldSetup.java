package com.worldshare.mod.cloud;

import com.google.api.services.drive.model.File;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.sync.BucketLayout;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One-time setup of a world's fixed remote file set.
 *
 * <p>Under the {@code drive.file} scope the mod can only reach files the user
 * personally handed it through Google's Picker, and it can never discover a file
 * by name. Everything a world will ever need therefore has to exist, and be
 * picked, before the first sync. That is what this class does, from the two sides
 * it can be approached from:
 *
 * <ul>
 *   <li>{@link #createNewWorld} - the player starting a shared world. They pick a
 *       Drive <em>folder</em>, and the mod creates the whole fixed file set inside
 *       it as empty placeholders. Files an app creates are permanently reachable
 *       by that app for that user, so the creator never has to pick them
 *       individually.</li>
 *   <li>{@link #joinExistingWorld} - the player being invited. The files already
 *       exist, created by someone else, so every one of them must be selected
 *       individually in the Picker. Selecting the containing folder does not
 *       work: testing showed a folder grant conveys no access to its contents,
 *       even for files that were already inside it when it was picked.</li>
 * </ul>
 *
 * <p><b>Why the files are created empty.</b> A placeholder can be picked, and a
 * picked file can be overwritten forever afterwards. Creating them up front is
 * what converts "an unbounded, growing set of region files" - which
 * {@code drive.file} cannot express - into "a fixed list somebody approved once".
 *
 * <p><b>Threading:</b> both entry points open a browser and block for as long as
 * the user takes. Never call them on the Minecraft main thread.
 */
public final class WorldSetup {

    /**
     * Content type for the placeholder bucket archives.
     *
     * <p>They start as zero-byte files, which is not a valid zip. Nothing reads
     * them until the first push replaces the content, and
     * {@code SyncEngine.downloadBucket} treats a zero-length archive as "nothing
     * pushed here yet" rather than as corruption.
     */
    private static final String MIME_TYPE_ZIP = "application/zip";

    private WorldSetup() {
        // utility class
    }

    /**
     * Create a brand new shared world's remote files.
     *
     * <p>Consent is requested with folder selection enabled; whatever folder the
     * user picks becomes the home of the fixed file set. Nothing outside that
     * folder is ever touched.
     *
     * @param urlPresenter how to show the user the authorization URL
     * @param bucketCount  how many bucket archives to create; frozen for this
     *                     world's lifetime
     * @param worldName    used only for logging
     * @return a complete {@link RemoteFileSet} ready to be saved into the world's
     *         {@code WorldLink}
     * @throws IOException if the user picked nothing, picked something that isn't a
     *                     folder, or a file couldn't be created
     */
    public static RemoteFileSet createNewWorld(final Consumer<String> urlPresenter,
                                               final int bucketCount,
                                               final String worldName)
            throws IOException, GeneralSecurityException {
        final BucketLayout layout = new BucketLayout(bucketCount);

        final PickerAuthResult auth =
                OAuthHelper.authorizeWithPicker(urlPresenter, false, true);
        if (!auth.hasPicks()) {
            throw new IOException(
                    "No folder was selected. Re-run setup and choose (or create) a Drive "
                            + "folder to keep this world in.");
        }

        final String folderId = auth.pickedFileIds().get(0);
        CloudModule.refreshCredential(auth.credential());

        final DriveClient client = CloudModule.driveClient();
        final File folderMeta = client.getFileMeta(folderId);
        if (folderMeta == null) {
            throw new IOException("Drive didn't return the folder you picked. Try setup again.");
        }
        if (!DriveClient.MIME_TYPE_FOLDER.equals(folderMeta.getMimeType())) {
            throw new IOException(
                    "You picked a file ('" + folderMeta.getName() + "'), not a folder. "
                            + "Re-run setup and choose a folder to keep this world in.");
        }

        WorldShareMod.LOGGER.info("Setting up world '{}' in Drive folder '{}' ({}), {} buckets",
                worldName, folderMeta.getName(), folderId, bucketCount);

        final RemoteFileSet remote = RemoteFileSet.empty(bucketCount);
        remote.driveFolderId = folderId;

        remote.controlFileId = client.writeText(
                null, BucketLayout.CONTROL_FILENAME, folderId, "", DriveClient.MIME_TYPE_JSON);
        remote.presenceFileId = client.writeText(
                null, BucketLayout.PRESENCE_FILENAME, folderId, "", DriveClient.MIME_TYPE_JSON);

        for (int i = 0; i < bucketCount; i++) {
            final String id = client.writeText(
                    null, BucketLayout.bucketFilename(i), folderId, "", MIME_TYPE_ZIP);
            remote.setBucketFileId(i, id);
        }

        if (!remote.isComplete()) {
            // Shouldn't happen - every create above either returned an ID or threw -
            // but an incomplete set that reaches disk is a world that silently can't
            // sync, so it's worth refusing loudly here.
            throw new IOException("Setup finished but the file set is incomplete, missing: "
                    + remote.missingFilenames());
        }

        WorldShareMod.LOGGER.info("Created {} remote file(s) for world '{}'",
                layout.remoteFileCount(), worldName);
        return remote;
    }

    /**
     * Adopt an existing shared world by picking its files.
     *
     * <p>The player has to select every remote file individually. Picking the
     * folder instead is the obvious thing to try and it silently yields nothing -
     * a folder grant reaches neither files added later nor files already inside it -
     * so that case is detected and called out specifically rather than surfacing as
     * a bare "files missing".
     *
     * <p>A folder that comes back is still listed, on the chance the user selected
     * both it and its contents. Anything unrecognised is ignored rather than
     * treated as an error: shift-selecting a stray file in the shared folder should
     * not fail setup.
     *
     * <p>The joiner does not need to be told the world's bucket count in advance.
     * That number lives in the control file, which can't be read until the control
     * file has been picked - a chicken-and-egg the method resolves by matching on
     * filenames first with a provisional count, then re-matching once the real
     * count is known. Filenames encode their own bucket index, so the second pass
     * is exact.
     *
     * @param urlPresenter how to show the user the authorization URL
     * @return a {@link RemoteFileSet} which the caller MUST check with
     *         {@link RemoteFileSet#isComplete()} before saving
     */
    public static RemoteFileSet joinExistingWorld(final Consumer<String> urlPresenter)
            throws IOException, GeneralSecurityException {
        final PickerAuthResult auth =
                OAuthHelper.authorizeWithPicker(urlPresenter, true, true);
        if (!auth.hasPicks()) {
            throw new IOException(
                    "Nothing was selected. Re-run setup and choose the world's files "
                            + "(or the folder containing them) in the picker.");
        }

        CloudModule.refreshCredential(auth.credential());
        final DriveClient client = CloudModule.driveClient();

        final Map<String, String> nameToId = new LinkedHashMap<>();
        boolean pickedAnyFolder = false;
        for (final String pickedId : auth.pickedFileIds()) {
            final File meta = client.getFileMeta(pickedId);
            if (meta == null) {
                WorldShareMod.LOGGER.warn("join: picked ID {} isn't readable, skipping", pickedId);
                continue;
            }

            if (DriveClient.MIME_TYPE_FOLDER.equals(meta.getMimeType())) {
                // Expected to come back empty - a folder grant doesn't reach the
                // folder's contents - but list it anyway in case the user selected
                // the folder *and* the files inside it.
                pickedAnyFolder = true;
                final Map<String, String> children = listChildren(client, pickedId);
                WorldShareMod.LOGGER.info(
                        "join: picked folder '{}' exposes {} file(s)", meta.getName(), children.size());
                nameToId.putAll(children);
            } else {
                nameToId.put(meta.getName(), pickedId);
            }
        }

        if (nameToId.isEmpty() && pickedAnyFolder) {
            // By far the most likely way this goes wrong, and the least obvious to
            // diagnose from a generic "nothing matched" message.
            throw new IOException(
                    "You selected the folder itself, which doesn't grant access to what's "
                            + "inside it. Re-run this, open the shared folder in the picker, "
                            + "and select all the worldshare-* files within it.");
        }

        // First pass at the default layout, purely to locate the control file among
        // everything that came back.
        RemoteFileSet remote = RemoteFileSet.empty(BucketLayout.DEFAULT_BUCKET_COUNT);
        remote.acceptPicked(nameToId);

        if (remote.controlFileId == null) {
            throw new IOException(
                    "Couldn't find " + BucketLayout.CONTROL_FILENAME + " in what you picked. "
                            + "Open the shared folder in the picker and select all of its "
                            + "worldshare-* files, then try again.");
        }

        // Now the authoritative bucket count is readable. Re-match against it, so a
        // world set up with a non-default layout lands in the right slots.
        final int actualBucketCount = readBucketCount(remote.controlFileId);
        if (actualBucketCount != remote.bucketCount) {
            WorldShareMod.LOGGER.info("join: world uses {} buckets, not the default {}",
                    actualBucketCount, remote.bucketCount);
            remote = RemoteFileSet.empty(actualBucketCount);
            remote.acceptPicked(nameToId);
        }

        WorldShareMod.LOGGER.info("join: matched {} of {} required file(s)",
                remote.asNameToIdMap().values().stream().filter(v -> v != null).count(),
                new BucketLayout(remote.bucketCount).remoteFileCount());

        return remote;
    }

    /**
     * Read a world's bucket count straight from its control file.
     *
     * <p>Falls back to the default when the control file is still an empty
     * placeholder - a world whose creator set it up but never pushed. That's the
     * right guess, because a creator who never pushed also never departed from the
     * default, and if they somehow did, the layout mismatch guard in the sync
     * engine catches it loudly on the first sync rather than corrupting anything.
     */
    private static int readBucketCount(final String controlFileId) throws IOException {
        final ControlFile control = ControlFileClient.read(controlFileId);
        return (control == null) ? BucketLayout.DEFAULT_BUCKET_COUNT : control.bucketCount;
    }

    /**
     * List the files inside a folder that this token can actually see.
     *
     * <p>Under {@code drive.file} a listing is filtered to app-accessible files, so
     * an empty result is a legitimate answer meaning "the grant doesn't reach
     * these", not an error.
     */
    private static Map<String, String> listChildren(final DriveClient client,
                                                    final String folderId) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final File child : client.listFolderChildren(folderId)) {
            if (child.getName() != null && child.getId() != null) {
                out.put(child.getName(), child.getId());
            }
        }
        return out;
    }

    /**
     * Human-readable summary of what a partly-finished setup is still waiting on.
     * Kept here so the command and the GUI word it the same way.
     */
    public static String describeMissing(final RemoteFileSet remote) {
        final List<String> missing = new ArrayList<>(remote.missingFilenames());
        if (missing.isEmpty()) {
            return "nothing - setup is complete";
        }
        final int shown = Math.min(5, missing.size());
        final String head = String.join(", ", missing.subList(0, shown));
        return missing.size() > shown
                ? head + ", and " + (missing.size() - shown) + " more"
                : head;
    }
}
