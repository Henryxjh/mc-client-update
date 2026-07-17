package io.github.henryxjh.mcclientupdate.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A manually‑updated artifact that the user must download outside the tool.
 * <p>
 * Contains the list of associated mod ids, the target file name, version,
 * a URL for the download page, and a message to show to the user.
 * </p>
 */
public record ManualUpdate(
        List<String> modIds,
        String fileName,
        String version,
        String pageUrl,
        String message) {

    public ManualUpdate {
        Objects.requireNonNull(modIds, "modIds");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(pageUrl, "pageUrl");
        Objects.requireNonNull(message, "message");
        if (modIds.isEmpty()) {
            throw new IllegalArgumentException("modIds must not be empty");
        }
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (pageUrl.isBlank()) {
            throw new IllegalArgumentException("pageUrl must not be blank");
        }
        List<String> sorted = new ArrayList<>(modIds);
        Collections.sort(sorted);
        modIds = List.copyOf(sorted);
    }

    @Override
    public String toString() {
        return "ManualUpdate[modIds=%s, fileName=%s, version=%s]".formatted(modIds, fileName, version);
    }
}
