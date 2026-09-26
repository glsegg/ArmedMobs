package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunClips;
import com.gfl.tarkovscav.gun.GunUser;
import com.tacz.guns.api.item.IGun;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.state.BoneSnapshot;
import software.bernie.geckolib.model.GeoModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared behaviour of the two Bedrock rigs (the scav's imported YSM model and the gunner pillager's
 * placeholder): hiding the author's reference props, and making the torso and head follow the aim.
 *
 * <h2>The bone tree (measured from the imported geometry - {@code tools/analyze_scav_model.js})</h2>
 * <pre>
 *   Root → MAllBody → AllBody ─┬─ UpBody → UpperBody ─┬─ Arm ─┬─ RightArm → RightForeArm ─┬─ bone52
 *                              │                     │       │                           └─ RightHand
 *                              │                     │       │                                ├─ Gun3   ← gun anchor
 *                              │                     │       │                                ├─ Ban / Lianru /
 *                              │                     │       │                                │  Bao / Spwt / Jiu /
 *                              │                     │       │                                │  Parrot / money   ← props
 *                              │                     │       │                                ├─ RightHandDMZ
 *                              │                     │       │                                └─ RightHandLocator
 *                              │                     │       └─ LeftArm → … → LeftHand
 *                              │                     ├─ AllHead → Head → … (face, hat, ears)
 *                              │                     ├─ Bag, fangdanyi_2
 *                              │                     ├─ RifleLocator    (back-mount anchor, reserved)
 *                              │                     └─ PistolLocator   (thigh holster, reserved)
 *                              └─ DownBody → Leg ─┬─ LeftLeg → LeftLowerLeg → leftfoot
 *                                                 └─ RightLeg → RightLowerLeg → rightfoot
 * </pre>
 *
 * <p>The split at {@code UpBody}/{@code DownBody} is what makes the layered animation possible: see
 * {@link GunClips}.</p>
 */
public final class RigSupport {
    /**
     * The default gun anchor: {@code RightHandLocator}, the model author's in-hand item locator.
     *
     * <p>It is a child of {@code RightHand} (pivot {@code [-5.080, 16.597, 0.133]}, no cubes of its
     * own) and - crucially - it is the bone the rig's {@code tac:hold:*}, {@code tac:aim:*} and
     * {@code tac:reload:*} clips animate, which is how the author expresses the gun pose. YSM 2.6.5,
     * which the model was authored for, renders a held TaCZ gun on exactly this kind of locator group.
     * See {@code client.gunAnchorBone} to move it.</p>
     */
    public static final String DEFAULT_GUN_ANCHOR = "RightHandLocator";

    /**
     * The default offhand anchor: {@code LeftHandLocator}, the rig's own left-hand locator.
     *
     * <p>Measured from the imported geometry: it exists, it is a child of {@code LeftHand}, it has no
     * cubes of its own, and its pivot {@code [5.07955, 16.59652, 0.1325]} is the exact mirror of
     * {@code RightHandLocator}'s {@code [-5.07955, 16.59652, 0.1325]} - i.e. inside the left palm. Unlike
     * its right-hand counterpart NO clip animates it ({@code node tools/mount_matrix.js} prints that), so
     * it is a clean hand anchor rather than an authored gun pose.</p>
     */
    public static final String DEFAULT_OFFHAND_ANCHOR = "LeftHandLocator";

    /**
     * The rig's other gun-related bones, all empty markers:
     * <ul>
     *   <li>{@code Gun3} - a <em>modelled</em> placeholder rifle under {@code RightHand}; hidden by
     *       default, and a sibling (not a child) of {@code RightHandLocator};</li>
     *   <li>{@code RifleLocator} - back mount, child of {@code UpperBody}, pivot {@code [5, 26.096, 3]}
     *       (YSM's "背部枪械" / back gun anchor);</li>
     *   <li>{@code PistolLocator} - thigh mount, child of {@code RightLeg}, pivot
     *       {@code [-4.882, 18.160, 0]} (YSM's "腿部枪械" / leg gun anchor).</li>
     * </ul>
     * The last two are reserved for holstered weapons and are not used yet.
     */
    public static final String BACK_GUN_ANCHOR = "RifleLocator";

