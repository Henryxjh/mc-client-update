package io.github.henryxjh.mcclientupdate.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A download that failed, together with a machine‑readable
 * {@code category} and a safe, short message intended for logs / reports.
 */
public record DownloadFailure(
        List<String> modIds,
        String fileName,
        String version,
        String sourceType,
        String category,
        String message) {

    public DownloadFailure {
        Objects.requireNonNull(modIds, "modIds");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
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
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        List<String> sorted = new ArrayList<>(modIds);
        Collections.sort(sorted);
        modIds = List.copyOf(sorted);
    }

    @Override
    public String toString() {
        return "DownloadFailure[modIds=%s, fileName=%s, version=%s, sourceType=%s, category=%s]"
                .formatted(modIds, fileName, version, sourceType, category);
    }
}
