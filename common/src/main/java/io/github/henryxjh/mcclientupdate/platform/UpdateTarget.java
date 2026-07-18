package io.github.henryxjh.mcclientupdate.platform;

import java.util.Locale;
import java.util.Objects;

/** Stable loader/OS/architecture tuple used to select a manifest artifact. */
public record UpdateTarget(String loader, RuntimePlatform platform, String loaderVersion) {
    public UpdateTarget {
        loader = Objects.requireNonNull(loader, "loader").trim().toLowerCase(Locale.ROOT);
        if (loader.isEmpty()) {
            throw new IllegalArgumentException("loader must not be empty");
        }
        Objects.requireNonNull(platform, "platform");
        loaderVersion = Objects.requireNonNull(loaderVersion, "loaderVersion").strip();
        if (loaderVersion.isEmpty()) {
            throw new IllegalArgumentException("loaderVersion must not be blank");
        }
    }

    public UpdateTarget(String loader, RuntimePlatform platform) {
        this(loader, platform, "0");
    }

    public static UpdateTarget detect(PlatformContext context) {
        Objects.requireNonNull(context, "context");
        return new UpdateTarget(context.loaderName(), RuntimePlatform.detect(),
                context.loaderVersion());
    }

    /** For example: {@code neoforge-android-aarch64}. */
    public String classifier() {
        return loader + "-" + platform.classifier();
    }
}