    public static final String THIGH_GUN_ANCHOR = "PistolLocator";

    public static final String PLACEHOLDER_RIFLE_BONE = "Gun3";

    /**
     * The rig's own Molang variables, fed from the live entity in {@link #supplyMolangVariables}.
     *
     * <p>The values the rig was authored against: {@code ysm.head_yaw} is the head's yaw relative to
     * the body in degrees, which is exactly the quantity GeckoLib hands a model as
     * {@code EntityModelData#netHeadYaw} (already negated), and {@code ysm.head_pitch} is
     * {@code EntityModelData#headPitch}. The {@code query.*} pair are the same two quantities under the
     * names the {@code tac:idle} clip asks for, and {@code query.is_sneaking} is the one crouch flag the
     * rig tests.</p>
     */
    public static final String MOLANG_HEAD_YAW = "ysm.head_yaw";

    public static final String MOLANG_HEAD_PITCH = "ysm.head_pitch";

    public static final String MOLANG_HEAD_Y_ROTATION = "query.head_y_rotation";

    public static final String MOLANG_HEAD_X_ROTATION = "query.head_x_rotation";

    public static final String MOLANG_IS_SNEAKING = "query.is_sneaking";

    /**
     * Bumped whenever the client config is re-read in game ({@code /tarkovscav client reload}), so
     * every model re-runs its once-per-bake pass (hide, head pitch, anchor) on the next frame without
     * needing a resource reload. A rebake is still detected by the {@code BakedGeoModel} identity.
     */
    private static int configGeneration;

    /** Bones already reported by the hide pass, so the log stays readable. */
    private static final java.util.Set<String> LOGGED_HIDE = new java.util.HashSet<>();

    /**
     * Configured bone names already reported as "not in this rig", keyed on {@code rig|bone}.
     *
     * <p>The hide pass runs once per baked model, and a bake happens again after a resource reload - while
     * an existing {@code .toml} (which Forge deliberately never rewrites) can easily name a dozen bones the
     * current rig no longer has. Reporting each of those on every pass is how a log turns into a wall of
     * text, so the individual message is emitted once per (rig, bone) and the full list is summarised once
     * per rig. Nothing is silent: the names are still all in the summary line.</p>
     */
    private static final java.util.Set<String> LOGGED_MISSING_BONES = new java.util.HashSet<>();

    /** Rigs whose "these configured bones are not in this rig" summary has already been printed. */
    private static final java.util.Set<String> SUMMARIZED_RIGS = new java.util.HashSet<>();

    /** Head-pitch configurations already reported. */
    private static final java.util.Set<String> LOGGED_PITCH = new java.util.HashSet<>();

    /** "the clip owns this bone, so the code yielded" reports already made. */
    private static final java.util.Set<String> LOGGED_POSE_YIELD = new java.util.HashSet<>();

    private RigSupport() {
    }

    /** The config generation the models compare against; see {@link #invalidateConfig()}. */
    public static int configGeneration() {
        return configGeneration;
    }

    /** Re-arm the once-per-bake work and the log guards after the config changed in game. */
    public static void invalidateConfig() {
        configGeneration++;
        LOGGED_HIDE.clear();
        LOGGED_PITCH.clear();
        LOGGED_POSE_YIELD.clear();
        LOGGED_MISSING_BONES.clear();
        SUMMARIZED_RIGS.clear();
        PoseWriters.forget();
        GunInHandGeoLayer.forgetLogGuards();
    }

