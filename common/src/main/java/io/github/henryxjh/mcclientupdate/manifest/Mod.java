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
        Optional<String> skipIfInstalledVersionGreaterThan,
        List<Variant> variants,
        ModAction action) {

    public Mod {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mod name must not be blank");
        }
        Objects.requireNonNull(homepage, "homepage");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(skipIfInstalledVersionGreaterThan, "skipIfInstalledVersionGreaterThan");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(variants, "variants");
        skipIfInstalledVersionGreaterThan.ifPresent(v -> {
            if (v.isBlank()) {
                throw new IllegalArgumentException("skipIfInstalledVersionGreaterThan must not be blank");
            }
        });
        if (variants.isEmpty() && action == ModAction.INSTALL) {
            throw new IllegalArgumentException("variants must not be empty for INSTALL");
        }
        variants = List.copyOf(variants);
    }

    /**
     * Construct a Mod with default action {@link ModAction#INSTALL}
     * and no {@code skipIfInstalledVersionGreaterThan}.
     */
    public Mod(String name, boolean required,
               Optional<String> homepage, Optional<String> license,
               List<Variant> variants) {
        this(name, required, homepage, license, Optional.empty(), variants, ModAction.INSTALL);
    }

    /**
     * Construct a Mod with an explicit {@link ModAction} but no
     * {@code skipIfInstalledVersionGreaterThan}.
     */
    public Mod(String name, boolean required,
               Optional<String> homepage, Optional<String> license,
               List<Variant> variants, ModAction action) {
        this(name, required, homepage, license, Optional.empty(), variants, action);
    }
}
