import com.eliotlash.mclib.math.IValue;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.gfl.tarkovscav.client.ArmPose;
import com.gfl.tarkovscav.client.RotationSplineCompat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.Keyframe;
import software.bernie.geckolib.core.keyframe.KeyframeStack;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.util.JsonUtil;
import software.bernie.geckolib.util.RenderUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Samples real GeckoLib clips, including Molang and spline easing, without booting the game.
 * This measures the locator and legacy frame; an item's origin is not necessarily its grip.
 * Production frame methods are compiled verbatim by selftest.ps1. TaCZ positioning has a separate
 * real-code test; controller cross-fades, look tracking and shaders still need the in-game preview.
 * Unknown Molang symbols fail rather than being silently replaced with zero.
 */
public final class MountMatrixTest {
    private static final Map<String, GeoBone> BONES = new LinkedHashMap<>();
    private static final Map<String, float[]> REST = new LinkedHashMap<>();
    private static final AnimationController<GeoAnimatable> SAMPLER =
            new AnimationController<>(null, "mount_audit", 0, state -> PlayState.CONTINUE);
    private static final Set<String> SUPPLIED = Set.of("ysm.head_yaw", "ysm.head_pitch",
            "query.head_y_rotation", "query.head_x_rotation", "query.is_sneaking");
    private static Method animationPoint;
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void collect(GeoBone bone) {
        bone.saveInitialSnapshot(); // AnimationProcessor.registerGeoBone does this before animation.
        BONES.put(bone.getName(), bone);
        REST.put(bone.getName(), new float[]{bone.getRotX(), bone.getRotY(), bone.getRotZ()});
        bone.getChildBones().forEach(MountMatrixTest::collect);
    }

    private static float sample(List<Keyframe<IValue>> frames, double tick, boolean rotation,
                                software.bernie.geckolib.core.object.Axis axis) throws Exception {
        AnimationPoint point = (AnimationPoint) animationPoint.invoke(SAMPLER, frames, tick, rotation, axis);
        float value = (float) EasingType.lerpWithOverride(point, null);
        if (!Float.isFinite(value)) throw new AssertionError("Non-finite sampled animation value");
        return value;
    }

    private static float[] sample(KeyframeStack<Keyframe<IValue>> frames, double tick, boolean rotation)
            throws Exception {
        if (frames.xKeyframes().isEmpty()) return null;
        return new float[]{sample(frames.xKeyframes(), tick, rotation, software.bernie.geckolib.core.object.Axis.X),
                sample(frames.yKeyframes(), tick, rotation, software.bernie.geckolib.core.object.Axis.Y),
                sample(frames.zKeyframes(), tick, rotation, software.bernie.geckolib.core.object.Axis.Z)};
    }

    private static void apply(Animation animation, double tick) throws Exception {
        for (BoneAnimation track : animation.boneAnimations()) {
            GeoBone bone = BONES.get(track.boneName());
            if (bone == null) continue; // GeckoLib's crashIfBoneMissing=false behaviour.
            float[] rest = REST.get(bone.getName());
            float[] rotation = sample(track.rotationKeyFrames(), tick, true);
            float[] position = sample(track.positionKeyFrames(), tick, false);
            float[] scale = sample(track.scaleKeyFrames(), tick, false);
            if (rotation != null) bone.updateRotation(rest[0] + rotation[0], rest[1] + rotation[1], rest[2] + rotation[2]);
            if (position != null) bone.updatePosition(position[0], position[1], position[2]);
            if (scale != null) bone.updateScale(scale[0], scale[1], scale[2]);
        }
    }

    private static void reset() {
        BONES.forEach((name, bone) -> {
            float[] rest = REST.get(name);
            bone.updateRotation(rest[0], rest[1], rest[2]);
            bone.updatePosition(0, 0, 0);
            bone.updateScale(1, 1, 1);
        });
    }

    private static void feed(double yaw, double pitch) {
        MolangParser.INSTANCE.setValue("ysm.head_yaw", () -> yaw);
        MolangParser.INSTANCE.setValue("ysm.head_pitch", () -> pitch);
        MolangParser.INSTANCE.setValue("query.head_y_rotation", () -> yaw);
        MolangParser.INSTANCE.setValue("query.head_x_rotation", () -> pitch);
        MolangParser.INSTANCE.setValue("query.is_sneaking", () -> 0);
    }

