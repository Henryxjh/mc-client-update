package io.github.henryxjh.mcclientupdate.update.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Successfully installed artifact. */
public record InstalledArtifact(
        List<String> modIds,
        String fileName,
        String version,
        String sourceType,
        String action, // "ADD" or "REPLACE"
        String installedRelativePath,
        Optional<String> backupRelativePath) {

    public InstalledArtifact {
        Objects.requireNonNull(modIds, "modIds");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(installedRelativePath, "installedRelativePath");
        Objects.requireNonNull(backupRelativePath, "backupRelativePath");
        if (modIds.isEmpty()) {
            throw new IllegalArgumentException("modIds must not be empty");
        }
        if (!"ADD".equals(action) && !"REPLACE".equals(action)) {
            throw new IllegalArgumentException("action must be ADD or REPLACE");
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
        if (installedRelativePath.isBlank()) {
            throw new IllegalArgumentException("installedRelativePath must not be blank");
        }
        if ("REPLACE".equals(action) && backupRelativePath.isEmpty()) {
            throw new IllegalArgumentException("backupRelativePath required for REPLACE");
        }
        if ("ADD".equals(action) && backupRelativePath.isPresent()) {
            throw new IllegalArgumentException("backupRelativePath must not be present for ADD");
        }
        List<String> sorted = new ArrayList<>(modIds);
        Collections.sort(sorted);
        modIds = List.copyOf(sorted);
    }

    @Override
    public String toString() {
        return "InstalledArtifact[modIds=%s, fileName=%s, version=%s, action=%s]"
                .formatted(modIds, fileName, version, action);
    }
}
