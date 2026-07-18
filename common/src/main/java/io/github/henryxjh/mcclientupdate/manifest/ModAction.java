package io.github.henryxjh.mcclientupdate.manifest;

/**
 * Action that should be performed for this mod entry.
 */
public enum ModAction {
    /** Install/update the mod from the manifest definition. */
    INSTALL,
    /** Delete the mod if present locally. */
    DELETE
}
