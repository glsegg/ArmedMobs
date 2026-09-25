package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Doors for every armed unit: walk through a wooden door, then shut it behind you (README 5a).
 *
 * <p><b>Why this class exists at all.</b> {@link GunUser#allowDoors} only loosens the
 * <em>pathfinder</em> ({@code GroundPathNavigation#setCanOpenDoors}). The physical open is done by a
 * vanilla goal, and of our nine armed types exactly none of them has one to rely on:</p>
 * <ul>
 *   <li>{@code OpenDoorGoal} is registered by {@code Vindicator} alone ({@code
 *       AbstractIllager$RaiderOpenDoorGoal}); {@code Raider} and {@code Pillager} reference no door goal
 *       at all (checked with javap against the mapped 1.20.1 jar), so {@code ScavEntity} (a plain
 *       {@code Monster}) and the whole pillager family path through a closed wooden door and then stand
 *       against it - the "steps in place at a doorway" report {@code GunUser#allowDoors} was meant to
 *       fix;</li>
 *   <li>the villager family ({@code GunnerVillagerEntity} and its three subclasses) does have the
 *       vanilla brain behaviour {@code InteractWithDoor}, but {@code
 *       GunnerVillagerEntity#customServerAiStep} parks that brain for as long as the mob has a target
 *       (it would fight the {@code GunAttackGoal} for the walk target), i.e. exactly while it is
 *       advancing through a building.</li>
 * </ul>
 * <p>And <b>nothing in vanilla closes a door after passing</b> except that same parked villager brain
 * behaviour ({@code InteractWithDoor#closeDoorsThatIHaveOpenedOrPassedThrough}). The only goal-based
 * vanilla door opener, {@code AbstractIllager$RaiderOpenDoorGoal}, is built with {@code closeDoor =
 * false} (the bytecode pushes {@code iconst_0}), so it opens for a tick and then stops. A unit that
 * walks through a building therefore leaves every door open behind it, which is the reported
 * behaviour.</p>
 *
 * <p><b>How the two halves of the rule are implemented.</b> Both directions are done here, for every
 * {@link GunUser}, with the same rule vanilla uses for the open:</p>
 * <ol>
 *   <li><b>Open</b> - when the unit is pressed against something ({@code
 *       horizontalCollision}), its ground navigation may open doors, and its current {@code Path} has
 *       a node on a closed wooden door within 2 nodes of the next node. That is
 *       {@code DoorInteractGoal#canUse} copied, so we only ever open the door the unit was already
 *       pathing through. Iron doors are never touched: {@code DoorBlock#isWoodenDoor} is
 *       {@code type().canOpenByHand()}, which is false for {@code BlockSetType.IRON} and for the
 *       stone/blackstone/gold set types;</li>
 *   <li><b>Remember</b> - the lower half of every door <em>we</em> opened, with the game time, at most
 *       {@link #MAX_REMEMBERED_DOORS}. A door that was already open (a player left it open) is not
 *       ours and is never touched, which is also what makes an open/close loop impossible;</li>
 *   <li><b>Close</b> - once the unit is clear of the doorway and no living entity is near either half
 *       (see {@link #shouldClose}), the door goes back to {@code OPEN = false} with the matched
 *       {@code BlockSetType#doorClose} sound, and is forgotten. So each door is opened and closed at
 *       most once per unit.</li>
 * </ol>
 *
 * <p><b>Two drivers, one tick.</b> {@link GunBrain#tick} calls {@link #tick(ServerLevel)}, but that
 * method only runs while {@code GunAttackGoal} is active - a mob with no target (or with no gun)
 * never reaches it, so an idle villager that strolled through a door would keep it open for ever.
 * The {@link #onLivingTick} handler is therefore registered on the Forge event bus and ticks the same
 * object for every {@code GunUser} in the world, every tick (Forge fires it from the top of
 * {@code LivingEntity#tick}, i.e. before that tick's movement, which is what an open needs).
 * {@link #lastTick} - compared against the level's game time, not the entity's tick counter, which is
 * still one behind at that point - makes the second call a no-op, so a mob that is fighting is not
 * ticked twice.</p>
 */
public final class DoorBehavior {
    /**
     * How many doors one unit may remember at once. A unit normally has zero or one pending door; the
     * bound only exists for the pathological case (a path that keeps changing between doorways) and is
     * deliberately small: if it is ever reached, the oldest door is forgotten and simply stays open,
     * which is the safe failure.
     */
    private static final int MAX_REMEMBERED_DOORS = 8;

    /**
     * Vanilla's own door interaction range, from {@code DoorInteractGoal#canUse}: a path node counts as
     * "this door" when {@code distanceToSqr <= 2.25}, i.e. 1.5 blocks. Copied so our open fires exactly
     * when the vanilla goal would have fired.
     */
    private static final double VANILLA_DOOR_REACH_SQR = 2.25D;

    private final Mob mob;
    /** Doors this unit opened and has not closed yet, oldest first. */
    private final List<OpenedDoor> opened = new ArrayList<>();
    /** The game time this behaviour last ran, so the two drivers cannot tick it twice in one tick. */
    private long lastTick = Long.MIN_VALUE;

    /** One door we opened: the <b>lower</b> half, and the game time we opened it at. */
    private record OpenedDoor(BlockPos pos, long openedAt) {
    }

    public DoorBehavior(Mob mob) {
        this.mob = mob;
    }

    /** How many doors this unit has opened and not closed yet - logged by the no-progress watchdog. */
    public int pending() {
        return this.opened.size();
    }

    /**
     * The every-tick driver. {@code GunBrain#tick} is only called from {@code GunAttackGoal}, which is
     * inactive whenever the mob has no target, so the brain alone is not enough; this handler covers
     * every {@code GunUser} in every server level, including future unit types, with no per-entity
     * code. {@link #lastTick} keeps the two drivers from doing the work twice in one tick.
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof GunUser user
                && event.getEntity().level() instanceof ServerLevel level
                // A dead or already-removed unit is skipped here as well as inside tick(): this handler
                // runs for every armed unit in the world every tick, so the guard has to be in front of
                // the brain lookup, not only in front of the work it does.
                && !event.getEntity().isRemoved()
                && !event.getEntity().isDeadOrDying()) {
            user.gunBrain().doors().tick(level);
        }
    }

    /** One tick of open-then-shut for this unit. Server side only, and idempotent within a tick. */
    public void tick(ServerLevel level) {
        // A corpse cannot walk through a door, and it must not keep paying for the question either: the
        // 20-tick death animation used to run this for every unit that died. Clearing the list is the
        // safe half - a door this unit opened stays open if it dies before closing it, which is what
        // vanilla leaves behind for a player who dies in a doorway.
        if (this.mob.isRemoved() || this.mob.isDeadOrDying()) {
            this.opened.clear();
            return;
        }
        long now = level.getGameTime();
        if (this.lastTick == now) {
            return;
        }
        this.lastTick = now;
        if (!Config.SPEC.isLoaded()) {
            return;
        }

        boolean closeBehind = Config.CLOSE_DOORS_BEHIND.get();
        if (!closeBehind) {
            // The switch is off: forget the pending doors (we will not close them) but keep opening
            // doors, which is the behaviour allowDoors already shipped.
            this.opened.clear();
        }
        openDoorInTheWay(level, now);
        if (closeBehind) {
            closeDoorsLeftOpen(level, now);
        }
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens the closed wooden door this unit is walking into. The conditions are
     * {@code DoorInteractGoal#canUse} verbatim: ground navigation that may open doors, a horizontal
     * collision, a path that is not finished, and a wooden door on a node within 2 of the next node.
     */
    private void openDoorInTheWay(ServerLevel level, long now) {
        if (!(this.mob.getNavigation() instanceof GroundPathNavigation ground) || !ground.canOpenDoors()) {
            return;
        }
        if (!this.mob.horizontalCollision) {
            return;
        }
        Path path = ground.getPath();
        if (path == null || path.isDone()) {
            return;
        }

        BlockPos pos = doorOnPath(level, path);
        if (pos == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        // The wooden test is repeated here even though doorOnPath already made it: this is the one
        // place that may change a block, and an iron door must be left alone even if the path lies.
        if (!DoorBlock.isWoodenDoor(state) || state.getValue(DoorBlock.OPEN)) {
            return;
        }

        setOpen(level, pos, state, true);
        log("{} opened the wooden door at {} (it was walking into it)", name(), fmt(pos));
        if (Config.CLOSE_DOORS_BEHIND.get()) {
            remember(lowerHalf(state, pos), now);
        }
    }

    /**
     * The door position vanilla would open, or null. The first pass is vanilla's path scan (it looks at
     * the node <em>above</em> each path node, which is the door's upper half); the fallback is the block
     * the mob's head is in, exactly as {@code DoorInteractGoal#canUse} ends.
     */
    private BlockPos doorOnPath(ServerLevel level, Path path) {
        int limit = Math.min(path.getNextNodeIndex() + 2, path.getNodeCount());
        for (int i = 0; i < limit; i++) {
            Node node = path.getNode(i);
            BlockPos above = new BlockPos(node.x, node.y + 1, node.z);
            if (this.mob.distanceToSqr(above.getX(), this.mob.getY(), above.getZ()) <= VANILLA_DOOR_REACH_SQR
                    && DoorBlock.isWoodenDoor(level, above)) {
                return above;
            }
        }
        BlockPos above = this.mob.blockPosition().above();
        return DoorBlock.isWoodenDoor(level, above) ? above : null;
    }

    private void remember(BlockPos lower, long now) {
        for (int i = 0; i < this.opened.size(); i++) {
            if (this.opened.get(i).pos().equals(lower)) {
                // Already ours: refresh the timestamp instead of adding a second entry for one door.
                this.opened.set(i, new OpenedDoor(lower, now));
                return;
            }
        }
        if (this.opened.size() >= MAX_REMEMBERED_DOORS) {
            this.opened.remove(0);
        }
        this.opened.add(new OpenedDoor(lower, now));
    }

    // ------------------------------------------------------------------ closing

    private void closeDoorsLeftOpen(ServerLevel level, long now) {
        double radius = Config.DOOR_CLOSE_ALLY_RADIUS.get();
        int delayTicks = Config.DOOR_CLOSE_DELAY_TICKS.get();

        for (int i = this.opened.size() - 1; i >= 0; i--) {
            OpenedDoor door = this.opened.get(i);
            BlockPos lower = door.pos();
            BlockState state = level.getBlockState(lower);
            // Not a door any more, no longer wooden, or already shut by somebody else: nothing to do.
            if (!DoorBlock.isWoodenDoor(state) || !state.getValue(DoorBlock.OPEN)) {
                this.opened.remove(i);
                continue;
            }
            BlockPos upper = otherHalf(state, lower);
            boolean occupied = doorwayOccupied(lower, upper);
            double distance = this.mob.position().distanceTo(Vec3.atCenterOf(lower));

            if (this.mob.horizontalCollision && distance <= radius) {
                // Still pushing at this door (a doorway is a chokepoint: a squadmate in front of it is
                // the normal case). Restart the delay instead of shutting the door in its own face -
                // this guard is what makes an open/close loop impossible, because the door can only be
                // closed once the unit is no longer walking into it.
                this.opened.set(i, new OpenedDoor(lower, now));
                continue;
            }

            int elapsed = (int) Math.min(Integer.MAX_VALUE, now - door.openedAt());
            boolean timing = elapsed >= delayTicks || distance > radius;
            // The entity query runs only when the cheap half of the rule already allows a close.
            boolean allyNearby = !occupied && timing && otherLivingWithin(level, lower, upper, radius);
            if (shouldClose(elapsed, delayTicks, distance, radius, allyNearby, occupied)) {
                setOpen(level, lower, state, false);
                log("{} closed the wooden door at {} ({} ticks after opening it, {} blocks away)",
                        name(), fmt(lower), elapsed, fmt(distance));
                this.opened.remove(i);
            }
        }
    }

    /**
     * The close decision, with every world lookup already done - pure, so it can be simulated
     * (tools/selftest_doors.js) and so there is exactly one place the rule lives.
     *
     * <p>Two safety rules come first, and they win over the timing:</p>
     * <ul>
     *   <li>{@code doorwayOccupied}: <b>this unit's</b> bounding box intersects one of the two door
     *       halves, i.e. it is standing in the doorway itself. Never close on it - the timing below may
     *       otherwise fire while it is still inside. (Any other body in the doorway is inside the radius
     *       below, so the second rule catches it.);</li>
     *   <li>{@code otherLivingNearby}: another living entity within {@code doorCloseAllyRadius} of
     *       either half. Never trap a mob (or a player) that is walking through, or about to.</li>
     * </ul>
     * <p>Only then the timing: the unit has been clear of the door for at least {@code delayTicks}, or
     * it is already further away than {@code clearRadius}. Whichever happens first, because the delay is
     * the backstop for a unit that stopped right next to the door, not a minimum hold time.</p>
     */
    static boolean shouldClose(int elapsedTicks, int delayTicks, double distanceToUnit, double clearRadius,
                               boolean otherLivingNearby, boolean doorwayOccupied) {
        if (doorwayOccupied || otherLivingNearby) {
            return false;
        }
        return elapsedTicks >= delayTicks || distanceToUnit > clearRadius;
    }

    /** True when this unit's own hitbox is standing in one of the two door blocks. */
    private boolean doorwayOccupied(BlockPos lower, BlockPos upper) {
        AABB box = this.mob.getBoundingBox();
        return box.intersects(new AABB(lower)) || box.intersects(new AABB(upper));
    }

    /**
     * True when a living entity other than this unit stands within {@code radius} of either half. The
     * test is vanilla's own, from {@code InteractWithDoor#areOtherMobsComingThroughDoor}:
     * {@code doorPos.closerToCenterThan(entity.position(), 2.0)}, i.e. the centre of the door block
     * against the entity's exact position.
     */
    private boolean otherLivingWithin(ServerLevel level, BlockPos lower, BlockPos upper, double radius) {
        AABB around = new AABB(lower, upper).inflate(radius);
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, around, LivingEntity::isAlive)) {
            if (other == this.mob) {
                continue;
            }
            if (lower.closerToCenterThan(other.position(), radius)
                    || upper.closerToCenterThan(other.position(), radius)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the block change

    /**
     * Writes {@code OPEN} and plays the matching door sound - the body of vanilla
     * {@code DoorBlock#setOpen} without the null check, because both callers already hold a wooden
     * {@code DoorBlock} state.
     *
     * <p>Both halves are written explicitly. {@code DoorBlock#setOpen} writes one half with flag 10 and
     * lets {@code DoorBlock#updateShape} copy {@code OPEN} to the other (that is how a player's click on
     * one half opens the whole door), but the second {@code setBlock} here makes the result independent
     * of that propagation: the test is {@code OPEN != open}, so it is a no-op when the update already
     * happened.</p>
     */
    private void setOpen(ServerLevel level, BlockPos pos, BlockState state, boolean open) {
        if (!(state.getBlock() instanceof DoorBlock door) || state.getValue(DoorBlock.OPEN) == open) {
            return;
        }
        level.setBlock(pos, state.setValue(DoorBlock.OPEN, open), 10);
        BlockPos other = otherHalf(state, pos);
        BlockState otherState = level.getBlockState(other);
        if (otherState.getBlock() == door && otherState.getValue(DoorBlock.OPEN) != open) {
            level.setBlock(other, otherState.setValue(DoorBlock.OPEN, open), 10);
        }
        SoundEvent sound = open ? door.type().doorOpen() : door.type().doorClose();
        level.playSound(this.mob, pos, sound, SoundSource.BLOCKS, 1.0F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
        // Vanilla also fires the game event, so a sculk sensor hears our door exactly like a player's.
        level.gameEvent(this.mob, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    private static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
    }

    private static BlockPos otherHalf(BlockState state, BlockPos pos) {
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
    }

    private String name() {
        return this.mob.getName().getString();
    }

    private static String fmt(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    /** Same switch as the rest of the gun AI, so one log line shows the doors next to the fight. */
    private void log(String format, Object... args) {
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[doors] " + format, args);
        }
    }
}
