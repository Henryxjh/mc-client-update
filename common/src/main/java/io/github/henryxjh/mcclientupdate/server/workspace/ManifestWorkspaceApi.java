package io.github.henryxjh.mcclientupdate.server.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * API for reading and editing {@code manifest-workspace.json}.
 *
 * <p>The workspace file is an editable intermediate format shared with the
 * Python manifest generator. Read methods parse stable fields into typed
 * records and ignore unknown fields. Write methods mutate the raw JSON document
 * through a typed editor, preserving unknown fields unless a caller explicitly
 * replaces the edited object.</p>
 */
public final class ManifestWorkspaceApi {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

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

    /**
     * Edits and saves the configured workspace file.
     *
     * <p>The editor mutates the raw workspace JSON document and writes it back
     * with unknown fields preserved. If the workspace file does not exist, an
     * empty workspace document is created before applying the edit.</p>
     *
     * @param edit consumer that applies changes through the typed editor API
     * @return write result containing the parsed workspace after the edit
     */
    public WorkspaceWriteResult edit(Consumer<WorkspaceEditor> edit) {
        Objects.requireNonNull(edit, "edit");
        Path workspacePath = path();
        boolean existed = Files.exists(workspacePath);
        JsonObject root = existed ? readRaw(workspacePath) : new JsonObject();
        String before = GSON.toJson(root);

        edit.accept(new WorkspaceEditor(root));

        String after = GSON.toJson(root);
        boolean changed = !before.equals(after);
        if (changed) {
            writeRaw(workspacePath, root);
        }

        Workspace workspace = parseWorkspace(root);
        String message = changed
                ? "Saved workspace with " + workspace.mods().size() + " mods to " + workspacePath
                : "Workspace unchanged: " + workspacePath;
        return new WorkspaceWriteResult(workspacePath, existed, changed, workspace, message);
    }

    private static JsonObject readRaw(Path workspacePath) {
        try (Reader reader = Files.newBufferedReader(workspacePath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Workspace root must be a JSON object: " + workspacePath);
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read workspace: " + workspacePath, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to parse workspace: " + workspacePath, e);
        }
    }

