package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Attaches items to animated hand locators and draws them after the body has finished its buffer.
 * The rig locator is already at the palm. TaCZ's THIRD_PERSON_RIGHT_HAND renderer positions each
 * gun by its own thirdperson_hand group, so no per-gun shoulder offset belongs in this layer.
 * Anchor resolution is repeated after a model rebake or configuration reload.
 */
public class GunInHandGeoLayer<T extends Entity & GeoAnimatable> extends BlockAndItemGeoLayer<T> {
    /**
     * The anchor candidates after the configured name: the scav rig's in-hand locator, then the
     * placeholder rifle bone, then the hand itself. Ordered from "carries the authored pose" to
     * "better than nothing".
     */
    private static final List<String> ANCHOR_FALLBACKS = List.of(
            RigSupport.DEFAULT_GUN_ANCHOR, RigSupport.PLACEHOLDER_RIFLE_BONE, "RightHand");

    /**
     * The offhand candidates after the configured name: the rig's own left locator, then the left hand.
     * Same idea as {@link #ANCHOR_FALLBACKS}, and the same "never silently empty-handed" rule.
     */
    private static final List<String> OFFHAND_FALLBACKS = List.of(
            RigSupport.DEFAULT_OFFHAND_ANCHOR, "LeftHand");

    /** Bones whose mount decision has been logged, with the config generation it was logged for. */
    private static final java.util.Map<String, Integer> LOGGED = new java.util.HashMap<>();

    /** Context warnings already emitted, cleared when the config is re-read in game. */
    private static final Set<String> WARNED_CONTEXTS = new HashSet<>();

    /** Lets {@code /tarkovscav client reload} get the mount line printed again with the new values. */
    static void forgetLogGuards() {
        LOGGED.clear();
        WARNED_CONTEXTS.clear();
    }

    /** How many bone names a "the gun will not be displayed" warning lists before it truncates. */
    private static final int MAX_BONES_IN_WARNING = 24;

    /** The baked model the anchor below was resolved against; a rebake replaces the instance. */
    private BakedGeoModel anchorResolvedOn;

    /** The config generation the anchors above were resolved for. */
    private int resolvedGeneration = -1;

    /** The bone of the current model the gun is mounted on, or null when the rig has none of them. */
    private String anchorBone = RigSupport.DEFAULT_GUN_ANCHOR;

    /** The bone of the current model the offhand item is mounted on, or null when the rig has none. */
    private String offhandAnchorBone = RigSupport.DEFAULT_OFFHAND_ANCHOR;

    private final DeferredItemPass itemPass = new DeferredItemPass();

