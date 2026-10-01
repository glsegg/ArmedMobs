package com.gfl.tarkovscav.client;

import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.event.data.CustomInstructionKeyframeData;
import software.bernie.geckolib.core.keyframe.event.data.ParticleKeyframeData;
import software.bernie.geckolib.core.keyframe.event.data.SoundKeyframeData;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.util.Arrays;
import java.util.Set;

/** Bridges the optional legacy pillager rig to the armed controllers without replacing its gun pose. */
final class PillagerAnimationFallback {
    private static final Set<String> LEG_BONES = Set.of("Root", "LeftLeg", "RightLeg", "DownBody",
            "LeftLowerLeg", "RightLowerLeg", "leftfoot", "rightfoot");
    private static final Animation.Keyframes NO_EVENTS = new Animation.Keyframes(new SoundKeyframeData[0],
            new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]);

    private PillagerAnimationFallback() {
    }

    static Animation resolve(BakedAnimations animations, String name) {
        // A resource pack can provide proper authored clips at any time; those always take priority.
        Animation original = animations.getAnimation(name);
        if (original != null) return original;

        String movement = switch (name) {
            case "tac:idle" -> "idle";
            case "tac:walk", "tac:run", "run" -> "walk";
            default -> null;
        };
        if (movement != null) {
            Animation source = animations.getAnimation(movement);
            if (source == null) return null;
            BoneAnimation[] bones = name.startsWith("tac:")
                    ? Arrays.stream(source.boneAnimations())
                            .filter(bone -> LEG_BONES.contains(bone.boneName())).toArray(BoneAnimation[]::new)
                    : source.boneAnimations();
            return new Animation(name, source.length(), source.loopType(), bones, source.keyFrames());
        }

        return switch (name) {
            // The legacy "aiming" and "firing" clips rotate the arms and torso. Aliasing those here
            // would take ownership away from the already calibrated RigSupport code poses. An explicit
            // no-track clip lets that fallback keep owning the arms without Gecko retrying a missing clip
            // every frame. It supplies no invented reload/fire motion or sound events.
            case "tac:hold:pistol", "tac:hold:rifle", "tac:aim:pistol", "tac:aim:rifle",
                    "tac:aim:fire:pistol", "tac:aim:fire:rifle", "tac:reload:pistol", "tac:reload:rifle",
                    "tac:melee:pistol", "tac:melee:rifle" ->
                    new Animation(name, 1.0D, Animation.LoopType.LOOP, new BoneAnimation[0], NO_EVENTS);
            default -> null;
        };
    }
}
