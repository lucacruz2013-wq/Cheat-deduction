package com.cheatdeduction.network;

import com.cheatdeduction.client.ClientHooks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: open=true abre a VotingScreen com a lista de jogadores votáveis e os já descobertos
 * por este votante; open=false fecha a tela (fim/timeout da votação).
 */
public record VotingPacket(boolean open, List<Entry> players, Set<UUID> discovered) {

    public record Entry(UUID id, String name) {}

    public static VotingPacket close() {
        return new VotingPacket(false, List.of(), Set.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeCollection(players, (b, e) -> {
            b.writeUUID(e.id());
            b.writeUtf(e.name(), 64);
        });
        buf.writeCollection(discovered, FriendlyByteBuf::writeUUID);
    }

    public static VotingPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        List<Entry> players = buf.readList(b -> new Entry(b.readUUID(), b.readUtf(64)));
        Set<UUID> discovered = new HashSet<>(buf.readList(FriendlyByteBuf::readUUID));
        return new VotingPacket(open, players, discovered);
    }

    public static void handle(VotingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.handleVoting(msg));
        ctx.get().setPacketHandled(true);
    }
}
