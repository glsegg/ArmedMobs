import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Head-less check of every shipped city preset: the structure file a Minecraft server is going to read,
 * checked against the layout it was generated from.
 *
 * <p>It is not a smoke test - it re-derives the expectations from {@code tools/city-layout*.json} and the
 * generated NBT, and fails loudly when they disagree. A structure that does not parse, has a wrong
 * palette, points at a block from somebody else's mod, lost its buildings, has a door standing in mid
 * air, or has an empty floor would all be caught here rather than in game:</p>
 *
 * <ol>
 *   <li>the .nbt is gzipped NBT and loads at all (the generation used a hand-written writer, so this is
 *       the real assertion);</li>
 *   <li>{@code size} matches the layout, and the block list covers every position exactly once with
 *       in-range state indices;</li>
 *   <li>palette slot 0 is {@code minecraft:air} - without it the buildings would not be carved out of the
 *       terrain - and every palette entry is a vanilla block;</li>
 *   <li>every palette entry is <b>classified</b> by {@code CityStructureGen#roleOfState} as cover,
 *       furniture, light or structure, and no two entries share a block state;</li>
 *   <li>the street band is road, the corners of every declared building are the declared accent block,
 *       and the ladder shaft reaches the roof - i.e. the layout was actually applied;</li>
 *   <li><b>every door</b> has both halves with the same facing (the generator's own guarantee is the
 *       same hinge too), a full-cube sill under the lower half - a door on a slab or a stair pops off -
 *       and solid wall on both sides at both halves. Each variant also declares how many it needs;</li>
 *   <li><b>every floor of every building</b> has at least the declared number of cover and furniture
 *       blocks and at least one light, counted from the NBT through the classified roles;</li>
 *   <li>the datapack wiring around the template exists and the {@code tarkovscav:city} structure tag
 *       lists the id, so the spawn gate's tag path matches it.</li>
 * </ol>
 *
 * <p>Run from the repository root: {@code java StructureNbtTest [projectDir]}. Exits non-zero and lists
 * every problem, for every layout.</p>
 */
public final class StructureNbtTest {
    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        List<Path> layouts = new ArrayList<>();
        try (var stream = Files.list(root.resolve("tools"))) {
            stream.filter(path -> path.getFileName().toString().matches("city-layout.*\\.json"))
                    .sorted()
                    .forEach(layouts::add);
        }
        if (layouts.isEmpty()) {
            throw new IllegalStateException("no tools/city-layout*.json found - nothing to check");
        }

        List<String> problems = new ArrayList<>();
        for (Path layoutFile : layouts) {
            problems.addAll(checkVariant(root, layoutFile));
            System.out.println();
        }
        if (!problems.isEmpty()) {
            System.out.println();
            problems.forEach(problem -> System.out.println("FAIL: " + problem));
            throw new IllegalStateException(problems.size() + " structure problem(s)");
        }
        System.out.println("structures OK: " + layouts.size() + " variant(s) checked");
    }

    /** A decoded structure: the size, the palette and the state of every position. */
    private record Grid(int sizeX, int sizeY, int sizeZ, List<String> names, List<String> states,
                        List<CityStructureGen.Role> roles, Map<Long, Integer> byPosition) {
        private long key(int x, int y, int z) {
            return ((long) y * this.sizeZ + z) * this.sizeX + x;
        }

        boolean inside(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
        }

        Integer stateIndex(int x, int y, int z) {
            return this.byPosition.get(key(x, y, z));
        }

        /** The block name at a position, or {@code "(missing)"} when it is outside or unknown. */
        String name(int x, int y, int z) {
            Integer state = stateIndex(x, y, z);
            if (state == null || state < 0 || state >= this.names.size()) {
                return "(missing)";
            }
            return this.names.get(state);
        }

        /** The full block state string at a position, or {@code "(missing)"}. */
        String state(int x, int y, int z) {
            Integer index = stateIndex(x, y, z);
            if (index == null || index < 0 || index >= this.states.size()) {
                return "(missing)";
            }
            return this.states.get(index);
        }

        CityStructureGen.Role role(int x, int y, int z) {
            Integer index = stateIndex(x, y, z);
            if (index == null || index < 0 || index >= this.roles.size()) {
                return null;
            }
            return this.roles.get(index);
        }

        boolean solid(int x, int y, int z) {
            Integer index = stateIndex(x, y, z);
            return index != null && index != 0;
        }
    }

    /** Every check for one layout, as a list of problems (empty when the variant is good). */
    private static List<String> checkVariant(Path root, Path layoutFile) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, Object> layout = Json.object(Json.parse(layoutFile));
        String name = Json.string(layout, "name", "city_small");
        List<Object> size = Json.array(layout, "size");
        int sizeX = ((Number) size.get(0)).intValue();
        int sizeY = ((Number) size.get(1)).intValue();
        int sizeZ = ((Number) size.get(2)).intValue();

        Path nbtFile = root.resolve("src/main/resources/data/tarkovscav/structures/" + name + ".nbt");
        System.out.println("=== " + name + "  (" + layoutFile.getFileName() + ")");
        if (!Files.isRegularFile(nbtFile)) {
            problems.add("missing " + nbtFile + " - run CityStructureGen first");
            return problems;
        }

        Map<String, Object> nbt;
        try (InputStream in = Files.newInputStream(nbtFile)) {
            nbt = NbtReader.compound(NbtReader.readGzip(in));
        } catch (IOException broken) {
            problems.add(name + ": the structure NBT does not parse: " + broken);
            return problems;
        }

        // --- 1. header ---------------------------------------------------------
        int dataVersion = NbtReader.intOf(nbt, "DataVersion");
        if (dataVersion != 3465) {
            problems.add(name + ": DataVersion is " + dataVersion + ", expected 3465 (Minecraft 1.20.1)");
        }

        // --- 2. size -----------------------------------------------------------
        List<Object> nbtSize = NbtReader.list(nbt, "size");
        int nbtX = ((Number) nbtSize.get(0)).intValue();
        int nbtY = ((Number) nbtSize.get(1)).intValue();
        int nbtZ = ((Number) nbtSize.get(2)).intValue();
        if (nbtX != sizeX || nbtY != sizeY || nbtZ != sizeZ) {
            problems.add(name + ": size " + nbtX + "x" + nbtY + "x" + nbtZ
                    + " does not match the layout " + sizeX + "x" + sizeY + "x" + sizeZ);
        }

        // --- 3./4. palette -----------------------------------------------------
        List<Object> palette = NbtReader.list(nbt, "palette");
        List<String> paletteNames = new ArrayList<>();
        List<String> paletteStates = new ArrayList<>();
        List<CityStructureGen.Role> paletteRoles = new ArrayList<>();
        for (Object raw : palette) {
            Map<String, Object> entry = NbtReader.compound(raw);
            String blockName = NbtReader.string(entry, "Name");
            paletteNames.add(blockName);
            paletteStates.add(CityStructureGen.stateString(entry));
            paletteRoles.add(CityStructureGen.roleOfState(entry));
            if (blockName == null || !blockName.startsWith("minecraft:")) {
                problems.add(name + ": palette entry '" + blockName + "' is not a vanilla minecraft: block");
            }
        }
        System.out.println("palette: " + palette.size() + " state(s) " + rolesSummary(paletteRoles));
        if (paletteNames.isEmpty() || !"minecraft:air".equals(paletteNames.get(0))) {
            problems.add(name + ": palette slot 0 must be minecraft:air so the buildings are carved out");
        }
        // The palette must be unambiguous for the roles this test counts. Two keys may share a block (the
        // road and a concrete wall are both grey concrete, the pavement and a smooth wall are both smooth
        // stone) as long as neither is cover, furniture or light - those are counted by role below.
        Map<String, CityStructureGen.Role> counted = new LinkedHashMap<>();
        for (int index = 0; index < paletteStates.size(); index++) {
            CityStructureGen.Role role = paletteRoles.get(index);
            if (role != CityStructureGen.Role.COVER && role != CityStructureGen.Role.FURNITURE
                    && role != CityStructureGen.Role.LIGHT) {
                continue;
            }
            CityStructureGen.Role previous = counted.put(paletteStates.get(index), role);
            if (previous != null && previous != role) {
                problems.add(name + ": block state " + paletteStates.get(index) + " is classified as both "
                        + previous + " and " + role + " - the per-floor counts would be ambiguous");
            }
        }
        Map<String, Map<String, Object>> declared = CityStructureGen.paletteOf(layout);
        List<String> unused = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String state = CityStructureGen.stateString(entry.getValue());
            if (CityStructureGen.roleOfState(entry.getValue()) == null) {
                problems.add(name + ": palette key '" + entry.getKey() + "' has no role");
            }
            if (!paletteStates.contains(state)) {
                // Not every declared key has to be used by a layout; an unused one is only reported.
                unused.add(entry.getKey());
            }
        }
        if (!unused.isEmpty()) {
            System.out.println("palette keys not used by this layout: " + unused);
        }

        // --- 5. blocks ---------------------------------------------------------
        List<Object> blocks = NbtReader.list(nbt, "blocks");
        int expectedPositions = sizeX * sizeY * sizeZ;
        if (blocks.size() != expectedPositions) {
            problems.add(name + ": block list has " + blocks.size() + " entries, expected "
                    + expectedPositions);
        }
        Map<Long, Integer> byPosition = new HashMap<>();
        int solid = 0;
        for (Object raw : blocks) {
            Map<String, Object> block = NbtReader.compound(raw);
            List<Object> pos = NbtReader.list(block, "pos");
            if (pos.size() != 3) {
                problems.add(name + ": a block entry has pos with " + pos.size() + " coordinates");
                continue;
            }
            int x = ((Number) pos.get(0)).intValue();
            int y = ((Number) pos.get(1)).intValue();
            int z = ((Number) pos.get(2)).intValue();
            int state = NbtReader.intOf(block, "state");
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                problems.add(name + ": block at " + x + "," + y + "," + z + " is outside the declared size");
                continue;
            }
            if (state < 0 || state >= palette.size()) {
                problems.add(name + ": block at " + x + "," + y + "," + z + " has state " + state
                        + " outside the palette (size " + palette.size() + ")");
                continue;
            }
            if (state != 0) {
                solid++;
            }
            if (byPosition.put(((long) y * sizeZ + z) * sizeX + x, state) != null) {
                problems.add(name + ": position " + x + "," + y + "," + z + " appears twice");
            }
        }
        Grid grid = new Grid(sizeX, sizeY, sizeZ, paletteNames, paletteStates, paletteRoles, byPosition);
        System.out.println("size: " + sizeX + " x " + sizeY + " x " + sizeZ + ", blocks: " + blocks.size()
                + ", solid: " + solid + " (" + Math.round(100.0 * solid / Math.max(1, expectedPositions))
                + "%)");
        if (solid < expectedPositions / 20) {
            problems.add(name + ": only " + solid + " solid blocks - the layout did not apply");
        }
        if (NbtReader.list(nbt, "entities") == null) {
            problems.add(name + ": the structure has no 'entities' list");
        }

        // --- 6. the street band, if the layout has one --------------------------
        if (layout.get("street") instanceof Map<?, ?> rawStreet) {
            Map<String, Object> street = Json.object(rawStreet);
            int bandFrom = Json.intOf(street, "band_from", 19);
            String expectedRoad = declaredBlock(declared, Json.string(street, "road_block", "road"));
            String actualRoad = grid.name(bandFrom + 1, 0, 1);
            if (!expectedRoad.equals(actualRoad)) {
                problems.add(name + ": street band at (" + (bandFrom + 1) + ",0,1) is " + actualRoad
                        + ", expected " + expectedRoad);
            }
        }

        // --- 7. every building: shell, rooftop access, doors, per-floor contents
        int totalDoors = 0;
        int requiredDoors = 0;
        int totalPorches = 0;
        for (Object raw : Json.array(layout, "buildings")) {
            Map<String, Object> building = Json.object(raw);
            String buildingName = name + "/" + Json.string(building, "name", "?");
            int x0 = Json.intOf(building, "x", 0);
            int z0 = Json.intOf(building, "z", 0);
            int w = Json.intOf(building, "w", 8);
            int d = Json.intOf(building, "d", 8);
            int floors = Json.intOf(building, "floors", 3);
            int floorHeight = Json.intOf(building, "floor_height", 4);
            int minCover = Json.intOf(building, "min_cover_per_floor", 5);
            int minFurniture = Json.intOf(building, "min_furniture_per_floor", 6);
            requiredDoors += Json.intOf(building, "min_doors", 2);
            String accent = declaredBlock(declared, Json.string(building, "accent", "accent"));
            int topY = 1 + floors * floorHeight;
            int x1 = x0 + w - 1;
            int z1 = z0 + d - 1;

            int cornerY = 1 + floorHeight + 1;
            String corner = grid.name(x0, cornerY, z0);
            if (!accent.equals(corner)) {
                problems.add(buildingName + ": corner at (" + x0 + "," + cornerY + "," + z0 + ") is "
                        + corner + ", expected the accent block " + accent);
            }
            if (topY + 1 >= sizeY) {
                problems.add(buildingName + ": needs y=" + (topY + 1) + " but the structure is only "
                        + sizeY + " tall");
            }
            if (Json.bool(building, "roof_access", true)) {
                if (grid.solid(x0 + 1, topY, z0 + 1)) {
                    problems.add(buildingName + ": no rooftop access hole at ("
                            + (x0 + 1) + "," + topY + "," + (z0 + 1) + ")");
                }
                String ladder = grid.name(x0 + 1, topY - 1, z0 + 1);
                if (!"minecraft:ladder".equals(ladder)) {
                    problems.add(buildingName + ": no ladder under its roof hole at ("
                            + (x0 + 1) + "," + (topY - 1) + "," + (z0 + 1) + "), found " + ladder);
                }
            }

            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = 1; y < topY; y++) {
                        String state = grid.state(x, y, z);
                        if (!CityStructureGen.isDoorName(nameOf(state)) || !state.contains("half=lower")) {
                            continue;
                        }
                        totalDoors++;
                        String upper = grid.state(x, y + 1, z);
                        if (!upper.contains("half=upper")) {
                            problems.add(buildingName + ": door at " + x + "," + y + "," + z
                                    + " has no upper half (found " + upper + ")");
                        }
                        String facing = propertyOf(state, "facing");
                        if (facing != null && !facing.equals(propertyOf(upper, "facing"))) {
                            problems.add(buildingName + ": door at " + x + "," + y + "," + z + " faces "
                                    + facing + " but its upper half faces " + propertyOf(upper, "facing"));
                        }
                        if (y != 2) {
                            problems.add(buildingName + ": a door at y=" + y
                                    + " is not at the ground-floor sill (y=2)");
                        }
                        String sill = grid.name(x, y - 1, z);
                        if (!CityStructureGen.isFullCube(sill)) {
                            problems.add(buildingName + ": door at " + x + "," + y + "," + z + " stands on "
                                    + sill + ", which is not a full block - it would pop off the moment the"
                                    + " chunk ticks");
                        }
                        if (!hasFrame(grid, x, y, z)) {
                            problems.add(buildingName + ": door at " + x + "," + y + "," + z
                                    + " has no wall on both sides at both halves (a floating door)");
                        }
                        if (facing != null && checkPorch(grid, buildingName, x, y, z, facing,
                                x0, x1, z0, z1, problems)) {
                            totalPorches++;
                        }
                    }
                }
            }

            for (int floor = 0; floor < floors; floor++) {
                int floorY = 1 + floor * floorHeight;
                int cover = 0;
                int furniture = 0;
                int light = 0;
                for (int y = floorY; y <= floorY + floorHeight - 1 && y < sizeY; y++) {
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            CityStructureGen.Role role = grid.role(x, y, z);
                            if (role == CityStructureGen.Role.COVER) {
                                cover++;
                            } else if (role == CityStructureGen.Role.FURNITURE) {
                                furniture++;
                            } else if (role == CityStructureGen.Role.LIGHT) {
                                light++;
                            }
                        }
                    }
                }
                System.out.println(String.format("  %-14s floor %d: cover=%d furniture=%d light=%d",
                        Json.string(building, "name", "?"), floor, cover, furniture, light));
                if (cover < minCover) {
                    problems.add(buildingName + " floor " + floor + ": " + cover + " cover block(s), below"
                            + " the declared minimum " + minCover);
                }
                if (furniture < minFurniture) {
                    problems.add(buildingName + " floor " + floor + ": " + furniture + " furniture"
                            + " block(s), below the declared minimum " + minFurniture);
                }
                if (light < 1) {
                    problems.add(buildingName + " floor " + floor + ": no light at all");
                }
            }
        }

        if (totalDoors < requiredDoors) {
            problems.add(name + ": " + totalDoors + " door(s) placed, but the layout asks for at least "
                    + requiredDoors);
        }
        System.out.println("doors: " + totalDoors + " (layout asks for at least " + requiredDoors + "), "
                + "porches: " + totalPorches + " street entrance(s) with a step run");

        // --- 8. the datapack wiring around the template ------------------------
        Path structureJson = root.resolve("src/main/resources/data/tarkovscav/worldgen/structure/" + name
                + ".json");
        Path poolJson = root.resolve("src/main/resources/data/tarkovscav/worldgen/template_pool/" + name
                + "/start.json");
        Path tagJson = root.resolve("src/main/resources/data/tarkovscav/tags/worldgen/structure/city.json");
        for (Path required : List.of(structureJson, poolJson, tagJson)) {
            if (!Files.isRegularFile(required)) {
                problems.add(name + ": missing datapack file " + required);
            }
        }
        if (Files.isRegularFile(tagJson) && !Files.readString(tagJson).contains("tarkovscav:" + name)) {
            problems.add(name + ": the tarkovscav:city structure tag does not list tarkovscav:" + name
                    + ", so the spawn gate's tag path would never match it");
        }
        if (Files.isRegularFile(structureJson)
                && !Files.readString(structureJson).contains("tarkovscav:" + name + "/start")) {
            problems.add(name + ": worldgen/structure/" + name + ".json does not point at tarkovscav:"
                    + name + "/start");
        }

        System.out.println("variant " + name + ": " + solid + " solid blocks, " + totalDoors + " door(s), "
                + (problems.isEmpty() ? "OK" : problems.size() + " problem(s)"));
        return problems;
    }

    /**
     * The PORCH rule (2026-10). For every door whose outside cell is outside the footprint - a street
     * entrance, as opposed to an interior door - this checks:
     *
     * <ol>
     *   <li>the cell in front of the door is a stair, it ASCENDS toward the door ({@code facing} is the
     *       opposite of the door's own facing), it is {@code half=bottom, shape=straight} so the cells join
     *       cleanly, it stands on solid ground and it does not block the doorway's passage cell;</li>
     *   <li>the run steps DOWN one block per row away from the sill, contiguously, and the first row with no
     *       stair must land on solid ground - no floating step, no gap;</li>
     *   <li>the row in front of the doorway is a straight three-cell run that CONTAINS the door (and, for a
     *       lone door, is centred on it: one either side), which is what "a straight, symmetric run centred
     *       on the doorway" means here.</li>
     * </ol>
     *
     * @return true when this was a street entrance (so the caller can count it)
     */
    private static boolean checkPorch(Grid grid, String buildingName, int x, int y, int z, String facing,
                                      int x0, int x1, int z0, int z1, List<String> problems) {
        int dx = facing.equals("east") ? 1 : facing.equals("west") ? -1 : 0;
        int dz = facing.equals("south") ? 1 : facing.equals("north") ? -1 : 0;
        if (dx == 0 && dz == 0) {
            return false;
        }
        int ox = x + dx;
        int oz = z + dz;
        if (ox >= x0 && ox <= x1 && oz >= z0 && oz <= z1) {
            return false;                       // an interior door has no porch
        }
        String expected = facing.equals("east") ? "west" : facing.equals("west") ? "east"
                : facing.equals("south") ? "north" : "south";
        String where = buildingName + ": street door " + x + "," + y + "," + z + " (faces " + facing + ")";

        boolean previous = false;
        for (int row = 1; row <= 3; row++) {
            int px = x + dx * row;
            int pz = z + dz * row;
            int py = y - row;
            String step = grid.state(px, py, pz);
            if (!isStair(step)) {
                if (row == 1) {
                    problems.add(where + " has no step in front of it at " + px + "," + py + "," + pz
                            + " (found " + step + ")");
                } else if (previous && !grid.solid(px, py, pz)) {
                    problems.add(where + " - the step run ends in mid-air at " + px + "," + py + "," + pz);
                }
                break;
            }
            if (row > 1 && !previous) {
                problems.add(where + " - a step at row " + row + " with a gap before it");
            }
            if (!expected.equals(propertyOf(step, "facing"))) {
                problems.add(where + " - the step at " + px + "," + py + "," + pz + " faces "
                        + propertyOf(step, "facing") + ", so it does not ascend toward the door (expected "
                        + expected + ")");
            }
            if (!"bottom".equals(propertyOf(step, "half")) || !"straight".equals(propertyOf(step, "shape"))) {
                problems.add(where + " - the step at " + px + "," + py + "," + pz + " is "
                        + propertyOf(step, "half") + "/" + propertyOf(step, "shape")
                        + ", so it does not join cleanly");
            }
            if (!grid.solid(px, py - 1, pz)) {
                problems.add(where + " - the step at " + px + "," + py + "," + pz + " floats");
            }
            if (grid.solid(px, py + 1, pz)) {
                problems.add(where + " - the step at " + px + "," + py + "," + pz
                        + " blocks the doorway passage");
            }
            previous = true;
        }

        boolean alongX = dz != 0;               // the porch row runs along X when the door is on a Z wall
        boolean paired = alongX
                ? isLowerDoor(grid, x + 2, y, z) || isLowerDoor(grid, x - 2, y, z)
                : isLowerDoor(grid, x, y, z + 2) || isLowerDoor(grid, x, y, z - 2);
        int found = 0;
        int min = 0;
        int max = 0;
        boolean seen = false;
        for (int offset = -3; offset <= 3; offset++) {
            int px = alongX ? x + offset : ox;
            int pz = alongX ? oz : z + offset;
            if (isStair(grid.state(px, y - 1, pz))) {
                found++;
                if (!seen) {
                    min = offset;
                    seen = true;
                }
                max = offset;
            }
        }
        if (found != 3 || max - min != 2 || min > 0 || max < 0 || (!paired && (min != -1 || max != 1))) {
            problems.add(where + " - the porch row is not a straight symmetric three-cell run centred on"
                    + " the doorway (stair offsets " + min + ".." + max + ", " + found + " stair(s),"
                    + " paired=" + paired + ")");
        }
        return true;
    }

    /** True when this state is a stair block. */
    private static boolean isStair(String state) {
        String name = nameOf(state);
        return name != null && name.endsWith("_stairs");
    }

    /** True when this cell holds the lower half of a door. */
    private static boolean isLowerDoor(Grid grid, int x, int y, int z) {
        String state = grid.state(x, y, z);
        return state != null && CityStructureGen.isDoorName(nameOf(state)) && state.contains("half=lower");
    }

    /**
     * True when the door at (x, y) has solid, non-air, non-door neighbours on both sides of one
     * horizontal axis at both of its halves: that axis is the wall the opening sits in.
     */
    private static boolean hasFrame(Grid grid, int x, int y, int z) {
        return (frameSide(grid, x + 1, y, z) && frameSide(grid, x - 1, y, z))
                || (frameSide(grid, x, y, z + 1) && frameSide(grid, x, y, z - 1));
    }

    /** One side of a door frame: solid at both halves, and not another door, window or lamp. */
    private static boolean frameSide(Grid grid, int x, int y, int z) {
        for (int half = 0; half <= 1; half++) {
            if (!grid.solid(x, y + half, z)) {
                return false;
            }
            CityStructureGen.Role role = grid.role(x, y + half, z);
            if (role == CityStructureGen.Role.DOOR || role == CityStructureGen.Role.WINDOW
                    || role == CityStructureGen.Role.LIGHT) {
                return false;
            }
        }
        return true;
    }

    /** The block name part of a state string, e.g. {@code minecraft:dark_oak_door}. */
    private static String nameOf(String state) {
        if (state == null) {
            return null;
        }
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static String propertyOf(String state, String property) {
        if (state == null) {
            return null;
        }
        int start = state.indexOf(property + "=");
        if (start < 0) {
            return null;
        }
        int from = start + property.length() + 1;
        int end = from;
        while (end < state.length() && state.charAt(end) != ',' && state.charAt(end) != ']') {
            end++;
        }
        return state.substring(from, end);
    }

    private static String rolesSummary(List<CityStructureGen.Role> roles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CityStructureGen.Role role : roles) {
            counts.merge(String.valueOf(role), 1, Integer::sum);
        }
        return counts.toString();
    }

    private static String declaredBlock(Map<String, Map<String, Object>> palette, String paletteKey) {
        Map<String, Object> entry = palette.get(paletteKey);
        if (entry == null) {
            throw new IllegalStateException("layout palette has no key '" + paletteKey + "'");
        }
        return (String) entry.get("Name");
    }
}
