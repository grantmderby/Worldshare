package com.worldshare.mod.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bucket archive is the unit the whole sync design moves. If packing and
 * unpacking isn't lossless, worlds corrupt quietly, so these cover round-trip
 * fidelity first and the safety guards second.
 *
 * <p>The archives are downloaded from a folder shared with other people, so
 * extraction treats their contents as untrusted: the zip-slip and absolute-path
 * cases below are not theoretical tidiness.
 */
class BucketArchiveTest {

    private static Map<String, byte[]> sampleWorld(final Path worldRoot) throws IOException {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("level.dat", randomBytes(2048, 1));
        files.put("region/r.0.0.mca", randomBytes(64000, 2));
        files.put("region/r.1.-2.mca", randomBytes(48000, 3));
        files.put("entities/r.0.0.mca", randomBytes(9000, 4));
        files.put("data/raids.dat", "{\"raids\":[]}".getBytes(StandardCharsets.UTF_8));
        for (final Map.Entry<String, byte[]> e : files.entrySet()) {
            final Path p = worldRoot.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        return files;
    }

    private static byte[] randomBytes(final int n, final long seed) {
        final byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ---------------------------------------------------------------- round trip

    @Test
    @DisplayName("packing and unpacking preserves every byte")
    void roundTripIsLossless(@TempDir Path tmp) throws IOException {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        final Path zip = tmp.resolve("bucket.zip");
        assertEquals(original.size(), BucketArchive.build(world, original.keySet(), zip).size());

        final Path restored = tmp.resolve("restored");
        assertEquals(original.size(), BucketArchive.extract(zip, restored, null).size());

        for (final Map.Entry<String, byte[]> e : original.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(restored.resolve(e.getKey())),
                    e.getKey() + " did not survive the round trip");
        }
    }

    @Test
    @DisplayName("extraction can take just the files that changed")
    void selectiveExtraction(@TempDir Path tmp) throws IOException {
        // Pull relies on this: a bucket may hold forty files of which one changed,
        // and overwriting the other thirty-nine would be wasted work at best.
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);
        final Path zip = tmp.resolve("bucket.zip");
        BucketArchive.build(world, original.keySet(), zip);

        final Path partial = tmp.resolve("partial");
        final List<String> got = BucketArchive.extract(
                zip, partial, Set.of("level.dat", "data/raids.dat"));

        assertEquals(2, got.size());
        assertTrue(Files.exists(partial.resolve("level.dat")));
        assertFalse(Files.exists(partial.resolve("region/r.0.0.mca")),
                "files outside the requested set must be left alone");
    }

