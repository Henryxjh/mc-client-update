package io.github.henryxjh.mcclientupdate.neoforge;

import com.mojang.logging.LogUtils;
import io.github.henryxjh.mcclientupdate.ClientUpdateBootstrap;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.ModScanException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
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

            @Override
            public List<InstalledMod> installedMods() {
                List<? extends IModInfo> allMods = ModList.get().getMods();
                List<InstalledMod> result = new ArrayList<>();
                for (IModInfo info : allMods) {
                    String modId = info.getModId();
                    String version = info.getVersion().toString();
                    var owningFileInfo = info.getOwningFile();
                    if (owningFileInfo == null) {
                        throw new ModScanException(
                                "NeoForge gave no owning file for mod " + modId);
                    }
                    Path filePath = owningFileInfo.getFile().getFilePath().toAbsolutePath().normalize();
                    result.add(new InstalledMod(modId, version, filePath));
                }
                return List.copyOf(result);
            }

            @Override
            public String loaderVersion() {
                for (IModInfo info : ModList.get().getMods()) {
                    if ("neoforge".equals(info.getModId())) {
                        return info.getVersion().toString();
                    }
                }
                return "0.0.0";
            }

            @Override
            public String minecraftVersion() {
                for (IModInfo info : ModList.get().getMods()) {
                    if ("minecraft".equals(info.getModId())) {
                        return info.getVersion().toString();
                    }
                }
                throw new ModScanException("Minecraft version not available: minecraft mod not found");
            }
        });
    }
}
