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
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;

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
import java.util.Collections;
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

        // Build canonical tasks early to count total items for progress display
        Map<String, InstallTarget> canonicalMap = new LinkedHashMap<>();
        for (UpdateCandidate candidate : otherCandidates) {
            DownloadedArtifact downloaded = modIdToDownloaded.get(candidate.modId());
            if (downloaded == null) {
                continue;
            }
            Artifact art = candidate.selectedVariant().artifact();
            String canonical = canonicalName(downloaded, art);
            InstallTarget target = canonicalMap.computeIfAbsent(canonical, c -> {
                InstallTarget t = new InstallTarget();
                t.modIds = new ArrayList<>();
                t.downloaded = downloaded;
                t.artifact = art;
                t.canonicalFileName = c;
                t.targetPath = gameDirectory.resolve("mods").resolve(c);
                return t;
            });
            target.modIds.add(candidate.modId());
            if (candidate.reason() == UpdateCandidate.Reason.HASH_MISMATCH) {
                target.hasHashMismatch = true;
                target.originalInstalledJar = candidate.installed().get().file();
            } else {
                target.hasMissingRequired = true;
            }
        }

        int totalInstallItems = deleteCandidates.size() + canonicalMap.size();
        UpdateProgressDisplay.installPhaseStart(totalInstallItems);

        for (int i = 0; i < deleteCandidates.size(); i++) {
            UpdateCandidate del = deleteCandidates.get(i);
            if (Thread.currentThread().isInterrupted()) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.INTERRUPTED, "Install interrupted");
                UpdateProgressDisplay.itemFail("Interrupted");
                markRemainingDeletedInterrupted(failures, deleteCandidates, i + 1);
                break;
            }
            InstalledMod installedMod = del.installed().orElse(null);
            if (installedMod == null) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.IO_ERROR, "No installed mod for DELETE");
                UpdateProgressDisplay.itemFail("No installed mod");
                continue;
            }
            Path originalJar = installedMod.file();
            String fileName = originalJar.getFileName().toString();
            String version = installedMod.version();

            UpdateProgressDisplay.itemStart(del.modId(), 0, "DELETE");
            UpdateProgressDisplay.reportModIds(List.of(del.modId()));

            Path backupPath = originalJar.resolveSibling(
                    "." + fileName + DELETE_SUFFIX);

            if (Files.exists(backupPath)) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                        "Backup file already exists");
                UpdateProgressDisplay.itemFail("Backup file already exists");
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
                UpdateProgressDisplay.itemOk("deleted (backup kept)");
            } catch (IOException e) {
                installDeleteFailure(failures, del,
                        InstallFailure.InstallFailureCategory.IO_ERROR,
                        installFailureMessage("I/O error while deleting mod", e));
                UpdateProgressDisplay.itemFail("I/O error");
            }
        }

        // Convert to ordered list (canonicalMap already built above)
        List<InstallTarget> orderedTasks = new ArrayList<>(canonicalMap.values());

        for (int idx = 0; idx < orderedTasks.size(); idx++) {
            InstallTarget task = orderedTasks.get(idx);

            if (Thread.currentThread().isInterrupted()) {
                markRemainingInterrupted(failures, orderedTasks, idx);
                break;
            }

            // Determine action for progress display
            boolean replace = task.hasHashMismatch && !task.hasMissingRequired;
            boolean add = task.hasMissingRequired && !replace;
            String actionLabel;
            if (replace) {
                actionLabel = "REPLACE, hash verified";
            } else if (add) {
                actionLabel = "ADD";
            } else {
                actionLabel = "UNKNOWN";
            }
            UpdateProgressDisplay.itemStart(task.canonicalFileName, 0, actionLabel);
            UpdateProgressDisplay.reportModIds(task.modIds);

            // Conflict detection
            boolean conflict = false;
            if (task.hasMissingRequired && Files.exists(task.targetPath)) {
                // Existing file or directory -> TARGET_CONFLICT
                installFailure(
                        failures,
                        task,
                        InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                        "File already exists");
                UpdateProgressDisplay.itemFail("File already exists");
                conflict = true;
            }
            if (conflict) {
                continue;
            }

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
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                            installFailureMessage("I/O error while creating target directory", e));
                    continue;
                }

                // Copy to pending
                try {
                    Files.copy(sourceFile, pendingFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                            installFailureMessage("I/O error while copying to pending file", e));
                    continue;
                }
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
                    boolean samePath = task.targetPath.equals(task.originalInstalledJar);
                    if (samePath) {
                        // Use JarTransaction when target is the current jar
                        JarTransaction.Result result;
                        try {
                            result = JarTransaction.install(task.targetPath, pendingFile, expectedHashes);
                            pendingCreated = false; // taken over by transaction
                        } catch (IOException e) {
                            installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                                    installFailureMessage("Error while performing jar transaction", e));
                            continue;
                        }

                        Path installedJar = result.installedJar();
                        Path backupJar = result.backupJar();

                        String installedRel = gameDirectory.relativize(installedJar).toString()
                                .replace('\\', '/');
                        String backupRel = gameDirectory.relativize(backupJar).toString()
                                .replace('\\', '/');

                        InstalledArtifact artifactRecord = new InstalledArtifact(
                                List.copyOf(task.modIds),
                                task.canonicalFileName,
                                task.downloaded.version(),
                                task.downloaded.sourceType(),
                                "REPLACE",
                                installedRel,
                                Optional.of(backupRel));
                        installedList.add(artifactRecord);
                        UpdateProgressDisplay.itemOk("installed, old version backed up");
                    } else {
                        // Rename replacement: target path differs from current jar
                        Path oldJar = task.originalInstalledJar;

                        // conflict if target already exists (and it's not the old jar)
                        if (Files.exists(task.targetPath)) {
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                                    "Target already exists");
                            continue;
                        }

                        Path backupPath = oldJar.resolveSibling(
                                "." + oldJar.getFileName().toString() + ".mc-client-update-old");
                        if (Files.exists(backupPath)) {
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.TARGET_CONFLICT,
                                    "Backup file already exists");
                            continue;
                        }

                        // backup old jar
                        try {
                            atomicMove(oldJar, backupPath);
                        } catch (IOException e) {
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.IO_ERROR,
                                    installFailureMessage("I/O error while backing up old file", e));
                            continue;
                        }

                        // install pending to target
                        try {
                            atomicMove(pendingFile, task.targetPath);
                            pendingCreated = false; // pending moved away
                        } catch (IOException e) {
                            // rollback: restore old jar from backup
                            boolean rollbackOk = rollbackDifferentTargetInstall(
                                    task.targetPath, backupPath, oldJar);
                            String msg = installFailureMessage("I/O error while moving installed file", e);
                            msg += rollbackOk ? " (rolled back)" : " (rollback failed)";
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.IO_ERROR,
                                    msg);
                            continue;
                        }

                        // verify final file
                        try {
                            Hashing.Hashes finalHashes = Hashing.hashes(task.targetPath);
                            long finalSize = Files.size(task.targetPath);
                            if (!verifyHashes(finalSize, finalHashes, expectedSize, expectedHashes)) {
                                boolean rollbackOk = rollbackDifferentTargetInstall(
                                        task.targetPath, backupPath, oldJar);
                                String msg = "Installed file size or hash mismatch; ";
                                msg += rollbackOk ? "rolled back" : "rollback failed";
                                installFailure(failures, task,
                                        InstallFailure.InstallFailureCategory.HASH_MISMATCH,
                                        msg);
                                continue;
                            }
                        } catch (IOException e) {
                            boolean rollbackOk = rollbackDifferentTargetInstall(
                                    task.targetPath, backupPath, oldJar);
                            String msg = installFailureMessage("Failed to verify installed file", e);
                            msg += rollbackOk ? " (rolled back)" : " (rollback failed)";
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.IO_ERROR,
                                    msg);
                            continue;
                        }

                        String installedRel = gameDirectory.relativize(task.targetPath).toString()
                                .replace('\\', '/');
                        String backupRel = gameDirectory.relativize(backupPath).toString()
                                .replace('\\', '/');
                        InstalledArtifact artifactRecord = new InstalledArtifact(
                                List.copyOf(task.modIds),
                                task.canonicalFileName,
                                task.downloaded.version(),
                                task.downloaded.sourceType(),
                                "REPLACE",
                                installedRel,
                                Optional.of(backupRel));
                        installedList.add(artifactRecord);
                        UpdateProgressDisplay.itemOk("installed, old version backed up");
                    }
                } else {
                    // ADD: move pending to final location
                    try {
                        Files.move(pendingFile, task.targetPath,
                                StandardCopyOption.ATOMIC_MOVE);
                        pendingCreated = false; // pending moved away
                    } catch (AtomicMoveNotSupportedException amne) {
                        try {
                            Files.move(pendingFile, task.targetPath);
                            pendingCreated = false; // pending moved away
                        } catch (IOException fallbackMoveError) {
                            installFailure(failures, task,
                                    InstallFailure.InstallFailureCategory.IO_ERROR,
                                    installFailureMessage("I/O error while moving installed file", fallbackMoveError));
                            continue;
                        }
                    } catch (IOException moveError) {
                        installFailure(failures, task,
                                InstallFailure.InstallFailureCategory.IO_ERROR,
                                installFailureMessage("I/O error while moving installed file", moveError));
                        continue;
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
                        installFailure(failures, task,
                                InstallFailure.InstallFailureCategory.IO_ERROR,
                                installFailureMessage("Failed to verify installed file", e));
                        continue;
                    }

                    String installedRel = gameDirectory.relativize(task.targetPath).toString()
                            .replace('\\', '/');
                    InstalledArtifact addRecord = new InstalledArtifact(
                            List.copyOf(task.modIds),
                            task.canonicalFileName,
                            task.downloaded.version(),
                            task.downloaded.sourceType(),
                            "ADD",
                            installedRel,
                            Optional.empty());
                    installedList.add(addRecord);
                    UpdateProgressDisplay.itemOk("installed");
                }
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    installFailure(failures, task, InstallFailure.InstallFailureCategory.INTERRUPTED,
                            "Install interrupted");
                    markRemainingInterrupted(failures, orderedTasks, idx + 1);
                    break;
                }
                installFailure(failures, task, InstallFailure.InstallFailureCategory.IO_ERROR,
                        installFailureMessage("Unexpected I/O error during install", e));
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

        UpdateProgressDisplay.installPhaseDone(installedList.size(), failures.size());

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
        Path originalInstalledJar;
        String canonicalFileName;
    }

    private static void installFailure(
            List<InstallFailure> failures,
            InstallTarget target,
            InstallFailure.InstallFailureCategory category,
            String message) {
        String fileName;
        if (target.canonicalFileName != null) {
            fileName = target.canonicalFileName;
        } else {
            fileName = target.downloaded.fileName();
        }
        failures.add(new InstallFailure(
                List.copyOf(target.modIds),
                fileName,
                target.downloaded.version(),
                target.downloaded.sourceType(),
                category,
                message));
        UpdateProgressDisplay.itemFail(message);
    }

    private static void markRemainingInterrupted(
            List<InstallFailure> failures,
            List<InstallTarget> tasks,
            int startIdx) {
        for (int i = startIdx; i < tasks.size(); i++) {
            InstallTarget remaining = tasks.get(i);
            UpdateProgressDisplay.itemStart(remaining.canonicalFileName, 0, "");
            UpdateProgressDisplay.reportModIds(remaining.modIds);
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

    private static String canonicalName(DownloadedArtifact downloaded, Artifact artifact) {
        List<String> modIds = downloaded.modIds();
        List<String> sortedIds = new ArrayList<>(modIds);
        Collections.sort(sortedIds);
        String modid = sortedIds.isEmpty() ? "unknown" : sortedIds.get(0);
        String version = safeVersion(artifact.version());
        String hash = shortHash(getInstallHash(artifact));
        return modid + "-" + version + "-" + hash + ".jar";
    }

    private static String safeVersion(String version) {
        if (version == null || version.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (isSafeChar(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            return "unknown";
        }
        return result;
    }

    private static boolean isSafeChar(char c) {
        // keep ASCII letters, digits, '.', '_', '+', '-'
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') ||
               c == '.' || c == '_' || c == '+' || c == '-';
    }

    private static String getInstallHash(Artifact artifact) {
        Optional<String> sha512 = artifact.hashes().sha512();
        if (sha512.isPresent()) {
            return sha512.get();
        }
        return artifact.hashes().sha256().orElseThrow(() ->
                new IllegalArgumentException("artifact has no sha256 or sha512 hash"));
    }

    private static String shortHash(String fullHash) {
        if (fullHash == null || fullHash.length() < 16) {
            throw new IllegalArgumentException("hash too short for canonical name: " + fullHash);
        }
        return fullHash.substring(0, 16);
    }

    private static void markRemainingDeletedInterrupted(
            List<InstallFailure> failures,
            List<UpdateCandidate> deleteCandidates,
            int startIdx) {
        for (int i = startIdx; i < deleteCandidates.size(); i++) {
            UpdateCandidate c = deleteCandidates.get(i);
            UpdateProgressDisplay.itemStart(c.modId(), 0, "DELETE");
            UpdateProgressDisplay.reportModIds(List.of(c.modId()));
            UpdateProgressDisplay.itemFail("Interrupted");
            installDeleteFailure(failures, c,
                    InstallFailure.InstallFailureCategory.INTERRUPTED,
                    "Install interrupted");
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static boolean rollbackDifferentTargetInstall(Path targetPath, Path backupPath, Path oldJar) {
        try {
            Files.deleteIfExists(targetPath);
            atomicMove(backupPath, oldJar);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String installFailureMessage(String phase, Throwable t) {
        String clazz = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.toString();
        }
        return phase + ": " + clazz + ": " + msg;
    }

}
