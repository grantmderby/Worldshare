package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.UUID;

/**
 * A stable per-machine identifier, used by the lock system to determine
 * "is this lock ours?" across Minecraft launches.
 *
 * <p>We intentionally don't use the Minecraft player UUID because a single
 * Minecraft account can be used on multiple PCs — we want the lock tied to
 * the actual physical machine, so that if Grant plays on his desktop, his
 * laptop doesn't think it already holds the lock.
 *
 * <p>Generation strategy (tries in order):
 * <ol>
 *   <li>Read existing ID from {@code config/worldshare/machine_id} if present</li>
 *   <li>Derive from the first non-loopback MAC address on the system</li>
 *   <li>Fall back to a random UUID</li>
 * </ol>
 * The chosen ID is always persisted to {@code machine_id} so subsequent
 * launches read the same value.
 *
 * <p>Note: the MAC-based ID is NOT the raw MAC — it's hashed to a UUID-like
 * form so the file can be safely included in diagnostics/bug reports without
 * exposing network identifiers.
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

        // 2. Derive from MAC address
        String id = deriveFromMac();

        // 3. Fallback: random UUID
        if (id == null) {
            id = UUID.randomUUID().toString();
            WorldShareMod.LOGGER.info("Generated random machine ID (no MAC available): {}", id);
        } else {
            WorldShareMod.LOGGER.info("Derived machine ID from MAC: {}", id);
        }

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

    /**
     * Build a stable ID by hashing the first usable MAC address on the system,
     * then formatting as a UUID so it's safe to share in logs/reports.
     *
     * @return a deterministic UUID-string, or null if no MAC was available
     */
    private static String deriveFromMac() {
        try {
            final Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                final NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                final byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    final String hex = SHA256Util.hashBytes(mac);
                    // Format first 32 hex chars as a UUID for readability.
                    return hex.substring(0, 8) + "-"
                            + hex.substring(8, 12) + "-"
                            + hex.substring(12, 16) + "-"
                            + hex.substring(16, 20) + "-"
                            + hex.substring(20, 32);
                }
            }
        } catch (final SocketException e) {
            WorldShareMod.LOGGER.warn("Could not enumerate network interfaces", e);
        }
        return null;
    }
}
