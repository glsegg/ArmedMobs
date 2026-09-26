package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import com.tacz.guns.api.item.IGun;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * The vanilla villager model plus a weapon pose.
 *
 * <h2>Why a subclass is needed at all</h2>
 * <p>Everything visual about the gunner villager is vanilla: the geometry here is
 * {@code VillagerModel}'s own {@code ModelLayers.VILLAGER} bake, the textures are the vanilla base plus
 * the vanilla type/profession overlays, and the sounds come from {@code Villager}. The one thing that
 * does not exist for villagers is a weapon pose:</p>
 * <ul>
 *   <li>illagers get theirs from {@code IllagerModel} + {@code AbstractIllager#getArmPose()} - the
 *       illager model has separate arm parts and an {@code ArmPose} enum to switch between them;</li>
 *   <li>{@code VillagerModel} has <b>no arm parts to pose</b>. Its mesh has a single static
 *       {@code arms} block (part names: {@code head}, {@code hat}, {@code hat_rim}, {@code nose},
 *       {@code body}, {@code jacket}, {@code legs}, {@code arms}) and its {@code setupAnim} only
 *       animates {@code head} and the two legs - {@code arms} is never touched, so the crossed arms are
 *       baked in.</li>
 *   <li>{@code VillagerModel} also does not implement {@link ArmedModel}, and the vanilla
 *       {@code ItemInHandLayer} is declared as {@code <T extends LivingEntity, M extends EntityModel<T>
 *       & ArmedModel>}, so the held item cannot be rendered on it without that interface.</li>
 * </ul>
 *
 * <p>So this subclass adds exactly two things, and nothing else:</p>
 * <ol>
 *   <li><b>the arms rotation</b>, driven by the synced {@code GunAiState} through {@link ArmPose} -
 *       the same single source of truth the Bedrock rig uses, so the villager cannot end up "aiming"
 *       while the brain is idle;</li>
 *   <li><b>a grip socket</b> on each half of the crossed forearm bar. {@link GunGripModel} lets the
 *       held-gun layer use that socket directly, without vanilla's extra humanoid arm-length offset.
 *       The gun pack's {@code thirdperson_hand} locator therefore meets the rendered hand, and gun
 *       scale/rotation act around that grip instead of moving it towards the face.</li>
 * </ol>
 *
 * <p>The two arm angles are config keys ({@code client.gunnerVillagerAimArmPitch} /
 * {@code _HoldArmPitch}) plus {@code client.gunnerVillagerGunOffset}, because the exact number that
 * looks right on a vanilla villager body is an eye question, and a config flip is a better answer to
 * that than a rebuild.</p>
 */
public class GunnerVillagerModel extends VillagerModel<GunnerVillagerEntity> implements ArmedModel, GunGripModel {
    /**
     * The vanilla mesh's crossed-arms block. {@code VillagerModel} keeps no field for it and never poses it,
     * so it is looked up from the baked root exactly as the vanilla mesh names it - and its baked pose is
     * captured below, because that pose IS "the normal villager arms".
     */
    private final ModelPart arms;
    /**
     * The arms block's <b>baked rotation from the vanilla mesh</b>: {@code VillagerModel} builds the crossed
     * arms with {@code PartPose.offsetAndRotation(0, 3, -1, -0.75F, 0, 0)} - verified in the 1.20.1 client
     * jar's bytecode ({@code client-1.20.1-20230612.114412-srg.jar}, the {@code m_171052_} mesh method: the
     * two "arms" cubes are followed by {@code ldc -0.75f} into {@code PartPose.m_171423_}). Vanilla's
     * {@code setupAnim} never rewrites it, so this value - about <b>-43 degrees</b> - is the normal villager
     * arm position.
     *
     * <p>Writing a bare {@code 0} here (which the LOWERED branch and the no-weapon path both did) throws that
     * pose away and lays the crossed arms flat against the belly: the 2026 "when he is idle his hands lie
     * flat against his body and clip into it" report. Both paths now restore this captured value instead, and
     * {@code client.gunnerVillagerHoldArmPitch} is an OFFSET from it (0 = exactly the vanilla arms).</p>
     */
    private final float armsRestXRot;
    /** The torso root, used when {@code client.gunnerVillagerGunAnchor = body}. */
    private final ModelPart torso;
    /**
     * The pose {@link #setupAnim} resolved for this frame, so {@link #translateToHand} (which is handed no
     * entity) can apply the idle-only gun offset. Vanilla calls setupAnim before the layers render, so this
     * is always the current frame's pose.
     */
    private ArmPose currentPose = ArmPose.LOWERED;

    public GunnerVillagerModel(ModelPart root) {
        super(root);
        this.arms = root.getChild("arms");
        // Captured BEFORE anything writes to it: this is the pose the vanilla mesh was baked with, i.e. what
        // a plain villager shows (see armsRestXRot). Every "vanilla" path below restores this value.
        this.armsRestXRot = this.arms.xRot;
        this.torso = root;
    }

    @Override
    public void setupAnim(GunnerVillagerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // The vanilla head/legs walk animation first, byte for byte.
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // The pose is resolved first and remembered for translateToHand, even on the no-weapon path: the
        // held-gun layer asks for it, and a stale value would apply the idle offset to an aiming frame.
        ArmPose pose = ArmPose.forState(entity.gunAiState());
        this.currentPose = pose;
        if (!holdsGun(entity)) {
            // No weapon: the vanilla crossed arms, exactly as the mesh was baked - NOT a flat 0 (which is what
            // this used to write, and why an unarmed gunner villager's arms sat inside its belly).
            this.arms.xRot = this.armsRestXRot;
            this.arms.yRot = 0.0F;
            return;
        }

        // The arm angle is a function of the brain's own state (ArmPose), never of how the mob happens to
        // look this frame. FOUR silhouettes, and since 2026-09-24 all four mean the SAME thing: an OFFSET from
        // the vanilla rest (armsRestXRot, -43 degrees):
        //   RAISED    (ALERT/AIM/FIRE/SUPPRESS/BOLT/REPOSITION) -> rest + gunnerVillagerAimArmPitch
        //   RELOADING (RELOAD)                                  -> rest + gunnerVillagerReloadArmPitch
        //   HUNKERED  (RETREAT)                                 -> rest + gunnerVillagerHunkerArmPitch
        //   LOWERED   (IDLE while holding a weapon)             -> rest + gunnerVillagerHoldArmPitch
        // 0 = exactly the vanilla crossed arms, negative = lift above the rest, positive = press down.
        //
        // The four used to be inconsistent: LOWERED was an offset while the other three were absolute
        // angles, so the user - who had learned "0 = vanilla" from hold - set reload to -30 and got arms even
        // lower than the rest ("when reloading his arms still lie flat"). One meaning, four poses.
        if (pose == ArmPose.LOWERED) {
            // The write is NOT optional even at offset 0: a ModelPart keeps its rotation between frames, so
            // without it the arms would stay at whatever the last aiming frame wrote.
            float hold = Config.gunnerVillagerHoldArmPitch();
            this.arms.xRot = this.armsRestXRot + hold * Mth.DEG_TO_RAD;
            this.arms.yRot = 0.0F;
            return;
        }
        float pitch = switch (pose) {
            case RAISED -> Config.gunnerVillagerAimArmPitch();
            case RELOADING -> Config.gunnerVillagerReloadArmPitch();
            case HUNKERED -> Config.gunnerVillagerHunkerArmPitch();
            // LOWERED returned above (and the no-weapon path returned before that).
            case LOWERED -> 0.0F;
        };
        float aimPitch = pose == ArmPose.RAISED ? headPitch : 0.0F;
        this.arms.xRot = this.armsRestXRot + (pitch + aimPitch) * Mth.DEG_TO_RAD;
        this.arms.yRot = pose == ArmPose.RAISED ? netHeadYaw * Mth.DEG_TO_RAD : 0.0F;
    }

    /**
     * Legacy vanilla item path, retained for ordinary non-TaCZ equipment. Guns use the explicit
     * socket below, through {@link TaczItemInHandLayer}, so they do not inherit a second hand offset.
     */
    @Override
    public void translateToHand(HumanoidArm arm, PoseStack poseStack) {
        ModelPart anchor = Config.gunnerVillagerGunOnBody() ? this.root() : this.arms;
        anchor.translateAndRotate(poseStack);
        applyGunRotation(poseStack);
        float[] offset = gunOffset();
        poseStack.translate(offset[0], offset[1], offset[2]);
        applyGunScale(poseStack);
    }

    /**
     * The crossed forearm bar occupies x=-4..4, y=2..6, z=-2..2 in the vanilla mesh. A socket in
     * the centre of each half of its front face stays on the rendered hands in every arm pose.
     * TaCZ maps its thirdperson_hand locator to the item origin, so no humanoid arm-length
     * translation is needed. With zero correction offset, rotation and scale cannot move the grip.
     * Explicit offsets retain the existing rotated arm-frame convention.
     */
    @Override
    public void translateToGunGrip(HumanoidArm arm, PoseStack poseStack) {
        if (Config.gunnerVillagerGunOnBody()) {
            this.torso.translateAndRotate(poseStack);
            poseStack.translate(this.arms.x / 16.0F, this.arms.y / 16.0F, this.arms.z / 16.0F);
            poseStack.mulPose(com.mojang.math.Axis.XP.rotation(this.armsRestXRot));
        } else {
            this.arms.translateAndRotate(poseStack);
        }
        poseStack.translate(arm == HumanoidArm.RIGHT ? -2.0F / 16.0F : 2.0F / 16.0F,
                4.0F / 16.0F, -2.0F / 16.0F);
        applyGunRotation(poseStack);
        float[] offset = gunOffset();
        poseStack.translate(offset[0], offset[1], offset[2]);
    }

    @Override
    public void applyGunGripTransform(PoseStack poseStack) {
        applyGunScale(poseStack);
    }

    private void applyGunRotation(PoseStack poseStack) {
        float[] rotation = Config.gunnerVillagerGunRotation();
        // One delta per pose, ADDED to the base - and only addition, so aiming/firing (RAISED) is exactly the
        // base rotation the user calibrated, and the other three states can be tuned without touching it.
        float[] delta = switch (this.currentPose) {
            case LOWERED -> Config.gunnerVillagerIdleGunRotation();
            case RELOADING -> Config.gunnerVillagerReloadGunRotation();
            case HUNKERED -> Config.gunnerVillagerHunkerGunRotation();
            // RAISED: the base rotation IS this pose's rotation, so its delta is zero by construction.
            case RAISED -> null;
        };
        if (delta != null && (delta[0] != 0.0F || delta[1] != 0.0F || delta[2] != 0.0F)) {
            rotation = new float[]{rotation[0] + delta[0], rotation[1] + delta[1], rotation[2] + delta[2]};
        }
        if (rotation[0] != 0.0F) {
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotation[0]));
        }
        if (rotation[1] != 0.0F) {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotation[1]));
        }
        if (rotation[2] != 0.0F) {
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotation[2]));
        }
    }

    private float[] gunOffset() {
        float[] offset = Config.gunnerVillagerGunOffset();
        // ... plus this pose's own POSITION delta, the same arrangement as the rotation above: RAISED (aiming
        // and firing) is the base position the user calibrated, and the other three can move the gun without
        // touching it - which is the only way to "take the gun out of his body while idle" without moving the
        // aiming pose as well.
        float[] offsetDelta = switch (this.currentPose) {
            case LOWERED -> Config.gunnerVillagerIdleGunOffset();
            case RELOADING -> Config.gunnerVillagerReloadGunOffset();
            case HUNKERED -> Config.gunnerVillagerHunkerGunOffset();
            case RAISED -> null;
        };
        if (offsetDelta != null) {
            offset = new float[]{offset[0] + offsetDelta[0], offset[1] + offsetDelta[1],
                    offset[2] + offsetDelta[2]};
        }
        return offset;
    }

    private static void applyGunScale(PoseStack poseStack) {
        float scale = Config.gunnerVillagerGunScale();
        if (scale != 1.0F) {
            poseStack.scale(scale, scale, scale);
        }
    }

    /** Client-side "is a TaCZ gun in the main hand" - the same test {@code ScavEntity#isArmed} uses. */
    private static boolean holdsGun(GunnerVillagerEntity entity) {
        ItemStack held = entity.getMainHandItem();
        return !held.isEmpty() && IGun.getIGunOrNull(held) != null;
    }
}
