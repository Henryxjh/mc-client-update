package io.github.henryxjh.mcclientupdate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientUpdateConfigTest {
    @TempDir
    Path gameDirectory;

    @Test
    void createsDisabledDefaultConfiguration() throws IOException {
        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);

        Path configFile = gameDirectory.resolve("config").resolve(ClientUpdateConfig.FILE_NAME);
        assertFalse(config.updatesEnabled());
        assertTrue(Files.isRegularFile(configFile));
        assertTrue(Files.readString(configFile).contains("\"completionHoldSeconds\""));
        assertEquals(10, config.connectTimeout().toSeconds());
        assertEquals(30, config.readTimeout().toSeconds());
        assertEquals("fail", config.minecraftVersionMismatchAction());
        assertEquals(8, config.completionHoldSeconds());
    }

    @Test
    void loadsHttpsManifestAndRedactsSecrets() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://user:password@updates.example.com/client.json?token=secret#fragment",
                  "connectTimeoutSeconds": 5,
                  "readTimeoutSeconds": 20,
                  "allowInsecureHttp": false
                }
                """);

        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);

        assertTrue(config.updatesEnabled());
        assertEquals("https://updates.example.com/client.json", config.redactedManifestEndpoint());
        assertEquals(5, config.connectTimeout().toSeconds());
        assertEquals(20, config.readTimeout().toSeconds());
        assertEquals("fail", config.minecraftVersionMismatchAction());
    }

    @Test
    void rejectsHttpByDefault() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "http://192.168.1.2/client.json",
                  "connectTimeoutSeconds": 10,
                  "readTimeoutSeconds": 30,
                  "allowInsecureHttp": false
                }
                """);

        assertThrows(IllegalStateException.class, () -> ClientUpdateConfig.load(gameDirectory));
    }

    @Test
    void permitsExplicitHttpForLocalTesting() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "http://127.0.0.1:8080/client.json",
                  "connectTimeoutSeconds": 10,
                  "readTimeoutSeconds": 30,
                  "allowInsecureHttp": true
                }
                """);

        assertTrue(ClientUpdateConfig.load(gameDirectory).updatesEnabled());
    }

    @Test
    void loadsIgnoreAction() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://example.com/client.json",
                  "minecraftVersionMismatchAction": "ignore"
                }
                """);
        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);
        assertEquals("ignore", config.minecraftVersionMismatchAction());
    }

    @Test
    void loadsCompletionHoldSeconds() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://example.com/client.json",
                  "completionHoldSeconds": 0
                }
                """);

        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);

        assertEquals(0, config.completionHoldSeconds());
    }

    @Test
    void treatsNegativeCompletionHoldSecondsAsZero() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://example.com/client.json",
                  "completionHoldSeconds": -1
                }
                """);

        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);

        assertEquals(0, config.completionHoldSeconds());
    }

    @Test
    void rejectsInvalidMismatchAction() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://example.com/client.json",
                  "minecraftVersionMismatchAction": "warn"
                }
                """);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ClientUpdateConfig.load(gameDirectory));
        assertTrue(ex.getMessage().contains("minecraftVersionMismatchAction"));
    }

    @Test
    void rejectsNullMismatchAction() throws IOException {
        writeConfig("""
                {
                  "manifestUrl": "https://example.com/client.json",
                  "minecraftVersionMismatchAction": null
                }
                """);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ClientUpdateConfig.load(gameDirectory));
        assertTrue(ex.getMessage().contains("minecraftVersionMismatchAction"));
    }

    private void writeConfig(String contents) throws IOException {
        Path configDirectory = gameDirectory.resolve("config");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve(ClientUpdateConfig.FILE_NAME), contents);
    }
}
