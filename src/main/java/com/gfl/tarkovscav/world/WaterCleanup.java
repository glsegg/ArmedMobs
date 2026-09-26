package com.gfl.tarkovscav.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The body behind {@code /armedmobs fillwater} - replace the water inside a cube with air or any block.
 *
 * <h2>Why this exists</h2>
 * <p>The urban wasteland shipped with aquifers on and {@code default_fluid: minecraft:water}, which put
 * standing water in the seams between the city pieces: a hole a player (and a mob) falls into. That is
 * fixed for <em>newly generated</em> chunks in {@code noise_settings/urban_wasteland.json}
 * ({@code aquifers_enabled: false}, {@code default_fluid: minecraft:air}), but a datapack change cannot
 * touch chunks that are already on disk. This is the retro-fit path: an operator runs the command over the
 * affected area and the existing water is replaced with something solid (or drained, if they ask for air).
 *
 * <h2>What exactly it replaces</h2>
 * <p>Only the two real water blocks - {@code minecraft:water} and {@code minecraft:flowing_water}. A
 * <em>waterlogged</em> block (a stair, a fence, a slab that merely holds water) is counted and left alone:
 * swapping it for stone would delete the block and everything that reads it, which is a destructive
 * surprise an operator typing a water command does not expect. The count is printed so the number is
 * visible rather than implied.
 *
 * <h2>The boundary is stated, not hidden</h2>
 * <p>Water outside the cube flows back into it after the run (that is what water does), so a drain with
 * {@code minecraft:air} is only stable for a water body that fits entirely inside the radius, or on the
 * second pass. The report names the exact box that was swept and recommends a larger radius when the
 * sweep hit a water block on the boundary.
 */
public final class WaterCleanup {
    /** The default replacement: something you can stand on, which is what the wasteland seams need. */
    public static final String DEFAULT_BLOCK = "minecraft:stone";

    private WaterCleanup() {
    }

    /** What one run did, plus the box it did it in. */
    public record Report(BlockPos center, int radius, BlockPos min, BlockPos max,
                         BlockState target, int scanned, int replaced, int waterlogged,
                         int boundaryWater, boolean allLoaded) {
        public String describe() {
            return "fillwater " + blockName(this.target) + " r=" + this.radius
                    + " box " + this.min.toShortString() + ".." + this.max.toShortString()
                    + " scanned=" + this.scanned + " replaced=" + this.replaced
                    + " waterloggedKept=" + this.waterlogged
                    + " boundaryWater=" + this.boundaryWater
                    + (this.allLoaded ? "" : " (some chunks are not loaded - load them and run again)");
        }
    }

    /**
     * Resolves the block argument. A bare name is read as {@code minecraft:<name>}, so
     * {@code fillwater 48 stone} and {@code fillwater 48 minecraft:stone} both work. Returns {@code null}
     * for an unknown block, so the command can refuse with a message instead of defaulting silently.
     */
    @Nullable
    public static BlockState parseTarget(String raw) {
        String id = raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null || block == Blocks.AIR) {
            // AIR is also this registry's answer for an id it does not know, so only an explicit
            // minecraft:air is allowed to mean air; anything else that resolved to AIR is a typo.
            return "minecraft:air".equals(id) ? Blocks.AIR.defaultBlockState() : null;
        }
        return block.defaultBlockState();
    }

    private static String blockName(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key == null ? state.toString() : key.toString();
    }

    /**
     * Sweeps the cube of {@code radius} around {@code center} and replaces every full water block with
     * {@code target}. The cube is clamped to the level's build limits, and a position whose chunk is not
     * loaded is skipped (and reported), because forcing generation from a command is a worse surprise than
     * an incomplete sweep.
     */
    public static Report run(ServerLevel level, BlockPos center, int radius, BlockState target) {
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        BlockPos min = new BlockPos(center.getX() - radius, minY, center.getZ() - radius);
        BlockPos max = new BlockPos(center.getX() + radius, maxY, center.getZ() + radius);

        int scanned = 0;
        int replaced = 0;
        int waterlogged = 0;
        int boundaryWater = 0;
        boolean allLoaded = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) {
                    allLoaded = false;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    scanned++;
                    // There is no separate flowing_water BLOCK in 1.20.1: minecraft:water carries a
                    // 0..15 level property, so this one identity covers both still and flowing water.
                    // Waterlogged blocks are a different block that merely holds the fluid, so they fall
                    // through to the counter below.
                    Block here = state.getBlock();
                    boolean fullWater = here == Blocks.WATER;
                    if (!fullWater) {
                        if (state.getFluidState().getType() == Fluids.WATER) {
                            waterlogged++;
                        }
                        continue;
                    }
                    // UPDATE_CLIENTS only: a mass fill must not run the neighbour-update cascade for every
                    // block, or the command becomes a several-second tick stall on a large radius.
                    level.setBlock(cursor, target, Block.UPDATE_CLIENTS);
                    replaced++;
                    boolean onBoundary = x == min.getX() || x == max.getX()
                            || z == min.getZ() || z == max.getZ()
                            || y == minY || y == maxY;
                    if (onBoundary) {
                        boundaryWater++;
                    }
                }
            }
        }
        return new Report(center, radius, min, max, target, scanned, replaced, waterlogged,
                boundaryWater, allLoaded);
    }
}
