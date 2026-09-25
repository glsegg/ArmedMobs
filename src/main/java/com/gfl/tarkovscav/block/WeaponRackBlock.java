package com.gfl.tarkovscav.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The weapon rack: one slot, four ways to interact, and never a silent one (README 5n).
 *
 * <h2>The interaction truth table, in order</h2>
 * <table border="1">
 *   <caption>rows are checked top to bottom; the first match wins</caption>
 *   <tr><th>#</th><th>Hand</th><th>Rack</th><th>Result</th></tr>
 *   <tr><td>1</td><td>sneaking (any hand)</td><td>any</td><td><b>take</b> ("took X") / "the rack is empty"</td></tr>
 *   <tr><td>2</td><td>empty</td><td>any</td><td><b>take</b> ("took X") / "the rack is empty"</td></tr>
 *   <tr><td>3</td><td>item</td><td>empty, accepted</td><td><b>place</b> ("put X on the rack")</td></tr>
 *   <tr><td>4</td><td>item</td><td>occupied</td><td><b>refused</b>: "X is already on the rack - take it first.
 *       Nothing was moved" (no swap, so no item can be lost or duplicated)</td></tr>
 *   <tr><td>5</td><td>item</td><td>empty, not accepted</td><td><b>refused</b>: names the item and lists what the
 *       rack does accept, plus how to allow anything ({@code rack.acceptsAnyItem})</td></tr>
 * </table>
 *
 * <p>Every branch sends a message: there is no path where a right click does nothing and says nothing. The
 * refusal messages deliberately say <b>where the old item is</b> ("it is still on the rack"), because that
 * is the only thing a player can be unsure about.</p>
 *
 * <h2>Dropping the contents</h2>
 * <p>The stack is returned to the world <b>exactly once</b>, whichever way the rack disappears:
 * {@link #playerWillDestroy} spills it for a survival break (and empties the slot), while {@link #onRemove}
 * covers everything else - a creative break (where {@code instabuild} suppresses the first path), explosions,
 * pistons and {@code /setblock}. Because {@link WeaponRackBlockEntity#take()} and
 * {@link WeaponRackBlockEntity#dropContents} are the only two ways the slot empties and both empty it, the
 * second path always finds an empty rack and cannot drop the same stack twice. A creative rack's slot is a
 * template and spills nothing either way ({@link WeaponRackBlockEntity#dropContents}).</p>
 *
 * <h2>The creative twin ({@code tarkovscav:creative_weapon_rack})</h2>
 * <p>A separate block with the same model and the same rules, except that its slot is a <b>template</b> that
 * is never consumed ({@link WeaponRackBlockEntity#infinite}). Two consequences are visible here: taking hands
 * over a copy and says so ("the rack still holds it, as many copies as you like"), and placing onto an
 * occupied creative rack REPLACES the template instead of being refused - a normal rack can be emptied and
 * therefore can be refused, but a creative rack never empties, so refusing would make it unchangeable. The
 * old template is returned to the player, so nothing vanishes. {@code rack.creativeRackEnabled = false} makes
 * the block inert and every branch says so.</p>
 *
 * <h2>Absorbing a thrown weapon</h2>
 * <p>Right-clicking cannot be the only way in: with a gun in hand, right-click is the place/refuse branch
 * (and TaCZ wants that button for firing), so a player who wants to hand a weapon over has no way to
 * interact. {@link WeaponRackBlockEntity#serverTick} therefore also scans for a dropped item on top of the
 * rack and absorbs <b>one</b> of it while the rack is empty. See {@link WeaponRackBlockEntity#absorb}.</p>
 */
public class WeaponRackBlock extends BaseEntityBlock {
    /** Matches the model's footprint (base plate + posts + top bar), so the outline is the rack itself. */
    private static final VoxelShape SHAPE = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.9375D, 1.0D);

    /**
     * Which way the rack faces (README 5n). The property is copied from vanilla's
     * {@code HorizontalDirectionalBlock} by hand, because this class has to extend {@link BaseEntityBlock} to
     * own a block entity and Java has no second inheritance.
     *
     * <p>{@link Direction#NORTH} is the default <b>and</b> the state every rack placed before this property
     * existed upgrades to, so a legacy world's racks keep looking exactly as they did.</p>
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** True on {@code tarkovscav:creative_weapon_rack} (README 5n): the slot is an endless template. */
    private final boolean creative;

    public WeaponRackBlock() {
        this(false);
    }

    public WeaponRackBlock(boolean creative) {
        super(Block.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F)
                .sound(SoundType.WOOD)
                .noOcclusion());
        this.creative = creative;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** True on the creative rack; the block entity carries the same flag (see its javadoc). */
    public boolean creative() {
        return this.creative;
    }

    // ------------------------------------------------------------------ facing

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * A rack is placed the way chests and workbenches are: the player faces it, so the rack faces the
     * player ({@code getHorizontalDirection()} is where the player looks, its opposite is "at the player").
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Rotating the block (a structure block, a modded wrench) turns the rack with it. */
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Mirroring a structure mirrors the rack too. */
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // The shape is the full block footprint, so it is the same for all four facings (no getShape(FACING)).
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // The item on the rack is drawn by the block entity renderer, not by the baked model.
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeaponRackBlockEntity(pos, state, this.creative);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, this.creative
                        ? com.gfl.tarkovscav.registry.ModBlocks.CREATIVE_WEAPON_RACK_BE.get()
                        : com.gfl.tarkovscav.registry.ModBlocks.WEAPON_RACK_BE.get(),
                WeaponRackBlockEntity::serverTick);
    }

    // ------------------------------------------------------------------ the truth table

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WeaponRackBlockEntity rack)) {
            return InteractionResult.PASS;
        }
        ItemStack inHand = player.getItemInHand(hand);
        boolean infinite = rack.infinite();
        if (infinite && !com.gfl.tarkovscav.Config.RACK_CREATIVE_RACK_ENABLED.get()) {
            return say(level, player, "The creative weapon rack is switched off"
                    + " (rack.creativeRackEnabled = false). It is inert: it arms nobody and takes nothing.");
        }

        // 1 + 2: sneaking, or an empty hand, always means "give it to me".
        // On a creative rack this is a COPY and the template stays - which is the whole point of the block,
        // so it is said out loud rather than left for the player to discover.
        if (player.isShiftKeyDown() || inHand.isEmpty()) {
            if (rack.isEmpty()) {
                return say(level, player, "The weapon rack is empty.");
            }
            ItemStack taken = rack.claim();
            if (!player.getInventory().add(taken)) {
                player.drop(taken, false);
            }
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.8F, 1.0F);
            return say(level, player, "Took " + taken.getHoverName().getString()
                    + (infinite ? " off the creative weapon rack - the rack still holds it, as many copies"
                            + " as you like." : " off the weapon rack."));
        }

        // 4: occupied. A normal rack refuses, because a swap is where items get lost. A CREATIVE rack
        // replaces the template instead - the player can never empty it, so refusing would make the block
        // impossible to change - and the old template is handed back, so nothing vanishes.
        if (!rack.isEmpty()) {
            if (!infinite) {
                return say(level, player, "The weapon rack already holds "
                        + rack.held().getHoverName().getString() + " - take it first (sneak or empty hand)."
                        + " Nothing was moved, and the item on the rack is still there.");
            }
            if (!WeaponRackArmament.accepts(inHand)) {
                return say(level, player, "The creative weapon rack does not take "
                        + inHand.getHoverName().getString() + ". It accepts "
                        + WeaponRackArmament.acceptedList()
                        + ". Set rack.acceptsAnyItem = true to allow anything.");
            }
            ItemStack previous = rack.take();
            ItemStack next = inHand.copyWithCount(1);
            rack.put(next);
            if (!player.getAbilities().instabuild) {
                inHand.shrink(1);
            }
            if (!player.getInventory().add(previous)) {
                player.drop(previous, false);
            }
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
            return say(level, player, "Replaced the template " + previous.getHoverName().getString()
                    + " with " + next.getHoverName().getString() + " ("
                    + WeaponRackArmament.armamentOf(next).id() + "). The old one is back in your inventory.");
        }

        // 5: not a weapon, and acceptsAnyItem is off.
        if (!WeaponRackArmament.accepts(inHand)) {
            return say(level, player, "The weapon rack does not take "
                    + inHand.getHoverName().getString() + ". It accepts " + WeaponRackArmament.acceptedList()
                    + ". Set rack.acceptsAnyItem = true to allow anything.");
        }

        // 3: place one. On a creative rack the placed item becomes the (endless) template.
        ItemStack one = inHand.copyWithCount(1);
        rack.put(one);
        if (!player.getAbilities().instabuild) {
            inHand.shrink(1);
        }
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
        return say(level, player, "Put " + one.getHoverName().getString() + " on the weapon rack ("
                + WeaponRackArmament.armamentOf(one).id() + ")"
                + (infinite ? " as the endless template - mobs can take it any number of times." : "."));
    }

    /** Client-safe feedback: chat on the server, action bar on both, so no branch is ever silent. */
    private static InteractionResult say(Level level, Player player, String text) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ------------------------------------------------------------------ never swallow the item

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.getAbilities().instabuild
                && level.getBlockEntity(pos) instanceof WeaponRackBlockEntity rack) {
            // The block item is dropped by the loot table; this is the CONTENT only. A creative rack's slot
            // is a template and spills nothing (see WeaponRackBlockEntity#dropContents).
            rack.dropContents(level);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof WeaponRackBlockEntity rack && !rack.isEmpty()) {
            // Reached for anything that did not go through playerWillDestroy: explosion, piston, /setblock.
            rack.dropContents(level);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
