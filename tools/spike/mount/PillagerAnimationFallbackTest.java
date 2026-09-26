package com.gfl.tarkovscav.client;

import com.google.gson.JsonParser;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Runs the production resolver against GeckoLib's real parsed, unmodified shipped animation files. */
public final class PillagerAnimationFallbackTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static BakedAnimations bake(String path) throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(path)))
                .getAsJsonObject().getAsJsonObject("animations");
        return JsonUtil.GEO_GSON.fromJson(json, BakedAnimations.class);
    }
    public static void main(String[] args) throws Exception {
        BakedAnimations legacy = bake(args[0]);
        BakedAnimations modern = bake(args[1]);
        check(legacy.animations().keySet().equals(Set.of("idle", "walk", "aiming", "firing")),
                "test no longer exercises the shipped four-clip legacy rig");
        for (String name : legacy.animations().keySet()) {
            check(PillagerAnimationFallback.resolve(legacy, name) == legacy.getAnimation(name),
                    "existing authored clip changed: " + name);
        }
        for (String name : List.of("tac:idle", "tac:walk", "tac:run")) {
            Animation fallback = PillagerAnimationFallback.resolve(legacy, name);
            Animation source = legacy.getAnimation(name.equals("tac:idle") ? "idle" : "walk");
            check(fallback != null && fallback.name().equals(name), "armed movement has a usable clip");
            check(fallback.length() == source.length() && fallback.loopType() == source.loopType()
                    && fallback.keyFrames() == source.keyFrames(), "movement metadata/event timing preserved");
            check(fallback.boneAnimations().length > 0, "movement fallback is not empty");
            for (BoneAnimation bone : fallback.boneAnimations()) {
                check(Set.of("Root", "LeftLeg", "RightLeg").contains(bone.boneName()),
                        "armed movement must not take calibrated arms/head/torso: " + bone.boneName());
                check(java.util.Arrays.asList(source.boneAnimations()).contains(bone),
                        "fallback shares untouched original movement tracks");
            }
        }
        Animation run = PillagerAnimationFallback.resolve(legacy, "run");
        check(run.boneAnimations() == legacy.getAnimation("walk").boneAnimations(),
                "unarmed run reuses existing walk without losing natural arm swing");
        for (String family : List.of("pistol", "rifle")) {
            for (String action : List.of("hold", "aim", "aim:fire", "reload", "melee")) {
                String name = "tac:" + action + ":" + family;
                Animation fallback = PillagerAnimationFallback.resolve(legacy, name);
                check(fallback != null && fallback.name().equals(name), "known missing gun clip resolved");
                check(fallback.boneAnimations().length == 0, "code remains the sole owner of gun pose");
                check(fallback.keyFrames().sounds().length == 0 && fallback.keyFrames().particles().length == 0
                        && fallback.keyFrames().customInstructions().length == 0, "no invented gun events");
                check(fallback.loopType() == Animation.LoopType.LOOP && fallback.length() > 0,
                        "neutral code pose is a valid looping stage");
                Animation authored = modern.getAnimation(name);
                check(authored != null && PillagerAnimationFallback.resolve(modern, name) == authored,
                        "a rebake with a real gun clip always wins over the legacy fallback");
            }
        }
        for (String unknown : List.of("death", "tac:teleport:rifle", "tac:aim:laser", "typo", "aim")) {
            check(PillagerAnimationFallback.resolve(legacy, unknown) == null,
                    "unrelated absent clip must remain absent: " + unknown);
        }
        var missingMovement = new BakedAnimations(new HashMap<>(legacy.animations()));
        missingMovement.animations().remove("walk");
        check(PillagerAnimationFallback.resolve(missingMovement, "tac:walk") == null,
                "do not manufacture locomotion when the existing source is absent");
        check(PillagerAnimationFallback.resolve(legacy, "tac:walk") != null,
                "resource bake identity is not cached globally");
        check(legacy.getAnimation("idle").boneAnimations().length == 5
                        && legacy.getAnimation("walk").boneAnimations().length == 5,
                "legacy shared arrays were not filtered in place");
        System.out.println("PASS " + checks + " production fallback checks using actual GeckoLib bakes");
    }
}
