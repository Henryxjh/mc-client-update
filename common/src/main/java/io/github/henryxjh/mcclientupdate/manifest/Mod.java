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
        List<Variant> variants,
        ModAction action) {

    public Mod {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mod name must not be blank");
        }
        Objects.requireNonNull(homepage, "homepage");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(variants, "variants");
        if (variants.isEmpty() && action == ModAction.INSTALL) {
            throw new IllegalArgumentException("variants must not be empty for INSTALL");
        }
        variants = List.copyOf(variants);
    }

    /**
     * Construct a Mod with default action {@link ModAction#INSTALL}.
     */
    public Mod(String name, boolean required,
               Optional<String> homepage, Optional<String> license,
               List<Variant> variants) {
        this(name, required, homepage, license, variants, ModAction.INSTALL);
    }
}
