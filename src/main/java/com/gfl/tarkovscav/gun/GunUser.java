package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.entity.ScavTier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;

/**
 * Implemented by every mob that fights with a TaCZ gun ({@code ScavEntity},
 * {@code GunnerPillagerEntity}).
 *
 * <p>The point of the interface is that {@link GunBrain} - all of the state machine, the aiming, the
 * ammo bookkeeping - is written once and shared. The two mobs are otherwise unrelated (one is a
 * Monster, the other extends the vanilla Pillager and keeps all of its behaviour), so composition
 * through this interface is what keeps the gun logic in a single place.</p>
 */
public interface GunUser {
    /** The mob itself. */
    Mob asMob();

    /** The (lazily created) brain that drives this mob's gun. */
    GunBrain gunBrain();

    /**
     * The small inventory whose contents TaCZ eats when the mob reloads.
     *
     * <p>This has to be exposed as a Forge {@code IItemHandler} capability (see
     * {@link GunCapabilities}): TaCZ looks ammo up through
     * {@code ForgeCapabilities.ITEM_HANDLER} on the shooter, and a vanilla {@code Mob} has no such
     * capability, so without this a mob could fire the round in the chamber and then never reload
     * again.</p>
     */
    MobAmmoInventory ammoInventory();

    /** The tier this mob rolled on spawn (drives its gun pool and its per-tier numbers). */
    ScavTier scavTier();

    /** Assigns the tier. Called from {@code finalizeSpawn}; re-rolls the gear on the next tick. */
    void setScavTier(ScavTier tier);

    /**
     * Pushes the current gun pose to the client so the animation controllers can pick a clip. Every
     * implementation backs this with the {@link GunPose.Keys} it created for its own class.
     */
    void setGunPose(boolean aiming, boolean firing, boolean reloading);

    /**
     * Pushes the state machine's own state to the client. Called from
     * {@link GunBrain#transition} - the same place that calls {@link #setGunPose} - so the two can
     * never disagree, and a renderer that branches on this state is branching on exactly the state
     * the brain is in (see {@code ArmPose}).
     */
    void setGunAiState(GunAiState state);

    /** Client-safe: the synced AI state, {@link GunAiState#IDLE} before the brain has ever run. */
    GunAiState gunAiState();

    /** Tells the client which clip family (one-handed or two-handed) the equipped gun uses. */
    void setPistolClips(boolean pistolClips);

    /** Client-safe: true while the weapon is raised (aiming, advancing, firing, reloading). */
    boolean isGunAiming();

    /** Client-safe: true on the ticks a shot is actually being taken. */
    boolean isGunFiring();

    /** Client-safe: true while TaCZ is reloading - the upper body plays the reload clip. */
    boolean isGunReloading();

    /** Client-safe: true when the equipped gun uses the rig's one-handed (*_pistol) clips. */
    boolean usesPistolClips();

    /**
     * README 5ab: true while the shot this unit is about to take is being taken against an EXPOSED
     * target - eyes AND feet visible from the mob's own eyes, and the target inside its effective
     * range. The brain recomputes this once per AI tick, before it pulls the trigger, and the whole
     * tick's decisions read that one verdict: the burst length, the post-burst pause, and the warm-up
     * waiver {@link AccuracyProfile#warmingUp} applies.
     *
     * <p>It is a default method on purpose: the verdict is entirely the brain's, so the two
     * implementations (and the nine entity types behind them) need no per-class field, and a unit whose
     * brain has not been built yet simply answers false - the safe, pre-5ab answer.</p>
     */
    default boolean targetExposedNow() {
        GunBrain brain = gunBrain();
        return brain != null && brain.targetExposedNow();
    }

    /**
     * Lets this mob walk through and open doors. <b>Call it from the constructor.</b>
     *
     * <p>This is the fix for "the mob stands at a closed door stamping its feet": a
     * {@code PathNavigation} that cannot pass doors fails the path node at the door, so
     * {@code moveTo} silently produces no movement while the walk animation still runs - exactly the
     * reported symptom. The vanilla facts, read from the 1.20.1 class files:</p>
     * <ul>
     *   <li>{@code Villager}, {@code Vindicator} and {@code Zombie} constructors call
     *       {@code GroundPathNavigation#setCanOpenDoors(true)};</li>
     *   <li>{@code Raider} and {@code Pillager} do <b>not</b> - so a gunner pillager inherited "cannot
     *       open doors" from vanilla, and a scav (a plain {@code Monster}) never had the ability at
     *       all;</li>
     *   <li>{@code setCanPassDoors(true)} is the companion flag for walking through a door that is
     *       already open.</li>
     * </ul>
     * <p>{@code GunnerVillagerEntity} does not need this: it extends {@code Villager}, whose own
     * constructor already sets it.</p>
     */
    static void allowDoors(Mob mob) {
        if (mob.getNavigation() instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);
            ground.setCanPassDoors(true);
        }
    }
}