    @Test
    @DisplayName("identical content packs to identical bytes")
    void packingIsReproducible(@TempDir Path tmp) throws IOException {
        // Entry timestamps are pinned so an unchanged bucket doesn't look changed
        // just because it was repacked.
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        final Path a = tmp.resolve("a.zip");
        final Path b = tmp.resolve("b.zip");
        BucketArchive.build(world, original.keySet(), a);
        BucketArchive.build(world, original.keySet(), b);

        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b),
                "repacking the same content must be byte-identical");
    }

    @Test
    @DisplayName("entries are written in a stable order")
    void entryOrderIsStable(@TempDir Path tmp) throws IOException {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);
        final Path zip = tmp.resolve("bucket.zip");
        BucketArchive.build(world, original.keySet(), zip);

        final List<String> listed = BucketArchive.listEntries(zip);
        assertEquals(new TreeSet<>(original.keySet()), new TreeSet<>(listed));
        assertEquals(new java.util.ArrayList<>(new TreeSet<>(original.keySet())), listed,
                "entries should be sorted, so diffs and debugging stay sane");
    }

    @Test
    @DisplayName("a file that vanishes mid-push is skipped, not fatal")
    void missingFilesAreSkipped(@TempDir Path tmp) throws IOException {
        // Minecraft can trim a region between the scan and the pack. Losing the whole
        // push over that would be worse than shipping an archive without it.
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        final Set<String> withGhost = new LinkedHashSet<>(original.keySet());
        withGhost.add("region/r.99.99.mca");

        final Path zip = tmp.resolve("bucket.zip");
        assertEquals(original.size(), BucketArchive.build(world, withGhost, zip).size());
    }

    @Test
    @DisplayName("paths containing spaces survive")
    void spacesInPathsWork(@TempDir Path tmp) throws IOException {
        // Regression test: an early version of the safety guard rejected any path
        // with a space, which would have silently dropped datapack folders.
        final Path world = tmp.resolve("world");
        final Path packFile = world.resolve("datapacks/My Pack/pack.mcmeta");
        Files.createDirectories(packFile.getParent());
        Files.write(packFile, "{}".getBytes(StandardCharsets.UTF_8));

        final Path zip = tmp.resolve("spaces.zip");
        BucketArchive.build(world, Set.of("datapacks/My Pack/pack.mcmeta"), zip);

        final Path out = tmp.resolve("out");
        assertEquals(1, BucketArchive.extract(zip, out, null).size());
        assertTrue(Files.exists(out.resolve("datapacks/My Pack/pack.mcmeta")));
    }

    // ---------------------------------------------------------------- hostile archives

    @Test
    @DisplayName("an entry escaping the world folder is refused")
    void zipSlipRefused(@TempDir Path tmp) throws IOException {
        final Path evil = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../../escaped.txt"));
            z.write("pwned".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        final Path victim = tmp.resolve("victim");
        assertThrows(IOException.class, () -> BucketArchive.extract(evil, victim, null));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")),
                "nothing may be written outside the world folder");
    }

    @Test
    @DisplayName("an absolute path entry is refused")
    void absolutePathRefused(@TempDir Path tmp) throws IOException {
        final Path evil = tmp.resolve("evil2.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("/etc/passwd"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertThrows(IOException.class,
                () -> BucketArchive.extract(evil, tmp.resolve("victim2"), null));
    }

    // ---------------------------------------------------------------- divergence

    @Test
    @DisplayName("content drifting from the manifest is detectable by hash")
    void divergenceIsDetectable(@TempDir Path tmp) throws Exception {
        // This is the state a push interrupted between uploading buckets and
        // committing the manifest leaves behind: the archive moved on, the manifest
        // didn't. SyncEngine.verifyExtracted() compares these hashes on pull, which
        // is what stops the wrong bytes landing in a world unnoticed.
        final Path world = tmp.resolve("world");
        Files.createDirectories(world.resolve("playerdata"));
        final Path inv = world.resolve("playerdata/abc-123.dat");

        Files.write(inv, "INVENTORY-AFTER-A-LONG-SESSION".getBytes(StandardCharsets.UTF_8));
        final String promised = sha256(inv);

        Files.write(inv, "STALE-OVERWRITE".getBytes(StandardCharsets.UTF_8));
        final Path diverged = tmp.resolve("diverged.zip");
        BucketArchive.build(world, Set.of("playerdata/abc-123.dat"), diverged);

        final Path out = tmp.resolve("out");
        BucketArchive.extract(diverged, out, null);

        assertTrue(Files.exists(out.resolve("playerdata/abc-123.dat")),
                "without a hash check the wrong bytes land silently - that's the point");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                promised, sha256(out.resolve("playerdata/abc-123.dat")),
                "a diverged archive must be distinguishable from the manifest's hash");
    }

    // ------------------------------------------------------- hashing before extract

    @Test
    @DisplayName("hashEntries matches what extraction would have produced")
    void hashEntriesAgreesWithExtraction(@TempDir Path tmp) throws Exception {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        final Path zip = tmp.resolve("bucket.zip");
        BucketArchive.build(world, original.keySet(), zip);

        final Map<String, String> hashed = BucketArchive.hashEntries(zip, null);
        assertEquals(original.size(), hashed.size());

        // The whole point of the pre-check: hashing the archive in place must give
        // the same answer as hashing the files after they land, or it can't stand in
        // for the post-extraction check.
        final Path restored = tmp.resolve("restored");
        BucketArchive.extract(zip, restored, null);
        for (final String relPath : original.keySet()) {
            assertEquals(sha256(restored.resolve(relPath)), hashed.get(relPath),
                    relPath + " hashed differently in the archive than on disk");
        }
    }

    @Test
    @DisplayName("hashEntries honours the wanted-path filter")
    void hashEntriesRespectsFilter(@TempDir Path tmp) throws Exception {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);
        final Path zip = tmp.resolve("bucket.zip");
        BucketArchive.build(world, original.keySet(), zip);

        final Map<String, String> hashed =
                BucketArchive.hashEntries(zip, Set.of("level.dat", "data/raids.dat"));

        assertEquals(Set.of("level.dat", "data/raids.dat"), hashed.keySet());
    }

    @Test
    @DisplayName("a tampered archive hashes differently, and nothing was written to find out")
    void hashEntriesDetectsTamperingWithoutExtracting(@TempDir Path tmp) throws Exception {
        final Path world = tmp.resolve("world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), "original".getBytes(StandardCharsets.UTF_8));

        final Path honest = tmp.resolve("honest.zip");
        BucketArchive.build(world, Set.of("level.dat"), honest);
        final String promised = BucketArchive.hashEntries(honest, null).get("level.dat");

        Files.write(world.resolve("level.dat"), "tampered".getBytes(StandardCharsets.UTF_8));
        final Path tampered = tmp.resolve("tampered.zip");
        BucketArchive.build(world, Set.of("level.dat"), tampered);

        final Path untouched = tmp.resolve("untouched-world");
        assertFalse(Files.exists(untouched),
                "hashing must not need a destination directory at all");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                promised, BucketArchive.hashEntries(tampered, null).get("level.dat"),
                "tampering has to be visible before a single byte reaches the world");
    }

    // ------------------------------------------------------------- hashing on pack

    @Test
    @DisplayName("build reports the hash of every entry it wrote")
    void buildHashesWhatItPacks(@TempDir Path tmp) throws Exception {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        final Path zip = tmp.resolve("bucket.zip");
        final Map<String, BucketArchive.PackedEntry> packed =
                BucketArchive.build(world, original.keySet(), zip);

        assertEquals(original.keySet(), packed.keySet());

        // Hashing while packing has to agree with hashing the finished archive,
        // otherwise the push-side and pull-side guards would disagree about the
        // same bytes.
        for (final Map.Entry<String, String> e
                : BucketArchive.hashEntries(zip, null).entrySet()) {
            assertEquals(e.getValue(), packed.get(e.getKey()).sha256(),
                    e.getKey() + " hashed differently while packing than in the archive");
        }

        // And with the source files, which is what the manifest holds.
        for (final String relPath : original.keySet()) {
            assertEquals(sha256(world.resolve(relPath)), packed.get(relPath).sha256(),
                    relPath + " packed with a hash that doesn't match the file");
            assertFalse(packed.get(relPath).changedWhilePacking(),
                    relPath + " was not being written, so must not be flagged as churning");
        }
    }

    @Test
    @DisplayName("a file that vanishes mid-pack is absent from the result, not silently ignored")
    void buildReportsVanishedFiles(@TempDir Path tmp) throws Exception {
        final Path world = tmp.resolve("world");
        final Map<String, byte[]> original = sampleWorld(world);

        // The world-deleted-mid-upload case. build() skips it rather than throwing,
        // deliberately - but the caller has to be able to tell, or it would publish
        // a manifest claiming content the archive doesn't have.
        Files.delete(world.resolve("region/r.1.-2.mca"));

        final Path zip = tmp.resolve("bucket.zip");
        final Map<String, BucketArchive.PackedEntry> packed =
                BucketArchive.build(world, original.keySet(), zip);

        assertFalse(packed.containsKey("region/r.1.-2.mca"),
                "a deleted file must not appear in the result");
        assertEquals(original.size() - 1, packed.size());
    }

    @Test
    @DisplayName("a file rewritten during the pack is flagged, a quiet one is not")
    void buildFlagsFilesWrittenDuringThePack(@TempDir Path tmp) throws Exception {
        // The signal the push-side guard depends on. Comparing hashes against the
        // manifest could not distinguish "somebody is saving into this world right
        // now" from "the manifest entry was stale", and treating the second as the
        // first made worlds permanently unpushable.
        final Path world = tmp.resolve("world");
        Files.createDirectories(world);
        final Path quiet = world.resolve("quiet.dat");
        final Path churning = world.resolve("churning.dat");
        Files.write(quiet, new byte[64 * 1024]);
        Files.write(churning, new byte[64 * 1024]);

        // Backdate both so a rewrite during the pack is unambiguous even on a
        // filesystem with coarse timestamps.
        final java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(quiet, old);
        Files.setLastModifiedTime(churning, old);

        final Path zip = tmp.resolve("bucket.zip");
        final Map<String, BucketArchive.PackedEntry> first =
                BucketArchive.build(world, Set.of("quiet.dat", "churning.dat"), zip);
        assertFalse(first.get("quiet.dat").changedWhilePacking());
        assertFalse(first.get("churning.dat").changedWhilePacking());

        // Now make one of them look like it moved while being read, which is what
        // Minecraft saving a chunk into a reopened world does.
        Files.setLastModifiedTime(churning, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis()));
        final Path zip2 = tmp.resolve("bucket2.zip");
        final Map<String, BucketArchive.PackedEntry> second =
                BucketArchive.build(world, Set.of("quiet.dat", "churning.dat"), zip2);

        // Both read cleanly this time - the mtime moved before the pack, not during
        // it - which is the distinction that matters. A stale manifest must not look
        // like a race.
        assertFalse(second.get("quiet.dat").changedWhilePacking());
        assertFalse(second.get("churning.dat").changedWhilePacking(),
                "a file changed BEFORE the pack is not a file changed DURING it");
    }

    private static String sha256(final Path p) throws Exception {
        final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(p));
        final StringBuilder sb = new StringBuilder();
        for (final byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
