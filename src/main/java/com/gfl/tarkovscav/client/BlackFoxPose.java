package com.gfl.tarkovscav.client;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.ArrayList;
import java.util.List;

/** One live look writer for the Black Fox rigs, applied after their neutral-look animation clips. */
final class BlackFoxPose {
    private static final float RADIANS = (float) (Math.PI / 180.0D);
    private static final float CARRY_LIFT = 2.0F;
    private final List<Frame> neutral = new ArrayList<>(3);

    void clear() {
        this.neutral.clear();
    }

    void restore() {
        for (Frame frame : this.neutral) {
            frame.restore();
        }
        this.neutral.clear();
    }

    void apply(CoreGeoBone chest, CoreGeoBone arms, CoreGeoBone head, CoreGeoBone leftShoulder,
               CoreGeoBone rightShoulder, float headYaw, float headPitch, boolean armed) {
        float yaw = boundedYaw(headYaw);
        float pitch = bounded(headPitch, 50.0F);
        capture(chest);
        capture(arms);
        capture(head);

        if (armed && chest != null && arms != null) {
            // The old fallback pitched the chest as well as the clip's UpBody/Arms. Keep the
            // chest upright; aiming up/down belongs at the actual shoulders of the new rig.
            chest.updateRotation(chest.getInitialSnapshot().getRotX(),
                    chest.getInitialSnapshot().getRotY(), chest.getInitialSnapshot().getRotZ());
            Quaternionf neutralParent = worldRotation(arms.getParent());
            Quaternionf neutralArms = rotation(arms);
            float torsoYaw = Math.max(-45.0F, Math.min(45.0F, yaw));
            chest.setRotY(chest.getInitialSnapshot().getRotY() + torsoYaw * RADIANS);

            if (leftShoulder != null && rightShoulder != null) {
                arms.updatePivot((leftShoulder.getPivotX() + rightShoulder.getPivotX()) * 0.5F,
                        (leftShoulder.getPivotY() + rightShoulder.getPivotY()) * 0.5F,
                        (leftShoulder.getPivotZ() + rightShoulder.getPivotZ()) * 0.5F);
            }
            // Move both arms and their palm-mounted equipment together. No item-only offset
            // separates the gun from its grip, and no shoulder pivot is moved independently.
            arms.setPosY(arms.getPosY() + CARRY_LIFT);
            Quaternionf aimed = new Quaternionf().rotateY(yaw * RADIANS)
                    .rotateX(pitch * RADIANS).mul(neutralParent).mul(neutralArms);
            setRotation(arms, worldRotation(arms.getParent()).invert().mul(aimed));
        }

        if (head != null) {
            // Resolve against the actual parent frame instead of adding another yaw/pitch to
            // the clip's nested axes. The face follows the look once, including during reload.
            Quaternionf desired = new Quaternionf().rotateY(yaw * RADIANS).rotateX(pitch * RADIANS);
            setRotation(head, worldRotation(head.getParent()).invert().mul(desired));
        }
    }

    private void capture(CoreGeoBone bone) {
        if (bone != null) {
            this.neutral.add(new Frame(bone, bone.getRotX(), bone.getRotY(), bone.getRotZ(),
                    bone.getPosX(), bone.getPosY(), bone.getPosZ(), bone.getPivotX(),
                    bone.getPivotY(), bone.getPivotZ()));
        }
    }

    private static float bounded(float value, float limit) {
        return Float.isFinite(value) ? Math.max(-limit, Math.min(limit, value)) : 0.0F;
    }

    private static float boundedYaw(float value) {
        if (!Float.isFinite(value)) {
            return 0.0F;
        }
        return bounded((float) Math.IEEEremainder(value, 360.0D), 70.0F);
    }

    private static Quaternionf rotation(CoreGeoBone bone) {
        // Matches GeckoLib RenderUtils.rotateMatrixAroundBone: Rz, then Ry, then Rx.
        return new Quaternionf().rotationZYX(bone.getRotZ(), bone.getRotY(), bone.getRotX());
    }

    private static Quaternionf worldRotation(CoreGeoBone bone) {
        if (bone == null) {
            return new Quaternionf();
        }
        return worldRotation(bone.getParent()).mul(rotation(bone));
    }

    private static void setRotation(CoreGeoBone bone, Quaternionf rotation) {
        // JOML 1.10.5's getEulerAnglesZYX uses the wrong X denominator when Y is non-zero.
        // Extract the same Rz * Ry * Rx convention from the matrix, including its singular case.
        Matrix3f matrix = new Matrix3f().rotation(rotation.normalize());
        float y = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -matrix.m02())));
        float x;
        float z;
        if (Math.abs(Math.cos(y)) < 1.0E-6D) {
            x = 0.0F;
            z = (float) Math.atan2(-matrix.m10(), matrix.m11());
        } else {
            x = (float) Math.atan2(matrix.m12(), matrix.m22());
            z = (float) Math.atan2(matrix.m01(), matrix.m00());
        }
        bone.updateRotation(x, y, z);
    }

    private record Frame(CoreGeoBone bone, float x, float y, float z, float posX, float posY,
                         float posZ, float pivotX, float pivotY, float pivotZ) {
        void restore() {
            this.bone.updateRotation(this.x, this.y, this.z);
            this.bone.updatePosition(this.posX, this.posY, this.posZ);
            this.bone.updatePivot(this.pivotX, this.pivotY, this.pivotZ);
            // These writes restore a baseline; they must not masquerade as a controller update
            // and prevent GeckoLib from resetting an untracked bone for the next entity.
            this.bone.resetStateChanges();
        }
    }
}
