package io.github.henryxjh.mcclientupdate.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable selection criteria for a variant.
 * Missing fields mean "any".
 */
public record Selector(
        Optional<List<String>> loaders,
        Optional<List<String>> operatingSystems,
        Optional<List<String>> architectures) {

    public Selector {
        Objects.requireNonNull(loaders, "loaders");
        Objects.requireNonNull(operatingSystems, "operatingSystems");
        Objects.requireNonNull(architectures, "architectures");
        loaders = loaders.map(List::copyOf);
        operatingSystems = operatingSystems.map(List::copyOf);
        architectures = architectures.map(List::copyOf);
    }
}
