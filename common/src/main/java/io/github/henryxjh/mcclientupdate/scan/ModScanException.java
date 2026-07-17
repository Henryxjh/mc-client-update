package io.github.henryxjh.mcclientupdate.scan;

/**
 * Thrown when the mod update scanner cannot proceed.
 */
public final class ModScanException extends RuntimeException {
    public ModScanException(String message) {
        super(message);
    }

    public ModScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
