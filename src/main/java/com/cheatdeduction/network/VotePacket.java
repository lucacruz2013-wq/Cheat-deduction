package com.cheatdeduction.network;

import com.cheatdeduction.game.GameManager;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** C2S: voto (acusado + cheat) ou "skip" (fechou a tela / pulou). */
public record VotePacket(boolean skip, UUID accused, int cheat) {

    public static VotePacket skipVote() {
        return new VotePacket(true, new UUID(0L, 0L), 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(skip);
        if (!skip) {
            buf.writeUUID(accused);
            buf.writeVarInt(cheat);
        }
    }

    public static VotePacket decode(FriendlyByteBuf buf) {
        boolean skip = buf.readBoolean();
        if (skip) return skipVote();
        return new VotePacket(false, buf.readUUID(), buf.readVarInt());
    }

    public static void handle(VotePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            GameManager.handleVote(sender, msg.skip() ? null : msg.accused(), msg.cheat());
        }
        ctx.get().setPacketHandled(true);
    }
}
