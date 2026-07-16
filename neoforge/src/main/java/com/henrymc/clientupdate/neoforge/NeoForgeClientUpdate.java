package com.henrymc.clientupdate.neoforge;

import com.henrymc.clientupdate.ClientUpdateBootstrap;
import com.henrymc.clientupdate.PlatformContext;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

@Mod("mc_client_update")
public final class NeoForgeClientUpdate {
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
                System.out.println("[MCClientUpdate] " + message);
            }
        });
    }
}
