import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Part 2 of the city survey: a per-block-class OBSTACLE INVENTORY of one city district (from a save
 * box) or one generator structure (from an NBT), grouped by combat function, with the numbers the
 * design guidance is made of.
 *
 * <p>Everything printed is MEASURED from the voxel volume. The two inferred quantities are stated as
 * such in the output: "floorLevels" (a local maximum of the walkable-cell histogram, not a declared
 * floor) and "coverDensity" (see the definition printed with it).</p>
 *
 * <pre>
 *   ObstacleScan --label NAME [--world DIR --box x1 y1 z1 x2 y2 z2] [--nbt FILE]
 *                [--ground Y] [--gen DIR] [--radius 3] [--signature]
 * </pre>
 *
 * Read-only.
 */
public final class ObstacleScan {

    // combat-function classes
    static final Set<String> FULL = new java.util.HashSet<>(List.of(
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks",
            "bricks", "deepslate_tiles", "deepslate_bricks", "chiseled_deepslate", "smooth_stone",
            "polished_andesite", "polished_diorite", "polished_granite", "cobblestone",
            "mossy_cobblestone", "cobbled_deepslate", "blackstone", "polished_blackstone",
            "nether_bricks", "quartz_block", "prismarine", "end_stone_bricks", "mud_bricks",
            "smooth_sandstone", "cut_sandstone", "sandstone", "packed_mud", "terracotta",
            "orange_terracotta", "brown_terracotta", "white_terracotta", "gray_terracotta",
            "light_gray_terracotta", "red_terracotta", "yellow_terracotta", "green_terracotta",
            "blue_terracotta", "black_terracotta", "cyan_terracotta", "lime_terracotta",
            "magenta_terracotta", "pink_terracotta", "purple_terracotta", "light_blue_terracotta",
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite",
            "white_concrete", "light_gray_concrete", "gray_concrete", "black_concrete",
            "brown_concrete", "red_concrete", "green_concrete", "blue_concrete", "yellow_concrete"));

    static final Set<String> LOW = new java.util.HashSet<>(List.of(
            "hay_block", "cauldron", "water_cauldron", "lava_cauldron", "composter", "snow",
            "barrel", "chest", "trapped_chest", "ender_chest", "flower_pot", "lectern", "anvil",
            "brewing_stand", "bell", "campfire", "soul_campfire", "honey_block", "slime_block",
            "dirt_path", "farmland", "stonecutter", "grindstone", "smithing_table",
            "cartography_table", "fletching_table", "loom", "crafting_table", "furnace",
            "blast_furnace", "smoker", "bookshelf", "chiseled_bookshelf"));

    static final Set<String> SEE_THROUGH = new java.util.HashSet<>(List.of(
            "glass", "tinted_glass", "iron_bars", "cobweb", "chain", "scaffolding", "ladder"));

    static final Set<String> CONTAINERS = new java.util.HashSet<>(List.of(
            "barrel", "chest", "trapped_chest", "ender_chest", "furnace", "blast_furnace", "smoker",
            "crafting_table", "bookshelf", "chiseled_bookshelf", "lectern", "brewing_stand",
            "decorated_pot"));

    static final Set<String> INTERIOR = new java.util.HashSet<>(List.of(
            "red_bed", "white_bed", "orange_bed", "magenta_bed", "light_blue_bed", "yellow_bed",
            "lime_bed", "pink_bed", "gray_bed", "light_gray_bed", "cyan_bed", "purple_bed",
            "blue_bed", "brown_bed", "green_bed", "black_bed", "spawner", "crafting_table",
            "furnace", "blast_furnace", "smoker", "bookshelf", "chiseled_bookshelf", "lectern",
            "brewing_stand", "flower_pot", "item_frame", "glow_item_frame", "armor_stand",
            "barrel", "chest", "trapped_chest", "ender_chest", "torch", "wall_torch", "lantern"));

    static boolean isLowCover(String id) {
        if (LOW.contains(id)) {
            return true;
        }
        return id.endsWith("_slab") || id.endsWith("_carpet") || id.endsWith("_wool")
                || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_wall")
                || id.endsWith("_stairs") || id.endsWith("_bed") || id.equals("iron_bars")
                || id.equals("glass_pane") || id.endsWith("_pane");
    }

