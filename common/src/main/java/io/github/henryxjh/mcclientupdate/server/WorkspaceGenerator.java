package io.github.henryxjh.mcclientupdate.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.update.Hashing;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Core logic for generating a {@code manifest-workspace.json} from the
 * server's installed mod list.
 */
public final class WorkspaceGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern NEOFORGE_LICENSE_PATTERN =
            Pattern.compile("^\\s*license\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    private WorkspaceGenerator() {}

    /**
     * Result of a generate run.
     */
    public record Result(
            String manifestId,
            int scanned,
            int skippedNonModFolder,
            int skippedIgnored,
            int skippedDuplicateJar,
            int updated,
            int added,
            int markedDelete,
            String outputFileName
    ) {}

    /**
     * Generate/update the workspace file.
     *
     * @param gameDir          server root directory
     * @param installedMods    mods reported by the loader (in loader order)
     * @param loader           current loader name ("fabric" or "neoforge")
     * @param mcVersion        current Minecraft version
     * @param config           loaded configuration
     * @param manifestIdArg    user-specified manifestId, or null
     * @param log              output consumer for progress messages
     */
    public static Result generate(
            Path gameDir,
            List<InstalledMod> installedMods,
            String loader,
            String mcVersion,
            ManifestGenConfig config,
            String manifestIdArg,
            Consumer<String> log) {

        Path modsDir = gameDir.resolve("mods").toAbsolutePath().normalize();
        Path realModsDir;
        try {
            realModsDir = modsDir.toRealPath();
        } catch (IOException e) {
            throw new RuntimeException("Cannot resolve mods directory: " + modsDir, e);
        }

        // Read existing workspace
        Path outputPath = config.getOutputPath(gameDir);
        JsonObject workspace;
        if (Files.exists(outputPath)) {
            try (Reader reader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
                workspace = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                log.accept("Warning: failed to read existing workspace, starting fresh: " + e.getMessage());
                workspace = new JsonObject();
            }
        } else {
            workspace = new JsonObject();
        }

        // Determine manifestId
        String manifestId;
        if (manifestIdArg != null && !manifestIdArg.isBlank()) {
            manifestId = manifestIdArg.strip();
        } else if (workspace.has("manifestId") && !workspace.get("manifestId").getAsString().isBlank()) {
            manifestId = workspace.get("manifestId").getAsString();
        } else {
            manifestId = mcVersion + "-" + loader;
        }
        workspace.addProperty("manifestId", manifestId);

        // Ensure mods object exists
        JsonObject modsObj;
        if (workspace.has("mods") && workspace.get("mods").isJsonObject()) {
            modsObj = workspace.getAsJsonObject("mods");
        } else {
            modsObj = new JsonObject();
            workspace.add("mods", modsObj);
        }

        Set<String> ignoredSet = new HashSet<>(config.getIgnoredMods());
        Set<String> existingModIds = new HashSet<>(modsObj.keySet());

        int scanned = 0;
        int skippedNonModFolder = 0;
        int skippedIgnored = 0;
        int skippedDuplicateJar = 0;
        int updated = 0;
        int added = 0;
        int markedDelete = 0;

        Set<Path> seenJarPaths = new HashSet<>();
        List<InstalledMod> processedMods = new ArrayList<>();
        List<String> duplicateNames = new ArrayList<>();

        // Phase 1: filter installed mods
        for (InstalledMod mod : installedMods) {
            String modId = mod.modId();

            // Check mods/ folder
            Path normPath = mod.file().toAbsolutePath().normalize();
            Path realPath;
            try {
                realPath = normPath.toRealPath();
            } catch (IOException e) {
                skippedNonModFolder++;
                continue;
            }
            if (!realPath.startsWith(realModsDir)) {
                skippedNonModFolder++;
                continue;
            }

            // Check ignored
            if (ignoredSet.contains(modId)) {
                skippedIgnored++;
                continue;
            }

            // Dedup by JAR path (handles Fabric JiJ)
            if (!seenJarPaths.add(realPath)) {
                skippedDuplicateJar++;
                duplicateNames.add(modId);
                continue;
            }

            scanned++;
            processedMods.add(mod);
        }

        // Log summary of filtering
        if (skippedNonModFolder > 0) {
            log.accept("Skipped " + skippedNonModFolder + " non-mod-folder entries");
        }
        if (skippedIgnored > 0) {
            log.accept("Skipped " + skippedIgnored + " ignored: " + String.join(", ", config.getIgnoredMods()));
        }
        if (skippedDuplicateJar > 0) {
            log.accept("Skipped " + skippedDuplicateJar + " duplicate JAR entries" +
                    (duplicateNames.isEmpty() ? "" : " (" + String.join(", ", duplicateNames) + ")"));
        }

        // Phase 2: process each mod
        Set<String> processedModIds = new HashSet<>();
        List<String> licenseLines = new ArrayList<>();

        for (InstalledMod mod : processedMods) {
            String modId = mod.modId();
            processedModIds.add(modId);

            JsonObject modEntry;
            boolean isNew = false;
            if (modsObj.has(modId) && modsObj.get(modId).isJsonObject()) {
                modEntry = modsObj.getAsJsonObject(modId);
            } else {
                modEntry = new JsonObject();
                isNew = true;
            }

            // Set/update name
            modEntry.addProperty("name", modId); // fallback, may be overridden

            // Compute hashes and size
            Hashing.Hashes hashes;
            long size;
            try {
                hashes = Hashing.hashes(mod.file());
                size = Files.size(mod.file());
            } catch (IOException e) {
                log.accept("  FAIL " + modId + ": cannot read file - " + e.getMessage());
                continue;
            }

            // Extract license from JAR metadata
            String license = extractLicense(mod.file(), loader);
            if (license != null && !license.isBlank()) {
                modEntry.addProperty("license", license);
                licenseLines.add(modId + "=" + license);
            }

            // Build variants array
            JsonArray variants;
            if (modEntry.has("variants") && modEntry.get("variants").isJsonArray()) {
                variants = modEntry.getAsJsonArray("variants");
            } else {
                variants = new JsonArray();
                modEntry.add("variants", variants);
            }

            // Find matching variant
            boolean matched = false;
            for (int i = 0; i < variants.size(); i++) {
                JsonObject variant = variants.get(i).getAsJsonObject();
                if (variantMatches(variant, loader)) {
                    // Update this variant
                    variant.addProperty("version", mod.version());
                    variant.addProperty("fileName", mod.file().getFileName().toString());
                    variant.addProperty("size", size);
                    JsonObject hashesObj = new JsonObject();
                    hashesObj.addProperty("sha256", hashes.sha256());
                    hashesObj.addProperty("sha512", hashes.sha512());
                    variant.add("hashes", hashesObj);
                    // Ensure download section exists
                    if (!variant.has("download")) {
                        JsonObject dl = new JsonObject();
                        dl.addProperty("type", "hosted");
                        dl.addProperty("url", "TODO");
                        variant.add("download", dl);
                    }
                    matched = true;
                    updated++;
                    break;
                }
            }

            if (!matched) {
                // Add new variant
                JsonObject newVariant = new JsonObject();
                JsonObject selector = new JsonObject();
                JsonArray loaders = new JsonArray();
                loaders.add(loader);
                selector.add("loaders", loaders);
                newVariant.add("selector", selector);
                newVariant.addProperty("version", mod.version());
                newVariant.addProperty("fileName", mod.file().getFileName().toString());
                newVariant.addProperty("size", size);
                JsonObject hashesObj = new JsonObject();
                hashesObj.addProperty("sha256", hashes.sha256());
                hashesObj.addProperty("sha512", hashes.sha512());
                newVariant.add("hashes", hashesObj);
                JsonObject dl = new JsonObject();
                dl.addProperty("type", "hosted");
                dl.addProperty("url", "TODO");
                newVariant.add("download", dl);
                variants.add(newVariant);
                added++;
            }

            // Ensure required is set
            if (!modEntry.has("required")) {
                modEntry.addProperty("required", true);
            }

            modsObj.add(modId, modEntry);
        }

        // Phase 3: handle removed mods (in workspace but not installed)
        for (String existingId : existingModIds) {
            if (processedModIds.contains(existingId)) {
                continue;
            }

            JsonObject modEntry = modsObj.getAsJsonObject(existingId);
            boolean required = modEntry.has("required") && modEntry.get("required").getAsBoolean();

            JsonArray variants = null;
            if (modEntry.has("variants") && modEntry.get("variants").isJsonArray()) {
                variants = modEntry.getAsJsonArray("variants");
            }

            if (variants == null || variants.size() == 0) {
                if (!required) {
                    modsObj.remove(existingId);
                }
                continue;
            }

            // Find matching variant
            JsonArray remainingVariants = new JsonArray();
            boolean hasMatching = false;
            for (int i = 0; i < variants.size(); i++) {
                JsonObject variant = variants.get(i).getAsJsonObject();
                if (variantMatches(variant, loader)) {
                    hasMatching = true;
                    if (required) {
                        // Replace with DELETE action
                        JsonObject deleteVariant = new JsonObject();
                        if (variant.has("selector")) {
                            deleteVariant.add("selector", variant.get("selector"));
                        }
                        deleteVariant.addProperty("action", "delete");
                        remainingVariants.add(deleteVariant);
                        markedDelete++;
                    }
                    // else: required=false → omit this variant
                } else {
                    // Non-matching → preserve
                    remainingVariants.add(variant);
                }
            }

            if (remainingVariants.size() == 0) {
                modsObj.remove(existingId);
            } else if (hasMatching) {
                modEntry.add("variants", remainingVariants);
            }
        }

        // Log license info
        if (!licenseLines.isEmpty()) {
            log.accept("License from JAR metadata: " + String.join(", ", licenseLines));
        }

        // Write output
        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, GSON.toJson(workspace) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write workspace to " + outputPath, e);
        }

        return new Result(manifestId, scanned, skippedNonModFolder, skippedIgnored,
                skippedDuplicateJar, updated, added, markedDelete,
                outputPath.getFileName().toString());
    }

    // ---- Selector matching --------------------------------------------

    private static boolean variantMatches(JsonObject variant, String currentLoader) {
        if (!variant.has("selector")) {
            return false; // defensive
        }
        JsonObject selector = variant.getAsJsonObject("selector");

        // Loader check
        if (selector.has("loaders") && selector.get("loaders").isJsonArray()) {
            boolean loaderMatch = false;
            for (JsonElement el : selector.getAsJsonArray("loaders")) {
                if (el.getAsString().equals(currentLoader)) {
                    loaderMatch = true;
                    break;
                }
            }
            if (!loaderMatch) {
                return false;
            }
        }

        // Skip OS/arch checks for server-side generation
        // (the server only generates for itself; OS/arch-specific variants
        //  are manually added by the pack author)

        return true;
    }

    // ---- License extraction -------------------------------------------

    private static String extractLicense(Path jarPath, String loader) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if ("fabric".equals(loader)) {
                return extractFabricLicense(jar);
            } else if ("neoforge".equals(loader)) {
                return extractNeoForgeLicense(jar);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String extractFabricLicense(JarFile jar) throws IOException {
        ZipEntry entry = jar.getEntry("fabric.mod.json");
        if (entry == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("license") && !json.get("license").isJsonNull()) {
                String license = json.get("license").getAsString();
                if (!license.isBlank()) {
                    return license;
                }
            }
        }
        return null;
    }

    private static String extractNeoForgeLicense(JarFile jar) throws IOException {
        ZipEntry entry = jar.getEntry("META-INF/neoforge.mods.toml");
        if (entry == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            Matcher m = NEOFORGE_LICENSE_PATTERN.matcher(sb.toString());
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
