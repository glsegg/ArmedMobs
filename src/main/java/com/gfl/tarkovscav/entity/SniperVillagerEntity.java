package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.gun.SniperBehavior;
import com.gfl.tarkovscav.gun.SniperMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

/**
 * 狙击手村民 / the sniper villager (README 5q): a gunner villager that fights from a <b>post</b> instead of
 * walking in - the villager counterpart of {@link SniperPillagerEntity}, and the answer to "给村民也加个狙击手的".
 *
 * <h2>What is reused (which is nearly everything)</h2>
 * <table border="1">
 *   <caption>piece by piece</caption>
 *   <tr><th>Piece</th><th>Where it comes from</th></tr>
 *   <tr><td>model + texture + profession/biome skins</td><td>{@code GunnerVillagerEntity} (vanilla
 *       {@code VillagerModel} + {@code VillagerProfessionLayer}, no new assets)</td></tr>
 *   <tr><td>sounds</td><td>{@code Villager}'s own ambient/hurt/death sounds, inherited unchanged</td></tr>
 *   <tr><td>arm pose + gun mount</td><td>{@code GunnerVillagerModel} (the {@code ArmedModel} hand frame)</td></tr>
 *   <tr><td>combat</td><td>{@code GunBrain} / {@code GunAttackGoal} / cover / reload / suppression /
 *       clips - identical to the other three gun mobs</td></tr>
 *   <tr><td>the sniper AI</td><td>{@link SniperBehavior} - the <b>same object</b> the sniper pillager
 *       ticks, not a copy of it</td></tr>
 *   <tr><td>faction</td><td>{@code #tarkovscav:faction_village}: friendly to players and villagers, hostile
 *       to illagers, and it shoots back at whoever hurts it</td></tr>
 * </table>
 *
 * <p>So this class is small <b>on purpose</b>: everything that makes it a sniper is in
 * {@link SniperBehavior}, and everything that makes it a villager is in the parent. What is left here is the
 * fixed sniper tier, the attributes, and one villager-specific guard (the brain, below).</p>
 *
 * <h2>The one villager-specific line: parking the Brain while relocating</h2>
 * <p>A villager is a <b>Brain</b> mob, so the parent already parks its brain while it has a target (otherwise
 * {@code MoveToTargetSink} would keep overwriting the path the gun goal issued). Relocating is the second case
 * where the brain has to stay out of the way: the sniper clears its target for the duration of the walk (that
 * is how it survives the trip without being dragged back into the fight), and a cleared target is exactly what
 * would wake the brain up and let it fight the navigation the behaviour just set. So the guard covers
 * "relocating" too.</p>
 */
public class SniperVillagerEntity extends GunnerVillagerEntity implements SniperMob {
    private final SniperBehavior sniper = new SniperBehavior(this);

    public SniperVillagerEntity(EntityType<? extends SniperVillagerEntity> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------ tier

    /** Always the sniper tier, exactly like the pillager sniper (and therefore the veteran profile). */
    @Override
    public ScavTier scavTier() {
        return ScavTier.SNIPER;
    }

    /** And the saved tier field too; see {@link GunnerVillagerEntity#forcedSpawnTier()}. */
    @Override
    protected ScavTier forcedSpawnTier() {
        return ScavTier.SNIPER;
    }

    /**
     * The sniper's sight: 64 blocks by default, which is what makes holding a post worth it.
     *
     * <p>Registered with {@link Config#DEFAULT_SNIPER_FOLLOW_RANGE} on purpose - this runs inside
     * {@code EntityAttributeCreationEvent}, where the config is not loaded yet and a live
     * {@code Config.SNIPER_FOLLOW_RANGE.get()} threw {@code Cannot get config value before config is
     * loaded}. {@link #onAddedToWorld()} applies the configured value afterwards.</p>
     */
    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createSniperAttributes() {
        return Villager.createAttributes()
                .add(Attributes.FOLLOW_RANGE, Config.DEFAULT_SNIPER_FOLLOW_RANGE);
    }

    /** The configured half of the attribute fix; see {@link SniperPillagerEntity#onAddedToWorld()}. */
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

    /**
     * See the class comment: the villager brain must also stay parked while the sniper is walking to a new
     * post, because the behaviour clears the target for that walk.
     */
    @Override
    protected void customServerAiStep() {
        if (this.getTarget() == null && !this.sniper.isRelocating()) {
            super.customServerAiStep();
        }
    }

    @Override
    public String toString() {
        return "SniperVillager[" + scavTier().id() + " " + com.gfl.tarkovscav.TarkovScav.MOD_ID + "] "
                + this.sniper.describe();
    }
}
