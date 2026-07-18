package io.github.henryxjh.mcclientupdate.ui;

import io.github.henryxjh.mcclientupdate.download.DownloadFailure;
import io.github.henryxjh.mcclientupdate.download.ManualUpdate;
import io.github.henryxjh.mcclientupdate.update.install.InstallFailure;
import io.github.henryxjh.mcclientupdate.update.install.InstalledArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAttentionMessageTest {

    @Test
    void formatMessageContainsAllProvidedEntries() {
        ManualUpdate mu = new ManualUpdate(
                List.of("modA"),
                "manual.jar",
                "1.0.0",
                "https://example.com",
                "Please install manually");
        DownloadFailure df = new DownloadFailure(
                List.of("modB"),
                "dl.jar",
                "2.0",
                "hosted",
                "SIZE_MISMATCH",
                "wrong size");
        InstallFailure inf = new InstallFailure(
                List.of("modC"),
                "install.jar",
                "3.0",
                "hosted",
                InstallFailure.InstallFailureCategory.HASH_MISMATCH,
                "hash error");
        InstalledArtifact inst = new InstalledArtifact(
                List.of("modInstalled"),
                "installedArtifact.jar",
                "5.0.0",
                "hosted",
                "REPLACE",
                "mods/installedArtifact.jar",
                Optional.of("mods/installedArtifact.jar.mc-client-update-old"));

        String text = UpdateAttentionMessage.formatMessage(
                List.of(mu), List.of(df), List.of(inf), List.of(inst));

        assertTrue(text.contains("Manual updates"));
        assertTrue(text.contains("modA"));
        assertTrue(text.contains("manual.jar"));
        assertTrue(text.contains("https://example.com"));
        assertTrue(text.contains("Please install manually"));
        assertTrue(text.contains("Download failures"));
        assertTrue(text.contains("modB"));
        assertTrue(text.contains("dl.jar"));
        assertTrue(text.contains("SIZE_MISMATCH"));
        assertTrue(text.contains("wrong size"));
        assertTrue(text.contains("Install failures"));
        assertTrue(text.contains("modC"));
        assertTrue(text.contains("install.jar"));
        assertTrue(text.contains("HASH_MISMATCH"));
        assertTrue(text.contains("hash error"));
        assertTrue(text.contains("Installed updates"));
        assertTrue(text.contains("modInstalled"));
        assertTrue(text.contains("installedArtifact.jar"));
        assertTrue(text.contains("5.0.0"));
        assertTrue(text.contains("REPLACE"));
        assertTrue(text.contains("mods/installedArtifact.jar"));
        assertTrue(text.contains("mods/installedArtifact.jar.mc-client-update-old"));
    }

    @Test
    void emptyListsProduceMinimalContent() {
        String text = UpdateAttentionMessage.formatMessage(
                List.of(), List.of(), List.of(), List.of());
        assertTrue(text.isEmpty());
    }

    @Test
    void restartRequiredMessageContainsReportPathAndAttentionText() {
        String attentionText = "important details";
        String msg = UpdateAttentionMessage.restartRequiredMessage(attentionText);
        assertTrue(msg.contains("config/mc-client-update-download-report.json"));
        assertTrue(msg.contains("MC Client Update"));
        assertTrue(msg.contains(attentionText));
    }

    @Test
    void downloadFailureMessageContainsReportPathAndAttentionText() {
        String attentionText = "important details";
        String msg = UpdateAttentionMessage.downloadFailureMessage(attentionText);
        assertTrue(msg.contains("config/mc-client-update-download-report.json"));
        assertTrue(msg.contains("MC Client Update"));
        assertTrue(msg.contains(attentionText));
    }
}
