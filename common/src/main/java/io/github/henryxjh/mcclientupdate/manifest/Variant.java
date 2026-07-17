package io.github.henryxjh.mcclientupdate.manifest;

/**
 * Immutable variant of a mod entry, selecting which platforms receive which artifact.
 */
public record Variant(
        Selector selector,
        int priority,
        Artifact artifact) {

    public Variant {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        if (artifact == null) {
            throw new IllegalArgumentException("artifact must not be null");
        }
    }
}
