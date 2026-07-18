package io.github.henryxjh.mcclientupdate.update.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.henryxjh.mcclientupdate.download.DownloadBatchResult;
import io.github.henryxjh.mcclientupdate.download.DownloadedArtifact;
import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.Hashes;
import io.github.henryxjh.mcclientupdate.manifest.HostedDownload;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.Selector;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;
import io.github.henryxjh.mcclientupdate.update.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactInstallerTest {

    @TempDir
    Path tempDir;

    private Path gameDir() throws IOException {
        Path dir = tempDir.resolve("game");
        Files.createDirectories(dir);
        return dir;
    }

    private Path stagingDir(Path gameDir) throws IOException {
        Path dir = gameDir.resolve(".mc-client-update").resolve("downloads");
        Files.createDirectories(dir);
        return dir;
    }

    private Manifest makeManifest(Map<String, Mod> mods) {
        return new Manifest(1, "test-install", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(), mods);
    }

    @Test
    void replaceExistingJar() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path existing = modsDir.resolve("existing.jar");
        Files.writeString(existing, "old", UTF_8);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("existing.jar");
        Files.writeString(staged, "new", UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                        Optional.of(stagedHashes.sha256()),
                        Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("1.0", "existing.jar", "new".length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Variant variant = new Variant(
                new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 1, artifact);
        Mod mod = new Mod("mod-x", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-x", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-x"), "existing.jar", "1.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "mod-x", mod, variant,
                        Optional.of(new InstalledMod("mod-x", "0.9", existing)),
                        UpdateCandidate.Reason.HASH_MISMATCH)),
                1, 1);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("REPLACE", installed.action());
        assertEquals("new", Files.readString(existing, UTF_8));
    }

    @Test
    void addMissingRequired() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("mod.jar");
        String content = "mod-content";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                        Optional.of(stagedHashes.sha256()),
                        Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("2.0", "mod.jar", content.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Variant variant = new Variant(
                new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 1, artifact);
        Mod mod = new Mod("mod-y", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-y", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-y"), "mod.jar", "2.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "mod-y", mod, variant,
                        Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("ADD", installed.action());
        assertTrue(installed.backupRelativePath().isEmpty());
        Path expectedPath = modsDir.resolve("mod.jar");
        assertTrue(Files.isRegularFile(expectedPath));
        assertEquals(content, Files.readString(expectedPath, UTF_8));
    }

    @Test
    void targetConflict() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path existing = modsDir.resolve("present.jar");
        Files.writeString(existing, "old-stuff", UTF_8);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("present.jar");
        Files.writeString(staged, "new-stuff", UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                        Optional.of(stagedHashes.sha256()),
                        Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("1.0", "present.jar", "new-stuff".length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Variant variant = new Variant(
                new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 1, artifact);
        Mod mod = new Mod("mod-z", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-z", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-z"), "present.jar", "1.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "mod-z", mod, variant,
                        Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(0, res.installed().size());
        assertEquals(1, res.failures().size());
        InstallFailure fail = res.failures().get(0);
        assertEquals(InstallFailure.InstallFailureCategory.TARGET_CONFLICT, fail.category());
        assertEquals("present.jar", fail.fileName());
    }

    @Test
    void failureDoesNotInterruptRemainingInstallations() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path existingA = modsDir.resolve("A.jar");
        Files.writeString(existingA, "oldA", UTF_8);

        Path stageDir = stagingDir(gameDir);

        Path stagedA = stageDir.resolve("A.jar");
        String contentA = "AAAA";
        Files.writeString(stagedA, contentA, UTF_8);
        Path stagedB = stageDir.resolve("B.jar");
        String contentB = "BBBB";
        Files.writeString(stagedB, contentB, UTF_8);

        Hashing.Hashes hashesA = Hashing.hashes(stagedA);
        Hashes mhA = new Hashes(
                        Optional.of(hashesA.sha256()), Optional.of(hashesA.sha512()));

        Hashing.Hashes hashesB = Hashing.hashes(stagedB);
        Hashes mhB = new Hashes(
                        Optional.of(hashesB.sha256()), Optional.of(hashesB.sha512()));

        Artifact artA = new Artifact("1", "A.jar", contentA.length(),
                mhA, new HostedDownload("http://example.com/A.jar"));
        Artifact artB = new Artifact("2", "B.jar", contentB.length(),
                mhB, new HostedDownload("http://example.com/B.jar"));

        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant varA = new Variant(sel, 1, artA);
        Variant varB = new Variant(sel, 1, artB);

        Mod modA = new Mod("mod-a", true, Optional.empty(), Optional.empty(), List.of(varA));
        Mod modB = new Mod("mod-b", true, Optional.empty(), Optional.empty(), List.of(varB));

        Manifest manifest = makeManifest(Map.of("mod-a", modA, "mod-b", modB));

        DownloadedArtifact daA = new DownloadedArtifact(
                List.of("mod-a"), "A.jar", "1", "hosted", stagedA,
                ".mc-client-update/downloads/" + stagedA.getFileName());
        DownloadedArtifact daB = new DownloadedArtifact(
                List.of("mod-b"), "B.jar", "2", "hosted", stagedB,
                ".mc-client-update/downloads/" + stagedB.getFileName());

        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(daA, daB), List.of(), List.of());

        ScanResult scan = new ScanResult(
                List.of(
                        new UpdateCandidate(
                                "mod-a", modA, varA,
                                Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED),
                        new UpdateCandidate(
                                "mod-b", modB, varB,
                                Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);

        assertEquals(1, res.installed().size());
        assertEquals("B.jar", res.installed().get(0).fileName());
        assertEquals(1, res.failures().size());
        InstallFailure fail = res.failures().get(0);
        assertEquals(InstallFailure.InstallFailureCategory.TARGET_CONFLICT, fail.category());
        assertEquals("A.jar", fail.fileName());
    }
}
