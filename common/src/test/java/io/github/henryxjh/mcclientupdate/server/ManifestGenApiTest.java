package io.github.henryxjh.mcclientupdate.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestGenApiTest {

    @TempDir
    Path gameDir;

    @AfterEach
    void resetSharedApi() {
        ManifestGenApi.resetForTests();
    }

    @Test
    void registerAndGetReturnSharedCommonApiInstance() {
        ManifestGenApi.register(gameDir, "fabric", () -> "1.21.1", List::of);

        assertTrue(ManifestGenApi.isRegistered());
        ManifestGenApi first = ManifestGenApi.get();
        ManifestGenApi second = ManifestGenApi.get();

        assertSame(first, second);
        assertEquals(gameDir, first.gameDirectory());
        assertEquals("fabric", first.loader());
        assertEquals("1.21.1", first.minecraftVersion());
    }

    @Test
    void generateUsesSameWorkspaceGeneratorAndReturnsMessages() throws Exception {
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path jar = createFabricModJar(modsDir, "example", "1.2.3");
        InstalledMod mod = new InstalledMod("example", "1.2.3", jar);
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", () -> List.of(mod));

        List<String> streamed = new ArrayList<>();
        ManifestGenApi.GenerateResult result = api.generate("pack-id", streamed::add);

        assertEquals(1, result.installedModCount());
        assertEquals("pack-id", result.workspaceResult().manifestId());
        assertEquals(1, result.workspaceResult().added());
        assertTrue(streamed.contains("Scanning 1 loaded mods..."));
        assertTrue(streamed.contains("Manifest ID: pack-id"));
        assertEquals(streamed, result.messages());

        Path workspace = gameDir.resolve("manifest-workspace.json");
        JsonObject root = JsonParser.parseString(Files.readString(workspace)).getAsJsonObject();
        assertEquals("pack-id", root.get("manifestId").getAsString());
        assertTrue(root.getAsJsonObject("mods").has("example"));
    }

    @Test
    void ignoreApiAddsListsAndRemovesIgnoredMods() {
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);

        ManifestGenApi.IgnoreChangeResult added = api.addIgnoredMod("example");
        assertTrue(added.changed());
        assertEquals("Added \"example\" to ignored mods.", added.message());
        assertTrue(api.listIgnoredMods().ignoredMods().contains("example"));

        ManifestGenApi.IgnoreChangeResult duplicate = api.addIgnoredMod("example");
        assertFalse(duplicate.changed());
        assertEquals("\"example\" is already ignored.", duplicate.message());

        ManifestGenApi.IgnoreListResult listed = api.listIgnoredMods();
        assertTrue(listed.message().contains("Ignored mods"));
        assertTrue(listed.ignoredMods().contains("example"));

        ManifestGenApi.IgnoreChangeResult removed = api.removeIgnoredMod("example");
        assertTrue(removed.changed());
        assertEquals("Removed \"example\" from ignored mods.", removed.message());

        ManifestGenApi.IgnoreChangeResult missing = api.removeIgnoredMod("example");
        assertFalse(missing.changed());
        assertEquals("\"example\" is not in ignored list.", missing.message());
    }

    @Test
    void allowedUserApiAddsListsChecksAndRemovesUsers() {
        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1", List::of);

        assertFalse(api.isUserAllowed("alice"));
        ManifestGenApi.AllowedUserChangeResult added = api.addAllowedUser("alice");
        assertTrue(added.changed());
        assertEquals("Added \"alice\" to allowed users.", added.message());
        assertTrue(added.allowedUsers().contains("alice"));
        assertTrue(api.isUserAllowed("alice"));

        ManifestGenApi.AllowedUserChangeResult duplicate = api.addAllowedUser("alice");
        assertFalse(duplicate.changed());
        assertEquals("\"alice\" is already allowed.", duplicate.message());

        ManifestGenApi.AllowedUserListResult listed = api.listAllowedUsers();
        assertEquals(List.of("alice"), listed.allowedUsers());
        assertEquals("Allowed users (1): alice", listed.message());

        ManifestGenApi.AllowedUserChangeResult removed = api.removeAllowedUser("alice");
        assertTrue(removed.changed());
        assertEquals("Removed \"alice\" from allowed users.", removed.message());
        assertFalse(api.isUserAllowed("alice"));

        ManifestGenApi.AllowedUserChangeResult missing = api.removeAllowedUser("alice");
        assertFalse(missing.changed());
        assertEquals("\"alice\" is not in allowed users.", missing.message());
        assertEquals("No player users are allowed; console only.", api.listAllowedUsers().message());
    }

    @Test
    void suggestionsRespectIgnoredModsAndModsDirectory() throws Exception {
        Path modsDir = gameDir.resolve("mods");
        Path elsewhere = gameDir.resolve("elsewhere");
        Files.createDirectories(modsDir);
        Files.createDirectories(elsewhere);

        Path includedJar = modsDir.resolve("included.jar");
        Path ignoredJar = modsDir.resolve("ignored.jar");
        Path externalJar = elsewhere.resolve("external.jar");
        Files.writeString(includedJar, "included");
        Files.writeString(ignoredJar, "ignored");
        Files.writeString(externalJar, "external");

        ManifestGenApi api = new ManifestGenApi(gameDir, "fabric", "1.21.1",
                () -> List.of(
                        new InstalledMod("included", "1", includedJar),
                        new InstalledMod("ignored", "1", ignoredJar),
                        new InstalledMod("external", "1", externalJar)));
        api.addIgnoredMod("ignored");

        assertEquals(List.of("included"), api.suggestIgnoreAddModIds());
        assertTrue(api.suggestIgnoreRemoveModIds().contains("ignored"));
    }

    private static Path createFabricModJar(Path modsDir, String modId, String version) throws Exception {
        Path jar = modsDir.resolve(modId + "-" + version + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("dummy.class"));
            out.write("data".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            JsonObject fabricJson = new JsonObject();
            fabricJson.addProperty("schemaVersion", 1);
            fabricJson.addProperty("id", modId);
            fabricJson.addProperty("version", version);
            fabricJson.addProperty("name", modId);
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write(fabricJson.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
