package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.network.ModNetwork;
import com.cheatdeduction.network.VotePacket;
import com.cheatdeduction.network.VotingPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Votação em duas etapas, só com botões nativos Button.builder:
 * 1) escolher o jogador acusado (desabilitado se já descoberto por este votante);
 * 2) escolher qual dos 8 cheats ele usa.
 * Fechar a tela sem votar envia um "skip" ao servidor.
 */
public class VotingScreen extends Screen {
    private final List<VotingPacket.Entry> players;
    private final Set<UUID> discovered;
    private VotingPacket.Entry selected;
    private boolean finished;

    private record Item(Component label, boolean active, Runnable action) {}

    public VotingScreen(List<VotingPacket.Entry> players, Set<UUID> discovered) {
        super(Component.literal("Votação"));
        this.players = players;
        this.discovered = discovered;
    }

    @Override
    protected void init() {
        List<Item> items = new ArrayList<>();
        if (selected == null) {
            for (VotingPacket.Entry e : players) {
                items.add(new Item(Component.literal(e.name()), !discovered.contains(e.id()), () -> {
                    selected = e;
                    rebuildWidgets();
                }));
            }
        } else {
            for (Cheat c : Cheat.VALUES) {
                items.add(new Item(Component.literal(c.displayName()), true, () -> submit(c)));
            }
        }
        addGrid(items);

        Component label = selected == null ? Component.literal("Pular voto") : Component.literal("Voltar");
        addRenderableWidget(Button.builder(label, btn -> {
            if (selected == null) {
                onClose();
            } else {
                selected = null;
                rebuildWidgets();
            }
        }).bounds(width / 2 - 60, height - 30, 120, 20).build());
    }

    private void addGrid(List<Item> items) {
        int cols = items.size() > 5 ? 2 : 1;
        int w = cols == 2 ? 150 : 200;
        int gap = 8;
        int rows = Math.max(1, (items.size() + cols - 1) / cols);
        int startX = (width - (cols * w + (cols - 1) * gap)) / 2;
        int startY = Math.max(44, (height - (rows * 24 - 4)) / 2);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            Button b = Button.builder(it.label(), btn -> it.action().run())
                    .bounds(startX + (i % cols) * (w + gap), startY + (i / cols) * 24, w, 20)
                    .build();
            b.active = it.active();
            addRenderableWidget(b);
        }
    }

    private void submit(Cheat cheat) {
        if (selected == null) return;
        ModNetwork.CHANNEL.sendToServer(new VotePacket(false, selected.id(), cheat.ordinal()));
        finished = true;
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Component head = selected == null
                ? Component.literal("Quem está usando cheat?")
                : Component.literal("Qual cheat " + selected.name() + " está usando?");
        g.drawCenteredString(font, head, width / 2, 16, 0xFFFFFF);
        if (selected == null) {
            String sub = players.isEmpty() ? "Nenhum outro jogador na partida." : "Botões desativados = já descobertos por você";
            g.drawCenteredString(font, sub, width / 2, 28, 0xAAAAAA);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Fecha por ordem do servidor (fim/timeout da votação) sem enviar skip. */
    public void closeFromServer() {
        finished = true;
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void removed() {
        if (!finished) {
            finished = true;
            ModNetwork.CHANNEL.sendToServer(VotePacket.skipVote());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // não pausa o servidor integrado (singleplayer/LAN)
    }
}
