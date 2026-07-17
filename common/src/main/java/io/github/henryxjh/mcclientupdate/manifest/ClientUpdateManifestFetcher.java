package io.github.henryxjh.mcclientupdate.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Downloads and parses a client update manifest from a configured URL.
 */
public final class ClientUpdateManifestFetcher {

    private static final int MAX_RESPONSE_SIZE = 4 * 1024 * 1024; // 4 MiB
    private static final String USER_AGENT = "mc-client-update/0.1.0";

    private static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    private static final Set<String> ALLOWED_LOADERS = Set.of("fabric", "neoforge");
    private static final Set<String> ALLOWED_OS = Set.of("android", "windows", "linux", "macos");
    private static final Set<String> ALLOWED_ARCH = Set.of("x86_64", "x86_32", "aarch64", "arm32", "riscv64", "loongarch64");
    private static final Set<String> ALLOWED_PROVIDERS = Set.of("modrinth", "curseforge", "github", "other");

    private static final Gson GSON = new GsonBuilder().create();

    private ClientUpdateManifestFetcher() {
    }

    /**
     * Fetch the manifest, validate its schema and semantics, and return an immutable view.
     *
     * @throws ManifestFetchException if anything goes wrong (network error, invalid JSON, validation failure, etc.)
     */
    public static Manifest fetchManifest(URI manifestUri, Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(manifestUri)
                .GET()
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .timeout(readTimeout)
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ManifestFetchException("Failed to contact update endpoint", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManifestFetchException("Interrupted while contacting update endpoint", e);
        }

        int status = response.statusCode();
        byte[] body;
        try (InputStream bodyStream = response.body()) {
            if (status != 200) {
                throw new ManifestFetchException("Update manifest endpoint returned HTTP " + status);
            }
            body = readLimited(bodyStream, MAX_RESPONSE_SIZE);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Response body exceeds size limit";
            throw new ManifestFetchException(msg, e);
        }

        String rawJson = new String(body, StandardCharsets.UTF_8);

        ManifestJson manifestJson;
        try {
            manifestJson = GSON.fromJson(rawJson, ManifestJson.class);
        } catch (JsonParseException e) {
            throw new ManifestFetchException("Unable to parse manifest JSON", e);
        }

        if (manifestJson == null) {
            throw new ManifestFetchException("Manifest JSON is null after parsing");
        }

        return convertAndValidate(manifestJson);
    }

    static Manifest convertAndValidate(ManifestJson json) {
        if (json.schemaVersion == null) {
            throw new ManifestFetchException("Manifest missing required field 'schemaVersion'");
        }
        if (json.schemaVersion != 1) {
            throw new ManifestFetchException("Unsupported manifest schema version: " + json.schemaVersion);
        }
        if (json.manifestId == null || json.manifestId.isBlank()) {
            throw new ManifestFetchException("Manifest missing required field 'manifestId'");
        }
        if (json.revision == null) {
            throw new ManifestFetchException("Manifest missing required field 'revision'");
        }
        if (json.revision < 0) {
            throw new ManifestFetchException("Manifest revision must be >= 0");
        }

        Instant generatedAt;
        try {
            generatedAt = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(json.generatedAt));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ManifestFetchException("Manifest 'generatedAt' is not a valid ISO-8601 timestamp", e);
        }

