package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.tracking.LastLookTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Lógica client-side: MINING (X-Ray), AIM_LOCK, render do INVISIBLE e tilt do FALLDAMAGE. */
@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static final int ORE_RADIUS = 32;
    private static final int SCAN_INTERVAL_TICKS = 20;

    private static final double AIM_RANGE = 25.0D;
    private static final int AIM_TICKS_REQUIRED = 60;   // 3 s olhando
    private static final int AIM_LOCK_DURATION = 40;    // 2 s travado

    private record OreBox(BlockPos pos, float r, float g, float b) {}

    // MINING
    private static List<OreBox> ores = List.of();
    private static boolean miningOn;
    private static int scanCooldown;

    // AIM_LOCK
    private static LivingEntity aimTarget;
    private static int aimTicks;
    private static int lockRemaining;

    // FALLDAMAGE (fallback visual)
    private static int fallHurtSuppress;

    private ClientEvents() {}

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (ClientModEvents.MINING_KEY.consumeClick()) {
            if (mc.player != null && ClientCheatState.get() == Cheat.MINING) {
                miningOn = !miningOn;
                scanCooldown = 0;
                mc.player.displayClientMessage(Component.literal("Mining X-Ray: " + (miningOn ? "ON" : "OFF")), true);
            }
        }

        if (mc.player == null || mc.level == null) {
            resetAll();
            return;
        }

        Cheat cheat = ClientCheatState.get();

        // MINING
        if (cheat != Cheat.MINING) {
            miningOn = false;
            ores = List.of();
        } else if (miningOn) {
            if (--scanCooldown <= 0) {
                scanCooldown = SCAN_INTERVAL_TICKS;
                scanOres(mc);
            }
        } else {
            ores = List.of();
        }

        // AIM_LOCK
        if (cheat == Cheat.AIM_LOCK && !mc.isPaused()) tickAimLock(mc);
        else resetAim();

        // FALLDAMAGE: zera o hurtTime só logo após uma queda (não esconde dano de mobs).
        // hurtDuration NÃO é zerado: GameRenderer#bobHurt divide por ele (risco de NaN na câmera).
        if (cheat == Cheat.FALLDAMAGE && fallHurtSuppress > 0) {
            fallHurtSuppress--;
            mc.player.hurtTime = 0;
        }
    }

    private static void resetAim() {
        aimTarget = null;
        aimTicks = 0;
        lockRemaining = 0;
    }

    private static void resetAll() {
        resetAim();
        miningOn = false;
        ores = List.of();
        fallHurtSuppress = 0;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientCheatState.set(null);
        resetAll();
    }

    // ------------------------------------------------------------------ FALLDAMAGE (cliente)

    @SubscribeEvent
    public static void onLocalFall(LivingFallEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.getEntity() == mc.player && ClientCheatState.get() == Cheat.FALLDAMAGE) {
            fallHurtSuppress = 10;
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ MINING (X-Ray, raio 32)

    private static void scanOres(Minecraft mc) {
        List<OreBox> found = new ArrayList<>();
        BlockPos c = mc.player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int r2 = ORE_RADIUS * ORE_RADIUS;
        for (int x = -ORE_RADIUS; x <= ORE_RADIUS; x++) {
            for (int y = -ORE_RADIUS; y <= ORE_RADIUS; y++) {
                for (int z = -ORE_RADIUS; z <= ORE_RADIUS; z++) {
                    if (x * x + y * y + z * z > r2) continue;
                    m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                    BlockState st = mc.level.getBlockState(m);
                    if (st.isAir()) continue;
                    if (st.is(Tags.Blocks.ORES)) {
                        float[] col = colorFor(st);
                        found.add(new OreBox(m.immutable(), col[0], col[1], col[2]));
                    }
                }
            }
        }
        ores = found;
    }

    private static float[] colorFor(BlockState st) {
        var key = ForgeRegistries.BLOCKS.getKey(st.getBlock());
        String n = key == null ? "" : key.getPath();
        if (n.contains("diamond")) return new float[] {0.2F, 1.0F, 1.0F};
        if (n.contains("emerald")) return new float[] {0.1F, 1.0F, 0.2F};
        if (n.contains("gold")) return new float[] {1.0F, 0.85F, 0.1F};
        if (n.contains("iron")) return new float[] {0.95F, 0.7F, 0.55F};
        if (n.contains("coal")) return new float[] {0.45F, 0.45F, 0.45F};
        if (n.contains("redstone")) return new float[] {1.0F, 0.1F, 0.1F};
        if (n.contains("lapis")) return new float[] {0.2F, 0.35F, 1.0F};
        if (n.contains("copper")) return new float[] {1.0F, 0.5F, 0.2F};
        if (n.contains("quartz")) return new float[] {1.0F, 1.0F, 1.0F};
        if (n.contains("debris") || n.contains("netherite")) return new float[] {0.65F, 0.3F, 0.1F};
        return new float[] {1.0F, 0.0F, 1.0F};
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!miningOn || ores.isEmpty() || ClientCheatState.get() != Cheat.MINING) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Profundidade desligada ANTES de desenhar e reativada DEPOIS do endBatch
        // (as linhas só vão para a GPU no endBatch).
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            VertexConsumer vc = buffers.getBuffer(XrayRenderTypes.LINES_NO_DEPTH);
            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);
            for (OreBox o : ores) {
                // alpha = 1.0f: contornos nítidos e brilhantes através de qualquer parede
                LevelRenderer.renderLineBox(ps, vc, new AABB(o.pos()).inflate(0.002D), o.r(), o.g(), o.b(), 1.0F);
            }
            ps.popPose();
            buffers.endBatch(XrayRenderTypes.LINES_NO_DEPTH);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    // ------------------------------------------------------------------ INVISIBLE (render)

    /**
     * Agachado + invisível => cancela o render por completo (corpo, armadura, itens nas mãos e
     * nametag). O servidor só deixa o jogador invisível agachado com o cheat INVISIBLE; para o
     * jogador local usamos também o estado do cheat sincronizado.
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        Player p = e.getEntity();
        if (p.isSpectator() || !p.isCrouching()) return;
        boolean localWithCheat = p == Minecraft.getInstance().player && ClientCheatState.get() == Cheat.INVISIBLE;
        if (localWithCheat || p.isInvisible()) {
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ AIM_LOCK

    /**
     * Mantendo a mira (ProjectileUtil, 25 blocos, linha de visão livre) na mesma entidade viva
     * (mobs OU outros jogadores) por 60 ticks => trava a câmera no centro da hitbox por 40 ticks.
     * Depois solta a câmera e zera o contador.
     */
    private static void tickAimLock(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!p.isAlive() || p.isSpectator()) {
            resetAim();
            return;
        }
        if (mc.screen != null) return; // menu aberto: não mexe na câmera

        if (lockRemaining <= 0) {
            LivingEntity looked = lookedEntity(p);
            if (looked == null) {
                aimTarget = null;
                aimTicks = 0;
                return;
            }
            if (looked != aimTarget) {
                aimTarget = looked;
                aimTicks = 0;
                return;
            }
            if (++aimTicks < AIM_TICKS_REQUIRED) return;
            lockRemaining = AIM_LOCK_DURATION; // inicia a trava de 2 s
        }

        // trava ativa
        if (aimTarget == null || !aimTarget.isAlive()) {
            resetAim();
            return;
        }
        lockOn(p, aimTarget);
        if (--lockRemaining <= 0) resetAim(); // solta a câmera e zera o contador
    }

    /** Entidade viva (inclui Players, exceto o próprio) na cruz de mira, 25 blocos, sem blocos no meio. */
    private static LivingEntity lookedEntity(LocalPlayer p) {
        EntityHitResult hit = LastLookTracker.raycast(p, AIM_RANGE);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity le) || le == p) return null;
        return p.hasLineOfSight(le) ? le : null;
    }

    /** Aponta a câmera para o centro da hitbox via setYRot/setXRot. */
    private static void lockOn(LocalPlayer p, LivingEntity target) {
        Vec3 d = target.getBoundingBox().getCenter().subtract(p.getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, horizontal) * (180.0D / Math.PI)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }
}
