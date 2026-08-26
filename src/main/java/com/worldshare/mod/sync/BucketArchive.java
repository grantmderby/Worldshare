package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Packs a bucket's share of a world into a single zip, and unpacks it again.
 *
 * <p>A bucket archive is always written <em>whole</em>. Drive has no partial
 * upload - {@code files.update()} replaces a file's entire content - so there is
 * no such thing as appending one changed chunk to an existing archive. Every
 * rebuild therefore has to include every file currently assigned to that bucket,
 * not just the ones that changed. A pleasant side effect: deletions need no
 * special handling at all, because a file that no longer exists locally simply
 * doesn't get written into the new archive.
 *
 * <p><b>Compression level.</b> These archives are mostly {@code .mca} region files
 * and NBT data, which Minecraft has already compressed internally (zlib per chunk,
 * gzip for {@code level.dat}). Running deflate over that again buys almost
 * nothing and costs real CPU on a machine that is also running the game, so this
 * uses {@link Deflater#BEST_SPEED}. The small plain-JSON files - stats,
 * advancements - still compress fine at that level.
 */
public final class BucketArchive {

    /**
     * Cap on a single entry's uncompressed size when extracting: 512 MB.
     *
     * <p>Guards against a zip bomb in a downloaded archive. No legitimate file in
     * a Minecraft world comes close - a fully-populated region file is a few tens
     * of megabytes - so anything past this is either corruption or an attack, and
     * both deserve the same refusal.
     */
    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;

    private BucketArchive() {
        // utility class
    }

    /**
     * Write the given world files into a zip.
     *
     * <p>Paths that no longer exist on disk are skipped rather than failing the
     * build: a world file can legitimately vanish between the scan and the pack
     * (Minecraft trimming an empty region, say), and losing the whole push over
     * that would be worse than shipping an archive without it.
     *
     * @param worldRoot the local world folder that {@code relPaths} are relative to
     * @param relPaths  forward-slash relative paths to include
     * @param destZip   file to create; overwritten if it already exists
     * @return the relative paths actually written, sorted, so the caller can
     *         reconcile against what it asked for
     */
    public static List<String> build(final Path worldRoot,
                                     final Collection<String> relPaths,
                                     final Path destZip) throws IOException {
        if (destZip.getParent() != null) {
            Files.createDirectories(destZip.getParent());
        }

        // Sorted for determinism: two machines packing identical content produce
        // byte-identical entry ordering, which keeps diffs and debugging sane.
        final Set<String> ordered = new TreeSet<>(relPaths);
        final List<String> written = new ArrayList<>(ordered.size());

        try (OutputStream fileOut = Files.newOutputStream(destZip);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            zip.setLevel(Deflater.BEST_SPEED);

            for (final String relPath : ordered) {
                final Path source = worldRoot.resolve(relPath);
                if (!Files.isRegularFile(source)) {
                    WorldShareMod.LOGGER.debug(
                            "BucketArchive.build: skipping missing file {}", relPath);
                    continue;
                }

                final ZipEntry entry = new ZipEntry(relPath);
                // Fixed timestamp so an unchanged bucket packs to identical bytes
                // rather than differing only by mtime. Makes "did this actually
                // change?" answerable by hashing the archive.
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(source, zip);
                zip.closeEntry();
                written.add(relPath);
            }
        }

        WorldShareMod.LOGGER.debug("BucketArchive.build: {} -> {} entries, {} bytes",
                destZip.getFileName(), written.size(), Files.size(destZip));
        return written;
    }

    /**
     * Extract an archive into a world folder.
     *
     * <p>Entries are written to a temporary file and then moved into place, so a
     * failure partway through a large region file can't leave a half-written
     * {@code .mca} that Minecraft would refuse to load.
     *
     * @param zipFile   archive to read
     * @param worldRoot destination world folder
     * @param only      if non-null, extract only these relative paths and ignore
     *                  the rest of the archive; if null, extract everything
     * @return the relative paths actually extracted
     * @throws IOException on read failure, or if the archive contains an entry
     *                     that tries to escape {@code worldRoot}
     */
    public static List<String> extract(final Path zipFile,
                                       final Path worldRoot,
                                       final Set<String> only) throws IOException {
        Files.createDirectories(worldRoot);
        final Path worldRootReal = worldRoot.toAbsolutePath().normalize();
        final List<String> extracted = new ArrayList<>();

        try (InputStream fileIn = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(fileIn)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                final String relPath = entry.getName().replace('\\', '/');
                if (only != null && !only.contains(relPath)) {
                    zip.closeEntry();
                    continue;
                }

                final Path destination = resolveSafely(worldRootReal, relPath);
                if (destination.getParent() != null) {
                    Files.createDirectories(destination.getParent());
                }

                // Write beside the target so the atomic move stays on one filesystem.
                final Path temp = Files.createTempFile(
                        destination.getParent(), ".worldshare-", ".part");
                try {
                    final long bytes = copyBounded(zip, temp);
                    if (bytes > MAX_ENTRY_BYTES) {
                        throw new IOException("Archive entry '" + relPath + "' exceeds the "
                                + MAX_ENTRY_BYTES + " byte limit; refusing to extract");
                    }
                    moveIntoPlace(temp, destination);
                    extracted.add(relPath);
                } finally {
                    Files.deleteIfExists(temp);
                }
                zip.closeEntry();
            }
        }

        WorldShareMod.LOGGER.debug("BucketArchive.extract: {} -> {} entries into {}",
                zipFile.getFileName(), extracted.size(), worldRoot);
        return extracted;
    }

    /**
     * List an archive's entry paths without extracting anything. Used to work out
     * what a remote bucket currently holds.
     */
    public static List<String> listEntries(final Path zipFile) throws IOException {
        final List<String> names = new ArrayList<>();
        try (InputStream fileIn = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName().replace('\\', '/'));
                }
                zip.closeEntry();
            }
        }
        return names;
    }

    // -----------------------------------------------------------------

    /**
     * Resolve an archive entry name against the world root, refusing anything that
     * would land outside it.
     *
     * <p>This is the "zip slip" guard. Bucket archives are downloaded from a
     * shared Drive folder, so their contents are only as trustworthy as everyone
     * the world was shared with - an entry named {@code ../../.minecraft/mods/x.jar}
     * must not be able to write there.
     */
    private static Path resolveSafely(final Path worldRootReal, final String relPath)
            throws IOException {
        // Reject absolute paths outright - both POSIX-style and a Windows drive
        // prefix, which normalize() below would otherwise resolve away from the
        // world root. Ordinary characters such as spaces are fine; world paths
        // legitimately contain them.
        if (relPath.isEmpty()
                || relPath.startsWith("/")
                || relPath.matches("^[A-Za-z]:.*")) {
            throw new IOException("Refusing archive entry with unsafe name: '" + relPath + "'");
        }
        final Path resolved = worldRootReal.resolve(relPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(worldRootReal)) {
            throw new IOException("Refusing archive entry that escapes the world folder: '"
                    + relPath + "'");
        }
        return resolved;
    }

    /**
     * Copy the current zip entry to a file, stopping once it becomes clear the
     * entry is implausibly large.
     *
     * @return total bytes copied; a value above {@link #MAX_ENTRY_BYTES} means the
     *         caller should reject the entry
     */
    private static long copyBounded(final ZipInputStream zip, final Path destination)
            throws IOException {
        long total = 0L;
        final byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(destination)) {
            int read;
            while ((read = zip.read(buffer)) > 0) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    return total;
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * Move the staged file over the destination, preferring an atomic move.
     *
     * <p>Windows can refuse {@code ATOMIC_MOVE} when the destination exists or is
     * held open by another handle, which is a real possibility for world files. The
     * non-atomic replace is the fallback rather than the default, since it's the
     * one that can leave a partial file behind if it fails midway.
     */
    private static void moveIntoPlace(final Path temp, final Path destination)
            throws IOException {
        try {
            Files.move(temp, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicFailed) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
