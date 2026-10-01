package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.BlackFoxEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * The four Black Fox rigs share the scav's animation layering, Molang feed and spline correction.
 * Their movement clips are retargeted to their own bones; accessory-only clips are applied by a
 * separate controller, so a visor variation cannot overwrite the gun pose or locomotion.
 */
public final class BlackFoxGeoModel extends ScavGeoModel {
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
