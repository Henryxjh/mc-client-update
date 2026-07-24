package io.github.henryxjh.mcclientupdate.platform;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.ui.LoadingProgressSink;
import io.github.henryxjh.mcclientupdate.ui.NoopLoadingProgressSink;
import java.nio.file.Path;
import java.util.List;

public interface PlatformContext {
    String loaderName();

    Path gameDirectory();

    Path selfModPath();

    void log(String message);

    List<InstalledMod> installedMods();

    String loaderVersion();

    String minecraftVersion();

    default String selfModId() {
        return "mc_client_update";
    }

    /**
     * Allows the platform to supply a native loading-progress indicator.
     * The default returns a no-op sink.
     */
    default LoadingProgressSink loadingProgressSink() {
        return new NoopLoadingProgressSink();
    }
}
