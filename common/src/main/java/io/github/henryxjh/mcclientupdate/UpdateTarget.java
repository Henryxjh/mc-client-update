package io.github.henryxjh.mcclientupdate;

import java.util.Locale;
import java.util.Objects;

/** Stable loader/OS/architecture tuple used to select a manifest artifact. */
public record UpdateTarget(String loader, RuntimePlatform platform) {
    public UpdateTarget {
        loader = Objects.requireNonNull(loader, "loader").trim().toLowerCase(Locale.ROOT);
        if (loader.isEmpty()) {
            throw new IllegalArgumentException("loader must not be empty");
        }
        Objects.requireNonNull(platform, "platform");
    }

    public static UpdateTarget detect(PlatformContext context) {
        Objects.requireNonNull(context, "context");
        return new UpdateTarget(context.loaderName(), RuntimePlatform.detect());
    }

    /** For example: {@code neoforge-android-aarch64}. */
    public String classifier() {
        return loader + "-" + platform.classifier();
    }
}
