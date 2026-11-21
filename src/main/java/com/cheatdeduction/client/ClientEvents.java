package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.network.ModNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
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
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static final int ORE_RADIUS = 32;
    private static final int SCAN_INTERVAL_TICKS = 20;

    private static final double AIM_RANGE = 30.0D;
    private static final double AIM_SCREEN_FOV_DOT = 0.45D;
    private static final int AIM_TICKS_REQUIRED = 50;
    private static final int AIM_LOCK_DURATION = 70;
    private static final double BASE_FPS = 120.0D;
    private static final float SMOOTH_STEP_AT_120FPS = 0.22F;

    private record OreBox(BlockPos pos, float r, float g, float b) {}

    private static List<OreBox> ores = List.of();
    private static boolean miningOn;
    private static int scanCooldown;

    private static LivingEntity aimTarget;
    private static int aimTicks;
    private static int lockRemaining;
    private static boolean serverNotifiedLocked;
    private static long lastFrameNanos;

    private static int fallHurtSuppress;

    private ClientEvents() {}

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

        if (cheat == Cheat.FALLDAMAGE && fallHurtSuppress > 0) {
            fallHurtSuppress--;
            mc.player.hurtTime = 0;
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;

        long now = System.nanoTime();
        double dtSeconds = (lastFrameNanos == 0L) ? (1.0D / BASE_FPS) : (now - lastFrameNanos) / 1_000_000_000.0D;
        lastFrameNanos = now;
        dtSeconds = Mth.clamp(dtSeconds, 0.0005D, 0.05D);

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.isPaused() || mc.screen != null) return;
        if (ClientCheatState.get() != Cheat.AIM_LOCK || lockRemaining <= 0 || aimTarget == null || !aimTarget.isAlive()) {
            return;
        }

        // Normaliza a suavização para uma base fixa de 120 FPS (idêntico em 60Hz, 144Hz ou 165Hz)
        float alpha = (float) (1.0D - Math.pow(1.0D - SMOOTH_STEP_AT_120FPS, dtSeconds * BASE_FPS));
        smoothLockOn(p, aimTarget, e.renderTickTime, Mth.clamp(alpha, 0.02F, 1.0F));
    }

    private static void setLockedState(boolean locked) {
        if (serverNotifiedLocked != locked) {
            serverNotifiedLocked = locked;
            if (Minecraft.getInstance().getConnection() != null) {
                ModNetwork.sendAimLockState(locked);
            }
        }
    }

    private static void resetAim() {
        aimTarget = null;
        aimTicks = 0;
        lockRemaining = 0;
        setLockedState(false);
    }

    private static void resetAll() {
        resetAim();
        miningOn = false;
        ores = List.of();
        fallHurtSuppress = 0;
        lastFrameNanos = 0L;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientCheatState.set(null);
        resetAll();
    }

    @SubscribeEvent
    public static void onLocalFall(LivingFallEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.getEntity() == mc.player && ClientCheatState.get() == Cheat.FALLDAMAGE) {
            fallHurtSuppress = 10;
            e.setCanceled(true);
        }
    }

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
                        found.add(new OreBox(m.immutable(), col, col, col));
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

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            VertexConsumer vc = buffers.getBuffer(XrayRenderTypes.LINES_NO_DEPTH);
            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);
            for (OreBox o : ores) {
                LevelRenderer.renderLineBox(ps, vc, new AABB(o.pos()).inflate(0.002D), o.r(), o.g(), o.b(), 1.0F);
            }
            ps.popPose();
            buffers.endBatch(XrayRenderTypes.LINES_NO_DEPTH);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        Player p = e.getEntity();
        if (p.isSpectator() || !p.isCrouching()) return;
        boolean localWithCheat = p == Minecraft.getInstance().player && ClientCheatState.get() == Cheat.INVISIBLE;
        if (localWithCheat || p.isInvisible()) {
            e.setCanceled(true);
        }
    }

    private static void tickAimLock(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!p.isAlive() || p.isSpectator()) {
            resetAim();
            return;
        }
        if (mc.screen != null) return;

        if (lockRemaining <= 0) {
            setLockedState(false);
            LivingEntity bestOnScreen = findBestOnScreen(p);
            if (bestOnScreen == null) {
                aimTarget = null;
                aimTicks = 0;
                return;
            }

            aimTarget = bestOnScreen;
                       if (++aimTicks < AIM_TICKS_REQUIRED) return;

            lockRemaining = AIM_LOCK_DURATION;
            setLockedState(true);
        }

        if (aimTarget == null || !aimTarget.isAlive() || aimTarget.distanceTo(p) > AIM_RANGE + 5.0D) {
            resetAim();
            return;
        }

        setLockedState(true);
        if (--lockRemaining <= 0) resetAim();
    }

    private static LivingEntity findBestOnScreen(LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F).normalize();
        AABB box = p.getBoundingBox().inflate(AIM_RANGE);

        return p.level().getEntitiesOfClass(LivingEntity.class, box, e ->
                e != p && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand)
                        && e.distanceToSqr(p) <= AIM_RANGE * AIM_RANGE && p.hasLineOfSight(e)
        ).stream()
        .filter(e -> {
            Vec3 toCenter = e.getBoundingBox().getCenter().subtract(eye).normalize();
            return look.dot(toCenter) >= AIM_SCREEN_FOV_DOT;
        })
        .min(Comparator.comparingDouble(e -> {
            Vec3 toCenter = e.getBoundingBox().getCenter().subtract(eye).normalize();
            return 1.0D - look.dot(toCenter);
        }))
        .orElse(null);
    }

    private static void smoothLockOn(LocalPlayer p, LivingEntity target, float partialTick, float alpha) {
        Vec3 eye = p.getEyePosition(partialTick);
        Vec3 targetCenter = target.getPosition(partialTick).add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 d = targetCenter.subtract(eye);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);

        float targetYaw = (float) (Mth.atan2(d.z, d.x) * (180.0D / Math.PI)) - 90.0F;
        float targetPitch = Mth.clamp((float) (-(Mth.atan2(d.y, horizontal) * (180.0D / Math.PI))), -90.0F, 90.0F);

        float newYaw = Mth.rotLerp(alpha, p.getYRot(), targetYaw);
        float newPitch = Mth.lerp(alpha, p.getXRot(), targetPitch);

        p.setYRot(newYaw);
        p.setXRot(newPitch);
        p.setYHeadRot(newYaw);
        p.yRotO = newYaw;
        p.xRotO = newPitch;
    }
}


