package com.worldshare.mod.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helper used by the cloud sync engine to detect changed files.
 *
 * <p>This is written defensively: it streams the file rather than loading it
 * into memory, because region files ({@code .mca}) can be tens of megabytes
 * and loading 60+ of them at once would blow up the heap.
 */
public final class SHA256Util {

    private static final int BUFFER_SIZE = 64 * 1024; // 64 KiB - a reasonable file read chunk
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SHA256Util() {
        // utility class
    }

    /**
     * Computes the hex-encoded SHA-256 hash of the file at {@code path}.
     *
     * @param path path to an existing, readable file
     * @return lowercase hex digest (64 chars)
     * @throws IOException if the file can't be read
     */
    public static String hashFile(final Path path) throws IOException {
        final MessageDigest digest = newDigest();
        final byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * Computes the hex-encoded SHA-256 hash of an in-memory byte array.
     * Useful for hashing small things like the serialized manifest.
     */
    public static String hashBytes(final byte[] data) {
        final MessageDigest digest = newDigest();
        digest.update(data);
        return toHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm on every conforming JRE. If this
            // throws we're running on something truly broken and we can't continue.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    private static String toHex(final byte[] bytes) {
        final char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int b = bytes[i] & 0xFF;
            chars[i * 2] = HEX[b >>> 4];
            chars[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(chars);
    }
}
