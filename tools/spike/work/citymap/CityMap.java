import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

/**
 * Part 3 of the city survey: top-down PNG maps of a city district (from a save) and of the shipped
 * generator templates (from structure NBT), drawn with the same palette and the same rules so the two
 * can be compared by eye.
 *
 * <p>Image labels are ASCII only on purpose: the JDK's default logical font has no CJK glyphs on this
 * machine in headless mode, and a map full of tofu boxes would be worse than an English legend. The
 * Chinese explanation lives in the report in docs (file name is CJK).</p>
 *
 * <pre>
 *   CityMap box       --world DIR --box x1 y1 z1 x2 y2 z2 --out PREFIX --title T [--scale N] [--slice N] [--ground Y]
 *   CityMap structure --nbt FILE --out FILE --title T [--scale N] [--slice N]
 *   CityMap pieces    --dir DIR --out FILE [--title T]
 *   CityMap compare   --world DIR --box x1 y1 z1 x2 y2 z2 --nbt FILE --out FILE [--scale N]
 *   CityMap fonts
 * </pre>
 *
 * Read-only with respect to the save and the mod sources.
 */
public final class CityMap {

    // ------------------------------------------------------------------ map palette

    static final int AIR = 0, TERRAIN = 1, WALL_LIGHT = 2, WALL_GRAY = 3, WALL_BLACK = 4,
            WALL_BROWN = 5, WALL_WHITE = 6, WALL_RED = 7, WALL_OTHER = 8, TERRACOTTA = 9,
            WOOD = 10, GLASS = 11, IRONBARS = 12, HALF = 13, STAIRS = 14, DOOR = 15,
            CONTAINER = 16, SPAWNER = 17, WATER = 18, LEAVES = 19, OTHER = 20;

    static final String[] LEGEND_LABEL = {
        "air / walkable", "terrain / ground", "wall light_gray_concrete", "wall gray_concrete",
        "wall black_concrete", "wall brown_concrete", "wall white_concrete", "wall red_concrete",
        "wall other concrete", "terracotta", "wood / planks / log", "glass (see-through)",
        "iron_bars (see-through)", "half-height / low cover", "stairs", "door",
        "container (chest/barrel)", "spawner", "water", "leaves", "unknown / other"};

    static final int[] LEGEND_COLOR = {
        0xE9E9E9, 0xD9C08C, 0xC8C8C8, 0x8A8A8A,
        0x2E2E2E, 0x6E4B33, 0xF2F2F2, 0xA33A32,
        0x9E9E9E, 0xB4693C, 0x8B5A2B, 0xA8DCF0,
        0x444444, 0xF0D030, 0x3AA83C, 0xE030D0,
        0x2438A8, 0xE02828, 0x2858E0, 0x1E5B1E, 0x000000};

    // height-map palette
    static final int H_OPEN = 0, H_HALF = 1, H_FULL1 = 2, H_FULL2 = 3, H_BUILD = 4,
            H_TERRAIN = 5, H_WATER = 6, H_UNKNOWN = 7;
    static final int[] H_COLOR = {0xEDEDED, 0xF0D030, 0xF0A030, 0xD06020, 0x8B2E2E,
        0xD9C08C, 0x2858E0, 0x000000};
    static final String[] H_LABEL = {"0 open ground / air", "1 half-height cover",
        "1 one-block full block", "2 two-block full cover", "3+ building / vertical",
        "natural terrain", "water", "unknown"};

    static int rgb(int v) {
        return 0xFF000000 | v;
    }

