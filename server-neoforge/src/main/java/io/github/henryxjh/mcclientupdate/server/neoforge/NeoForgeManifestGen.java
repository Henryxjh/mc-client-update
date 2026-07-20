package io.github.henryxjh.mcclientupdate.server.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.server.ManifestGenConfig;
import io.github.henryxjh.mcclientupdate.server.WorkspaceGenerator;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforgespi.language.IModInfo;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod("mcu-manifest-gen")
public final class NeoForgeManifestGen {
    private static final Logger LOGGER = LogUtils.getLogger();

    public NeoForgeManifestGen(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        LOGGER.info("[MCUManifestGen] Loaded. Config: config/mcu-manifest-gen.json");
        LOGGER.info("[MCUManifestGen] Run /mcum-gen generate to create workspace.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        var root = literal("mcum-gen")
                .requires(this::checkPermission)
                .then(literal("generate")
                        .executes(ctx -> executeGenerate(ctx, null))
                        .then(argument("manifestId", StringArgumentType.word())
                                .executes(ctx -> executeGenerate(ctx,
                                        StringArgumentType.getString(ctx, "manifestId")))))
                .then(literal("ignore")
                        .then(literal("add")
                                .then(argument("modId", StringArgumentType.word())
                                        .suggests(IgnoreAddSuggestions.INSTANCE)
                                        .executes(this::executeIgnoreAdd)))
                        .then(literal("remove")
                                .then(argument("modId", StringArgumentType.word())
                                        .suggests(IgnoreRemoveSuggestions.INSTANCE)
                                        .executes(this::executeIgnoreRemove)))
                        .then(literal("list")
                                .executes(this::executeIgnoreList)));

        dispatcher.register(root);
    }

    private boolean checkPermission(CommandSourceStack source) {
        if (source.getEntity() == null) {
            return true;
        }
        ManifestGenConfig config = loadConfig();
        if (source.getPlayer() != null) {
            return config.isUserAllowed(source.getPlayer().getGameProfile().getName());
        }
        return false;
    }

    // ---- generate -----------------------------------------------------

    private int executeGenerate(CommandContext<CommandSourceStack> ctx, String manifestId) {
        CommandSourceStack source = ctx.getSource();
        ManifestGenConfig config = loadConfig();
        Path gameDir = FMLPaths.GAMEDIR.get();

        List<InstalledMod> installedMods = getInstalledMods();
        String loader = "neoforge";
        String mcVersion = getMcVersion();

        source.sendSuccess(() -> Component.literal("Scanning " + installedMods.size() + " loaded mods..."), false);

        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir, installedMods, loader, mcVersion, config, manifestId,
                msg -> {
                    LOGGER.info("[MCUManifestGen] {}", msg);
                    source.sendSuccess(() -> Component.literal(msg), false);
                });

        source.sendSuccess(() -> Component.literal("Manifest ID: " + result.manifestId()), false);
        source.sendSuccess(() -> Component.literal("Wrote " + result.scanned() + " mods (" +
                result.updated() + " updated, " + result.added() + " added, " +
                result.markedDelete() + " marked DELETE) to " + result.outputFileName()), false);
        source.sendSuccess(() -> Component.literal("Skipped: " + result.skippedNonModFolder() +
                " non-mod-folder, " + result.skippedIgnored() + " ignored, " +
                result.skippedDuplicateJar() + " duplicate JAR"), false);

        return 1;
    }

    // ---- ignore add ---------------------------------------------------

    private int executeIgnoreAdd(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenConfig config = loadConfig();
        if (config.addIgnored(modId)) {
            config.save(FMLPaths.GAMEDIR.get());
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Added \"" + modId + "\" to ignored mods."), false);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("\"" + modId + "\" is already ignored."), false);
        }
        return 1;
    }

    // ---- ignore remove ------------------------------------------------

    private int executeIgnoreRemove(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenConfig config = loadConfig();
        if (config.removeIgnored(modId)) {
            config.save(FMLPaths.GAMEDIR.get());
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Removed \"" + modId + "\" from ignored mods."), false);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("\"" + modId + "\" is not in ignored list."), false);
        }
        return 1;
    }

    // ---- ignore list --------------------------------------------------

    private int executeIgnoreList(CommandContext<CommandSourceStack> ctx) {
        ManifestGenConfig config = loadConfig();
        List<String> ignored = config.getIgnoredMods();
        if (ignored.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("No mods are ignored."), false);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Ignored mods (" + ignored.size() + "): "
                            + String.join(", ", ignored)), false);
        }
        return 1;
    }

    // ---- Suggestions --------------------------------------------------

    private enum IgnoreAddSuggestions implements SuggestionProvider<CommandSourceStack> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<CommandSourceStack> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            ManifestGenConfig config = ManifestGenConfig.load(FMLPaths.GAMEDIR.get());
            Set<String> ignored = Set.copyOf(config.getIgnoredMods());
            Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
            Path realModsDir;
            try {
                realModsDir = modsDir.toRealPath();
            } catch (Exception e) {
                return builder.buildFuture();
            }

            for (InstalledMod mod : getInstalledMods()) {
                String id = mod.modId();
                if (ignored.contains(id)) continue;
                try {
                    Path realPath = mod.file().toAbsolutePath().normalize().toRealPath();
                    if (realPath.startsWith(realModsDir)) {
                        builder.suggest(id);
                    }
                } catch (Exception ignored2) {
                }
            }
            return builder.buildFuture();
        }
    }

    private enum IgnoreRemoveSuggestions implements SuggestionProvider<CommandSourceStack> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<CommandSourceStack> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            ManifestGenConfig config = ManifestGenConfig.load(FMLPaths.GAMEDIR.get());
            for (String id : config.getIgnoredMods()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        }
    }

    // ---- Helpers ------------------------------------------------------

    private static ManifestGenConfig loadConfig() {
        return ManifestGenConfig.load(FMLPaths.GAMEDIR.get());
    }

    private static List<InstalledMod> getInstalledMods() {
        List<InstalledMod> result = new ArrayList<>();
        for (IModInfo info : net.neoforged.fml.ModList.get().getMods()) {
            String modId = info.getModId();
            String version = info.getVersion().toString();
            var owningFileInfo = info.getOwningFile();
            if (owningFileInfo == null) {
                continue;
            }
            Path filePath = owningFileInfo.getFile().getFilePath().toAbsolutePath().normalize();
            result.add(new InstalledMod(modId, version, filePath));
        }
        return result;
    }

    private static String getMcVersion() {
        for (IModInfo info : net.neoforged.fml.ModList.get().getMods()) {
            if ("minecraft".equals(info.getModId())) {
                return info.getVersion().toString();
            }
        }
        return "unknown";
    }
}
