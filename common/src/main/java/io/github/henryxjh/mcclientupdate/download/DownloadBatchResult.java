package io.github.henryxjh.mcclientupdate.download;

import java.util.List;
import java.util.Objects;

/** Immutable outcome of a batch download operation for a manifest revision. */
public record DownloadBatchResult(
        String manifestId,
        long revision,
        List<DownloadedArtifact> downloaded,
        List<DownloadFailure> failed,
        List<ManualUpdate> manualUpdates) {

    public DownloadBatchResult {
        Objects.requireNonNull(manifestId, "manifestId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >=0");
        }
        Objects.requireNonNull(downloaded, "downloaded");
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(manualUpdates, "manualUpdates");
        downloaded = List.copyOf(downloaded);
        failed = List.copyOf(failed);
        manualUpdates = List.copyOf(manualUpdates);
    }
}
