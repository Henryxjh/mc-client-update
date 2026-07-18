package io.github.henryxjh.mcclientupdate.cleanup;

/** Result of a cleanup run, counting removed files/directories and any failures. */
public final class CleanupResult {
    private final long deletedBackupFiles;
    private final long deletedCacheFiles;
    private final long deletedCacheDirectories;
    private final long failureCount;

    public CleanupResult(
            long deletedBackupFiles,
            long deletedCacheFiles,
            long deletedCacheDirectories,
            long failureCount) {
        this.deletedBackupFiles = deletedBackupFiles;
        this.deletedCacheFiles = deletedCacheFiles;
        this.deletedCacheDirectories = deletedCacheDirectories;
        this.failureCount = failureCount;
    }

    /** Number of stale backup/pending/deleted files removed from {@code mods/}. */
    public long deletedBackupFiles() {
        return deletedBackupFiles;
    }

    /** Regular files removed from the download cache ({@code .mc-client-update/downloads}). */
    public long deletedCacheFiles() {
        return deletedCacheFiles;
    }

    /** Empty directories removed from the download cache tree. */
    public long deletedCacheDirectories() {
        return deletedCacheDirectories;
    }

    /** Number of failures (IO errors) encountered while attempting deletions. */
    public long failureCount() {
        return failureCount;
    }
}