        Instant expiresInstant = null;
        if (json.expiresAt != null && !json.expiresAt.isBlank()) {
            try {
                expiresInstant = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(json.expiresAt));
            } catch (DateTimeParseException e) {
                throw new ManifestFetchException("Manifest 'expiresAt' is not a valid ISO-8601 timestamp", e);
            }
            if (Instant.now().isAfter(expiresInstant)) {
                throw new ManifestFetchException("Manifest has expired at " + json.expiresAt);
            }
        }

        String minecraftVersion = json.minecraftVersion;
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new ManifestFetchException("Manifest must include 'minecraftVersion'");
        }

        if (json.baseUrl != null && !json.baseUrl.isBlank()) {
            String b = json.baseUrl.strip();
            URI baseUri;
            try {
                baseUri = URI.create(b);
            } catch (IllegalArgumentException e) {
                throw new ManifestFetchException("Manifest 'baseUrl' is not a valid URI: " + b, e);
            }
            String scheme = baseUri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new ManifestFetchException("Manifest 'baseUrl' must be an absolute HTTP(S) URI: " + b);
            }
            String host = baseUri.getHost();
            if (host == null || host.isBlank()) {
                throw new ManifestFetchException("Manifest 'baseUrl' must have a non-empty host: " + b);
            }
        }

        Map<String, ModJson> modsJson = json.mods;
        if (modsJson == null || modsJson.isEmpty()) {
            throw new ManifestFetchException("Manifest must contain at least one mod entry");
        }

        for (String modId : modsJson.keySet()) {
            if (!MOD_ID_PATTERN.matcher(modId).matches()) {
                throw new ManifestFetchException("Invalid modid: " + modId);
            }
        }

        Map<String, Mod> mods = modsJson.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> convertMod(e.getValue())));

        return new Manifest(
                1,
                json.manifestId.strip(),
                json.revision,
                generatedAt,
                Optional.ofNullable(expiresInstant),
                minecraftVersion.strip(),
                Optional.ofNullable(json.baseUrl).map(String::strip).filter(s -> !s.isBlank()),
                mods);
    }

    private static Mod convertMod(ModJson modJson) {
        if (modJson.name == null || modJson.name.isBlank()) {
            throw new ManifestFetchException("Mod entry is missing required 'name' field");
        }
        if (modJson.required == null) {
            throw new ManifestFetchException("Mod entry '" + modJson.name + "' is missing required field 'required'");
        }
        boolean required = modJson.required;
        String homepageStr = null;
        if (modJson.homepage != null && !modJson.homepage.isBlank()) {
            String raw = modJson.homepage.strip();
            try {
                URI uri = URI.create(raw);
                if (!uri.isAbsolute()) {
                    throw new ManifestFetchException("Mod homepage must be an absolute URI: " + raw);
                }
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                    throw new ManifestFetchException("Mod homepage must be an absolute HTTP(S) URI: " + raw);
                }
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    throw new ManifestFetchException("Mod homepage must have a host: " + raw);
                }
                homepageStr = raw;
            } catch (IllegalArgumentException e) {
                throw new ManifestFetchException("Invalid homepage URI: " + raw, e);
            }
        }

        List<Variant> variants;
        if (modJson.variants == null || modJson.variants.isEmpty()) {
            throw new ManifestFetchException("Mod '" + modJson.name + "' must contain at least one variant");
        }
        for (VariantJson vj : modJson.variants) {
            if (vj == null) {
                throw new ManifestFetchException("Mod '" + modJson.name + "' contains a null variant entry");
            }
        }
        variants = modJson.variants.stream()
                .map(ClientUpdateManifestFetcher::convertVariant)
                .collect(Collectors.toUnmodifiableList());

        return new Mod(
                modJson.name.strip(),
                required,
                Optional.ofNullable(homepageStr),
                Optional.ofNullable(modJson.license).map(String::strip).filter(s -> !s.isBlank()),
                variants);
    }

    private static Variant convertVariant(VariantJson variantJson) {
        SelectorJson sel = variantJson.selector;
        if (sel == null) {
            throw new ManifestFetchException("Variant is missing required 'selector'");
        }

        Selector selector = new Selector(
                validateSelectorList(sel.loaders, "loaders", ALLOWED_LOADERS),
                validateSelectorList(sel.operatingSystems, "operatingSystems", ALLOWED_OS),
                validateSelectorList(sel.architectures, "architectures", ALLOWED_ARCH));

        Artifact artifact = convertArtifact(variantJson.artifact);
        return new Variant(selector, variantJson.priority, artifact);
    }

    private static Optional<List<String>> validateSelectorList(List<String> raw, String fieldName, Set<String> allowed) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.isEmpty()) {
            throw new ManifestFetchException("Selector '" + fieldName + "' must not be empty when present");
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                throw new ManifestFetchException("Selector '" + fieldName + "' contains a blank entry");
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                throw new ManifestFetchException("Unknown value in selector '" + fieldName + "': " + value);
            }
        }
        List<String> copy = List.copyOf(raw.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).distinct().toList());
        if (copy.size() < raw.size()) {
            throw new ManifestFetchException("Selector '" + fieldName + "' contains duplicate entries");
        }
        return Optional.of(copy);
    }

    private static Artifact convertArtifact(ArtifactJson artJson) {
        if (artJson == null) {
            throw new ManifestFetchException("Variant is missing required 'artifact'");
        }
        if (artJson.version == null || artJson.version.isBlank()) {
            throw new ManifestFetchException("Artifact 'version' must not be blank");
        }
        if (artJson.fileName == null || artJson.fileName.isBlank()) {
            throw new ManifestFetchException("Artifact 'fileName' must not be blank");
        }
        if (!artJson.fileName.matches("^[^/\\\\]+\\.jar$")) {
            throw new ManifestFetchException("Artifact 'fileName' does not match allowed pattern: " + artJson.fileName);
        }
        if (artJson.size == null) {
            throw new ManifestFetchException("Artifact 'size' is missing");
        }
        if (artJson.size <= 0) {
            throw new ManifestFetchException("Artifact 'size' must be positive, got: " + artJson.size);
        }
        Hashes hashes = convertHashes(artJson.hashes);
        Download download = convertDownload(artJson.download);

        return new Artifact(
                artJson.version.strip(),
                artJson.fileName.strip(),
                artJson.size,
                hashes,
                download);
    }

    private static Hashes convertHashes(HashesJson h) {
        if (h == null) {
            throw new ManifestFetchException("Artifact is missing required 'hashes'");
        }
        String s256 = h.sha256 != null ? h.sha256.strip().toLowerCase(Locale.ROOT) : "";
        String s512 = h.sha512 != null ? h.sha512.strip().toLowerCase(Locale.ROOT) : "";
        boolean has256 = !s256.isEmpty();
        boolean has512 = !s512.isEmpty();
        if (!has256 && !has512) {
            throw new ManifestFetchException("Artifact hashes must contain at least one of sha256/sha512");
        }
        if (has256) {
            if (s256.length() != 64 || !s256.matches("[0-9a-f]+")) {
                throw new ManifestFetchException("Invalid sha256 hash: " + s256);
            }
        }
        if (has512) {
            if (s512.length() != 128 || !s512.matches("[0-9a-f]+")) {
                throw new ManifestFetchException("Invalid sha512 hash: " + s512);
            }
        }
        return new Hashes(
                has256 ? Optional.of(s256) : Optional.empty(),
                has512 ? Optional.of(s512) : Optional.empty());
    }

    private static Download convertDownload(DownloadJson d) {
        if (d == null) {
            throw new ManifestFetchException("Artifact is missing required 'download'");
        }
        String type = d.type != null ? d.type.strip().toLowerCase(Locale.ROOT) : "";
        return switch (type) {
            case "hosted" -> {
                if (d.url == null || d.url.isBlank()) {
                    throw new ManifestFetchException("hosted download url is required");
                }
                String hurl = d.url.strip();
                URI hostUri;
                try {
                    hostUri = URI.create(hurl);
                } catch (IllegalArgumentException e) {
                    throw new ManifestFetchException("Invalid hosted download URL: " + hurl, e);
                }
                if (hostUri.isAbsolute()) {
                    String scheme = hostUri.getScheme();
                    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                        throw new ManifestFetchException("hosted download absolute URL must be HTTP(S): " + hurl);
                    }
                    String host = hostUri.getHost();
                    if (host == null || host.isBlank()) {
                        throw new ManifestFetchException("hosted download absolute URL must contain a host: " + hurl);
                    }
                }
                yield new HostedDownload(hurl);
            }
            case "direct" -> {
                if (d.url == null || d.url.isBlank()) {
                    throw new ManifestFetchException("direct download url is required");
                }
                String duri = d.url.strip();
                URI directUri;
                try {
                    directUri = URI.create(duri);
                } catch (IllegalArgumentException e) {
                    throw new ManifestFetchException("Invalid direct download URL: " + duri, e);
                }
                String directScheme = directUri.getScheme();
                if (directScheme == null || (!directScheme.equalsIgnoreCase("http") && !directScheme.equalsIgnoreCase("https"))) {
                    throw new ManifestFetchException("direct download url must be absolute HTTP(S): " + duri);
                }
                String host = directUri.getHost();
                if (host == null || host.isBlank()) {
                    throw new ManifestFetchException("direct download url must have a host: " + duri);
                }
                Optional<String> prov = optionalStringFromElement(d.provider);
                prov.ifPresent(p -> {
                    if (!ALLOWED_PROVIDERS.contains(p.toLowerCase(Locale.ROOT))) {
                        throw new ManifestFetchException("Unknown direct download provider: " + p);
                    }
                });
                yield new DirectDownload(duri, prov, parseStringOrNumber(d.projectId), parseStringOrNumber(d.versionId));
            }
            case "manual" -> {
                if (d.pageUrl == null || d.pageUrl.isBlank()) {
                    throw new ManifestFetchException("manual download pageUrl is required");
                }
                String purl = d.pageUrl.strip();
                URI manualUri;
                try {
                    manualUri = URI.create(purl);
                } catch (IllegalArgumentException e) {
                    throw new ManifestFetchException("Invalid manual download pageUrl: " + purl, e);
                }
                String manualScheme = manualUri.getScheme();
                if (manualScheme == null || (!manualScheme.equalsIgnoreCase("http") && !manualScheme.equalsIgnoreCase("https"))) {
                    throw new ManifestFetchException("manual download pageUrl must be absolute HTTP(S): " + purl);
                }
                String host = manualUri.getHost();
                if (host == null || host.isBlank()) {
                    throw new ManifestFetchException("manual download pageUrl must have a host: " + purl);
                }
                yield new ManualDownload(purl, optionalString(d.message));
            }
            default -> throw new ManifestFetchException("Unsupported download type: " + d.type);
        };
    }

    private static <T> T throwMissing(String field) {
        throw new ManifestFetchException("Required field missing: " + field);
    }

    private static Optional<String> optionalString(Object obj) {
        if (obj == null) {
            return Optional.empty();
        }
        String s = obj.toString().strip();
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    // --------------- JSON mapping classes ---------------

    @SuppressWarnings("unused")
    private static final class ManifestJson {
        @SerializedName("schemaVersion")
        Integer schemaVersion;
        String manifestId;
        Long revision;
        String generatedAt;
        String expiresAt;
        String minecraftVersion;
        String baseUrl;
        Map<String, ModJson> mods;
    }

    @SuppressWarnings("unused")
    private static final class ModJson {
        String name;
        Boolean required;
        String homepage;
        String license;
        List<VariantJson> variants;
    }

    @SuppressWarnings("unused")
    private static final class VariantJson {
        SelectorJson selector;
        int priority;
        ArtifactJson artifact;
    }

    @SuppressWarnings("unused")
    private static final class SelectorJson {
        List<String> loaders;
        List<String> operatingSystems;
        List<String> architectures;
    }

    @SuppressWarnings("unused")
    private static final class ArtifactJson {
        String version;
        String fileName;
        Long size;
        HashesJson hashes;
        DownloadJson download;
    }

    @SuppressWarnings("unused")
    private static final class HashesJson {
        String sha256;
        String sha512;
    }

    @SuppressWarnings("unused")
    private static final class DownloadJson {
        String type;
        String url;
        String pageUrl;
        JsonElement provider;
        JsonElement projectId;
        JsonElement versionId;
        String message;
    }

    // --------------- JSON element helpers ---------------

    private static Optional<String> optionalStringFromElement(JsonElement element) {
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ManifestFetchException("String value expected but got " + element);
        }
        String s = element.getAsString().strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    private static Optional<String> parseStringOrNumber(JsonElement element) {
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive()) {
            throw new ManifestFetchException("Expected a string or number but got " + element);
        }
        JsonPrimitive prim = element.getAsJsonPrimitive();
        if (prim.isString()) {
            return Optional.of(prim.getAsString().strip());
        }
        if (prim.isBoolean()) {
            throw new ManifestFetchException("Boolean value is not allowed for string/number field");
        }
        if (prim.isNumber()) {
            try {
                BigDecimal bd = prim.getAsBigDecimal();
                BigInteger bi = bd.toBigIntegerExact();
                return Optional.of(bi.toString());
            } catch (ArithmeticException | NumberFormatException e) {
                throw new ManifestFetchException("Only integer JSON numbers are allowed for this field: " + element, e);
            }
        }
        throw new ManifestFetchException("Unexpected JSON value type: " + element);
    }

    // --------------- Utility ---------------

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Response body exceeds maximum allowed size (" + limit + " bytes)");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }
}
