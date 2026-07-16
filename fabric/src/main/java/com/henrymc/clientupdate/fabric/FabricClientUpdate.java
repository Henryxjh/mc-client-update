package com.henrymc.clientupdate.fabric;

import com.henrymc.clientupdate.ClientUpdateBootstrap;
import com.henrymc.clientupdate.PlatformContext;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricClientUpdate implements ModInitializer {
    private static final String MOD_ID = "mc_client_update";

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
                System.out.println("[MCClientUpdate] " + message);
            }
        });
    }
}
