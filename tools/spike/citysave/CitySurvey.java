import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * M1 of the city work: a read-only survey of a box of a saved world, per BUILDING.
 *
 * <p>It answers, with numbers a human can check: where each building stands (bounding box), how many
 * floors it has, what its walls / roof / floor slabs are made of, whether it sits on a flat platform that
 * must be excluded when it is extracted into a structure, what is inside its ground floor, and which block
 * entities (chests, spawners, signs, our weapon racks) would have to travel with a structure NBT.</p>
 *
 * <pre>
 *   CitySurvey &lt;worldDir&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;out.json&gt;
 * </pre>
 *
 * <p>Everything is decoded out of a COPY of the region files (never the live save) and nothing is written
 * back; the only file it creates is the JSON report.</p>
 */
public final class CitySurvey {

    /** Blocks that make up a "platform" a builder puts a whole district on (excluded from the buildings). */
    static final List<String> PLATFORM_BLOCKS = List.of(
            "minecraft:smooth_stone", "minecraft:smooth_stone_slab", "minecraft:stone_slab",
            "minecraft:stone", "minecraft:polished_andesite", "minecraft:polished_andesite_slab",
            "minecraft:andesite", "minecraft:sandstone", "minecraft:smooth_sandstone",
            "minecraft:red_sandstone", "minecraft:smooth_red_sandstone", "minecraft:gray_concrete",
            "minecraft:light_gray_concrete", "minecraft:black_concrete");

    /** Street furniture / cover: things that are not a building wall but somebody placed on purpose. */
    static final List<String> COVER_BLOCKS = List.of(
            "minecraft:cobblestone_wall", "minecraft:iron_bars", "minecraft:glass_pane",
            "minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:dark_oak_fence",
            "minecraft:nether_brick_fence", "minecraft:chain", "minecraft:cobweb",
            "minecraft:barrier", "minecraft:lightning_rod", "minecraft:end_rod",
            "minecraft:scaffolding", "minecraft:ladder", "minecraft:torch", "minecraft:soul_torch",
            "minecraft:lantern", "minecraft:barrel", "minecraft:crafting_table", "minecraft:furnace",
            "minecraft:cauldron", "minecraft:anvil", "minecraft:composter", "minecraft:bell",
            "minecraft:campfire", "minecraft:hay_block", "minecraft:white_wool", "minecraft:terracotta");

    /** Blocks that only ever appear in a BUILT wall (as opposed to the platform/terrain the district sits on). */
    static final List<String> WALL_BLOCKS = List.of(
            "minecraft:light_gray_concrete", "minecraft:gray_concrete", "minecraft:black_concrete",
            "minecraft:brown_concrete", "minecraft:white_concrete", "minecraft:red_concrete",
            "minecraft:terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
            "minecraft:brown_terracotta", "minecraft:light_gray_terracotta", "minecraft:red_terracotta",
            "minecraft:yellow_terracotta", "minecraft:deepslate_tiles", "minecraft:deepslate_bricks",
            "minecraft:stone_bricks", "minecraft:bricks", "minecraft:glass_pane", "minecraft:glass",
            "minecraft:stripped_dark_oak_log", "minecraft:dark_oak_planks", "minecraft:oak_planks",
            "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:iron_bars",
            "minecraft:dark_oak_door", "minecraft:spruce_door", "minecraft:oak_door", "minecraft:iron_door",
            "minecraft:ladder", "minecraft:white_wool", "minecraft:chain", "minecraft:oak_fence",
            "minecraft:dark_oak_fence", "minecraft:spruce_fence");

    static boolean isWallish(String name) {
        return WALL_BLOCKS.contains(name);
    }

    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
        int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
        Path out = Path.of(args[7]);

        int dx = x2 - x1 + 1, dy = y2 - y1 + 1, dz = z2 - z1 + 1;
        Map<String, Integer> dictionary = new LinkedHashMap<>();
        List<String> states = new ArrayList<>();
        int[] volume = new int[dx * dy * dz];
        Map<Long, Be> blockEntities = new TreeMap<>();

