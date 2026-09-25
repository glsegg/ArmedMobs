package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;

/**
 * Who is allowed to write a pose bone: the rig's own clip keyframes, this mod's code, or the
 * per-bone decision {@code auto} makes from what the currently playing clip actually animates.
 *
 * <h2>Why this exists</h2>
 * <p>The imported YSM rig expresses its aim pose in Molang that references YSM-only variables
 * ({@code ysm.head_yaw}, {@code ysm.head_pitch}, ...). GeckoLib resolves an unknown variable to 0, so
 * all 106 of those keyframes used to collapse to constants and the author's torso/head aim was dead.
 * {@link RigSupport} therefore wrote the aim itself - {@code UpperBody} and {@code Head}, absolutely -
 * and that is what the user accepted.</p>
 *
 * <p>Now that the variables are supplied again ({@code client.molangVariables}) the author's tracks do
 * move, and two writers on one bone is exactly the failure this enum exists to prevent: on one bone the
 * two contributions add up (the angle is larger than either author intended), and the frame-to-frame
 * winner can even alternate, which reads as the upper body twisting as the mob walks. So at most one of
 * the two may write a given bone, and this setting says which:</p>
 *
 * <ul>
 *   <li>{@link #AUTO} (default, and the shipped value) - per bone: if the clip that is playing drives
 *       that bone from the entity's look (its keyframes are Molang, not constants, see
 *       {@link ClipPose#lookDriven}) <b>and</b> the yaw symbols are actually being fed
 *       ({@code client.molangVariables = all}), the clip owns it and the code writes nothing there;
 *       otherwise the code keeps its absolute fallback write. This is "respect the author where the
 *       author expressed the aim, keep our tracking everywhere else". The code's total yaw/pitch gain
 *       stays 1.0 by construction ({@link RigSupport}), so reviving the author's tracking cannot double
 *       it.</li>
 *   <li>{@link #CODE} - the code writes {@code UpperBody} and {@code Head} exactly as it did before the
 *       author's Molang was revived. The one-key rollback: pair it with
 *       {@code client.molangVariables = false} to reproduce the previous behaviour byte for byte.</li>
 *   <li>{@link #CLIPS} - the code writes no pose bone at all; the rig is entirely the author's. Useful
 *       as the "other extreme" of the A/B, not as a play mode: a rig whose clips are missing plays
 *       nothing, and the {@code headRestPitchDegrees} correction is inactive by definition.</li>
 * </ul>
 */
public enum PoseSource {
    /** Per bone: the clip owns it where the author's keyframes react to the look, the code elsewhere. */
    AUTO,
    /** The code owns {@code UpperBody} and {@code Head} (the pre-Molang behaviour). */
    CODE,
    /** The clips own everything; the code writes no pose bone. */
    CLIPS;

    /** The value {@code auto} is the shipped default, so a typo falls back to it, not to a mode change. */
    public static final String DEFAULT = "auto";

    /**
     * Parses {@code client.poseSource}. An unreadable value is reported and treated as {@link #AUTO},
     * never as {@link #CODE}: silently switching the aim tracking off because of a typo would look like
     * a regression in a feature that has nothing to do with this key.
     */
    public static PoseSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String token = raw.trim();
        for (PoseSource candidate : values()) {
            if (candidate.name().equalsIgnoreCase(token)) {
                return candidate;
            }
        }
        if (token.equalsIgnoreCase("clip") || token.equalsIgnoreCase("author")) {
            return CLIPS;
        }
        if (token.equalsIgnoreCase("java") || token.equalsIgnoreCase("code-only")) {
            return CODE;
        }
        TarkovScav.LOGGER.warn("client.poseSource = '{}' is neither auto, code nor clips; using {}", raw,
                DEFAULT);
        return AUTO;
    }
}
