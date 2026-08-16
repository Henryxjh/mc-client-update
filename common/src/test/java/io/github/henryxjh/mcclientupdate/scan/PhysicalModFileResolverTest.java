package io.github.henryxjh.mcclientupdate.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalModFileResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesVirtualJarToUniqueTopLevelContainer() throws IOException {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path outerJar = mods.resolve("sodium.jar");
        createJarWithEntry(outerJar, "META-INF/jarjar/sodium-mod.jar");

        Path virtualArchive = tempDir.resolve("virtual.zip");
        createJarWithEntry(virtualArchive, "marker");
        try (FileSystem zip = FileSystems.newFileSystem(virtualArchive)) {
            Path virtualPath = zip.getPath("/sodium-mod.jar");
            assertEquals(
                    outerJar.toRealPath(),
                    PhysicalModFileResolver.resolve(virtualPath, mods.toRealPath(), "sodium"));
        }
    }

    @Test
    void rejectsAmbiguousContainerForManifestManagedVirtualMod() throws IOException {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJarWithEntry(mods.resolve("sodium.jar"), "META-INF/jarjar/shared.jar");
        createJarWithEntry(mods.resolve("iris.jar"), "META-INF/jarjar/shared.jar");

        Path virtualArchive = tempDir.resolve("virtual.zip");
        createJarWithEntry(virtualArchive, "marker");
        try (FileSystem zip = FileSystems.newFileSystem(virtualArchive)) {
            ModScanException error = assertThrows(
                    ModScanException.class,
                    () -> PhysicalModFileResolver.resolve(
                            zip.getPath("/shared.jar"), mods.toRealPath(), "shared"));
            assertTrue(error.getMessage().contains("multiple top-level JARs"));
        }
    }

    private static void createJarWithEntry(Path jar, String entry) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + jar.toUri()), Map.of("create", "true"))) {
            Path path = zip.getPath("/" + entry);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, new byte[] {1});
        }
    }
}
