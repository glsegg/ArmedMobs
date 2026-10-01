package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.BlackFoxEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.gun.GunClips;
import com.gfl.tarkovscav.gun.TaczPresence;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * The four Black Fox rigs share the scav's animation layering and spline correction.
 * Their movement clips are retargeted to their own bones; accessory-only clips are applied by a
 * separate controller, so a visor variation cannot overwrite the gun pose or locomotion.
 */
public final class BlackFoxGeoModel extends ScavGeoModel {
    private final BlackFoxPose pose = new BlackFoxPose();
    private BakedGeoModel poseModel;

    private static String assetName(ScavEntity entity) {
        return "blackfox_" + ((BlackFoxEntity) entity).role().id();
    }

    @Override
    public ResourceLocation getModelResource(ScavEntity entity) {
        return TarkovScav.id("geo/" + assetName(entity) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ScavEntity entity) {
        return TarkovScav.id("textures/entity/" + assetName(entity) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(ScavEntity entity) {
        return TarkovScav.id("animations/" + assetName(entity) + ".animation.json");
    }

    @Override
    protected void supplyMolang(ScavEntity entity, AnimationState<ScavEntity> state) {
        // The imported clips distribute look over Root, AllBody, UpBody, Arms and Head. On these
        // rigs their different pivots turn that into a whole-body twist. Keep their recoil/reload
        // keyframes, and let one bounded pose writer supply the live look after the clips tick.
        RigSupport.supplyMolangVariables(entity, 0.0F, 0.0F);
    }

    @Override
    protected void applyAimTracking(ScavEntity entity, long instanceId, EntityModelData modelData) {
        // Applied below on every draw, including GeckoLib's same-frame animation-cache path.
    }

    @Override
    public void handleAnimations(ScavEntity entity, long instanceId, AnimationState<ScavEntity> state) {
        BakedGeoModel baked = getBakedModel(getModelResource(entity));
        // Bones are shared by every entity using this bake. Undo only our previous post-clip pose
        // before another entity's controllers run; their own snapshot/transition data stays intact.
        this.pose.restore();
        if (baked != this.poseModel) {
            this.poseModel = baked;
            this.pose.clear();
        }
        super.handleAnimations(entity, instanceId, state);
        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        if (data == null || !entity.isAlive() || entity.deathTime > 0
                || ClipPose.of(entity, instanceId).clips().contains(GunClips.DEATH)) {
            return;
        }
        this.pose.apply(getBone("UpperBody").orElse(null), getBone("Arms").orElse(null),
                getBone("Head").orElse(null), getBone("LeftArm").orElse(null),
                getBone("RightArm").orElse(null), data.netHeadYaw(), data.headPitch(),
                TaczPresence.isGun(entity.getMainHandItem()));
    }

    @Override
    protected void configureVisibility(ScavEntity entity) {
        // Scav hat/eye-gear settings name that rig's accessories. Applying them to the new rigs
        // would hide unrelated parts or report absent bones; their own cosmetic clips control them.
        RigVisibility.restore(getAnimationProcessor().getRegisteredBones());
        // These meshes are the author's reference guns, not the equipped ItemStack. Keep the
        // actual palm locators visible and draw the current equipment through DeferredItemPass.
        String referenceGun = switch (((BlackFoxEntity) entity).role()) {
            case ASSAULT -> null;
            case HEAVY -> "SCAR";
            case DEMOLITION -> "VV";
            case COMMANDER -> "HK416";
        };
        if (referenceGun != null) {
            getBone(referenceGun).ifPresent(RigVisibility::hide);
        }
    }
}
