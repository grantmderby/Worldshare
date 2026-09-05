package com.worldshare.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.util.WorldSharePaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages the user's list of subscribed Contributor Worlds, persisted at
 * {@code config/worldshare/subscriptions.json}.
 *
 * <p>Worlds are keyed by control file ID, not Drive folder ID - see
 * {@link WorldSubscription} for why the identifier had to change.
 *
 * <p>This replaces the single {@code driveFolderId} config value from M0-M4.
 * On first load after the M5 update, if the legacy {@code driveFolderId}
 * is non-empty, it is automatically migrated into this store as the first
 * subscription entry.
 *
 * <p>All mutations are immediately flushed to disk. The in-memory list is the
 * authoritative source; reads of the JSON file are only done at startup.
 *
 * <p><b>M7:</b> If subscriptions.json is corrupted on load, it is renamed to
 * {@code subscriptions.json.corrupted-<timestamp>} and a fresh empty store is
 * started. This prevents the user's entire subscription list from disappearing
 * silently on a JSON parse error.
 *
 * <p><b>Thread safety:</b> mutating methods are {@code synchronized}. Reads
 * from other threads are safe since we return defensive copies.
 */
public final class SubscriptionStore {

    private static final String FILENAME = "subscriptions.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<WorldSubscription>>() {}.getType();

    private static final SubscriptionStore INSTANCE = new SubscriptionStore();

    private final List<WorldSubscription> subscriptions = new ArrayList<>();
    private boolean loaded = false;

    private SubscriptionStore() {}

    public static SubscriptionStore get() {
        return INSTANCE;
    }

    // ----- Lifecycle -----

    /**
     * Load subscriptions from disk. Call once during mod init. Safe to call
     * multiple times — subsequent calls are no-ops if already loaded.
     *
     * @param legacyFolderId the old single-folder config value, used for
     *                       migration if non-empty and the subscription file
     *                       doesn't already exist. Pass null to skip.
     */
    public synchronized void load(final String legacyFolderId) {
        if (loaded) return;
        loaded = true;

        final Path file = storePath();
        if (Files.exists(file)) {
            try {
                final String json = Files.readString(file, StandardCharsets.UTF_8);
                final List<WorldSubscription> loadedList = GSON.fromJson(json, LIST_TYPE);
                if (loadedList != null) {
                    subscriptions.addAll(loadedList);
                }
                WorldShareMod.LOGGER.info(
                        "SubscriptionStore: loaded {} world(s) from {}",
                        subscriptions.size(), file.getFileName());
            } catch (final JsonSyntaxException jse) {
                // M7: corruption recovery. Move corrupted file aside so the
                // user can inspect it manually, and start fresh.
                WorldShareMod.LOGGER.error(
                        "SubscriptionStore: subscriptions.json is corrupted: {}",
                        jse.getMessage());
                try {
                    final Path backup = file.resolveSibling(
                            "subscriptions.json.corrupted-" + System.currentTimeMillis());
                    Files.move(file, backup);
                    WorldShareMod.LOGGER.error(
                            "SubscriptionStore: moved corrupted file to {}. "
                                    + "Starting with empty subscription list.",
                            backup.getFileName());
                } catch (final IOException moveErr) {
                    WorldShareMod.LOGGER.error(
                            "SubscriptionStore: also couldn't back up corrupted file",
                            moveErr);
                }
            } catch (final IOException e) {
                WorldShareMod.LOGGER.error(
                        "SubscriptionStore: failed to read {}: {}", file, e.getMessage());
            }
        } else if (legacyFolderId != null && !legacyFolderId.isBlank()) {
            // First launch after M5 upgrade: migrate the old single-folder config.
            WorldShareMod.LOGGER.info(
                    "SubscriptionStore: migrating legacy driveFolderId '{}' to subscriptions",
                    legacyFolderId);
            // Carried over as a legacy entry: a bare folder ID is unusable under the
            // drive.file scope, so this exists to be shown in the UI with a
            // "re-run setup" prompt rather than to be synced.
            subscriptions.add(WorldSubscription.legacy(
                    legacyFolderId,
                    "Shared World",
                    null
            ));
            flush();
        } else {
            WorldShareMod.LOGGER.info(
                    "SubscriptionStore: no existing subscriptions (fresh start)");
        }
    }

    // ----- Query -----

    /**
     * @return an unmodifiable snapshot of all subscriptions.
     */
    public synchronized List<WorldSubscription> all() {
        return Collections.unmodifiableList(new ArrayList<>(subscriptions));
    }

    /**
     * Find a subscription by its control file ID - the stable identifier for a
     * shared world under the {@code drive.file} scope.
     *
     * @return the subscription, or {@code null} if not found
     */
    public synchronized WorldSubscription findByControlFileId(final String controlFileId) {
        if (controlFileId == null) return null;
        for (final WorldSubscription s : subscriptions) {
            if (controlFileId.equals(s.controlFileId())) return s;
        }
        return null;
    }

