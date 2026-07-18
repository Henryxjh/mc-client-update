package io.github.henryxjh.mcclientupdate.fabric;

import com.mojang.logging.LogUtils;
import io.github.henryxjh.mcclientupdate.ClientUpdateBootstrap;
import io.github.henryxjh.mcclientupdate.platform.PlatformContext;
import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.scan.ModScanException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

public final class FabricClientUpdate implements ModInitializer {
    private static final String MOD_ID = "mc_client_update";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer selfModContainer = loader.getModContainer(MOD_ID)
                .orElseThrow();
        List<Path> selfPaths = selfModContainer.getOrigin().getPaths();
        if (selfPaths.size() != 1) {
            throw new ModScanException(
                    "Expected exactly one origin path for this mod, but got " + selfPaths.size());
        }
        Path selfPath = selfPaths.get(0).toAbsolutePath().normalize();

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

            @Override
            public List<InstalledMod> installedMods() {
                FabricLoader localLoader = FabricLoader.getInstance();
                List<InstalledMod> mods = new ArrayList<>();
                for (ModContainer container : localLoader.getAllMods()) {
                    String modId = container.getMetadata().getId();
                    String version = container.getMetadata().getVersion().getFriendlyString();
                    var origin = container.getOrigin();
                    List<Path> paths = origin.getPaths();
                    if (paths.size() != 1) {
                        throw new ModScanException(
                                "Fabric loader gave " + paths.size() + " origin paths for mod " + modId
                                + " (expected exactly 1)");
                    }
                    Path filePath = paths.get(0).toAbsolutePath().normalize();
                    mods.add(new InstalledMod(modId, version, filePath));
                }
                return List.copyOf(mods);
            }

            @Override
            public String loaderVersion() {
                return FabricLoader.getInstance()
                        .getModContainer("fabricloader")
                        .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                        .orElseThrow(() -> new ModScanException("Fabric Loader not found"));
            }

            @Override
            public String minecraftVersion() {
                FabricLoader fabricLoader = FabricLoader.getInstance();
                return fabricLoader.getModContainer("minecraft")
                        .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                        .orElseThrow(() -> new ModScanException("Minecraft version not available: minecraft mod not found"));
            }
        });
    }
}
