package com.gfl.tarkovscav.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * One explicit "this is a city" box, configured in {@code spawn.cityRegions} or added in game with
 * {@code /tarkovscav city add <name> [radius]}.
 *
 * <p>This is the escape hatch for cities that are <b>not</b> worldgen structures: something the user
 * built and edited by hand, or a structure from a mod whose ids they do not want to hunt down.</p>
 *
 * <p>Serialised form (also the format to type into the config by hand):</p>
 * <pre>
 *   [&lt;name&gt;|]&lt;dimension&gt;|&lt;x1&gt; &lt;y1&gt; &lt;z1&gt;|&lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *   minecraft:overworld|-120 40 -80|120 120 80
 *   downtown|minecraft:overworld|-120 40 -80|120 120 80
 * </pre>
 */
public record CityRegion(String name, ResourceLocation dimension, AABB box) {
    /** @return the parsed region, or null when the line is malformed. */
    @Nullable
    public static CityRegion parse(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("\\|");
        try {
            if (parts.length == 3) {
                ResourceLocation dimension = ResourceLocation.tryParse(parts[0].trim());
                if (dimension == null) {
                    return null;
                }
                return new CityRegion("region", dimension, box(parts[1], parts[2]));
            }
            if (parts.length == 4) {
                ResourceLocation dimension = ResourceLocation.tryParse(parts[1].trim());
                if (dimension == null) {
                    return null;
                }
                return new CityRegion(parts[0].trim(), dimension, box(parts[2], parts[3]));
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return null;
    }

    private static AABB box(String first, String second) {
        double[] a = xyz(first);
        double[] b = xyz(second);
        return new AABB(
                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
    }

    private static double[] xyz(String text) {
        String[] numbers = text.trim().split("[ ,]+");
        return new double[]{
                Double.parseDouble(numbers[0]),
                Double.parseDouble(numbers[1]),
                Double.parseDouble(numbers[2])};
    }

    public static CityRegion cube(String name, ResourceLocation dimension, BlockPos center, int radius) {
        return new CityRegion(name, dimension, new AABB(
                center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                center.getX() + radius + 1.0D, center.getY() + radius + 1.0D, center.getZ() + radius + 1.0D));
    }

    /** Same box, grown by {@code padding} blocks horizontally (and vertically, harmlessly). */
    public CityRegion inflated(int padding) {
        if (padding <= 0) {
            return this;
        }
        return new CityRegion(this.name, this.dimension,
                new AABB(this.box.minX - padding, this.box.minY - padding, this.box.minZ - padding,
                        this.box.maxX + padding, this.box.maxY + padding, this.box.maxZ + padding));
    }

    public boolean contains(ResourceLocation dimension, BlockPos pos) {
        return this.dimension.equals(dimension) && this.box.contains(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    @Override
    public String toString() {
        return this.name + "|" + this.dimension + "|"
                + (int) this.box.minX + " " + (int) this.box.minY + " " + (int) this.box.minZ + "|"
                + (int) this.box.maxX + " " + (int) this.box.maxY + " " + (int) this.box.maxZ;
    }
}
