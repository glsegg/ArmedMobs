package com.gfl.tarkovscav.worldgen;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Puts a city district on the ground <b>right now</b>, out of the very same structure NBT files the
 * worldgen structure draws from (README section 7, {@code /tarkovscav city district [seed]}).
 *
 * <h2>Why this exists</h2>
 * <p>World generation only ever touches chunks that have not been generated yet, so an existing save would
 * never show the district. This class is the "see it today" path: same pieces, same pools, placed around the
 * player. What that means honestly: the <b>pieces</b> are identical and the <b>pools</b> are read from the
 * same JSON files, but the arrangement is this class's own simple grid, not the jigsaw engine's random walk -
 * so a hand-placed district and a naturally generated one are made of the same buildings and look like the
 * same city, but they are not the same layout.</p>
 *
 * <h2>The layout</h2>
 * <ol>
 *   <li>an {@code n x n} grid of street tiles (default 3x3, so 48x48 blocks of pavement), each one rotated
 *       by a deterministic amount drawn from {@code seed};</li>
 *   <li>a building in the cell next to a street, on every side that faces out of the grid - each with a
 *       {@code chance} so the result is not a perfect checkerboard;</li>
 *   <li>the two rubble piles on the free corners of some street tiles (same chance);</li>
 *   <li>every piece is placed on the terrain height of its own anchor column, minus its own foundation, so
 *       the 5-block footings bury themselves in a slope instead of floating.</li>
 * </ol>
 */
public final class CityDistrictAssembler {

    /** A piece and how far its walkable floor sits above the bottom of its own NBT. */
    private record Piece(String path, int floorOffset, int sizeX, int sizeZ) { }

    private static final List<Piece> STREETS = List.of(
            new Piece("buildings/street_tile_a", 3, 16, 16),
            new Piece("buildings/street_tile_b", 3, 16, 16));
    private static final List<Piece> BUILDINGS = List.of(
            new Piece("buildings/building_a1", 5, 16, 16),
            new Piece("buildings/building_a2", 5, 16, 16),
            new Piece("buildings/building_b1", 5, 16, 16),
            new Piece("buildings/building_b2", 5, 16, 16),
            new Piece("buildings/gen_district_b1", 5, 16, 17),
            new Piece("buildings/gen_district_b2", 5, 17, 16));
    private static final List<Piece> DECOR = List.of(
            new Piece("buildings/decor_rubble_west", 0, 16, 11),
            new Piece("buildings/decor_rubble_east", 0, 11, 9));

    /** The pools the worldgen structure draws from - the command reads the very same files. */
    private static final String STREET_POOL = "tarkovscav:city_district/street";
    private static final String BUILDING_POOL = "tarkovscav:city_district/building";
    private static final String DECOR_POOL = "tarkovscav:city_district/decor";
    /** The footing the shipped pieces were baked with (extractor: 5 for buildings, 3 for streets). */
    private static final int BAKED_BUILDING_DEPTH = 5;

    private CityDistrictAssembler() {
    }

    /**
     * The pieces of one pool, read out of the SAME {@code template_pool/city_district/*.json} files the
     * worldgen structure uses, so the command cannot drift away from natural generation. Falls back to the
     * built-in list when a resource cannot be read (a reload in progress, a hand-made jar).
     */
    static List<Piece> poolPieces(ServerLevel level, String pool, List<Piece> fallback) {
        try {
            var resource = level.getServer().getResourceManager().getResource(poolResource(pool));
            if (resource.isEmpty()) { return fallback; }
            try (var reader = resource.get().openAsReader()) {
                var json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                List<Piece> pieces = new ArrayList<>();
                for (var element : json.getAsJsonArray("elements")) {
                    String location = element.getAsJsonObject().getAsJsonObject("element")
                            .get("location").getAsString();
                    if (!location.startsWith(TarkovScav.MOD_ID + ":")) { continue; }
                    String path = location.substring(TarkovScav.MOD_ID.length() + 1);
                    pieces.add(fallback.stream().filter(p -> p.path().equals(path)).findFirst()
                            .orElse(new Piece(path, 5, 16, 16)));
                }
                return pieces.isEmpty() ? fallback : pieces;
            }
        } catch (Exception failure) {
            TarkovScav.LOGGER.warn("[city] could not read pool {} ({}), using the built-in piece list",
                    pool, failure.toString());
            return fallback;
        }
    }

