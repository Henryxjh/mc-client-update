package io.github.henryxjh.mcclientupdate.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinecraftVersionValidatorTest {

    @Test
    void validatesWhenVersionsMatch() {
        // Should not throw
        assertDoesNotThrow(() ->
                MinecraftVersionValidator.validateMinecraftVersion("1.21.1", "1.21.1"));
    }

    @Test
    void throwsManifestFetchExceptionWhenVersionsDiffer() {
        ManifestFetchException ex = assertThrows(ManifestFetchException.class,
                () -> MinecraftVersionValidator.validateMinecraftVersion("1.21.1", "1.20.1"));
        assertTrue(ex.getMessage().contains("1.21.1"));
        assertTrue(ex.getMessage().contains("1.20.1"));
    }

    @Test
    void throwsManifestFetchExceptionWhenCurrentVersionIsEmpty() {
        ManifestFetchException ex = assertThrows(ManifestFetchException.class,
                () -> MinecraftVersionValidator.validateMinecraftVersion("1.21.1", ""));
        assertTrue(ex.getMessage().contains("1.21.1"));
        assertTrue(ex.getMessage().contains("''")); // shows empty string
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> MinecraftVersionValidator.validateMinecraftVersion(null, "1.21"));
        assertThrows(NullPointerException.class,
                () -> MinecraftVersionValidator.validateMinecraftVersion("1.21", null));
    }
}