    // ------------------------------------------------------------------ the cover-line metric
    //
    // The first version of this metric counted every "low-ish" block, which mixed three very
    // different things: real torso cover (slab / stairs / wall / fence / barrel / chest ...),
    // SEE-THROUGH panes, and flat decoration (carpet / bed) that gives no torso cover at all.
    // Worse, a building's stone_brick_wall WAINSCOT course is the bottom of a full-height wall, so
    // counting it produced "cover lines" a player can never use. The metric below restricts the
    // block set and adds the two conditions that make a line usable, and it prints every variant so
    // the numbers can be checked by hand.

    /** Flat decoration: no torso cover (carpet is 0.06 tall, a bed 0.56). */
    static boolean isFlatDecoration(String id) {
        return id.endsWith("_carpet") || id.endsWith("_bed");
    }

    /** See-through low blocks: they hide nothing, so they are not cover you can shoot over. */
    static boolean isSeeThroughLow(String id) {
        return id.equals("glass_pane") || id.endsWith("_pane") || id.endsWith("_stained_glass")
                || id.endsWith("_stained_glass_pane") || id.equals("iron_bars");
    }

    /** Solid low cover: opaque, roughly 0.5 to 1.5 blocks tall. */
    static boolean isSolidLow(String id) {
        if (!CityMap.isBuilt(id) || id.endsWith("air")) {
            return false;
        }
        if (CityMap.isPassable(id)) {
            return false;   // also removes carpet, torches, ladders, doors, trapdoors, gates
        }
        if (isFlatDecoration(id) || isSeeThroughLow(id)) {
            return false;
        }
        return LOW.contains(id) || id.endsWith("_slab") || id.endsWith("_stairs")
                || id.endsWith("_wall") || id.endsWith("_fence");
    }

    /**
     * One cell of a candidate cover line for the given variant.
     * 1 = every low-ish block (the original, too generous metric)
     * 2 = solid low cover only
     * 3 = 2 plus "exposed": the block above must be passable, so it is not the bottom of a wall
     * 4 = 3 plus "standable": at least one horizontal neighbour at the same Y is walkable
     */
    static boolean coverCell(Voxel v, int x, int y, int z, int variant) {
        String id = v.at(x, y, z);
        if (id.equals("?") || id.endsWith("air")) {
            return false;
        }
        if (variant == 1) {
            return isLowCover(id) && CityMap.isBuilt(id);
        }
        if (!isSolidLow(id)) {
            return false;
        }
        if (variant >= 3 && !CityMap.isPassable(v.at(x, y + 1, z))) {
            return false;
        }
        if (variant >= 4) {
            return isWalkable(v, x - 1, y, z) || isWalkable(v, x + 1, y, z)
                    || isWalkable(v, x, y, z - 1) || isWalkable(v, x, y, z + 1);
        }
        return true;
    }