    /**
     * Hides {@code client.hiddenBones} plus the configured head accessories: the author's reference
     * props in the right hand, the placeholder rifle on the gun anchor, and the hats / eye gear /
     * cigarette of the {@code headAccessories} section.
     *
     * <p>Hiding a bone hides its children as well, so listing the prop roots is enough. Anything in
     * {@code Config.PROTECTED_BONES} is refused with a warning for the {@code hiddenBones} list - that
     * is the guard against the Girls' Frontline mistake, where hiding a layered clothing bone left
     * holes in the outfit. The accessory switches deliberately bypass that guard (see
     * {@code Config#accessoryHiddenBones}): the six bones they name are measured accessories, not
     * body layers.</p>
     *
     * <p><b>Call this exactly once per baked model</b>, not once per entity: the flags live on the
     * shared {@link GeoBone} objects, so one call covers every mob using the rig, and a rebake throws
     * the flags away (see the callers). The log line follows the same rule - once per bake - so a
     * rebake that re-hides the props is visible in the log instead of silent.</p>
     */
    public static void hideReferenceProps(GeoModel<?> model, Entity entity) {
        // Config reload can remove a hidden bone or enable an accessory. Undo our previous pass
        // first, retaining authored hidden flags and flags on bones we have never touched.
        RigVisibility.restore(model.getAnimationProcessor().getRegisteredBones());
        List<String> hidden = Config.hiddenBones();
        StringBuilder applied = new StringBuilder();
        StringBuilder reasons = new StringBuilder();
        int matched = 0;
        int missing = 0;
        // The names a configured list asked for that this rig does not have. They are collected instead of
        // counted so the summary at the end can name them (a count alone is not actionable).
        List<String> missingNames = new java.util.ArrayList<>();

        for (String boneName : hidden) {
            Optional<GeoBone> bone = model.getBone(boneName);
            if (bone.isPresent()) {
                RigVisibility.hide(bone.get());
                matched++;
                applied.append(boneName).append(' ');
                reasons.append("\n    ").append(boneName).append(" <- hiddenBones (reference prop / anchor mesh)");
            } else {
                missing++;
                missingNames.add(boneName);
            }
        }

        // ---- the head accessories ---------------------------------------------------------
        List<Config.HiddenBone> accessories = Config.accessoryHiddenBones();
        int accessoryMatched = 0;
        for (Config.HiddenBone entry : accessories) {
            Optional<GeoBone> bone = model.getBone(entry.name());
            if (bone.isPresent()) {
                if (bone.get().isHidden()) {
                    continue;
                }
                RigVisibility.hide(bone.get());
                accessoryMatched++;
                applied.append(entry.name()).append(' ');
                reasons.append("\n    ").append(entry.name()).append(" <- ").append(entry.reason());
            }
        }

        // A configured accessory bone that this rig does not have is never silent - but it is reported ONCE
        // per (rig, bone) and summarised once per rig, because this whole pass runs again on every rebake
        // and an old .toml can name a dozen bones the current rig dropped. The 2026 rig re-export deleted
        // the placeholder rifle and the author's props, so "configured but absent" is the normal state of an
        // upgraded install rather than a typo.
        List<String> absent = new java.util.ArrayList<>(missingNames);
        for (String configured : Config.accessoryConfiguredBones()) {
            if (model.getBone(configured).isEmpty() && !absent.contains(configured)) {
                absent.add(configured);
            }
        }
        List<String> fresh = new java.util.ArrayList<>();
        for (String name : absent) {
            if (LOGGED_MISSING_BONES.add(entity.getType().toShortString() + "|"
                    + name.toLowerCase(java.util.Locale.ROOT))) {
                fresh.add(name);
            }
        }
        for (String name : fresh) {
            TarkovScav.LOGGER.warn("[model] {}: the configured bone '{}' does not exist in this rig, so"
                            + " nothing was hidden or kept for it (a bone that is not in the geometry cannot be"
                            + " touched)",
                    entity.getType().toShortString(), name);
        }
        if (!absent.isEmpty() && SUMMARIZED_RIGS.add(entity.getType().toShortString())) {
            TarkovScav.LOGGER.info("[model] {}: {} configured bone(s) not in this rig ({}) - the rig no"
                            + " longer has them; the config entries are harmless and each name is reported"
                            + " only once",
                    entity.getType().toShortString(), absent.size(), String.join(", ", absent));
        }
        List<String> accessoryMissing = absent;

        if (Config.LOG_HIDDEN_BONES.get() && LOGGED_HIDE.add(entity.getType().toShortString()
                + "|" + matched + "|" + accessoryMatched + "|" + missing + "|" + accessoryMissing.size())) {
            TarkovScav.LOGGER.info("[model] {}: hid {} bone(s) [{}] - {} from client.hiddenBones, {} from"
                            + " the head-accessory switches; {} hiddenBones entr(ies) and {} accessory"
                            + " name(s) matched no bone{}",
                    entity.getType().toShortString(), matched + accessoryMatched, applied.toString().trim(),
                    matched, accessoryMatched, missing, accessoryMissing.size(), reasons);
            TarkovScav.LOGGER.info("[model] {}: the held TaCZ gun is mounted on '{}' - this rig {} the"
                            + " placeholder rifle mesh ('{}'), so there is nothing to draw next to the real"
                            + " gun; GeckoLib skips a hidden bone's cubes and still calls the render layers,"
                            + " so the anchor stays usable either way",
                    entity.getType().toShortString(),
                    Config.SPEC.isLoaded() ? Config.GUN_ANCHOR_BONE.get() : DEFAULT_GUN_ANCHOR,
                    model.getBone(PLACEHOLDER_RIFLE_BONE).isPresent() ? "still has" : "no longer has",
                    PLACEHOLDER_RIFLE_BONE);
        }
    }

