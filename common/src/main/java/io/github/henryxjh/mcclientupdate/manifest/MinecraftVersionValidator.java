package io.github.henryxjh.mcclientupdate.manifest;

import java.util.Objects;

/**
 * Utility that ensures the Minecraft version in the update manifest matches the running Minecraft version.
 */
public final class MinecraftVersionValidator {

    private MinecraftVersionValidator() {
        // utility class
    }

    /**
     * Validates that the manifest's minecraft version matches the provided current version.
     *
     * @param manifestVersion the {@code minecraftVersion} from the manifest, never {@code null}
     * @param currentVersion  the running Minecraft version as provided by the platform, never {@code null}
     * @throws ManifestFetchException if the two versions do not match
     */
    public static void validateMinecraftVersion(String manifestVersion, String currentVersion) {
        Objects.requireNonNull(manifestVersion, "manifestVersion");
        Objects.requireNonNull(currentVersion, "currentVersion");
        if (!manifestVersion.equals(currentVersion)) {
            String message = "Manifest Minecraft version '" + manifestVersion
                    + "' does not match current version '" + currentVersion + "'";
            throw new ManifestFetchException(message);
        }
    }
}
