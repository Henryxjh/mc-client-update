package io.github.henryxjh.mcclientupdate.ui;

import java.util.List;

/**
 * Sink for forwarding loading progress events to platform-specific indicators,
 * for example NeoForge's loading bar.
 */
public interface LoadingProgressSink {

    void phaseStart(DisplaySnapshot.Phase phase, int totalItems, long totalBytes);

    void itemStart(String name, long totalBytes, String extra);

    void progressTick(int itemIndex, int totalItems, long itemTotalBytes, long itemReadBytes,
                      String speedText, long phaseDoneBytes, long phaseTotalBytes);

    void itemDone(boolean success, String message);

    void phaseDone(int ok, int failed, int manual);

    /** Called when an install item finishes, so the bar can reflect completed count. */
    void installCountProgress(int doneCount, int totalItems);

    void reportModIds(String joinedModIds);

    void reportDownloadUrl(String url);

    void showCompletion(List<String> installed, List<String> manual, List<String> failed);

    void close();
}