        int chunksRead = 0, chunksAbsent = 0;
        Map<String, Integer> rawHistogram = new TreeMap<>();
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                NbtRewriter.Compound chunk = readChunk(world, cx, cz);
                if (chunk == null) { chunksAbsent++; continue; }
                chunksRead++;
                readBlockEntities(chunk, x1, y1, z1, x2, y2, z2, blockEntities);
                NbtRewriter.Tag sectionsTag = chunk.get("sections");
                if (sectionsTag == null) { continue; }
                for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
                    NbtRewriter.Compound section = sectionTag.asCompound();
                    NbtRewriter.Tag yTag = section.get("Y");
                    NbtRewriter.Tag statesTag = section.get("block_states");
                    if (yTag == null || statesTag == null) { continue; }
                    int sectionY = ((Number) yTag.value).intValue();
                    NbtRewriter.Tag paletteTag = statesTag.asCompound().get("palette");
                    if (paletteTag == null) { continue; }
                    List<NbtRewriter.Tag> palette = paletteTag.asList().items;
                    int[] ids = new int[palette.size()];
                    for (int i = 0; i < palette.size(); i++) {
                        String state = stateString(palette.get(i).asCompound());
                        String name = blockName(state);
                        rawHistogram.merge(name, 1, Integer::sum);
                        Integer id = dictionary.get(state);
                        if (id == null) {
                            id = states.size();
                            dictionary.put(state, id);
                            states.add(state);
                        }
                        ids[i] = id;
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
                                int paletteIndex;
                                if (data == null) {
                                    paletteIndex = 0;
                                } else {
                                    int longIndex = index / perLong;
                                    int offset = (index % perLong) * bits;
                                    paletteIndex = (int) ((data[longIndex] >>> offset) & mask);
                                }
                                if (paletteIndex < 0 || paletteIndex >= ids.length) { paletteIndex = 0; }
                                volume[((worldY - y1) * dz + (worldZ - z1)) * dx + (worldX - x1)] =
                                        ids[paletteIndex];
                            }
                        }
                    }
                }
            }
        }

        System.out.println("world=" + world);
        System.out.println("box=x[" + x1 + ".." + x2 + "] y[" + y1 + ".." + y2 + "] z[" + z1 + ".." + z2 + "]");
        System.out.println("chunksRead=" + chunksRead + " chunksAbsent=" + chunksAbsent);

        Survey survey = new Survey(x1, y1, z1, x2, y2, z2, dx, dy, dz, states, volume, blockEntities);

        // ---- the platform: the flat layer the district was built on, if there is one.
        TreeMap<Integer, Integer> platformByY = new TreeMap<>();
        for (int y = 0; y < dy; y++) {
            int count = 0;
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    String name = survey.nameAt(x, y, z);
                    if (isPlatform(name)) { count++; }
                }
            }
            if (count > (dx * dz) / 4) { platformByY.merge(y + y1, count, Integer::sum); }
        }
        System.out.println("platform candidate levels (block coverage > 25% of the box): " + platformByY);

        // ---- what the street itself is made of: the layer at the platform top and the one above it.
        int surfaceY = platformTopOf(platformByY);
        Map<String, Map<String, Integer>> surfaceLayers = new LinkedHashMap<>();
        for (int y : new int[]{surfaceY, surfaceY + 1}) {
            if (y < y1 || y > y2) { continue; }
            Map<String, Integer> layer = new TreeMap<>();
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    String name = survey.nameAt(x, y - y1, z);
                    if (!isAir(name)) { layer.merge(name, 1, Integer::sum); }
                }
            }
            surfaceLayers.put("y" + y, layer);
            System.out.println("street layer y=" + y + ": " + top(layer, 8));
        }

        // ---- buildings: columns with enough stacked solid blocks, flood filled.
        int platformTop = surfaceY;
        List<Building> buildings = survey.findBuildings(platformTop);
        System.out.println("buildings found: " + buildings.size());
        System.out.println();
        for (Building b : buildings) {
            survey.describe(b, platformTop);
        }

        // ---- covers / street furniture: what the builder placed OUTSIDE the walls, in the few blocks above
        // the street. That is the band the user's "掩体" live in, and it is what M2 turns into decor pieces.
        Map<String, Integer> coverBlocks = new TreeMap<>();
        Map<Integer, Integer> coverByY = new TreeMap<>();
        boolean[] wallColumn = survey.wallColumns;
        List<Cover> covers = new ArrayList<>();
        boolean[] coverCell = new boolean[dx * dz * dy];
        for (int y = platformTop + 2; y <= Math.min(platformTop + 6, y2); y++) {
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    if (wallColumn[z * dx + x]) { continue; }
                    String name = survey.nameAt(x, y - y1, z);
                    if (isAir(name) || isPlatform(name)) { continue; }
                    coverBlocks.merge(name, 1, Integer::sum);
                    coverByY.merge(y, 1, Integer::sum);
                    coverCell[(y - y1) * dx * dz + z * dx + x] = true;
                }
            }
        }
        // cluster the cover cells (6-connected) so the report can say "a pile of rubble at x,y,z .. x,y,z"
        boolean[] seen = new boolean[coverCell.length];
        for (int y = platformTop + 2; y <= Math.min(platformTop + 6, y2); y++) {
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    int start = (y - y1) * dx * dz + z * dx + x;
                    if (!coverCell[start] || seen[start]) { continue; }
                    Deque<int[]> queue = new ArrayDeque<>();
                    queue.add(new int[]{x, y, z});
                    seen[start] = true;
                    Map<String, Integer> blocks = new TreeMap<>();
                    int minX = x, maxX = x, minY = y, maxY = y, minZ = z, maxZ = z, count = 0;
                    while (!queue.isEmpty()) {
                        int[] cur = queue.poll();
                        count++;
                        minX = Math.min(minX, cur[0]); maxX = Math.max(maxX, cur[0]);
                        minY = Math.min(minY, cur[1]); maxY = Math.max(maxY, cur[1]);
                        minZ = Math.min(minZ, cur[2]); maxZ = Math.max(maxZ, cur[2]);
                        blocks.merge(survey.nameAt(cur[0], cur[1] - y1, cur[2]), 1, Integer::sum);
                        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
                                {0, 0, 1}, {0, 0, -1}}) {
                            int nx = cur[0] + d[0], ny = cur[1] + d[1], nz = cur[2] + d[2];
                            if (nx < 0 || nx >= dx || nz < 0 || nz >= dz || ny < y1 || ny > y2) { continue; }
                            int at = (ny - y1) * dx * dz + nz * dx + nx;
                            if (!coverCell[at] || seen[at]) { continue; }
                            seen[at] = true;
                            queue.add(new int[]{nx, ny, nz});
                        }
                    }
                    if (count < 4) { continue; }
                    covers.add(new Cover(minX + x1, maxX + x1, minY, maxY, minZ + z1, maxZ + z1, count, blocks));
                }
            }
        }
        covers.sort(Comparator.comparingInt((Cover c) -> c.x1).thenComparingInt(c -> c.z1));
        System.out.println();
        System.out.println("street furniture / cover in the band y" + (platformTop + 2) + ".."
                + Math.min(platformTop + 6, y2) + " (outside every wall column): " + top(coverBlocks, 12));
        System.out.println("  per Y: " + coverByY);
        System.out.println("  clusters (>= 4 blocks): " + covers.size());
        for (Cover c : covers) {
            System.out.println("    " + c.id() + "  x[" + c.x1 + ".." + c.x2 + "] y[" + c.y1 + ".." + c.y2
                    + "] z[" + c.z1 + ".." + c.z2 + "]  n=" + c.count + "  " + top(c.blocks, 5));
        }

        survey.writeJson(out, platformTop, platformByY, rawHistogram, chunksRead, chunksAbsent, buildings,
                coverBlocks, coverByY, surfaceLayers, covers);
        System.out.println();
        System.out.println("json: " + out);

        // Optional ASCII map of the clusters, so the extraction step can see the district's shape.
        if (args.length > 8 && args[8].equals("map")) {
            boolean[] buildingColumn = new boolean[dx * dz];
            for (Building b : buildings) {
                // Only the compact ones (a real building, not the enclosure blob that spans the district).
                if ((b.x2 - b.x1 + 1) * (b.z2 - b.z1 + 1) > 1000) { continue; }
                for (int z = b.z1; z <= b.z2; z++) {
                    for (int x = b.x1; x <= b.x2; x++) { buildingColumn[(z - z1) * dx + (x - x1)] = true; }
                }
            }
            System.out.println();
            System.out.println("=== cluster map ('#' = wall column outside any detected building, 'o' = inside "
                    + "one) - x across (left = " + x1 + "), z down (top = " + z1 + ") ===");
            StringBuilder header = new StringBuilder("      ");
            for (int x = 0; x < dx; x++) {
                header.append((x1 + x) % 10 == 0 ? '|' : ' ');
            }
            System.out.println(header);
            for (int z = 0; z < dz; z++) {
                StringBuilder row = new StringBuilder(String.format("%5d ", z1 + z));
                for (int x = 0; x < dx; x++) {
                    row.append(buildingColumn[z * dx + x] ? 'o'
                            : (survey.wallColumns[z * dx + x] ? '#' : '.'));
                }
                System.out.println(row);
            }
        }
    }

    // ------------------------------------------------------------------ model

    record Be(String id, int x, int y, int z, boolean hasItems) {}

    /** A cluster of cover / street furniture blocks (rubble, sandbag-ish piles, fences, barrels). */
    static final class Cover {
        final int x1, x2, y1, y2, z1, z2, count;
        final Map<String, Integer> blocks;

        Cover(int x1, int x2, int y1, int y2, int z1, int z2, int count, Map<String, Integer> blocks) {
            this.x1 = x1; this.x2 = x2; this.y1 = y1; this.y2 = y2; this.z1 = z1; this.z2 = z2;
            this.count = count; this.blocks = blocks;
        }

        String id() { return String.format("c_%d_%d_%d", this.x1, this.y1, this.z1); }
    }

    static final class Survey {
        final int x1, y1, z1, x2, y2, z2, dx, dy, dz;
        final List<String> states;
        final int[] volume;
        final Map<Long, Be> blockEntities;
        /** True for every column that belongs to a detected building/enclosure, so covers can exclude them. */
        boolean[] wallColumns = new boolean[0];

        Survey(int x1, int y1, int z1, int x2, int y2, int z2, int dx, int dy, int dz,
               List<String> states, int[] volume, Map<Long, Be> blockEntities) {
            this.x1 = x1; this.y1 = y1; this.z1 = z1; this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.states = states; this.volume = volume; this.blockEntities = blockEntities;
        }

        int idx(int x, int y, int z) { return (y * dz + z) * dx + x; }

        String stateAt(int x, int y, int z) {
            return this.states.get(this.volume[idx(x, y, z)]);
        }

        String nameAt(int x, int y, int z) { return blockName(stateAt(x, y, z)); }

        boolean solidAt(int x, int y, int z) { return !isAir(nameAt(x, y, z)); }

        /** Per-column: highest and lowest solid y inside the box. */
        int[] columnTop() {
            int[] top = new int[dx * dz];
            for (int i = 0; i < top.length; i++) { top[i] = Integer.MIN_VALUE; }
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    for (int y = dy - 1; y >= 0; y--) {
                        if (solidAt(x, y, z)) { top[z * dx + x] = y + y1; break; }
                    }
                }
            }
            return top;
        }

        List<Building> findBuildings(int platformTop) {
            int[] top = columnTop();
            boolean[] isBuildingColumn = new boolean[dx * dz];
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    int columnTop = top[z * dx + x];
                    if (columnTop == Integer.MIN_VALUE) { continue; }
                    int floor = platformTop >= 0 ? platformTop : y1;
                    int stacked = 0;
                    boolean wallish = false;
                    for (int y = floor + 1; y <= columnTop; y++) {
                        String name = nameAt(x, y - y1, z);
                        if (!isAir(name)) { stacked++; }
                        if (isWallish(name)) { wallish = true; }
                    }
                    // A wall is >= 8 blocks of stacked material above the platform and contains at least one
                    // block that only a builder places: the district's own flat platform (polished andesite
                    // steps, smooth stone kerbs) is therefore NOT a building, which is what keeps the whole
                    // district from collapsing into one blob.
                    isBuildingColumn[z * dx + x] = stacked >= 8 && wallish;
                }
            }
            boolean[] seen = new boolean[dx * dz];
            this.wallColumns = isBuildingColumn;
            List<Building> out = new ArrayList<>();
            for (int z = 0; z < dz; z++) {
                for (int x = 0; x < dx; x++) {
                    if (!isBuildingColumn[z * dx + x] || seen[z * dx + x]) { continue; }
                    Deque<int[]> queue = new ArrayDeque<>();
                    queue.add(new int[]{x, z});
                    seen[z * dx + x] = true;
                    List<int[]> members = new ArrayList<>();
                    while (!queue.isEmpty()) {
                        int[] cur = queue.poll();
                        members.add(cur);
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int nx = cur[0] + d[0], nz = cur[1] + d[1];
                            if (nx < 0 || nx >= dx || nz < 0 || nz >= dz) { continue; }
                            if (!isBuildingColumn[nz * dx + nx] || seen[nz * dx + nx]) { continue; }
                            seen[nz * dx + nx] = true;
                            queue.add(new int[]{nx, nz});
                        }
                    }
                    if (members.size() < 12) { continue; }
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                    for (int[] m : members) {
                        minX = Math.min(minX, m[0]); maxX = Math.max(maxX, m[0]);
                        minZ = Math.min(minZ, m[1]); maxZ = Math.max(maxZ, m[1]);
                    }
                    int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                    for (int zz = minZ; zz <= maxZ; zz++) {
                        for (int xx = minX; xx <= maxX; xx++) {
                            int columnTop = top[zz * dx + xx];
                            if (columnTop == Integer.MIN_VALUE) { continue; }
                            maxY = Math.max(maxY, columnTop);
                            int floor = platformTop >= 0 ? platformTop : y1;
                            for (int y = floor + 1; y <= columnTop; y++) {
                                if (solidAt(xx, y - y1, zz)) { minY = Math.min(minY, y); break; }
                            }
                        }
                    }
                    out.add(new Building(members, minX + x1, maxX + x1, minY, maxY, minZ + z1, maxZ + z1));
                }
            }
            out.sort(Comparator.comparingInt((Building b) -> b.x1).thenComparingInt(b -> b.z1));
            return out;
        }

        /** Occupancy per Y inside a building box: how many columns carry a block at that level. */
        Map<Integer, Integer> occupancy(Building b) {
            Map<Integer, Integer> out = new TreeMap<>();
            for (int y = b.y1; y <= b.y2; y++) {
                int count = 0;
                for (int z = b.z1; z <= b.z2; z++) {
                    for (int x = b.x1; x <= b.x2; x++) {
                        if (solidAt(x - x1, y - y1, z - z1)) { count++; }
                    }
                }
                out.put(y, count);
            }
            return out;
        }

        boolean perimeter(int x, int z, Building b) {
            return x == b.x1 || x == b.x2 || z == b.z1 || z == b.z2;
        }

        void describe(Building b, int platformTop) {
            Map<Integer, Integer> occ = occupancy(b);
            int maxOcc = occ.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            int area = (b.x2 - b.x1 + 1) * (b.z2 - b.z1 + 1);
            List<Integer> floorLevels = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : occ.entrySet()) {
                if (e.getValue() >= Math.max(4, (int) (maxOcc * 0.6D))) { floorLevels.add(e.getKey()); }
            }
            Map<String, Integer> walls = new TreeMap<>();
            Map<String, Integer> roof = new TreeMap<>();
            Map<String, Integer> floorBlocks = new TreeMap<>();
            for (int y = b.y1; y <= b.y2; y++) {
                boolean isFloorLevel = floorLevels.contains(y);
                for (int z = b.z1; z <= b.z2; z++) {
                    for (int x = b.x1; x <= b.x2; x++) {
                        String name = nameAt(x - x1, y - y1, z - z1);
                        if (isAir(name)) { continue; }
                        if (y == b.y2) { roof.merge(name, 1, Integer::sum); }
                        else if (perimeter(x, z, b)) { walls.merge(name, 1, Integer::sum); }
                        else if (isFloorLevel && y != b.y2) { floorBlocks.merge(name, 1, Integer::sum); }
                    }
                }
            }
            System.out.println("building " + b.id() + "  x[" + b.x1 + ".." + b.x2 + "] y[" + b.y1 + ".." + b.y2
                    + "] z[" + b.z1 + ".." + b.z2 + "]  size=" + (b.x2 - b.x1 + 1) + "x" + (b.y2 - b.y1 + 1)
                    + "x" + (b.z2 - b.z1 + 1) + "  footprint=" + area + " columns=" + b.columns.size());
            System.out.println("  floor levels (Y): " + floorLevels);
            System.out.println("  walls : " + top(walls, 6));
            System.out.println("  roof  : " + top(roof, 4));
            System.out.println("  floor slabs: " + top(floorBlocks, 4));
            System.out.println("  base platform under it: " + (platformTop >= 0 ? "y<=" + platformTop : "(none)"));
            // ground floor interior: inside the perimeter, between the platform and the first floor level + 4
            int firstFloor = floorLevels.isEmpty() ? b.y1 + 3 : floorLevels.get(0);
            Map<String, Integer> interior = new TreeMap<>();
            Map<String, Integer> cover = new TreeMap<>();
            for (int y = (platformTop >= 0 ? platformTop + 1 : b.y1); y <= Math.min(firstFloor + 4, b.y2); y++) {
                for (int z = b.z1 + 1; z <= b.z2 - 1; z++) {
                    for (int x = b.x1 + 1; x <= b.x2 - 1; x++) {
                        String name = nameAt(x - x1, y - y1, z - z1);
                        if (isAir(name)) { continue; }
                        interior.merge(name, 1, Integer::sum);
                    }
                }
            }
            System.out.println("  ground-floor interior: " + top(interior, 10));
            System.out.println("  per-storey interior blocks:");
            for (int i = 0; i < floorLevels.size(); i++) {
                int from = floorLevels.get(i) + 1;
                int to = (i + 1 < floorLevels.size()) ? floorLevels.get(i + 1) - 1 : Math.min(from + 3, b.y2);
                Map<String, Integer> storey = new TreeMap<>();
                for (int y = from; y <= to; y++) {
                    for (int z = b.z1 + 1; z <= b.z2 - 1; z++) {
                        for (int x = b.x1 + 1; x <= b.x2 - 1; x++) {
                            String name = nameAt(x - x1, y - y1, z - z1);
                            if (isAir(name)) { continue; }
                            storey.merge(name, 1, Integer::sum);
                        }
                    }
                }
                System.out.println("    storey " + (i + 1) + " (y" + from + ".." + to + "): " + top(storey, 8));
            }
            List<String> bes = new ArrayList<>();
            for (Be be : this.blockEntities.values()) {
                if (be.x >= b.x1 && be.x <= b.x2 && be.z >= b.z1 && be.z <= b.z2
                        && be.y >= b.y1 && be.y <= b.y2) {
                    bes.add(be.id + "@" + be.x + "," + be.y + "," + be.z + (be.hasItems ? "[Items]" : ""));
                }
            }
            System.out.println("  block entities: " + (bes.isEmpty() ? "(none)" : bes));
        }

        void writeJson(Path out, int platformTop, Map<Integer, Integer> platformByY,
                       Map<String, Integer> rawHistogram, int chunksRead, int chunksAbsent,
                       List<Building> buildings, Map<String, Integer> coverBlocks,
                       Map<Integer, Integer> coverByY, Map<String, Map<String, Integer>> surfaceLayers,
                       List<Cover> covers) throws IOException {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("{");
                w.println("  \"box\": {\"x1\": " + x1 + ", \"y1\": " + y1 + ", \"z1\": " + z1
                        + ", \"x2\": " + x2 + ", \"y2\": " + y2 + ", \"z2\": " + z2 + "},");
                w.println("  \"size\": {\"dx\": " + dx + ", \"dy\": " + dy + ", \"dz\": " + dz + "},");
                w.println("  \"chunksRead\": " + chunksRead + ", \"chunksAbsent\": " + chunksAbsent + ",");
                w.println("  \"platformTopY\": " + platformTop + ",");
                w.println("  \"platformLevels\": " + mapJson(platformByY) + ",");
                w.println("  \"paletteOccurrences\": " + mapJson(rawHistogram) + ",");
                w.println("  \"streetFurniture\": " + mapJson(coverBlocks) + ",");
                w.println("  \"streetFurnitureByY\": " + mapJson(coverByY) + ",");
                w.print("  \"coverClusters\": [");
                boolean firstCover = true;
                for (Cover c : covers) {
                    if (!firstCover) { w.print(", "); }
                    firstCover = false;
                    w.print("\n    {\"id\": \"" + c.id() + "\", \"x1\": " + c.x1 + ", \"x2\": " + c.x2
                            + ", \"y1\": " + c.y1 + ", \"y2\": " + c.y2 + ", \"z1\": " + c.z1
                            + ", \"z2\": " + c.z2 + ", \"blocks\": " + c.count + ", \"palette\": "
                            + mapJson(c.blocks) + "}");
                }
                w.println(firstCover ? "]," : "\n  ],");
                w.print("  \"surfaceLayers\": {");
                boolean firstLayer = true;
                for (Map.Entry<String, Map<String, Integer>> e : surfaceLayers.entrySet()) {
                    if (!firstLayer) { w.print(", "); }
                    firstLayer = false;
                    w.print("\"" + e.getKey() + "\": " + mapJson(e.getValue()));
                }
                w.println("},");
                w.print("  \"blockEntities\": [");
                boolean first = true;
                for (Be be : this.blockEntities.values()) {
                    if (!first) { w.print(", "); }
                    first = false;
                    w.print("\n    {\"id\": \"" + be.id + "\", \"x\": " + be.x + ", \"y\": " + be.y
                            + ", \"z\": " + be.z + ", \"hasItems\": " + be.hasItems + "}");
                }
                w.println(first ? "]," : "\n  ],");
                w.println("  \"buildings\": [");
                for (int i = 0; i < buildings.size(); i++) {
                    Building b = buildings.get(i);
                    Map<Integer, Integer> occ = occupancy(b);
                    int maxOcc = occ.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                    List<Integer> floors = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> e : occ.entrySet()) {
                        if (e.getValue() >= Math.max(4, (int) (maxOcc * 0.6D))) { floors.add(e.getKey()); }
                    }
                    Map<String, Integer> walls = new TreeMap<>();
                    Map<String, Integer> roof = new TreeMap<>();
                    Map<String, Integer> slab = new TreeMap<>();
                    for (int y = b.y1; y <= b.y2; y++) {
                        boolean isFloorLevel = floors.contains(y);
                        for (int z = b.z1; z <= b.z2; z++) {
                            for (int x = b.x1; x <= b.x2; x++) {
                                String name = nameAt(x - x1, y - y1, z - z1);
                                if (isAir(name)) { continue; }
                                if (y == b.y2) { roof.merge(name, 1, Integer::sum); }
                                else if (perimeter(x, z, b)) { walls.merge(name, 1, Integer::sum); }
                                else if (isFloorLevel) { slab.merge(name, 1, Integer::sum); }
                            }
                        }
                    }
                    int firstFloor = floors.isEmpty() ? b.y1 + 3 : floors.get(0);
                    Map<String, Integer> interior = new TreeMap<>();
                    for (int y = (platformTop >= 0 ? platformTop + 1 : b.y1);
                         y <= Math.min(firstFloor + 4, b.y2); y++) {
                        for (int z = b.z1 + 1; z <= b.z2 - 1; z++) {
                            for (int x = b.x1 + 1; x <= b.x2 - 1; x++) {
                                String name = nameAt(x - x1, y - y1, z - z1);
                                if (isAir(name)) { continue; }
                                interior.merge(name, 1, Integer::sum);
                            }
                        }
                    }
                    List<String> bes = new ArrayList<>();
                    for (Be be : this.blockEntities.values()) {
                        if (be.x >= b.x1 && be.x <= b.x2 && be.z >= b.z1 && be.z <= b.z2
                                && be.y >= b.y1 && be.y <= b.y2) {
                            bes.add("\"" + be.id + "@" + be.x + "," + be.y + "," + be.z
                                    + (be.hasItems ? "[Items]" : "") + "\"");
                        }
                    }
                    w.println("    {");
                    w.println("      \"id\": \"" + b.id() + "\",");
                    w.println("      \"bounds\": {\"x1\": " + b.x1 + ", \"x2\": " + b.x2 + ", \"y1\": " + b.y1
                            + ", \"y2\": " + b.y2 + ", \"z1\": " + b.z1 + ", \"z2\": " + b.z2 + "},");
                    w.println("      \"columns\": " + b.columns.size() + ",");
                    w.println("      \"floorLevels\": " + floors + ",");
                    w.println("      \"occupancyByY\": " + mapJson(occ) + ",");
                    w.println("      \"walls\": " + mapJson(walls) + ",");
                    w.println("      \"roof\": " + mapJson(roof) + ",");
                    w.println("      \"floorSlabs\": " + mapJson(slab) + ",");
                    w.println("      \"groundFloorInterior\": " + mapJson(interior) + ",");
                    w.print("      \"perStoreyInterior\": [");
                    for (int storeyIndex = 0; storeyIndex < floors.size(); storeyIndex++) {
                        int from = floors.get(storeyIndex) + 1;
                        int to = (storeyIndex + 1 < floors.size()) ? floors.get(storeyIndex + 1) - 1
                                : Math.min(from + 3, b.y2);
                        Map<String, Integer> storey = new TreeMap<>();
                        for (int y = from; y <= to; y++) {
                            for (int z = b.z1 + 1; z <= b.z2 - 1; z++) {
                                for (int x = b.x1 + 1; x <= b.x2 - 1; x++) {
                                    String name = nameAt(x - x1, y - y1, z - z1);
                                    if (isAir(name)) { continue; }
                                    storey.merge(name, 1, Integer::sum);
                                }
                            }
                        }
                        w.print((storeyIndex == 0 ? "\n        " : ",\n        ")
                                + "{\"storey\": " + (storeyIndex + 1) + ", \"yFrom\": " + from
                                + ", \"yTo\": " + to + ", \"blocks\": " + mapJson(storey) + "}");
                    }
                    w.println(floors.isEmpty() ? "]," : "\n      ],");
                    w.println("      \"blockEntities\": [" + String.join(", ", bes) + "]");
                    w.print("    }");
                    w.println(i == buildings.size() - 1 ? "" : ",");
                }
                w.println("  ]");
                w.println("}");
            }
        }
    }

    static final class Building {
        final List<int[]> columns;
        final int x1, x2, y1, y2, z1, z2;

        Building(List<int[]> columns, int x1, int x2, int y1, int y2, int z1, int z2) {
            this.columns = columns;
            this.x1 = x1; this.x2 = x2; this.y1 = y1; this.y2 = y2; this.z1 = z1; this.z2 = z2;
        }

        String id() {
            return String.format("b_%d_%d", this.x1, this.z1);
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean isAir(String name) {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air");
    }

    /** The highest level of the box that is >25 % platform-block: the top of the flat district floor. */
    static int platformTopOf(Map<Integer, Integer> platformByY) {
        int top = -1;
        for (int y : platformByY.keySet()) { top = Math.max(top, y); }
        return top;
    }

    static boolean isPlatform(String name) {
        return PLATFORM_BLOCKS.contains(name);
    }

    static String top(Map<String, Integer> map, int limit) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + " x" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

    static String mapJson(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) { sb.append(", "); }
            first = false;
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    static String stateString(NbtRewriter.Compound paletteEntry) {
        Object name = paletteEntry.get("Name").value;
        StringBuilder sb = new StringBuilder(String.valueOf(name));
        NbtRewriter.Tag props = paletteEntry.get("Properties");
        if (props != null) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, NbtRewriter.Tag> e : props.asCompound().entrySet()) {
                sorted.put(e.getKey(), String.valueOf(e.getValue().value));
            }
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) { sb.append(','); }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(']');
        }
        return sb.toString();
    }

    static String blockName(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    static void readBlockEntities(NbtRewriter.Compound chunk, int x1, int y1, int z1, int x2, int y2, int z2,
                                  Map<Long, Be> out) {
        NbtRewriter.Tag beTag = chunk.get("block_entities");
        if (beTag == null) { return; }
        for (NbtRewriter.Tag entry : beTag.asList().items) {
            NbtRewriter.Compound be = entry.asCompound();
            if (be.get("id") == null || be.get("x") == null || be.get("y") == null || be.get("z") == null) {
                continue;
            }
            String id = String.valueOf(be.get("id").value);
            int x = ((Number) be.get("x").value).intValue();
            int y = ((Number) be.get("y").value).intValue();
            int z = ((Number) be.get("z").value).intValue();
            if (x < x1 || x > x2 || y < y1 || y > y2 || z < z1 || z > z2) { continue; }
            boolean hasItems = be.get("Items") != null;
            out.put(((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL),
                    new Be(id, x, y, z, hasItems));
        }
    }

    /** The same anvil decoding RegionStat/PaletteScan use: a copy of the region file, never the live save. */
    static NbtRewriter.Compound readChunk(Path world, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        Path file = world.resolve("region").resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file) || Files.size(file) == 0) { return null; }
        int slot = (chunkX & 31) + (chunkZ & 31) * 32;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            if (channel.read(header, 0) < 4096) { return null; }
            byte[] h = header.array();
            int offset = ((h[slot * 4] & 0xFF) << 16) | ((h[slot * 4 + 1] & 0xFF) << 8) | (h[slot * 4 + 2] & 0xFF);
            if (offset == 0) { return null; }
            long byteOffset = (long) offset * 4096L;
            ByteBuffer head = ByteBuffer.allocate(5);
            if (channel.read(head, byteOffset) < 5) { return null; }
            head.flip();
            int length = head.getInt();
            int compression = head.get() & 0xFF;
            if (length <= 1) { return null; }
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            channel.read(payload, byteOffset + 5);
            payload.flip();
            byte[] raw = new byte[length - 1];
            payload.get(raw);
            InputStream in = switch (compression) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(raw));
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater());
                case 3 -> new ByteArrayInputStream(raw);
                default -> throw new IOException("unknown compression " + compression);
            };
            try (DataInputStream data = new DataInputStream(in)) {
                int type = data.readUnsignedByte();
                data.readUTF();
                return NbtRewriter.readPayload(data, type).asCompound();
            }
        }
    }

    private CitySurvey() {
    }
}
