package io.github.henryxjh.mcclientupdate.scan;

import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single update candidate produced by the scanner.
 */
public record UpdateCandidate(
        String modId,
        Mod manifestMod,
        Variant selectedVariant,
        Optional<InstalledMod> installed,
        Reason reason) {

    public enum Reason {
        MISSING_REQUIRED,
        HASH_MISMATCH;
    }

    public UpdateCandidate {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(manifestMod, "manifestMod");
        Objects.requireNonNull(selectedVariant, "selectedVariant");
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(reason, "reason");
        switch (reason) {
            case MISSING_REQUIRED:
                if (!manifestMod.required() || installed.isPresent()) {
                    throw new ModScanException(
                            "MISSING_REQUIRED candidate must be required and have no installed mod");
                }
                break;
            case HASH_MISMATCH:
                if (installed.isEmpty()) {
                    throw new ModScanException(
                            "HASH_MISMATCH candidate must have an installed mod");
                }
                break;
            default:
                throw new IllegalStateException("Unexpected reason: " + reason);
        }
    }
}
