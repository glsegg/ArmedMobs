package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.ScavEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ScavRenderer extends GeoEntityRenderer<ScavEntity> {
    public ScavRenderer(EntityRendererProvider.Context context) {
        super(context, new ScavGeoModel());

        // No withScale(...) on purpose: the size is read per render (see scale() below), so
        // /tarkovscav client scale applies on the next frame instead of needing a restart.
        this.shadowRadius = 0.5F * Config.renderScale();

        // shows the TaCZ gun it is holding in its RightHand bone
        this.addRenderLayer(new GunInHandGeoLayer<>(this));
    }

    /**
     * The live model size. GeckoLib's {@code GeoEntityRenderer} is not a {@code MobRenderer}, so there is
     * no {@code scale(...)} hook to override; its size comes from the {@code scaleWidth}/{@code scaleHeight}
     * fields, which is what {@code withScale(...)} sets. Writing them here - on every render, right before
     * the super call - is what makes {@code /tarkovscav client scale} take effect on the next frame instead
     * of needing a restart (the old code baked the scale in at construction).
     */
    private void applyLiveScale() {
        float scale = Config.renderScale();
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = 0.5F * scale;
    }

    /**
     * The rig is drawn with one of the stock entity render types, chosen by
     * {@code client.modelRenderType} - see {@link ModelRenderTypes} for the A/B and why the default is
     * the plain cutout one.
     */
    @Override
    public RenderType getRenderType(ScavEntity animatable, ResourceLocation texture,
                                    net.minecraft.client.renderer.MultiBufferSource bufferSource,
                                    float partialTick) {
        return ModelRenderTypes.of(texture);
    }

    /**
     * Every draw of this mob is bracketed by {@link RenderStateGuard}: the stencil test is forced to
     * always-pass (so a state left behind by TaCZ's gun renderer cannot cull our geometry - that is the
     * "half the mob is missing and I see the golem behind it" report), and the state we found is put
     * back afterwards. See README 5k for the bytecode evidence.
     */
    @Override
    public void render(ScavEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        applyLiveScale();
        RenderStateGuard guard = RenderStateGuard.snapshot("scav " + entity.getId());
        RenderStateGuard.forceAlwaysPassStencil();
        try {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            guard.restore();
        }
    }

}
