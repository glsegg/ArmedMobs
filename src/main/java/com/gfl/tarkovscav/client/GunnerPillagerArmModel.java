package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The vanilla illager model with one addition: the gun arm follows the gun AI's own state.
 *
 * <h2>Why a subclass instead of a render layer</h2>
 * <p>The illager's arm pose is decided inside {@code IllagerModel#setupAnim} (its {@code getArmPose}
 * is private and the arm {@link ModelPart}s are private fields), so there is no way to change it from
 * outside the model. This subclass calls {@code super.setupAnim} first - which keeps the whole
 * vanilla walk cycle, the head look, the crossed-arms idle and the death pose exactly as they were -
 * and only then overwrites the two arm rotations for the states in which the weapon is up.</p>
 *
 * <h2>Where the pose comes from</h2>
 * <p>{@link ArmPose#forState} maps the <em>synced</em> gun-AI state, so this is the same value
 * {@code GunBrain} itself transitions on: a mob cannot aim the model while the brain is idle, and it
 * cannot fire with its arms down. See {@link ArmPose} for the state table and for why that predicate
 * is a superset of "TaCZ is asked to shoot this tick".</p>
 *
 * <h2>Only xRot and yRot are written</h2>
 * <p>Vanilla assigns both of those absolutely on every frame in both of its branches, so nothing this
 * class writes can leak into a later frame, and {@link ArmPose#LOWERED} writes nothing at all - the
 * idle look is byte-for-byte the pose the user said was fine. {@code zRot}, which one branch of
 * vanilla does not assign, is deliberately left alone.</p>
 */
public class GunnerPillagerArmModel extends IllagerModel<GunnerPillagerEntity> {
    /** Arm forward at the shoulder: half a turn down from the rest pose. */
    private static final float ARM_FORWARD = -Mth.PI / 2.0F;

    private final ModelPart rightArm;
    private final ModelPart leftArm;

    public GunnerPillagerArmModel(ModelPart root) {
        super(root);
        this.rightArm = child(root, "right_arm");
        this.leftArm = child(root, "left_arm");
    }

    /** {@code getChild} throws for a name that is not there; a missing arm must not crash a render. */
    private static ModelPart child(ModelPart root, String name) {
        return root.hasChild(name) ? root.getChild(name) : null;
    }

    @Override
    public void setupAnim(GunnerPillagerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // The vanilla pose first: walking, head look, crossed arms, everything.
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        ArmPose pose = RigSupport.armPose(entity);
        if (pose == ArmPose.LOWERED) {
            // The gun hangs in the hand, exactly as before this class existed.
            return;
        }
        if (this.rightArm == null || this.leftArm == null) {
            return;
        }

        // Left/right arm yaw follows the head (both are radians here, EntityModel space). The same
        // trick the vanilla BOW_AND_ARROW pose uses, so the weapon points where the mob is looking.
        ModelPart head = this.getHead();
        float yaw = head == null ? 0.0F : head.yRot;
        float pitch = head == null ? 0.0F : head.xRot;

        switch (pose) {
            case RAISED -> {
                this.rightArm.xRot = ARM_FORWARD + pitch * 0.5F;
                this.rightArm.yRot = yaw - 0.1F;
                // The support hand comes up and across towards the handguard.
                this.leftArm.xRot = ARM_FORWARD + pitch * 0.5F;
                this.leftArm.yRot = yaw + 0.5F;
            }
            case RELOADING -> {
                // Gun pulled in towards the chest, both hands on the magazine.
                this.rightArm.xRot = -0.5F + pitch * 0.3F;
                this.rightArm.yRot = yaw * 0.4F;
                this.leftArm.xRot = -1.0F;
                this.leftArm.yRot = 0.6F;
            }
            case HUNKERED -> {
                // Breaking contact: gun down, shoulders in, still facing the threat.
                this.rightArm.xRot = -0.35F;
                this.rightArm.yRot = 0.0F;
                this.leftArm.xRot = -0.35F;
                this.leftArm.yRot = 0.0F;
            }
            default -> {
                // LOWERED was handled above.
            }
        }
    }
}
