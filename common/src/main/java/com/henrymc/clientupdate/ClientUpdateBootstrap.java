package com.henrymc.clientupdate;

import java.util.Objects;

/** Loader-independent startup entry point. */
public final class ClientUpdateBootstrap {
    private ClientUpdateBootstrap() {
    }

    public static void start(PlatformContext platform) {
        Objects.requireNonNull(platform, "platform");
        platform.log("MC Client Update initialized on " + platform.loaderName()
                + "; gameDir=" + platform.gameDirectory()
                + "; self=" + platform.selfModPath());

        // The server manifest protocol will be connected here. The filesystem
        // transaction implementation is intentionally usable without either loader.
    }
}
