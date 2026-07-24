package io.github.henryxjh.mcclientupdate.ui;

import java.util.List;

/** A do-nothing sink used when the platform does not provide a native progress indicator. */
public final class NoopLoadingProgressSink implements LoadingProgressSink {

    @Override public void phaseStart(DisplaySnapshot.Phase phase, int totalItems, long totalBytes) { }

    @Override public void itemStart(String name, long totalBytes, String extra) { }

    @Override public void progressTick(int itemIndex, int totalItems, long itemTotalBytes,
                                       long itemReadBytes, String speedText,
                                       long phaseDoneBytes, long phaseTotalBytes) { }

    @Override public void itemDone(boolean success, String message) { }

    @Override public void phaseDone(int ok, int failed, int manual) { }

    @Override public void installCountProgress(int doneCount, int totalItems) { }

    @Override public void reportModIds(String joinedModIds) { }

    @Override public void reportDownloadUrl(String url) { }

    @Override public void showCompletion(List<String> installed, List<String> manual, List<String> failed) { }

    @Override public void close() { }
}
