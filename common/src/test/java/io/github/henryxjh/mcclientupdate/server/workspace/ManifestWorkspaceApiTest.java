package io.github.henryxjh.mcclientupdate.server.workspace;

import io.github.henryxjh.mcclientupdate.server.ManifestGenApi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestWorkspaceApiTest {

    @TempDir
    Path gameDir;

    @Test
    void loadReturnsEmptyWorkspaceWhenFileIsMissing() {
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);

        ManifestWorkspaceApi.WorkspaceLoadResult result = api.workspace().load();

        assertFalse(result.exists());
        assertEquals(gameDir.resolve("manifest-workspace.json"), result.path());
        assertTrue(result.workspace().mods().isEmpty());
        assertEquals(1, result.workspace().revision());
    }

    @Test
    void loadParsesWorkspaceIntoTypedView() throws Exception {
        Files.writeString(gameDir.resolve("manifest-workspace.json"), """
                {
                  "manifestId": "tech-pack",
                  "revision": 7,
                  "minecraftVersion": "1.21.1",
                  "baseUrl": "https://cdn.example.test/mods/",
                  "expiresAt": "2026-08-01T00:00:00Z",
                  "minimumLoaderVersions": {
                    "neoforge": "21.1.200"
                  },
                  "mods": {
                    "create": {
                      "name": "Create",
                      "required": true,
                      "license": "MIT",
                      "homepage": "https://example.test/create",
                      "skipIfInstalledVersionGreaterThan": "6.0.0",
                      "variants": [
                        {
                          "selector": {
                            "loaders": ["neoforge"],
                            "operatingSystems": ["linux"],
                            "architectures": ["x86_64", "loongarch64"]
                          },
                          "action": "install",
                          "priority": 3,
                          "version": "6.0.1",
                          "fileName": "create.jar",
                          "size": 12345,
                          "localFile": "/srv/mods/create.jar",
                          "download": {
                            "type": "direct",
                            "url": "https://cdn.example.test/create.jar"
                          },
                          "hashes": {
                            "sha256": "abc",
                            "sha512": "def"
                          }
                        }
                      ]
                    },
                    "olddep": {
                      "name": "Old Dependency",
                      "required": false,
                      "action": "delete"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ManifestGenApi api = new ManifestGenApi(gameDir, "neoforge", "1.21.1", List::of);
        ManifestWorkspaceApi.WorkspaceLoadResult loaded = api.workspace().load();
        ManifestWorkspaceApi.Workspace workspace = loaded.workspace();

        assertTrue(loaded.exists());
        assertEquals("tech-pack", workspace.manifestId());
        assertEquals(7, workspace.revision());
        assertEquals("1.21.1", workspace.minecraftVersion());
        assertEquals("https://cdn.example.test/mods/", workspace.baseUrl());
        assertEquals("2026-08-01T00:00:00Z", workspace.expiresAt());
        assertEquals(Map.of("neoforge", "21.1.200"), workspace.minimumLoaderVersions());

        ManifestWorkspaceApi.WorkspaceMod create = api.workspace().findMod("create").orElseThrow();
        assertEquals("create", create.modId());
        assertEquals("Create", create.name());
        assertTrue(create.required());
        assertEquals("install", create.action());
        assertEquals("MIT", create.license());
        assertEquals("https://example.test/create", create.homepage());
        assertEquals("6.0.0", create.skipIfInstalledVersionGreaterThan());

        ManifestWorkspaceApi.WorkspaceVariant variant = create.variants().get(0);
        assertEquals("install", variant.action());
        assertEquals(3, variant.priority());
        assertEquals("6.0.1", variant.version());
        assertEquals("create.jar", variant.fileName());
        assertEquals(12345L, variant.size());
        assertEquals("/srv/mods/create.jar", variant.localFile());
        assertEquals(List.of("neoforge"), variant.selector().loaders());
        assertEquals(List.of("linux"), variant.selector().operatingSystems());
        assertEquals(List.of("x86_64", "loongarch64"), variant.selector().architectures());
        assertEquals("direct", variant.download().type());
        assertEquals("https://cdn.example.test/create.jar", variant.download().url());
        assertNull(variant.download().pageUrl());
        assertEquals(Map.of("sha256", "abc", "sha512", "def"), variant.hashes());

        ManifestWorkspaceApi.WorkspaceMod olddep = api.workspace().findMod("olddep").orElseThrow();
        assertEquals("delete", olddep.action());
        assertTrue(olddep.variants().isEmpty());

        ManifestWorkspaceApi.WorkspaceModListResult listed = api.workspace().listMods();
        assertEquals(List.of("create", "olddep"),
                listed.mods().stream().map(ManifestWorkspaceApi.WorkspaceMod::modId).toList());
        assertFalse(api.workspace().findMod("missing").isPresent());
    }
}
