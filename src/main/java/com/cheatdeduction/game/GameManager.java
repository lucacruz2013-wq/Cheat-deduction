package com.cheatdeduction.game;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.event.CommonEvents;
import com.cheatdeduction.network.ModNetwork;
import com.cheatdeduction.network.VotingPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Estado da partida (somente lado servidor / servidor integrado). */
public final class GameManager {
    public static final int ROUND_TICKS = 12000;          // 10 minutos
    public static final int VOTING_TIMEOUT_TICKS = 1200;  // 60 s para votar

    private static final Map<UUID, Cheat> CHEATS = new HashMap<>();
    private static final Map<UUID, Integer> POINTS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> DISCOVERED = new HashMap<>(); // votante -> já descobertos
    private static final Set<UUID> PARTICIPANTS = new LinkedHashSet<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Set<UUID> VOTED = new HashSet<>();

    private static boolean active;
    private static boolean voting;
    private static int remaining;
    private static int votingTicks;
    private static ServerBossEvent bar;

    private GameManager() {}

    // ------------------------------------------------------------------ consultas

    @Nullable
    public static Cheat getCheat(UUID id) { return CHEATS.get(id); }
    public static boolean isActive() { return active; }

    // ------------------------------------------------------------------ cheats

    /** Atribui (ou remove, com null) o cheat de um jogador e sincroniza com o cliente. */
    public static void assignCheat(ServerPlayer p, @Nullable Cheat cheat) {
        Cheat old = CHEATS.get(p.getUUID());
        if (cheat == null) CHEATS.remove(p.getUUID()); else CHEATS.put(p.getUUID(), cheat);
        if (old != null && old != cheat) CommonEvents.removeEffects(p, old);
        ModNetwork.sendCheat(p, cheat);
    }

    // ------------------------------------------------------------------ /startcheat

