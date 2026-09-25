package net.minecraft.world.level;

import net.minecraft.core.BlockPos;

/**
 * Minimal stand-in for Minecraft's Level for the spike tools (see Vec3's note). The solver only ever calls
 * {@code getBlockState(pos).getCollisionShape(level, pos).isEmpty()} through {@code levelBlockTest}, and the
 * spike test always passes its own BlockTest, so this never runs - it only has to compile.
 */
public class Level {
    public BlockState getBlockState(BlockPos pos) {
        return new BlockState();
    }
}
