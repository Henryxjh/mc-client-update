package io.github.henryxjh.mcclientupdate.platform;

import java.nio.file.Path;

public interface PlatformContext {
    String loaderName();

    Path gameDirectory();

    Path selfModPath();

    void log(String message);
}
