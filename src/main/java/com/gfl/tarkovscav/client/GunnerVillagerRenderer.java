package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders the gunner villager with the vanilla villager model, the vanilla textures and the vanilla
 * profession layer - plus the held gun.
 *
 * <p><b>The texture setup is copied from {@code net.minecraft.client.renderer.entity.VillagerRenderer}
 * on purpose</b>, because that is what "reuse the vanilla villager" has to mean to keep the
 * biome/profession skins: base {@code textures/entity/villager/villager.png}, one
 * {@code VillagerProfessionLayer} constructed with {@code "villager"} (vanilla passes exactly that
 * string), and the same {@code ModelLayers.VILLAGER} bake. The only difference is the model class
 * (ours poses the arms) and the extra {@code ItemInHandLayer} so the TaCZ gun is visible in the
 * villager's hands - the layer the vanilla renderer does not need, since villagers never visibly hold
 * an item.</p>
 */
public class GunnerVillagerRenderer
        extends MobRenderer<GunnerVillagerEntity, GunnerVillagerModel> {

    /** The vanilla villager body texture - no new asset is shipped for this mob. */
    private static final ResourceLocation VILLAGER_TEXTURE =
            new ResourceLocation("textures/entity/villager/villager.png");

    public GunnerVillagerRenderer(EntityRendererProvider.Context context) {
        super(context, new GunnerVillagerModel(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
        // The villager family has its OWN scale key (README 5b/5j). Unlike the Bedrock rig, the vanilla
        // villager mesh is authored at 1.0, so villagerRenderScale is used ABSOLUTELY and its default 1.0 is
        // exactly vanilla size. The old code divided renderScale by the RIG's 0.7 baseline instead, so
        // enlarging the rig to 0.77 made every armed villager 10 % too big.
        this.shadowRadius = 0.5F * Config.villagerRenderScale();
        // Same layer and same path prefix as the vanilla renderer: biome type + profession + level.
        this.addLayer(new VillagerProfessionLayer<>(this, context.getResourceManager(), "villager"));
        // The gun. TaCZ's item renderer draws in THIRD_PERSON_RIGHT_HAND, which is the context this
        // layer passes for the main hand.
        this.addLayer(new HeldGunLayer(this, context.getItemInHandRenderer()));
    }

    /**
     * The held-gun layer with one opt-in exception: {@code client.hideGunWhenIdle} (default false) skips the
     * draw while the synced state is {@link ArmPose#LOWERED} - the mob is standing still with the vanilla
     * crossed arms, and the user may prefer the gun not to sit on top of them.
     *
     * <p>Off by default on purpose: the gun is where the vanilla {@code ItemInHandLayer} puts a held item,
     * so this is a taste switch and not a fix (README 5j). The gun comes back on the next frame after the
     * mob aims, reloads or retreats, because the layer asks the state every frame.</p>
     */
    private static final class HeldGunLayer
            extends TaczItemInHandLayer<GunnerVillagerEntity, GunnerVillagerModel> {
        HeldGunLayer(GunnerVillagerRenderer renderer,
                     net.minecraft.client.renderer.ItemInHandRenderer itemInHandRenderer) {
            super(renderer, itemInHandRenderer);
        }

        @Override
        public void render(com.mojang.blaze3d.vertex.PoseStack poseStack,
                           net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight,
                           GunnerVillagerEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (Config.hideGunWhenIdle() && RigSupport.armPose(entity) == ArmPose.LOWERED) {
                return;
            }
            super.render(poseStack, bufferSource, packedLight, entity, limbSwing, limbSwingAmount, partialTick,
                    ageInTicks, netHeadYaw, headPitch);
        }
    }

    /**
     * The live model size for the villager family; read every frame from <b>its own key</b>
     * ({@code client.villagerRenderScale}), applied absolutely: 1.0 is the vanilla villager, which is how the
     * vanilla mesh is authored.
     *
     * <p>It deliberately does NOT read {@code client.renderScale}: that is the Bedrock rig's key (authored at
     * 0.7), and sharing it was what made the armed villagers 10 % too big once the rig was enlarged
     * (README 5b/5j). The shadow radius is kept in step here, so a live change moves both.</p>
     */
    @Override
    protected void scale(GunnerVillagerEntity entity, com.mojang.blaze3d.vertex.PoseStack poseStack,
                         float partialTick) {
        float villagerScale = Config.villagerRenderScale();
        this.shadowRadius = 0.5F * villagerScale;
        poseStack.scale(villagerScale, villagerScale, villagerScale);
    }

    @Override
    public ResourceLocation getTextureLocation(GunnerVillagerEntity entity) {
        return VILLAGER_TEXTURE;
    }

    /**
     * The villager renderer is vanilla-model based, but the same leak applies: its held-item layer draws
     * a TaCZ gun, whose renderer leaves the stencil func at {@code GL_EQUAL/0} (README 5k). Bracket the
     * whole entity draw with {@link RenderStateGuard}, exactly like the two Bedrock renderers.
     */
    @Override
    public void render(GunnerVillagerEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        RenderStateGuard guard = RenderStateGuard.snapshot("gunner_villager " + entity.getId());
        RenderStateGuard.forceAlwaysPassStencil();
        try {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            guard.restore();
        }
    }
}
