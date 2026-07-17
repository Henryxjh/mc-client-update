package io.github.henryxjh.mcclientupdate.scan;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of a mod update scan.
 */
public record ScanResult(List<UpdateCandidate> candidates,
                         int manifestModCount,
                         int installedManagedModCount) {

    public ScanResult {
        Objects.requireNonNull(candidates, "candidates");
        candidates = List.copyOf(candidates);
        if (manifestModCount < 0) {
            throw new IllegalArgumentException("manifestModCount must be >= 0");
        }
        if (installedManagedModCount < 0) {
            throw new IllegalArgumentException("installedManagedModCount must be >= 0");
        }
    }
}
