import java.io.BufferedInputStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Part 1 of the city-district survey: walk EVERY chunk of a save's overworld region files and, per
 * chunk, count blocks split by ORIGIN and by OBSTACLE CLASS.
 *
 * <p>Origin is the honest discriminator. The generator's own block vocabulary is read out of the
 * shipped structure NBTs (city_small, city_a/b/c, gen_district, the buildings folder), so a block that is
 * neither in that vocabulary nor in the natural-terrain vocabulary cannot have been produced by
 * worldgen: it is a hand edit. That is what locates the user's district without guessing.</p>
 *
 * <pre>
 *   ChunkScan --world &lt;saveDir&gt; --gen &lt;nbtDirOrFile&gt; --boxes &lt;boxes.txt&gt;
 *             --csv &lt;chunks.csv&gt; [--names &lt;names.tsv&gt;] [--ymin N] [--ymax N] [--top N]
 * </pre>
 *
 * Read-only. It never opens a region file for writing and never touches the save directory tree.
 */
public final class ChunkScan {

    // ------------------------------------------------------------------ vocabulary

    static final Set<String> GEN = new HashSet<>();
    static final Set<String> NATURAL = new HashSet<>();
    static final Set<String> TRACKED_NATURAL = new HashSet<>();
    /** Blocks a vanilla worldgen structure (mineshaft, deep dark, fossil, ...) can leave behind. */
    static final Set<String> NATURAL_STRUCTURE = new HashSet<>();
    /** Blocks that only a player (or a placed feature, i.e. a hand edit) puts down. */
    static final Set<String> ARTIFACT = new HashSet<>();
    static final List<String> NATURAL_SUFFIX = List.of(
            "_ore", "_log", "_wood", "_leaves", "_sapling", "_coral", "_coral_block",
            "_coral_fan", "_coral_wall_fan", "_roots", "_mushroom_block", "_stem", "_hyphae");

