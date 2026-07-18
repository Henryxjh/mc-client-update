package io.github.henryxjh.mcclientupdate.cleanup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Performs safe file‑based cleanup of stale download caches and install‑time
 * backups / pending / deleted artifacts.
 *
 * <p>All operations are done with standard NIO file operations; no external
 * libraries are required.
 */
public final class UpdateCleanup {

    private static final String CACHE_DIR = ".mc-client-update/downloads";
    private static final String MODS_DIR = "mods";

    /** Filename suffixes that identify artifacts which can be cleaned up. */
    private static final List<String> SAFE_SUFFIXES = List.of(
            ".mc-client-update-old",
            ".mc-client-update-deleted",
            ".mc-client-update-rejected",
            ".mc-client-update-pending"
    );

    private UpdateCleanup() {
    }

    /**
     * Clean up stale backup files (in {@code gameDirectory/mods}) and stale
     * download‑cache files/directories (in {@code gameDirectory/.mc-client-update/downloads}).
     *
     * @param gameDirectory   root of the Minecraft instance (commonly {@code .minecraft})
     * @param backupRetention entries in {@code mods/} whose last‑modified time is older than this
     *                        duration are eligible for deletion; a {@code Duration.ZERO} value
     *                        (or a negative duration) disables backup cleanup
     * @param cacheRetention  entries inside the download cache older than this duration are
     *                        eligible; a zero (or negative) duration disables cache cleanup
     * @return a summary of what was deleted and how many failures occurred
     */
    public static CleanupResult cleanup(
            Path gameDirectory,
            Duration backupRetention,
            Duration cacheRetention) {

        Instant now = Instant.now();

        long deletedBackupFiles = 0L;
        long deletedCacheFiles = 0L;
        long deletedCacheDirs = 0L;
        long failures = 0L;

        // ----- 1) Backup / residual cleanup (mods directory, non‑recursive) -----
        boolean doBackup = backupRetention != null && !backupRetention.isNegative()
                && !backupRetention.isZero();
        if (doBackup) {
            Path mods = gameDirectory.resolve(MODS_DIR);
            if (!Files.isSymbolicLink(mods)) {
                long cutoffEpoch = now.minus(backupRetention).toEpochMilli();
                if (Files.isDirectory(mods)) {
                    List<Path> entries;
                    try (Stream<Path> s = Files.list(mods)) {
                        entries = s.toList();
                    } catch (IOException e) {
                        failures++;
                        entries = List.of();
                    }

                    for (Path entry : entries) {
                        try {
                            if (Files.isSymbolicLink(entry)) {
                                continue;
                            }
                            if (!Files.isRegularFile(entry)) {
                                continue;
                            }
                            String name = entry.getFileName().toString();
                            boolean safeSuffix = false;
                            for (String suffix : SAFE_SUFFIXES) {
                                if (name.endsWith(suffix)) {
                                    safeSuffix = true;
                                    break;
                                }
                            }
                            if (!safeSuffix) {
                                continue;
                            }

                            long lastModified = Files.getLastModifiedTime(entry).toMillis();
                            if (lastModified < cutoffEpoch) {
                                try {
                                    Files.deleteIfExists(entry);
                                    deletedBackupFiles++;
                                } catch (IOException e) {
                                    failures++;
                                }
                            }
                        } catch (IOException e) {
                            failures++;
                        }
                    }
                }
            }
        }

        // ----- 2) Download cache cleanup (recursive, no symlink‑follow) -----
        boolean doCache = cacheRetention != null && !cacheRetention.isNegative()
                && !cacheRetention.isZero();
        if (doCache) {
            CacheCleanupCounts cacheCounts = cleanDownloadCache(
                    gameDirectory.resolve(CACHE_DIR), cacheRetention, now);
            deletedCacheFiles += cacheCounts.deletedFiles();
            deletedCacheDirs += cacheCounts.deletedDirs();
            failures += cacheCounts.failures();
        }

        return new CleanupResult(
                deletedBackupFiles,
                deletedCacheFiles,
                deletedCacheDirs,
                failures);
    }

    /**
     * Cleans up stale files and empty directories inside the download cache.
     *
     * @param cacheDir the path to the download cache directory (may be missing)
     * @param retention how long files must be unmodified to be eligible for deletion;
     *                  must be non‑negative and non‑zero (caller ensures this)
     * @param now       the current instant for age comparison
     * @return counts of deleted files, empty directories, and IO failures
     */
    private static CacheCleanupCounts cleanDownloadCache(
            Path cacheDir, Duration retention, Instant now) {
        if (!Files.isDirectory(cacheDir) || Files.isSymbolicLink(cacheDir)) {
            return new CacheCleanupCounts(0, 0, 0);
        }
        long cutoffEpoch = now.minus(retention).toEpochMilli();
        // mutable counters bridged into the visitor via a single-element array.
        // [0] = deletedCacheFiles, [1] = deletedCacheDirs, [2] = failures
        final long[] counts = new long[]{0L, 0L, 0L};

        try {
            Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                                                         BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    long lastModified = attrs.lastModifiedTime().toMillis();
                    if (lastModified < cutoffEpoch) {
                        try {
                            Files.deleteIfExists(file);
                            counts[0]++;
                        } catch (IOException e) {
                            counts[2]++;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir,
                                                          IOException exc) {
                    if (exc != null) {
                        counts[2]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!dir.equals(cacheDir)) {
                        boolean empty;
                        try (Stream<Path> list = Files.list(dir)) {
                            empty = list.findAny().isEmpty();
                        } catch (IOException e) {
                            counts[2]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (empty) {
                            try {
                                Files.deleteIfExists(dir);
                                counts[1]++;
                            } catch (IOException e) {
                                counts[2]++;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            counts[2]++;
        }

        return new CacheCleanupCounts(counts[0], counts[1], counts[2]);
    }

    private record CacheCleanupCounts(long deletedFiles, long deletedDirs, long failures) {}
}
