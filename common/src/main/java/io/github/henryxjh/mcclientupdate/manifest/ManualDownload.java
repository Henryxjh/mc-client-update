package io.github.henryxjh.mcclientupdate.manifest;

import java.util.Objects;
import java.util.Optional;

/**
 * Artifact that can only be obtained manually from a web page.
 */
public record ManualDownload(String pageUrl, Optional<String> message) implements Download {

    public ManualDownload {
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new IllegalArgumentException("manual download pageUrl must not be blank");
        }
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String type() {
        return "manual";
    }
}
