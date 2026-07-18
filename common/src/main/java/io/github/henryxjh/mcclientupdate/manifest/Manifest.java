package io.github.henryxjh.mcclientupdate.manifest;

import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
        Map<String, Mod> mods,
        Optional<Map<String, String>> minimumLoaderVersions) {

    public Manifest(int schemaVersion,
                    String manifestId,
                    long revision,
                    Instant generatedAt,
                    Optional<Instant> expiresAt,
                    String minecraftVersion,
                    Optional<String> baseUrl,
                    Map<String, Mod> mods) {
        this(schemaVersion, manifestId, revision, generatedAt, expiresAt,
             minecraftVersion, baseUrl, mods, Optional.empty());
    }

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
        Objects.requireNonNull(minimumLoaderVersions, "minimumLoaderVersions");
        minimumLoaderVersions = minimumLoaderVersions.map(map -> {
            if (map.size() != 1) {
                throw new IllegalArgumentException("minimumLoaderVersions must contain exactly one entry");
            }
            var entry = map.entrySet().iterator().next();
            String rawKey = entry.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                throw new IllegalArgumentException("minimumLoaderVersions key must not be null or blank");
            }
            String key = rawKey.strip().toLowerCase(Locale.ROOT);
            if (!Set.of("fabric", "neoforge", "forge").contains(key)) {
                throw new IllegalArgumentException("Unsupported loader in minimumLoaderVersions: " + key);
            }
            String rawValue = entry.getValue();
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("minimumLoaderVersions value must not be null or blank");
            }
            String value = rawValue.strip();
            return Map.of(key, value);
        });
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
