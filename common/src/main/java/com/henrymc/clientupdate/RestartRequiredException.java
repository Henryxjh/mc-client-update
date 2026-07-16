package com.henrymc.clientupdate;

/** Thrown on the loader startup thread after a verified update was installed. */
public final class RestartRequiredException extends RuntimeException {
    public RestartRequiredException(String message) {
        super(message);
    }
}
