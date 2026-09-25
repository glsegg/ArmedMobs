package net.minecraft.core;

import java.util.Locale;

/**
 * Minimal stand-in for Minecraft's BlockPos for the spike tools (see Vec3's note). {@code containing} uses
 * {@code Mth.floor}, i.e. the same floor the real BlockPos uses, because the solver samples a position per
 * tick and the block it lands in decides everything.
 */
public class BlockPos {
    private final int x;
    private final int y;
    private final int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockPos containing(double x, double y, double z) {
        return new BlockPos(floor(x), floor(y), floor(z));
    }

    /** Mth.floor: cast-and-correct, which is Math.floor for every finite double. */
    private static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockPos pos && pos.x == this.x && pos.y == this.y && pos.z == this.z;
    }

    @Override
    public int hashCode() {
        return (this.y + this.z * 31) * 31 + this.x;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "(%d, %d, %d)", this.x, this.y, this.z);
    }
}
