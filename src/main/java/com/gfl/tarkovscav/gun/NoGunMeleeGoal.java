package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * The melee fallback, made <b>mutually exclusive</b> with {@link GunAttackGoal}.
 *
 * <h2>Why the plain {@code MeleeAttackGoal} was not enough</h2>
 * <p>Both goals carry {@code Flag.MOVE | Flag.LOOK} and both can be capable of running at the same
 * time: {@code MeleeAttackGoal#canUse} only needs a live target within reach, while
 * {@code GunAttackGoal#canUse} only needs a live target and a gun. They sit at different priorities,
 * so the higher one <em>should</em> always win - but "should" is exactly what turns into the reported
 * "steps in place" symptom, because the two of them issue different navigation calls:</p>
 * <ul>
 *   <li>{@code MeleeAttackGoal} re-issues {@code getNavigation().moveTo(target, 1.0)} on its own
 *       cadence and takes a path <em>to the target</em>;</li>
 *   <li>the gun tactics take paths <em>to cover</em> and call {@code getNavigation().stop()} in
 *       {@code AIM}/{@code FIRE}/{@code SUPPRESS}.</li>
 * </ul>
 * <p>An interleaving of those two in one tick leaves the mob with no usable path while its animation
 * still sees "intent to move" - the mob animates walking and does not move. Making the fallback say
 * "I only exist when there is no gun" removes the overlap by construction instead of by priority
 * ordering.</p>
 *
 * <p>This is the pattern {@code GunAttackGoal}'s own javadoc has always claimed ("plain melee stays
 * available as a fallback ... which lets the ordinary MeleeAttackGoal handle the fight instead of the
 * mob standing there doing nothing") - the claim needed to be enforced, not assumed.</p>
 */
public class NoGunMeleeGoal extends MeleeAttackGoal {
    private final GunUser user;

    public NoGunMeleeGoal(GunUser user, double speedModifier, boolean followTargetEvenIfNotSeen) {
        // Every GunUser is a PathfinderMob (ScavEntity is a Monster, GunnerPillagerEntity a Pillager,
        // GunnerVillagerEntity a Villager), which is also why MeleeAttackGoal can be used at all.
        super((PathfinderMob) user.asMob(), speedModifier, followTargetEvenIfNotSeen);
        this.user = user;
    }

    /**
     * True only while the mob has no usable gun. {@link GunBrain#hasGun()} is the same predicate
     * {@code GunAttackGoal} uses, so exactly one of the two goals can ever be usable: no gun -&gt;
     * melee, gun -&gt; {@link GunAttackGoal}.
     */
    @Override
    public boolean canUse() {
        return usable() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return usable() && super.canContinueToUse();
    }

    /**
     * No usable gun <b>and</b> no bow/crossbow taken off the weapon rack (README 5n). The bow case is the
     * third one: with a bow in hand {@link ArmedRangedGoal} owns the fight, and a melee goal that also ran
     * would walk the archer into the thing it is supposed to be shooting.
     */
    private boolean usable() {
        return !this.user.gunBrain().hasGun()
                && !ArmedRangedGoal.isRangedWeapon(this.user.asMob().getMainHandItem());
    }
}
