package io.github.henryxjh.mcclientupdate.ui;

import java.util.List;

import io.github.henryxjh.mcclientupdate.download.DownloadFailure;
import io.github.henryxjh.mcclientupdate.download.ManualUpdate;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.update.install.InstallFailure;
import io.github.henryxjh.mcclientupdate.update.install.InstalledArtifact;

/**
 * Pure-text formatting and logging util for attention text, without any Swing dependency.
 */
public final class UpdateAttentionMessage {

    public static final String REPORT_PATH = "config/mc-client-update-download-report.json";

    private UpdateAttentionMessage() {}

    public static String formatMessage(
            List<ManualUpdate> manualUpdates,
            List<DownloadFailure> downloadFailures,
            List<InstallFailure> installFailures,
            List<InstalledArtifact> installedArtifacts) {
        StringBuilder sb = new StringBuilder();

        if (!manualUpdates.isEmpty() || !downloadFailures.isEmpty()
                || !installFailures.isEmpty() || !installedArtifacts.isEmpty()) {
            sb.append("Close this dialog to exit the game.\n\n");
        }

        if (!installedArtifacts.isEmpty()) {
            sb.append("Installed updates\n\n");
            for (InstalledArtifact art : installedArtifacts) {
                sb.append("modIds: ").append(art.modIds()).append('\n');
                sb.append("  fileName: ").append(art.fileName()).append('\n');
                sb.append("  version: ").append(art.version()).append('\n');
                sb.append("  sourceType: ").append(art.sourceType()).append('\n');
                sb.append("  action: ").append(art.action()).append('\n');
                sb.append("  installedRelativePath: ").append(art.installedRelativePath()).append('\n');
                if (art.backupRelativePath().isPresent()) {
                    sb.append("  backupRelativePath: ").append(art.backupRelativePath().get()).append('\n');
                }
                sb.append("\n");
            }
        }

        if (!manualUpdates.isEmpty()) {
            sb.append("Manual updates\n\n");
            for (ManualUpdate mu : manualUpdates) {
                sb.append("modIds: ").append(mu.modIds()).append('\n');
                sb.append("  fileName: ").append(mu.fileName()).append('\n');
                sb.append("  version: ").append(mu.version()).append('\n');
                sb.append("  pageUrl: ").append(mu.pageUrl()).append('\n');
                sb.append("  message: ").append(mu.message()).append("\n\n");
            }
        }

        if (!downloadFailures.isEmpty()) {
            sb.append("Download failures\n\n");
            for (DownloadFailure df : downloadFailures) {
                sb.append("modIds: ").append(df.modIds()).append('\n');
                sb.append("  fileName: ").append(df.fileName()).append('\n');
                sb.append("  version: ").append(df.version()).append('\n');
                sb.append("  sourceType: ").append(df.sourceType()).append('\n');
                sb.append("  category: ").append(df.category()).append('\n');
                sb.append("  message: ").append(df.message()).append("\n\n");
            }
        }

        if (!installFailures.isEmpty()) {
            sb.append("Install failures\n\n");
            for (InstallFailure f : installFailures) {
                sb.append("modIds: ").append(f.modIds()).append('\n');
                sb.append("  fileName: ").append(f.fileName()).append('\n');
                sb.append("  version: ").append(f.version()).append('\n');
                sb.append("  sourceType: ").append(f.sourceType()).append('\n');
                sb.append("  category: ").append(f.category()).append('\n');
                sb.append("  message: ").append(f.message()).append("\n\n");
            }
        }

        return sb.toString();
    }

    public static void logToGameLog(String attentionText, PlatformContext platform) {
        platform.log("--- MC Client Update attention begin ---");
        attentionText.lines().forEach(platform::log);
        platform.log("--- MC Client Update attention end ---");
    }

    public static String restartRequiredMessage(String attentionText) {
        return "MC Client Update: Installation succeeded; restart required."
                + " See " + REPORT_PATH + " for details.\n"
                + attentionText;
    }

    public static String downloadFailureMessage(String attentionText) {
        return "MC Client Update: No updates installed (failures/manual updates remain)."
                + " See " + REPORT_PATH + " for details.\n"
                + attentionText;
    }
}
