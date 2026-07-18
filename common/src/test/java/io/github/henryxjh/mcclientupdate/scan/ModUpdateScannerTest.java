package io.github.henryxjh.mcclientupdate.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.HostedDownload;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.ModAction;
import io.github.henryxjh.mcclientupdate.manifest.Selector;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import io.github.henryxjh.mcclientupdate.platform.CpuArchitecture;
import io.github.henryxjh.mcclientupdate.platform.OperatingSystem;
import io.github.henryxjh.mcclientupdate.platform.RuntimePlatform;
import io.github.henryxjh.mcclientupdate.platform.UpdateTarget;
import io.github.henryxjh.mcclientupdate.update.Hashing;
import io.github.henryxjh.mcclientupdate.update.Hashing.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModUpdateScannerTest {

    @TempDir
    Path tempDir;

    private static final HostedDownload FAKE_DOWNLOAD = new HostedDownload("https://example.com/mod.jar");

    // ---------- helpers ----------

    private Path modsDir() throws IOException {
        Path dir = tempDir.resolve("mods");
        Files.createDirectories(dir);
        return dir;
    }

    private UpdateTarget defaultTarget() {
        return new UpdateTarget("fabric",
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"));
    }

    private Artifact artifact(String version, long size,
                              Optional<String> sha256, Optional<String> sha512) {
        return new Artifact(version, "mod.jar", size,
                new io.github.henryxjh.mcclientupdate.manifest.Hashes(sha256, sha512),
                FAKE_DOWNLOAD);
    }

    private Variant variant(int priority, Selector selector, Artifact artifact) {
        return new Variant(selector, priority, artifact);
    }

    private Mod manifestMod(String name, boolean required, List<Variant> variants) {
        return new Mod(name, required, Optional.empty(), Optional.empty(), variants);
    }

    private Manifest makeManifest(Map<String, Mod> mods) {
        return new Manifest(1, "test-id", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(), mods);
    }

    private InstalledMod installed(String modId, String version, Path file) {
        return new InstalledMod(modId, version, file);
    }

    /** Writes a small file and returns the hashes of its content. */
    private Hashes writeAndHash(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return Hashing.hashes(file);
    }

    // ---------- tests ----------

    @Test
    void noCandidateWhenHashMatches() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] data = { 1, 2, 3, 4 };
        Hashes h = writeAndHash(modJar, data);
        Artifact art = artifact("1.0", data.length, Optional.of(h.sha256()), Optional.of(h.sha512()));
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", modJar));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(0, result.candidates().size());
        assertEquals(1, result.manifestModCount());
        assertEquals(1, result.installedManagedModCount());
    }

    @Test
    void hashMismatchGeneratesCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] data = { 1, 2, 3, 4 };
        Hashes h = writeAndHash(modJar, data);
        // use a different sha256 to force a mismatch
        String wrongSha256 = Hashing.sha256(Files.writeString(modsDir.resolve("helper"), "other"));
        Artifact art = artifact("1.0", data.length, Optional.of(wrongSha256), Optional.of(h.sha512()));
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", modJar));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals("mymod", c.modId());
        assertEquals(UpdateCandidate.Reason.HASH_MISMATCH, c.reason());
        assertTrue(c.installed().isPresent());
        assertEquals(modJar.toAbsolutePath().normalize(), c.installed().get().file());
    }

    @Test
    void sha512OnlyConsistency() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] data = { 1, 2, 3 };
        Hashes h = writeAndHash(modJar, data);
        Artifact art = artifact("1.0", data.length, Optional.empty(), Optional.of(h.sha512()));
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", modJar));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(0, result.candidates().size());
    }

    @Test
    void missingRequiredGeneratesCandidate() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of());
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals(UpdateCandidate.Reason.MISSING_REQUIRED, c.reason());
        assertTrue(c.installed().isEmpty());
    }

    @Test
    void optionalMissingIsSkipped() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", false, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void selectorMatching() throws Exception {
        Path modsDir = modsDir();
        UpdateTarget target = defaultTarget();
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.of(List.of("linux")),
                Optional.of(List.of("x86_64")));
        Artifact art = artifact("1.0", 10, Optional.empty(), Optional.of("b".repeat(128)));
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        ScanResult result = ModUpdateScanner.scan(manifest, target, modsDir, List.of());
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.MISSING_REQUIRED, result.candidates().get(0).reason());
    }

    @Test
    void selectorMismatchCausesNoVariant() throws IOException {
        Path modsDir = modsDir();
        UpdateTarget target = defaultTarget();
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.of(List.of("windows")),
                Optional.empty());
        Artifact art = artifact("1.0", 10, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void priorityTieBreaker() throws Exception {
        Path modsDir = modsDir();
        UpdateTarget target = defaultTarget();
        Selector commonSel = new Selector(Optional.of(List.of("fabric")), Optional.empty(), Optional.empty());
        Artifact lowArt = artifact("low", 10, Optional.empty(), Optional.of("a".repeat(128)));
        // produce a high priority variant with a different hash to ensure mismatch
        Manifest manifestFixed = makeManifest(Map.of("mymod",
                manifestMod("mymod", true, List.of(
                        new Variant(commonSel, 1, lowArt),
                        new Variant(commonSel, 2,
                                artifact("high", 2,
                                        Optional.of("0".repeat(64)),
                                        Optional.empty()))
                ))));

        Path modJar = modsDir.resolve("mymod.jar");
        Files.write(modJar, new byte[]{9,9});
        InstalledMod installed = installed("mymod", "old", modJar);
        ScanResult result = ModUpdateScanner.scan(manifestFixed, target, modsDir, List.of(installed));
        assertEquals(1, result.candidates().size());
        assertEquals("high", result.candidates().get(0).selectedVariant().artifact().version());
    }

    @Test
    void specificityBreaksTie() throws Exception {
        Path modsDir = modsDir();
        UpdateTarget target = defaultTarget();
        Artifact art = artifact("v", 10, Optional.of("c".repeat(64)), Optional.empty());
        Selector moreSpecific = new Selector(Optional.of(List.of("fabric")),
                Optional.of(List.of("linux")),
                Optional.of(List.of("x86_64")));
        Selector lessSpecific = new Selector(Optional.of(List.of("fabric")),
                Optional.empty(), Optional.empty());
        Variant v1 = variant(5, lessSpecific, art);
        Variant v2 = variant(5, moreSpecific, art);
        Mod mod = manifestMod("mymod", true, List.of(v1, v2));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        Path modJar = modsDir.resolve("mymod.jar");
        Files.write(modJar, new byte[]{1});
        // v2 is more specific; create an artifact that mismatches the file
        Artifact v2Art = artifact("v", 1, Optional.of("0".repeat(64)), Optional.empty());
        Manifest fixed = makeManifest(Map.of("mymod",
                manifestMod("mymod", true, List.of(
                        new Variant(lessSpecific, 5, art),
                        new Variant(moreSpecific, 5, v2Art)))));
        ScanResult result = ModUpdateScanner.scan(fixed, target, modsDir,
                List.of(installed("mymod", "any", modJar)));
        assertEquals(1, result.candidates().size());
        assertEquals(v2Art, result.candidates().get(0).selectedVariant().artifact());
    }

    @Test
    void ambiguityThrows() throws IOException {
        Path modsDir = modsDir();
        UpdateTarget target = defaultTarget();
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.of(List.of("linux")),
                Optional.of(List.of("x86_64")));
        Artifact a1 = artifact("a", 10, Optional.of("d".repeat(64)), Optional.empty());
        Artifact a2 = artifact("b", 10, Optional.of("e".repeat(64)), Optional.empty());
        Variant v1 = variant(3, sel, a1);
        Variant v2 = variant(3, sel, a2);
        Mod mod = manifestMod("mymod", true, List.of(v1, v2));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void duplicateModIdThrows() throws Exception {
        Path modsDir = modsDir();
        Manifest manifest = makeManifest(Map.of(
                "a", manifestMod("a", true, List.of(variant(0,
                        new Selector(Optional.empty(), Optional.empty(), Optional.empty()),
                        artifact("v", 10, Optional.of("a".repeat(64)), Optional.empty()))))));
        Path f1 = modsDir.resolve("a.jar");
        Path f2 = modsDir.resolve("a2.jar");
        Files.write(f1, new byte[]{1});
        Files.write(f2, new byte[]{2});
        List<InstalledMod> dup = List.of(installed("a", "1", f1), installed("a", "2", f2));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, dup));
    }

    @Test
    void outsideModsDirectoryRejected() throws Exception {
        Path modsDir = modsDir();
        Path outside = tempDir.resolve("outside.jar");
        Files.write(outside, new byte[]{1,2,3});
        Artifact art = artifact("1.0", 3, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", outside));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed));
    }

    @Test
    void nonRegularFileRejected() throws Exception {
        Path modsDir = modsDir();
        Path dir = modsDir.resolve("mymod.jar");
        Files.createDirectories(dir);
        Artifact art = artifact("1.0", 10, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", dir));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed));
    }

    @Test
    void sameJarMultipleModIdsCompatible() throws Exception {
        Path modsDir = modsDir();
        Path sharedJar = modsDir.resolve("shared.jar");
        byte[] data = { 1, 2, 3, 4, 5 };
        Hashes h = writeAndHash(sharedJar, data);
        Artifact sharedArt = artifact("v", data.length, Optional.of(h.sha256()), Optional.of(h.sha512()));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, sharedArt);
        Manifest manifest = makeManifest(Map.of(
                "a", manifestMod("a", true, List.of(var)),
                "b", manifestMod("b", true, List.of(var))));
        List<InstalledMod> installed = List.of(
                installed("a", "1.0", sharedJar),
                installed("b", "1.0", sharedJar));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void sameJarConflictDetected() throws Exception {
        Path modsDir = modsDir();
        Path sharedJar = modsDir.resolve("shared.jar");
        byte[] data = { 10, 20, 30 };
        writeAndHash(sharedJar, data);
        Artifact artA = artifact("a-ver", data.length,
                Optional.of("f".repeat(64)), Optional.empty());
        Artifact artB = artifact("b-ver", data.length,
                Optional.of("0".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant varA = variant(0, sel, artA);
        Variant varB = variant(0, sel, artB);
        Manifest manifest = makeManifest(Map.of(
                "a", manifestMod("a", true, List.of(varA)),
                "b", manifestMod("b", true, List.of(varB))));
        List<InstalledMod> installed = List.of(
                installed("a", "1.0", sharedJar),
                installed("b", "1.0", sharedJar));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed));
    }

    @Test
    void sameJarViaSymlinkAliasSameArtifactAllowed() throws Exception {
        Path modsDir = modsDir();
        Path realJar = modsDir.resolve("real.jar");
        byte[] data = { 3, 1, 4 };
        writeAndHash(realJar, data);
        Path linkJar = modsDir.resolve("link.jar");
        try {
            Files.createSymbolicLink(linkJar, realJar);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Cannot create symlink on this platform");
            return;
        }

        // Ensure the real path resolves to the same location
        Artifact sharedArt = artifact("v", data.length,
                Optional.of("0".repeat(64)), Optional.of("1".repeat(128))); // mismatch anyway
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, sharedArt);
        Manifest manifest = makeManifest(Map.of(
                "a", manifestMod("a", true, List.of(var)),
                "b", manifestMod("b", true, List.of(var))));
        List<InstalledMod> installed = List.of(
                installed("a", "1.0", realJar),
                installed("b", "2.0", linkJar));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().stream().allMatch(c -> c.reason() == UpdateCandidate.Reason.HASH_MISMATCH));
    }

    @Test
    void sameJarViaSymlinkAliasDifferentArtifactConflict() throws Exception {
        Path modsDir = modsDir();
        Path realJar = modsDir.resolve("real.jar");
        byte[] data = { 7, 8, 9 };
        writeAndHash(realJar, data);
        Path linkJar = modsDir.resolve("link.jar");
        try {
            Files.createSymbolicLink(linkJar, realJar);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Cannot create symlink on this platform");
            return;
        }

        Artifact artA = artifact("a-ver", data.length,
                Optional.of("f".repeat(64)), Optional.empty());
        Artifact artB = artifact("b-ver", data.length,
                Optional.of("0".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant varA = variant(0, sel, artA);
        Variant varB = variant(0, sel, artB);
        Manifest manifest = makeManifest(Map.of(
                "a", manifestMod("a", true, List.of(varA)),
                "b", manifestMod("b", true, List.of(varB))));
        List<InstalledMod> installed = List.of(
                installed("a", "1.0", realJar),
                installed("b", "1.0", linkJar));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed));
    }

    @Test
    void outputOrderIsStable() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("v", 10, Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, new Selector(Optional.empty(), Optional.empty(), Optional.empty()), art);
        Manifest manifest = makeManifest(Map.of(
                "z", manifestMod("z", true, List.of(var)),
                "a", manifestMod("a", true, List.of(var))));
        Path fileA = modsDir.resolve("a.jar");
        Path fileZ = modsDir.resolve("z.jar");
        Files.write(fileA, new byte[]{1});
        Files.write(fileZ, new byte[]{2});
        List<InstalledMod> installed = List.of(
                installed("a", "va", fileA),
                installed("z", "vz", fileZ));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        List<UpdateCandidate> candidates = result.candidates();
        assertEquals(2, candidates.size());
        assertEquals("a", candidates.get(0).modId());
        assertEquals("z", candidates.get(1).modId());
    }

    @Test
    void deleteInstalledGeneratesDeleteCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] data = {1, 2, 3};
        Files.write(modJar, data);
        Mod mod = new Mod("mymod", false, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "1.0", modJar));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals(UpdateCandidate.Reason.DELETE, c.reason());
        assertTrue(c.installed().isPresent());
        assertNull(c.selectedVariant());
    }

    @Test
    void deleteNotInstalledSkips() throws Exception {
        Path modsDir = modsDir();
        Mod mod = new Mod("mymod", false, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void deleteIgnoresVariantSelectorAndRequired() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        Files.write(modJar, new byte[]{1});
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        List<InstalledMod> installed = List.of(installed("mymod", "any", modJar));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals(UpdateCandidate.Reason.DELETE, c.reason());
        assertNull(c.selectedVariant());
    }

    @Test
    void deleteRequiredNotInstalledSkips() throws Exception {
        Path modsDir = modsDir();
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of());
        assertTrue(result.candidates().isEmpty());
    }

    // ---- minimum loader version tests (top-level) ----

    private UpdateTarget targetWithLoaderVersion(String loader, String version) {
        return new UpdateTarget(loader,
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"),
                version);
    }

    @Test
    void minimumLoaderVersionPassesWhenCurrentExceedsMinimum() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-min", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("fabric", "0.15.0")));
        UpdateTarget target = targetWithLoaderVersion("fabric", "0.16.0");
        ScanResult result = ModUpdateScanner.scan(manifest, target, modsDir, List.of());
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.MISSING_REQUIRED, result.candidates().get(0).reason());
    }

    @Test
    void minimumLoaderVersionFailsLowerCurrent() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-min", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("fabric", "0.16.0")));
        UpdateTarget target = targetWithLoaderVersion("fabric", "0.14.0");
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void minimumLoaderVersionAbsentCurrentLoaderKeyCausesNoMatch() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-min", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("neoforge", "1.0")));
        UpdateTarget target = targetWithLoaderVersion("fabric", "1.0");
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void minimumLoaderVersionEqualCurrentPasses() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("fabric")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-min", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("fabric", "0.16.0")));
        UpdateTarget target = targetWithLoaderVersion("fabric", "0.16.0");
        ScanResult result = ModUpdateScanner.scan(manifest, target, modsDir, List.of());
        assertEquals(1, result.candidates().size());
    }

    @Test
    void minimumLoaderVersionMultiDigitPass() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("neoforge")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-multidigit", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("neoforge", "21.1.100")));
        UpdateTarget target = new UpdateTarget("neoforge",
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"),
                "21.1.100");
        ScanResult result = ModUpdateScanner.scan(manifest, target, modsDir, List.of());
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.MISSING_REQUIRED, result.candidates().get(0).reason());
    }

    @Test
    void minimumLoaderVersionMultiDigitFailLower() throws Exception {
        Path modsDir = modsDir();
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.of(List.of("neoforge")),
                Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = new Manifest(1, "test-multidigit", 1L, Instant.now(),
                Optional.empty(), "1.21.1", Optional.empty(),
                Map.of("mymod", mod),
                Optional.of(Map.of("neoforge", "21.1.100")));
        UpdateTarget target = new UpdateTarget("neoforge",
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"),
                "21.1.99");
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void forgeSelectorMatchesForgeTarget() throws Exception {
        Path modsDir = modsDir();
        UpdateTarget target = new UpdateTarget("forge",
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"));
        Selector sel = new Selector(Optional.of(List.of("forge")),
                Optional.empty(), Optional.empty());
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        ScanResult result = ModUpdateScanner.scan(manifest, target, modsDir, List.of());
        assertEquals(1, result.candidates().size());
        assertEquals("mymod", result.candidates().get(0).modId());
    }

    @Test
    void skipIfGreaterInstalledVersionSkipsCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] content = {1, 2, 3};
        Hashes fileHashes = writeAndHash(modJar, content);
        Artifact art = artifact("1.5.0", content.length, Optional.empty(), Optional.of(fileHashes.sha512()));
        // use a mismatched sha to force hash mismatch, but should be skipped because version > threshold
        Artifact mismatchedArt = artifact("1.5.0", content.length, Optional.of("0".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, mismatchedArt);
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                Optional.of("1.4.0"), // threshold
                List.of(var), ModAction.INSTALL);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        InstalledMod installed = installed("mymod", "1.5.0", modJar);
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of(installed));
        assertEquals(0, result.candidates().size());
    }

    @Test
    void skipIfEqualInstalledVersionCreatesHashMismatchCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] content = {4,5,6};
        Hashes fileHashes = writeAndHash(modJar, content);
        String wrongSha = Hashing.sha256(Files.writeString(modsDir.resolve("other"), "fff"));
        Artifact art = artifact("1.3.0", content.length, Optional.of(wrongSha), Optional.of(fileHashes.sha512()));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                Optional.of("1.3.0"), // threshold equal
                List.of(var), ModAction.INSTALL);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        InstalledMod installed = installed("mymod", "1.3.0", modJar);
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of(installed));
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.HASH_MISMATCH, result.candidates().get(0).reason());
    }

    @Test
    void skipIfLowerInstalledVersionCreatesHashMismatchCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        byte[] content = {7,8};
        Hashes fileHashes = writeAndHash(modJar, content);
        String wrongSha = Hashing.sha256(Files.writeString(modsDir.resolve("other2"), "abc"));
        Artifact art = artifact("1.2.0", content.length, Optional.of(wrongSha), Optional.of(fileHashes.sha512()));
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                Optional.of("1.3.0"), // threshold > installed
                List.of(var), ModAction.INSTALL);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        InstalledMod installed = installed("mymod", "1.2.0", modJar);
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of(installed));
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.HASH_MISMATCH, result.candidates().get(0).reason());
    }

    @Test
    void skipIfInstalledVersionGreaterDoesNotAffectDeleteAction() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("mymod.jar");
        Files.write(modJar, new byte[]{1});
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                Optional.of("1.0.0"), // threshold (ignored for delete)
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        InstalledMod installed = installed("mymod", "2.0.0", modJar);
        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of(installed));
        assertEquals(1, result.candidates().size());
        assertEquals(UpdateCandidate.Reason.DELETE, result.candidates().get(0).reason());
    }

    @Test
    void forgeSelectorDoesNotMatchNeoforgeTarget() throws IOException {
        Path modsDir = modsDir();
        UpdateTarget target = new UpdateTarget("neoforge",
                new RuntimePlatform(OperatingSystem.LINUX, CpuArchitecture.X86_64,
                        "Linux", "5.15.0-70-generic", "amd64"));
        Selector sel = new Selector(Optional.of(List.of("forge")),
                Optional.empty(), Optional.empty());
        Artifact art = artifact("1.0", 10,
                Optional.of("a".repeat(64)), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = manifestMod("mymod", true, List.of(var));
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, target, modsDir, List.of()));
    }

    @Test
    void skipIfGreaterInstalledOutsideModsDirectoryRejected() throws IOException {
        Path modsDir = modsDir();
        Path outside = tempDir.resolve("outside.jar");
        Files.write(outside, new byte[]{1,2,3});
        Artifact art = artifact("1.0", 3, Optional.of("a".repeat(64)), Optional.empty());
        Selector sel = new Selector(Optional.empty(), Optional.empty(), Optional.empty());
        Variant var = variant(0, sel, art);
        Mod mod = new Mod("mymod", true, Optional.empty(), Optional.empty(),
                Optional.of("0.9"), // threshold
                List.of(var), ModAction.INSTALL);
        Manifest manifest = makeManifest(Map.of("mymod", mod));
        InstalledMod installed = installed("mymod", "2.0", outside);
        assertThrows(ModScanException.class,
                () -> ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, List.of(installed)));
    }

    @Test
    void protectedDeleteSelfModSkippedWhenInstalled() throws Exception {
        Path modsDir = modsDir();
        Path selfJar = modsDir.resolve("mc-client-update.jar");
        byte[] content = { 1, 2, 3 };
        Files.write(selfJar, content);

        Mod mod = new Mod("mc_client_update", false, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mc_client_update", mod));
        List<InstalledMod> installed = List.of(installed("mc_client_update", "1.0.0", selfJar));

        Set<String> protectedIds = Set.of("mc_client_update");
        ScanResult result = ModUpdateScanner.scan(
                manifest,
                defaultTarget(),
                modsDir,
                installed,
                protectedIds);
        assertEquals(0, result.candidates().size());
        assertEquals(1, result.manifestModCount());
        assertEquals(1, result.installedManagedModCount());
    }

    @Test
    void unprotectedDeleteStillEmitsCandidate() throws Exception {
        Path modsDir = modsDir();
        Path modJar = modsDir.resolve("some-other-mod.jar");
        byte[] content = { 5, 6, 7 };
        Files.write(modJar, content);

        Mod mod = new Mod("some-other-mod", false, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("some-other-mod", mod));
        List<InstalledMod> installed = List.of(installed("some-other-mod", "1.0.0", modJar));

        ScanResult result = ModUpdateScanner.scan(
                manifest,
                defaultTarget(),
                modsDir,
                installed,
                Collections.emptySet());
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals("some-other-mod", c.modId());
        assertEquals(UpdateCandidate.Reason.DELETE, c.reason());
    }

    @Test
    void oldScanOverloadStillEmitsDeleteForSelfModId() throws Exception {
        Path modsDir = modsDir();
        Path selfJar = modsDir.resolve("mc-client-update.jar");
        byte[] content = { 1, 2 };
        Files.write(selfJar, content);

        Mod mod = new Mod("mc_client_update", true, Optional.empty(), Optional.empty(),
                List.of(), ModAction.DELETE);
        Manifest manifest = makeManifest(Map.of("mc_client_update", mod));
        List<InstalledMod> installed = List.of(installed("mc_client_update", "1.0.0", selfJar));

        ScanResult result = ModUpdateScanner.scan(manifest, defaultTarget(), modsDir, installed);
        assertEquals(1, result.candidates().size());
        UpdateCandidate c = result.candidates().get(0);
        assertEquals("mc_client_update", c.modId());
        assertEquals(UpdateCandidate.Reason.DELETE, c.reason());
    }
}
