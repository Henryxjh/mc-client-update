package io.github.henryxjh.mcclientupdate.scan;

import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.Selector;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import io.github.henryxjh.mcclientupdate.platform.UpdateTarget;
import io.github.henryxjh.mcclientupdate.update.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Determines which manifest‑declared mods need to be downloaded/replaced.
 */
public final class ModUpdateScanner {

    private ModUpdateScanner() {
    }

    /**
     * Scans installed mods against the given manifest and platform target.
     *
     * @param manifest      the parsed manifest
     * @param target        the local loader/OS/CPU triplet
     * @param modsDirectory the (normalised) mods directory
     * @param installedMods the list of currently installed mods (must not contain duplicate mod IDs)
     * @return a description of which mods need attention
     * @throws ModScanException if the installed set is inconsistent,
     *                          a required mod cannot be satisfied,
     *                          variant matching is ambiguous, or a safe JAR cannot be read
     */
    public static ScanResult scan(Manifest manifest,
                                  UpdateTarget target,
                                  Path modsDirectory,
                                  List<InstalledMod> installedMods) {
        Map<String, InstalledMod> installedByModId = indexInstalled(installedMods);

        Path normalizedModsDir = modsDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedModsDir)) {
            throw new ModScanException("Mods directory does not exist: " + normalizedModsDir.getFileName());
        }

        Path realModsDir;
        try {
            realModsDir = normalizedModsDir.toRealPath();
        } catch (IOException e) {
            throw new ModScanException("Unable to resolve real path of mods directory", e);
        }

        // Process manifest mods in stable order
        List<String> orderedModIds = new ArrayList<>(manifest.mods().keySet());
        orderedModIds.sort(Comparator.naturalOrder());

        List<UpdateCandidate> candidates = new ArrayList<>();
        Map<Path, Artifact> fileArtifactMap = new HashMap<>();

        for (String modId : orderedModIds) {
            Mod mod = manifest.mods().get(modId);
            Variant selected = selectVariant(mod, target);

            // No matching variant
            if (selected == null) {
                if (mod.required()) {
                    throw new ModScanException(
                            "No matching variant for required mod " + modId
                                    + " (loader=" + target.loader()
                                    + ", os=" + target.platform().operatingSystem().id()
                                    + ", arch=" + target.platform().architecture().id() + ")");
                }
                // optional & no variant => simply skip
                continue;
            }

            InstalledMod installed = installedByModId.get(modId);

            if (installed != null) {
                // verify safety constraints
                Path normFile = installed.file().toAbsolutePath().normalize();
                if (!Files.isRegularFile(normFile)) {
                    throw new ModScanException("Installed mod " + modId + " is not a regular file");
                }
                Path realFile;
                try {
                    realFile = normFile.toRealPath();
                } catch (IOException e) {
                    throw new ModScanException(
                            "Cannot resolve real path for installed file of mod " + modId
                                    + " (" + normFile.getFileName() + ")", e);
                }
                if (!realFile.startsWith(realModsDir)) {
                    throw new ModScanException(
                            "Installed mod " + modId + " is outside the mods directory");
                }

                // check hashes
                Hashing.Hashes fileHashes;
                try {
                    fileHashes = Hashing.hashes(realFile);
                } catch (IOException e) {
                    throw new ModScanException(
                            "Cannot read installed mod " + modId + " (" + normFile.getFileName() + ")", e);
                }

                if (!hashMatches(selected.artifact(), fileHashes)) {
                    UpdateCandidate candidate = new UpdateCandidate(
                            modId, mod, selected, Optional.of(installed),
                            UpdateCandidate.Reason.HASH_MISMATCH);
                    validateFileArtifactConsistency(fileArtifactMap, realFile, selected.artifact(), modId);
                    candidates.add(candidate);
                    fileArtifactMap.put(realFile, selected.artifact());
                    // continue (candidate added)
                }
                // hash matches === already up-to-date, no candidate
            } else {
                // not installed
                if (mod.required()) {
                    candidates.add(new UpdateCandidate(
                            modId, mod, selected, Optional.empty(),
                            UpdateCandidate.Reason.MISSING_REQUIRED));
                }
                // optional and not installed -> skip silently
            }
        }

        int installedManagedCount = 0;
        for (String id : installedByModId.keySet()) {
            if (manifest.mods().containsKey(id)) {
                installedManagedCount++;
            }
        }

        return new ScanResult(candidates, manifest.mods().size(), installedManagedCount);
    }

    private static Map<String, InstalledMod> indexInstalled(List<InstalledMod> mods) {
        Map<String, InstalledMod> map = new HashMap<>();
        for (InstalledMod m : mods) {
            InstalledMod previous = map.putIfAbsent(m.modId(), m);
            if (previous != null) {
                throw new ModScanException("Duplicate mod ID in installed list: " + m.modId());
            }
        }
        return map;
    }

    /**
     * Returns {@code true} when the declared hashes in {@code artifact} all match the actual
     * hashes computed for the installed jar file.
     */
    private static boolean hashMatches(Artifact artifact, Hashing.Hashes actual) {
        Optional<String> manifestSha256 = artifact.hashes().sha256();
        Optional<String> manifestSha512 = artifact.hashes().sha512();

        if (manifestSha256.isPresent()) {
            if (!manifestSha256.get().equals(actual.sha256())) {
                return false;
            }
        }
        if (manifestSha512.isPresent()) {
            if (!manifestSha512.get().equals(actual.sha512())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Throws {@link ModScanException} if two different {@link Artifact}s are assigned to the same
     * physical jar file.
     */
    private static void validateFileArtifactConsistency(
            Map<Path, Artifact> seen,
            Path file,
            Artifact candidateArtifact,
            String currentModId) {
        Artifact existing = seen.get(file);
        if (existing != null && !existing.equals(candidateArtifact)) {
            throw new ModScanException(
                    "Conflict: jar " + file.getFileName()
                            + " used by multiple mods (including " + currentModId
                            + ") but selected different artifacts");
        }
    }

    /**
     * Picks the most specific matching variant, or returns {@code null} if none exists.
     *
     * <p>Ambiguity (multiple variants with the same maximal priority and specificity) causes a
     * {@link ModScanException}.
     */
    private static Variant selectVariant(Mod mod, UpdateTarget target) {
        List<Variant> matches = new ArrayList<>();
        for (Variant v : mod.variants()) {
            if (matchesSelector(v.selector(), target)) {
                matches.add(v);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }

        int maxPriority = matches.stream()
                .mapToInt(Variant::priority)
                .max()
                .orElse(0);
        List<Variant> topPriority = matches.stream()
                .filter(v -> v.priority() == maxPriority)
                .toList();
        if (topPriority.size() == 1) {
            return topPriority.get(0);
        }

        int maxSpecificity = topPriority.stream()
                .mapToInt(ModUpdateScanner::specificity)
                .max()
                .orElse(0);
        List<Variant> best = topPriority.stream()
                .filter(v -> specificity(v) == maxSpecificity)
                .toList();
        if (best.size() == 1) {
            return best.get(0);
        }

        throw new ModScanException(
                "Ambiguous variant selection for mod " + mod.name()
                        + ": multiple variants with priority=" + maxPriority
                        + " and specificity=" + maxSpecificity);
    }

    private static boolean matchesSelector(Selector selector, UpdateTarget target) {
        if (selector.loaders().isPresent()) {
            if (!selector.loaders().get().contains(target.loader())) {
                return false;
            }
        }
        if (selector.operatingSystems().isPresent()) {
            if (!selector.operatingSystems().get()
                    .contains(target.platform().operatingSystem().id())) {
                return false;
            }
        }
        if (selector.architectures().isPresent()) {
            if (!selector.architectures().get()
                    .contains(target.platform().architecture().id())) {
                return false;
            }
        }
        return true;
    }

    private static int specificity(Variant variant) {
        Selector selector = variant.selector();
        return (selector.loaders().isPresent() ? 1 : 0)
                + (selector.operatingSystems().isPresent() ? 1 : 0)
                + (selector.architectures().isPresent() ? 1 : 0);
    }

}
