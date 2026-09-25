import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Read-only reconnaissance for the city survey (M1): walks every region file under a directory and reports
 * which CHUNKS contain "built" blocks, using the section palette only - no per-block iteration, so it is
 * fast enough to sweep a whole world.
 *
 * <p>A block name is "interesting" when it is NOT in the natural whitelist below. That is deliberately a
 * whitelist rather than a blacklist: the question M1 asks is "where did somebody put something", and every
 * block the terrain generator can produce is enumerated here.</p>
 *
 * <pre>
 *   PaletteScan &lt;dir&gt; [naturalWhitelist=default] [maxChunksPerRegion=40]
 * </pre>
 *
 * <p>It also lists the block entities of every interesting chunk (chests, spawners, signs, ...), because
 * those are the things a structure NBT has to carry.</p>
 */
public final class PaletteScan {

    /** Blocks worldgen produces. Anything else is treated as "somebody built this". */
    static final Set<String> NATURAL = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:diorite",
            "minecraft:andesite", "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block",
            "minecraft:bedrock", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium", "minecraft:moss_block",
            "minecraft:mud", "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
            "minecraft:gravel", "minecraft:clay", "minecraft:snow", "minecraft:snow_block",
            "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:water",
            "minecraft:lava", "minecraft:magma_block", "minecraft:obsidian", "minecraft:crying_obsidian",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:emerald_ore",
            "minecraft:deepslate_emerald_ore", "minecraft:raw_iron_block", "minecraft:raw_copper_block",
            "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:birch_log", "minecraft:birch_leaves",
            "minecraft:spruce_log", "minecraft:spruce_leaves", "minecraft:jungle_log",
            "minecraft:jungle_leaves", "minecraft:acacia_log", "minecraft:acacia_leaves",
            "minecraft:dark_oak_log", "minecraft:dark_oak_leaves", "minecraft:mangrove_log",
            "minecraft:mangrove_leaves", "minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots",
            "minecraft:cherry_log", "minecraft:cherry_leaves", "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves", "minecraft:vine", "minecraft:glow_lichen",
            "minecraft:moss_carpet", "minecraft:hanging_roots", "minecraft:big_dripleaf",
            "minecraft:small_dripleaf", "minecraft:lily_pad", "minecraft:seagrass",
            "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:tube_coral",
            "minecraft:brain_coral", "minecraft:bubble_coral", "minecraft:fire_coral",
            "minecraft:horn_coral", "minecraft:dead_tube_coral", "minecraft:dead_brain_coral",
            "minecraft:dead_bubble_coral", "minecraft:dead_fire_coral", "minecraft:dead_horn_coral",
            "minecraft:short_grass", "minecraft:grass", "minecraft:tall_grass", "minecraft:fern",
            "minecraft:large_fern", "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
            "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
            "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
            "minecraft:lily_of_the_valley", "minecraft:sunflower", "minecraft:lilac",
            "minecraft:rose_bush", "minecraft:peony", "minecraft:sugar_cane", "minecraft:bamboo",
            "minecraft:cactus", "minecraft:dead_bush", "minecraft:pumpkin", "minecraft:melon",
            "minecraft:sweet_berry_bush", "minecraft:cocoa", "minecraft:wheat", "minecraft:carrots",
            "minecraft:potatoes", "minecraft:beetroots", "minecraft:brown_mushroom", "minecraft:red_mushroom",
            "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block", "minecraft:mushroom_stem",
            "minecraft:infested_stone", "minecraft:infested_deepslate", "minecraft:sculk",
            "minecraft:sculk_vein", "minecraft:sculk_catalyst", "minecraft:sculk_shrieker",
            "minecraft:sculk_sensor", "minecraft:amethyst_block", "minecraft:budding_amethyst",
            "minecraft:amethyst_cluster", "minecraft:pointed_dripstone", "minecraft:soul_sand",
            "minecraft:soul_soil", "minecraft:basalt", "minecraft:blackstone", "minecraft:netherrack",
            "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore", "minecraft:glowstone",
            "minecraft:end_stone", "minecraft:chorus_plant", "minecraft:chorus_flower",
            "minecraft:spore_blossom", "minecraft:cave_vines", "minecraft:cave_vines_plant",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:twisting_vines",
            "minecraft:twisting_vines_plant", "minecraft:warped_roots", "minecraft:crimson_roots",
            "minecraft:nether_sprouts", "minecraft:sea_pickle", "minecraft:prismarine",
            "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:sea_lantern",
            "minecraft:sponge", "minecraft:wet_sponge", "minecraft:iceberg", "minecraft:powder_snow",
            "minecraft:bone_block", "minecraft:suspicious_sand", "minecraft:suspicious_gravel",
            "minecraft:ochre_froglight", "minecraft:verdant_froglight", "minecraft:pearlescent_froglight",
            "minecraft:decorated_pot", "minecraft:flower_pot", "minecraft:torchflower",
            "minecraft:pitcher_plant", "minecraft:pink_petals");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int maxChunks = args.length > 1 ? Integer.parseInt(args[1]) : 40;

        List<Path> regions = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("region"))
                    .sorted().forEach(regions::add);
        }

        Map<String, Integer> builtHistogram = new TreeMap<>();
        Map<String, int[]> builtBounds = new TreeMap<>();
        Map<String, Integer> blockEntities = new TreeMap<>();
        int totalChunks = 0;
        List<String> interesting = new ArrayList<>();

        for (Path region : regions) {
            Path world = region.getParent().getParent();
            String name = region.getFileName().toString();
            int rx = Integer.parseInt(name.split("\\.")[1]);
            int rz = Integer.parseInt(name.split("\\.")[2]);
            if (Files.size(region) == 0) {
                System.out.println("region " + name + ": EMPTY FILE (0 bytes)");
                continue;
            }
            byte[] header = readHeader(region);
            int chunksHere = 0;
            for (int slot = 0; slot < 1024; slot++) {
                int offset = ((header[slot * 4] & 0xFF) << 16) | ((header[slot * 4 + 1] & 0xFF) << 8)
                        | (header[slot * 4 + 2] & 0xFF);
                if (offset == 0) { continue; }
                int cx = rx * 32 + (slot % 32);
                int cz = rz * 32 + (slot / 32);
                NbtRewriter.Compound chunk = readChunk(region, offset);
                if (chunk == null) { continue; }
                chunksHere++;
                totalChunks++;
                TreeSet<String> built = new TreeSet<>();
                TreeSet<Integer> builtSections = new TreeSet<>();
                NbtRewriter.Tag sectionsTag = chunk.get("sections");
                if (sectionsTag != null) {
                    for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
                        NbtRewriter.Compound section = sectionTag.asCompound();
                        NbtRewriter.Tag yTag = section.get("Y");
                        NbtRewriter.Tag statesTag = section.get("block_states");
                        if (yTag == null || statesTag == null) { continue; }
                        int sectionY = ((Number) yTag.value).intValue();
                        NbtRewriter.Tag paletteTag = statesTag.asCompound().get("palette");
                        if (paletteTag == null) { continue; }
                        for (NbtRewriter.Tag entry : paletteTag.asList().items) {
                            Object raw = entry.asCompound().get("Name").value;
                            String block = String.valueOf(raw);
                            if (!NATURAL.contains(block)) {
                                built.add(block);
                                builtSections.add(sectionY);
                                builtHistogram.merge(block, 1, Integer::sum);
                                int[] b = builtBounds.get(block);
                                if (b == null) {
                                    builtBounds.put(block, new int[]{cx, cz, cx, cz, 1});
                                } else {
                                    b[0] = Math.min(b[0], cx);
                                    b[1] = Math.min(b[1], cz);
                                    b[2] = Math.max(b[2], cx);
                                    b[3] = Math.max(b[3], cz);
                                    b[4]++;
                                }
                            }
                        }
                    }
                }
                TreeSet<String> entities = new TreeSet<>();
                NbtRewriter.Tag beTag = chunk.get("block_entities");
                if (beTag != null) {
                    for (NbtRewriter.Tag entry : beTag.asList().items) {
                        NbtRewriter.Compound be = entry.asCompound();
                        String id = String.valueOf(be.get("id").value);
                        int bx = ((Number) be.get("x").value).intValue();
                        int by = ((Number) be.get("y").value).intValue();
                        int bz = ((Number) be.get("z").value).intValue();
                        boolean hasItems = be.get("Items") != null;
                        entities.add(id + "@" + bx + "," + by + "," + bz + (hasItems ? "[Items]" : ""));
                        blockEntities.merge(id, 1, Integer::sum);
                    }
                }
                if (!built.isEmpty() || !entities.isEmpty()) {
                    String line = String.format("chunk %d,%d  x[%d..%d] z[%d..%d]  sections=%s  built=%s%s",
                            cx, cz, cx * 16, cx * 16 + 15, cz * 16, cz * 16 + 15, builtSections,
                            built, entities.isEmpty() ? "" : "  BEs=" + entities);
                    interesting.add(region.getFileName() + " -> " + line);
                }
            }
            System.out.println("region " + name + ": chunks=" + chunksHere + " interesting=" + interesting.stream()
                    .filter(s -> s.startsWith(name)).count());
        }

        System.out.println();
        System.out.println("region files scanned: " + regions.size() + "  chunks: " + totalChunks);
        System.out.println("interesting chunks: " + interesting.size());
        System.out.println();
        System.out.println("=== built block names (palette occurrences, i.e. chunks x sections) ===");
        builtHistogram.forEach((block, count) -> {
            int[] b = builtBounds.get(block);
            System.out.printf("  %-42s x%-5d chunks x[%d..%d] z[%d..%d]  world x[%d..%d] z[%d..%d]%n",
                    block, count, b[0], b[2], b[1], b[3], b[0] * 16, b[2] * 16 + 15, b[1] * 16, b[3] * 16 + 15);
        });
        System.out.println();
        System.out.println("=== block entities ===");
        if (blockEntities.isEmpty()) {
            System.out.println("  (none)");
        }
        blockEntities.forEach((id, count) -> System.out.println("  " + id + " x" + count));
        System.out.println();
        System.out.println("=== interesting chunks (first " + maxChunks + " of " + interesting.size() + ") ===");
        interesting.stream().limit(maxChunks).forEach(line -> System.out.println("  " + line));
    }

    static byte[] readHeader(Path region) throws IOException {
        byte[] header = new byte[4096];
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            channel.read(ByteBuffer.wrap(header), 0);
        }
        return header;
    }

    /** Reads the chunk at a sector offset (the same anvil decoding RegionStat uses). */
    static NbtRewriter.Compound readChunk(Path region, int sectorOffset) throws IOException {
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            long byteOffset = (long) sectorOffset * 4096L;
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

    private PaletteScan() {
    }
}
