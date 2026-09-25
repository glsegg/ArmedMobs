package net.minecraft.world.phys;

/**
 * Minimal stand-in for Minecraft's Vec3, used ONLY by the spike tools that have to execute a real mod class
 * without a game on the classpath (see tools/spike/GrenadeBallisticsTest.java). Every member below mirrors
 * the vanilla implementation exactly for the values the solver uses, so the arithmetic under test is the
 * arithmetic the mod ships.
 */
public class Vec3 {
    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double lengthSqr() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    /** Vanilla: divide by the length, or return the zero vector when it is shorter than 1.0E-4. */
    public Vec3 normalize() {
        double length = Math.sqrt(this.lengthSqr());
        return length < 1.0E-4D ? new Vec3(0.0D, 0.0D, 0.0D)
                : new Vec3(this.x / length, this.y / length, this.z / length);
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public Vec3 scale(double factor) {
        return new Vec3(this.x * factor, this.y * factor, this.z * factor);
    }

    /** The horizontal distance between two points - what the solver's error() measures. */
    public double horizontalDistanceTo(Vec3 other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", this.x, this.y, this.z);
    }
}
