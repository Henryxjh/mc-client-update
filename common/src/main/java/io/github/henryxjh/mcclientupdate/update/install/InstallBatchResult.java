package io.github.henryxjh.mcclientupdate.update.install;

import java.util.List;
import java.util.Objects;

/** Immutable outcome of an installation batch. */
public record InstallBatchResult(
        String manifestId,
        long revision,
        List<InstalledArtifact> installed,
        List<InstallFailure> failures) {

    public InstallBatchResult {
        Objects.requireNonNull(manifestId, "manifestId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(failures, "failures");
        installed = List.copyOf(installed);
        failures = List.copyOf(failures);
    }
}
