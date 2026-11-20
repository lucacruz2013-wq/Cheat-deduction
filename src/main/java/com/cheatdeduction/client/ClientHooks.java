package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.network.VotingPacket;
import net.minecraft.client.Minecraft;

/** Chamado apenas no cliente (via DistExecutor) pelos pacotes. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void setCheat(int ordinal) {
        ClientCheatState.set(Cheat.byId(ordinal));
    }

    public static void handleVoting(VotingPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.open()) {
            mc.setScreen(new VotingScreen(packet.players(), packet.discovered()));
        } else if (mc.screen instanceof VotingScreen screen) {
            screen.closeFromServer();
        }
    }
}
