package com.cheatdeduction.event;

import com.cheatdeduction.Cheat;
import com.cheatdeduction.CheatDeductionMod;
import com.cheatdeduction.ballistics.BallisticSolver;
import com.cheatdeduction.game.GameManager;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
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

    private CommonEvents() {}

    // ------------------------------------------------------------ tick (SPEED / INVISIBLE / FLY)

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

        // SPEED: +15% (MULTIPLY_TOTAL) apenas enquanto correndo
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            boolean want = cheat == Cheat.SPEED && p.isSprinting();
            boolean has = speed.getModifier(SPEED_ID) != null;
            if (want && !has) {
                speed.addTransientModifier(new AttributeModifier(SPEED_ID, "cheatdeduction_speed",
                        0.15D, AttributeModifier.Operation.MULTIPLY_TOTAL));
            } else if (!want && has) {
                speed.removeModifier(SPEED_ID);
            }
        }

        // INVISIBLE: invisível enquanto agachado
        if (cheat == Cheat.INVISIBLE) {
            boolean want = p.isCrouching();
            if (p.isInvisible() != want && !p.hasEffect(MobEffects.INVISIBILITY)) {
                p.setInvisible(want);
            }
        }

        // FLY: mayfly no survival
        if (cheat == Cheat.FLY && !p.isCreative() && !p.isSpectator()) {
            Abilities ab = p.getAbilities();
            if (!ab.mayfly) {
                ab.mayfly = true;
                p.onUpdateAbilities();
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
            default -> { }
        }
    }

    // ------------------------------------------------------------ FALLDAMAGE / KILLBOT

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        if (e.getEntity() instanceof ServerPlayer victim
                && e.getSource().is(DamageTypes.FALL)
                && GameManager.getCheat(victim.getUUID()) == Cheat.FALLDAMAGE) {
            e.setCanceled(true);
            return;
        }
        if (e.getSource().getEntity() instanceof ServerPlayer attacker
                && GameManager.getCheat(attacker.getUUID()) == Cheat.KILLBOT) {
            e.setAmount(Float.MAX_VALUE);
        }
    }

    // ------------------------------------------------------------ INVISIBLE (mobs)

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
        if (e.getEntity() instanceof ServerPlayer p) GameManager.onLogout(p);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        GameManager.reset();
    }
}
