package com.gfl.tarkovscav.grenade;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The one ballistic solver behind every grenade throw (README 5v).
 *
 * <h2>Why it exists</h2>
 * <p>The first version aimed a grenade along a straight line from the thrower's eyes to the target's eyes and
 * let gravity do the rest. That line is wrong on its own - at the speed a mob throws, a flat grenade hits the
 * ground after about 12 blocks, so it lands short even in an empty field - and any low wall, fence, slab or
 * step between the two swallowed it before it got there. That is the user's report that the AI's grenades are
 * very easily eaten by cover. This solver <b>simulates the flight</b> for a series of launch pitches and
 * returns the one that really lands on the target without hitting anything on the way.</p>
 *
 * <h2>The physics it models (vanilla ThrowableItemProjectile)</h2>
 * <p>One tick is: {@code v = v * 0.99}, then {@code v.y -= 0.03}, then {@code pos += v}. The sample that is
 * tested against the world is the <b>bottom centre of the entity's 0.25 box</b>
 * ({@code pos.y - HALF_BOX}), because that is the point of the box that really touches a block first: an arc
 * that squeaks over a wall by less than half the box is rejected, exactly as the real grenade would clip it.
 * The box's horizontal 0.125 is not sampled (README 8.0). Vanilla also applies its first move before the
 * first drag/gravity step, so this model is a simplification.</p>
 *
 * <h2>What "clear" means</h2>
 * <p>The returned landing point is the first block the arc enters. An arc is <b>clear</b> when that landing is
 * within {@link #ARRIVE_TOLERANCE} blocks (horizontally) of the aim point, which also means the path hit
 * nothing before it got there. When no pitch can do that, the best-effort arc is still returned - so a caller
 * may throw it anyway when {@code mob.requireClearArc = false} - but {@link Solution#blockedAt()} then names
 * the block that stopped the flattest ("most direct") attempt: the cover the mob is up against.</p>
 *
 * <h2>Purity</h2>
 * <p>{@link #solve} reads no world state: the caller passes a {@link BlockTest}. That is what lets one
 * function be the ally-safety prediction, the throw direction and the JS gate's simulation at the same
 * time.</p>
 */
public final class GrenadeBallistics {
    /** Vanilla's drag for a thrown item: the whole velocity is scaled by this every tick. */
    private static final double DRAG = 0.99D;
    /** Vanilla's gravity for a thrown item, in blocks per tick squared. */
    private static final double GRAVITY = 0.03D;
    /**
     * Half of the entity's 0.25 box: the flight is tested at the bottom of that box, so a lob that would only
     * squeak over a wall by less than 0.125 blocks is treated as a hit - which is what the real box does.
     */
    private static final double HALF_BOX = 0.125D;
    /** Hard cap on the simulated flight; the longest arc at the fastest throw is about 60 ticks. */
    private static final int MAX_STEPS = 400;
    /** An arc that has dropped this far below the muzzle is never coming back to anything useful. */
    private static final double DROP_LIMIT = 64.0D;
    /**
     * How close (horizontally, in blocks) the landing has to be to the aim point for the arc to count as a
     * hit. 2.5 blocks is about a body plus the blast, and it is also the line that separates "a lob over the
     * wall lands on him" from "the only arcs that clear the wall land 6+ blocks past him" - see the gate.
     */
    public static final double ARRIVE_TOLERANCE = 2.5D;
    /** Coarse-to-fine passes that polish the chosen pitch after the first sweep. */
    private static final int REFINE_ROUNDS = 4;
    /** Sub-samples per refinement pass; the window shrinks by this factor each round. */
    private static final int REFINE_POINTS = 4;

    private GrenadeBallistics() {
    }

    /** "Is this block in the way?" - the caller decides (the world, or the gate's fake world). */
    @FunctionalInterface
    public interface BlockTest {
        boolean blocked(BlockPos pos);
    }

    /**
     * One solved throw.
     *
     * @param direction    the unit launch direction (horizontal aim plus the chosen pitch)
     * @param landing      where the grenade first meets a block, or null when the arc never hit anything
     * @param pitchDegrees the launch angle above the horizon, in degrees
     * @param blockedAt    the block that stopped the flattest attempt, or null when the arc is clear
     */
    public record Solution(Vec3 direction, @Nullable Vec3 landing, double pitchDegrees,
                           @Nullable BlockPos blockedAt) {
        /** True when the grenade lands on the target: nothing between two points ate it. */
        public boolean clear() {
            return this.blockedAt == null && this.landing != null;
        }
    }

    /** The flight of one candidate arc: where it stopped, and which block stopped it. */
    private record Flight(@Nullable Vec3 landing, @Nullable BlockPos hit) {
    }

    /**
     * The solver: sweep {@code samples} launch pitches from 0 to {@code maxPitchDegrees}, then polish the best
     * one, and report where that arc lands. Pure - the same inputs always give the same answer.
     *
     * @param from             the muzzle (the thrower's eyes, already lowered to the spawn height)
     * @param aim              the point the grenade should come down on (target eye plus lead)
     * @param speed            the initial speed in blocks per tick
     * @param samples          candidate pitches across the range (the mob config's arcSamples)
     * @param maxPitchDegrees  the steepest launch angle the sweep may use
     * @param blocked          the world model
     */
    public static Solution solve(Vec3 from, Vec3 aim, double speed,
                                 int samples, double maxPitchDegrees, BlockTest blocked) {
        Vec3 flat = flatDirection(from, aim);
        int count = Math.max(2, samples);
        double maxPitch = Math.max(0.0D, maxPitchDegrees);
        double step = maxPitch / (count - 1);
        double bestPitch = 0.0D;
        Flight best = fly(from, launchDirection(flat, 0.0D), speed, blocked);
        for (int i = 1; i < count; i++) {
            double pitch = step * i;
            Flight arc = fly(from, launchDirection(flat, pitch), speed, blocked);
            if (error(arc, aim) < error(best, aim)) {
                best = arc;
                bestPitch = pitch;
            }
        }
        // The sweep is coarse on purpose (13 pitches over 45 degrees is 3.75 degrees apart, and the range
        // moves several blocks per step); without this pass the landing would be blocks off the target.
        double half = step;
        for (int round = 0; round < REFINE_ROUNDS; round++) {
            double lo = Math.max(0.0D, bestPitch - half);
            double hi = Math.min(maxPitch, bestPitch + half);
            for (int k = 0; k <= REFINE_POINTS; k++) {
                double pitch = lo + (hi - lo) * k / REFINE_POINTS;
                Flight arc = fly(from, launchDirection(flat, pitch), speed, blocked);
                if (error(arc, aim) < error(best, aim)) {
                    best = arc;
                    bestPitch = pitch;
                }
            }
            half = (hi - lo) / REFINE_POINTS;
        }
        Vec3 direction = launchDirection(flat, bestPitch);
        if (best.landing() == null) {
            // Nothing in range of the world at all (a fall into the void): report the direction, no landing.
            return new Solution(direction, null, bestPitch, null);
        }
        if (error(best, aim) <= ARRIVE_TOLERANCE) {
            return new Solution(direction, best.landing(), bestPitch, null);
        }
        // No pitch can put it on the target. Still answer with the closest arc, but say what stopped the
        // flattest one: that block is the cover the mob is staring at, which is what a reader wants in the log.
        Flight flattest = fly(from, launchDirection(flat, 0.0D), speed, blocked);
        BlockPos cover = flattest.hit() != null ? flattest.hit() : best.hit();
        return new Solution(direction, best.landing(), bestPitch, cover);
    }

    /** The world as a {@link BlockTest}: a block with a collision shape blocks, grass and air do not. */
    public static BlockTest levelBlockTest(Level level) {
        return pos -> !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Horizontal distance from the landing to the aim; infinite when the arc never landed. */
    private static double error(Flight flight, Vec3 aim) {
        if (flight.landing() == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = flight.landing().x - aim.x;
        double dz = flight.landing().z - aim.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * One flight, stepped exactly like vanilla's thrown item. Returns the first position whose box bottom is
     * inside a blocking block, or null when the arc never hit one within {@link #MAX_STEPS} /
     * {@link #DROP_LIMIT}.
     */
    private static Flight fly(Vec3 from, Vec3 direction, double speed, BlockTest blocked) {
        double vx = direction.x * speed;
        double vy = direction.y * speed;
        double vz = direction.z * speed;
        double x = from.x;
        double y = from.y;
        double z = from.z;
        for (int step = 0; step < MAX_STEPS; step++) {
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
            x += vx;
            y += vy;
            z += vz;
            BlockPos pos = BlockPos.containing(x, y - HALF_BOX, z);
            if (blocked.blocked(pos)) {
                return new Flight(new Vec3(x, y, z), pos);
            }
            if (y < from.y - DROP_LIMIT) {
                break;
            }
        }
        return new Flight(null, null);
    }

    /** The horizontal unit vector from the muzzle to the aim (degenerate: straight up, use +Z). */
    private static Vec3 flatDirection(Vec3 from, Vec3 aim) {
        Vec3 flat = new Vec3(aim.x - from.x, 0.0D, aim.z - from.z);
        if (flat.lengthSqr() < 1.0E-8D) {
            return new Vec3(0.0D, 0.0D, 1.0D);
        }
        return flat.normalize();
    }

    /** A unit direction at {@code pitchDegrees} above the horizon, aimed along {@code flat}. */
    private static Vec3 launchDirection(Vec3 flat, double pitchDegrees) {
        double radians = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(radians);
        return new Vec3(flat.x * horizontal, Math.sin(radians), flat.z * horizontal);
    }
}
