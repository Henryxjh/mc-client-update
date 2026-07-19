package io.github.henryxjh.mcclientupdate.manifest;

/**
 * Immutable variant of a mod entry, selecting which platforms receive which artifact.
 */
public record Variant(
        Selector selector,
        int priority,
        Artifact artifact,
        ModAction action) {

    public Variant(Selector selector, int priority, Artifact artifact) {
        this(selector, priority, artifact, ModAction.INSTALL);
    }

    public Variant {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (action == ModAction.INSTALL && artifact == null) {
            throw new IllegalArgumentException("artifact must not be null for INSTALL action");
        }
        if (action == ModAction.DELETE && artifact != null) {
            throw new IllegalArgumentException("artifact must be null for DELETE action");
        }
    }
}
