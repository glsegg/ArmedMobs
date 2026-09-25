import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the 塔科夫Scav city presets - one per {@code tools/city-layout*.json}.
 *
 * <p>For every layout this writes, from the same in-memory block grid so they can never drift apart:</p>
 * <ol>
 *   <li>{@code src/main/resources/data/tarkovscav/structures/&lt;name&gt;.nbt} - a real structure template,
 *       placeable with {@code /place template tarkovscav:&lt;name&gt;} and usable as the start piece of the
 *       matching jigsaw structure. <b>Air is written explicitly</b>, because a structure that only lists
 *       solid blocks would leave the surrounding terrain inside the buildings instead of carving them
 *       out;</li>
 *   <li>{@code tools/spike/work/&lt;name&gt;_commands.txt} - the same city as {@code /setblock} /
 *       {@code /fill} commands, which {@code tools/build_city.ps1} feeds to a dev server over RCON
 *       (air skipped there: the platform is already empty);</li>
 *   <li>{@code tools/spike/work/&lt;name&gt;_platform.txt} - the 64 x 64 stone platform and the air clear
 *       around it, as commands, so the same city can be laid out in a flat sandbox world.</li>
 * </ol>
 *
 * <h2>The two palettes are constants, on purpose</h2>
 * <p>{@link #EXTERIOR_MODERN} is the <b>outside</b> of a building (stone bricks, deepslate, smooth stone,
 * grey concrete, a dark frame, glass panes, a dark oak double door with a framed opening and stone steps)
 * and {@link #INTERIOR_SCAV} is what a building looks like <b>inside</b> after it was looted: crate and
 * barrel stacks, sandbag piles, plank barricades, iron bar gates, overturned tables, rubble, shelves and
 * lockers, lanterns and end rods, carpets and potted plants. Furniture is built out of slabs, signs,
 * fences and trapdoors - all vanilla blocks, because a structure is placed by the server with only this
 * mod, TaCZ and GeckoLib loaded, and a block from any other mod would place as air.</p>
 *
 * <p>A layout picks a palette by name ({@code "palette": "modern_scav"}) or carries its own inline
 * {@code palette} object. Swapping either palette wholesale is a one-place edit here, and
 * {@code StructureNbtTest} classifies every palette entry through {@link #roleOfState} so a new block
 * cannot slip into a structure without being counted as cover, furniture or structure.</p>
 *
 * <p>Run it from the repository root: {@code java -cp tools/spike/out CityStructureGen [layout.json ...]}
 * - with no arguments every {@code tools/city-layout*.json} is regenerated.</p>
 */
public final class CityStructureGen {
    /** 1.20.1. Keeps the structure from being "upgraded" through a datafixer on load. */
    private static final int DATA_VERSION = 3465;

    // ================================================================== the palettes

    /** What a palette entry is for. The self test counts COVER / FURNITURE / LIGHT per floor. */
    public enum Role {
        AIR, GROUND, STREET, WALL, FLOOR, ROOF, WINDOW, DOOR, FRAME, STEP, LADDER, COVER, FURNITURE, LIGHT,
        /**
         * A block entity that is placed for the mob it produces, not for its looks - the indoor
         * {@code minecraft:spawner}. Its own role so no cover / furniture / light count can ever include it
         * by accident, and so the NBT gate can name it.
         */
        SPAWNER
    }

    /**
     * The outside of a modern block: stone brick and deepslate walls, smooth stone and grey concrete
     * bands, a dark frame grid, glass panes, and a dark oak door in a chiselled frame with steps.
     */
    public static final Map<String, Map<String, Object>> EXTERIOR_MODERN = exteriorModern();

    /**
     * What the inside of a looted building looks like: crate and barrel stacks, sandbag piles, plank
     * barricades, iron bar gates, overturned tables, rubble, shelves and lockers, lanterns and end rods,
     * carpets and potted plants.
     *
     * <p>Every key here has a <b>distinct</b> block state string, so the self test can map a palette
     * entry in the NBT back to its key and therefore to its {@link Role}.</p>
     */
    public static final Map<String, Map<String, Object>> INTERIOR_SCAV = interiorScav();

    /** The palette a layout gets when it names {@code "modern_scav"} (the shipped one). */
    public static final Map<String, Map<String, Map<String, Object>>> PALETTES = namedPalettes();

    private static Map<String, Map<String, Object>> exteriorModern() {
        Map<String, Map<String, Object>> palette = new LinkedHashMap<>();
        put(palette, "ground", "minecraft:coarse_dirt", Role.GROUND);
        put(palette, "road", "minecraft:gray_concrete", Role.STREET);
        put(palette, "line", "minecraft:light_gray_concrete", Role.STREET);
        put(palette, "walk", "minecraft:smooth_stone", Role.STREET);
        put(palette, "wall_brick", "minecraft:stone_bricks", Role.WALL);
        put(palette, "wall_slate", "minecraft:deepslate_bricks", Role.WALL);
        put(palette, "wall_smooth", "minecraft:smooth_stone", Role.WALL);
        put(palette, "wall_concrete", "minecraft:gray_concrete", Role.WALL);
        // The user's own two concrete walls (M1 survey of the hand-built district): the generator can now
        // build in his palette instead of only in the preset's.
        put(palette, "wall_concrete_light", "minecraft:light_gray_concrete", Role.WALL);
        put(palette, "wall_concrete_brown", "minecraft:brown_concrete", Role.WALL);
        // The terracotta wainscot every ground floor of his buildings has.
        put(palette, "skirt", "minecraft:terracotta", Role.WALL);
        put(palette, "skirt_alt", "minecraft:white_terracotta", Role.WALL);
        put(palette, "skirt_warm", "minecraft:orange_terracotta", Role.WALL);
        put(palette, "wall_inner", "minecraft:stone_brick_wall", Role.WALL);
        put(palette, "accent", "minecraft:polished_deepslate", Role.FRAME);
        put(palette, "frame", "minecraft:stripped_dark_oak_log", Role.FRAME,
                "axis", "y");
        put(palette, "floor", "minecraft:polished_andesite", Role.FLOOR);
        put(palette, "floor_inner", "minecraft:polished_diorite", Role.FLOOR);
        put(palette, "roof", "minecraft:deepslate_tiles", Role.ROOF);
        put(palette, "window", "minecraft:glass_pane", Role.WINDOW,
                "waterlogged", "false");
        put(palette, "window_band", "minecraft:light_gray_stained_glass_pane", Role.WINDOW,
                "waterlogged", "false");
        put(palette, "door_frame", "minecraft:chiseled_stone_bricks", Role.FRAME);
        // A real wooden door. Both halves are set explicitly and the second door of a pair is hinged the
        // other way, so an entrance reads as a double door.
        put(palette, "door", "minecraft:dark_oak_door", Role.DOOR,
                "facing", "south", "half", "lower", "hinge", "left", "powered", "false", "open", "false");
        put(palette, "step", "minecraft:stone_brick_stairs", Role.STEP,
                "facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
        put(palette, "ladder", "minecraft:ladder", Role.LADDER,
                "facing", "south", "waterlogged", "false");
        // Extra wall / floor / roof / window looks for the big strongpoint, so 18 buildings do not all read
        // as the same block. They are ADDITIONS: no shipped preset names them, so nothing else moves.
        put(palette, "wall_mossy", "minecraft:mossy_stone_bricks", Role.WALL);
        put(palette, "floor_tile", "minecraft:polished_granite", Role.FLOOR);
        put(palette, "roof_brick", "minecraft:polished_blackstone_bricks", Role.ROOF);
        put(palette, "roof_warm", "minecraft:brown_terracotta", Role.ROOF);
        put(palette, "window_gray", "minecraft:gray_stained_glass_pane", Role.WINDOW,
                "waterlogged", "false");
        return palette;
    }

    private static Map<String, Map<String, Object>> interiorScav() {
        Map<String, Map<String, Object>> palette = new LinkedHashMap<>();
        // ---- cover: things a mob can shoot over or hide behind ------------------------------------
        put(palette, "cover_sandbag", "minecraft:white_concrete", Role.COVER);
        put(palette, "cover_sandbag_top", "minecraft:white_carpet", Role.COVER);
        put(palette, "cover_barrier", "minecraft:iron_bars", Role.COVER,
                "waterlogged", "false");
        put(palette, "cover_wall", "minecraft:cobblestone_wall", Role.COVER,
                "up", "true", "waterlogged", "false");
        put(palette, "cover_rubble", "minecraft:cobblestone", Role.COVER);
        put(palette, "cover_rubble_top", "minecraft:cobblestone_slab", Role.COVER,
                "type", "bottom", "waterlogged", "false");
        put(palette, "cover_brick", "minecraft:cracked_stone_bricks", Role.COVER);
        put(palette, "cover_plank", "minecraft:oak_slab", Role.COVER,
                "type", "bottom", "waterlogged", "false");
        put(palette, "cover_plank_lid", "minecraft:oak_trapdoor", Role.COVER,
                "facing", "north", "half", "bottom", "open", "false", "powered", "false",
                "waterlogged", "false");
        put(palette, "cover_crate", "minecraft:barrel", Role.COVER,
                "facing", "north", "open", "false");
        put(palette, "cover_crate_top", "minecraft:barrel", Role.COVER,
                "facing", "up", "open", "false");
        put(palette, "cover_table_top", "minecraft:dark_oak_trapdoor", Role.COVER,
                "facing", "north", "half", "top", "open", "true", "powered", "false",
                "waterlogged", "false");
        put(palette, "cover_car", "minecraft:black_concrete", Role.COVER);
        put(palette, "cover_car_glass", "minecraft:tinted_glass", Role.COVER);
        // ---- furniture: what is left in the rooms --------------------------------------------------
        put(palette, "furn_crate", "minecraft:barrel", Role.FURNITURE,
                "facing", "east", "open", "false");
        put(palette, "furn_chest", "minecraft:chest", Role.FURNITURE,
                "facing", "south", "type", "single", "waterlogged", "false");
        // The LOOT chest (deliverable C): the same block, a different state so the NBT gates can tell a
        // container the user can open from a decorative one, and its block entity carries a LootTable.
        put(palette, "loot_chest", "minecraft:chest", Role.FURNITURE,
                "facing", "north", "type", "single", "waterlogged", "false");
        // The indoor spawner (deliverable B). A block entity, so the one that carries SpawnData is written
        // by the generator's NBT writer, not by the palette alone.
        put(palette, "spawner", "minecraft:spawner", Role.SPAWNER);
        put(palette, "furn_shelf", "minecraft:bookshelf", Role.FURNITURE);
        put(palette, "furn_shelf_top", "minecraft:stripped_oak_log", Role.FURNITURE,
                "axis", "y");
        put(palette, "furn_table_top", "minecraft:oak_slab", Role.FURNITURE,
                "type", "top", "waterlogged", "false");
        put(palette, "furn_table_floor", "minecraft:oak_slab", Role.FURNITURE,
                "type", "double", "waterlogged", "false");
        put(palette, "furn_leg", "minecraft:oak_fence", Role.FURNITURE,
                "east", "false", "north", "false", "south", "false", "waterlogged", "false",
                "west", "false");
        put(palette, "furn_stool", "minecraft:oak_stairs", Role.FURNITURE,
                "facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
        put(palette, "furn_sign", "minecraft:oak_sign", Role.FURNITURE,
                "rotation", "8", "waterlogged", "false");
        put(palette, "furn_cabinet", "minecraft:barrel", Role.FURNITURE,
                "facing", "south", "open", "false");
        put(palette, "furn_carpet", "minecraft:red_carpet", Role.FURNITURE);
        put(palette, "furn_carpet_grey", "minecraft:gray_carpet", Role.FURNITURE);
        put(palette, "furn_plant", "minecraft:potted_fern", Role.FURNITURE);
        put(palette, "furn_plant2", "minecraft:potted_oak_sapling", Role.FURNITURE);
        put(palette, "furn_cauldron", "minecraft:cauldron", Role.FURNITURE);
        put(palette, "furn_crafting", "minecraft:crafting_table", Role.FURNITURE);
        // ---- light ---------------------------------------------------------------------------------
        put(palette, "light_lantern", "minecraft:lantern", Role.LIGHT,
                "hanging", "false", "waterlogged", "false");
        put(palette, "light_lantern_hang", "minecraft:lantern", Role.LIGHT,
                "hanging", "true", "waterlogged", "false");
        put(palette, "light_chain", "minecraft:chain", Role.LIGHT,
                "axis", "y", "waterlogged", "false");
        put(palette, "light_end_rod", "minecraft:end_rod", Role.LIGHT,
                "facing", "up");
        // A plain torch: what the user's own ground floors use, and the cheapest readable light.
        put(palette, "light_torch", "minecraft:torch", Role.LIGHT);
        // A bed, head and foot as the two halves the game expects (a bare "white_bed" would not render).
        put(palette, "furn_bed", "minecraft:white_bed", Role.FURNITURE,
                "facing", "north", "part", "head", "occupied", "false");
        put(palette, "furn_bed_foot", "minecraft:white_bed", Role.FURNITURE,
                "facing", "north", "part", "foot", "occupied", "false");
        return palette;
    }

    private static Map<String, Map<String, Map<String, Object>>> namedPalettes() {
        Map<String, Map<String, Map<String, Object>>> palettes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> combined = new LinkedHashMap<>(exteriorModern());
        combined.putAll(interiorScav());
        palettes.put("modern_scav", combined);
        return palettes;
    }

    /** Registers one palette entry; {@code properties} is a flat {@code key, value, key, value} list. */
    private static void put(Map<String, Map<String, Object>> palette, String key, String name, Role role,
                            String... properties) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Name", name);
        entry.put("_role", role);
        if (properties.length > 0) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i + 1 < properties.length; i += 2) {
                values.put(properties[i], properties[i + 1]);
            }
            entry.put("Properties", values);
        }
        palette.put(key, entry);
    }

    /** The palette a layout asks for, by name, or the default. */
    public static Map<String, Map<String, Object>> paletteFor(String name) {
        if (name == null) {
            return PALETTES.get("modern_scav");
        }
        Map<String, Map<String, Object>> named = PALETTES.get(name);
        if (named != null) {
            return named;
        }
        throw new IllegalArgumentException("unknown palette '" + name + "' (have " + PALETTES.keySet() + ")");
    }

    /** The palette a layout object asks for: {@code "palette": "modern_scav"} or an inline object. */
    public static Map<String, Map<String, Object>> paletteOf(Map<String, Object> layout) {
        Object declared = layout.get("palette");
        if (declared instanceof String name) {
            return paletteFor(name);
        }
        if (declared instanceof Map<?, ?> map) {
            Map<String, Map<String, Object>> inline = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                inline.put(String.valueOf(entry.getKey()), Json.object(entry.getValue()));
            }
            for (Map.Entry<String, Map<String, Object>> entry : inline.entrySet()) {
                if (!entry.getValue().containsKey("_role")) {
                    entry.getValue().put("_role", roleOfState(entry.getValue()));
                }
            }
            return inline;
        }
        return paletteFor(null);
    }

    /**
     * The role of a palette entry, by its exact block state. This is what the self test counts with, so
     * a block that nobody classified fails that test instead of quietly counting as "structure".
     */
    public static Role roleOfState(Map<String, Object> entry) {
        String state = stateString(entry);
        for (Map<String, Map<String, Object>> palette : List.of(PALETTES.get("modern_scav"))) {
            for (Map.Entry<String, Map<String, Object>> candidate : palette.entrySet()) {
                if (stateString(candidate.getValue()).equals(state)) {
                    Object role = candidate.getValue().get("_role");
                    return role instanceof Role value ? value : Role.WALL;
                }
            }
        }
        // A property override of a declared key (a door half, a stair facing, a trapdoor) - decide it by
        // block name, which is unambiguous for those families.
        String name = String.valueOf(entry.get("Name"));
        if (isDoorName(name)) {
            return Role.DOOR;
        }
        if (name.endsWith("_stairs")) {
            return Role.STEP;
        }
        if (name.endsWith("_trapdoor") || name.endsWith("_fence") || name.endsWith("_sign")
                || name.endsWith("_wall")) {
            return Role.FURNITURE;
        }
        if (name.contains("lantern") || name.endsWith("end_rod") || name.endsWith("chain")) {
            return Role.LIGHT;
        }
        if (name.contains("glass")) {
            return Role.WINDOW;
        }
        if (name.endsWith("_slab")) {
            return Role.COVER;
        }
        return Role.WALL;
    }

    /** The role of a layout palette key (used by the generator itself while it builds). */
    public static Role roleOfKey(String key, Map<String, Map<String, Object>> palette) {
        Map<String, Object> entry = palette.get(key);
        if (entry == null) {
            return Role.WALL;
        }
        Object role = entry.get("_role");
        return role instanceof Role value ? value : roleOfState(entry);
    }

    /** Every door block name this generator can place; the self test looks for these. */
    public static boolean isDoorName(String name) {
        return name != null && name.endsWith("_door") && !name.endsWith("trapdoor");
    }

    /**
     * Block names that are not a full cube, i.e. that cannot carry a door. A door's sill has to be a
     * full block or the door pops off the moment the chunk ticks.
     */
    private static final Set<String> NOT_FULL_CUBE = Set.of(
            "minecraft:glass_pane", "minecraft:light_gray_stained_glass_pane",
            "minecraft:gray_stained_glass_pane",
            "minecraft:iron_bars",
            "minecraft:chain", "minecraft:lantern", "minecraft:end_rod", "minecraft:ladder",
            "minecraft:cobblestone_wall", "minecraft:cobblestone_slab", "minecraft:oak_slab",
            "minecraft:oak_trapdoor", "minecraft:dark_oak_trapdoor", "minecraft:oak_fence",
            "minecraft:oak_sign", "minecraft:oak_stairs", "minecraft:stone_brick_stairs",
            "minecraft:stone_brick_wall", "minecraft:red_carpet", "minecraft:gray_carpet",
            "minecraft:white_carpet", "minecraft:potted_fern", "minecraft:potted_oak_sapling",
            "minecraft:chest", "minecraft:crafting_table", "minecraft:cauldron");

    /** True when a block name can hold a door up (a full cube). */
    public static boolean isFullCube(String name) {
        return name != null && !NOT_FULL_CUBE.contains(name) && !isDoorName(name);
    }

    /**
     * Blocks whose BLOCK SUPPORT SHAPE is a full cube even though their outline is not - a chest, a
     * crafting table and a cauldron are full cubes for {@code isFaceSturdy(FULL)}, which is the test vanilla
     * panes, fences and walls use to decide whether to attach to their neighbour. They are excluded from
     * {@link #NOT_FULL_CUBE} here rather than removed from it, because that set also answers "can this hold
     * a door up", where the old answer is kept.
     */
    private static final Set<String> NOT_FULL_SUPPORT = NOT_FULL_CUBE.stream()
            .filter((name) -> !name.equals("minecraft:chest")
                    && !name.equals("minecraft:crafting_table")
                    && !name.equals("minecraft:cauldron"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * Vanilla's {@code isFaceSturdy(..., SupportType.FULL)}, approximated from the block name: the shape
     * this project's connector bake-in and its gate both use. Out-of-structure neighbours are not
     * connectable, exactly like vanilla structures.
     */
    public static boolean supportsFullFace(String name) {
        return name != null && !NOT_FULL_SUPPORT.contains(name) && !isDoorName(name);
    }

    /** The layout keys of one palette, for diagnostics. */
    public static Set<String> paletteKeys(String paletteName) {
        return new LinkedHashSet<>(paletteFor(paletteName).keySet());
    }

    // ------------------------------------------------------------------ the generator itself

    private final String name;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /** One state key per cell; {@code "air"} or {@code "&lt;baseKey&gt;|prop=value,..."}. */
    private final String[] cells;
    private final Map<String, Map<String, Object>> palette;
    /** state key -&gt; the palette entry (Name + Properties), in first-use order after "air". */
    private final Map<String, Map<String, Object>> statePalette = new LinkedHashMap<>();
    /** state key -&gt; the layout palette key it came from. */
    private final Map<String, String> baseKeyOfState = new LinkedHashMap<>();
    private final List<String> stateOrder = new ArrayList<>();
    /** Per floor role counts, keyed {@code "building/floor"} -&gt; role -&gt; count. */
    private final Map<String, Map<Role, Integer>> floorCounts = new TreeMap<>();
    /** Every door placed, so the generator can check its own frame before the self test does. */
    private final List<int[]> doors = new ArrayList<>();
    /** Every door with its facing, for the post-pass that guarantees a doorway is never blocked. */
    private final List<int[]> doorCells = new ArrayList<>();
    private final List<String> doorFacings = new ArrayList<>();
    private final List<String> doorHinges = new ArrayList<>();
    /** The entrance door of each building: the seed of the connectivity flood fill. */
    private final List<int[]> entranceDoors = new ArrayList<>();
    /**
     * Interior openings that are NOT door blocks - the 1x2 passages punched through the partition walls.
     * They are recorded so the same post-pass that protects a door also protects these: the cover-line pass
     * runs last and used to be able to seal a room by placing cover in its only doorway.
     * Layout: {x, y, z, alongX ? 1 : 0}.
     */
    private final List<int[]> openings = new ArrayList<>();
    /** Every interior room, as {x0, z0, x1, z1, floorY}: the connectivity invariant counts these. */
    private final List<int[]> roomRecords = new ArrayList<>();
    /**
     * Every BLOCK ENTITY the structure carries, keyed by grid index: the compound written as the block
     * entry's {@code nbt} tag. Structure NBT supports {@code blocks: [{pos, state, nbt}]} and this is how a
     * planned {@code minecraft:spawner} gets its {@code SpawnData} and a {@code minecraft:chest} its
     * {@code LootTable} - without it the game would place a blank, inert block.
     *
     * <p>Deliberately a map and not per-list bookkeeping: the NBT writer walks the whole volume in one
     * pass, so a lookup by index is the natural shape.</p>
     */
    private final Map<Integer, Map<String, Object>> blockEntities = new LinkedHashMap<>();
    /** Cells that received a spawner / a chest, for the report and the gates. */
    private final List<int[]> spawnerCells = new ArrayList<>();
    private final List<int[]> chestCells = new ArrayList<>();
    /**
     * Every cell a floor hole needs kept clear (the hole itself plus one cell above and two below), by grid
     * index. A fixture placed in one of them would plug the vertical connection the hole exists for.
     */
    private final Set<Integer> holeClearance = new LinkedHashSet<>();
    /** Reported by the post-passes. */
    private int bakedConnectors;
    private int repairedDoorwayCells;
    private int sealedRooms;
    /** The porch report (2026-10): how many stairs and how many entrances actually got a run. */
    private int porchStairs;
    private int porchEntrances;
    private int breachedRooms;
    /** Cells {@link #sweepDetachedDecor} cleared, for the log. */
    private int sweptDecor;

    private CityStructureGen(String name, int sizeX, int sizeY, int sizeZ,
                             Map<String, Map<String, Object>> palette) {
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new String[sizeX * sizeY * sizeZ];
        this.palette = palette;
        java.util.Arrays.fill(this.cells, "air");
        this.statePalette.put("air", Map.of("Name", "minecraft:air"));
        this.baseKeyOfState.put("air", "air");
        this.stateOrder.add("air");
    }

    /**
     * Pieces-only mode: when true the generator writes one structure NBT per building into {@link #piecesDir}
     * and does NOT touch the city template - the M4 path, so the shipped city .nbt files stay byte-identical
     * while new buildings join the jigsaw district pool.
     */
    private static boolean piecesOnly;
    private static String piecesDir = "src/main/resources/data/tarkovscav/structures/buildings";
    /** How many blocks of footing the generated pieces are given (mirrors the mod's city.foundationDepth). */
    private static int foundation = 5;
    /**
     * Report-only mode ({@code --report}): build the layout, print the interior-variation report and the
     * floor report, and write NOTHING. This is what {@code tools/selftest_interior_variation.js} runs - a
     * gate must never rewrite the shipped structures.
     */
    private static boolean reportOnly;

    public static void main(String[] args) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("tools"))) {
            throw new IllegalStateException("run this from the repository root (tools/ not found in " + root + ")");
        }

        List<Path> layouts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pieces-only" -> piecesOnly = true;
                case "--report" -> reportOnly = true;
                case "--out-dir" -> piecesDir = args[++i];
                case "--foundation" -> foundation = Integer.parseInt(args[++i]);
                default -> layouts.add(Path.of(args[i]));
            }
        }
        if (layouts.isEmpty()) {
            try (var stream = Files.list(root.resolve("tools"))) {
                stream.filter(path -> path.getFileName().toString().matches("city-layout.*\\.json"))
                        .sorted()
                        .forEach(layouts::add);
            }
        }
        if (layouts.isEmpty()) {
            throw new IllegalStateException("no tools/city-layout*.json found");
        }

        for (Path layoutFile : layouts) {
            generate(root, layoutFile);
            System.out.println();
        }
    }

    /** Reads one layout and writes its NBT, its command list, its platform and its floor report. */
    private static void generate(Path root, Path layoutFile) throws IOException {
        Map<String, Object> layout = Json.object(Json.parse(layoutFile));
        String name = Json.string(layout, "name", "city_small");
        List<Object> size = Json.array(layout, "size");
        int sizeX = ((Number) size.get(0)).intValue();
        int sizeY = ((Number) size.get(1)).intValue();
        int sizeZ = ((Number) size.get(2)).intValue();

        CityStructureGen city = new CityStructureGen(name, sizeX, sizeY, sizeZ, paletteOf(layout));
        city.build(layout);

        // ---- pieces-only mode (M4): write ONE structure NBT per building and touch nothing else. This is
        // how the jigsaw pool gets buildings that follow the same standard as the hand-built ones, without
        // regenerating (and therefore changing) the four shipped city templates.
        if (piecesOnly) {
            System.out.println("city layout   : " + layoutFile);
            System.out.println("name          : " + name);
            System.out.println("mode          : pieces only (the city .nbt / commands / platform are NOT written)");
            city.exportBuildingPieces(root, layout, piecesDir, foundation);
            city.printInteriorReport();
            return;
        }

        if (reportOnly) {
            System.out.println("city layout   : " + layoutFile);
            System.out.println("name          : " + name);
            System.out.println("mode          : report only (NOTHING is written)");
            city.printFloorReport(layout);
            city.printInteriorReport();
            return;
        }

        Path nbtOut = root.resolve("src/main/resources/data/tarkovscav/structures/" + name + ".nbt");
        Files.createDirectories(nbtOut.getParent());
        city.writeNbt(nbtOut);

        // The per-building map the runtime uses to give each building its own faction. Written from the SAME
        // layout the walls are built from, so it can never describe a building the structure does not have.
        Path buildingsOut = root.resolve("src/main/resources/data/tarkovscav/city_buildings/" + name + ".json");
        city.writeBuildingMap(buildingsOut, layout);

        Path commandsOut = root.resolve("tools/spike/work/" + name + "_commands.txt");
        Files.createDirectories(commandsOut.getParent());
        int commands = city.writeCommands(commandsOut);

        Map<String, Object> platform = layout.get("platform") instanceof Map<?, ?> map
                ? Json.object(map) : Map.of();
        int margin = Json.intOf(platform, "margin", 8);
        int clearTo = Json.intOf(platform, "clear_to", 150);
        int stoneBelow = Json.intOf(platform, "stone_below", 8);
        Path platformOut = root.resolve("tools/spike/work/" + name + "_platform.txt");
        int platformCommands = city.writePlatformCommands(platformOut, margin, clearTo, stoneBelow);

        long solid = city.countSolid();
        System.out.println("city layout   : " + layoutFile);
        System.out.println("name          : " + name);
        System.out.println("palette       : " + city.palette.size() + " key(s), "
                + city.statePalette.size() + " distinct state(s) in the NBT");
        System.out.println("size          : " + sizeX + " x " + sizeY + " x " + sizeZ
                + " (" + (sizeX * sizeY * sizeZ) + " positions)");
        System.out.println("solid blocks  : " + solid + " ("
                + Math.round(100.0 * solid / (sizeX * sizeY * sizeZ)) + "% of the volume)");
        System.out.println("doors         : " + city.doors.size());
        System.out.println("porches       : " + city.porchStairs + " stair(s) across " + city.porchEntrances
                + " entrance(s)");
        System.out.println("post-passes   : baked " + city.bakedConnectors + " connector state(s),"
                + " cleared " + city.repairedDoorwayCells + " cell(s) out of a doorway,"
                + " sealedRooms=" + city.sealedRooms + " breached=" + city.breachedRooms
                + ", rooms=" + city.roomRecords.size()
                + ", detachedDecor=" + city.sweptDecor);
        System.out.println("wrote         : " + nbtOut + " (" + Files.size(nbtOut) + " bytes, gzipped NBT)");
        System.out.println("wrote         : " + commandsOut + " (" + commands + " RCON commands)");
        System.out.println("wrote         : " + platformOut + " (" + platformCommands
                + " RCON commands: platform + clear)");
        city.printFloorReport(layout);
        System.out.println("block entities: " + city.blockEntities.size() + " (" + city.spawnerCells.size()
                + " spawner(s), " + city.chestCells.size() + " loot chest(s))");
        city.printInteriorReport();
        for (int[] cell : city.spawnerCells) {
            System.out.println("  spawner at " + cell[0] + "," + cell[1] + "," + cell[2]);
        }
        for (int[] cell : city.chestCells) {
            System.out.println("  loot chest at " + cell[0] + "," + cell[1] + "," + cell[2]);
        }
    }

    // ------------------------------------------------------------------ building

    /**
     * Writes one structure NBT per building in the layout (M4): the building's own box, trimmed of the
     * air-only border, with {@code foundation} blocks of footing grown downwards out of the material each
     * column bottoms out on, and a real {@code minecraft:jigsaw} connector at its door so the district's
     * template pool can attach it to a street.
     *
     * <p>The four shipped city templates are never touched by this path - that is the point of the mode.</p>
     */
    private void exportBuildingPieces(Path root, Map<String, Object> layout, String outDir, int foundation)
            throws IOException {
        Path dir = root.resolve(outDir);
        Files.createDirectories(dir);
        int index = 0;
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            if (this.lastBuildingBox == null) { continue; }   // buildBuilding records the last one only
            index++;
            String buildingName = Json.string(building, "name", "building");
            int x0 = Json.intOf(building, "x", 0);
            int z0 = Json.intOf(building, "z", 0);
            int w = Json.intOf(building, "w", 16);
            int d = Json.intOf(building, "d", 16);
            int floors = Json.intOf(building, "floors", 4);
            int floorHeight = Json.intOf(building, "floor_height", 4);
            int topY = 1 + floors * floorHeight;
            String name = String.format("%s_b%d", Json.string(layout, "name", "district"), index);
            int[] box = new int[]{x0, 1, z0, x0 + w - 1, topY, z0 + d - 1};
            Path out = dir.resolve(name + ".nbt");
            this.pieceDoor = this.buildingDoors.get(buildingName);
            this.pieceDoorSide = this.buildingDoorSides.getOrDefault(buildingName, "south");
            writePiece(out, box, foundation, buildingName);
        }
        System.out.println("pieces        : " + index + " building piece(s) -> " + dir);
    }

    /**
     * One piece: trim the air-only border, grow the footing, write the NBT, and inject the door connector.
     * The connector is placed one block OUTSIDE the door, pointing away from the building, so the piece can
     * be attached to a street lane without punching a hole in its wall.
     */
    private void writePiece(Path out, int[] box, int foundation, String buildingName) throws IOException {
        int bx0 = box[0], by0 = box[1], bz0 = box[2], bx1 = box[3], by1 = box[4], bz1 = box[5];
        // trim to the non-air contents (air inside the trimmed box is kept: it carves the interior)
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = by0; y <= by1; y++) {
            for (int z = bz0; z <= bz1; z++) {
                for (int x = bx0; x <= bx1; x++) {
                    String state = this.cell(x, y, z);
                    if (state == null || state.equals("air")) { continue; }
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (minX > maxX) {
            System.out.println("  piece " + out.getFileName() + ": EMPTY, skipped");
            return;
        }
        int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;
        // The connector sits one block OUTSIDE the door, so it may fall outside the trimmed box: widen the box
        // to include it rather than shifting the geometry around afterwards.
        int[] connectorGen = null;
        String orientation = "north_up";
        if (this.pieceDoor != null) {
            int doorX = this.pieceDoor[0];
            int doorY = this.pieceDoor[1];
            int doorZ = this.pieceDoor[2];
            int outX = this.pieceDoorSide.equals("east") ? 1
                    : this.pieceDoorSide.equals("west") ? -1 : 0;
            int outZ = this.pieceDoorSide.equals("south") ? 1
                    : this.pieceDoorSide.equals("north") ? -1 : 0;
            orientation = outZ < 0 ? "north_up" : outZ > 0 ? "south_up"
                    : outX > 0 ? "east_up" : "west_up";
            connectorGen = new int[]{doorX + outX, Math.max(by0, doorY), doorZ + outZ};
            minX = Math.min(minX, connectorGen[0]);
            maxX = Math.max(maxX, connectorGen[0]);
            minZ = Math.min(minZ, connectorGen[2]);
            maxZ = Math.max(maxZ, connectorGen[2]);
            w = maxX - minX + 1;
            d = maxZ - minZ + 1;
        }
        String[][][] grown = new String[w][h + foundation][d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    grown[x][y + foundation][z] = this.cell(minX + x, minY + y, minZ + z);
                }
            }
        }
        // the footing: the material the column already stands on, so it reads as one wall going down
        Map<String, Integer> bottomMaterials = new LinkedHashMap<>();
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    String state = grown[x][y + foundation][z];
                    if (state != null && !state.equals("air")) {
                        bottomMaterials.merge(state, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        String fallback = bottomMaterials.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("wall_brick");
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                String material = null;
                for (int y = 0; y < h; y++) {
                    String state = grown[x][y + foundation][z];
                    if (state != null && !state.equals("air")) { material = state; break; }
                }
                if (material == null) { continue; }
                for (int i = 1; i <= foundation; i++) {
                    grown[x][foundation - i][z] = material;
                }
            }
        }
        // palette + blocks
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int y = 0; y < h + foundation; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    String state = grown[x][y][z];
                    if (state == null) { continue; }
                    if (isPieceStripped(String.valueOf(paletteStateEntry(state).get("Name")))) { continue; }
                    Integer at = paletteIndex.get(state);
                    if (at == null) { at = palette.size(); paletteIndex.put(state, at); palette.add(state); }
                    positions.add(new int[]{x, y, z});
                    indices.add(at);
                }
            }
        }
        // The block entities this piece carries, translated to piece-local coordinates. In pieces-only mode
        // there are none by construction: `isPieceStripped` keeps every container and every spawner out of a
        // piece (the M2 rule `selftest_city_district.js` asserts), so this map is empty for the shipped
        // pieces and the lookup below is the honest, general form rather than a claim that cannot happen.
        Map<Integer, Map<String, Object>> pieceNbt = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : this.blockEntities.entrySet()) {
            int[] global = this.positionOfIndex(entry.getKey());
            if (global[0] < minX || global[0] > maxX || global[1] < minY || global[1] > maxY
                    || global[2] < minZ || global[2] > maxZ) {
                continue;
            }
            pieceNbt.put(((global[1] - minY + foundation) * d + (global[2] - minZ)) * w
                    + (global[0] - minX), entry.getValue());
        }
        // the door connector, one block outside the door (already inside the widened box)
        Integer connectorStateIndex = null;
        String connectorState = "minecraft:jigsaw[orientation=" + orientation + "]";
        if (connectorGen != null) {
            connectorStateIndex = paletteIndex.get(connectorState);
            if (connectorStateIndex == null) {
                connectorStateIndex = palette.size();
                paletteIndex.put(connectorState, connectorStateIndex);
                palette.add(connectorState);
            }
        }
        int blockCount = positions.size() + (connectorGen == null ? 0 : 1);
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out))))) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(3);
            data.writeUTF("DataVersion");
            data.writeInt(DATA_VERSION);
            data.writeByte(8);
            data.writeUTF("author");
            data.writeUTF("tarkovscav CityStructureGen --pieces-only (README 7b)");
            data.writeByte(9);
            data.writeUTF("size");
            data.writeByte(3);
            data.writeInt(3);
            data.writeInt(w);
            data.writeInt(h + foundation);
            data.writeInt(d);
            data.writeByte(9);
            data.writeUTF("palette");
            data.writeByte(10);
            data.writeInt(palette.size());
            for (String state : palette) {
                writePaletteEntry(data, paletteStateEntry(state));
            }
            data.writeByte(9);
            data.writeUTF("blocks");
            data.writeByte(10);
            data.writeInt(blockCount);
            for (int i = 0; i < positions.size(); i++) {
                int[] pos = positions.get(i);
                writeBlock(data, pos[0], pos[1], pos[2], indices.get(i), null,
                        pieceNbt.get((pos[1] * d + pos[2]) * w + pos[0]));
            }
            if (connectorGen != null && connectorStateIndex != null) {
                writeBlock(data, connectorGen[0] - minX, connectorGen[1] - minY + foundation,
                        connectorGen[2] - minZ, connectorStateIndex,
                        new String[]{"tarkovscav:building_door", "minecraft:empty", "minecraft:empty",
                                "minecraft:air", "aligned"}, null);
            }
            data.writeByte(9);
            data.writeUTF("entities");
            data.writeByte(10);
            data.writeInt(0);
            data.writeByte(0);
        }
        System.out.println("  piece " + out.getFileName() + ": " + w + "x" + (h + foundation) + "x" + d
                + " " + blockCount + " blocks, " + palette.size() + " palette, foundation " + foundation
                + (connectorGen != null ? ", connector at " + connectorGen[0] + "," + connectorGen[1] + ","
                        + connectorGen[2] : ", no connector")
                + " -> " + Files.size(out) + " bytes");
    }

    /** A `pos`/`state`[/`nbt`] block entry, the shape StructureTemplate.load expects. */
    private static void writeBlock(DataOutputStream data, int x, int y, int z, int state, String[] jigsaw,
                                   Map<String, Object> nbt) throws IOException {
        data.writeByte(9);
        data.writeUTF("pos");
        data.writeByte(3);
        data.writeInt(3);
        data.writeInt(x);
        data.writeInt(y);
        data.writeInt(z);
        data.writeByte(3);
        data.writeUTF("state");
        data.writeInt(state);
        if (jigsaw != null) {
            data.writeByte(10);          // nbt: TAG_Compound
            data.writeUTF("nbt");
            data.writeByte(8);
            data.writeUTF("id");
            data.writeUTF("minecraft:jigsaw");
            data.writeByte(8);
            data.writeUTF("name");
            data.writeUTF(jigsaw[0]);
            data.writeByte(8);
            data.writeUTF("target");
            data.writeUTF(jigsaw[1]);
            data.writeByte(8);
            data.writeUTF("pool");
            data.writeUTF(jigsaw[2]);
            data.writeByte(8);
            data.writeUTF("final_state");
            data.writeUTF(jigsaw[3]);
            data.writeByte(8);
            data.writeUTF("joint");
            data.writeUTF(jigsaw[4]);
            data.writeByte(3);
            data.writeUTF("x");
            data.writeInt(x);
            data.writeByte(3);
            data.writeUTF("y");
            data.writeInt(y);
            data.writeByte(3);
            data.writeUTF("z");
            data.writeInt(z);
            data.writeByte(0);
        } else if (nbt != null) {
            data.writeByte(10);          // nbt: TAG_Compound of the block entity
            data.writeUTF("nbt");
            // writeTags writes the compound's own closing TAG_End.
            writeTags(data, nbt);
        }
        data.writeByte(0);
    }

    /**
     * Writes a whole TAG_Compound payload (no name, no root header - just the named children and the
     * closing TAG_End), so a block entity's NBT is expressed as plain Java maps/lists/strings/numbers and
     * this one method has to know the tag numbers. Values used so far:
     * {@code String -> TAG_String, Integer -> TAG_Int, Short -> TAG_Short, Byte -> TAG_Byte,
     * Map<String,Object> -> TAG_Compound, List<Object> -> TAG_List (element type from the first entry,
     * TAG_End when empty), int[] -> TAG_Int_Array}.
     */
    private static void writeTags(DataOutputStream data, Map<String, Object> compound) throws IOException {
        for (Map.Entry<String, Object> entry : compound.entrySet()) {
            writeTag(data, entry.getKey(), entry.getValue());
        }
        data.writeByte(0);
    }

    private static void writeTag(DataOutputStream data, String name, Object value) throws IOException {
        if (value instanceof String text) {
            data.writeByte(8);
            data.writeUTF(name);
            data.writeUTF(text);
        } else if (value instanceof Integer number) {
            data.writeByte(3);
            data.writeUTF(name);
            data.writeInt(number);
        } else if (value instanceof Short number) {
            data.writeByte(2);
            data.writeUTF(name);
            data.writeShort(number);
        } else if (value instanceof Byte number) {
            data.writeByte(1);
            data.writeUTF(name);
            data.writeByte(number);
        } else if (value instanceof Long number) {
            data.writeByte(4);
            data.writeUTF(name);
            data.writeLong(number);
        } else if (value instanceof Float number) {
            data.writeByte(5);
            data.writeUTF(name);
            data.writeFloat(number);
        } else if (value instanceof Double number) {
            data.writeByte(6);
            data.writeUTF(name);
            data.writeDouble(number);
        } else if (value instanceof int[] array) {
            data.writeByte(11);
            data.writeUTF(name);
            data.writeInt(array.length);
            for (int element : array) {
                data.writeInt(element);
            }
        } else if (value instanceof Map<?, ?> map) {
            data.writeByte(10);
            data.writeUTF(name);
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> child : map.entrySet()) {
                typed.put(String.valueOf(child.getKey()), child.getValue());
            }
            writeTags(data, typed);
        } else if (value instanceof List<?> list) {
            data.writeByte(9);
            data.writeUTF(name);
            data.writeByte(list.isEmpty() ? 0 : tagTypeOf(list.get(0)));
            data.writeInt(list.size());
            for (Object element : list) {
                writeTagPayload(data, element);
            }
        } else {
            throw new IllegalArgumentException("NBT value for '" + name + "' is not writable: " + value);
        }
    }

    /** The TAG id of one Java value, for the element type byte of a TAG_List. */
    private static int tagTypeOf(Object value) {
        if (value instanceof String) {
            return 8;
        }
        if (value instanceof Integer) {
            return 3;
        }
        if (value instanceof Short) {
            return 2;
        }
        if (value instanceof Byte) {
            return 1;
        }
        if (value instanceof Long) {
            return 4;
        }
        if (value instanceof Float) {
            return 5;
        }
        if (value instanceof Double) {
            return 6;
        }
        if (value instanceof int[]) {
            return 11;
        }
        if (value instanceof Map<?, ?>) {
            return 10;
        }
        if (value instanceof List<?>) {
            return 9;
        }
        throw new IllegalArgumentException("NBT list element type is not writable: " + value);
    }

    /** The payload of one tag (no type byte, no name) - the shape a TAG_List element needs. */
    @SuppressWarnings("unchecked")
    private static void writeTagPayload(DataOutputStream data, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> child : map.entrySet()) {
                typed.put(String.valueOf(child.getKey()), child.getValue());
            }
            writeTags(data, typed);
        } else if (value instanceof List<?> list) {
            data.writeByte(list.isEmpty() ? 0 : tagTypeOf(list.get(0)));
            data.writeInt(list.size());
            for (Object element : list) {
                writeTagPayload(data, element);
            }
        } else if (value instanceof int[] array) {
            data.writeInt(array.length);
            for (int element : array) {
                data.writeInt(element);
            }
        } else if (value instanceof String text) {
            data.writeUTF(text);
        } else if (value instanceof Integer number) {
            data.writeInt(number);
        } else if (value instanceof Short number) {
            data.writeShort(number);
        } else if (value instanceof Byte number) {
            data.writeByte(number);
        } else if (value instanceof Long number) {
            data.writeLong(number);
        } else if (value instanceof Float number) {
            data.writeFloat(number);
        } else if (value instanceof Double number) {
            data.writeDouble(number);
        } else {
            throw new IllegalArgumentException("NBT list element is not writable: " + value);
        }
    }


    /**
     * "Only the building body" - the same rule the extracted pieces follow: a generated piece must not carry
     * a container, a sign, a spawner or fire. The user asked for decoration (beds, torches, ladders, the
     * terracotta wainscot), not for loot, and a generated city must not be able to burn down.
     */
    static boolean isPieceStripped(String state) {
        int bar = state.indexOf('|');
        int bracket = state.indexOf('[');
        String name = bar >= 0 ? state.substring(0, bar)
                : bracket >= 0 ? state.substring(0, bracket) : state;
        if (name.equals("minecraft:fire") || name.equals("minecraft:soul_fire")
                || name.equals("minecraft:mob_spawner") || name.equals("minecraft:spawner")
                || name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest")
                || name.equals("minecraft:barrel") || name.equals("minecraft:hopper")
                || name.equals("minecraft:dispenser") || name.equals("minecraft:dropper")
                || name.equals("minecraft:furnace") || name.equals("minecraft:smoker")
                || name.equals("minecraft:blast_furnace") || name.equals("minecraft:brewing_stand")
                || name.equals("minecraft:lectern") || name.equals("minecraft:ender_chest")) {
            return true;
        }
        return name.endsWith("_sign") || name.endsWith("_wall_sign") || name.endsWith("_hanging_sign");
    }

    /** "minecraft:oak_stairs[facing=north]" -> the palette entry the NBT writer wants. */
    private static Map<String, Object> paletteStateEntry(String state) {
        int literal = state.indexOf('[');
        if (literal > 0) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", state.substring(0, literal));
            Map<String, Object> properties = new LinkedHashMap<>();
            String inner = state.substring(literal + 1, state.length() - 1);
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) { properties.put(pair.substring(0, eq), pair.substring(eq + 1)); }
            }
            entry.put("Properties", properties);
            entry.put("_role", Role.WALL);
            return entry;
        }
        int bracket = state.indexOf('|');
        String key = bracket < 0 ? state : state.substring(0, bracket);
        Map<String, Object> entry = new LinkedHashMap<>();
        if (bracket >= 0) {
            // "key|facing=north,half=lower" - a palette key with property overrides
            String[] overrides = state.substring(bracket + 1).split(",");
            Map<String, Object> base = PALETTES.get("modern_scav").get(key);
            entry.put("Name", base.get("Name"));
            Map<String, Object> properties = new LinkedHashMap<>();
            Object declared = base.get("Properties");
            if (declared instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    properties.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            for (String override : overrides) {
                int eq = override.indexOf('=');
                if (eq > 0) { properties.put(override.substring(0, eq), override.substring(eq + 1)); }
            }
            entry.put("Properties", properties);
            entry.put("_role", roleOfKey(key, PALETTES.get("modern_scav")));
            return entry;
        }
        Map<String, Object> base = PALETTES.get("modern_scav").get(state);
        if (base != null) { return base; }
        entry.put("Name", state);
        entry.put("_role", roleOfState(entry));
        return entry;
    }

    // ------------------------------------------------------------------ building helpers

    /** The layout currently being built, so per-layout switches (ground_floor_look) can be read. */
    private Map<String, Object> layoutFlagOwner = Map.of();

    /** A number a building entry may override and the layout may default: building first, then layout. */
    private int setting(Map<String, Object> building, String key, int fallback) {
        if (building.containsKey(key)) {
            return Json.intOf(building, key, fallback);
        }
        return Json.intOf(this.layoutFlagOwner, key, fallback);
    }

    private void build(Map<String, Object> layout) {
        this.layoutFlagOwner = layout;
        Map<String, Object> street = layout.get("street") instanceof Map<?, ?> map
                ? Json.object(map) : null;
        if (street != null) {
            buildStreet(street);
        } else {
            // No street band: the whole ground layer is one paved plot (a plaza, or a courtyard).
            String plot = Json.string(layout, "plot_block", "ground");
            for (int x = 0; x < this.sizeX; x++) {
                for (int z = 0; z < this.sizeZ; z++) {
                    this.set(x, 0, z, plot);
                }
            }
        }

        for (Object raw : Json.array(layout, "buildings")) {
            buildBuilding(Json.object(raw));
        }

        // The height a street cover entry with "level": "roof" lands on: one above the tallest roof slab.
        this.streetRoofY = 1;
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            int floors = Json.intOf(building, "floors", 3);
            int floorHeight = Json.intOf(building, "floor_height", 4);
            this.streetRoofY = Math.max(this.streetRoofY, 2 + floors * floorHeight);
        }

        for (Object raw : Json.array(layout, "cover")) {
            streetPiece(Json.object(raw));
        }

        // ---- the post-passes, all AFTER the volume is final and BEFORE the NBT is written, because they
        // need the finished neighbour grid and none may be undone by a later build step.
        bakeConnectorStates();
        repairDoorways();
        ensureConnectivity();

        // ---- the FLOOR HOLES (deliverable G) run here, on the CONNECTED grid and before the cover lines:
        // "whatever is below must be reachable floor" is only a meaningful test once the doorway repair has
        // opened the entrance and the connectivity pass has breached the rooms the shell sealed, and cutting
        // them before the lines means the line builder sees the final floor plan (a hole can therefore never
        // be placed in the middle of a usable line - the line builder treats the hole column as occupied).
        markPassages();
        for (HoleJob job : this.holeJobs) {
            this.shaftX = job.x0 + 1;
            this.shaftZ = job.z0 + 1;
            cutFloorHoles(job.name, job.x0, job.z0, job.x1 - job.x0 + 1, job.z1 - job.z0 + 1, job.floors,
                    job.floorHeight, job.holesMax, job.seed, job.floorBlock);
        }
        // ---- the cover LINES of every floor and roof, now that the holes are part of the floor plan.
        for (LinePlan plan : this.allLinePlans) {
            int[] result = placeCoverLines(plan.band, plan.rooms, plan.yLow, plan.yHigh, minCoverLinesFor(plan),
                    plan.minLength, plan.maxLength, plan.kinds, plan.shaftX, plan.shaftZ, plan.seed,
                    plan.boxX0, plan.boxZ0, plan.boxX1, plan.boxZ1);
            if (result[0] < minCoverLinesFor(plan)) {
                throw new IllegalStateException("floor " + plan.band + ": placed only " + result[0]
                        + " of " + minCoverLinesFor(plan) + " requested usable cover line(s) from "
                        + result[4] + " slot(s); blocked=" + result[1] + " merged=" + result[2]
                        + " tooShort=" + result[3] + " rooms=" + plan.rooms.size()
                        + " y=" + plan.yLow + ".." + plan.yHigh);
            }
            System.out.println("  cover lines " + plan.band + ": " + result[0] + "/"
                    + minCoverLinesFor(plan) + " from " + result[4] + " slot(s) (blocked=" + result[1]
                    + " merged=" + result[2] + " short=" + result[3] + ")");
        }

        // ---- the indoor fixtures (deliverables B and C): the spawners that produce the TROOP tier and the
        // loot chests. They run last, on the finished floor plan, because both of their acceptance
        // measurements (the usable-cover-line metric and the flood fill from the entrances) are only
        // meaningful once the doorways are cleared and the rooms the shell sealed have been breached.
        // Pieces-only mode skips them: a district piece must not carry a container or a spawner (the M2 rule
        // tools/selftest_city_district.js asserts).
        if (!piecesOnly) {
            placeIndoorFixtures(layout);
            // A fixture is a full cube: a pane, a fence or a wall beside it must bake its connection arm,
            // and a fixture could in principle have sealed a room - so the same three passes run again on the
            // final grid. They are idempotent when nothing changed, which is what the byte-pins prove.
            bakeConnectorStates();
            repairDoorways();
            ensureConnectivity();
        }
        checkVerticalInvariant();
        // The doorway repair can take a cover piece or a stool out of a passage after the floors were topped
        // up, so the declared per-floor minimums are re-established here, on the final grid - with the
        // usable-line metric protected exactly like the fixtures protect it.
        topUpFloors(layout);
        // ---- last, the detached decoration: a ruin's rubble can land on the slab a lamp's chain hangs from,
        // and a small room can push a table leg past the piece it stands on. Either leaves a cluster of
        // furniture attached to nothing, which is what tools/selftest_strongpoint.js counts as an orphan
        // component. Remove them - but the removal is recorded, and the per-floor counts are corrected, so
        // the floor report cannot claim furniture that is no longer there.
        this.sweptDecor = sweepDetachedDecor();
    }

    /**
     * Re-establishes every floor's declared {@code min_cover_per_floor} / {@code min_furniture_per_floor} on
     * the FINAL grid, with ONE block at a time and only where it cannot rob a usable cover line of its
     * walkable neighbour (the cell itself and its four neighbours must not be cover-line cells). The doorway
     * repair and the fixture pass both run before this, so a floor that lost a piece to a passage gets it
     * back here instead of shipping a report that does not match the NBT.
     */
    private void topUpFloors(Map<String, Object> layout) {
        List<Object> buildings = Json.array(layout, "buildings");
        for (int index = 0; index < this.buildingVariations.size() && index < buildings.size(); index++) {
            BuildingVariation building = this.buildingVariations.get(index);
            Map<String, Object> record = Json.object(buildings.get(index));
            int minCover = Math.max(0, setting(record, "min_cover_per_floor", 5));
            int minFurniture = Math.max(0, setting(record, "min_furniture_per_floor", 6));
            java.util.Random random = new java.util.Random(seedOf(record) * 71L + 4099L);
            for (int floor = 0; floor < building.floors; floor++) {
                String band = building.name + "/" + floor;
                int yLow = 2 + floor * building.floorHeight;
                for (int guard = 0; guard < 96
                        && roles(band).getOrDefault(Role.COVER, 0) < minCover; guard++) {
                    if (!placeSingle(band, building, yLow, "cover_sandbag", Role.COVER, random)) {
                        break;
                    }
                }
                for (int guard = 0; guard < 96
                        && roles(band).getOrDefault(Role.FURNITURE, 0) < minFurniture; guard++) {
                    if (!placeSingle(band, building, yLow, guard % 2 == 0 ? "furn_crate" : "furn_stool",
                            Role.FURNITURE, random)) {
                        break;
                    }
                }
            }
        }
    }

    /** One flat block of the given kind somewhere safe on the floor, or false when no cell is free. */
    private boolean placeSingle(String band, BuildingVariation building, int yLow, String key, Role role,
                                java.util.Random random) {
        for (int attempt = 0; attempt < 64; attempt++) {
            int x = building.x0 + 1 + random.nextInt(Math.max(1, building.x1 - building.x0 - 1));
            int z = building.z0 + 1 + random.nextInt(Math.max(1, building.z1 - building.z0 - 1));
            if (!this.cell(x, yLow, z).equals("air") || !this.cell(x, yLow + 1, z).equals("air")
                    || passableForPlayer(nameAt(x, yLow - 1, z))) {
                continue;
            }
            if (this.metricCoverCell(x, yLow, z) || this.metricCoverCell(x, yLow + 1, z)
                    || this.passageClear != null && this.passageClear[this.index(x, yLow, z)]) {
                continue;
            }
            boolean robsALine = false;
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (this.metricCoverCell(x + step[0], yLow, z + step[1])) {
                    robsALine = true;
                    break;
                }
            }
            if (robsALine || this.holeClearance.contains(this.index(x, yLow, z))) {
                continue;
            }
            if (this.blockEntities.containsKey(this.index(x, yLow, z))) {
                continue;
            }
            this.set(x, yLow, z, key);
            // ... and it must not seal a room: the flood from the entrances has to still reach every room.
            if (!this.everyRecordedRoomReachable()) {
                this.set(x, yLow, z, "air");
                continue;
            }
            count(band, role);
            return true;
        }
        return false;
    }

    /**
     * Removes every cluster of FURNITURE / LIGHT cells that is attached to nothing, using the same rule
     * {@code tools/selftest_strongpoint.js} measures the building components with: air and the low-cover
     * families (which that gate skips as street-cover noise) do not count as a connection, everything else
     * does. A hanging lamp (its chain touches the ceiling) and a table (its legs touch the floor) are kept; a
     * floating lantern and a leg standing on a collapse block are removed.
     *
     * @return how many cells were cleared
     */
    private int sweepDetachedDecor() {
        boolean[] visited = new boolean[this.sizeX * this.sizeY * this.sizeZ];
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int removed = 0;
        for (int y = 1; y < this.sizeY; y++) {
            for (int z = 0; z < this.sizeZ; z++) {
                for (int x = 0; x < this.sizeX; x++) {
                    int start = this.index(x, y, z);
                    if (visited[start] || !isDecorRole(this.roleAt(x, y, z))) {
                        continue;
                    }
                    List<int[]> cluster = new ArrayList<>();
                    boolean attached = false;
                    java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
                    queue.add(new int[]{x, y, z});
                    visited[start] = true;
                    while (!queue.isEmpty()) {
                        int[] cell = queue.poll();
                        cluster.add(cell);
                        for (int[] step : steps) {
                            int nx = cell[0] + step[0];
                            int ny = cell[1] + step[1];
                            int nz = cell[2] + step[2];
                            if (nx < 0 || ny < 1 || nz < 0 || nx >= this.sizeX || ny >= this.sizeY
                                    || nz >= this.sizeZ) {
                                continue;
                            }
                            Role role = this.roleAt(nx, ny, nz);
                            if (role == null) {
                                continue;
                            }
                            if (isDecorRole(role) || role == Role.COVER) {
                                int index = this.index(nx, ny, nz);
                                if (!visited[index] && isDecorRole(role)) {
                                    visited[index] = true;
                                    queue.add(new int[]{nx, ny, nz});
                                }
                                continue;
                            }
                            attached = true;                 // a floor, a wall, a roof, a ladder, a door
                        }
                    }
                    if (!attached) {
                        for (int[] cell : cluster) {
                            this.set(cell[0], cell[1], cell[2], "air");
                            this.uncountAt(cell[0], cell[1], cell[2], this.roleAt(cell[0], cell[1], cell[2]));
                            removed++;
                        }
                    }
                }
            }
        }
        return removed;
    }

    /** FURNITURE or LIGHT: the two roles a detached cluster may consist of. */
    private static boolean isDecorRole(Role role) {
        return role == Role.FURNITURE || role == Role.LIGHT;
    }

    /** The role of the block at a cell, or null for air. */
    private Role roleAt(int x, int y, int z) {
        String state = this.cell(x, y, z);
        if (state.equals("air")) {
            return null;
        }
        Map<String, Object> entry = this.statePalette.get(state);
        Object role = entry == null ? null : entry.get("_role");
        return role instanceof Role value ? value : null;
    }

    /** Takes one cell back out of its floor's role count, for a clear {@link #sweepDetachedDecor} made. */
    private void uncountAt(int x, int y, int z, Role role) {
        if (role == null) {
            return;
        }
        for (BuildingVariation building : this.buildingVariations) {
            if (x < building.x0 || x > building.x1 || z < building.z0 || z > building.z1) {
                continue;
            }
            int floor = (y - 1) / building.floorHeight;
            if (floor >= 0 && floor < building.floors) {
                String band = building.name + "/" + floor;
                this.floorCounts.computeIfPresent(band, (key, counts) -> {
                    counts.merge(role, -1, Integer::sum);
                    return counts;
                });
            }
            return;
        }
    }

    /** How many usable cover lines one floor plan asks for. */
    private int minCoverLinesFor(LinePlan plan) {
        return plan.wanted;
    }

    /** One building's floor-hole job: everything {@link #cutFloorHoles} needs, recorded while it is built. */
    private static final class HoleJob {
        final String name;
        final int x0;
        final int z0;
        final int x1;
        final int z1;
        final int floors;
        final int floorHeight;
        final int holesMax;
        final int seed;
        final String floorBlock;

        HoleJob(String name, int x0, int z0, int x1, int z1, int floors, int floorHeight, int holesMax,
                int seed, String floorBlock) {
            this.name = name;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.floors = floors;
            this.floorHeight = floorHeight;
            this.holesMax = holesMax;
            this.seed = seed;
            this.floorBlock = floorBlock;
        }
    }

    private final List<HoleJob> holeJobs = new ArrayList<>();
    private final List<LinePlan> allLinePlans = new ArrayList<>();
    /** The cover-line band of the building being built right now, so buildInterior can plan its lines. */
    private int coverLineMin = 3;
    private int coverLineMax = 6;
    private List<String> coverLineBlocks = DEFAULT_COVER_LINE_BLOCKS;

    // ================================================================== indoor fixtures (B + C)

    /**
     * The TROOP tier of the intelligence mapping ({@code gun/AiProfile.java}): {@code Tier.TROOP} is exactly
     * {@code UsecVillagerEntity} and {@code BearPillagerEntity}. These are the two ids the spawner's
     * {@code SpawnData} / {@code SpawnPotentials} name, so a spawner can only ever produce a troop - never
     * an elite, a sniper or a plain thug.
     */
    static final List<String> TROOP_SPAWN_IDS =
            List.of("tarkovscav:usec_villager", "tarkovscav:bear_pillager");

    /**
     * The vanilla loot table every loot chest points at, by id and never copied: other mods' injections
     * into the same table apply automatically, and the TaCZ extras come from the Java global loot modifier.
     */
    static final String CHEST_LOOT_TABLE = "minecraft:chests/abandoned_mineshaft";

    /**
     * The spawner's own numbers, in ticks, written explicitly so the shipped NBT says what it does:
     * two units per cycle, a 4-block spawn radius, a 3-second first delay, then 15-45 s between cycles, and
     * it only ticks while a player is within 16 blocks (the vanilla default) - never more than 6 troop
     * bodies alive around one spawner, so a building cannot become a farm.
     */
    static final int SPAWNER_COUNT = 2;
    static final int SPAWNER_RANGE = 4;
    static final int SPAWNER_INITIAL_DELAY = 60;
    static final int SPAWNER_MIN_DELAY = 300;
    static final int SPAWNER_MAX_DELAY = 900;
    static final int SPAWNER_REQUIRED_PLAYER_RANGE = 16;
    static final int SPAWNER_MAX_NEARBY = 6;
    /**
     * How many candidate cells one fixture may try. Each try costs two flood fills (the room check and the
     * vertical check), and the candidate list is sorted best-first, so ten is plenty - and it keeps the
     * 18-building strongpoint's generation time in seconds rather than minutes.
     */
    static final int MAX_FIXTURE_CANDIDATES = 10;

    /**
     * One spawner or chest per building (deliverable B/C), on a floor against an interior wall, never in a
     * doorway, never in the ladder shaft, never on a cover-line cell and never where it would rob a usable
     * cover line of its walkable neighbour.
     *
     * <p>Every candidate is verified by PLACING it and re-measuring, not by reasoning about it: the usable
     * low-cover-line count of the affected floor band may not drop, and the flood fill from the building's
     * entrances must still reach every recorded room. A candidate that fails either is reverted and the next
     * one is tried. That is the same accept/revert discipline {@code placeCoverLines} uses.</p>
     */
    private void placeIndoorFixtures(Map<String, Object> layout) {
        int spawnerBudget = Json.intOf(layout, "spawner_budget", Integer.MAX_VALUE);
        List<int[]> spawnerTargets = new ArrayList<>();
        List<int[]> chestTargets = new ArrayList<>();
        int index = 0;
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            int spanners = Math.max(0, setting(building, "spawners_per_building", 0));
            if (spanners > 0) {
                java.util.Random random = new java.util.Random(seedOf(building) * 61L + 1013L);
                int count = 1 + random.nextInt(spanners);
                for (int i = 0; i < count && spawnerTargets.size() < spawnerBudget; i++) {
                    spawnerTargets.add(new int[]{index, i});
                }
            }
            int chests = Math.max(0, setting(building, "chests_per_building", 0));
            if (chests > 0) {
                java.util.Random random = new java.util.Random(seedOf(building) * 67L + 2017L);
                int count = 1 + random.nextInt(chests);
                for (int i = 0; i < count; i++) {
                    chestTargets.add(new int[]{index, i});
                }
            }
            index++;
        }

        // Spawners first: they are the rare, load-bearing fixture, so they get the first pick of the cells.
        for (int[] target : spawnerTargets) {
            placeFixture(layout, target[0], target[1], true);
        }
        for (int[] target : chestTargets) {
            placeFixture(layout, target[0], target[1], false);
        }
    }

    /** The seed a building declares, or the same name hash {@code buildBuilding} falls back to. */
    private static int seedOf(Map<String, Object> building) {
        return Json.intOf(building, "seed",
                Math.abs(Json.string(building, "name", "building").hashCode()) % 100_000);
    }

    /**
     * One fixture: try the candidate cells of the target floor, best (corner) first, and keep the first one
     * that survives the two acceptance measurements.
     */
    private void placeFixture(Map<String, Object> layout, int buildingIndex, int slot, boolean spawner) {
        List<Object> buildings = Json.array(layout, "buildings");
        if (buildingIndex >= buildings.size()) {
            return;
        }
        Map<String, Object> building = Json.object(buildings.get(buildingIndex));
        String buildingName = Json.string(building, "name", "building");
        int x0 = Json.intOf(building, "x", 0);
        int z0 = Json.intOf(building, "z", 0);
        int w = Json.intOf(building, "w", 8);
        int d = Json.intOf(building, "d", 8);
        int floors = Json.intOf(building, "floors", 3);
        int floorHeight = Json.intOf(building, "floor_height", 4);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int floor = Math.floorMod(slot, floors);
        int yLow = 2 + floor * floorHeight;

        List<int[]> candidates = new ArrayList<>();
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                if (this.validFixtureCell(x, yLow, z, spawner)) {
                    candidates.add(new int[]{x, z});
                }
            }
        }
        // Corners first: two perpendicular interior-wall neighbours, then one, then the rest. A stable sort
        // keeps the candidate list deterministic for a given layout.
        candidates.sort((a, b) -> Integer.compare(cornerScore(b[0], yLow, b[1]),
                cornerScore(a[0], yLow, a[1])));

        String key = spawner ? "spawner" : "loot_chest";
        boolean hasVariation = Math.max(0, setting(building, "interior_variation", 0)) > 0;
        this.shaftX = x0 + 1;
        this.shaftZ = z0 + 1;
        int tried = 0;
        for (int[] candidate : candidates) {
            if (tried++ >= MAX_FIXTURE_CANDIDATES) {
                break;                          // bounded work: the best 10 cells are plenty
            }
            int x = candidate[0];
            int z = candidate[1];
            int[] linesBefore = this.linesAround(yLow, floorHeight, x0, z0, w, d);
            this.set(x, yLow, z, key);
            this.blockEntities.put(this.index(x, yLow, z),
                    spawner ? spawnerTag() : chestTag());
            // Never a DROP in the usable low-cover-line metric: a chest is itself low cover, so it may add a
            // line (a gain is fine), but a fixture that robs a line of its walkable neighbour is reverted.
            int[] linesAfter = this.linesAround(yLow, floorHeight, x0, z0, w, d);
            boolean linesOk = true;
            for (int i = 0; i < linesAfter.length; i++) {
                if (linesAfter[i] < linesBefore[i]) {
                    linesOk = false;
                    break;
                }
            }
            boolean roomsOk = this.everyRecordedRoomReachable();
            // ... and the vertical invariant has to survive too: a fixture must not plug the way to a floor
            // hole's landing on the floor below, which the room check above cannot see (the ladder shaft is
            // still there, so every room stays reachable while a floor stops being reachable through a hole).
            boolean verticalOk = !hasVariation || floorsReachableWithoutShaft(x0, x1, z0, z1, floors,
                    floorHeight) == floors;
            if (linesOk && roomsOk && verticalOk) {
                if (spawner) {
                    this.spawnerCells.add(new int[]{x, yLow, z});
                    count(buildingName + "/" + floor, Role.SPAWNER);
                } else {
                    this.chestCells.add(new int[]{x, yLow, z});
                    count(buildingName + "/" + floor, Role.FURNITURE);
                }
                for (BuildingVariation variation : this.buildingVariations) {
                    if (variation.name.equals(buildingName)) {
                        if (spawner) {
                            variation.spawners++;
                        } else {
                            variation.chests++;
                        }
                        break;
                    }
                }
                return;
            }
            this.set(x, yLow, z, "air");
            this.blockEntities.remove(this.index(x, yLow, z));
        }
        System.out.println("  WARNING " + buildingName + " floor " + floor + ": no cell accepted a "
                + (spawner ? "spawner" : "chest") + " slot " + slot + " (candidates=" + candidates.size()
                + " roomsOk=" + this.everyRecordedRoomReachable() + ")");
    }

    /** The usable cover lines of the floor band around {@code yLow} (and the one below it). */
    private int[] linesAround(int yLow, int floorHeight, int x0, int z0, int w, int d) {
        int[] above = countUsableLines(yLow, yLow + floorHeight - 1, x0 - 1, z0 - 1, x0 + w, z0 + d);
        int[] below = yLow - floorHeight >= 1
                ? countUsableLines(yLow - floorHeight, yLow - 1, x0 - 1, z0 - 1, x0 + w, z0 + d)
                : new int[]{0, 0};
        return new int[]{above[0], above[1], below[0], below[1]};
    }

    /** True when the flood fill from this building's entrances still reaches every recorded room. */
    private boolean everyRecordedRoomReachable() {
        if (this.entranceDoors.isEmpty() || this.roomRecords.isEmpty()) {
            return true;
        }
        boolean[] reachable = floodFrom(this.entranceDoors);
        for (int[] room : this.roomRecords) {
            boolean any = false;
            for (int x = room[0]; x <= room[2] && !any; x++) {
                for (int z = room[1]; z <= room[3] && !any; z++) {
                    if (reachable[this.index(x, room[4], z)]) {
                        any = true;
                    }
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** How many perpendicular interior walls a cell touches: 2 is a corner, 1 is a wall face. */
    private int cornerScore(int x, int y, int z) {
        int score = 0;
        if (isInteriorWall(x - 1, y, z)) {
            score++;
        }
        if (isInteriorWall(x + 1, y, z)) {
            score++;
        }
        if (isInteriorWall(x, y, z - 1)) {
            score++;
        }
        if (isInteriorWall(x, y, z + 1)) {
            score++;
        }
        return score;
    }

    /** A solid wall of the building's own shell or one of its partitions, at this height. */
    private boolean isInteriorWall(int x, int y, int z) {
        Map<String, Object> entry = this.statePalette.get(this.cell(x, y, z));
        if (entry == null) {
            return false;
        }
        Object role = entry.get("_role");
        return role == Role.WALL || role == Role.FRAME;
    }

    /**
     * Every reason a cell may not hold a fixture: air with two blocks of clearance over a solid floor,
     * against an interior wall, clear of every doorway and opening, clear of the ladder shaft, not on a
     * cover-line cell and not next to one (so no line can lose the walkable neighbour it was counted by).
     */
    private boolean validFixtureCell(int x, int y, int z, boolean spawner) {
        if (!this.cell(x, y, z).equals("air") || !this.cell(x, y + 1, z).equals("air")
                || !this.cell(x, y + 2, z).equals("air")) {
            return false;
        }
        if (passableForPlayer(nameAt(x, y - 1, z))) {
            return false;                       // nothing to stand on
        }
        if (cornerScore(x, y, z) < 1) {
            return false;                       // not against an interior wall
        }
        if (isShaftCell(x, y, z, this.shaftX, this.shaftZ)
                || isShaftCell(x, y + 1, z, this.shaftX, this.shaftZ)) {
            return false;
        }
        if (this.holeClearance.contains(this.index(x, y, z))
                || this.holeClearance.contains(this.index(x, y + 1, z))) {
            return false;                       // a floor hole needs this column kept clear
        }
        for (int dy = 0; dy <= 1; dy++) {
            if (nearPassage(x, y + dy, z)) {
                return false;                   // in, above or right beside a doorway / opening
            }
        }
        if (this.metricCoverCell(x, y, z) || this.metricCoverCell(x, y + 1, z)) {
            return false;
        }
        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] step : neighbours) {
            if (this.metricCoverCell(x + step[0], y, z + step[1])) {
                return false;                   // a line cell would lose this walkable neighbour
            }
            if (this.blockEntities.containsKey(this.index(x + step[0], y, z + step[1]))) {
                return false;                   // never two fixtures side by side
            }
        }
        return true;
    }

    /**
     * The spawner's NBT: {@code SpawnData} (the single-entity form) plus {@code SpawnPotentials} (the
     * weighted list), both naming ONLY the two TROOP ids. Verified against the real 1.20.1 codec
     * ({@code SpawnData.CODEC} is a record with the field {@code entity} of type {@code CompoundTag}, and
     * {@code SimpleWeightedRandomList.wrappedCodecAllowingEmpty} wraps it as {@code {data, weight}}).
     */
    private static Map<String, Object> spawnerTag() {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("id", "minecraft:spawner");
        tag.put("SpawnData", spawnData(TROOP_SPAWN_IDS.get(0)));
        List<Object> potentials = new ArrayList<>();
        for (String id : TROOP_SPAWN_IDS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("weight", 1);
            entry.put("data", spawnData(id));
            potentials.add(entry);
        }
        tag.put("SpawnPotentials", potentials);
        tag.put("SpawnCount", (short) SPAWNER_COUNT);
        tag.put("SpawnRange", (short) SPAWNER_RANGE);
        tag.put("Delay", (short) SPAWNER_INITIAL_DELAY);
        tag.put("MinSpawnDelay", (short) SPAWNER_MIN_DELAY);
        tag.put("MaxSpawnDelay", (short) SPAWNER_MAX_DELAY);
        tag.put("RequiredPlayerRange", (short) SPAWNER_REQUIRED_PLAYER_RANGE);
        tag.put("MaxNearbyEntities", (short) SPAWNER_MAX_NEARBY);
        return tag;
    }

    /** {@code {entity: {id: ...}}} - the 1.19+ SpawnData shape, not the pre-1.19 flat {@code {id}}. */
    private static Map<String, Object> spawnData(String entityId) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", entityId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", entity);
        return data;
    }

    /** The chest's block entity: the vanilla mineshaft table by ID, so other mods' injections apply. */
    private static Map<String, Object> chestTag() {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("id", "minecraft:chest");
        tag.put("LootTable", CHEST_LOOT_TABLE);
        return tag;
    }


    // ------------------------------------------------------------------ post-pass 1: connection states

    /** The block name of a cell, or null for air and for anything outside the volume. */
    private String nameAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
            return null;
        }
        String key = this.cells[this.index(x, y, z)];
        if (key.equals("air")) {
            return null;
        }
        Map<String, Object> entry = this.statePalette.get(key);
        return entry == null ? null : (String) entry.get("Name");
    }

    /**
     * The blocks this generator places that a player can walk THROUGH: no collision, plus doors (closed,
     * but openable). Everything else - a pane, a wall, a fence, a slab, a stair, a chest, a barrel - blocks
     * the space. This is the predicate the doorway and connectivity passes use; {@link #supportsFullFace}
     * is the different question vanilla's connector attachment asks.
     */
    private static final Set<String> NO_COLLISION = Set.of(
            "minecraft:ladder", "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch",
            "minecraft:soul_wall_torch", "minecraft:end_rod", "minecraft:chain", "minecraft:oak_sign",
            "minecraft:red_carpet", "minecraft:gray_carpet", "minecraft:white_carpet");

    static boolean passableForPlayer(String name) {
        return name == null || NO_COLLISION.contains(name) || isDoorName(name)
                || name.endsWith("_carpet") || name.endsWith("_sign") || name.endsWith("_banner");
    }

    /**
     * A cell a player can occupy: air or a no-collision block, or a door (which can be opened). A chest
     * counts as solid - its collision shape is a full cube - which is the fact the doorway repair and the
     * connectivity flood both depend on.
     */
    private boolean openAt(int x, int y, int z) {
        return passableForPlayer(nameAt(x, y, z));
    }

    static boolean isPaneFamily(String name) {
        return name != null && (name.endsWith("_pane") || name.equals("minecraft:iron_bars"));
    }

    static boolean isFenceFamily(String name) {
        return name != null && (name.endsWith("_fence") || name.endsWith("_fence_gate"));
    }

    static boolean isWallName(String name) {
        return name != null && name.endsWith("_wall");
    }

    static boolean isConnectorName(String name) {
        return isPaneFamily(name) || isFenceFamily(name) || isWallName(name);
    }

    /**
     * Vanilla's attach test, over the NBT grid: a pane or a fence attaches to its own family or to a full
     * solid face, a wall likewise (with the low/tall distinction handled by the caller).
     */
    private boolean connectsTo(String neighbour, String self) {
        if (neighbour == null) {
            return false;
        }
        if (isPaneFamily(self)) {
            return isPaneFamily(neighbour) || supportsFullFace(neighbour);
        }
        if (isFenceFamily(self)) {
            return isFenceFamily(neighbour) || supportsFullFace(neighbour);
        }
        if (isWallName(self)) {
            return isWallName(neighbour) || supportsFullFace(neighbour);
        }
        return false;
    }

    /** Vanilla WallBlock: a neighbour wall -> tall, a full solid face -> low, anything else -> none. */
    private String wallSideAt(int x, int y, int z) {
        String neighbour = nameAt(x, y, z);
        if (isWallName(neighbour)) {
            return "tall";
        }
        if (supportsFullFace(neighbour)) {
            return "low";
        }
        return "none";
    }

    /**
     * Vanilla's post rule for the {@code up} property: a post is raised unless the wall is a straight run on
     * one axis (two opposite sides connected and the other two not), and a wall whose neighbour above has a
     * post keeps one too. Walls are baked bottom-up so the block above is already final.
     */
    private String raisePost(String north, String east, String south, String west, int x, int y, int z) {
        String above = nameAt(x, y + 1, z);
        if (isWallName(above)) {
            Map<String, Object> entry = this.statePalette.get(this.cells[this.index(x, y + 1, z)]);
            Object up = entry == null ? null : propertiesOf(entry).get("up");
            if (up == null || Boolean.parseBoolean(String.valueOf(up))) {
                return "true";
            }
        }
        boolean alongZ = !north.equals("none") && !south.equals("none");
        boolean alongX = !east.equals("none") && !west.equals("none");
        return String.valueOf(!(alongZ ^ alongX));
    }

    private static Map<String, String> propertiesOf(Map<String, Object> entry) {
        Map<String, String> out = new LinkedHashMap<>();
        if (entry.get("Properties") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> property : declared.entrySet()) {
                out.put(String.valueOf(property.getKey()), String.valueOf(property.getValue()));
            }
        }
        return out;
    }

    /**
     * Recomputes every pane / iron bars / fence / fence gate / wall state from the FINAL neighbour grid.
     *
     * <p>Necessary because a structure NBT stores explicit block states and Minecraft does not re-run
     * {@code updateShape} when a structure places them: a {@code glass_pane} written with its default
     * properties renders as a lone post in the middle of a window, and a {@code stone_brick_wall} written
     * without north/south/east/west renders as a row of disconnected posts. That is the defect the user
     * photographed ("这个墙 以及玻璃没有互相连在一起"), and this is where it is fixed.</p>
     *
     * <p>Out-of-structure neighbours are treated as not connectable, exactly like vanilla structures.</p>
     */
    private void bakeConnectorStates() {
        int baked = 0;
        for (int y = 0; y < this.sizeY; y++) {
            for (int z = 0; z < this.sizeZ; z++) {
                for (int x = 0; x < this.sizeX; x++) {
                    String state = this.cells[this.index(x, y, z)];
                    if (state.equals("air")) {
                        continue;
                    }
                    Map<String, Object> entry = this.statePalette.get(state);
                    if (entry == null) {
                        continue;
                    }
                    String name = (String) entry.get("Name");
                    if (!isConnectorName(name)) {
                        continue;
                    }
                    String base = this.baseKeyOfState.getOrDefault(state,
                            state.contains("|") ? state.substring(0, state.indexOf('|')) : state);
                    String north = wallSideAt(x, y, z - 1);
                    String east = wallSideAt(x + 1, y, z);
                    String south = wallSideAt(x, y, z + 1);
                    String west = wallSideAt(x - 1, y, z);
                    StringBuilder key = new StringBuilder(base)
                            .append("|north=").append(north)
                            .append(",east=").append(east)
                            .append(",south=").append(south)
                            .append(",west=").append(west);
                    if (isWallName(name)) {
                        key.append(",up=").append(raisePost(north, east, south, west, x, y, z));
                    } else {
                        // panes and fences carry plain booleans, and no up property
                        key.setLength(0);
                        key.append(base)
                                .append("|north=").append(connectsTo(nameAt(x, y, z - 1), name))
                                .append(",east=").append(connectsTo(nameAt(x + 1, y, z), name))
                                .append(",south=").append(connectsTo(nameAt(x, y, z + 1), name))
                                .append(",west=").append(connectsTo(nameAt(x - 1, y, z), name));
                    }
                    this.cells[this.index(x, y, z)] = stateKey(key.toString());
                    baked++;
                }
            }
        }
        this.bakedConnectors = baked;
    }

    // ------------------------------------------------------------------ post-pass 2: doors + connectivity

    /**
     * Every doorway survives: both halves are the door again, and the two cells on each side along the
     * door's passage axis have two blocks of clearance. A partition wall or a frame that ended up in a
     * doorway is removed - that is the user's "建筑内的墙偶尔会挡住门".
     *
     * <p>The interior passages punched through the partition walls are protected the same way. They have no
     * door block, so before this they were invisible to the post-pass and the cover-line pass - which runs
     * last - could seal a room by dropping one cover piece into its only opening.</p>
     */
    private void repairDoorways() {
        int repaired = 0;
        for (int i = 0; i < this.doorCells.size(); i++) {
            int[] door = this.doorCells.get(i);
            String facing = this.doorFacings.get(i);
            String hinge = this.doorHinges.get(i);
            int x = door[0];
            int y = door[1];
            int z = door[2];
            this.set(x, y, z, "door|facing=" + facing + ",half=lower,hinge=" + hinge);
            this.set(x, y + 1, z, "door|facing=" + facing + ",half=upper,hinge=" + hinge);
            boolean alongZ = facing.equals("north") || facing.equals("south");
            repaired += clearPassage(x, y, z, alongZ);
        }
        for (int[] opening : this.openings) {
            int x = opening[0];
            int y = opening[1];
            int z = opening[2];
            boolean alongX = opening[3] == 1;
            // make sure the opening itself is still open, then clear its two sides
            for (int dy = 0; dy <= 1; dy++) {
                if (!this.cell(x, y + dy, z).equals("air")) {
                    this.uncountAt(x, y + dy, z, this.roleAt(x, y + dy, z));
                    this.set(x, y + dy, z, "air");
                    repaired++;
                }
            }
            repaired += clearPassage(x, y, z, !alongX);
        }
        this.repairedDoorwayCells = repaired;
    }

    /**
     * Clears the two cells on each side of a passage along its axis, at the passage's own level and above.
     *
     * <p>AIR, not "passable": a carpet or a torch has no collision, so the player-facing test lets it through,
     * but {@code tools/selftest_blockstates.js} requires a doorway's passage cells to be AIR - a carpet in a
     * doorway is a blocked doorway as far as that gate is concerned, and it was right the three times this
     * check failed. Anything removed here is taken back out of its floor's counts.</p>
     *
     * @param alongZ true when the passage runs along Z (a door facing north or south, or an opening in a
     *               wall that spans X)
     */
    private int clearPassage(int x, int y, int z, boolean alongZ) {
        int repaired = 0;
        for (int side = -1; side <= 1; side += 2) {
            int sideX = alongZ ? 0 : side;
            int sideZ = alongZ ? side : 0;
            for (int dy = 0; dy <= 1; dy++) {
                if (!this.cell(x + sideX, y + dy, z + sideZ).equals("air")) {
                    this.uncountAt(x + sideX, y + dy, z + sideZ,
                            this.roleAt(x + sideX, y + dy, z + sideZ));
                    this.set(x + sideX, y + dy, z + sideZ, "air");
                    repaired++;
                }
            }
        }
        return repaired;
    }

    /** Everything reachable on foot from a set of seed cells, over the 6-neighbour grid. */
    private boolean[] floodFrom(List<int[]> seeds) {
        return floodFrom(seeds, false);
    }

    /**
     * The same flood, optionally treating the ladder shaft as a WALL. The shaft-free variant is how the
     * interior-variation pass proves that the floor holes are real vertical connections: a floor it can
     * still reach is a floor connected by a hole, not by the ladder.
     */
    private boolean[] floodFrom(List<int[]> seeds, boolean blockLadderShaft) {
        boolean[] seen = new boolean[this.sizeX * this.sizeY * this.sizeZ];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        for (int[] seed : seeds) {
            for (int dy = 0; dy <= 1; dy++) {
                int x = seed[0];
                int y = seed[1] + dy;
                int z = seed[2];
                if (x < 0 || y < 1 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
                    continue;
                }
                if ((blockLadderShaft && isShaftCell(x, y, z, this.shaftX, this.shaftZ))
                        || !openAt(x, y, z) || seen[this.index(x, y, z)]) {
                    continue;
                }
                seen[this.index(x, y, z)] = true;
                queue.add(new int[]{x, y, z});
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] step : steps) {
                int nx = cell[0] + step[0];
                int ny = cell[1] + step[1];
                int nz = cell[2] + step[2];
                if (nx < 0 || ny < 1 || nz < 0 || nx >= this.sizeX || ny >= this.sizeY || nz >= this.sizeZ) {
                    continue;
                }
                if (seen[this.index(nx, ny, nz)] || !openAt(nx, ny, nz)) {
                    continue;
                }
                if (blockLadderShaft && isShaftCell(nx, ny, nz, this.shaftX, this.shaftZ)) {
                    continue;
                }
                seen[this.index(nx, ny, nz)] = true;
                queue.add(new int[]{nx, ny, nz});
            }
        }
        return seen;
    }

    /**
     * The connectivity invariant: from the buildings' entrances, every recorded room must be reachable. A
     * sealed room is breached by opening one 2-high cell in its wall next to something already reachable,
     * rather than being left sealed - and the count is reported, so the self test can assert it is 0.
     */
    private void ensureConnectivity() {
        if (this.entranceDoors.isEmpty() || this.roomRecords.isEmpty()) {
            return;
        }
        this.breachedRooms = 0;
        for (int attempt = 0; attempt < 128; attempt++) {
            boolean[] reachable = floodFrom(this.entranceDoors);
            List<int[]> sealed = new ArrayList<>();
            for (int[] room : this.roomRecords) {
                boolean any = false;
                for (int x = room[0]; x <= room[2] && !any; x++) {
                    for (int z = room[1]; z <= room[3] && !any; z++) {
                        if (reachable[this.index(x, room[4], z)]) {
                            any = true;
                        }
                    }
                }
                if (!any) {
                    sealed.add(room);
                }
            }
            this.sealedRooms = sealed.size();
            if (sealed.isEmpty()) {
                return;
            }
            if (!breach(sealed.get(0), reachable)) {
                return;
            }
            this.breachedRooms++;
        }
    }

    /**
     * True when a cell is within one block of a door or an interior opening, at either of their two head
     * heights. The breach pass uses it to keep a doorway's frame and flanking wall intact.
     */
    private boolean nearPassage(int x, int y, int z) {
        for (int[] door : this.doorCells) {
            if (Math.abs(door[0] - x) <= 1 && Math.abs(door[2] - z) <= 1
                    && Math.abs(door[1] - y) <= 1) {
                return true;
            }
        }
        for (int[] opening : this.openings) {
            if (Math.abs(opening[0] - x) <= 1 && Math.abs(opening[2] - z) <= 1
                    && Math.abs(opening[1] - y) <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens one 2-high breach in a sealed room's wall, next to a cell that is already reachable. Any wall
     * cell on the room's boundary ring is fair game - preferring one with the most reachable neighbours -
     * because "leave the room sealed" is the one outcome the invariant forbids.
     */
    private boolean breach(int[] room, boolean[] reachable) {
        int y = room[4];
        int bestX = Integer.MIN_VALUE;
        int bestZ = Integer.MIN_VALUE;
        int bestScore = -1;
        for (int x = room[0] - 1; x <= room[2] + 1; x++) {
            for (int z = room[1] - 1; z <= room[3] + 1; z++) {
                boolean onEdge = x == room[0] - 1 || x == room[2] + 1 || z == room[1] - 1 || z == room[3] + 1;
                if (!onEdge) {
                    continue;
                }
                // Never breach next to a door or an opening: removing a door's flanking wall is what turns
                // a door into a floating door (StructureNbtTest asserts a door has walls on both sides).
                if (nearPassage(x, y, z)) {
                    continue;
                }
                // a wall cell: something is there and a player cannot pass it
                if (openAt(x, y, z) && openAt(x, y + 1, z)) {
                    continue;
                }
                int score = 0;
                int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] step : neighbours) {
                    int nx = x + step[0];
                    int nz = z + step[1];
                    if (nx < 0 || nz < 0 || nx >= this.sizeX || nz >= this.sizeZ) {
                        continue;
                    }
                    if (reachable[this.index(nx, y, nz)]) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        if (bestScore < 0) {
            return false;
        }
        this.set(bestX, y, bestZ, "air");
        this.set(bestX, y + 1, bestZ, "air");
        return true;
    }

    /** Where a {@code "level": "roof"} street cover entry goes (1 when the layout has no buildings). */
    private int streetRoofY = 1;

    /** The ladder shaft of the building being built right now (the interior-variation pass needs it). */
    private int shaftX;
    private int shaftZ;

    private void buildStreet(Map<String, Object> street) {
        int bandFrom = Json.intOf(street, "band_from", 19);
        int bandTo = Json.intOf(street, "band_to", 28);
        int sidewalk = Json.intOf(street, "sidewalk", 3);
        String roadBlock = Json.string(street, "road_block", "road");
        String lineBlock = Json.string(street, "line_block", "line");
        String sidewalkBlock = Json.string(street, "sidewalk_block", "walk");
        String groundBlock = Json.string(street, "ground_block", "ground");

        // One band by default (band_from..band_to). A grid layout may instead name several bands per
        // axis; without the new keys the single band below reproduces the old behaviour exactly.
        List<int[]> bandsX = bandsOf(street, "bands_x", bandFrom, bandTo);
        List<int[]> bandsZ = bandsOf(street, "bands_z", bandFrom, bandTo);

        for (int x = 0; x < this.sizeX; x++) {
            for (int z = 0; z < this.sizeZ; z++) {
                boolean inBandX = inAnyBand(x, bandsX);
                boolean inBandZ = inAnyBand(z, bandsZ);
                if (inBandX || inBandZ) {
                    boolean centreLine = (inBandX && nearBandCentre(x, bandsX))
                            || (inBandZ && nearBandCentre(z, bandsZ));
                    this.set(x, 0, z, centreLine && !(inBandX && inBandZ) ? lineBlock : roadBlock);
                } else if (isNearAnyBand(x, bandsX, sidewalk) || isNearAnyBand(z, bandsZ, sidewalk)) {
                    this.set(x, 0, z, sidewalkBlock);
                } else {
                    this.set(x, 0, z, groundBlock);
                }
            }
        }
    }

    /** The street bands of one axis: the explicit {@code bands_*} list, or the single legacy band. */
    private static List<int[]> bandsOf(Map<String, Object> street, String key, int from, int to) {
        List<Object> declared = Json.array(street, key);
        if (declared.isEmpty()) {
            return List.of(new int[]{from, to});
        }
        List<int[]> bands = new ArrayList<>();
        for (Object raw : declared) {
            if (raw instanceof List<?> list && list.size() >= 2) {
                bands.add(new int[]{((Number) list.get(0)).intValue(),
                        ((Number) list.get(1)).intValue()});
            }
        }
        return bands.isEmpty() ? List.of(new int[]{from, to}) : bands;
    }

    private static boolean inAnyBand(int value, List<int[]> bands) {
        for (int[] band : bands) {
            if (value >= band[0] && value <= band[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearBandCentre(int value, List<int[]> bands) {
        for (int[] band : bands) {
            if (value >= band[0] && value <= band[1] && value == (band[0] + band[1]) / 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearAnyBand(int value, List<int[]> bands, int distance) {
        for (int[] band : bands) {
            if (isNearBand(value, band[0], band[1], distance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearBand(int value, int from, int to, int distance) {
        return (value >= from - distance && value < from) || (value > to && value <= to + distance);
    }

    /**
     * One building: perimeter walls with window rows, a floor slab per storey, a ladder shaft with a
     * hole in every slab, a <b>framed double door with steps</b> on the requested side, interior rooms
     * with doorways, furniture and cover on every floor, a parapet, and - when {@code ruined} is set - a
     * collapsed quadrant through the top two floors.
     */
    private void buildBuilding(Map<String, Object> building) {
        String buildingName = Json.string(building, "name", "building");
        int x0 = Json.intOf(building, "x", 0);
        int z0 = Json.intOf(building, "z", 0);
        int w = Json.intOf(building, "w", 8);
        int d = Json.intOf(building, "d", 8);
        int floors = Json.intOf(building, "floors", 3);
        int floorHeight = Json.intOf(building, "floor_height", 4);
        String wall = Json.string(building, "wall", "wall_brick");
        String accent = Json.string(building, "accent", "accent");
        String floorBlock = Json.string(building, "floor_block", "floor");
        String roofBlock = Json.string(building, "roof_block", "roof");
        String windowBlock = Json.string(building, "window_block", "window");
        String doorSide = Json.string(building, "door_side", "south");
        boolean roofAccess = Json.bool(building, "roof_access", true);
        boolean ruined = Json.bool(building, "ruined", false);
        int roomsX = Json.intOf(building, "rooms_x", 2);
        int roomsZ = Json.intOf(building, "rooms_z", 2);
        int minCover = Json.intOf(building, "min_cover_per_floor", 5);
        int minFurniture = Json.intOf(building, "min_furniture_per_floor", 6);
        int minDoors = Json.intOf(building, "min_doors", 2);
        // ---- interior variation (deliverable G, opt-in): per-floor random room subdivision, extra 1x2
        // openings between adjacent rooms and 1..N floor holes per upper floor. A layout that does not name
        // "interior_variation" gets the historical fixed rooms_x x rooms_z grid, unchanged. The layout may
        // carry the keys once for every building; a building entry overrides them.
        int interiorVariation = Math.max(0, setting(building, "interior_variation", 0));
        int extraOpeningsMax = Math.max(0, setting(building, "extra_openings_per_wall", 0));
        int floorHolesMax = Math.max(0, setting(building, "floor_holes_per_floor", 0));
        // ---- cover LINES (opt-in). A layout that does not name these keys gets minCoverLines = 0 and is
        // generated exactly as before, byte for byte: the four shipped presets must not move.
        int minCoverLines = Json.intOf(building, "min_cover_lines_per_floor", 0);
        int coverLineMin = Math.max(3, Json.intOf(building, "cover_line_min_length", 3));
        int coverLineMax = Math.max(coverLineMin, Json.intOf(building, "cover_line_max_length", 6));
        List<String> coverLineBlocks = Json.strings(building, "cover_line_blocks");
        if (coverLineBlocks.isEmpty()) {
            coverLineBlocks = DEFAULT_COVER_LINE_BLOCKS;
        }
        this.coverLineMin = coverLineMin;
        this.coverLineMax = coverLineMax;
        this.coverLineBlocks = coverLineBlocks;
        int seed = Json.intOf(building, "seed", Math.abs(buildingName.hashCode()) % 100_000);

        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int topY = 1 + floors * floorHeight;
        int shaftX = x0 + 1;
        int shaftZ = z0 + 1;
        this.shaftX = shaftX;
        this.shaftZ = shaftZ;
        // Doors placed for THIS building: the entrance makes up whatever the interior did not place.
        this.interiorDoors = 0;
        this.linePlans.clear();
        this.buildingVariation.clear();

        for (int floor = 0; floor < floors; floor++) {
            int floorY = 1 + floor * floorHeight;
            String band = buildingName + "/" + floor;

            // the slab, with the ladder hole
            this.fill(x0, floorY, z0, x1, floorY, z1, floorBlock);
            this.set(shaftX, floorY, shaftZ, "air");
            count(band, Role.FLOOR);
            this.set(shaftX, floorY, shaftZ, "air");

            // the ladder up to the next slab, with the cell beside it kept clear
            for (int y = floorY + 1; y < floorY + floorHeight; y++) {
                this.set(shaftX, y, shaftZ, "ladder");
                this.set(shaftX, y, shaftZ + 1, "air");
                count(band, Role.LADDER);
            }

            // the shell
            int windowY = floorY + 2;
            for (int y = floorY + 1; y <= floorY + floorHeight; y++) {
                boolean belt = y == floorY + floorHeight;
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean perimeter = x == x0 || x == x1 || z == z0 || z == z1;
                        if (!perimeter) {
                            continue;
                        }
                        boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                        if (corner) {
                            this.set(x, y, z, accent);
                            count(band, Role.FRAME);
                        } else if (belt) {
                            this.set(x, y, z, "frame");
                            count(band, Role.FRAME);
                        } else if (y == windowY && ((x + z) % 2 == 0)) {
                            this.set(x, y, z, (x + z) % 4 == 0 ? "window_band" : windowBlock);
                            count(band, Role.WINDOW);
                        } else {
                            this.set(x, y, z, wall);
                            count(band, Role.WALL);
                        }
                    }
                }
            }

            // interior rooms, then furniture and cover in each of them; the cover LINES are planned here
            // and placed once the shell is final (see the LinePlan block in buildBuilding).
            buildInterior(building, band, x0, z0, w, d, floorY, floorHeight, roomsX, roomsZ, wall,
                    seed + floor * 31, minCover, minFurniture, shaftX, shaftZ, minCoverLines,
                    seed + floor * 31 + 7, interiorVariation, extraOpeningsMax);
        }

        // roof slab and parapet
        this.fill(x0, topY, z0, x1, topY, z1, roofBlock);
        count(buildingName + "/roof", Role.ROOF);
        if (roofAccess) {
            this.set(shaftX, topY, shaftZ, "air");
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == x0 || x == x1 || z == z0 || z == z1) {
                    this.set(x, topY + 1, z, roofBlock);
                }
            }
        }
        if (roofAccess) {
            this.set(shaftX, topY - 1, shaftZ, "ladder");
            this.set(shaftX, topY, shaftZ, "air");
        }

        if (ruined) {
            collapseQuadrant(x0, z0, w, d, topY - 2 * floorHeight, topY + 1);
            // OPT-IN ruin hygiene (layout key "ruin_light_cleanup", default off): a hanging lantern whose
            // ceiling the collapse removed would pop off the moment a neighbour updates, so take it down
            // here instead of shipping a light hanging in mid air. Off by default because the four
            // shipped presets are byte-pinned by the gates.
            if (Json.bool(layoutFlagOwner, "ruin_light_cleanup", false)) {
                // The whole column, not just the collapsed Y range: a chain one block BELOW the collapse
                // lost the ceiling that held it, so it is orphaned even though its own Y was not touched.
                sweepDetachedLights(x0, z0, w, d, 1, topY + 1);
            }
        }
        // The street entrance goes in LAST: a ruined building clears a quadrant that can reach the ground
        // floor, and a door placed before that would be deleted along with the wall it sits in.
        int doorsBefore = this.doors.size();
        entrance(building, buildingName + "/0", x0, z0, w, d, doorSide, minDoors - interiorDoors);
        // ... and the ground floor gets the look the user's own buildings have (M1 survey of his district):
        // a terracotta wainscot, a torch, a bed and a little more cover. It runs after the entrance so the
        // door stays a door.
        if (Json.bool(layoutFlagOwner, "ground_floor_look", false)) {
            groundFloorLook(building, buildingName + "/0", x0, z0, w, d, wall, seed, minCover);
        }
        // ---- the FLOOR HOLES of every upper floor (deliverable G) are only RECORDED here: they are cut in
        // build(), on the connected grid and before the cover lines, because "whatever is below must be
        // reachable floor" is only a meaningful test once the doorway repair has run.
        if (interiorVariation > 0 && floorHolesMax > 0) {
            this.holeJobs.add(new HoleJob(buildingName, x0, z0, x1, z1, floors, floorHeight, floorHolesMax,
                    seed + 613, floorBlock));
        }
        // ---- the cover LINES of every floor and the roof are only PLANNED here too; build() places them
        // after the floor holes, so a hole can never sit in the middle of a usable line.
        if (minCoverLines > 0) {
            linePlans.add(new LinePlan(buildingName + "/roof", roofQuadrants(x0, z0, w, d, coverLineMin),
                    topY + 1, topY + 2, x0 - 1, z0 - 1, x1 + 1, z1 + 1, shaftX, shaftZ, seed + 991,
                    minCoverLines, coverLineMin, coverLineMax, coverLineBlocks));
            this.allLinePlans.addAll(linePlans);
        }
        // The door cell this building ended up with, for the structure-piece export.
        int[] doorCell = this.doors.size() > doorsBefore ? this.doors.get(this.doors.size() - 1) : null;
        if (doorCell != null) {
            this.buildingDoors.put(buildingName, doorCell);
            this.buildingDoorSides.put(buildingName, doorSide);
        }
        this.lastBuildingBox = new int[]{x0, 1, z0, x1, topY, z1};
        System.out.println("  building '" + buildingName + "' at (" + x0 + "," + z0 + ") " + w + "x" + d
                + ", " + floors + " floors, top y=" + topY + (ruined ? ", ruined" : ""));
        reportVariation(buildingName, floors, floorHeight, x0, z0, x1, z1, interiorVariation > 0);
    }

    /**
     * The vertical invariant, measured on the FINAL grid: for every building, how many of its floors are
     * reachable from the entrance with the ladder shaft treated as a wall - i.e. through a FLOOR HOLE rather
     * than up the ladder. That is the measured proof that a hole IS a vertical connection in the
     * connectivity graph; the gate {@code tools/selftest_interior_variation.js} asserts the number and the
     * per-floor hole count, and a building where no hole could be cut at all is reported loudly here.
     */
    private void checkVerticalInvariant() {
        for (BuildingVariation building : this.buildingVariations) {
            this.shaftX = building.x0 + 1;
            this.shaftZ = building.z0 + 1;
            building.floorsWithoutShaft = floorsReachableWithoutShaft(building.x0, building.x1, building.z0,
                    building.z1, building.floors, building.floorHeight);
            if (building.variation && building.floorsWithoutShaft == 0) {
                System.out.println("  WARNING " + building.name + ": not one floor is reachable without the"
                        + " ladder shaft - no floor hole survived");
            }
        }
    }

    /** Per-building door cell + the side it faces, so the piece export can put a connector at each door. */
    private final Map<String, int[]> buildingDoors = new LinkedHashMap<>();
    private final Map<String, String> buildingDoorSides = new LinkedHashMap<>();
    private int[] lastBuildingBox;
    /** Overrides the door cell/side while one piece is being written (set by the export loop). */
    private int[] pieceDoor;
    private String pieceDoorSide = "south";
    private final List<Object[]> exportedPieces = new ArrayList<>();

    /**
     * The ground-floor look of the user's own buildings (M1 survey): a terracotta wainscot along the inside
     * of the walls, a torch, one bed, and extra cover. Everything here is decoration - no container, no
     * spawner, no fire - so a generated building can go into the same pool as the extracted ones.
     */
    private void groundFloorLook(Map<String, Object> building, String band, int x0, int z0, int w, int d,
                                 String wall, int seed, int minCover) {
        java.util.Random random = new java.util.Random(seed * 7L + 13L);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        // The wainscot: the first course inside the perimeter walls, alternating two terracotta shades the
        // way his own ground floors do.
        for (int y = 2; y <= 3; y++) {
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    boolean innerFace = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
                    if (!innerFace) { continue; }
                    String state = this.cell(x, y, z);
                    if (state != null && !state.equals("air")) { continue; }
                    this.place(band, x, y, z, y == 2 ? "skirt" : ((x + z) % 2 == 0 ? "skirt_alt" : "skirt_warm"),
                            Role.WALL);
                }
            }
        }
        // A torch on the wall and, in one corner, a bed (head + foot).
        this.place(band, x0 + 1, 4, z0 + 1, "light_torch", Role.LIGHT);
        int bedX = x1 - 1;
        int bedZ = z1 - 1;
        if (this.cell(bedX, 2, bedZ) != null && this.cell(bedX, 2, bedZ).equals("air")) {
            this.place(band, bedX, 2, bedZ, "furn_bed", Role.FURNITURE);
            this.place(band, bedX, 2, bedZ - 1, "furn_bed_foot", Role.FURNITURE);
        }
        // A little more cover on the ground floor than upstairs: the entrance hall is where a fight starts.
        for (int i = 0; i < 3; i++) {
            int cx = x0 + 2 + random.nextInt(Math.max(1, w - 4));
            int cz = z0 + 2 + random.nextInt(Math.max(1, d - 4));
            placeCover(band, cx, cz, 2, random.nextBoolean() ? "x" : "z", 1 + random.nextInt(3),
                    random.nextInt(2));
        }
    }

    /** Interior doors placed per building, so the entrance can make up the difference. */
    private int interiorDoors;

    /** One floor's interior-variation facts, for the report and the randomness gate. */
    static final class FloorVariation {
        final String band;
        final int partitionsX;
        final int partitionsZ;
        final int openings;
        final int holes;
        final int rooms;
        final long fingerprint;

        FloorVariation(String band, int partitionsX, int partitionsZ, int openings, int holes, int rooms,
                       long fingerprint) {
            this.band = band;
            this.partitionsX = partitionsX;
            this.partitionsZ = partitionsZ;
            this.openings = openings;
            this.holes = holes;
            this.rooms = rooms;
            this.fingerprint = fingerprint;
        }

        /** {@code floor=<n> partitionsX=.. partitionsZ=.. openings=.. holes=.. rooms=.. hash=..} */
        String text() {
            int slash = this.band.lastIndexOf('/');
            return "floor=" + (slash < 0 ? this.band : this.band.substring(slash + 1))
                    + " partitionsX=" + this.partitionsX + " partitionsZ=" + this.partitionsZ
                    + " partitions=" + (this.partitionsX + this.partitionsZ)
                    + " openings=" + this.openings + " holes=" + this.holes
                    + " rooms=" + this.rooms + " hash=" + Long.toHexString(this.fingerprint);
        }
    }

    /** The variation records of the building being built right now. */
    private final List<FloorVariation> buildingVariation = new ArrayList<>();

    /**
     * Splits the interior into rooms with one-block walls and a doorway each, then furnishes every room: at
     * least one piece of cover, one piece of furniture and one light, chosen deterministically from the seed
     * so the same layout always generates the same building.
     *
     * <p>With {@code interior_variation} on the room grid is itself drawn from the seed (deliverable G):
     * the number of partitions per axis moves by -1..+1 around the declared {@code rooms_x}/{@code rooms_z},
     * every partition is jittered, and 0..N EXTRA 1x2 openings are punched between adjacent rooms. Every
     * position, count and hash is recorded in {@link #buildingVariation} so the randomness is measurable
     * instead of asserted.</p>
     */
    private void buildInterior(Map<String, Object> building, String band, int x0, int z0, int w, int d,
                               int floorY, int floorHeight, int roomsX, int roomsZ, String wall,
                               int seed, int minCover, int minFurniture, int shaftX, int shaftZ,
                               int minCoverLines, int lineSeed, int interiorVariation,
                               int extraOpeningsMax) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int yLow = floorY + 1;
        int yHigh = floorY + floorHeight - 1;
        java.util.Random random = new java.util.Random(seed);

        // clear the interior, then put the dividing walls in
        for (int y = yLow; y <= yHigh; y++) {
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    this.set(x, y, z, "air");
                }
            }
        }
        List<Integer> dividersX = new ArrayList<>();
        List<Integer> dividersZ = new ArrayList<>();
        if (interiorVariation > 0) {
            dividersX = jitteredDividers(x0, x1, roomsX, random);
            dividersZ = jitteredDividers(z0, z1, roomsZ, random);
        } else {
            for (int room = 1; room < roomsX; room++) {
                dividersX.add(x0 + (w * room) / roomsX);
            }
            for (int room = 1; room < roomsZ; room++) {
                dividersZ.add(z0 + (d * room) / roomsZ);
            }
        }
        int extraOpenings = 0;
        StringBuilder fingerprint = new StringBuilder();
        // The partition walls of this floor, one course at a time.
        for (int y = yLow; y <= yHigh; y++) {
            for (int x : dividersX) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    this.set(x, y, z, "wall_inner");
                    count(band, Role.WALL);
                }
            }
            for (int z : dividersZ) {
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    this.set(x, y, z, "wall_inner");
                    count(band, Role.WALL);
                }
            }
        }
        // ... and their doorways plus the extra openings, once per wall (never once per height).
        for (int x : dividersX) {
            int doorway = (z0 + z1) / 2;
            if (interiorVariation > 0) {
                doorway = jitteredDoorway(z0 + 1, z1 - 1, doorway, random);
            }
            this.set(x, yLow, doorway, "air");
            this.set(x, yLow + 1, doorway, "air");
            this.set(x, yHigh, doorway, "door_frame");
            this.openings.add(new int[]{x, yLow, doorway, 1});
            fingerprint.append('x').append(x).append(':').append(doorway).append(';');
            if (interiorVariation > 0) {
                extraOpenings += extraOpenings(band, fingerprint, true, x, yLow, yHigh, z0 + 1, z1 - 1,
                        doorway, extraOpeningsMax, random);
            }
        }
        for (int z : dividersZ) {
            int doorway = (x0 + x1) / 2;
            if (interiorVariation > 0) {
                doorway = jitteredDoorway(x0 + 1, x1 - 1, doorway, random);
            }
            this.set(doorway, yLow, z, "air");
            this.set(doorway, yLow + 1, z, "air");
            this.set(doorway, yHigh, z, "door_frame");
            this.openings.add(new int[]{doorway, yLow, z, 0});
            fingerprint.append('z').append(z).append(':').append(doorway).append(';');
            if (interiorVariation > 0) {
                extraOpenings += extraOpenings(band, fingerprint, false, z, yLow, yHigh, x0 + 1, x1 - 1,
                        doorway, extraOpeningsMax, random);
            }
        }
        // keep the ladder shaft usable: no wall may sit in it or beside it
        for (int y = yLow; y <= yHigh; y++) {
            this.set(shaftX, y, shaftZ, y < yHigh ? "ladder" : "air");
            this.set(shaftX, y, shaftZ + 1, "air");
            this.set(shaftX + 1, y, shaftZ, "air");
        }

        // the room boxes, from the wall edges (outer wall, dividers, outer wall)
        List<int[]> rooms = new ArrayList<>();
        List<Integer> xEdges = new ArrayList<>();
        xEdges.add(x0);
        xEdges.addAll(dividersX);
        xEdges.add(x1);
        List<Integer> zEdges = new ArrayList<>();
        zEdges.add(z0);
        zEdges.addAll(dividersZ);
        zEdges.add(z1);
        for (int edgeX = 0; edgeX + 1 < xEdges.size(); edgeX++) {
            for (int edgeZ = 0; edgeZ + 1 < zEdges.size(); edgeZ++) {
                int rx0 = xEdges.get(edgeX) + 1;
                int rx1 = xEdges.get(edgeX + 1) - 1;
                int rz0 = zEdges.get(edgeZ) + 1;
                int rz1 = zEdges.get(edgeZ + 1) - 1;
                if (rx1 - rx0 >= 1 && rz1 - rz0 >= 1) {
                    rooms.add(new int[]{rx0, rz0, rx1, rz1});
                }
            }
        }

        for (int index = 0; index < rooms.size(); index++) {
            int[] room = rooms.get(index);
            furnishing(band, room, yLow, yHigh, random, index);
            this.roomRecords.add(new int[]{room[0], room[1], room[2], room[3], yLow});
            fingerprint.append('r').append(room[0]).append(',').append(room[1]).append(',')
                    .append(room[2]).append(',').append(room[3]).append(';');
        }

        // A bigger building with few rooms can still fall short, so top the floor up until it passes its
        // own declared minimum - the self test checks the same numbers from the NBT.
        int guard = 0;
        while (roles(band).getOrDefault(Role.COVER, 0) < minCover && guard++ < 64) {
            int[] room = rooms.get(guard % Math.max(1, rooms.size()));
            placeCover(band, room[0] + (guard % 3), room[1] + (guard % 2), yLow, "x", 3, guard);
            if (guard > 60) {
                break;
            }
        }
        guard = 0;
        while (roles(band).getOrDefault(Role.FURNITURE, 0) < minFurniture && guard++ < 64) {
            int[] room = rooms.get(guard % Math.max(1, rooms.size()));
            placeFurniture(band, room[0] + (guard % 3), room[1] + (guard % 2), yLow, guard);
            if (guard > 60) {
                break;
            }
        }

        this.buildingVariation.add(new FloorVariation(band, dividersX.size(), dividersZ.size(),
                dividersX.size() + dividersZ.size() + extraOpenings, 0, rooms.size(),
                stableHash(fingerprint.toString())));

        // ---- cover LINES are only PLANNED here. They are placed once the whole shell is final (after the
        // ruin collapse, the entrance and the ground-floor look), so nothing can later bury a line under a
        // wall course or delete the floor a line stands next to.
        if (minCoverLines > 0) {
            this.linePlans.add(new LinePlan(band, rooms, yLow, yHigh, x0 - 1, z0 - 1, x0 + w, z0 + d,
                    shaftX, shaftZ, lineSeed, minCoverLines, coverLineMin, coverLineMax, coverLineBlocks));
        }
    }

    /** A stable 64-bit hash of a text, so two seeds can be compared without depending on String.hashCode. */
    private static long stableHash(String text) {
        long hash = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            hash = 31 * hash + text.charAt(i);
        }
        return hash;
    }

    /**
     * The partition positions of one axis, drawn from the seed: the declared count moves by -1..+1 (never
     * below 0 and never more than the axis can hold at 3 cells per room), the positions are then chosen at
     * random from the legal set - every room at least 3 cells wide, every pair of partitions at least 4
     * apart, so a room can still host a 3-long cover line.
     */
    private static List<Integer> jitteredDividers(int low, int high, int wanted, java.util.Random random) {
        int inner = high - low - 1;
        int maxCount = Math.max(0, inner / 4);
        int count = Math.min(maxCount, Math.max(0, wanted + random.nextInt(3) - 1));
        List<Integer> legal = new ArrayList<>();
        for (int position = low + 4; position <= high - 4; position++) {
            legal.add(position);
        }
        for (int attempt = 0; attempt < 8 && count > 0; attempt++) {
            java.util.Collections.shuffle(legal, random);
            List<Integer> chosen = new ArrayList<>();
            for (int position : legal) {
                boolean fits = true;
                for (int other : chosen) {
                    if (Math.abs(other - position) < 4) {
                        fits = false;
                        break;
                    }
                }
                if (fits) {
                    chosen.add(position);
                }
                if (chosen.size() == count) {
                    break;
                }
            }
            if (chosen.size() == count) {
                java.util.Collections.sort(chosen);
                return chosen;
            }
            count--;
        }
        java.util.Collections.sort(legal);
        return new ArrayList<>();
    }

    /** The doorway of a partition, moved off the exact middle but kept at least 2 cells from its corners. */
    private static int jitteredDoorway(int from, int to, int middle, java.util.Random random) {
        int lo = Math.max(from, middle - 2);
        int hi = Math.min(to, middle + 2);
        if (hi <= lo) {
            return Math.max(from, Math.min(to, middle));
        }
        return lo + random.nextInt(hi - lo + 1);
    }

    /**
     * 0..{@code extraMax} extra 1x2 openings in one partition wall, each at least 3 cells from the main
     * doorway and from the previous extra (so a wall keeps a solid pier between its openings), and never on
     * the ladder shaft. Returns how many were punched.
     */
    private int extraOpenings(String band, StringBuilder fingerprint, boolean alongX, int fixed, int yLow,
                              int yHigh, int from, int to, int doorway, int extraMax,
                              java.util.Random random) {
        if (extraMax <= 0) {
            return 0;
        }
        int wanted = random.nextInt(extraMax + 1);
        int placed = 0;
        List<Integer> used = new ArrayList<>();
        used.add(doorway);
        for (int attempt = 0; attempt < 12 && placed < wanted; attempt++) {
            int at = from + random.nextInt(Math.max(1, to - from + 1));
            boolean fits = true;
            for (int other : used) {
                if (Math.abs(other - at) < 3) {
                    fits = false;
                    break;
                }
            }
            int x = alongX ? fixed : at;
            int z = alongX ? at : fixed;
            if (!fits || isShaftCell(x, yLow, z, this.shaftX, this.shaftZ)
                    || isShaftCell(x, yLow + 1, z, this.shaftX, this.shaftZ)) {
                continue;
            }
            this.set(x, yLow, z, "air");
            this.set(x, yLow + 1, z, "air");
            this.set(x, yHigh, z, "door_frame");
            this.openings.add(new int[]{x, yLow, z, alongX ? 1 : 0});
            used.add(at);
            fingerprint.append('o').append(x).append(',').append(z).append(';');
            placed++;
        }
        return placed;
    }

    // ================================================================== floor holes (deliverable G)

    /**
     * 1..N floor holes per upper floor: a 1x1 or 1x2 passage cut through a slab, which is a VERTICAL
     * connection in the connectivity graph (the flood fill moves in six directions, so
     * {@code (x, floorY-1, z) -> (x, floorY, z) -> (x, floorY+1, z)} is a legal step as soon as all three
     * cells are passable).
     *
     * <p>The rule enforced here is stronger than "there is a hole": every hole must connect a cell that is
     * ALREADY reachable from the building's entrance - with the ladder shaft treated as a wall - to a room
     * that is not reachable yet. So the holes alone span the building vertically, and the report proves it
     * ({@code floorsWithoutShaft}). A layout whose geometry cannot manage that fails loudly instead of
     * shipping a floor nothing can reach.</p>
     *
     * <p>A hole is refused unless: it is strictly inside the building; it replaces real floor material; it
     * has 2 blocks of headroom on BOTH floors; it is clear of the ladder shaft, of every doorway/opening at
     * all three heights, of any cover-line cell and of any block entity; and the cell below it is reachable
     * floor.</p>
     */
    private void cutFloorHoles(String buildingName, int x0, int z0, int w, int d, int floors, int floorHeight,
                               int holesMax, int seed, String floorBlock) {
        java.util.Random random = new java.util.Random(seed);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int floor = 1; floor < floors; floor++) {
            int floorY = 1 + floor * floorHeight;
            int wanted = 1 + random.nextInt(Math.max(1, holesMax));
            int placed = 0;
            StringBuilder where = new StringBuilder();
            for (int hole = 0; hole < wanted; hole++) {
                // "Whatever is below must be reachable floor": the grid is connected by now (the doorway
                // repair and the connectivity breach have run), so this is the real invariant and not the
                // pre-post-pass approximation it would have been inside buildBuilding.
                boolean[] reachable = floodFrom(this.entranceDoors);
                boolean[] shaftFree = floodFrom(this.entranceDoors, true);
                List<int[]> useful = new ArrayList<>();
                List<int[]> any = new ArrayList<>();
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    for (int z = z0 + 1; z <= z1 - 1; z++) {
                        for (int alongX = 0; alongX <= 1; alongX++) {
                            for (int length = 1; length <= 2; length++) {
                                if (!holeFits(x, z, floorY, alongX == 1, length, x0, x1, z0, z1, reachable,
                                        floorBlock)) {
                                    continue;
                                }
                                int[] candidate = new int[]{x, z, alongX, length};
                                any.add(candidate);
                                if (shaftFree[this.index(x, floorY - 1, z)]
                                        && holeOpensANewRoom(x, z, floorY, alongX == 1, length, x0, x1, z0,
                                                z1, shaftFree)) {
                                    useful.add(candidate);
                                }
                            }
                        }
                    }
                }
                // Prefer a hole that connects a room nothing reaches yet; a building that is already
                // connected vertically (a ruin's collapsed quadrant) still gets its hole, it is just
                // redundant there.
                List<int[]> from = useful.isEmpty() ? any : useful;
                if (from.isEmpty()) {
                    break;
                }
                int[] pick = from.get(random.nextInt(from.size()));
                for (int i = 0; i < pick[3]; i++) {
                    int x = pick[2] == 1 ? pick[0] + i : pick[0];
                    int z = pick[2] == 1 ? pick[1] : pick[1] + i;
                    this.set(x, floorY, z, "air");
                    for (int y = floorY - 1; y <= floorY + 2; y++) {
                        this.holeClearance.add(this.index(x, y, z));
                    }
                }
                // Prove the hole really is a passage: the cell above it must now be reachable from an
                // entrance (it has to be - the two are two passable cells apart through the hole - but the
                // check makes the claim testable rather than argued).
                boolean[] after = floodFrom(this.entranceDoors);
                if (!after[this.index(pick[0], floorY + 1, pick[1])]) {
                    throw new IllegalStateException(buildingName + " floor " + floor + ": the hole at "
                            + pick[0] + "," + floorY + "," + pick[1] + " is not a passage");
                }
                where.append(pick[0]).append(',').append(pick[1])
                        .append(pick[2] == 1 ? 'x' : 'z').append(pick[3]).append(';');
                placed++;
            }
            if (placed == 0) {
                System.out.println("  WARNING " + buildingName + " floor " + floor + " (y=" + floorY
                        + "): no legal floor hole - the rooms above every reachable cell of the floor below "
                        + "are already connected, or no column has the headroom");
            }
            // Record the holes on the floor they belong to (the slab that was cut is the CEILING of the
            // floor below and the FLOOR of this one, so the hole counts for this band).
            for (BuildingVariation building : this.buildingVariations) {
                if (!building.name.equals(buildingName)) {
                    continue;
                }
                for (int index = 0; index < building.detail.size(); index++) {
                    FloorVariation record = building.detail.get(index);
                    if (record.band.equals(buildingName + "/" + floor)) {
                        building.detail.set(index, new FloorVariation(record.band, record.partitionsX,
                                record.partitionsZ, record.openings, placed, record.rooms,
                                stableHash(Long.toHexString(record.fingerprint) + "|holes|" + where)));
                        break;
                    }
                }
                break;
            }
        }
    }

    /** True when a 1x{@code length} hole at (x, floorY, z) is legal - see {@link #cutFloorHoles}. */
    private boolean holeFits(int x, int z, int floorY, boolean alongX, int length, int x0, int x1, int z0,
                             int z1, boolean[] reachable, String floorBlock) {
        for (int i = 0; i < length; i++) {
            int cx = alongX ? x + i : x;
            int cz = alongX ? z : z + i;
            if (cx <= x0 || cx >= x1 || cz <= z0 || cz >= z1) {
                return false;
            }
            String base = this.baseKeyOfState.getOrDefault(this.cell(cx, floorY, cz),
                    this.cell(cx, floorY, cz));
            if (!base.equals(floorBlock) && !base.equals("floor") && !base.equals("floor_inner")
                    && !base.endsWith("_tile")) {
                return false;                        // not a real piece of the slab
            }
            for (int dy : new int[]{-1, 0, 1, 2}) {
                int y = floorY + dy;
                if (dy == -1 || dy == 0 || dy == 1) {
                    if (isShaftCell(cx, y, cz, this.shaftX, this.shaftZ)) {
                        return false;
                    }
                }
                if (dy >= -1 && dy <= 1 && nearPassage(cx, y, cz)) {
                    return false;
                }
                if (this.blockEntities.containsKey(this.index(cx, y, cz))) {
                    return false;
                }
                if (dy == -1 || dy == 1 || dy == 2) {
                    // AIR, not merely passable: a hanging chain lives in the cell under many of these slabs,
                    // and a hole cut above it would leave the chain, its lantern and its end rod as a light
                    // cluster attached to nothing. A hole therefore only goes where the column is empty.
                    if (!this.cell(cx, y, cz).equals("air")) {
                        return false;                // 2 blocks of headroom on both floors
                    }
                }
            }
            if (!reachable[this.index(cx, floorY - 1, cz)]) {
                return false;                        // whatever is below must be reachable floor
            }
            if (this.metricCoverCell(cx, floorY - 1, cz) || this.metricCoverCell(cx, floorY + 1, cz)) {
                return false;
            }
        }
        return true;
    }

    /** True when the hole's upper end opens into a room the shaft-free flood has not reached yet. */
    private boolean holeOpensANewRoom(int x, int z, int floorY, boolean alongX, int length, int x0, int x1,
                                      int z0, int z1, boolean[] reachable) {
        for (int i = 0; i < length; i++) {
            int cx = alongX ? x + i : x;
            int cz = alongX ? z : z + i;
            if (!roomIsUnreachable(cx, floorY + 1, cz, reachable, x0, x1, z0, z1)) {
                return false;
            }
        }
        return true;
    }

    /** Debug twin of {@link #roomIsUnreachable} that ignores the room filter (temporary). */
    private boolean houseUnreachableProbe(int x, int y, int z, boolean[] reachable, int x0, int x1, int z0,
                                          int z1) {
        for (int[] room : this.roomRecords) {
            if (room[4] != y || room[0] < x0 || room[2] > x1 || room[1] < z0 || room[3] > z1) {
                continue;
            }
            if (x < room[0] || x > room[2] || z < room[1] || z > room[3]) {
                continue;
            }
            for (int rx = room[0]; rx <= room[2]; rx++) {
                for (int rz = room[1]; rz <= room[3]; rz++) {
                    if (reachable[this.index(rx, y, rz)]) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    /** True when the recorded room containing (x, y, z) has NOT ONE cell the flood has reached. */
    private boolean roomIsUnreachable(int x, int y, int z, boolean[] reachable, int x0, int x1, int z0,
                                      int z1) {
        for (int[] room : this.roomRecords) {
            if (room[4] != y || room[0] < x0 || room[2] > x1 || room[1] < z0 || room[3] > z1) {
                continue;
            }
            if (x < room[0] || x > room[2] || z < room[1] || z > room[3]) {
                continue;
            }
            for (int rx = room[0]; rx <= room[2]; rx++) {
                for (int rz = room[1]; rz <= room[3]; rz++) {
                    if (reachable[this.index(rx, y, rz)]) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * How many floors of the building that just finished are reachable from its entrance with the ladder
     * shaft treated as a wall - i.e. how many floors the FLOOR HOLES alone connect. Must equal
     * {@code floors}; the generator refuses to ship a building where it does not.
     */
    private int floorsReachableWithoutShaft(int x0, int x1, int z0, int z1, int floors, int floorHeight) {
        boolean[] reachable = floodFrom(this.entranceDoors, true);
        int reached = 0;
        for (int floor = 0; floor < floors; floor++) {
            int yLow = 2 + floor * floorHeight;
            boolean any = false;
            for (int[] room : this.roomRecords) {
                if (room[4] != yLow || room[0] < x0 || room[2] > x1 || room[1] < z0 || room[3] > z1) {
                    continue;
                }
                for (int x = room[0]; x <= room[2] && !any; x++) {
                    for (int z = room[1]; z <= room[3] && !any; z++) {
                        if (reachable[this.index(x, yLow, z)]) {
                            any = true;
                        }
                    }
                }
            }
            if (any) {
                reached++;
            }
        }
        return reached;
    }

    /** Appends the interior-variation facts of one finished building; the vertical check runs later. */
    private void reportVariation(String buildingName, int floors, int floorHeight, int x0, int z0, int x1,
                                 int z1, boolean variation) {
        this.buildingVariations.add(new BuildingVariation(buildingName, floors, floorHeight, x0, z0, x1, z1,
                variation, new ArrayList<>(this.buildingVariation)));
    }

    /** One building's variation facts, filled while it is built and completed by the fixture pass. */
    private static final class BuildingVariation {
        final String name;
        final int floors;
        final int floorHeight;
        final int x0;
        final int z0;
        final int x1;
        final int z1;
        final boolean variation;
        final List<FloorVariation> detail;
        int spawners;
        int chests;
        int floorsWithoutShaft;

        BuildingVariation(String name, int floors, int floorHeight, int x0, int z0, int x1, int z1,
                          boolean variation, List<FloorVariation> detail) {
            this.name = name;
            this.floors = floors;
            this.floorHeight = floorHeight;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.variation = variation;
            this.detail = detail;
        }

        /** The one-line human readout of this building. */
        String text() {
            StringBuilder text = new StringBuilder();
            for (FloorVariation record : this.detail) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(record.text());
            }
            return text.toString();
        }

        /** This building as one JSON object - the shape tools/selftest_interior_variation.js parses. */
        String json() {
            StringBuilder floors = new StringBuilder();
            for (FloorVariation record : this.detail) {
                if (floors.length() > 0) {
                    floors.append(',');
                }
                floors.append("{\"band\":\"").append(record.band)
                        .append("\",\"partitionsX\":").append(record.partitionsX)
                        .append(",\"partitionsZ\":").append(record.partitionsZ)
                        .append(",\"partitions\":").append(record.partitionsX + record.partitionsZ)
                        .append(",\"openings\":").append(record.openings)
                        .append(",\"holes\":").append(record.holes)
                        .append(",\"rooms\":").append(record.rooms)
                        .append(",\"hash\":\"").append(Long.toHexString(record.fingerprint)).append("\"}");
            }
            return "{\"building\":\"" + this.name + "\",\"floors\":" + this.floors
                    + ",\"floorsWithoutShaft\":" + this.floorsWithoutShaft
                    + ",\"spawners\":" + this.spawners + ",\"chests\":" + this.chests
                    + ",\"floorsDetail\":[" + floors + "]}";
        }
    }

    /** Every building's variation facts, in build order. */
    private final List<BuildingVariation> buildingVariations = new ArrayList<>();

    /**
     * The interior-variation report: one human line per building, then one
     * {@code INTERIOR_REPORT <json>} line with the whole layout. Printed by every run (so the numbers are in
     * the log of a normal regeneration) and it is the ONLY output of {@code --report}.
     */
    private void printInteriorReport() {
        int partitions = 0;
        int openings = 0;
        int holes = 0;
        int rooms = 0;
        int upperFloors = 0;
        int minPartitions = Integer.MAX_VALUE;
        int maxPartitions = 0;
        int minHoles = Integer.MAX_VALUE;
        int maxHoles = 0;
        int minOpenings = Integer.MAX_VALUE;
        int maxOpenings = 0;
        for (BuildingVariation building : this.buildingVariations) {
            System.out.println("  interior '" + building.name + "': " + building.text()
                    + " spawners=" + building.spawners + " chests=" + building.chests);
            for (FloorVariation floor : building.detail) {
                int perFloor = floor.partitionsX + floor.partitionsZ;
                partitions += perFloor;
                openings += floor.openings;
                rooms += floor.rooms;
                minPartitions = Math.min(minPartitions, perFloor);
                maxPartitions = Math.max(maxPartitions, perFloor);
                minOpenings = Math.min(minOpenings, floor.openings);
                maxOpenings = Math.max(maxOpenings, floor.openings);
                if (floor.holes > 0) {
                    upperFloors++;
                    holes += floor.holes;
                    minHoles = Math.min(minHoles, floor.holes);
                    maxHoles = Math.max(maxHoles, floor.holes);
                }
            }
        }
        System.out.println("  interior variation: " + this.buildingVariations.size() + " building(s), "
                + partitions + " partition(s), " + openings + " opening(s), " + holes
                + " hole(s) over " + upperFloors + " upper floor(s); partitions/floor "
                + (minPartitions == Integer.MAX_VALUE ? 0 : minPartitions) + ".." + maxPartitions
                + ", openings/floor " + (minOpenings == Integer.MAX_VALUE ? 0 : minOpenings) + ".."
                + maxOpenings + ", holes/upper floor " + (minHoles == Integer.MAX_VALUE ? 0 : minHoles)
                + ".." + maxHoles);
        StringBuilder json = new StringBuilder();
        for (BuildingVariation building : this.buildingVariations) {
            if (json.length() > 0) {
                json.append(',');
            }
            json.append(building.json());
        }
        System.out.println("INTERIOR_REPORT {\"layout\":\"" + this.name + "\",\"buildings\":[" + json
                + "]}");
    }

    /** One room's worth of loot: one cover piece, one or two furniture pieces and a light. */
    private void furnishing(String band, int[] room, int yLow, int yHigh, java.util.Random random,
                            int index) {
        int rx = room[0];
        int rz = room[1];
        placeCover(band, rx, rz, yLow, index % 2 == 0 ? "x" : "z", 2 + (index % 3), index);
        placeFurniture(band, rx + 1, rz, yLow, index);
        if ((room[2] - room[0]) >= 3 && (room[3] - room[1]) >= 3) {
            placeFurniture(band, rx, rz + 1, yLow, index + 7);
        }
        placeLight(band, (room[0] + room[2]) / 2, (room[1] + room[3]) / 2, yHigh);
        if (random.nextBoolean()) {
            place(band, rx, yLow, rz + 1, "furn_carpet", Role.FURNITURE);
        }
        if (random.nextInt(3) == 0) {
            place(band, room[2], yLow, room[3], "furn_plant", Role.FURNITURE);
        }
    }

    /** Places a cell only when it is still air, and counts it only when it really landed. */
    private void place(String band, int x, int y, int z, String key, Role role) {
        if (setIfAir(x, y, z, key)) {
            count(band, role);
        }
    }

    /** Low cover: sandbags, a plank barricade, a crate stack, rubble or a barricade of iron bars. */
    private void placeCover(String band, int x, int z, int yLow, String axis, int length, int variant) {
        switch (variant % 6) {
            case 0 -> {   // sandbag pile
                for (int i = 0; i < length; i++) {
                    int cx = axis.equals("x") ? x + i : x;
                    int cz = axis.equals("x") ? z : z + i;
                    place(band, cx, yLow, cz, "cover_sandbag", Role.COVER);
                    place(band, cx, yLow + 1, cz,
                            i % 2 == 0 ? "cover_sandbag" : "cover_sandbag_top", Role.COVER);
                }
            }
            case 1 -> {   // plank barricade: a slab run with a trapdoor lid
                for (int i = 0; i < length; i++) {
                    int cx = axis.equals("x") ? x + i : x;
                    int cz = axis.equals("x") ? z : z + i;
                    place(band, cx, yLow, cz, "cover_plank", Role.COVER);
                    if (i % 2 == 0) {
                        place(band, cx, yLow + 1, cz, "cover_plank_lid", Role.COVER);
                    }
                }
            }
            case 2 -> {   // crate stack
                place(band, x, yLow, z, "cover_crate", Role.COVER);
                place(band, x, yLow + 1, z, "cover_crate_top", Role.COVER);
                place(band, x + 1, yLow, z, "cover_crate", Role.COVER);
            }
            case 3 -> {   // rubble
                place(band, x, yLow, z, "cover_rubble", Role.COVER);
                place(band, x + 1, yLow, z, "cover_rubble_top", Role.COVER);
                place(band, x, yLow, z + 1, "cover_brick", Role.COVER);
            }
            case 4 -> {   // iron bar gate
                for (int i = 0; i < length; i++) {
                    int cx = axis.equals("x") ? x + i : x;
                    int cz = axis.equals("x") ? z : z + i;
                    place(band, cx, yLow, cz, "cover_barrier", Role.COVER);
                    place(band, cx, yLow + 1, cz, "cover_barrier", Role.COVER);
                }
            }
            default -> {  // cobblestone wall stub
                for (int i = 0; i < length; i++) {
                    int cx = axis.equals("x") ? x + i : x;
                    int cz = axis.equals("x") ? z : z + i;
                    place(band, cx, yLow, cz, "cover_wall", Role.COVER);
                }
            }
        }
    }

    /** Furniture: a shelf, a table with a sign, a locker, an overturned table or a stool. */
    private void placeFurniture(String band, int x, int z, int yLow, int variant) {
        switch (variant % 6) {
            case 0 -> {   // shelf unit
                place(band, x, yLow, z, "furn_shelf", Role.FURNITURE);
                place(band, x, yLow + 1, z, "furn_shelf_top", Role.FURNITURE);
            }
            case 1 -> {   // table: four fence legs, a slab top, a sign standing on it
                place(band, x, yLow, z, "furn_leg", Role.FURNITURE);
                place(band, x + 1, yLow, z, "furn_leg", Role.FURNITURE);
                place(band, x, yLow, z + 1, "furn_leg", Role.FURNITURE);
                place(band, x + 1, yLow, z + 1, "furn_leg", Role.FURNITURE);
                place(band, x, yLow + 1, z, "furn_table_top", Role.FURNITURE);
                place(band, x + 1, yLow + 1, z, "furn_table_top", Role.FURNITURE);
                place(band, x, yLow + 2, z, "furn_sign", Role.FURNITURE);
            }
            case 2 -> {   // locker row
                place(band, x, yLow, z, "furn_cabinet", Role.FURNITURE);
                place(band, x, yLow + 1, z, "furn_cabinet", Role.FURNITURE);
            }
            case 3 -> {   // overturned table (also counts as cover)
                place(band, x, yLow, z, "cover_table_top", Role.COVER);
                place(band, x, yLow + 1, z, "furn_leg", Role.FURNITURE);
            }
            case 4 -> {   // chest and a stool
                place(band, x, yLow, z, "furn_chest", Role.FURNITURE);
                place(band, x, yLow, z + 1, "furn_stool", Role.FURNITURE);
            }
            default -> {  // workbench corner
                place(band, x, yLow, z, "furn_crafting", Role.FURNITURE);
                place(band, x + 1, yLow, z, "furn_cauldron", Role.FURNITURE);
                place(band, x, yLow, z + 1, "furn_crate", Role.FURNITURE);
            }
        }
    }

    /**
     * A hanging lantern: a chain under the ceiling with the lantern on it, plus an end rod. Each part is only
     * placed when the part it hangs from landed: in a small room a piece of furniture can occupy the lantern's
     * cell, and an end rod placed next to a lantern that never appeared is a light floating in mid air - which
     * is both wrong to look at and a detached cluster in the structure's connected-component report.
     */
    private void placeLight(String band, int x, int z, int yHigh) {
        if (!setIfAir(x, yHigh, z, "light_chain")) {
            return;
        }
        count(band, Role.LIGHT);
        if (!setIfAir(x, yHigh - 1, z, "light_lantern_hang")) {
            return;
        }
        count(band, Role.LIGHT);
        if (setIfAir(x + 1, yHigh - 1, z, "light_end_rod")) {
            count(band, Role.LIGHT);
        }
    }

    /**
     * The street entrance: a framed 1x2 opening with a real dark oak <b>door pair</b> (lower and upper
     * half), a chiselled frame, and steps outside. The door sits one block above the ground-floor slab,
     * so its sill is a full block - a door whose sill is a slab or a stair pops off.
     */
    private void entrance(Map<String, Object> building, String band, int x0, int z0, int w, int d,
                          String doorSide, int wanted) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int doorY = 2;                        // the slab of the ground floor is y = 1
        int middleX = x0 + w / 2;
        int middleZ = z0 + d / 2;
        int doorX = doorSide.equals("west") || doorSide.equals("east") ? (doorSide.equals("west") ? x0 : x1)
                : middleX;
        int doorZ = doorSide.equals("north") || doorSide.equals("south") ? (doorSide.equals("north") ? z0 : z1)
                : middleZ;

        // the opening, with a chiselled frame around it
        for (int y = doorY; y <= doorY + 2; y++) {
            this.set(doorX, y, doorZ, "air");
        }
        this.set(doorX, doorY + 2, doorZ, "door_frame");
        count(band, Role.FRAME);
        boolean alongX = doorZ == z0 || doorZ == z1;
        int sideX = alongX ? 1 : 0;
        int sideZ = alongX ? 0 : 1;
        for (int y = doorY; y <= doorY + 1; y++) {
            for (int side = -1; side <= 1; side += 2) {
                // `set`, not `place`: the frame REPLACES the wall (or the window row) beside the opening,
                // which is exactly what makes the door's neighbours solid on both sides.
                this.set(doorX + sideX * side, y, doorZ + sideZ * side, "door_frame");
                count(band, Role.FRAME);
            }
        }

        placeDoor(band, doorX, doorY, doorZ, doorSide, "left");
        this.entranceDoors.add(new int[]{doorX, doorY, doorZ});
        // Clear the entry passage NOW, not only in the post-pass: the interior-variation pass needs a ground
        // floor that is really reachable from the door, and with an even width and rooms_x = 2 the entrance
        // can line up exactly with the first partition (the same defect `repairDoorways` fixes globally, just
        // later than the holes are cut).
        clearPassage(doorX, doorY, doorZ, alongX);
        // A second door on the same side, offset one cell along the wall, so a building has at least two
        // (and the two hinge the other way, so the pair reads as a double door).
        if (wanted > 1 && w >= 8 && alongX) {
            placeDoor(band, doorX + 2, doorY, doorZ, doorSide, "right");
            for (int y = doorY; y <= doorY + 1; y++) {
                this.set(doorX + 1, y, doorZ, "door_frame");
                count(band, Role.FRAME);
                this.set(doorX + 3, y, doorZ, "door_frame");
                count(band, Role.FRAME);
            }
            this.set(doorX + 2, doorY + 2, doorZ, "door_frame");
            count(band, Role.FRAME);
        } else if (wanted > 1 && d >= 8 && !alongX) {
            placeDoor(band, doorX, doorY, doorZ + 2, doorSide, "right");
            for (int y = doorY; y <= doorY + 1; y++) {
                this.set(doorX, y, doorZ + 1, "door_frame");
                count(band, Role.FRAME);
                this.set(doorX, y, doorZ + 3, "door_frame");
                count(band, Role.FRAME);
            }
            this.set(doorX, doorY + 2, doorZ + 2, "door_frame");
            count(band, Role.FRAME);
        }

        // ---- the PORCH (2026-10 fix) ---------------------------------------------------------------
        // The bug this replaces: exactly one stair block outside the door, whose `facing` came from the
        // palette default (`north`). Every door on the north, east or west wall therefore got a crooked
        // step, and there was no porch at all. The rule now:
        //   * the run is ALWAYS axis-aligned with the door and centred on the doorway: the door's own cell
        //     plus one either side, or exactly the two door cells of the pair (with the mullion between);
        //   * every stair ASCENDS toward the door (`facing` = the direction from the step back to the
        //     door), and is written with half=bottom, shape=straight so the cells join cleanly;
        //   * the run steps DOWN one block per row away from the sill and stops the moment a row would
        //     have to cut into the ground or float - so the doorstep of this preset is the single row at
        //     the sill's level that bridges street (1.0) -> stair (1.5) -> sill (2.0);
        //   * it is placed only on air with something solid below, so it never blocks the doorway's two
        //     passage cells and never floats.
        int outX = doorSide.equals("west") ? -1 : doorSide.equals("east") ? 1 : 0;
        int outZ = doorSide.equals("north") ? -1 : doorSide.equals("south") ? 1 : 0;
        String stepFacing = outZ > 0 ? "north" : outZ < 0 ? "south" : outX > 0 ? "west" : "east";
        boolean pair = wanted > 1 && ((alongX && w >= 8) || (!alongX && d >= 8));
        int spanFrom = alongX ? (pair ? doorX : doorX - 1) : (pair ? doorZ : doorZ - 1);
        int spanTo = alongX ? (pair ? doorX + 2 : doorX + 1) : (pair ? doorZ + 2 : doorZ + 1);
        int porchRows = 0;
        for (int row = 1; row <= 3; row++) {
            int py = doorY - row;
            boolean any = false;
            for (int offset = spanFrom; offset <= spanTo; offset++) {
                int px = alongX ? offset : doorX + outX * row;
                int pz = alongX ? doorZ + outZ * row : offset;
                if (!this.cell(px, py, pz).equals("air")) {
                    continue;
                }
                if (this.cell(px, py - 1, pz).equals("air")) {
                    continue;                       // never a floating step
                }
                place(band, px, py, pz, "step|facing=" + stepFacing, Role.STEP);
                any = true;
                this.porchStairs++;
            }
            if (!any) {
                break;
            }
            porchRows++;
        }
        if (porchRows > 0) {
            this.porchEntrances++;
        }
        place(band, doorX, 1, doorZ, "floor_inner", Role.FLOOR);
    }

    /** Places both halves of a door and records it for the self test's frame check. */
    private void placeDoor(String band, int x, int y, int z, String facing, String hinge) {
        this.set(x, y, z, "door|facing=" + facing + ",half=lower,hinge=" + hinge);
        this.set(x, y + 1, z, "door|facing=" + facing + ",half=upper,hinge=" + hinge);
        this.doors.add(new int[]{x, y, z});
        this.doorCells.add(new int[]{x, y, z});
        this.doorFacings.add(facing);
        this.doorHinges.add(hinge);
        count(band, Role.DOOR);
        count(band, Role.DOOR);
        this.interiorDoors++;
    }

    /** Rough "a bomb hit it": clear a quadrant of the top floors and scatter rubble on the slab. */
    private void collapseQuadrant(int x0, int z0, int w, int d, int fromY, int toY) {
        int halfX = x0 + w / 2;
        int halfZ = z0 + d / 2;
        for (int y = Math.max(1, fromY); y <= toY; y++) {
            for (int x = halfX; x < x0 + w; x++) {
                for (int z = halfZ; z < z0 + d; z++) {
                    this.set(x, y, z, "air");
                }
            }
        }
        for (int i = 0; i < 18; i++) {
            int x = x0 + 1 + (i * 7) % Math.max(1, w - 2);
            int z = z0 + 1 + (i * 11) % Math.max(1, d - 2);
            this.set(x, Math.max(1, fromY), z, "cover_rubble");
            count("ruins", Role.COVER);
            if (i % 3 == 0) {
                this.set(x, Math.max(1, fromY) + 1, z, "cover_rubble_top");
                count("ruins", Role.COVER);
            }
        }
    }

    /**
     * Takes down lights the collapse orphaned: a hanging chain or lantern with air above it, or an
     * end rod with air below it, is a block the game itself would drop on the next neighbour update.
     * Runs from the top of the ruin downwards, so a chain clears before the lantern hanging on it.
     */
    private void sweepDetachedLights(int x0, int z0, int w, int d, int fromY, int toY) {
        for (int y = toY; y >= fromY; y--) {
            for (int x = x0; x < x0 + w; x++) {
                for (int z = z0; z < z0 + d; z++) {
                    String state = this.cell(x, y, z);
                    if (state.equals("air")) {
                        continue;
                    }
                    String key = this.baseKeyOfState.getOrDefault(state, state);
                    boolean detached = switch (key) {
                        case "light_chain", "light_lantern_hang" -> this.cell(x, y + 1, z).equals("air");
                        case "light_end_rod" -> this.cell(x, y - 1, z).equals("air");
                        default -> false;
                    };
                    if (detached) {
                        this.set(x, y, z, "air");
                    }
                }
            }
        }
    }

    /** A street piece from the layout's {@code cover} list. */
    private void streetPiece(Map<String, Object> piece) {
        String type = Json.string(piece, "type", "rubble");
        int x = Json.intOf(piece, "x", 0);
        int z = Json.intOf(piece, "z", 0);
        // "level" is opt-in: absent means the street plane (y = 1), exactly as before.
        int base = coverBaseY(piece);
        switch (type) {
            case "low_wall" -> this.lowWall(base, x, z, Json.intOf(piece, "length", 4),
                    Json.string(piece, "axis", "x"));
            case "barrier" -> this.barrier(base, x, z, Json.intOf(piece, "length", 3),
                    Json.string(piece, "axis", "x"));
            case "car" -> this.car(base, x, z);
            case "crate" -> {
                this.set(x, base, z, "cover_crate");
                this.set(x, base + 1, z, "cover_crate_top");
            }
            case "sandbag" -> this.sandbag(base, x, z, Json.intOf(piece, "length", 3),
                    Json.string(piece, "axis", "x"));
            case "rubble" -> this.rubble(base, x, z, Json.intOf(piece, "radius", 2));
            default -> throw new IllegalArgumentException("unknown cover type: " + type);
        }
    }

    /**
     * The Y a street cover entry starts on. Absent or {@code "ground"} is the street plane, y = 1, which
     * is what every layout before this one relied on; an integer is a floor index (the layout's top-level
     * {@code floor_height} times the index, above the street plane); {@code "roof"} is one above the
     * tallest roof slab in the layout.
     */
    private int coverBaseY(Map<String, Object> piece) {
        Object level = piece.get("level");
        if (level == null) {
            return 1;
        }
        if (level instanceof Number number) {
            return 1 + number.intValue() * Json.intOf(this.layoutFlagOwner, "floor_height", 4);
        }
        String text = String.valueOf(level);
        if (text.equals("ground")) {
            return 1;
        }
        if (text.equals("roof")) {
            return this.streetRoofY;
        }
        throw new IllegalArgumentException("unknown cover level '" + text + "'");
    }

    private void lowWall(int base, int x, int z, int length, String axis) {
        for (int i = 0; i < length; i++) {
            int cx = axis.equals("x") ? x + i : x;
            int cz = axis.equals("x") ? z : z + i;
            this.set(cx, base, cz, "cover_wall");
            count("street", Role.COVER);
        }
    }

    private void barrier(int base, int x, int z, int length, String axis) {
        for (int i = 0; i < length; i++) {
            int cx = axis.equals("x") ? x + i : x;
            int cz = axis.equals("x") ? z : z + i;
            this.set(cx, base, cz, "cover_barrier");
            this.set(cx, base + 1, cz, "cover_barrier");
            count("street", Role.COVER);
            count("street", Role.COVER);
        }
    }

    private void sandbag(int base, int x, int z, int length, String axis) {
        for (int i = 0; i < length; i++) {
            int cx = axis.equals("x") ? x + i : x;
            int cz = axis.equals("x") ? z : z + i;
            this.set(cx, base, cz, "cover_sandbag");
            this.set(cx, base + 1, cz, "cover_sandbag_top");
            count("street", Role.COVER);
            count("street", Role.COVER);
        }
    }

    /** A burnt-out car: solid body with a glass band and a missing roof - instant street cover. */
    private void car(int base, int x, int z) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                this.set(x + dx, base, z + dz, "cover_car");
                count("street", Role.COVER);
            }
        }
        for (int dz = 0; dz < 4; dz++) {
            this.set(x, base + 1, z + dz, "cover_car");
            count("street", Role.COVER);
            this.set(x + 1, base + 1, z + dz, "cover_car_glass");
            count("street", Role.COVER);
        }
    }

    private void rubble(int base, int centreX, int centreZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = Math.abs(dx) + Math.abs(dz);
                if (distance > radius) {
                    continue;
                }
                int x = centreX + dx;
                int z = centreZ + dz;
                this.set(x, base, z, "cover_rubble");
                count("street", Role.COVER);
                if (distance <= radius - 1 && (x + z) % 3 == 0) {
                    this.set(x, base + 1, z, "cover_rubble_top");
                    count("street", Role.COVER);
                }
            }
        }
    }

    // ------------------------------------------------------------------ usable cover lines

    /**
     * One floor's cover-line plan: the interior room boxes, the Y band to prove the lines in, the XZ box
     * the metric may look at, and the cells the ladder shaft needs kept clear.
     */
    private static final class LinePlan {
        final String band;
        final List<int[]> rooms;
        final int yLow;
        final int yHigh;
        final int boxX0;
        final int boxZ0;
        final int boxX1;
        final int boxZ1;
        final int shaftX;
        final int shaftZ;
        final int seed;
        final int wanted;
        final int minLength;
        final int maxLength;
        final List<String> kinds;

        LinePlan(String band, List<int[]> rooms, int yLow, int yHigh, int boxX0, int boxZ0, int boxX1,
                 int boxZ1, int shaftX, int shaftZ, int seed, int wanted, int minLength, int maxLength,
                 List<String> kinds) {
            this.band = band;
            this.rooms = rooms;
            this.yLow = yLow;
            this.yHigh = yHigh;
            this.boxX0 = boxX0;
            this.boxZ0 = boxZ0;
            this.boxX1 = boxX1;
            this.boxZ1 = boxZ1;
            this.shaftX = shaftX;
            this.shaftZ = shaftZ;
            this.seed = seed;
            this.wanted = wanted;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.kinds = kinds;
        }
    }

    /** The cover-line plans of the building being built right now. */
    private final List<LinePlan> linePlans = new ArrayList<>();

    /**
     * The block kinds a line is built from when a layout does not name {@code cover_line_blocks}. The
     * first one is the intended "sandbag line": a run of the white_concrete sandbag base with the white
     * carpet course on top of it. The other three are the families that count as solid low cover under
     * the strict reading of the metric as well (a cobblestone wall, an oak slab, a barrel).
     */
    private static final List<String> DEFAULT_COVER_LINE_BLOCKS =
            List.of("cover_sandbag", "cover_wall", "cover_plank", "cover_crate");

    /**
     * How one line-capable palette key is laid along the ground: the column of keys from the floor
     * upwards. Only the sandbag gets a second course (its carpet top); a barrel or a slab line is one
     * block tall, so the block above every cell stays passable and the whole run counts as "exposed".
     */
    private static final Map<String, String[]> LINE_COLUMNS = Map.of(
            "cover_sandbag", new String[]{"cover_sandbag", "cover_sandbag_top"},
            "cover_barrier", new String[]{"cover_barrier"},
            "cover_wall", new String[]{"cover_wall"},
            "cover_plank", new String[]{"cover_plank"},
            "cover_crate", new String[]{"cover_crate"},
            "cover_rubble", new String[]{"cover_rubble"},
            "cover_rubble_top", new String[]{"cover_rubble_top"});

    // ---- the acceptance metric, variant V4, ported from tools/spike/work/citymap/ObstacleScan.java.
    // A USABLE LOW-COVER LINE is a MAXIMAL run of >= 3 consecutive cells at one Y, along X or along Z,
    // where every cell is (a) opaque solid low cover roughly 0.5-1.5 blocks tall, (b) EXPOSED - the block
    // directly above is passable, so it is not the bottom course of a wall - and (c) next to at least one
    // WALKABLE cell at the same Y. X and Z are counted separately. The one deliberate addition to the
    // reference set is white_concrete: in this palette it is only ever the base of the sandbag pair, and
    // the brief that approved this feature names that pair as low cover.

    private static final Set<String> METRIC_LOW = Set.of(
            "hay_block", "cauldron", "water_cauldron", "lava_cauldron", "composter", "snow",
            "barrel", "chest", "trapped_chest", "ender_chest", "flower_pot", "lectern", "anvil",
            "brewing_stand", "bell", "campfire", "soul_campfire", "honey_block", "slime_block",
            "dirt_path", "farmland", "stonecutter", "grindstone", "smithing_table",
            "cartography_table", "fletching_table", "loom", "crafting_table", "furnace",
            "blast_furnace", "smoker", "bookshelf", "chiseled_bookshelf", "white_concrete");

    private static final Set<String> METRIC_PASSABLE = Set.of(
            "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch",
            "redstone_wall_torch", "grass", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "vine", "vines", "glow_lichen", "seagrass",
            "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "rail", "powered_rail",
            "detector_rail", "activator_rail", "tripwire", "string", "lily_pad", "water",
            "bubble_column", "ladder", "scaffolding", "chain", "cobweb", "flower_pot", "lantern",
            "soul_lantern", "item_frame", "armor_stand");

    /** True when a block name (no namespace) lets a body through. */
    static boolean metricPassable(String id) {
        if (id.equals("?") || id.endsWith("air")) {
            return true;
        }
        if (METRIC_PASSABLE.contains(id) || id.endsWith("_carpet")) {
            return true;
        }
        if (id.endsWith("_button") || id.endsWith("_pressure_plate") || id.endsWith("_sign")
                || id.endsWith("_banner") || id.endsWith("_sapling") || id.endsWith("_flower")) {
            return true;
        }
        return id.endsWith("_door") || id.endsWith("_fence_gate") || id.endsWith("_trapdoor");
    }

    /** True for opaque solid low cover: roughly 0.5 to 1.5 blocks tall, no torso-sized gap. */
    static boolean metricSolidLow(String id) {
        if (id.isEmpty() || id.equals("?") || id.endsWith("air") || metricPassable(id)) {
            return false;
        }
        if (id.endsWith("_carpet") || id.endsWith("_bed")) {
            return false;
        }
        if (id.equals("glass_pane") || id.endsWith("_pane") || id.endsWith("_stained_glass")
                || id.equals("iron_bars")) {
            return false;
        }
        return METRIC_LOW.contains(id) || id.endsWith("_slab") || id.endsWith("_stairs")
                || id.endsWith("_wall") || id.endsWith("_fence");
    }

    /** The block name (namespace and properties stripped) of one internal state key. */
    private String blockNameOf(String state) {
        Map<String, Object> entry = this.statePalette.get(state);
        String name = entry == null ? state : String.valueOf(entry.get("Name"));
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        int bracket = name.indexOf('[');
        return bracket >= 0 ? name.substring(0, bracket) : name;
    }

    /** A cell a player can stand in: passable, with headroom, standing on something solid. */
    private boolean metricWalkable(int x, int y, int z) {
        if (!metricPassable(this.blockNameOf(this.cell(x, y, z)))
                || !metricPassable(this.blockNameOf(this.cell(x, y + 1, z)))) {
            return false;
        }
        String below = this.blockNameOf(this.cell(x, y - 1, z));
        return !below.equals("?") && !below.endsWith("air") && !metricPassable(below);
    }

    /** One cell of a usable (V4) cover line. */
    private boolean metricCoverCell(int x, int y, int z) {
        if (!metricSolidLow(this.blockNameOf(this.cell(x, y, z)))
                || !metricPassable(this.blockNameOf(this.cell(x, y + 1, z)))) {
            return false;
        }
        return metricWalkable(x - 1, y, z) || metricWalkable(x + 1, y, z)
                || metricWalkable(x, y, z - 1) || metricWalkable(x, y, z + 1);
    }

    /**
     * {lines, blocksInLines} for the usable cover lines whose Y is in {@code [yFrom..yTo]} and whose
     * cells are inside the given XZ box. X and Z runs are counted separately, exactly like the reference
     * implementation's coverLines(variant 4).
     */
    private int[] countUsableLines(int yFrom, int yTo, int xFrom, int zFrom, int xTo, int zTo) {
        int lines = 0;
        int blocks = 0;
        for (int y = Math.max(0, yFrom); y <= Math.min(this.sizeY - 1, yTo); y++) {
            for (int z = Math.max(0, zFrom); z <= Math.min(this.sizeZ - 1, zTo); z++) {
                int run = 0;
                for (int x = Math.max(0, xFrom); x <= Math.min(this.sizeX, xTo); x++) {
                    boolean hit = x <= Math.min(this.sizeX - 1, xTo) && this.metricCoverCell(x, y, z);
                    if (hit) {
                        run++;
                    } else {
                        if (run >= 3) {
                            lines++;
                            blocks += run;
                        }
                        run = 0;
                    }
                }
            }
            for (int x = Math.max(0, xFrom); x <= Math.min(this.sizeX - 1, xTo); x++) {
                int run = 0;
                for (int z = Math.max(0, zFrom); z <= Math.min(this.sizeZ, zTo); z++) {
                    boolean hit = z <= Math.min(this.sizeZ - 1, zTo) && this.metricCoverCell(x, y, z);
                    if (hit) {
                        run++;
                    } else {
                        if (run >= 3) {
                            lines++;
                            blocks += run;
                        }
                        run = 0;
                    }
                }
            }
        }
        return new int[]{lines, blocks};
    }

    /** The cells kept clear around the ladder shaft: a cover line must never seal the way up. */
    private static boolean isShaftCell(int x, int y, int z, int shaftX, int shaftZ) {
        if (y < 1) {
            return false;
        }
        return (x == shaftX && z == shaftZ) || (x == shaftX && z == shaftZ + 1)
                || (x == shaftX + 1 && z == shaftZ);
    }

    /**
     * The longest contiguous span of one line inside a room edge or centre line: cells that are still
     * air, are not needed by the ladder shaft, and have a WALKABLE neighbour on the perpendicular side.
     * A run placed on such a span is usable by construction - every cell ends up with a passable block
     * above it and somebody able to stand next to it - which is what the acceptance metric asks for.
     *
     * @return {first index of the span, length}; length is 0 when no cell qualifies
     */
    private int[] usableSpan(boolean alongX, int from, int to, int fixed, int y, int shaftX, int shaftZ) {
        int bestStart = -1;
        int bestLength = 0;
        int runStart = -1;
        int runLength = 0;
        for (int i = from; i <= to; i++) {
            int x = alongX ? i : fixed;
            int z = alongX ? fixed : i;
            boolean sideways = alongX
                    ? (this.metricWalkable(x, y, z - 1) || this.metricWalkable(x, y, z + 1))
                    : (this.metricWalkable(x - 1, y, z) || this.metricWalkable(x + 1, y, z));
            boolean ok = this.cell(x, y, z).equals("air")
                    && !isShaftCell(x, y, z, shaftX, shaftZ) && sideways
                    // A floor hole's landing: the cell above a hole is air but NOT walkable (nothing solid
                    // under it), and a cover line placed on it would plug the vertical connection the hole
                    // exists for - so the line builder treats the whole hole column as occupied.
                    && !this.holeClearance.contains(this.index(x, y, z))
                    // ... and a doorway's passage, which the doorway repair clears later.
                    && (this.passageClear == null || !this.passageClear[this.index(x, y, z)]);
            if (ok) {
                if (runLength == 0) {
                    runStart = i;
                }
                runLength++;
                if (runLength > bestLength) {
                    bestLength = runLength;
                    bestStart = runStart;
                }
            } else {
                runLength = 0;
            }
        }
        return new int[]{bestStart, bestLength};
    }

    /**
     * Places at least {@code wanted} USABLE cover lines at {@code yLow}, and PROVES each one before
     * keeping it: the longest usable span of the chosen slot is filled, the acceptance metric is
     * recounted, and the cells are reverted to air unless the number of usable lines really went up. A
     * line therefore cannot be silently embedded in a wall, put under a window pane, or merged into an
     * existing run - and a placement that would ROB an existing line of its walkable neighbour (which is
     * how a blind edge placement can destroy a line) is rejected too.
     *
     * <p>Candidate slots are the interior room walls (both X edges, both Z edges) and the two centre
     * lines of every room - the places a player would actually use: along a room wall, across a doorway,
     * at a window sill, at a stairwell landing.</p>
     */
    private int[] placeCoverLines(String band, List<int[]> rooms, int yLow, int yHigh, int wanted,
                                int minLength, int maxLength, List<String> kinds, int shaftX, int shaftZ,
                                int seed, int boxX0, int boxZ0, int boxX1, int boxZ1) {
        if (wanted <= 0 || rooms.isEmpty() || kinds.isEmpty()) {
            return new int[]{0, 0, 0, 0, 0};
        }
        List<String> usable = new ArrayList<>();
        for (String kind : kinds) {
            if (kind.isEmpty() || kind.equals("air")) {
                continue;
            }
            if (!this.palette.containsKey(kind)) {
                throw new IllegalArgumentException("cover_line_blocks names '" + kind
                        + "', which is not a palette key");
            }
            usable.add(kind);
        }
        if (usable.isEmpty()) {
            return new int[]{0, 0, 0, 0, 0};
        }
        java.util.Random random = new java.util.Random(seed * 131L + 17L);

        List<int[]> slots = new ArrayList<>();
        for (int[] room : rooms) {
            int rx0 = room[0];
            int rz0 = room[1];
            int rx1 = room[2];
            int rz1 = room[3];
            int midX = (rx0 + rx1) / 2;
            int midZ = (rz0 + rz1) / 2;
            // EVERY row and every column of the room, not only its edges and centre lines. The interior
            // rows and columns are what makes the layout robust: a doorway or an extra opening poisons the
            // three cells of the wall it sits in, which can kill the room's own edge slot - but never an
            // interior lane one cell further in.
            for (int x = rx0; x <= rx1; x++) {
                slots.add(new int[]{x, rz0, x, rz1, 1});
            }
            for (int z = rz0; z <= rz1; z++) {
                slots.add(new int[]{rx0, z, rx1, z, 0});
            }
            slots.add(new int[]{rx0, midZ, rx1, midZ, 0});
            slots.add(new int[]{midX, rz0, midX, rz1, 1});
        }

        int placed = 0;
        int kindAt = 0;
        int blockedCount = 0;
        int mergedCount = 0;
        int shortCount = 0;
        int attempts = 0;
        // Several passes, each starting at a different slot, so one unlucky room cannot stop a floor.
        for (int pass = 0; pass < 4 && placed < wanted; pass++) {
            for (int s = 0; s < slots.size() && placed < wanted; s++) {
                int[] slot = slots.get((s + pass * 5) % slots.size());
                attempts++;
                boolean alongX = slot[4] == 0;
                int from = alongX ? slot[0] : slot[1];
                int to = alongX ? slot[2] : slot[3];
                int fixed = alongX ? slot[1] : slot[0];
                int[] span = usableSpan(alongX, from, to, fixed, yLow, shaftX, shaftZ);
                if (span[1] < minLength) {
                    shortCount++;
                    continue;
                }
                int length = Math.min(maxLength, span[1]);
                int start = span[0] + random.nextInt(span[1] - length + 1);
                String kind = usable.get(kindAt % usable.size());
                String[] column = LINE_COLUMNS.getOrDefault(kind, new String[]{kind});

                List<int[]> cells = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    int x = alongX ? start + i : fixed;
                    int z = alongX ? fixed : start + i;
                    for (int layer = 0; layer < column.length; layer++) {
                        cells.add(new int[]{x, yLow + layer, z});
                        keys.add(column[layer]);
                    }
                }
                boolean blocked = false;
                for (int[] cell : cells) {
                    String state = this.cell(cell[0], cell[1], cell[2]);
                    if (state == null || !state.equals("air")) {
                        blocked = true;
                        break;
                    }
                }
                if (blocked) {
                    blockedCount++;
                    continue;
                }
                int before = countUsableLines(yLow, yHigh, boxX0, boxZ0, boxX1, boxZ1)[0];
                for (int i = 0; i < cells.size(); i++) {
                    int[] cell = cells.get(i);
                    this.set(cell[0], cell[1], cell[2], keys.get(i));
                }
                int after = countUsableLines(yLow, yHigh, boxX0, boxZ0, boxX1, boxZ1)[0];
                if (after > before) {
                    placed++;
                    kindAt++;
                    for (int i = 0; i < cells.size(); i++) {
                        count(band, Role.COVER);
                    }
                } else {
                    mergedCount++;
                    for (int[] cell : cells) {
                        this.set(cell[0], cell[1], cell[2], "air");
                    }
                }
            }
        }
        return new int[]{placed, blockedCount, mergedCount, shortCount, attempts};
    }

    /** The same builder, on the roof: four quadrant "rooms" inside the parapet, the ladder hole kept clear. */
    private static List<int[]> roofQuadrants(int x0, int z0, int w, int d, int minLength) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int midX = (x0 + x1) / 2;
        int midZ = (z0 + z1) / 2;
        List<int[]> rooms = new ArrayList<>();
        for (int[] xs : new int[][]{{x0 + 1, midX}, {midX + 1, x1 - 1}}) {
            for (int[] zs : new int[][]{{z0 + 1, midZ}, {midZ + 1, z1 - 1}}) {
                if (xs[1] - xs[0] + 1 >= minLength && zs[1] - zs[0] + 1 >= 2) {
                    rooms.add(new int[]{xs[0], zs[0], xs[1], zs[1]});
                }
            }
        }
        return rooms;
    }

    // ------------------------------------------------------------------ grid helpers

    private void count(String band, Role role) {
        this.floorCounts.computeIfAbsent(band, key -> new TreeMap<>())
                .merge(role, 1, Integer::sum);
    }

    private Map<Role, Integer> roles(String band) {
        return this.floorCounts.getOrDefault(band, Map.of());
    }

    /** Sets a cell, replacing whatever is there (structure: walls, slabs, doors). */
    private void set(int x, int y, int z, String key) {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
            return;
        }
        String state = stateKey(key);
        this.cells[this.index(x, y, z)] = state;
    }

    /** Sets a cell only when the current block is air; returns whether it landed. */
    private boolean setIfAir(int x, int y, int z, String key) {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
            return false;
        }
        if (!this.cells[this.index(x, y, z)].equals("air")) {
            return false;
        }
        set(x, y, z, key);
        return true;
    }

    /**
     * Resolves a layout key (optionally with {@code |prop=value,...} overrides) into a palette state,
     * registering a palette entry the first time that exact state is used.
     */
    private String stateKey(String key) {
        if (key.equals("air")) {
            return "air";
        }
        String baseKey = key;
        String overrides = "";
        int separator = key.indexOf('|');
        if (separator >= 0) {
            baseKey = key.substring(0, separator);
            overrides = key.substring(separator + 1);
        }
        String state = overrides.isEmpty() ? baseKey : baseKey + "|" + overrides;
        if (this.statePalette.containsKey(state)) {
            return state;
        }
        Map<String, Object> base = this.palette.get(baseKey);
        if (base == null) {
            throw new IllegalArgumentException("block key '" + baseKey + "' is not in the palette");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("Name", base.get("Name"));
        Object role = base.get("_role");
        if (role != null) {
            resolved.put("_role", role);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (base.get("Properties") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> property : declared.entrySet()) {
                properties.put(String.valueOf(property.getKey()), String.valueOf(property.getValue()));
            }
        }
        for (String override : overrides.split(",")) {
            if (override.isBlank()) {
                continue;
            }
            String[] pair = override.split("=", 2);
            properties.put(pair[0].trim(), pair.length > 1 ? pair[1].trim() : "");
        }
        if (!properties.isEmpty()) {
            resolved.put("Properties", properties);
        }
        this.statePalette.put(state, resolved);
        this.baseKeyOfState.put(state, baseKey);
        this.stateOrder.add(state);
        return state;
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String key) {
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    this.set(x, y, z, key);
                }
            }
        }
    }

    private int index(int x, int y, int z) {
        return (y * this.sizeZ + z) * this.sizeX + x;
    }

    /**
     * The cells every doorway and every interior opening needs free - the passage cell and the two cells
     * beyond it at both head heights, exactly the set {@link #clearPassage} clears. Consulted by the cover-line
     * builder so a line can never be placed where the doorway repair would delete it a moment later (which is
     * how a floor could end up below its usable-line minimum).
     */
    private boolean[] passageClear;

    private void markPassages() {
        this.passageClear = new boolean[this.sizeX * this.sizeY * this.sizeZ];
        for (int i = 0; i < this.doorCells.size(); i++) {
            int[] door = this.doorCells.get(i);
            String facing = this.doorFacings.get(i);
            markPassage(door[0], door[1], door[2],
                    facing.equals("north") || facing.equals("south"));
        }
        for (int[] opening : this.openings) {
            markPassage(opening[0], opening[1], opening[2], opening[3] != 1);
        }
    }

    private void markPassage(int x, int y, int z, boolean alongZ) {
        markCell(x, y, z);
        markCell(x, y + 1, z);
        for (int side = -1; side <= 1; side += 2) {
            int sideX = alongZ ? 0 : side;
            int sideZ = alongZ ? side : 0;
            for (int dy = 0; dy <= 1; dy++) {
                markCell(x + sideX, y + dy, z + sideZ);
            }
        }
    }

    private void markCell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
            return;
        }
        this.passageClear[this.index(x, y, z)] = true;
    }

    /** The inverse of {@link #index(int, int, int)}: {x, y, z} of a grid index. */
    private int[] positionOfIndex(int index) {
        int x = index % this.sizeX;
        int rest = index / this.sizeX;
        int z = rest % this.sizeZ;
        int y = rest / this.sizeZ;
        return new int[]{x, y, z};
    }

    private String cell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
            return "air";
        }
        return this.cells[this.index(x, y, z)];
    }

    private long countSolid() {
        long count = 0;
        for (String cell : this.cells) {
            if (!cell.equals("air")) {
                count++;
            }
        }
        return count;
    }

    private int paletteIndex(String state) {
        int index = this.stateOrder.indexOf(state);
        if (index < 0) {
            throw new IllegalArgumentException("state '" + state + "' is not in the palette");
        }
        return index;
    }

    // ------------------------------------------------------------------ the floor report

    /** Prints, and asserts, the per-floor cover / furniture / light counts and cover-line counts. */
    private void printFloorReport(Map<String, Object> layout) {
        Map<String, String> minimums = new LinkedHashMap<>();
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            String buildingName = Json.string(building, "name", "building");
            int x0 = Json.intOf(building, "x", 0);
            int z0 = Json.intOf(building, "z", 0);
            int w = Json.intOf(building, "w", 8);
            int d = Json.intOf(building, "d", 8);
            int floors = Json.intOf(building, "floors", 3);
            int floorHeight = Json.intOf(building, "floor_height", 4);
            int minCover = Json.intOf(building, "min_cover_per_floor", 5);
            int minFurniture = Json.intOf(building, "min_furniture_per_floor", 6);
            int minCoverLines = Json.intOf(building, "min_cover_lines_per_floor", 0);
            int topY = 1 + floors * floorHeight;
            for (int floor = 0; floor < floors; floor++) {
                String band = buildingName + "/" + floor;
                int floorY = 1 + floor * floorHeight;
                Map<Role, Integer> counts = roles(band);
                int cover = counts.getOrDefault(Role.COVER, 0);
                int furniture = counts.getOrDefault(Role.FURNITURE, 0);
                int light = counts.getOrDefault(Role.LIGHT, 0);
                int[] lines = countUsableLines(floorY, floorY + floorHeight - 1, x0 - 1, z0 - 1,
                        x0 + w, z0 + d);
                boolean shortfall = cover < minCover || furniture < minFurniture
                        || lines[0] < minCoverLines;
                System.out.println("  floor " + band + ": cover=" + cover + " furniture=" + furniture
                        + " light=" + light + " usableLines=" + lines[0]
                        + " cellsInLines=" + lines[1]
                        + (shortfall ? "  <-- BELOW MINIMUM" : ""));
                if (shortfall) {
                    minimums.put(band, cover + "/" + furniture + "/" + lines[0]);
                }
            }
            if (minCoverLines > 0) {
                String band = buildingName + "/roof";
                int[] lines = countUsableLines(topY, topY + 2, x0 - 1, z0 - 1, x0 + w, z0 + d);
                System.out.println("  floor " + band + ": cover="
                        + roles(band).getOrDefault(Role.COVER, 0) + " usableLines=" + lines[0]
                        + " cellsInLines=" + lines[1]
                        + (lines[0] < minCoverLines ? "  <-- BELOW MINIMUM" : ""));
                if (lines[0] < minCoverLines) {
                    minimums.put(band, "lines=" + lines[0]);
                }
            }
        }
        int[] street = countUsableLines(0, this.sizeY - 1, 0, 0, this.sizeX - 1, this.sizeZ - 1);
        System.out.println("  street: " + roles("street") + "   ruins: " + roles("ruins"));
        System.out.println("  whole structure: usableLowCoverLines=" + street[0]
                + " cellsInLines=" + street[1]);
        if (!minimums.isEmpty()) {
            throw new IllegalStateException("floors below their declared minimum cover/furniture/lines: "
                    + minimums);
        }
    }

    // ------------------------------------------------------------------ output

    /**
     * Writes the building map the runtime uses to give EACH BUILDING its own faction
     * ({@code com.gfl.tarkovscav.world.CityBuildings} reads it back). One rectangle per layout building, in
     * template-local coordinates - exactly the {@code x/z/w/d} the generator itself builds the walls from,
     * so the map cannot drift from the structure next to it. The structure's {@code size} is included so the
     * reader can work out how a random jigsaw rotation maps local to world without guessing.
     *
     * <p>The file is deterministic: every field is written in a fixed order and there is no map iteration.</p>
     */
    private void writeBuildingMap(Path out, Map<String, Object> layout) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"structure\": \"").append(this.name).append("\",\n");
        builder.append("  \"size\": [").append(this.sizeX).append(", ").append(this.sizeY)
                .append(", ").append(this.sizeZ).append("],\n");
        builder.append("  \"buildings\": [\n");
        int index = 0;
        boolean first = true;
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            String buildingName = Json.string(building, "name", "building");
            int x = Json.intOf(building, "x", 0);
            int z = Json.intOf(building, "z", 0);
            int w = Json.intOf(building, "w", 0);
            int d = Json.intOf(building, "d", 0);
            if (w > 0 && d > 0) {
                if (!first) {
                    builder.append(",\n");
                }
                first = false;
                builder.append("    { \"id\": \"").append(index).append(':').append(buildingName)
                        .append("\", \"x\": ").append(x).append(", \"z\": ").append(z)
                        .append(", \"w\": ").append(w).append(", \"d\": ").append(d).append(" }");
            }
            index++;
        }
        builder.append("\n  ]\n}\n");
        Files.createDirectories(out.getParent());
        Files.write(out, builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeNbt(Path out) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out))))) {
            data.writeByte(10);            // root: TAG_Compound
            data.writeUTF("");             // empty root name
            data.writeByte(3);             // DataVersion: TAG_Int
            data.writeUTF("DataVersion");
            data.writeInt(DATA_VERSION);

            data.writeByte(9);             // size: TAG_List of TAG_Int
            data.writeUTF("size");
            data.writeByte(3);
            data.writeInt(3);
            data.writeInt(this.sizeX);
            data.writeInt(this.sizeY);
            data.writeInt(this.sizeZ);

            data.writeByte(9);             // palette: TAG_List of TAG_Compound
            data.writeUTF("palette");
            data.writeByte(10);
            data.writeInt(this.stateOrder.size());
            for (String state : this.stateOrder) {
                writePaletteEntry(data, this.statePalette.get(state));
            }

            data.writeByte(9);             // blocks: TAG_List of TAG_Compound
            data.writeUTF("blocks");
            data.writeByte(10);
            data.writeInt(this.sizeX * this.sizeY * this.sizeZ);
            for (int y = 0; y < this.sizeY; y++) {
                for (int z = 0; z < this.sizeZ; z++) {
                    for (int x = 0; x < this.sizeX; x++) {
                        String state = this.cells[this.index(x, y, z)];
                        // NOTE: `pos` has to be a TAG_List of TAG_Int. StructureTemplate.load reads
                        // it with getList("pos", 3) - a TAG_Int_Array would silently not load.
                        data.writeByte(9);     // pos: TAG_List
                        data.writeUTF("pos");
                        data.writeByte(3);     // ... of TAG_Int
                        data.writeInt(3);
                        data.writeInt(x);
                        data.writeInt(y);
                        data.writeInt(z);
                        data.writeByte(3);     // state: TAG_Int
                        data.writeUTF("state");
                        data.writeInt(paletteIndex(state));
                        // A block entity (spawner / chest): the same block compound carries an `nbt`
                        // compound, which is where its SpawnData / LootTable lives.
                        Map<String, Object> blockEntity = this.blockEntities.get(this.index(x, y, z));
                        if (blockEntity != null) {
                            data.writeByte(10);
                            data.writeUTF("nbt");
                            // writeTags writes the compound's own closing TAG_End.
                            writeTags(data, blockEntity);
                        }
                        data.writeByte(0);     // TAG_End of this block compound
                    }
                }
            }

            data.writeByte(9);             // entities: TAG_List of TAG_Compound (empty)
            data.writeUTF("entities");
            data.writeByte(10);
            data.writeInt(0);

            data.writeByte(0);             // TAG_End of the root compound
        }
    }

    private static void writePaletteEntry(DataOutputStream data, Map<String, Object> entry) throws IOException {
        data.writeByte(8);                 // Name: TAG_String
        data.writeUTF("Name");
        data.writeUTF((String) entry.get("Name"));

        Object properties = entry.get("Properties");
        if (properties instanceof Map<?, ?> map && !map.isEmpty()) {
            data.writeByte(10);            // Properties: TAG_Compound
            data.writeUTF("Properties");
            for (Map.Entry<?, ?> property : map.entrySet()) {
                data.writeByte(8);
                data.writeUTF(String.valueOf(property.getKey()));
                data.writeUTF(String.valueOf(property.getValue()));
            }
            data.writeByte(0);
        }
        data.writeByte(0);                 // TAG_End of the palette entry
    }

    /**
     * The same city as Minecraft commands, greedily merged into rectangles per layer - a floor slab
     * becomes one {@code /fill} instead of forty, which matters a lot because every command is a
     * separate RCON round trip. Air is skipped: the platform is already empty.
     */
    private int writeCommands(Path out) throws IOException {
        List<String> commands = new ArrayList<>();
        boolean[] used = new boolean[this.sizeX * this.sizeZ];

        for (int y = 0; y < this.sizeY; y++) {
            java.util.Arrays.fill(used, false);
            for (int z = 0; z < this.sizeZ; z++) {
                for (int x = 0; x < this.sizeX; x++) {
                    if (used[z * this.sizeX + x]) {
                        continue;
                    }
                    String key = this.cells[this.index(x, y, z)];
                    if (key.equals("air")) {
                        used[z * this.sizeX + x] = true;
                        continue;
                    }

                    int width = 1;
                    while (x + width < this.sizeX
                            && !used[z * this.sizeX + x + width]
                            && this.cells[this.index(x + width, y, z)].equals(key)) {
                        width++;
                    }

                    int depth = 1;
                    boolean growing = true;
                    while (growing && z + depth < this.sizeZ) {
                        for (int i = 0; i < width; i++) {
                            if (used[(z + depth) * this.sizeX + x + i]
                                    || !this.cells[this.index(x + i, y, z + depth)].equals(key)) {
                                growing = false;
                                break;
                            }
                        }
                        if (growing) {
                            depth++;
                        }
                    }

                    for (int dz = 0; dz < depth; dz++) {
                        for (int dx = 0; dx < width; dx++) {
                            used[(z + dz) * this.sizeX + x + dx] = true;
                        }
                    }

                    commands.add(command(x, x + width - 1, y, z, z + depth - 1, key));
                }
            }
        }
        // A spawner and a chest are BLOCK ENTITIES: /setblock places a blank block, so the sandbox build
        // would show an empty spawner and an empty chest. `/data merge block` is the only vanilla command
        // that can fill one in, and tools/build_city.ps1 shifts its coordinates exactly like a setblock's.
        for (Map.Entry<Integer, Map<String, Object>> entry : this.blockEntities.entrySet()) {
            int[] pos = this.positionOfIndex(entry.getKey());
            commands.add("/data merge block " + pos[0] + " " + pos[1] + " " + pos[2] + " "
                    + snbt(entry.getValue()));
        }
        Files.write(out, commands, StandardCharsets.UTF_8);
        return commands.size();
    }

    /** One compound as SNBT - the argument {@code /data merge block} takes. */
    private static String snbt(Map<String, Object> compound) {
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : compound.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(entry.getKey()).append(':').append(snbtValue(entry.getValue()));
        }
        return text.append('}').toString();
    }

    private static String snbtValue(Object value) {
        if (value instanceof String string) {
            return "\"" + string + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> child : map.entrySet()) {
                typed.put(String.valueOf(child.getKey()), child.getValue());
            }
            return snbt(typed);
        }
        if (value instanceof List<?> list) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(snbtValue(list.get(i)));
            }
            return text.append(']').toString();
        }
        if (value instanceof Short || value instanceof Byte) {
            return value + "s";
        }
        return String.valueOf(value);
    }

    /**
     * The flat sandbox the city is laid out on, as commands in the city's own coordinate space: a stone
     * base, one smooth stone surface layer, and the air clear above it. {@code tools/build_city.ps1}
     * shifts these and the city commands to the requested origin and sends them over RCON.
     *
     * <p>Minecraft refuses a {@code /fill} above 32768 blocks ("Too many blocks in the specified area"),
     * and one column of the plot is 150 blocks tall, so the volume is emitted one <b>y layer at a
     * time</b> (and, if a layer is still too big, in x slices). A single big fill would silently do
     * nothing and the city would end up buried in the terrain.</p>
     */
    private int writePlatformCommands(Path out, int margin, int clearTo, int stoneBelow) throws IOException {
        List<String> commands = new ArrayList<>();
        int x0 = -margin;
        int z0 = -margin;
        int x1 = this.sizeX - 1 + margin;
        int z1 = this.sizeZ - 1 + margin;
        int maxFill = 32768;

        for (int y = -stoneBelow; y <= clearTo; y++) {
            String block = y == 0 ? "minecraft:smooth_stone"
                    : y > 0 ? "minecraft:air"
                    : "minecraft:stone";
            int width = x1 - x0 + 1;
            int depth = z1 - z0 + 1;
            if (width * depth <= maxFill) {
                commands.add("/fill " + x0 + " " + y + " " + z0 + " " + x1 + " " + y + " " + z1
                        + " " + block);
                continue;
            }
            // Split along x so every command stays inside the limit.
            int slice = Math.max(1, maxFill / depth);
            for (int x = x0; x <= x1; x += slice) {
                commands.add("/fill " + x + " " + y + " " + z0 + " " + Math.min(x1, x + slice - 1) + " "
                        + y + " " + z1 + " " + block);
            }
        }
        Files.write(out, commands, StandardCharsets.UTF_8);
        return commands.size();
    }

    private String command(int x1, int x2, int y, int z1, int z2, String state) {
        String block = stateString(this.statePalette.get(state));
        if (x1 == x2 && z1 == z2) {
            return "/setblock " + x1 + " " + y + " " + z1 + " " + block;
        }
        return "/fill " + x1 + " " + y + " " + z1 + " " + x2 + " " + y + " " + z2 + " " + block;
    }

    /** {@code minecraft:oak_slab[type=top]} - the same string a palette entry means. */
    @SuppressWarnings("unchecked")
    public static String stateString(Map<String, Object> entry) {
        String name = (String) entry.get("Name");
        Object properties = entry.get("Properties");
        if (!(properties instanceof Map<?, ?> map) || map.isEmpty()) {
            return name;
        }
        StringBuilder builder = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<?, ?> property : ((Map<String, Object>) map).entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        return builder.append(']').toString();
    }

    /** Kept for the old call sites: the block state string of a palette entry. */
    public static String blockStateString(Map<String, Object> entry) {
        return stateString(entry);
    }
}
