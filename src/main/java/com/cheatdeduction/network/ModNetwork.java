package com.cheatdeduction.network;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CheatDeductionMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ModNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(SyncCheatPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncCheatPacket::encode).decoder(SyncCheatPacket::decode)
                .consumerMainThread(SyncCheatPacket::handle).add();
        CHANNEL.messageBuilder(VotingPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VotingPacket::encode).decoder(VotingPacket::decode)
                .consumerMainThread(VotingPacket::handle).add();
        CHANNEL.messageBuilder(VotePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VotePacket::encode).decoder(VotePacket::decode)
                .consumerMainThread(VotePacket::handle).add();
        CHANNEL.messageBuilder(AimLockStatePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AimLockStatePacket::encode).decoder(AimLockStatePacket::decode)
                .consumerMainThread(AimLockStatePacket::handle).add();
    }

    /** Servidor -> cliente: cheat atual do jogador (null = nenhum). */
    public static void sendCheat(ServerPlayer player, @Nullable Cheat cheat) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncCheatPacket(cheat == null ? -1 : cheat.ordinal()));
    }

    /** Servidor -> cliente: abre/fecha a VotingScreen. */
    public static void sendVoting(ServerPlayer player, VotingPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Cliente -> servidor: avisa quando a trava do Aimlock ativa ou desativa. */
    public static void sendAimLockState(boolean locked) {
        CHANNEL.sendToServer(new AimLockStatePacket(locked));
    }
}
