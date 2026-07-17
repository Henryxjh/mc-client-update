package io.github.henryxjh.mcclientupdate.manifest;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable pair of hex-encoded lower-case hashes.
 * At least one of {@code sha256} or {@code sha512} must be present.
 */
public record Hashes(Optional<String> sha256, Optional<String> sha512) {

    public Hashes {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(sha512, "sha512");
        boolean hasSha256 = sha256.isPresent() && !sha256.get().isBlank();
        boolean hasSha512 = sha512.isPresent() && !sha512.get().isBlank();
        if (!hasSha256 && !hasSha512) {
            throw new IllegalArgumentException("At least one hash must be provided");
        }
        if (hasSha256) {
            String val = sha256.get().strip().toLowerCase(Locale.ROOT);
            if (val.length() != 64 || !val.matches("[0-9a-f]+")) {
                throw new IllegalArgumentException("Invalid sha256: " + sha256.get());
            }
            sha256 = Optional.of(val);
        }
        if (hasSha512) {
            String val = sha512.get().strip().toLowerCase(Locale.ROOT);
            if (val.length() != 128 || !val.matches("[0-9a-f]+")) {
                throw new IllegalArgumentException("Invalid sha512: " + sha512.get());
            }
            sha512 = Optional.of(val);
        }
    }
}
