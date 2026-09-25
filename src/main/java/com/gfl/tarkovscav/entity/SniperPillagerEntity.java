package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.gun.SniperBehavior;
import com.gfl.tarkovscav.gun.SniperMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The sniper pillager (README 5q): a gunner pillager that fights from a <b>post</b> instead of walking in.
 *
 * <h2>What is reused, and what is new</h2>
 * <p>Everything about shooting is inherited: the same model, the same renderer, {@code GunBrain},
 * {@code GunAttackGoal}, the cover/reload/suppression tactics and the clip selection. The sniper part - hold a
 * post, leave when it is burned, walk to a new one out of sight, re-acquire on arrival - is
 * {@link SniperBehavior}, which this class <b>owns and forwards to</b>. It is a component rather than a
 * superclass because the two sniper mobs must extend different vanilla classes (this one a {@code Pillager},
 * the villager sniper a {@code Villager}); see {@link SniperBehavior} for the full rule list.</p>
 *
 * <p>Because it is issued a sniper-tier loadout ({@link #forcedSpawnTier()}), it automatically lands in the
 * <b>veteran</b> accuracy profile - that is the "stronger humanoid shoots straighter" the user asked for, and
 * it needs no line of code in this class to be true.</p>
 */
public class SniperPillagerEntity extends GunnerPillagerEntity implements SniperMob {
    private final SniperBehavior sniper = new SniperBehavior(this);

    public SniperPillagerEntity(EntityType<? extends SniperPillagerEntity> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------ tier

    /**
     * Always the sniper tier: the whole point of the mob. The loadout (and therefore the exact gun id) still
     * comes from {@code GunPool}, so the actual rifle is whatever the installed gun pack offers - the entity
     * never names a gun.
     */
    @Override
    public ScavTier scavTier() {
        return ScavTier.SNIPER;
    }

    /**
     * And the same for the tier <em>field</em>, which is what {@code applyTierAttributes} reads and what is
     * saved. Overriding only {@link #scavTier()} used to leave the field on a randomly rolled tier, so a
     * sniper could spawn with a rifleman's health and - worse - be saved as that tier and come back as a
     * rifleman after a reload. Both now agree, by construction.
     */
    @Override
    protected ScavTier forcedSpawnTier() {
        return ScavTier.SNIPER;
    }

    /**
     * The sniper's sight: much further than a rifleman's, which is what makes a post useful.
     *
     * <p>Registered with the <b>constant</b>, never with a live config read: this method is called from
     * {@code EntityAttributeCreationEvent}, which fires before Forge has loaded the common config, so a
     * {@code Config.SNIPER_FOLLOW_RANGE.get()} here threw
     * {@code Cannot get config value before config is loaded} and stopped the server from starting.
     * {@link #onAddedToWorld()} applies the configured value once the entity exists.</p>
     */
    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createSniperAttributes() {
        return net.minecraft.world.entity.monster.Pillager.createAttributes()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE,
                        com.gfl.tarkovscav.Config.DEFAULT_SNIPER_FOLLOW_RANGE);
    }

    // ------------------------------------------------------------------ the configured sight range

    /**
     * The configured half of the attribute fix: {@code sniper.followRange} is written here, not at
     * registration time. Forge calls this for a natural spawn, a chunk load and a {@code /summon} alike, and
     * {@link SniperMob#applySniperFollowRange()} refuses to read the config until it is loaded.
     */
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.level().isClientSide) {
            applySniperFollowRange();
        }
    }

    // ------------------------------------------------------------------ the shared sniper behaviour

    @Override
    public SniperBehavior sniper() {
        return this.sniper;
    }

    @Override
    public void tick() {
        super.tick();
        // PERFORMANCE (tools/spike/work/aiperf): a dead or already-removed sniper must not keep ticking its
        // post logic for the 20-tick death animation. Vanilla's death handling is in super.tick().
        if (this.isRemoved() || this.isDeadOrDying()) {
            return;
        }
        if (!this.level().isClientSide) {
            this.sniper.serverTick();
        }
    }

    // ------------------------------------------------------------------ debug

    @Override
    public String toString() {
        return super.toString() + " " + this.sniper.describe();
    }
}
