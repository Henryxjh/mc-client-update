package io.github.henryxjh.mcclientupdate;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.github.henryxjh.mcclientupdate.cleanup.CleanupResult;
import io.github.henryxjh.mcclientupdate.cleanup.UpdateCleanup;
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
import io.github.henryxjh.mcclientupdate.manifest.MinecraftVersionValidator;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.platform.RuntimePlatform;
import io.github.henryxjh.mcclientupdate.platform.UpdateTarget;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.ModUpdateScanner;
import io.github.henryxjh.mcclientupdate.scan.ScanResult;
import io.github.henryxjh.mcclientupdate.scan.UpdateCandidate;
import io.github.henryxjh.mcclientupdate.ui.UpdateAttentionDialog;
import io.github.henryxjh.mcclientupdate.ui.UpdateAttentionMessage;
import io.github.henryxjh.mcclientupdate.ui.UpdateProgressDisplay;
import io.github.henryxjh.mcclientupdate.update.install.ArtifactInstaller;
import io.github.henryxjh.mcclientupdate.update.install.InstallBatchResult;
import io.github.henryxjh.mcclientupdate.update.install.InstallFailure;
import io.github.henryxjh.mcclientupdate.update.install.InstalledArtifact;
import java.util.Set;

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

        // Validate that the manifest Minecraft version matches the client's Minecraft version
        String currentMcVersion = platform.minecraftVersion();
        try {
            MinecraftVersionValidator.validateMinecraftVersion(manifest.minecraftVersion(), currentMcVersion);
        } catch (ManifestFetchException e) {
            platform.log("Manifest Minecraft version mismatch: " + e.getMessage());
            if ("ignore".equals(config.minecraftVersionMismatchAction())) {
                platform.log("minecraftVersionMismatchAction=ignore; skipping update");
                return;
            }
            throw e;
        }
        platform.log("Current Minecraft version: " + currentMcVersion
                + ", manifest Minecraft version: " + manifest.minecraftVersion());

        Path modsDirectory = platform.gameDirectory().resolve("mods");
        List<InstalledMod> installedMods = platform.installedMods();
        Set<String> protectedDeleteModIds = Set.of(platform.selfModId());
        ScanResult scanResult = ModUpdateScanner.scan(
                manifest,
                target,
                modsDirectory,
                installedMods,
                protectedDeleteModIds);

        platform.log("Installed managed mods: " + scanResult.installedManagedModCount()
                + "; update candidates: " + scanResult.candidates().size());

        for (UpdateCandidate candidate : scanResult.candidates()) {
            String version;
            if (candidate.reason() == UpdateCandidate.Reason.DELETE) {
                version = "<delete>";
            } else {
                version = candidate.selectedVariant().artifact().version();
            }
            platform.log("  " + candidate.modId()
                    + " reason=" + candidate.reason()
                    + " targetVersion=" + version);
        }

        UpdateProgressDisplay.start(platform);
        DownloadBatchResult batchResult;
        InstallBatchResult installResult;
        try {
            try {
                URI manifestUri = config.manifestUri().orElseThrow();
                batchResult = ArtifactDownloader.downloadBatch(
                        manifest, manifestUri, scanResult, platform.gameDirectory(),
                        config.connectTimeout(), config.readTimeout());
            } catch (Exception e) {
                platform.log("Download phase failed: " + e.getMessage());
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new DownloadException("Download phase failed", e);
            }

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

        try {
            installResult = ArtifactInstaller.installBatch(manifest, scanResult, batchResult, platform.gameDirectory());
        } catch (Exception e) {
            platform.log("Install phase failed: " + e.getMessage());
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new DownloadException("Install phase failed", e);
        }

        platform.log("Installed: " + installResult.installed().size()
                + " failed: " + installResult.failures().size());
        for (InstalledArtifact installed : installResult.installed()) {
            platform.log("  installed " + installed.fileName() + " modIds=" + installed.modIds()
                    + " action=" + installed.action() + " path=" + installed.installedRelativePath());
        }
        for (InstallFailure failure : installResult.failures()) {
            platform.log("  INSTALL FAILED " + failure.fileName() + " modIds=" + failure.modIds()
                    + " category=" + failure.category() + " message=" + failure.message());
        }

        DownloadReportWriter.writeFullReport(batchResult, installResult, platform.gameDirectory());

        // ---- cleanup stale backups and download cache ----
        int backupDays = config.cleanupBackupsAfterDays();
        int cacheDays = config.cleanupDownloadCacheAfterDays();
        Duration backupRetention = backupDays > 0
                ? Duration.ofDays(backupDays) : Duration.ZERO;
        Duration cacheRetention = cacheDays > 0
                ? Duration.ofDays(cacheDays) : Duration.ZERO;

        if (backupRetention.isZero() && cacheRetention.isZero()) {
            platform.log("Cleanup disabled (both retention days set to 0)");
        } else {
            try {
                CleanupResult cleanupResult = UpdateCleanup.cleanup(
                        platform.gameDirectory(), backupRetention, cacheRetention);
                platform.log("Cleanup completed: "
                        + "deleted backup files=" + cleanupResult.deletedBackupFiles()
                        + ", deleted cache files=" + cleanupResult.deletedCacheFiles()
                        + ", deleted cache directories=" + cleanupResult.deletedCacheDirectories()
                        + ", failures=" + cleanupResult.failureCount());
            } catch (Exception e) {
                platform.log("Cleanup failed: " + e.getMessage());
            }
        }
        // ---------------------------------------------------
        } finally {
            UpdateProgressDisplay.stop();
        }

        String attentionText = UpdateAttentionMessage.formatMessage(
                batchResult.manualUpdates(),
                batchResult.failed(),
                installResult.failures(),
                installResult.installed());

        // Always write attention text to game log (no Swing dependency)
        UpdateAttentionMessage.logToGameLog(attentionText, platform);

        // Attempt to show Swing dialog (catches own exceptions internally)
        try {
            UpdateAttentionDialog.showTextIfNeeded(attentionText, platform);
        } catch (LinkageError | RuntimeException e) {
            platform.log("Unable to show update attention dialog: " + e);
        }

        boolean anyInstalled = !installResult.installed().isEmpty();
        boolean anyFailureOrManual = !batchResult.failed().isEmpty()
                || !batchResult.manualUpdates().isEmpty()
                || !installResult.failures().isEmpty();

        if (anyInstalled) {
            platform.log("Installation succeeded; restart required.");
            throw new RestartRequiredException(
                    UpdateAttentionMessage.restartRequiredMessage(attentionText));
        } else if (anyFailureOrManual) {
            throw new DownloadException(
                    UpdateAttentionMessage.downloadFailureMessage(attentionText));
        }
    }
}
