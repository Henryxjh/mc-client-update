package io.github.henryxjh.mcclientupdate.manifest;

/**
 * Immutable description of an artifact (a single JAR to install).
 */
public record Artifact(
        String version,
        String fileName,
        long size,
        Hashes hashes,
        Download download) {

    public Artifact {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("artifact version must not be blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        if (download == null) {
            throw new IllegalArgumentException("download must not be null");
        }
    }
}
