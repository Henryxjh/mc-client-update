package io.github.henryxjh.mcclientupdate.server.workspace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
                          "required": true,
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
        assertEquals(Boolean.TRUE, variant.required());
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

    @Test
    void editCreatesWorkspaceAndParsesWrittenValues() throws Exception {
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);

        ManifestWorkspaceApi.WorkspaceWriteResult written = api.workspace().edit(editor -> {
            editor.setManifestId("managed-pack")
                    .setRevision(2)
                    .setMinecraftVersion("1.21.1")
                    .setBaseUrl("https://cdn.example.test/")
                    .setMinimumLoaderVersion("fabric", "0.16.14");
            editor.mod("private_mod")
                    .setName("Private Mod")
                    .setRequired(true)
                    .setLicense("")
                    .setHomepage("https://example.test/private")
                    .addOrReplaceVariant(new ManifestWorkspaceApi.WorkspaceVariant(
                            new ManifestWorkspaceApi.WorkspaceSelector(
                                    List.of("fabric"),
                                    List.of("linux"),
                                    List.of("x86_64")),
                            Boolean.TRUE,
                            "install",
                            5,
                            "1.0.0",
                            "private_mod.jar",
                            42L,
                            "/srv/mods/private_mod.jar",
                            new ManifestWorkspaceApi.WorkspaceDownload(
                                    "direct",
                                    "https://cdn.example.test/private_mod.jar",
                                    null),
                            Map.of("sha512", "abc")));
            editor.mod("removed_dep").markDelete();
        });

        assertFalse(written.existedBefore());
        assertTrue(written.changed());
        assertTrue(Files.exists(gameDir.resolve("manifest-workspace.json")));

        ManifestWorkspaceApi.Workspace workspace = written.workspace();
        assertEquals("managed-pack", workspace.manifestId());
        assertEquals(2, workspace.revision());
        assertEquals(Map.of("fabric", "0.16.14"), workspace.minimumLoaderVersions());

        ManifestWorkspaceApi.WorkspaceMod privateMod = workspace.mods().get("private_mod");
        assertEquals("", privateMod.license());
        ManifestWorkspaceApi.WorkspaceVariant variant = privateMod.variants().get(0);
        assertEquals(Boolean.TRUE, variant.required());
        assertEquals("private_mod.jar", variant.fileName());
        assertEquals("direct", variant.download().type());
        assertEquals(Map.of("sha512", "abc"), variant.hashes());

        ManifestWorkspaceApi.WorkspaceMod removedDep = workspace.mods().get("removed_dep");
        assertEquals("delete", removedDep.action());
        assertFalse(removedDep.required());
    }

    @Test
    void editPreservesUnknownFieldsAndCanReplaceOrRemoveVariantBySelector() throws Exception {
        Files.writeString(gameDir.resolve("manifest-workspace.json"), """
                {
                  "manifestId": "old",
                  "unknownTop": {"kept": true},
                  "mods": {
                    "create": {
                      "name": "Create",
                      "unknownMod": "keep-me",
                      "variants": [
                        {
                          "selector": {"loaders": ["fabric"]},
                          "fileName": "old.jar",
                          "unknownVariant": "will-be-replaced"
                        },
                        {
                          "selector": {"loaders": ["neoforge"]},
                          "fileName": "keep.jar",
                          "unknownVariant": "keep-me"
                        }
                      ]
                    },
                    "remove_me": {
                      "name": "Remove Me"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);
        ManifestWorkspaceApi.WorkspaceSelector fabricSelector = new ManifestWorkspaceApi.WorkspaceSelector(
                List.of("fabric"),
                List.of(),
                List.of());

        ManifestWorkspaceApi.WorkspaceWriteResult written = api.workspace().edit(editor -> {
            editor.setManifestId("new");
            editor.removeMod("remove_me");
            editor.mod("create")
                    .setLicense("")
                    .addOrReplaceVariant(new ManifestWorkspaceApi.WorkspaceVariant(
                            fabricSelector,
                            null,
                            "install",
                            0,
                            "2.0.0",
                            "new.jar",
                            null,
                            null,
                            null,
                            Map.of()));
            editor.mod("create").removeVariant(new ManifestWorkspaceApi.WorkspaceSelector(
                    List.of("neoforge"),
                    List.of(),
                    List.of()));
        });

        assertTrue(written.existedBefore());
        assertTrue(written.changed());
        assertEquals("new", written.workspace().manifestId());
        assertFalse(written.workspace().mods().containsKey("remove_me"));
        assertEquals("", written.workspace().mods().get("create").license());

        JsonObject raw = JsonParser.parseString(Files.readString(gameDir.resolve("manifest-workspace.json")))
                .getAsJsonObject();
        assertTrue(raw.getAsJsonObject("unknownTop").get("kept").getAsBoolean());
        JsonObject create = raw.getAsJsonObject("mods").getAsJsonObject("create");
        assertEquals("keep-me", create.get("unknownMod").getAsString());
        assertEquals(1, create.getAsJsonArray("variants").size());
        JsonObject onlyVariant = create.getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("new.jar", onlyVariant.get("fileName").getAsString());
        assertFalse(onlyVariant.has("unknownVariant"));
    }

    @Test
    void editVariantLocalFileAndDownloadUrlPreservesUnknownVariantFields() throws Exception {
        Files.writeString(gameDir.resolve("manifest-workspace.json"), """
                {
                  "mods": {
                    "create": {
                      "variants": [
                        {
                          "selector": {"loaders": ["fabric"]},
                          "localFile": "/old/create.jar",
                          "download": {
                            "type": "direct",
                            "url": "https://old.example.test/create.jar",
                            "unknownDownload": "keep-me"
                          },
                          "unknownVariant": "keep-me"
                        }
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);
        ManifestWorkspaceApi.WorkspaceSelector fabricSelector = new ManifestWorkspaceApi.WorkspaceSelector(
                List.of("fabric"),
                List.of(),
                List.of());

        api.workspace().edit(editor -> editor.mod("create")
                .variant(fabricSelector)
                .setRequired(true)
                .setLocalFile("/new/create.jar")
                .setDownloadUrl("https://new.example.test/create.jar"));

        ManifestWorkspaceApi.WorkspaceVariant parsed = api.workspace()
                .findMod("create")
                .orElseThrow()
                .variants()
                .get(0);
        assertEquals(Boolean.TRUE, parsed.required());
        assertEquals("/new/create.jar", parsed.localFile());
        assertEquals("https://new.example.test/create.jar", parsed.download().url());

        JsonObject rawVariant = JsonParser.parseString(Files.readString(gameDir.resolve("manifest-workspace.json")))
                .getAsJsonObject()
                .getAsJsonObject("mods")
                .getAsJsonObject("create")
                .getAsJsonArray("variants")
                .get(0)
                .getAsJsonObject();
        assertEquals("keep-me", rawVariant.get("unknownVariant").getAsString());
        assertEquals("keep-me", rawVariant.getAsJsonObject("download").get("unknownDownload").getAsString());
        assertTrue(rawVariant.get("required").getAsBoolean());

        api.workspace().edit(editor -> editor.mod("create")
                .variant(fabricSelector)
                .clearRequired());

        ManifestWorkspaceApi.WorkspaceVariant cleared = api.workspace()
                .findMod("create")
                .orElseThrow()
                .variants()
                .get(0);
        assertNull(cleared.required());
    }

    @Test
    void editVariantLocalFileAndDownloadUrlCanCreateMissingVariant() {
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);
        ManifestWorkspaceApi.WorkspaceSelector androidSelector = new ManifestWorkspaceApi.WorkspaceSelector(
                List.of("neoforge"),
                List.of("android"),
                List.of("aarch64"));

        ManifestWorkspaceApi.WorkspaceWriteResult written = api.workspace().edit(editor -> editor.mod("sable")
                .variant(androidSelector)
                .setRequired(true)
                .setLocalFile("/srv/mods/sable-android.jar")
                .setDownloadType("direct")
                .setDownloadUrl("https://cdn.example.test/sable-android.jar"));

        ManifestWorkspaceApi.WorkspaceVariant variant = written.workspace()
                .mods()
                .get("sable")
                .variants()
                .get(0);
        assertEquals(List.of("neoforge"), variant.selector().loaders());
        assertEquals(Boolean.TRUE, variant.required());
        assertEquals(List.of("android"), variant.selector().operatingSystems());
        assertEquals(List.of("aarch64"), variant.selector().architectures());
        assertEquals("/srv/mods/sable-android.jar", variant.localFile());
        assertEquals("direct", variant.download().type());
        assertEquals("https://cdn.example.test/sable-android.jar", variant.download().url());
    }
}
