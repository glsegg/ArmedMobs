package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Minimal stand-in for Minecraft's BlockState for the spike tools (see Level's note). */
public class BlockState {
    public VoxelShape getCollisionShape(Level level, BlockPos pos) {
        return new VoxelShape(true);
    }
}
