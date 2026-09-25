import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M2 of the city work: cuts a BUILDING out of a saved world's region files and writes it as a structure
 * NBT - the artifact a jigsaw template pool is made of.
 *
 * <p>Rules (all of them are arguments, and all of them are on purpose):</p>
 * <ul>
 *   <li><b>only the building body</b>: the flat platform and the pavement the district sits on are excluded
 *       ({@code --base-y}), while the building's own bottom layer is kept as the top of the foundation;</li>
 *   <li><b>air is trimmed</b> on all six sides, so the piece is exactly as big as its contents;</li>
 *   <li><b>a foundation is grown downwards</b> ({@code --foundation N}, default 5) out of the material each
 *       column already stands on, so a piece can be dropped on ground that is not perfectly flat;</li>
 *   <li><b>functional blocks are stripped</b> ({@code --strip-block-entities true}): spawners, containers,
 *       signs and our own racks are replaced with AIR - the request was "only the building body". Removing
 *       just the NBT would leave an empty spawner cage, which is worse;</li>
 *   <li><b>fire is stripped</b> ({@code --keep-fire false}): with {@code doFireTick=true} a generated
 *       district would set itself on fire. Decoration the user wants (beds, bells, doors, ladders, torches,
 *       terracotta) is kept;</li>
 *   <li>anything else - every block, every state property - is copied verbatim.</li>
 * </ul>
 *
 * <pre>
 *   SaveBuildingExtract --world &lt;copy&gt; --out &lt;dir&gt; --name &lt;piece&gt;
 *       --box x1 y1 z1 x2 y2 z2 [--base-y 63] [--foundation 5] [--min-column-blocks 1]
 *       [--exclude x1 y1 z1 x2 y2 z2]... [--strip-block-entities true] [--keep-fire false]
 * </pre>
 */
public final class SaveBuildingExtract {

    static final int DATA_VERSION = 3465;

    /** Blocks that are a FUNCTION, not a look: replaced with air when stripping is on. */
    static boolean isStripped(String name, boolean strip, boolean keepFire) {
        if (!strip) { return false; }
        if (!keepFire && (name.equals("minecraft:fire") || name.equals("minecraft:soul_fire"))) { return true; }
        if (name.equals("minecraft:mob_spawner") || name.equals("minecraft:spawner")
                || name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest")
                || name.equals("minecraft:barrel") || name.equals("minecraft:hopper")
                || name.equals("minecraft:dispenser") || name.equals("minecraft:dropper")
                || name.equals("minecraft:furnace") || name.equals("minecraft:blast_furnace")
                || name.equals("minecraft:smoker") || name.equals("minecraft:brewing_stand")
                || name.equals("minecraft:lectern") || name.equals("minecraft:ender_chest")
                || name.equals("minecraft:decorated_pot")
                || name.startsWith("tarkovscav:")) {
            return true;
        }
        return name.endsWith("_sign") || name.endsWith("_wall_sign") || name.endsWith("_hanging_sign");
    }

    /** Blocks that may not be used as foundation material even when a column happens to bottom out on one. */
    static boolean foundationHostile(String name) {
        return name.contains("glass") || name.endsWith("_pane") || name.endsWith("_door")
                || name.endsWith("_trapdoor") || name.endsWith("_fence") || name.endsWith("_fence_gate")
                || name.endsWith("_slab") || name.endsWith("_stairs") || name.endsWith("_wall")
                || name.endsWith("_carpet") || name.endsWith("_bed") || name.endsWith("_banner")
                || name.equals("minecraft:ladder") || name.equals("minecraft:torch")
                || name.equals("minecraft:soul_torch") || name.equals("minecraft:lantern")
                || name.equals("minecraft:chain") || name.equals("minecraft:iron_bars")
                || name.equals("minecraft:bell") || name.equals("minecraft:fire")
                || name.equals("minecraft:soul_fire") || name.endsWith("_leaves")
                || name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_sapling")
                || name.endsWith("_flower") || name.endsWith("_bush");
    }

