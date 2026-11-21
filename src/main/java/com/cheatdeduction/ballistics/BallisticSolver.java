package com.cheatdeduction.ballistics;

import com.cheatdeduction.tracking.LastLookTracker;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class BallisticSolver {
    public static final double GRAVITY = 0.05D;
    public static final double DRAG = 0.99D;

    private static final double MAX_RANGE = 64.0D;
    private static final double MAX_CONE_DEGREES = 50.0D;
    private static final int MAX_TICKS = 220;
    private static final double HIT_TOLERANCE = 0.55D;
    private static final double MAX_ACCEPTED_MISS = 1.6D;

    private BallisticSolver() {}

    private record Solution(Vec3 velocity, int ticks) {}

    public static void aim(AbstractArrow arrow, ServerPlayer shooter) {
        Vec3 v0 = arrow.getDeltaMovement();
        double speed = v0.length();
        if (speed < 0.1D) return;

        LivingEntity target = pickTarget(shooter);
        if (target == null) return;

        Vec3 origin = arrow.position();
        double g = arrow.isNoGravity() ? 0.0D : GRAVITY;
        Vec3 targetMotion = new Vec3(
                target.getX() - target.xo,
                target.onGround() ? 0.0D : target.getY() - target.yo,
                target.getZ() - target.zo
        );

        AABB box = target.getBoundingBox();
        double[] heightRatios = new double[] { 0.65D, 0.90D, 0.50D, 0.30D };

        for (double ratio : heightRatios) {
            Vec3 baseAim = new Vec3(
                    box.getCenter().x,
                    box.minY + (box.getYsize() * ratio),
                    box.getCenter().z
            );

            Vec3 aimPoint = baseAim;
            Solution best = null;
            for (int i = 0; i < 3; i++) {
                Solution s = solveClear(arrow, origin, aimPoint, speed, g, target);
                if (s == null) break;
                best = s;
                aimPoint = baseAim.add(targetMotion.scale(s.ticks()));
            }

            if (best != null) {
                Vec3 v = best.velocity();
                arrow.setDeltaMovement(v);
                float yaw = (float) (Mth.atan2(v.x, v.z) * (180.0D / Math.PI));
                float pitch = (float) (Mth.atan2(v.y, v.horizontalDistance()) * (180.0D / Math.PI));
                arrow.setYRot(yaw);
                arrow.setXRot(pitch);
                arrow.yRotO = yaw;
                arrow.xRotO = pitch;
                return;
            }
        }
    }

    private static LivingEntity pickTarget(ServerPlayer shooter) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        LivingEntity last = LastLookTracker.server(shooter.getUUID()).getLast(shooter.level());

        AABB area = shooter.getBoundingBox().inflate(MAX_RANGE);
        List<LivingEntity> pool = shooter.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != shooter && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand));

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (LivingEntity e : pool) {
            double angle = angleTo(eye, look, e);
            if (angle < 0.0D) continue;
            if (e != last && angle > MAX_CONE_DEGREES) continue;

            double score = (e == last) ? angle * 0.65D : angle;
            if (score < bestScore && hasAnyClearLine(shooter, e, eye)) {
                bestScore = score;
                best = e;
            }
        }

        if (last != null && last != shooter && last.isAlive() && !pool.contains(last)) {
            double angle = angleTo(eye, look, last);
            if (angle >= 0.0D && angle * 0.65D < bestScore && hasAnyClearLine(shooter, last, eye)) {
                best = last;
            }
        }
        return best;
    }

    private static double angleTo(Vec3 eye, Vec3 look, LivingEntity e) {
        Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
        double dist = to.length();
        if (dist < 0.4D || dist > MAX_RANGE) return -1.0D;
        return Math.toDegrees(Math.acos(Mth.clamp(look.dot(to.scale(1.0D / dist)), -1.0D, 1.0D)));
    }

    private static boolean hasAnyClearLine(ServerPlayer shooter, LivingEntity target, Vec3 eye) {
        if (shooter.hasLineOfSight(target)) return true;
        AABB b = target.getBoundingBox();
        Vec3 head = new Vec3(b.getCenter().x, b.maxY - 0.1D, b.getCenter().z);
        HitResult hit = shooter.level().clip(new ClipContext(eye, head,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static Solution solveClear(AbstractArrow arrow, Vec3 origin, Vec3 targetPos,
                                       double speed, double g, LivingEntity targetEntity) {
        double dx = targetPos.x - origin.x;
        double dz = targetPos.z - origin.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        double hx = h > 1.0E-6D ? dx / h : 1.0D;
        double hz = h > 1.0E-6D ? dz / h : 0.0D;

        Level level = arrow.level();
        AABB targetBox = targetEntity.getBoundingBox().inflate(0.2D);

        double bestPitch = Double.NaN;
        double bestScore = Double.MAX_VALUE;

        for (double pitch = -85.0D; pitch <= 85.0D; pitch += 0.5D) {
            double[] r = simulateWithBlocks(level, arrow, origin, pitch, hx, hz, speed, g, targetPos, targetBox);
            if (r == null) continue;

            double score = r[0] <= HIT_TOLERANCE ? r[1] : 1000.0D + r[0];
            if (score < bestScore) {
                bestScore = score;
                bestPitch = pitch;
            }
        }

        if (Double.isNaN(bestPitch)) return null;

        double refinedPitch = bestPitch;
        double bestDist = Double.MAX_VALUE;
        int bestTicks = 0;

        for (double pitch = bestPitch - 0.5D; pitch <= bestPitch + 0.5D; pitch += 0.02D) {
            double[] r = simulateWithBlocks(level, arrow, origin, pitch, hx, hz, speed, g, targetPos, targetBox);
            if (r == null) continue;

            if (r[0] < bestDist) {
                bestDist = r[0];
                refinedPitch = pitch;
                bestTicks = (int) r[1];
            }
        }

        if (bestDist > MAX_ACCEPTED_MISS) return null;

        double pr = Math.toRadians(refinedPitch);
        double c = Math.cos(pr);
        return new Solution(new Vec3(hx * c * speed, Math.sin(pr) * speed, hz * c * speed), bestTicks);
    }

    private static double[] simulateWithBlocks(Level level, AbstractArrow arrow, Vec3 origin,
                                               double pitchDeg, double hx, double hz,
                                               double speed, double g, Vec3 targetPos, AABB targetBox) {
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
            Vec3 p0 = new Vec3(px, py, pz);
            Vec3 p1 = new Vec3(nx, ny, nz);

            if (targetBox.clip(p0, p1).isPresent() || targetBox.contains(p1)) {
                return new double[] { 0.0D, t };
            }

            HitResult blockHit = level.clip(new ClipContext(
                    p0, p1, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow
            ));
            if (blockHit.getType() != HitResult.Type.MISS) {
                return null;
            }

            double d = distSegmentPoint(px, py, pz, nx, ny, nz, targetPos);
            if (d < minDist) {
                minDist = d;
                minTick = t;
            }

            px = nx; py = ny; pz = nz;
            vx *= DRAG; vy *= DRAG; vz *= DRAG;
            vy -= g;
            if (py < targetPos.y - 48.0D && vy < 0.0D) break;
        }
        return new double[] { minDist, minTick };
    }

    private static double distSegmentPoint(double ax, double ay, double az,
                                           double bx, double by, double bz, Vec3 p) {
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 > 1.0E-12D
                ? Mth.clamp(((p.x - ax) * abx + (p.y - ay) * aby + (p.z - az) * abz) / len2, 0.0D, 1.0D)
                : 0.0D;
        double cx = ax + abx * t - p.x;
        double cy = ay + aby * t - p.y;
        double cz = az + abz * t - p.z;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}

