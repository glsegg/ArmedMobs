package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Runs {@link GunBrain} as a normal mob AI goal, so the gun fight slots into the vanilla goal
 * scheduler: it takes MOVE and LOOK while it is active, and a higher-priority goal (panic, hurt,
 * a plugin's own AI) can pre-empt it, at which point {@link #stop()} tells TaCZ to lower the weapon.
 *
 * <p>Plain melee stays available as a fallback: this goal refuses to run while the mob has no gun
 * (TaCZ index empty, or everything filtered out by the config), which lets the ordinary
 * {@code MeleeAttackGoal} handle the fight instead of the mob standing there doing nothing.</p>
 */
public class GunAttackGoal extends Goal {
    private final Mob mob;
    private final GunBrain brain;

    public GunAttackGoal(GunUser user) {
        this.mob = user.asMob();
        this.brain = user.gunBrain();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive() || !this.mob.canAttack(target)) {
            return false;
        }
        return this.brain.ensureEquipped();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive() && this.mob.canAttack(target) && this.brain.hasGun();
    }

    @Override
    public void start() {
        this.brain.onGoalStart();
    }

    @Override
    public void tick() {
        this.brain.tick();
    }

    @Override
    public void stop() {
        this.brain.onGoalStop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public String toString() {
        return "GunAttackGoal[" + this.mob.getType().toShortString() + " " + this.brain.debugSummary() + "]";
    }
}
