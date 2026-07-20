package io.github.henryxjh.mcclientupdate.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

class UpdateAttentionDialogTest {

    private static final class NoOpPlatformContext implements PlatformContext {

        @Override
        public String loaderName() {
            return "test";
        }

        @Override
        public Path gameDirectory() {
            return Path.of(".");
        }

        @Override
        public Path selfModPath() {
            return Path.of("mod.jar");
        }

        @Override
        public void log(String message) {
            // no‑op
        }

        @Override
        public List<InstalledMod> installedMods() {
            return Collections.emptyList();
        }

        @Override
        public String loaderVersion() {
            return "1.0.0";
        }

        @Override
        public String minecraftVersion() {
            return "1.20";
        }
    }

    @Test
    void emptyTextReturnsImmediately() {
        PlatformContext ctx = new NoOpPlatformContext();
        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> UpdateAttentionDialog.showTextIfNeeded("", ctx));
    }

    @Test
    void nonEmptyTextDoesNotBlockIndefinitely() {
        PlatformContext ctx = new NoOpPlatformContext();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> UpdateAttentionDialog.showWithTimeout("some text requiring action", ctx, 100L));
    }

    @Test
    void zeroTimeoutReturnsImmediately() {
        PlatformContext ctx = new NoOpPlatformContext();
        assertDoesNotThrow(() -> UpdateAttentionDialog.showWithTimeout("test", ctx, 0L));
    }

    @Test
    void showWithTimeoutCompletesQuickly() {
        PlatformContext ctx = new NoOpPlatformContext();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> UpdateAttentionDialog.showWithTimeout("sample", ctx, 100L));
    }
}