    /** Legend category of a block id (no namespace). */
    static int category(String id) {
        if (id.equals("?") || id.isEmpty()) {
            return OTHER;
        }
        if (id.endsWith("air")) {
            return AIR;
        }
        switch (id) {
            case "light_gray_concrete":
                return WALL_LIGHT;
            case "gray_concrete":
                return WALL_GRAY;
            case "black_concrete":
                return WALL_BLACK;
            case "brown_concrete":
                return WALL_BROWN;
            case "white_concrete":
                return WALL_WHITE;
            case "red_concrete":
                return WALL_RED;
            case "terracotta":
            case "white_terracotta":
            case "orange_terracotta":
            case "magenta_terracotta":
            case "light_blue_terracotta":
            case "yellow_terracotta":
            case "lime_terracotta":
            case "pink_terracotta":
            case "gray_terracotta":
            case "light_gray_terracotta":
            case "cyan_terracotta":
            case "purple_terracotta":
            case "blue_terracotta":
            case "brown_terracotta":
            case "green_terracotta":
            case "red_terracotta":
            case "black_terracotta":
                return TERRACOTTA;
            case "glass":
            case "tinted_glass":
                return GLASS;
            case "iron_bars":
                return IRONBARS;
            case "water":
            case "lava":
            case "bubble_column":
            case "ice":
            case "packed_ice":
            case "blue_ice":
                return WATER;
            case "barrel":
            case "chest":
            case "trapped_chest":
            case "ender_chest":
                return CONTAINER;
            case "spawner":
                return SPAWNER;
            default:
                break;
        }
        if (id.endsWith("_concrete")) {
            return WALL_OTHER;
        }
        if (id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane")
                || id.equals("glass_pane") || id.endsWith("_pane")) {
            return GLASS;
        }
        if (id.endsWith("_leaves")) {
            return LEAVES;
        }
        if (id.endsWith("_stairs")) {
            return STAIRS;
        }
        if (id.endsWith("_door") || id.endsWith("_trapdoor")) {
            return DOOR;
        }
        if (id.endsWith("_slab") || id.endsWith("_carpet") || id.endsWith("_wool")
                || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_wall")
                || id.equals("hay_block") || id.equals("cauldron") || id.equals("composter")
                || id.equals("snow") || id.equals("scaffolding") || id.equals("chain")
                || id.equals("cobweb") || id.equals("ladder") || id.equals("campfire")
                || id.equals("soul_campfire") || id.equals("lantern") || id.equals("soul_lantern")
                || id.equals("honey_block") || id.equals("slime_block") || id.equals("pointed_dripstone")
                || id.equals("dirt_path") || id.equals("farmland") || id.equals("flower_pot")
                || id.equals("lectern") || id.equals("anvil") || id.equals("bookshelf")
                || id.equals("brewing_stand") || id.equals("bell")) {
            return HALF;
        }
        if (id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood")
                || id.endsWith("_stem") || id.endsWith("_hyphae")) {
            return WOOD;
        }
        if (id.endsWith("_bed")) {
            return HALF;
        }
        if (id.startsWith("tarkovscav:") || id.equals("torch") || id.equals("wall_torch")) {
            return OTHER;
        }
        if (STONE_BUILD.contains(id)) {
            return WALL_OTHER;
        }
        if (isNaturalTerrain(id)) {
            return TERRAIN;
        }
        return OTHER;
    }

    static final java.util.Set<String> GEN = new java.util.HashSet<>();

