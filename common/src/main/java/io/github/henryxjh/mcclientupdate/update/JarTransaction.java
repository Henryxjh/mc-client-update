package io.github.henryxjh.mcclientupdate.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Replaces a loaded JAR by changing directory entries instead of overwriting
 * its contents. The backup deliberately does not end in .jar.
 */
public final class JarTransaction {
    private static final String BACKUP_SUFFIX = ".mc-client-update-old";

    private JarTransaction() {
    }

    public static Result install(Path currentJar, Path candidateJar, Hashing.Hashes expectedHashes) throws IOException {
        Path current = normalize(currentJar);
        Path candidate = normalize(candidateJar);
        Hashing.Hashes expected = Objects.requireNonNull(expectedHashes, "expectedHashes");
        validateInputs(current, candidate);

        Hashing.Hashes candidateHashes = Hashing.hashes(candidate);
        verifyHashes("Candidate", candidate, candidateHashes, expected);
        forceFile(candidate);

        Path backup = backupPath(current);
        if (Files.exists(backup)) {
            throw new IOException("Refusing to overwrite existing recovery file: " + backup);
        }

        MoveMode backupMove = move(current, backup);
        try {
            MoveMode installMove = move(candidate, current);
            Hashing.Hashes installedHashes = Hashing.hashes(current);
            verifyHashes("Installed", current, installedHashes, expected);
            return new Result(current, backup, backupMove, installMove, installedHashes);
        } catch (IOException installFailure) {
            if (Files.exists(backup)) {
                try {
                    if (Files.exists(current)) {
                        rollbackInstalledFile(current, backup);
                    } else {
                        move(backup, current);
                    }
                } catch (IOException rollbackFailure) {
                    installFailure.addSuppressed(rollbackFailure);
                }
            }
            throw installFailure;
        }
    }

    /** Call on a later successful launch, never immediately after replacement. */
    public static boolean cleanupBackup(Path currentJar) throws IOException {
        return Files.deleteIfExists(backupPath(normalize(currentJar)));
    }

    public static Path backupPath(Path currentJar) {
        Path current = normalize(currentJar);
        return current.resolveSibling('.' + current.getFileName().toString() + BACKUP_SUFFIX);
    }

    private static void validateInputs(Path current, Path candidate) throws IOException {
        if (!Files.isRegularFile(current)) {
            throw new IOException("Current JAR does not exist: " + current);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("Candidate JAR does not exist: " + candidate);
        }
        if (!Objects.equals(current.getParent(), candidate.getParent())) {
            throw new IOException("Candidate must be in the current JAR directory: " + candidate);
        }
        if (current.equals(candidate)) {
            throw new IOException("Candidate and current JAR are the same path");
        }
    }

    private static void verifyHashes(
            String stage,
            Path file,
            Hashing.Hashes actual,
            Hashing.Hashes expected) throws IOException {
        if (expected.hasSha256() && !actual.sha256().equals(expected.sha256())) {
            throw new IOException(stage + " SHA-256 mismatch for " + file);
        }
        if (expected.hasSha512() && !actual.sha512().equals(expected.sha512())) {
            throw new IOException(stage + " SHA-512 mismatch for " + file);
        }
    }

    private static void rollbackInstalledFile(Path current, Path backup) throws IOException {
        Path rejected = current.resolveSibling('.' + current.getFileName().toString() + ".mc-client-update-rejected");
        Files.deleteIfExists(rejected);
        move(current, rejected);
        try {
            move(backup, current);
        } catch (IOException rollbackFailure) {
            try {
                move(rejected, current);
            } catch (IOException restoreRejectedFailure) {
                rollbackFailure.addSuppressed(restoreRejectedFailure);
            }
            throw rollbackFailure;
        }
        Files.deleteIfExists(rejected);
    }

    private static MoveMode move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return MoveMode.ATOMIC;
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
            return MoveMode.REGULAR_FALLBACK;
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public enum MoveMode {
        ATOMIC,
        REGULAR_FALLBACK
    }

    public record Result(
            Path installedJar,
            Path backupJar,
            MoveMode backupMove,
            MoveMode installMove,
            Hashing.Hashes installedHashes
    ) {
    }
}