    public GunInHandGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** True when the item stack is a TaCZ gun. */
    private static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && com.tacz.guns.api.item.IGun.getIGunOrNull(stack) != null;
    }

    /**
     * Picks the anchor bone for the current bake, before the model's bones are drawn.
     *
     * <p>{@code preRender} is used rather than {@code renderForBone} because this runs once, before
     * the per-bone pass, and because GeckoLib hands it the {@link BakedGeoModel} that is being drawn -
     * which is the object identity the "have I resolved this model yet" guard needs.</p>
     */
    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                          int packedLight, int packedOverlay) {
        this.itemPass.clear();
        // One call per geometry pass, so this is where the "is the rig submitted twice?" counter lives.
        RenderStats.onGeometryPass(animatable, animatable.level() == null ? 0L : animatable.level().getGameTime());
        if (bakedModel != this.anchorResolvedOn || this.resolvedGeneration != RigSupport.configGeneration()) {
            this.anchorResolvedOn = bakedModel;
            this.resolvedGeneration = RigSupport.configGeneration();
            this.anchorBone = resolveAnchor(animatable, bakedModel);
            this.offhandAnchorBone = resolveOffhandAnchor(animatable, bakedModel);
        }
    }

    /**
     * Capture the animated hand frame without drawing into GeckoLib's active model buffer.
     * TaCZ can end buffers while drawing. Resuming the original consumer after that causes
     * "BufferBuilder not started" with some shader/outline buffer wrappers.
     */
    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        ItemStack stack = getStackForBone(bone, animatable);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        try {
            // renderRecursively has already applied this bone's rotation and scale.
            // Return to its pivot without multiplying its animated rotation a second time.
            RenderUtils.translateToPivotPoint(poseStack, bone);
            boolean offhand = this.offhandAnchorBone != null && bone.getName().equals(this.offhandAnchorBone);
            if (Config.normalisedHandMode() || (!offhand && !isGun(stack))) {
                if (isGun(stack)) {
                    applyPalmGunFrame(poseStack);
                } else {
                    applyVanillaHandFrame(poseStack, offhand);
                }
            }
            this.itemPass.add(poseStack, captured -> renderStackForBone(captured, bone, stack, animatable,
                    bufferSource, partialTick, packedLight, packedOverlay));
        } finally {
            poseStack.popPose();
        }
    }

    /** GeckoLib invokes this after all body bones have submitted their vertices. */
    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        this.itemPass.render();
    }

    /**
     * Walks the candidate chain and returns the first bone the rig really has, or null if it has none
     * of them. Logs the decision once per call, i.e. once per bake or config reload.
     *
     * <p>The explicit config value is always the FIRST candidate, so a rig that has the bone configured
     * can never be talked out of it by a fallback that happens to come earlier in the list; a fallback
     * only happens when the configured name is not in the rig, and then it says so in the log.</p>
     */
    @Nullable
    private String resolveAnchor(T animatable, BakedGeoModel bakedModel) {
        return resolveBone(animatable, bakedModel,
                Config.SPEC.isLoaded() ? Config.GUN_ANCHOR_BONE.get() : RigSupport.DEFAULT_GUN_ANCHOR,
                ANCHOR_FALLBACKS, "gunAnchorBone", "the held gun");
    }

    @Nullable
    private String resolveOffhandAnchor(T animatable, BakedGeoModel bakedModel) {
        if (Config.SPEC.isLoaded() && !Config.RENDER_OFFHAND_ITEM.get()
                && !Config.GUN_TWO_HANDED_SUPPORT.get()) {
            // Nothing will be drawn on it, so there is nothing to resolve or warn about.
            return null;
        }
        return resolveBone(animatable, bakedModel, Config.gunOffhandAnchorBone(), OFFHAND_FALLBACKS,
                "gunOffhandAnchorBone", "the offhand item");
    }

    @Nullable
    private String resolveBone(T animatable, BakedGeoModel bakedModel, String configured,
                               List<String> fallbacks, String configKey, String what) {
        List<String> candidates = new ArrayList<>(fallbacks.size() + 1);
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured.trim());
        }
        for (String fallback : fallbacks) {
            if (candidates.stream().noneMatch(existing -> existing.equalsIgnoreCase(fallback))) {
                candidates.add(fallback);
            }
        }

        List<String> present = boneNames(bakedModel);
        String chosen = null;
        for (String candidate : candidates) {
            // Match case-insensitively but keep the rig's own spelling: the config is typed by hand,
            // Bedrock bone names are not.
            String actual = present.stream()
                    .filter(name -> name.equalsIgnoreCase(candidate))
                    .findFirst()
                    .orElse(null);
            if (actual != null) {
                chosen = actual;
                break;
            }
        }

        if (chosen == null) {
            TarkovScav.LOGGER.warn("[gunmount] {}: none of the {} bones [{}] exists in this rig, so {}"
                            + " will NOT be displayed. This rig has {} bone(s): {}",
                    animatable.getType().toShortString(), configKey, String.join(" ", candidates), what,
                    present.size(), summarise(present));
            return null;
        }

        if (Config.LOG_GUN_MOUNT.get()) {
            TarkovScav.LOGGER.info("[gunmount] {}: mounting {} on '{}' (mode {})",
                    animatable.getType().toShortString(), what, chosen,
                    Config.normalisedHandMode() ? "normalisedHand" : "locatorAnimated");
            if (!chosen.equalsIgnoreCase(configured == null ? "" : configured.trim())) {
                TarkovScav.LOGGER.info("[gunmount] {}: '{}' from client.{} is not in this rig,"
                                + " falling back to '{}' (chain: {})",
                        animatable.getType().toShortString(), configured, configKey, chosen,
                        String.join(" -> ", candidates));
            }
        }
        return chosen;
    }
    /** Every bone name in the model, walking the whole tree - {@code getBones()} is top level only. */
    private static List<String> boneNames(BakedGeoModel bakedModel) {
        List<String> names = new ArrayList<>();
        for (CoreGeoBone topLevel : bakedModel.getBones()) {
            collectNames(topLevel, names);
        }
        return names;
    }

    private static void collectNames(CoreGeoBone bone, List<String> into) {
        into.add(bone.getName());
        for (CoreGeoBone child : bone.getChildBones()) {
            collectNames(child, into);
        }
    }

    /** A bounded bone-name list, so a rig with hundreds of bones does not flood the log. */
    private static String summarise(List<String> names) {
        if (names.size() <= MAX_BONES_IN_WARNING) {
            return names.toString();
        }
        return names.subList(0, MAX_BONES_IN_WARNING) + " (+" + (names.size() - MAX_BONES_IN_WARNING) + " more)";
    }

    @Nullable
    @Override
    protected ItemStack getStackForBone(GeoBone bone, T animatable) {
        if (!(animatable instanceof LivingEntity living)) {
            return null;
        }
        // The names were resolved against this bake in preRender; they are the rig's own spelling, so
        // exact comparisons are right here (the case-insensitive part happened during resolution).
        if (this.anchorBone != null && bone.getName().equals(this.anchorBone)) {
            ItemStack held = living.getMainHandItem();
            logMountDecision(bone, animatable, false, held);
            // Weapon racks also equip bows, crossbows and melee weapons on Gecko-rendered pillagers.
            return held.isEmpty() ? null : held;
        }
        if (this.offhandAnchorBone != null && bone.getName().equals(this.offhandAnchorBone)) {
            ItemStack offhand = living.getOffhandItem();
            logMountDecision(bone, animatable, true, offhand);
            if (!offhand.isEmpty() && Config.SPEC.isLoaded() && Config.RENDER_OFFHAND_ITEM.get()) {
                return offhand;
            }
            // No offhand item: the support copy of the main-hand gun, if that is switched on.
            if (Config.SPEC.isLoaded() && Config.GUN_TWO_HANDED_SUPPORT.get()) {
                ItemStack main = living.getMainHandItem();
                return isGun(main) ? main : null;
            }
            return null;
        }
        return null;
    }

    /** One log line per mob type per config generation, with the transform that will be applied. */
    private void logMountDecision(GeoBone bone, T animatable, boolean offhand, ItemStack stack) {
        if (!Config.LOG_GUN_MOUNT.get()) {
            return;
        }
        String key = animatable.getType().toShortString() + (offhand ? "|offhand" : "|main");
        if (LOGGED.getOrDefault(key, -1) == RigSupport.configGeneration()) {
            return;
        }
        LOGGED.put(key, RigSupport.configGeneration());
        boolean pistol = !offhand && animatable instanceof com.gfl.tarkovscav.gun.GunUser user
                && user.usesPistolClips();
        float[] rotation = offhand ? null : mountRotation(pistol);
        float[] offset = offhand ? null : mountOffset(pistol);
        float[] offhandMount = Config.offhandMount();
        TarkovScav.LOGGER.info("[gunmount] {}: {} bone '{}' (parent {}) mode={} rot=[{},{},{}]"
                        + " offset=[{},{},{}] scale={} context={} stack={}",
                animatable.getType().toShortString(), offhand ? "offhand" : "held", bone.getName(),
                bone.getParent() == null ? "none" : bone.getParent().getName(),
                Config.normalisedHandMode() ? "normalisedHand" : "locatorAnimated",
                offhand ? offhandMount[0] : rotation[0], offhand ? offhandMount[1] : rotation[1],
                offhand ? offhandMount[2] : rotation[2],
                offhand ? offhandMount[3] : offset[0], offhand ? offhandMount[4] : offset[1],
                offhand ? offhandMount[5] : offset[2],
                offhand ? offhandMount[6] : mountScale(pistol),
                displayContext(offhand, stack), stack.isEmpty() ? "empty" : stack.getItem());
    }

    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, T animatable) {
        boolean offhand = this.offhandAnchorBone != null && bone.getName().equals(this.offhandAnchorBone);
        return displayContext(offhand, stack);
    }

    /**
     * The {@code ItemDisplayContext} the gun item is rendered with, from
     * {@code client.gunMountDisplayContext}.
     *
     * <p>This is not cosmetic. TaCZ renders a gun item through its own
     * {@code BlockEntityWithoutLevelRenderer}, and that renderer branches on this value (verified in
     * the 1.1.8 bytecode, {@code GunItemRendererWrapper#renderByItem}): it draws <b>nothing</b> for
     * {@code FIRST_PERSON_*_HAND} and {@code THIRD_PERSON_LEFT_HAND}, only the flat slot icon for
     * {@code GUI}, and for {@code FIXED} it applies an item-frame layout -
     * {@code translate(0.5, 2, 0.5)}, {@code scale(-1, -1, 1)} (a mirror flip), the model's
     * {@code fixed} positioning group and the {@code fixed} display scale, which is 1.2 in TaCZ's own
     * gun pack. {@code THIRD_PERSON_RIGHT_HAND} instead applies the model's third-person-hand
     * positioning group and the {@code thirdperson} display scale (0.6), with the same XY axis conversion.
     * The different locator and doubled fixed scale are unsuitable for a hand; the default here is the
     * same context vanilla's {@code ItemInHandLayer} uses for a mob's held item.</p>
     *
     * @param offhand true when the stack is being drawn on the left-hand anchor, which selects the
     *                {@code *_LEFT_HAND} variant of the context
     */
    private static ItemDisplayContext displayContext(boolean offhand, ItemStack stack) {
        if (!isGun(stack)) {
            return offhand ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        }
        ItemDisplayContext context;
        try {
            context = ItemDisplayContext.valueOf(Config.gunMountDisplayContext());
        } catch (IllegalArgumentException notAContext) {
            if (WARNED_CONTEXTS.add(String.valueOf(Config.gunMountDisplayContext()))) {
                TarkovScav.LOGGER.warn("[gunmount] client.gunMountDisplayContext = '{}' is not an"
                                + " ItemDisplayContext; using THIRD_PERSON_RIGHT_HAND",
                        Config.GUN_MOUNT_DISPLAY_CONTEXT.get());
            }
            context = ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        }

        boolean leftHand = offhand;
        if (leftHand && context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            context = ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        } else if (leftHand && context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            context = ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        }

        // TaCZ draws NOTHING for these contexts. Vanilla does render an ordinary item in the left hand,
        // so the fallback is only needed for a TaCZ gun: mirror it to a context TaCZ DOES draw and say so,
        // because the alternative is a mob holding an invisible weapon. Both imaginable right-hand targets
        // are not usable: FIRST_PERSON_RIGHT_HAND is itself in TaCZ's "draws nothing" set (the cantdraw
        // WARN below exists for exactly that), so the only right-hand context left is the third-person
        // one. That is the hardcoded mirror the older EdDYON/tarkovscav snapshot used; taking it changes
        // nothing on the default client.gunMountDisplayContext = THIRD_PERSON_RIGHT_HAND path, and only
        // the non-default FIRST_PERSON_* setting stops producing an invisible gun.
        if (isGun(stack) && (context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)) {
            ItemDisplayContext mirrored = ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            if (WARNED_CONTEXTS.add("mirrored:" + context)) {
                TarkovScav.LOGGER.warn("[gunmount] TaCZ's gun renderer draws NOTHING for {}, so a TaCZ gun"
                        + " in that hand was mirrored to {} instead - the gun is visible, but its own"
                        + " left/right positioning group is the right-hand one. Use"
                        + " client.gunMountDisplayContext = THIRD_PERSON_RIGHT_HAND to silence this.",
                        context, mirrored);
            }
            return mirrored;
        }
        if (context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            if (WARNED_CONTEXTS.add("cantdraw:" + context)) {
                TarkovScav.LOGGER.warn("[gunmount] TaCZ's gun renderer draws NOTHING for the display"
                        + " context {}, so a held gun would be invisible. Use"
                        + " THIRD_PERSON_RIGHT_HAND (the default) instead.", context);
            }
        }
        return context;
    }

    /** Context names already warned about, so a per-frame call cannot flood the log. */
    private static boolean pistolFamily(Entity animatable) {
        return animatable instanceof com.gfl.tarkovscav.gun.GunUser user && user.usesPistolClips();
    }

    private static float[] mountRotation(boolean pistol) {
        if (!Config.SPEC.isLoaded()) {
            return new float[]{0.0F, 0.0F, 0.0F};
        }
        return pistol
                ? Config.triple(Config.GUN_MOUNT_PISTOL_ROTATION.get(), 0.0F, 0.0F, 0.0F)
                : Config.triple(Config.GUN_MOUNT_RIFLE_ROTATION.get(), 0.0F, 0.0F, 0.0F);
    }

    private static float[] mountOffset(boolean pistol) {
        if (!Config.SPEC.isLoaded()) {
            return new float[]{0.0F, 0.0F, 0.0F};
        }
        return pistol
                ? Config.triple(Config.GUN_MOUNT_PISTOL_OFFSET.get(), 0.0F, 0.0F, 0.0F)
                : Config.triple(Config.GUN_MOUNT_RIFLE_OFFSET.get(), 0.0F, 0.0F, 0.0F);
    }

    private static float mountScale(boolean pistol) {
        if (!Config.SPEC.isLoaded()) {
            return 1.0F;
        }
        return (pistol ? Config.GUN_MOUNT_PISTOL_SCALE.get() : Config.GUN_MOUNT_RIFLE_SCALE.get()).floatValue();
    }

    @Override
    protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T animatable,
                                      MultiBufferSource bufferSource, float partialTick, int packedLight,
                                      int packedOverlay) {
        boolean offhand = this.offhandAnchorBone != null && bone.getName().equals(this.offhandAnchorBone);
        boolean normalised = Config.normalisedHandMode();

        // The animated anchor/hand frame was captured during traversal. Apply only the extra mount
        // transform here; another render layer may have changed the shared bone rotations by now.
        float[] rotation;
        float[] offset;
        float scale;
        if (offhand) {
            float[] mount = Config.offhandMount();
            rotation = new float[]{mount[0], mount[1], mount[2]};
            offset = new float[]{mount[3], mount[4], mount[5]};
            scale = mount[6];
        } else if (isGun(stack)) {
            boolean pistol = pistolFamily(animatable);
            rotation = mountRotation(pistol);
            offset = mountOffset(pistol);
            scale = mountScale(pistol);
        } else {
            rotation = new float[]{0, 0, 0};
            offset = new float[]{0, 0, 0};
            scale = 1.0F;
        }

        poseStack.pushPose();
        try {
            if (rotation[0] != 0.0F) {
                poseStack.mulPose(Axis.XP.rotationDegrees(rotation[0]));
            }
            if (rotation[1] != 0.0F) {
                poseStack.mulPose(Axis.YP.rotationDegrees(rotation[1]));
            }
            if (rotation[2] != 0.0F) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation[2]));
            }
            if (offset[0] != 0.0F || offset[1] != 0.0F || offset[2] != 0.0F) {
                poseStack.translate(offset[0], offset[1], offset[2]);
            }
            if (scale != 1.0F) {
                poseStack.scale(scale, scale, scale);
            }

            // The body has finished submitting vertices. Guard the foreign draw and restore its GL
            // state before another deferred item or entity starts; do not resume the old model buffer.
            if (Config.logGlState()) {
                TarkovScav.LOGGER.info(RenderStateGuard.describe("before item draw (" + bone.getName() + ")"));
            }
            RenderStateGuard guard = RenderStateGuard.snapshot("item draw (" + bone.getName() + ")");
            try {
                super.renderStackForBone(poseStack, bone, stack, animatable, bufferSource, partialTick,
                        packedLight, packedOverlay);
            } finally {
                guard.restore();
                RenderStateGuard.rebindTexture(this.renderer.getTextureLocation(animatable));
                RenderStateGuard.forceAlwaysPassStencil();
            }
            if (Config.logGlState()) {
                TarkovScav.LOGGER.info(RenderStateGuard.describe("after item draw (" + bone.getName() + ")"));
            }
        } finally {
            poseStack.popPose();
        }

        // 3. the optional two-handed support copy of the main-hand gun, on the offhand anchor and only
        //    when the offhand itself is not carrying something (otherwise it would be drawn under it).
        if (offhand && Config.SPEC.isLoaded() && Config.GUN_TWO_HANDED_SUPPORT.get()
                && animatable instanceof LivingEntity living) {
            ItemStack main = living.getMainHandItem();
            if (living.getOffhandItem().isEmpty() && isGun(main) && main != stack) {
                poseStack.pushPose();
                try {
                    if (normalised) {
                        // The bone rotation was already cancelled and the hand frame already applied
                        // before step 2, so only the extra transform has to be repeated here.
                        float[] mount = Config.offhandMount();
                        if (mount[0] != 0.0F) {
                            poseStack.mulPose(Axis.XP.rotationDegrees(mount[0]));
                        }
                        if (mount[1] != 0.0F) {
                            poseStack.mulPose(Axis.YP.rotationDegrees(mount[1]));
                        }
                        if (mount[2] != 0.0F) {
                            poseStack.mulPose(Axis.ZP.rotationDegrees(mount[2]));
                        }
                    }
                    // The same guard for the support copy: it is the same foreign renderer (README 5w).
                    RenderStateGuard supportGuard = RenderStateGuard.snapshot("offhand item draw ("
                            + bone.getName() + ")");
                    try {
                        super.renderStackForBone(poseStack, bone, main, animatable, bufferSource, partialTick,
                                packedLight, packedOverlay);
                    } finally {
                        supportGuard.restore();
                        RenderStateGuard.rebindTexture(this.renderer.getTextureLocation(animatable));
                        RenderStateGuard.forceAlwaysPassStencil();
                    }
                } finally {
                    poseStack.popPose();
                }
            }
        }
    }

    /**
     * Gecko's palm locator uses +Y along the hand; the gunpack uses -Z along the barrel.
     * Rx(-90) maps the gun forward along -Y of the locator. Vanilla's extra Ry(180) would
     * reverse it, and its shoulder-to-hand translation would move it away from the palm.
     */
    private static void applyPalmGunFrame(PoseStack poseStack) {
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
    }

    /** Standard frame for ordinary items, retained independently from the TaCZ palm mount. */
    private static void applyVanillaHandFrame(PoseStack poseStack, boolean leftHand) {
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate(leftHand ? -1.0F / 16.0F : 1.0F / 16.0F, 0.125F, -0.625F);
    }
}
