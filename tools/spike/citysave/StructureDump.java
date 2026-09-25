import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints the size, the palette and the block entities of a structure NBT file, so the world content can be
 * compared with what the mod's own generator ships (M1 of the city work: "what is the preset style, and
 * what did the user add on top of it?").
 *
 * <pre>
 *   StructureDump &lt;file.nbt&gt; [maxPaletteLines]
 * </pre>
 *
 * Read-only: it never writes anything.
 */
public final class StructureDump {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        Object root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            root = NbtReader.readGzip(in);
        }
        Map<String, Object> structure = NbtReader.compound(root);
        System.out.println("file=" + file.getFileName() + " bytes=" + Files.size(file));
        System.out.println("DataVersion=" + structure.getOrDefault("DataVersion", "?"));
        System.out.println("size=" + structure.get("size"));

        List<Object> palette = NbtReader.list(structure, "palette");
        Map<String, Integer> histogram = new TreeMap<>();
        List<String> states = new ArrayList<>();
        for (Object entry : palette) {
            Map<String, Object> state = NbtReader.compound(entry);
            StringBuilder sb = new StringBuilder(String.valueOf(state.get("Name")));
            Object props = state.get("Properties");
            if (props instanceof Map<?, ?> map && !map.isEmpty()) {
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sorted.put(String.valueOf(e.getKey()), e.getValue());
                }
                List<String> parts = new ArrayList<>();
                sorted.forEach((k, v) -> parts.add(k + "=" + v));
                sb.append('[').append(String.join(",", parts)).append(']');
            }
            states.add(sb.toString());
        }
        List<Object> blocks = NbtReader.list(structure, "blocks");
        int withNbt = 0;
        Map<String, Integer> nbtByType = new TreeMap<>();
        for (Object entry : blocks) {
            Map<String, Object> block = NbtReader.compound(entry);
            int stateIndex = NbtReader.intOf(block, "state");
            String name = stateIndex >= 0 && stateIndex < states.size()
                    ? states.get(stateIndex).split("\\[")[0] : "?";
            histogram.merge(name, 1, Integer::sum);
            Object nbt = block.get("nbt");
            if (nbt instanceof Map<?, ?> nbtMap && !nbtMap.isEmpty()) {
                withNbt++;
                String id = String.valueOf(((Map<?, ?>) nbt).get("id"));
                nbtByType.merge(id, 1, Integer::sum);
            }
        }
        System.out.println("paletteEntries=" + palette.size() + " blocksPlaced=" + blocks.size()
                + " blocksWithNbt=" + withNbt);
        System.out.println();
        System.out.println("=== palette (state -> placements) ===");
        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        for (Object entry : blocks) {
            Map<String, Object> block = NbtReader.compound(entry);
            int stateIndex = NbtReader.intOf(block, "state");
            String state = stateIndex >= 0 && stateIndex < states.size() ? states.get(stateIndex) : "?";
            stateCounts.merge(state, 1, Integer::sum);
        }
        stateCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(e -> System.out.printf("  %-70s x%d%n", e.getKey(), e.getValue()));
        System.out.println();
        System.out.println("=== blocks carrying NBT (block entities) ===");
        if (nbtByType.isEmpty()) {
            System.out.println("  (none)");
        } else {
            nbtByType.forEach((id, count) -> System.out.println("  " + id + " x" + count));
        }
    }

    private StructureDump() {
    }
}
