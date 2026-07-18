package io.github.henryxjh.mcclientupdate.ui;

import io.github.henryxjh.mcclientupdate.download.DownloadFailure;
import io.github.henryxjh.mcclientupdate.download.ManualUpdate;
import io.github.henryxjh.mcclientupdate.update.install.InstallFailure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAttentionDialogTest {

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

        String text = UpdateAttentionDialog.formatMessage(
                List.of(mu),
                List.of(df),
                List.of(inf));

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
    }

    @Test
    void emptyListsProduceMinimalContent() {
        String text = UpdateAttentionDialog.formatMessage(
                List.of(), List.of(), List.of()
        );
        assertTrue(text.isEmpty());
    }
}
