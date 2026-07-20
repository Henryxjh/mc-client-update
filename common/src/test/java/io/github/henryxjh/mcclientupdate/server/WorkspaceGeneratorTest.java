package io.github.henryxjh.mcclientupdate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

class WorkspaceGeneratorTest {

    @TempDir
    Path tempDir;

    /** Helper: write a tiny {@code mcu-manifest-gen.json} config with given ignored mods. */
    private void writeConfig(Path gameDir, List<String> ignoredMods) throws IOException {
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        String json = configJson(ignoredMods, List.of());
        Files.writeString(configDir.resolve("mcu-manifest-gen.json"), json);
    }

    private String configJson(List<String> ignoredMods, List<String> allowedUsers) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"ignoredMods\":[");
        for (int i = 0; i < ignoredMods.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(ignoredMods.get(i)).append("\"");
        }
        sb.append("],\"allowedUsers\":[");
        for (int i = 0; i < allowedUsers.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(allowedUsers.get(i)).append("\"");
        }
        sb.append("],\"outputPath\":\"workspace.json\"}");
        return sb.toString();
    }

    private Path createModJar(Path modsDir, String modId, String version, String fileName, String loader, String license) throws Exception {
        Path jar = modsDir.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            // entry to give the jar a non‑zero size
            jos.putNextEntry(new ZipEntry("dummy.class"));
            jos.write("data".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            if ("fabric".equals(loader)) {
                JsonObject fabricJson = new JsonObject();
                fabricJson.addProperty("schemaVersion", 1);
                fabricJson.addProperty("id", modId);
                fabricJson.addProperty("version", version);
                fabricJson.addProperty("name", modId);
                if (license != null && !license.isBlank()) {
                    fabricJson.addProperty("license", license);
                }
                jos.putNextEntry(new ZipEntry("fabric.mod.json"));
                jos.write(fabricJson.toString().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            } else if ("neoforge".equals(loader)) {
                StringBuilder toml = new StringBuilder();
                toml.append("modId=\"").append(modId).append("\"\n");
                toml.append("version=\"").append(version).append("\"\n");
                if (license != null && !license.isBlank()) {
                    toml.append("license=\"").append(license).append("\"\n");
                }
                jos.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
                jos.write(toml.toString().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** Read the output workspace file as a JsonObject. */
    private JsonObject readWorkspace(Path gameDir) throws IOException {
        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path output = cfg.getOutputPath(gameDir);
        assertTrue(Files.exists(output), "workspace file missing: " + output);
        return JsonParser.parseReader(Files.newBufferedReader(output, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    // -----------------------------------------------------------------

    @Test
    void generate_createsWorkspaceForFirstRun_fabric() throws Exception {
        Path gameDir = tempDir.resolve("server1");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        Path jar = createModJar(gameDir.resolve("mods"), "testmod", "1.0.0", "testmod-1.0.0.jar", "fabric", "MIT");
        List<InstalledMod> mods = List.of(new InstalledMod("testmod", "1.0.0", jar));

        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir, mods, "fabric", "1.21.1",
                ManifestGenConfig.load(gameDir), null,
                msg -> {});

        assertEquals("1.21.1-fabric", result.manifestId());
        assertEquals(1, result.scanned());
        assertEquals(1, result.added());
        assertEquals(0, result.updated());

        JsonObject workspace = readWorkspace(gameDir);
        assertEquals("1.21.1-fabric", workspace.get("manifestId").getAsString());
        JsonObject modsObj = workspace.getAsJsonObject("mods");
        assertTrue(modsObj.has("testmod"));
        JsonObject entry = modsObj.getAsJsonObject("testmod");
        assertEquals("testmod", entry.get("name").getAsString());
        assertEquals("MIT", entry.get("license").getAsString());
        JsonArray variants = entry.getAsJsonArray("variants");
        assertEquals(1, variants.size());
        JsonObject variant = variants.get(0).getAsJsonObject();
        assertEquals("1.0.0", variant.get("version").getAsString());
        assertEquals("testmod-1.0.0.jar", variant.get("fileName").getAsString());
        assertEquals("fabric", variant.getAsJsonObject("selector").getAsJsonArray("loaders").get(0).getAsString());
        JsonObject hashes = variant.getAsJsonObject("hashes");
        assertNotNull(hashes.get("sha256").getAsString());
        assertNotNull(hashes.get("sha512").getAsString());
        JsonObject dl = variant.getAsJsonObject("download");
        assertEquals("hosted", dl.get("type").getAsString());
        assertEquals("TODO", dl.get("url").getAsString());
    }

    @Test
    void generate_preservesExistingManifestIdWhenNoArgumentGiven() throws Exception {
        Path gameDir = tempDir.resolve("server2");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        // pre‑create workspace with a known manifestId
        JsonObject pre = new JsonObject();
        pre.addProperty("manifestId", "existing-pack");
        pre.add("mods", new JsonObject());
        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path out = cfg.getOutputPath(gameDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, pre.toString());

        Path jar = createModJar(gameDir.resolve("mods"), "x", "1", "x.jar", "fabric", null);
        WorkspaceGenerator.generate(gameDir, List.of(new InstalledMod("x", "1", jar)),
                "fabric", "1.21.1", cfg, null, msg -> {});

        assertEquals("existing-pack", readWorkspace(gameDir).get("manifestId").getAsString());
    }

    @Test
    void generate_overwritesManifestIdWhenArgumentProvided() throws Exception {
        Path gameDir = tempDir.resolve("server3");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        Path jar = createModJar(gameDir.resolve("mods"), "m", "1", "m.jar", "fabric", null);
        WorkspaceGenerator.generate(gameDir, List.of(new InstalledMod("m", "1", jar)),
                "fabric", "1.21.1", ManifestGenConfig.load(gameDir),
                "custom-id", msg -> {});

        assertEquals("custom-id", readWorkspace(gameDir).get("manifestId").getAsString());
    }

    @Test
    void generate_updateExistingVariantPreservesCustomFields() throws Exception {
        Path gameDir = tempDir.resolve("server4");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        // pre‑create workspace that already has a variant with custom homepage/license/download
        JsonObject root = new JsonObject();
        root.addProperty("manifestId", "keep");
        JsonObject modsObj = new JsonObject();
        JsonObject mod = new JsonObject();
        mod.addProperty("name", "modA");
        mod.addProperty("required", true);
        mod.addProperty("license", "Apache-2.0");
        JsonArray variants = new JsonArray();
        JsonObject v = new JsonObject();
        JsonObject sel = new JsonObject();
        JsonArray loaders = new JsonArray();
        loaders.add("fabric");
        sel.add("loaders", loaders);
        v.add("selector", sel);
        v.addProperty("version", "old-version");
        v.addProperty("fileName", "old.jar");
        v.addProperty("size", 999);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha256", "aaa");
        hashes.addProperty("sha512", "bbb");
        v.add("hashes", hashes);
        JsonObject dl = new JsonObject();
        dl.addProperty("type", "hosted");
        dl.addProperty("url", "https://example.com/old.jar");
        v.add("download", dl);
        v.addProperty("homepage", "https://mod.page");
        variants.add(v);
        mod.add("variants", variants);
        modsObj.add("modA", mod);
        root.add("mods", modsObj);

        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path out = cfg.getOutputPath(gameDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toString());

        Path jar = createModJar(gameDir.resolve("mods"), "modA", "2.0.0", "modA-2.0.0.jar", "fabric", "Apache-2.0");
        WorkspaceGenerator.generate(gameDir, List.of(new InstalledMod("modA", "2.0.0", jar)),
                "fabric", "1.21.1", cfg, null, msg -> {});

        JsonObject workspace = readWorkspace(gameDir);
        JsonObject modEntry = workspace.getAsJsonObject("mods").getAsJsonObject("modA");
        JsonObject variant = modEntry.getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("2.0.0", variant.get("version").getAsString());
        assertEquals("modA-2.0.0.jar", variant.get("fileName").getAsString());
        assertNotEquals("aaa", variant.getAsJsonObject("hashes").get("sha256").getAsString());
        assertEquals("https://example.com/old.jar", variant.getAsJsonObject("download").get("url").getAsString());
        assertEquals("https://mod.page", variant.get("homepage").getAsString());
    }

    @Test
    void generate_uninstalledRequiredModAddsDeleteAction() throws Exception {
        Path gameDir = tempDir.resolve("server5");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        JsonObject root = new JsonObject();
        root.addProperty("manifestId", "t");
        JsonObject modsObj = new JsonObject();
        JsonObject mod = new JsonObject();
        mod.addProperty("required", true);
        JsonArray variants = new JsonArray();
        JsonObject v = new JsonObject();
        JsonObject sel = new JsonObject();
        JsonArray loaders = new JsonArray();
        loaders.add("fabric");
        sel.add("loaders", loaders);
        v.add("selector", sel);
        variants.add(v);
        mod.add("variants", variants);
        modsObj.add("gone", mod);
        root.add("mods", modsObj);

        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path out = cfg.getOutputPath(gameDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toString());

        WorkspaceGenerator.generate(gameDir, List.of(), "fabric", "1.21.1", cfg, null, msg -> {});

        JsonObject ws = readWorkspace(gameDir);
        JsonObject entry = ws.getAsJsonObject("mods").getAsJsonObject("gone");
        JsonArray arr = entry.getAsJsonArray("variants");
        assertEquals(1, arr.size());
        JsonObject var = arr.get(0).getAsJsonObject();
        assertEquals("delete", var.get("action").getAsString());
        assertTrue(var.has("selector"));
    }

    @Test
    void generate_uninstalledNotRequiredModRemovesVariant() throws Exception {
        Path gameDir = tempDir.resolve("server6");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        JsonObject root = new JsonObject();
        root.addProperty("manifestId", "t");
        JsonObject modsObj = new JsonObject();
        JsonObject mod = new JsonObject();
        mod.addProperty("required", false);
        JsonArray variants = new JsonArray();
        JsonObject v = new JsonObject();
        JsonObject sel = new JsonObject();
        JsonArray loaders = new JsonArray();
        loaders.add("fabric");
        sel.add("loaders", loaders);
        v.add("selector", sel);
        variants.add(v);
        mod.add("variants", variants);
        modsObj.add("opt", mod);
        root.add("mods", modsObj);

        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path out = cfg.getOutputPath(gameDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toString());

        WorkspaceGenerator.generate(gameDir, List.of(), "fabric", "1.21.1", cfg, null, msg -> {});

        JsonObject ws = readWorkspace(gameDir);
        assertFalse(ws.getAsJsonObject("mods").has("opt"));
    }

    @Test
    void generate_skipsEntriesOutsideModsFolder() throws Exception {
        Path gameDir = tempDir.resolve("server7");
        Files.createDirectories(gameDir.resolve("mods"));
        Path outside = Files.createTempFile(tempDir, "outside-", ".jar");
        writeConfig(gameDir, List.of());

        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir,
                List.of(new InstalledMod("outsider", "1", outside)),
                "fabric", "1.21.1",
                ManifestGenConfig.load(gameDir), null, msg -> {});

        assertEquals(1, result.skippedNonModFolder());
        assertEquals(0, result.scanned());
    }

    @Test
    void generate_skipsIgnoredMods() throws Exception {
        Path gameDir = tempDir.resolve("server8");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        writeConfig(gameDir, List.of("skip-me"));

        Path jar = createModJar(modsDir, "skip-me", "1", "skip-me.jar", "fabric", null);
        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir,
                List.of(new InstalledMod("skip-me", "1", jar)),
                "fabric", "1.21.1",
                ManifestGenConfig.load(gameDir), null,
                msg -> {});

        assertEquals(1, result.skippedIgnored());
        assertEquals(0, result.scanned());
    }

    @Test
    void generate_skipsDuplicatePhysicalJar() throws Exception {
        Path gameDir = tempDir.resolve("server9");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        writeConfig(gameDir, List.of());

        Path sharedJar = createModJar(modsDir, "parent", "1", "shared.jar", "fabric", null);
        InstalledMod first = new InstalledMod("parent", "1", sharedJar);
        InstalledMod second = new InstalledMod("child", "1", sharedJar);

        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir, List.of(first, second),
                "fabric", "1.21.1",
                ManifestGenConfig.load(gameDir), null, msg -> {});

        assertEquals(1, result.skippedDuplicateJar());
        assertEquals(1, result.scanned());
    }

    @Test
    void generate_extractsFabricLicense() throws Exception {
        Path gameDir = tempDir.resolve("server10");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        writeConfig(gameDir, List.of());

        Path jar = createModJar(modsDir, "libmod", "2", "libmod.jar", "fabric", "LGPL-3.0");
        WorkspaceGenerator.generate(gameDir, List.of(new InstalledMod("libmod", "2", jar)),
                "fabric", "1.21.1", ManifestGenConfig.load(gameDir), null, msg -> {});

        JsonObject ws = readWorkspace(gameDir);
        assertEquals("LGPL-3.0", ws.getAsJsonObject("mods").getAsJsonObject("libmod").get("license").getAsString());
    }

    @Test
    void generate_extractsNeoForgeLicense() throws Exception {
        Path gameDir = tempDir.resolve("server11");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        writeConfig(gameDir, List.of());

        Path jar = createModJar(modsDir, "nfr", "3", "nfr.jar", "neoforge", "All-Rights-Reserved");
        WorkspaceGenerator.generate(gameDir, List.of(new InstalledMod("nfr", "3", jar)),
                "neoforge", "1.21.1", ManifestGenConfig.load(gameDir), null, msg -> {});

        JsonObject ws = readWorkspace(gameDir);
        assertEquals("All-Rights-Reserved",
                ws.getAsJsonObject("mods").getAsJsonObject("nfr").get("license").getAsString());
    }

    @Test
    void generate_nonMatchingLoaderVariantsRemainUntouched() throws Exception {
        Path gameDir = tempDir.resolve("server12");
        Files.createDirectories(gameDir.resolve("mods"));
        writeConfig(gameDir, List.of());

        JsonObject root = new JsonObject();
        root.addProperty("manifestId", "multi");
        JsonObject modsObj = new JsonObject();
        JsonObject mod = new JsonObject();
        mod.addProperty("required", false);
        JsonArray variants = new JsonArray();

        // variant for neoforge (should stay)
        JsonObject nv = new JsonObject();
        JsonObject sel = new JsonObject();
        JsonArray loaders = new JsonArray();
        loaders.add("neoforge");
        sel.add("loaders", loaders);
        nv.add("selector", sel);
        nv.addProperty("version", "neo");
        variants.add(nv);

        // variant for fabric (will be removed because required=false and not installed)
        JsonObject fv = new JsonObject();
        JsonObject sel2 = new JsonObject();
        JsonArray loaders2 = new JsonArray();
        loaders2.add("fabric");
        sel2.add("loaders", loaders2);
        fv.add("selector", sel2);
        variants.add(fv);
        mod.add("variants", variants);
        modsObj.add("cross", mod);
        root.add("mods", modsObj);

        ManifestGenConfig cfg = ManifestGenConfig.load(gameDir);
        Path out = cfg.getOutputPath(gameDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toString());

        WorkspaceGenerator.generate(gameDir, List.of(), "fabric", "1.21.1", cfg, null, msg -> {});

        JsonObject ws = readWorkspace(gameDir);
        JsonObject entry = ws.getAsJsonObject("mods").getAsJsonObject("cross");
        JsonArray remaining = entry.getAsJsonArray("variants");
        assertEquals(1, remaining.size());
        assertEquals("neoforge", remaining.get(0).getAsJsonObject().getAsJsonObject("selector")
                .getAsJsonArray("loaders").get(0).getAsString());
    }
}
