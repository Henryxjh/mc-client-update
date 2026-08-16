package io.github.henryxjh.mcclientupdate.scan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves a manifest-managed mod path to its physical top-level JAR. */
final class PhysicalModFileResolver {

    private PhysicalModFileResolver() {
    }

    static Path resolve(Path reportedPath, Path realModsDirectory, String modId) {
        Path normalizedPath = reportedPath.toAbsolutePath().normalize();
        Path realPath = tryRealPath(normalizedPath, modId);
        if (realPath != null) {
            requireInsideModsDirectory(realPath, realModsDirectory, modId);
            return realPath;
        }

        Path reportedFileNamePath = reportedPath.getFileName();
        String reportedFileName = reportedFileNamePath == null ? "" : reportedFileNamePath.toString();
        if (reportedFileName.isBlank()) {
            throw unresolvedVirtualPath(reportedPath, modId, "the loader reported no file name");
        }

        List<Path> containers = findContainingJars(realModsDirectory, reportedFileName, modId);
        if (containers.size() == 1) {
            return containers.get(0);
        }
        if (containers.isEmpty()) {
            throw unresolvedVirtualPath(
                    reportedPath,
                    modId,
                    "no top-level JAR contains " + reportedFileName);
        }
        throw unresolvedVirtualPath(
                reportedPath,
                modId,
                "multiple top-level JARs contain " + reportedFileName + ": "
                        + containers.stream().map(path -> path.getFileName().toString()).toList());
    }

    private static Path tryRealPath(Path path, String modId) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new ModScanException(
                    "Cannot resolve real path for installed file of mod " + modId
                            + " (" + path.getFileName() + ")",
                    e);
        }
    }

    private static List<Path> findContainingJars(Path modsDirectory, String embeddedFileName, String modId) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(modsDirectory)) {
            for (Path candidate : files) {
                if (!Files.isRegularFile(candidate)
                        || !candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                if (containsEntry(candidate, embeddedFileName)) {
                    Path realCandidate = candidate.toRealPath();
                    requireInsideModsDirectory(realCandidate, modsDirectory, modId);
                    result.add(realCandidate);
                }
            }
        } catch (IOException e) {
            throw new ModScanException("Cannot scan mods directory for physical file of mod " + modId, e);
        }
        return result;
    }

    private static boolean containsEntry(Path jar, String embeddedFileName) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                    .map(ZipEntry::getName)
                    .anyMatch(name -> name.equals(embeddedFileName)
                            || name.endsWith("/" + embeddedFileName));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void requireInsideModsDirectory(Path file, Path modsDirectory, String modId) {
        if (!file.startsWith(modsDirectory)) {
            throw new ModScanException("Installed mod " + modId + " is outside the mods directory");
        }
    }

    private static ModScanException unresolvedVirtualPath(Path path, String modId, String detail) {
        return new ModScanException(
                "Cannot resolve virtual loader path for installed mod " + modId
                        + " (path=" + path + ", provider="
                        + path.getFileSystem().provider().getClass().getName() + "): " + detail);
    }
}
