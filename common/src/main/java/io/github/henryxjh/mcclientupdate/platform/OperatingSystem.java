package io.github.henryxjh.mcclientupdate.platform;

/** Stable operating-system identifiers used by the update manifest. */
public enum OperatingSystem {
    ANDROID("android"),
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("macos"),
    UNKNOWN("unknown");

    private final String id;

    OperatingSystem(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