    static ResourceLocation poolResource(String pool) {
        ResourceLocation id = new ResourceLocation(pool);
        return new ResourceLocation(id.getNamespace(), "worldgen/template_pool/" + id.getPath() + ".json");
    }

    static int[] gridCoordinates(int gridSize) {
        int min = -(gridSize / 2);
        return IntStream.range(min, min + gridSize).toArray();
    }

    static BlockPos tileOrigin(BlockPos anchor, int surface, int floorOffset, Vec3i size, Rotation rotation) {
        BlockPos corner = new BlockPos(anchor.getX(), surface - floorOffset, anchor.getZ());
        return StructureTemplate.getZeroPositionWithTransform(corner, Mirror.NONE, rotation,
                size.getX(), size.getZ());
    }

    /** The result of one placement, for the command's chat line. */
    public record Result(long seed, int gridSize, int streets, int buildings, int decor, String bounds) { }

    /**
     * Places a district centred on {@code centre}.
     *
     * @param gridSize     how many street tiles across (3 = 48x48 blocks of pavement)
     * @param buildingOdds chance (0..1) that a free side of the grid gets a building
     * @param decorOdds    chance (0..1) that a street tile gets a rubble pile
     */
    public static Result place(ServerLevel level, BlockPos centre, long seed, int gridSize,
                               double buildingOdds, double decorOdds) {
        RandomSource random = RandomSource.create(seed);
        StructureTemplateManager templates = level.getStructureManager();
        // Read the pools the worldgen structure uses, so the command and natural generation place the same
        // pieces (the built-in lists are only a fallback).
        List<Piece> streets = poolPieces(level, STREET_POOL, STREETS);
        List<Piece> buildingsPool = poolPieces(level, BUILDING_POOL, BUILDINGS);
        List<Piece> decorPool = poolPieces(level, DECOR_POOL, DECOR);
        TarkovScav.LOGGER.info("[city] district pools: streets={} buildings={} decor={} foundationDepth={}",
                streets.size(), buildingsPool.size(), decorPool.size(), Config.cityFoundationDepth());
        // The footing is BAKED into each piece (extractor: 5 for buildings, 3 for streets; generator:
        // --foundation N). This key is the number those pieces are supposed to carry, so a mismatch is a
        // warning rather than a silent surprise: the fix is to re-extract / re-generate with --foundation N.
        if (Config.cityFoundationDepth() != BAKED_BUILDING_DEPTH) {
            TarkovScav.LOGGER.warn("[city] spawn.foundationDepth = {} but the pieces in the jar are baked with "
                            + "{} blocks of footing; re-run the extractor or CityStructureGen --pieces-only "
                            + "--foundation {} to match",
                    Config.cityFoundationDepth(), BAKED_BUILDING_DEPTH, Config.cityFoundationDepth());
        }

        int[] cells = gridCoordinates(gridSize);
        int minCell = cells[0];
        int maxCell = cells[cells.length - 1];
        int placedStreets = 0;
        int placedBuildings = 0;
        int placedDecor = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        // A road grid first: one tile per cell, rotated deterministically.
        for (int gx : cells) {
            for (int gz : cells) {
                int x = centre.getX() + gx * 16;
                int z = centre.getZ() + gz * 16;
                Piece street = streets.get(random.nextInt(streets.size()));
                if (place(level, templates, street, new BlockPos(x, 0, z),
                        Rotation.values()[random.nextInt(4)], random)) {
                    placedStreets++;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + 15);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z + 15);

                // Decor on the street's free corners.
                if (random.nextDouble() < decorOdds) {
                    Piece decor = decorPool.get(random.nextInt(decorPool.size()));
                    int dx = x + 2 + random.nextInt(8);
                    int dz = z + 2 + random.nextInt(8);
                    if (place(level, templates, decor, new BlockPos(dx, 0, dz),
                            Rotation.values()[random.nextInt(4)], random)) {
                        placedDecor++;
                    }
                }
            }
        }

