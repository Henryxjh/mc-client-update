package io.github.henryxjh.mcclientupdate.update.install;

import io.github.henryxjh.mcclientupdate.download.DownloadBatchResult;
import io.github.henryxjh.mcclientupdate.download.DownloadedArtifact;
import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.Hashes;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.update.Hashing;
import io.github.henryxjh.mcclientupdate.update.JarTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Installs downloaded artifacts into the game directory. */
public final class ArtifactInstaller {

    private static final String PENDING_SUFFIX = ".mc-client-update-pending";
    private static final String DELETE_SUFFIX = ".mc-client-update-deleted";

    private ArtifactInstaller() {
    }

    public static InstallBatchResult installBatch(
            Manifest manifest,
            ScanResult scanResult,
            DownloadBatchResult batchResult,
            Path gameDirectory) {

        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(scanResult, "scanResult");
        Objects.requireNonNull(batchResult, "batchResult");
        Objects.requireNonNull(gameDirectory, "gameDirectory");

        // Build modId -> DownloadedArtifact map
        Map<String, DownloadedArtifact> modIdToDownloaded = new HashMap<>();
        for (DownloadedArtifact da : batchResult.downloaded()) {
            for (String modId : da.modIds()) {
                modIdToDownloaded.put(modId, da);
            }
        }

        List<InstalledArtifact> installedList = new ArrayList<>();
        List<InstallFailure> failures = new ArrayList<>();

        // Separate DELETE candidates and process them first
        List<UpdateCandidate> deleteCandidates = new ArrayList<>();
        List<UpdateCandidate> otherCandidates = new ArrayList<>();
        for (UpdateCandidate c : scanResult.candidates()) {
            if (c.reason() == UpdateCandidate.Reason.DELETE) {
                deleteCandidates.add(c);
            } else {
                otherCandidates.add(c);
            }
        }

        for (int i = 0; i < deleteCandidates.size(); i++) {
            UpdateCandidate del = deleteCandidates.get(i);
            if (Thread.currentThread().isInterrupted()) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.INTERRUPTED, "Install interrupted");
                markRemainingDeletedInterrupted(failures, deleteCandidates, i + 1);
                break;
            }
            InstalledMod installedMod = del.installed().orElse(null);
            if (installedMod == null) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.IO_ERROR, "No installed mod for DELETE");
                continue;
            }
            Path originalJar = installedMod.file();
            String fileName = originalJar.getFileName().toString();
            String version = installedMod.version();

            Path backupPath = originalJar.resolveSibling(
                    "." + fileName + DELETE_SUFFIX);

            if (Files.exists(backupPath)) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                        "Backup file already exists");
                continue;
            }

            try {
                try {
                    Files.move(originalJar, backupPath,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(originalJar, backupPath);
                }
                String installedRelPath = gameDirectory.relativize(originalJar)
                        .toString().replace('\\', '/');
                String backupRelPath = gameDirectory.relativize(backupPath)
                        .toString().replace('\\', '/');
                InstalledArtifact deleteRecord = new InstalledArtifact(
                        List.of(del.modId()),
                        fileName,
                        version,
                        "delete",
                        "DELETE",
                        installedRelPath,
                        Optional.of(backupRelPath));
                installedList.add(deleteRecord);
            } catch (IOException e) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.IO_ERROR,
                        "I/O error: " + e.toString());
            }
        }

        // Group tasks by target path (ADD / REPLACE only)
        Map<Path, InstallTarget> targets = new LinkedHashMap<>();

        for (UpdateCandidate candidate : otherCandidates) {
            DownloadedArtifact downloaded = modIdToDownloaded.get(candidate.modId());
            if (downloaded == null) {
                continue; // not downloaded
            }

            Path targetPath;
            if (candidate.reason() == UpdateCandidate.Reason.HASH_MISMATCH) {
                targetPath = candidate.installed().get().file();
            } else {
                targetPath = gameDirectory.resolve("mods")
                        .resolve(candidate.selectedVariant().artifact().fileName());
            }

            targets.computeIfAbsent(targetPath, tp -> {
                InstallTarget target = new InstallTarget();
                target.targetPath = tp;
                target.downloaded = downloaded;
                target.modIds = new ArrayList<>();
                return target;
            }).modIds.add(candidate.modId());

            // Record hash mismatch or missing required flags
            InstallTarget target = targets.get(targetPath);
            if (target.artifact == null) {
                target.artifact = candidate.selectedVariant().artifact();
            }
            if (candidate.reason() == UpdateCandidate.Reason.HASH_MISMATCH) {
                target.hasHashMismatch = true;
            } else {
                target.hasMissingRequired = true;
            }
        }

        // Convert to ordered list
        List<InstallTarget> orderedTasks = new ArrayList<>(targets.values());

        for (int idx = 0; idx < orderedTasks.size(); idx++) {
            InstallTarget task = orderedTasks.get(idx);

            if (Thread.currentThread().isInterrupted()) {
                markRemainingInterrupted(failures, orderedTasks, idx);
                break;
            }

            // Conflict detection
            boolean conflict = false;
            if (task.hasMissingRequired && Files.exists(task.targetPath)) {
                // Existing file or directory -> TARGET_CONFLICT
                installFailure(
                        failures,
                        task,
                        InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                        "File already exists");
                conflict = true;
            }
            if (conflict) {
                continue;
            }

            // Determine action
            boolean replace = task.hasHashMismatch && !task.hasMissingRequired;
            boolean add = task.hasMissingRequired && !replace;

            if (!add && !replace) {
                // Unexpected state
                installFailure(failures, task, InstallFailure.InstallFailureCategory.UNKNOWN,
                        "Internal: ambiguous action");
                continue;
            }

            Path sourceFile = task.downloaded.file();
            Path targetDir = task.targetPath.getParent();
            if (targetDir == null) {
                installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                        "Unable to determine target directory");
                continue;
            }

            // Create a pending file (not ending with .jar)
            String pendingName = "." + task.targetPath.getFileName().toString() + PENDING_SUFFIX;
            Path pendingFile = targetDir.resolve(pendingName);
            boolean pendingCreated = false;

            try {
                // Ensure parent directory exists
                Files.createDirectories(targetDir);

                // Copy to pending
                Files.copy(sourceFile, pendingFile, StandardCopyOption.REPLACE_EXISTING);
                pendingCreated = true;

                // Pre-install validation
                Artifact artifact = task.artifact;
                if (artifact == null) {
                    installFailure(failures, task, InstallFailure.InstallFailureCategory.UNKNOWN,
                            "No artifact metadata available");
                    continue;
                }
                long expectedSize = artifact.size();
                Hashes manifestHashes = artifact.hashes();
                Hashing.Hashes expectedHashes = new Hashing.Hashes(
                        manifestHashes.sha256().orElse(null),
                        manifestHashes.sha512().orElse(null));

                if (!verifySizeAndHashes(pendingFile, expectedSize, expectedHashes)) {
                    installFailure(failures, task,
                            InstallFailure.InstallFailureCategory.HASH_MISMATCH,
                            "Pre-install size or hash mismatch");
                    continue;
                }

                if (replace) {
                    // Perform replace using JarTransaction.install
                    JarTransaction.Result result;
                    try {
                        result = JarTransaction.install(task.targetPath, pendingFile, expectedHashes);
                        pendingCreated = false; // taken over by transaction
                    } catch (IOException e) {
                        installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                                "Jar transaction failed");
                        continue;
                    }

                    Path installedJar = result.installedJar(); // should match targetPath
                    Path backupJar = result.backupJar();

                    String installedRel = gameDirectory.relativize(installedJar).toString()
                            .replace('\\', '/');
                    String backupRel = gameDirectory.relativize(backupJar).toString()
                            .replace('\\', '/');

                    InstalledArtifact artifactRecord = new InstalledArtifact(
                            List.copyOf(task.modIds),
                            task.downloaded.fileName(),
                            task.downloaded.version(),
                            task.downloaded.sourceType(),
                            "REPLACE",
                            installedRel,
                            Optional.of(backupRel));
                    installedList.add(artifactRecord);
                } else {
                    // ADD: move pending to final location
                    try {
                        Files.move(pendingFile, task.targetPath,
                                StandardCopyOption.ATOMIC_MOVE);
                        pendingCreated = false; // pending moved away
                    } catch (AtomicMoveNotSupportedException amne) {
                        Files.move(pendingFile, task.targetPath);
                        pendingCreated = false; // pending moved away
                    }

                    // Final file verification
                    try {
                        Hashing.Hashes finalHashes = Hashing.hashes(task.targetPath);
                        long finalSize = Files.size(task.targetPath);
                        if (!verifyHashes(finalSize, finalHashes, expectedSize, expectedHashes)) {
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.HASH_MISMATCH,
                                    "Installed file size or hash mismatch");
                            continue;
                        }
                    } catch (IOException e) {
                        installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                                "Failed to verify installed file");
                        continue;
                    }

                    String installedRel = gameDirectory.relativize(task.targetPath).toString()
                            .replace('\\', '/');
                    InstalledArtifact addRecord = new InstalledArtifact(
                            List.copyOf(task.modIds),
                            task.downloaded.fileName(),
                            task.downloaded.version(),
                            task.downloaded.sourceType(),
                            "ADD",
                            installedRel,
                            Optional.empty());
                    installedList.add(addRecord);
                }
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    installFailure(failures, task, InstallFailure.InstallFailureCategory.INTERRUPTED,
                            "Install interrupted");
                    markRemainingInterrupted(failures, orderedTasks, idx + 1);
                    break;
                }
                installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                        "Install I/O error");
            } finally {
                // Clean up pending unless successfully moved or taken over by transaction
                if (pendingCreated && pendingFile != null) {
                    try {
                        Files.deleteIfExists(pendingFile);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        return new InstallBatchResult(
                manifest.manifestId(),
                manifest.revision(),
                List.copyOf(installedList),
                List.copyOf(failures));
    }

    // ----------------------------------------------------------
    // Internal helper classes
    // ----------------------------------------------------------
    private static final class InstallTarget {
        Path targetPath;
        DownloadedArtifact downloaded;
        List<String> modIds;
        Artifact artifact;
        boolean hasHashMismatch;
        boolean hasMissingRequired;
    }

    private static void installFailure(
            List<InstallFailure> failures,
            InstallTarget target,
            InstallFailure.InstallFailureCategory category,
            String message) {
        failures.add(new InstallFailure(
                List.copyOf(target.modIds),
                target.downloaded.fileName(),
                target.downloaded.version(),
                target.downloaded.sourceType(),
                category,
                message));
    }

    private static void markRemainingInterrupted(
            List<InstallFailure> failures,
            List<InstallTarget> tasks,
            int startIdx) {
        for (int i = startIdx; i < tasks.size(); i++) {
            InstallTarget remaining = tasks.get(i);
            installFailure(failures, remaining,
                    InstallFailure.InstallFailureCategory.INTERRUPTED,
                    "Install interrupted");
        }
    }

    private static boolean verifySizeAndHashes(
            Path file,
            long expectedSize,
            Hashing.Hashes expected) throws IOException {
        long actualSize = Files.size(file);
        if (actualSize != expectedSize) {
            return false;
        }
        Hashing.Hashes actualHashes = Hashing.hashes(file);
        return verifyHashes(actualSize, actualHashes, expectedSize, expected);
    }

    private static boolean verifyHashes(
            long actualSize,
            Hashing.Hashes actualHashes,
            long expectedSize,
            Hashing.Hashes expected) {
        if (actualSize != expectedSize) {
            return false;
        }
        if (expected.hasSha256() && !actualHashes.sha256().equals(expected.sha256())) {
            return false;
        }
        if (expected.hasSha512() && !actualHashes.sha512().equals(expected.sha512())) {
            return false;
        }
        return true;
    }

    private static void installDeleteFailure(
            List<InstallFailure> failures,
            UpdateCandidate candidate,
            InstallFailure.InstallFailureCategory category,
            String message) {
        InstalledMod m = candidate.installed().orElse(null);
        if (m == null) {
            failures.add(new InstallFailure(
                    List.of(candidate.modId()),
                    "unknown",
                    "unknown",
                    "delete",
                    category,
                    message));
            return;
        }
        failures.add(new InstallFailure(
                List.of(candidate.modId()),
                m.file().getFileName().toString(),
                m.version(),
                "delete",
                category,
                message));
    }

    private static void markRemainingDeletedInterrupted(
            List<InstallFailure> failures,
            List<UpdateCandidate> deleteCandidates,
            int startIdx) {
        for (int i = startIdx; i < deleteCandidates.size(); i++) {
            installDeleteFailure(failures, deleteCandidates.get(i),
                    InstallFailure.InstallFailureCategory.INTERRUPTED,
                    "Install interrupted");
        }
    }

}
