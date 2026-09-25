package com.gfl.tarkovscav.client;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What the clips that are playing on a mob actually animate, read out of GeckoLib's own parsed
 * {@link Animation} objects rather than guessed from the JSON.
 *
 * <p>Two questions are answered here, and they are different:</p>
 * <ul>
 *   <li>{@link #anyTrack} - does the bone have any keyframe track at all? That is the question the arm
 *       fallback needs: a bone the clip animates is the clip's, a bone no clip touches is the code's.</li>
 *   <li>{@link #lookDriven} - does the bone's own track <b>react to the entity's look</b>? A track only
 *       reacts when at least one of its keyframe values is a Molang expression rather than a parsed
 *       number; the author's numeric keyframes are fixed poses and cannot follow anything. This is the
 *       question the aim arbiter needs, because a fixed authored pose must not silently delete the aim
 *       tracking that already works (the unarmed {@code idle}/{@code walk}/{@code run} clips).</li>
 * </ul>
 *
 * <p>The keyframe values are reached through {@link MolangAccess} because GeckoLib's Molang types live
 * in a shaded nested jar that is not on this mod's compile classpath - see that class. If any of those
 * lookups fail, the analysis comes back empty, which means "no clip owns anything": the pose falls back
 * to the behaviour this mod shipped with, never to a crash.</p>
 */
public final class ClipPose {
    /** Analysis cache. Weak so a resource reload's discarded {@link Animation} objects can be collected. */
    private static final Map<Animation, ClipPose> CACHE = new WeakHashMap<>();

    /** Bones with at least one non-empty channel in any of the clips that are playing. */
    private final Set<String> tracked;

    /** Bones whose track contains a Molang keyframe, i.e. one that can react to the entity's look. */
    private final Set<String> lookDriven;

    /** The names of the clips this was built from, in controller registration order, for the log. */
    private final List<String> clips;

    private ClipPose(Set<String> tracked, Set<String> lookDriven, List<String> clips) {
        this.tracked = tracked;
        this.lookDriven = lookDriven;
        this.clips = clips;
    }

    /** Nothing is animated: the state a model is in before any clip plays (and the placeholder rigs). */
    public static final ClipPose EMPTY = new ClipPose(Set.of(), Set.of(), List.of());

    /** True when some playing clip has a keyframe track on this bone. */
    public boolean anyTrack(String bone) {
        return this.tracked.contains(bone);
    }

    /** True when some playing clip drives this bone from the entity's look (its track is Molang). */
    public boolean lookDriven(String bone) {
        return this.lookDriven.contains(bone);
    }

    /** The clips this was read from; empty when nothing is playing. */
    public List<String> clips() {
        return this.clips;
    }

    /**
     * Reads the currently playing clips off the mob's own controllers.
     *
     * <p>This is deliberately taken from {@code AnimationController#getCurrentAnimation} and not by
     * recomputing which clip <em>should</em> play: the entity decides that in Java
     * ({@code ScavEntity#singleController} and friends) and a second, independent guess at the same
     * question is exactly how a pose arbiter ends up disagreeing with the animation it is arbitrating.
     * What is playing is whatever GeckoLib is about to tick.</p>
     */
    public static ClipPose of(GeoAnimatable animatable, long instanceId) {
        Set<String> tracked = new LinkedHashSet<>();
        Set<String> lookDriven = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        Object manager = MolangAccess.managerOf(animatable, instanceId);
        if (manager instanceof AnimatableManager<?> animatableManager) {
            for (AnimationController<?> controller : animatableManager.getAnimationControllers().values()) {
                Animation animation = MolangAccess.currentAnimation(controller);
                if (animation == null) {
                    continue;
                }
                names.add(animation.name());
                ClipPose single = analyse(animation);
                tracked.addAll(single.tracked);
                lookDriven.addAll(single.lookDriven);
            }
        }
        if (names.isEmpty()) {
            return EMPTY;
        }
        return new ClipPose(Set.copyOf(tracked), Set.copyOf(lookDriven), List.copyOf(names));
    }

    /** Per-clip analysis, cached because it is read once per mob per frame. */
    public static ClipPose analyse(Animation animation) {
        ClipPose cached = CACHE.get(animation);
        if (cached != null) {
            return cached;
        }
        Set<String> tracked = new LinkedHashSet<>();
        Set<String> lookDriven = new LinkedHashSet<>();
        Object bones = MolangAccess.call(animation, "boneAnimations");
        int length = bones instanceof Object[] array ? array.length : 0;
        for (int index = 0; index < length; index++) {
            Object bone = ((Object[]) bones)[index];
            String name = stringOf(MolangAccess.call(bone, "boneName"));
            if (name == null) {
                continue;
            }
            boolean any = false;
            boolean molang = false;
            for (String channel : CHANNELS) {
                Object stack = MolangAccess.call(bone, channel);
                if (stack == null) {
                    continue;
                }
                for (String axis : AXES) {
                    Object keyframes = MolangAccess.call(stack, axis);
                    if (!(keyframes instanceof List<?> list) || list.isEmpty()) {
                        continue;
                    }
                    any = true;
                    for (Object keyframe : list) {
                        if (MolangAccess.isExpression(MolangAccess.call(keyframe, "startValue"))
                                || MolangAccess.isExpression(MolangAccess.call(keyframe, "endValue"))) {
                            molang = true;
                            break;
                        }
                    }
                }
            }
            if (any) {
                tracked.add(name);
            }
            if (molang) {
                lookDriven.add(name);
            }
        }
        ClipPose pose = new ClipPose(Set.copyOf(tracked), Set.copyOf(lookDriven), List.of(animation.name()));
        CACHE.put(animation, pose);
        return pose;
    }

    /** The three channels a {@code BoneAnimation} carries. */
    private static final List<String> CHANNELS = List.of("rotationKeyFrames", "positionKeyFrames",
            "scaleKeyFrames");

    /** The three axes a {@code KeyframeStack} carries. */
    private static final List<String> AXES = List.of("xKeyframes", "yKeyframes", "zKeyframes");

    private static String stringOf(Object value) {
        return value instanceof String text ? text : null;
    }
}
