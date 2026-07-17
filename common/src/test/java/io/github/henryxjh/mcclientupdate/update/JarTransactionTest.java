package io.github.henryxjh.mcclientupdate.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarTransactionTest {
    @TempDir
    Path directory;

    @Test
    void installsCandidateAndKeepsNonJarBackup() throws Exception {
        Path current = Files.writeString(directory.resolve("example.jar"), "old");
        Path candidate = Files.writeString(directory.resolve(".example.jar.pending"), "new");
        Hashing.Hashes calculated = Hashing.hashes(candidate);
        Hashing.Hashes expected = new Hashing.Hashes(null, calculated.sha512());

        JarTransaction.Result result = JarTransaction.install(current, candidate, expected);

        assertEquals("new", Files.readString(current));
        assertEquals("old", Files.readString(result.backupJar()));
        assertEquals(calculated, result.installedHashes());
        assertFalse(result.backupJar().getFileName().toString().endsWith(".jar"));
        assertTrue(JarTransaction.cleanupBackup(current));
        assertFalse(Files.exists(result.backupJar()));
    }

    @Test
    void rejectsWrongCandidateHashWithoutMovingCurrentJar() throws Exception {
        Path current = Files.writeString(directory.resolve("example.jar"), "old");
        Path candidate = Files.writeString(directory.resolve(".example.jar.pending"), "new");

        Hashing.Hashes wrongHashes = new Hashing.Hashes("0".repeat(64), "0".repeat(128));
        assertThrows(Exception.class, () -> JarTransaction.install(current, candidate, wrongHashes));
        assertEquals("old", Files.readString(current));
        assertTrue(Files.exists(candidate));
    }
}
