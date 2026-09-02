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
 *       even for files that were already inside it when it was picked. The
 *       inviter's folder ID is used to scope the Picker so those files are the
 *       only thing on screen.</li>
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
     * Reports how far through creating the remote files setup has got.
     *
     * <p>Twenty-six files created one at a time is around half a minute, and the
     * command said nothing for all of it.
     */
    @FunctionalInterface
    public interface SetupProgress {
        void onProgress(int done, int total);

        SetupProgress NOOP = (done, total) -> {};
    }

    /** Name of the folder that new world folders are created inside. */
    private static final String LIBRARY_FOLDER_NAME = "WorldShare";

    /** Private tag identifying that folder, so renaming it doesn't lose us. */
    private static final String LIBRARY_TAG_KEY = "worldshare";
    private static final String LIBRARY_TAG_VALUE = "library";

    /**
     * Tag carrying a world folder's name, so it can be found after a reinstall.
     *
     * <p>The name search that backs this looks across the whole Drive rather than
     * inside the library, which is the point: the player is free to drag the
     * folder anywhere, and finding it only where we left it would mean a
     * reinstall silently built a second world beside the real one.
     */
    private static final String WORLD_TAG_KEY = "worldshareWorld";

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
     * <p>If the chosen folder already holds a WorldShare world that this user
     * created, it is adopted rather than duplicated - so this doubles as the
     * recovery path for a creator who lost their local link file, and they get
     * their world back by picking one folder rather than every file.
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
        return createNewWorld(urlPresenter, bucketCount, worldName, false, SetupProgress.NOOP);
    }

    /**
     * @param adoptExisting open the Picker and set the world up in a folder the
     *                      player chooses, rather than making one. The
     *                      returning-creator case: somebody who lost
     *                      {@code worldshare-link.json} and whose world is still in
     *                      a folder, who would otherwise get a second world built
     *                      beside the real one.
     */
    public static RemoteFileSet createNewWorld(final Consumer<String> urlPresenter,
                                               final int bucketCount,
                                               final String worldName,
                                               final boolean adoptExisting)
            throws IOException, GeneralSecurityException {
        return createNewWorld(urlPresenter, bucketCount, worldName, adoptExisting,
                SetupProgress.NOOP);
    }

    /**
     * As above, reporting each remote file as it is created.
     *
     * @param progress called after every file, with how many exist so far out of
     *                 the total this world needs
     */
    public static RemoteFileSet createNewWorld(final Consumer<String> urlPresenter,
                                               final int bucketCount,
                                               final String worldName,
                                               final boolean adoptExisting,
                                               final SetupProgress progress)
            throws IOException, GeneralSecurityException {
        final String folderId;

        if (adoptExisting) {
            final PickerAuthResult auth =
                    OAuthHelper.authorizeWithPicker(urlPresenter, false, true);
            if (!auth.hasPicks()) {
                throw new IOException(
                        "No folder was selected. Re-run /worldshare setup existing and "
                                + "choose the folder your world is already in.");
            }
            CloudModule.refreshCredential(auth.credential());
            folderId = auth.pickedFileIds().get(0);
        } else {
            // Make the folder ourselves rather than asking the player to find one.
            //
            // The OAuth picker can only *select*, never create - it has no "new
            // folder" button and no parameter that adds one. So requiring a folder
            // meant a first-time player had to leave the game, make one in Drive's
            // web interface, come back and re-run setup, which is a poor first
            // thing to ask of somebody who installed this to avoid thinking about
            // Drive. The drive.file scope lets an app create folders and keeps
            // access to what it created, which is the same property the bucket
            // files already rely on.
            CloudModule.refreshCredential(OAuthHelper.authorize(urlPresenter));
            final DriveClient drive = CloudModule.driveClient();
            final String library = ensureLibraryFolder(drive);
            final String folderName = "WorldShare - " + worldName;

            // Look before creating. Setup can fail part-way - Drive goes
            // unreachable somewhere among twenty-six file creations - and it
            // writes worldshare-link.json only on success, so the world still
            // looks unconfigured afterwards and the natural response is to run
            // the command again. Creating unconditionally made that second run
            // build a second folder beside the abandoned one, with the world
            // bound to the new empty set and the first orphaned.
            //
            // Finding it instead hands the existing folder to the adoption logic
            // below, which creates only what is genuinely absent. That turns a
            // retry into a resume, and it does the same job for the creator who
            // reinstalled: their folder is still in the library, so plain setup
            // reuses it rather than needing 'setup existing'.
            // Tagged first, by name in the library second. The tag survives the
            // player renaming or moving the folder, which the library lookup does
            // not - and a reinstall that fails to find an existing world folder
            // creates a second one and orphans the first, so it is worth two
            // lookups to make that unlikely. The name search still covers folders
            // created before tagging existed.
            String reusable = drive.findFolderByAppProperty(WORLD_TAG_KEY, worldName);
            if (reusable == null) {
                reusable = findChildFolder(drive, library, folderName);
            }
            if (reusable != null) {
                folderId = reusable;
                WorldShareMod.LOGGER.info(
                        "setup: reusing the '{}' folder already in the library ({})",
                        folderName, folderId);
            } else {
                folderId = drive.createFolder(folderName, library,
                        Map.of(WORLD_TAG_KEY, worldName));
                WorldShareMod.LOGGER.info(
                        "setup: created Drive folder '{}' ({}) in the library ({})",
                        folderName, folderId, library);
            }
        }

        final DriveClient client = CloudModule.driveClient();
        final File folderMeta = client.getFileMeta(folderId);
        if (folderMeta == null) {
            throw new IOException("Drive didn't return the folder. Try setup again.");
        }
        if (!DriveClient.MIME_TYPE_FOLDER.equals(folderMeta.getMimeType())) {
            throw new IOException(
                    "'" + folderMeta.getName() + "' is a file, not a folder. "
                            + "Re-run setup and choose a folder to keep this world in.");
        }

        WorldShareMod.LOGGER.info("Setting up world '{}' in Drive folder '{}' ({})",
                worldName, folderMeta.getName(), folderId);

        // Adopt anything already in the folder before creating anything new.
        //
        // This is not an optimisation, it prevents data loss. A folder grant lets us
        // list the files *this app created for this user*, which means a returning
        // creator - reinstalled Minecraft, lost worldshare-link.json - shows up here
        // with their real world sitting in the folder. Creating blindly would mint a
        // second set of identically-named files, bind the mod to the new empty ones,
        // and orphan the world without any error the player would notice.
        //
        // A joining player sees an empty listing here instead, because they never
        // created these files; they go through joinExistingWorld and pick each file.
        final Map<String, String> existing = listChildren(client, folderId);

        final RemoteFileSet remote;
        if (existing.containsKey(BucketLayout.CONTROL_FILENAME)) {
            // The bucket count is whatever the existing world already uses; the
            // caller's preference doesn't get to re-partition an established world.
            final String existingControlId = existing.get(BucketLayout.CONTROL_FILENAME);
            final int existingBuckets = readBucketCount(existingControlId, existing);
            remote = RemoteFileSet.empty(existingBuckets);
            remote.acceptPicked(existing);
            WorldShareMod.LOGGER.info(
                    "setup: adopting the existing world already in this folder ({} buckets)",
                    existingBuckets);
        } else {
            remote = RemoteFileSet.empty(bucketCount);
        }
        remote.driveFolderId = folderId;

        // Create only what's genuinely absent. On the adoption path this is usually
        // nothing; on a folder where setup died halfway it fills just the gaps.
        int created = 0;
        int done = 0;
        final int total = remote.layout().remoteFileCount();
        if (remote.controlFileId == null) {
            remote.controlFileId = client.writeText(
                    null, BucketLayout.CONTROL_FILENAME, folderId, "", DriveClient.MIME_TYPE_JSON);
            created++;
        }
        progress.onProgress(++done, total);
        if (remote.presenceFileId == null) {
            remote.presenceFileId = client.writeText(
                    null, BucketLayout.PRESENCE_FILENAME, folderId, "", DriveClient.MIME_TYPE_JSON);
            created++;
        }
        progress.onProgress(++done, total);
        for (int i = 0; i < remote.bucketCount; i++) {
            if (remote.bucketFileId(i) == null) {
                final String id = client.writeText(
                        null, BucketLayout.bucketFilename(i), folderId, "", MIME_TYPE_ZIP);
                remote.setBucketFileId(i, id);
                created++;
            }
            progress.onProgress(++done, total);
        }
        WorldShareMod.LOGGER.info("setup: {} file(s) already present, {} created",
                remote.layout().remoteFileCount() - created, created);

        if (!remote.isComplete()) {
            // Shouldn't happen - every create above either returned an ID or threw -
            // but an incomplete set that reaches disk is a world that silently can't
            // sync, so it's worth refusing loudly here.
            throw new IOException("Setup finished but the file set is incomplete, missing: "
                    + remote.missingFilenames());
        }

        WorldShareMod.LOGGER.info("World '{}' is ready: {} remote file(s), {} bucket(s)",
                worldName, remote.layout().remoteFileCount(), remote.bucketCount);
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
     * @param inviteFolderId the world's Drive folder ID, from the invite the host
     *                       shared. Optional: when present the Picker shows only
     *                       that folder, so the player opens it and selects the
     *                       contents rather than hunting through their Drive. When
     *                       null they browse Drive themselves, which still works.
     * @return a {@link RemoteFileSet} which the caller MUST check with
     *         {@link RemoteFileSet#isComplete()} before saving
     */
    public static RemoteFileSet joinExistingWorld(final Consumer<String> urlPresenter,
                                                  final String inviteFolderId)
            throws IOException, GeneralSecurityException {
        final List<String> scope = (inviteFolderId == null || inviteFolderId.isBlank())
                ? null
                : List.of(inviteFolderId);

        // Folder selection is deliberately OFF here, unlike the creator flow.
        //
        // With the Picker scoped to the world's folder, the folder is the first
        // thing on screen and selecting it is the obvious move - but a folder grant
        // reaches none of its contents, so that returns a useless grant that looks
        // like success. Turning folder selection off leaves the folder navigable
        // while making it unselectable, which funnels the player into opening it
        // and selecting the files. Verified against the live Picker.
        final PickerAuthResult auth =
                OAuthHelper.authorizeWithPicker(urlPresenter, true, false, scope);
        if (!auth.hasPicks()) {
            throw new IOException(
                    "Nothing was selected. Open the world's folder in the picker and "
                            + "select the files inside it.");
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
            // Should be unreachable now that folder selection is off for this flow,
            // but a folder can still arrive if the user had previously granted one.
            throw new IOException(
                    "You selected a folder, which doesn't grant access to what's inside it. "
                            + "Open the folder in the picker and select the worldshare-* "
                            + "files within it.");
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
        final int actualBucketCount = readBucketCount(remote.controlFileId, nameToId);
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
     * How many buckets a world uses: what its control file says, or failing that,
     * how many bucket files it actually has.
     *
     * <p>The control file is authoritative when it has been written. Until somebody
     * pushes, it is an empty placeholder that says nothing - and this used to fall
     * back to {@link BucketLayout#DEFAULT_BUCKET_COUNT} at that point, on the
     * reasoning that a creator who never pushed never departed from the default.
     * True of the creator, and irrelevant: the default is a property of <em>the
     * client asking</em>, and clients disagree across versions.
     *
     * <p>Raising the default from 16 to 24 made that concrete. A 24-bucket client
     * meeting a never-pushed 16-bucket world would either report eight buckets
     * missing that never existed, when joining, or - worse, when re-running setup -
     * create those eight and re-partition a world that already had a layout, which
     * is the one thing the adoption path exists to prevent.
     *
     * <p>The files are the better witness anyway. They <em>are</em> the layout, they
     * are present whether or not anyone has pushed, and counting them cannot
     * disagree with itself between versions.
     *
     * @param present the world's remote files by name, as the caller already has
     *                them - picked files when joining, folder contents when adopting
     */
    private static int readBucketCount(final String controlFileId,
                                       final Map<String, String> present) throws IOException {
        final ControlFile control = ControlFileClient.read(controlFileId);
        if (control != null && control.bucketCount > 0) {
            return control.bucketCount;
        }
        final int counted = countBucketFiles(present);
        if (counted > 0) {
            WorldShareMod.LOGGER.info(
                    "setup: control file is still empty; taking the layout from the {} "
                            + "bucket file(s) present", counted);
            return counted;
        }
        // No control file content and no bucket files: genuinely a new world, so the
        // asking client's default is the right answer.
        return BucketLayout.DEFAULT_BUCKET_COUNT;
    }

    /** How many {@code worldshare-bucket_NN.zip} files are in this name set. */
    private static int countBucketFiles(final Map<String, String> present) {
        if (present == null) return 0;
        int count = 0;
        for (final String name : present.keySet()) {
            if (name != null
                    && name.startsWith(BucketLayout.BUCKET_PREFIX)
                    && name.endsWith(BucketLayout.BUCKET_SUFFIX)) {
                count++;
            }
        }
        return count;
    }

    /**
     * List the files inside a folder that this token can actually see.
     *
     * <p>Under {@code drive.file} a listing is filtered to app-accessible files, so
     * an empty result is a legitimate answer meaning "the grant doesn't reach
     * these", not an error.
     */
    /**
     * The one folder that holds every world this player creates, made on demand.
     *
     * <p>Without it each world drops a folder into the root of My Drive, and
     * somebody who shares four worlds has four of ours sitting among their own
     * files. One parent keeps that to a single entry.
     *
     * <p>Found by app property rather than by name, because the player owns this
     * folder and is free to rename or move it - a name search would quietly stop
     * matching and start minting duplicates. The tag is private to this app and
     * travels with the folder. If the folder is in the trash the search skips it
     * and we make a new one, which is the right answer: a trashed folder is one
     * the player was done with.
     *
     * <p>The world folders keep their {@code WorldShare - } prefix even though
     * the parent already says as much. The redundancy shows up once, in a listing
     * the creator understands; the name is read without that context by the
     * person they share it with, who sees it alone under "Shared with me".
     */
    private static String ensureLibraryFolder(final DriveClient client) throws IOException {
        final String found = client.findFolderByAppProperty(LIBRARY_TAG_KEY, LIBRARY_TAG_VALUE);
        if (found != null) {
            return found;
        }
        final String created = client.createFolder(
                LIBRARY_FOLDER_NAME, null, Map.of(LIBRARY_TAG_KEY, LIBRARY_TAG_VALUE));
        WorldShareMod.LOGGER.info("setup: created the '{}' library folder ({})",
                LIBRARY_FOLDER_NAME, created);
        return created;
    }

    /** A folder of this exact name directly inside {@code parentId}, or null. */
    private static String findChildFolder(final DriveClient client,
                                          final String parentId,
                                          final String name) throws IOException {
        for (final File child : client.listFolderChildren(parentId)) {
            if (name.equals(child.getName())
                    && DriveClient.MIME_TYPE_FOLDER.equals(child.getMimeType())) {
                return child.getId();
            }
        }
        return null;
    }

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
     * Pull a Drive folder ID out of whatever the host pasted to their friend.
     *
     * <p>Accepts a bare ID or a full folder URL, because the natural thing for a
     * host to send is the address bar of the folder they just shared.
     *
     * @return the folder ID, or null if the input doesn't contain a plausible one
     */
    public static String extractFolderId(final String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        final int folders = s.indexOf("/folders/");
        if (folders >= 0) {
            s = s.substring(folders + "/folders/".length());
        }
        final int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        final int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        if (!s.matches("[A-Za-z0-9_\\-]+")) return null;
        // Drive IDs are 25+ characters in practice; anything shorter is a typo or a
        // fragment of something else, and passing it to the Picker just yields an
        // empty screen with no explanation.
        if (s.length() < 25) return null;
        return s;
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
