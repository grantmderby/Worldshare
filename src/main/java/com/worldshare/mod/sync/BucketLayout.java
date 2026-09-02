package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;

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
     * <p>One of these is the reserved {@link #HOT_BUCKET}, so sixteen buckets
     * means fifteen region buckets plus one for everything else.
     *
     * <p><b>Why twenty-four.</b> A joining player must select every remote file by
     * hand - {@code bucketCount + 2} of them - because a Picker folder grant conveys
     * no access to the folder's contents. That made the count look like it was
     * bounded by patience, and it was set to 8 on that basis, then 16. Then the
     * picker's selection behaviour was actually checked: files toggle on a plain
     * click, with no modifier key, so twenty-six selections is a few seconds of
     * clicking in a single dialog rather than a chore.
     *
     * <p>With that constraint gone, what decides it is how often unrelated tiles end
     * up sharing a bucket. A session dirties a few tiles, but what gets uploaded is
     * every bucket those tiles landed in, whole - so a bucket holding three tiles
     * means editing one drags the other two across the network. Measured on a real
     * save at 16 buckets, a session at spawn cost 75% of the world, because a flight
     * corridor and a slice of the End had hashed into spawn's buckets.
     *
     * <p>Share of the world re-uploaded per session, modelled over world maturity:
     *
     * <pre>
     *                      16 buckets          24 buckets          32 buckets
     *   world size         avg worst 4-tile    avg worst 4-tile    avg worst 4-tile
     *   young (16 tiles)   13%  14%   23%      10%  14%   17%      10%  14%   18%
     *   established (32)   10%  17%   19%       8%  14%   14%       9%  17%   15%
     *   mature (64)        10%  13%   17%       7%  11%   13%       7%  10%   11%
     *   large (128)         9%  11%   15%       7%  10%   12%       6%  10%   10%
     * </pre>
     *
     * <p>Sixteen to twenty-four takes roughly a quarter off at every size, for eight
     * more clicks once. Twenty-four to thirty-two buys a point or two more. Note the
     * model gives every tile the same size and so <em>understates</em> the gain -
     * real tiles vary enormously, which is how a world the model puts at 13% measured
     * 75%.
     *
     * <p>A minor bonus, not a reason on its own: the region buckets number
     * {@code bucketCount - 1}, so 24 gives 23 and 32 gives 31, both prime, where 16
     * gives 15. A prime modulus spreads a weak hash more evenly, though the mixer
     * below is good enough that it should hardly matter.
     *
     * <p>The count is frozen per world at setup and carried in the control file, so
     * erring high is the recoverable direction: too many buckets costs a one-time
     * handful of clicks, too few costs bandwidth for the life of the world.
     */
    public static final int DEFAULT_BUCKET_COUNT = 24;

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
     * <p><b>Those figures describe a square world, and on their own they argue for
     * the wrong thing.</b> Re-measuring against a real save suggested 2x2 tiles were
     * better across the board - but that save was a blob around spawn plus one
     * flight corridor, which is not what people build. Modelling the shape they do
     * build - two bases, a corridor between them, short spokes to resource biomes -
     * reversed the answer: 4x4 costs 32% of the world for a session at a base
     * against 47% for 2x2, and 14% against 24% for a single chunk.
     *
     * <p>The reason is a size relationship worth stating outright: <b>a tile should
     * be larger than a base.</b> A 3x3-region base is 1536 blocks across and a 4x4
     * tile is 2048, so ordinary play at home usually stays inside one tile and
     * dirties one bucket. At 1024 blocks a 2x2 tile cannot contain the same base,
     * which straddles four of them however it is placed.
     *
     * <p>Usually, not always: alignment against the tile grid is luck. Over all 256
     * alignments of a 3x3 base, 4x4 touches between 1 and 4 tiles (2.25 on average)
     * while 2x2 always touches 4. In bytes, across every position in the modelled
     * world, 4x4 averages 21% against 2x2's 27% - but its worst case is 81% against
     * 69%, so a base built exactly on a crossroads pays for it every save, forever.
     * 4x4 is kept because the average is what most players get; the tail is real and
     * is the reason to revisit this if anyone reports it.
     *
     * <p>One last thing to know before changing this number: <b>tiling decides how
     * many buckets a session touches, and {@link #DEFAULT_BUCKET_COUNT} decides what
     * each one costs.</b> With fifteen region buckets, touching four is roughly a
     * quarter of the world however it is tiled - so on a large world the bucket
     * count is the stronger lever, and is where to look first.
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
     * the path. Also covers {@code .mcr} for ancient worlds.
     *
     * <p>This used to accept {@code .mcc} too, on the assumption that spill files
     * followed the same convention. They do not: they are named {@code c.<x>.<z>}
     * and carry <em>chunk</em> coordinates, so nothing matched and they fell
     * through to the hot bucket, away from the region pointing at them. See
     * {@link #EXTERNAL_CHUNK_FILE}.
     */
    private static final Pattern REGION_FILE =
            Pattern.compile("(?:^|/)r\\.(-?\\d+)\\.(-?\\d+)\\.mc[ar]$");

    /**
     * An oversized chunk stored beside its region file, {@code c.<x>.<z>.mcc}.
     *
     * <p>Minecraft writes a chunk too large to fit in its region file into a
     * separate file and leaves a pointer behind. It has to travel in the same
     * bucket as the region pointing at it; split them across two buckets and one
     * can be replaced on Drive without the other, leaving a region referencing a
     * chunk that isn't there.
     *
     * <p><b>These are chunk coordinates, not region coordinates.</b> A region
     * covers 32x32 chunks, so they need shifting by {@link #CHUNK_TO_REGION_SHIFT}
     * before they mean the same thing as the numbers in an {@code r.} filename.
     */
    private static final Pattern EXTERNAL_CHUNK_FILE =
            Pattern.compile("(?:^|/)c\\.(-?\\d+)\\.(-?\\d+)\\.mcc$");

    /** 32 chunks per region axis. */
    private static final int CHUNK_TO_REGION_SHIFT = 5;

    /**
     * Revision of the path-to-bucket mapping below.
     *
     * <p>Bump this whenever a path's bucket could change, and only then. Worlds
     * written under an older revision refuse to sync until they are repaired,
     * because a push rewrites only the buckets whose contents changed - so an
     * unchanged file would sit in its old archive while the new mapping looked for
     * it somewhere else, and the loss would show up as a file missing after a pull
     * rather than as an error.
     *
     * <p>Revision 2 fixed two mappings: oversized {@code .mcc} chunks were landing
     * in the hot bucket instead of with their region, and the hash ignored the
     * dimension, so every dimension's {@code r.0.0} shared one bucket.
     */
    public static final int LAYOUT_VERSION = 2;

    /**
     * Splits the dimension prefix off a chunk-storage path.
     *
     * <p>Everything before {@code /region/}, {@code /entities/} or {@code /poi/}:
     * {@code dim-1}, {@code dim1}, or {@code dimensions/<namespace>/<path>} for a
     * datapack or modded dimension, and nothing at all for the Overworld.
     */
    private static final Pattern DIMENSION_PREFIX =
            Pattern.compile("^(.*)/(?:region|entities|poi)/[^/]+$");

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

    /**
     * System property overriding the bucket count for newly created worlds.
     *
     * <p>A testing hook, and deliberately not a config option. A world's bucket
     * count is frozen when it is created and can never change, so exposing it to
     * players would only offer them a decision they cannot revisit and have no
     * basis to make. But two dev clients run from the same classes, so a constant
     * cannot differ between them, and checking that a 24-bucket client opens a
     * 16-bucket world needs exactly that.
     *
     * <p>Set on the clientThree run configuration in build.gradle. Nothing sets
     * it in a released build.
     */
    private static final String BUCKET_COUNT_PROPERTY = "worldshare.setupBucketCount";

    /**
     * How many buckets a world created right now should get.
     *
     * <p>{@link #DEFAULT_BUCKET_COUNT} unless the testing hook above says
     * otherwise. Existing worlds never consult this - their count comes from
     * their control file.
     */
    public static int newWorldBucketCount() {
        final String override = System.getProperty(BUCKET_COUNT_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_BUCKET_COUNT;
        }
        try {
            final int parsed = Integer.parseInt(override.trim());
            if (parsed < MIN_BUCKET_COUNT || parsed > MAX_BUCKET_COUNT) {
                WorldShareMod.LOGGER.warn(
                        "{}={} is outside {}..{}; using the default of {}",
                        BUCKET_COUNT_PROPERTY, parsed, MIN_BUCKET_COUNT, MAX_BUCKET_COUNT,
                        DEFAULT_BUCKET_COUNT);
                return DEFAULT_BUCKET_COUNT;
            }
            WorldShareMod.LOGGER.warn(
                    "New worlds will use {} buckets, not the default {} ({} is set)",
                    parsed, DEFAULT_BUCKET_COUNT, BUCKET_COUNT_PROPERTY);
            return parsed;
        } catch (final NumberFormatException e) {
            WorldShareMod.LOGGER.warn("{}='{}' isn't a number; using the default of {}",
                    BUCKET_COUNT_PROPERTY, override, DEFAULT_BUCKET_COUNT);
            return DEFAULT_BUCKET_COUNT;
        }
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

        final long regionX;
        final long regionZ;

        final Matcher region = REGION_FILE.matcher(normalized);
        final Matcher external = EXTERNAL_CHUNK_FILE.matcher(normalized);
        if (region.find()) {
            regionX = Long.parseLong(region.group(1));
            regionZ = Long.parseLong(region.group(2));
        } else if (external.find()) {
            // An oversized chunk. Shift its chunk coordinates down to the region
            // that holds it, so it tiles with the region file that points at it
            // rather than somewhere 32 times further out.
            regionX = Long.parseLong(external.group(1)) >> CHUNK_TO_REGION_SHIFT;
            regionZ = Long.parseLong(external.group(2)) >> CHUNK_TO_REGION_SHIFT;
        } else if (isKnownHotPath(normalized)) {
            // Minecraft's own per-session churn. See HOT_BUCKET.
            return HOT_BUCKET;
        } else {
            // Something we don't recognise - a mod's own folder, most likely.
            //
            // These used not to sync at all, so nothing here is moving buckets;
            // this rule only ever applies to paths absent from every existing
            // manifest. That is what lets the denylist ship without a layout
            // version bump, and equally why this rule has to be right the first
            // time - changing it later would move files that by then do sync.
            //
            // Hashed by top-level folder rather than dropped in the hot bucket,
            // because the hot bucket is repacked on essentially every push
            // (level.dat and stats always change). A mod folder living there would
            // be re-uploaded every session whether or not it changed. Its own
            // bucket means it travels only when it actually changes, and a whole
            // mod's data stays together.
            return bucketForOpaquePath(normalized);
        }

        // Hash the *tile* rather than the region, so neighbouring regions share a
        // bucket. See REGION_TILE_SHIFT.
        //
        // The dimension is part of the hash. Without it the filename alone decides,
        // so every dimension's r.0.0 - Overworld, Nether, End, and each modded one -
        // collided in a single bucket. That is also the bucket everyone spawns in,
        // and Nether coordinates being 1/8 scale concentrated it further.
        final long hash = regionHash(
                regionX >> REGION_TILE_SHIFT,
                regionZ >> REGION_TILE_SHIFT,
                dimensionOf(normalized));

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
    /**
     * Paths Minecraft itself rewrites every session, which belong in the hot bucket.
     *
     * <p>Listed explicitly rather than inferred. These are exactly the paths that
     * reach {@link #HOT_BUCKET} today, and letting any of them fall through to the
     * opaque-path rule would move it to a different bucket - which for files that
     * already sync is the silent-loss case {@link #LAYOUT_VERSION} exists to catch.
     * Adding to this list is a layout change; adding to the denylist is not.
     */
    private static final java.util.List<String> HOT_PREFIXES = java.util.List.of(
            "playerdata/", "stats/", "advancements/",
            "data/", "resources/", "datapacks/", "v_data/");

    private static boolean isKnownHotPath(final String normalized) {
        if (normalized.equals("level.dat") || normalized.equals("level.dat_old")) {
            return true;
        }
        // Checked after stripping any dimension prefix, because Minecraft keeps a
        // data/ folder per dimension - DIM-1/data/raids.dat is housekeeping in
        // exactly the way data/raids.dat is. Stripping precisely rather than
        // matching "/data/" anywhere keeps a mod's own data folder,
        // somemod/data/big.bin, out of the hot bucket and in its own.
        final String withoutDimension = stripDimensionPrefix(normalized);
        for (final String prefix : HOT_PREFIXES) {
            if (withoutDimension.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Remove a leading dimension folder, if the path has one.
     *
     * <p>{@code dim-1/}, {@code dim1/}, or {@code dimensions/<namespace>/<path>/}
     * for a datapack or modded dimension. Anything else is returned untouched.
     */
    private static String stripDimensionPrefix(final String normalized) {
        if (normalized.startsWith("dim-1/")) return normalized.substring(6);
        if (normalized.startsWith("dim1/")) return normalized.substring(5);
        if (normalized.startsWith("dimensions/")) {
            // dimensions/<namespace>/<path>/... - drop three segments.
            int cut = normalized.indexOf('/');
            for (int i = 0; i < 2 && cut >= 0; i++) {
                cut = normalized.indexOf('/', cut + 1);
            }
            if (cut >= 0 && cut + 1 < normalized.length()) {
                return normalized.substring(cut + 1);
            }
        }
        return normalized;
    }

    /**
     * A stable bucket for a path we know nothing about, keyed on its top-level
     * folder so one mod's data stays in one archive.
     *
     * <p>A loose file at the world root has no folder to group by and is almost
     * certainly small, so it goes to the hot bucket.
     */
    private int bucketForOpaquePath(final String normalized) {
        final int slash = normalized.indexOf('/');
        if (slash <= 0) {
            return HOT_BUCKET;
        }
        final String topFolder = normalized.substring(0, slash);

        final int regionBuckets = bucketCount - 1;
        if (regionBuckets <= 0) {
            return HOT_BUCKET;
        }
        // Reuses the region hash with zeroed coordinates so the folder name alone
        // decides, and so opaque folders share the same spread as region tiles
        // rather than needing a second hash function to reason about.
        final long hash = regionHash(0L, 0L, topFolder);
        return HOT_BUCKET + 1 + (int) Math.floorMod(hash, (long) regionBuckets);
    }

    /** The dimension a chunk-storage path belongs to; empty for the Overworld. */
    private static String dimensionOf(final String normalized) {
        final Matcher m = DIMENSION_PREFIX.matcher(normalized);
        return m.matches() ? m.group(1) : "";
    }

    private static long regionHash(final long tileX, final long tileZ,
                                   final String dimension) {
        long h = tileX * 0x9E3779B97F4A7C15L;
        h ^= tileZ * 0xC2B2AE3D27D4EB4FL;
        h ^= dimension.hashCode() * 0x94D049BB133111EBL;
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
