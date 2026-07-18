package io.github.henryxjh.mcclientupdate.update.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Represents a single installation failure. */
public record InstallFailure(
        List<String> modIds,
        String fileName,
        String version,
        String sourceType,
        InstallFailureCategory category,
        String message) {

    public enum InstallFailureCategory {
        SIZE_MISMATCH,
        HASH_MISMATCH,
        IO_ERROR,
        TARGET_CONFLICT,
        INTERRUPTED,
        UNKNOWN
    }

    public InstallFailure {
        Objects.requireNonNull(modIds, "modIds");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
        if (modIds.isEmpty()) {
            throw new IllegalArgumentException("modIds must not be empty");
        }
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (!"hosted".equals(sourceType) && !"direct".equals(sourceType)) {
            throw new IllegalArgumentException("sourceType must be hosted or direct");
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
        return "InstallFailure[modIds=%s, fileName=%s, category=%s]"
                .formatted(modIds, fileName, category);
    }
}
