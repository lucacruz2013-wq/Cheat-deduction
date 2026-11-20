package com.cheatdeduction.tracking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Guarda a última entidade viva que esteve na linha de mira direta do jogador
 * (ProjectileUtil.getEntityHitResult, alcance de 40 blocos) pelos últimos 100 ticks.
 * Servidor: uma instância por jogador (usada pelo NO_MISS).
 * Cliente: uma instância para o jogador local (usada pelo AIM_LOCK).
 */
public final class LastLookTracker {
    public static final double RANGE = 40.0D;
    public static final int MEMORY_TICKS = 100; // 5 s

    private static final Map<UUID, LastLookTracker> SERVER = new HashMap<>();

    private int entityId = -1;
    private long seenTime;

    // ------------------------------------------------------------ registro do servidor

    public static LastLookTracker server(UUID playerId) {
        return SERVER.computeIfAbsent(playerId, k -> new LastLookTracker());
    }

    public static void removeServer(UUID playerId) { SERVER.remove(playerId); }

    public static void clearServer() { SERVER.clear(); }

    // ------------------------------------------------------------ raycast

    /** Entidade viva diretamente na mira (bloqueada por blocos), ou null. */
    @Nullable
    public static EntityHitResult raycast(Player p, double range) {
        Vec3 start = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        Vec3 end = start.add(look.scale(range));

        double maxDistSqr = range * range;
        HitResult block = p.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
            maxDistSqr = start.distanceToSqr(end);
        }

        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D);
        return ProjectileUtil.getEntityHitResult(p, start, end, box,
                e -> e instanceof LivingEntity le && le.isAlive() && !le.isSpectator() && le.isPickable(),
                maxDistSqr);
    }

    // ------------------------------------------------------------ estado

    /** Atualiza o rastreador; devolve a entidade atualmente na mira (ou null). */
    @Nullable
    public LivingEntity update(Player p) {
        EntityHitResult hit = raycast(p, RANGE);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity le)) return null;
        entityId = le.getId();
        seenTime = p.level().getGameTime();
        return le;
    }

    /** Última entidade olhada nos últimos 100 ticks, se ainda estiver viva. */
    @Nullable
    public LivingEntity getLast(Level level) {
        if (entityId < 0 || level.getGameTime() - seenTime > MEMORY_TICKS) return null;
        Entity e = level.getEntity(entityId);
        return e instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    public void clear() { entityId = -1; }
}
