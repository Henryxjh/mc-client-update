package io.github.henryxjh.mcclientupdate.server.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.server.ManifestGenConfig;
import io.github.henryxjh.mcclientupdate.server.WorkspaceGenerator;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class FabricManifestGen implements ModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "mcu_manifest_gen";

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
        LOGGER.info("[MCUManifestGen] Loaded. Config: config/mcu-manifest-gen.json");
        LOGGER.info("[MCUManifestGen] Run /mcum-gen generate to create workspace.");
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
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

    private boolean checkPermission(ServerCommandSource source) {
        if (source.getEntity() == null) {
            return true; // console
        }
        ManifestGenConfig config = loadConfig();
        if (source.getPlayer() != null) {
            return config.isUserAllowed(source.getPlayer().getGameProfile().getName());
        }
        return false;
    }

    // ---- generate -----------------------------------------------------

    private int executeGenerate(CommandContext<ServerCommandSource> ctx, String manifestId) {
        ServerCommandSource source = ctx.getSource();
        ManifestGenConfig config = loadConfig();
        Path gameDir = FabricLoader.getInstance().getGameDir();

        List<InstalledMod> installedMods = getInstalledMods();
        String loader = "fabric";
        String mcVersion = getMcVersion();

        source.sendFeedback(() -> Text.literal("Scanning " + installedMods.size() + " loaded mods..."), false);

        WorkspaceGenerator.Result result = WorkspaceGenerator.generate(
                gameDir, installedMods, loader, mcVersion, config, manifestId,
                msg -> {
                    LOGGER.info("[MCUManifestGen] {}", msg);
                    source.sendFeedback(() -> Text.literal(msg), false);
                });

        source.sendFeedback(() -> Text.literal("Manifest ID: " + result.manifestId()), false);
        source.sendFeedback(() -> Text.literal("Wrote " + result.scanned() + " mods (" +
                result.updated() + " updated, " + result.added() + " added, " +
                result.markedDelete() + " marked DELETE) to " + result.outputFileName()), false);
        source.sendFeedback(() -> Text.literal("Skipped: " + result.skippedNonModFolder() +
                " non-mod-folder, " + result.skippedIgnored() + " ignored, " +
                result.skippedDuplicateJar() + " duplicate JAR"), false);

        return 1;
    }

    // ---- ignore add ---------------------------------------------------

    private int executeIgnoreAdd(CommandContext<ServerCommandSource> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenConfig config = loadConfig();
        if (config.addIgnored(modId)) {
            config.save(FabricLoader.getInstance().getGameDir());
            ctx.getSource().sendFeedback(
                    () -> Text.literal("Added \"" + modId + "\" to ignored mods."), false);
        } else {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("\"" + modId + "\" is already ignored."), false);
        }
        return 1;
    }

    // ---- ignore remove ------------------------------------------------

    private int executeIgnoreRemove(CommandContext<ServerCommandSource> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenConfig config = loadConfig();
        if (config.removeIgnored(modId)) {
            config.save(FabricLoader.getInstance().getGameDir());
            ctx.getSource().sendFeedback(
                    () -> Text.literal("Removed \"" + modId + "\" from ignored mods."), false);
        } else {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("\"" + modId + "\" is not in ignored list."), false);
        }
        return 1;
    }

    // ---- ignore list --------------------------------------------------

    private int executeIgnoreList(CommandContext<ServerCommandSource> ctx) {
        ManifestGenConfig config = loadConfig();
        List<String> ignored = config.getIgnoredMods();
        if (ignored.isEmpty()) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("No mods are ignored."), false);
        } else {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("Ignored mods (" + ignored.size() + "): "
                            + String.join(", ", ignored)), false);
        }
        return 1;
    }

    // ---- Suggestions --------------------------------------------------

    private enum IgnoreAddSuggestions implements SuggestionProvider<ServerCommandSource> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<ServerCommandSource> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            ManifestGenConfig config = ManifestGenConfig.load(FabricLoader.getInstance().getGameDir());
            Set<String> ignored = Set.copyOf(config.getIgnoredMods());
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
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

    private enum IgnoreRemoveSuggestions implements SuggestionProvider<ServerCommandSource> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<ServerCommandSource> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            ManifestGenConfig config = ManifestGenConfig.load(FabricLoader.getInstance().getGameDir());
            for (String id : config.getIgnoredMods()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        }
    }

    // ---- Helpers ------------------------------------------------------

    private ManifestGenConfig loadConfig() {
        return ManifestGenConfig.load(FabricLoader.getInstance().getGameDir());
    }

    private static List<InstalledMod> getInstalledMods() {
        FabricLoader loader = FabricLoader.getInstance();
        List<InstalledMod> mods = new ArrayList<>();
        for (ModContainer container : loader.getAllMods()) {
            String modId = container.getMetadata().getId();
            String version = container.getMetadata().getVersion().getFriendlyString();
            var origin = container.getOrigin();
            List<Path> paths = origin.getPaths();
            if (paths.size() != 1) {
                continue; // skip mods with ambiguous origins
            }
            Path filePath = paths.get(0).toAbsolutePath().normalize();
            mods.add(new InstalledMod(modId, version, filePath));
        }
        return mods;
    }

    private static String getMcVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
