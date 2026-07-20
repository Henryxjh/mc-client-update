package io.github.henryxjh.mcclientupdate.download;

import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.DirectDownload;
import io.github.henryxjh.mcclientupdate.manifest.Download;
import io.github.henryxjh.mcclientupdate.manifest.HostedDownload;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.ManualDownload;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;
import io.github.henryxjh.mcclientupdate.update.Hashing;
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArtifactDownloader {

    public enum FailureCategory {
        INVALID_FILE_NAME,
        HTTP_STATUS,
        SIZE_MISMATCH,
        HASH_MISMATCH,
        IO_ERROR,
        INTERRUPTED
    }

    private static final String USER_AGENT = "mc-client-update/0.2.0";
    private static final String STAGING_DIR = ".mc-client-update/downloads";
    private static final int BUFFER_SIZE = 64 * 1024;

    private ArtifactDownloader() {
    }

    public static DownloadBatchResult downloadBatch(
            Manifest manifest,
            URI manifestUri,
            ScanResult scanResult,
            Path gameDirectory,
            Duration connectTimeout,
            Duration readTimeout) {

        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(manifestUri, "manifestUri");
        Objects.requireNonNull(scanResult, "scanResult");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");

        Path stagingBase = gameDirectory.resolve(STAGING_DIR);
        try {
            Files.createDirectories(stagingBase);
        } catch (IOException e) {
            throw new DownloadException("Cannot create staging directory", e);
        }

        List<UpdateCandidate> sortedCandidates = new ArrayList<>(scanResult.candidates());
        sortedCandidates.sort(Comparator.comparing(UpdateCandidate::modId));

        LinkedHashMap<Artifact, List<String>> artifactToModIds = new LinkedHashMap<>();

        for (UpdateCandidate candidate : sortedCandidates) {
            if (candidate.reason() == UpdateCandidate.Reason.DELETE) {
                continue;
            }
            String modId = candidate.modId();
            Variant variant = candidate.selectedVariant();
            Artifact artifact = variant.artifact();
            artifactToModIds.computeIfAbsent(artifact, k -> new ArrayList<>()).add(modId);
        }

        for (List<String> modIds : artifactToModIds.values()) {
            Collections.sort(modIds);
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<DownloadedArtifact> downloaded = new ArrayList<>();
        List<DownloadFailure> failures = new ArrayList<>();
        List<ManualUpdate> manualUpdates = new ArrayList<>();

        // Collect manual updates upfront and exclude them from automatic download.
        List<Artifact> allArtifacts = new ArrayList<>(artifactToModIds.keySet());
        List<Artifact> orderedArtifacts = new ArrayList<>();
        for (Artifact artifact : allArtifacts) {
            if (artifact.download() instanceof ManualDownload manualDownload) {
                List<String> modIds = List.copyOf(artifactToModIds.get(artifact));
                manualUpdates.add(new ManualUpdate(
                        modIds,
                        artifact.fileName(),
                        artifact.version(),
                        manualDownload.pageUrl(),
                        manualDownload.message().orElse("")));
            } else {
                orderedArtifacts.add(artifact);
            }
        }

        // Progress: start download phase
        long phaseTotalBytes = 0;
        for (Artifact a : orderedArtifacts) {
            phaseTotalBytes += a.size();
        }
        UpdateProgressDisplay.downloadPhaseStart(orderedArtifacts.size(), phaseTotalBytes);

        for (int idx = 0; idx < orderedArtifacts.size(); idx++) {
            Artifact artifact = orderedArtifacts.get(idx);
            List<String> modIds = List.copyOf(artifactToModIds.get(artifact));

            String sourceType = resolveSourceType(artifact.download());
            String fileName = artifact.fileName();
            String version = artifact.version();
            long expectedSize = artifact.size();
            Optional<String> expectedSha256 = artifact.hashes().sha256();
            Optional<String> expectedSha512 = artifact.hashes().sha512();

            if (Thread.currentThread().isInterrupted()) {
                failures.add(new DownloadFailure(
                        modIds, fileName, version, sourceType,
                        FailureCategory.INTERRUPTED.name(),
                        "Download interrupted"));
                markRemainingInterrupted(failures, orderedArtifacts, artifactToModIds, idx + 1);
                break;
            }

            if (!isSimpleJarName(fileName)) {
                failures.add(new DownloadFailure(
                        modIds, fileName, version, sourceType,
                        FailureCategory.INVALID_FILE_NAME.name(),
                        "Invalid file name"));
                continue;
            }

            String hexPrefix;
            String hex;
            if (expectedSha512.isPresent()) {
                hex = expectedSha512.get();
                hexPrefix = "sha512-";
            } else {
                hex = expectedSha256.orElseThrow();
                hexPrefix = "sha256-";
            }
            Path destDir = stagingBase.resolve(hexPrefix + hex);
            Path destFile = destDir.resolve(fileName);

            if (Files.isRegularFile(destFile)) {
                boolean cacheValid = false;
                try {
                    long actualSize = Files.size(destFile);
                    if (actualSize == expectedSize) {
                        Hashing.Hashes actualHashes = Hashing.hashes(destFile);
                        boolean sha256Ok = expectedSha256
                                .map(expected -> expected.equals(actualHashes.sha256()))
                                .orElse(true);
                        boolean sha512Ok = expectedSha512
                                .map(expected -> expected.equals(actualHashes.sha512()))
                                .orElse(true);
                        if (sha256Ok && sha512Ok) {
                            cacheValid = true;
                        }
                    }
                } catch (IOException ignored) {
                }

                if (cacheValid) {
                    String sourceLabel = buildSourceLabel(artifact.download());
                    UpdateProgressDisplay.itemStart(fileName, expectedSize, sourceLabel);
                    String relativePath = gameDirectory.relativize(destFile).toString()
                            .replace('\\', '/');
                    downloaded.add(new DownloadedArtifact(modIds, fileName, version, sourceType,
                            destFile, relativePath));
                    UpdateProgressDisplay.itemOk("downloaded (cached), sha256 verified");
                    continue;
                } else {
                    try {
                        Files.deleteIfExists(destFile);
                    } catch (IOException e) {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.IO_ERROR.name(),
                                failureMessage("I/O error while deleting cached file", e)));
                        continue;
                    }
                }
            }

            try {
                Files.createDirectories(destDir);
            } catch (IOException e) {
                failures.add(new DownloadFailure(
                        modIds, fileName, version, sourceType,
                        FailureCategory.IO_ERROR.name(),
                        failureMessage("I/O error while creating destination directory", e)));
                UpdateProgressDisplay.itemFail("I/O error creating directory");
                continue;
            }

            Path partFile = null;
            boolean committed = false;
            try {

                // Report item start before network operations
                String sourceLabel = buildSourceLabel(artifact.download());
                UpdateProgressDisplay.itemStart(fileName, expectedSize, sourceLabel);

                URI downloadUri;
                try {
                    downloadUri = resolveDownloadUri(artifact.download(), manifest, manifestUri);
                } catch (IllegalArgumentException e) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            failureMessage("Invalid download URL while resolving", e)));
                    UpdateProgressDisplay.itemFail("Invalid download URL");
                    continue;
                }

                if (downloadUri == null) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            "Invalid download URL"));
                    UpdateProgressDisplay.itemFail("Invalid download URL");
                    continue;
                }

                boolean directRelativeErr = false;
                if (artifact.download() instanceof DirectDownload
                        && !downloadUri.isAbsolute()) {
                    directRelativeErr = true;
                }

                if (directRelativeErr) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            "Direct URL must be absolute"));
                    UpdateProgressDisplay.itemFail("Direct URL must be absolute");
                    continue;
                }

                if (!downloadUri.isAbsolute()
                        || downloadUri.getScheme() == null
                        || !("http".equalsIgnoreCase(downloadUri.getScheme())
                        || "https".equalsIgnoreCase(downloadUri.getScheme()))) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            "Invalid download URL"));
                    UpdateProgressDisplay.itemFail("Invalid download URL");
                    continue;
                }

                HttpRequest request;
                try {
                    request = HttpRequest.newBuilder(downloadUri)
                            .GET()
                            .header("Accept", "application/java-archive")
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", USER_AGENT)
                            .timeout(readTimeout)
                            .build();
                } catch (IllegalArgumentException e) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            failureMessage("Invalid download URL while constructing request", e)));
                    UpdateProgressDisplay.itemFail("Invalid download URL");
                    continue;
                }

                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.INTERRUPTED.name(),
                            "Download interrupted"));
                    UpdateProgressDisplay.itemFail("Interrupted");
                    markRemainingInterrupted(failures, orderedArtifacts, artifactToModIds,
                            idx + 1);
                    break;
                } catch (IOException e) {
                    failures.add(new DownloadFailure(
                            modIds, fileName, version, sourceType,
                            FailureCategory.IO_ERROR.name(),
                            failureMessage("I/O error while sending request", e)));
                    UpdateProgressDisplay.itemFail("I/O error");
                    continue;
                }

                try (InputStream body = response.body()) {
                    int httpStatus = response.statusCode();
                    if (httpStatus != 200) {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.HTTP_STATUS.name(),
                                "HTTP " + httpStatus));
                        UpdateProgressDisplay.itemFail("HTTP " + httpStatus);
                        continue;
                    }

                    MessageDigest sha256Digest;
                    MessageDigest sha512Digest;
                    try {
                        sha256Digest = MessageDigest.getInstance("SHA-256");
                        sha512Digest = MessageDigest.getInstance("SHA-512");
                    } catch (NoSuchAlgorithmException impossible) {
                        throw new AssertionError("SHA-256 or SHA-512 unavailable", impossible);
                    }

                    partFile = Files.createTempFile(destDir, "mc-update-", ".part");

                    long totalWritten = 0;
                    boolean interrupted = false;

                    try (OutputStream out = Files.newOutputStream(partFile)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        while (totalWritten < expectedSize) {
                            if (Thread.currentThread().isInterrupted()) {
                                interrupted = true;
                                break;
                            }
                            int limit = (int) Math.min(buf.length, expectedSize - totalWritten);
                            int n = body.read(buf, 0, limit);
                            if (n < 0) {
                                break;
                            }
                            out.write(buf, 0, n);
                            sha256Digest.update(buf, 0, n);
                            sha512Digest.update(buf, 0, n);
                            totalWritten += n;
                            UpdateProgressDisplay.downloadProgress(totalWritten);
                        }
                    }

                    if (interrupted) {
                        Thread.currentThread().interrupt();
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.INTERRUPTED.name(),
                                "Download interrupted"));
                        UpdateProgressDisplay.itemFail("Interrupted");
                        markRemainingInterrupted(failures, orderedArtifacts, artifactToModIds,
                                idx + 1);
                        break;
                    }

                    long got = totalWritten;
                    if (totalWritten == expectedSize) {
                        // Probe for extra data
                        if (Thread.currentThread().isInterrupted()) {
                            interrupted = true;
                            Thread.currentThread().interrupt();
                            failures.add(new DownloadFailure(
                                    modIds, fileName, version, sourceType,
                                    FailureCategory.INTERRUPTED.name(),
                                    "Download interrupted"));
                            UpdateProgressDisplay.itemFail("Interrupted");
                            markRemainingInterrupted(failures, orderedArtifacts, artifactToModIds,
                                    idx + 1);
                            break;
                        }
                        int probe = body.read();
                        if (probe >= 0) {
                            failures.add(new DownloadFailure(
                                    modIds, fileName, version, sourceType,
                                    FailureCategory.SIZE_MISMATCH.name(),
                                    "Expected " + expectedSize + " bytes, got at least " + (expectedSize + 1)));
                            UpdateProgressDisplay.itemFail("Size mismatch");
                            continue;
                        }
                    } else {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.SIZE_MISMATCH.name(),
                                "Expected " + expectedSize + " bytes, got " + got));
                        UpdateProgressDisplay.itemFail("Size mismatch");
                        continue;
                    }

                    String actualSha256 = HexFormat.of().formatHex(sha256Digest.digest());
                    String actualSha512 = HexFormat.of().formatHex(sha512Digest.digest());

                    boolean sha256Match = expectedSha256.map(actualSha256::equals).orElse(true);
                    boolean sha512Match = expectedSha512.map(actualSha512::equals).orElse(true);

                    if (!sha256Match || !sha512Match) {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.HASH_MISMATCH.name(),
                                "Hash verification failed"));
                        UpdateProgressDisplay.itemFail("Hash mismatch");
                        continue;
                    }

                    try {
                        Files.move(partFile, destFile, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        committed = true;
                    } catch (AtomicMoveNotSupportedException e) {
                        try {
                            Files.move(partFile, destFile,
                                    StandardCopyOption.REPLACE_EXISTING);
                            committed = true;
                        } catch (IOException moveFallbackError) {
                            failures.add(new DownloadFailure(
                                    modIds, fileName, version, sourceType,
                                    FailureCategory.IO_ERROR.name(),
                                    failureMessage("I/O error while moving temporary file", moveFallbackError)));
                            UpdateProgressDisplay.itemFail("I/O error during move");
                            continue;
                        }
                    } catch (IOException e) {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.IO_ERROR.name(),
                                failureMessage("I/O error while moving temporary file", e)));
                        UpdateProgressDisplay.itemFail("I/O error during move");
                        continue;
                    }

                    if (!committed) {
                        continue;
                    }

                    String relativePath = gameDirectory.relativize(destFile).toString()
                            .replace('\\', '/');
                    downloaded.add(new DownloadedArtifact(modIds, fileName, version,
                            sourceType, destFile, relativePath));
                    UpdateProgressDisplay.itemOk("downloaded, sha256 verified");

                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.INTERRUPTED.name(),
                                "Download interrupted"));
                        UpdateProgressDisplay.itemFail("Interrupted");
                        markRemainingInterrupted(failures, orderedArtifacts, artifactToModIds,
                                idx + 1);
                        break;
                    } else {
                        failures.add(new DownloadFailure(
                                modIds, fileName, version, sourceType,
                                FailureCategory.IO_ERROR.name(),
                                failureMessage("I/O error while reading response body", e)));
                        UpdateProgressDisplay.itemFail("I/O error");
                    }
                }
            } finally {
                if (!committed && partFile != null) {
                    try {
                        Files.deleteIfExists(partFile);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        UpdateProgressDisplay.downloadPhaseDone(
                downloaded.size(), failures.size(), manualUpdates.size());

        return new DownloadBatchResult(
                manifest.manifestId(),
                manifest.revision(),
                List.copyOf(downloaded),
                List.copyOf(failures),
                List.copyOf(manualUpdates));
    }

    private static String buildSourceLabel(Download download) {
        if (download instanceof HostedDownload) {
            return "hosted";
        }
        if (download instanceof DirectDownload direct) {
            if (direct.provider().isPresent() && !direct.provider().get().isBlank()) {
                return "direct (" + direct.provider().get().strip() + ")";
            }
            return "direct";
        }
        return "unknown";
    }

    private static URI resolveDownloadUri(Download download, Manifest manifest, URI manifestUri) {
        if (download instanceof HostedDownload hosted) {
            String baseUrl = manifest.baseUrl().orElse(null);
            URI base = baseUrl != null ? URI.create(baseUrl) : manifestUri;
            URI relative = URI.create(hosted.url());
            return base.resolve(relative);
        }
        if (download instanceof DirectDownload direct) {
            return URI.create(direct.url());
        }
        throw new IllegalArgumentException("Unsupported download type");
    }

    private static String resolveSourceType(Download download) {
        if (download instanceof HostedDownload) {
            return "hosted";
        } else if (download instanceof DirectDownload) {
            return "direct";
        }
        throw new IllegalArgumentException("Unsupported download type for automatic batch");
    }

    private static void markRemainingInterrupted(
            List<DownloadFailure> failures,
            List<Artifact> orderedArtifacts,
            Map<Artifact, List<String>> artifactToModIds,
            int startIdx) {

        for (int j = startIdx; j < orderedArtifacts.size(); j++) {
            Artifact remaining = orderedArtifacts.get(j);
            List<String> remainingModIds = List.copyOf(artifactToModIds.get(remaining));
            String sourceType = resolveSourceType(remaining.download());
            failures.add(new DownloadFailure(
                    remainingModIds,
                    remaining.fileName(),
                    remaining.version(),
                    sourceType,
                    FailureCategory.INTERRUPTED.name(),
                    "Download interrupted"));
        }
    }

    private static boolean isSimpleJarName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.contains("/") || name.contains("\\")) {
            return false;
        }
        if (!name.endsWith(".jar")) {
            return false;
        }
        if (name.equals(".jar") || name.equals("..")) {
            return false;
        }
        return true;
    }

    private static String failureMessage(String phase, Throwable t) {
        String clazz = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.toString();
        }
        return phase + ": " + clazz + ": " + msg;
    }
}
