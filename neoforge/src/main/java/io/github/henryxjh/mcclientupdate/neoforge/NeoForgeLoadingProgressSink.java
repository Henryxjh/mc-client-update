package io.github.henryxjh.mcclientupdate.neoforge;

import io.github.henryxjh.mcclientupdate.ui.DisplaySnapshot;
import io.github.henryxjh.mcclientupdate.ui.LoadingProgressSink;
import java.util.List;
import java.util.Locale;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

/**
 * Sends update progress to NeoForge's native loading progress bars.
 */
public final class NeoForgeLoadingProgressSink implements LoadingProgressSink {
    private static final int BYTE_STEPS = 1000;
    private static final int MAX_LABEL_LENGTH = 80;

    private ProgressMeter totalBar;
    private ProgressMeter currentBar;
    private DisplaySnapshot.Phase currentPhase;
    private long phaseTotalBytes;
    private int installTotalItems;
    private String currentModIds = "";
    private String currentSpeedText = "";
    private String installAction = "";

    @Override
    public void phaseStart(DisplaySnapshot.Phase phase, int totalItems, long totalBytes) {
        close();
        currentPhase = phase;
        phaseTotalBytes = totalBytes;
        installTotalItems = Math.max(totalItems, 1);
        currentModIds = "";
        currentSpeedText = "";
        installAction = "";

        if (phase == DisplaySnapshot.Phase.DOWNLOAD) {
            /*
             * NeoForge only renders the first two progress bars. prependProgressBar
             * inserts at the front, so create the current-item bar first and the
             * total bar second to display total above current.
             */
            currentBar = StartupNotificationManager.prependProgressBar("MCU Current", BYTE_STEPS);
            totalBar = StartupNotificationManager.prependProgressBar(
                    "MCU Downloading 0/" + Math.max(totalItems, 0), totalBytes > 0 ? BYTE_STEPS : 0);
        } else {
            totalBar = StartupNotificationManager.prependProgressBar("MCU Installing", installTotalItems);
        }
    }

    @Override
    public void itemStart(String name, long totalBytes, String extra) {
        currentModIds = "";
        currentSpeedText = "";
        if (currentPhase == DisplaySnapshot.Phase.INSTALL) {
            installAction = actionOnly(extra);
            updateInstallLabel();
        }
    }

    @Override
    public void progressTick(int itemIndex, int totalItems, long itemTotalBytes, long itemReadBytes,
                             String speedText, long phaseDoneBytes, long phaseTotalBytes) {
        if (currentPhase != DisplaySnapshot.Phase.DOWNLOAD) {
            return;
        }
        currentSpeedText = speedText == null ? "" : speedText;

        if (totalBar != null) {
            if (phaseTotalBytes > 0) {
                totalBar.setAbsolute(toProgressSteps(phaseDoneBytes, phaseTotalBytes));
                totalBar.label(label(String.format(Locale.ROOT,
                        "MCU Downloading %d/%d %s/%s",
                        itemIndex,
                        totalItems,
                        formatBytes(phaseDoneBytes),
                        formatBytes(phaseTotalBytes))));
            } else {
                totalBar.label(label(String.format(Locale.ROOT,
                        "MCU Downloading %d/%d %s",
                        itemIndex,
                        totalItems,
                        formatBytes(phaseDoneBytes))));
            }
        }

        if (currentBar != null) {
            if (itemTotalBytes > 0) {
                currentBar.setAbsolute(toProgressSteps(itemReadBytes, itemTotalBytes));
            }
            updateCurrentDownloadLabel();
        }
    }

    @Override
    public void itemDone(boolean success, String message) {
        // The next progress tick or final completion updates visible labels.
    }

    @Override
    public void phaseDone(int ok, int failed, int manual) {
        // Completion is rendered by showCompletion.
    }

    @Override
    public void installCountProgress(int doneCount, int totalItems) {
        if (currentPhase != DisplaySnapshot.Phase.INSTALL || totalBar == null) {
            return;
        }
        totalBar.setAbsolute(Math.max(0, Math.min(doneCount, installTotalItems)));
        updateInstallLabel();
    }

