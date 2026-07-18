package io.github.henryxjh.mcclientupdate.ui;

import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import io.github.henryxjh.mcclientupdate.download.DownloadFailure;
import io.github.henryxjh.mcclientupdate.download.ManualUpdate;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.update.install.InstallFailure;

/**
 * Shows a Swing dialog listing manual updates, download failures, and install failures.
 * The dialog must be closed before the game can proceed to exit.
 */
public final class UpdateAttentionDialog {

    private UpdateAttentionDialog() {
    }

    /**
     * Displays the attention dialog if at least one of the three lists is non-empty.
     * Blocks until the user closes the dialog, then returns.
     */
    public static void showIfNeeded(
            List<ManualUpdate> manualUpdates,
            List<DownloadFailure> downloadFailures,
            List<InstallFailure> installFailures,
            PlatformContext platform) {
        if (manualUpdates.isEmpty() && downloadFailures.isEmpty() && installFailures.isEmpty()) {
            return;
        }

        Runnable showRunnable = () -> {
            try {
                String text = formatMessage(manualUpdates, downloadFailures, installFailures);
                JTextArea area = new JTextArea(text, 30, 80);
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setCaretPosition(0);
                JScrollPane scrollPane = new JScrollPane(area);
                JOptionPane.showMessageDialog(
                        null,
                        scrollPane,
                        "MC Client Update - Action Required",
                        JOptionPane.WARNING_MESSAGE);
            } catch (Throwable t) {
                platform.log("Unable to show update attention dialog: " + t);
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showRunnable.run();
            } else {
                SwingUtilities.invokeAndWait(showRunnable);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            platform.log("Update attention dialog interrupted");
        } catch (Throwable t) {
            platform.log("Unknown error showing update attention dialog: " + t);
        }
    }

    // package-private for testing
    static String formatMessage(
            List<ManualUpdate> manualUpdates,
            List<DownloadFailure> downloadFailures,
            List<InstallFailure> installFailures) {
        StringBuilder sb = new StringBuilder();

        if (!manualUpdates.isEmpty() || !downloadFailures.isEmpty() || !installFailures.isEmpty()) {
            sb.append("Close this dialog to exit or restart the game.\n\n");
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
}
