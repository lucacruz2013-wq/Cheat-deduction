package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Lógica client-side: MINING (X-Ray), AIM_LOCK e render do INVISIBLE. */
@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static final int ORE_RADIUS = 20;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int AIM_TICKS_REQUIRED = 60; // 3 s
    private static final double AIM_RANGE = 48.0D;

    private record OreBox(BlockPos pos, float r, float g, float b) {}

    // MINING
    private static List<OreBox> ores = List.of();
    private static boolean miningOn;
    private static int scanCooldown;

    // AIM_LOCK
    private static LivingEntity aimTarget;
    private static int aimTicks;
    private static boolean aimLocked;

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

        if (cheat == Cheat.AIM_LOCK && !mc.isPaused()) tickAimLock(mc);
        else resetAim();
    }

    private static void resetAim() {
        aimTarget = null;
        aimTicks = 0;
        aimLocked = false;
    }

    private static void resetAll() {
        resetAim();
        miningOn = false;
        ores = List.of();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientCheatState.set(null);
        resetAll();
    }

    // ------------------------------------------------------------------ MINING (X-Ray)

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
        if (n.contains("coal")) return new float[] {0.4F, 0.4F, 0.4F};
        if (n.contains("redstone")) return new float[] {1.0F, 0.1F, 0.1F};
        if (n.contains("lapis")) return new float[] {0.2F, 0.35F, 1.0F};
        if (n.contains("copper")) return new float[] {1.0F, 0.5F, 0.2F};
        if (n.contains("quartz")) return new float[] {1.0F, 1.0F, 1.0F};
        if (n.contains("debris") || n.contains("netherite")) return new float[] {0.65F, 0.3F, 0.1F};
        return new float[] {1.0F, 0.0F, 1.0F};
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!miningOn || ores.isEmpty() || ClientCheatState.get() != Cheat.MINING) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(XrayRenderTypes.LINES_NO_DEPTH);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (OreBox o : ores) {
            LevelRenderer.renderLineBox(ps, vc, new AABB(o.pos()).inflate(0.002D), o.r(), o.g(), o.b(), 1.0F);
        }
        ps.popPose();
        buffers.endBatch(XrayRenderTypes.LINES_NO_DEPTH);
    }

    // ------------------------------------------------------------------ INVISIBLE (render)

    /**
     * Jogador invisível + agachado => cancela o render por completo (corpo, armadura,
     * itens e nametag). O servidor só deixa o jogador invisível agachado com o cheat INVISIBLE.
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        Player p = e.getEntity();
        if (p.isInvisible() && p.isCrouching() && !p.isSpectator()) {
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ AIM_LOCK

    private static void tickAimLock(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (mc.screen != null || !p.isAlive() || p.isSpectator()) {
            resetAim();
            return;
        }
        double fovHalf = Math.max(10.0D, mc.options.fov().get() / 2.0D);
        double breakAngle = Math.min(15.0D, fovHalf);

        // Já travado: continua mirando enquanto o alvo estiver perto do centro da mira.
        if (aimLocked) {
            if (aimTarget != null && isValidTarget(p, aimTarget) && angleTo(p, aimTarget) <= breakAngle) {
                snapTo(p, aimTarget);
                return;
            }
            aimLocked = false; // o jogador desviou a mira: libera
            aimTicks = 0;
        }

        LivingEntity candidate = null;
        if (aimTarget != null && isValidTarget(p, aimTarget) && angleTo(p, aimTarget) <= fovHalf) {
            candidate = aimTarget; // mantém o mesmo alvo enquanto ele estiver no FOV
        } else {
            double bestAngle = fovHalf;
            for (LivingEntity le : mc.level.getEntitiesOfClass(LivingEntity.class,
                    p.getBoundingBox().inflate(AIM_RANGE), x -> isValidTarget(p, x))) {
                double a = angleTo(p, le);
                if (a < bestAngle) {
                    bestAngle = a;
                    candidate = le;
                }
            }
        }

        if (candidate == null) {
            aimTarget = null;
            aimTicks = 0;
            return;
        }
        if (candidate != aimTarget) {
            aimTarget = candidate;
            aimTicks = 0;
        } else if (++aimTicks >= AIM_TICKS_REQUIRED) {
            aimLocked = true;
            snapTo(p, candidate);
        }
    }

    private static boolean isValidTarget(LocalPlayer p, LivingEntity e) {
        return e != p && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand)
                && p.distanceToSqr(e) <= AIM_RANGE * AIM_RANGE && p.hasLineOfSight(e);
    }

    private static double angleTo(LocalPlayer p, LivingEntity e) {
        Vec3 to = e.getBoundingBox().getCenter().subtract(p.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(Mth.clamp(p.getLookAngle().dot(to), -1.0D, 1.0D)));
    }

    /** Rotaciona a câmera para o centro da hitbox (Entity#lookAt também zera a interpolação). */
    private static void snapTo(LocalPlayer p, LivingEntity target) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
    }
}
