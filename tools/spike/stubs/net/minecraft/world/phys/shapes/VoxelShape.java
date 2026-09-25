package net.minecraft.world.phys.shapes;

/** Minimal stand-in for Minecraft's VoxelShape for the spike tools (see Level's note). */
public class VoxelShape {
    private final boolean empty;

    public VoxelShape(boolean empty) {
        this.empty = empty;
    }

    public boolean isEmpty() {
        return this.empty;
    }
}
