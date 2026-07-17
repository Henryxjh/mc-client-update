package io.github.henryxjh.mcclientupdate.manifest;

/** Thrown when the update manifest cannot be fetched or parsed successfully. */
public final class ManifestFetchException extends RuntimeException {

    public ManifestFetchException(String message) {
        super(message);
    }

    public ManifestFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
