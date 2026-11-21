package com.cheatdeduction.event;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.ballistics.BallisticSolver;
import com.cheatdeduction.game.GameManager;
import com.cheatdeduction.tracking.LastLookTracker;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.Brain;
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
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Lógica de servidor dos cheats (roda também no servidor integrado). */
@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID)
public final class CommonEvents {
    private static final UUID SPEED_ID = UUID.fromString("7d1c3f0e-6a52-4a2f-9a43-5c1d2e8b9a10");
    private static final String NOMISS_TAG = "cheatdeduction_nomiss_done";
    private static final double MOB_SWEEP_RADIUS = 32.0D;

    private CommonEvents() {}

    // ------------------------------------------------------------ tick

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

          // SPEED: +18% (MULTIPLY_TOTAL) apenas enquanto correndo
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


        // INVISIBLE: invisível enquanto agachado + mobs próximos esquecem o jogador
        if (cheat == Cheat.INVISIBLE) {
            boolean crouching = p.isCrouching();
            if (p.isInvisible() != crouching && !p.hasEffect(MobEffects.INVISIBILITY)) {
                p.setInvisible(crouching);
            }
            if (crouching) blindMobs(p);
        }

        // FLY: mayfly no survival
        if (cheat == Cheat.FLY && !p.isCreative() && !p.isSpectator()) {
            Abilities ab = p.getAbilities();
            if (!ab.mayfly) {
                ab.mayfly = true;
                p.onUpdateAbilities();
            }
        }

        // NO_MISS: registra a última entidade olhada (raio de 40 blocos, memória de 100 ticks)
        if (cheat == Cheat.NO_MISS) {
            LastLookTracker.server(p.getUUID()).update(p);
        }
    }

    /**
     * Mobs a 32 blocos que miram o jogador agachado esquecem dele usando APENAS
     * setTarget(null), setLastHurtByMob(null) e getNavigation().stop(). O Brain só é tocado dentro
     * de try-catch e apenas MemoryModuleType.ATTACK_TARGET (após hasMemoryValue).
     */
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
                // mobs sem Brain utilizável: ignora
            }
            if (brainTarget || mob.getTarget() == p || mob.getLastHurtByMob() == p) {
                mob.setTarget(null);
                mob.setLastHurtByMob(null);
                mob.getNavigation().stop();
            }
        }
    }

    /** Desfaz o efeito de um cheat quando ele é removido/trocado. */
    public static void removeEffects(ServerPlayer p, Cheat old) {
        switch (old) {
            case SPEED -> {
                AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
                if (a != null) a.removeModifier(SPEED_ID);
            }
            case INVISIBLE -> {
                if (!p.hasEffect(MobEffects.INVISIBILITY)) p.setInvisible(false);
            }
            case FLY -> {
                if (!p.isCreative() && !p.isSpectator()) {
                    Abilities ab = p.getAbilities();
                    ab.mayfly = false;
                    ab.flying = false;
                    p.onUpdateAbilities();
                }
            }
            case NO_MISS -> LastLookTracker.removeServer(p.getUUID());
            default -> { }
        }
    }

    // ------------------------------------------------------------ FALLDAMAGE (sem dano, sem tremor)

    /** Cancela antes de LivingEntity#hurt: sem dano, sem animação de hurt e sem tilt de câmera. */
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (e.getEntity() instanceof ServerPlayer p
                && e.getSource().is(DamageTypes.FALL)
                && GameManager.getCheat(p.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
        }
    }

    /** Cancela o cálculo de dano de queda (evita também o som de impacto e o hurt). */
    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (e.getEntity() instanceof ServerPlayer p
                && GameManager.getCheat(p.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------ FALLDAMAGE (fallback) / KILLBOT

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        DamageSource source = e.getSource();

        if (e.getEntity() instanceof ServerPlayer victim
                && source.is(DamageTypes.FALL)
                && GameManager.getCheat(victim.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
            return;
        }

        // KILLBOT (nerf): sem instakill por projéteis e nunca em outros jogadores
        if (source.getEntity() instanceof ServerPlayer attacker
                && GameManager.getCheat(attacker.getUUID()) == Cheat.KILLBOT
                && !source.is(DamageTypeTags.IS_PROJECTILE)
                && !(e.getEntity() instanceof Player)) {
            e.setAmount(Float.MAX_VALUE);
        }
    }

    // ------------------------------------------------------------ INVISIBLE (alvos de mobs)

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent e) {
        if (e.getNewTarget() instanceof ServerPlayer p
                && GameManager.getCheat(p.getUUID()) == Cheat.INVISIBLE
                && p.isCrouching()) {
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------ NO_MISS

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof ServerPlayer shooter)) return;
        if (GameManager.getCheat(shooter.getUUID()) != Cheat.NO_MISS) return;
        if (arrow.getPersistentData().getBoolean(NOMISS_TAG)) return;
        arrow.getPersistentData().putBoolean(NOMISS_TAG, true);
        BallisticSolver.aim(arrow, shooter);
    }

    // ------------------------------------------------------------ fim de partida

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof EnderDragon dragon && !dragon.level().isClientSide) {
            MinecraftServer server = dragon.getServer();
            if (server != null) GameManager.onDragonDeath(server);
        }
    }

    // ------------------------------------------------------------ conexão

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
