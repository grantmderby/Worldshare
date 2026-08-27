package com.worldshare.mod.sync;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a world-relative file path to one of a fixed number of remote archive
 * "buckets".
 *
 * <p><b>Why buckets exist at all.</b> The mod authenticates with Google's
 * {@code drive.file} scope, which grants access only to files the user
 * personally selected through the Picker. A file created later - say, a brand
 * new {@code r.4.-2.mca} that appears the first time somebody walks east - is
 * invisible to the other player's grant until they manually pick it. That makes
 * the old design (one Drive file per world file, created on demand) impossible.
 * Instead the remote side is a <em>fixed, known-in-advance</em> set of files,
 * picked once during setup: one control file plus N bucket archives. World files
 * are distributed among the buckets, and a sync rewrites only the buckets whose
 * contents actually changed.
 *
 * <p><b>The one rule that must never break:</b> a given relative path must map to
 * the same bucket index forever, on every machine, across every mod version. If
 * that stops holding, a file can end up living in two buckets at once and the
 * diff logic silently corrupts the world. Everything about this class - the
 * hand-rolled hash, the explicit {@link Locale#ROOT}, the frozen bucket count -
 * exists to protect that invariant. Do not "improve" the hash function.
 *
 * <p><b>Region grouping.</b> Minecraft stores the same map area across parallel
 * directory trees: {@code region/r.3.-1.mca}, {@code entities/r.3.-1.mca} and
 * {@code poi/r.3.-1.mca} all describe the same 512x512 block square, and they
 * almost always change together. Hashing on the region coordinates rather than
 * the full path lands all three in one bucket, so a session spent in a single
 * corner of the map dirties one bucket instead of three.
 */
public final class BucketLayout {

    /**
     * Default number of bucket archives.
     *
     * <p>This is a tuning knob with real tension on both sides: too few buckets
     * and each one is large, so a one-chunk edit re-uploads a big archive; too
     * many and the joining player has more files to hand-pick during setup, plus
     * more API calls per sync.
     *
     * <p><b>Eight, not sixteen, and the reason is measured rather than guessed.</b>
     * Testing established that a Picker folder grant conveys no access to the
     * folder's contents - not to files added afterwards, and not even to files
     * already sitting in it when it was picked (the folder itself resolves, a
     * listing returns nothing, and every child 404s). So there is no "just pick
     * the folder" shortcut: a joining player must select every remote file by
     * hand, and the count of those is {@code bucketCount + 2}. Eight keeps that
     * to ten selections. See {@code tools/oauth-picker-prototype/preexisting_folder_test.py}
     * for the test and {@code docs/CLOUD_BACKEND_DECISION.md} for the write-up.
     *
     * <p>Changing this value does not migrate existing worlds - the count is
     * frozen per world at setup time and carried in the control file.
     */
    public static final int DEFAULT_BUCKET_COUNT = 8;

    /** Lower bound on bucket count. One bucket is legal - it means "one big archive". */
    public static final int MIN_BUCKET_COUNT = 1;

    /**
     * Upper bound on bucket count. Past this, the one-time Picker selection turns
     * into an unreasonable chore for the player.
     */
    public static final int MAX_BUCKET_COUNT = 64;

    /**
     * Name of the control file on Drive.
     *
     * <p>Prefixed with the mod name because it lands in a folder the user chose,
     * very possibly alongside their own unrelated files, and a bare
     * {@code control.json} would be baffling to find later.
     */
    public static final String CONTROL_FILENAME = "worldshare-control.json";

    /**
     * Name of the live-presence file on Drive.
     *
     * <p>Kept out of the control file on purpose. Presence is rewritten every 60
     * seconds while somebody is hosting, and the control file carries the whole
     * world manifest - folding them together would mean re-uploading a manifest
     * that can run to hundreds of kilobytes, once a minute, to communicate a relay
     * address that changes almost never. One extra file to pick is the cheaper
     * trade.
     */
    public static final String PRESENCE_FILENAME = "worldshare-presence.json";

    /** Filename prefix shared by every bucket archive. */
    public static final String BUCKET_PREFIX = "worldshare-bucket_";

    /** Filename suffix shared by every bucket archive. */
    public static final String BUCKET_SUFFIX = ".zip";

    /**
     * Matches Minecraft's region-file naming, {@code r.<x>.<z>.mca}, anywhere in
     * the path. Also covers {@code .mcr} for ancient worlds and {@code .mcc}
     * oversized-chunk spill files, which follow the same convention.
     */
    private static final Pattern REGION_FILE =
            Pattern.compile("(?:^|/)r\\.(-?\\d+)\\.(-?\\d+)\\.mc[arc]$");

    private final int bucketCount;

    /**
     * @param bucketCount how many bucket archives this world uses; must be within
     *                    [{@value #MIN_BUCKET_COUNT}, {@value #MAX_BUCKET_COUNT}]
     * @throws IllegalArgumentException if the count is out of range
     */
    public BucketLayout(final int bucketCount) {
        if (bucketCount < MIN_BUCKET_COUNT || bucketCount > MAX_BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "bucketCount must be between " + MIN_BUCKET_COUNT + " and "
                            + MAX_BUCKET_COUNT + ", got " + bucketCount);
        }
        this.bucketCount = bucketCount;
    }

    /** A layout using {@link #DEFAULT_BUCKET_COUNT} buckets. */
    public static BucketLayout defaultLayout() {
        return new BucketLayout(DEFAULT_BUCKET_COUNT);
    }

    public int bucketCount() {
        return bucketCount;
    }

    /**
     * Which bucket a world file belongs in.
     *
     * @param relPath forward-slash relative path inside the world folder, exactly
     *                as it appears as a key in {@link WorldManifest#files()}
     * @return a bucket index in {@code [0, bucketCount)}
     */
    public int indexFor(final String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            // Defensive: a blank path is a caller bug, but silently corrupting the
            // world is worse than dumping it deterministically into bucket 0.
            return 0;
        }

        final String normalized = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);

        final Matcher region = REGION_FILE.matcher(normalized);
        final long hash;
        if (region.find()) {
            // Group region/entities/poi views of the same map square together.
            hash = regionHash(Long.parseLong(region.group(1)), Long.parseLong(region.group(2)));
        } else {
            hash = fnv1a(normalized);
        }

        // Math.floorMod, not %, so a negative hash can't produce a negative index.
        return (int) Math.floorMod(hash, (long) bucketCount);
    }

    /** Remote filename for a bucket index, e.g. {@code worldshare-bucket_03.zip}. */
    public static String bucketFilename(final int index) {
        return String.format(Locale.ROOT, "%s%02d%s", BUCKET_PREFIX, index, BUCKET_SUFFIX);
    }

    /**
     * Every remote filename this layout requires, in the order a player should be
     * asked to pick them: the control file first, then buckets ascending.
     *
     * <p>The count of this list is exactly what the setup flow must confirm the
     * user selected - anything fewer means setup is incomplete.
     */
    public String[] allRemoteFilenames() {
        final String[] names = new String[bucketCount + 2];
        names[0] = CONTROL_FILENAME;
        names[1] = PRESENCE_FILENAME;
        for (int i = 0; i < bucketCount; i++) {
            names[i + 2] = bucketFilename(i);
        }
        return names;
    }

    /** How many remote files a world of this layout needs in total. */
    public int remoteFileCount() {
        return bucketCount + 2;
    }

    /**
     * Parse a bucket index back out of a remote filename.
     *
     * @return the index, or -1 if the name isn't a bucket archive belonging to
     *         this layout (including a valid-looking name whose index is out of
     *         range, which signals a bucket-count mismatch between players)
     */
    public int indexFromFilename(final String filename) {
        if (filename == null
                || !filename.startsWith(BUCKET_PREFIX)
                || !filename.endsWith(BUCKET_SUFFIX)) {
            return -1;
        }
        final String digits = filename.substring(
                BUCKET_PREFIX.length(), filename.length() - BUCKET_SUFFIX.length());
        try {
            final int index = Integer.parseInt(digits);
            return (index >= 0 && index < bucketCount) ? index : -1;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    // -----------------------------------------------------------------

    /**
     * Mix two region coordinates into one hash.
     *
     * <p>The odd multipliers are large primes; they keep neighbouring regions
     * (which differ by 1 in x or z) from landing in the same bucket, so a player
     * wandering across a region boundary spreads load rather than concentrating
     * it. The final xor-shift avalanches the high bits down, because the low bits
     * are all {@code floorMod} actually looks at.
     */
    private static long regionHash(final long regionX, final long regionZ) {
        long h = regionX * 0x9E3779B97F4A7C15L;
        h ^= regionZ * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return h;
    }

    /**
     * FNV-1a over the path's UTF-16 code units.
     *
     * <p>Deliberately hand-rolled rather than {@code String.hashCode()}. The JDK
     * specifies that algorithm so it wouldn't actually drift, but writing it out
     * here makes the stability requirement visible at the call site instead of
     * resting on a spec guarantee a future reader has to go look up.
     */
    private static long fnv1a(final String text) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    @Override
    public String toString() {
        return "BucketLayout{buckets=" + bucketCount + "}";
    }
}