    /** Loads the generator's own block vocabulary out of the shipped structure NBTs. */
    static void loadGeneratorPalette(String spec) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String part : spec.split(";")) {
            Path p = Path.of(part);
            if (Files.isDirectory(p)) {
                try (var stream = Files.walk(p)) {
                    stream.filter(f -> f.toString().endsWith(".nbt")).forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        for (Path f : files) {
            Voxel v = Voxel.fromStructure(f, f.getFileName().toString());
            for (String s : v.paletteStates()) {
                if (s == null) {
                    continue;
                }
                int b = s.indexOf('[');
                GEN.add(b < 0 ? s : s.substring(0, b));
            }
        }
        System.out.println("generatorVocabularySize=" + GEN.size() + " from " + spec);
    }

    static final java.util.Set<String> TERRAIN_IDS = new java.util.HashSet<>(List.of(
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "dirt",
            "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud", "gravel",
            "sand", "red_sand", "sandstone", "red_sandstone", "clay", "bedrock", "obsidian",
            "magma_block", "basalt", "smooth_basalt", "netherrack", "end_stone", "snow_block",
            "snow", "moss_block", "moss_carpet", "pointed_dripstone", "dripstone_block", "amethyst_block",
            "budding_amethyst", "sculk", "grass", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "sugar_cane", "cactus", "vine", "vines", "seagrass", "tall_seagrass",
            "kelp", "kelp_plant", "bamboo", "lily_pad", "brown_mushroom", "red_mushroom",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony",
            "glow_lichen", "spore_blossom", "hanging_roots", "muddy_mangrove_roots", "packed_mud",
            "suspicious_sand", "suspicious_gravel", "bee_nest", "bone_block",
            "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "shroomlight"));

    /** Cut / polished stone family: grey build material, never natural terrain here. */
    static final java.util.Set<String> STONE_BUILD = new java.util.HashSet<>(List.of(
            "cobblestone", "mossy_cobblestone", "polished_andesite", "polished_diorite",
            "polished_granite", "smooth_stone", "stone_bricks", "mossy_stone_bricks",
            "cracked_stone_bricks", "chiseled_stone_bricks", "deepslate_bricks", "deepslate_tiles",
            "cracked_deepslate_bricks", "cracked_deepslate_tiles", "chiseled_deepslate",
            "cobbled_deepslate", "blackstone", "polished_blackstone", "bricks", "mud_bricks",
            "prismarine", "end_stone_bricks", "quartz_block", "smooth_sandstone", "cut_sandstone",
            "smooth_red_sandstone", "cut_red_sandstone", "chiseled_sandstone", "smooth_quartz"));

    static boolean isNaturalTerrain(String id) {
        if (STONE_BUILD.contains(id)) {
            return false;
        }
        if (TERRAIN_IDS.contains(id)) {
            return true;
        }
        return id.endsWith("_ore") || id.endsWith("_sapling") || id.endsWith("_coral")
                || id.endsWith("_coral_block") || id.endsWith("_coral_fan")
                || id.endsWith("_mushroom_block");
    }

    /**
     * Terrain materials the generator also happens to use (its platform, footing and street inlays are
     * made of stone, dirt and gravel). They are worldgen here, so they must not be read as "built",
     * otherwise a badlands hillside inside a city box counts as a skyscraper.
     */
    static final java.util.Set<String> TERRAIN_MATERIAL = new java.util.HashSet<>(List.of(
            "stone", "dirt", "coarse_dirt", "rooted_dirt", "gravel", "sand", "red_sand", "clay",
            "sandstone", "red_sandstone", "granite", "diorite", "andesite", "deepslate", "tuff",
            "calcite", "grass_block", "podzol", "mycelium", "mud", "snow", "snow_block", "ice",
            "packed_ice", "blue_ice", "obsidian", "basalt", "netherrack", "end_stone", "bedrock",
            "moss_block", "moss_carpet", "dripstone_block", "pointed_dripstone"));

    /** True for a block the generator or a player put there (as opposed to worldgen terrain). */
    static boolean isBuilt(String id) {
        if (id.equals("?") || id.endsWith("air")) {
            return false;
        }
        if (TERRAIN_MATERIAL.contains(id)) {
            return false;
        }
        if (GEN.contains(id)) {
            return true;
        }
        return !isNaturalTerrain(id);
    }

    static boolean isPassable(String id) {
        if (id.equals("?")) {
            return true;
        }
        if (id.endsWith("air")) {
            return true;
        }
        switch (id) {
            case "torch":
            case "wall_torch":
            case "soul_torch":
            case "soul_wall_torch":
            case "redstone_torch":
            case "redstone_wall_torch":
            case "grass":
            case "short_grass":
            case "tall_grass":
            case "fern":
            case "large_fern":
            case "dead_bush":
            case "dandelion":
            case "poppy":
            case "blue_orchid":
            case "allium":
            case "azure_bluet":
            case "oxeye_daisy":
            case "cornflower":
            case "lily_of_the_valley":
            case "vine":
            case "vines":
            case "glow_lichen":
            case "seagrass":
            case "tall_seagrass":
            case "kelp":
            case "kelp_plant":
            case "sugar_cane":
            case "rail":
            case "powered_rail":
            case "detector_rail":
            case "activator_rail":
            case "tripwire":
            case "string":
            case "lily_pad":
            case "water":
            case "bubble_column":
                return true;
            default:
                break;
        }
        if (id.endsWith("_carpet")) {
            return true;
        }
        if (id.endsWith("_button") || id.endsWith("_pressure_plate") || id.endsWith("_sign")
                || id.endsWith("_banner") || id.endsWith("_sapling") || id.endsWith("_flower")) {
            return true;
        }
        if (id.endsWith("_door") || id.endsWith("_fence_gate") || id.endsWith("_trapdoor")) {
            return true;
        }
        if (id.equals("ladder") || id.equals("scaffolding") || id.equals("chain")
                || id.equals("cobweb") || id.equals("flower_pot") || id.equals("lantern")
                || id.equals("soul_lantern") || id.equals("item_frame") || id.equals("armor_stand")) {
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ground detection

    /**
     * The street level. The signal used is the modal "open surface": the Y at which the most columns
     * have their highest built block with three passable blocks above it. In a city district that is
     * the platform/street plane, because the streets cover more columns than any single roof; for a
     * structure piece it is checked against the printed horizontal profile, and --ground overrides it.
     */
    static int detectGround(Voxel v) {
        Map<Integer, Integer> openSurface = new TreeMap<>();
        Map<Integer, Integer> horizontal = new TreeMap<>();
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int y = v.y0; y < v.y0 + v.sy; y++) {
                    if (isBuilt(v.at(x, y, z))) {
                        horizontal.merge(y, 1, Integer::sum);
                    }
                }
                for (int y = v.y0 + v.sy - 1; y >= v.y0; y--) {
                    if (isBuilt(v.at(x, y, z))) {
                        if (isPassable(v.at(x, y + 1, z)) && isPassable(v.at(x, y + 2, z))
                                && isPassable(v.at(x, y + 3, z))) {
                            openSurface.merge(y, 1, Integer::sum);
                        }
                        break;
                    }
                }
            }
        }
        List<Map.Entry<Integer, Integer>> top = new ArrayList<>(openSurface.entrySet());
        top.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed());
        System.out.println("  openSurfaceTop(" + Math.min(8, top.size()) + " of "
                + openSurface.size() + "): " + top.subList(0, Math.min(8, top.size())));
        StringBuilder sb = new StringBuilder("  horizontalBuiltPerY: ");
        for (Map.Entry<Integer, Integer> e : horizontal.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
        }
        System.out.println(sb);
        int modal = top.isEmpty() ? v.y0 : top.get(0).getKey();

        // A fully slug foundation (a structure piece whose 16x16 footprint is entirely built from the
        // bottom up) exposes no street, so the modal open surface would be its ROOF. Detect the
        // bottom-most contiguous run of near-full horizontal slabs and take its top instead.
        int full = (int) Math.ceil(0.85 * v.sx * v.sz);
        int slabTop = Integer.MIN_VALUE;
        for (int y = v.y0; y < v.y0 + v.sy; y++) {
            if (horizontal.getOrDefault(y, 0) >= full) {
                slabTop = y;
            } else {
                break;
            }
        }
        System.out.println("  fullSlabThreshold=" + full + " bottomSlabRunTop=" + slabTop
                + " modalOpenSurface=" + modal);
        if (slabTop != Integer.MIN_VALUE && slabTop < modal) {
            System.out.println("  -> foundation-slug piece, street level = bottom slab run top");
            return slabTop;
        }
        return modal;
    }

    // ------------------------------------------------------------------ block access helper

    static int groundY;

    /** Highest non-air block in a Y window, or null. */
    static String topIn(Voxel v, int x, int z, int yLo, int yHi) {
        for (int y = Math.min(yHi, v.y0 + v.sy - 1); y >= Math.max(yLo, v.y0); y--) {
            String id = v.at(x, y, z);
            if (!id.equals("?") && !id.endsWith("air")) {
                return id;
            }
        }
        return null;
    }

    /** Built height above the street level: -1 = nothing built in this column. */
    static int builtHeight(Voxel v, int x, int z) {
        for (int y = v.y0 + v.sy - 1; y >= v.y0; y--) {
            if (isBuilt(v.at(x, y, z))) {
                return y - groundY;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    static final class Canvas {
        final BufferedImage image;
        final Graphics2D g;

        Canvas(int w, int h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        }

        Graphics2D g() {
            return g;
        }

        void text(String s, int x, int y, int size, boolean bold, int color) {
            g.setColor(new Color(color));
            g.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
            g.drawString(s, x, y);
        }

        void save(Path path) throws IOException {
            g.dispose();
            Files.createDirectories(path.toAbsolutePath().getParent());
            ImageIO.write(image, "png", path.toFile());
            System.out.println("wrote " + path + " " + image.getWidth() + "x" + image.getHeight());
        }
    }

    static void rect(Graphics2D g, int x, int y, int w, int h, int color) {
        g.setColor(new Color(color));
        g.fillRect(x, y, w, h);
    }

    /** Draws one block-map panel into a canvas. Returns the panel height used. */
    static int drawPanel(Canvas c, Voxel v, int px, int py, int scale, int yLo, int yHi,
                         int kind, String title, boolean grid, boolean axes) {
        int w = v.sx * scale;
        int h = v.sz * scale;
        int headerH = 34;
        c.text(title, px, py + 14, 13, true, 0x000000);
        int top = py + headerH;
        int[] pixels = new int[w * h];
        for (int z = 0; z < v.sz; z++) {
            for (int x = 0; x < v.sx; x++) {
                int worldX = v.x0 + x;
                int worldZ = v.z0 + z;
                int color;
                if (kind == 0) {
                    String id = topIn(v, worldX, worldZ, yLo, yHi);
                    color = rgb(id == null ? LEGEND_COLOR[AIR] : LEGEND_COLOR[category(id)]);
                } else {
                    color = rgb(heightColor(v, worldX, worldZ));
                }
                for (int dy = 0; dy < scale; dy++) {
                    int rowBase = (z * scale + dy) * w + x * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[rowBase + dx] = color;
                    }
                }
            }
        }
        c.g().drawImage(toImage(pixels, w, h), px, top, null);
        if (grid) {
            Graphics2D g = c.g();
            g.setColor(new Color(0x50, 0x50, 0x50, 120));
            g.setStroke(new BasicStroke(1f));
            for (int gx = 0; gx <= v.sx; gx += 16) {
                g.drawLine(px + gx * scale, top, px + gx * scale, top + h);
            }
            for (int gz = 0; gz <= v.sz; gz += 16) {
                g.drawLine(px, top + gz * scale, px + w, top + gz * scale);
            }
            g.setColor(Color.BLACK);
            if (axes) {
                for (int gx = 0; gx <= v.sx; gx += 16) {
                    c.text(String.valueOf(v.x0 + gx), px + gx * scale + 2, top - 3, 10, false, 0x000000);
                    c.text(String.valueOf(v.x0 + gx), px + gx * scale + 2, top + h + 11, 10, false, 0x000000);
                }
                for (int gz = 0; gz <= v.sz; gz += 16) {
                    c.text(String.valueOf(v.z0 + gz), px - 30, top + gz * scale + 10, 10, false, 0x000000);
                    c.text(String.valueOf(v.z0 + gz), px + w + 3, top + gz * scale + 10, 10, false,
                            0x000000);
                }
            }
        }
        c.g().setColor(Color.BLACK);
        c.g().setStroke(new BasicStroke(1f));
        c.g().drawRect(px, top, w, h);
        return headerH + h;
    }

    static BufferedImage toImage(int[] pixels, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    static int heightColor(Voxel v, int x, int z) {
        int top = Integer.MIN_VALUE;
        String topId = null;
        for (int y = v.y0 + v.sy - 1; y >= v.y0; y--) {
            String id = v.at(x, y, z);
            if (!id.equals("?") && !id.endsWith("air")) {
                top = y;
                topId = id;
                break;
            }
        }
        if (top == Integer.MIN_VALUE) {
            return H_COLOR[H_OPEN];
        }
        int built = builtHeight(v, x, z);
        if (built < 0) {
            int cat = category(topId);
            if (cat == WATER) {
                return H_COLOR[H_WATER];
            }
            if (cat == LEAVES) {
                return 0x1E5B1E;
            }
            return H_COLOR[H_TERRAIN];
        }
        if (built <= 0) {
            return H_COLOR[H_OPEN];
        }
        if (built == 1) {
            int cat = category(topId);
            if (cat == HALF || cat == STAIRS || cat == GLASS || cat == IRONBARS || cat == CONTAINER) {
                return H_COLOR[H_HALF];
            }
            return H_COLOR[H_FULL1];
        }
        if (built == 2) {
            return H_COLOR[H_FULL2];
        }
        return H_COLOR[H_BUILD];
    }

    static void drawLegend(Canvas c, int px, int py, String[] labels, int[] colors, int perColumn) {
        int col = 0, row = 0;
        int colW = 250;
        for (int i = 0; i < labels.length; i++) {
            int x = px + col * colW;
            int y = py + row * 18;
            rect(c.g(), x, y, 14, 14, colors[i]);
            c.g().setColor(Color.BLACK);
            c.g().drawRect(x, y, 14, 14);
            c.text(labels[i], x + 20, y + 11, 11, false, 0x000000);
            row++;
            if (row >= perColumn) {
                row = 0;
                col++;
            }
        }
    }

    static void drawNorthArrow(Canvas c, int x, int y) {
        Graphics2D g = c.g();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x, y + 40, x, y);
        g.drawLine(x, y, x - 6, y + 12);
        g.drawLine(x, y, x + 6, y + 12);
        g.setStroke(new BasicStroke(1f));
        c.text("N = -Z (up)", x + 10, y + 8, 11, true, 0x000000);
        c.text("+Z = south = down", x + 10, y + 22, 11, false, 0x000000);
        c.text("+X = east = right", x + 10, y + 36, 11, false, 0x000000);
    }

    // ------------------------------------------------------------------ title layout
    //
    // The first version drew the title at a fixed 18 pt on one line, which ran off the right edge of
    // the narrower canvases ("cover heigh", "same pale"). The header is now measured, wrapped to the
    // canvas width and shrunk if a single word would not fit, and the panel below is shifted down by
    // the measured height.

    static final class Header {
        final List<String> titleLines = new ArrayList<>();
        final List<String> subLines = new ArrayList<>();
        int titleSize = 18;
        int bottom;
    }

    static int textWidth(String s, int size, boolean bold) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        int w = g.getFontMetrics(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size))
                .stringWidth(s);
        g.dispose();
        return w;
    }

    static List<String> wrapText(String text, int size, boolean bold, int maxW) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String paragraph : text.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String cand = cur.length() == 0 ? word : cur + " " + word;
                if (cur.length() == 0 || textWidth(cand, size, bold) <= maxW) {
                    cur = new StringBuilder(cand);
                } else {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                }
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
        }
        return out;
    }

    static Header layoutHeader(String title, String subtitle, int bodyW, int topY) {
        Header h = new Header();
        int size = 18;
        int widest = 0;
        for (String word : title.split(" ")) {
            widest = Math.max(widest, textWidth(word, size, true));
        }
        while (size > 10 && widest > bodyW) {
            size--;
            widest = 0;
            for (String word : title.split(" ")) {
                widest = Math.max(widest, textWidth(word, size, true));
            }
        }
        h.titleSize = size;
        h.titleLines.addAll(wrapText(title, size, true, bodyW));
        h.subLines.addAll(wrapText(subtitle, 12, false, bodyW));
        int y = topY + size;
        y += h.titleLines.size() * (size + 4);
        if (!h.subLines.isEmpty()) {
            y += 2 + h.subLines.size() * 15;
        }
        h.bottom = y;
        return h;
    }

    static void drawHeader(Canvas c, Header h, int marginX, int topY) {
        int y = topY + h.titleSize;
        for (String line : h.titleLines) {
            c.text(line, marginX, y, h.titleSize, true, 0x000000);
            y += h.titleSize + 4;
        }
        if (!h.subLines.isEmpty()) {
            y += 2;
            for (String line : h.subLines) {
                c.text(line, marginX, y, 12, false, 0x303030);
                y += 15;
            }
        }
    }

    // ------------------------------------------------------------------ modes

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opt.put(args[i], args[i + 1]);
                i++;
            } else {
                positional.add(args[i]);
            }
        }
        String mode = positional.isEmpty() ? "" : positional.get(0);
        if (opt.containsKey("--gen")) {
            loadGeneratorPalette(opt.get("--gen"));
        }
        switch (mode) {
            case "fonts" -> {
                String[] fam = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames();
                System.out.println("fontFamilies=" + fam.length);
                for (String f : fam) {
                    System.out.println("  " + f);
                }
            }
            case "box" -> boxMode(opt);
            case "structure" -> structureMode(opt);
            case "pieces" -> piecesMode(opt);
            case "compare" -> compareMode(opt);
            default -> System.out.println("unknown mode: " + mode);
        }
    }

    static Voxel loadBox(Map<String, String> opt) throws IOException {
        String[] parts = opt.get("--box").trim().split("\\s+");
        return Voxel.fromRegion(Path.of(opt.get("--world")),
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                opt.getOrDefault("--title", "box"));
    }

    static void boxMode(Map<String, String> opt) throws Exception {
        Voxel v = loadBox(opt);
        int scale = Integer.parseInt(opt.getOrDefault("--scale", "3"));
        int slice = Integer.parseInt(opt.getOrDefault("--slice", "8"));
        String prefix = opt.get("--out");
        String title = opt.getOrDefault("--title", "district");
        System.out.println("boxMode " + title + " size=" + v.sx + "x" + v.sy + "x" + v.sz
                + " missingChunks=" + v.missingChunks);
        groundY = opt.containsKey("--ground") ? Integer.parseInt(opt.get("--ground"))
                : detectGround(v);
        System.out.println("  groundY=" + groundY);

        int builtTop = builtTopY(v);
        System.out.println("  builtTopY=" + builtTop);
        int maxBuilt = 0;
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                maxBuilt = Math.max(maxBuilt, builtHeight(v, x, z));
            }
        }
        System.out.println("  maxBuiltHeight=" + maxBuilt);
        Map<Integer, Integer> heightHist = new TreeMap<>();
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                heightHist.merge(builtHeight(v, x, z), 1, Integer::sum);
            }
        }
        System.out.println("  builtHeightHistogram=" + heightHist);

        // height map
        int pad = 90;
        int canvasW = Math.max(v.sx * scale + pad * 2, 560);
        String sub = "box x[" + v.x0 + ".." + (v.x0 + v.sx - 1) + "] y[" + v.y0 + ".."
                + (v.y0 + v.sy - 1) + "] z[" + v.z0 + ".." + (v.z0 + v.sz - 1) + "]   "
                + scale + " px per block";
        Header hd = layoutHeader(title + " - cover height map (highest built block above street"
                + " level Y=" + groundY + ")", sub, canvasW - 40, 20);
        int panelTop = hd.bottom + 26;
        Canvas hm = new Canvas(canvasW,
                panelTop + 34 + v.sz * scale + 40 + 4 * 18 + 30);
        drawHeader(hm, hd, 20, 20);
        drawPanel(hm, v, pad, panelTop - 14, scale, v.y0, v.y0 + v.sy - 1, 1,
                "height above street level", true, true);
        drawNorthArrow(hm, v.sx * scale + pad + 30, panelTop + 16);
        drawLegend(hm, pad, panelTop - 14 + 34 + v.sz * scale + 40, H_LABEL, H_COLOR, 4);
        hm.save(Path.of(prefix + "_height.png"));

        // 1 px per block version
        int canvasW1 = Math.max(v.sx + pad * 2, 560);
        String sub1 = "box x[" + v.x0 + ".." + (v.x0 + v.sx - 1) + "] z[" + v.z0 + ".."
                + (v.z0 + v.sz - 1) + "]  street level Y=" + groundY;
        Header hd1 = layoutHeader(title + " - cover height map, 1 px per block", sub1,
                canvasW1 - 40, 20);
        int panelTop1 = hd1.bottom + 26;
        Canvas hm1 = new Canvas(canvasW1, panelTop1 + 34 + v.sz + 40 + 4 * 18 + 30);
        drawHeader(hm1, hd1, 20, 20);
        drawPanel(hm1, v, pad, panelTop1 - 14, 1, v.y0, v.y0 + v.sy - 1, 1, "1 px per block",
                true, true);
        drawNorthArrow(hm1, v.sx + pad + 30, panelTop1 + 16);
        drawLegend(hm1, pad, panelTop1 - 14 + 34 + v.sz + 40, H_LABEL, H_COLOR, 4);
        hm1.save(Path.of(prefix + "_height_1px.png"));

        // floor panels
        int yLo = Math.max(v.y0, groundY - 1);
        int yHi = Math.min(v.y0 + v.sy - 1, builtTop + 1);
        List<int[]> slices = new ArrayList<>();
        for (int y = yLo; y <= yHi; y += slice) {
            slices.add(new int[]{y, Math.min(y + slice - 1, yHi)});
        }
        int panelW = v.sx * scale + pad * 2;
        int panelH = v.sz * scale + 34 + 40;
        int cols = slices.size() <= 2 ? 1 : 2;
        int rows = (slices.size() + cols - 1) / cols;
        int flW = Math.max(cols * panelW + 40, 560);
        String flsub = "box x[" + v.x0 + ".." + (v.x0 + v.sx - 1) + "] y[" + yLo + ".." + yHi
                + "] z[" + v.z0 + ".." + (v.z0 + v.sz - 1) + "]   " + scale + " px per block";
        Header hdf = layoutHeader(title + " - horizontal slices of " + slice
                + " blocks of Y (highest block in the slice)", flsub, flW - 40, 20);
        int panelsTop = hdf.bottom + 26;
        Canvas fl = new Canvas(flW, panelsTop + rows * panelH + 4 * 18 + 40);
        drawHeader(fl, hdf, 20, 20);
        for (int i = 0; i < slices.size(); i++) {
            int cx = 20 + (i % cols) * panelW;
            int cy = panelsTop + (i / cols) * panelH;
            int[] s = slices.get(i);
            drawPanel(fl, v, cx + pad, cy, scale, s[0], s[1], 0,
                    "Y " + s[0] + " .. " + s[1], true, true);
        }
        drawLegend(fl, 20, panelsTop + rows * panelH + 10, LEGEND_LABEL, LEGEND_COLOR, 7);
        fl.save(Path.of(prefix + "_floors.png"));
    }

    static int builtTopY(Voxel v) {
        for (int y = v.y0 + v.sy - 1; y >= v.y0; y--) {
            for (int x = v.x0; x < v.x0 + v.sx; x++) {
                for (int z = v.z0; z < v.z0 + v.sz; z++) {
                    if (isBuilt(v.at(x, y, z))) {
                        return y;
                    }
                }
            }
        }
        return v.y0;
    }

    static void structureMode(Map<String, String> opt) throws Exception {
        Voxel v = Voxel.fromStructure(Path.of(opt.get("--nbt")), opt.get("--title"));
        int scale = Integer.parseInt(opt.getOrDefault("--scale", "4"));
        int slice = Integer.parseInt(opt.getOrDefault("--slice", "8"));
        String title = opt.getOrDefault("--title", v.label);
        groundY = detectGround(v);
        int builtTop = builtTopY(v);
        System.out.println("structureMode " + title + " size=" + v.sx + "x" + v.sy + "x" + v.sz
                + " groundY=" + groundY + " builtTopY=" + builtTop);
        Map<Integer, Integer> heightHist = new TreeMap<>();
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                heightHist.merge(builtHeight(v, x, z), 1, Integer::sum);
            }
        }
        System.out.println("  builtHeightHistogram=" + heightHist);
        System.out.println("  blocksInBox=" + (v.sx * v.sy * v.sz) + " nonAir=" + nonAir(v));

        int pad = 90;
        int yLo = Math.max(v.y0, groundY - 1);
        int yHi = Math.min(v.y0 + v.sy - 1, builtTop + 1);
        List<int[]> slices = new ArrayList<>();
        for (int y = yLo; y <= yHi; y += slice) {
            slices.add(new int[]{y, Math.min(y + slice - 1, yHi)});
        }
        int panelW = v.sx * scale + pad * 2;
        int panelH = v.sz * scale + 34 + 40;
        int cols = slices.size() <= 2 ? 1 : 2;
        int rows = (slices.size() + cols - 1) / cols;
        int heightBlockH = v.sz * scale + 34;
        int canvasW = Math.max(Math.max(cols * panelW + 40, v.sx * scale + pad * 2 + 60), 640);
        Header hd = layoutHeader(title + " - generator output (structure NBT), size " + v.sx + "x"
                + v.sy + "x" + v.sz,
                "street level Y=" + groundY + " (auto-detected)   " + scale + " px per block   "
                        + "+Z = south = down, +X = east = right", canvasW - 40, 20);
        int panelTop = hd.bottom + 26;
        int legendY = panelTop + heightBlockH + 30;
        int panelsY = legendY + 4 * 18 + 20;
        Canvas c = new Canvas(canvasW, panelsY + rows * panelH + 4 * 18 + 40);
        drawHeader(c, hd, 20, 20);
        drawPanel(c, v, pad, panelTop - 14, scale, v.y0, v.y0 + v.sy - 1, 1, "cover height map",
                true, true);
        drawNorthArrow(c, v.sx * scale + pad + 30, panelTop + 16);
        drawLegend(c, 20, legendY, H_LABEL, H_COLOR, 4);
        for (int i = 0; i < slices.size(); i++) {
            int cx = 20 + (i % cols) * panelW;
            int cy = panelsY + (i / cols) * panelH;
            int[] s = slices.get(i);
            drawPanel(c, v, cx + pad, cy, scale, s[0], s[1], 0, "Y " + s[0] + " .. " + s[1],
                    true, true);
        }
        drawLegend(c, 20, panelsY + rows * panelH + 10, LEGEND_LABEL, LEGEND_COLOR, 7);
        c.save(Path.of(opt.get("--out")));
    }

    static int nonAir(Voxel v) {
        int n = 0;
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int y = v.y0; y < v.y0 + v.sy; y++) {
                for (int z = v.z0; z < v.z0 + v.sz; z++) {
                    String id = v.at(x, y, z);
                    if (!id.equals("?") && !id.endsWith("air")) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    static void piecesMode(Map<String, String> opt) throws Exception {
        Path dir = Path.of(opt.get("--dir"));
        List<Path> files = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path p : stream) {
                files.add(p);
            }
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        int cols = 4;
        int cellW = 300;
        int cellH = 260;
        int rows = (files.size() + cols - 1) / cols;
        int canvasW = cols * cellW + 60;
        Header hd = layoutHeader(opt.getOrDefault("--title", "generator building pieces"),
                "one panel per structure NBT in " + dir + "   " + files.size()
                        + " pieces   ground-floor slice, 2 px per block", canvasW - 48, 20);
        int gridTop = hd.bottom + 26;
        Canvas c = new Canvas(canvasW, gridTop + rows * cellH + 4 * 18 + 40);
        drawHeader(c, hd, 24, 20);
        for (int i = 0; i < files.size(); i++) {
            Path f = files.get(i);
            Voxel v = Voxel.fromStructure(f, f.getFileName().toString().replace(".nbt", ""));
            groundY = detectGround(v);
            int cx = 24 + (i % cols) * cellW;
            int cy = gridTop + (i / cols) * cellH;
            drawPanel(c, v, cx + 20, cy, 2, groundY, Math.min(groundY + 6, v.y0 + v.sy - 1), 0,
                    v.label + "  " + v.sx + "x" + v.sy + "x" + v.sz, false, false);
            c.text("street Y=" + groundY + "  volume=" + (v.sx * v.sy * v.sz)
                    + "  nonAir=" + nonAir(v) + "  (ground-floor slice Y" + groundY + ".."
                    + Math.min(groundY + 6, v.y0 + v.sy - 1) + ")", cx + 20,
                    cy + 30 + v.sz * 2 + 16, 10, false, 0x404040);
        }
        drawLegend(c, 24, gridTop + rows * cellH + 10, H_LABEL, H_COLOR, 4);
        c.save(Path.of(opt.get("--out")));
    }

    static void compareMode(Map<String, String> opt) throws Exception {
        Voxel a = loadBox(opt);
        Voxel b = Voxel.fromStructure(Path.of(opt.get("--nbt")), opt.get("--title2"));
        int scale = Integer.parseInt(opt.getOrDefault("--scale", "2"));
        groundY = Integer.parseInt(opt.getOrDefault("--ground", String.valueOf(detectGround(a))));
        int groundA = groundY;
        int groundB = detectGround(b);
        int pad = 80;
        int wA = a.sx * scale;
        int hA = a.sz * scale;
        int wB = b.sx * scale;
        int hB = b.sz * scale;
        int bodyH = Math.max(hA, hB) + 80;
        int canvasW = Math.max(wA + wB + pad * 3 + 40, 700);
        Header hd = layoutHeader(opt.getOrDefault("--title", "hand-edited district vs generator"
                + " preset") + " - same scale, same palette (" + scale + " px per block)",
                "left: save district; right: shipped preset " + b.label
                        + "; both drawn as built height above their own street level",
                canvasW - 40, 20);
        int panelsTop = hd.bottom + 26;
        Canvas c = new Canvas(canvasW, panelsTop + bodyH + 4 * 18 + 40);
        drawHeader(c, hd, 20, 20);
        groundY = groundA;
        drawPanel(c, a, 20 + pad, panelsTop - 14, scale, a.y0, a.y0 + a.sy - 1, 1,
                "district x[" + a.x0 + ".." + (a.x0 + a.sx - 1) + "] z[" + a.z0 + ".."
                        + (a.z0 + a.sz - 1) + "]  " + a.sx + "x" + a.sz, true, true);
        groundY = groundB;
        drawPanel(c, b, 20 + pad * 2 + wA + 20, panelsTop - 14, scale, b.y0, b.y0 + b.sy - 1, 1,
                "preset " + b.label + "  " + b.sx + "x" + b.sz, true, true);
        drawLegend(c, 20, panelsTop - 14 + bodyH + 10, H_LABEL, H_COLOR, 4);
        c.save(Path.of(opt.get("--out")));
    }

    private CityMap() {
    }
}