    private static PoseStack traversal(GeoBone bone) {
        List<GeoBone> chain = new ArrayList<>();
        for (GeoBone current = bone; current != null; current = current.getParent()) chain.add(0, current);
        PoseStack pose = new PoseStack();
        // The same sequence used by GeoEntityRenderer.renderRecursively, from the shipped utility.
        for (GeoBone current : chain) RenderUtils.prepMatrixForBone(pose, current);
        return pose;
    }

    private static Vector3f origin(Matrix4f matrix) { return matrix.transformPosition(new Vector3f()); }
    private static float[] xyz(Vector3f value) { return new float[]{value.x, value.y, value.z}; }

    private static Vector3f palm(JsonObject geometry) {
        for (var entry : geometry.getAsJsonArray("bones")) {
            JsonObject bone = entry.getAsJsonObject();
            if (!bone.get("name").getAsString().equals("RightHand")) continue;
            JsonObject cube = bone.getAsJsonArray("cubes").get(0).getAsJsonObject();
            Vector3f centre = new Vector3f();
            for (int i = 0; i < 3; i++) centre.setComponent(i,
                    (cube.getAsJsonArray("origin").get(i).getAsFloat()
                            + cube.getAsJsonArray("size").get(i).getAsFloat() / 2) / 16);
            centre.x *= -1; // BakedModelFactory mirrors Bedrock X, including cube positions.
            return traversal(BONES.get("RightHand")).last().pose().transformPosition(centre);
        }
        throw new AssertionError("This audit requires a RightHand cube to identify the palm");
    }

