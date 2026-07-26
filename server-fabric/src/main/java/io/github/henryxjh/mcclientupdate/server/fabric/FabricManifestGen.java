package io.github.henryxjh.mcclientupdate.server.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.server.ManifestGenApi;

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

        ManifestGenApi.register(
                FabricLoader.getInstance().getGameDir(),
                "fabric",
                FabricManifestGen::getMcVersion,
                FabricManifestGen::getInstalledMods);
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
                                .executes(this::executeIgnoreList)))
                .then(literal("allow")
                        .requires(this::checkConsole)
                        .then(literal("add")
                                .then(argument("username", StringArgumentType.word())
                                        .executes(this::executeAllowAdd)))
                        .then(literal("remove")
                                .then(argument("username", StringArgumentType.word())
                                        .suggests(AllowedUserRemoveSuggestions.INSTANCE)
                                        .executes(this::executeAllowRemove)))
                        .then(literal("list")
                                .executes(this::executeAllowList)));

        dispatcher.register(root);
    }

    private boolean checkPermission(ServerCommandSource source) {
        if (source.getEntity() == null) {
            return true; // console
        }
        if (source.getPlayer() != null) {
            return ManifestGenApi.get().isUserAllowed(source.getPlayer().getGameProfile().getName());
        }
        return false;
    }

    private boolean checkConsole(ServerCommandSource source) {
        return source.getEntity() == null && source.hasPermissionLevel(4);
    }

    // ---- generate -----------------------------------------------------

    private int executeGenerate(CommandContext<ServerCommandSource> ctx, String manifestId) {
        ServerCommandSource source = ctx.getSource();
        ManifestGenApi.get().generate(
                manifestId,
                msg -> {
                    LOGGER.info("[MCUManifestGen] {}", msg);
                    source.sendFeedback(() -> Text.literal(msg), false);
                });
        return 1;
    }

    // ---- ignore add ---------------------------------------------------

    private int executeIgnoreAdd(CommandContext<ServerCommandSource> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenApi.IgnoreChangeResult result = ManifestGenApi.get().addIgnoredMod(modId);
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- ignore remove ------------------------------------------------

    private int executeIgnoreRemove(CommandContext<ServerCommandSource> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenApi.IgnoreChangeResult result = ManifestGenApi.get().removeIgnoredMod(modId);
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- ignore list --------------------------------------------------

    private int executeIgnoreList(CommandContext<ServerCommandSource> ctx) {
        ManifestGenApi.IgnoreListResult result = ManifestGenApi.get().listIgnoredMods();
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- allow add ----------------------------------------------------

    private int executeAllowAdd(CommandContext<ServerCommandSource> ctx) {
        String username = StringArgumentType.getString(ctx, "username");
        ManifestGenApi.AllowedUserChangeResult result = ManifestGenApi.get().addAllowedUser(username);
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- allow remove -------------------------------------------------

    private int executeAllowRemove(CommandContext<ServerCommandSource> ctx) {
        String username = StringArgumentType.getString(ctx, "username");
        ManifestGenApi.AllowedUserChangeResult result = ManifestGenApi.get().removeAllowedUser(username);
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- allow list ---------------------------------------------------

    private int executeAllowList(CommandContext<ServerCommandSource> ctx) {
        ManifestGenApi.AllowedUserListResult result = ManifestGenApi.get().listAllowedUsers();
        ctx.getSource().sendFeedback(() -> Text.literal(result.message()), false);
        return 1;
    }

    // ---- Suggestions --------------------------------------------------

    private enum IgnoreAddSuggestions implements SuggestionProvider<ServerCommandSource> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<ServerCommandSource> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            for (String id : ManifestGenApi.get().suggestIgnoreAddModIds()) {
                builder.suggest(id);
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
            for (String id : ManifestGenApi.get().suggestIgnoreRemoveModIds()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        }
    }

    private enum AllowedUserRemoveSuggestions implements SuggestionProvider<ServerCommandSource> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<ServerCommandSource> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            for (String username : ManifestGenApi.get().listAllowedUsers().allowedUsers()) {
                builder.suggest(username);
            }
            return builder.buildFuture();
        }
    }

    // ---- Helpers ------------------------------------------------------

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
