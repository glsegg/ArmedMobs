package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.block.WeaponRackBlock;
import com.gfl.tarkovscav.block.WeaponRackBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the one item on the rack: 3D, floating, and slowly turning (README 5n).
 *
 * <p>Two reasons this is a block entity renderer rather than a baked model: the item is arbitrary (any TaCZ
 * gun, any modded sword, with its own model), and the rotation has to be smooth, which a baked model cannot
 * do. The rotation is driven by {@code level.getGameTime()} rather than a per-tick field, so it is the same
 * on every client and pauses naturally when the game is paused.</p>
 *
 * <h2>Following the rack's facing</h2>
 * <p>The rack model is turned by the <b>blockstate</b> ({@code y} 0/90/180/270), which moves geometry only -
 * it does not touch a block entity renderer's pose. So the item is turned by the same angle here, on top of
 * the existing transform, or the gun would keep pointing one way while its rack turned.</p>
 *
 * <p>The two conventions have to be matched up: the blockstate json uses {@code y = 0} for
 * {@link net.minecraft.core.Direction#NORTH}, while {@link net.minecraft.core.Direction#toYRot()} is
 * measured from SOUTH ({@code NORTH.toYRot() == 180}). Hence the {@code - 180}. NORTH therefore adds exactly
 * zero degrees and a rack that faced north before this feature - and every rack in a world saved before it -
 * is drawn exactly as it was, pixel for pixel.</p>
 */
public class WeaponRackRenderer implements BlockEntityRenderer<WeaponRackBlockEntity> {
    /** Degrees per tick: a full turn every 6 s - slow enough to read. */
    private static final float DEGREES_PER_TICK = 360.0F / 120.0F;

    public WeaponRackRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * The y rotation the blockstate json already applied for this facing (README 5n), so the item can apply
     * the same one. Kept in one place so the json and the renderer cannot drift apart.
     */
    public static float facingDegrees(Direction facing) {
        return facing.toYRot() - 180.0F;
    }

    @Override
    public void render(WeaponRackBlockEntity rack, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        ItemStack stack = rack.held();
        if (stack.isEmpty() || rack.getLevel() == null) {
            return;
        }
        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
        float spin = (rack.getLevel().getGameTime() + partialTick) * DEGREES_PER_TICK;
        // Legacy racks (and anything that somehow has no FACING) fall back to NORTH = no extra rotation.
        Direction facing = rack.getBlockState().hasProperty(WeaponRackBlock.FACING)
                ? rack.getBlockState().getValue(WeaponRackBlock.FACING) : Direction.NORTH;

        pose.pushPose();
        // Above the base plate, centred, then turned and tilted so a flat item still reads as an object.
        pose.translate(0.5D, 0.82D, 0.5D);
        // 1) the rack's own facing, so the item turns with the rack ...
        pose.mulPose(Axis.YP.rotationDegrees(facingDegrees(facing)));
        // 2) ... and then the slow idle spin inside that frame.
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.scale(0.6F, 0.6F, 0.6F);
        pose.mulPose(Axis.XP.rotationDegrees(20.0F));
        items.renderStatic(stack, ItemDisplayContext.FIXED, packedLight,
                OverlayTexture.NO_OVERLAY, pose, buffers, rack.getLevel(), 0);
        pose.popPose();
    }

    /** No culling box: the item can stick out of the block, and a rack is cheap to draw. */
    @Override
    public boolean shouldRenderOffScreen(WeaponRackBlockEntity rack) {
        return false;
    }
}
