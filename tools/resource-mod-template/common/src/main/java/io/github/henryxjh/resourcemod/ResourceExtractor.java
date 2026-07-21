package io.github.henryxjh.resourcemod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Shared resource logic. No loader-specific dependencies.
 */
public final class ResourceExtractor {

    /** Name of the lock file inside the JAR (written by pack.py). */
    private static final String LOCK_FILE = ".resource-lock";

    /** JAR directory to extract (mirrored to game dir). */
    private static final String OVERRIDE_PREFIX = "override/";

    /** JAR directory scanned for resource packs. */
    private static final String RESOURCE_PACK_PREFIX = "resource-pack/";

    private ResourceExtractor() {}

    // ---- Override extraction ------------------------------------------

    /**
     * Extract {@code override/} to the game directory if the lock has changed.
     *
     * @param jarPath path to this mod's JAR
     * @param gameDir Minecraft game directory
     * @param modId   this mod's id (used for the marker file name)
     * @param logger  log consumer
     */
    public static void extractIfNeeded(Path jarPath, Path gameDir, String modId,
                                        Consumer<String> logger) {
        String jarLock = readJarLock(jarPath, logger);
        if (jarLock == null) return;

        Path marker = gameDir.resolve(".resource-extracted-" + modId);
        String storedLock = readMarker(marker, logger);
        if (jarLock.equals(storedLock)) return;

        logger.accept("Extracting override/ to game directory...");
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            extractOverride(jar, gameDir, logger);
        } catch (IOException e) {
            logger.accept("Extraction failed: " + e);
            return;
        }

        writeMarker(marker, jarLock, logger);
        logger.accept("Extraction complete.");
    }

    private static String readJarLock(Path jarPath, Consumer<String> logger) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(LOCK_FILE);
            if (entry == null) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes()).trim();
            }
        } catch (IOException e) {
            logger.accept("Cannot read " + LOCK_FILE + ": " + e);
            return null;
        }
    }

    private static String readMarker(Path marker, Consumer<String> logger) {
        try {
            if (Files.exists(marker)) {
                return Files.readString(marker).trim();
            }
        } catch (IOException e) {
            logger.accept("Cannot read marker file: " + e);
        }
        return null;
    }

    private static void writeMarker(Path marker, String lock, Consumer<String> logger) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, lock);
        } catch (IOException e) {
            logger.accept("Cannot write marker file: " + e);
        }
    }

    private static void extractOverride(JarFile jar, Path gameDir,
                                         Consumer<String> logger) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(OVERRIDE_PREFIX) || entry.isDirectory()) continue;
            String relative = name.substring(OVERRIDE_PREFIX.length());
            if (relative.isEmpty()) continue;
            Path target = gameDir.resolve(relative);
            Files.createDirectories(target.getParent());
            try (InputStream in = jar.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                logger.accept("  " + relative);
            }
        }
    }

    // ---- Resource pack discovery --------------------------------------

    /**
     * Discover resource pack directories inside the JAR.
     * Returns the name of each top-level subdirectory under
     * {@code resource-pack/} that contains a {@code pack.mcmeta}.
     */
    public static List<String> findResourcePacks(Path jarPath) {
        List<String> result = new ArrayList<>();
        if (jarPath == null || !Files.isRegularFile(jarPath)) return result;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(RESOURCE_PACK_PREFIX) || entry.isDirectory()) continue;
                String relative = name.substring(RESOURCE_PACK_PREFIX.length());
                int slash = relative.indexOf('/');
                if (slash < 0) continue;
                String packDir = relative.substring(0, slash);
                String fileInPack = relative.substring(slash + 1);
                if ("pack.mcmeta".equals(fileInPack) && !result.contains(packDir)) {
                    result.add(packDir);
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }
}