    public static void main(String[] args) throws Exception {
        String geometryText = Files.readString(Path.of(args[0]));
        String animationText = Files.readString(Path.of(args[1]));
        String requestedClip = args.length > 2 ? args[2] : "";
        String anchorName = args.length > 3 ? args[3] : "RightHandLocator";
        var symbols = Pattern.compile("\\b(?:query|ysm|variable|q|v)\\.[A-Za-z0-9_]+").matcher(animationText);
        while (symbols.find()) check(SUPPLIED.contains(symbols.group()), "Unsupported Molang symbol: " + symbols.group());
        feed(0, 0);
        Model raw = JsonUtil.GEO_GSON.fromJson(geometryText, Model.class);
        var baked = BakedModelFactory.DEFAULT_FACTORY.constructGeoModel(GeometryTree.fromModel(raw));
        baked.topLevelBones().forEach(MountMatrixTest::collect);
        JsonObject jsonAnimations = JsonParser.parseString(animationText).getAsJsonObject().getAsJsonObject("animations");
        BakedAnimations animations = JsonUtil.GEO_GSON.fromJson(jsonAnimations, BakedAnimations.class);
        Map<String, Animation> corrected = new LinkedHashMap<>();
        for (var entry : animations.animations().entrySet())
            corrected.put(entry.getKey(), RotationSplineCompat.correct(entry.getValue()));
        animations = new BakedAnimations(corrected);
        JsonObject geometry = JsonParser.parseString(geometryText).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        check(animations.animations().size() == jsonAnimations.size(), "Animation parser dropped a clip");
        animationPoint = AnimationController.class.getDeclaredMethod("getAnimationPointAtTick", List.class,
                double.class, boolean.class, software.bernie.geckolib.core.object.Axis.class);
        animationPoint.setAccessible(true);
        GeoBone anchor = BONES.get(anchorName);
        check(anchor != null, "Missing anchor " + anchorName);
        List<String> clips = requestedClip.isBlank() || requestedClip.equals("__ALL__")
                ? List.of("tac:hold:pistol", "tac:aim:pistol", "tac:reload:pistol",
                          "tac:hold:rifle", "tac:aim:rifle", "tac:reload:rifle") : List.of(requestedClip);
        List<Map<String, Object>> measurements = new ArrayList<>();
        for (String name : clips) {
            Animation animation = animations.getAnimation(name);
            check(animation != null, "Missing clip " + name);
            double length = animation.length() > 0 && animation.length() < 100_000 ? animation.length() : 20;
            for (double[] look : new double[][]{{0, 0}, {30, -20}, {-35, 25}}) {
                feed(look[0], look[1]);
                for (double fraction : new double[]{0, .125, .25, .5, .75, .875, .999}) {
                    double tick = length * fraction;
                    reset();
                    apply(animations.getAnimation("tac:idle"), tick);
                    apply(animation, tick);
                    PoseStack pose = traversal(anchor);
                    RenderUtils.translateToPivotPoint(pose, anchor);
                    Matrix4f locator = new Matrix4f(pose.last().pose());
                    Vector3f palm = palm(geometry);
                    PoseStack production = traversal(anchor);
                    ProductionPalmFrame.applyLocator(production, anchor);
                    check(production.last().pose().equals(locator, .00002F),
                            "Production locator frame applies an extra rotation: " + name);
                    ProductionPalmFrame.applyPalmGunFrame(production);
                    Matrix4f mount = new Matrix4f(production.last().pose());
                    check(origin(mount).distance(origin(locator)) < .00002F,
                            "Production frame shifts the TaCZ grip away from the locator: " + name);
                    // TaCZ's real positioning test proves its thirdperson_hand grip is item origin
                    // and Fxy does not reverse gun -Z. No fictional y=1.5 correction belongs here.
                    Vector3f forward = mount.transformDirection(new Vector3f(0, 0, -1)).normalize();
                    Matrix4f expectedFrame = new Matrix4f(locator).rotateX((float) -Math.PI / 2);
                    check(mount.equals(expectedFrame, .00002F), "Production frame must retain the locator rotation exactly once");
                    if (!name.contains("reload"))
                        check(forward.z < -.65F, "Hold/aim gun points away from the model front: " + name + ", " + forward);
                    // Cancel the extra R added by BlockAndItemGeoLayer; use the actual animation
                    // positions/scales to prove that only one locator rotation remains.
                    RenderUtils.rotateMatrixAroundBone(pose, anchor);
                    pose.mulPose(Axis.XP.rotation(-anchor.getRotX()));
                    pose.mulPose(Axis.YP.rotation(-anchor.getRotY()));
                    pose.mulPose(Axis.ZP.rotation(-anchor.getRotZ()));
                    check(pose.last().pose().equals(locator, .00002F), "Duplicate-rotation cancellation drift");
                    pose.mulPose(Axis.XP.rotationDegrees(-90));
                    pose.mulPose(Axis.YP.rotationDegrees(180));
                    pose.translate(1F / 16, .125F, -.625F);
                    Matrix4f legacyZeroOffset = new Matrix4f(pose.last().pose());
                    pose.translate(0, 0, -.7F);
                    Matrix4f legacyOffset = new Matrix4f(pose.last().pose());
                    Vector3f shift = origin(legacyOffset).sub(origin(legacyZeroOffset));
                    float handDistance = origin(locator).distance(palm);
                    check(locator.isFinite() && legacyOffset.isFinite(), "Non-finite mount matrix: " + name);
                    if (look[0] == 0 && look[1] == 0 && !name.contains("reload")) {
                        check(shift.z > .6F, "Regression fixture: old -0.7 moves toward the BACK (+Z)");
                        check(handDistance < .16F, "Locator should already be at the palm: " + name);
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("clip", name); row.put("seconds", tick / 20);
                    row.put("headYaw", look[0]); row.put("headPitch", look[1]);
                    row.put("locator", xyz(origin(locator))); row.put("palm", xyz(palm));
                    row.put("locatorPalmDistance", handDistance);
                    row.put("legacyMinusPointSevenDelta", xyz(shift));
                    row.put("vanillaWristShiftDistance", origin(legacyZeroOffset).distance(origin(locator)));
                    row.put("locatorMatrixColumnMajor", locator.get(new float[16]));
                    row.put("productionGrip", xyz(origin(mount)));
                    row.put("productionBarrelForward", xyz(forward));
                    row.put("productionMatrixColumnMajor", mount.get(new float[16]));
                    measurements.add(row);
                    if (fraction == 0 && look[0] == 0 && look[1] == 0) {
                        System.out.printf(java.util.Locale.ROOT,
                                "%s: locator-palm=%.4f, old -0.7 shift=(%.4f, %.4f, %.4f), wrist shift=%.4f%n",
                                name, handDistance, shift.x, shift.y, shift.z,
                                origin(legacyZeroOffset).distance(origin(locator)));
                    }
                }
            }
        }
        Files.writeString(Path.of("matrix-samples.json"), new GsonBuilder().setPrettyPrinting().create().toJson(measurements));
        placeholderRig();
        System.out.println("LIMIT: exact GeckoLib clips, current production frame and explicit Molang inputs; controller cross-fades, entity look tracking and shader behaviour require the in-game preview. TaCZ grip is checked separately by tacz_selftest.ps1.");
        System.out.println("PASS " + checks + " assertions across " + measurements.size() + " sampled poses; matrix-samples.json written.");
    }

    private static void placeholderRig() throws Exception {
        Path project = Path.of(System.getProperty("mount.project"));
        String raw = Files.readString(project.resolve("src/main/resources/assets/tarkovscav/geo/gunner_pillager.geo.json"));
        BONES.clear(); REST.clear();
        BakedModelFactory.DEFAULT_FACTORY.constructGeoModel(GeometryTree.fromModel(JsonUtil.GEO_GSON.fromJson(raw, Model.class)))
                .topLevelBones().forEach(MountMatrixTest::collect);
        String rig = Files.readString(project.resolve("src/main/java/com/gfl/tarkovscav/client/RigSupport.java"));
        check(rig.contains("!living.getMainHandItem().isEmpty() && IGun.getIGunOrNull(living.getMainHandItem()) != null")
                && rig.contains("entity instanceof GunUser user") && rig.contains("!user.usesPistolClips()"),
                "Low-ready classification must require an actual non-pistol TaCZ main-hand gun");
        check(Pattern.compile("if \\(armOwnsByCode\\(clip, \\\"RightArm\\\", source\\)\\) \\{[^}]+applyArm\\(right,").matcher(rig).find()
                && Pattern.compile("if \\(armOwnsByCode\\(clip, \\\"LeftArm\\\", source\\)\\) \\{[^}]+applyArm\\(left,").matcher(rig).find(),
                "Both arms must still respect authored-clip ownership before applying fallback angles");
        check(ProductionPalmFrame.armAngles(ArmPose.LOWERED, false) == null,
                "Pistol/ordinary-item idle must keep its original animation");
        check(ProductionPalmFrame.armAngles(ArmPose.RAISED, false)
                        == ProductionPalmFrame.armAngles(ArmPose.RAISED, true), "Raised fallback must not depend on gun family");
        for (boolean longGun : new boolean[]{false, true}) for (ArmPose state : ArmPose.values()) {
            reset();
            float[] values = ProductionPalmFrame.armAngles(state, longGun);
            if (values == null) continue;
            String name = state + (longGun ? " long gun" : " pistol/ordinary fallback");
            check(values.length == 6, "Placeholder arm constant is no longer six angles");
            if (longGun && state != ArmPose.RAISED)
                check(values[0] == 65 && values[3] == 65, "Long-gun lowered/reload/retreat must all select low-ready");
            if (!longGun && state == ArmPose.RELOADING) check(values[0] == 35, "Pistol reload changed");
            if (!longGun && state == ArmPose.HUNKERED) check(values[0] == 20, "Pistol retreat changed");
            GeoBone arm = BONES.get("RightArm");
            ProductionPalmFrame.applyArm(arm, values[0], values[1], values[2]);
            GeoBone anchor = BONES.get("RightHandLocator");
            PoseStack pose = traversal(anchor);
            ProductionPalmFrame.applyLocator(pose, anchor);
            Vector3f location = origin(pose.last().pose());
            ProductionPalmFrame.applyPalmGunFrame(pose);
            Vector3f forward = pose.last().pose().transformDirection(new Vector3f(0, 0, -1)).normalize();
            check(location.distance(origin(pose.last().pose())) < .00002F, "Placeholder grip offset: " + name);
            if (state == ArmPose.RAISED) check(forward.z < -.95F, "Placeholder raised gun should point forward");
            if (longGun && state != ArmPose.RAISED) check(forward.z < -.90F && forward.y > -.43F,
                    "Placeholder long gun should stay forward and at most 25 degrees below horizontal");
            System.out.printf(java.util.Locale.ROOT, "placeholder %s: barrel=(%.4f, %.4f, %.4f), grip displacement=0%n",
                    name, forward.x, forward.y, forward.z);
        }
    }
}
