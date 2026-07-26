package io.github.henryxjh.mcclientupdate.server.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;

import io.github.henryxjh.mcclientupdate.scan.InstalledMod;
import io.github.henryxjh.mcclientupdate.server.ManifestGenApi;

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

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod("mcu_manifest_gen")
public final class NeoForgeManifestGen {
    private static final Logger LOGGER = LogUtils.getLogger();

    public NeoForgeManifestGen(IEventBus modEventBus) {
        ManifestGenApi.register(
                FMLPaths.GAMEDIR.get(),
                "neoforge",
                NeoForgeManifestGen::getMcVersion,
                NeoForgeManifestGen::getInstalledMods);
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

    private boolean checkPermission(CommandSourceStack source) {
        if (source.getEntity() == null) {
            return true;
        }
        if (source.getPlayer() != null) {
            return ManifestGenApi.get().isUserAllowed(source.getPlayer().getGameProfile().getName());
        }
        return false;
    }

    private boolean checkConsole(CommandSourceStack source) {
        return source.getEntity() == null && source.hasPermission(4);
    }

    // ---- generate -----------------------------------------------------

    private int executeGenerate(CommandContext<CommandSourceStack> ctx, String manifestId) {
        CommandSourceStack source = ctx.getSource();
        ManifestGenApi.get().generate(
                manifestId,
                msg -> {
                    LOGGER.info("[MCUManifestGen] {}", msg);
                    source.sendSuccess(() -> Component.literal(msg), false);
                });
        return 1;
    }

    // ---- ignore add ---------------------------------------------------

    private int executeIgnoreAdd(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenApi.IgnoreChangeResult result = ManifestGenApi.get().addIgnoredMod(modId);
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- ignore remove ------------------------------------------------

    private int executeIgnoreRemove(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        ManifestGenApi.IgnoreChangeResult result = ManifestGenApi.get().removeIgnoredMod(modId);
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- ignore list --------------------------------------------------

    private int executeIgnoreList(CommandContext<CommandSourceStack> ctx) {
        ManifestGenApi.IgnoreListResult result = ManifestGenApi.get().listIgnoredMods();
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- allow add ----------------------------------------------------

    private int executeAllowAdd(CommandContext<CommandSourceStack> ctx) {
        String username = StringArgumentType.getString(ctx, "username");
        ManifestGenApi.AllowedUserChangeResult result = ManifestGenApi.get().addAllowedUser(username);
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- allow remove -------------------------------------------------

    private int executeAllowRemove(CommandContext<CommandSourceStack> ctx) {
        String username = StringArgumentType.getString(ctx, "username");
        ManifestGenApi.AllowedUserChangeResult result = ManifestGenApi.get().removeAllowedUser(username);
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- allow list ---------------------------------------------------

    private int executeAllowList(CommandContext<CommandSourceStack> ctx) {
        ManifestGenApi.AllowedUserListResult result = ManifestGenApi.get().listAllowedUsers();
        ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    // ---- Suggestions --------------------------------------------------

    private enum IgnoreAddSuggestions implements SuggestionProvider<CommandSourceStack> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<CommandSourceStack> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            for (String id : ManifestGenApi.get().suggestIgnoreAddModIds()) {
                builder.suggest(id);
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
            for (String id : ManifestGenApi.get().suggestIgnoreRemoveModIds()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        }
    }

    private enum AllowedUserRemoveSuggestions implements SuggestionProvider<CommandSourceStack> {
        INSTANCE;

        @Override
        public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                CommandContext<CommandSourceStack> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            for (String username : ManifestGenApi.get().listAllowedUsers().allowedUsers()) {
                builder.suggest(username);
            }
            return builder.buildFuture();
        }
    }

    // ---- Helpers ------------------------------------------------------

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
