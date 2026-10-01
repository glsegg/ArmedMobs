package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a position counts as "inside a city".
 *
 * <p>Three mechanisms, any of which is enough:</p>
 *
 * <ol>
 *   <li><b>Structure instances, read from the chunk.</b> {@code spawn.cityStructureIds} and
 *       {@code spawn.cityStructureTags} name the structures; the gate then walks the
 *       {@code StructureStart}s registered on the chunk containing the position and asks whether any
 *       of them matches and covers the position. This is the path that also works for a structure
 *       <b>placed with a command</b> - see the note below.</li>
 *   <li><b>Structure instances, read through the structure manager.</b>
 *       {@code StructureManager#getStructureWithPieceAt} with the configured key or tag. This is the
 *       canonical worldgen lookup and the one that applies {@code cityRegionPadding} to pieces.</li>
 *   <li><b>Explicit boxes.</b> {@code spawn.cityRegions} holds hand-made areas.</li>
 * </ol>
 *
 * <h2>Why mechanism 1 is not redundant</h2>
 * <p>{@code StructureManager#startsForStructure} - the only thing {@code getStructureAt} and
 * {@code getStructureWithPieceAt} consult - reads the chunk's <b>structure references</b>
 * ({@code ChunkAccess#getReferencesForStructure}), which worldgen writes during
 * {@code ChunkStatus.STRUCTURE_REFERENCES}. {@code /place structure} does <em>not</em> write them: it
 * calls {@code setStartForStructure} and {@code placeInChunk} only. So a city placed with
 * {@code /place structure} - exactly the workflow for editing the preset by hand - is invisible to
 * mechanism 2 while sitting right there in the world. Reading {@code LevelChunk#getAllStarts} covers
 * it. Both mechanisms are kept because they see slightly different things: 1 sees command-placed
 * starts, 2 sees worldgen pieces and applies the padding ring properly.</p>
 *
 * <h2>The limitation that remains</h2>
 * <p>A building pasted with {@code /place template}, or built by hand, is not a structure instance at
 * all and neither mechanism can see it. That is what {@code cityRegions} /
 * {@code /tarkovscav city add} are for. The third mechanism - a runtime instance placed with
 * {@code /tarkovscav city place} - is handled before all of them; see {@link CityStructures} for why a
 * file dropped into {@code tarkovscav/city/} can never become a worldgen structure.</p>
 */
public final class CityGate {
    /** Radial sample count used to apply {@code cityRegionPadding} around structure pieces. */
    private static final int PADDING_SAMPLES = 8;

    private CityGate() {
    }

    /** Outcome of a gate test, with a human-readable reason for the log and the debug command. */
    public record Result(boolean allowed, String reason) {
    }

    public static Result test(ServerLevel level, BlockPos pos) {
        List<String> ids = normalised(Config.CITY_STRUCTURE_IDS.get());
        List<String> tags = normalised(Config.CITY_STRUCTURE_TAGS.get());
        List<? extends String> regions = Config.CITY_REGIONS.get();
        int padding = Config.CITY_REGION_PADDING.get();

        // ---- 0. runtime city instances ------------------------------------------------------
        // Structures the user imported from their world with /tarkovscav city import and placed with
        // /tarkovscav city place. They are not worldgen structures - they cannot be, see CityStructures
        // - so their boxes are recorded when they are placed and consulted here, before anything else.
        // This is checked even when nothing is configured at all, because placing one is an explicit
        // "this is a city" action.
        CityStructures.Placed instance = CityStructures.instanceAt(level, pos);
        if (instance != null) {
            return new Result(true, "inside placed city structure '" + instance.name() + "' at "
                    + instance.origin().toShortString());
        }

        if (ids.isEmpty() && tags.isEmpty() && regions.isEmpty()) {
            // Nothing configured at all: refusing everything would make the mobs vanish, so say so
            // loudly and let them spawn. The default config ships a city, so this is an edge case.
            return new Result(true, "no city areas configured - gate disabled");
        }

        // ---- 1. structure starts registered on this chunk (covers /place structure) ----------
        Result fromChunk = matchChunkStarts(level, pos, ids, tags, padding);
        if (fromChunk != null) {
            return fromChunk;
        }

        // ---- 2. the canonical worldgen lookup, with padding -------------------------------
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                continue;
            }
            Result hit = matchStructure(level, pos, ResourceKey.create(Registries.STRUCTURE, location),
                    "structure " + id, padding);
            if (hit != null) {
                return hit;
            }
        }
        for (String tag : tags) {
            ResourceLocation location = ResourceLocation.tryParse(stripHash(tag));
            if (location == null) {
                continue;
            }
            Result hit = matchStructure(level, pos, TagKey.create(Registries.STRUCTURE, location),
                    "structure tag #" + location, padding);
            if (hit != null) {
                return hit;
            }
        }

        // ---- 3. explicit boxes ------------------------------------------------------------
        for (String entry : regions) {
            CityRegion region = CityRegion.parse(entry);
            if (region == null) {
                continue;
            }
            if (region.contains(level.dimension().location(), pos)) {
                return new Result(true, "city region '" + region.name() + "'");
            }
            if (region.inflated(padding).contains(level.dimension().location(), pos)) {
                return new Result(true, "within " + padding + " blocks of city region '" + region.name() + "'");
            }
        }

        return new Result(false, "not inside any configured city ("
                + ids.size() + " structure id(s), " + tags.size() + " tag(s), "
                + regions.size() + " region(s) checked)");
    }

    public static boolean isCityArea(ServerLevel level, BlockPos pos) {
        return test(level, pos).allowed();
    }

    // ------------------------------------------------------------------ city identity (the garrison)

    /**
     * One city area with the <b>identity</b> the one-time garrison is bookkept under.
     *
     * <p>{@code key} is stable for a given generated city: it is the structure id (or region/runtime
     * name) plus the city box's centre rounded to 16 blocks, so two visits to the same city produce the
     * same string and two different cities 200 blocks apart never do. The rounding is what makes the key
     * survive a chunk reload, a server restart and a rebuild of the same structure: a bounding box that
     * shifts by a block or two still lands on the same centre cell.</p>
     */
    public record Area(String key, String name, BoundingBox box, List<CityBuildings.Building> buildings) {
        /**
         * A city with no building breakdown: the whole box is one unit. Used by the paths that can see a
         * box but no jigsaw pieces (a hand-made {@code cityRegions} entry, a runtime placed instance).
         */
        public Area(String key, String name, BoundingBox box) {
            this(key, name, box, List.of());
        }

        public Area {
            if (buildings == null) {
                buildings = List.of();
            }
        }

        /** The centre used both for the key and for the "is the player near it" test. */
        public BlockPos centre() {
            return new BlockPos((box.minX() + box.maxX()) >> 1, (box.minY() + box.maxY()) >> 1,
                    (box.minZ() + box.maxZ()) >> 1);
        }

        public int width() {
            return box.maxX() - box.minX() + 1;
        }

        public int depth() {
            return box.maxZ() - box.minZ() + 1;
        }
    }

    /**
     * Every city area within {@code radius} blocks of {@code pos}, in this dimension.
     *
     * <p>This is the garrison's discovery step, and it is deliberately a <b>separate method</b> from
     * {@link #test}: the spawn gate asks "may a mob exist here?" once per spawn attempt and must stay
     * cheap, while the garrison asks "which cities are near this player?" once per coarse interval and
     * needs the box, not just a yes/no.</p>
     *
     * <p>Only <b>loaded</b> chunks are inspected ({@code requireChunk = false}), so a player standing in
     * the wilderness never causes a chunk load; a city whose origin chunk is not loaded simply is not
     * found yet, and will be on the next check once the player is closer.</p>
     */
    public static List<Area> citiesNear(ServerLevel level, BlockPos pos, int radius) {
        List<Area> found = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int padding = Math.max(0, Config.CITY_REGION_PADDING.get());
        int reach = Math.max(0, radius) + padding;
        ResourceLocation dimension = level.dimension().location();

        // ---- 0. runtime instances (placed with /tarkovscav city place) --------------------
        for (CityStructures.Placed placed : CityStructures.placed(level.getServer())) {
            if (!placed.dimension().equals(dimension) || distanceToBox(placed.box(), pos) > reach) {
                continue;
            }
            addArea(found, seen, new Area("runtime/" + placed.name() + "/" + centreKey(placed.box()),
                    placed.name(), placed.box()));
        }

        List<String> ids = normalised(Config.CITY_STRUCTURE_IDS.get());
        List<String> tags = normalised(Config.CITY_STRUCTURE_TAGS.get());

        // ---- 1. structure starts on the loaded chunks around the player -------------------
        // Plus half a structure's worth of slack: a jigsaw's start lives on the chunk that generated it,
        // which can be a few chunks behind the pieces the player is standing next to.
        int chunkRadius = (reach >> 4) + 2;
        int centreChunkX = pos.getX() >> 4;
        int centreChunkZ = pos.getZ() >> 4;
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                var raw = level.getChunkSource().getChunk(centreChunkX + dx, centreChunkZ + dz,
                        net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
                if (!(raw instanceof LevelChunk chunk)) {
                    continue;
                }
                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    StructureStart start = entry.getValue();
                    if (start == null || !start.isValid() || start.getPieces().isEmpty()) {
                        continue;
                    }
                    ResourceLocation id = registry.getKey(entry.getKey());
                    if (id == null || !matches(registry, entry.getKey(), ids, tags)) {
                        continue;
                    }
                    BoundingBox box = start.getBoundingBox();
                    if (distanceToBox(box, pos) > reach) {
                        continue;
                    }
                    addArea(found, seen, new Area("structure/" + id + "/" + centreKey(box),
                            id.toString(), box, CityBuildings.forStart(level, id, start)));
                }
            }
        }

        // ---- 2. explicit boxes (they carry no worldgen structure at all) ------------------
        for (String entry : Config.CITY_REGIONS.get()) {
            CityRegion region = CityRegion.parse(entry);
            if (region == null || !region.dimension().equals(dimension)) {
                continue;
            }
            BoundingBox box = BoundingBox.fromCorners(
                    BlockPos.containing(region.box().minX, region.box().minY, region.box().minZ),
                    BlockPos.containing(region.box().maxX, region.box().maxY, region.box().maxZ));
            if (distanceToBox(box, pos) > reach) {
                continue;
            }
            addArea(found, seen, new Area("region/" + region.name() + "/" + centreKey(box),
                    "region " + region.name(), box));
        }

        return found;
    }

    /**
     * The city area a position is inside, with its building breakdown, or null when it is not in one.
     *
     * <p>This is the <b>same membership decision</b> as {@link #test} - same order (runtime instance, chunk
     * starts, structure manager with padding, explicit regions), same configured ids and tags, same padding -
     * but it returns the identity of the city instead of a yes/no. The natural-spawn rule asks it so that
     * "which building is this mob in" and "is this a city at all" cannot disagree; the two keys it produces
     * are byte-identical to {@link #citiesNear}'s, because both derive them from the same bounding box.</p>
     */
    @Nullable
    public static Area areaAt(ServerLevel level, BlockPos pos) {
        CityStructures.Placed instance = CityStructures.instanceAt(level, pos);
        if (instance != null) {
            return new Area("runtime/" + instance.name() + "/" + centreKey(instance.box()),
                    instance.name(), instance.box());
        }

        List<String> ids = normalised(Config.CITY_STRUCTURE_IDS.get());
        List<String> tags = normalised(Config.CITY_STRUCTURE_TAGS.get());
        List<? extends String> regions = Config.CITY_REGIONS.get();
        if (ids.isEmpty() && tags.isEmpty() && regions.isEmpty()) {
            return null;
        }
        int padding = Config.CITY_REGION_PADDING.get();

        Area fromChunk = matchChunkStartArea(level, pos, ids, tags, padding);
        if (fromChunk != null) {
            return fromChunk;
        }
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                continue;
            }
            Area hit = structureArea(level, pos, ResourceKey.create(Registries.STRUCTURE, location), padding);
            if (hit != null) {
                return hit;
            }
        }
        for (String tag : tags) {
            ResourceLocation location = ResourceLocation.tryParse(stripHash(tag));
            if (location == null) {
                continue;
            }
            Area hit = tagArea(level, pos, TagKey.create(Registries.STRUCTURE, location), padding);
            if (hit != null) {
                return hit;
            }
        }
        for (String entry : regions) {
            CityRegion region = CityRegion.parse(entry);
            if (region == null) {
                continue;
            }
            if (region.contains(level.dimension().location(), pos)
                    || region.inflated(padding).contains(level.dimension().location(), pos)) {
                BoundingBox box = BoundingBox.fromCorners(
                        BlockPos.containing(region.box().minX, region.box().minY, region.box().minZ),
                        BlockPos.containing(region.box().maxX, region.box().maxY, region.box().maxZ));
                return new Area("region/" + region.name() + "/" + centreKey(box),
                        "region " + region.name(), box);
            }
        }
        return null;
    }

    /** The chunk-start path of {@link #areaAt}, mirroring {@link #matchChunkStarts}. */
    @Nullable
    private static Area matchChunkStartArea(ServerLevel level, BlockPos pos, List<String> ids,
                                            List<String> tags, int padding) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        LevelChunk chunk = level.getChunkAt(pos);
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid() || start.getPieces().isEmpty()) {
                continue;
            }
            ResourceLocation id = registry.getKey(entry.getKey());
            if (id == null || !matches(registry, entry.getKey(), ids, tags)) {
                continue;
            }
            BoundingBox box = start.getBoundingBox();
            if (!box.isInside(pos) && !(padding > 0 && box.inflatedBy(padding).isInside(pos))) {
                continue;
            }
            return structureArea(level, start, id);
        }
        return null;
    }

    /** The structure-manager path of {@link #areaAt}, with the same padding ring {@link #test} uses. */
    @Nullable
    private static Area structureArea(ServerLevel level, BlockPos pos, ResourceKey<Structure> key, int padding) {
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, key);
        if (isUsable(start)) {
            return structureArea(level, start, key.location());
        }
        if (padding <= 0) {
            return null;
        }
        for (int i = 0; i < PADDING_SAMPLES; i++) {
            double angle = (Math.PI * 2.0D * i) / PADDING_SAMPLES;
            BlockPos sample = pos.offset(
                    (int) Math.round(Math.cos(angle) * padding), 0, (int) Math.round(Math.sin(angle) * padding));
            StructureStart sampled = level.structureManager().getStructureWithPieceAt(sample, key);
            if (isUsable(sampled)) {
                return structureArea(level, sampled, key.location());
            }
        }
        return null;
    }

    /** The tag path of {@link #areaAt}: each member structure of the tag is asked by its own id. */
    @Nullable
    private static Area tagArea(ServerLevel level, BlockPos pos, TagKey<Structure> key, int padding) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var holders = registry.getTag(key);
        if (holders.isEmpty()) {
            return null;
        }
        for (Holder<Structure> holder : holders.get()) {
            var id = holder.unwrapKey();
            if (id.isEmpty()) {
                continue;
            }
            Area hit = structureArea(level, pos, id.get(), padding);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** One structure start as an Area, with its building breakdown, in the same key shape as citiesNear. */
    private static Area structureArea(ServerLevel level, StructureStart start, ResourceLocation id) {
        BoundingBox box = start.getBoundingBox();
        return new Area("structure/" + id + "/" + centreKey(box), id.toString(), box,
                CityBuildings.forStart(level, id, start));
    }

    /** The stable half of a city key: the box centre snapped to a 16-block cell. */
    public static String centreKey(BoundingBox box) {
        return ((box.minX() + box.maxX()) >> 1) / 16 + "," + ((box.minY() + box.maxY()) >> 1) / 16 + ","
                + ((box.minZ() + box.maxZ()) >> 1) / 16;
    }

    private static void addArea(List<Area> found, List<String> seen, Area area) {
        if (seen.contains(area.key())) {
            return;
        }
        seen.add(area.key());
        found.add(area);
    }

    /** Shortest distance from {@code pos} to the box; 0 when the position is inside it. */
    public static double distanceToBox(BoundingBox box, BlockPos pos) {
        double dx = Math.max(Math.max(box.minX() - pos.getX(), pos.getX() - box.maxX()), 0);
        double dy = Math.max(Math.max(box.minY() - pos.getY(), pos.getY() - box.maxY()), 0);
        double dz = Math.max(Math.max(box.minZ() - pos.getZ(), pos.getZ() - box.maxZ()), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Diagnostic report of what the chunk under {@code pos} actually knows about structures.
     *
     * <p>Written because "the gate says no even though the city is right there" has exactly three
     * possible causes - no structure start on the chunk, a start whose bounding box does not cover
     * the position, or a chunk that only has structure <em>references</em> and no start - and the
     * three are indistinguishable from the outside. {@code /tarkovscav city test} prints this.</p>
     */
    public static String describeChunkStarts(ServerLevel level, BlockPos pos) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        LevelChunk chunk = level.getChunkAt(pos);

        StringBuilder builder = new StringBuilder();
        builder.append("chunk ").append(chunk.getPos().x).append(',').append(chunk.getPos().z)
                .append(" starts=").append(chunk.getAllStarts().size());

        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            builder.append(" | ").append(registry.getKey(entry.getKey()))
                    .append(" valid=").append(start != null && start.isValid())
                    .append(" pieces=").append(start == null ? -1 : start.getPieces().size())
                    .append(" box=").append(start == null ? "null" : start.getBoundingBox());
        }

        StringBuilder references = new StringBuilder();
        for (Structure structure : registry) {
            var refs = chunk.getReferencesForStructure(structure);
            if (!refs.isEmpty()) {
                references.append(' ').append(registry.getKey(structure)).append(':').append(refs.size());
            }
        }
        builder.append(" references=[").append(references.toString().trim()).append(']');
        return builder.toString();
    }

    /**
     * Walks the structure starts stored on the chunk containing the position. This is the only path
     * that sees a structure placed with {@code /place structure}.
     */
    private static Result matchChunkStarts(ServerLevel level, BlockPos pos, List<String> ids,
                                           List<String> tags, int padding) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        LevelChunk chunk = level.getChunkAt(pos);

        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid() || start.getPieces().isEmpty()) {
                continue;
            }
            ResourceLocation id = registry.getKey(structure);
            if (id == null || !matches(registry, structure, ids, tags)) {
                continue;
            }

            BoundingBox box = start.getBoundingBox();
            if (box.isInside(pos)) {
                return new Result(true, "inside structure " + id + " (chunk start)");
            }
            if (padding > 0 && box.inflatedBy(padding).isInside(pos)) {
                return new Result(true, "within " + padding + " blocks of structure " + id + " (chunk start)");
            }
        }
        return null;
    }

    /** True when the structure is one of the configured ids, or carries one of the configured tags. */
    private static boolean matches(Registry<Structure> registry, Structure structure,
                                  List<String> ids, List<String> tags) {
        ResourceLocation id = registry.getKey(structure);
        if (id == null) {
            return false;
        }
        if (ids.contains(id.toString())) {
            return true;
        }
        if (tags.isEmpty()) {
            return false;
        }
        Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
        if (holder == null) {
            return false;
        }
        for (String tag : tags) {
            ResourceLocation tagId = ResourceLocation.tryParse(stripHash(tag));
            if (tagId != null && holder.is(TagKey.create(Registries.STRUCTURE, tagId))) {
                return true;
            }
        }
        return false;
    }

    /** True when the position is inside this exact structure (or within padding of a piece of it). */
    private static Result matchStructure(ServerLevel level, BlockPos pos, ResourceKey<Structure> key,
                                         String label, int padding) {
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, key);
        if (isUsable(start)) {
            return new Result(true, "inside " + label);
        }
        return padding <= 0 ? null : matchStructureWithinPadding(level, pos, key, label, padding);
    }

    private static Result matchStructure(ServerLevel level, BlockPos pos, TagKey<Structure> key,
                                         String label, int padding) {
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, key);
        if (isUsable(start)) {
            return new Result(true, "inside " + label);
        }
        return padding <= 0 ? null : matchStructureWithinPadding(level, pos, key, label, padding);
    }

    private static boolean isUsable(StructureStart start) {
        return start != null && start.isValid() && !start.getPieces().isEmpty();
    }

    /**
     * "Within padding blocks of the structure" approximation: sample a ring of points at the padding
     * distance and accept when any of them is inside. It is not the exact distance to the nearest
     * piece, but it costs eight cheap lookups instead of walking every piece of every structure, and
     * for the job it does - letting a scav walk the streets around the city walls - that is plenty.
     */
    private static Result matchStructureWithinPadding(ServerLevel level, BlockPos pos, ResourceKey<Structure> key,
                                                      String label, int padding) {
        for (int i = 0; i < PADDING_SAMPLES; i++) {
            double angle = (Math.PI * 2.0D * i) / PADDING_SAMPLES;
            BlockPos sample = pos.offset(
                    (int) Math.round(Math.cos(angle) * padding), 0, (int) Math.round(Math.sin(angle) * padding));
            if (isUsable(level.structureManager().getStructureWithPieceAt(sample, key))) {
                return new Result(true, "within " + padding + " blocks of " + label);
            }
        }
        return null;
    }

    private static Result matchStructureWithinPadding(ServerLevel level, BlockPos pos, TagKey<Structure> key,
                                                      String label, int padding) {
        for (int i = 0; i < PADDING_SAMPLES; i++) {
            double angle = (Math.PI * 2.0D * i) / PADDING_SAMPLES;
            BlockPos sample = pos.offset(
                    (int) Math.round(Math.cos(angle) * padding), 0, (int) Math.round(Math.sin(angle) * padding));
            if (isUsable(level.structureManager().getStructureWithPieceAt(sample, key))) {
                return new Result(true, "within " + padding + " blocks of " + label);
            }
        }
        return null;
    }

    private static String stripHash(String tag) {
        return tag.startsWith("#") ? tag.substring(1) : tag;
    }

    private static List<String> normalised(List<? extends String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return out;
    }
}
