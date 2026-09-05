package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A stable per-machine identifier, used by the lock system to determine
 * "is this lock ours?" across Minecraft launches.
 *
 * <p>We intentionally don't use the Minecraft player UUID because a single
 * Minecraft account can be used on multiple PCs — we want the lock tied to
 * the installation, so that if Grant plays on his desktop, his laptop doesn't
 * think it already holds the lock.
 *
 * <p><b>It is per-installation, not per-machine, and the difference bit us.</b>
 * This used to derive the ID from the first non-loopback MAC address, which made
 * two game directories on one PC report the same identity. The lock system asks
 * "is this lock ours?" by comparing exactly this value, so two installations on
 * one machine each believed they held the other's lock: one showed the other's
 * live session as {@code LOCKED_BY_US} and offered to resume it, and - far worse -
 * the pre-upload ownership check passed for both, so either could overwrite the
 * other's work with no warning at all.
 *
 * <p>That is not only a development-rig problem. Two people sharing a PC under
 * different Windows accounts, or one person running two instances, hit it exactly
 * the same way.
 *
 * <p>Hardware told us nothing we needed. The ID has to be stable across launches
 * and distinct between installations, and a random UUID persisted next to the rest
 * of the installation's config is both, with no way to collide:
 * <ol>
 *   <li>Read existing ID from {@code config/worldshare/machine_id} if present</li>
 *   <li>Otherwise generate a random UUID and persist it there</li>
 * </ol>
 *
 * <p>Existing installations keep the ID they already have, since step 1 comes
 * first - including MAC-derived ones, which are perfectly good as long as no
 * second installation shares the machine.
 */
public final class MachineId {

    private static final String FILENAME = "machine_id";

    private static volatile String cachedId;

    private MachineId() {
        // utility class
    }

    /**
     * @return the stable machine ID. Safe to call on any thread; result is
     *         cached after first call.
     */
    public static String get() {
        String local = cachedId;
        if (local != null) {
            return local;
        }
        synchronized (MachineId.class) {
            local = cachedId;
            if (local != null) {
                return local;
            }
            local = loadOrGenerate();
            cachedId = local;
            return local;
        }
    }

    private static String loadOrGenerate() {
        final Path path = WorldSharePaths.worldshareConfigDir().resolve(FILENAME);

        // 1. Read existing ID if present
        if (Files.isRegularFile(path)) {
            try {
                final String existing = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    WorldShareMod.LOGGER.debug("Loaded existing machine ID: {}", existing);
                    return existing;
                }
            } catch (final IOException e) {
                WorldShareMod.LOGGER.warn("Could not read {}; regenerating", path, e);
            }
        }

        // 2. Generate one. Random, not derived: see the class note on why deriving
        // from the MAC made two installations on one PC indistinguishable to the
        // lock, and let each of them overwrite the other's work.
        final String id = UUID.randomUUID().toString();
        WorldShareMod.LOGGER.info("Generated machine ID for this installation: {}", id);

        // Persist
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, id, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            WorldShareMod.LOGGER.warn("Could not persist machine ID to {}", path, e);
            // Not fatal - we'll just regenerate on next launch.
        }
        return id;
    }

}
