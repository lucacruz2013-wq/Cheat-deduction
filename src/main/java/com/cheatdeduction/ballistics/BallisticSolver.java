package com.cheatdeduction.ballistics;

import com.cheatdeduction.tracking.LastLookTracker;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NO_MISS: escolhe o alvo com MENOR ÂNGULO em relação ao vetor de visão exato do atirador,
 * descartando qualquer entidade com blocos no caminho (hasLineOfSight + ClipContext COLLIDER
 * até o centro da hitbox). A última entidade olhada (LastLookTracker, 100 ticks) também entra
 * como candidata. A velocidade inicial é recalculada simulando a física vanilla de
 * AbstractArrow:  pos += v;  v *= 0.99;  v.y -= 0.05.
 * O módulo da velocidade original é preservado; a flecha segue a curva natural.
 */
public final class BallisticSolver {
    public static final double GRAVITY = 0.05D;
    public static final double DRAG = 0.99D;

    private static final double MAX_RANGE = 64.0D;
    private static final double MAX_CONE_DEGREES = 40.0D;
    private static final int MAX_TICKS = 300;
    private static final double HIT_TOLERANCE = 0.6D;
    private static final double MAX_ACCEPTED_MISS = 2.0D;

    private BallisticSolver() {}

    private record Solution(Vec3 velocity, int ticks) {}

    public static void aim(AbstractArrow arrow, ServerPlayer shooter) {
        Vec3 v0 = arrow.getDeltaMovement();
        double speed = v0.length();
        if (speed < 0.1D) return;

        LivingEntity target = pickTarget(shooter);
        if (target == null) return; // nenhum alvo visível: tiro vanilla

        Vec3 origin = arrow.position();
        double g = arrow.isNoGravity() ? 0.0D : GRAVITY;
        Vec3 targetMotion = new Vec3(target.getX() - target.xo,
                target.onGround() ? 0.0D : target.getY() - target.yo,
                target.getZ() - target.zo);

        Vec3 aimPoint = target.getBoundingBox().getCenter();
        Solution best = null;
        for (int i = 0; i < 3; i++) { // refina a antecipação do movimento do alvo
            Solution s = solve(origin, aimPoint, speed, g);
            if (s == null) break;
            best = s;
            aimPoint = target.getBoundingBox().getCenter().add(targetMotion.scale(s.ticks()));
        }
        if (best == null) return;

        Vec3 v = best.velocity();
        arrow.setDeltaMovement(v);
        float yaw = (float) (Mth.atan2(v.x, v.z) * (180.0D / Math.PI));
        float pitch = (float) (Mth.atan2(v.y, v.horizontalDistance()) * (180.0D / Math.PI));
        arrow.setYRot(yaw);
        arrow.setXRot(pitch);
        arrow.yRotO = yaw;
        arrow.xRotO = pitch;
    }

    /** Menor ângulo em relação ao lookVec do atirador, só entre alvos com linha de visão livre. */
    private static LivingEntity pickTarget(ServerPlayer shooter) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        LivingEntity last = LastLookTracker.server(shooter.getUUID()).getLast(shooter.level());