    static {
        String[] nat = {
            "air", "cave_air", "void_air", "stone", "granite", "diorite", "andesite", "deepslate",
            "tuff", "calcite", "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol",
            "mycelium", "mud", "muddy_mangrove_roots", "gravel", "sand", "red_sand", "sandstone",
            "clay", "bedrock", "obsidian", "magma_block", "basalt", "smooth_basalt", "netherrack",
            "end_stone", "soul_sand", "soul_soil", "snow", "snow_block", "ice", "packed_ice",
            "blue_ice", "frosted_ice", "grass", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "sugar_cane", "cactus", "melon", "pumpkin", "carved_pumpkin", "jack_o_lantern",
            "vine", "vines", "glow_lichen", "moss_carpet", "moss_block", "azalea", "flowering_azalea",
            "big_dripleaf", "small_dripleaf", "lily_pad", "brown_mushroom", "red_mushroom",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "torchflower",
            "pink_petals", "spore_blossom", "hanging_roots", "sea_pickle", "sponge", "wet_sponge",
            "amethyst_block", "budding_amethyst", "amethyst_cluster", "dripstone_block",
            "bubble_column", "sculk", "sculk_vein", "wheat", "carrots", "potatoes", "beetroots",
            "attached_melon_stem", "attached_pumpkin_stem", "pumpkin_stem", "melon_stem",
            "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "cocoa", "sweet_berry_bush",
            "chorus_flower", "chorus_plant", "frogspawn", "ochre_froglight", "verdant_froglight",
            "pearlescent_froglight", "glow_berries", "cave_vines", "cave_vines_plant", "twisting_vines",
            "weeping_vines", "nether_sprouts", "warped_roots", "crimson_roots", "shroomlight",
            "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud", "sea_lantern",
            "tube_coral", "brain_coral", "fire_coral", "horn_coral", "bubble_coral",
            // the world these cities sit in is a badlands with a large lake, so the whole terracotta
            // family, the sand family, water and lava are TERRAIN here, not build material
            "water", "lava", "terracotta", "white_terracotta", "orange_terracotta",
            "magenta_terracotta", "light_blue_terracotta", "yellow_terracotta", "lime_terracotta",
            "pink_terracotta", "gray_terracotta", "light_gray_terracotta", "cyan_terracotta",
            "purple_terracotta", "blue_terracotta", "brown_terracotta", "green_terracotta",
            "red_terracotta", "black_terracotta", "seagrass", "tall_seagrass",
            "sandstone", "red_sandstone", "smooth_basalt", "glow_lichen", "sculk_vein",
            "muddy_mangrove_roots", "packed_mud", "hanging_roots", "small_dripleaf", "big_dripleaf",
            "pitcher_plant", "torchflower", "spore_blossom", "cave_vines", "cave_vines_plant",
            "bee_nest", "suspicious_sand", "suspicious_gravel"
        };
        NATURAL.addAll(List.of(nat));
        TRACKED_NATURAL.addAll(List.of("water", "lava", "snow", "sand", "gravel", "clay",
                "pointed_dripstone", "bamboo", "kelp", "kelp_plant", "cobweb"));
        NATURAL_STRUCTURE.addAll(List.of(
                "mossy_cobblestone", "cobweb", "rail", "powered_rail", "detector_rail",
                "activator_rail", "bone_block", "coal_block", "raw_copper_block", "raw_iron_block",
                "raw_gold_block", "sculk", "sculk_sensor", "sculk_catalyst", "sculk_shrieker",
                "crying_obsidian", "fire", "soul_fire", "spawner", "chest", "torch", "wall_torch",
                "oak_fence", "oak_planks", "oak_log", "oak_slab", "oak_stairs", "dirt_path",
                "farmland", "wheat", "carrots", "potatoes", "beetroots", "composter",
                "guaniao:crow_nest", "amethyst_cluster", "budding_amethyst",
                "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud",
                "cobbled_deepslate", "deepslate_bricks", "deepslate_tiles", "tuff", "gravel"));
        for (String n : new String[]{
                // wood families: any full wood building set a player places
                "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
                "crimson", "warped", "bamboo", "mangrove"}) {
            for (String kind : new String[]{"planks", "fence", "fence_gate", "door", "trapdoor",
                    "stairs", "slab", "button", "pressure_plate", "sign", "wall_sign", "hanging_sign",
                    "log", "wood", "stripped_log", "stripped_wood"}) {
                ARTIFACT.add(n + "_" + kind);
            }
        }
        for (String color : new String[]{"white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black"}) {
            for (String kind : new String[]{"wool", "carpet", "bed", "banner", "wall_banner",
                    "stained_glass", "stained_glass_pane", "glazed_terracotta", "concrete",
                    "concrete_powder", "candle", "shulker_box", "terracotta"}) {
                ARTIFACT.add(color + "_" + kind);
            }
        }
        ARTIFACT.addAll(List.of(
                "smooth_sandstone", "cut_sandstone", "smooth_red_sandstone", "cut_red_sandstone",
                "smooth_sandstone_slab", "smooth_sandstone_stairs", "sandstone_slab",
                "sandstone_stairs", "sandstone_wall", "red_sandstone_slab", "red_sandstone_stairs",
                "red_sandstone_wall", "sandstone", "chiseled_sandstone", "smooth_stone",
                "smooth_stone_slab", "stone_bricks", "stone_brick_slab", "stone_brick_stairs",
                "stone_brick_wall", "chiseled_stone_bricks", "cracked_stone_bricks",
                "mossy_stone_bricks", "mossy_stone_brick_slab", "mossy_stone_brick_stairs",
                "mossy_stone_brick_wall", "cobblestone_wall", "cobblestone_slab",
                "cobblestone_stairs", "bricks", "brick_slab", "brick_stairs", "brick_wall",
                "glass", "glass_pane", "tinted_glass", "iron_bars", "chain", "scaffolding",
                "ladder", "lantern", "soul_lantern", "campfire", "soul_campfire", "torch",
                "soul_torch", "wall_torch", "soul_wall_torch", "hay_block", "bookshelf",
                "chiseled_bookshelf", "lectern", "flower_pot", "potted_cactus", "potted_dead_bush",
                "potted_oak_sapling", "armor_stand", "item_frame", "glow_item_frame", "painting",
                "barrel", "chest", "trapped_chest", "ender_chest", "crafting_table", "furnace",
                "blast_furnace", "smoker", "stonecutter", "grindstone", "loom", "cartography_table",
                "fletching_table", "smithing_table", "anvil", "chipped_anvil", "damaged_anvil",
                "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "bell",
                "gold_block", "iron_block", "diamond_block", "emerald_block", "netherite_block",
                "lapis_block", "redstone_block", "glowstone", "sea_lantern", "obsidian",
                "decorated_pot", "brewing_stand", "enchanting_table", "jukebox", "note_block",
                "dirt_path", "farmland", "podzol", "coarse_dirt", "rooted_dirt", "mud", "moss_block",
                "moss_carpet", "vine", "cobweb", "spawner", "white_wall_banner", "honey_block",
                "slime_block", "powder_snow", "tnt", "tripwire", "tripwire_hook", "lever",
                "redstone_lamp", "target", "beehive", "campfire", "soul_campfire",
                "tarkovscav:creative_weapon_rack", "tarkovscav:weapon_rack", "tarkovscav:spawner"));
    }

    /** A hand-edit candidate: a placed-by-a-player block the generator's own vocabulary lacks. */
    static boolean isArtifact(String name) {
        return !GEN.contains(name) && (ARTIFACT.contains(name) || name.startsWith("tarkovscav:"));
    }

    static boolean isNaturalStructure(String name) {
        return NATURAL_STRUCTURE.contains(name) && !GEN.contains(name);
    }

    /** The obstacle taxonomy the survey reports on. One label per block name, first match wins. */
    static final Map<String, String> OBSTACLE = new LinkedHashMap<>();

