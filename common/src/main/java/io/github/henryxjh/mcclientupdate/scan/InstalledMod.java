package io.github.henryxjh.mcclientupdate.scan;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A mod that is currently installed and recognised by the loader.
 */
public record InstalledMod(String modId, String version, Path file) {
    public InstalledMod {
        Objects.requireNonNull(modId, "modId");
        if (modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        Objects.requireNonNull(version, "version");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(file, "file");
        file = file.toAbsolutePath().normalize();
    }
}
