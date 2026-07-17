package io.github.henryxjh.mcclientupdate.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashingTest {
    @TempDir
    Path directory;

    @Test
    void calculatesSha256AndSha512() throws Exception {
        Path file = Files.writeString(directory.resolve("test.jar"), "abc");

        Hashing.Hashes hashes = Hashing.hashes(file);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashes.sha256());
        assertEquals(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                hashes.sha512());
        assertEquals(hashes.sha256(), Hashing.sha256(file));
        assertEquals(hashes.sha512(), Hashing.sha512(file));
    }

    @Test
    void rejectsMalformedSha512() {
        assertThrows(IllegalArgumentException.class, () -> Hashing.normalizeSha512("0".repeat(127)));
    }

    @Test
    void acceptsEitherHashButRejectsAnEmptyHashSet() {
        String sha256 = "0".repeat(64);
        String sha512 = "0".repeat(128);

        assertEquals(sha256, new Hashing.Hashes(sha256, null).sha256());
        assertEquals(sha512, new Hashing.Hashes(null, sha512).sha512());
        assertThrows(IllegalArgumentException.class, () -> new Hashing.Hashes(null, null));
    }
}
