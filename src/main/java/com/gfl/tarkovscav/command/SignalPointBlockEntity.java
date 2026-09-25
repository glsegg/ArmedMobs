package com.gfl.tarkovscav.command;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The signal point's block entity: it remembers which mark letter this block owns.
 *
 * <h2>Why the letter is stored at all</h2>
 * <p>The mark itself lives in {@link MarkData}, keyed by dimension. What the block has to remember is the
 * <b>letter</b> it was given when it was placed, because that is the only thing that lets
 * {@code onRemove} retract exactly this mark - "breaking the block invalidates the mark", and it must
 * invalidate <em>its own</em> mark and not, say, letter A because A happens to be the first mark in the
 * dimension.</p>
 *
 * <p>It is written on placement and read on break, and it is saved with the chunk, so a signal point still
 * retracts its mark after a restart, a chunk unload or a {@code /setblock} over it.</p>
 */
public class SignalPointBlockEntity extends BlockEntity {
    private static final String KEY_LETTER = "markLetter";

    private String letter;

    public SignalPointBlockEntity(BlockPos pos, BlockState state) {
        super(com.gfl.tarkovscav.registry.ModBlocks.SIGNAL_POINT_BE.get(), pos, state);
    }

    /** The letter of the mark this block owns, or null when it never got one. */
    public String letter() {
        return this.letter;
    }

    public void setLetter(String letter) {
        this.letter = letter;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.letter != null) {
            tag.putString(KEY_LETTER, this.letter);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.letter = tag.contains(KEY_LETTER) ? tag.getString(KEY_LETTER) : null;
    }
}
