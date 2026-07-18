package io.github.henryxjh.mcclientupdate.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.henryxjh.mcclientupdate.config.ClientUpdateConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCleanupTest {

    @TempDir
    Path gameDir;

    // ---------- configuration defaults & validation ----------

    @Test
    void defaultCleanupDaysAre14And30() {
        ClientUpdateConfig config = ClientUpdateConfig.load(gameDir);
        // freshly created config file gets defaults even when manifestUrl is empty
        assertEquals(14, config.cleanupBackupsAfterDays());
        assertEquals(30, config.cleanupDownloadCacheAfterDays());
    }

    @Test
    void cleanupDaysOutOfRangeFails() throws Exception {
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        // too large
        Files.writeString(configDir.resolve("mc-client-update.json"),
                "{\"manifestUrl\":\"\", \"cleanupBackupsAfterDays\":9999}");
        assertThrows(IllegalStateException.class, () -> ClientUpdateConfig.load(gameDir));
    }

    @Test
    void cleanupDaysZeroAccepted() throws Exception {
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mc-client-update.json"),
                "{\"manifestUrl\":\"\", \"cleanupBackupsAfterDays\":0,"
                + "\"cleanupDownloadCacheAfterDays\":0}");
        ClientUpdateConfig config = ClientUpdateConfig.load(gameDir);
        assertEquals(0, config.cleanupBackupsAfterDays());
        assertEquals(0, config.cleanupDownloadCacheAfterDays());
    }

    // ---------- backup/residual cleanup ----------

    @Test
    void staleBackupFilesAreRemoved() throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);

        // an old backup file
        Path oldBackup = mods.resolve("example-mod.jar.mc-client-update-old");
        Files.writeString(oldBackup, "old");
        Files.setLastModifiedTime(oldBackup,
                FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        // a very fresh backup (should stay)
        Path freshBackup = mods.resolve("important.jar.mc-client-update-deleted");
        Files.writeString(freshBackup, "fresh");

        // a regular .jar (must NOT be deleted)
        Path jar = mods.resolve("keep-me.jar");
        Files.writeString(jar, "jar-content");

        Duration retention = Duration.ofDays(5);
        CleanupResult result = UpdateCleanup.cleanup(gameDir, retention, Duration.ZERO);

        assertEquals(1, result.deletedBackupFiles());
        assertEquals(0, result.deletedCacheFiles());
        assertEquals(0, result.deletedCacheDirectories());

        assertFalse(Files.exists(oldBackup));
        assertTrue(Files.exists(freshBackup));
        assertTrue(Files.exists(jar));
    }

    @Test
    void backupCleanupSkipsSymlinks() throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);

        {
            Path linkTest = mods.resolve("linktest");
            Path target = mods.resolve("targetTest");
            try {
                Files.writeString(target, "target");
                Files.createSymbolicLink(linkTest, target);
                Files.deleteIfExists(linkTest);
                Files.deleteIfExists(target);
            } catch (UnsupportedOperationException | IOException e) {
                Assumptions.assumeTrue(false, "Symbolic links not supported");
            }
        }

        Path realBackup = mods.resolve("mod.jar.mc-client-update-old");
        Files.writeString(realBackup, "data");
        Files.setLastModifiedTime(realBackup,
                FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS)));

        Path symlinkTarget = mods.resolve("target.jar");
        Files.writeString(symlinkTarget, "target");
        Files.setLastModifiedTime(symlinkTarget,
                FileTime.from(Instant.now().minus(100, ChronoUnit.DAYS)));
        Path symlink = mods.resolve("mod.jar.mc-client-update-pending");
        Files.createSymbolicLink(symlink, symlinkTarget);

        Duration retention = Duration.ofDays(10);
        CleanupResult result = UpdateCleanup.cleanup(gameDir, retention, Duration.ZERO);

        // real file deleted, symlink not followed or deleted
        assertEquals(1, result.deletedBackupFiles());
        assertFalse(Files.exists(realBackup));
        assertTrue(Files.exists(symlinkTarget));  // original untouched
        // symlink itself is not deleted because it is neither a regular file nor followed
        assertTrue(Files.exists(symlink));
    }

    // ---------- download cache cleanup ----------

    @Test
    void staleCacheFilesAndEmptyDirectoriesAreCleaned() throws Exception {
        Path cache = gameDir.resolve(".mc-client-update/downloads");
        Files.createDirectories(cache);

        // put a stale file
        Path stale = cache.resolve("lib.jar");
        Files.writeString(stale, "stale");
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(50, ChronoUnit.DAYS)));

        // a fresh file that should remain
        Path fresh = cache.resolve("modern.jar");
        Files.writeString(fresh, "fresh");

        // create a sub‑directory that will become empty after stale file removal
        Path sub = cache.resolve("nested");
        Files.createDirectories(sub);
        Path innerStale = sub.resolve("x.jar");
        Files.writeString(innerStale, "inner");
        Files.setLastModifiedTime(innerStale,
                FileTime.from(Instant.now().minus(50, ChronoUnit.DAYS)));

        Duration retention = Duration.ofDays(10);
        CleanupResult result = UpdateCleanup.cleanup(gameDir, Duration.ZERO, retention);

        // stale file plus inner stale => 2 deleted cache files
        assertEquals(2, result.deletedCacheFiles());
        // sub directory became empty -> 1 deleted cache directory
        assertEquals(1, result.deletedCacheDirectories());
        assertEquals(0, result.failureCount());

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
        assertFalse(Files.exists(sub));
    }

    @Test
    void missingDirectoriesAreTreatedAsNoOp() {
        // mods/ and .mc-client-update/downloads do not exist
        CleanupResult result = UpdateCleanup.cleanup(
                gameDir, Duration.ofDays(5), Duration.ofDays(5));
        assertEquals(0, result.deletedBackupFiles());
        assertEquals(0, result.deletedCacheFiles());
        assertEquals(0, result.deletedCacheDirectories());
        assertEquals(0, result.failureCount());
    }

    @Test
    void zeroRetentionDisablesCleanup() throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path old = mods.resolve("x.jar.mc-client-update-old");
        Files.writeString(old, "old");
        Files.setLastModifiedTime(old,
                FileTime.from(Instant.now().minus(1000, ChronoUnit.DAYS)));

        Path cache = gameDir.resolve(".mc-client-update/downloads");
        Files.createDirectories(cache);
        Path oldCache = cache.resolve("data.jar");
        Files.writeString(oldCache, "old");
        Files.setLastModifiedTime(oldCache,
                FileTime.from(Instant.now().minus(1000, ChronoUnit.DAYS)));

        CleanupResult result = UpdateCleanup.cleanup(
                gameDir, Duration.ZERO, Duration.ZERO);
        assertEquals(0, result.deletedBackupFiles());
        assertEquals(0, result.deletedCacheFiles());
    }

    @Test
    void regularJarsAreNeverDeletedFromMods() throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path jar = mods.resolve("my-mod.jar");
        Files.writeString(jar, "content");
        Files.setLastModifiedTime(jar,
                FileTime.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        CleanupResult result = UpdateCleanup.cleanup(
                gameDir, Duration.ofDays(1), Duration.ZERO);
        assertEquals(0, result.deletedBackupFiles());
        assertTrue(Files.exists(jar));
    }
}
