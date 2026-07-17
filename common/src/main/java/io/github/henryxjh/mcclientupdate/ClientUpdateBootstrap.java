package io.github.henryxjh.mcclientupdate;

import java.util.Objects;

/** Loader-independent startup entry point. */
public final class ClientUpdateBootstrap {
    private ClientUpdateBootstrap() {
    }

    public static void start(PlatformContext platform) {
        Objects.requireNonNull(platform, "platform");
        UpdateTarget target = UpdateTarget.detect(platform);
        platform.log("MC Client Update initialized on " + platform.loaderName()
                + "; target=" + target.classifier()
                + "; gameDir=" + platform.gameDirectory()
                + "; self=" + platform.selfModPath());

        RuntimePlatform runtime = target.platform();
        platform.log("Runtime platform detected as " + runtime.classifier()
                + " from os.name=" + runtime.rawOsName()
                + ", os.version=" + runtime.rawOsVersion()
                + ", os.arch=" + runtime.rawOsArch());

        ClientUpdateConfig config = ClientUpdateConfig.load(platform.gameDirectory());
        if (!config.updatesEnabled()) {
            platform.log("Update checking is disabled; configure config/" + ClientUpdateConfig.FILE_NAME);
            return;
        }
        platform.log("Update manifest endpoint=" + config.redactedManifestEndpoint()
                + "; connectTimeout=" + config.connectTimeout().toSeconds() + "s"
                + "; readTimeout=" + config.readTimeout().toSeconds() + "s");

        // The server manifest protocol will be connected here. The filesystem
        // transaction implementation is intentionally usable without either loader.
    }
}