    /**
     * Subscriptions carried over from before the {@code drive.file} migration.
     * These can't sync until the player re-runs setup and picks their world's
     * files; the Contributor Worlds screen surfaces them so they aren't silently
     * broken.
     */
    public synchronized List<WorldSubscription> legacyEntries() {
        final List<WorldSubscription> out = new ArrayList<>();
        for (final WorldSubscription s : subscriptions) {
            if (s.isLegacy()) out.add(s);
        }
        return out;
    }

    /**
     * Find a subscription by local folder name.
     *
     * @return the subscription, or {@code null} if not found
     */
    public synchronized WorldSubscription findByLocalFolder(final String localFolderName) {
        for (final WorldSubscription s : subscriptions) {
            if (localFolderName.equals(s.localFolderName)) return s;
        }
        return null;
    }

    public synchronized boolean isEmpty() {
        return subscriptions.isEmpty();
    }

    // ----- Mutation -----

    /**
     * Add a new subscription. If one already exists for the same world, its file
     * set is refreshed rather than duplicated - re-running setup to re-pick files
     * should update the existing entry, not create a second one.
     *
     * @return the new or existing subscription
     */
    public synchronized WorldSubscription subscribe(final RemoteFileSet remote,
                                                    final String displayName) {
        Objects.requireNonNull(remote, "remote");
        final String controlFileId = remote.controlFileId;

        final WorldSubscription existing = findByControlFileId(controlFileId);
        if (existing != null) {
            WorldShareMod.LOGGER.debug(
                    "SubscriptionStore: already subscribed to {}", controlFileId);
            existing.remote = remote;
            if (displayName != null && !displayName.equals(existing.displayName)) {
                existing.displayName = displayName;
            }
            flush();
            return existing;
        }

        final WorldSubscription sub = new WorldSubscription(remote, displayName, null);
        subscriptions.add(sub);
        flush();
        WorldShareMod.LOGGER.info("SubscriptionStore: subscribed to '{}' (control file {})",
                displayName, controlFileId);
        return sub;
    }

    /**
     * Remove a subscription by control file ID.
     *
     * <p>This only forgets the world locally. It deliberately does not touch
     * anything on Drive: the files belong to whoever created them, the other
     * player may still be using them, and under {@code drive.file} a deleted file
     * can never be restored to the same ID anyway.
     *
     * @return true if it was found and removed
     */
    public synchronized boolean unsubscribe(final String controlFileId) {
        if (controlFileId == null) return false;
        final boolean removed = subscriptions.removeIf(
                s -> controlFileId.equals(s.controlFileId()));
        if (removed) {
            flush();
            WorldShareMod.LOGGER.info(
                    "SubscriptionStore: unsubscribed from {}", controlFileId);
        }
        return removed;
    }

    /**
     * Record that the world at {@code localFolderName} corresponds to
     * {@code driveFolderId}. Creates a subscription if one doesn't exist yet.
     * Also writes a {@link WorldLink} file into the local world folder.
     *
     * <p>Called from:
     * <ul>
     *   <li>{@code /worldshare setDriveLink} — host binds their open world</li>
     *   <li>{@link com.worldshare.mod.ui.ContributorWorldsScreen} download flow — guest</li>
     * </ul>
     *
     * @param localWorldRoot absolute path to the local world folder (for writing link file)
     * @param localFolderName just the folder name component (for subscription record)
     */
    public synchronized void linkWorldToRemote(final Path localWorldRoot,
                                               final String localFolderName,
                                               final RemoteFileSet remote,
                                               final String displayName) throws IOException {
        Objects.requireNonNull(remote, "remote");

        WorldSubscription sub = findByControlFileId(remote.controlFileId);
        if (sub == null) {
            sub = new WorldSubscription(remote, displayName, localFolderName);
            subscriptions.add(sub);
        } else {
            sub.remote = remote;
            sub.localFolderName = localFolderName;
            if (displayName != null && !displayName.isBlank()) {
                sub.displayName = displayName;
            }
        }
        flush();

        WorldLink.write(localWorldRoot, remote, sub.displayName);

        WorldShareMod.LOGGER.info(
                "SubscriptionStore: linked '{}' (local folder: '{}') -> control file {}",
                sub.displayName, localFolderName, remote.controlFileId);
    }

    /**
     * Update the display name of a subscribed world.
     */
    public synchronized void rename(final String controlFileId, final String newName) {
        final WorldSubscription sub = findByControlFileId(controlFileId);
        if (sub != null) {
            sub.displayName = newName;
            flush();
        }
    }

    // ----- Disk I/O -----

    /**
     * Persist the store after a caller edited a subscription in place.
     *
     * <p>For incidental corrections rather than deliberate changes - adopting a
     * world's real name once the host has published it, say. {@link #flush} already
     * swallows its own errors, so a failed write here costs nothing but a repeat of
     * the same correction next time.
     */
    public synchronized void flushQuietly() {
        flush();
    }

    private void flush() {
        final Path file = storePath();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(subscriptions), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            WorldShareMod.LOGGER.error("SubscriptionStore: failed to save {}: {}",
                    file, e.getMessage());
        }
    }

    private static Path storePath() {
        return WorldSharePaths.worldshareConfigDir().resolve(FILENAME);
    }
}