        AABB area = shooter.getBoundingBox().inflate(MAX_RANGE);
        List<LivingEntity> pool = shooter.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != shooter && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand));

        LivingEntity best = null;
        double bestAngle = Double.MAX_VALUE;
        for (LivingEntity e : pool) {
            double angle = angleTo(eye, look, e);
            if (angle < 0.0D) continue;
            if (e != last && angle > MAX_CONE_DEGREES) continue;
            if (angle < bestAngle && hasClearLine(shooter, e, eye)) {
                bestAngle = angle;
                best = e;
            }
        }
        // última entidade olhada que não está na lista (ex.: fora da área) ainda é candidata
        if (last != null && last != shooter && !pool.contains(last)) {
            double angle = angleTo(eye, look, last);
            if (angle >= 0.0D && angle < bestAngle && hasClearLine(shooter, last, eye)) best = last;
        }
        return best;
    }

    /** Ângulo (graus) entre o lookVec e o centro da hitbox; -1 se fora do alcance. */
    private static double angleTo(Vec3 eye, Vec3 look, LivingEntity e) {
        Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
        double dist = to.length();
        if (dist < 0.5D || dist > MAX_RANGE) return -1.0D;
        return Math.toDegrees(Math.acos(Mth.clamp(look.dot(to.scale(1.0D / dist)), -1.0D, 1.0D)));
    }

    /** Sem blocos/paredes entre o atirador e o alvo (olhos->olhos e olhos->centro da hitbox). */
    private static boolean hasClearLine(ServerPlayer shooter, LivingEntity target, Vec3 eye) {
        if (!shooter.hasLineOfSight(target)) return false;
        Vec3 center = target.getBoundingBox().getCenter();
        HitResult hit = shooter.level().clip(new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Procura o ângulo de elevação cuja trajetória passa mais perto de {@code target}. */
    private static Solution solve(Vec3 origin, Vec3 target, double speed, double g) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        double hx = h > 1.0E-6D ? dx / h : 1.0D;
        double hz = h > 1.0E-6D ? dz / h : 0.0D;

        // Varredura grossa: prefere a trajetória tensa (menor tempo de voo) que acerta.
        double bestPitch = 0.0D;
        double bestScore = Double.MAX_VALUE;
        for (double pitch = -85.0D; pitch <= 85.0D; pitch += 0.25D) {
            double[] r = simulate(origin, pitch, hx, hz, speed, g, target);
            double score = r[0] <= HIT_TOLERANCE ? r[1] : 1000.0D + r[0];
            if (score < bestScore) {
                bestScore = score;
                bestPitch = pitch;
            }
        }

        // Refinamento fino no mesmo ramo (minimiza a distância ao centro da hitbox).
        double refinedPitch = bestPitch;
        double bestDist = Double.MAX_VALUE;
        int bestTicks = 0;
        for (double pitch = bestPitch - 0.25D; pitch <= bestPitch + 0.25D; pitch += 0.005D) {
            double[] r = simulate(origin, pitch, hx, hz, speed, g, target);
            if (r[0] < bestDist) {
                bestDist = r[0];
                refinedPitch = pitch;
                bestTicks = (int) r[1];
            }
        }
        if (bestDist > MAX_ACCEPTED_MISS) return null; // fora de alcance: tiro vanilla

        double pr = Math.toRadians(refinedPitch);
        double c = Math.cos(pr);
        return new Solution(new Vec3(hx * c * speed, Math.sin(pr) * speed, hz * c * speed), bestTicks);
    }

    /** @return {menor distância da trajetória ao ponto, tick em que ocorreu}. */
    private static double[] simulate(Vec3 origin, double pitchDeg, double hx, double hz,
                                     double speed, double g, Vec3 target) {
        double pr = Math.toRadians(pitchDeg);
        double c = Math.cos(pr);
        double vx = hx * c * speed;
        double vy = Math.sin(pr) * speed;
        double vz = hz * c * speed;
        double px = origin.x, py = origin.y, pz = origin.z;

        double minDist = Double.MAX_VALUE;
        int minTick = 0;
        for (int t = 1; t <= MAX_TICKS; t++) {
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            double d = distSegmentPoint(px, py, pz, nx, ny, nz, target);
            if (d < minDist) {
                minDist = d;
                minTick = t;
            }
            px = nx; py = ny; pz = nz;
            vx *= DRAG; vy *= DRAG; vz *= DRAG;
            vy -= g;
            if (py < target.y - 48.0D && vy < 0.0D) break;
        }
        return new double[] { minDist, minTick };
    }

    private static double distSegmentPoint(double ax, double ay, double az,
                                           double bx, double by, double bz, Vec3 p) {
        double sx = bx - ax, sy = by - ay, sz = bz - az;
        double len2 = sx * sx + sy * sy + sz * sz;
        double t = len2 == 0.0D ? 0.0D
                : Mth.clamp(((p.x - ax) * sx + (p.y - ay) * sy + (p.z - az) * sz) / len2, 0.0D, 1.0D);
        double cx = ax + sx * t - p.x, cy = ay + sy * t - p.y, cz = az + sz * t - p.z;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}
