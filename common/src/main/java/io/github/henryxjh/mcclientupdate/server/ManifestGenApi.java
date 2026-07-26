package io.github.henryxjh.mcclientupdate.server;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stable server-side API for the manifest generator mod.
 *
 * <p>Server management mods should call this class instead of invoking
 * Brigadier commands or copying command implementation details. Platform
 * entrypoints provide {@code api()} factories that already know how to collect
 * the current loader's installed mod list.</p>
 */
public final class ManifestGenApi {

    private final Path gameDirectory;
    private final String loader;
    private final String minecraftVersion;
    private final Supplier<List<InstalledMod>> installedModsSupplier;

    /**
     * Creates a platform-independent manifest generator API instance.
     *
     * @param gameDirectory server game directory containing {@code mods/} and {@code config/}
     * @param loader stable loader id written into generated selectors, for example {@code fabric} or {@code neoforge}
     * @param minecraftVersion Minecraft version written into a new workspace when no existing manifest id is present
     * @param installedModsSupplier supplier returning the loader-reported installed mods for the current server
     */
    public ManifestGenApi(
            Path gameDirectory,
            String loader,
            String minecraftVersion,
            Supplier<List<InstalledMod>> installedModsSupplier) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.loader = requireNonBlank(loader, "loader");
        this.minecraftVersion = requireNonBlank(minecraftVersion, "minecraftVersion");
        this.installedModsSupplier = Objects.requireNonNull(installedModsSupplier, "installedModsSupplier");
    }

    /**
     * Returns the server game directory used by this API instance.
     */
    public Path gameDirectory() {
        return gameDirectory;
    }

    /**
     * Returns the stable loader id used for workspace selector generation.
     */
    public String loader() {
        return loader;
    }

    /**
     * Returns the Minecraft version used for workspace generation.
     */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    /**
     * Loads the current manifest generator config from {@code config/mcu-manifest-gen.json}.
     *
     * <p>If the config file is missing, default config is created and saved.</p>
     */
    public ManifestGenConfig loadConfig() {
        return ManifestGenConfig.load(gameDirectory);
    }

    /**
     * Checks whether a player name is allowed to use manifest generator commands/API actions.
     *
     * @param username exact player name to check
     * @return {@code true} when the config allow-list contains the player
     */
    public boolean isUserAllowed(String username) {
        return loadConfig().isUserAllowed(username);
    }

    /**
     * Returns a defensive copy of the loader-reported installed mod list.
     */
    public List<InstalledMod> installedMods() {
        return List.copyOf(installedModsSupplier.get());
    }

    /**
     * Generates or updates the configured workspace file.
     *
     * <p>Progress and summary messages are collected in the returned result.
     * Use {@link #generate(String, Consumer)} when the caller wants to stream
     * messages to chat, logs, or another UI while generation is running.</p>
     *
     * @param manifestId optional manifest id override; {@code null} or blank keeps existing/default behavior
     * @return structured result for the generation run
     */
    public GenerateResult generate(String manifestId) {
        List<String> messages = new ArrayList<>();
        return generate(manifestId, messages::add);
    }

    /**
     * Generates or updates the configured workspace file and streams user-facing messages.
     *
     * @param manifestId optional manifest id override; {@code null} or blank keeps existing/default behavior
     * @param messageSink consumer receiving the same messages exposed through {@link GenerateResult#messages()}
     * @return structured result for the generation run
     */
    public GenerateResult generate(String manifestId, Consumer<String> messageSink) {
        Objects.requireNonNull(messageSink, "messageSink");

        ManifestGenConfig config = loadConfig();
        List<InstalledMod> installedMods = installedMods();
        List<String> progressMessages = new ArrayList<>();
        List<String> summaryMessages = new ArrayList<>();

        String scanningMessage = "Scanning " + installedMods.size() + " loaded mods...";
        messageSink.accept(scanningMessage);

        WorkspaceGenerator.Result workspaceResult = WorkspaceGenerator.generate(
                gameDirectory,
                installedMods,
                loader,
                minecraftVersion,
                config,
                manifestId,
                msg -> {
                    progressMessages.add(msg);
                    messageSink.accept(msg);
                });

        summaryMessages.add("Manifest ID: " + workspaceResult.manifestId());
        summaryMessages.add("Wrote " + workspaceResult.scanned() + " mods ("
                + workspaceResult.updated() + " updated, "
                + workspaceResult.added() + " added, "
                + workspaceResult.markedDelete() + " marked DELETE) to "
                + workspaceResult.outputFileName());
        summaryMessages.add("Skipped: " + workspaceResult.skippedNonModFolder()
                + " non-mod-folder, " + workspaceResult.skippedIgnored()
                + " ignored, " + workspaceResult.skippedDuplicateJar()
                + " duplicate JAR");

        for (String msg : summaryMessages) {
            messageSink.accept(msg);
        }

        return new GenerateResult(
                installedMods.size(),
                workspaceResult,
                progressMessages,
                summaryMessages);
    }

    /**
     * Adds a mod id to the ignored-mod list and saves the config if it changed.
     *
     * @param modId mod id to ignore
     * @return result describing whether the config changed and the user-facing message
     */
    public IgnoreChangeResult addIgnoredMod(String modId) {
        String normalizedModId = requireNonBlank(modId, "modId");
        ManifestGenConfig config = loadConfig();
        boolean changed = config.addIgnored(normalizedModId);
        if (changed) {
            config.save(gameDirectory);
        }
        String message = changed
                ? "Added \"" + normalizedModId + "\" to ignored mods."
                : "\"" + normalizedModId + "\" is already ignored.";
        return new IgnoreChangeResult(normalizedModId, changed, config.getIgnoredMods(), message);
    }

    /**
     * Removes a mod id from the ignored-mod list and saves the config if it changed.
     *
     * @param modId mod id to stop ignoring
     * @return result describing whether the config changed and the user-facing message
     */
    public IgnoreChangeResult removeIgnoredMod(String modId) {
        String normalizedModId = requireNonBlank(modId, "modId");
        ManifestGenConfig config = loadConfig();
        boolean changed = config.removeIgnored(normalizedModId);
        if (changed) {
            config.save(gameDirectory);
        }
        String message = changed
                ? "Removed \"" + normalizedModId + "\" from ignored mods."
                : "\"" + normalizedModId + "\" is not in ignored list.";
        return new IgnoreChangeResult(normalizedModId, changed, config.getIgnoredMods(), message);
    }

    /**
     * Lists the currently ignored mod ids.
     *
     * @return ignored mod ids and the same user-facing message used by the command
     */
    public IgnoreListResult listIgnoredMods() {
        List<String> ignored = loadConfig().getIgnoredMods();
        String message = ignored.isEmpty()
                ? "No mods are ignored."
                : "Ignored mods (" + ignored.size() + "): " + String.join(", ", ignored);
        return new IgnoreListResult(ignored, message);
    }

    /**
     * Returns mod ids suitable for {@code ignore add} completion.
     *
     * <p>The result contains loader-reported mods whose files are inside the
     * server {@code mods/} directory and are not already ignored.</p>
     */
    public List<String> suggestIgnoreAddModIds() {
        ManifestGenConfig config = loadConfig();
        Set<String> ignored = new HashSet<>(config.getIgnoredMods());
        Path realModsDir = realModsDirectoryOrNull();
        if (realModsDir == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (InstalledMod mod : installedMods()) {
            String id = mod.modId();
            if (ignored.contains(id)) {
                continue;
            }
            if (isInsideModsDirectory(mod, realModsDir)) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns mod ids suitable for {@code ignore remove} completion.
     */
    public List<String> suggestIgnoreRemoveModIds() {
        return loadConfig().getIgnoredMods();
    }

    /**
     * Resolves the real {@code mods/} directory path, or returns {@code null}
     * when it does not exist or cannot be resolved.
     */
    private Path realModsDirectoryOrNull() {
        try {
            return gameDirectory.resolve("mods").toRealPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks whether an installed mod file resolves inside the real mods directory.
     */
    private static boolean isInsideModsDirectory(InstalledMod mod, Path realModsDir) {
        try {
            Path realPath = mod.file().toAbsolutePath().normalize().toRealPath();
            return realPath.startsWith(realModsDir);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Normalizes a required string argument and rejects null or blank values.
     */
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    /**
     * Structured result of a workspace generation run.
     *
     * @param installedModCount number of loader-reported installed mods before filtering
     * @param workspaceResult low-level workspace generator result counters
     * @param progressMessages progress messages produced while scanning/generating
     * @param summaryMessages final summary messages matching the command output
     */
    public record GenerateResult(
            int installedModCount,
            WorkspaceGenerator.Result workspaceResult,
            List<String> progressMessages,
            List<String> summaryMessages) {
        public GenerateResult {
            progressMessages = List.copyOf(progressMessages);
            summaryMessages = List.copyOf(summaryMessages);
        }

        /**
         * Returns all user-facing messages in command output order.
         */
        public List<String> messages() {
            List<String> messages = new ArrayList<>();
            messages.add("Scanning " + installedModCount + " loaded mods...");
            messages.addAll(progressMessages);
            messages.addAll(summaryMessages);
            return List.copyOf(messages);
        }
    }

    /**
     * Result of adding or removing a mod id from the ignored-mod list.
     *
     * @param modId normalized mod id passed to the operation
     * @param changed whether the config file was changed and saved
     * @param ignoredMods ignored-mod list after the operation
     * @param message user-facing status message matching the command output
     */
    public record IgnoreChangeResult(
            String modId,
            boolean changed,
            List<String> ignoredMods,
            String message) {
        public IgnoreChangeResult {
            ignoredMods = List.copyOf(ignoredMods);
        }
    }

    /**
     * Result of listing ignored mods.
     *
     * @param ignoredMods current ignored-mod list
     * @param message user-facing status message matching the command output
     */
    public record IgnoreListResult(List<String> ignoredMods, String message) {
        public IgnoreListResult {
            ignoredMods = List.copyOf(ignoredMods);
        }
    }
}
