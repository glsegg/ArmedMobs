package com.gfl.tarkovscav.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.HumanoidArm;

/** A mesh with an explicit grip socket instead of a humanoid shoulder-to-hand offset. */
public interface GunGripModel {
    void translateToGunGrip(HumanoidArm arm, PoseStack poseStack);

    /** Called after the standard hand orientation, at the grip origin. */
    void applyGunGripTransform(PoseStack poseStack);
}