    /**
     * Twists the torso and head towards whatever the mob is aiming at. The head takes whatever the
     * torso did not, so the total rotation lands on the target instead of overshooting it.
     *
     * <h2>Both bones are written absolutely, and that is the whole point</h2>
     * <p>GeckoLib only pulls a bone back to its rest rotation while that bone still reports
     * {@code hasRotationChanged() == false} ({@code AnimationProcessor#tickAnimation},
     * {@code resetBoneTransformationMarkers}), and {@code setRotY}/{@code setRotX} set that flag
     * ({@code GeoBone.java}). So a write that *adds to whatever the bone currently holds* marks the
     * bone as changed, is therefore never reset, and adds to its own previous value again on the next
     * rendered frame. None of the clips that play while the mob is armed - {@code tac:idle/walk/run},
     * {@code tac:hold:*}, {@code tac:aim:*}, {@code tac:aim:fire:*}, {@code tac:reload:*} - animates
     * {@code UpperBody}: they animate its parent {@code UpBody}, and only the unarmed
     * {@code idle/walk/run/death} clips (plus the unused {@code tac:melee:*} ones) touch
     * {@code UpperBody} at all - {@code tools/list_animated_bones.js} prints that per clip. So nothing
     * else wrote that bone while aiming and a "+=" there accumulated without bound: at 60 fps a
     * 10 degree head/body yaw offset already turns into ~7 degrees of extra torso rotation *per frame*,
     * i.e. more than a full turn per second. The torso, and with it the arms, the head and the gun in
     * the hand (all descendants of {@code UpperBody}), spun around the shoulder pivot at a rate that
     * depended only on how long the mob had been aiming - which is the "upper body looks wrong,
     * sometimes it shows and sometimes it does not" report, while the legs (under {@code DownBody})
     * stayed perfectly normal.
     *
     * <p>The pose below is therefore the bone's own rest rotation plus the aim share: idempotent, so
     * the rendered frame count cannot change it. The torso write stays behind the {@code aiming} guard
     * so the clips that *do* animate {@code UpperBody} (unarmed movement, death) keep their own
     * keyframes; for the armed clips, which leave the bone alone, GeckoLib's reset brings it back to
     * rest once the mob stops aiming - and it now only has to travel the small aim share, instead of an
     * angle that had been growing for as long as the mob had been aiming.</p>
     *
     * <h2>One writer per bone, and the gain stays 1.0 ({@code client.poseSource})</h2>
     * <p>Now that the rig's own aim variables are fed again ({@link #supplyMolangVariables}) the author's
     * keyframes on {@code UpBody}, {@code AllBody}, {@code Head} and {@code Arm} move, and two writers on
     * one bone would add their angles. So a bone is written here only when it is <em>not</em> owned by a
     * clip: under {@link PoseSource#AUTO} a bone is the clip's when the playing clip drives it from the
     * entity's look ({@link ClipPose#lookDriven} - its track is Molang, a fixed authored pose is not),
     * under {@link PoseSource#CODE} the code keeps both bones, and under {@link PoseSource#CLIPS} the code
     * writes neither. {@link PoseWriters#note} records the decision per frame so the invariant is
     * visible in the log.</p>
     *
     * <p>When the head becomes the clip's, the share the head would have taken is folded into the torso
     * (and the other way round), so the code's <b>total</b> yaw gain over the bones it still owns stays
     * exactly 1.0 instead of dropping to the 0.7 torso share. Without that fold the total would be
     * 0.7 x netHeadYaw the moment the author's head track came alive - the aim would lag by 30 % and
     * look like a new bug. {@code tools/selftest_pose_writers.js} measures the gain before and after.</p>
     */
    public static void applyAimTracking(GeoModel<?> model, Entity entity, long instanceId, float netHeadYaw,
                                        float headPitch, boolean aiming, GunAiState state) {
        // The torso share is a stylistic lean, and it is the size of the reported twist: netHeadYaw is by
        // definition the head's yaw RELATIVE TO THE BODY, so the head is where the whole of it belongs
        // and a share on the torso over-rotates the chest by exactly that fraction. The head takes the
        // rest, so the total gain is 1.0 whatever this is set to - see tools/selftest_pose_writers.js.
        float torsoShare = aiming ? Config.torsoYawShare() : 0.0F;
        float pitchShare = aiming ? 0.4F : 0.0F;

        PoseSource source = Config.poseSource();
        ClipPose clip = clipPoseOf(entity, instanceId);
        CoreGeoBone upperBody = model.getAnimationProcessor().getBone("UpperBody");
        CoreGeoBone head = model.getAnimationProcessor().getBone("Head");

        boolean torsoOwns = upperBody != null && aiming && !clipOwns(clip, "UpperBody", source);
        boolean headOwns = head != null && !clipOwns(clip, "Head", source);

        // ---- the share fold ------------------------------------------------------------------
        // The head's share of the yaw (and pitch) moves to the torso when the head is the clip's, so the
        // code's total gain stays 1.0. When the torso is not written at all there is nothing to fold
        // into, and the head takes the whole share.
        float headYawShare = 1.0F - torsoShare;
        float headPitchShare = 1.0F - pitchShare;
        if (torsoOwns && !headOwns) {
            torsoShare = 1.0F;
            pitchShare = 1.0F;
        } else if (!torsoOwns && headOwns) {
            headYawShare = 1.0F;
            headPitchShare = 1.0F;
        }

        Map<String, String> writers = PoseWriters.frame(clip);
        reportYield(entity, clip, source, aiming, torsoOwns, headOwns);

        if (torsoOwns) {
            PoseWriters.note(writers, "UpperBody", PoseWriters.CODE);
            BoneSnapshot rest = upperBody.getInitialSnapshot();
            upperBody.setRotY(rest.getRotY() + netHeadYaw * torsoShare * Mth.DEG_TO_RAD);
            upperBody.setRotX(rest.getRotX() + headPitch * pitchShare * Mth.DEG_TO_RAD);
        }

        // ---- the head rest pitch --------------------------------------------------------------
        // Applied ONLY to the states the config names (default: idle) and never while aiming, so the
        // aiming path below is the same expression it has always been - the user's observation was
        // that the head is already correct as soon as the mob is alerted or shot at, and only the
        // idle head sits low. See the class javadoc of ArmPose for why the state comes from the
        // synced AI state rather than from a second guess.
        float restPitch = 0.0F;
        if (!aiming && Config.headRestPitchAppliesTo(state)) {
            restPitch = Config.headRestPitchDegrees();
        }

        if (headOwns) {
            PoseWriters.note(writers, "Head", PoseWriters.CODE);
            head.setRotY(netHeadYaw * headYawShare * Mth.DEG_TO_RAD);
            if (restPitch != 0.0F) {
                // Absolute, rest + share + correction: idempotent, so the rendered frame count cannot
                // change the pose (an additive write here would grow without bound - see the class
                // javadoc and tools/selftest_rig_pose.js).
                head.setRotX((headPitch * headPitchShare + restPitch) * Mth.DEG_TO_RAD);
            } else {
                head.setRotX(headPitch * headPitchShare * Mth.DEG_TO_RAD);
            }
        }

        PoseWriters.flush(entity, source, clip, netHeadYaw, headPitch, model);

        if (restPitch != 0.0F && Config.LOG_HIDDEN_BONES.get()) {
            String key = "headpitch|" + restPitch + "|" + state + "|" + headOwns;
            if (LOGGED_PITCH.add(key)) {
                if (headOwns) {
                    TarkovScav.LOGGER.info("[model] head rest pitch: +{} degree(s) added on bone 'Head'"
                                    + " while the gun AI state is {} (client.headRestPitchDegrees, states"
                                    + " {}) - the aiming path is driven by the look direction and is"
                                    + " untouched",
                            restPitch, state, Config.HEAD_REST_PITCH_STATES.get());
                } else {
                    TarkovScav.LOGGER.info("[model] head rest pitch: the +{} degree(s) for state {} was NOT"
                                    + " added - the clip '{}' drives bone 'Head' from the entity's look and"
                                    + " client.poseSource = {} lets the clip own it. Set client.poseSource"
                                    + " = code to get the correction back and lose the author's head"
                                    + " keyframes.",
                            restPitch, state, clip.clips(), source.name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
    }

    /** True when the clips own this bone for the aim path, so the code must leave it alone. */
    private static boolean clipOwns(ClipPose clip, String bone, PoseSource source) {
        return switch (source) {
            case CODE -> false;
            case CLIPS -> true;
            // AUTO: the clip owns the bone only where the author actually drives it from the look. A
            // fixed authored track cannot follow anything, and yielding to it would delete the aim
            // tracking that already works (e.g. the unarmed idle/walk/run clips - their head tracks are
            // plain numbers). And a yaw track can only follow the look while the YAW symbols are fed,
            // so with client.molangVariables = pitch (the default) or off nothing is look-driven and
            // auto behaves exactly like code - which is what makes the two keys independent switches
            // instead of one coupled pair.
            case AUTO -> Config.feedsMolangYaw() && clip.lookDriven(bone);
        };
    }

    /** The playing clips of an entity, or {@link ClipPose#EMPTY} when it is not a GeckoLib animatable. */
    private static ClipPose clipPoseOf(Entity entity, long instanceId) {
        if (entity instanceof software.bernie.geckolib.core.animatable.GeoAnimatable geoAnimatable) {
            return ClipPose.of(geoAnimatable, instanceId);
        }
        return ClipPose.EMPTY;
    }

    /** Says once per (bone, source) which side took a bone, so a vanished pose is never silent. */
    private static void reportYield(Entity entity, ClipPose clip, PoseSource source, boolean aiming,
                                    boolean torsoOwns, boolean headOwns) {
        if (!Config.LOG_HIDDEN_BONES.get()) {
            return;
        }
        for (String bone : List.of("UpperBody", "Head")) {
            boolean owned = bone.equals("UpperBody") ? torsoOwns : headOwns;
            boolean look = Config.feedsMolangYaw() && clip.lookDriven(bone);
            String key = entity.getType().toShortString() + "|" + bone + "|" + source + "|"
                    + look + "|" + clip.clips();
            if (LOGGED_POSE_YIELD.add(key)) {
                TarkovScav.LOGGER.info("[pose] {} bone '{}': writer={} (client.poseSource={}, aiming={},"
                                + " clips={}, clipDrivesLook={})",
                        entity.getType().toShortString(), bone,
                        owned ? PoseWriters.CODE : PoseWriters.CLIPS_MOLANG,
                        source.name().toLowerCase(java.util.Locale.ROOT), aiming, clip.clips(), look);
            }
        }
    }

    /** The synced gun-AI state of a mob, or {@link GunAiState#IDLE} when it has no gun brain yet. */
    public static GunAiState gunAiState(Entity entity) {
        return entity instanceof GunUser user ? user.gunAiState() : GunAiState.IDLE;
    }

    /** The arm/weapon pose for a mob, taken from the synced AI state. */
    public static ArmPose armPose(Entity entity) {
        return ArmPose.forState(gunAiState(entity));
    }

    /**
     * Arm angles for the placeholder Bedrock rig, in degrees: {@code {rightX, rightY, rightZ,
     * leftX, leftY, leftZ}} added to the bone's own rest rotation.
     *
     * <p>Sign convention (the same one the head rest pitch uses): the rig's front is -Z and
     * GeckoLib applies the bone's X rotation about +X, so a positive X rotation swings a limb that
     * hangs down (-Y) towards the front. The raised pose is therefore "arm forward and slightly
     * across the body", and the left arm crosses further to support the handguard.</p>
     *
     * <p>These numbers only exist because the shipped placeholder rig has no usable gun clips at
     * all: {@code gunner_pillager.animation.json} contains {@code idle}, {@code walk}, {@code aiming}
     * and {@code firing}, while the entity plays {@code GunClips}' {@code tac:hold:*} /
     * {@code tac:aim:*} names - none of which exist in that file. A real rig dropped in by the user
     * should bring those clips; the code pose below is then only the fallback for rigs that do not
     * have them, and it is applied <em>after</em> the controllers, so it wins for the two arm bones
     * while the clips keep driving the rest.</p>
     */
    private static final float[] GEO_ARM_RAISED = {75.0F, 0.0F, -10.0F, 65.0F, 25.0F, 20.0F};
    private static final float[] GEO_ARM_RELOADING = {35.0F, 0.0F, -20.0F, 55.0F, 35.0F, 15.0F};
    private static final float[] GEO_ARM_HUNKERED = {20.0F, 0.0F, -25.0F, 20.0F, 0.0F, 25.0F};
    /** Lowered forward carry keeps a long barrel above ground on rigs without authored gun clips. */
    private static final float[] GEO_ARM_LOW_READY = {65.0F, 0.0F, -10.0F, 65.0F, 25.0F, 20.0F};

    /**
     * Poses the arms of a Bedrock rig from {@link #armPose}: the code-side equivalent of an aiming
     * clip, for rigs that do not ship one.
     *
     * <p>Every write is absolute (rest snapshot + angle), which is what makes it idempotent: an
     * additive write here would mark the bone changed, GeckoLib's reset pass would be skipped, and the
     * angle would grow on every rendered frame (see {@link #applyAimTracking}). Lowered long guns
     * use a forward carry; pistols and ordinary items keep their existing idle animation.</p>
     *
     * <p>Under {@code client.poseSource = auto} an arm bone a playing clip animates is the clip's and
     * is left alone - this pose is a fallback for a rig with no gun clips, not a second opinion on a rig
     * that has them. The placeholder {@code gunner_pillager} rig ships no {@code tac:*} clip at all, so
     * its arms are still written here; a real rig that brings them keeps its own arms.</p>
     */
    public static void applyArmPose(GeoModel<?> model, Entity entity, long instanceId) {
        boolean longGun = entity instanceof LivingEntity living && entity instanceof GunUser user
                && !living.getMainHandItem().isEmpty() && IGun.getIGunOrNull(living.getMainHandItem()) != null
                && !user.usesPistolClips();
        float[] angles = armAngles(armPose(entity), longGun);
        if (angles == null) {
            return;
        }
        CoreGeoBone right = model.getAnimationProcessor().getBone("RightArm");
        CoreGeoBone left = model.getAnimationProcessor().getBone("LeftArm");
        if (right == null && left == null) {
            // A rig with no arm bones at all (the vanilla illager model path) - nothing to do.
            return;
        }
        PoseSource source = Config.poseSource();
        ClipPose clip = clipPoseOf(entity, instanceId);
        Map<String, String> writers = PoseWriters.frame(clip);
        // A headless fallback for a rig that has no clips: any track on the bone wins, Molang or not.
        if (armOwnsByCode(clip, "RightArm", source)) {
            PoseWriters.note(writers, "RightArm", PoseWriters.CODE);
            applyArm(right, angles[0], angles[1], angles[2]);
        }
        if (armOwnsByCode(clip, "LeftArm", source)) {
            PoseWriters.note(writers, "LeftArm", PoseWriters.CODE);
            applyArm(left, angles[3], angles[4], angles[5]);
        }
    }

    private static float[] armAngles(ArmPose pose, boolean longGun) {
        return switch (pose) {
            case LOWERED -> longGun ? GEO_ARM_LOW_READY : null;
            case RAISED -> GEO_ARM_RAISED;
            case RELOADING -> longGun ? GEO_ARM_LOW_READY : GEO_ARM_RELOADING;
            case HUNKERED -> longGun ? GEO_ARM_LOW_READY : GEO_ARM_HUNKERED;
        };
    }

    /** Arm ownership: {@code clips} never writes, {@code code} always, {@code auto} only with no track. */
    private static boolean armOwnsByCode(ClipPose clip, String bone, PoseSource source) {
        return switch (source) {
            case CODE -> true;
            case CLIPS -> false;
            case AUTO -> !clip.anyTrack(bone);
        };
    }

    /**
     * Feeds the rig's own aim variables from the live entity for one frame.
     *
     * <p>Called from {@code handleAnimations}, before GeckoLib ticks the controllers, which is where
     * GeckoLib itself sets its own {@code query.*} values - so the keyframes evaluate against this
     * frame's entity and nothing else. GeckoLib evaluates the keyframes inside the same call, so the
     * variables never have to survive across mobs.</p>
     *
     * <p>With {@code client.molangVariables = false} every symbol is set to a constant 0 instead, which
     * is exactly the state the mod shipped in before the variables were fed: the author's keyframes
     * collapse and this mod's code owns the aim. That is what makes the A/B a config change rather than
     * a rebuild.</p>
     */
    public static void supplyMolangVariables(Entity entity, float netHeadYaw, float headPitch) {
        if (!MolangAccess.ready()) {
            return;
        }
        Config.MolangFeed feed = Config.molangFeed();
        boolean yaw = feed == Config.MolangFeed.ALL;
        boolean pitch = feed != Config.MolangFeed.OFF;
        float feedYaw = yaw ? netHeadYaw : 0.0F;
        float feedPitch = pitch ? headPitch : 0.0F;
        double sneaking = pitch && (entity.getPose() == Pose.CROUCHING || entity.isShiftKeyDown()) ? 1.0D : 0.0D;
        MolangAccess.setValue(MOLANG_HEAD_YAW, () -> feedYaw);
        MolangAccess.setValue(MOLANG_HEAD_Y_ROTATION, () -> feedYaw);
        MolangAccess.setValue(MOLANG_HEAD_PITCH, () -> feedPitch);
        MolangAccess.setValue(MOLANG_HEAD_X_ROTATION, () -> feedPitch);
        MolangAccess.setValue(MOLANG_IS_SNEAKING, () -> sneaking);
    }

    private static void applyArm(CoreGeoBone bone, float x, float y, float z) {
        if (bone == null) {
            return;
        }
        BoneSnapshot rest = bone.getInitialSnapshot();
        bone.setRotX(rest.getRotX() + x * Mth.DEG_TO_RAD);
        bone.setRotY(rest.getRotY() + y * Mth.DEG_TO_RAD);
        bone.setRotZ(rest.getRotZ() + z * Mth.DEG_TO_RAD);
    }

    /** True when this entity is currently holding its weapon up (client-side synced flag). */
    public static boolean isAiming(Entity entity) {
        return entity instanceof GunUser user && user.isGunAiming();
    }

    /**
     * The clip names this mod expects the rig's animation file to provide. The asset self-test checks
     * every one of them against the imported file, so a missing clip is caught before it shows up as
     * a mob frozen in its bind pose.
     */
    public static String[] expectedClips() {
        return new String[]{
                GunClips.IDLE, GunClips.WALK, GunClips.RUN, GunClips.DEATH,
                GunClips.ARMED_IDLE, GunClips.ARMED_WALK, GunClips.ARMED_RUN,
                GunClips.gun(GunClips.FAMILY_RIFLE, "hold"),
                GunClips.gun(GunClips.FAMILY_RIFLE, "aim"),
                GunClips.gun(GunClips.FAMILY_RIFLE, "aim:fire"),
                GunClips.gun(GunClips.FAMILY_RIFLE, "reload"),
                GunClips.gun(GunClips.FAMILY_RIFLE, "melee"),
                GunClips.gun(GunClips.FAMILY_PISTOL, "hold"),
                GunClips.gun(GunClips.FAMILY_PISTOL, "aim"),
                GunClips.gun(GunClips.FAMILY_PISTOL, "aim:fire"),
                GunClips.gun(GunClips.FAMILY_PISTOL, "reload"),
                GunClips.gun(GunClips.FAMILY_PISTOL, "melee"),
        };
    }
}