    private static void writeRaw(Path workspacePath, JsonObject root) {
        try {
            Path parent = workspacePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(workspacePath, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write workspace: " + workspacePath, e);
        }
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
                    booleanOrNull(variant, "required"),
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
            return value.getAsString();
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

    private static Boolean booleanOrNull(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return null;
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

    private static JsonObject ensureObject(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        JsonObject object = new JsonObject();
        json.add(key, object);
        return object;
    }

    private static JsonArray ensureArray(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonArray()) {
            return value.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        json.add(key, array);
        return array;
    }

    private static void setStringOrRemove(JsonObject json, String key, String value) {
        if (value == null) {
            json.remove(key);
        } else {
            json.addProperty(key, value);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static JsonObject selectorToJson(WorkspaceSelector selector) {
        JsonObject json = new JsonObject();
        WorkspaceSelector safeSelector = selector != null
                ? selector
                : new WorkspaceSelector(List.of(), List.of(), List.of());
        addStringArray(json, "loaders", safeSelector.loaders());
        addStringArray(json, "operatingSystems", safeSelector.operatingSystems());
        addStringArray(json, "architectures", safeSelector.architectures());
        return json;
    }

    private static void addStringArray(JsonObject json, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        if (array.size() > 0) {
            json.add(key, array);
        }
    }

    private static JsonObject variantToJson(WorkspaceVariant variant) {
        Objects.requireNonNull(variant, "variant");
        JsonObject json = new JsonObject();
        json.add("selector", selectorToJson(variant.selector()));
        if (variant.required() != null) {
            json.addProperty("required", variant.required());
        }
        setStringOrRemove(json, "action", variant.action() != null ? variant.action() : "install");
        if (variant.priority() != 0) {
            json.addProperty("priority", variant.priority());
        }
        setStringOrRemove(json, "version", variant.version());
        setStringOrRemove(json, "fileName", variant.fileName());
        if (variant.size() != null) {
            json.addProperty("size", variant.size());
        }
        setStringOrRemove(json, "localFile", variant.localFile());
        if (variant.download() != null) {
            JsonObject download = new JsonObject();
            setStringOrRemove(download, "type", variant.download().type());
            setStringOrRemove(download, "url", variant.download().url());
            setStringOrRemove(download, "pageUrl", variant.download().pageUrl());
            json.add("download", download);
        }
        if (!variant.hashes().isEmpty()) {
            JsonObject hashes = new JsonObject();
            for (Map.Entry<String, String> entry : variant.hashes().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    hashes.addProperty(entry.getKey(), entry.getValue());
                }
            }
            json.add("hashes", hashes);
        }
        return json;
    }

    private static boolean selectorMatches(JsonObject variant, WorkspaceSelector selector) {
        return selectorEquals(parseSelector(objectOrNull(variant, "selector")), selector);
    }

    private static boolean selectorEquals(WorkspaceSelector a, WorkspaceSelector b) {
        WorkspaceSelector safeA = a != null ? a : new WorkspaceSelector(List.of(), List.of(), List.of());
        WorkspaceSelector safeB = b != null ? b : new WorkspaceSelector(List.of(), List.of(), List.of());
        return stringSetEquals(safeA.loaders(), safeB.loaders())
                && stringSetEquals(safeA.operatingSystems(), safeB.operatingSystems())
                && stringSetEquals(safeA.architectures(), safeB.architectures());
    }

    private static boolean stringSetEquals(List<String> left, List<String> right) {
        return new java.util.HashSet<>(left).equals(new java.util.HashSet<>(right));
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
     * Result of editing and saving the workspace file.
     *
     * @param path workspace file path
     * @param existedBefore whether the file existed before this edit
     * @param changed whether the edit changed the JSON document and wrote it to disk
     * @param workspace parsed typed workspace view after the edit
     * @param message user-facing status message
     */
    public record WorkspaceWriteResult(
            Path path,
            boolean existedBefore,
            boolean changed,
            Workspace workspace,
            String message) {}

    /**
     * Mutable editor for the workspace document.
     *
     * <p>Only fields modified through this editor are changed. Existing
     * unknown fields are preserved in the raw JSON document.</p>
     */
    public static final class WorkspaceEditor {
        private final JsonObject root;

        private WorkspaceEditor(JsonObject root) {
            this.root = root;
        }

        /**
         * Sets the workspace manifest id.
         */
        public WorkspaceEditor setManifestId(String manifestId) {
            root.addProperty("manifestId", requireNonBlank(manifestId, "manifestId"));
            return this;
        }

        /**
         * Sets the workspace revision.
         */
        public WorkspaceEditor setRevision(int revision) {
            root.addProperty("revision", revision);
            return this;
        }

        /**
         * Sets or clears the target Minecraft version.
         */
        public WorkspaceEditor setMinecraftVersion(String minecraftVersion) {
            setStringOrRemove(root, "minecraftVersion", minecraftVersion);
            return this;
        }

        /**
         * Sets or clears the hosted base URL.
         */
        public WorkspaceEditor setBaseUrl(String baseUrl) {
            setStringOrRemove(root, "baseUrl", baseUrl);
            return this;
        }

        /**
         * Sets or clears the expiry timestamp.
         */
        public WorkspaceEditor setExpiresAt(String expiresAt) {
            setStringOrRemove(root, "expiresAt", expiresAt);
            return this;
        }

        /**
         * Sets a minimum loader version entry.
         */
        public WorkspaceEditor setMinimumLoaderVersion(String loader, String version) {
            ensureObject(root, "minimumLoaderVersions")
                    .addProperty(requireNonBlank(loader, "loader"), requireNonBlank(version, "version"));
            return this;
        }

        /**
         * Removes a minimum loader version entry.
         */
        public WorkspaceEditor removeMinimumLoaderVersion(String loader) {
            JsonObject versions = objectOrNull(root, "minimumLoaderVersions");
            if (versions != null) {
                versions.remove(requireNonBlank(loader, "loader"));
            }
            return this;
        }

        /**
         * Returns an editor for a mod entry, creating the entry if it does not exist.
         */
        public WorkspaceModEditor mod(String modId) {
            String normalizedModId = requireNonBlank(modId, "modId");
            JsonObject mods = ensureObject(root, "mods");
            JsonObject mod;
            JsonElement current = mods.get(normalizedModId);
            if (current != null && current.isJsonObject()) {
                mod = current.getAsJsonObject();
            } else {
                mod = new JsonObject();
                mod.addProperty("name", normalizedModId);
                mod.addProperty("required", true);
                mod.add("variants", new JsonArray());
                mods.add(normalizedModId, mod);
            }
            return new WorkspaceModEditor(mod, normalizedModId);
        }

        /**
         * Removes a mod entry.
         */
        public WorkspaceEditor removeMod(String modId) {
            JsonObject mods = objectOrNull(root, "mods");
            if (mods != null) {
                mods.remove(requireNonBlank(modId, "modId"));
            }
            return this;
        }
    }

    /**
     * Mutable editor for one workspace mod entry.
     */
    public static final class WorkspaceModEditor {
        private final JsonObject mod;
        private final String modId;

        private WorkspaceModEditor(JsonObject mod, String modId) {
            this.mod = mod;
            this.modId = modId;
        }

        /**
         * Sets the display name.
         */
        public WorkspaceModEditor setName(String name) {
            mod.addProperty("name", requireNonBlank(name, "name"));
            return this;
        }

        /**
         * Sets whether this mod is required.
         */
        public WorkspaceModEditor setRequired(boolean required) {
            mod.addProperty("required", required);
            return this;
        }

        /**
         * Sets or clears the mod-level action.
         */
        public WorkspaceModEditor setAction(String action) {
            setStringOrRemove(mod, "action", action);
            return this;
        }

        /**
         * Sets or clears the license string. Empty string is preserved.
         */
        public WorkspaceModEditor setLicense(String license) {
            setStringOrRemove(mod, "license", license);
            return this;
        }

        /**
         * Sets or clears the homepage URL.
         */
        public WorkspaceModEditor setHomepage(String homepage) {
            setStringOrRemove(mod, "homepage", homepage);
            return this;
        }

        /**
         * Sets or clears the version replacement guard.
         */
        public WorkspaceModEditor setSkipIfInstalledVersionGreaterThan(String version) {
            setStringOrRemove(mod, "skipIfInstalledVersionGreaterThan", version);
            return this;
        }

        /**
         * Adds or replaces a variant matched by selector.
         *
         * <p>Replacing a variant intentionally replaces that variant object.
         * Other variants and mod-level unknown fields are preserved.</p>
         */
        public WorkspaceModEditor addOrReplaceVariant(WorkspaceVariant variant) {
            Objects.requireNonNull(variant, "variant");
            JsonArray variants = ensureArray(mod, "variants");
            for (int i = 0; i < variants.size(); i++) {
                JsonElement current = variants.get(i);
                if (current.isJsonObject() && selectorMatches(current.getAsJsonObject(), variant.selector())) {
                    variants.set(i, variantToJson(variant));
                    return this;
                }
            }
            variants.add(variantToJson(variant));
            return this;
        }

        /**
         * Returns an editor for a variant matched by selector, creating it if missing.
         *
         * <p>This editor mutates the existing variant object in place, so
         * unknown fields on that variant are preserved.</p>
         */
        public WorkspaceVariantEditor variant(WorkspaceSelector selector) {
            Objects.requireNonNull(selector, "selector");
            JsonArray variants = ensureArray(mod, "variants");
            for (JsonElement current : variants) {
                if (current.isJsonObject() && selectorMatches(current.getAsJsonObject(), selector)) {
                    return new WorkspaceVariantEditor(current.getAsJsonObject());
                }
            }

            JsonObject variant = new JsonObject();
            variant.add("selector", selectorToJson(selector));
            variants.add(variant);
            return new WorkspaceVariantEditor(variant);
        }

        /**
         * Sets or clears {@code localFile} on the variant matched by selector.
         */
        public WorkspaceModEditor setVariantLocalFile(WorkspaceSelector selector, String localFile) {
            variant(selector).setLocalFile(localFile);
            return this;
        }

        /**
         * Sets or clears {@code download.url} on the variant matched by selector.
         */
        public WorkspaceModEditor setVariantDownloadUrl(WorkspaceSelector selector, String url) {
            variant(selector).setDownloadUrl(url);
            return this;
        }

        /**
         * Sets selector-specific required state on the variant matched by selector.
         */
        public WorkspaceModEditor setVariantRequired(WorkspaceSelector selector, boolean required) {
            variant(selector).setRequired(required);
            return this;
        }

        /**
         * Removes variants with a matching selector.
         */
        public WorkspaceModEditor removeVariant(WorkspaceSelector selector) {
            JsonArray variants = ensureArray(mod, "variants");
            JsonArray remaining = new JsonArray();
            for (JsonElement current : variants) {
                if (!current.isJsonObject() || !selectorMatches(current.getAsJsonObject(), selector)) {
                    remaining.add(current);
                }
            }
            mod.add("variants", remaining);
            return this;
        }

        /**
         * Marks this mod for deletion at mod level.
         */
        public WorkspaceModEditor markDelete() {
            mod.addProperty("action", "delete");
            mod.addProperty("required", false);
            return this;
        }

        /**
         * Returns the mod id edited by this object.
         */
        public String modId() {
            return modId;
        }
    }

    /**
     * Mutable editor for one workspace variant.
     */
    public static final class WorkspaceVariantEditor {
        private final JsonObject variant;

        private WorkspaceVariantEditor(JsonObject variant) {
            this.variant = variant;
        }

        /**
         * Sets selector-specific required state.
         */
        public WorkspaceVariantEditor setRequired(boolean required) {
            variant.addProperty("required", required);
            return this;
        }

        /**
         * Clears selector-specific required state, falling back to mod-level required.
         */
        public WorkspaceVariantEditor clearRequired() {
            variant.remove("required");
            return this;
        }

        /**
         * Sets or clears the local file used by the Python generator.
         */
        public WorkspaceVariantEditor setLocalFile(String localFile) {
            setStringOrRemove(variant, "localFile", localFile);
            return this;
        }

        /**
         * Sets or clears the download type.
         */
        public WorkspaceVariantEditor setDownloadType(String type) {
            setStringOrRemove(ensureObject(variant, "download"), "type", type);
            return this;
        }

        /**
         * Sets or clears {@code download.url}.
         */
        public WorkspaceVariantEditor setDownloadUrl(String url) {
            setStringOrRemove(ensureObject(variant, "download"), "url", url);
            return this;
        }

        /**
         * Sets or clears {@code download.pageUrl}.
         */
        public WorkspaceVariantEditor setDownloadPageUrl(String pageUrl) {
            setStringOrRemove(ensureObject(variant, "download"), "pageUrl", pageUrl);
            return this;
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
     * @param required selector-specific required state, or {@code null} when absent
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
            Boolean required,
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
