package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import java.util.HashMap;
import java.util.Map;

/**
 * The gun-armed pillager's Bedrock rig, used only when {@code client.useGeckoModel} is true.
 *
 * <p>Placeholder files ship with the mod (the same blocky rig as the scav's first placeholder,
 * tinted) so the switch can be flipped immediately; drop the real
 * {@code geo/gunner_pillager.geo.json}, {@code animations/gunner_pillager.animation.json} and
 * {@code textures/entity/gunner_pillager.png} in to replace them. The bone names to use are the same
 * as the scav rig's ({@link RigSupport}), and the gun mounts on {@code Gun3}.</p>
 */
public class GunnerPillagerGeoModel extends GeoModel<GunnerPillagerEntity> {
    /** See {@code ScavGeoModel}: keyed on the baked model, because a rebake rebuilds the bones. */
    private BakedGeoModel hiddenOn;

    /** See {@code ScavGeoModel}: /tarkovscav client reload bumps the generation. */
    private int configuredGeneration = -1;
    private BakedAnimations fallbackAnimationsOn;
    private final Map<String, Animation> fallbackAnimations = new HashMap<>();
    private boolean fallbackReported;

    @Override
    public Animation getAnimation(GunnerPillagerEntity animatable, String name) {
        // Keep Gecko's normal missing-file/path errors and any real clip supplied by a resource pack.
        Animation original = super.getAnimation(animatable, name);
        BakedAnimations animations = GeckoLibCache.getBakedAnimations().get(getAnimationResource(animatable));
        if (this.fallbackAnimationsOn != animations) {
            this.fallbackAnimationsOn = animations;
            this.fallbackAnimations.clear();
            this.fallbackReported = false;
        }
        if (original != null || animations == null) return original;
        Animation fallback = this.fallbackAnimations.computeIfAbsent(name,
                clip -> PillagerAnimationFallback.resolve(animations, clip));
        if (fallback != null && !this.fallbackReported) {
            this.fallbackReported = true;
            TarkovScav.LOGGER.warn("[clips] {} lacks {}; using legacy movement and code-driven gun poses"
                            + " where armed clips are absent. Resource: {}",
                    animatable.getType().toShortString(), name, getAnimationResource(animatable));
        }
        return fallback;
    }

    @Override
    public ResourceLocation getModelResource(GunnerPillagerEntity animatable) {
        return TarkovScav.id("geo/gunner_pillager.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(GunnerPillagerEntity animatable) {
        return TarkovScav.id("textures/entity/gunner_pillager.png");
    }

    @Override
    public ResourceLocation getAnimationResource(GunnerPillagerEntity animatable) {
        return TarkovScav.id("animations/gunner_pillager.animation.json");
    }

    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }

    @Override
    public void handleAnimations(GunnerPillagerEntity animatable, long instanceId,
                                 AnimationState<GunnerPillagerEntity> state) {
        PoseWriters.beginMob();
        EntityModelData modelData = state == null ? null : state.getData(DataTickets.ENTITY_MODEL_DATA);
        RigSupport.supplyMolangVariables(animatable, modelData == null ? 0.0F : modelData.netHeadYaw(),
                modelData == null ? 0.0F : modelData.headPitch());
        super.handleAnimations(animatable, instanceId, state);
    }

    @Override
    public void setCustomAnimations(GunnerPillagerEntity animatable, long instanceId,
                                    AnimationState<GunnerPillagerEntity> state) {
        super.setCustomAnimations(animatable, instanceId, state);

        BakedGeoModel baked = getBakedModel(getModelResource(animatable));
        boolean rebaked = baked != this.hiddenOn;
        if (rebaked || this.configuredGeneration != RigSupport.configGeneration()) {
            this.hiddenOn = baked;
            this.configuredGeneration = RigSupport.configGeneration();
            RigSupport.hideReferenceProps(this, animatable);
        }

        // The arm pose is written for EVERY rig, because the code cannot know whether the rig that is
        // installed ships gun clips. Under client.poseSource = auto it is the fallback for a rig with no
        // clips: an arm bone a playing clip animates is left to that clip.
        RigSupport.applyArmPose(this, animatable, instanceId);

        EntityModelData modelData = state.getData(DataTickets.ENTITY_MODEL_DATA);
        if (modelData == null) {
            return;
        }
        RigSupport.applyAimTracking(this, animatable, instanceId, modelData.netHeadYaw(), modelData.headPitch(),
                RigSupport.isAiming(animatable), RigSupport.gunAiState(animatable));
    }
}