    @Override
    public void reportModIds(String joinedModIds) {
        currentModIds = normalizeModIds(joinedModIds);
        if (currentPhase == DisplaySnapshot.Phase.DOWNLOAD) {
            updateCurrentDownloadLabel();
        } else if (currentPhase == DisplaySnapshot.Phase.INSTALL) {
            updateInstallLabel();
        }
    }

    @Override
    public void reportDownloadUrl(String url) {
        // File names and URLs are intentionally not shown in NeoForge labels.
    }

    @Override
    public void showCompletion(List<String> installed, List<String> manual, List<String> failed) {
        // Discard any previous bars without interfering with the final close.
        completeCurrentBar();
        if (totalBar != null) {
            totalBar.complete();
            totalBar = null;
        }

        int installedCount = installed == null ? 0 : installed.size();
        int manualCount = manual == null ? 0 : manual.size();
        int failedCount = failed == null ? 0 : failed.size();

        // Create the result bar (bottom) first, then the restart message bar (top)
        // because prependProgressBar inserts at the front of the rendering list.
        ProgressMeter resultBar = StartupNotificationManager.prependProgressBar(
                "MCU Result", 1);
        resultBar.label(label(String.format(Locale.ROOT,
                "MCU Done OK=%d MANUAL=%d FAIL=%d",
                installedCount, manualCount, failedCount)));
        resultBar.setAbsolute(resultBar.steps());

        ProgressMeter restartBar = StartupNotificationManager.prependProgressBar(
                "MCU Restart", 1);
        restartBar.label(label("Restart your game to apply updates"));
        restartBar.setAbsolute(restartBar.steps());

        // Keep references so close() can clean them up.
        totalBar = resultBar;
        currentBar = restartBar;
    }

    @Override
    public void close() {
        if (totalBar != null) {
            totalBar.complete();
            totalBar = null;
        }
        completeCurrentBar();
    }

    private void completeCurrentBar() {
        if (currentBar != null) {
            currentBar.complete();
            currentBar = null;
        }
    }

    private void updateCurrentDownloadLabel() {
        if (currentBar == null) {
            return;
        }
        StringBuilder text = new StringBuilder("MCU");
        if (!currentModIds.isEmpty()) {
            text.append(' ').append(currentModIds);
        }
        if (!currentSpeedText.isEmpty()) {
            text.append(' ').append(currentSpeedText);
        }
        currentBar.label(label(text.toString()));
    }

    private void updateInstallLabel() {
        if (totalBar == null) {
            return;
        }
        StringBuilder text = new StringBuilder("MCU Installing");
        if (!installAction.isEmpty()) {
            text.append(' ').append(installAction);
        }
        if (!currentModIds.isEmpty()) {
            text.append(' ').append(currentModIds);
        }
        totalBar.label(label(text.toString()));
    }

    private static int toProgressSteps(long current, long total) {
        if (total <= 0) {
            return 0;
        }
        long clamped = Math.max(0, Math.min(current, total));
        return (int) Math.min(BYTE_STEPS, clamped * BYTE_STEPS / total);
    }

    private static String actionOnly(String extra) {
        if (extra == null || extra.isBlank()) {
            return "";
        }
        int comma = extra.indexOf(',');
        String value = comma >= 0 ? extra.substring(0, comma) : extra;
        return ascii(value.trim().toUpperCase(Locale.ROOT));
    }

    private static String normalizeModIds(String joinedModIds) {
        if (joinedModIds == null || joinedModIds.isBlank()) {
            return "";
        }
        return ascii(joinedModIds.replace(", ", ",").trim());
    }

    private static String label(String value) {
        String ascii = ascii(value);
        if (ascii.length() <= MAX_LABEL_LENGTH) {
            return ascii;
        }
        return ascii.substring(0, MAX_LABEL_LENGTH - 3) + "...";
    }

    private static String ascii(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 32 && c <= 126 ? c : '?');
        }
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
