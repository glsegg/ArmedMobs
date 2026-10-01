import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.gfl.tarkovscav.client.RotationSplineCompat;
import com.google.gson.JsonParser;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.keyframe.*;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.core.object.Axis;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.util.JsonUtil;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class SplineCompatTest {
    private static int checks;
    private static final AnimationController<GeoAnimatable> SAMPLER = new AnimationController<>(null, "spline", 0, state -> PlayState.CONTINUE);
    private static Method point;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static void feed(double yaw, double pitch) {
        MolangParser.INSTANCE.setValue("ysm.head_yaw", () -> yaw);
        MolangParser.INSTANCE.setValue("ysm.head_pitch", () -> pitch);
        MolangParser.INSTANCE.setValue("query.is_sneaking", () -> 0);
    }
    private static List<Keyframe<IValue>> axis(BoneAnimation bone, Axis axis) {
        return switch (axis) {
            case X -> bone.rotationKeyFrames().xKeyframes();
            case Y -> bone.rotationKeyFrames().yKeyframes();
            case Z -> bone.rotationKeyFrames().zKeyframes();
        };
    }
    private static AnimationPoint point(List<Keyframe<IValue>> frames, double tick, Axis axis) throws Exception {
        return (AnimationPoint) point.invoke(SAMPLER, frames, tick, true, axis);
    }
    private static double control(IValue value, Axis axis) {
        return value instanceof Constant ? value.get() : Math.toRadians(value.get()) * (axis == Axis.Z ? 1 : -1);
    }
    private static double reference(AnimationPoint point, Axis axis) {
        Keyframe<?> frame = point.keyFrame();
        if (frame.easingType() != EasingType.CATMULLROM || frame.easingArgs().size() != 2
                || point.currentTick() >= point.transitionLength()) return EasingType.lerpWithOverride(point, null);
        double t = point.currentTick() / point.transitionLength();
        double p0 = control(frame.easingArgs().get(0), axis), p1 = point.animationStartValue();
        double p2 = point.animationEndValue(), p3 = control(frame.easingArgs().get(1), axis);
        return .5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                + (3 * p1 - p0 - 3 * p2 + p3) * t * t * t);
    }
    private static void structure(Animation raw, Animation fixed) throws Exception {
        check(raw.name().equals(fixed.name()) && raw.length() == fixed.length() && raw.loopType() == fixed.loopType()
                && raw.keyFrames() == fixed.keyFrames(), "Animation metadata/event tracks changed");
        check(RotationSplineCompat.correct(fixed) == fixed, "Corrected controls converted twice");
        for (int b = 0; b < raw.boneAnimations().length; b++) {
            BoneAnimation before = raw.boneAnimations()[b], after = fixed.boneAnimations()[b];
            check(before.boneName().equals(after.boneName()) && before.positionKeyFrames() == after.positionKeyFrames()
                    && before.scaleKeyFrames() == after.scaleKeyFrames(), "Position/scale/bone order changed");
            for (Axis axis : Axis.values()) {
                List<Keyframe<IValue>> from = axis(before, axis), to = axis(after, axis);
                check(from.size() == to.size(), "Keyframe count changed");
                for (int i = 0; i < from.size(); i++) {
                    Keyframe<IValue> f = from.get(i), g = to.get(i);
                    check(f.length() == g.length() && f.startValue() == g.startValue() && f.endValue() == g.endValue()
                            && f.easingType() == g.easingType(), "Endpoint, duration or authored easing changed");
                    for (int j = 0; j < f.easingArgs().size(); j++) {
                        if (f.easingType() != EasingType.CATMULLROM || f.easingArgs().get(j) instanceof Constant)
                            check(f.easingArgs().get(j) == g.easingArgs().get(j), "Static/non-spline control changed");
                        if (f != g && !(f.easingArgs().get(j) instanceof Constant))
                            check(f.easingArgs().get(j) != g.easingArgs().get(j), "Shared resource control mutated instead of copied");
                    }
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        feed(0, 0);
        var json = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject().getAsJsonObject("animations");
        BakedAnimations animations = JsonUtil.GEO_GSON.fromJson(json, BakedAnimations.class);
        point = AnimationController.class.getDeclaredMethod("getAnimationPointAtTick", List.class, double.class, boolean.class, Axis.class);
        point.setAccessible(true);
        for (String name : List.of("tac:hold:pistol", "tac:aim:pistol", "tac:hold:rifle", "tac:aim:rifle")) {
            Animation raw = animations.getAnimation(name), fixed = RotationSplineCompat.correct(raw);
            check(raw == fixed, "Hold/aim bake changed: " + name);
            System.out.println(name + ": identity unchanged, all times/looks unchanged (including 1.75s)");
        }
        for (String name : List.of("tac:reload:pistol", "tac:reload:rifle")) {
            Animation raw = animations.getAnimation(name), fixed = RotationSplineCompat.correct(raw);
            check(raw != fixed, "Regression fixture no longer exercises mixed-unit rotation spline: " + name);
            structure(raw, fixed);
            double worst = 0;
            for (double pitch : new double[]{-45, -30, 0, 30, 45}) for (double yaw : new double[]{-30, 0, 30}) {
                feed(yaw, pitch);
                for (int b = 0; b < raw.boneAnimations().length; b++) for (Axis axis : Axis.values()) {
                    var before = axis(raw.boneAnimations()[b], axis);
                    var after = axis(fixed.boneAnimations()[b], axis);
                    if (before.isEmpty()) continue;
                    for (double tick = 0; tick <= 40; tick += .5) {
                        AnimationPoint original = point(before, tick, axis);
                        double expected = reference(original, axis);
                        double actual = EasingType.lerpWithOverride(point(after, tick, axis), null);
                        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-9,
                                name + "/" + raw.boneAnimations()[b].boneName() + "/" + axis + " @" + tick);
                        worst = Math.max(worst, Math.abs(EasingType.lerpWithOverride(original, null) - actual));
                    }
                }
            }
            System.out.printf(Locale.ROOT, "%s: largest old/new unit error=%.2f degrees; corrected samples match the authored Catmull-Rom curve%n", name, Math.toDegrees(worst));
        }
        // A fresh resource bake needs a distinct corrected animation and cannot reuse old controls.
        BakedAnimations rebaked = JsonUtil.GEO_GSON.fromJson(json, BakedAnimations.class);
        check(RotationSplineCompat.correct(rebaked.getAnimation("tac:reload:rifle"))
                != RotationSplineCompat.correct(animations.getAnimation("tac:reload:rifle")), "Resource rebake reused old animation");
        System.out.println("PASS " + checks + " spline checks: pitch -45/-30/0/30/45, yaw -30/0/30, reload 0..2 seconds every .025 seconds.");
    }
}
