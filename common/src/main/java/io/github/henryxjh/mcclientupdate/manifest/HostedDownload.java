package io.github.henryxjh.mcclientupdate.manifest;

/**
 * Artifact hosted on the same server that serves the manifest.
 * {@code url} is resolved relative to {@code baseUrl} or the manifest URL.
 */
public record HostedDownload(String url) implements Download {

    public HostedDownload {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("hosted download url must not be blank");
        }
    }

    @Override
    public String type() {
        return "hosted";
    }
}