    public static void main(String[] args) throws Exception {
        Path world = null, outDir = null, report = null;
        String name = null;
        int[] box = null;
        int baseY = 63;
        int foundation = 5;
        int minColumnBlocks = 1;
        boolean strip = true;
        boolean keepFire = false;
        boolean noAir = false;
        List<int[]> excludes = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world" -> world = Path.of(args[++i]);
                case "--out" -> outDir = Path.of(args[++i]);
                case "--name" -> name = args[++i];
                case "--base-y" -> baseY = Integer.parseInt(args[++i]);
                case "--foundation" -> foundation = Integer.parseInt(args[++i]);
                case "--min-column-blocks" -> minColumnBlocks = Integer.parseInt(args[++i]);
                case "--strip-block-entities" -> strip = Boolean.parseBoolean(args[++i]);
                case "--keep-fire" -> keepFire = Boolean.parseBoolean(args[++i]);
                case "--no-air" -> noAir = Boolean.parseBoolean(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--exclude" -> excludes.add(new int[]{Integer.parseInt(args[++i]),
                        Integer.parseInt(args[++i]), Integer.parseInt(args[++i]),
                        Integer.parseInt(args[++i]), Integer.parseInt(args[++i]), Integer.parseInt(args[++i])});
                case "--box" -> box = new int[]{Integer.parseInt(args[++i]), Integer.parseInt(args[++i]),
                        Integer.parseInt(args[++i]), Integer.parseInt(args[++i]), Integer.parseInt(args[++i]),
                        Integer.parseInt(args[++i])};
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (world == null || outDir == null || name == null || box == null) {
            throw new IllegalArgumentException("--world, --out, --name and --box are required");
        }

        int x1 = box[0], y1 = box[1], z1 = box[2], x2 = box[3], y2 = box[4], z2 = box[5];
        int dx = x2 - x1 + 1, dy = y2 - y1 + 1, dz = z2 - z1 + 1;

