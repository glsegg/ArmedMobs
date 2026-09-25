package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.gun.AiProfile;
import com.gfl.tarkovscav.gun.CombatTactics;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.gun.SquadCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Walks a unit towards the mark it was ordered to, and nothing else.
 *
 * <h2>Why this is a goal and not a brain state</h2>
 * <p>A goal is the only place in vanilla AI where priority is expressed for free: this one takes
 * {@link Flag#MOVE} at a <b>lower priority</b> than {@code GunAttackGoal}, so the moment the unit has a
 * target the gun goal owns the movement and this one cannot run. That single fact is "combat takes
 * priority", "retreat takes priority" (retreat happens inside {@code GunAttackGoal}) and "the order
 * resumes afterwards" - no flags, no state machine, no second brain.</p>
 *
 * <h2>The advance itself</h2>
 * <ul>
 *   <li>speed is {@code command.speedScale} (default 0.65) of the unit's walking speed, and sprinting is
 *       always switched off - "slowly", not a charge;</li>
 *   <li>the step is chosen by the <b>existing</b> cover search
 *       ({@link CombatTactics#bestCover} towards the mark), so the unit moves cover to cover instead of
 *       in a straight line, and the cover block is claimed through {@link SquadCoordinator} so two units
 *       of one element cannot pick the same wall;</li>
 *   <li>with {@code command.coordination} on, the element leapfrogs: on each overwatch window exactly one
 *       member holds (navigation stopped) while the rest move, using
 *       {@link SquadCoordinator#isSuppressor} - the firefight's own rotation, not a new one;</li>
 *   <li>departure is staggered by entity id ({@link AdvanceOrder#startDelay}), so an element leaves as a
 *       ragged file rather than one block stepping off together;</li>
 *   <li>inside {@code command.arrivalRadius} of the mark the order is cleared and autonomous AI resumes.</li>
 * </ul>
 */
public class AdvanceOrderGoal extends Goal {
    /** How often the step is re-chosen. Between repaths the unit just keeps walking. */
    private static final int REPATH_TICKS = 20;

    private final Mob mob;
    private final CombatTactics tactics;
    private final SquadCoordinator squad;
    private int repathIn;

    public AdvanceOrderGoal(Mob mob) {
        this.mob = mob;
        this.tactics = new CombatTactics(mob);
        this.squad = new SquadCoordinator(mob);
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    // ------------------------------------------------------------------ gating

    @Override
    public boolean canUse() {
        if (!Config.SPEC.isLoaded() || !Config.COMMAND_ENABLED.get()) {
            return false;
        }
        // A dead or already-removed unit cannot obey an order. This is asked FIRST because canUse() is
        // itself the expensive part - it reads the order out of the mob's NBT and asks the mark ledger
        // whether the mark still exists - and the goal selector calls it every tick for every unit that
        // has an order, including the twenty ticks a killed one spends dying.
        if (this.mob.isRemoved() || this.mob.isDeadOrDying()) {
            return false;
        }
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        AdvanceOrder order = AdvanceOrder.read(this.mob);
        if (order == null) {
            return false;
        }
        long now = level.getGameTime();
        // An order belongs to one level. A unit that travelled to another dimension keeps the order in its
        // NBT (so coming back resumes it) but does not walk towards a coordinate it cannot reach.
        if (!order.dimension().equals(level.dimension().location())) {
            return false;
        }
        if (!AdvanceOrder.valid(order.expiresTick(), now)) {
            AdvanceOrder.clear(this.mob);
            return false;
        }
        // "The order expires with the mark": a deleted mark, an expired stick or a broken signal point
        // makes the order invalid here, and the unit goes back to its own AI.
        if (!MarkData.get(level.getServer()).stillExists(order.dimension(), order.letter(),
                order.target(), now)) {
            AdvanceOrder.clear(this.mob);
            return false;
        }
        if (AdvanceOrder.arrived(distanceSqr(order, this.mob), Config.COMMAND_ARRIVAL_RADIUS.get())) {
            AdvanceOrder.clear(this.mob);
            return false;
        }
        if (AdvanceOrder.combatOverrides(hasLiveTarget()) || AdvanceOrder.retreatOverrides(isRetreating())) {
            return false;
        }
        // Staggered departure: until this unit's offset has elapsed it simply does not start.
        int delay = AdvanceOrder.startDelay(this.mob.getId(), coordination());
        return now >= order.issuedTick() + delay;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // ------------------------------------------------------------------ the walk

    @Override
    public void start() {
        this.repathIn = 0;
        this.mob.setSprinting(false);
    }

    @Override
    public void tick() {
        if (this.mob.isRemoved() || this.mob.isDeadOrDying()) {
            return;
        }
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        AdvanceOrder order = AdvanceOrder.read(this.mob);
        if (order == null) {
            return;
        }
        long now = level.getGameTime();
        this.mob.setSprinting(false);

        // Leapfrog: the holder of this window stops and watches while the rest of the element moves. The
        // role rotates through the existing overwatch window, so nobody is parked for long.
        long window = now / Math.max(1, Config.AI_COORD_OVERWATCH_WINDOW_TICKS.get());
        if (AdvanceOrder.holds(order.index(), window, order.size(), coordination())) {
            this.mob.getNavigation().stop();
            return;
        }

        if (this.repathIn > 0) {
            this.repathIn--;
            if (!this.mob.getNavigation().isDone()) {
                return;
            }
        }
        this.repathIn = REPATH_TICKS;

        Vec3 markCentre = new Vec3(order.target().getX() + 0.5D, order.target().getY() + 0.5D,
                order.target().getZ() + 0.5D);
        double speed = Math.max(0.05D, Config.COMMAND_SPEED_SCALE.get());
        BlockPos step = nextStep(level, markCentre);
        if (step == null) {
            // No cover to use: walk straight at the mark. Still slow, still no sprint.
            this.mob.getNavigation().moveTo(markCentre.x, markCentre.y, markCentre.z, speed);
            return;
        }
        this.mob.getNavigation().moveTo(step.getX() + 0.5D, step.getY(), step.getZ() + 0.5D, speed);
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.squad.release();
    }

    /**
     * The next cover step towards the mark, or null when there is none. The search is the existing
     * cover search with the mark as the "threat" it advances on, and a squad veto so a claimed block is
     * never picked twice.
     */
    @Nullable
    private BlockPos nextStep(ServerLevel level, Vec3 markCentre) {
        CombatTactics.Spot spot = this.tactics.bestCover(level, markCentre, true,
                Math.max(0.0D, AiProfile.advanceCoverStep(this.mob)), false,
                pos -> this.squad.isFree(pos));
        if (spot == null) {
            return null;
        }
        BlockPos pos = BlockPos.containing(spot.x(), spot.y(), spot.z());
        this.squad.claim(pos);
        return pos;
    }

    // ------------------------------------------------------------------ helpers

    private boolean hasLiveTarget() {
        return this.mob.getTarget() != null && this.mob.getTarget().isAlive();
    }

    private boolean isRetreating() {
        return this.mob instanceof GunUser user && user.gunAiState() == GunAiState.RETREAT;
    }

    private static boolean coordination() {
        return Config.SPEC.isLoaded() && Config.COMMAND_COORDINATION.get();
    }

    private static double distanceSqr(AdvanceOrder order, Mob mob) {
        // The mark's own squared-distance helper lives on CommandMark; the order carries the same block,
        // and re-stating the four lines here keeps the goal from having to rebuild a CommandMark every tick.
        double dx = order.target().getX() + 0.5D - mob.getX();
        double dy = order.target().getY() + 0.5D - mob.getY();
        double dz = order.target().getZ() + 0.5D - mob.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
