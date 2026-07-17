package io.github.henryxjh.mcclientupdate.download;

/** Signals a fatal, non‑recoverable problem during the download phase. */
public final class DownloadException extends RuntimeException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