    /** {linesX, blocksX, longestX, linesZ, blocksZ, longestZ} for one variant. */
    static int[] coverLines(Voxel v, int variant) {
        int linesX = 0, blocksX = 0, longestX = 0;
        int linesZ = 0, blocksZ = 0, longestZ = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                int run = 0;
                for (int x = v.x0; x <= v.x0 + v.sx; x++) {
                    boolean hit = x < v.x0 + v.sx && coverCell(v, x, y, z, variant);
                    if (hit) {
                        run++;
                    } else {
                        if (run >= 3) {
                            linesX++;
                            blocksX += run;
                            longestX = Math.max(longestX, run);
                        }
                        run = 0;
                    }
                }
            }
            for (int x = v.x0; x < v.x0 + v.sx; x++) {
                int run = 0;
                for (int z = v.z0; z <= v.z0 + v.sz; z++) {
                    boolean hit = z < v.z0 + v.sz && coverCell(v, x, y, z, variant);
                    if (hit) {
                        run++;
                    } else {
                        if (run >= 3) {
                            linesZ++;
                            blocksZ += run;
                            longestZ = Math.max(longestZ, run);
                        }
                        run = 0;
                    }
                }
            }
        }
        return new int[]{linesX, blocksX, longestX, linesZ, blocksZ, longestZ};
    }

    static boolean isFullBlocker(String id) {
        if (CityMap.isPassable(id) || id.endsWith("air") || id.equals("?")) {
            return false;
        }
        if (isLowCover(id) || SEE_THROUGH.contains(id)) {
            return false;
        }
        if (id.endsWith("_door") || id.endsWith("_trapdoor")) {
            return false;
        }
        if (id.endsWith("_pane") || id.endsWith("_stairs") || id.endsWith("_slab")) {
            return false;
        }
        return CityMap.category(id) != CityMap.AIR;
    }

    static int yLo;
    static int yHi;

    /** Highest Y in the volume that holds a built block (generator or player). */
    static int builtTop(Voxel v) {
        for (int y = v.y0 + v.sy - 1; y >= v.y0; y--) {
            for (int x = v.x0; x < v.x0 + v.sx; x++) {
                for (int z = v.z0; z < v.z0 + v.sz; z++) {
                    if (CityMap.isBuilt(v.at(x, y, z))) {
                        return y;
                    }
                }
            }
        }
        return v.y0;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i], args[i + 1]);
        }
        if (opt.containsKey("--gen")) {
            CityMap.loadGeneratorPalette(opt.get("--gen"));
        }
        String label = opt.getOrDefault("--label", "unknown");
        Voxel v;
        String kind;
        if (opt.containsKey("--nbt")) {
            v = Voxel.fromStructure(Path.of(opt.get("--nbt")), label);
            kind = "structure";
        } else {
            String[] p = opt.get("--box").trim().split("\\s+");
            v = Voxel.fromRegion(Path.of(opt.get("--world")),
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), label);
            kind = "saveBox";
        }
        CityMap.groundY = opt.containsKey("--ground") ? Integer.parseInt(opt.get("--ground"))
                : CityMap.detectGround(v);
        int radius = Integer.parseInt(opt.getOrDefault("--radius", "3"));
        // The analysis window is the city itself: from the street plane up to the highest built block.
        // Everything below is geology and would swamp every count.
        yLo = opt.containsKey("--ylo") ? Integer.parseInt(opt.get("--ylo"))
                : Math.max(v.y0, CityMap.groundY);
        yHi = opt.containsKey("--yhi") ? Integer.parseInt(opt.get("--yhi"))
                : Math.min(v.y0 + v.sy - 1, builtTop(v));

        System.out.println("## label=" + label + " kind=" + kind
                + " size=" + v.sx + "x" + v.sy + "x" + v.sz
                + " groundY=" + CityMap.groundY + " missingChunks=" + v.missingChunks
                + " volume=" + (v.sx * v.sy * v.sz));
        System.out.println("## analysisWindow y[" + yLo + ".." + yHi + "]"
                + " (streetPlane=" + CityMap.groundY + " builtTop=" + builtTop(v) + ")");

        // ---------------- raw class counts
        Map<String, Integer> byName = new TreeMap<>();
        int nonAir = 0;
        int built = 0;
        int naturalInWindow = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    String id = v.at(x, y, z);
                    if (id.equals("?") || id.endsWith("air")) {
                        continue;
                    }
                    nonAir++;
                    byName.merge(id, 1, Integer::sum);
                    if (CityMap.isBuilt(id)) {
                        built++;
                    } else {
                        naturalInWindow++;
                    }
                }
            }
        }
        System.out.println("## nonAirBlocks=" + nonAir + " builtBlocks=" + built
                + " naturalTerrainBlocksInWindow=" + naturalInWindow
                + " distinctBlocks=" + byName.size());
        StringBuilder sb = new StringBuilder("## topBlocks: ");
        byName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(28)
                .forEach(e -> sb.append(e.getKey()).append(" x").append(e.getValue()).append("; "));
        System.out.println(sb);

        // ---------------- 1 full-height sightline blockers
        int fullBlocks = 0;
        int fullColumns2 = 0;
        Map<String, Integer> fullHist = new TreeMap<>();
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            if (!CityMap.isBuilt(e.getKey())) {
                continue;   // natural terrain is cover, but it is not a BUILT blocker
            }
            if (isFullBlocker(e.getKey())) {
                fullBlocks += e.getValue();
                fullHist.put(e.getKey(), e.getValue());
            }
        }
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                if (CityMap.builtHeight(v, x, z) >= 2) {
                    fullColumns2++;
                }
            }
        }
        System.out.println("## FULL_HEIGHT: fullBlockerBlocks=" + fullBlocks
                + " columnsWithBuiltHeightGE2=" + fullColumns2);
        System.out.println("## FULL_HEIGHT_hist: " + topOf(fullHist, 16));

        // ---------------- 2 half-height / low cover + usable lines of 3+
        int lowBlocks = 0;
        int solidLowBlocks = 0;
        int seeLowBlocks = 0;
        int flatBlocks = 0;
        Map<String, Integer> lowHist = new TreeMap<>();
        Map<String, Integer> solidHist = new TreeMap<>();
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            String id = e.getKey();
            if (CityMap.isBuilt(id) && isLowCover(id)) {
                lowBlocks += e.getValue();
                lowHist.put(id, e.getValue());
            }
            if (isSolidLow(id)) {
                solidLowBlocks += e.getValue();
                solidHist.put(id, e.getValue());
            } else if (CityMap.isBuilt(id) && isSeeThroughLow(id)) {
                seeLowBlocks += e.getValue();
            } else if (CityMap.isBuilt(id) && isFlatDecoration(id)) {
                flatBlocks += e.getValue();
            }
        }
        int exposedCells = 0;
        int usableCells = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    if (coverCell(v, x, y, z, 3)) {
                        exposedCells++;
                        if (coverCell(v, x, y, z, 4)) {
                            usableCells++;
                        }
                    }
                }
            }
        }
        int[] v1 = coverLines(v, 1);
        int[] v2 = coverLines(v, 2);
        int[] v3 = coverLines(v, 3);
        int[] v4 = coverLines(v, 4);
        System.out.println("## LOW_RAW: lowIshBlocks=" + lowBlocks + " solidLowBlocks=" + solidLowBlocks
                + " solidLowExposedCells=" + exposedCells + " solidLowUsableCells=" + usableCells
                + " seeThroughLowBlocks=" + seeLowBlocks + " flatDecorationBlocks=" + flatBlocks);
        System.out.println("## LOW_RAW_hist: " + topOf(lowHist, 16));
        System.out.println("## LOW_SOLID_hist: " + topOf(solidHist, 16));
        System.out.println("## COVER_LINES_V1_allLowIsh: X=" + v1[0] + "/" + v1[1] + "bl/len" + v1[2]
                + " Z=" + v1[3] + "/" + v1[4] + "bl/len" + v1[5]
                + " total=" + (v1[0] + v1[3]));
        System.out.println("## COVER_LINES_V2_solidLowOnly: X=" + v2[0] + "/" + v2[1] + "bl/len" + v2[2]
                + " Z=" + v2[3] + "/" + v2[4] + "bl/len" + v2[5]
                + " total=" + (v2[0] + v2[3]));
        System.out.println("## COVER_LINES_V3_solidExposed: X=" + v3[0] + "/" + v3[1] + "bl/len" + v3[2]
                + " Z=" + v3[3] + "/" + v3[4] + "bl/len" + v3[5]
                + " total=" + (v3[0] + v3[3]));
        System.out.println("## COVER_LINES_V4_USABLE: X=" + v4[0] + "/" + v4[1] + "bl/len" + v4[2]
                + " Z=" + v4[3] + "/" + v4[4] + "bl/len" + v4[5]
                + " total=" + (v4[0] + v4[3]) + " blocksInLines=" + (v4[1] + v4[4])
                + " longest=" + Math.max(v4[2], v4[5]));
        // legacy line, kept so the earlier report can be reconciled
        System.out.println("## LOW_COVER: lowCoverBlocks=" + lowBlocks + " linesX_ge3=" + v1[0]
                + " blocksInLinesX=" + v1[1] + " longestLineX=" + v1[2]
                + " linesZ_ge3=" + v1[3] + " blocksInLinesZ=" + v1[4]
                + " longestLineZ=" + v1[5]);
        System.out.println("## LOW_COVER_hist: " + topOf(lowHist, 16));

        // ---------------- 3 see-through cover
        int seeThrough = 0;
        Map<String, Integer> seeHist = new TreeMap<>();
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            String id = e.getKey();
            if (SEE_THROUGH.contains(id) || id.endsWith("_pane")
                    || id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane")
                    || id.endsWith("_leaves") || id.endsWith("_fence") || id.endsWith("_fence_gate")) {
                seeThrough += e.getValue();
                seeHist.put(id, e.getValue());
            }
        }
        System.out.println("## SEE_THROUGH: seeThroughBlocks=" + seeThrough);
        System.out.println("## SEE_THROUGH_hist: " + topOf(seeHist, 16));

        // ---------------- 4 doorways and 1-wide gaps
        Map<String, Integer> doorFacing = new TreeMap<>();
        Map<String, Integer> doorKind = new TreeMap<>();
        int doors = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    String id = v.at(x, y, z);
                    if (id.endsWith("_door") || id.endsWith("_trapdoor")
                            || id.endsWith("_fence_gate")) {
                        doors++;
                        String facing = v.prop(x, y, z, "facing");
                        doorFacing.merge(String.valueOf(facing), 1, Integer::sum);
                        String half = v.prop(x, y, z, "half");
                        doorKind.merge(id + (half == null ? "" : "[" + half + "]"), 1, Integer::sum);
                    }
                }
            }
        }
        int gaps = countOneWideGaps(v);
        System.out.println("## DOORS: doorBlocks=" + doors + " byFacing=" + doorFacing);
        System.out.println("## DOOR_KINDS: " + topOf(doorKind, 12));
        System.out.println("## ONE_WIDE_GAPS: walkableFullHeightGapsInSolidWalls=" + gaps);

        // ---------------- 5 verticality
        int stairs = 0;
        int ladders = 0;
        int slabs = 0;
        Map<String, Integer> vertHist = new TreeMap<>();
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            String id = e.getKey();
            if (id.endsWith("_stairs")) {
                stairs += e.getValue();
                vertHist.merge(id, e.getValue(), Integer::sum);
            }
            if (id.equals("ladder") || id.equals("scaffolding") || id.equals("vine")
                    || id.equals("chain")) {
                ladders += e.getValue();
                vertHist.merge(id, e.getValue(), Integer::sum);
            }
            if (id.endsWith("_slab")) {
                slabs += e.getValue();
            }
        }
        Map<Integer, Integer> ladderY = new TreeMap<>();
        int ladderColumns = 0;
        for (int x = v.x0; x < v.x0 + v.sx; x++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                boolean any = false;
                for (int y = yLo; y <= yHi; y++) {
                    String id = v.at(x, y, z);
                    if (id.equals("ladder") || id.equals("scaffolding")) {
                        ladderY.merge(y, 1, Integer::sum);
                        any = true;
                    }
                }
                if (any) {
                    ladderColumns++;
                }
            }
        }
        Map<Integer, Integer> levelHist = walkableLevels(v);
        List<Integer> floors = localMaxima(levelHist);
        System.out.println("## VERTICALITY: stairs=" + stairs + " slabs=" + slabs
                + " laddersAndScaffolding=" + ladders + " ladderColumns=" + ladderColumns);
        System.out.println("## VERTICALITY_hist: " + topOf(vertHist, 12));
        System.out.println("## ladderBlocksPerY: " + ladderY);
        System.out.println("## walkableCellsPerY: " + levelHist);
        System.out.println("## INFERRED_floorLevels(local maxima of walkableCellsPerY)=" + floors);
        System.out.println("## INFERRED_floorCount=" + floors.size());

        // ---------------- 6 interiors
        Map<String, Integer> interiorHist = new TreeMap<>();
        int interiors = 0;
        int modBlocks = 0;
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            if (INTERIOR.contains(e.getKey())) {
                interiors += e.getValue();
                interiorHist.put(e.getKey(), e.getValue());
            }
            if (e.getKey().startsWith("tarkovscav:")) {
                modBlocks += e.getValue();
                interiorHist.put(e.getKey(), e.getValue());
            }
        }
        System.out.println("## INTERIOR: interiorBlocks=" + interiors
                + " tarkovscavBlocks=" + modBlocks);
        System.out.println("## INTERIOR_hist: " + topOf(interiorHist, 18));

        // ---------------- 7 cover density
        int[] density = coverDensity(v, radius);
        System.out.printf("## COVER_DENSITY: radius=%d walkableCells=%d cellsWithCoverWithin=%d "
                + "coverDensity=%.4f walkableLevels=%d%n",
                radius, density[0], density[1], density[0] == 0 ? 0.0 : (double) density[1] / density[0],
                density[2]);

        System.out.println("## OPEN_GROUND: groundColumns=" + density[3]
                + " columnsWithNoCoverOnGround=" + density[4]
                + " openGroundFraction=" + (density[3] == 0 ? "0"
                        : String.format("%.4f", (double) density[4] / density[3])));

        if (opt.containsKey("--signature")) {
            System.out.println("## SIGNATURE_footprintGE2=" + signature(v));
        }
        if (opt.containsKey("--yhist")) {
            System.out.println("## YHIST");
            for (String token : opt.get("--yhist").split(",")) {
                int y = Integer.parseInt(token.trim());
                Map<String, Integer> hist = new TreeMap<>();
                for (int z = v.z0; z < v.z0 + v.sz; z++) {
                    for (int x = v.x0; x < v.x0 + v.sx; x++) {
                        hist.merge(v.at(x, y, z), 1, Integer::sum);
                    }
                }
                System.out.println("##   y=" + y + " " + topOf(hist, 8) + " totalCells="
                        + (v.sx * v.sz));
            }
        }
        if (opt.containsKey("--fullhist")) {
            System.out.println("## FULLHIST " + byName.size() + " distinct blocks, all counts:");
            byName.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.println("##   " + e.getKey() + " x" + e.getValue()));
        }
        if (opt.containsKey("--find")) {
            for (String token : opt.get("--find").split(",")) {
                String want = token.trim();
                List<int[]> hits = v.find(want);
                System.out.println("## FIND " + want + " count=" + hits.size());
                StringBuilder line = new StringBuilder("##   pos: ");
                for (int i = 0; i < hits.size(); i++) {
                    int[] p = hits.get(i);
                    line.append("(").append(p[0]).append(",").append(p[1]).append(",").append(p[2])
                            .append(") ");
                    if (line.length() > 900) {
                        System.out.println(line);
                        line = new StringBuilder("##   pos: ");
                    }
                }
                if (line.length() > 10) {
                    System.out.println(line);
                }
            }
        }
        System.out.println("## END " + label);
    }

    static String topOf(Map<String, Integer> hist, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(hist.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            sb.append(list.get(i).getKey()).append(" x").append(list.get(i).getValue()).append("; ");
        }
        return sb.toString();
    }

    /**
     * Straight runs of three or more low-cover blocks at one Y, along X and along Z separately.
     * Returns {linesX, blocksInLinesX, longestX, linesZ, blocksInLinesZ, longestZ}.
     */
    static int[] countLowCoverLines(Voxel v) {
        int linesX = 0, blocksX = 0, longestX = 0;
        int linesZ = 0, blocksZ = 0, longestZ = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                int run = 0;
                for (int x = v.x0; x <= v.x0 + v.sx; x++) {
                    boolean low = x < v.x0 + v.sx && isLowCover(v.at(x, y, z));
                    if (low) {
                        run++;
                    } else {
                        if (run >= 3) {
                            linesX++;
                            blocksX += run;
                            longestX = Math.max(longestX, run);
                        }
                        run = 0;
                    }
                }
            }
            for (int x = v.x0; x < v.x0 + v.sx; x++) {
                int run = 0;
                for (int z = v.z0; z <= v.z0 + v.sz; z++) {
                    boolean low = z < v.z0 + v.sz && isLowCover(v.at(x, y, z));
                    if (low) {
                        run++;
                    } else {
                        if (run >= 3) {
                            linesZ++;
                            blocksZ += run;
                            longestZ = Math.max(longestZ, run);
                        }
                        run = 0;
                    }
                }
            }
        }
        return new int[]{linesX, blocksX, longestX, linesZ, blocksZ, longestZ};
    }

    /** Walkable cells at any Y whose two opposite horizontal neighbours are full-height blockers. */
    static int countOneWideGaps(Voxel v) {
        int gaps = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    String here = v.at(x, y, z);
                    if (!here.endsWith("air")) {
                        continue;   // a real opening, not a slab, torch or carpet at this level
                    }
                    if (!v.at(x, y + 1, z).endsWith("air")) {
                        continue;   // must be full height
                    }
                    if (v.at(x, y - 1, z).endsWith("air") || v.at(x, y - 1, z).equals("?")) {
                        continue;   // must stand on a floor
                    }
                    boolean wallNS = isFullBlocker(v.at(x - 1, y, z)) && isFullBlocker(v.at(x + 1, y, z));
                    boolean wallEW = isFullBlocker(v.at(x, y, z - 1)) && isFullBlocker(v.at(x, y, z + 1));
                    if (wallNS ^ wallEW) {
                        gaps++;
                    }
                }
            }
        }
        return gaps;
    }

    /** Walkable cells per Y: passable with headroom and a solid support below. */
    static Map<Integer, Integer> walkableLevels(Voxel v) {
        Map<Integer, Integer> hist = new TreeMap<>();
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    if (isWalkable(v, x, y, z)) {
                        hist.merge(y, 1, Integer::sum);
                    }
                }
            }
        }
        return hist;
    }

    static boolean isWalkable(Voxel v, int x, int y, int z) {
        String here = v.at(x, y, z);
        if (!CityMap.isPassable(here)) {
            return false;
        }
        if (!CityMap.isPassable(v.at(x, y + 1, z))) {
            return false;
        }
        String below = v.at(x, y - 1, z);
        return !below.equals("?") && !below.endsWith("air") && !CityMap.isPassable(below);
    }

    static List<Integer> localMaxima(Map<Integer, Integer> hist) {
        List<Integer> out = new ArrayList<>();
        List<Integer> keys = new ArrayList<>(hist.keySet());
        for (int i = 0; i < keys.size(); i++) {
            int y = keys.get(i);
            int c = hist.get(y);
            if (c < 16) {
                continue;
            }
            int prev = i > 0 ? hist.get(keys.get(i - 1)) : 0;
            int next = i + 1 < keys.size() ? hist.get(keys.get(i + 1)) : 0;
            if (c > prev && c >= next && (out.isEmpty() || y - out.get(out.size() - 1) >= 2)) {
                out.add(y);
            }
        }
        return out;
    }

    /**
     * For every walkable cell, is there a cover block (full blocker, low cover, see-through barrier)
     * within Chebyshev radius r in XZ at the cell's own Y or one above? Returns
     * {walkableCells, cellsWithCover, distinctWalkableLevels, groundCells, groundCellsWithoutCover}.
     */
    static int[] coverDensity(Voxel v, int r) {
        int cells = 0, withCover = 0, groundCells = 0, openGround = 0;
        Set<Integer> levels = new java.util.TreeSet<>();
        for (int y = yLo; y <= yHi; y++) {
            for (int z = v.z0; z < v.z0 + v.sz; z++) {
                for (int x = v.x0; x < v.x0 + v.sx; x++) {
                    if (!isWalkable(v, x, y, z)) {
                        continue;
                    }
                    cells++;
                    levels.add(y);
                    boolean cover = false;
                    for (int dx = -r; dx <= r && !cover; dx++) {
                        for (int dz = -r; dz <= r && !cover; dz++) {
                            if (dx == 0 && dz == 0) {
                                continue;
                            }
                            for (int dy = 0; dy <= 1 && !cover; dy++) {
                                String id = v.at(x + dx, y + dy, z + dz);
                                if (id.equals("?") || id.endsWith("air")) {
                                    continue;
                                }
                                if (!CityMap.isBuilt(id)) {
                                    continue;   // worldgen terrain is cover too, but it is not BUILT cover
                                }
                                if (isFullBlocker(id) || isLowCover(id)
                                        || SEE_THROUGH.contains(id)
                                        || id.endsWith("_door") || id.endsWith("_trapdoor")) {
                                    cover = true;
                                }
                            }
                        }
                    }
                    if (cover) {
                        withCover++;
                    }
                    if (y == CityMap.groundY + 1) {
                        groundCells++;
                        if (!cover) {
                            openGround++;
                        }
                    }
                }
            }
        }
        return new int[]{cells, withCover, levels.size(), groundCells, openGround};
    }

    /** FNV-1a hash of the built-height-above-2 footprint mask, to compare two districts exactly. */
    static String signature(Voxel v) {
        long hash = 0xcbf29ce484222325L;
        StringBuilder sb = new StringBuilder();
        for (int z = v.z0; z < v.z0 + v.sz; z++) {
            for (int x = v.x0; x < v.x0 + v.sx; x++) {
                int h = CityMap.builtHeight(v, x, z);
                char c = h >= 3 ? '#' : (h == 2 ? '+' : (h == 1 ? '.' : ' '));
                sb.append(c);
                hash ^= c;
                hash *= 0x100000001b3L;
            }
        }
        return Long.toHexString(hash) + " maskWritten=" + sb.length();
    }

    private ObstacleScan() {
    }
}
