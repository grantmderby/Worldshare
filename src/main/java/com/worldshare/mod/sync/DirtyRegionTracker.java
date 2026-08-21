package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which .mca files have been written to disk since the last successful push,
 * using {@link ChunkDataEvent.Save} as the signal.
 *
 * <p>Key optimization: instead of hashing every .mca file on each push, WorldFileScanner
 * only hashes (and considers for upload) files that MC actually wrote to this session.
 * MC fires ChunkDataEvent.Save only when a chunk is actually serialized and saved —
 * not for chunks that are merely loaded or walked through without modification.
 *
 * <p>State transitions:
 * <ul>
 *   <li>Server starts → dirty set cleared (fresh session)</li>
 *   <li>First ChunkDataEvent.Save → {@code active = true}, path added to dirty set</li>
 *   <li>Successful push → dirty set cleared; {@code active} stays true (still in-world)</li>
 *   <li>Pull completes → dirty set cleared; {@code active = false} (local now matches Drive)</li>
 * </ul>
 *
 * <p>Registered on NeoForge.EVENT_BUS in WorldShareMod constructor (class, not instance).
 *
 * <p>Thread-safety: dirtyPaths is a ConcurrentHashMap set (ChunkDataEvent.Save fires on
 * the server thread; reads happen on the cloud executor thread).
 */
public final class DirtyRegionTracker {

    private DirtyRegionTracker() {}

    // Forward-slash relative paths (relative to world root) known to have been
    // written by MC since the last push. Covers region/, entities/, and poi/ .mca files.
    private static final Set<String> dirtyPaths = ConcurrentHashMap.newKeySet();

    // True once at least one ChunkDataEvent.Save has fired this session.
    // False means no tracking data exists — WorldFileScanner should do a full scan.
    private static volatile boolean active = false;

    // True if any chunk was saved in an unrecognized dimension (e.g. Create Aeronautics
    // custom dims). When true we can't compute the region file path, so WorldFileScanner
    // must skip dirty filtering entirely.
    private static volatile boolean hasUnknownDimChanges = false;

    // ---- NeoForge EVENT_BUS handlers ----

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        dirtyPaths.clear();
        active = false;
        hasUnknownDimChanges = false;
        WorldShareMod.LOGGER.debug("DirtyRegionTracker: reset on server start");
    }

    @SubscribeEvent
    public static void onChunkDataSave(final ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        final ChunkPos pos = event.getChunk().getPos();
        // ChunkPos.getRegionX/Z = chunk coord >> 5 (region coords)
        final int regionX = pos.getRegionX();
        final int regionZ = pos.getRegionZ();

        final String prefix = dimensionPrefix(serverLevel);
        if (prefix == null) {
            // Unknown/custom dimension — can't compute the .mca path reliably.
            // Signal WorldFileScanner to skip filtering and do a full .mca scan.
            if (!hasUnknownDimChanges) {
                hasUnknownDimChanges = true;
                WorldShareMod.LOGGER.debug(
                        "DirtyRegionTracker: unknown dimension '{}', disabling region filter",
                        serverLevel.dimension().location());
            }
            active = true;
            return;
        }

        // Track all three .mca types for this region coordinate.
        // entities/ and poi/ use the same 32x32 region grid as region/.
        dirtyPaths.add(prefix + "region/r."   + regionX + "." + regionZ + ".mca");
        dirtyPaths.add(prefix + "entities/r." + regionX + "." + regionZ + ".mca");
        dirtyPaths.add(prefix + "poi/r."      + regionX + "." + regionZ + ".mca");
        active = true;
    }

    // ---- API for SyncEngine / WorldFileScanner ----

    /**
     * @return true if at least one ChunkDataEvent.Save has fired this session.
     *         False means WorldFileScanner should skip dirty filtering and scan all files.
     */
    public static boolean isActive() {
        return active;
    }

    /**
     * @return true if any chunk was saved in an untracked dimension.
     *         When true, WorldFileScanner cannot safely skip any .mca files.
     */
    public static boolean hasUnknownDimChanges() {
        return hasUnknownDimChanges;
    }

    /**
     * Whether region filtering is safe to apply for the current scan.
     * Shorthand for {@code isActive() && !hasUnknownDimChanges()}.
     */
    public static boolean shouldFilterRegions() {
        return active && !hasUnknownDimChanges;
    }

    /**
     * @return snapshot of dirty .mca relative paths (forward-slash, relative to world root).
     *         Empty if no chunks saved yet or after a reset.
     */
    public static Set<String> getDirtyPaths() {
        return Set.copyOf(dirtyPaths);
    }

    /**
     * Called after a successful push. Clears the dirty set so the next push only
     * tracks changes made after this push. Active state stays true (server still running).
     */
    public static void resetAfterPush() {
        dirtyPaths.clear();
        hasUnknownDimChanges = false;
        WorldShareMod.LOGGER.debug("DirtyRegionTracker: dirty set cleared after push");
    }

    /**
     * Called after a pull. Clears dirty set and resets active state.
     * After a pull, local files match Drive exactly — no .mca files are dirty.
     * The next scan should include all .mca files until we start tracking again.
     */
    public static void resetAfterPull() {
        dirtyPaths.clear();
        active = false;
        hasUnknownDimChanges = false;
        WorldShareMod.LOGGER.debug("DirtyRegionTracker: reset after pull");
    }

    // ---- Helpers ----

    /**
     * Returns the path prefix for a dimension's data within the world folder.
     * e.g. "" for overworld, "DIM-1/" for nether, "DIM1/" for end.
     * Returns null for any unrecognized dimension — caller should disable filtering.
     */
    private static String dimensionPrefix(final ServerLevel level) {
        final var loc = level.dimension().location();
        if (!"minecraft".equals(loc.getNamespace())) return null;
        return switch (loc.getPath()) {
            case "overworld" -> "";
            case "the_nether" -> "DIM-1/";
            case "the_end" -> "DIM1/";
            default -> null; // modded vanilla-namespace dim — treat as unknown
        };
    }
}