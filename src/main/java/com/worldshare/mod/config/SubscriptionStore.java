package com.worldshare.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.util.WorldSharePaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the user's list of subscribed Contributor Worlds, persisted at
 * {@code config/worldshare/subscriptions.json}.
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
            subscriptions.add(new WorldSubscription(
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
     * Find a subscription by Drive folder ID.
     *
     * @return the subscription, or {@code null} if not found
     */
    public synchronized WorldSubscription findByFolderId(final String folderId) {
        for (final WorldSubscription s : subscriptions) {
            if (s.driveFolderId.equals(folderId)) return s;
        }
        return null;
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
     * Add a new subscription. No-op if a subscription with the same Drive
     * folder ID already exists.
     *
     * @return the new or existing subscription
     */
    public synchronized WorldSubscription subscribe(final String driveFolderId,
                                                    final String displayName) {
        for (final WorldSubscription s : subscriptions) {
            if (s.driveFolderId.equals(driveFolderId)) {
                WorldShareMod.LOGGER.debug(
                        "SubscriptionStore: already subscribed to {}", driveFolderId);
                if (displayName != null && !displayName.equals(s.displayName)) {
                    s.displayName = displayName;
                    flush();
                }
                return s;
            }
        }
        final WorldSubscription sub = new WorldSubscription(driveFolderId, displayName, null);
        subscriptions.add(sub);
        flush();
        WorldShareMod.LOGGER.info("SubscriptionStore: subscribed to '{}' ({})",
                displayName, driveFolderId);
        return sub;
    }

    /**
     * Remove a subscription by Drive folder ID.
     *
     * @return true if it was found and removed
     */
    public synchronized boolean unsubscribe(final String driveFolderId) {
        final boolean removed = subscriptions.removeIf(
                s -> s.driveFolderId.equals(driveFolderId));
        if (removed) {
            flush();
            WorldShareMod.LOGGER.info(
                    "SubscriptionStore: unsubscribed from {}", driveFolderId);
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
    public synchronized void linkWorldToFolder(final Path localWorldRoot,
                                               final String localFolderName,
                                               final String driveFolderId,
                                               final String displayName) throws IOException {
        WorldSubscription sub = findByFolderId(driveFolderId);
        if (sub == null) {
            sub = new WorldSubscription(driveFolderId, displayName, localFolderName);
            subscriptions.add(sub);
        } else {
            sub.localFolderName = localFolderName;
            if (displayName != null && !displayName.isBlank()) {
                sub.displayName = displayName;
            }
        }
        flush();

        WorldLink.write(localWorldRoot, driveFolderId, sub.displayName);

        WorldShareMod.LOGGER.info(
                "SubscriptionStore: linked '{}' (local folder: '{}') -> Drive folder {}",
                sub.displayName, localFolderName, driveFolderId);
    }

    /**
     * Update the display name of a subscribed world.
     */
    public synchronized void rename(final String driveFolderId, final String newName) {
        for (final WorldSubscription s : subscriptions) {
            if (s.driveFolderId.equals(driveFolderId)) {
                s.displayName = newName;
                flush();
                return;
            }
        }
    }

    // ----- Disk I/O -----

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