        // ---- decode the box out of the region files.
        Map<String, Integer> dictionary = new LinkedHashMap<>();
        List<String> states = new ArrayList<>();
        String[] volume = new String[dx * dy * dz];
        Map<Long, String> blockEntityIds = new TreeMap<>();
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                NbtRewriter.Compound chunk = CitySurvey.readChunk(world, cx, cz);
                if (chunk == null) { continue; }
                NbtRewriter.Tag beTag = chunk.get("block_entities");
                if (beTag != null) {
                    for (NbtRewriter.Tag entry : beTag.asList().items) {
                        NbtRewriter.Compound be = entry.asCompound();
                        if (be.get("x") == null) { continue; }
                        int bx = ((Number) be.get("x").value).intValue();
                        int by = ((Number) be.get("y").value).intValue();
                        int bz = ((Number) be.get("z").value).intValue();
                        if (bx < x1 || bx > x2 || by < y1 || by > y2 || bz < z1 || bz > z2) { continue; }
                        blockEntityIds.put(key(bx, by, bz), String.valueOf(be.get("id").value));
                    }
                }
                NbtRewriter.Tag sectionsTag = chunk.get("sections");
                if (sectionsTag == null) { continue; }
                for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
                    NbtRewriter.Compound section = sectionTag.asCompound();
                    if (section.get("Y") == null || section.get("block_states") == null) { continue; }
                    int sectionY = ((Number) section.get("Y").value).intValue();
                    NbtRewriter.Tag statesTag = section.get("block_states");
                    NbtRewriter.Tag paletteTag = statesTag.asCompound().get("palette");
                    if (paletteTag == null) { continue; }
                    List<NbtRewriter.Tag> palette = paletteTag.asList().items;
                    String[] names = new String[palette.size()];
                    for (int i = 0; i < palette.size(); i++) {
                        names[i] = CitySurvey.stateString(palette.get(i).asCompound());
                    }
                    long[] data = null;
                    NbtRewriter.Tag dataTag = statesTag.asCompound().get("data");
                    if (dataTag != null) { data = (long[]) dataTag.value; }
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
                    int perLong = 64 / bits;
                    long mask = (1L << bits) - 1L;
                    for (int y = 0; y < 16; y++) {
                        int worldY = sectionY * 16 + y;
                        if (worldY < y1 || worldY > y2) { continue; }
                        for (int z = 0; z < 16; z++) {
                            int worldZ = cz * 16 + z;
                            if (worldZ < z1 || worldZ > z2) { continue; }
                            for (int x = 0; x < 16; x++) {
                                int worldX = cx * 16 + x;
                                if (worldX < x1 || worldX > x2) { continue; }
                                int index = (y << 8) | (z << 4) | x;
                                int paletteIndex = data == null ? 0
                                        : (int) ((data[index / perLong] >>> ((index % perLong) * bits)) & mask);
                                if (paletteIndex < 0 || paletteIndex >= names.length) { paletteIndex = 0; }
                                volume[idx(worldX - x1, worldY - y1, worldZ - z1, dx, dz)] = names[paletteIndex];
                            }
                        }
                    }
                }
            }
        }

        // ---- the platform/pavement is not part of the building: everything at or below base-y goes.
        Map<String, Integer> strippedCounts = new TreeMap<>();
        Map<String, Integer> baseCounts = new TreeMap<>();
        for (int y = 0; y < dy; y++) {
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    String state = volume[idx(x, y, z, dx, dz)];
                    if (state == null) { continue; }
                    String simple = CitySurvey.blockName(state);
                    if (y + y1 <= baseY) {
                        volume[idx(x, y, z, dx, dz)] = null;
                        baseCounts.merge(simple, 1, Integer::sum);
                        continue;
                    }
                    boolean inExclude = false;
                    for (int[] e : excludes) {
                        int ex = x + x1, ey = y + y1, ez = z + z1;
                        if (ex >= e[0] && ex <= e[3] && ey >= e[1] && ey <= e[4] && ez >= e[2] && ez <= e[5]) {
                            inExclude = true;
                            break;
                        }
                    }
                    if (inExclude) {
                        volume[idx(x, y, z, dx, dz)] = null;
                        strippedCounts.merge("(excluded) " + simple, 1, Integer::sum);
                        continue;
                    }
                    if (isStripped(simple, strip, keepFire)) {
                        volume[idx(x, y, z, dx, dz)] = null;
                        strippedCounts.merge(simple, 1, Integer::sum);
                        if (blockEntityIds.containsKey(key(x + x1, y + y1, z + z1))) {
                            strippedCounts.merge(simple + " [block entity]", 1, Integer::sum);
                        }
                    }
                }
            }
        }

        // ---- columns that are only pavement disappear (used by the wall/street pieces).
        if (minColumnBlocks > 1) {
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    int blocks = 0;
                    for (int y = 0; y < dy; y++) {
                        if (volume[idx(x, y, z, dx, dz)] != null) { blocks++; }
                    }
                    if (blocks >= minColumnBlocks) { continue; }
                    for (int y = 0; y < dy; y++) {
                        if (volume[idx(x, y, z, dx, dz)] != null) {
                            strippedCounts.merge("(thin column) " + CitySurvey.blockName(volume[idx(x, y, z, dx, dz)]),
                                    1, Integer::sum);
                            volume[idx(x, y, z, dx, dz)] = null;
                        }
                    }
                }
            }
        }

        // ---- trim the PURE-AIR border: the bounds come from the non-air blocks, but the air INSIDE those
        // bounds is kept (it is what carves the building's interior out of the terrain it lands on).
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = 0; y < dy; y++) {
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    String state = volume[idx(x, y, z, dx, dz)];
                    if (state == null || CitySurvey.isAir(CitySurvey.blockName(state))) { continue; }
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (minX > maxX) { throw new IllegalStateException("piece " + name + " is empty"); }
        int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;

        // ---- the foundation: grow DOWNWARDS out of the material each column bottoms out on.
        String[][][] grown = new String[w][h + foundation][d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    grown[x][y + foundation][z] = volume[idx(minX + x, minY + y, minZ + z, dx, dz)];
                }
            }
        }
        Map<String, Integer> bottomMaterials = new TreeMap<>();
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                String material = null;
                for (int y = 0; y < h; y++) {
                    String state = grown[x][y + foundation][z];
                    if (state != null && !CitySurvey.isAir(CitySurvey.blockName(state))) {
                        material = state;
                        break;
                    }
                }
                if (material != null && !foundationHostile(CitySurvey.blockName(material))) {
                    bottomMaterials.merge(material, 1, Integer::sum);
                }
            }
        }
        String fallback = bottomMaterials.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                String material = null;
                for (int y = 0; y < h; y++) {
                    String state = grown[x][y + foundation][z];
                    if (state != null && !CitySurvey.isAir(CitySurvey.blockName(state))) {
                        material = state;
                        break;
                    }
                }
                if (material == null) { continue; }
                if (foundationHostile(CitySurvey.blockName(material))) { material = fallback; }
                if (material == null) { continue; }
                for (int i = 1; i <= foundation; i++) {
                    grown[x][foundation - i][z] = material;
                }
            }
        }

        // ---- compose the structure NBT.
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int totalHeight = heightOf(w, d, h + foundation, grown);
        for (int y = 0; y < totalHeight; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    String state = grown[x][y][z];
                    if (state == null) { continue; }
                    // --no-air: a wall or a rubble pile must only ADD blocks - air cells would carve a hole
                    // in whatever the piece lands on, which for a 66x27x80 wall ring would be the whole site.
                    if (noAir && CitySurvey.isAir(CitySurvey.blockName(state))) { continue; }
                    Integer at = paletteIndex.get(state);
                    if (at == null) {
                        at = palette.size();
                        paletteIndex.put(state, at);
                        palette.add(state);
                    }
                    positions.add(new int[]{x, y, z});
                    indices.add(at);
                }
            }
        }

        NbtRewriter.Compound root = new NbtRewriter.Compound();
        root.put("DataVersion", new NbtRewriter.Tag(NbtRewriter.INT, DATA_VERSION));
        root.put("author", new NbtRewriter.Tag(NbtRewriter.STRING, "tarkovscav SaveBuildingExtract (extracted "
                + "from the user's save; see README section 7)"));
        NbtRewriter.ListTag size = new NbtRewriter.ListTag(NbtRewriter.INT);
        size.items.add(new NbtRewriter.Tag(NbtRewriter.INT, w));
        size.items.add(new NbtRewriter.Tag(NbtRewriter.INT, totalHeight));
        size.items.add(new NbtRewriter.Tag(NbtRewriter.INT, d));
        root.put("size", new NbtRewriter.Tag(NbtRewriter.LIST, size));
        NbtRewriter.ListTag paletteTag = new NbtRewriter.ListTag(NbtRewriter.COMPOUND);
        for (String state : palette) {
            paletteTag.items.add(new NbtRewriter.Tag(NbtRewriter.COMPOUND, paletteEntry(state)));
        }
        root.put("palette", new NbtRewriter.Tag(NbtRewriter.LIST, paletteTag));
        NbtRewriter.ListTag blocksTag = new NbtRewriter.ListTag(NbtRewriter.COMPOUND);
        for (int i = 0; i < positions.size(); i++) {
            int[] pos = positions.get(i);
            NbtRewriter.Compound block = new NbtRewriter.Compound();
            NbtRewriter.ListTag p = new NbtRewriter.ListTag(NbtRewriter.INT);
            p.items.add(new NbtRewriter.Tag(NbtRewriter.INT, pos[0]));
            p.items.add(new NbtRewriter.Tag(NbtRewriter.INT, pos[1]));
            p.items.add(new NbtRewriter.Tag(NbtRewriter.INT, pos[2]));
            block.put("pos", new NbtRewriter.Tag(NbtRewriter.LIST, p));
            block.put("state", new NbtRewriter.Tag(NbtRewriter.INT, indices.get(i)));
            blocksTag.items.add(new NbtRewriter.Tag(NbtRewriter.COMPOUND, block));
        }
        root.put("blocks", new NbtRewriter.Tag(NbtRewriter.LIST, blocksTag));
        root.put("entities", new NbtRewriter.Tag(NbtRewriter.LIST, new NbtRewriter.ListTag(NbtRewriter.COMPOUND)));

        Files.createDirectories(outDir);
        Path target = outDir.resolve(name + ".nbt");
        NbtRewriter.writeGzip(target, "", new NbtRewriter.Tag(NbtRewriter.COMPOUND, root));

        // ---- report.
        Map<String, Integer> kept = new TreeMap<>();
        for (String state : allStates(grown, w, d, h + foundation)) {
            if (state == null) { continue; }
            if (noAir && CitySurvey.isAir(CitySurvey.blockName(state))) { continue; }
            kept.merge(CitySurvey.blockName(state), 1, Integer::sum);
        }
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-22s %-6s source=x[%d..%d] y[%d..%d] z[%d..%d] size=%dx%dx%d blocks=%d "
                        + "palette=%d foundation=%d baseY=%d",
                name, Path.of(name).getFileName(), x1, x2, y1, y2, z1, z2, w, totalHeight, d,
                positions.size(), palette.size(), foundation, baseY));
        System.out.println(line);
        System.out.println("  kept: " + top(kept, 10));
        System.out.println("  stripped (" + strippedCounts.values().stream().mapToInt(Integer::intValue).sum()
                + " blocks): " + top(strippedCounts, 12));
        System.out.println("  platform/base-y removed: " + baseCounts.values().stream()
                .mapToInt(Integer::intValue).sum() + " blocks " + top(baseCounts, 6));
        System.out.println("  file: " + target + " (" + Files.size(target) + " bytes)");
        if (report != null) {
            Files.createDirectories(report.getParent());
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(report,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
                writer.println(line);
                writer.println("  kept: " + top(kept, 14));
                writer.println("  stripped: " + top(strippedCounts, 14));
                writer.println("  base: " + top(baseCounts, 8));
            }
        }
    }

    static int heightOf(int w, int d, int total, String[][][] grown) {
        int highest = 0;
        for (int y = 0; y < total; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    if (grown[x][y][z] != null) { highest = y + 1; break; }
                }
            }
        }
        return highest;
    }

    static List<String> allStates(String[][][] grown, int w, int d, int total) {
        List<String> out = new ArrayList<>();
        for (int y = 0; y < total; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    if (grown[x][y][z] != null) { out.add(grown[x][y][z]); }
                }
            }
        }
        return out;
    }

    /** "minecraft:oak_stairs[facing=north,...]" -> {Name, Properties}. */
    static NbtRewriter.Compound paletteEntry(String state) {
        NbtRewriter.Compound entry = new NbtRewriter.Compound();
        int bracket = state.indexOf('[');
        String name = bracket < 0 ? state : state.substring(0, bracket);
        entry.put("Name", new NbtRewriter.Tag(NbtRewriter.STRING, name));
        if (bracket >= 0) {
            String props = state.substring(bracket + 1, state.length() - 1);
            NbtRewriter.Compound properties = new NbtRewriter.Compound();
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) { continue; }
                properties.put(pair.substring(0, eq),
                        new NbtRewriter.Tag(NbtRewriter.STRING, pair.substring(eq + 1)));
            }
            entry.put("Properties", new NbtRewriter.Tag(NbtRewriter.COMPOUND, properties));
        }
        return entry;
    }

    /** Volume index: y-major, then z, then x - the same layout CitySurvey uses. */
    static int idx(int x, int y, int z, int dx, int dz) {
        return (y * dz + z) * dx + x;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    static String top(Map<String, Integer> map, int limit) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + " x" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

    private SaveBuildingExtract() {
    }
}
