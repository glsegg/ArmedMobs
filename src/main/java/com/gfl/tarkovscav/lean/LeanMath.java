package com.gfl.tarkovscav.lean;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The trigonometry and the collision of the player lean (README 5s), in one place so the camera offset and
 * the shot-origin offset can never disagree: both call {@link #offsetFor}.
 *
 * <h2>Which way is "right"</h2>
 * <p>Minecraft's yaw {@code t} has the look direction {@code (-sin t, 0, cos t)} - yaw 0 faces +Z (south),
 * yaw 90 faces -X (west). The right-hand vector is therefore {@code (-cos t, 0, -sin t)}: facing south, west
 * is on your right. {@code lean > 0} means "lean to the right", so the offset is
 * {@code right * min(|lean|,1) * maxOffset}.</p>
 *
 * <h2>Why the shot is a PARALLEL ray (no direction correction)</h2>
 * <p>When the camera is translated by {@code o}, the crosshair is still drawn at the centre of the screen, so
 * the crosshair ray <em>is</em> the ray from the shifted eye along the unchanged look direction. Translating a
 * projectile's spawn by {@code o} while leaving its velocity alone produces exactly that ray - the same
 * direction, a parallel copy shifted by {@code o}. So the bullet goes precisely where the shifted crosshair
 * points, and the two things the user can see both hold:</p>
 * <ul>
 *   <li>the shot comes out of the leaned muzzle (the visual "I shot from the lean");</li>
 *   <li>hitting a wall while leaning moves the impact by ~{@code o} compared with standing still, because two
 *       parallel rays that are {@code o} apart stay {@code o} apart. That is the measurement the acceptance
 *       test asks for (0.4-0.6 blocks for the default 0.5).</li>
 * </ul>
 * <p>Rotating the velocity to "re-aim at what the unshifted eye sees" would undo exactly that, and would put
 * the impact off the crosshair, so it is deliberately not done.</p>
 *
 * <h2>The wall rule</h2>
 * <p>{@link #slide} shortens a desired offset so it stops just short of the first block between the start and
 * the offset: the camera cannot be pushed into (or through) a wall, which would otherwise be a free x-ray.</p>
 */
public final class LeanMath {
    /** Distance kept between the camera/projectile and the block it would have entered. */
    public static final double WALL_MARGIN = 0.1D;

    private LeanMath() {
    }

    /** The lean amount as it is used everywhere: clamped to [-1, 1]. */
    public static float clamp(float lean) {
        return Math.max(-1.0F, Math.min(1.0F, lean));
    }

    /** The unit right-hand vector for a yaw in degrees (MC convention, see the class comment). */
    public static Vec3 rightOf(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
    }

    /** The unit right-hand vector of an entity's facing. */
    public static Vec3 rightOf(Entity entity) {
        return rightOf(entity.getYRot());
    }

    /**
     * The lateral offset of a lean amount: {@code right(yaw) * clamp(lean) * maxOffset}. Positive = to the
     * player's right. Used by BOTH the camera and the projectile spawn, which is what keeps them identical.
     */
    public static Vec3 offsetFor(float yawDegrees, float lean, double maxOffset) {
        return rightOf(yawDegrees).scale(clamp(lean) * Math.max(0.0D, maxOffset));
    }

    /** {@link #offsetFor} for an entity's own facing. */
    public static Vec3 offsetFor(Entity entity, float lean, double maxOffset) {
        return offsetFor(entity.getYRot(), lean, maxOffset);
    }

    /**
     * The part of {@code offset} that fits before a block: a ray from {@code from} to {@code from + offset} is
     * clipped against block collisions and the result is shortened to stop {@link #WALL_MARGIN} short of the
     * hit. Returns the zero vector when there is no room at all, and the requested offset when the way is
     * clear. The offset is horizontal, so this cannot lift anything off the ground.
     */
    public static Vec3 slide(Level level, Vec3 from, Vec3 offset) {
        if (offset.lengthSqr() < 1.0E-8D) {
            return Vec3.ZERO;
        }
        // The null entity is deliberate: ClipContext turns it into CollisionContext.empty(), i.e. "clip against
        // blocks only, ignore every entity" - exactly what a camera/offset probe wants.
        HitResult hit = level.clip(new ClipContext(from, from.add(offset), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, null));
        if (hit.getType() == HitResult.Type.MISS) {
            return offset;
        }
        double room = hit.getLocation().distanceTo(from) - WALL_MARGIN;
        if (room <= 0.0D) {
            return Vec3.ZERO;
        }
        double wanted = offset.length();
        return offset.scale(Math.min(room, wanted) / wanted);
    }
}
