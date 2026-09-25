package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * The GeckoLib variant of the gunner pillager's renderer - the one to use once the Bedrock model has
 * been dropped in ({@code client.useGeckoModel = true}).
 *
 * <p>Swapping between the vanilla illager renderer and this one is the only change needed: both are
 * registered for the same entity type in {@link ClientSetup}.</p>
 */
public class GunnerPillagerGeoRenderer extends GeoEntityRenderer<GunnerPillagerEntity> {
    public GunnerPillagerGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new GunnerPillagerGeoModel());

        // Live size: read per render, see scale() below (and ScavRenderer for why).
        this.shadowRadius = 0.5F * Config.renderScale();
        this.addRenderLayer(new GunInHandGeoLayer<>(this));
    }

    /** Same live size as {@link ScavRenderer}: GeckoLib's scale fields, written per render. */
    private void applyLiveScale() {
        float scale = Config.renderScale();
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = 0.5F * scale;
    }

    /** Same A/B as {@link ScavRenderer}: see {@link ModelRenderTypes}. */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(GunnerPillagerEntity animatable,
                                                                  net.minecraft.resources.ResourceLocation texture,
                                                                  net.minecraft.client.renderer.MultiBufferSource bufferSource,
                                                                  float partialTick) {
        return ModelRenderTypes.of(texture);
    }

    /** Same {@link RenderStateGuard} bracket as {@link ScavRenderer} - see README 5k. */
    @Override
    public void render(GunnerPillagerEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        applyLiveScale();
        RenderStateGuard guard = RenderStateGuard.snapshot("gunner_pillager " + entity.getId());
        RenderStateGuard.forceAlwaysPassStencil();
        try {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            guard.restore();
        }
    }

}
