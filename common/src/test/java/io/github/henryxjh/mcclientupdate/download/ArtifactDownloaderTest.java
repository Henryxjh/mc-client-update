package io.github.henryxjh.mcclientupdate.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.henryxjh.mcclientupdate.manifest.Artifact;
import io.github.henryxjh.mcclientupdate.manifest.DirectDownload;
import io.github.henryxjh.mcclientupdate.manifest.Download;
import io.github.henryxjh.mcclientupdate.manifest.Hashes;
import io.github.henryxjh.mcclientupdate.manifest.HostedDownload;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.ManualDownload;
import io.github.henryxjh.mcclientupdate.manifest.Mod;
import io.github.henryxjh.mcclientupdate.manifest.ModAction;
import io.github.henryxjh.mcclientupdate.manifest.Selector;
import io.github.henryxjh.mcclientupdate.manifest.Variant;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDownloaderTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private URI manifestUri;
    private Path gameDir;
    private final Duration timeout = Duration.ofSeconds(5);

    // Collected request information for verification
    private final Map<String, String> capturedRequestHeaders = new HashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private byte[] responseBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        manifestUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        gameDir = tempDir.resolve("game");
        Files.createDirectories(gameDir);
        requestCount.set(0);
        capturedRequestHeaders.clear();
        responseBody = new byte[0];
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private void installSingleContextHandler(String path, int status, byte[] body, String contentType) {
        server.createContext(path, exchange -> {
            incrementAndCapture(exchange);
            sendResponse(exchange, status, body, contentType);
        });
    }

    private void incrementAndCapture(HttpExchange exchange) {
        requestCount.incrementAndGet();
        // Capture first-value of each header for simplicity
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (!values.isEmpty()) {
                capturedRequestHeaders.put(key, values.get(0));
            }
        });
    }

    private static void sendResponse(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        if (body != null && body.length > 0) {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } else {
            exchange.sendResponseHeaders(status, -1);
        }
        exchange.close();
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha512Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            return hex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private Manifest buildManifest(Optional<URI> baseUri, Map<String, Mod> mods) {
        return new Manifest(1, "test-manifest", 1L, Instant.now(),
                Optional.empty(), "1.21.1", baseUri.map(URI::toString), mods);
    }

    private Mod buildMod(String name, boolean required, List<Variant> variants) {
        return new Mod(name, required, Optional.empty(), Optional.empty(), variants);
    }

    private Variant buildVariant(Download download) {
        return new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "mod.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.of(sha512Hex(responseBody))),
                        download));
    }

    private Variant buildManualVariant(String pageUrl, String message) {
        return new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "manual.jar", 100L,
                        new Hashes(Optional.empty(), Optional.of("a".repeat(128))),
                        new ManualDownload(pageUrl, Optional.ofNullable(message))));
    }

    private ScanResult scanResultFor(List<UpdateCandidate> candidates) {
        return new ScanResult(candidates, candidates.size(), 0);
    }

    // -----------------------------------------------------------------
    // hosted relative with baseUrl – header & content verification
    // -----------------------------------------------------------------

    @Test
    void hostedRelativeWithBaseUrlSucceedsAndChecksHeaders() throws Exception {
        responseBody = new byte[1024];
        for (int i = 0; i < responseBody.length; i++) {
            responseBody[i] = (byte) (i % 127);
        }
        installSingleContextHandler("/mod.jar", 200, responseBody, "application/java-archive");

        Mod mod = buildMod("example", true, List.of(buildVariant(new HostedDownload("mod.jar"))));
        Manifest manifest = buildManifest(Optional.of(manifestUri), Map.of("example", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("example", mod, mod.variants().get(0), Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);

        assertEquals(0, result.failed().size());
        assertEquals(0, result.manualUpdates().size());
        assertEquals(1, result.downloaded().size());
        DownloadedArtifact downloaded = result.downloaded().get(0);
        assertEquals("mod.jar", downloaded.fileName());
        assertEquals("1.0", downloaded.version());
        assertEquals("hosted", downloaded.sourceType());
        assertTrue(downloaded.file().toFile().exists());

        String relative = gameDir.relativize(downloaded.file()).toString().replace('\\', '/');
        assertTrue(relative.startsWith(".mc-client-update/downloads/sha512-"));
        assertTrue(relative.endsWith("/mod.jar"));

        byte[] actual = Files.readAllBytes(downloaded.file());
        assertEquals(responseBody.length, actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(responseBody[i], actual[i]);
        }

        assertEquals("application/java-archive", capturedRequestHeaders.get("Accept"));
        assertEquals("identity", capturedRequestHeaders.get("Accept-encoding"));
        assertNotNull(capturedRequestHeaders.get("User-agent"));
    }

    // -----------------------------------------------------------------
    // no baseUrl => resolves against manifest URI
    // -----------------------------------------------------------------

    @Test
    void hostedRelativeWithoutBaseUrlUsesManifestUri() throws Exception {
        responseBody = new byte[512];
        // server handler on same path
        String path = "/relative.jar";
        installSingleContextHandler(path, 200, responseBody, "application/java-archive");

        Mod mod = buildMod("modA", true, List.of(buildVariant(new HostedDownload(path))));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("modA", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("modA", mod, mod.variants().get(0), Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.failed().size());
        assertEquals(1, result.downloaded().size());
        assertTrue(result.downloaded().get(0).file().toFile().exists());
    }

    // -----------------------------------------------------------------
    // direct absolute URL succeeds
    // -----------------------------------------------------------------

    @Test
    void directAbsoluteUrlSucceeds() throws Exception {
        responseBody = new byte[768];
        // Use a separate path to avoid collision
        String directPath = "/direct.jar";
        installSingleContextHandler(directPath, 200, responseBody, "application/java-archive");

        URI directUrl = manifestUri.resolve(directPath);
        DirectDownload direct = new DirectDownload(directUrl.toString(), Optional.empty(),
                Optional.empty(), Optional.empty());
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("2.0", "direct.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()), direct));
        Mod mod = buildMod("directMod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("directMod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("directMod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.failed().size());
        assertEquals(1, result.downloaded().size());
        assertEquals("direct", result.downloaded().get(0).sourceType());
    }

    // -----------------------------------------------------------------
    // manual downloads are collected – no network request
    // -----------------------------------------------------------------

    @Test
    void manualDownloadsAreCollectedAndMerged() throws Exception {
        Variant manualVariantA = buildManualVariant("https://example.com/a", "manual message");
        Variant manualVariantB = buildManualVariant("https://example.com/a", "manual message");
        Mod modA = buildMod("modA", true, List.of(manualVariantA));
        Mod modB = buildMod("modB", true, List.of(manualVariantB));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("modA", modA, "modB", modB));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("modA", modA, manualVariantA, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED),
                new UpdateCandidate("modB", modB, manualVariantB, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.downloaded().size());
        assertEquals(0, result.failed().size());
        assertEquals(1, result.manualUpdates().size()); // merged same key
        ManualUpdate merged = result.manualUpdates().get(0);
        assertEquals(List.of("modA", "modB"), merged.modIds());
        assertEquals("manual.jar", merged.fileName());
        assertEquals("1.0", merged.version());
        assertEquals("https://example.com/a", merged.pageUrl());
        assertEquals("manual message", merged.message());
        assertEquals(0, requestCount.get());
    }

    // -----------------------------------------------------------------
    // duplicate artifact is downloaded only once and modIds are merged/sorted
    // -----------------------------------------------------------------

    @Test
    void duplicateArtifactServesMultipleModsWithSingleRequest() throws Exception {
        responseBody = new byte[256];
        String path = "/shared.jar";
        installSingleContextHandler(path, 200, responseBody, "application/java-archive");

        Variant sharedVariant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "shared.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()),
                        new HostedDownload(path)));
        Mod modX = buildMod("modX", true, List.of(sharedVariant));
        Mod modY = buildMod("modY", true, List.of(sharedVariant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("modX", modX, "modY", modY));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("modX", modX, sharedVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED),
                new UpdateCandidate("modY", modY, sharedVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.failed().size());
        assertEquals(1, result.downloaded().size());
        DownloadedArtifact downloaded = result.downloaded().get(0);
        assertEquals(List.of("modX", "modY"), downloaded.modIds());
        assertEquals("hosted", downloaded.sourceType());
        assertEquals(1, requestCount.get());
    }

    // -----------------------------------------------------------------
    // failure category: HTTP status 404
    // -----------------------------------------------------------------

    @Test
    void http404GeneratesFailureAndDoesNotBlockLater() throws Exception {
        // First artifact returns 404, second succeeds
        responseBody = new byte[100];
        String successPath = "/ok.jar";
        server.createContext("/missing.jar", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        installSingleContextHandler(successPath, 200, responseBody, "application/java-archive");

        Variant missingVariant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "missing.jar", 100L,
                        new Hashes(Optional.empty(), Optional.of("a".repeat(128))),
                        new HostedDownload("/missing.jar")));
        Variant okVariant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("2.0", "ok.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()),
                        new HostedDownload(successPath)));
        Mod modFail = buildMod("modFail", true, List.of(missingVariant));
        Mod modOk = buildMod("modOk", true, List.of(okVariant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("modFail", modFail, "modOk", modOk));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("modFail", modFail, missingVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED),
                new UpdateCandidate("modOk", modOk, okVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(1, result.failed().size());
        assertEquals(1, result.downloaded().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals(List.of("modFail"), failure.modIds());
        assertEquals("missing.jar", failure.fileName());
        assertEquals("HTTP_STATUS", failure.category());
        assertEquals("HTTP 404", failure.message());

        // Subsequent success should be present
        assertEquals("ok.jar", result.downloaded().get(0).fileName());
    }

    // -----------------------------------------------------------------
    // size mismatch
    // -----------------------------------------------------------------

    @Test
    void sizeMismatchCausesFailure() throws Exception {
        responseBody = new byte[50];
        installSingleContextHandler("/wrong.jar", 200, responseBody, "application/java-archive");
        int declaredSize = 200;
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "wrong.jar", declaredSize,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()),
                        new HostedDownload("/wrong.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(1, result.failed().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals("SIZE_MISMATCH", failure.category());
        Path stagingBase = gameDir.resolve(".mc-client-update/downloads");
        if (Files.exists(stagingBase)) {
            try (var stream = Files.walk(stagingBase)) {
                java.util.List<Path> regularFiles = stream.filter(Files::isRegularFile).toList();
                for (Path f : regularFiles) {
                    assertFalse(f.getFileName().toString().endsWith(".part"),
                            "Unexpected .part file: " + f);
                }
                assertTrue(regularFiles.isEmpty(), "No ordinary files should remain in " + stagingBase);
            }
        }
    }

    // -----------------------------------------------------------------
    // hash mismatch
    // -----------------------------------------------------------------

    @Test
    void oversizedResponseLeadsToSizeMismatchAndLeavesNoPart() throws Exception {
        int extra = 10;
        int declaredSize = 120;
        byte[] oversized = new byte[declaredSize + extra];
        for (int i = 0; i < oversized.length; i++) {
            oversized[i] = (byte) (i % 127);
        }
        installSingleContextHandler("/oversized.jar", 200, oversized, "application/java-archive");

        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "oversized.jar", declaredSize,
                        new Hashes(Optional.empty(), Optional.of("c".repeat(128))),
                        new HostedDownload("/oversized.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);

        assertEquals(1, result.failed().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals("SIZE_MISMATCH", failure.category());

        // staging must contain no ordinary files and no .part files
        Path stagingBase = gameDir.resolve(".mc-client-update/downloads");
        try (var stream = Files.walk(stagingBase)) {
            List<Path> regularFiles = stream.filter(Files::isRegularFile).toList();
            for (Path f : regularFiles) {
                assertFalse(f.getFileName().toString().endsWith(".part"),
                        "Unexpected .part file: " + f);
            }
            assertTrue(regularFiles.isEmpty(),
                    "No ordinary files should remain in " + stagingBase);
        }
    }

    @Test
    void hashMismatchCausesFailure() throws Exception {
        responseBody = new byte[60];
        installSingleContextHandler("/hash.jar", 200, responseBody, "application/java-archive");
        // declare a hash that does not match the body
        String wrongHash = "b".repeat(64);
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "hash.jar", responseBody.length,
                        new Hashes(Optional.of(wrongHash), Optional.empty()), new HostedDownload("/hash.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(1, result.failed().size());
        assertEquals("HASH_MISMATCH", result.failed().get(0).category());
    }

    // -----------------------------------------------------------------
    // invalid file name
    // -----------------------------------------------------------------

    @Test
    void invalidFileNameYieldsCategory() throws Exception {
        String invalidName = "evil/../../../file.jar";
        responseBody = new byte[200];
        installSingleContextHandler("/evil.jar", 200, responseBody, "application/java-archive");
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", invalidName, responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()),
                        new HostedDownload("/evil.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(1, result.failed().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals("INVALID_FILE_NAME", failure.category());
        assertEquals("Invalid file name", failure.message());
        assertEquals(0, requestCount.get());
    }

    // -----------------------------------------------------------------
    // relative direct URL fails
    // -----------------------------------------------------------------

    @Test
    void hostedDownloadFailureDoesNotBlockFollowingDirectDownloads() throws Exception {
        // Hosted "bad.jar" returns 404, direct "ok.jar" returns 200.
        server.createContext("/bad.jar", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        responseBody = new byte[128];
        installSingleContextHandler("/ok.jar", 200, responseBody, "application/java-archive");

        Variant badVariant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "bad.jar", 100L,
                        new Hashes(Optional.empty(), Optional.of("b".repeat(128))),
                        new HostedDownload("/bad.jar")));
        Variant okVariant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("2.0", "ok.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.of(sha512Hex(responseBody))),
                        new HostedDownload("/ok.jar")));

        // mod "a" (bad) comes before "b" (good) due to alphabetical ordering
        Mod modBad = buildMod("a", true, List.of(badVariant));
        Mod modGood = buildMod("b", true, List.of(okVariant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("a", modBad, "b", modGood));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("a", modBad, badVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED),
                new UpdateCandidate("b", modGood, okVariant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);

        assertEquals(1, result.failed().size());
        assertEquals(1, result.downloaded().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals(List.of("a"), failure.modIds());
        assertEquals("HTTP_STATUS", failure.category());
        assertEquals("HTTP 404", failure.message());

        DownloadedArtifact success = result.downloaded().get(0);
        assertEquals("ok.jar", success.fileName());
        assertTrue(success.file().toFile().exists());
    }

    @Test
    void relativeDirectUrlFails() throws Exception {
        responseBody = new byte[100];
        String relativePath = "/relative-direct.jar";
        installSingleContextHandler(relativePath, 200, responseBody, "application/java-archive");
        DirectDownload direct = new DirectDownload(relativePath, Optional.empty(), Optional.empty(), Optional.empty());
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "rdirect.jar", responseBody.length,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()), direct));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(1, result.failed().size());
        DownloadFailure failure = result.failed().get(0);
        assertEquals("IO_ERROR", failure.category());
    }

    // -----------------------------------------------------------------
    // cache reuse – no network request
    // -----------------------------------------------------------------

    @Test
    void manualDownloadsWithSamePageUrlAndDifferentMessagesAreNotMerged() throws Exception {
        Variant manualVariantX = buildManualVariant("https://example.com/samePage", "msg1");
        Variant manualVariantY = buildManualVariant("https://example.com/samePage", "msg2");
        Mod modX = buildMod("modX", true, List.of(manualVariantX));
        Mod modY = buildMod("modY", true, List.of(manualVariantY));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("modX", modX, "modY", modY));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("modX", modX, manualVariantX, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED),
                new UpdateCandidate("modY", modY, manualVariantY, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.downloaded().size());
        assertEquals(0, result.failed().size());
        assertEquals(2, result.manualUpdates().size()); // not merged
        ManualUpdate first = result.manualUpdates().get(0);
        ManualUpdate second = result.manualUpdates().get(1);
        assertEquals(List.of("modX"), first.modIds());
        assertEquals(List.of("modY"), second.modIds());
        assertEquals("https://example.com/samePage", first.pageUrl());
        assertEquals("https://example.com/samePage", second.pageUrl());
        assertEquals("msg1", first.message());
        assertEquals("msg2", second.message());
        assertEquals(0, requestCount.get());
    }

    @Test
    void cacheValidReusesArtifactWithoutNetworkRequest() throws Exception {
        responseBody = new byte[512];
        String sha256 = sha256Hex(responseBody);
        String sha512 = sha512Hex(responseBody);
        Path stagingBase = gameDir.resolve(".mc-client-update/downloads");
        Path destDir = stagingBase.resolve("sha512-" + sha512);
        Files.createDirectories(destDir);
        Path cachedFile = destDir.resolve("mod.jar");
        Files.write(cachedFile, responseBody);

        // install handler but expect zero invocations
        server.createContext("/mod.jar", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "mod.jar", responseBody.length,
                        new Hashes(Optional.of(sha256), Optional.of(sha512)),
                        new HostedDownload("mod.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.of(manifestUri), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.failed().size());
        assertEquals(1, result.downloaded().size());
        assertEquals(0, requestCount.get());
    }

    // -----------------------------------------------------------------
    // bad cache content is re-downloaded
    // -----------------------------------------------------------------

    @Test
    void badCacheIsDeletedAndRefetched() throws Exception {
        responseBody = new byte[200];
        String sha512 = sha512Hex(responseBody);
        Path stagingBase = gameDir.resolve(".mc-client-update/downloads");
        Path destDir = stagingBase.resolve("sha512-" + sha512);
        Files.createDirectories(destDir);
        Path cachedFile = destDir.resolve("mod.jar");
        // write wrong content
        Files.write(cachedFile, new byte[]{1, 2, 3});

        installSingleContextHandler("/mod.jar", 200, responseBody, "application/java-archive");

        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "mod.jar", responseBody.length,
                        new Hashes(Optional.empty(), Optional.of(sha512)),
                        new HostedDownload("mod.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.of(manifestUri), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.failed().size());
        assertEquals(1, result.downloaded().size());
        assertEquals(1, requestCount.get());
        byte[] actual = Files.readAllBytes(result.downloaded().get(0).file());
        assertEquals(responseBody.length, actual.length);
    }

    // -----------------------------------------------------------------
    // no .part leftovers after failure
    // -----------------------------------------------------------------

    @Test
    void deleteOnlyCandidatesYieldsNothing() throws Exception {
        responseBody = new byte[100];
        server.createContext("/mod.jar", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        Mod delMod = new Mod("del-mod", false, Optional.empty(),
                Optional.empty(), List.of(), ModAction.DELETE);
        Manifest manifest = buildManifest(Optional.empty(),
                Map.of("del-mod", delMod));
        Path dummyJar = gameDir.resolve("mods").resolve("del-mod.jar");
        Files.createDirectories(dummyJar.getParent());
        Files.write(dummyJar, responseBody);
        InstalledMod installed = new InstalledMod(
                "del-mod", "0.9", dummyJar);
        UpdateCandidate delCand = new UpdateCandidate(
                "del-mod", delMod, null, Optional.of(installed),
                UpdateCandidate.Reason.DELETE);
        ScanResult scan = scanResultFor(List.of(delCand));
        DownloadBatchResult result = ArtifactDownloader.downloadBatch(
                manifest, manifestUri, scan, gameDir, timeout, timeout);
        assertEquals(0, result.downloaded().size());
        assertEquals(0, result.failed().size());
        assertEquals(0, result.manualUpdates().size());
    }

    @Test
    void noPartFilesRemainAfterFailure() throws Exception {
        responseBody = new byte[50];
        installSingleContextHandler("/mod.jar", 200, responseBody, "application/java-archive");
        // size mismatch will cause failure and cleanup
        Variant variant = new Variant(new Selector(Optional.empty(), Optional.empty(), Optional.empty()), 0,
                new Artifact("1.0", "mod.jar", 9999L,
                        new Hashes(Optional.of(sha256Hex(responseBody)), Optional.empty()),
                        new HostedDownload("mod.jar")));
        Mod mod = buildMod("mod", true, List.of(variant));
        Manifest manifest = buildManifest(Optional.empty(), Map.of("mod", mod));
        ScanResult scan = scanResultFor(List.of(
                new UpdateCandidate("mod", mod, variant, Optional.empty(),
                        UpdateCandidate.Reason.MISSING_REQUIRED)));

        ArtifactDownloader.downloadBatch(manifest, manifestUri, scan, gameDir, timeout, timeout);
        Path stagingBase = gameDir.resolve(".mc-client-update/downloads");
        try (var stream = Files.walk(stagingBase)) {
            java.util.List<Path> partFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".part"))
                    .toList();
            assertTrue(partFiles.isEmpty(), "Unexpected .part files: " + partFiles);
        }
    }
}
