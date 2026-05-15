package com.worldshare.mod.modmanager;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modpack management entry points.
 *
 * <p>Two main operations:
 * <ul>
 *   <li>{@link #generateAndUpload(String)} — host scans local mods, resolves
 *       Modrinth URLs, and writes {@code modpack.json} to Drive.</li>
 *   <li>{@link #checkGuestMissingMods(String)} — guest reads Drive's
 *       {@code modpack.json} and returns mods they don't have locally.</li>
 * </ul>
 */
public final class ModManagerModule {

    public static final String MODPACK_FILENAME = "modpack.json";
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String LOADER = "neoforge";

    private ModManagerModule() {}

    public static void init() {
        WorldShareMod.LOGGER.info("ModManagerModule initialized.");
    }

    /**
     * Host flow: scan local mods, resolve Modrinth download URLs, write to Drive.
     *
     * @param driveFolderId target Drive folder
     * @return summary stats
     */
    public static GenerateResult generateAndUpload(final String driveFolderId)
            throws IOException, InterruptedException {
        final List<LocalModScanner.ScannedMod> scanned = LocalModScanner.scanAll();

        if (scanned.isEmpty()) {
            WorldShareMod.LOGGER.info(
                    "ModManager: no eligible mods to publish (dev environment?)");
            return new GenerateResult(0, 0, 0);
        }

        // Bulk lookup all SHA-1s in one Modrinth call.
        final List<String> hashes = new ArrayList<>();
        for (final LocalModScanner.ScannedMod m : scanned) hashes.add(m.sha1);

        final Map<String, ModrinthClient.HashMatch> matches =
                ModrinthClient.bulkLookup(hashes);

        // Build manifest.
        final ModpackManifest manifest = new ModpackManifest();
        manifest.minecraft_version = MINECRAFT_VERSION;
        manifest.loader = LOADER;
        manifest.generated_at = Instant.now().toString();

        int auto = 0;
        int manual = 0;
        for (final LocalModScanner.ScannedMod m : scanned) {
            final ModrinthClient.HashMatch match = matches.get(m.sha1);
            final String url = match != null ? match.downloadUrl : null;
            if (url != null) auto++; else manual++;

            manifest.mods.add(new ModpackManifest.ModEntry(
                    m.modId, m.displayName, m.version, m.sha1, m.filename, url));
        }

        // Write to Drive (overwrite if exists).
        final DriveClient client = CloudModule.driveClient();
        final String existingId = client.findFileByName(MODPACK_FILENAME, driveFolderId);
        client.writeText(
                existingId,
                MODPACK_FILENAME,
                driveFolderId,
                manifest.toJson(),
                DriveClient.MIME_TYPE_JSON);

        WorldShareMod.LOGGER.info(
                "ModManager: published modpack.json - {} total mods, {} auto-installable, {} manual",
                scanned.size(), auto, manual);
        return new GenerateResult(scanned.size(), auto, manual);
    }

    /**
     * Guest flow: read Drive's modpack.json, return mods we don't have locally.
     * Returns empty list if no modpack.json exists.
     */
    public static ModCheckResult checkGuestMissingMods(final String driveFolderId)
            throws IOException {
        final DriveClient client = CloudModule.driveClient();
        final String fileId = client.findFileByName(MODPACK_FILENAME, driveFolderId);
        if (fileId == null) return new ModCheckResult(List.of(), List.of());

        final String json = client.readText(fileId);
        final ModpackManifest manifest = ModpackManifest.fromJson(json);
        if (manifest == null || manifest.mods == null) return new ModCheckResult(List.of(), List.of());

        // Hash local mods for comparison.
        final List<LocalModScanner.ScannedMod> localMods = LocalModScanner.scanAll();
        final Set<String> localModIds = new HashSet<>();
        final Set<String> localHashes = new HashSet<>();
        for (final LocalModScanner.ScannedMod m : localMods) {
            localModIds.add(m.modId);
            localHashes.add(m.sha1);
        }

        // Missing if mod ID not present locally. (Different version of an
        // installed mod is shown as informational only - we can't replace
        // running jars on Windows.)
        // Classify each required mod as missing, wrong version, or fine.
        final List<ModpackManifest.ModEntry> missing = new ArrayList<>();
        final List<ModpackManifest.ModEntry> wrongVersion = new ArrayList<>();

        for (final ModpackManifest.ModEntry mod : manifest.mods) {
            if (localHashes.contains(mod.sha1)) {
                // Exact hash match — correct version installed.
                continue;
            }
            if (!localModIds.contains(mod.mod_id)) {
                // Not installed at all.
                missing.add(mod);
            } else {
                // Mod ID present but hash doesn't match — wrong version.
                wrongVersion.add(mod);
            }
        }
        return new ModCheckResult(missing, wrongVersion);
    }

    public static final class GenerateResult {
        public final int total;
        public final int autoInstallable;
        public final int manualInstall;

        GenerateResult(final int total, final int auto, final int manual) {
            this.total = total;
            this.autoInstallable = auto;
            this.manualInstall = manual;
        }
    }

        public static final class ModCheckResult {
        public final List<ModpackManifest.ModEntry> missing;
        public final List<ModpackManifest.ModEntry> wrongVersion;

        ModCheckResult(final List<ModpackManifest.ModEntry> missing,
                       final List<ModpackManifest.ModEntry> wrongVersion) {
            this.missing = missing;
            this.wrongVersion = wrongVersion;
        }

        public boolean hasIssues() {
            return !missing.isEmpty() || !wrongVersion.isEmpty();
        }
    }
}