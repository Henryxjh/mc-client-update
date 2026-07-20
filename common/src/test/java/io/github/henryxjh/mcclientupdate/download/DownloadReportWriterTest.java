package io.github.henryxjh.mcclientupdate.download;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadReportWriterTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void writeReportCreatesFileWithCorrectFields() throws Exception {
        Path gameDir = tempDir.resolve("game");
        Files.createDirectories(gameDir.resolve("config"));
        Path artifactFile = gameDir.resolve(".mc-client-update/downloads/sha512-abcd/mod.jar");
        Files.createDirectories(artifactFile.getParent());
        Files.write(artifactFile, new byte[]{1, 2, 3});

        DownloadedArtifact downloaded = new DownloadedArtifact(
                List.of("modA"), "mod.jar", "1.0", "hosted", artifactFile,
                ".mc-client-update/downloads/sha512-abcd/mod.jar");
        DownloadFailure failure = new DownloadFailure(
                List.of("modB"), "bad.jar", "2.0", "direct", "HTTP_STATUS", "HTTP 404");
        ManualUpdate manual = new ManualUpdate(
                List.of("modC"), "manual.jar", "3.0", "https://example.com", "manual message");

        DownloadBatchResult result = new DownloadBatchResult(
                "manifest-1", 42L,
                List.of(downloaded),
                List.of(failure),
                List.of(manual));

        DownloadReportWriter.writeReport(result, gameDir);
        Path reportPath = gameDir.resolve("config").resolve("mc-client-update-download-report.json");
        assertTrue(Files.isRegularFile(reportPath));

        String content = Files.readString(reportPath);
        JsonObject root = GSON.fromJson(content, JsonObject.class);

        assertNotNull(root.get("generatedAt"));
        assertEquals("manifest-1", root.get("manifestId").getAsString());
        assertEquals(42L, root.get("revision").getAsLong());

        JsonArray downloadedArr = root.getAsJsonArray("downloaded");
        assertEquals(1, downloadedArr.size());
        JsonObject d = downloadedArr.get(0).getAsJsonObject();
        assertEquals("modA", d.getAsJsonArray("modIds").get(0).getAsString());
        assertEquals("mod.jar", d.get("fileName").getAsString());
        assertEquals("1.0", d.get("version").getAsString());
        assertEquals("hosted", d.get("sourceType").getAsString());
        assertEquals(".mc-client-update/downloads/sha512-abcd/mod.jar", d.get("relativePath").getAsString());
        assertFalse(d.has("file"), "absolute file path must not be serialized");

        JsonArray failedArr = root.getAsJsonArray("failed");
        assertEquals(1, failedArr.size());
        JsonObject f = failedArr.get(0).getAsJsonObject();
        assertEquals("modB", f.getAsJsonArray("modIds").get(0).getAsString());
        assertEquals("bad.jar", f.get("fileName").getAsString());
        assertEquals("direct", f.get("sourceType").getAsString());
        assertEquals("HTTP_STATUS", f.get("category").getAsString());
        assertEquals("HTTP 404", f.get("message").getAsString());

        JsonArray manualArr = root.getAsJsonArray("manualUpdates");
        assertEquals(1, manualArr.size());
        JsonObject m = manualArr.get(0).getAsJsonObject();
        assertEquals("modC", m.getAsJsonArray("modIds").get(0).getAsString());
        assertEquals("manual.jar", m.get("fileName").getAsString());
        assertEquals("3.0", m.get("version").getAsString());
        assertEquals("https://example.com", m.get("pageUrl").getAsString());
        assertEquals("manual message", m.get("message").getAsString());

        // verify no .tmp files remain
        Path configDir = gameDir.resolve("config");
        try (var stream = Files.list(configDir)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().contains(".tmp.")).toList();
            assertTrue(tmpFiles.isEmpty(), "Temporary files left behind: " + tmpFiles);
        }
    }

    @Test
    void overwritingRemovesPreviousEntries() throws Exception {
        Path gameDir = tempDir.resolve("game");
        Files.createDirectories(gameDir.resolve("config"));
        Path dummyFile = gameDir.resolve(".mc-client-update/downloads/sha512-xxxx/old.jar");
        Files.createDirectories(dummyFile.getParent());
        Files.write(dummyFile, new byte[]{9, 9});

        DownloadBatchResult first = new DownloadBatchResult("m1", 1L,
                List.of(new DownloadedArtifact(List.of("x"), "old.jar", "9.9", "hosted",
                        dummyFile, ".mc-client-update/downloads/sha512-xxxx/old.jar")),
                List.of(),
                List.of());
        DownloadReportWriter.writeReport(first, gameDir);
        Path report = gameDir.resolve("config").resolve("mc-client-update-download-report.json");
        assertTrue(Files.isRegularFile(report));

        // write second report with empty lists
        DownloadBatchResult second = new DownloadBatchResult("m2", 2L,
                List.of(), List.of(), List.of());
        DownloadReportWriter.writeReport(second, gameDir);

        String content = Files.readString(report);
        JsonObject root = GSON.fromJson(content, JsonObject.class);
        assertEquals("m2", root.get("manifestId").getAsString());
        assertEquals(2L, root.get("revision").getAsLong());
        assertEquals(0, root.getAsJsonArray("downloaded").size());
        assertEquals(0, root.getAsJsonArray("failed").size());
        assertEquals(0, root.getAsJsonArray("manualUpdates").size());

        Path configDir = gameDir.resolve("config");
        try (var stream = Files.list(configDir)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().contains(".tmp.")).toList();
            assertTrue(tmpFiles.isEmpty(), "Temp files after overwrite: " + tmpFiles);
        }
    }
}
