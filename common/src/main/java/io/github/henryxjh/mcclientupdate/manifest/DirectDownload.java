package io.github.henryxjh.mcclientupdate.manifest;

import java.util.Objects;
import java.util.Optional;

/**
 * Artifact provided by an external service such as Modrinth or CurseForge.
 */
public record DirectDownload(
        String url,
        Optional<String> provider,
        Optional<String> projectId,
        Optional<String> versionId) implements Download {

    public DirectDownload {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("direct download url must not be blank");
        }
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(versionId, "versionId");
    }

    @Override
    public String type() {
        return "direct";
    }
}
