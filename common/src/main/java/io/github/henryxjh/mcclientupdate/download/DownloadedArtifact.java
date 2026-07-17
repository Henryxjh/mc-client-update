package io.github.henryxjh.mcclientupdate.download;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A successfully downloaded artifact ready for later installation.
 * <p>
 * The {@code relativePath} is the path relative to the game directory
 * that points to the staged file inside {@code .mc-client-update/downloads}.
 * </p>
 */
public record DownloadedArtifact(
        List<String> modIds,
        String fileName,
        String version,
        String sourceType,
        Path file,
        String relativePath) {

    public DownloadedArtifact {
        Objects.requireNonNull(modIds, "modIds");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relativePath, "relativePath");
        if (modIds.isEmpty()) {
            throw new IllegalArgumentException("modIds must not be empty");
        }
        if (!"hosted".equals(sourceType) && !"direct".equals(sourceType)) {
            throw new IllegalArgumentException("sourceType must be hosted or direct");
        }
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        List<String> sorted = new ArrayList<>(modIds);
        Collections.sort(sorted);
        modIds = List.copyOf(sorted);
    }

    @Override
    public String toString() {
        return "DownloadedArtifact[modIds=%s, fileName=%s, version=%s, sourceType=%s]"
                .formatted(modIds, fileName, version, sourceType);
    }
}
