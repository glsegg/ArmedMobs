package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The tactics layer: cover search, target memory and the "under fire" timer.
 *
 * <p>Cover is found the cheap and honest way the brief asks for: sample candidate positions around
 * the mob, then ray cast {@code level.clip} from <b>the threat's eyes</b> to the candidate's eyes and
 * to its feet. If neither is reachable, the candidate hides the mob's body from that threat. Each
 * candidate is scored by</p>
 *
 * <pre>
 *   score = (fully hidden ? HIDDEN_BONUS : 0)
 *         - distanceFromMob            * 0.5      // near cover beats a sprint across the street
 *         + movementGain               * weight   // towards the target when advancing,
 *                                                // away from it when retreating
 * </pre>
 *
 * <p>and the best one wins. Results are cached for {@code tactics.coverCacheTicks} and the whole
 * thing costs two ray casts per candidate per refresh, i.e. nothing per tick.</p>
 *
 * <p>Target memory: whenever the mob can see its target, the target's position is remembered for
 * {@code tactics.targetMemoryTicks} ticks after the last sighting, which is what makes SUPPRESS
 * ("blind fire at where it went") possible.</p>
 */
public final class CombatTactics {
    /** Multiplies the "is this cover worth moving to" term; keeps near cover attractive. */
    private static final double HIDDEN_BONUS = 12.0D;
    private static final double DISTANCE_PENALTY = 0.5D;

    /** One candidate position, already evaluated against the current threat. */
    public record Spot(double x, double y, double z, double distanceFromMob, double distanceFromThreat,
                       boolean hidden, boolean partial, double score) {
        public Vec3 position() {
            return new Vec3(this.x, this.y, this.z);
        }

        public String describe() {
            return String.format("(%.0f,%.0f,%.0f) dMob=%.1f dThreat=%.1f %s",
                    this.x, this.y, this.z, this.distanceFromMob, this.distanceFromThreat,
                    this.hidden ? "HIDDEN" : this.partial ? "partial" : "open");
        }
    }

    private final Mob mob;

    private List<Spot> cached = List.of();
    private int cacheTicks;
    private Vec3 lastKnownTarget = Vec3.ZERO;
    private int memoryTicks;
    private int underFireTicks;

