import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Per-Y-layer breakdown of a structure .nbt: how many solid blocks sit on each layer. */
public final class NbtLayers {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            try (InputStream in = new FileInputStream(arg)) {
                Map<String, Object> root = NbtReader.compound(NbtReader.readGzip(in));
                List<?> size = (List<?>) root.get("size");
                int sy = ((Number) size.get(1)).intValue();
                List<?> palette = (List<?>) root.get("palette");
                List<?> blocks = (List<?>) root.get("blocks");
                int[] solid = new int[sy];
                int[] air = new int[sy];
                String[] sample = new String[sy];
                for (Object o : blocks) {
                    Map<String, Object> entry = NbtReader.compound(o);
                    List<?> pos = (List<?>) entry.get("pos");
                    int y = ((Number) pos.get(1)).intValue();
                    int state = ((Number) entry.get("state")).intValue();
                    String name = String.valueOf(NbtReader.compound(palette.get(state)).get("Name"));
                    if (name.equals("minecraft:air")) {
                        air[y]++;
                    } else {
                        solid[y]++;
                        sample[y] = name;
                    }
                }
                System.out.println("file=" + arg + " sizeY=" + sy);
                for (int y = 0; y < sy; y++) {
                    System.out.printf("y=%2d solid=%4d air=%4d %s%n", y, solid[y], air[y], sample[y] == null ? "" : sample[y]);
                }
            }
        }
    }
}
