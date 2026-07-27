package io.github.henryxjh.mcclientupdate.server.workspace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.henryxjh.mcclientupdate.server.ManifestGenConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-only API for {@code manifest-workspace.json}.
 *
 * <p>The workspace file is an editable intermediate format shared with the
 * Python manifest generator. This API intentionally parses only stable fields
 * into typed records and ignores unknown fields, so Java callers are not tied
 * to every internal generator detail.</p>
 */
public final class ManifestWorkspaceApi {

    private final Path gameDirectory;
    private final Supplier<ManifestGenConfig> configSupplier;

    /**
     * Creates a workspace API bound to a server game directory.
     *
     * @param gameDirectory server game directory
     * @param configSupplier supplier returning the current manifest generator config
     */
    public ManifestWorkspaceApi(Path gameDirectory, Supplier<ManifestGenConfig> configSupplier) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    /**
     * Returns the configured workspace file path.
     */
    public Path path() {
        return configSupplier.get().getOutputPath(gameDirectory);
    }

    /**
     * Loads and parses the configured workspace file.
     *
     * <p>If the file does not exist, this returns an empty workspace with
     * {@link WorkspaceLoadResult#exists()} set to {@code false}. Invalid JSON
     * or I/O errors throw {@link IllegalStateException}.</p>
     */
    public WorkspaceLoadResult load() {
        Path workspacePath = path();
        if (!Files.exists(workspacePath)) {
            Workspace empty = new Workspace(null, 1, null, null, null, Map.of(), Map.of());
            return new WorkspaceLoadResult(
                    workspacePath,
                    false,
                    empty,
                    "Workspace file does not exist: " + workspacePath);
        }

        try (Reader reader = Files.newBufferedReader(workspacePath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Workspace root must be a JSON object: " + workspacePath);
            }
            Workspace workspace = parseWorkspace(parsed.getAsJsonObject());
            return new WorkspaceLoadResult(
                    workspacePath,
                    true,
                    workspace,
                    "Loaded workspace with " + workspace.mods().size() + " mods from " + workspacePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read workspace: " + workspacePath, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to parse workspace: " + workspacePath, e);
        }
    }

    /**
     * Lists mods from the configured workspace file, sorted by mod id.
     */
    public WorkspaceModListResult listMods() {
        WorkspaceLoadResult loaded = load();
        List<WorkspaceMod> mods = new ArrayList<>(loaded.workspace().mods().values());
        mods.sort(Comparator.comparing(WorkspaceMod::modId));
        String message = loaded.exists()
                ? "Workspace mods (" + mods.size() + "): " + loaded.path()
                : loaded.message();
        return new WorkspaceModListResult(loaded.path(), loaded.exists(), mods, message);
    }

    /**
     * Finds one mod entry in the configured workspace file.
     *
     * @param modId exact mod id to look up
     * @return the workspace mod when present
     */
    public Optional<WorkspaceMod> findMod(String modId) {
        Objects.requireNonNull(modId, "modId");
        return Optional.ofNullable(load().workspace().mods().get(modId));
    }

    private static Workspace parseWorkspace(JsonObject root) {
        return new Workspace(
                stringOrNull(root, "manifestId"),
                intOrDefault(root, "revision", 1),
                stringOrNull(root, "minecraftVersion"),
                stringOrNull(root, "baseUrl"),
                stringOrNull(root, "expiresAt"),
                stringMap(root, "minimumLoaderVersions"),
                parseMods(root));
    }