    static {
        // full height structural blocks
        put("concrete", "wall_concrete", "light_gray_concrete", "gray_concrete", "black_concrete",
                "brown_concrete", "white_concrete", "red_concrete", "green_concrete", "blue_concrete",
                "yellow_concrete", "orange_concrete", "lime_concrete", "cyan_concrete", "purple_concrete",
                "magenta_concrete", "pink_concrete");
        put("wall_stone", "stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks",
                "chiseled_stone_bricks", "bricks", "deepslate_tiles", "cracked_deepslate_tiles",
                "deepslate_bricks", "cracked_deepslate_bricks", "chiseled_deepslate",
                "smooth_stone", "polished_andesite", "polished_diorite", "polished_granite",
                "cobblestone", "mossy_cobblestone", "stone", "smooth_sandstone", "cut_sandstone",
                "smooth_red_sandstone", "cut_red_sandstone", "nether_bricks", "red_nether_bricks",
                "quartz_block", "smooth_quartz", "prismarine", "prismarine_bricks", "dark_prismarine",
                "end_stone_bricks", "purpur_block", "blackstone", "polished_blackstone",
                "polished_blackstone_bricks", "gilded_blackstone", "mud_bricks", "packed_mud");
        put("terracotta", "terracotta", "white_terracotta", "orange_terracotta", "magenta_terracotta",
                "light_blue_terracotta", "yellow_terracotta", "lime_terracotta", "pink_terracotta",
                "gray_terracotta", "light_gray_terracotta", "cyan_terracotta", "purple_terracotta",
                "blue_terracotta", "brown_terracotta", "green_terracotta", "red_terracotta",
                "black_terracotta", "glazed_terracotta");
        // half height and low cover
        put("slab", "oak_slab", "spruce_slab", "birch_slab", "jungle_slab", "acacia_slab",
                "dark_oak_slab", "mangrove_slab", "cherry_slab", "crimson_slab", "warped_slab",
                "bamboo_slab", "stone_slab", "smooth_stone_slab", "cobblestone_slab",
                "mossy_cobblestone_slab", "stone_brick_slab", "brick_slab", "deepslate_slab",
                "cobbled_deepslate_slab", "polished_deepslate_slab", "deepslate_tile_slab",
                "deepslate_brick_slab", "andesite_slab", "polished_andesite_slab", "diorite_slab",
                "polished_diorite_slab", "granite_slab", "polished_granite_slab", "sandstone_slab",
                "smooth_sandstone_slab", "cut_sandstone_slab", "red_sandstone_slab",
                "smooth_red_sandstone_slab", "cut_red_sandstone_slab", "nether_brick_slab",
                "red_nether_brick_slab", "quartz_slab", "smooth_quartz_slab", "prismarine_slab",
                "prismarine_brick_slab", "dark_prismarine_slab", "blackstone_slab",
                "polished_blackstone_slab", "polished_blackstone_brick_slab", "end_stone_brick_slab",
                "mud_brick_slab", "purpur_slab", "tuff_slab", "polished_tuff_slab",
                "resin_brick_slab", "petrified_oak_slab");
        put("stairs", "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "acacia_stairs",
                "dark_oak_stairs", "mangrove_stairs", "cherry_stairs", "crimson_stairs", "warped_stairs",
                "bamboo_stairs", "stone_stairs", "cobblestone_stairs", "mossy_cobblestone_stairs",
                "stone_brick_stairs", "brick_stairs", "deepslate_stairs", "cobbled_deepslate_stairs",
                "polished_deepslate_stairs", "deepslate_tile_stairs", "deepslate_brick_stairs",
                "andesite_stairs", "polished_andesite_stairs", "diorite_stairs",
                "polished_diorite_stairs", "granite_stairs", "polished_granite_stairs",
                "sandstone_stairs", "smooth_sandstone_stairs", "red_sandstone_stairs",
                "smooth_red_sandstone_stairs", "nether_brick_stairs", "red_nether_brick_stairs",
                "quartz_stairs", "smooth_quartz_stairs", "prismarine_stairs",
                "prismarine_brick_stairs", "dark_prismarine_stairs", "blackstone_stairs",
                "polished_blackstone_stairs", "polished_blackstone_brick_stairs",
                "end_stone_brick_stairs", "mud_brick_stairs", "purpur_stairs", "tuff_stairs",
                "polished_tuff_stairs", "cut_copper_stairs", "exposed_cut_copper_stairs",
                "weathered_cut_copper_stairs", "oxidized_cut_copper_stairs");
        put("fence", "oak_fence", "spruce_fence", "birch_fence", "jungle_fence", "acacia_fence",
                "dark_oak_fence", "mangrove_fence", "cherry_fence", "crimson_fence", "warped_fence",
                "bamboo_fence", "nether_brick_fence", "iron_bars");
        put("fence_gate", "oak_fence_gate", "spruce_fence_gate", "birch_fence_gate", "jungle_fence_gate",
                "acacia_fence_gate", "dark_oak_fence_gate", "mangrove_fence_gate", "cherry_fence_gate",
                "crimson_fence_gate", "warped_fence_gate", "bamboo_fence_gate");
        put("low_wall", "cobblestone_wall", "mossy_cobblestone_wall", "stone_brick_wall",
                "mossy_stone_brick_wall", "andesite_wall", "diorite_wall", "granite_wall",
                "sandstone_wall", "red_sandstone_wall", "brick_wall", "deepslate_brick_wall",
                "cobbled_deepslate_wall", "polished_deepslate_wall", "deepslate_tile_wall",
                "blackstone_wall", "polished_blackstone_wall", "polished_blackstone_brick_wall",
                "nether_brick_wall", "red_nether_brick_wall", "end_stone_brick_wall",
                "prismarine_wall", "mud_brick_wall", "tuff_wall", "polished_tuff_wall",
                "resin_brick_wall");
        put("barrel_chest", "barrel", "chest", "trapped_chest", "ender_chest", "crafting_table",
                "furnace", "blast_furnace", "smoker", "cauldron", "water_cauldron", "lava_cauldron",
                "powder_snow_cauldron", "anvil", "chipped_anvil", "damaged_anvil", "composter",
                "lectern", "bookshelf", "chiseled_bookshelf", "flower_pot", "bell", "hay_block",
                "armor_stand", "item_frame", "glow_item_frame", "painting", "decorated_pot",
                "brewing_stand", "enchanting_table", "jukebox", "note_block", "loom", "cartography_table",
                "fletching_table", "smithing_table", "stonecutter", "grindstone", "campfire",
                "soul_campfire", "spawner", "trial_spawner", "vault", "beacon", "conduit");
        put("light", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern", "soul_lantern",
                "redstone_torch", "redstone_wall_torch", "end_rod", "candle", "glowstone",
                "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
                "sea_lantern", "jack_o_lantern");
        put("plant_soft", "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
                "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves",
                "azalea_leaves", "flowering_azalea_leaves", "cobweb", "honey_block", "slime_block",
                "powder_snow", "scaffolding", "chain", "ladder", "bamboo", "kelp", "kelp_plant",
                "pointed_dripstone", "snow", "dirt_path", "farmland", "soul_sand", "mud",
                "carpet", "moss_carpet", "vine", "vines", "glow_lichen", "lily_pad", "sea_pickle",
                "sugar_cane", "cactus", "dead_bush", "short_grass", "tall_grass", "fern", "large_fern");
        put("bed", "red_bed", "white_bed", "orange_bed", "magenta_bed", "light_blue_bed", "yellow_bed",
                "lime_bed", "pink_bed", "gray_bed", "light_gray_bed", "cyan_bed", "purple_bed",
                "blue_bed", "brown_bed", "green_bed", "black_bed");
        put("door", "oak_door", "spruce_door", "birch_door", "jungle_door", "acacia_door",
                "dark_oak_door", "mangrove_door", "cherry_door", "crimson_door", "warped_door",
                "bamboo_door", "iron_door", "copper_door", "oak_trapdoor", "spruce_trapdoor",
                "birch_trapdoor", "jungle_trapdoor", "acacia_trapdoor", "dark_oak_trapdoor",
                "mangrove_trapdoor", "cherry_trapdoor", "crimson_trapdoor", "warped_trapdoor",
                "bamboo_trapdoor", "iron_trapdoor");
        put("transparent", "glass", "glass_pane", "tinted_glass", "white_stained_glass",
                "white_stained_glass_pane", "gray_stained_glass", "gray_stained_glass_pane",
                "black_stained_glass", "black_stained_glass_pane", "brown_stained_glass",
                "brown_stained_glass_pane", "light_gray_stained_glass",
                "light_gray_stained_glass_pane", "iron_bars", "oak_fence", "dark_oak_fence",
                "spruce_fence", "birch_fence", "jungle_fence", "acacia_fence", "nether_brick_fence");
        put("water", "water", "lava", "bubble_column", "ice", "packed_ice", "blue_ice", "frosted_ice",
                "snow_block");
        put("floor_wood", "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                "crimson_planks", "warped_planks", "bamboo_planks", "bamboo_mosaic",
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "crimson_stem", "warped_stem",
                "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_dark_oak_log",
                "oak_wood", "spruce_wood", "birch_wood", "dark_oak_wood");
        // carpet / wool families, matched by suffix below anyway
        put("fabric", "white_wool", "orange_wool", "magenta_wool", "light_blue_wool", "yellow_wool",
                "lime_wool", "pink_wool", "gray_wool", "light_gray_wool", "cyan_wool", "purple_wool",
                "blue_wool", "brown_wool", "green_wool", "red_wool", "black_wool");
        put("rail", "rail", "powered_rail", "detector_rail", "activator_rail");
    }

