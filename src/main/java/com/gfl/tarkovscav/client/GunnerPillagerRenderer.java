package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.IllagerRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * The default renderer: a vanilla illager wearing the vanilla pillager texture, holding whatever is
 * in its main hand (a TaCZ gun) in the normal way.
 *
 * <p>This is deliberately the "no new assets" option, so the mob looks right before the Bedrock
 * model exists. {@link GunnerPillagerGeoRenderer} replaces it when
 * {@code client.useGeckoModel} is switched on.</p>
 *
 * <p>The model is {@link GunnerPillagerArmModel}, which keeps this vanilla pose for everything except
 * the arms - those follow the gun AI's synced state, so the mob raises the gun when it is about to
 * shoot and keeps the accepted hanging pose when it is idle. The texture stays the vanilla
 * {@code illager/pillager.png}: the mod's own {@code textures/entity/gunner_pillager.png} belongs to
 * the placeholder Bedrock rig's UV layout and would smear across this vanilla model.</p>
 *
 * <p>{@code ItemInHandLayer} renders the held item with {@code ItemDisplayContext.THIRD_PERSON_RIGHT_HAND}
 * / {@code _LEFT_HAND}, which is also the default of the Bedrock path's gun layer - so both render
 * paths ask TaCZ for the same thing.</p>
 */
public class GunnerPillagerRenderer extends IllagerRenderer<GunnerPillagerEntity> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("textures/entity/illager/pillager.png");

    public GunnerPillagerRenderer(EntityRendererProvider.Context context) {
        super(context, new GunnerPillagerArmModel(context.bakeLayer(ModelLayers.PILLAGER)), 0.5F);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(GunnerPillagerEntity entity) {
        return TEXTURE;
    }
}
