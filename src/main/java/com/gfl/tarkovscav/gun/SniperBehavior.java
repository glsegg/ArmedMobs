package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Random;

/**
 * The sniper AI (README 5q): <b>hold a post, leave when it is burned</b>. This is the whole behaviour, in one
 * class, shared by every sniper mob - {@code SniperPillagerEntity} and {@code SniperVillagerEntity} each do
 * nothing but own an instance of it and forward their tick, so the two cannot drift apart.
 *
 * <h2>Why it is a component and not a superclass</h2>
 * <p>The two snipers have to extend different vanilla classes ({@code Pillager} vs {@code Villager}) because
 * their bodies, renderers, sounds and idle brains are the vanilla ones. Java has single inheritance, so
 * "both are snipers" cannot be expressed by a shared parent - it is expressed by this object: the mob passes
 * itself in, the behaviour asks it for the few things it needs (navigation, move control, target, whether the
 * gun is firing), and all the state ({@link SniperPost}) still lives in the mob. Nothing about {@link GunBrain}
 * changes: the sniper <b>stops moving</b> rather than teaching the shared brain about sniping, which is what
 * keeps an ordinary gunner byte-for-byte unaffected.</p>
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li><b>Hold</b>: with a target and a post, cancel any path and pin the move control to the mob's own
 *       position - no strafe, no advance, displacement exactly zero. Shots taken from here are counted
 *       through the shared {@link GunUser} readout, not a private counter;</li>
 *   <li><b>Leave</b> when one of four triggers fires: took damage; the target can see it from closer than
 *       {@code sniper.discoveredRange}; it has fired {@code sniper.shotsBeforeMove} shots from here; or the
 *       target is closer than {@code sniper.closeRange};</li>
 *   <li><b>Walk</b> to a new post at least {@code sniper.minPostDistance} away that is as far out of the
 *       target's current line of sight as it can find (16 sampled candidates, unseen preferred, distance
 *       second), snapping to the surface. No qualifying post means it <b>backs away</b> from the target
 *       instead - logged, never silent;</li>
 *   <li><b>Re-acquire</b> the stashed target on arrival, which is what makes {@link GunBrain} re-enter AIM by
 *       itself. A walk that takes longer than {@code sniper.relocateTimeoutTicks} gives up, digs in where it
 *       is and re-acquires, so a bad path can never leave it wandering.</li>
 * </ol>
 */
public final class SniperBehavior {
    private final Mob mob;
    private final GunUser user;

    /** The post it is walking to, while relocating. */
    @Nullable
    private BlockPos movingTo;
    /** The target it dropped for the move, so it can pick the fight back up. */
    @Nullable
    private LivingEntity stashedTarget;
    private long relocateStartedAt;
    private String moveReason = "none";

    /**
     * @param mob the sniper, which must implement {@link GunUser} (both snipers do); the cast is checked here
     *            so a mistake is a clear exception in the constructor instead of a ClassCastException in a tick
     */
    public SniperBehavior(Mob mob) {
        if (!(mob instanceof GunUser gunUser)) {
            throw new IllegalArgumentException(mob + " is not a GunUser and cannot be a sniper");
        }
        this.mob = mob;
        this.user = gunUser;
    }

    /** True while it is walking to a new post (used by the villager's brain parking and the command). */
    public boolean isRelocating() {
        return this.movingTo != null;
    }

    /** The post being walked to, or null. */
    @Nullable
    public BlockPos movingTo() {
        return this.movingTo;
    }

    /** The last relocation reason, for the debug line. */
    public String moveReason() {
        return this.moveReason;
    }

    /** One-line report for {@code /tarkovscav debug}, {@code spawn} and {@code test sniper}. */
    public String describe() {
        return SniperPost.describe(this.mob) + " state=" + this.user.gunAiState()
                + (this.movingTo == null ? "" : " ->" + this.movingTo.toShortString());
    }

