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
 * <p><b>Region grouping, in two layers.</b> Minecraft stores the same map area
 * across parallel directory trees: {@code region/r.3.-1.mca},
 * {@code entities/r.3.-1.mca} and {@code poi/r.3.-1.mca} all describe the same
 * 512x512 block square and almost always change together, so hashing the
 * coordinates rather than the path lands all three in one bucket. On top of that,
 * regions are grouped into 4x4 tiles before hashing, so <em>neighbouring</em>
 * regions also share a bucket - a player wandering across a region boundary
 * should not dirty a second archive. See {@link #REGION_TILE_SHIFT} for the
 * measurements behind that.
 *
 * <p><b>The hot bucket.</b> Bucket {@value #HOT_BUCKET} is reserved for everything
 * that isn't a region file, because those files change every session regardless of
 * where the player went. See {@link #HOT_BUCKET} for what happened when they were
 * hashed like everything else.
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
     * <p>Note that one of these is the reserved {@link #HOT_BUCKET}, so eight
     * buckets means seven region buckets plus one for everything else.
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

    /**
     * Lower bound on bucket count. One bucket is legal - it means "one big archive".
     *
     * <p>Note that 2 is the <em>worst</em> possible value, not a middle ground:
     * with bucket 0 reserved for non-region files, a count of 2 leaves exactly one
     * region bucket, so every session re-uploads all of it. That is the cost of a
     * single archive with none of the simplicity. Use 1 if you want one archive.
     */
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
     * How many bits of region coordinate to discard before hashing, grouping
     * regions into square tiles. 2 means 4x4-region tiles, i.e. 2048x2048 blocks.
     *
     * <p><b>Why tiles instead of hashing each region.</b> An earlier version hashed
     * every region independently and deliberately scattered neighbours, on the
     * reasoning that spreading load was desirable. That is right for a load
     * balancer and exactly backwards here: the goal is for one session's changes to
     * <em>concentrate</em> into as few archives as possible, and players move
     * continuously through space rather than teleporting randomly.
     *
     * <p>Measured, as a share of the world's region bytes re-uploaded per session
     * (8 buckets, a world explored over a 41x41 region area):
     *
     * <pre>
     *   session area     no tiles    4x4 tiles
     *   1x1 regions        14.3%       18.7%
     *   2x2 regions        45.8%       26.1%
     *   3x3 regions        74.4%       34.0%
     *   4x4 regions        91.3%       42.4%
     * </pre>
     *
     * <p>Without tiling, a session wandering 1536 blocks re-uploaded three quarters
     * of the world - barely better than shipping one monolithic archive. Tiles cost
     * a little on the very smallest sessions, because grouping makes bucket sizes
     * less even, and win decisively everywhere else.
     *
     * <p>Larger tiles keep improving locality but make the size imbalance worse:
     * 8x8 tiles left some buckets empty and others holding 40% of the world, which
     * costs more than the locality gains. 4x4 is the point where the two curves
     * cross.
     */
    private static final int REGION_TILE_SHIFT = 2;

    /**
     * The bucket holding every file that isn't a region file.
     *
     * <p><b>Why this is reserved rather than hashed like everything else.</b>
     * Minecraft rewrites a handful of small files on essentially every session no
     * matter what the player did - {@code level.dat}, playerdata, stats,
     * advancements, {@code data/*.dat}. Hashing those by path scattered them across
     * the layout, and measurement showed the consequence: ten such files dirtied
     * <em>six of eight</em> buckets before the player had moved a single block.
     * Each one then dragged its bucket's worth of region files across the network
     * with it, so nearly every push re-uploaded nearly the whole world and
     * bucketing bought almost nothing.
     *
     * <p>Putting them together in one reserved bucket means a session dirties this
     * bucket - which is tiny, a few hundred kilobytes against a world's tens or
     * hundreds of megabytes - plus only the region buckets the player actually
     * visited. That is the behaviour the whole design is for.
     */
    public static final int HOT_BUCKET = 0;

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
            // world is worse than dumping it deterministically into one bucket.
            return HOT_BUCKET;
        }

        final String normalized = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);

        final Matcher region = REGION_FILE.matcher(normalized);
        if (!region.find()) {
            // Not a region file: it goes in the hot bucket. See HOT_BUCKET.
            return HOT_BUCKET;
        }

        // A region file. Hash the *tile* it sits in rather than the region itself,
        // so neighbouring regions share a bucket. See REGION_TILE_SHIFT.
        final long regionX = Long.parseLong(region.group(1));
        final long regionZ = Long.parseLong(region.group(2));
        final long hash = regionHash(regionX >> REGION_TILE_SHIFT, regionZ >> REGION_TILE_SHIFT);

        // One bucket is reserved, so region files spread across the remaining
        // bucketCount-1. With bucketCount == 1 there is nothing to spread across
        // and everything shares the single archive.
        final int regionBuckets = bucketCount - 1;
        if (regionBuckets <= 0) {
            return HOT_BUCKET;
        }

        // Math.floorMod, not %, so a negative hash can't produce a negative index.
        return HOT_BUCKET + 1 + (int) Math.floorMod(hash, (long) regionBuckets);
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
     * Mix two tile coordinates into one hash.
     *
     * <p>Callers pass <em>tile</em> coordinates, not raw region coordinates - the
     * grouping happens before this is called. Within a tile there is nothing left
     * to mix; the job here is only to distribute whole tiles evenly across the
     * available buckets so no single archive ends up holding most of the world.
     *
     * <p>The odd multipliers are large primes, and the final xor-shift avalanches
     * the high bits down, because the low bits are all {@code floorMod} actually
     * looks at.
     */
    private static long regionHash(final long tileX, final long tileZ) {
        long h = tileX * 0x9E3779B97F4A7C15L;
        h ^= tileZ * 0xC2B2AE3D27D4EB4FL;
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
