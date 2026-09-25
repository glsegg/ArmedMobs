package com.gfl.tarkovscav.gun;

/**
 * The gun-AI state machine - a small "Armed Mobs" behaviour set rather than a straight
 * shoot-when-you-see-someone loop.
 *
 * <pre>
 * IDLE ──spots a target──▶ ALERT ──reaction time──▶ decide()
 *                                                    │
 *   ┌────────────────────────────────────────────────┼───────────────────────────────┐
 *   │ in range + line of sight                       │ too far                       │ no line of sight
 *   ▼                                                ▼                               ▼
 *  AIM ─▶ FIRE ──burst done──▶ REPOSITION        ADVANCE (cover to cover)        SUPPRESS (blind fire on the
 *   │        │                                     │  arrives / gets close        │  remembered position)
 *   │        ├─ NO_AMMO ─▶ RELOAD (behind cover) ──▶ AIM                          │  memory expires ─▶ ADVANCE
 *   │        └─ NEED_BOLT ─▶ BOLT ─▶ AIM                                          │  line of sight back ─▶ AIM
 *   │
 *   └── hurt hard / health low ─▶ RETREAT (cover away from the threat) ─▶ decide() again
 * </pre>
 *
 * <p>Every transition, every TaCZ {@code ShootResult} and every cover decision is logged when
 * {@code tactics.logGunAi} is on - that log is the evidence that the mob is really driving TaCZ and
 * really using cover, rather than standing in the open.</p>
 */
public enum GunAiState {
    /** No target: wander, look around, keep the gun holstered. */
    IDLE,
    /** A target was spotted; turn to face it and let the reaction timer run down. */
    ALERT,
    /** Too far to shoot: move from cover to cover towards the target. */
    ADVANCE,
    /** Raise the weapon (TaCZ {@code draw}) and settle the aim. */
    AIM,
    /** Pull the trigger; TaCZ enforces the gun's real rate of fire. */
    FIRE,
    /** The target broke line of sight: keep firing at the last known position to pin it down. */
    SUPPRESS,
    /** Magazine empty: get behind cover, then TaCZ {@code reload()} (which eats real ammo items). */
    RELOAD,
    /** Bolt-action: TaCZ {@code bolt()} between shots. */
    BOLT,
    /** Move sideways to a new firing position so the target cannot just pre-aim this one. */
    REPOSITION,
    /** Taking fire or badly hurt: break contact towards cover that is further from the threat. */
    RETREAT
}
