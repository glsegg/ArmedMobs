package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;

/**
 * The clip names the mob plays, and which TaCZ state each one belongs to.
 *
 * <p>This is the port of the model author's own YSM layering. Their rig splits at the top into
 * {@code UpBody} and {@code DownBody}, and their clip library reflects that: {@code tac:walk} and
 * {@code tac:run} touch <b>only</b> the lower body (measured: 0 upper-body bones), while every
 * {@code tac:aim:*}, {@code tac:hold:*} and {@code tac:reload:*} clip touches <b>only</b> the upper
 * body. So the mob runs two GeckoLib controllers:</p>
 *
 * <ul>
 *   <li><b>movement</b> - lower body: a plain {@code idle}/{@code walk}/{@code run} when unarmed,
 *       the author's {@code tac:*} lower-body clips when armed;</li>
 *   <li><b>gun</b> - upper body: one clip chosen from the TaCZ state, one-handed ({@code :pistol})
 *       or two-handed ({@code :rifle}) depending on the gun's TaCZ type.</li>
 * </ul>
 *
 * <p>GeckoLib applies the controllers in registration order and the later one wins per bone, so the
 * gun controller is registered second and takes the arms, head and torso while the movement
 * controller keeps the legs - no single clip overrides the whole body, which is what used to make a
 * walking mob look like it was sliding.</p>
 *
 * <p>The names below are exactly the keys in {@code assets/tarkovscav/animations/scav.animation.json},
 * which {@code tools/import_scav_assets.js} writes from the YSM sources.</p>
 */
public final class GunClips {
    // ---- lower body, no gun -------------------------------------------------------------
    public static final String IDLE = "idle";
    public static final String WALK = "walk";
    public static final String RUN = "run";
    public static final String DEATH = "death";

    // ---- lower body, gun in hand (the author's own armed movement set) -------------------
    public static final String ARMED_IDLE = "tac:idle";
    public static final String ARMED_WALK = "tac:walk";
    public static final String ARMED_RUN = "tac:run";

    /** Clip families in the rig: one-handed and two-handed. */
    public static final String FAMILY_PISTOL = "pistol";
    public static final String FAMILY_RIFLE = "rifle";

    private GunClips() {
    }

    /**
     * Which family of gun clips this weapon uses. TaCZ's gun {@code type} decides it, so a custom gun
     * pack is classified automatically: {@code pistol} and {@code smg} fire one-handed (the config
     * lists them) and everything else uses the two-handed poses.
     */
    public static String family(GunLoadout loadout) {
        if (loadout == null) {
            return FAMILY_RIFLE;
        }
        return Config.usesPistolClips(loadout.gunType()) ? FAMILY_PISTOL : FAMILY_RIFLE;
    }

    /** {@code tac:aim:rifle}, {@code tac:reload:pistol}, ... */
    public static String gun(String family, String action) {
        return "tac:" + action + ":" + family;
    }

    /** The upper-body clip for a state, or null when the state has no gun pose of its own. */
    public static String forState(GunAiState state, String family) {
        return switch (state) {
            case RELOAD -> gun(family, "reload");
            case FIRE, SUPPRESS -> gun(family, "aim:fire");
            case AIM, ADVANCE -> gun(family, "aim");
            case RETREAT -> gun(family, "hold");
            case ALERT, REPOSITION, BOLT -> gun(family, "aim");
            case IDLE -> gun(family, "hold");
        };
    }

    /**
     * The clip action the CLIENT picks, from the synced pose flags plus the synced AI state.
     *
     * <p>The three booleans alone are not quite enough: {@code RETREAT} has {@code aiming = true}
     * ({@code GunBrain#transition} sets {@code next != IDLE}) while the brain calls
     * {@code op.aim(false)} in that state and {@link #forState} gives it the lowered {@code hold} pose.
     * The state byte that the arm pose already syncs resolves the ambiguity, so both sides now name the
     * same clip for all ten states - which is what {@code tools/selftest_clip_mapping.js} asserts.</p>
     */
    public static String actionFor(boolean aiming, boolean firing, boolean reloading, GunAiState state) {
        if (reloading) {
            return "reload";
        }
        if (firing) {
            return "aim:fire";
        }
        if (state == GunAiState.RETREAT) {
            return "hold";
        }
        return aiming ? "aim" : "hold";
    }

    /** The lower-body clip. Armed mobs use the author's {@code tac:*} leg clips. */
    public static String movement(boolean armed, boolean moving, boolean running) {
        if (armed) {
            if (!moving) {
                return ARMED_IDLE;
            }
            return running ? ARMED_RUN : ARMED_WALK;
        }
        if (!moving) {
            return IDLE;
        }
        return running ? RUN : WALK;
    }
}
