package io.github.henryxjh.mcclientupdate.manifest;

/** Sealed hierarchy for the three supported artifact download types. */
public sealed interface Download permits HostedDownload, DirectDownload, ManualDownload {

    /** The string constant used for JSON discrimination. */
    String type();
}
