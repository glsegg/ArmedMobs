package com.gfl.tarkovscav.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The buildings that make up one placed city instance - the unit a faction belongs to.
 *
 * <h2>Where the building footprints come from</h2>
 * <ul>
 *   <li><b>Several pieces:</b> a jigsaw that is assembled from one element per building (the district)
 *       already tells us the answer - each {@link StructurePiece}'s world bounding box IS a building.</li>
 *   <li><b>One piece, with a baked map:</b> the shipped single-piece cities (the whole city is one NBT) ship
 *       a {@code data/<namespace>/city_buildings/<structure>.json} written by
 *       {@code tools/spike/CityStructureGen} straight from the layout, so the map cannot drift from the
 *       structure it describes. It holds one rectangle per building in <b>template-local</b>
 *       coordinates.</li>
 *   <li><b>One piece, no map:</b> the whole city is one building. That is the correct fallback for another
 *       mod's structure, a hand-made {@code cityRegions} box, or a runtime {@code city place} instance.</li>
 * </ul>
 *
 * <h2>Local to world, without guessing the rotation convention</h2>
 * <p>A jigsaw root piece gets a random 90-degree {@link Rotation} ({@code JigsawPlacement} rolls it), so a
 * local rectangle has to be rotated before it can be matched against world positions. Instead of
 * re-deriving Minecraft's rotation signs, the mapping is taken from the piece's own world bounding box:
 * the template's local box is {@code [0..sx-1] x [0..sy-1] x [0..sz-1]}, and a 90-degree rotation maps that
 * box onto the piece's box, so each world axis counts either up or down from one of the piece's corners.
 * Whether the rotation swaps the horizontal axes is read from the sign pattern of
 * {@link StructureTemplate#transform} applied to the local diagonal - only the <em>difference</em> is used,
 * which is independent of the pivot convention.</p>
 */
public final class CityBuildings {
    /** One building of one city instance: a stable id and its world box. */
    public record Building(String id, BoundingBox box) {
    }

    /** Parsed map for one structure id; immutable and shared. */
    private record Map2(int sizeX, int sizeY, int sizeZ, List<Entry> entries) {
    }

    /** One rectangle as baked by the generator, template-local and inclusive. */
    private record Entry(String id, int x, int z, int w, int d) {
    }

    /** Parsed maps by structure id. The files are shipped resources; they cannot change while running. */
    private static final Map<ResourceLocation, Map2> CACHE = new HashMap<>();

    private CityBuildings() {
    }

    /**
     * The buildings of one placed structure start, in world coordinates, in the generator's layout order.
     * Never returns null; an empty list means "no building could be told apart" and the caller treats the
     * whole city as one unit.
     */
    public static List<Building> forStart(ServerLevel level, ResourceLocation structureId, StructureStart start) {
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty()) {
            return List.of();
        }
        if (pieces.size() > 1) {
            List<Building> out = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                out.add(new Building("p" + i, pieces.get(i).getBoundingBox()));
            }
            return out;
        }
        StructurePiece piece = pieces.get(0);
        List<Building> mapped = fromMap(level, structureId, piece);
        if (!mapped.isEmpty()) {
            return mapped;
        }
        return List.of(new Building("city", piece.getBoundingBox()));
    }

    /** The shipped per-building rectangles of one structure, rotated into the piece's world box. */
    private static List<Building> fromMap(ServerLevel level, ResourceLocation structureId, StructurePiece piece) {
        Map2 map = load(level, structureId);
        if (map == null || piece.getMirror() != Mirror.NONE) {
            return List.of();
        }
        return fromMap(map, piece.getBoundingBox(), piece.getRotation());
    }

    private static List<Building> fromMap(Map2 map, BoundingBox world, Rotation rotation) {
        BlockPos delta = StructureTemplate.transform(
                        new BlockPos(map.sizeX() - 1, 0, map.sizeZ() - 1), Mirror.NONE, rotation, BlockPos.ZERO)
                .subtract(StructureTemplate.transform(BlockPos.ZERO, Mirror.NONE, rotation, BlockPos.ZERO));
        boolean swapped = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        boolean xUp = delta.getX() > 0;
        boolean zUp = delta.getZ() > 0;

        List<Building> out = new ArrayList<>(map.entries().size());
        for (Entry entry : map.entries()) {
            int lx1 = entry.x();
            int lz1 = entry.z();
            int lx2 = entry.x() + entry.w() - 1;
            int lz2 = entry.z() + entry.d() - 1;
            int wx1 = xUp ? world.minX() + (swapped ? lz1 : lx1) : world.maxX() - (swapped ? lz1 : lx1);
            int wx2 = xUp ? world.minX() + (swapped ? lz2 : lx2) : world.maxX() - (swapped ? lz2 : lx2);
            int wz1 = zUp ? world.minZ() + (swapped ? lx1 : lz1) : world.maxZ() - (swapped ? lx1 : lz1);
            int wz2 = zUp ? world.minZ() + (swapped ? lx2 : lz2) : world.maxZ() - (swapped ? lx2 : lz2);
            out.add(new Building(entry.id(), BoundingBox.fromCorners(
                    new BlockPos(Math.min(wx1, wx2), world.minY(), Math.min(wz1, wz2)),
                    new BlockPos(Math.max(wx1, wx2), world.maxY(), Math.max(wz1, wz2)))));
        }
        return out;
    }

    /** The baked map of one structure id, or null when the pack ships none (or it cannot be read). */
    private static Map2 load(ServerLevel level, ResourceLocation structureId) {
        Map2 cached = CACHE.get(structureId);
        if (cached != null) {
            return cached;
        }
        ResourceLocation file = new ResourceLocation(structureId.getNamespace(),
                "city_buildings/" + structureId.getPath() + ".json");
        List<Resource> stack = level.getServer().getResourceManager().getResourceStack(file);
        if (stack.isEmpty()) {
            return null;
        }
        Map2 parsed = null;
        try (BufferedReader reader = stack.get(stack.size() - 1).openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root != null && root.isJsonObject()) {
                parsed = parse(root.getAsJsonObject());
            }
        } catch (Exception exception) {
            return null;
        }
        if (parsed != null) {
            CACHE.put(structureId, parsed);
        }
        return parsed;
    }

    private static Map2 parse(JsonObject json) {
        JsonArray size = json.getAsJsonArray("size");
        if (size == null || size.size() < 3) {
            return null;
        }
        int sizeX = size.get(0).getAsInt();
        int sizeY = size.get(1).getAsInt();
        int sizeZ = size.get(2).getAsInt();
        JsonArray buildings = json.getAsJsonArray("buildings");
        if (buildings == null) {
            return null;
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonElement raw : buildings) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject building = raw.getAsJsonObject();
            String id = building.has("id") ? building.get("id").getAsString()
                    : Integer.toString(entries.size());
            entries.add(new Entry(id,
                    building.get("x").getAsInt(), building.get("z").getAsInt(),
                    building.get("w").getAsInt(), building.get("d").getAsInt()));
        }
        return new Map2(sizeX, sizeY, sizeZ, entries);
    }

    /** Clears the parse cache; called on a datapack reload so an edited map is picked up. */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * Which building a position belongs to: the footprint that CONTAINS it, or - when it is in the street
     * between buildings - the nearest footprint. Returns -1 only when the list is empty.
     *
     * <p>The nearest-footprint fallback is deliberate: a spawner is always inside a building (the generator
     * puts it on a floor against an interior wall), so containment decides those, while a garrison spot or a
     * natural spawn in the street takes the faction of the building it is closest to. That is the smallest
     * rule that answers "which building is this" for every position inside a city.</p>
     */
    public static int indexAt(List<Building> buildings, BlockPos pos) {
        int best = -1;
        for (int i = 0; i < buildings.size(); i++) {
            if (buildings.get(i).box().isInside(pos)) {
                return i;
            }
        }
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < buildings.size(); i++) {
            double distance = CityGate.distanceToBox(buildings.get(i).box(), pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
