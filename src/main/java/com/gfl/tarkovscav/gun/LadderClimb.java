package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Everything about ladder climbing that needs the game (README 7o, design plan A): the real
 * {@link LadderSearch.Probe}, the movement primitives the goal uses, and the shaft fall-damage rule.
 *
 * <h2>What vanilla already does, measured</h2>
 * <p>Read from the mapped 1.20.1 jar with {@code javap} while implementing this, because the movement
 * contract depends on it:</p>
 * <ul>
 *   <li>{@code LivingEntity#onClimbable()} is true when the <em>feet</em> block is in
 *       {@code BlockTags.CLIMBABLE} (through {@code ForgeHooks.isLivingOnLadder}), so a mob standing in a
 *       floor-plate hole has it false for that one tick even though the rung is one block above;</li>
 *   <li>{@code LivingEntity#handleOnClimbable(Vec3)} - reached from {@code travel} - calls
 *       {@code resetFallDistance()} and clamps x and z to [-0.15, 0.15] while {@code onClimbable()}. The
 *       <b>upward</b> delta is not clamped (only y is floored at -0.15), which is why a per-tick
 *       {@code setDeltaMovement(0, climbSpeed, 0)} from a goal produces an exact climb;</li>
 *   <li>{@code travel} applies gravity to the <em>result</em> delta after the move, and the AI's tick runs
 *       before {@code travel} - so re-asserting the delta every tick wins, which is exactly how
 *       {@code LocalPlayer} climbs a ladder.</li>
 * </ul>
 *
 * <h2>Why the movement is ours and not the navigator's</h2>
 * <p>{@code PathNavigation} cannot express "up the rungs": it also re-issues horizontal movement every
 * repath, which fights a climb. So while on the rungs the goal stops the navigation, zeroes the movement
 * input and writes the delta itself ({@link #climbTick}). Handing control back is the goal's decision, and
 * it only happens once the unit is standing on a real floor.</p>
 */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID)
public final class LadderClimb {
    private LadderClimb() {
    }

    // ------------------------------------------------------------------ positions

    /** The block the mob's feet are in. The 0.05 lift keeps a feet-at-2.0 mob in y=2, not y=1. */
    public static BlockPos feet(Entity entity) {
        return BlockPos.containing(entity.getX(), entity.getY() + 0.05D, entity.getZ());
    }

    /** The feet block's Y, the number every shaft decision is expressed in. */
    public static int feetY(Entity entity) {
        return Mth.floor(entity.getY() + 0.05D);
    }

    /**
     * The yaw that faces the block {@code (x, *, z)} from where the entity is now. Computed <b>before</b>
     * the unit is moved into the column, because from inside the column the vector is zero.
     */
    public static float facingYaw(Entity entity, int x, int z) {
        double dx = x + 0.5D - entity.getX();
        double dz = z + 0.5D - entity.getZ();
        return (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
    }

    /**
     * True while the unit is holding the rungs. Deliberately looser than {@code onClimbable()}: the
     * generator punches a one-block hole through every floor plate, and in that cell the feet block is air
     * even though the unit is on the ladder. Checking one block above/below the feet as well keeps the
     * climb from being dropped in the middle of a storey - and it never widens the test beyond the column,
     * so a unit standing in the room is not "on the rungs".
     */
    public static boolean onRungs(Mob mob) {
        if (mob.onClimbable()) {
            return true;
        }
        BlockPos feet = feet(mob);
        Level level = mob.level();
        for (int dy = -1; dy <= 1; dy += 2) {
            if (climbable(level, feet.offset(0, dy, 0))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the movement primitives

    /**
     * One tick of climbing: no navigation, no movement input, an exact vertical delta, and the yaw held
     * towards the ladder. Called from the goal every tick while on the rungs and while landing.
     *
     * <p>The horizontal delta is a small pull back towards the centre of the ladder column rather than a
     * hard zero. Zero would be right in a world where nothing else writes movement, but a villager is a
     * Brain mob: its {@code MoveToTargetSink} can create a walk target, and the brain is only parked while
     * the unit has an order or a target ({@code GunnerVillagerEntity#customServerAiStep}). A pull that is
     * clamped to vanilla's own on-ladder limit (±0.15, see {@code LivingEntity#handleOnClimbable}) cancels
     * any such drift instead of letting 30 ticks of climb smear the unit off the rungs.</p>
     */
    public static void climbTick(Mob mob, double vertical, float yaw, int columnX, int columnZ) {
        mob.getNavigation().stop();
        neutraliseMoveControl(mob);
        mob.setSprinting(false);
        double pullX = Mth.clamp((columnX + 0.5D - mob.getX()) * 0.25D, -0.15D, 0.15D);
        double pullZ = Mth.clamp((columnZ + 0.5D - mob.getZ()) * 0.25D, -0.15D, 0.15D);
        mob.setDeltaMovement(pullX, vertical, pullZ);
        faceLadder(mob, yaw);
    }

    /** Holds the whole body on the ladder's yaw, so the climb reads as a climb and not a sideways drift. */
    public static void faceLadder(Mob mob, float yaw) {
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
        mob.yHeadRotO = yaw;
    }

    /**
     * Moves the unit one block sideways into a cell - onto the rungs from the foot, or out of the shaft
     * into the destination opening. It is an exact placement rather than a navigation call on purpose:
     * both cells are adjacent to a floor hole, so a mob that "walks" there has nothing to stand on for the
     * one tick in between and simply falls. The move is at most one block.
     */
    public static void moveIntoCell(Mob mob, int x, int y, int z, float yaw) {
        mob.getNavigation().stop();
        neutraliseMoveControl(mob);
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
        mob.moveTo(x + 0.5D, y, z + 0.5D, yaw, 0.0F);
        mob.fallDistance = 0.0F;
        faceLadder(mob, yaw);
    }

    /**
     * Stops {@code MoveControl} from writing movement input during a climb. Vanilla has no public "wait"
     * switch on {@code MoveControl} in 1.20.1 (checked with {@code javap}: the class exposes
     * {@code setWantedPosition}, {@code strafe}, {@code hasWanted}, {@code tick} - no {@code setWait}), so
     * the goal is pointed at the mob's own position with speed 0. {@code MoveControl#tick} then sees a
     * zero-length vector, writes {@code zza = 0} and returns without touching the yaw or the speed.
     *
     * <p>The movement input has to be zeroed as well as the delta: {@code travel} adds the input to the
     * delta inside {@code moveRelative}, so a stale forward input would push the unit off the rungs.</p>
     */
    private static void neutraliseMoveControl(Mob mob) {
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
        mob.setSpeed(0.0F);
        mob.xxa = 0.0F;
        mob.yya = 0.0F;
        mob.zza = 0.0F;
    }

    // ------------------------------------------------------------------ the real Level probe

    /** The {@link LadderSearch.Probe} the goal scans the world with. Allocates one tiny adapter. */
    public static LadderSearch.Probe probe(Level level) {
        return new LevelProbe(level);
    }

    private record LevelProbe(Level level) implements LadderSearch.Probe {
        @Override
        public boolean climbable(int x, int y, int z) {
            return LadderClimb.climbable(this.level, new BlockPos(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            BlockState state = this.level.getBlockState(new BlockPos(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            return state.getCollisionShape(this.level, new BlockPos(x, y, z)).isEmpty();
        }

        @Override
        public boolean solidTop(int x, int y, int z) {
            BlockState state = this.level.getBlockState(new BlockPos(x, y, z));
            return !state.getCollisionShape(this.level, new BlockPos(x, y, z)).isEmpty();
        }
    }

    private static boolean climbable(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    // ------------------------------------------------------------------ the shaft fall rule

    /**
     * True when the position is inside a ladder column: the feet block itself or anything within two blocks
     * of it in the same column is climbable. Two blocks covers the floor-plate hole plus the drop of a
     * rung or two, which is what "falling inside the shaft" means in practice.
     */
    public static boolean inShaft(Level level, BlockPos feet) {
        if (climbable(level, feet)) {
            return true;
        }
        for (int dy = 1; dy <= 2; dy++) {
            if (climbable(level, feet.offset(0, dy, 0)) || climbable(level, feet.offset(0, -dy, 0))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ladder.fallDamageInShaft}: slipping a rung or two onto the floor plate of a shaft is part of
     * climbing and reads as a bug when it costs health (the design doc calls it out), so by default the
     * fall is cancelled - but only for the nine armed units, and only when the landing really is inside a
     * ladder column. A unit knocked OUT of the shaft (no ladder under it any more) has
     * {@link #inShaft} false and takes normal fall damage, which is the documented half of the key.
     *
     * <p>This is a Forge {@code LivingFallEvent} subscriber in its own class on purpose: the feature owns
     * no shared setup file, so the registration lives here rather than in {@code TarkovScav}.</p>
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        // The key is "take the damage": nothing to do then, and a cancelled event must never be a
        // side effect of a config read.
        if (!Config.SPEC.isLoaded() || Config.LADDER_FALL_DAMAGE_IN_SHAFT.get()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide || !(entity instanceof GunUser)) {
            return;
        }
        if (inShaft(entity.level(), feet(entity))) {
            event.setCanceled(true);
            // Not silent: the one line that makes "it fell five blocks down the shaft and took nothing"
            // explainable in the log instead of looking like a bug.
            if (Config.LOG_GUN_AI.get()) {
                TarkovScav.LOGGER.info("[ladder] {} fell {} blocks inside a ladder shaft - damage cancelled"
                                + " (ladder.fallDamageInShaft=false)",
                        entity.getName().getString(), String.format(java.util.Locale.ROOT, "%.1f",
                                event.getDistance()));
            }
        }
    }

    /**
     * This class is its own Forge event subscriber ({@code @Mod.EventBusSubscriber} above), so the fall
     * rule needs no line in any shared setup file.
     */
}
