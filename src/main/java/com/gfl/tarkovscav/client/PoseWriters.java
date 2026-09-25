package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.model.GeoModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The evidence half of {@code client.poseSource}: which writer put the angle on which bone, and what
 * the angle came out as.
 *
 * <h2>What it is for</h2>
 * <p>"The upper body twists about while the mob walks" has two very different causes and they look the
 * same in a screenshot:</p>
 * <ul>
 *   <li><b>two writers on one bone</b> - the clip's keyframe and this mod's code both rotate it, so the
 *       angle is the sum (larger than either author intended). The first difference of the yaw series
 *       then tracks {@code netHeadYaw} with a gain greater than 1.</li>
 *   <li><b>two writers alternating</b> - the frame-to-frame winner changes (a clip that starts and stops
 *       animating the bone, or a controller ordering effect), so the series is not a scaled copy of
 *       {@code netHeadYaw} at all: it steps between two poses. Its first difference does not correlate
 *       with the first difference of {@code netHeadYaw}, and its zero-crossing count is unrelated to
 *       the input's.</li>
 * </ul>
 * <p>One line per rendered frame, containing the input yaw/pitch, the writer of every pose bone and the
 * resulting angles, is what separates the two: differentiate the {@code chainYaw} column, correlate it
 * with {@code yaw}, count zero crossings. That is why the per-frame dump exists instead of a summary -
 * a summary cannot show an alternation.</p>
 *
 * <h2>The invariant it also enforces</h2>
 * <p>{@link #note} records the writer of each bone for the frame and WARNs, once per bone and pair, when
 * one bone is written by <b>both a look-driven clip track and this mod's code</b>. That is the defect:
 * two expressions describing the same aim on one bone, with the visible winner depending on the AI
 * state. The arbiter in {@link RigSupport} makes it impossible under {@code poseSource = auto} and
 * {@code clips}, so the WARN firing there is a real defect signal; under {@code code} it is expected -
 * that mode deliberately keeps the code in charge - and it is the reason {@code code} is a fallback to
 * be paired with {@code client.molangVariables = false}, not an equal A/B.</p>
 *
 * <p>The code replacing a clip's <b>fixed</b> (non-Molang) track is counted separately and never
 * warned about: a numeric authored keyframe cannot follow the look direction, so replacing it is the
 * fallback this mod has always used for the unarmed {@code idle}/{@code walk}/{@code run} clips.</p>
 */
public final class PoseWriters {
    /** The bone that carries the whole body below the torso split; rotated by {@code AllBody}. */
    public static final List<String> LOGGED_BONES =
            List.of("Root", "AllBody", "UpBody", "UpperBody", "Body", "Head", "Arm", "RightArm", "LeftArm");

    public static final String CODE = "code";
    public static final String CLIPS_MOLANG = "clips(molang)";
    public static final String CLIPS_FIXED = "clips(fixed)";

    /** Frames logged, frames in which a look-driven clip track and the code both wrote one bone. */
    private static long frames;
    private static long lookConflicts;

    /** Frames in which the code replaced a clip's fixed (non-look) track - the documented fallback. */
    private static long fixedOverrides;

    /** Conflict keys already reported, so the log stays readable. */
    private static final Set<String> LOGGED_CONFLICTS = ConcurrentHashMap.newKeySet();

    /**
     * The frame being written, per thread. A model writes its pose bones in one
     * {@code setCustomAnimations} call, and the arm pose and the aim pose are two calls on the same
     * model, so the map has to survive between them - but only until the frame is logged.
     */
    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    private PoseWriters() {
    }

    /** True when the per-frame dump is on; the callers use it to skip building the bookkeeping map. */
    public static boolean enabled() {
        return Config.SPEC.isLoaded() && Config.LOG_POSE_WRITERS.get();
    }

    /**
     * Starts a mob's frame. Called once per rendered mob from {@code GeoModel#handleAnimations}, i.e.
     * before the controllers tick and before the code writes its pose, so a frame that was never
     * flushed (a model that returned early) cannot attribute one mob's bones to the next one.
     */
    public static void beginMob() {
        CURRENT.remove();
    }

    /**
     * Starts (or continues) a frame: a writer map pre-filled with every pose bone the clips drive.
     * Returns null when the dump is off, which is what keeps the per-frame cost at zero in normal play.
     *
     * <p>An existing map is kept rather than replaced, because one model writes its pose in two calls -
     * the arm fallback and then the aim tracking - and both belong to the same logged frame.</p>
     */
    public static Map<String, String> frame(ClipPose clip) {
        if (!enabled()) {
            CURRENT.remove();
            return null;
        }
        Map<String, String> writers = CURRENT.get();
        if (writers == null) {
            writers = new LinkedHashMap<>();
            CURRENT.set(writers);
        }
        for (String bone : LOGGED_BONES) {
            if (!writers.containsKey(bone) && clip.anyTrack(bone)) {
                // "molang" means the track can actually react to the look, which needs the yaw symbols
                // to be fed - with client.molangVariables = pitch (the default) a yaw expression is a
                // constant, and calling it look-driven in the log would report conflicts that are not.
                boolean look = Config.feedsMolangYaw() && clip.lookDriven(bone);
                writers.put(bone, look ? CLIPS_MOLANG : CLIPS_FIXED);
            }
        }
        return writers;
    }

    /**
     * Records that {@code writer} wrote {@code bone} this frame.
     *
     * <p>Two cases are counted separately, because only one of them is a defect:</p>
     * <ul>
     *   <li>the bone already had a <b>look-driven</b> clip track ({@code clips(molang)}): a defect. Two
     *       writers are expressing the same aim on one bone, the later one wins, and the winner changes
     *       with the AI state - the twist. Counted as a look-conflict and WARNed, once per bone and
     *       pair. {@link RigSupport}'s arbiter makes it impossible under {@code auto}/{@code clips}, so
     *       this firing is a real signal; under {@code poseSource = code} it is expected and is what
     *       makes that mode a fallback rather than an A/B equal.</li>
     *   <li>the bone already had a <b>fixed</b> clip track ({@code clips(fixed)}): the documented
     *       fallback. A numeric authored keyframe cannot follow the look direction, so the code's
     *       absolute aim write replacing it is the behaviour this mod has always had for the unarmed
     *       {@code idle}/{@code walk}/{@code run} clips - counted, never warned about.</li>
     * </ul>
     * {@code writers} may be null when the dump is off.
     */
    public static void note(Map<String, String> writers, String bone, String writer) {
        if (writers == null) {
            return;
        }
        String existing = writers.get(bone);
        if (existing == null) {
            writers.put(bone, writer);
            return;
        }
        if (existing.startsWith("clips") && writer.startsWith("clips")) {
            // Two clips writing one bone is a layering (registration order decides), not a conflict.
            if (!existing.contains(writer)) {
                writers.put(bone, existing + "+" + writer);
            }
            return;
        }
        if (existing.startsWith("clips")) {
            if (existing.contains(CLIPS_MOLANG)) {
                lookConflicts++;
                String key = bone + "|" + existing + "|" + writer;
                if (LOGGED_CONFLICTS.add(key)) {
                    TarkovScav.LOGGER.warn("[pose] bone '{}' was written by BOTH a look-driven clip track"
                                    + " ('{}') and this mod's code in one frame. That is the two-writer"
                                    + " defect client.poseSource exists to prevent: the two expressions"
                                    + " describe the same aim on one bone, the later writer wins, and"
                                    + " which one wins changes with the AI state - the pose flips instead"
                                    + " of following. Use client.poseSource = auto (or clips) so only one"
                                    + " of them writes it.",
                            bone, existing);
                }
                return;
            }
            fixedOverrides++;
            writers.put(bone, existing + "+" + writer);
            return;
        }
        writers.put(bone, existing + "+" + writer);
    }

    /**
     * Ends a frame: logs the line when the dump is on, and drops the thread-local so a frame that was
     * never flushed cannot leak into the next mob.
     */
    public static void flush(Entity entity, PoseSource source, ClipPose clip, float netHeadYaw,
                             float headPitch, GeoModel<?> model) {
        Map<String, String> writers = CURRENT.get();
        CURRENT.remove();
        if (writers != null) {
            // README 5w: a bone whose rotation/position/scale is NaN or infinite makes its geometry
            // degenerate (huge stretched or flipped blocks, or a bone that vanishes), and the usual cause is
            // an author Molang expression fed a value it was never written for. Report it ONCE per mob type
            // and bone while logPoseWriters is on, with the writer that produced it, so a real machine can
            // point at the expression instead of guessing.
            checkFinite(entity, model, writers);
            log(entity, source, clip, netHeadYaw, headPitch, model, writers);
        }
    }

    /** Bones already reported as non-finite, so a broken pose logs one line instead of one per frame. */
    private static final java.util.Set<String> NON_FINITE_REPORTED = java.util.concurrent.ConcurrentHashMap
            .newKeySet();

    /**
     * The non-finite guard: every registered bone is checked for NaN/Inf in its rotation, position and scale.
     * An ERROR is logged once per mob type + bone + field, naming the value and the writers - the evidence a
     * "the mob renders as a black block" report needs (README 5w).
     */
    private static void checkFinite(Entity entity, GeoModel<?> model, Map<String, String> writers) {
        for (CoreGeoBone bone : model.getAnimationProcessor().getRegisteredBones()) {
            check(entity, bone.getName(), "rot", bone.getRotX(), bone.getRotY(), bone.getRotZ(), writers);
            check(entity, bone.getName(), "pos", bone.getPosX(), bone.getPosY(), bone.getPosZ(), writers);
            check(entity, bone.getName(), "scale", bone.getScaleX(), bone.getScaleY(), bone.getScaleZ(),
                    writers);
        }
    }

    private static void check(Entity entity, String bone, String field, float x, float y, float z,
                              Map<String, String> writers) {
        if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)) {
            return;
        }
        String key = entity.getType().toShortString() + "|" + bone + "|" + field;
        if (NON_FINITE_REPORTED.add(key)) {
            TarkovScav.LOGGER.error("[pose] NON-FINITE {}.{} = ({}, {}, {}) on {} - this is a broken bone"
                            + " matrix, not a modelling choice. Writers: {}. Check the Molang expression that"
                            + " feeds this bone (see README 5w), or set client.poseSource=code /"
                            + " molangVariables=off to take the Molang feed out of the picture.",
                    bone, field, x, y, z, entity.getType().toShortString(), join(writers));
        }
    }

    /**
     * One line per rendered frame: the input the pose was driven from, the writer of every pose bone,
     * and the angles those writers produced.
     */
    private static void log(Entity entity, PoseSource source, ClipPose clip, float netHeadYaw,
                            float headPitch, GeoModel<?> model, Map<String, String> writers) {
        if (writers == null) {
            return;
        }
        frames++;
        StringBuilder angles = new StringBuilder();
        float chainYaw = 0.0F;
        for (String bone : LOGGED_BONES) {
            CoreGeoBone geoBone = model.getAnimationProcessor().getBone(bone);
            if (geoBone == null) {
                continue;
            }
            float yawDegrees = geoBone.getRotY() / ((float) Math.PI / 180.0F);
            // The head's own chain: every bone between the root and the face that carries a Y rotation.
            if (bone.equals("AllBody") || bone.equals("UpBody") || bone.equals("UpperBody")
                    || bone.equals("Body") || bone.equals("Head")) {
                chainYaw += yawDegrees;
            }
            if (angles.length() > 0) {
                angles.append(',');
            }
            angles.append(bone).append(".Y=").append(round(yawDegrees));
        }
        CoreGeoBone head = model.getAnimationProcessor().getBone("Head");
        String headPitchDegrees = head == null ? "n/a"
                : round(head.getRotX() / ((float) Math.PI / 180.0F));
        TarkovScav.LOGGER.info("[pose] frame={} mob={}#{} src={} clips={} yaw={} pitch={} writers=[{}]"
                        + " yawDeg=[{}] headX={} chainYaw={}",
                frames, entity.getType().toShortString(), entity.getId(),
                source.name().toLowerCase(Locale.ROOT), clip.clips(), round(netHeadYaw), round(headPitch),
                join(writers), angles, headPitchDegrees, round(chainYaw));
    }

    private static String join(Map<String, String> writers) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : writers.entrySet()) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    private static String round(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Frames logged, look-conflicts and fixed-track overrides, for {@code /tarkovscav client state}. */
    public static String summary() {
        return "pose frames=" + frames + " two-writer-look-conflicts=" + lookConflicts
                + " fixed-track-overrides=" + fixedOverrides;
    }

    /** Called by {@link RigSupport#invalidateConfig()}: a live config change starts a fresh count. */
    static void forget() {
        LOGGED_CONFLICTS.clear();
    }
}
