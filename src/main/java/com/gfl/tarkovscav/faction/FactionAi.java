package com.gfl.tarkovscav.faction;

import com.gfl.tarkovscav.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * The one call an entity's own {@code tick()} makes to take part in the faction layer (README 5m).
 *
 * <p>It is deliberately a separate seam from {@code GunBrain}: the gun AI decides <em>how</em> to fight,
 * the faction layer decides <em>what it heard</em>, and keeping them apart is what lets this batch claim
 * "no gameplay AI was touched". Concretely, nothing here can aim, fire, reload or take cover:
 * {@link AlertNetwork} writes only a {@code SharedContact} (a direction and a distance band) and, at the
 * very most, calls {@code getNavigation().moveTo} or {@code getLookControl().setLookAt}. A target can only
 * ever come from line of sight, which is still {@code GunBrain}'s job.</p>
 */
public final class FactionAi {
    /**
     * How often the faction layer runs for one unit. Staggered by entity id, so a squad spreads over
     * the interval instead of all asking on the same tick.
     *
     * <p>Measured with {@code tools/spike/work/aiperf}: the body below is either a persistent-data
     * read (the renegade decay clock), a map lookup, or - as soon as a report is cached - an entity
     * query, and it used to run for every armed unit in the world on every tick. Everything it drives
     * is measured in hundreds of ticks ({@code alert.memoryTicks}, {@code alert.minRepathIntervalTicks},
     * {@code alert.broadcastCooldownTicks}, {@code faction.renegadeDecayTicks}), so a 4-tick cadence
     * cannot be observed in the behaviour; and the one thing a player could notice - the head turning
     * towards a report - is smoothed by the vanilla {@code LookControl} interpolating to the target.</p>
     */
    private static final int TICK_INTERVAL_TICKS = 4;

    private FactionAi() {
    }

    /** Server-side, once per tick, from the entity's own {@code tick()}. */
    public static void tick(Mob mob, @Nullable LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level) || !Config.FACTION_ENABLED.get()) {
            return;
        }
        // A dead or removed unit has left the network. Dropping its report here is also what keeps the
        // received/repath maps from holding an entry for an entity that will never read it again.
        if (mob.isRemoved() || mob.isDeadOrDying()) {
            AlertNetwork.forget(mob);
            return;
        }
        if ((mob.tickCount + mob.getId()) % TICK_INTERVAL_TICKS != 0) {
            return;
        }
        Renegade.tickDecay(level, mob);
        if (!Config.ALERT_ENABLED.get()) {
            return;
        }
        boolean engaged = target != null && target.isAlive();
        if (engaged) {
            // It can see something itself: report it, and forget the old report (a live target wins).
            AlertNetwork.forgetReport(mob);
            AlertNetwork.broadcastIfDue(mob, target);
            return;
        }
        // No target: act on whatever the network told it, if anything. The "is there a report at all"
        // question is asked FIRST because the answer is a map lookup, while asking "which converger am I"
        // is an entity query - doing that every tick for every idle mob would be the expensive part.
        if (AlertNetwork.current(mob) == null) {
            return;
        }
        AlertNetwork.act(mob, false, AlertNetwork.convergerIndex(mob));
    }
}
