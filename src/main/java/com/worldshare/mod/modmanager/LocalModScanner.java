package com.worldshare.mod.modmanager;

import com.worldshare.mod.WorldShareMod;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans the loaded mod list and produces metadata + SHA-1 hashes for each
 * mod's jar file.
 *
 * <p>Uses {@link ModList#getMods()} to get the runtime-loaded mod list rather
 * than scanning {@code mods/} directly — this gives us mod IDs and versions
 * for free, and means we never look at jars that NeoForge rejected.
 *
 * <p>Filtered out:
 * <ul>
 *   <li>Excluded mod IDs ({@link #EXCLUDED_IDS})</li>
 *   <li>Mods loaded from non-jar paths (dev class directories)</li>
 * </ul>
 *
 * <p>NeoForge 1.21.1 API used:
 * <pre>
 *   ModList.get().getMods()                       // List&lt;IModInfo&gt;
 *     .getModId() / .getVersion() / .getDisplayName()
 *     .getOwningFile().getFile().getFilePath()    // Path to .jar
 * </pre>
 *
 * <p><b>Verify in IntelliJ:</b> the {@code getOwningFile().getFile().getFilePath()}
 * chain. If any link in the chain doesn't resolve, autocomplete will show the
 * correct method name.
 */
public final class LocalModScanner {

    /** Mod IDs we never include in modpack.json. */
    public static final Set<String> EXCLUDED_IDS = Set.of(
            "minecraft", "neoforge", "forge", "fml", "worldshare", "e4mc"
    );

    private LocalModScanner() {}

    public static final class ScannedMod {
        public final String modId;
        public final String displayName;
        public final String version;
        public final String sha1;
        public final String filename;
        public final Path jarPath;

        ScannedMod(final String modId, final String displayName, final String version,
                   final String sha1, final String filename, final Path jarPath) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
            this.sha1 = sha1;
            this.filename = filename;
            this.jarPath = jarPath;
        }
    }

    /**
     * Scan all loaded mods and return metadata + SHA-1 for each.
     *
     * @return list of ScannedMod entries (excluded IDs and non-jar paths filtered out)
     */
    public static List<ScannedMod> scanAll() {
        final List<ScannedMod> result = new ArrayList<>();
        for (final IModInfo info : ModList.get().getMods()) {
            final String modId = info.getModId();
            if (EXCLUDED_IDS.contains(modId)) continue;

            final Path jarPath;
            try {
                jarPath = info.getOwningFile().getFile().getFilePath();
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.debug(
                        "LocalModScanner: couldn't get path for '{}' - skipping: {}",
                        modId, t.getMessage());
                continue;
            }

            // Skip mods loaded from class directories (dev environment).
            // Also skip if the path doesn't exist for whatever reason.
            if (jarPath == null
                    || !Files.isRegularFile(jarPath)
                    || !jarPath.toString().endsWith(".jar")) {
                WorldShareMod.LOGGER.debug(
                        "LocalModScanner: skipping non-jar mod '{}' at {}", modId, jarPath);
                continue;
            }

            try {
                final String sha1 = sha1Hex(jarPath);
                final String version = info.getVersion().toString();
                final String displayName = info.getDisplayName();
                final String filename = jarPath.getFileName().toString();
                result.add(new ScannedMod(modId, displayName, version,
                        sha1, filename, jarPath));
            } catch (final IOException e) {
                WorldShareMod.LOGGER.warn(
                        "LocalModScanner: failed to hash {} - skipping: {}",
                        jarPath, e.getMessage());
            }
        }
        WorldShareMod.LOGGER.info("LocalModScanner: scanned {} eligible mod(s)", result.size());
        return result;
    }

    private static String sha1Hex(final Path file) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (final var in = Files.newInputStream(file)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            final byte[] digest = md.digest();
            final StringBuilder sb = new StringBuilder(40);
            for (final byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
    }
}