    static void put(String label, String... names) {
        for (String n : names) {
            OBSTACLE.putIfAbsent(n, label);
        }
    }

    /** Returns the obstacle class label for a block name, or null when it is not cover at all. */
    static String obstacleClass(String name) {
        String exact = OBSTACLE.get(name);
        if (exact != null) {
            return exact;
        }
        if (name.endsWith("_carpet")) {
            return "plant_soft";
        }
        if (name.endsWith("_pane")) {
            return "transparent";
        }
        if (name.endsWith("_wall")) {
            return "low_wall";
        }
        if (name.endsWith("_fence") || name.equals("iron_bars")) {
            return "fence";
        }
        if (name.endsWith("_fence_gate")) {
            return "fence_gate";
        }
        if (name.endsWith("_slab")) {
            return "slab";
        }
        if (name.endsWith("_stairs")) {
            return "stairs";
        }
        if (name.endsWith("_wool")) {
            return "fabric";
        }
        if (name.endsWith("_planks")) {
            return "floor_wood";
        }
        if (name.endsWith("_door") || name.endsWith("_trapdoor")) {
            return "door";
        }
        if (name.endsWith("_bed")) {
            return "bed";
        }
        if (name.startsWith("tarkovscav:")) {
            return "mod_block";
        }
        return null;
    }

