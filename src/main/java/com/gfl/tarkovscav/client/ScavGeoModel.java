package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * The scav's Bedrock rig: the user's own YSM Scav model, imported by
 * {@code tools/import_scav_assets.js} from {@code assets_source/scav/}.
 *
 * <p>Asset paths, bone names and the gun anchor are documented in {@link RigSupport}.</p>
 */
public class ScavGeoModel extends GeoModel<ScavEntity> {
    /**
     * The baked model whose bones already carry the hidden flags, or null before the first hide pass.
     *
     * <p>Keyed on the {@link BakedGeoModel} object itself. GeckoLib's {@code instanceId} is <em>not</em>
     * usable for this: for an entity it is the entity's network id
     * ({@code GeoEntityRenderer#getInstanceId}), which does not change when the model is rebaked, so
     * keying on it - as this class did - meant that after a resource reload (F3+T, a texture pack
     * switch, another mod reloading resources) GeckoLib built brand-new bones with {@code hidden} back
     * at false and nothing hid the author's reference props again for the rest of the session. A
     * rebake replaces the cached {@code BakedGeoModel} instance, so object identity is exactly the
     * "did this model change" test that is wanted - and one call covers every mob using the rig,
     * because the flags live on the shared bones.</p>
     */
    private BakedGeoModel hiddenOn;

    /** Missing-clip reports already made, keyed by rig + config generation. */
    private static final java.util.Set<String> LOGGED_MISSING_CLIPS = new java.util.HashSet<>();

    /** The config generation the hide pass above last ran for; /tarkovscav client reload bumps it. */
    private int configuredGeneration = -1;

    @Override
    public ResourceLocation getModelResource(ScavEntity animatable) {
        return TarkovScav.id("geo/scav.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ScavEntity animatable) {
        return TarkovScav.id("textures/entity/scav.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ScavEntity animatable) {
        return TarkovScav.id("animations/scav.animation.json");
    }

    /** A rig should not hard-crash the game over one renamed bone. */
    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }

    @Override
    public void handleAnimations(ScavEntity animatable, long instanceId, AnimationState<ScavEntity> state) {
        PoseWriters.beginMob();
        supplyMolang(animatable, state);
        super.handleAnimations(animatable, instanceId, state);
    }

    /**
     * Feeds the rig's own aim variables for this mob before the controllers tick (see
     * {@link RigSupport#supplyMolangVariables}), so the author's Molang keyframes evaluate against the
     * look direction GeckoLib already computed for this frame rather than against 0.
     */
    private void supplyMolang(ScavEntity animatable, AnimationState<ScavEntity> state) {
        EntityModelData modelData = state == null ? null : state.getData(DataTickets.ENTITY_MODEL_DATA);
        RigSupport.supplyMolangVariables(animatable, modelData == null ? 0.0F : modelData.netHeadYaw(),
                modelData == null ? 0.0F : modelData.headPitch());
    }

    @Override
    public void setCustomAnimations(ScavEntity animatable, long instanceId, AnimationState<ScavEntity> state) {
        super.setCustomAnimations(animatable, instanceId, state);

        BakedGeoModel baked = getBakedModel(getModelResource(animatable));
        boolean rebaked = baked != this.hiddenOn;
        if (rebaked || this.configuredGeneration != RigSupport.configGeneration()) {
            this.hiddenOn = baked;
            this.configuredGeneration = RigSupport.configGeneration();
            RigSupport.hideReferenceProps(this, animatable);
        }

        warnAboutMissingClips(animatable);

        EntityModelData modelData = state.getData(DataTickets.ENTITY_MODEL_DATA);
        if (modelData == null) {
            return;
        }
        RigSupport.applyAimTracking(this, animatable, instanceId, modelData.netHeadYaw(), modelData.headPitch(),
                RigSupport.isAiming(animatable), RigSupport.gunAiState(animatable));
    }

    /**
     * A clip the code plays but the animation file does not have is a mob frozen in its bind pose, and
     * GeckoLib only prints {@code Unable to find animation: ...} to {@code System.out} - not to the mod
     * log, and without saying which mob or which clip family. This says it once per bake, per rig, with
     * the clip names and the rig that is missing them (the shipped {@code gunner_pillager} placeholder
     * rig is missing all of the {@code tac:*} names, which is exactly the case this exists for).
     */
    private void warnAboutMissingClips(ScavEntity animatable) {
        String key = "scav|" + RigSupport.configGeneration();
        if (!LOGGED_MISSING_CLIPS.add(key)) {
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String clip : RigSupport.expectedClips()) {
            boolean present;
            try {
                present = getAnimation(animatable, clip) != null;
            } catch (RuntimeException unreadable) {
                present = false;
            }
            if (!present) {
                missing.add(clip);
            }
        }
        if (!missing.isEmpty()) {
            TarkovScav.LOGGER.warn("[clips] {}: the animation file has no clip(s) {} - a mob that is told to"
                            + " play one of those stays in its bind pose. Model {}",
                    animatable.getType().toShortString(), missing, getModelResource(animatable));
        }
    }
}
