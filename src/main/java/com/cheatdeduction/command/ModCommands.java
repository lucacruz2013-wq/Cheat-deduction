package com.cheatdeduction.command;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.game.GameManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID)
public final class ModCommands {
    private ModCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("startcheat")
                .requires(s -> s.hasPermission(2))
                .executes(ModCommands::startCheat));

        d.register(Commands.literal("autovote")
                .requires(s -> s.hasPermission(2))
                .executes(ModCommands::autoVote));

        d.register(Commands.literal("choose")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("hack", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Cheat.commandNames(), builder))
                        .executes(ModCommands::choose)));
    }

    private static int startCheat(CommandContext<CommandSourceStack> ctx) {
        String err = GameManager.start(ctx.getSource().getServer());
        if (err != null) {
            ctx.getSource().sendFailure(Component.literal(err));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Partida iniciada."), true);
        return 1;
    }

    private static int autoVote(CommandContext<CommandSourceStack> ctx) {
        String err = GameManager.startVoting(ctx.getSource().getServer());
        if (err != null) {
            ctx.getSource().sendFailure(Component.literal(err));
            return 0;
        }
        return 1;
    }

    private static int choose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "hack");
        Cheat cheat = Cheat.byCommandName(name);
        if (cheat == null) {
            ctx.getSource().sendFailure(Component.literal("Cheat desconhecido: " + name));
            return 0;
        }
        GameManager.assignCheat(player, cheat);
        ctx.getSource().sendSuccess(() -> Component.literal("Cheat definido: " + cheat.displayName()), false);
        return 1;
    }
}
