package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * The vanilla illager model with one addition: the gun arm follows the gun AI's own state.
 *
 * <h2>Why a subclass instead of a render layer</h2>
 * <p>The illager's arm pose is decided inside {@code IllagerModel#setupAnim} (its {@code getArmPose}
 * is private and the arm {@link ModelPart}s are private fields), so there is no way to change it from
 * outside the model. This subclass calls {@code super.setupAnim} first - which keeps the whole
 * vanilla walk cycle, the head look, the crossed-arms idle and the death pose exactly as they were -
 * and only then overwrites the two arm rotations for the selected weapon pose.</p>
 *
 * <h2>Where the pose comes from</h2>
 * <p>{@link ArmPose#forState} maps the <em>synced</em> gun-AI state, so this is the same value
 * {@code GunBrain} itself transitions on: a mob cannot aim the model while the brain is idle, and it
 * cannot fire with its arms down. See {@link ArmPose} for the state table and for why that predicate
 * is a superset of "TaCZ is asked to shoot this tick".</p>
 *
 * <p>Weapon poses write absolute arm angles after vanilla animation, including clearing the melee
 * swing's roll. In {@link ArmPose#LOWERED}, long guns use a forward, lowered carry so their barrels
 * do not hang below the feet; pistols and ordinary items retain the vanilla idle pose.</p>
 */
public class GunnerPillagerArmModel extends IllagerModel<GunnerPillagerEntity> {
    /** Arm forward at the shoulder: half a turn down from the rest pose. */
    private static final float ARM_FORWARD = -Mth.PI / 2.0F;
    /** Muzzle 25 degrees below horizontal, with the grip still on the actual hand. */
    private static final float ARM_LOW_READY = -65.0F * Mth.DEG_TO_RAD;

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
        boolean longGun = !entity.getMainHandItem().isEmpty()
                && IGun.getIGunOrNull(entity.getMainHandItem()) != null
                && !entity.usesPistolClips();
        if (pose == ArmPose.LOWERED && !longGun) {
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
        boolean rightHanded = entity.getMainArm() == HumanoidArm.RIGHT;
        ModelPart weaponArm = rightHanded ? this.rightArm : this.leftArm;
        ModelPart supportArm = rightHanded ? this.leftArm : this.rightArm;
        float side = rightHanded ? 1.0F : -1.0F;
        weaponArm.zRot = 0.0F;
        supportArm.zRot = 0.0F;

        switch (pose) {
            case RAISED -> {
                weaponArm.xRot = ARM_FORWARD + pitch;
                weaponArm.yRot = yaw;
                // The support hand comes up and across towards the handguard.
                supportArm.xRot = ARM_FORWARD + pitch;
                supportArm.yRot = yaw + side * 0.5F;
            }
            case RELOADING -> {
                // Gun pulled in towards the chest, both hands on the magazine.
                weaponArm.xRot = longGun ? ARM_LOW_READY + pitch * 0.15F : -0.5F + pitch * 0.3F;
                weaponArm.yRot = yaw * 0.4F;
                supportArm.xRot = longGun ? ARM_LOW_READY : -1.0F;
                supportArm.yRot = side * 0.6F;
            }
            case HUNKERED -> {
                // Breaking contact: gun down, shoulders in, still facing the threat.
                weaponArm.xRot = longGun ? ARM_LOW_READY : -0.35F;
                weaponArm.yRot = 0.0F;
                supportArm.xRot = weaponArm.xRot;
                supportArm.yRot = longGun ? side * 0.5F : 0.0F;
            }
            case LOWERED -> {
                weaponArm.xRot = ARM_LOW_READY;
                weaponArm.yRot = 0.0F;
                supportArm.xRot = ARM_LOW_READY;
                supportArm.yRot = side * 0.5F;
            }
        }
    }
}
