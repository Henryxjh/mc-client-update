package io.github.henryxjh.mcclientupdate.ui;

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the current progress display state.
 * Updated by {@link UpdateProgressDisplay} via volatile write,
 * read by platform-specific render integrations.
 */
public final class DisplaySnapshot {

    public enum Phase { DOWNLOAD, INSTALL, COMPLETE }

    private final Phase phase;
    private final int itemIndex;
    private final int totalItems;
    private final long phaseStartMs;
    private final String currentItemName;
    private final long currentItemTotalBytes;
    private final long currentBytesRead;
    private final String currentExtra;
    private final String currentModIds;
    private final String currentUrl;
    private final String speedText;
    private final int doneCount;
    private final int failCount;
    private final int manualCount;
    private final long phaseTotalBytes;
    private final long phaseDownloadedBytes;
    private final List<ItemResult> recentResults;

    // Completion summary fields
    private final List<String> installedSummary;
    private final List<String> manualSummary;
    private final List<String> failedSummary;

    DisplaySnapshot(Phase phase, int itemIndex, int totalItems, long phaseStartMs,
                    String currentItemName, long currentItemTotalBytes, long currentBytesRead,
                    String currentExtra, String currentModIds, String currentUrl,
                    String speedText, int doneCount, int failCount, int manualCount,
                    long phaseTotalBytes, long phaseDownloadedBytes,
                    List<ItemResult> recentResults,
                    List<String> installedSummary, List<String> manualSummary, List<String> failedSummary) {
        this.phase = phase;
        this.itemIndex = itemIndex;
        this.totalItems = totalItems;
        this.phaseStartMs = phaseStartMs;
        this.currentItemName = currentItemName != null ? currentItemName : "";
        this.currentItemTotalBytes = currentItemTotalBytes;
        this.currentBytesRead = currentBytesRead;
        this.currentExtra = currentExtra != null ? currentExtra : "";
        this.currentModIds = currentModIds != null ? currentModIds : "";
        this.currentUrl = currentUrl != null ? currentUrl : "";
        this.speedText = speedText != null ? speedText : "";
        this.doneCount = doneCount;
        this.failCount = failCount;
        this.manualCount = manualCount;
        this.phaseTotalBytes = phaseTotalBytes;
        this.phaseDownloadedBytes = phaseDownloadedBytes;
        this.recentResults = recentResults != null
                ? List.copyOf(recentResults) : Collections.emptyList();
        this.installedSummary = installedSummary != null
                ? List.copyOf(installedSummary) : Collections.emptyList();
        this.manualSummary = manualSummary != null
                ? List.copyOf(manualSummary) : Collections.emptyList();
        this.failedSummary = failedSummary != null
                ? List.copyOf(failedSummary) : Collections.emptyList();
    }

    public Phase phase() { return phase; }
    public int itemIndex() { return itemIndex; }
    public int totalItems() { return totalItems; }
    public long phaseStartMs() { return phaseStartMs; }
    public String currentItemName() { return currentItemName; }
    public long currentItemTotalBytes() { return currentItemTotalBytes; }
    public long currentBytesRead() { return currentBytesRead; }
    public String currentExtra() { return currentExtra; }
    public String currentModIds() { return currentModIds; }
    public String currentUrl() { return currentUrl; }
    public String speedText() { return speedText; }
    public int doneCount() { return doneCount; }
    public int failCount() { return failCount; }
    public int manualCount() { return manualCount; }
    public long phaseTotalBytes() { return phaseTotalBytes; }
    public long phaseDownloadedBytes() { return phaseDownloadedBytes; }
    public List<ItemResult> recentResults() { return recentResults; }
    public List<String> installedSummary() { return installedSummary; }
    public List<String> manualSummary() { return manualSummary; }
    public List<String> failedSummary() { return failedSummary; }

    // ---- Builder methods for UpdateProgressDisplay ----

    static DisplaySnapshot forProgress(Phase phase, int itemIndex, int totalItems, long phaseStartMs,
            String currentItemName, long currentItemTotalBytes, long currentBytesRead,
            String currentExtra, String currentModIds, String currentUrl,
            String speedText, int doneCount, int failCount, int manualCount,
            long phaseTotalBytes, long phaseDownloadedBytes,
            List<ItemResult> recentResults) {
        return new DisplaySnapshot(phase, itemIndex, totalItems, phaseStartMs,
                currentItemName, currentItemTotalBytes, currentBytesRead,
                currentExtra, currentModIds, currentUrl,
                speedText, doneCount, failCount, manualCount,
                phaseTotalBytes, phaseDownloadedBytes,
                recentResults, null, null, null);
    }

    static DisplaySnapshot forCompletion(List<String> installed, List<String> manual, List<String> failed) {
        return new DisplaySnapshot(Phase.COMPLETE, 0, 0, 0,
                "", 0, 0, "", "", "", "",
                0, 0, 0, 0, 0,
                Collections.emptyList(),
                installed, manual, failed);
    }

    /** Result entry for a recently completed item. */
    public static final class ItemResult {
        private final String name;
        private final boolean success;
        private final String message;

        ItemResult(String name, boolean success, String message) {
            this.name = name;
            this.success = success;
            this.message = message;
        }

        public String name() { return name; }
        public boolean success() { return success; }
        public String message() { return message; }
    }
}
