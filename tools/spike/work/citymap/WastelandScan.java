import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * The urban-wasteland evidence scan: reads the dimension's region files directly (never asking the
 * server anything) and reports the four facts the report needs.
 *
 * <pre>
 *   WastelandScan --world &lt;dimensionDir&gt; --gen &lt;structureNbtDirOrFile&gt;
 *                 --x1 N --z1 N --x2 N --z2 N [--ymin N] [--ymax N] [--csv out.csv]
 * </pre>
 *
 * <p>What it measures, and why each number is the honest one:</p>
 * <ol>
 *   <li><b>How many city structures actually generated.</b> Not a heuristic: every chunk's NBT carries
 *       {@code structures.starts}, a compound keyed by the STRUCTURE ID (that is how
 *       {@code ChunkSerializer} writes {@code ChunkAccess#getAllStarts}). Counting those keys counts
 *       generated structure starts exactly, per id.</li>
 *   <li><b>The surface block histogram</b> of the terrain: the topmost non-air block of every column in
 *       the box, which is what a player standing there sees.</li>
 *   <li><b>The man-made share of the surface</b>: a surface column is man-made when its top block is in
 *       the palette of the shipped city structure NBTs (read out of those files, not hard-coded).</li>
 *   <li><b>The relief and the water count</b>: min/mean/max surface Y and how many columns are water,
 *       which is what "low-relief, waterless wasteland" has to mean numerically.</li>
 * </ol>
 */
public final class WastelandScan {

    /** Blocks this generator can produce on its own: the noise default, the surface palette and plants. */
    private static final Set<String> WASTELAND = new LinkedHashSet<>(List.of(
            "air", "cave_air", "void_air", "stone", "andesite", "tuff", "gravel", "coarse_dirt", "dirt",
            "deepslate", "bedrock", "dead_bush", "granite", "diorite", "clay", "sand", "red_sand",
            "sandstone", "water", "lava", "cobweb", "mossy_cobblestone", "cobblestone", "moss_block",
            "short_grass", "grass", "fern", "vine", "glow_lichen", "snow", "ice", "pointed_dripstone",
            "dripstone_block", "calcite", "smooth_basalt", "basalt", "amethyst_block", "budding_amethyst",
            "mushroom_stem", "brown_mushroom_block", "red_mushroom_block", "cactus", "sugar_cane",
            "chest", "spawner", "torch", "wall_torch", "oak_planks", "oak_fence", "oak_log", "oak_slab",
            "rail", "dirt_path", "farmland", "wheat", "coal_ore", "iron_ore", "copper_ore", "gold_ore",
            "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore", "coal_block", "raw_iron_block",
            "raw_copper_block", "moss_carpet", "big_dripleaf", "small_dripleaf", "azalea", "mangrove_roots",
            "mud", "muddy_mangrove_roots", "packed_mud", "sculk", "sculk_vein", "bone_block"));

    /** Anything a structure NBT palette contains is generator material by definition. */
    private static final Set<String> STRUCTURE_PALETTE = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        String worldArg = null;
        String genArg = null;
        int x1 = 0, z1 = 0, x2 = 0, z2 = 0;
        int ymin = -64, ymax = 319;
        Path csv = null;
        Path structs = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--world" -> worldArg = args[i + 1];
                case "--gen" -> genArg = args[i + 1];
                case "--x1" -> x1 = Integer.parseInt(args[i + 1]);
                case "--z1" -> z1 = Integer.parseInt(args[i + 1]);
                case "--x2" -> x2 = Integer.parseInt(args[i + 1]);
                case "--z2" -> z2 = Integer.parseInt(args[i + 1]);
                case "--ymin" -> ymin = Integer.parseInt(args[i + 1]);
                case "--ymax" -> ymax = Integer.parseInt(args[i + 1]);
                case "--csv" -> csv = Path.of(args[i + 1]);
                case "--structs" -> structs = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (worldArg == null || genArg == null) {
            System.out.println("usage: WastelandScan --world <dir> --gen <nbtDir> --x1 N --z1 N --x2 N --z2 N");
            return;
        }
        Path world = Path.of(worldArg);
        loadStructurePalette(Path.of(genArg));
        System.out.println("structurePalette=" + STRUCTURE_PALETTE.size() + " block(s) from " + genArg);

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        System.out.println("world=" + world.toAbsolutePath());
        System.out.println("box=x[" + minX + ".." + maxX + "] z[" + minZ + ".." + maxZ + "] y[" + ymin + ".." + ymax
                + "]  columns=" + ((maxX - minX + 1) * (maxZ - minZ + 1)));

        Map<String, Integer> structures = new TreeMap<>();
        List<String> structureStarts = new ArrayList<>();
        Map<String, Integer> surface = new TreeMap<>();
        Map<Integer, Integer> heightHist = new TreeMap<>();
        Map<String, Integer> manMadeBlocks = new TreeMap<>();
        int chunks = 0;
        int chunksWithStructure = 0;
        int columns = 0;
        int manMadeColumns = 0;
        int wastelandColumns = 0;
        int otherColumns = 0;
        int overlapColumns = 0;
        int waterColumns = 0;
        Map<String, Integer> otherBlocks = new TreeMap<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long heightSum = 0;

        PrintStream csvOut = csv == null ? null : new PrintStream(Files.newOutputStream(csv), true, "UTF-8");
        if (csvOut != null) {
            csvOut.println("chunkX,chunkZ,columns,manMadeColumns,structures,surfaceTop");
        }

        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                NbtRewriter.Compound chunk = readChunk(world, cx, cz);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                List<String> chunkStructures = structureIds(chunk);
                for (String id : chunkStructures) {
                    structures.merge(id, 1, Integer::sum);
                    structureStarts.add(id + " @ chunk " + cx + "," + cz);
                }
                if (!chunkStructures.isEmpty()) {
                    chunksWithStructure++;
                }
                String[][][] tops = topBlocks(chunk, cx, cz, minX, maxX, minZ, maxZ, ymin, ymax);
                int chunkColumns = 0, chunkManMade = 0;
                Map<String, Integer> chunkSurface = new TreeMap<>();
                for (int z = Math.max(minZ, cz * 16); z <= Math.min(maxZ, cz * 16 + 15); z++) {
                    for (int x = Math.max(minX, cx * 16); x <= Math.min(maxX, cx * 16 + 15); x++) {
                        String[] top = tops[x - cx * 16][z - cz * 16];
                        if (top == null || top[0] == null) {
                            continue;
                        }
                        String name = top[0];
                        int y = Integer.parseInt(top[1]);
                        chunkColumns++;
                        columns++;
                        surface.merge(name, 1, Integer::sum);
                        chunkSurface.merge(name, 1, Integer::sum);
                        heightHist.merge(y, 1, Integer::sum);
                        heightSum += y;
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                        if (name.equals("water")) {
                            waterColumns++;
                        }
                        boolean inStructure = STRUCTURE_PALETTE.contains(bare(name));
                        boolean inWasteland = WASTELAND.contains(bare(name));
                        if (inStructure && inWasteland) {
                            // Blocks BOTH palettes use (stone, andesite, gravel, coarse_dirt, cobblestone):
                            // the ground and a building's foundation can both be one of these, so they are
                            // counted as terrain and reported separately - guessing would inflate the
                            // man-made share with the wasteland's own surface.
                            overlapColumns++;
                        } else if (inStructure) {
                            manMadeColumns++;
                            chunkManMade++;
                            manMadeBlocks.merge(name, 1, Integer::sum);
                        } else if (inWasteland) {
                            wastelandColumns++;
                        } else {
                            otherColumns++;
                            otherBlocks.merge(name, 1, Integer::sum);
                        }
                    }
                }
                if (csvOut != null) {
                    csvOut.println(cx + "," + cz + "," + chunkColumns + "," + chunkManMade + ","
                            + chunkStructures.size() + "," + topName(chunkSurface));
                }
            }
        }
        if (csvOut != null) {
            csvOut.close();
        }

        System.out.println();
        System.out.println("=== 1. GENERATED STRUCTURES (from each chunk's structures.starts, by id) ===");
        System.out.println("chunksRead=" + chunks + " chunksWithAStructureStart=" + chunksWithStructure);
        int totalStructures = 0;
        for (Map.Entry<String, Integer> entry : structures.entrySet()) {
            System.out.println("  " + entry.getKey() + " x" + entry.getValue());
            totalStructures += entry.getValue();
        }
        System.out.println("  TOTAL structure starts in the sampled box = " + totalStructures);
        if (structs != null) {
            java.util.Collections.sort(structureStarts);
            Files.write(structs, structureStarts);
            System.out.println("  wrote " + structureStarts.size() + " start position(s) to " + structs);
        }

        System.out.println();
        System.out.println("=== 2. SURFACE BLOCK HISTOGRAM (topmost non-air block per column) ===");
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(surface.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        for (int i = 0; i < Math.min(30, ranked.size()); i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            System.out.printf(Locale.ROOT, "  %-26s %8d  %6.2f%%%n", entry.getKey(), entry.getValue(),
                    100.0 * entry.getValue() / Math.max(1, columns));
        }
        System.out.println("  distinct surface blocks = " + surface.size());

        System.out.println();
        System.out.println("=== 3. MAN-MADE vs WASTELAND SURFACE ===");
        System.out.println("columns=" + columns + " manMade(unique to a city palette)=" + manMadeColumns
                + " (" + pct(manMadeColumns, columns) + ") wastelandPalette=" + wastelandColumns
                + " (" + pct(wastelandColumns, columns) + ") both=" + overlapColumns
                + " (" + pct(overlapColumns, columns) + ") other=" + otherColumns
                + " (" + pct(otherColumns, columns) + ")");
        System.out.println("  (the 'both' column is the honest one: stone/andesite/gravel/coarse_dirt/"
                + "cobblestone are terrain blocks AND foundation blocks, so they are not claimed as man-made)");
        for (Map.Entry<String, Integer> entry : new TreeMap<>(manMadeBlocks).entrySet()) {
            System.out.printf(Locale.ROOT, "  man-made top: %-24s %7d%n", entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : otherBlocks.entrySet()) {
            System.out.printf(Locale.ROOT, "  OTHER top:    %-24s %7d%n", entry.getKey(), entry.getValue());
        }
        System.out.println("chunksWithStructure=" + chunksWithStructure + " of " + chunks
                + " (" + pct(chunksWithStructure, chunks) + ")");

        System.out.println();
        System.out.println("=== 4. RELIEF AND WATER (the noise-settings choices, measured) ===");
        System.out.println("surfaceY min=" + minY + " max=" + maxY + " mean="
                + String.format(Locale.ROOT, "%.1f", (double) heightSum / Math.max(1, columns))
                + " relief(max-min)=" + (maxY - minY));
        System.out.println("waterColumns=" + waterColumns + " (" + pct(waterColumns, columns) + ")");
        List<Integer> ys = new ArrayList<>(heightHist.keySet());
        System.out.println("surfaceY histogram (y=count, 1-block buckets, non-empty only):");
        StringBuilder line = new StringBuilder("  ");
        int printed = 0;
        for (int y : ys) {
            line.append(y).append('=').append(heightHist.get(y)).append("  ");
            if (++printed % 8 == 0) {
                System.out.println(line);
                line = new StringBuilder("  ");
            }
        }
        if (line.length() > 2) {
            System.out.println(line);
        }
    }

    private static String topName(Map<String, Integer> map) {
        return map.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
    }

    private static String pct(int part, int whole) {
        return String.format(Locale.ROOT, "%.2f%%", whole == 0 ? 0.0 : 100.0 * part / whole);
    }

    /** Both sides of the comparison drop the namespace, so the palette from the NBTs matches the chunk. */
    static String bare(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    // ------------------------------------------------------------------ chunk reading

    static NbtRewriter.Compound readChunk(Path world, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        Path file = world.resolve("region").resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        int slot = (chunkX & 31) + (chunkZ & 31) * 32;
        byte[] header = new byte[4096];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.read(ByteBuffer.wrap(header), 0) < 4096) {
                return null;
            }
            int offset = ((header[slot * 4] & 0xFF) << 16) | ((header[slot * 4 + 1] & 0xFF) << 8)
                    | (header[slot * 4 + 2] & 0xFF);
            int sectors = header[slot * 4 + 3] & 0xFF;
            if (offset == 0 || sectors == 0) {
                return null;
            }
            long byteOffset = (long) offset * 4096L;
            ByteBuffer head = ByteBuffer.allocate(5);
            if (channel.read(head, byteOffset) < 5) {
                return null;
            }
            head.flip();
            int length = head.getInt();
            int compression = head.get() & 0xFF;
            if (length <= 1) {
                return null;
            }
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            channel.read(payload, byteOffset + 5);
            payload.flip();
            byte[] raw = new byte[length - 1];
            payload.get(raw);
            return parse(raw, compression);
        }
    }

    static NbtRewriter.Compound parse(byte[] raw, int compression) throws IOException {
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(new ByteArrayInputStream(raw));
            case 2 -> new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater());
            case 3 -> new ByteArrayInputStream(raw);
            default -> throw new IOException("unknown compression " + compression);
        };
        try (DataInputStream data = new DataInputStream(in)) {
            int type = data.readUnsignedByte();
            data.readUTF();
            NbtRewriter.Tag tag = NbtRewriter.readPayload(data, type);
            return tag.asCompound();
        }
    }

    /** The structure ids with a start in this chunk: the keys of structures.starts. */
    static List<String> structureIds(NbtRewriter.Compound chunk) {
        List<String> ids = new ArrayList<>();
        NbtRewriter.Tag structures = chunk.get("structures");
        if (structures == null) {
            return ids;
        }
        NbtRewriter.Tag starts = structures.asCompound().get("starts");
        if (starts == null) {
            return ids;
        }
        ids.addAll(starts.asCompound().keySet());
        return ids;
    }

    /**
     * Per-column topmost non-air block. Index is local (x - chunkX*16, z - chunkZ*16); each entry is
     * {blockName, y} or null when the column is empty in the Y range.
     */
    static String[][][] topBlocks(NbtRewriter.Compound chunk, int chunkX, int chunkZ,
                                int minX, int maxX, int minZ, int maxZ, int ymin, int ymax) {
        String[][][] tops = new String[16][16][];
        int[] topY = new int[16 * 16];
        for (int i = 0; i < topY.length; i++) {
            topY[i] = Integer.MIN_VALUE;
        }
        NbtRewriter.Tag sectionsTag = chunk.get("sections");
        if (sectionsTag == null) {
            return tops;
        }
        for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
            NbtRewriter.Compound section = sectionTag.asCompound();
            NbtRewriter.Tag yTag = section.get("Y");
            if (yTag == null) {
                continue;
            }
            int sectionY = ((Number) yTag.value).intValue();
            if (sectionY * 16 + 15 < ymin || sectionY * 16 > ymax) {
                continue;
            }
            NbtRewriter.Tag statesTag = section.get("block_states");
            if (statesTag == null) {
                continue;
            }
            NbtRewriter.Compound states = statesTag.asCompound();
            NbtRewriter.Tag paletteTag = states.get("palette");
            if (paletteTag == null) {
                continue;
            }
            List<NbtRewriter.Tag> palette = paletteTag.asList().items;
            String[] names = new String[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                names[i] = String.valueOf(palette.get(i).asCompound().get("Name").value);
            }
            long[] data = null;
            NbtRewriter.Tag dataTag = states.get("data");
            if (dataTag != null) {
                data = (long[]) dataTag.value;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            for (int y = 0; y < 16; y++) {
                int worldY = sectionY * 16 + y;
                if (worldY < ymin || worldY > ymax) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    int worldZ = chunkZ * 16 + z;
                    if (worldZ < minZ || worldZ > maxZ) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        int worldX = chunkX * 16 + x;
                        if (worldX < minX || worldX > maxX) {
                            continue;
                        }
                        int index = (y << 8) | (z << 4) | x;
                        int state;
                        if (data == null) {
                            state = 0;
                        } else {
                            int longIndex = index / perLong;
                            int offset = (index % perLong) * bits;
                            state = (int) ((data[longIndex] >>> offset) & mask);
                        }
                        if (state < 0 || state >= names.length) {
                            state = 0;
                        }
                        String name = names[state];
                        if (name.endsWith("air")) {
                            continue;
                        }
                        if (worldY > topY[z * 16 + x]) {
                            topY[z * 16 + x] = worldY;
                            tops[x][z] = new String[] { name, Integer.toString(worldY) };
                        }
                    }
                }
            }
        }
        return tops;
    }

    // ------------------------------------------------------------------ the structure palette

    static void loadStructurePalette(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    loadStructurePalette(child);
                }
            }
            return;
        }
        if (!path.toString().endsWith(".nbt")) {
            return;
        }
        NbtRewriter.Root root = NbtRewriter.readRaw(Files.newInputStream(path));
        NbtRewriter.Tag palette = root.tag.asCompound().get("palette");
        if (palette == null) {
            return;
        }
        for (NbtRewriter.Tag entry : palette.asList().items) {
            Object name = entry.asCompound().get("Name").value;
            STRUCTURE_PALETTE.add(bare(String.valueOf(name)));
        }
    }

    private WastelandScan() {
    }
}
