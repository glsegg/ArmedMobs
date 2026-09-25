package com.gfl.tarkovscav.block;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Holds <b>exactly one</b> item stack (README 5n).
 *
 * <h2>Why "exactly one" is the whole design</h2>
 * <p>A single slot is what makes the accept/refuse/swap rules decidable, what makes the drop-on-break rule
 * provable (there is at most one stack, so "the item is either on the rack or on the ground, never both"),
 * and what makes "the AI cannot duplicate" checkable (the take path is one {@code remove(0,1)} that hands
 * its result to exactly one caller).</p>
 *
 * <h2>The two invariants this class owns</h2>
 * <ol>
 *   <li><b>NBT persistence</b>: {@link #saveAdditional} writes the stack with the full item tag (so a TaCZ
 *       gun keeps its attachments and its magazine), and {@link #load} reads it back. A chunk unload, a
 *       save and a server restart do not lose it.</li>
 *   <li><b>The item is never swallowed</b>: {@link #take()} and {@link #dropContents} are the only two ways
 *       the stack leaves, both clear the slot, and the block calls {@code dropContents} before the block
 *       entity is removed (see {@link WeaponRackBlock}).</li>
 *   <li><b>A creative rack is never emptied</b>: {@link #take()} stays the one consuming path, but callers
 *       that may be looking at a creative rack go through {@link #claim()}, which copies instead. That is why
 *       an endless rack can arm mob after mob while the template stays put.</li>
 * </ol>
 */
public class WeaponRackBlockEntity extends BlockEntity {
    private static final String TAG_ITEM = "Item";

    private ItemStack held = ItemStack.EMPTY;
    /** Server-side cooldown, in ticks, after a mob took something (see WeaponRackTaker). */
    private int takeCooldown;

    /**
     * True on the CREATIVE rack ({@code tarkovscav:creative_weapon_rack}): the slot is a template that is
     * never consumed, so {@link #claim()} hands out copies instead of emptying it. Set from the block
     * entity TYPE, never from NBT and never from a block state: the only way to get this behaviour is to
     * place the creative block, which is creative-inventory-only and has no recipe (README 5n).
     */
    private final boolean infinite;

    public WeaponRackBlockEntity(BlockPos pos, BlockState state) {
        this(pos, state, false);
    }

    public WeaponRackBlockEntity(BlockPos pos, BlockState state, boolean infinite) {
        super(infinite
                ? com.gfl.tarkovscav.registry.ModBlocks.CREATIVE_WEAPON_RACK_BE.get()
                : com.gfl.tarkovscav.registry.ModBlocks.WEAPON_RACK_BE.get(), pos, state);
        this.infinite = infinite;
    }

    /** True on the creative rack: the slot is a template and taking never consumes it. */
    public boolean infinite() {
        return this.infinite;
    }

    // ------------------------------------------------------------------ contents

    public ItemStack held() {
        return this.held;
    }

    public boolean isEmpty() {
        return this.held.isEmpty();
    }

    /** Puts a stack on the rack. The rack must be empty; the caller decides what to do otherwise. */
    public void put(ItemStack stack) {
        this.held = stack.copyWithCount(1);
        setChanged();
        sync();
    }

    /**
     * Takes the one stack off the rack and clears the slot in the same breath, so the caller cannot take it
     * twice and a second caller cannot take it at all. <b>This is the only consuming path</b> - every
     * caller that may be looking at a creative rack must go through {@link #claim()} instead.
     */
    public ItemStack take() {
        ItemStack out = this.held;
        this.held = ItemStack.EMPTY;
        setChanged();
        sync();
        return out;
    }

    /**
     * What a caller may take from this rack: the stack itself on a normal rack, a <b>copy</b> on a creative
     * one (the template stays, so the same block can arm mob after mob, and a player can take copy after
     * copy). Both the mob taker and the player interaction use this one method, so the two can never
     * disagree about whether an item is consumed.
     */
    public ItemStack claim() {
        if (this.infinite) {
            return this.held.copy();
        }
        return take();
    }

    public int takeCooldown() {
        return this.takeCooldown;
    }

    public void setTakeCooldown(int ticks) {
        this.takeCooldown = ticks;
    }

    /**
     * Spills the stack into the world and clears the slot. Called from the block's break path <b>before</b>
     * the block entity goes away; because {@link #take()} / this method are the only exits and both empty
     * the slot, no path can drop the same stack twice.
     *
     * <p>A creative rack's slot is a template, not a container, so it spills nothing: the template is part
     * of the block and taking copies of it is already unlimited, so a break must not turn it into a free
     * item on top of that.</p>
     */
    public void dropContents(Level level) {
        if (this.infinite || this.held.isEmpty()) {
            return;
        }
        ItemStack out = take();
        Containers.dropItemStack(level, this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D,
                this.worldPosition.getZ() + 0.5D, out);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.held.isEmpty()) {
            // The FULL stack tag, not just the item id: a TaCZ gun's attachments and magazine ride in here.
            tag.put(TAG_ITEM, this.held.save(new CompoundTag()));
        }
        tag.putInt("TakeCooldown", this.takeCooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.held = tag.contains(TAG_ITEM) ? ItemStack.of(tag.getCompound(TAG_ITEM)) : ItemStack.EMPTY;
        this.takeCooldown = tag.getInt("TakeCooldown");
    }

    // ------------------------------------------------------------------ client sync (the BER needs the stack)

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    private void sync() {
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------ tick

    /** Server tick: the only place the taker and the absorb scan run, so an unloaded rack costs nothing. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, WeaponRackBlockEntity rack) {
        if (level.isClientSide || !Config.RACK_ENABLED.get()) {
            return;
        }
        if (rack.infinite && !Config.RACK_CREATIVE_RACK_ENABLED.get()) {
            // The creative rack is switched off: inert, and it says so when right-clicked.
            return;
        }
        long time = level.getGameTime();
        // The two steps have their own intervals and their own guards, so a busy taker cannot starve the
        // absorb scan and a full rack cannot stop the taker from being polled.
        int absorbInterval = Math.max(1, Config.RACK_ABSORB_CHECK_INTERVAL_TICKS.get());
        if ((time + pos.asLong()) % absorbInterval == 0L) {
            absorb(level, pos, rack);
        }

        if (rack.takeCooldown > 0) {
            rack.takeCooldown--;
        }
        if (rack.takeCooldown > 0) {
            return;
        }
        int interval = Math.max(1, Config.RACK_TAKE_CHECK_INTERVAL_TICKS.get());
        if ((time + pos.asLong()) % interval != 0L) {
            return;
        }
        WeaponRackTaker.tick(level, pos, rack);
    }

    /** The crate the absorb scan looks in: the rack's own block plus {@code absorbRadius} around it. */
    public static net.minecraft.world.phys.AABB absorbBox(BlockPos pos) {
        double radius = Config.RACK_ABSORB_RADIUS.get();
        double height = Config.RACK_ABSORB_HEIGHT.get();
        double centreX = pos.getX() + 0.5D;
        double centreZ = pos.getZ() + 0.5D;
        return new net.minecraft.world.phys.AABB(
                centreX - radius, pos.getY(), centreZ - radius,
                centreX + radius, pos.getY() + height, centreZ + radius);
    }

    /**
     * Picks up ONE dropped item that landed on the rack (README 5n).
     *
     * <p>The rules, in order, and each one is a gate assertion:</p>
     * <ol>
     *   <li>switched off, or the rack is not empty -&gt; nothing happens. A rack never overwrites or queues:
     *       its slot holds one stack, so an item that arrives while it is busy stays on the ground;</li>
     *   <li>the item is not one the rack accepts ({@link WeaponRackArmament#accepts}) -&gt; left where it is,
     *       so rubbish cannot be absorbed and then block the slot for a real weapon;</li>
     *   <li>exactly ONE item is taken, <b>not the whole stack</b>: a copy of one is put on the rack and the
     *       entity's stack is shrunk by one. A stack of 5 leaves 4 on the ground, and an item entity that
     *       empties this way is discarded by its own tick (never by us, so nothing is removed twice);</li>
     *   <li>an entity that was removed by something else between the query and here is skipped, so the take
     *       cannot resurrect a dead drop.</li>
     * </ol>
     *
     * @return true when one item was absorbed
     */
    public static boolean absorb(Level level, BlockPos pos, WeaponRackBlockEntity rack) {
        if (!Config.RACK_ABSORB_DROPPED_ITEMS.get() || !rack.isEmpty()) {
            return false;
        }
        net.minecraft.world.entity.item.ItemEntity drop = nearestDrop(level, pos);
        if (drop == null) {
            return false;
        }
        ItemStack onGround = drop.getItem();
        if (onGround.isEmpty() || !WeaponRackArmament.accepts(onGround)) {
            return false;
        }
        // One item, and the entity keeps the rest. `put` copies with count 1 on top of that.
        ItemStack one = onGround.copyWithCount(1);
        onGround.shrink(1);
        rack.put(one);
        TarkovScav.LOGGER.info("[rack] absorbed {} x1 ({}) dropped at {} onto the rack at {}",
                one.getHoverName().getString(), WeaponRackArmament.armamentOf(one).id(),
                drop.blockPosition().toShortString(), pos.toShortString());
        return true;
    }

    /** The nearest non-removed dropped item inside {@link #absorbBox}, ties broken by entity id. */
    @Nullable
    public static net.minecraft.world.entity.item.ItemEntity nearestDrop(Level level, BlockPos pos) {
        var candidates = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                absorbBox(pos), drop -> !drop.isRemoved() && !drop.getItem().isEmpty());
        net.minecraft.world.entity.item.ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.item.ItemEntity drop : candidates) {
            double distance = drop.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distance < bestDistance
                    || (distance == bestDistance && best != null && drop.getId() < best.getId())) {
                best = drop;
                bestDistance = distance;
            }
        }
        return best;
    }
}