    /**
     * The per-tick step. Call it from the mob's own {@code tick()} <b>after</b> {@code super.tick()}, on the
     * server only; it is inert on the client and harmless if the mob is not in a server level.
     */
    public void serverTick() {
        if (!Config.SNIPER_ENABLED.get()) {
            // The master switch (sniper.enabled): the post logic stops entirely - no holding, no relocation.
            // The mob still fights with the shared gun brain, it just stops behaving like a sniper.
            return;
        }
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.stashedTarget != null && !this.stashedTarget.isAlive()) {
            this.stashedTarget = null;
        }
        if (this.movingTo != null) {
            continueMove(level);
            return;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            // No fight: a sniper with no post walks to high ground like any other mob (its own navigation is
            // untouched here), and forgets that it was dug in.
            SniperPost.setHoldingPost(this.mob, false);
            return;
        }
        String reason = relocationReason(level, target);
        if (reason != null) {
            startMove(level, target, reason);
            return;
        }
        hold(level, target);
    }

    /**
     * The four triggers, in the order they are checked. Returns the reason, or null when the post is still
     * good. Each one is separate on purpose: the user asked for all of them, and the log line names which one
     * fired.
     */
    @Nullable
    private String relocationReason(ServerLevel level, LivingEntity target) {
        if (this.mob.getLastHurtByMob() != null && this.mob.getLastHurtByMob() == target
                && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() <= 100) {
            return "took damage";
        }
        double distance = this.mob.distanceTo(target);
        if (distance < Config.SNIPER_DISCOVERED_RANGE.get() && this.mob.hasLineOfSight(target)) {
            return "discovered (" + String.format(Locale.ROOT, "%.1f", distance) + " < "
                    + Config.SNIPER_DISCOVERED_RANGE.get() + " and visible)";
        }
        if (SniperPost.shotsFromHere(this.mob) >= Config.SNIPER_SHOTS_BEFORE_MOVE.get()) {
            return "fired " + SniperPost.shotsFromHere(this.mob) + " shot(s) from here";
        }
        if (distance < Config.SNIPER_CLOSE_RANGE.get()) {
            return "too close (" + String.format(Locale.ROOT, "%.1f", distance) + " < "
                    + Config.SNIPER_CLOSE_RANGE.get() + ")";
        }
        return null;
    }

    /** Pins the mob to its post: no advance, no strafe, no path. */
    private void hold(ServerLevel level, LivingEntity target) {
        if (!SniperPost.holdingPost(this.mob)) {
            SniperPost.setHoldingPost(this.mob, true);
            SniperPost.setPost(this.mob, this.mob.blockPosition());
            SniperPost.clearShots(this.mob);
            TarkovScav.LOGGER.info("[sniper] {} took a post at {} (target {} blocks away)",
                    this.mob.getName().getString(), this.mob.blockPosition().toShortString(),
                    String.format(Locale.ROOT, "%.1f", this.mob.distanceTo(target)));
        }
        // The whole "does not move" rule, in two calls: cancel any path, and tell the move control to stay
        // where it is. Both are needed - a path already in flight keeps walking without the second one.
        this.mob.getNavigation().stop();
        this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        // Count the shots it takes from here (trigger 3). isGunFiring() is the shared GunUser readout.
        if (this.user.isGunFiring()) {
            SniperPost.noteShot(this.mob, level.getGameTime());
        }
    }

    private void startMove(ServerLevel level, LivingEntity target, String reason) {
        this.moveReason = reason;
        BlockPos candidate = findPost(level, target);
        boolean fallback = candidate == null;
        if (fallback) {
            candidate = retreatPoint(level, target);
        }
        this.stashedTarget = target;
        this.mob.setTarget(null);
        this.movingTo = candidate;
        SniperPost.setHoldingPost(this.mob, false);
        SniperPost.clearShots(this.mob);
        SniperPost.setReason(this.mob, reason);
        SniperPost.setMovedAt(this.mob, level.getGameTime());
        this.relocateStartedAt = level.getGameTime();
        this.mob.getNavigation().moveTo(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                Config.SNIPER_MOVE_SPEED.get());
        TarkovScav.LOGGER.info("[sniper] {} relocating from {} to {} because {} ({})",
                this.mob.getName().getString(),
                SniperPost.post(this.mob) == null ? "?" : SniperPost.post(this.mob),
                candidate.toShortString(), reason, fallback ? "no post found, backing off" : "post chosen");
    }

    /** Arrival handling: re-acquire the target, which is what makes GunBrain aim again. */
    private void continueMove(ServerLevel level) {
        boolean arrived = this.movingTo != null
                && this.mob.distanceToSqr(Vec3.atCenterOf(this.movingTo)) <= 4.0D;
        boolean timedOut = level.getGameTime() - this.relocateStartedAt
                > Config.SNIPER_RELOCATE_TIMEOUT_TICKS.get();
        if (!arrived && !timedOut) {
            return;
        }
        BlockPos reached = this.mob.blockPosition();
        this.movingTo = null;
        SniperPost.setPost(this.mob, reached);
        SniperPost.setHoldingPost(this.mob, true);
        SniperPost.setMovedAt(this.mob, level.getGameTime());
        this.mob.getNavigation().stop();
        if (this.stashedTarget != null && this.stashedTarget.isAlive()) {
            this.mob.setTarget(this.stashedTarget);
        }
        this.stashedTarget = null;
        TarkovScav.LOGGER.info("[sniper] {} {} at {} after {} tick(s) (reason was: {}); target re-acquired",
                this.mob.getName().getString(), arrived ? "arrived" : "gave up the walk",
                reached.toShortString(), level.getGameTime() - this.relocateStartedAt, this.moveReason);
    }

    /**
     * A new post: at least {@code sniper.minPostDistance} away, scored by "is the target looking at it" first
     * and distance second, so it prefers somewhere genuinely out of sight. A handful of candidates are sampled
     * (this runs on a trigger, not per tick, but sampling keeps it cheap and the result is still a real
     * choice).
     */
    @Nullable
    private BlockPos findPost(ServerLevel level, LivingEntity target) {
        Random random = new Random(this.mob.getRandom().nextLong());
        double min = Config.SNIPER_MIN_POST_DISTANCE.get();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 16; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = min + random.nextDouble() * min;
            BlockPos candidate = BlockPos.containing(
                    target.getX() + Math.cos(angle) * radius, target.getY(),
                    target.getZ() + Math.sin(angle) * radius);
            candidate = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    candidate);
            if (this.mob.distanceToSqr(Vec3.atCenterOf(candidate)) < min * min) {
                continue;
            }
            // Prefer somewhere the target cannot currently see, then simply further away.
            boolean visible = level.clip(new net.minecraft.world.level.ClipContext(
                    target.getEyePosition(), Vec3.atCenterOf(candidate).add(0.0D, 1.0D, 0.0D),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, target)).getType()
                    == net.minecraft.world.phys.HitResult.Type.MISS;
            double score = (visible ? 0.0D : 1000.0D) + radius;
            // README 5aa: a sniper prefers CONCEALED firing positions. A fully unseen post keeps the
            // hard-coded 1000 above; this is the second-best band - a partially concealed post (one of
            // the eye/feet lines blocked, but not both) or one that sits at least a block above the
            // target - so a rooftop with a firing slit beats an open street.
            double bonus = AiProfile.partialCoverBonus(this.mob);
            if (bonus > 0.0D
                    && (partiallyConcealed(level, target, candidate) || candidate.getY() > target.getY() + 1)) {
                score += bonus;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * True when exactly one of the candidate's eye and feet lines is blocked from the target's eyes:
     * "partial cover", as opposed to the full concealment {@link #findPost} already scores with 1000.
     * Only used for the README 5aa concealment preference, so it never gates whether a post exists.
     */
    private boolean partiallyConcealed(ServerLevel level, LivingEntity target, BlockPos candidate) {
        Vec3 feet = Vec3.atCenterOf(candidate);
        Vec3 eye = feet.add(0.0D, 1.6D, 0.0D);
        boolean eyeVisible = level.clip(new net.minecraft.world.level.ClipContext(
                target.getEyePosition(), eye,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, target)).getType()
                == net.minecraft.world.phys.HitResult.Type.MISS;
        boolean feetVisible = level.clip(new net.minecraft.world.level.ClipContext(
                target.getEyePosition(), feet,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, target)).getType()
                == net.minecraft.world.phys.HitResult.Type.MISS;
        return eyeVisible != feetVisible;
    }

    /** The executable fallback: put distance between itself and the target, along the current bearing. */
    private BlockPos retreatPoint(ServerLevel level, LivingEntity target) {
        Vec3 away = this.mob.position().subtract(target.position());
        Vec3 flat = new Vec3(away.x, 0.0D, away.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(1.0D, 0.0D, 0.0D);
        }
        flat = flat.normalize().scale(Config.SNIPER_MIN_POST_DISTANCE.get());
        BlockPos candidate = BlockPos.containing(this.mob.getX() + flat.x, this.mob.getY(), this.mob.getZ() + flat.z);
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, candidate);
    }
}
