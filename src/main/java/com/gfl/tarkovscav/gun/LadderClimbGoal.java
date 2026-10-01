package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.command.AdvanceOrder;
import com.gfl.tarkovscav.command.MarkData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * The vertical link as an AI goal: walks a unit to a legal ladder shaft, climbs it, steps out on the
 * destination floor, and hands control back (README 7o, {@code docs/爬梯设计.md} §1 layer 3).
 *
 * <h2>What it takes over, and what it gives back</h2>
 * <p>The goal holds {@link Flag#MOVE} and {@link Flag#LOOK}. Those two flags are the whole "the unit is
 * not shooting" mechanism this feature reuses, not a new flag of its own:</p>
 * <ul>
 *   <li>{@code GunAttackGoal} (priority 1) and therefore all of {@link GunBrain} - aiming, firing,
 *       reloading, suppression, retreat - needs the same two flags, so while a climb is running the brain
 *       is not ticked at all; it was already put into {@link GunAiState#IDLE} and had its weapon lowered by
 *       {@code GunBrain#onGoalStop} when {@code GunAttackGoal} stopped. That is why a climbing unit does
 *       not shoot or reload with {@code ladder.combatWhileClimbing=false}.</li>
 *   <li>{@code NoGunMeleeGoal}, {@code GrenadeResupplyGoal} and {@code ArmedRangedGoal} also need MOVE, so
 *       they are suppressed by the same arbitration.</li>
 *   <li>{@code GrenadeThrowGoal} is the one exception and it is measured, not assumed: it never calls
 *       {@code setFlags}, so its flag set is empty, and vanilla's
 *       {@code GoalSelector#goalCanBeReplacedForAllFlags} is vacuously true for an empty flag set - such a
 *       goal may start beside any running goal. That is why the throw goal is registered inside
 *       {@link LadderGatedGoal}, which refuses it while the unit is on the rungs ("a grenade is never
 *       thrown from a ladder, whatever {@code ladder.combatWhileClimbing} says").</li>
 *   <li>Priority 5 puts the goal above {@code AdvanceOrderGoal} (6) and the stroll (7) and below every
 *       combat goal (0..4). {@code AdvanceOrderGoal} is below it on purpose: both take MOVE, and the
 *       order's destination is exactly what the climb is serving, so the ladder must win the vertical leg
 *       and give the horizontal leg back the moment the unit is on the destination floor.</li>
 * </ul>
 *
 * <h2>The safety rule: never end a climb in mid-air</h2>
 * <p>{@link #isInterruptable()} returns false while the unit is on the rungs. Forge's
 * {@code WrappedGoal#canBeReplacedBy} consults exactly that method (verified in the mapped 1.20.1 jar:
 * {@code isInterruptable() && other.getPriority() < this.getPriority()}), so a higher-priority goal cannot
 * steal the flags and leave the unit hanging. Instead the goal notices the interruption itself
 * ({@link LadderSearch#interrupted}) and drives a LANDING phase to the nearest height in the shaft where
 * the unit can stand, then it stops and combat takes over. {@link #stop()} is the backstop for the cases
 * the goal cannot control (removal, a goal reset, another mod) and lands the unit there too.</p>
 *
 * <h2>Compromise, stated honestly</h2>
 * <p>Design doc §3 also lists "retreat: allowed to go DOWN a ladder". This implementation does not do
 * that, because the same section's priority rule - combat and self-preservation win over climbing - is the
 * harder requirement and the retreat is executed inside {@code GunAttackGoal}, which the ladder goal's
 * MOVE flag would block. A retreating unit therefore keeps its existing escape path; the ladder goal
 * refuses to start ({@link LadderSearch#shouldStart}) while it is retreating. The other interface bullets
 * (command marks, garrison units ordered upstairs) are covered, and the "back-off" destination is wired
 * through {@code GunBrain#currentSpot}.</p>
 */
public class LadderClimbGoal extends Goal {
    /** How often the walk-to-the-foot step is re-issued. */
    private static final int REPATH_TICKS = 10;
    /** How close (squared) the unit must be to the foot cell before it steps onto the rungs. */
    private static final double FOOT_REACH_SQR = 2.25D;
    /**
     * How often the (not free) shaft scan and the destination re-check run. {@code canUse} is polled every
     * tick for every unit, so an unthrottled scan of a radius-8 square would be the most expensive AI in
     * the mod; a cached verdict for a quarter of a second cannot be observed at climbing speeds.
     */
    private static final int SCAN_TICKS = 5;
    /** The walk-to-the-foot speed modifier; the unit is not charging, it is finding a ladder. */
    private static final double WALK_SPEED = 0.8D;
    /** Ticks the goal stays quiet after a climb that did not end at the destination. */
    private static final int RETRY_COOLDOWN_TICKS = 40;

    private enum Phase {
        /** Walking to the cell beside the shaft on the unit's own floor. */
        TO_FOOT,
        /** On the rungs, moving up or down. */
        CLIMB,
        /** At the destination height, stepping sideways out of the shaft. */
        STEP_OUT,
        /** Interrupted: moving to the nearest height in the shaft where a floor can be stepped onto. */
        LAND
    }

    /**
     * The destination the climb serves: its feet height decides which floor is wanted. The horizontal
     * position is deliberately not stored - {@code AdvanceOrderGoal} and the gun brain already own the
     * horizontal route, and this goal only supplies the vertical link.
     */
    private record Destination(int feetY, String source) {
    }

    private final Mob mob;
    /** Every class that registers this goal implements {@link GunUser}; null only if one ever does not. */
    @Nullable
    private final GunUser user;

    @Nullable
    private LadderSearch.Shaft shaft;
    private Phase phase = Phase.TO_FOOT;
    /** The unit's feet Y when the climb started, the origin of {@code ladder.maxHeight}. */
    private int startFeetY;
    /** The yaw that faces the ladder, sampled before the unit steps into the column. */
    private float climbYaw;
    private int repathIn;
    /** Set by {@link #finish}: the goal is over and must not be re-used until the selector restarts it. */
    private boolean done;
    private int cooldown;
    /** Re-check countdowns, and the cached scan so {@code canUse} is cheap. */
    private int scanIn;
    private int destCheckIn;
    private int cachedMineY = Integer.MIN_VALUE;
    private int cachedDestY = Integer.MIN_VALUE;
    @Nullable
    private LadderSearch.Shaft cachedShaft;
    /** The LANDING / STEP_OUT target. */
    private int landingY = Integer.MIN_VALUE;
    @Nullable
    private LadderSearch.Opening landingOpening;
    /** Which exit the LANDING phase is serving, so the log line names it. */
    private LadderSearch.Exit landingExit = LadderSearch.Exit.STOPPED;
    /** The destination of the climb in progress, kept for the interruption re-check. */
    @Nullable
    private Destination activeDestination;
    /** True once the unit has been on the rungs - the state that makes {@link #isInterruptable} false. */
    private boolean touchedRungs;

    public LadderClimbGoal(Mob mob) {
        this.mob = mob;
        this.user = mob instanceof GunUser gunUser ? gunUser : null;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------------ gating

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (!Config.SPEC.isLoaded() || !Config.LADDER_ENABLED.get()) {
            return false;
        }
        // Asked first: this is polled every tick for every unit, including the twenty ticks a killed one
        // spends dying, and everything below reads NBT and scans blocks.
        if (this.mob.isRemoved() || this.mob.isDeadOrDying() || this.user == null) {
            return false;
        }
        if (!(this.mob.level() instanceof ServerLevel)) {
            return false;
        }
        BlockPos feet = LadderClimb.feet(this.mob);
        int mineY = LadderClimb.feetY(this.mob);
        Destination destination = destination();
        if (destination == null) {
            return false;
        }
        boolean sameFloor = !LadderSearch.differentFloor(mineY, destination.feetY());
        // The command layer's own vetoes, called rather than re-stated (see AdvanceOrder#combatOverrides).
        boolean combat = AdvanceOrder.combatOverrides(hasLiveTarget());
        boolean retreating = AdvanceOrder.retreatOverrides(isRetreating());
        boolean combatWhileClimbing = Config.LADDER_COMBAT_WHILE_CLIMBING.get();
        // The intent rule, evaluated once with the cheap half of it. `hasShaft` is passed as true here and
        // the real value is enforced by the call right after the scan; the point of this first call is to
        // return before paying for a shaft scan while the unit is fighting or retreating.
        if (!LadderSearch.shouldStart(true, true, sameFloor, combat, retreating, combatWhileClimbing)) {
            return false;
        }
        LadderSearch.Shaft found = scan(feet, mineY, destination.feetY());
        if (!LadderSearch.shouldStart(true, found != null, sameFloor, combat, retreating,
                combatWhileClimbing)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.done || this.mob.isRemoved() || this.mob.isDeadOrDying()) {
            return false;
        }
        // On the rungs (or landing on them) the climb always finishes: handing the flags back here is
        // exactly what would leave the unit hanging in the shaft.
        if (this.phase == Phase.CLIMB || this.phase == Phase.LAND || LadderClimb.onRungs(this.mob)) {
            return true;
        }
        // Still walking to the foot: the ordinary priority rules apply, so combat takes the walk back.
        if (blockedByCombat()) {
            return false;
        }
        Destination destination = destination();
        return destination != null && this.activeDestination != null
                && destination.feetY() == this.activeDestination.feetY();
    }

    /**
     * The safety switch described in the class comment: a unit on the rungs cannot be replaced by a
     * higher-priority goal. The moment it stands on a floor the climb yields, so combat and
     * self-preservation do win - they just win after the climb has ended somewhere legal.
     */
    @Override
    public boolean isInterruptable() {
        if (this.shaft == null) {
            return true;
        }
        return this.phase != Phase.CLIMB && this.phase != Phase.LAND && !LadderClimb.onRungs(this.mob);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        Destination destination = destination();
        LadderSearch.Shaft found = destination == null ? null : scan(LadderClimb.feet(this.mob),
                LadderClimb.feetY(this.mob), destination.feetY());
        if (destination == null || found == null) {
            // canUse approved this a tick ago; a removed mark or a changed floor can invalidate it in
            // between. Nothing to do - say so, do not pretend to climb.
            this.done = true;
            return;
        }
        this.shaft = found;
        this.activeDestination = destination;
        this.startFeetY = LadderClimb.feetY(this.mob);
        this.phase = Phase.TO_FOOT;
        this.done = false;
        this.touchedRungs = false;
        this.repathIn = 0;
        this.destCheckIn = 0;
        this.landingY = Integer.MIN_VALUE;
        this.landingOpening = null;
        log("{} ordered to y={} ({}) from y={} - shaft {}", this.mob.getName().getString(),
                destination.feetY(), destination.source(), this.startFeetY,
                LadderSearch.describe(this.shaft));
    }

    @Override
    public void tick() {
        if (this.mob.isRemoved() || this.mob.isDeadOrDying() || this.done) {
            return;
        }
        if (!(this.mob.level() instanceof ServerLevel level) || this.shaft == null) {
            finish(LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        int feetY = LadderClimb.feetY(this.mob);
        switch (this.phase) {
            case TO_FOOT -> tickToFoot(feetY);
            case CLIMB -> tickClimb(feetY);
            case STEP_OUT -> tickStepOut(feetY);
            case LAND -> tickLand(feetY);
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        // Backstop (see the class comment): isInterruptable() is false while on the rungs, so this should
        // be unreachable from the goal scheduler - but removal, /kill or another mod can still land here,
        // and a unit left holding a rung with no controller would fall. Land it on a floor.
        if (!this.done && this.shaft != null && !this.mob.isRemoved() && !this.mob.isDeadOrDying()
                && LadderClimb.onRungs(this.mob)) {
            LadderSearch.Probe probe = LadderClimb.probe(this.mob.level());
            int feetY = LadderClimb.feetY(this.mob);
            int y = LadderSearch.nearestLanding(probe, this.shaft, feetY);
            LadderSearch.Opening out = y == Integer.MIN_VALUE ? null
                    : LadderSearch.opening(probe, this.shaft.x(), y, this.shaft.z());
            if (out != null) {
                LadderClimb.moveIntoCell(this.mob, out.x(), out.y(), out.z(), this.climbYaw);
                log("{} was stopped on the rungs - landed at y={} instead of hanging", name(), out.y());
            } else {
                // No silent fallback: if this ever happens the shaft has no floor to stand on and the
                // honest thing is to say so rather than to pretend the safety rule held.
                TarkovScav.LOGGER.warn("[ladder] {} was stopped on the rungs of shaft {} and found no"
                        + " standable floor in it - it will fall normally", name(),
                        LadderSearch.describe(this.shaft));
            }
        }
        this.shaft = null;
        this.activeDestination = null;
        this.landingOpening = null;
        this.landingY = Integer.MIN_VALUE;
        this.cachedShaft = null;
        this.scanIn = 0;
        this.destCheckIn = 0;
        this.touchedRungs = false;
        this.done = false;
    }

    // ------------------------------------------------------------------ the four branches

    /** Walk to the foot of the shaft, then step onto the rungs. */
    private void tickToFoot(int feetY) {
        LadderSearch.Opening foot = this.shaft.startOpening();
        LadderSearch.Probe probe = LadderClimb.probe(this.mob.level());
        if (!LadderSearch.standable(probe, foot.x(), foot.y(), foot.z())) {
            // The generator's post-passes (cover lines) protect the shaft, but a changed world must not be
            // climbed anyway: there is no floor to stand on at the foot.
            finish(LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        BlockPos at = LadderClimb.feet(this.mob);
        if (LadderClimb.onRungs(this.mob) && at.getX() == this.shaft.x() && at.getZ() == this.shaft.z()) {
            // Already holding the column (walked into it, fell in, or a previous climb left it here):
            // adopt it instead of snapping it to a floor.
            this.climbYaw = this.mob.getYRot();
            this.touchedRungs = true;
            this.phase = Phase.CLIMB;
            return;
        }
        if (this.repathIn-- <= 0) {
            this.repathIn = REPATH_TICKS;
            this.mob.getNavigation().moveTo(foot.x() + 0.5D, foot.y(), foot.z() + 0.5D, WALK_SPEED);
        }
        double dx = this.mob.getX() - (foot.x() + 0.5D);
        double dz = this.mob.getZ() - (foot.z() + 0.5D);
        if (dx * dx + dz * dz <= FOOT_REACH_SQR && feetY == this.shaft.startY()) {
            beginClimb();
        }
    }

    /** Step from the floor beside the shaft onto the rungs and face the ladder. */
    private void beginClimb() {
        this.climbYaw = LadderClimb.facingYaw(this.mob, this.shaft.x(), this.shaft.z());
        this.touchedRungs = true;
        LadderClimb.moveIntoCell(this.mob, this.shaft.x(), this.shaft.startY(), this.shaft.z(),
                this.climbYaw);
        this.phase = Phase.CLIMB;
        this.destCheckIn = SCAN_TICKS;
        log("{} on the rungs: {} climbing {}{}", name(), LadderSearch.describe(this.shaft),
                this.shaft.direction() > 0 ? "up" : "down",
                Config.LADDER_COMBAT_WHILE_CLIMBING.get() ? " (combatWhileClimbing)" : "");
    }

    /** Up or down the rungs; interrupts to LANDING rather than ever stopping in mid-air. */
    private void tickClimb(int feetY) {
        boolean combat = AdvanceOrder.combatOverrides(hasLiveTarget());
        boolean combatWhileClimbing = Config.LADDER_COMBAT_WHILE_CLIMBING.get();
        boolean destinationValid;
        if (this.destCheckIn-- <= 0) {
            this.destCheckIn = SCAN_TICKS;
            Destination now = destination();
            destinationValid = now != null && this.activeDestination != null
                    && now.feetY() == this.activeDestination.feetY();
        } else {
            destinationValid = true;
        }
        if (LadderSearch.interrupted(destinationValid, combat, combatWhileClimbing)) {
            beginLanding(feetY, destinationValid ? LadderSearch.Exit.COMBAT_INTERRUPTED
                    : LadderSearch.Exit.DESTINATION_GONE);
            return;
        }
        if (LadderSearch.reached(feetY, this.shaft)) {
            this.landingY = this.shaft.endY();
            this.landingOpening = this.shaft.endOpening();
            this.landingExit = LadderSearch.Exit.ARRIVED;
            this.phase = Phase.STEP_OUT;
            return;
        }
        if (LadderSearch.exceededHeight(this.startFeetY, feetY, Config.LADDER_MAX_HEIGHT.get())) {
            beginLanding(feetY, LadderSearch.Exit.MAX_HEIGHT);
            return;
        }
        BlockPos at = LadderClimb.feet(this.mob);
        if (LadderSearch.geometryLost(LadderClimb.probe(this.mob.level()), this.shaft, at.getX(), feetY,
                at.getZ())) {
            // Knocked out of the shaft: no teleport (that would undo the knockdown), normal fall rules.
            log("{} knocked out of shaft {} - the climb is over and the fall is a normal fall", name(),
                    LadderSearch.describe(this.shaft));
            finish(LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        if (combatWhileClimbing && this.user != null && this.user.gunBrain().hasGun()) {
            // The opt-in half of ladder.combatWhileClimbing: the brain is not ticked by GunAttackGoal while
            // this goal holds MOVE+LOOK, so it is ticked here - the same GunBrain#tick, once. The climb
            // re-asserts the delta afterwards, so the brain's own navigation cannot win the tick.
            this.user.gunBrain().tick();
        }
        double step = this.shaft.direction() > 0 ? Config.LADDER_CLIMB_SPEED.get()
                : -Config.LADDER_DOWN_SPEED.get();
        LadderClimb.climbTick(this.mob, step, this.climbYaw, this.shaft.x(), this.shaft.z());
    }

    /** At the destination height: step into the opening, and only then call the climb finished. */
    private void tickStepOut(int feetY) {
        LadderSearch.Opening out = this.landingOpening;
        if (out == null || !LadderSearch.standable(LadderClimb.probe(this.mob.level()), out.x(), out.y(),
                out.z())) {
            // The opening was legal when the shaft was chosen; a block placed in it since must not be
            // climbed into. Fall back to landing somewhere in this shaft.
            beginLanding(feetY, LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        LadderClimb.moveIntoCell(this.mob, out.x(), out.y(), out.z(), this.climbYaw);
        log("{} arrived on floor y={} of shaft {} and stepped out at ({},{},{})", name(), out.y(),
                LadderSearch.describe(this.shaft), out.x(), out.y(), out.z());
        finish(LadderSearch.Exit.ARRIVED);
    }

    /** Interrupted: move to the nearest height with a real floor, then step out onto it. */
    private void tickLand(int feetY) {
        if (this.landingOpening == null || this.landingY == Integer.MIN_VALUE) {
            finish(LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        if (feetY == this.landingY) {
            LadderSearch.Opening out = LadderSearch.opening(LadderClimb.probe(this.mob.level()),
                    this.shaft.x(), this.landingY, this.shaft.z());
            if (out == null) {
                finish(LadderSearch.Exit.GEOMETRY_LOST);
                return;
            }
            LadderClimb.moveIntoCell(this.mob, out.x(), out.y(), out.z(), this.climbYaw);
            log("{} {} - landed on the nearest floor, y={}", name(), this.landingExit, out.y());
            finish(this.landingExit);
            return;
        }
        double step = this.landingY > feetY ? Config.LADDER_CLIMB_SPEED.get()
                : -Config.LADDER_DOWN_SPEED.get();
        LadderClimb.climbTick(this.mob, step, this.climbYaw, this.shaft.x(), this.shaft.z());
    }

    private void beginLanding(int feetY, LadderSearch.Exit exit) {
        LadderSearch.Probe probe = LadderClimb.probe(this.mob.level());
        int y = LadderSearch.nearestLanding(probe, this.shaft, feetY);
        LadderSearch.Opening out = y == Integer.MIN_VALUE ? null
                : LadderSearch.opening(probe, this.shaft.x(), y, this.shaft.z());
        if (out == null) {
            // Nothing in this shaft can be stood on at all. Finish where we are and say so loudly; the
            // fall handler still covers the shaft, so the unit is not left "hanging" by our own movement.
            TarkovScav.LOGGER.warn("[ladder] {} was interrupted ({}) on shaft {} and found no standable"
                    + " floor in it", name(), exit, LadderSearch.describe(this.shaft));
            finish(LadderSearch.Exit.GEOMETRY_LOST);
            return;
        }
        this.landingY = y;
        this.landingOpening = out;
        this.landingExit = exit;
        this.phase = Phase.LAND;
        log("{} {} at y={} - landing on the nearest floor, y={}", name(), exit, feetY, y);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The climb's destination, or null when there is none. In priority order:
     * <ol>
     *   <li>the unit's command mark, when the order is still valid and the mark still exists - the same
     *       two checks {@code AdvanceOrderGoal#canUse} makes, so an order that has expired or whose mark
     *       was deleted stops the climb as well;</li>
     *   <li>with {@code ladder.combatWhileClimbing} on, the combat target's own floor (the unit climbs
     *       towards it and shoots from the rungs);</li>
     *   <li>with the same key on, the gun brain's current cover / back-off spot
     *       ({@code GunBrain#currentSpot}), which is where a repositioning unit was already heading.</li>
     * </ol>
     * The mark's own block is used as the feet height because {@code AdvanceOrderGoal} navigates to the
     * mark's centre, i.e. the mark block is the cell the unit is meant to occupy.
     */
    @Nullable
    private Destination destination() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        AdvanceOrder order = AdvanceOrder.read(this.mob);
        if (order != null) {
            long now = level.getGameTime();
            if (AdvanceOrder.valid(order.expiresTick(), now)
                    && order.dimension().equals(level.dimension().location())
                    && MarkData.get(level.getServer()).stillExists(order.dimension(), order.letter(),
                    order.target(), now)) {
                return new Destination(order.target().getY(), "mark " + order.letter());
            }
        }
        if (!Config.LADDER_COMBAT_WHILE_CLIMBING.get() || this.user == null) {
            return null;
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            return new Destination(LadderClimb.feetY(target), "target");
        }
        CombatTactics.Spot spot = this.user.gunBrain().currentSpot();
        if (spot != null) {
            return new Destination(Mth.floor(spot.y()), "back-off spot");
        }
        return null;
    }

    /**
     * The cached shaft scan. The cache is keyed on the unit's floor and the destination floor and expires
     * after {@link #SCAN_TICKS}; a climb in progress never re-scans (it holds the shaft it started with),
     * so the only cost is while a unit is idling with an order that needs a vertical leg.
     */
    @Nullable
    private LadderSearch.Shaft scan(BlockPos feet, int mineY, int destY) {
        if (this.scanIn > 0 && this.cachedShaft != null && this.cachedMineY == mineY
                && this.cachedDestY == destY) {
            this.scanIn--;
            return this.cachedShaft;
        }
        this.scanIn = SCAN_TICKS;
        this.cachedMineY = mineY;
        this.cachedDestY = destY;
        this.cachedShaft = LadderSearch.find(LadderClimb.probe(this.mob.level()), feet.getX(), mineY,
                feet.getZ(), destY, Config.LADDER_SEARCH_RADIUS.get(), Config.LADDER_MAX_HEIGHT.get());
        return this.cachedShaft;
    }

    /** Combat wins over climbing, exactly as it does over an advance order ({@code AdvanceOrderGoal}). */
    private boolean blockedByCombat() {
        if (AdvanceOrder.retreatOverrides(isRetreating())) {
            return true;
        }
        return AdvanceOrder.combatOverrides(hasLiveTarget()) && !Config.LADDER_COMBAT_WHILE_CLIMBING.get();
    }

    private boolean hasLiveTarget() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }

    /** Self-preservation, read from the brain the same way {@code AdvanceOrderGoal#isRetreating} reads it. */
    private boolean isRetreating() {
        return this.user != null && this.user.gunAiState() == GunAiState.RETREAT;
    }

    private String name() {
        return this.mob.getName().getString();
    }

    private void log(String format, Object... args) {
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[ladder] " + format, args);
        }
    }

    /**
     * Ends the climb. {@code ARRIVED} is the only exit that may be followed by another climb immediately;
     * every other one arms {@link #RETRY_COOLDOWN_TICKS}, so an interruption that cannot be fixed (a
     * destination that keeps being unreachable) cannot turn into a climb/land loop.
     */
    private void finish(LadderSearch.Exit exit) {
        this.done = true;
        if (exit != LadderSearch.Exit.ARRIVED) {
            this.cooldown = RETRY_COOLDOWN_TICKS;
        }
    }

    @Override
    public String toString() {
        return "LadderClimbGoal[" + this.mob.getType().toShortString() + " " + this.phase
                + (this.shaft == null ? "" : " " + LadderSearch.describe(this.shaft)) + "]";
    }
}
