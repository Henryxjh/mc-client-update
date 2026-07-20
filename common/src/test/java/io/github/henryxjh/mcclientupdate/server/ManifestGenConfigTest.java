package io.github.henryxjh.mcclientupdate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ManifestGenConfigTest {

    @TempDir
    Path gameDir;

    @Test
    void loadCreatesDefaultConfigWhenMissing() throws Exception {
        ManifestGenConfig config = ManifestGenConfig.load(gameDir);

        assertEquals(List.of("mcu-manifest-gen"), config.getIgnoredMods());
        assertTrue(config.getAllowedUsers().isEmpty());
        assertEquals(gameDir.resolve("manifest-workspace.json"), config.getOutputPath(gameDir));
    }

    @Test
    void loadExistingConfigParsesValues() throws Exception {
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        String json = "{\"ignoredMods\":[\"mod-a\",\"mod-b\"],\"allowedUsers\":[\"alice\"],\"outputPath\":\"custom.json\"}";
        Files.writeString(configDir.resolve("mcu-manifest-gen.json"), json);

        ManifestGenConfig config = ManifestGenConfig.load(gameDir);

        assertEquals(List.of("mod-a", "mod-b"), config.getIgnoredMods());
        assertEquals(List.of("alice"), config.getAllowedUsers());
        assertEquals(gameDir.resolve("custom.json"), config.getOutputPath(gameDir));
    }

    @Test
    void isUserAllowedReturnsFalseWhenAllowedUsersEmpty() {
        ManifestGenConfig config = ManifestGenConfig.load(gameDir);
        // empty list means only console is allowed – no player is allowed
        assertFalse(config.isUserAllowed("bob"));
    }

    @Test
    void isUserAllowedRespectsAllowedUsersList() throws Exception {
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        String json = "{\"allowedUsers\":[\"eve\"]}";
        Files.writeString(configDir.resolve("mcu-manifest-gen.json"), json);

        ManifestGenConfig config = ManifestGenConfig.load(gameDir);

        assertTrue(config.isUserAllowed("eve"));
        assertFalse(config.isUserAllowed("charlie"));
    }
}
