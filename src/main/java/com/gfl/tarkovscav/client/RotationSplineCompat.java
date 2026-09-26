package com.gfl.tarkovscav.client;

import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.KeyframeStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * GeckoLib 4.8.4 converts rotation endpoints to radians, but Catmull-Rom reads its neighbouring
 * Molang values directly in degrees. Keep the authored spline and convert only those two controls.
 * All records/lists we change are copies; the shared resource bake and other models stay untouched.
 * mclib is a nested GeckoLib dependency, so its IValue boundary uses reflection like MolangAccess.
 */
public final class RotationSplineCompat {
    private RotationSplineCompat() {}

    private static final class Access {
        static final Class<?> VALUE;
        static final Class<?> CONSTANT;
        static final Constructor<?> KEYFRAME;
        static final Constructor<BoneAnimation> BONE;
        static final Method POSITION, SCALE;
        static final Method GET, LENGTH, START, END, EASING, ARGS;
        static {
            try {
                VALUE = Class.forName("com.eliotlash.mclib.math.IValue");
                CONSTANT = Class.forName("com.eliotlash.mclib.math.Constant");
                Class<?> frame = Class.forName("software.bernie.geckolib.core.keyframe.Keyframe");
                KEYFRAME = frame.getConstructor(double.class, VALUE, VALUE, EasingType.class, List.class);
                BONE = BoneAnimation.class.getConstructor(String.class, KeyframeStack.class, KeyframeStack.class, KeyframeStack.class);
                POSITION = BoneAnimation.class.getMethod("positionKeyFrames");
                SCALE = BoneAnimation.class.getMethod("scaleKeyFrames");
                GET = VALUE.getMethod("get");
                LENGTH = frame.getMethod("length");
                START = frame.getMethod("startValue");
                END = frame.getMethod("endValue");
                EASING = frame.getMethod("easingType");
                ARGS = frame.getMethod("easingArgs");
            } catch (ReflectiveOperationException failed) {
                throw new ExceptionInInitializerError(failed);
            }
        }
    }

    /** Dynamic: a cached animation must still evaluate each entity's current Molang inputs. */
    private record RadiansControl(Object source, double sign) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "get" -> sign * Math.toRadians(((Number) Access.GET.invoke(source)).doubleValue());
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "rotationRadians(" + source + ")";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }

    private static Object radiansControl(Object value, double sign) {
        // Match AnimationController's exact Constant test, including constant-valued expressions.
        if (Access.CONSTANT.isInstance(value)
                || (Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof RadiansControl)) return value;
        return Proxy.newProxyInstance(Access.VALUE.getClassLoader(), new Class<?>[]{Access.VALUE},
                new RadiansControl(value, sign));
    }

    private static List<?> axis(List<?> frames, double sign) throws ReflectiveOperationException {
        List<Object> result = null;
        for (int i = 0; i < frames.size(); i++) {
            Object frame = frames.get(i);
            if (Access.EASING.invoke(frame) != EasingType.CATMULLROM) continue;
            List<?> args = (List<?>) Access.ARGS.invoke(frame);
            if (args.size() != 2) continue;
            Object first = radiansControl(args.get(0), sign);
            Object last = radiansControl(args.get(1), sign);
            if (first == args.get(0) && last == args.get(1)) continue;
            if (result == null) result = new ArrayList<>(frames);
            result.set(i, Access.KEYFRAME.newInstance(Access.LENGTH.invoke(frame), Access.START.invoke(frame),
                    Access.END.invoke(frame), EasingType.CATMULLROM, List.of(first, last)));
        }
        return result == null ? frames : List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Animation correct(Animation original) throws ReflectiveOperationException {
        if (original == null) return null;
        BoneAnimation[] result = null;
        BoneAnimation[] bones = original.boneAnimations();
        for (int i = 0; i < bones.length; i++) {
            BoneAnimation bone = bones[i];
            KeyframeStack<?> rotation = bone.rotationKeyFrames();
            List<?> x = axis(rotation.xKeyframes(), -1);
            List<?> y = axis(rotation.yKeyframes(), -1);
            List<?> z = axis(rotation.zKeyframes(), 1);
            if (x == rotation.xKeyframes() && y == rotation.yKeyframes() && z == rotation.zKeyframes()) continue;
            if (result == null) result = bones.clone();
            result[i] = Access.BONE.newInstance(bone.boneName(), new KeyframeStack((List) x, (List) y, (List) z),
                    Access.POSITION.invoke(bone), Access.SCALE.invoke(bone));
        }
        return result == null ? original : new Animation(original.name(), original.length(), original.loopType(),
                result, original.keyFrames());
    }
}
