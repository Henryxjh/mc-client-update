package io.github.henryxjh.mcclientupdate.fabric;

import com.mojang.logging.LogUtils;
import io.github.henryxjh.mcclientupdate.ClientUpdateBootstrap;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class FabricClientUpdate implements ModInitializer {
    private static final String MOD_ID = "mc_client_update";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        Path selfPath = loader.getModContainer(MOD_ID)
                .orElseThrow()
                .getOrigin()
                .getPaths()
                .getFirst();

        ClientUpdateBootstrap.start(new PlatformContext() {
            @Override
            public String loaderName() {
                return "fabric";
            }

            @Override
            public Path gameDirectory() {
                return loader.getGameDir();
            }

            @Override
            public Path selfModPath() {
                return selfPath;
            }

            @Override
            public void log(String message) {
                LOGGER.info("[MCClientUpdate] {}", message);
            }
        });
    }
}
