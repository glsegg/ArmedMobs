import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 of the city work: puts {@code minecraft:jigsaw} connector blocks (with their block entity NBT) into a
 * structure NBT, so the extracted pieces can be assembled by the vanilla jigsaw engine.
 *
 * <p>A connector is described on the command line and looks like this:</p>
 * <pre>
 *   8,4,0,north_up,tarkovscav:street_side,tarkovscav:street_side,tarkovscav:city_district/street,aligned,minecraft:air
 *   ^pos ^orientation ^name                ^target                  ^pool                            ^joint  ^final_state
 * </pre>
 *
 * <p>Semantics (vanilla, 1.20.1): the block at {@code pos} becomes a jigsaw whose <b>front</b> (the
 * orientation) points at the neighbour it wants to grow towards. <b>name</b> is how a PARENT refers to this
 * connector, <b>target</b> is the name the CHILD's connector must have, and <b>pool</b> is where the child is
 * drawn from. {@code final_state} is the block the jigsaw turns into once the world is generated - air for
 * our pieces, so no connector is ever visible in game.</p>
 *
 * <pre>
 *   StructureConnectors --in piece.nbt --out piece.nbt [--connector &lt;spec&gt;]...
 * </pre>
 */
public final class StructureConnectors {

    public static void main(String[] args) throws Exception {
        Path in = null, out = null;
        List<String> specs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> in = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--connector" -> specs.add(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (in == null || out == null) { throw new IllegalArgumentException("--in and --out are required"); }

        NbtRewriter.Root root = NbtRewriter.readRaw(Files.newInputStream(in));
        NbtRewriter.Compound structure = root.tag.asCompound();
        List<NbtRewriter.Tag> size = structure.get("size").asList().items;
        int sx = ((Number) size.get(0).value).intValue();
        int sy = ((Number) size.get(1).value).intValue();
        int sz = ((Number) size.get(2).value).intValue();
        NbtRewriter.ListTag palette = structure.get("palette").asList();
        NbtRewriter.ListTag blocks = structure.get("blocks").asList();

        // pos -> index into blocks, so a connector replaces whatever is at that cell.
        Map<String, Integer> byPos = new LinkedHashMap<>();
        for (int i = 0; i < blocks.items.size(); i++) {
            NbtRewriter.Compound block = blocks.items.get(i).asCompound();
            List<NbtRewriter.Tag> pos = block.get("pos").asList().items;
            byPos.put(((Number) pos.get(0).value).intValue() + "," + ((Number) pos.get(1).value).intValue()
                    + "," + ((Number) pos.get(2).value).intValue(), i);
        }

        int added = 0;
        for (String spec : specs) {
            String[] parts = spec.split(",");
            if (parts.length != 9) { throw new IllegalArgumentException("bad connector spec: " + spec); }
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            String orientation = parts[3].trim();
            String name = parts[4].trim();
            String target = parts[5].trim();
            String pool = parts[6].trim();
            String joint = parts[7].trim();
            String finalState = parts[8].trim();
            if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
                throw new IllegalArgumentException("connector " + spec + " is outside the piece "
                        + sx + "x" + sy + "x" + sz);
            }
            String state = "minecraft:jigsaw[orientation=" + orientation + "]";
            // final_state=ORIGINAL keeps whatever block the connector replaced, so a connector dropped on a
            // pavement tile does not leave a one-block hole in the street after generation.
            String existingName = null;
            Integer existingIndex = byPos.get(x + "," + y + "," + z);
            if (existingIndex != null) {
                int stateAt = ((Number) blocks.items.get(existingIndex).asCompound().get("state").value).intValue();
                existingName = String.valueOf(palette.items.get(stateAt).asCompound().get("Name").value);
            }
            if (finalState.equals("ORIGINAL")) {
                finalState = existingName == null ? "minecraft:air" : existingName;
            }
            int stateIndex = -1;
            for (int i = 0; i < palette.items.size(); i++) {
                NbtRewriter.Compound entry = palette.items.get(i).asCompound();
                String entryName = String.valueOf(entry.get("Name").value);
                String entryOrientation = "north_up";
                NbtRewriter.Tag props = entry.get("Properties");
                if (props != null && props.asCompound().get("orientation") != null) {
                    entryOrientation = String.valueOf(props.asCompound().get("orientation").value);
                }
                if (entryName.equals("minecraft:jigsaw") && entryOrientation.equals(orientation)) {
                    stateIndex = i;
                    break;
                }
            }
            if (stateIndex < 0) {
                NbtRewriter.Compound entry = new NbtRewriter.Compound();
                entry.put("Name", new NbtRewriter.Tag(NbtRewriter.STRING, "minecraft:jigsaw"));
                NbtRewriter.Compound properties = new NbtRewriter.Compound();
                properties.put("orientation", new NbtRewriter.Tag(NbtRewriter.STRING, orientation));
                entry.put("Properties", new NbtRewriter.Tag(NbtRewriter.COMPOUND, properties));
                palette.items.add(new NbtRewriter.Tag(NbtRewriter.COMPOUND, entry));
                stateIndex = palette.items.size() - 1;
            }

            NbtRewriter.Compound nbt = new NbtRewriter.Compound();
            nbt.put("id", new NbtRewriter.Tag(NbtRewriter.STRING, "minecraft:jigsaw"));
            nbt.put("name", new NbtRewriter.Tag(NbtRewriter.STRING, name));
            nbt.put("target", new NbtRewriter.Tag(NbtRewriter.STRING, target));
            nbt.put("pool", new NbtRewriter.Tag(NbtRewriter.STRING, pool));
            nbt.put("final_state", new NbtRewriter.Tag(NbtRewriter.STRING, finalState));
            nbt.put("joint", new NbtRewriter.Tag(NbtRewriter.STRING, joint));
            nbt.put("x", new NbtRewriter.Tag(NbtRewriter.INT, x));
            nbt.put("y", new NbtRewriter.Tag(NbtRewriter.INT, y));
            nbt.put("z", new NbtRewriter.Tag(NbtRewriter.INT, z));

            Integer existing = byPos.get(x + "," + y + "," + z);
            if (existing != null) {
                NbtRewriter.Compound block = blocks.items.get(existing).asCompound();
                block.put("state", new NbtRewriter.Tag(NbtRewriter.INT, stateIndex));
                block.put("nbt", new NbtRewriter.Tag(NbtRewriter.COMPOUND, nbt));
            } else {
                NbtRewriter.Compound block = new NbtRewriter.Compound();
                NbtRewriter.ListTag pos = new NbtRewriter.ListTag(NbtRewriter.INT);
                pos.items.add(new NbtRewriter.Tag(NbtRewriter.INT, x));
                pos.items.add(new NbtRewriter.Tag(NbtRewriter.INT, y));
                pos.items.add(new NbtRewriter.Tag(NbtRewriter.INT, z));
                block.put("pos", new NbtRewriter.Tag(NbtRewriter.LIST, pos));
                block.put("state", new NbtRewriter.Tag(NbtRewriter.INT, stateIndex));
                block.put("nbt", new NbtRewriter.Tag(NbtRewriter.COMPOUND, nbt));
                blocks.items.add(new NbtRewriter.Tag(NbtRewriter.COMPOUND, block));
                byPos.put(x + "," + y + "," + z, blocks.items.size() - 1);
            }
            added++;
            System.out.println("  connector " + name + " -> " + target + " (pool " + pool + ", " + joint
                    + ", " + orientation + ") at " + x + "," + y + "," + z + " of " + in.getFileName());
        }

        NbtRewriter.writeGzip(out, root.name, root.tag);
        System.out.println(in.getFileName() + ": size " + sx + "x" + sy + "x" + sz + ", " + blocks.items.size()
                + " blocks, " + palette.items.size() + " palette, " + added + " connector(s), "
                + Files.size(out) + " bytes -> " + out.getFileName());
    }

    private StructureConnectors() {
    }
}
