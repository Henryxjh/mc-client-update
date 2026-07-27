package io.github.henryxjh.mcclientupdate.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientUpdateManifestFetcherTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;
    private URI manifestUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(null); // default executor
        server.start();
        manifestUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/manifest.json");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---- helpers ----

    private void respond(int code, String body) {
        server.createContext("/manifest.json", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static String minimalValidManifest() {
        return """
                {
                  "schemaVersion": 1,
                  "manifestId": "test-manifest",
                  "revision": 1,
                  "generatedAt": "2026-07-17T12:00:00Z",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "test_mod": {
                      "name": "Test Mod",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0.0",
                            "fileName": "test.jar",
                            "size": 1024,
                            "hashes": {
                              "sha256": "1111111111111111111111111111111111111111111111111111111111111111"
                            },
                            "download": {
                              "type": "hosted",
                              "url": "mods/test.jar"
                            }
                          }
                        }
                      ]
                    }
                  }
                }""";
    }

    private Manifest fetch() {
        return ClientUpdateManifestFetcher.fetchManifest(manifestUri, SHORT_TIMEOUT, SHORT_TIMEOUT);
    }

    // ---- tests ----

    @Test
    void shouldReturnManifestWhenHttp200AndValid() {
        respond(HTTP_OK, minimalValidManifest());
        Manifest m = fetch();
        assertEquals("test-manifest", m.manifestId());
        assertEquals(1, m.revision());
        assertEquals(1, m.modCount());
    }

    @Test
    void shouldRejectNon200StatusCode() {
        respond(HTTP_NOT_FOUND, "{}");
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().contains("404"), "Error message should contain status code");
    }

    @Test
    void shouldRejectOverlyLargeResponse() {
        StringBuilder big = new StringBuilder(minimalValidManifest());
        // append padding so body exceeds 4 MiB
        big.append(" ".repeat(4 * 1024 * 1024 + 1));
        respond(HTTP_OK, big.toString());
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("response body exceeds"),
                "should complain about size limit");
    }

    @Test
    void shouldRejectInvalidJson() {
        respond(HTTP_OK, "not-json");
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().contains("parse") || ex.getCause() != null,
                "should contain parse failure");
    }

    @Test
    void shouldRejectExpiredManifest() {
        String expired = """
                {
                  "schemaVersion": 1,
                  "manifestId": "expired",
                  "revision": 0,
                  "generatedAt": "2020-01-01T00:00:00Z",
                  "expiresAt": "2020-01-02T00:00:00Z",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "some_mod": {
                      "name": "Some Mod",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "a.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "a.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""";
        respond(HTTP_OK, expired);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("expired"),
                "should complain about expired manifest");
    }

    @Test
    void shouldAcceptOnlySha256() {
        String only256 = """
                {
                  "schemaVersion": 1,
                  "manifestId": "only256",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "mm": {
                      "name": "M",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "m.jar",
                            "size": 100,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "m.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, only256);
        Manifest m = fetch();
        assertEquals("only256", m.manifestId());
    }

    @Test
    void shouldAcceptOnlySha512() {
        String only512 = """
                {
                  "schemaVersion": 1,
                  "manifestId": "only512",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "mm": {
                      "name": "MM",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "mm.jar",
                            "size": 200,
                            "hashes": { "sha512": "11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "mm.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, only512);
        Manifest m = fetch();
        assertEquals("only512", m.manifestId());
    }

    @Test
    void shouldRejectEmptyHashes() {
        String emptyHashes = """
                {
                  "schemaVersion": 1,
                  "manifestId": "emptyHashes",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "eh": {
                      "name": "EH",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "eh.jar",
                            "size": 1,
                            "hashes": {},
                            "download": { "type": "hosted", "url": "eh.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, emptyHashes);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        String msg = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(msg.contains("at least one") && msg.contains("hash"),
                "should reject empty hashes");
    }

    @Test
    void shouldRejectInvalidHashLength() {
        String badHash = """
                {
                  "schemaVersion": 1,
                  "manifestId": "badHash",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "bh": {
                      "name": "BH",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "b.jar",
                            "size": 1,
                            "hashes": { "sha256": "aaa" },
                            "download": { "type": "hosted", "url": "b.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, badHash);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("invalid"), "should reject invalid hash length");
    }

    @Test
    void shouldRejectUnknownDownloadType() {
        String unknownType = """
                {
                  "schemaVersion": 1,
                  "manifestId": "unknownType",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "ut": {
                      "name": "UT",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "u.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "ftp", "url": "u.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, unknownType);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("unsupported download type"), "should reject unknown download type");
    }

    @Test
    void shouldRejectMissingVersion() {
        String missingVersion = """
                {
                  "schemaVersion": 1,
                  "manifestId": "missingVersion",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "mv": {
                      "name": "MV",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "",
                            "fileName": "m.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "m.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, missingVersion);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("version"), "should reject blank version");
    }

    @Test
    void shouldRejectMissingFileName() {
        String missingFile = """
                {
                  "schemaVersion": 1,
                  "manifestId": "missingFile",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "mf": {
                      "name": "MF",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "f.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, missingFile);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("filename"), "should reject blank fileName");
    }

    @Test
    void shouldAcceptSelectorFiltersAndValidateEnum() {
        String selectorOk = """
                {
                  "schemaVersion": 1,
                  "manifestId": "selectorOk",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "sm": {
                      "name": "SM",
                      "required": false,
                      "variants": [
                        {
                          "selector": { "loaders": ["fabric"], "operatingSystems": ["linux","macos"], "architectures": ["x86_64","aarch64"] },
                          "priority": 0,
                          "artifact": {
                            "version": "1.0",
                            "fileName": "sm.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "sm.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, selectorOk);
        Manifest m = fetch();
        assertEquals("selectorOk", m.manifestId());
        assertEquals(1, m.modCount());
    }

    @Test
    void shouldRejectUnknownSelectorValue() {
        String badSelector = """
                {
                  "schemaVersion": 1,
                  "manifestId": "badSelector",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "bs": {
                      "name": "BS",
                      "required": true,
                      "variants": [
                        {
                          "selector": { "loaders": ["fabric","unknownLoader"] },
                          "priority": 0,
                          "artifact": {
                            "version": "1.0",
                            "fileName": "bs.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "bs.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, badSelector);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("unknown"), "should reject unknown loader");
    }

    @Test
    void shouldHandleDirectDownloadWithNumericIds() {
        String numericIds = """
                {
                  "schemaVersion": 1,
                  "manifestId": "numericIds",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "num": {
                      "name": "Num",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "2.0",
                            "fileName": "num.jar",
                            "size": 500,
                            "hashes": { "sha512": "11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111" },
                            "download": {
                              "type": "direct",
                              "url": "https://example.com/num.jar",
                              "provider": "modrinth",
                              "projectId": 54321,
                              "versionId": "123"
                            }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, numericIds);
        Manifest m = fetch();
        assertEquals(1, m.modCount());
    }

    @Test
    void shouldParseAllDownloadTypes() {
        String allDownloads = """
                {
                  "schemaVersion": 1,
                  "manifestId": "all-downloads",
                  "revision": 5,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "all_dl": {
                      "name": "All Downloads Mod",
                      "required": false,
                      "variants": [
                        {
                          "selector": { "loaders": ["fabric"], "operatingSystems": ["windows"] },
                          "priority": 10,
                          "artifact": {
                            "version": "1.0.0",
                            "fileName": "hosted.jar",
                            "size": 512,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": {
                              "type": "hosted",
                              "url": "relative/hosted.jar"
                            }
                          }
                        },
                        {
                          "selector": { "architectures": ["x86_64"] },
                          "required": true,
                          "priority": 5,
                          "artifact": {
                            "version": "2.0.0",
                            "fileName": "direct.jar",
                            "size": 1024,
                            "hashes": { "sha512": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
                            "download": {
                              "type": "direct",
                              "url": "https://cdn.example.com/mods/direct.jar",
                              "provider": "modrinth",
                              "projectId": 12345,
                              "versionId": "1.2"
                            }
                          }
                        },
                        {
                          "selector": {},
                          "artifact": {
                            "version": "3.0.0",
                            "fileName": "manual-one.jar",
                            "size": 2048,
                            "hashes": { "sha256": "2222222222222222222222222222222222222222222222222222222222222222" },
                            "download": {
                              "type": "manual",
                              "pageUrl": "https://example.com/downloads/manual-one",
                              "message": "Please download manually"
                            }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, allDownloads);
        Manifest m = fetch();

        assertEquals(1, m.modCount());
        Mod mod = m.mods().get("all_dl");
        assertNotNull(mod);
        assertEquals("All Downloads Mod", mod.name());
        assertFalse(mod.required());
        assertEquals(3, mod.variants().size());

        Variant hostedVariant = mod.variants().stream()
                .filter(v -> v.artifact().download() instanceof HostedDownload).findFirst().orElseThrow();
        HostedDownload hosted = (HostedDownload) hostedVariant.artifact().download();
        assertEquals("relative/hosted.jar", hosted.url());
        assertTrue(hostedVariant.selector().loaders().isPresent());
        assertEquals(List.of("fabric"), hostedVariant.selector().loaders().get());
        assertTrue(hostedVariant.selector().operatingSystems().isPresent());
        assertEquals(List.of("windows"), hostedVariant.selector().operatingSystems().get());
        assertEquals(10, hostedVariant.priority());

        Variant directVariant = mod.variants().stream()
                .filter(v -> v.artifact().download() instanceof DirectDownload).findFirst().orElseThrow();
        DirectDownload direct = (DirectDownload) directVariant.artifact().download();
        assertEquals("https://cdn.example.com/mods/direct.jar", direct.url());
        assertEquals(Optional.of("modrinth"), direct.provider());
        assertEquals(Optional.of("12345"), direct.projectId());
        assertEquals(Optional.of("1.2"), direct.versionId());
        assertTrue(directVariant.required());
        assertEquals(5, directVariant.priority());

        Variant manualVariant = mod.variants().stream()
                .filter(v -> v.artifact().download() instanceof ManualDownload).findFirst().orElseThrow();
        ManualDownload manual = (ManualDownload) manualVariant.artifact().download();
        assertEquals("https://example.com/downloads/manual-one", manual.pageUrl());
        assertEquals(Optional.of("Please download manually"), manual.message());
    }

    @Test
    void shouldRejectNonIntegerNumberForProjectId() {
        String j = """
                {
                  "schemaVersion": 1,
                  "manifestId": "rejectFloat",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "rf": {
                      "name": "rf",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "rf.jar",
                            "size": 10,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": {
                              "type": "direct",
                              "url": "https://example.com/rf.jar",
                              "provider": "modrinth",
                              "projectId": 1.5
                            }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, j);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("integer") ||
                ex.getMessage().contains("integer"));
    }

    @Test
    void shouldAcceptLargeIntegerBeyondLongRange() {
        String largeJson = """
                {
                  "schemaVersion": 1,
                  "manifestId": "largeInt",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "li": {
                      "name": "LI",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "li.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": {
                              "type": "direct",
                              "url": "https://example.com/li.jar",
                              "provider": "modrinth",
                              "projectId": 99999999999999999999
                            }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600)));
        respond(HTTP_OK, largeJson);
        Manifest m = fetch();
        assertEquals("largeInt", m.manifestId());
        Mod mod = m.mods().get("li");
        assertNotNull(mod);
        Variant v = mod.variants().get(0);
        Download dl = v.artifact().download();
        assertTrue(dl instanceof DirectDownload);
        DirectDownload d = (DirectDownload) dl;
        assertEquals(Optional.of("99999999999999999999"), d.projectId());
    }

    @Test
    void shouldUseDeleteAction() {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
                {
                  "schemaVersion": 1,
                  "manifestId": "action-delete",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "to-del": {
                      "name": "Delete Me",
                      "required": false,
                      "action": "delete"
                    }
                  }
                }""".formatted(timestamp);
        respond(HTTP_OK, json);
        Manifest m = fetch();
        Mod delMod = m.mods().get("to-del");
        assertNotNull(delMod);
        assertEquals(ModAction.DELETE, delMod.action());
        assertTrue(delMod.variants().isEmpty());
    }

    @Test
    void shouldRejectUnknownAction() {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
                {
                  "schemaVersion": 1,
                  "manifestId": "action-unknown",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "bad": {
                      "name": "Bad",
                      "required": false,
                      "action": "purge",
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "x.jar",
                            "size": 10,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "x.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(timestamp);
        respond(HTTP_OK, json);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("action"),
                "should mention action for unknown action");
    }

    @Test
    void shouldDefaultActionToInstall() {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
                {
                  "schemaVersion": 1,
                  "manifestId": "action-default",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "has": {
                      "name": "Has",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "d.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "d.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(timestamp);
        respond(HTTP_OK, json);
        Manifest m = fetch();
        Mod mod = m.mods().get("has");
        assertNotNull(mod);
        assertEquals(ModAction.INSTALL, mod.action());
    }

    @Test
    void shouldUseDeleteActionForVariant() {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
                {
                  "schemaVersion": 1,
                  "manifestId": "variant-action-delete",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "to-del": {
                      "name": "Delete Variant",
                      "required": false,
                      "variants": [
                        {
                          "selector": { "loaders": ["fabric"] },
                          "action": "delete"
                        }
                      ]
                    }
                  }
                }""".formatted(timestamp);
        respond(HTTP_OK, json);
        Manifest m = fetch();
        Variant v = m.mods().get("to-del").variants().get(0);
        assertEquals(ModAction.DELETE, v.action());
        assertTrue(v.artifact() == null);
    }

    @Test
    void shouldRejectUnknownVariantAction() {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
                {
                  "schemaVersion": 1,
                  "manifestId": "variant-action-unknown",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "bad": {
                      "name": "Bad Variant",
                      "required": false,
                      "variants": [
                        {
                          "selector": {},
                          "action": "purge"
                        }
                      ]
                    }
                  }
                }""".formatted(timestamp);
        respond(HTTP_OK, json);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("action"),
                "should mention action for unknown variant action");
    }

    @Test
    void shouldAcceptMinimumLoaderVersions() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "with-min-loader",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "minimumLoaderVersions": { "fabric": "0.14.0" },
                  "mods": {
                    "ml": {
                      "name": "MinLoaderMod",
                      "required": true,
                      "variants": [
                        {
                          "selector": {
                            "loaders": ["fabric"]
                          },
                          "artifact": {
                            "version": "1.0",
                            "fileName": "min.jar",
                            "size": 10,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "min.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        Manifest m = fetch();
        Mod mod = m.mods().get("ml");
        assertNotNull(mod);
        assertTrue(m.minimumLoaderVersions().isPresent());
        assertEquals("0.14.0", m.minimumLoaderVersions().get().get("fabric"));
    }

    @Test
    void shouldRejectUnknownLoaderInMinimumLoaderVersions() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "unknown-loader",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "minimumLoaderVersions": { "quilt": "1.0" },
                  "mods": {
                    "ul": {
                      "name": "UnknownLoaderMod",
                      "required": true,
                      "variants": [
                        {
                          "selector": {
                            "loaders": ["fabric"]
                          },
                          "artifact": {
                            "version": "1.0",
                            "fileName": "ul.jar",
                            "size": 10,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "ul.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("unknown loader"),
                "should mention unknown loader");
    }

    @Test
    void shouldRejectEmptyMinimumLoaderVersionsObject() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "empty-min-ver",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "minimumLoaderVersions": {},
                  "mods": {
                    "ev": {
                      "name": "EmptyVer",
                      "required": true,
                      "variants": [
                        {
                          "selector": {
                            "loaders": ["fabric"]
                          },
                          "artifact": {
                            "version": "1.0",
                            "fileName": "ev.jar",
                            "size": 10,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "ev.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("minimumloaderversions"),
                "should complain about empty minimumLoaderVersions");
    }

    @Test
    void shouldRejectMultipleLoaderVersions() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "multi-loader",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "minimumLoaderVersions": { "fabric": "0.16.0", "neoforge": "21.1.0" },
                  "mods": {
                    "ml": {
                      "name": "MultiLoaderMod",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "m.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "m.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("exactly one"),
                "Should reject multiple loader versions");
    }

    @Test
    void shouldAcceptForgeLoaderKey() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "forge-loader",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "minimumLoaderVersions": { "forge": "43.2.0" },
                  "mods": {
                    "fl": {
                      "name": "ForgeLoad",
                      "required": true,
                      "variants": [
                        {
                          "selector": {},
                          "artifact": {
                            "version": "1.0",
                            "fileName": "f.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "f.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        Manifest m = fetch();
        assertTrue(m.minimumLoaderVersions().isPresent());
        assertEquals("43.2.0", m.minimumLoaderVersions().get().get("forge"));
    }

    @Test
    void shouldAcceptSkipIfInstalledVersionGreaterThan() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
            {
              "schemaVersion": 1,
              "manifestId": "skip-ver",
              "revision": 0,
              "generatedAt": "%s",
              "minecraftVersion": "1.21.1",
              "mods": {
                "sv": {
                  "name": "SkipVers",
                  "required": true,
                  "skipIfInstalledVersionGreaterThan": "1.2.0",
                  "variants": [
                    {
                      "selector": {},
                      "artifact": {
                        "version": "1.2.0",
                        "fileName": "sv.jar",
                        "size": 1,
                        "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                        "download": { "type": "hosted", "url": "sv.jar" }
                      }
                    }
                  ]
                }
              }
            }""".formatted(time);
        respond(HTTP_OK, json);
        Manifest m = fetch();
        Mod mod = m.mods().get("sv");
        assertNotNull(mod);
        assertTrue(mod.skipIfInstalledVersionGreaterThan().isPresent());
        assertEquals("1.2.0", mod.skipIfInstalledVersionGreaterThan().get());
    }

    @Test
    void shouldRejectBlankSkipIfInstalledVersionGreaterThan() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String json = """
            {
              "schemaVersion": 1,
              "manifestId": "blank-skip",
              "revision": 0,
              "generatedAt": "%s",
              "minecraftVersion": "1.21.1",
              "mods": {
                "bs": {
                  "name": "BlankSkip",
                  "required": true,
                  "skipIfInstalledVersionGreaterThan": "   ",
                  "variants": [
                    {
                      "selector": {},
                      "artifact": {
                        "version": "2.0.0",
                        "fileName": "bs.jar",
                        "size": 1,
                        "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                        "download": { "type": "hosted", "url": "bs.jar" }
                      }
                    }
                  ]
                }
              }
            }""".formatted(time);
        respond(HTTP_OK, json);
        ManifestFetchException ex = assertThrows(ManifestFetchException.class, this::fetch);
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("blank"),
                "should reject blank skipIfInstalledVersionGreaterThan");
    }

    @Test
    void shouldAcceptForgeInSelectorLoader() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
        String body = """
                {
                  "schemaVersion": 1,
                  "manifestId": "forge-in-selector",
                  "revision": 0,
                  "generatedAt": "%s",
                  "minecraftVersion": "1.21.1",
                  "mods": {
                    "fis": {
                      "name": "ForgeInSel",
                      "required": false,
                      "variants": [
                        {
                          "selector": { "loaders": ["forge"] },
                          "artifact": {
                            "version": "1.0",
                            "fileName": "fis.jar",
                            "size": 1,
                            "hashes": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                            "download": { "type": "hosted", "url": "fis.jar" }
                          }
                        }
                      ]
                    }
                  }
                }""".formatted(time);
        respond(HTTP_OK, body);
        Manifest m = fetch();
        assertEquals("forge-in-selector", m.manifestId());
        assertEquals(1, m.modCount());
    }
}
