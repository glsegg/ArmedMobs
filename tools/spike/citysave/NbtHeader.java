import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prints only the header facts of a vanilla structure .nbt, one short line per fact. */
public final class NbtHeader {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            try (InputStream in = new FileInputStream(arg)) {
                Map<String, Object> root = NbtReader.compound(NbtReader.readGzip(in));
                List<?> size = (List<?>) root.get("size");
                int sx = ((Number) size.get(0)).intValue();
                int sy = ((Number) size.get(1)).intValue();
                int sz = ((Number) size.get(2)).intValue();
                System.out.println("file=" + arg);
                System.out.println("DataVersion=" + root.get("DataVersion"));
                System.out.println("size=" + sx + "," + sy + "," + sz);
                System.out.println("volume=" + (sx * sy * sz));
                List<?> blocks = (List<?>) root.get("blocks");
                System.out.println("blockEntries=" + blocks.size());
                System.out.println("entities=" + ((List<?>) root.get("entities")).size());
                System.out.println("paletteSize=" + ((List<?>) root.get("palette")).size());
                // histogram of palette indices actually used
                TreeMap<Integer, Integer> histogram = new TreeMap<>();
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (Object o : blocks) {
                    Map<String, Object> entry = NbtReader.compound(o);
                    int state = ((Number) entry.get("state")).intValue();
                    histogram.merge(state, 1, Integer::sum);
                    List<?> pos = (List<?>) entry.get("pos");
                    int x = ((Number) pos.get(0)).intValue();
                    int y = ((Number) pos.get(1)).intValue();
                    int z = ((Number) pos.get(2)).intValue();
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
                System.out.println("posRange=x[" + minX + ".." + maxX + "] y[" + minY + ".." + maxY + "] z[" + minZ + ".." + maxZ + "]");
                List<?> palette = (List<?>) root.get("palette");
                Map<String, Integer> byName = new LinkedHashMap<>();
                histogram.forEach((state, count) -> {
                    Map<String, Object> entry = NbtReader.compound(palette.get(state));
                    byName.merge(String.valueOf(entry.get("Name")), count, Integer::sum);
                });
                byName.forEach((name, count) -> System.out.println("  " + name + " x" + count));
            }
        }
    }
}
