package com.cheatdeduction.event;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.ballistics.BallisticSolver;
import com.cheatdeduction.game.GameManager;
import com.cheatdeduction.tracking.LastLookTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID)
public final class CommonEvents {
    private static final UUID SPEED_ID = UUID.fromString("7d1c3f0e-6a52-4a2f-9a43-5c1d2e8b9a10");
    private static final String NOMISS_TAG = "cheatdeduction_nomiss_done";
    private static final double MOB_SWEEP_RADIUS = 32.0D;
    private static final Map<UUID, Float> FLY_FALL_DIST = new HashMap<>();

    private CommonEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) GameManager.serverTick(server);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p)) return;
        Cheat cheat = GameManager.getCheat(p.getUUID());

        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            boolean want = cheat == Cheat.SPEED && p.isSprinting();
            boolean has = speed.getModifier(SPEED_ID) != null;
            if (want && !has) {
                speed.addTransientModifier(new AttributeModifier(SPEED_ID, "cheatdeduction_speed",
                        0.18D, AttributeModifier.Operation.MULTIPLY_TOTAL));
            } else if (!want && has) {
                speed.removeModifier(SPEED_ID);
            }
        }

        if (cheat == Cheat.INVISIBLE) {
            if (p.isCrouching()) {
                p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 5, 0, false, false, false));
                blindMobs(p);
            } else {
                p.removeEffect(MobEffects.INVISIBILITY);
            }
        }

        if (!p.isCreative() && !p.isSpectator()) {
            Abilities ab = p.getAbilities();
            boolean wantFly = (cheat == Cheat.FLY);
            float targetFlySpeed = wantFly ? 0.022F : 0.05F;
            if (ab.mayfly != wantFly || ab.getFlyingSpeed() != targetFlySpeed) {
                ab.mayfly = wantFly;
                if (!wantFly) ab.flying = false;
                ab.setFlyingSpeed(targetFlySpeed);
                p.onUpdateAbilities();
            }

            if (wantFly) {
                UUID id = p.getUUID();
                if (ab.flying || p.isInWater() || p.isInLava() || p.onClimbable()) {
                    FLY_FALL_DIST.remove(id);
                } else if (!p.onGround()) {
                    double dy = p.getDeltaMovement().y;
                    if (dy < 0.0D) {
                        FLY_FALL_DIST.merge(id, (float) (-dy), Float::sum);
                    }
                } else {
                    Float fallen = FLY_FALL_DIST.remove(id);
                    if (fallen != null && fallen > 3.0F) {
                        p.hurt(p.damageSources().fall(), fallen - 3.0F);
                    }
                }
            } else {
                FLY_FALL_DIST.remove(p.getUUID());
            }
        }

        if (cheat == Cheat.NO_MISS) {
            LastLookTracker.server(p.getUUID()).update(p);
        }
    }

    private static void blindMobs(ServerPlayer p) {
        AABB area = p.getBoundingBox().inflate(MOB_SWEEP_RADIUS);
        double maxSqr = MOB_SWEEP_RADIUS * MOB_SWEEP_RADIUS;
        for (Mob mob : p.serverLevel().getEntitiesOfClass(Mob.class, area, m -> m.distanceToSqr(p) <= maxSqr)) {
            boolean brainTarget = false;
            try {
                Brain<?> brain = mob.getBrain();
                if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                    brainTarget = brain.getMemory(MemoryModuleType.ATTACK_TARGET).filter(t -> t == p).isPresent();
                    if (brainTarget) brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                }
            } catch (Exception ignored) {
            }
            if (brainTarget || mob.getTarget() == p || mob.getLastHurtByMob() == p) {
                mob.setTarget(null);
                mob.setLastHurtByMob(null);
                mob.getNavigation().stop();
            }
        }
    }

    public static void removeEffects(ServerPlayer p, Cheat old) {
        switch (old) {
            case SPEED -> {
                AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
                if (a != null) a.removeModifier(SPEED_ID);
            }
            case INVISIBLE -> p.removeEffect(MobEffects.INVISIBILITY);
            case FLY -> {
                FLY_FALL_DIST.remove(p.getUUID());
                if (!p.isCreative() && !p.isSpectator()) {
                    Abilities ab = p.getAbilities();
                    ab.mayfly = false;
                    ab.flying = false;
                    ab.setFlyingSpeed(0.05F);
                    p.onUpdateAbilities();
                }
            }
            case NO_MISS -> LastLookTracker.removeServer(p.getUUID());
            default -> { }
        }
    }

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (e.getEntity() instanceof ServerPlayer p
                && e.getSource().is(DamageTypes.FALL)
                && GameManager.getCheat(p.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (e.getEntity() instanceof ServerPlayer p
                && GameManager.getCheat(p.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        DamageSource source = e.getSource();

        if (e.getEntity() instanceof ServerPlayer victim
                && source.is(DamageTypes.FALL)
                && GameManager.getCheat(victim.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
            return;
        }

        if (source.getEntity() instanceof ServerPlayer attacker
                && GameManager.getCheat(attacker.getUUID()) == Cheat.KILLBOT
                && !source.is(DamageTypeTags.IS_PROJECTILE)
                && !(e.getEntity() instanceof Player)) {
            e.setAmount(Float.MAX_VALUE);
        }
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent e) {
        if (e.getNewTarget() instanceof ServerPlayer p
                && GameManager.getCheat(p.getUUID()) == Cheat.INVISIBLE
                && p.isCrouching()) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof ServerPlayer shooter)) return;
        if (GameManager.getCheat(shooter.getUUID()) != Cheat.NO_MISS) return;
        if (arrow.getPersistentData().getBoolean(NOMISS_TAG)) return;
        arrow.getPersistentData().putBoolean(NOMISS_TAG, true);
        BallisticSolver.aim(arrow, shooter);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof EnderDragon dragon && !dragon.level().isClientSide) {
            MinecraftServer server = dragon.getServer();
            if (server != null) GameManager.onDragonDeath(server);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) GameManager.onLogin(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            LastLookTracker.removeServer(p.getUUID());
            GameManager.onLogout(p);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        LastLookTracker.clearServer();
        GameManager.reset();
    }
}
