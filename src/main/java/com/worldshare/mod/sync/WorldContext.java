package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the currently-loaded world's filesystem path and the local player's UUID.
 *
 * <p>For Milestone 3 testing, sync commands operate on the world the user has
 * open. This class extracts that "current world" information from Minecraft.
 *
 * <p>If no world is loaded (we're at the title screen), {@link #current()}
 * returns {@code Optional.empty()}. M5 will replace this with a UI-driven
 * mechanism for selecting a Drive-side world to sync from outside any
 * loaded session.
 */
public final class WorldContext {

    public static final class CurrentWorld {
        /** Filesystem path to the world folder (the one containing level.dat). */
        public final Path worldRoot;
        /** Display name of the world (folder name typically). */
        public final String name;
        /** Local player's UUID. Used for per-UUID file filtering by TrackedPaths. */
        public final UUID playerUuid;

        public CurrentWorld(final Path worldRoot, final String name, final UUID playerUuid) {
            this.worldRoot = worldRoot;
            this.name = name;
            this.playerUuid = playerUuid;
        }
    }

    private WorldContext() {
        // utility class
    }

    /**
     * Locate the currently-loaded world.
     *
     * <p>For singleplayer, Minecraft runs an integrated server inside the
     * client process. We derive the world folder from the level.dat path -
     * a stable approach that doesn't depend on any specific
     * {@code LevelResource} constant that might vary between versions.
     */
    public static Optional<CurrentWorld> current() {
        try {
            final Minecraft mc = Minecraft.getInstance();
            final MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                return Optional.empty();
            }

            // LEVEL_DATA_FILE points to <world>/level.dat. The parent of that
            // is the world root. This is the most reliable approach: even if
            // some LevelResource constants change between MC versions,
            // LEVEL_DATA_FILE is core to the save format and very stable.
            final Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
                    .toAbsolutePath().normalize();
            final Path worldRoot = levelDat.getParent();
            if (worldRoot == null) {
                WorldShareMod.LOGGER.warn(
                        "WorldContext: level.dat had no parent directory: {}", levelDat);
                return Optional.empty();
            }

            // Get the UUID of the local player.
            UUID uuid = null;
            if (mc.getUser() != null) {
                try {
                    uuid = mc.getUser().getProfileId();
                } catch (final Throwable t) {
                    // Some user accounts (especially in dev/offline mode) may have
                    // a null profile ID. Fall back to deriving from the username.
                    WorldShareMod.LOGGER.debug(
                            "WorldContext: getProfileId() failed, deriving from name", t);
                }
            }
            if (uuid == null && mc.getUser() != null) {
                final String name = mc.getUser().getName();
                if (name != null) {
                    // Offline UUID derivation - matches how Minecraft itself does it.
                    uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            final String worldName = worldRoot.getFileName() == null
                    ? "(unnamed)" : worldRoot.getFileName().toString();
            return Optional.of(new CurrentWorld(worldRoot, worldName, uuid));
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.warn("WorldContext.current() failed", t);
            return Optional.empty();
        }
    }
}