    public CombatTactics(Mob mob) {
        this.mob = mob;
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Call once per AI tick. */
    public void tick(@Nullable LivingEntity target) {
        tick(target, target != null && this.mob.hasLineOfSight(target));
    }

    /**
     * The same, with the line-of-sight verdict handed in instead of asked again.
     *
     * <p>Every one of these is a {@code Level#clip} ray cast through the block grid, and the brain has
     * to answer exactly this question for its own state machine on the same tick - so asking it here
     * as well was a second identical ray cast per mob per tick for nothing. {@code GunBrain#seesTarget}
     * is now the single asker (and the single place the verdict is staggered), and the answer it
     * already has is passed in.</p>
     */
    public void tick(@Nullable LivingEntity target, boolean canSeeTarget) {
        if (this.underFireTicks > 0) {
            this.underFireTicks--;
        }
        if (this.cacheTicks > 0) {
            this.cacheTicks--;
        }

        if (target != null && canSeeTarget) {
            this.lastKnownTarget = target.position();
            this.memoryTicks = Config.TARGET_MEMORY_TICKS.get();
        } else if (this.memoryTicks > 0) {
            this.memoryTicks--;
        }
    }

    /** Called from the mob's {@code hurt} hook - this is what makes a Scav run for cover. */
    public void markUnderFire() {
        this.underFireTicks = Config.UNDER_FIRE_TICKS.get();
    }

    public boolean isUnderFire() {
        return this.underFireTicks > 0;
    }

    public boolean remembersTarget() {
        return this.memoryTicks > 0;
    }

    public Vec3 lastKnownTarget() {
        return this.lastKnownTarget;
    }

    public int memoryTicks() {
        return this.memoryTicks;
    }

    public int underFireTicks() {
        return this.underFireTicks;
    }

    /** Drops the cache, e.g. after the mob moved to a new position. */
    public void invalidate() {
        this.cacheTicks = 0;
    }

    // ------------------------------------------------------------------ cover search

    /** The cached candidate list, refreshed when it expires. */
    public List<Spot> spots(ServerLevel level, Vec3 threatEye) {
        if (this.cacheTicks <= 0 || this.cached.isEmpty()) {
            // README 5aa: the radius and the cache lifetime are the tier's, scaled on top of the two
            // global [tactics] keys, so a scav searches a small area and reuses the stale list longer.
            this.cached = sample(level, threatEye, AiProfile.coverSearchRadius(this.mob),
                    Config.COVER_SAMPLES.get());
            this.cacheTicks = AiProfile.coverCacheTicks(this.mob);
        }
        return this.cached;
    }

    /**
     * The best covered position to move to.
     *
     * @param towardThreat true when advancing (prefer spots closer to the threat), false when
     *                     retreating (prefer spots further away)
     * @param minGain      how much closer/further than the current position the spot has to be;
     *                     this is what stops a mob from shuffling between two equally good walls
     * @param requireHidden when true only fully hidden spots are considered
     */
    @Nullable
    public Spot bestCover(ServerLevel level, Vec3 threatEye, boolean towardThreat, double minGain,
                          boolean requireHidden) {
        return bestCover(level, threatEye, towardThreat, minGain, requireHidden, pos -> true);
    }

    /**
     * The same search, with a veto on individual candidate blocks. This is the seam the squad layer
     * uses (README 5aa): a spot somebody else has claimed - or one on the wrong side of the axis for
     * a flanker - is simply never considered, so two mobs cannot pick the same block.
     */
    @Nullable
    public Spot bestCover(ServerLevel level, Vec3 threatEye, boolean towardThreat, double minGain,
                          boolean requireHidden, Predicate<BlockPos> allowed) {
        double currentDistance = this.mob.position().distanceTo(threatEye);
        Spot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Spot spot : spots(level, threatEye)) {
            if (requireHidden && !spot.hidden()) {
                continue;
            }
            if (!allowed.test(BlockPos.containing(spot.x(), spot.y(), spot.z()))) {
                continue;
            }
            double gain = towardThreat
                    ? currentDistance - spot.distanceFromThreat()
                    : spot.distanceFromThreat() - currentDistance;
            if (gain < minGain) {
                continue;
            }
            double score = (spot.hidden() ? HIDDEN_BONUS : 0.0D)
                    + gain
                    - spot.distanceFromMob() * DISTANCE_PENALTY;
            if (score > bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        return best;
    }

    /**
     * A position near the mob from which the given point <b>is</b> visible - the "peek out from
     * behind cover" move. Without this a mob behind good cover would simply never fire.
     */
    @Nullable
    public Spot peekSpot(ServerLevel level, Vec3 aimAt, double radius) {
        return peekSpot(level, aimAt, radius, pos -> true);
    }

    /** The peek search with a veto on individual candidate blocks (README 5aa, cover claims). */
    @Nullable
    public Spot peekSpot(ServerLevel level, Vec3 aimAt, double radius, Predicate<BlockPos> allowed) {
        // Sampling against `aimAt` as the threat gives exactly what a peek needs: the spots that are
        // NOT hidden from it.
        List<Spot> candidates = sample(level, aimAt, Math.max(3, (int) Math.ceil(radius)), 12);
        Spot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Spot spot : candidates) {
            if (spot.hidden()) {
                continue;
            }
            if (!allowed.test(BlockPos.containing(spot.x(), spot.y(), spot.z()))) {
                continue;
            }
            Vec3 eye = new Vec3(spot.x(), spot.y() + this.mob.getEyeHeight(), spot.z());
            if (!hasLineOfSight(level, eye, aimAt)) {
                continue;
            }
            if (spot.distanceFromMob() < bestDistance) {
                bestDistance = spot.distanceFromMob();
                best = spot;
            }
        }
        return best;
    }

    /**
     * Samples candidate positions on two rings around {@code around}, snaps them to the ground and
     * evaluates them against {@code threatEye}.
     */
    private List<Spot> sample(ServerLevel level, Vec3 threatEye, int radius, int count) {
        List<Spot> result = new ArrayList<>(count);
        Vec3 centre = this.mob.position();

        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D * i) / count + (this.mob.getRandom().nextDouble() - 0.5D) * 0.4D;
            // alternate a near ring and the full radius: near cover is usually the right answer, but
            // sometimes the only cover is across the street
            double distance = (i % 2 == 0 ? 0.45D : 1.0D) * radius * (0.6D + this.mob.getRandom().nextDouble() * 0.4D);
            double x = centre.x + Math.cos(angle) * distance;
            double z = centre.z + Math.sin(angle) * distance;

            BlockPos ground = findGround(level, Mth.floor(x), Mth.floor(centre.y), Mth.floor(z));
            if (ground == null) {
                continue;
            }

            Vec3 spotFeet = new Vec3(ground.getX() + 0.5D, ground.getY(), ground.getZ() + 0.5D);
            Vec3 spotEye = spotFeet.add(0.0D, this.mob.getEyeHeight(), 0.0D);
            boolean eyeBlocked = !hasLineOfSight(level, threatEye, spotEye);
            boolean feetBlocked = !hasLineOfSight(level, threatEye, spotFeet.add(0.0D, 0.2D, 0.0D));

            double distanceFromMob = centre.distanceTo(spotFeet);
            double distanceFromThreat = threatEye.distanceTo(spotFeet);
            result.add(new Spot(spotFeet.x, spotFeet.y, spotFeet.z, distanceFromMob, distanceFromThreat,
                    eyeBlocked && feetBlocked, eyeBlocked || feetBlocked, 0.0D));
        }
        return result;
    }

    /** Solid ground with two blocks of air above it, within three blocks of the given height. */
    @Nullable
    private BlockPos findGround(ServerLevel level, int x, int y, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = new BlockPos(x, y + dy, z);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()
                    && !level.getBlockState(candidate.below()).isAir()
                    && !level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** True when nothing solid blocks the straight line between the two points. */
    public boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** True when the mob itself is currently hidden from the threat (used for the log lines). */
    public boolean isInCoverFrom(ServerLevel level, Vec3 threatEye) {
        Vec3 eye = this.mob.getEyePosition();
        Vec3 feet = this.mob.position().add(0.0D, 0.2D, 0.0D);
        return !hasLineOfSight(level, threatEye, eye) && !hasLineOfSight(level, threatEye, feet);
    }

    // ------------------------------------------------------------------ exposure (README 5ab)

    /**
     * The first half of the exposure test: does this mob have an unobstructed line from its own eyes
     * to the other entity's <b>eyes</b>? This is the exact mirror of the first ray cast
     * {@link #isInCoverFrom} already does, just with the roles swapped (a normal mob is the threat
     * there, the other entity here).
     *
     * <p>The full rule is one line and lives in {@link AiProfile#isExposed}: eyes AND feet AND in
     * range. The two halves are separate methods only so the gate can print the truth table.</p>
     */
    public boolean canSeeEyes(ServerLevel level, LivingEntity other) {
        return hasLineOfSight(level, this.mob.getEyePosition(), other.getEyePosition());
    }

    /**
     * The second half of the exposure test: is the other entity's <b>feet</b> (sampled 0.2 blocks up
     * from its position, the same sample {@link #isInCoverFrom} uses for our own feet) visible from
     * this mob's eyes? A target that has ducked so that only its legs show is therefore NOT exposed.
     */
    public boolean canSeeFeet(ServerLevel level, LivingEntity other) {
        return hasLineOfSight(level, this.mob.getEyePosition(), other.position().add(0.0D, 0.2D, 0.0D));
    }
}