    /** @return mensagem de erro, ou null em caso de sucesso. */
    @Nullable
    public static String start(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        if (players.isEmpty()) return "Nenhum jogador online.";
        if (players.size() > Cheat.VALUES.length) {
            return "Há " + players.size() + " jogadores online, mas só existem " + Cheat.VALUES.length + " cheats únicos.";
        }
        stopInternal(server);

        List<Cheat> pool = new ArrayList<>(List.of(Cheat.VALUES));
        Collections.shuffle(pool);

        bar = new ServerBossEvent(Component.literal("Tempo restante: 10:00"),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1.0F);

        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            UUID id = p.getUUID();
            PARTICIPANTS.add(id);
            NAMES.put(id, p.getGameProfile().getName());
            POINTS.put(id, 0);
            assignCheat(p, pool.get(i));
            bar.addPlayer(p);
            p.sendSystemMessage(Component.literal("Seu cheat é: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(pool.get(i).displayName()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        }
        active = true;
        voting = false;
        remaining = ROUND_TICKS;
        updateBar();
        broadcast(server, Component.literal("A partida começou! Descubra quem usa qual cheat. Mate o Ender Dragon para vencer.")
                .withStyle(ChatFormatting.YELLOW));
        return null;
    }

    // ------------------------------------------------------------------ tick / timer

    public static void serverTick(MinecraftServer server) {
        if (!active) return;
        if (voting) {
            if (++votingTicks >= VOTING_TIMEOUT_TICKS) finishVoting(server);
            return;
        }
        remaining--;
        if (remaining % 20 == 0) updateBar();
        if (remaining <= 0) startVoting(server);
    }

    private static void updateBar() {
        if (bar == null) return;
        int secs = Math.max(0, remaining / 20);
        bar.setName(Component.literal(String.format("Tempo restante: %02d:%02d", secs / 60, secs % 60)));
        bar.setProgress(Mth.clamp(remaining / (float) ROUND_TICKS, 0.0F, 1.0F));
    }

    // ------------------------------------------------------------------ /autovote

    /** Pausa o timer e abre a tela de votação. @return erro ou null. */
    @Nullable
    public static String startVoting(MinecraftServer server) {
        if (!active) return "Nenhuma partida em andamento. Use /startcheat.";
        if (voting) return "Já existe uma votação em andamento.";
        voting = true;
        votingTicks = 0;
        VOTED.clear();
        if (bar != null) {
            bar.setName(Component.literal("Votação em andamento..."));
            bar.setColor(BossEvent.BossBarColor.PURPLE);
        }
        for (ServerPlayer p : onlineParticipants(server)) {
            List<VotingPacket.Entry> list = new ArrayList<>();
            for (ServerPlayer other : onlineParticipants(server)) {
                if (other != p) list.add(new VotingPacket.Entry(other.getUUID(), other.getGameProfile().getName()));
            }
            Set<UUID> known = new HashSet<>(DISCOVERED.getOrDefault(p.getUUID(), Set.of()));
            ModNetwork.sendVoting(p, new VotingPacket(true, list, known));
        }
        broadcast(server, Component.literal("Votação aberta! O timer foi pausado.").withStyle(ChatFormatting.LIGHT_PURPLE));
        checkRoundComplete(server);
        return null;
    }

    public static void handleVote(ServerPlayer voter, @Nullable UUID accusedId, int cheatId) {
        MinecraftServer server = voter.getServer();
        if (server == null || !active || !voting) return;
        UUID vid = voter.getUUID();
        if (!PARTICIPANTS.contains(vid) || !VOTED.add(vid)) return; // 1 voto por rodada
        if (accusedId != null) resolveGuess(server, voter, accusedId, Cheat.byId(cheatId));
        checkRoundComplete(server);
    }

    private static void resolveGuess(MinecraftServer server, ServerPlayer voter, UUID accusedId, @Nullable Cheat guess) {
        ServerPlayer accused = server.getPlayerList().getPlayer(accusedId);
        Set<UUID> known = DISCOVERED.computeIfAbsent(voter.getUUID(), k -> new HashSet<>());
        if (accused == null || guess == null || !PARTICIPANTS.contains(accusedId)
                || accusedId.equals(voter.getUUID()) || known.contains(accusedId)) {
            voter.sendSystemMessage(Component.literal("Voto inválido.").withStyle(ChatFormatting.RED));
            return;
        }
        if (CHEATS.get(accusedId) != guess) {
            voter.sendSystemMessage(Component.literal("Palpite errado!").withStyle(ChatFormatting.RED));
            return;
        }
        int pts = POINTS.merge(voter.getUUID(), 1, Integer::sum);
        known.add(accusedId);

        broadcast(server, Component.literal("")
                .append(Component.literal(voter.getGameProfile().getName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" descobriu o cheat de ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(accused.getGameProfile().getName()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal("!").withStyle(ChatFormatting.YELLOW)));
        voter.sendSystemMessage(Component.literal("Você acertou! Pontos: " + pts).withStyle(ChatFormatting.GREEN));

        // Penalidade: volta ao spawn, mantém o cheat e continua descobrível por outros jogadores.
        teleportToSpawn(server, accused);
    }

    private static void checkRoundComplete(MinecraftServer server) {
        for (ServerPlayer p : onlineParticipants(server)) {
            if (!VOTED.contains(p.getUUID())) return;
        }
        finishVoting(server);
    }

    private static void finishVoting(MinecraftServer server) {
        if (!voting) return;
        voting = false;
        closeVotingScreens(server);
        remaining = ROUND_TICKS;
        if (bar != null) bar.setColor(BossEvent.BossBarColor.GREEN);
        updateBar();
        broadcast(server, Component.literal("Votação encerrada. O timer de 10 minutos foi reiniciado.").withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    private static void closeVotingScreens(MinecraftServer server) {
        for (ServerPlayer p : onlineParticipants(server)) ModNetwork.sendVoting(p, VotingPacket.close());
    }

    // ------------------------------------------------------------------ penalidade

    private static void teleportToSpawn(MinecraftServer server, ServerPlayer p) {
        ServerLevel level = null;
        Vec3 pos = null;
        BlockPos respawn = p.getRespawnPosition();
        if (respawn != null) {
            ServerLevel rl = server.getLevel(p.getRespawnDimension());
            if (rl != null) {
                Optional<Vec3> found = Player.findRespawnPositionAndUseSpawnBlock(
                        rl, respawn, p.getRespawnAngle(), p.isRespawnForced(), false);
                if (found.isPresent()) { level = rl; pos = found.get(); }
            }
        }
        if (pos == null) {
            level = server.overworld();
            BlockPos spawn = level.getSharedSpawnPos();
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
            pos = Vec3.atBottomCenterOf(top);
        }
        p.teleportTo(level, pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
        p.fallDistance = 0.0F;
    }

    // ------------------------------------------------------------------ fim de jogo

    public static void onDragonDeath(MinecraftServer server) {
        if (!active) return;
        int max = 0;
        for (int v : POINTS.values()) max = Math.max(max, v);

        List<String> winners = new ArrayList<>();
        for (UUID id : PARTICIPANTS) {
            if (POINTS.getOrDefault(id, 0) == max) winners.add(NAMES.getOrDefault(id, "?"));
        }
        if (max == 0) {
            broadcast(server, Component.literal("O Ender Dragon caiu! Ninguém descobriu nenhum cheat — sem vencedor.")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            broadcast(server, Component.literal("O Ender Dragon caiu! Vencedor: " + String.join(", ", winners)
                    + " com " + max + " ponto(s)!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        List<UUID> ranking = new ArrayList<>(PARTICIPANTS);
        ranking.sort((a, b) -> Integer.compare(POINTS.getOrDefault(b, 0), POINTS.getOrDefault(a, 0)));
        for (UUID id : ranking) {
            broadcast(server, Component.literal(" - " + NAMES.getOrDefault(id, "?") + ": " + POINTS.getOrDefault(id, 0))
                    .withStyle(ChatFormatting.GRAY));
        }
        stopInternal(server);
    }

    private static void stopInternal(MinecraftServer server) {
        if (active) closeVotingScreens(server);
        if (bar != null) { bar.removeAllPlayers(); bar = null; }
        for (UUID id : new ArrayList<>(CHEATS.keySet())) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) assignCheat(sp, null);
        }
        CHEATS.clear();
        POINTS.clear();
        DISCOVERED.clear();
        PARTICIPANTS.clear();
        NAMES.clear();
        VOTED.clear();
        active = false;
        voting = false;
    }

    /** Chamado quando o servidor para (evita vazar estado entre mundos no singleplayer). */
    public static void reset() {
        CHEATS.clear(); POINTS.clear(); DISCOVERED.clear(); PARTICIPANTS.clear(); NAMES.clear(); VOTED.clear();
        bar = null; active = false; voting = false;
    }

    // ------------------------------------------------------------------ login / logout

    public static void onLogin(ServerPlayer p) {
        ModNetwork.sendCheat(p, CHEATS.get(p.getUUID()));
        if (active && bar != null && PARTICIPANTS.contains(p.getUUID())) bar.addPlayer(p);
    }

    public static void onLogout(ServerPlayer p) {
        if (bar != null) bar.removePlayer(p);
        MinecraftServer server = p.getServer();
        if (server != null && active && voting && PARTICIPANTS.contains(p.getUUID())) {
            VOTED.add(p.getUUID());
            checkRoundComplete(server);
        }
    }

    // ------------------------------------------------------------------ util

    private static List<ServerPlayer> onlineParticipants(MinecraftServer server) {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : PARTICIPANTS) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) list.add(sp);
        }
        return list;
    }

    private static void broadcast(MinecraftServer server, Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }
}
