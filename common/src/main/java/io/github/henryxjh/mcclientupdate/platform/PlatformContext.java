package io.github.henryxjh.mcclientupdate.platform;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import java.nio.file.Path;
import java.util.List;

public interface PlatformContext {
    String loaderName();

    Path gameDirectory();

    Path selfModPath();

    void log(String message);

    List<InstalledMod> installedMods();
}
