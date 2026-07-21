package io.github.henryxjh.resourcemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.List;

public final class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();

        // Find which mod owns this entrypoint
        ModContainer self = loader.getEntrypointContainers("main", ModInitializer.class)
                .stream()
                .filter(c -> c.getEntrypoint() == this)
                .findFirst()
                .map(c -> c.getProvider())
                .orElse(null);
        if (self == null) return;

        String modId = self.getMetadata().getId();
        Path gameDir = loader.getGameDir();
        Path jarPath = self.getOrigin().getPaths().stream().findFirst()
                .map(Path::toAbsolutePath).map(Path::normalize).orElse(null);

        // Extract override/ if lock changed
        ResourceExtractor.extractIfNeeded(jarPath, gameDir, modId,
                msg -> System.out.println("[ResourceMod] " + msg));

        // Register built-in resource packs from resource-pack/
        List<String> packs = ResourceExtractor.findResourcePacks(jarPath);
        for (String packName : packs) {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    Identifier.of(modId, packName),
                    self,
                    Text.literal(packName),
                    ResourcePackActivationType.NORMAL);
            System.out.println("[ResourceMod] Registered resource pack: " + packName);
        }
    }
}
