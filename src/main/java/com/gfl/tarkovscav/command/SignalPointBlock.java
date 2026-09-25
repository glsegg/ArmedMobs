package com.gfl.tarkovscav.command;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The signal point: a placeable block that owns a <b>permanent</b> mark, and retracts it when broken.
 *
 * <h2>Permanent means permanent</h2>
 * <p>The mark it creates has no expiry tick, so it never times out - the only two ways it ends are the
 * player deleting it ({@code /armedmobs marks remove <letter>}) or the block being broken. Breaking it
 * removes exactly its own mark: the letter it was given is stored in
 * {@link SignalPointBlockEntity} and looked up by {@link MarkData#removeExact}, so pulling up one signal
 * point cannot take another one's mark with it.</p>
 *
 * <h2>Every path</h2>
 * <p>{@code setPlacedBy} covers a player placing it. {@code onRemove} covers everything that takes it
 * away - a break, an explosion, a piston, {@code /setblock} - because vanilla calls it for all of them,
 * and the block entity is still readable at that moment (the same ordering {@code WeaponRackBlock} relies
 * on to spill its contents exactly once).</p>
 *
 * <p>A signal point placed by {@code /setblock} with no block entity has no letter and therefore creates no
 * mark: there is no way to know which letter to give it without a placement event, and silently stealing
 * a free letter on a chunk load would be worse.</p>
 */
public class SignalPointBlock extends BaseEntityBlock {
    /** A slim post: the outline matches the model, so it does not feel like a full cube to walk into. */
    private static final VoxelShape SHAPE = Shapes.box(0.25D, 0.0D, 0.25D, 0.75D, 0.9375D, 0.75D);

    public SignalPointBlock() {
        super(Properties.of()
                .mapColor(MapColor.METAL)
                .strength(1.5F)
                .sound(SoundType.METAL)
                .lightLevel(state -> 10)
                .noOcclusion());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SignalPointBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ placement

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        CommandMark mark = CommandMarks.create(serverLevel, pos, CommandMark.Source.POINT, now);
        if (serverLevel.getBlockEntity(pos) instanceof SignalPointBlockEntity point) {
            point.setLetter(mark.letter());
        }
        if (placer instanceof net.minecraft.world.entity.player.Player player) {
            player.displayClientMessage(Component.translatable("tarkovscav.command.point.placed",
                    mark.chatLine()).withStyle(ChatFormatting.AQUA), false);
        }
    }

    // ------------------------------------------------------------------ removal

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && serverLevel.getBlockEntity(pos) instanceof SignalPointBlockEntity point
                && point.letter() != null) {
            boolean removed = MarkData.get(serverLevel.getServer()).removeExact(
                    serverLevel.dimension().location(), point.letter(), pos);
            if (removed) {
                com.gfl.tarkovscav.TarkovScav.LOGGER.info(
                        "[command] signal point at {} retracted mark {}",
                        pos.toShortString(), point.letter());
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
