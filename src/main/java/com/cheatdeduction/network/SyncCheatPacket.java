package com.cheatdeduction.network;

import com.cheatdeduction.client.ClientHooks;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** S2C: ordinal do cheat do jogador (-1 = nenhum). */
public record SyncCheatPacket(int cheat) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(cheat + 1);
    }

    public static SyncCheatPacket decode(FriendlyByteBuf buf) {
        return new SyncCheatPacket(buf.readVarInt() - 1);
    }

    public static void handle(SyncCheatPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.setCheat(msg.cheat()));
        ctx.get().setPacketHandled(true);
    }
}
