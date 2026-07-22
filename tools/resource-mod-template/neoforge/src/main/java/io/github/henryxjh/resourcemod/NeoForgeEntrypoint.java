package io.github.henryxjh.resourcemod;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.nio.file.Path;
import java.util.List;

@Mod("{{MODID}}")
public final class NeoForgeEntrypoint {

    // modId is set at compile time via {{MODID}} in @Mod; read from ModList at runtime
    private static final String MOD_ID = "{{MODID}}";

    public NeoForgeEntrypoint(IEventBus modEventBus) {
        // If Kilt is present, it handles registration via the Fabric
        // entrypoint. Skip NeoForge init to avoid double-registration.
        try {
            Class.forName("xyz.bluspring.kilt.Kilt");
            return;
        } catch (ClassNotFoundException ignored) {
        }

        modEventBus.addListener(this::onCommonSetup);

        modEventBus.addListener((AddPackFindersEvent event) -> {
            Path jarPath = ModList.get().getModFileById(MOD_ID).getFile().getFilePath();
            List<String> packs = ResourceExtractor.findResourcePacks(jarPath);
            for (String packName : packs) {
                event.addPackFinders(
                        ResourceLocation.fromNamespaceAndPath(MOD_ID, "resource-pack/" + packName),
                        PackType.CLIENT_RESOURCES,
                        Component.literal(packName),
                        PackSource.BUILT_IN,
                        false,
                        Pack.Position.TOP);
                System.out.println("[ResourceMod] Registered resource pack: " + packName);
            }
        });
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path jarPath = ModList.get().getModFileById(MOD_ID).getFile().getFilePath();

        ResourceExtractor.extractIfNeeded(jarPath, gameDir, MOD_ID,
                msg -> System.out.println("[ResourceMod] " + msg));
    }
}
