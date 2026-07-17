package io.github.henryxjh.mcclientupdate;

import io.github.henryxjh.mcclientupdate.config.ClientUpdateConfig;
import io.github.henryxjh.mcclientupdate.download.ArtifactDownloader;
import io.github.henryxjh.mcclientupdate.download.DownloadBatchResult;
import io.github.henryxjh.mcclientupdate.download.DownloadedArtifact;
import io.github.henryxjh.mcclientupdate.download.DownloadException;
import io.github.henryxjh.mcclientupdate.download.DownloadFailure;
import io.github.henryxjh.mcclientupdate.download.DownloadReportWriter;
import io.github.henryxjh.mcclientupdate.download.ManualUpdate;
import io.github.henryxjh.mcclientupdate.manifest.ClientUpdateManifestFetcher;
import io.github.henryxjh.mcclientupdate.manifest.Manifest;
import io.github.henryxjh.mcclientupdate.manifest.ManifestFetchException;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.platform.RuntimePlatform;
import io.github.henryxjh.mcclientupdate.platform.UpdateTarget;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.ModUpdateScanner;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class ClientUpdateBootstrap {

    private ClientUpdateBootstrap() {
    }

    public static void start(PlatformContext platform) {
        Objects.requireNonNull(platform, "platform");
        UpdateTarget target = UpdateTarget.detect(platform);
        platform.log("MC Client Update initialized on " + platform.loaderName()
                + "; target=" + target.classifier());

        RuntimePlatform runtime = target.platform();
        platform.log("Runtime platform detected as " + runtime.classifier()
                + " from os.name=" + runtime.rawOsName()
                + ", os.version=" + runtime.rawOsVersion()
                + ", os.arch=" + runtime.rawOsArch());

        ClientUpdateConfig config = ClientUpdateConfig.load(platform.gameDirectory());
        if (!config.updatesEnabled()) {
            platform.log("Update checking is disabled; configure config/" + ClientUpdateConfig.FILE_NAME);
            return;
        }
        platform.log("Update manifest endpoint=" + config.redactedManifestEndpoint()
                + "; connectTimeout=" + config.connectTimeout().toSeconds() + "s"
                + "; readTimeout=" + config.readTimeout().toSeconds() + "s");

        Manifest manifest;
        try {
            manifest = ClientUpdateManifestFetcher.fetchManifest(
                    config.manifestUri().orElseThrow(),
                    config.connectTimeout(),
                    config.readTimeout());
        } catch (ManifestFetchException e) {
            platform.log("Mandatory update check failed: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            platform.log("Unexpected error during update check: " + e.getMessage());
            throw new RuntimeException("Failed to perform mandatory update check", e);
        }

        platform.log("Update manifest loaded: manifestId=" + manifest.manifestId()
                + ", revision=" + manifest.revision()
                + ", mods=" + manifest.modCount());

        Path modsDirectory = platform.gameDirectory().resolve("mods");
        List<InstalledMod> installedMods = platform.installedMods();
        ScanResult scanResult = ModUpdateScanner.scan(manifest, target, modsDirectory, installedMods);

        platform.log("Installed managed mods: " + scanResult.installedManagedModCount()
                + "; update candidates: " + scanResult.candidates().size());

        for (UpdateCandidate candidate : scanResult.candidates()) {
            platform.log("  " + candidate.modId()
                    + " reason=" + candidate.reason()
                    + " targetVersion=" + candidate.selectedVariant().artifact().version());
        }

        try {
            URI manifestUri = config.manifestUri().orElseThrow();
            DownloadBatchResult batchResult = ArtifactDownloader.downloadBatch(
                    manifest, manifestUri, scanResult, platform.gameDirectory(),
                    config.connectTimeout(), config.readTimeout());

            platform.log("Downloaded: " + batchResult.downloaded().size()
                    + " failed: " + batchResult.failed().size()
                    + " manual: " + batchResult.manualUpdates().size());
            for (DownloadedArtifact downloaded : batchResult.downloaded()) {
                platform.log("  downloaded " + downloaded.fileName() + " modIds=" + downloaded.modIds());
            }
            for (DownloadFailure failure : batchResult.failed()) {
                platform.log("  FAILED " + failure.fileName() + " modIds=" + failure.modIds()
                        + " category=" + failure.category() + " message=" + failure.message());
            }
            for (ManualUpdate manual : batchResult.manualUpdates()) {
                platform.log("  MANUAL " + manual.fileName() + " modIds=" + manual.modIds()
                        + " pageUrl=" + manual.pageUrl() + " msg=" + manual.message());
            }
            DownloadReportWriter.writeReport(batchResult, platform.gameDirectory());
        } catch (Exception e) {
            platform.log("Download phase failed: " + e.getMessage());
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new DownloadException("Download phase failed", e);
        }
    }
}
