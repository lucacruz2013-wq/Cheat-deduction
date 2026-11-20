package com.cheatdeduction.ballistics;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * NO_MISS: recalcula a velocidade inicial da flecha para acertar o alvo vivo mais próximo
 * na linha de visão, simulando exatamente a física vanilla de AbstractArrow:
 *   pos += v;  v *= 0.99;  v.y -= 0.05
 * A flecha continua seguindo a curva balística natural (só a velocidade inicial muda;
 * o módulo da velocidade original é preservado).
 */
public final class BallisticSolver {
    public static final double GRAVITY = 0.05D;
    public static final double DRAG = 0.99D;

    private static final double MAX_RANGE = 64.0D;
    private static final int MAX_TICKS = 300;
    private static final double HIT_TOLERANCE = 0.6D;   // distância ao centro para considerar "acerto"
    private static final double MAX_ACCEPTED_MISS = 2.0D;
    private static final double CONE_DOT = 0.5D;        // alvo dentro de ~60° da direção original

    private BallisticSolver() {}

    private record Solution(Vec3 velocity, int ticks) {}

    public static void aim(AbstractArrow arrow, LivingEntity shooter) {
        Vec3 v0 = arrow.getDeltaMovement();
        double speed = v0.length();
        if (speed < 0.1D) return;

        Vec3 origin = arrow.position();
        LivingEntity target = findTarget(arrow, shooter, origin, v0.normalize());
        if (target == null) return;

        double g = arrow.isNoGravity() ? 0.0D : GRAVITY;
        Vec3 targetMotion = new Vec3(target.getX() - target.xo,
                target.onGround() ? 0.0D : target.getY() - target.yo,
                target.getZ() - target.zo);

        Vec3 aimPoint = target.getBoundingBox().getCenter();
        Solution best = null;
        for (int i = 0; i < 3; i++) { // refinamento de antecipação (lead) do alvo
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

    private static LivingEntity findTarget(AbstractArrow arrow, LivingEntity shooter, Vec3 origin, Vec3 dir) {
        AABB area = arrow.getBoundingBox().inflate(MAX_RANGE);
        LivingEntity best = null;
        double bestDist = MAX_RANGE * MAX_RANGE;
        for (LivingEntity e : arrow.level().getEntitiesOfClass(LivingEntity.class, area,
                x -> x != shooter && x.isAlive() && !x.isSpectator() && !(x instanceof ArmorStand))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(origin);
            double d2 = to.lengthSqr();
            if (d2 >= bestDist || d2 < 1.0E-4D) continue;
            if (to.normalize().dot(dir) < CONE_DOT) continue;
            if (!shooter.hasLineOfSight(e)) continue;
            best = e;
            bestDist = d2;
        }
        return best;
    }

    /** Procura o ângulo de elevação cuja trajetória passa mais perto de {@code target}. */
    private static Solution solve(Vec3 origin, Vec3 target, double speed, double g) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        double hx = h > 1.0E-6D ? dx / h : 1.0D;
        double hz = h > 1.0E-6D ? dz / h : 0.0D;

        // Varredura grossa: prefere a trajetória TENSA (menor tempo de voo) que acerta.
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

        // Refinamento fino dentro do mesmo ramo (minimiza a distância ao centro).
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
        if (bestDist > MAX_ACCEPTED_MISS) return null; // fora de alcance: mantém o tiro vanilla

        double pr = Math.toRadians(refinedPitch);
        double c = Math.cos(pr);
        Vec3 vel = new Vec3(hx * c * speed, Math.sin(pr) * speed, hz * c * speed);
        return new Solution(vel, bestTicks);
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
