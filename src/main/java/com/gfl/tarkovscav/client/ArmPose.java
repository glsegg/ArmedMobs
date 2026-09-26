package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.gun.GunAiState;

/**
 * What the arms should be doing, as a function of the gun AI's own state.
 *
 * <h2>One predicate, taken from the state machine - not from how it looks</h2>
 * <p>The requirement was explicit: the pose must not be a second, independent guess at "is this mob
 * about to shoot", because that is how a mob ends up holding a gun up while not firing, or firing
 * with its arms down. The single source of truth used here is the state the brain itself is in,
 * synced to the client by {@code GunPose.Keys#setState} from the same {@code GunBrain#transition}
 * call that sets the aiming/firing/reloading flags.</p>
 *
 * <h2>Is it the same condition as "TaCZ is asked to shoot"?</h2>
 * <p>{@code IGunOperator#shoot} is called from exactly two places in {@link
 * com.gfl.tarkovscav.gun.GunBrain}: {@code tickFire} (state {@code FIRE}) and {@code tickSuppress}
 * (state {@code SUPPRESS}). Both map to {@link #RAISED} here, so "a shot can leave the barrel" always
 * implies "the weapon is up". The converse is deliberately not true: {@code AIM}, {@code ALERT},
 * {@code ADVANCE}, {@code BOLT} and {@code REPOSITION} also keep the weapon raised although they are
 * not shooting - which is what "he should raise the gun when he is about to shoot" means, and it is
 * the same relationship the original mod already used for the clips
 * ({@code isGunAiming() = state != IDLE}) and for TaCZ's own {@code aim(true)} calls.</p>
 *
 * <p>The two states where the brain explicitly lowers the weapon at the TaCZ level are
 * {@code RELOAD} ({@code op.aim(false)}) and {@code RETREAT} ({@code op.aim(false)}); they get their
 * own poses instead of the raised one, which is exactly what the user asked for ("reloading: lower
 * the gun; retreating: hunker down"). {@code IDLE} uses the model's relaxed carry: long guns on the
 * vanilla illager use a lowered forward hold to keep long barrels above the ground.</p>
 */
public enum ArmPose {
    /** Relaxed carry, chosen by the model for its equipped weapon family. */
    LOWERED,
    /** Weapon up, pointing where the head looks: alert, advancing, aiming, firing, suppressing. */
    RAISED,
    /** Magazine out: gun lowered towards the chest, both hands working. */
    RELOADING,
    /** Breaking contact: gun down, shoulders in. */
    HUNKERED;

    /** The pose for one AI state. This is the only mapping in the mod. */
    public static ArmPose forState(GunAiState state) {
        if (state == null) {
            return LOWERED;
        }
        return switch (state) {
            case IDLE -> LOWERED;
            case RELOAD -> RELOADING;
            case RETREAT -> HUNKERED;
            // everything that points the gun at something
            case ALERT, ADVANCE, AIM, FIRE, SUPPRESS, BOLT, REPOSITION -> RAISED;
        };
    }

    /** True for every pose that holds the weapon up. */
    public boolean raisesWeapon() {
        return this != LOWERED;
    }
}
