package io.github.henryxjh.mcclientupdate.update.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.henryxjh.mcclientupdate.download.DownloadBatchResult;
import io.github.henryxjh.mcclientupdate.download.DownloadedArtifact;
import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.Hashes;
import io.github.henryxjh.mcclientupdate.manifest.HostedDownload;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.ModAction;
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

    private static String canonicalFor(Artifact artifact, String modId, Hashing.Hashes hashes) {
        String full;
        if (hashes.sha512() != null && !hashes.sha512().isEmpty()) {
            full = hashes.sha512();
        } else {
            full = hashes.sha256();
        }
        if (full == null || full.length() < 32) {
            throw new IllegalArgumentException("hash too short for canonical name: " + full);
        }
        String hash = full.substring(0, 32);
        return modId + "-" + artifact.version() + "-" + hash + ".jar";
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

        // canonical name -> modid-version-hash.jar; using sha512 because it exists
        String canonical = canonicalFor(artifact, "mod-x", stagedHashes);
        assertEquals(canonical, installed.fileName());
        Path canonicalPath = modsDir.resolve(canonical);
        assertTrue(Files.isRegularFile(canonicalPath));
        assertEquals("new", Files.readString(canonicalPath, UTF_8));

        // old jar must have been backed up (rename scenario because name differs)
        assertTrue(Files.notExists(existing));
        Path backup = modsDir.resolve(".existing.jar.mc-client-update-old");
        assertTrue(Files.isRegularFile(backup));
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

        String canonical = canonicalFor(artifact, "mod-y", stagedHashes);
        assertEquals(canonical, installed.fileName());
        Path expectedPath = modsDir.resolve(canonical);
        assertTrue(Files.isRegularFile(expectedPath));
        assertEquals(content, Files.readString(expectedPath, UTF_8));
    }

    @Test
    void targetConflictDueToCanonicalName() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        // create a file that will conflict with the canonical target
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

        // create a dummy file that matches the canonical name
        String canonical = canonicalFor(artifact, "mod-z", stagedHashes);
        Path conflict = modsDir.resolve(canonical);
        Files.writeString(conflict, "some-existing", UTF_8);

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
        assertEquals(canonical, fail.fileName());
    }

    @Test
    void failureDoesNotInterruptRemainingInstallations() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        // create a conflict for one mod by pre-creating the canonical file
        Path stageDir = stagingDir(gameDir);

        // mod-a: will conflict
        Path stagedA = stageDir.resolve("A.jar");
        String contentA = "AAAA";
        Files.writeString(stagedA, contentA, UTF_8);
        // mod-b: fine
        Path stagedB = stageDir.resolve("B.jar");
        String contentB = "BBBB";
        Files.writeString(stagedB, contentB, UTF_8);

        Hashing.Hashes hashesA = Hashing.hashes(stagedA);
        Hashes mhA = new Hashes(Optional.of(hashesA.sha256()), Optional.of(hashesA.sha512()));

        Hashing.Hashes hashesB = Hashing.hashes(stagedB);
        Hashes mhB = new Hashes(Optional.of(hashesB.sha256()), Optional.of(hashesB.sha512()));

        Artifact artA = new Artifact("1", "A.jar", contentA.length(), mhA,
                new HostedDownload("http://example.com/A.jar"));
        Artifact artB = new Artifact("2", "B.jar", contentB.length(), mhB,
                new HostedDownload("http://example.com/B.jar"));

        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant varA = new Variant(sel, 1, artA);
        Variant varB = new Variant(sel, 1, artB);

        Mod modA = new Mod("mod-a", true, Optional.empty(), Optional.empty(), List.of(varA));
        Mod modB = new Mod("mod-b", true, Optional.empty(), Optional.empty(), List.of(varB));

        Manifest manifest = makeManifest(Map.of("mod-a", modA, "mod-b", modB));

        // pre-create conflict file for mod-a
        String canonicalA = canonicalFor(artA, "mod-a", hashesA);
        Path conflict = modsDir.resolve(canonicalA);
        Files.writeString(conflict, "blocker", UTF_8);

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
                        new UpdateCandidate("mod-a", modA, varA, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED),
                        new UpdateCandidate("mod-b", modB, varB, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);

        assertEquals(1, res.installed().size());
        assertEquals(canonicalFor(artB, "mod-b", hashesB), res.installed().get(0).fileName());
        assertEquals(1, res.failures().size());
        InstallFailure fail = res.failures().get(0);
        assertEquals(InstallFailure.InstallFailureCategory.TARGET_CONFLICT, fail.category());
        assertEquals(canonicalA, fail.fileName());
    }

    @Test
    void successfulDeleteMovesAndRecordsArtifact() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path existing = modsDir.resolve("delme.jar");
        String content = "to-be-removed";
        Files.writeString(existing, content, UTF_8);

        InstalledMod installed = new InstalledMod("delete-me", "1.2.3", existing);
        Mod delMod = new Mod("delete-me", false, Optional.empty(),
                Optional.empty(), List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("delete-me", delMod));
        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "delete-me", delMod, null,
                        Optional.of(installed), UpdateCandidate.Reason.DELETE)),
                1, 1);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(
                manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installedArt = res.installed().get(0);
        assertEquals("DELETE", installedArt.action());
        assertEquals("delete", installedArt.sourceType());
        assertEquals("delme.jar", installedArt.fileName());
        assertEquals("1.2.3", installedArt.version());
        assertTrue(installedArt.backupRelativePath().isPresent());
        String backupRel = installedArt.backupRelativePath().get();
        Path backupPath = gameDir.resolve(backupRel);
        assertEquals(
                "." + existing.getFileName().toString()
                + ".mc-client-update-deleted",
                backupPath.getFileName().toString());
        assertTrue(Files.isRegularFile(backupPath));
        assertTrue(Files.notExists(existing));
    }

    @Test
    void deleteBackupConflictRecordsFailure() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path existing = modsDir.resolve("conflict.jar");
        Files.writeString(existing, "stuff", UTF_8);
        Path backup = existing.resolveSibling(
                "." + existing.getFileName().toString()
                + ".mc-client-update-deleted");
        Files.writeString(backup, "occupied", UTF_8);

        InstalledMod installed = new InstalledMod("conflict-mod", "0.1", existing);
        Mod delMod = new Mod("conflict-mod", false, Optional.empty(),
                Optional.empty(), List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("conflict-mod", delMod));
        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "conflict-mod", delMod, null,
                        Optional.of(installed), UpdateCandidate.Reason.DELETE)),
                1, 1);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(
                manifest, scan, dl, gameDir);
        assertEquals(0, res.installed().size());
        assertEquals(1, res.failures().size());
        InstallFailure failure = res.failures().get(0);
        assertEquals(InstallFailure.InstallFailureCategory.TARGET_CONFLICT, failure.category());
        assertEquals("conflict.jar", failure.fileName());
        assertEquals("0.1", failure.version());
        assertEquals("delete", failure.sourceType());
        assertTrue(Files.isRegularFile(existing));
        assertTrue(Files.isRegularFile(backup));
    }

    /**
     * Multiple modIds pointing to the same artifact install only once
     * and the canonical name uses the first sorted modId.
     */
    @Test
    void sharedArtifactInstallsOnceWithFirstModId() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("shared.jar");
        String content = "shared";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                Optional.of(stagedHashes.sha256()),
                Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("3.0", "shared.jar", content.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant variant = new Variant(sel, 1, artifact);
        Mod modFirst = new Mod("first-mod", true, Optional.empty(), Optional.empty(), List.of(variant));
        Mod modSecond = new Mod("second-mod", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("first-mod", modFirst, "second-mod", modSecond));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("first-mod", "second-mod"), "shared.jar", "3.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        ScanResult scan = new ScanResult(
                List.of(
                        new UpdateCandidate("first-mod", modFirst, variant, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED),
                        new UpdateCandidate("second-mod", modSecond, variant, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("ADD", installed.action());

        // sorted first modId is "first-mod"
        String canonical = canonicalFor(artifact, "first-mod", stagedHashes);
        assertEquals(canonical, installed.fileName());
        Path expected = modsDir.resolve(canonical);
        assertTrue(Files.isRegularFile(expected));
        assertEquals(content, Files.readString(expected, UTF_8));
    }

    /**
     * REPLACE where old name differs from canonical name moves old file to backup
     * and installs new canonical file.
     */
    @Test
    void replaceRenameOldJar() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path oldPath = modsDir.resolve("old-name.jar");
        Files.writeString(oldPath, "old-content", UTF_8);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("new-name.jar");
        String newContent = "new-content";
        Files.writeString(staged, newContent, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                Optional.of(stagedHashes.sha256()),
                Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("5.0", "new-name.jar", newContent.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant variant = new Variant(sel, 1, artifact);
        Mod mod = new Mod("rename-mod", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("rename-mod", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("rename-mod"), "new-name.jar", "5.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        InstalledMod installed = new InstalledMod("rename-mod", "0.1", oldPath);
        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "rename-mod", mod, variant,
                        Optional.of(installed), UpdateCandidate.Reason.HASH_MISMATCH)),
                1, 1);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installedArt = res.installed().get(0);
        assertEquals("REPLACE", installedArt.action());

        String canonical = canonicalFor(artifact, "rename-mod", stagedHashes);
        assertEquals(canonical, installedArt.fileName());
        Path canonicalPath = modsDir.resolve(canonical);
        assertTrue(Files.isRegularFile(canonicalPath));
        assertEquals(newContent, Files.readString(canonicalPath, UTF_8));

        assertTrue(Files.notExists(oldPath));
        Path backup = modsDir.resolve(".old-name.jar.mc-client-update-old");
        assertTrue(Files.isRegularFile(backup));
        assertEquals("old-content", Files.readString(backup, UTF_8));
    }

    /**
     * Version containing unsafe characters is sanitised in canonical name.
     */
    @Test
    void sanitisedVersionInCanonicalName() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("z.jar");
        String content = "z";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        Hashes manifestHashes = new Hashes(
                Optional.of(stagedHashes.sha256()),
                Optional.of(stagedHashes.sha512()));

        // version contains characters like space and slash
        Artifact artifact = new Artifact("1 2/3!@", "z.jar", content.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Variant variant = new Variant(
                new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 1, artifact);
        Mod mod = new Mod("abc", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("abc", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("abc"), "z.jar", "1 2/3!@", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "abc", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("ADD", installed.action());

        String fullHash = stagedHashes.sha512() != null && !stagedHashes.sha512().isEmpty()
                ? stagedHashes.sha512() : stagedHashes.sha256();
        String hash = fullHash.substring(0, 32);
        // sanitised version replaces /, ! and spaces with underscores
        assertTrue(installed.fileName().startsWith("abc-1_2_3__-"));
        assertTrue(installed.fileName().endsWith("-" + hash + ".jar"));
        assertFalse(installed.fileName().contains(" 2/"));
        assertFalse(installed.fileName().contains("!"));
    }

    @Test
    void sha512MissingUsesSha256InCanonicalName() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("mod.jar");
        String content = "content-without-sha512";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        // manifest contains only sha256, no sha512
        Hashes manifestHashes = new Hashes(
                Optional.of(stagedHashes.sha256()),
                Optional.empty());

        Artifact artifact = new Artifact("1.0", "mod.jar", content.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Variant variant = new Variant(
                new Selector(Optional.empty(), Optional.empty(), Optional.empty()),
                1,
                artifact);
        Mod mod = new Mod("mod-a", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-a", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-a"), "mod.jar", "1.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate(
                        "mod-a", mod, variant,
                        Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("ADD", installed.action());

        String expectedHash = stagedHashes.sha256().substring(0, 32);
        String expectedFileName = "mod-a" + "-" + artifact.version() + "-" + expectedHash + ".jar";
        assertEquals(expectedFileName, installed.fileName());
        Path expectedPath = modsDir.resolve(expectedFileName);
        assertTrue(Files.isRegularFile(expectedPath));
        assertEquals(content, Files.readString(expectedPath, UTF_8));
    }

    @Test
    void commonFileNameDifferentHashInstallsBoth() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path sub1 = stageDir.resolve("a");
        Files.createDirectory(sub1);
        Path staged1 = sub1.resolve("common.jar");
        String content1 = "first-content";
        Files.writeString(staged1, content1, UTF_8);

        Path sub2 = stageDir.resolve("b");
        Files.createDirectory(sub2);
        Path staged2 = sub2.resolve("common.jar");
        String content2 = "second-content";
        Files.writeString(staged2, content2, UTF_8);

        Hashing.Hashes h1 = Hashing.hashes(staged1);
        Hashes mh1 = new Hashes(Optional.of(h1.sha256()), Optional.of(h1.sha512()));

        Hashing.Hashes h2 = Hashing.hashes(staged2);
        Hashes mh2 = new Hashes(Optional.of(h2.sha256()), Optional.of(h2.sha512()));

        Artifact art1 = new Artifact("1.0", "common.jar", content1.length(),
                mh1, new HostedDownload("http://example.com/a.jar"));
        Artifact art2 = new Artifact("1.0", "common.jar", content2.length(),
                mh2, new HostedDownload("http://example.com/b.jar"));

        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var1 = new Variant(sel, 1, art1);
        Variant var2 = new Variant(sel, 1, art2);

        Mod modA = new Mod("mod-a", true, Optional.empty(), Optional.empty(), List.of(var1));
        Mod modB = new Mod("mod-b", true, Optional.empty(), Optional.empty(), List.of(var2));
        Manifest manifest = makeManifest(Map.of("mod-a", modA, "mod-b", modB));

        DownloadedArtifact da1 = new DownloadedArtifact(
                List.of("mod-a"), "common.jar", "1.0", "hosted", staged1,
                ".mc-client-update/downloads/" + staged1.getFileName());
        DownloadedArtifact da2 = new DownloadedArtifact(
                List.of("mod-b"), "common.jar", "1.0", "hosted", staged2,
                ".mc-client-update/downloads/" + staged2.getFileName());

        ScanResult scan = new ScanResult(
                List.of(
                        new UpdateCandidate("mod-a", modA, var1, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED),
                        new UpdateCandidate("mod-b", modB, var2, Optional.empty(),
                                UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da1, da2), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(2, res.installed().size());
        assertTrue(res.failures().isEmpty());

        long distinctPaths = res.installed().stream()
                .map(inst -> gameDir.resolve("mods").resolve(inst.fileName()))
                .distinct()
                .count();
        assertEquals(2, distinctPaths, "both artifacts must be installed under different canonical names");
        for (InstalledArtifact inst : res.installed()) {
            Path p = gameDir.resolve("mods").resolve(inst.fileName());
            assertTrue(Files.isRegularFile(p), "installed file missing: " + p);
            assertTrue(Files.readString(p, UTF_8).length() > 0, "empty content");
        }
        // verify that content matches expected hash-based naming
        // first file uses sha256 of content1, second uses content2
        InstalledArtifact first = res.installed().get(0);
        InstalledArtifact second = res.installed().get(1);
        assertFalse(first.fileName().equals(second.fileName()));
        assertTrue(first.fileName().contains("-1.0-"));
        assertTrue(second.fileName().contains("-1.0-"));
    }

    @Test
    void sha512HashTruncatedTo32() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("mod.jar");
        String content = "test";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes stagedHashes = Hashing.hashes(staged);
        // only sha512
        Hashes manifestHashes = new Hashes(Optional.empty(), Optional.of(stagedHashes.sha512()));

        Artifact artifact = new Artifact("1.0", "mod.jar", content.length(),
                manifestHashes, new HostedDownload("http://example.com/mod.jar"));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant variant = new Variant(sel, 1, artifact);
        Mod mod = new Mod("mod-t", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-t", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-t"), "mod.jar", "1.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate("mod-t", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(1, res.installed().size());
        InstalledArtifact installed = res.installed().get(0);
        assertEquals("ADD", installed.action());

        // hash part should be first 32 chars of sha512
        String expectedHash = stagedHashes.sha512().substring(0, 32);
        String expectedFileName = "mod-t" + "-1.0-" + expectedHash + ".jar";
        assertEquals(expectedFileName, installed.fileName());
        Path expectedPath = modsDir.resolve(expectedFileName);
        assertTrue(Files.isRegularFile(expectedPath));
        assertEquals(content, Files.readString(expectedPath, UTF_8));
    }

    @Test
    void installIoErrorContainsStageAndException() throws IOException {
        Path gameDir = gameDir();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path stageDir = stagingDir(gameDir);
        Path staged = stageDir.resolve("mod.jar");
        String content = "mod";
        Files.writeString(staged, content, UTF_8);

        Hashing.Hashes h = Hashing.hashes(staged);
        Hashes mh = new Hashes(Optional.of(h.sha256()), Optional.of(h.sha512()));

        Artifact artifact = new Artifact("1.0", "mod.jar", content.length(),
                mh, new HostedDownload("http://example.com/mod.jar"));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant variant = new Variant(sel, 1, artifact);
        Mod mod = new Mod("mod-io", true, Optional.empty(), Optional.empty(), List.of(variant));
        Manifest manifest = makeManifest(Map.of("mod-io", mod));

        DownloadedArtifact da = new DownloadedArtifact(
                List.of("mod-io"), "mod.jar", "1.0", "hosted", staged,
                ".mc-client-update/downloads/" + staged.getFileName());

        ScanResult scan = new ScanResult(
                List.of(new UpdateCandidate("mod-io", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)),
                1, 0);
        DownloadBatchResult dl = new DownloadBatchResult(
                manifest.manifestId(), 1L, List.of(da), List.of(), List.of());

        // Create the pending path as a non-empty directory to provoke copy failure
        String canonical = canonicalFor(artifact, "mod-io", h);
        Path pendingPath = modsDir.resolve("." + canonical + ".mc-client-update-pending");
        Files.createDirectories(pendingPath);
        Files.writeString(pendingPath.resolve("block"), "block", UTF_8);

        InstallBatchResult res = ArtifactInstaller.installBatch(manifest, scan, dl, gameDir);
        assertEquals(0, res.installed().size());
        assertEquals(1, res.failures().size());
        InstallFailure failure = res.failures().get(0);
        assertEquals(InstallFailure.InstallFailureCategory.IO_ERROR, failure.category());
        String msg = failure.message();
        assertTrue(msg.contains("I/O error while copying to pending file"),
                "Message must contain stage description, got: " + msg);
        assertTrue(msg.contains("FileAlreadyExistsException")
                        || msg.contains("DirectoryNotEmptyException")
                        || msg.contains("FileSystemException"),
                "Message must contain exception class name, got: " + msg);
    }
}
