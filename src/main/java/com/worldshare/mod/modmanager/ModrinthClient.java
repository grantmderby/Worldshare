package com.worldshare.mod.modmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP client for the Modrinth public API.
 *
 * <p>Uses only one endpoint: bulk hash → version lookup, which lets us look up
 * download URLs for many mods in a single request.
 *
 * <p><b>User-Agent is required.</b> Modrinth rejects requests without a
 * uniquely-identifying user agent header, so we always send one.
 */
public final class ModrinthClient {

    private static final String BULK_HASH_URL = "https://api.modrinth.com/v2/version_files";
    private static final String USER_AGENT = "WorldShare/0.1 (github.com/anonymous/worldshare)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private ModrinthClient() {}

    /** One Modrinth version match for a hash. */
    public static final class HashMatch {
        public final String downloadUrl;
        public final String filename;
        public final String versionNumber;

        HashMatch(final String downloadUrl, final String filename, final String versionNumber) {
            this.downloadUrl = downloadUrl;
            this.filename = filename;
            this.versionNumber = versionNumber;
        }
    }

    /**
     * Bulk lookup: send all hashes in one POST, get back hash → match.
     *
     * <p>Hashes that aren't found on Modrinth simply won't appear in the result map.
     *
     * @param sha1Hashes SHA-1 hashes (lowercase hex) of jars to look up
     * @return map of hash → match (only hashes Modrinth recognized)
     */
    public static Map<String, HashMatch> bulkLookup(final List<String> sha1Hashes)
            throws IOException, InterruptedException {
        if (sha1Hashes.isEmpty()) return Map.of();

        final JsonObject body = new JsonObject();
        final JsonArray hashesArray = new JsonArray();
        for (final String h : sha1Hashes) hashesArray.add(h);
        body.add("hashes", hashesArray);
        body.addProperty("algorithm", "sha1");

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BULK_HASH_URL))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        final HttpResponse<String> response = CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Modrinth bulk lookup failed: HTTP "
                    + response.statusCode() + " - " + response.body());
        }

        return parseBulkResponse(response.body());
    }

    /**
     * Parse the bulk response. Schema: {@code {hash: {files: [{url, filename}], version_number, ...}}}.
     */
    private static Map<String, HashMatch> parseBulkResponse(final String json) {
        final Map<String, HashMatch> result = new HashMap<>();
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        for (final Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
            final String hash = entry.getKey();
            try {
                final JsonObject version = entry.getValue().getAsJsonObject();
                final JsonArray files = version.getAsJsonArray("files");
                if (files == null || files.size() == 0) continue;

                // Find primary file, or fall back to first.
                JsonObject chosen = null;
                for (int i = 0; i < files.size(); i++) {
                    final JsonObject f = files.get(i).getAsJsonObject();
                    if (f.has("primary") && f.get("primary").getAsBoolean()) {
                        chosen = f;
                        break;
                    }
                }
                if (chosen == null) chosen = files.get(0).getAsJsonObject();

                final String url = chosen.has("url") ? chosen.get("url").getAsString() : null;
                final String filename = chosen.has("filename")
                        ? chosen.get("filename").getAsString() : null;
                final String versionNumber = version.has("version_number")
                        ? version.get("version_number").getAsString() : null;

                if (url != null) {
                    result.put(hash, new HashMatch(url, filename, versionNumber));
                }
            } catch (final Throwable t) {
                WorldShareMod.LOGGER.debug(
                        "ModrinthClient: skipping malformed entry for hash {}: {}",
                        hash, t.getMessage());
            }
        }
        return result;
    }

    /**
     * Download a file from a Modrinth CDN URL to a local path.
     */
    public static void downloadFile(final String url, final Path destination)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        Files.createDirectories(destination.getParent());
        final Path tmp = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            final HttpResponse<Path> response = CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            try { Files.deleteIfExists(tmp); } catch (final IOException ignored) {}
            throw e;
        }
    }
}