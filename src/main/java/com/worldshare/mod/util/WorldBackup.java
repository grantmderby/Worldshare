package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * Takes a timestamped copy of a world folder before something irreversible.
 *
 * <p>Two flows need this and they need it to behave identically: overriding a
 * stale lock, which pulls someone else's state over the top of yours, and
 * repairing an inconsistent remote, which republishes your copy over everyone
 * else's. Both are the kind of operation where "there is a backup" has to be true
 * rather than nearly true, so they share one implementation rather than each
 * keeping a private near-copy.
 *
 * <p>Backups are left in {@code saves/} beside the world, named
 * {@code <world>_offline_backup_<timestamp>}. Minecraft will list them as ordinary
 * worlds, which is the point: recovering means opening one, not finding a tool.
 */
public final class WorldBackup {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private WorldBackup() {}

    /**
     * Copy a world folder to a timestamped sibling.
     *
     * @return the backup directory, or null if there was nothing to back up
     */
    public static Path create(final Path localWorld) throws IOException {
        if (localWorld == null || !Files.isDirectory(localWorld)) return null;

        final Path backupDir = localWorld.getParent().resolve(
                localWorld.getFileName() + "_offline_backup_" + STAMP.format(LocalDateTime.now()));
        Files.createDirectories(backupDir);
        copyDirectory(localWorld, backupDir);
        WorldShareMod.LOGGER.info("WorldBackup: copied {} to {}", localWorld, backupDir);
        return backupDir;
    }

    private static void copyDirectory(final Path src, final Path dst) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            for (final Path file : (Iterable<Path>) stream::iterator) {
                final Path dest = dst.resolve(src.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
