package com.gfl.tarkovscav.gun;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Ranged combat for a mob that took a bow or a crossbow off the weapon rack (README 5n).
 *
 * <h2>Why this is not vanilla's {@code RangedBowAttackGoal}</h2>
 * <p>That class is declared {@code RangedBowAttackGoal<T extends Monster & RangedAttackMob>}, and one of the
 * two converted mobs is a {@code GunnerVillagerEntity extends Villager} - not a {@code Monster} - so the
 * vanilla goal cannot be used for it. Rather than have two ranged paths (vanilla for the pillager, something
 * else for the villager), this goal serves both and mirrors the vanilla sequence: close to within
 * {@code attackRadius}, draw the weapon ({@code startUsingItem} - which is what makes the bow visibly pull
 * back), and on completion hand the shot to the mob's own {@link RangedAttackMob#performRangedAttack}.</p>
 *
 * <p>A crossbow is drawn for {@code CROSSBOW_DRAW_TICKS} instead of {@code BOW_DRAW_TICKS}: it has no
 * charge animation of its own here, and drawing it as fast as a bow would make the two mechanically
 * identical (and a crossbow strictly better).</p>
 */
public class ArmedRangedGoal extends Goal {
    /** The vanilla bow draw, in ticks. */
    public static final int BOW_DRAW_TICKS = 20;
    /** Deliberately longer: a crossbow hits harder, so it takes longer to be ready again. */
    public static final int CROSSBOW_DRAW_TICKS = 25;

    private final Mob mob;
    private final RangedAttackMob shooter;
    private final double speedModifier;
    private final float attackRadius;
    private final float attackRadiusSqr;

    private int attackTime = -1;
    private int seeTime;
    private int strafeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;

    public ArmedRangedGoal(Mob mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.shooter = (RangedAttackMob) mob;
        this.speedModifier = speedModifier;
        this.attackRadius = attackRadius;
        this.attackRadiusSqr = attackRadius * attackRadius;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** True when the stack is something this goal shoots. Used by the melee fallback to stand down. */
    public static boolean isRangedWeapon(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.BOW) || stack.is(Items.CROSSBOW));
    }

    private ItemStack weapon() {
        ItemStack main = this.mob.getMainHandItem();
        return isRangedWeapon(main) ? main : this.mob.getOffhandItem();
    }

    private int drawTicks() {
        return weapon().is(Items.CROSSBOW) ? CROSSBOW_DRAW_TICKS : BOW_DRAW_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // Draw/cooldown constants and seeTime are expressed in actual game ticks.
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        // Inert unless it is actually holding a bow/crossbow, so a normal gunner is untouched by this goal.
        return target != null && target.isAlive() && isRangedWeapon(weapon());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || (!this.mob.getNavigation().isDone() && isRangedWeapon(weapon())
                && this.mob.getTarget() != null);
    }

    @Override
    public void start() {
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }
        double distanceSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean canSee = this.mob.getSensing().hasLineOfSight(target);
        this.seeTime = canSee ? this.seeTime + 1 : 0;

        // Walk in until the target is inside the firing range, then hold still and shoot.
        if (distanceSqr > this.attackRadiusSqr || this.seeTime < 5) {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
        } else {
            this.mob.getNavigation().stop();
            this.strafeTime++;
            if (this.strafeTime >= 20) {
                if (this.mob.getRandom().nextFloat() < 0.3F) {
                    this.strafingClockwise = !this.strafingClockwise;
                }
                if (this.mob.getRandom().nextFloat() < 0.3F) {
                    this.strafingBackwards = !this.strafingBackwards;
                }
                this.strafeTime = 0;
            }
            if (!this.strafingBackwards && distanceSqr > this.attackRadiusSqr * 0.6F) {
                this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F,
                        this.strafingClockwise ? 0.5F : -0.5F);
            }
        }
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Draw, then release into the mob's own ranged attack.
        if (this.mob.isUsingItem()) {
            if (!isRangedWeapon(this.mob.getUseItem())) {
                this.mob.stopUsingItem();
            } else if (this.mob.getTicksUsingItem() >= drawTicks()) {
                this.mob.releaseUsingItem();
                // getMobArrow interprets this argument as draw power (0..1), not blocks. Passing
                // a 15-block distance used to make a plain bow deal roughly 30 base damage.
                this.shooter.performRangedAttack(target, 1.0F);
                this.attackTime = drawTicks() + 10;
            }
        } else if (this.attackTime <= 0 && canSee) {
            InteractionHand hand = ProjectileUtil.getWeaponHoldingHand(this.mob,
                    item -> item == Items.BOW || item == Items.CROSSBOW);
            this.mob.startUsingItem(hand);
        }
        if (this.attackTime > 0) {
            this.attackTime--;
        }
    }

    /** Shared fully drawn bow shot, including pillagers whose vanilla attack handles crossbows only. */
    public static void shootArrow(Mob mob, LivingEntity target, float drawPower) {
        ItemStack weapon = mob.getItemInHand(ProjectileUtil.getWeaponHoldingHand(mob,
                item -> item == Items.BOW || item == Items.CROSSBOW));
        ItemStack ammo = mob.getProjectile(weapon);
        var arrow = ProjectileUtil.getMobArrow(mob, ammo, net.minecraft.util.Mth.clamp(drawPower, 0.0F, 1.0F));
        double dx = target.getX() - mob.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F,
                (float) (14 - mob.level().getDifficulty().getId() * 4));
        if (mob.level().addFreshEntity(arrow)) {
            mob.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1.0F,
                    1.0F / (mob.getRandom().nextFloat() * 0.4F + 0.8F));
            if (mob instanceof SniperMob && SniperPost.holdingPost(mob)) {
                SniperPost.noteShot(mob, mob.level().getGameTime());
            }
        }
    }
}
