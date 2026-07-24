package io.github.henryxjh.mcclientupdate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Per-instance configuration loaded from {@code config/mc-client-update.json}. */
public final class ClientUpdateConfig {
    public static final String FILE_NAME = "mc-client-update.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private static final int DEFAULT_CLEANUP_BACKUPS_AFTER_DAYS = 14;
    private static final int DEFAULT_CLEANUP_DOWNLOAD_CACHE_AFTER_DAYS = 30;
    private static final int MAX_CLEANUP_AFTER_DAYS = 3650;
    private static final int DEFAULT_COMPLETION_HOLD_SECONDS = 8;

    private final URI manifestUri;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int cleanupBackupsAfterDays;
    private final int cleanupDownloadCacheAfterDays;
    private final String minecraftVersionMismatchAction;
    private final int completionHoldSeconds;

    private ClientUpdateConfig(
            URI manifestUri,
            Duration connectTimeout,
            Duration readTimeout,
            int cleanupBackupsAfterDays,
            int cleanupDownloadCacheAfterDays,
            String minecraftVersionMismatchAction,
            int completionHoldSeconds) {
        this.manifestUri = manifestUri;
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.cleanupBackupsAfterDays = cleanupBackupsAfterDays;
        this.cleanupDownloadCacheAfterDays = cleanupDownloadCacheAfterDays;
        this.minecraftVersionMismatchAction = Objects.requireNonNull(
                minecraftVersionMismatchAction, "minecraftVersionMismatchAction");
        this.completionHoldSeconds = completionHoldSeconds;
    }

    public static ClientUpdateConfig load(Path gameDirectory) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Path configPath = gameDirectory.resolve("config").resolve(FILE_NAME);
        createDefaultIfMissing(configPath);

        ConfigJson json;
        try {
            json = GSON.fromJson(Files.readString(configPath, StandardCharsets.UTF_8), ConfigJson.class);
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException("Failed to read " + configPath, exception);
        }
        if (json == null) {
            throw new IllegalStateException("Configuration is empty: " + configPath);
        }

        int connectTimeoutSeconds = validateTimeout(
                "connectTimeoutSeconds", json.connectTimeoutSeconds, configPath);
        int readTimeoutSeconds = validateTimeout(
                "readTimeoutSeconds", json.readTimeoutSeconds, configPath);
        URI manifestUri = parseManifestUri(json.manifestUrl, json.allowInsecureHttp, configPath);

        int cleanupBackups = validateDays(
                "cleanupBackupsAfterDays", json.cleanupBackupsAfterDays, configPath);
        int cleanupCache = validateDays(
                "cleanupDownloadCacheAfterDays", json.cleanupDownloadCacheAfterDays, configPath);

        String mismatchAction = validateMismatchAction(json.minecraftVersionMismatchAction, configPath);
        int completionHoldSeconds = validateCompletionHoldSeconds(json.completionHoldSeconds, configPath);

        return new ClientUpdateConfig(
                manifestUri,
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(readTimeoutSeconds),
                cleanupBackups,
                cleanupCache,
                mismatchAction,
                completionHoldSeconds);
    }

    public Optional<URI> manifestUri() {
        return Optional.ofNullable(manifestUri);
    }

    public boolean updatesEnabled() {
        return manifestUri != null;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    /** Number of days after which install‑time backup/residual files are eligible for cleanup. */
    public int cleanupBackupsAfterDays() {
        return cleanupBackupsAfterDays;
    }

    /** Number of days after which download‑cache files are eligible for cleanup. */
    public int cleanupDownloadCacheAfterDays() {
        return cleanupDownloadCacheAfterDays;
    }

    /** Behavior when the manifest Minecraft version does not match the current client version:
     *  "fail" (default) or "ignore".
     */
    public String minecraftVersionMismatchAction() {
        return minecraftVersionMismatchAction;
    }

    /** Seconds to keep the completion result visible before exiting. */
    public int completionHoldSeconds() {
        return completionHoldSeconds;
    }

    /** Endpoint suitable for logs: user info, query parameters and fragments are removed. */
    public String redactedManifestEndpoint() {
        if (manifestUri == null) {
            return "disabled";
        }
        try {
            return new URI(
                    manifestUri.getScheme(),
                    null,
                    manifestUri.getHost(),
                    manifestUri.getPort(),
                    manifestUri.getPath(),
                    null,
                    null).toString();
        } catch (URISyntaxException exception) {
            return manifestUri.getScheme() + "://" + manifestUri.getHost();
        }
    }

    private static void createDefaultIfMissing(Path configPath) {
        if (Files.exists(configPath)) {
            return;
        }

        ConfigJson defaults = new ConfigJson();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(
                    configPath,
                    GSON.toJson(defaults) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // Another startup path created it between the existence check and CREATE_NEW.
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create " + configPath, exception);
        }
    }

    private static int validateTimeout(String name, int seconds, Path configPath) {
        if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalStateException(
                    name + " must be between 1 and " + MAX_TIMEOUT_SECONDS + " in " + configPath);
        }
        return seconds;
    }

    private static int validateDays(String name, int days, Path configPath) {
        if (days < 0 || days > MAX_CLEANUP_AFTER_DAYS) {
            throw new IllegalStateException(
                    name + " must be between 0 and " + MAX_CLEANUP_AFTER_DAYS + " in " + configPath);
        }
        return days;
    }

    private static int validateCompletionHoldSeconds(int seconds, Path configPath) {
        if (seconds < 0) {
            return 0;
        }
        return seconds;
    }

    private static String validateMismatchAction(String raw, Path configPath) {
        if (raw == null) {
            throw new IllegalStateException(
                    "minecraftVersionMismatchAction must not be null in " + configPath);
        }
        String trimmed = raw.trim();
        if ("fail".equals(trimmed) || "ignore".equals(trimmed)) {
            return trimmed;
        }
        throw new IllegalStateException(
                "minecraftVersionMismatchAction must be 'fail' or 'ignore' in " + configPath);
    }

    private static URI parseManifestUri(String value, boolean allowInsecureHttp, Path configPath) {
        if (value == null || value.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid manifestUrl in " + configPath, exception);
        }

        String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("manifestUrl must be an absolute HTTP(S) URL in " + configPath);
        }
        if ("https".equals(scheme)) {
            return uri;
        }
        if ("http".equals(scheme) && allowInsecureHttp) {
            return uri;
        }
        if ("http".equals(scheme)) {
            throw new IllegalStateException(
                    "manifestUrl uses HTTP; set allowInsecureHttp=true only for trusted local testing in "
                            + configPath);
        }
        throw new IllegalStateException("manifestUrl must use HTTPS or explicitly allowed HTTP in " + configPath);
    }

    private static final class ConfigJson {
        private String manifestUrl = "";
        private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;
        private int readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;
        private boolean allowInsecureHttp;
        private int cleanupBackupsAfterDays = DEFAULT_CLEANUP_BACKUPS_AFTER_DAYS;
        private int cleanupDownloadCacheAfterDays = DEFAULT_CLEANUP_DOWNLOAD_CACHE_AFTER_DAYS;
        private String minecraftVersionMismatchAction = "fail";
        private int completionHoldSeconds = DEFAULT_COMPLETION_HOLD_SECONDS;
    }
}
