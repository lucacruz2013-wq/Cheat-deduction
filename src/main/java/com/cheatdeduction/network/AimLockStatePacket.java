package com.cheatdeduction.network;

import com.cheatdeduction.event.CommonEvents;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

public class AimLockStatePacket {
    private final boolean locked;

    public AimLockStatePacket(boolean locked) {
        this.locked = locked;
    }

    public static void encode(AimLockStatePacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.locked);
    }

    public static AimLockStatePacket decode(FriendlyByteBuf buf) {
        return new AimLockStatePacket(buf.readBoolean());
    }

    public static void handle(AimLockStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                CommonEvents.setAimLocked(sender.getUUID(), pkt.locked);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
