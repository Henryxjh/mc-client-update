package io.github.henryxjh.mcclientupdate.manifest;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a validated {@code client-update-manifest.json}.
 */
public record Manifest(
        int schemaVersion,
        String manifestId,
        long revision,
        Instant generatedAt,
        Optional<Instant> expiresAt,
        String minecraftVersion,
        Optional<String> baseUrl,
        Map<String, Mod> mods) {

    public Manifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported schema version: " + schemaVersion);
        }
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (manifestId == null || manifestId.isBlank()) {
            throw new IllegalArgumentException("manifestId must not be blank");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt must not be null");
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        if (mods == null || mods.isEmpty()) {
            throw new IllegalArgumentException("mods must not be empty");
        }
        // defensive copy
        mods = Collections.unmodifiableMap(Map.copyOf(mods));
        expiresAt = expiresAt.map(instant -> {
            return instant; // already immutable
        });
    }

    public int modCount() {
        return mods.size();
    }
}
