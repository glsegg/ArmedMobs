package com.gfl.tarkovscav.gun;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A thin gate around a goal that must not run while the unit is on a ladder (README 7o).
 *
 * <h2>Why a wrapper is needed at all</h2>
 * <p>{@code LadderClimbGoal} holds {@link Flag#MOVE} and {@link Flag#LOOK}, and that is enough to keep
 * every goal that also needs those flags from running while a climb is in progress - {@code GunAttackGoal},
 * {@code NoGunMeleeGoal}, {@code GrenadeResupplyGoal}, {@code ArmedRangedGoal}. It is <b>not</b> enough for
 * {@code GrenadeThrowGoal}: measured from its source, that goal never calls {@code setFlags}, so its flag
 * set is empty. Vanilla's {@code GoalSelector#goalCanBeReplacedForAllFlags} iterates a goal's flags, so an
 * empty flag set is vacuously replaceable and such a goal may <b>start at any time, no matter what is
 * running</b>. Its own {@code isInterruptable() == false} only protects it from being replaced once it has
 * started; it does not stop it from starting next to a climb.</p>
 *
 * <p>The design's rule is "a grenade is never thrown from a ladder, whatever
 * {@code ladder.combatWhileClimbing} says", so the throw goal is registered inside this gate on the three
 * classes that register goals. The gate adds no state of its own: it asks exactly one question - is the unit
 * on the rungs right now? - and forwards everything else to the delegate, so the grenade behaviour is
 * otherwise byte-for-byte the shipped one.</p>
 */
public final class LadderGatedGoal extends Goal {
    private final Mob mob;
    private final Goal delegate;

    /**
     * @param mob      the unit the delegate belongs to
     * @param delegate the real goal; it must <b>not</b> also be registered, or it would run unguarded
     */
    public LadderGatedGoal(Mob mob, Goal delegate) {
        this.mob = mob;
        this.delegate = delegate;
        // The delegate's own flags, so the wrapper conflicts with exactly what the delegate conflicts with.
        this.setFlags(delegate.getFlags().isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(delegate.getFlags()));
    }

    /**
     * The delegate's own verdict, vetoed while the unit is on the rungs. The delegate is asked first on
     * purpose: this method is polled every tick for every armed unit, and the delegate's cheap early-outs
     * (grenades disabled, cooldown, no target) then save the block lookups {@link LadderClimb#onRungs} costs.
     */
    @Override
    public boolean canUse() {
        return this.delegate.canUse() && !LadderClimb.onRungs(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        return this.delegate.canContinueToUse();
    }

    /** Forwarded, so a delegate that must not be interrupted (the throw goal) keeps that property. */
    @Override
    public boolean isInterruptable() {
        return this.delegate.isInterruptable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return this.delegate.requiresUpdateEveryTick();
    }

    @Override
    public void start() {
        this.delegate.start();
    }

    @Override
    public void stop() {
        this.delegate.stop();
    }

    @Override
    public void tick() {
        this.delegate.tick();
    }

    @Override
    public String toString() {
        return "LadderGatedGoal[" + this.delegate + "]";
    }
}