        // Then the buildings, on the ring of cells just outside the grid, facing it.
        for (int gx = minCell - 1; gx <= maxCell + 1; gx++) {
            for (int gz = minCell - 1; gz <= maxCell + 1; gz++) {
                boolean inside = gx >= minCell && gx <= maxCell && gz >= minCell && gz <= maxCell;
                if (inside) { continue; }
                if (random.nextDouble() >= buildingOdds) { continue; }
                int x = centre.getX() + gx * 16;
                int z = centre.getZ() + gz * 16;
                Piece building = buildingsPool.get(random.nextInt(buildingsPool.size()));
                // Face the road: the building's own connector is on its north side, so rotate it towards
                // the grid centre and the entrance ends up on the street.
                Rotation rotation = facingTheGrid(gx, gz);
                if (place(level, templates, building, new BlockPos(x, 0, z), rotation, random)) {
                    placedBuildings++;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + 15);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z + 15);
            }
        }

        Result result = new Result(seed, gridSize, placedStreets, placedBuildings, placedDecor,
                "x[" + minX + ".." + maxX + "] z[" + minZ + ".." + maxZ + "]");
        TarkovScav.LOGGER.info("[city] district placed by command: seed={} grid={} streets={} buildings={} "
                + "decor={} bounds={}", seed, gridSize, placedStreets, placedBuildings, placedDecor,
                result.bounds());
        return result;
    }

    /**
     * Which way a building has to be turned so that its north-side entrance points at the road grid.
     * The grid centre is at grid (0,0); a cell north of it (negative gz) must face south, and so on.
     */
    static Rotation facingTheGrid(int gx, int gz) {
        if (gz < 0 && Math.abs(gz) >= Math.abs(gx)) { return Rotation.CLOCKWISE_180; }
        if (gz > 0 && Math.abs(gz) >= Math.abs(gx)) { return Rotation.NONE; }
        if (gx < 0) { return Rotation.COUNTERCLOCKWISE_90; }
        return Rotation.CLOCKWISE_90;
    }

    /** Places one piece on the terrain height of its anchor column. Returns false when the file is missing. */
    private static boolean place(ServerLevel level, StructureTemplateManager templates, Piece piece,
                                 BlockPos anchor, Rotation rotation, RandomSource random) {
        Optional<StructureTemplate> loaded = templates.get(new ResourceLocation(TarkovScav.MOD_ID, piece.path()));
        if (loaded.isEmpty()) {
            TarkovScav.LOGGER.warn("[city] structure {} is missing from the jar - district incomplete",
                    piece.path());
            return false;
        }
        StructureTemplate template = loaded.get();
        Vec3i size = template.getSize();
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, anchor.getX(), anchor.getZ());
        BlockPos origin = tileOrigin(anchor, surface, piece.floorOffset(), size, rotation);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setIgnoreEntities(true)
                .setKeepLiquids(false);
        return template.placeInWorld(level, origin, origin, settings, random, 2);
    }

    /** The piece paths, for the command's listing and for the gate. */
    public static List<String> piecePaths() {
        List<String> out = new ArrayList<>();
        STREETS.forEach(p -> out.add(p.path()));
        BUILDINGS.forEach(p -> out.add(p.path()));
        DECOR.forEach(p -> out.add(p.path()));
        return out;
    }
}
