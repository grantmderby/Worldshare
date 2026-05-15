package com.worldshare.mod.cloud;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.worldshare.mod.WorldShareMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * High-level Google Drive operations the rest of the mod uses.
 *
 * <p>This class deliberately does NOT know anything about Minecraft, worlds, or
 * checksums — it's a boring wrapper that makes the Drive v3 API a little less
 * verbose to call. Business logic (what to sync, when to sync it) lives in
 * higher-level classes that will be added in M2/M3.
 *
 * <p>Every method is synchronous and may block on network. Callers must dispatch
 * onto {@link CloudModule#executor()} to keep the Minecraft main thread responsive.
 *
 * <p>Instances are obtained via {@link CloudModule#driveClient()}, which handles
 * the OAuth lifecycle. Don't construct this directly outside that module.
 */
public final class DriveClient {

    /** MIME type Drive uses to represent folders. */
    public static final String MIME_TYPE_FOLDER = "application/vnd.google-apps.folder";

    /** MIME type used for manifest.json and session.lock uploads. */
    public static final String MIME_TYPE_JSON = "application/json";

    /** Default fields we request from Drive for file metadata — id+name+size+md5+modifiedTime. */
    /**
     * Default fields requested for file metadata. Drive only returns the fields
     * you explicitly ask for via {@code setFields}, so {@code mimeType} MUST be
     * here for {@link #getFileMeta} to be able to distinguish files from folders.
     * Adding fields here is safe; removing requires checking every caller of
     * {@link #getFileMeta}.
     */
    private static final String DEFAULT_FIELDS = "id, name, size, md5Checksum, modifiedTime, mimeType";

    private final Drive drive;

    DriveClient(final Drive drive) {
        this.drive = Objects.requireNonNull(drive);
    }

    /**
     * Build a new DriveClient from an authorized Credential. Package-private so
     * only {@link CloudModule} can do this - enforces the "one DriveClient per
     * session" invariant.
     */
    static DriveClient fromCredential(final com.google.api.client.auth.oauth2.Credential credential)
            throws GeneralSecurityException, IOException {
        final Drive drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("WorldShare")
                .build();
        return new DriveClient(drive);
    }

    // ----- Upload / Download -----

    /**
     * Upload a local file to Drive.
     *
     * @param localPath      the file to upload
     * @param driveFileName  the name the file will have on Drive
     * @param parentFolderId Drive ID of the destination folder, or {@code null}
     *                       to upload to the user's root Drive folder
     * @return the Drive file ID of the newly created file
     */
    public String uploadFile(final Path localPath,
                             final String driveFileName,
                             final String parentFolderId) throws IOException {
        final File metadata = new File();
        metadata.setName(driveFileName);
        if (parentFolderId != null) {
            metadata.setParents(Collections.singletonList(parentFolderId));
        }

        final FileContent content = new FileContent(null, localPath.toFile());

        final File created = drive.files().create(metadata, content)
                .setFields(DEFAULT_FIELDS)
                .execute();

        WorldShareMod.LOGGER.debug("Uploaded {} -> drive file id {}", localPath, created.getId());
        return created.getId();
    }

    /**
     * Replace the contents of an existing Drive file.
     *
     * @param fileId     Drive ID of the file to overwrite
     * @param localPath  local file whose contents become the new remote contents
     */
    public void updateFile(final String fileId, final Path localPath) throws IOException {
        final FileContent content = new FileContent(null, localPath.toFile());
        drive.files().update(fileId, null, content)
                .setFields(DEFAULT_FIELDS)
                .execute();
        WorldShareMod.LOGGER.debug("Updated drive file id {} with {}", fileId, localPath);
    }

    /**
     * Download a Drive file to a local path. Creates parent directories if needed.
     */
    public void downloadFile(final String fileId, final Path localDestination) throws IOException {
        if (localDestination.getParent() != null) {
            Files.createDirectories(localDestination.getParent());
        }
        final com.google.api.client.http.HttpResponse response =
                drive.files().get(fileId).executeMedia();
        try {
            final java.io.InputStream in = response.getContent();
            if (in != null) {
                try (OutputStream out = Files.newOutputStream(localDestination)) {
                    in.transferTo(out);
                }
            }
            // If content is null, localDestination was already created by
            // newOutputStream above (or doesn't exist yet — caller handles that).
        } finally {
            try { response.disconnect(); } catch (final IOException ignored) {}
        }
        WorldShareMod.LOGGER.debug("Downloaded drive file {} -> {}", fileId, localDestination);
    }

    // ----- JSON convenience (for session.lock, manifest.json) -----

    /**
     * Read a small JSON file from Drive as a UTF-8 string. Used for the session
     * lock and world manifest, both of which are a few kilobytes at most.
     */
    public String readText(final String fileId) throws IOException {
        final com.google.api.client.http.HttpResponse response =
                drive.files().get(fileId).executeMedia();
        try {
            final java.io.InputStream in = response.getContent();
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            try { response.disconnect(); } catch (final IOException ignored) {}
        }
    }

    /**
     * Create or overwrite a small JSON/text file on Drive.
     *
     * @param fileId         Drive ID of an existing file to replace, or {@code null}
     *                       to create a new file
     * @param name           desired filename (ignored if {@code fileId} is given)
     * @param parentFolderId Drive folder ID for new file (ignored if {@code fileId} is given)
     * @param content        UTF-8 text content
     * @param mimeType       MIME type, typically {@link #MIME_TYPE_JSON}
     * @return the Drive file ID (same as input if updating, new if creating)
     */
    public String writeText(final String fileId,
                            final String name,
                            final String parentFolderId,
                            final String content,
                            final String mimeType) throws IOException {
        final ByteArrayContent body = ByteArrayContent.fromString(mimeType, content);

        if (fileId != null) {
            drive.files().update(fileId, null, body).setFields("id").execute();
            return fileId;
        } else {
            final File metadata = new File();
            metadata.setName(name);
            metadata.setMimeType(mimeType);
            if (parentFolderId != null) {
                metadata.setParents(Collections.singletonList(parentFolderId));
            }
            final File created = drive.files().create(metadata, body).setFields("id").execute();
            return created.getId();
        }
    }

    // ----- Lookup / management -----

    /**
     * Find a file by name inside a specific folder.
     *
     * @param name           exact file name to match
     * @param parentFolderId Drive folder ID to search within, or {@code null} for root
     * @return Drive file ID of the first match, or {@code null} if none found
     */
    public String findFileByName(final String name, final String parentFolderId) throws IOException {
        // Escape single-quotes in the name for Drive's query language.
        final String escaped = name.replace("\\", "\\\\").replace("'", "\\'");
        final StringBuilder q = new StringBuilder();
        q.append("name = '").append(escaped).append("' and trashed = false");
        if (parentFolderId != null) {
            q.append(" and '").append(parentFolderId).append("' in parents");
        }

        final FileList result = drive.files().list()
                .setQ(q.toString())
                .setFields("files(id, name)")
                .setPageSize(1)
                .execute();

        final List<File> files = result.getFiles();
        return (files == null || files.isEmpty()) ? null : files.get(0).getId();
    }

    /**
     * Delete a file by ID. Moves it to trash in most Drive UIs.
     */
    public void deleteFile(final String fileId) throws IOException {
        drive.files().delete(fileId).execute();
        WorldShareMod.LOGGER.debug("Deleted drive file id {}", fileId);
    }

    /**
     * Fetch metadata for a single file. Useful to check existence and size
     * without downloading the contents.
     *
     * @return the Drive File object, or {@code null} if not found / not accessible
     */
    public File getFileMeta(final String fileId) throws IOException {
        try {
            return drive.files().get(fileId).setFields(DEFAULT_FIELDS).execute();
        } catch (final com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Create a folder on Drive.
     *
     * @param name           the folder name
     * @param parentFolderId Drive ID of the parent folder, or {@code null} to create at root
     * @return Drive ID of the new folder
     */
    public String createFolder(final String name, final String parentFolderId) throws IOException {
        final File metadata = new File();
        metadata.setName(name);
        metadata.setMimeType(MIME_TYPE_FOLDER);
        if (parentFolderId != null) {
            metadata.setParents(Collections.singletonList(parentFolderId));
        }
        final File created = drive.files().create(metadata)
                .setFields("id")
                .execute();
        WorldShareMod.LOGGER.debug("Created drive folder '{}' (id {}) in parent {}",
                name, created.getId(), parentFolderId);
        return created.getId();
    }

    /**
     * Rename an existing file or folder on Drive.
     */
    public void renameFile(final String fileId, final String newName) throws IOException {
        final File patch = new File();
        patch.setName(newName);
        drive.files().update(fileId, patch).setFields("id, name").execute();
        WorldShareMod.LOGGER.debug("Renamed drive file id {} -> {}", fileId, newName);
    }
}