    private static Map<String, WorkspaceMod> parseMods(JsonObject root) {
        JsonObject modsObject = objectOrNull(root, "mods");
        if (modsObject == null) {
            return Map.of();
        }

        Map<String, WorkspaceMod> mods = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : modsObject.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            String modId = entry.getKey();
            mods.put(modId, parseMod(modId, entry.getValue().getAsJsonObject()));
        }
        return Collections.unmodifiableMap(mods);
    }

    private static WorkspaceMod parseMod(String modId, JsonObject json) {
        return new WorkspaceMod(
                modId,
                stringOrDefault(json, "name", modId),
                booleanOrDefault(json, "required", false),
                stringOrDefault(json, "action", "install"),
                stringOrNull(json, "license"),
                stringOrNull(json, "homepage"),
                stringOrNull(json, "skipIfInstalledVersionGreaterThan"),
                parseVariants(json));
    }

    private static List<WorkspaceVariant> parseVariants(JsonObject json) {
        JsonElement variantsElement = json.get("variants");
        if (variantsElement == null || !variantsElement.isJsonArray()) {
            return List.of();
        }

        List<WorkspaceVariant> variants = new ArrayList<>();
        for (JsonElement element : variantsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject variant = element.getAsJsonObject();
            variants.add(new WorkspaceVariant(
                    parseSelector(objectOrNull(variant, "selector")),
                    stringOrDefault(variant, "action", "install"),
                    intOrDefault(variant, "priority", 0),
                    stringOrNull(variant, "version"),
                    stringOrNull(variant, "fileName"),
                    longOrNull(variant, "size"),
                    stringOrNull(variant, "localFile"),
                    parseDownload(objectOrNull(variant, "download")),
                    stringMap(variant, "hashes")));
        }
        return List.copyOf(variants);
    }

    private static WorkspaceSelector parseSelector(JsonObject selector) {
        if (selector == null) {
            return new WorkspaceSelector(List.of(), List.of(), List.of());
        }
        return new WorkspaceSelector(
                stringList(selector, "loaders"),
                stringList(selector, "operatingSystems"),
                stringList(selector, "architectures"));
    }

    private static WorkspaceDownload parseDownload(JsonObject download) {
        if (download == null) {
            return null;
        }
        return new WorkspaceDownload(
                stringOrNull(download, "type"),
                stringOrNull(download, "url"),
                stringOrNull(download, "pageUrl"));
    }

    private static JsonObject objectOrNull(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        return value.getAsJsonObject();
    }

    private static String stringOrNull(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            String text = value.getAsString();
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stringOrDefault(JsonObject json, String key, String fallback) {
        String value = stringOrNull(json, key);
        return value != null ? value : fallback;
    }

    private static boolean booleanOrDefault(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intOrDefault(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Long longOrNull(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> stringList(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                String text = element.getAsString();
                if (text != null && !text.isBlank()) {
                    result.add(text);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonObject json, String key) {
        JsonObject object = objectOrNull(json, key);
        if (object == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            try {
                result.put(entry.getKey(), value.getAsString());
            } catch (RuntimeException ignored) {
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Result of loading the workspace file.
     *
     * @param path workspace file path
     * @param exists whether the workspace file exists on disk
     * @param workspace parsed typed workspace view
     * @param message user-facing status message
     */
    public record WorkspaceLoadResult(Path path, boolean exists, Workspace workspace, String message) {}

    /**
     * Result of listing workspace mods.
     *
     * @param path workspace file path
     * @param exists whether the workspace file exists on disk
     * @param mods sorted workspace mods
     * @param message user-facing status message
     */
    public record WorkspaceModListResult(Path path, boolean exists, List<WorkspaceMod> mods, String message) {
        public WorkspaceModListResult {
            mods = List.copyOf(mods);
        }
    }

    /**
     * Typed view of a workspace document.
     *
     * @param manifestId workspace manifest id, or {@code null} when absent
     * @param revision workspace revision, defaulting to {@code 1}
     * @param minecraftVersion target Minecraft version, or {@code null} when absent
     * @param baseUrl optional hosted download base URL
     * @param expiresAt optional manifest expiry time string
     * @param minimumLoaderVersions optional minimum loader versions by loader id
     * @param mods mod entries keyed by mod id
     */
    public record Workspace(
            String manifestId,
            int revision,
            String minecraftVersion,
            String baseUrl,
            String expiresAt,
            Map<String, String> minimumLoaderVersions,
            Map<String, WorkspaceMod> mods) {
        public Workspace {
            minimumLoaderVersions = Collections.unmodifiableMap(new LinkedHashMap<>(minimumLoaderVersions));
            mods = Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        }
    }

    /**
     * Typed view of one workspace mod entry.
     *
     * @param modId mod id key
     * @param name display name, defaulting to the mod id
     * @param required whether the mod is required by the pack
     * @param action mod-level action, defaulting to {@code install}
     * @param license optional license string
     * @param homepage optional homepage URL
     * @param skipIfInstalledVersionGreaterThan optional client-side version replacement guard
     * @param variants variants declared for this mod
     */
    public record WorkspaceMod(
            String modId,
            String name,
            boolean required,
            String action,
            String license,
            String homepage,
            String skipIfInstalledVersionGreaterThan,
            List<WorkspaceVariant> variants) {
        public WorkspaceMod {
            variants = List.copyOf(variants);
        }
    }

    /**
     * Typed view of one workspace variant.
     *
     * @param selector platform selector for this variant
     * @param action variant-level action, defaulting to {@code install}
     * @param priority selector conflict priority, defaulting to {@code 0}
     * @param version mod version string
     * @param fileName source or target file name
     * @param size file size in bytes when known
     * @param localFile optional local file used by the Python generator
     * @param download optional download metadata
     * @param hashes optional hashes by hash type
     */
    public record WorkspaceVariant(
            WorkspaceSelector selector,
            String action,
            int priority,
            String version,
            String fileName,
            Long size,
            String localFile,
            WorkspaceDownload download,
            Map<String, String> hashes) {
        public WorkspaceVariant {
            hashes = Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
        }
    }

    /**
     * Platform selector used by a workspace variant.
     *
     * @param loaders accepted loaders
     * @param operatingSystems accepted operating systems
     * @param architectures accepted CPU architectures
     */
    public record WorkspaceSelector(
            List<String> loaders,
            List<String> operatingSystems,
            List<String> architectures) {
        public WorkspaceSelector {
            loaders = List.copyOf(loaders);
            operatingSystems = List.copyOf(operatingSystems);
            architectures = List.copyOf(architectures);
        }
    }

    /**
     * Download metadata declared by a workspace variant.
     *
     * @param type download type such as {@code direct}, {@code hosted}, or {@code manual}
     * @param url direct or hosted URL
     * @param pageUrl manual download page URL
     */
    public record WorkspaceDownload(String type, String url, String pageUrl) {}
}
