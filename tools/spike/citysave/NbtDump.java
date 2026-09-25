import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dumps the header of a vanilla structure .nbt file: Size, DataVersion, palette size, block count
 * and the block-id histogram. Used here to prove what tarkovscav:city_small actually contains and
 * how large a footprint each placed copy needs.
 */
public final class NbtDump {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            System.out.println("===== " + arg + " =====");
            try (InputStream in = new FileInputStream(arg)) {
                Map<String, Object> root = NbtReader.compound(NbtReader.readGzip(in));
                System.out.println("root keys: " + root.keySet());
                for (String key : new String[]{"DataVersion", "author", "size", "entities", "blocks", "palette", "palettes"}) {
                    if (!root.containsKey(key)) {
                        continue;
                    }
                    Object value = root.get(key);
                    if (value instanceof int[] arr) {
                        System.out.println(key + " = [" + arr[0] + ", " + arr[1] + ", " + arr[2] + "]");
                    } else if (value instanceof List<?> list) {
                        System.out.println(key + " = list(" + list.size() + ") " + list);
                    } else {
                        System.out.println(key + " = " + value);
                    }
                }
                Object palette = root.get("palette");
                if (palette instanceof List<?> list) {
                    java.util.Map<String, Integer> histogram = new java.util.TreeMap<>();
                    for (Object entry : list) {
                        Map<String, Object> compound = NbtReader.compound(entry);
                        System.out.println("  palette entry: " + compound.get("Name") + " " + compound.getOrDefault("Properties", ""));
                    }
                }
                Object blocks = root.get("blocks");
                if (blocks instanceof List<?> list) {
                    System.out.println("block entries: " + list.size());
                    Object first = list.isEmpty() ? null : list.get(0);
                    if (first != null) {
                        System.out.println("first block entry keys: " + NbtReader.compound(first).keySet());
                    }
                }
                Object entities = root.get("entities");
                if (entities instanceof List<?> list) {
                    System.out.println("entity entries: " + list.size());
                }
                System.out.println("file size: " + Files.size(Path.of(arg)));
            }
            System.out.println();
        }
    }
}
