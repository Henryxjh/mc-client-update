package com.henrymc.clientupdate;

import java.nio.file.Path;

public interface PlatformContext {
    String loaderName();

    Path gameDirectory();

    Path selfModPath();

    void log(String message);
}
