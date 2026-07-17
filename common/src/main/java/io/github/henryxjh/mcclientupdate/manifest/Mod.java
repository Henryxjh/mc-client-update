package io.github.henryxjh.mcclientupdate.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of a single mod entry in the manifest.
 */
public record Mod(
        String name,
        boolean required,
        Optional<String> homepage,
        Optional<String> license,
        List<Variant> variants) {

    public Mod {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mod name must not be blank");
        }
        Objects.requireNonNull(homepage, "homepage");
        Objects.requireNonNull(license, "license");
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("mod variants must not be empty");
        }
        variants = List.copyOf(variants);
    }
}
