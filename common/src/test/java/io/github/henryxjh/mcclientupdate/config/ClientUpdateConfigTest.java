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
    void createsDisabledDefaultConfiguration() {
        ClientUpdateConfig config = ClientUpdateConfig.load(gameDirectory);

        assertFalse(config.updatesEnabled());
        assertTrue(Files.isRegularFile(gameDirectory.resolve("config").resolve(ClientUpdateConfig.FILE_NAME)));
        assertEquals(10, config.connectTimeout().toSeconds());
        assertEquals(30, config.readTimeout().toSeconds());
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

    private void writeConfig(String contents) throws IOException {
        Path configDirectory = gameDirectory.resolve("config");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve(ClientUpdateConfig.FILE_NAME), contents);
    }
}
