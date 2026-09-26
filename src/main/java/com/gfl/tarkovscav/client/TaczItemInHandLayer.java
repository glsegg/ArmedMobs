package com.gfl.tarkovscav.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Keep the actual hand transform while using a third-person context that TaCZ can draw. */
public class TaczItemInHandLayer<T extends LivingEntity, M extends EntityModel<T> & ArmedModel>
        extends ItemInHandLayer<T, M> {
    private final ItemInHandRenderer itemRenderer;

    public TaczItemInHandLayer(RenderLayerParent<T, M> renderer, ItemInHandRenderer itemRenderer) {
        super(renderer, itemRenderer);
        this.itemRenderer = itemRenderer;
    }

    @Override
    protected void renderArmWithItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                     HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffers,
                                     int packedLight) {
        if (stack.isEmpty() || IGun.getIGunOrNull(stack) == null) {
            super.renderArmWithItem(entity, stack, context, arm, poseStack, buffers, packedLight);
            return;
        }

        // TaCZ's ItemInHandLayerMixin cancels the superclass method for the physical LEFT arm
        // whenever the entity's main hand holds a gun, even if its display context is RIGHT_HAND.
        // Render guns directly, retaining vanilla's actual hand transform and mirror flag.
        boolean left = arm == HumanoidArm.LEFT;
        poseStack.pushPose();
        try {
            if (getParentModel() instanceof GunGripModel grip) {
                grip.translateToGunGrip(arm, poseStack);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                // The socket already is the hand, so adding vanilla's humanoid hand translation here
                // would move a villager's gun another ten model pixels away from its crossed arms.
                grip.applyGunGripTransform(poseStack);
            } else {
                getParentModel().translateToHand(arm, poseStack);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.translate((left ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
            }
            // TaCZ's gun renderer also ignores THIRD_PERSON_LEFT_HAND, independently of its mixin.
            this.itemRenderer.renderItem(entity, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    left, poseStack, buffers, packedLight);
        } finally {
            poseStack.popPose();
        }
    }
}
