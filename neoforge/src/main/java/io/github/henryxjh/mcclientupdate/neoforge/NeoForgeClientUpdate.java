package io.github.henryxjh.mcclientupdate.neoforge;

import com.mojang.logging.LogUtils;
import io.github.henryxjh.mcclientupdate.ClientUpdateBootstrap;
import io.github.henryxjh.mcclientupdate.PlatformContext;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod("mc_client_update")
public final class NeoForgeClientUpdate {
    private static final Logger LOGGER = LogUtils.getLogger();

    public NeoForgeClientUpdate() {
        Path selfPath = ModList.get()
                .getModFileById("mc_client_update")
                .getFile()
                .getFilePath();

        ClientUpdateBootstrap.start(new PlatformContext() {
            @Override
            public String loaderName() {
                return "neoforge";
            }

            @Override
            public Path gameDirectory() {
                return FMLPaths.GAMEDIR.get();
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
