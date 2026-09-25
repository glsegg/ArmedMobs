package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.lang.reflect.Method;
import java.util.function.DoubleSupplier;

/**
 * The one place this mod touches GeckoLib's Molang machinery, and the reason it does so reflectively.
 *
 * <h2>Why reflection and not a plain call</h2>
 * <p>GeckoLib's Molang engine is the shaded {@code com.eliotlash.mclib} library, shipped as a
 * <em>nested</em> jar ({@code META-INF/jarjar/mclib-20.jar}) inside {@code geckolib-forge-*.jar}. A
 * nested jar is not on a mod's compile classpath, so {@code MolangParser#setValue} (inherited from
 * {@code com.eliotlash.mclib.math.MathBuilder}) and the whole {@code IValue}/{@code Keyframe} generic
 * hierarchy cannot be named from this mod's source: {@code javac} fails with
 * "cannot access com.eliotlash.mclib.math.MathBuilder / class file for ... not found". GeckoLib itself
 * can call them because it was compiled against mclib.</p>
 *
 * <p>Everything that has to cross that boundary is therefore done here, by name, with the lookups cached
 * once. Two consequences worth stating plainly:</p>
 * <ul>
 *   <li>If any lookup fails (another GeckoLib version, a repackaged build), {@link #ready()} is false and
 *       every caller falls back to the behaviour this mod shipped with: variables pinned to 0, no clip
 *       owns a bone, the code writes the aim. A pose feature must never take the render thread down.</li>
 *   <li>The lookups are done against GeckoLib's own classes, not this mod's, so a failure is reported
 *       once with the class and method that could not be found.</li>
 * </ul>
 */
public final class MolangAccess {
    private MolangAccess() {
    }

    /** {@code MolangParser.INSTANCE}, or null when the class could not be resolved. */
    private static final Object PARSER = lookupParser();

    /** {@code MolangParser#setValue(String, DoubleSupplier)}. */
    private static final Method SET_VALUE = lookupSetValue();

    /** One report per failure, so a diagnostic cannot flood the log. */
    private static boolean reportedFailure;

    /** True when the variables can actually be fed; reported once when false. */
    public static boolean ready() {
        boolean ready = PARSER != null && SET_VALUE != null;
        if (!ready && !reportedFailure) {
            reportedFailure = true;
            TarkovScav.LOGGER.warn("[molang] GeckoLib's MolangParser#setValue could not be reached"
                    + " reflectively (parser={}, method={}), so the rig's own aim variables stay at 0 and"
                    + " the author's Molang keyframes keep collapsing - exactly the behaviour this mod"
                    + " shipped with. client.molangVariables has no effect until that is resolved.",
                    PARSER != null, SET_VALUE != null);
        }
        return ready;
    }

    /**
     * Binds one Molang symbol to a live value supplier, the same way GeckoLib's own
     * {@code GeoModel#applyMolangQueries} binds {@code query.*}. The supplier is re-read every time
     * GeckoLib evaluates an expression, and GeckoLib evaluates the keyframes in the same call that fed
     * them, so this never has to survive across mobs.
     */
    public static void setValue(String name, DoubleSupplier value) {
        if (!ready()) {
            return;
        }
        try {
            SET_VALUE.invoke(PARSER, name, value);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            if (!reportedFailure) {
                reportedFailure = true;
                TarkovScav.LOGGER.warn("[molang] feeding the variable '{}' failed", name, failed);
            }
        }
    }

    /** The clip a controller is playing right now, or null. */
    public static Animation currentAnimation(AnimationController<?> controller) {
        try {
            AnimationProcessor.QueuedAnimation queued = controller.getCurrentAnimation();
            return queued == null ? null : queued.animation();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The mob's animation manager, or null when GeckoLib has not built it yet. */
    public static Object managerOf(GeoAnimatable animatable, long instanceId) {
        try {
            return animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Calls a no-argument accessor by name. Used for the mclib-typed getters
     * ({@code Animation#boneAnimations}, {@code BoneAnimation#rotationKeyFrames},
     * {@code KeyframeStack#xKeyframes}, ...) whose declared types cannot be named here.
     */
    public static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method accessor = target.getClass().getMethod(method);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    /** True when the value is a Molang expression rather than a plain parsed number. */
    public static boolean isExpression(Object value) {
        if (value == null) {
            return true;
        }
        Class<?> type = value.getClass();
        try {
            Object constant = type.getMethod("isConstant").invoke(value);
            return !(constant instanceof Boolean flag) || !flag;
        } catch (NoSuchMethodException notAMolangValue) {
            // mclib types other than Constant (Variable, Operation, Group, Function) are expressions by
            // construction; only Constant is a number, and it is the one the loader wraps bare numbers in.
            return !type.getName().equals("com.eliotlash.mclib.math.Constant");
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return true;
        }
    }

    private static Object lookupParser() {
        try {
            Class<?> parser = Class.forName("software.bernie.geckolib.core.molang.MolangParser");
            return parser.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static Method lookupSetValue() {
        if (PARSER == null) {
            return null;
        }
        try {
            return PARSER.getClass().getMethod("setValue", String.class, DoubleSupplier.class);
        } catch (NoSuchMethodException | RuntimeException unavailable) {
            return null;
        }
    }
}
