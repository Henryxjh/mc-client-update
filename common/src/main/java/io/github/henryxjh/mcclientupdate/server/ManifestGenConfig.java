package io.github.henryxjh.mcclientupdate.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for mcu-manifest-gen, stored at {@code config/mcu-manifest-gen.json}.
 */
public final class ManifestGenConfig {

    private static final String FILE_NAME = "mcu-manifest-gen.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {}.getType();

    private List<String> ignoredMods;
    private List<String> allowedUsers;
    private String outputPath;

    private ManifestGenConfig(List<String> ignoredMods, List<String> allowedUsers, String outputPath) {
        this.ignoredMods = new ArrayList<>(ignoredMods);
        this.allowedUsers = new ArrayList<>(allowedUsers);
        this.outputPath = outputPath;
    }

    // ---- Load / Save -------------------------------------------------

    public static ManifestGenConfig load(Path gameDirectory) {
        Path configPath = gameDirectory.resolve("config").resolve(FILE_NAME);
        if (!Files.exists(configPath)) {
            ManifestGenConfig defaults = new ManifestGenConfig(
                    new ArrayList<>(List.of("mcu_manifest_gen")),
                    new ArrayList<>(),
                    "manifest-workspace.json");
            defaults.save(gameDirectory);
            return defaults;
        }

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            ConfigJson json = GSON.fromJson(raw, ConfigJson.class);
            if (json == null) {
                throw new IllegalStateException("Config is empty: " + configPath);
            }
            return new ManifestGenConfig(
                    json.ignoredMods != null ? json.ignoredMods : List.of("mcu_manifest_gen"),
                    json.allowedUsers != null ? json.allowedUsers : List.of(),
                    json.outputPath != null && !json.outputPath.isBlank()
                            ? json.outputPath : "manifest-workspace.json");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + configPath, e);
        }
    }

    public void save(Path gameDirectory) {
        Path configPath = gameDirectory.resolve("config").resolve(FILE_NAME);
        ConfigJson json = new ConfigJson();
        json.ignoredMods = new ArrayList<>(this.ignoredMods);
        json.allowedUsers = new ArrayList<>(this.allowedUsers);
        json.outputPath = this.outputPath;
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(json) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + configPath, e);
        }
    }

    // ---- Accessors ----------------------------------------------------

    public List<String> getIgnoredMods() {
        return Collections.unmodifiableList(ignoredMods);
    }

    public List<String> getAllowedUsers() {
        return Collections.unmodifiableList(allowedUsers);
    }

    public Path getOutputPath(Path gameDirectory) {
        return gameDirectory.resolve(outputPath);
    }

    public boolean isUserAllowed(String username) {
        if (allowedUsers.isEmpty()) {
            return false; // only console is allowed when the list is empty
        }
        return allowedUsers.contains(username);
    }

    // ---- Ignored mods management --------------------------------------

    public boolean addIgnored(String modId) {
        Objects.requireNonNull(modId, "modId");
        if (ignoredMods.contains(modId)) {
            return false;
        }
        ignoredMods.add(modId);
        return true;
    }

    public boolean removeIgnored(String modId) {
        Objects.requireNonNull(modId, "modId");
        return ignoredMods.remove(modId);
    }

    // ---- JSON DTO -----------------------------------------------------

    private static class ConfigJson {
        List<String> ignoredMods;
        List<String> allowedUsers;
        String outputPath;
    }
}
