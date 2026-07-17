package io.github.henryxjh.mcclientupdate.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class DownloadReportWriter {

    private static final String REPORT_FILE_NAME = "mc-client-update-download-report.json";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private DownloadReportWriter() {}

    public static void writeReport(DownloadBatchResult result, Path gameDirectory) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(gameDirectory, "gameDirectory");

        Path configDir = gameDirectory.resolve("config");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create config directory " + configDir, e);
        }
        Path configPath = configDir.resolve(REPORT_FILE_NAME);
        Path tmpPath = configDir.resolve(REPORT_FILE_NAME + TEMP_SUFFIX + "." + System.nanoTime());

        ReportJson report = new ReportJson(
                Instant.now(),
                result.manifestId(),
                result.revision(),
                toDownloadedJsons(result.downloaded()),
                toFailedJsons(result.failed()),
                toManualJsons(result.manualUpdates()));

        try {
            String json = GSON.toJson(report);
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmpPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) { }
            throw new DownloadException("Failed to write download report to " + configPath, e);
        }
    }

    private static final class ReportJson {
        String generatedAt;
        String manifestId;
        long revision;
        List<DownloadedJson> downloaded;
        List<FailedJson> failed;
        List<ManualJson> manualUpdates;

        ReportJson(
                Instant generatedAt,
                String manifestId,
                long revision,
                List<DownloadedJson> downloaded,
                List<FailedJson> failed,
                List<ManualJson> manualUpdates) {
            this.generatedAt = DateTimeFormatter.ISO_INSTANT.format(generatedAt.atOffset(ZoneOffset.UTC));
            this.manifestId = manifestId;
            this.revision = revision;
            this.downloaded = downloaded;
            this.failed = failed;
            this.manualUpdates = manualUpdates;
        }
    }

    private static final class DownloadedJson {
        List<String> modIds;
        String fileName;
        String version;
        String sourceType;
        String relativePath;

        DownloadedJson(
                DownloadedArtifact from) {
            this.modIds = from.modIds();
            this.fileName = from.fileName();
            this.version = from.version();
            this.sourceType = from.sourceType();
            this.relativePath = from.relativePath();
        }
    }

    private static final class FailedJson {
        List<String> modIds;
        String fileName;
        String version;
        String sourceType;
        String category;
        String message;

        FailedJson(
                DownloadFailure from) {
            this.modIds = from.modIds();
            this.fileName = from.fileName();
            this.version = from.version();
            this.sourceType = from.sourceType();
            this.category = from.category();
            this.message = from.message();
        }
    }

    private static final class ManualJson {
        List<String> modIds;
        String fileName;
        String version;
        String pageUrl;
        String message;

        ManualJson(
                ManualUpdate from) {
            this.modIds = from.modIds();
            this.fileName = from.fileName();
            this.version = from.version();
            this.pageUrl = from.pageUrl();
            this.message = from.message();
        }
    }

    private static List<DownloadedJson> toDownloadedJsons(List<DownloadedArtifact> items) {
        return items.stream().map(DownloadedJson::new).collect(Collectors.toList());
    }

    private static List<FailedJson> toFailedJsons(List<DownloadFailure> items) {
        return items.stream().map(FailedJson::new).collect(Collectors.toList());
    }

    private static List<ManualJson> toManualJsons(List<ManualUpdate> items) {
        return items.stream().map(ManualJson::new).collect(Collectors.toList());
    }
}