    static boolean isNatural(String name) {
        if (NATURAL.contains(name)) {
            return true;
        }
        for (String suffix : NATURAL_SUFFIX) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i], args[i + 1]);
        }
        Path world = Path.of(opt.get("--world"));
        Path csv = Path.of(opt.get("--csv"));
        Path names = opt.containsKey("--names") ? Path.of(opt.get("--names")) : null;
        int ymin = opt.containsKey("--ymin") ? Integer.parseInt(opt.get("--ymin")) : -64;
        int ymax = opt.containsKey("--ymax") ? Integer.parseInt(opt.get("--ymax")) : 319;
        int top = opt.containsKey("--top") ? Integer.parseInt(opt.get("--top")) : 40;

        loadGeneratorPalette(opt.get("--gen"));
        System.out.println("generatorVocabularySize=" + GEN.size() + " from " + opt.get("--gen"));

        List<Box> boxes = new ArrayList<>();
        if (opt.containsKey("--boxes")) {
            for (String line : Files.readAllLines(Path.of(opt.get("--boxes")))) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                boxes.add(Box.parse(line));
            }
        }

        Path regionDir = world.resolve("region");
        List<Path> regionFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path p : stream) {
                if (Files.size(p) > 0) {
                    regionFiles.add(p);
                }
            }
        }
        regionFiles.sort(Comparator.comparing(Path::toString));
        System.out.println("world=" + world.toAbsolutePath());
        System.out.println("regionFiles=" + regionFiles.size() + " yRange=" + ymin + ".." + ymax);

        List<ChunkRecord> records = new ArrayList<>();
        Map<String, Integer> interestingTotals = new TreeMap<>();
        Map<String, Integer> otherTotals = new TreeMap<>();
        int chunksRead = 0;
        int chunksSkipped = 0;
        long totalBlocks = 0;

        PrintStream csvOut = new PrintStream(Files.newOutputStream(csv), true, "UTF-8");
        csvOut.println("chunkX,chunkZ,x0,z0,gen,natural,other,obstacle,otherObstacle,artifact");
        PrintStream nameOut = names == null ? null
                : new PrintStream(Files.newOutputStream(names), true, "UTF-8");
        if (nameOut != null) {
            nameOut.println("chunkX\tchunkZ\tblock\tcount");
        }

        long t0 = System.currentTimeMillis();
        for (Path regionFile : regionFiles) {
            String fn = regionFile.getFileName().toString();
            String[] parts = fn.split("\\.");
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
                byte[] header = new byte[4096];
                if (channel.read(ByteBuffer.wrap(header), 0) < 4096) {
                    continue;
                }
                for (int slot = 0; slot < 1024; slot++) {
                    int offset = ((header[slot * 4] & 0xFF) << 16)
                            | ((header[slot * 4 + 1] & 0xFF) << 8) | (header[slot * 4 + 2] & 0xFF);
                    int sectors = header[slot * 4 + 3] & 0xFF;
                    if (offset == 0 || sectors == 0) {
                        continue;
                    }
                    int chunkX = rx * 32 + (slot & 31);
                    int chunkZ = rz * 32 + (slot >> 5);
                    NbtRewriter.Compound chunk = readChunkAt(channel, offset, sectors);
                    if (chunk == null) {
                        chunksSkipped++;
                        continue;
                    }
                    ChunkRecord record = new ChunkRecord(chunkX, chunkZ);
                    countChunk(chunk, chunkX, chunkZ, ymin, ymax, record);
                    chunksRead++;
                    for (Map.Entry<String, Integer> e : record.names.entrySet()) {
                        String name = e.getKey();
                        interestingTotals.merge(name, e.getValue(), Integer::sum);
                        record.obstacle += obstacleCount(name, e.getValue());
                        if (!GEN.contains(name)) {
                            record.other += e.getValue();
                            record.otherObstacle += obstacleCount(name, e.getValue());
                            if (isArtifact(name)) {
                                record.artifact += e.getValue();
                            }
                            if (isNaturalStructure(name)) {
                                record.naturalStructure += e.getValue();
                            }
                            otherTotals.merge(name, e.getValue(), Integer::sum);
                        } else {
                            record.generatorBlocks += e.getValue();
                        }
                    }
                    record.gen = record.generatorBlocks;
                    totalBlocks += record.blocks;
                    if (record.artifact > 0 || record.other > 0 || record.gen > 0 || record.obstacle >= 32) {
                        records.add(record);
                        csvOut.println(record.chunkX + "," + record.chunkZ + ","
                                + (record.chunkX * 16) + "," + (record.chunkZ * 16) + ","
                                + record.gen + "," + record.natural + "," + record.other + ","
                                + record.obstacle + "," + record.otherObstacle + "," + record.artifact);
                        if (nameOut != null && record.other > 0) {
                            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(record.names.entrySet());
                            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
                            for (Map.Entry<String, Integer> e : sorted) {
                                if (!GEN.contains(e.getKey())) {
                                    nameOut.println(record.chunkX + "\t" + record.chunkZ + "\t"
                                            + e.getKey() + "\t" + e.getValue());
                                }
                            }
                        }
                    }
                    if (chunksRead % 512 == 0) {
                        System.err.println("  progress chunks=" + chunksRead
                                + " elapsed=" + (System.currentTimeMillis() - t0) / 1000 + "s");
                    }
                }
            }
        }
        csvOut.close();
        if (nameOut != null) {
            nameOut.close();
        }
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("=== SCAN SUMMARY ===");
        System.out.println("chunksRead=" + chunksRead + " chunksUnreadable=" + chunksSkipped
                + " elapsedSeconds=" + elapsed / 1000);
        System.out.println("blocksCountedInYRange=" + totalBlocks);
        System.out.println();

        System.out.println("=== TOP " + top + " CHUNKS BY HAND-PLACED (artifact) COUNT ===");
        System.out.printf("%-22s %-10s %-10s %-9s %-9s %-9s %-9s %-9s%n",
                "chunk(cx,cz)", "worldX0", "worldZ0", "artifact", "natStruct", "otherAll", "gen", "obstacle");
        List<ChunkRecord> byArtifact = new ArrayList<>(records);
        byArtifact.sort(Comparator.comparingInt((ChunkRecord r) -> r.artifact).reversed()
                .thenComparing(Comparator.comparingInt((ChunkRecord r) -> r.other).reversed()));
        for (int i = 0; i < Math.min(top, byArtifact.size()); i++) {
            ChunkRecord r = byArtifact.get(i);
            if (r.artifact == 0) {
                break;
            }
            System.out.printf("%-22s %-10d %-10d %-9d %-9d %-9d %-9d %-9d%n",
                    "(" + r.chunkX + "," + r.chunkZ + ")", r.chunkX * 16, r.chunkZ * 16,
                    r.artifact, r.naturalStructure, r.other, r.gen, r.obstacle);
        }
        System.out.println();

        System.out.println("=== CLUSTERS OF HAND-PLACED (artifact) BLOCKS (chunk adjacency, Chebyshev <= 3) ===");
        List<List<ChunkRecord>> clusters = cluster(byArtifact, 3);
        clusters.sort(Comparator.comparingInt((List<ChunkRecord> c) -> sumArtifact(c)).reversed());
        for (int i = 0; i < clusters.size(); i++) {
            List<ChunkRecord> cluster = clusters.get(i);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            int other = 0, otherObs = 0, gen = 0, obs = 0, artifact = 0, natStruct = 0;
            Map<String, Integer> hist = new TreeMap<>();
            for (ChunkRecord r : cluster) {
                minX = Math.min(minX, r.chunkX);
                maxX = Math.max(maxX, r.chunkX);
                minZ = Math.min(minZ, r.chunkZ);
                maxZ = Math.max(maxZ, r.chunkZ);
                other += r.other;
                otherObs += r.otherObstacle;
                gen += r.gen;
                obs += r.obstacle;
                artifact += r.artifact;
                natStruct += r.naturalStructure;
                for (Map.Entry<String, Integer> e : r.names.entrySet()) {
                    if (!GEN.contains(e.getKey())) {
                        hist.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
            System.out.println("cluster#" + i + " chunks=" + cluster.size()
                    + " chunkBox=(" + minX + "," + minZ + ")..(" + maxX + "," + maxZ + ")"
                    + " worldBox=x[" + (minX * 16) + ".." + (maxX * 16 + 15) + "] z["
                    + (minZ * 16) + ".." + (maxZ * 16 + 15) + "]");
            System.out.println("    artifactBlocks=" + artifact
                    + " naturalStructureBlocks=" + natStruct
                    + " genBlocks=" + gen + " allObstacleBlocks=" + obs
                    + " nonGeneratorBlocks=" + other + " nonGeneratorObstacleBlocks=" + otherObs);
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hist.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder sb = new StringBuilder("    topNonGeneratorBlocks: ");
            for (int k = 0; k < Math.min(14, sorted.size()); k++) {
                sb.append(sorted.get(k).getKey()).append(" x").append(sorted.get(k).getValue()).append("; ");
            }
            System.out.println(sb);
        }
        System.out.println();

        System.out.println("=== PER-CONFIGURED-CITY-BOX TOTALS ===");
        for (Box box : boxes) {
            int other = 0, otherObs = 0, gen = 0, obs = 0, natural = 0, artifact = 0, natStruct = 0;
            int chunks = 0;
            Map<String, Integer> hist = new TreeMap<>();
            Map<String, Integer> genHist = new TreeMap<>();
            Map<String, Integer> artifactHist = new TreeMap<>();
            for (ChunkRecord r : records) {
                int x0 = r.chunkX * 16, z0 = r.chunkZ * 16;
                if (x0 + 15 < box.x1 || x0 > box.x2 || z0 + 15 < box.z1 || z0 > box.z2) {
                    continue;
                }
                chunks++;
                other += r.other;
                otherObs += r.otherObstacle;
                gen += r.gen;
                obs += r.obstacle;
                natural += r.natural;
                artifact += r.artifact;
                natStruct += r.naturalStructure;
                for (Map.Entry<String, Integer> e : r.names.entrySet()) {
                    if (GEN.contains(e.getKey())) {
                        genHist.merge(e.getKey(), e.getValue(), Integer::sum);
                    } else {
                        hist.merge(e.getKey(), e.getValue(), Integer::sum);
                        if (isArtifact(e.getKey())) {
                            artifactHist.merge(e.getKey(), e.getValue(), Integer::sum);
                        }
                    }
                }
            }
            System.out.println("box " + box.name + " x[" + box.x1 + ".." + box.x2 + "] y["
                    + box.y1 + ".." + box.y2 + "] z[" + box.z1 + ".." + box.z2 + "]"
                    + " interestingChunks=" + chunks);
            System.out.println("    ARTIFACT=" + artifact + " natStructure=" + natStruct
                    + " scannerSeesGenBlocks=" + gen + " naturalBlocks=" + natural
                    + " nonGeneratorBlocks=" + other + " obstacleBlocks=" + obs
                    + " nonGeneratorObstacle=" + otherObs);
            List<Map.Entry<String, Integer>> asorted = new ArrayList<>(artifactHist.entrySet());
            asorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder sb = new StringBuilder("    ARTIFACT_LIST: ");
            for (int k = 0; k < Math.min(30, asorted.size()); k++) {
                sb.append(asorted.get(k).getKey()).append(" x").append(asorted.get(k).getValue()).append("; ");
            }
            System.out.println(sb);
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hist.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            sb = new StringBuilder("    nonGenerator: ");
            for (int k = 0; k < Math.min(24, sorted.size()); k++) {
                sb.append(sorted.get(k).getKey()).append(" x").append(sorted.get(k).getValue()).append("; ");
            }
            System.out.println(sb);
            List<Map.Entry<String, Integer>> gsorted = new ArrayList<>(genHist.entrySet());
            gsorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            sb = new StringBuilder("    generatorPaletteBlocks: ");
            for (int k = 0; k < Math.min(24, gsorted.size()); k++) {
                sb.append(gsorted.get(k).getKey()).append(" x").append(gsorted.get(k).getValue()).append("; ");
            }
            System.out.println(sb);
        }
        System.out.println();

        System.out.println("=== GLOBAL non-generator block totals (all scanned chunks) ===");
        otherTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(90)
                .forEach(e -> System.out.println("  " + e.getKey() + " x" + e.getValue() + (isArtifact(e.getKey())
                        ? "   [ARTIFACT]" : (isNaturalStructure(e.getKey()) ? "   [worldgen structure]" : ""))));

        System.out.println();
        System.out.println("=== OBSTACLE-CLASS TOTALS PER CLUSTER (all obstacle blocks) ===");
        for (int i = 0; i < clusters.size(); i++) {
            List<ChunkRecord> cluster = clusters.get(i);
            Map<String, Integer> byClass = new TreeMap<>();
            for (ChunkRecord r : cluster) {
                for (Map.Entry<String, Integer> e : r.names.entrySet()) {
                    String cls = obstacleClass(e.getKey());
                    if (cls != null) {
                        byClass.merge(cls, e.getValue(), Integer::sum);
                    }
                }
            }
            System.out.println("cluster#" + i + " " + byClass);
        }
    }

    static int sumArtifact(List<ChunkRecord> cluster) {
        int sum = 0;
        for (ChunkRecord r : cluster) {
            sum += r.artifact;
        }
        return sum;
    }

    static int obstacleCount(String name, int count) {
        return obstacleClass(name) == null ? 0 : count;
    }

    static List<List<ChunkRecord>> cluster(List<ChunkRecord> records, int radius) {
        List<ChunkRecord> pool = new ArrayList<>();
        for (ChunkRecord r : records) {
            if (r.artifact > 0) {
                pool.add(r);
            }
        }
        boolean[] used = new boolean[pool.size()];
        List<List<ChunkRecord>> out = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<ChunkRecord> group = new ArrayList<>();
            List<Integer> queue = new ArrayList<>();
            queue.add(i);
            used[i] = true;
            while (!queue.isEmpty()) {
                int cur = queue.remove(queue.size() - 1);
                ChunkRecord r = pool.get(cur);
                group.add(r);
                for (int j = 0; j < pool.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    ChunkRecord o = pool.get(j);
                    if (Math.abs(o.chunkX - r.chunkX) <= radius && Math.abs(o.chunkZ - r.chunkZ) <= radius) {
                        used[j] = true;
                        queue.add(j);
                    }
                }
            }
            out.add(group);
        }
        return out;
    }

    // ------------------------------------------------------------------ generator vocabulary

    static void loadGeneratorPalette(String spec) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String part : spec.split(";")) {
            Path p = Path.of(part);
            if (Files.isDirectory(p)) {
                Files.walk(p).filter(f -> f.toString().endsWith(".nbt")).forEach(files::add);
            } else {
                files.add(p);
            }
        }
        for (Path f : files) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
                Object root = NbtReader.readGzip(in);
                Map<String, Object> structure = NbtReader.compound(root);
                for (Object entry : NbtReader.list(structure, "palette")) {
                    Map<String, Object> state = NbtReader.compound(entry);
                    String name = String.valueOf(state.get("Name"));
                    if (name.startsWith("minecraft:")) {
                        name = name.substring("minecraft:".length());
                    }
                    GEN.add(name);
                }
            }
        }
    }

    // ------------------------------------------------------------------ chunk decode

    static NbtRewriter.Compound readChunkAt(FileChannel channel, int offset, int sectors)
            throws IOException {
        long byteOffset = (long) offset * 4096L;
        ByteBuffer head = ByteBuffer.allocate(5);
        if (channel.read(head, byteOffset) < 5) {
            return null;
        }
        head.flip();
        int length = head.getInt();
        int compression = head.get() & 0xFF;
        if (length <= 1 || length > sectors * 4096) {
            return null;
        }
        ByteBuffer payload = ByteBuffer.allocate(length - 1);
        int read = 0;
        while (read < length - 1) {
            int n = channel.read(payload, byteOffset + 5 + read);
            if (n <= 0) {
                break;
            }
            read += n;
        }
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

    static void countChunk(NbtRewriter.Compound chunk, int chunkX, int chunkZ,
                           int ymin, int ymax, ChunkRecord record) {
        NbtRewriter.Tag sectionsTag = chunk.get("sections");
        if (sectionsTag == null) {
            return;
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
            int n = palette.size();
            String[] names = new String[n];
            boolean allAir = true;
            for (int i = 0; i < n; i++) {
                String name = String.valueOf(palette.get(i).asCompound().get("Name").value);
                if (name.startsWith("minecraft:")) {
                    name = name.substring("minecraft:".length());
                }
                names[i] = name;
                if (!name.endsWith("air")) {
                    allAir = false;
                }
            }
            if (allAir) {
                int vol = 16 * 16 * 16;
                record.blocks += vol;
                record.air += vol;
                continue;
            }
            long[] data = null;
            NbtRewriter.Tag dataTag = states.get("data");
            if (dataTag != null) {
                data = (long[]) dataTag.value;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1)));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            int[] counts = new int[n];
            for (int y = 0; y < 16; y++) {
                int worldY = sectionY * 16 + y;
                if (worldY < ymin || worldY > ymax) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    int base = (y << 8) | (z << 4);
                    for (int x = 0; x < 16; x++) {
                        int index = base | x;
                        int state;
                        if (data == null) {
                            state = 0;
                        } else {
                            int longIndex = index / perLong;
                            int off = (index % perLong) * bits;
                            state = (int) ((data[longIndex] >>> off) & mask);
                        }
                        if (state < 0 || state >= n) {
                            state = 0;
                        }
                        counts[state]++;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                int c = counts[i];
                if (c == 0) {
                    continue;
                }
                String name = names[i];
                record.blocks += c;
                if (name.endsWith("air")) {
                    record.air += c;
                } else if (isNatural(name) && !TRACKED_NATURAL.contains(name)) {
                    record.natural += c;
                } else {
                    if (isNatural(name)) {
                        record.natural += c;
                    }
                    record.names.merge(name, c, Integer::sum);
                }
            }
            record.sectionCount++;
        }
    }

    // ------------------------------------------------------------------ types

    static final class ChunkRecord {
        final int chunkX;
        final int chunkZ;
        final Map<String, Integer> names = new HashMap<>();
        int gen;
        int natural;
        int other;
        int obstacle;
        int otherObstacle;
        int generatorBlocks;
        int artifact;
        int naturalStructure;
        int air;
        int blocks;
        int sectionCount;

        ChunkRecord(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    static final class Box {
        final String name;
        final int x1;
        final int y1;
        final int z1;
        final int x2;
        final int y2;
        final int z2;

        Box(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
            this.name = name;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
        }

        static Box parse(String line) {
            String[] pipe = line.split("\\|");
            int[] a = ints(pipe[2]);
            int[] b = ints(pipe[3]);
            return new Box(pipe[0], a[0], a[1], a[2], b[0], b[1], b[2]);
        }

        static int[] ints(String s) {
            String[] parts = s.trim().split("\\s+");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])};
        }
    }

    private ChunkScan() {
    }
}
