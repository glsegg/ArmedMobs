import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.world.CityBuildings;
import com.gfl.tarkovscav.world.CityCapture;
import com.gfl.tarkovscav.world.CityGarrison;
import com.gfl.tarkovscav.world.CityPlacementData;
import com.gfl.tarkovscav.world.CityStructures;
import com.gfl.tarkovscav.world.GarrisonData;
import com.gfl.tarkovscav.worldgen.WastelandSpreadPlacement;
import com.gfl.tarkovscav.worldgen.CityDistrictAssembler;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Runs production classes against the mapped Minecraft dependency jar; no copied gameplay algorithm. */
public final class WorldRegressionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        placementFrequency();
        buildingRotations();
        districtLayout();
        savedCityBounds();
        captureOverrides();
        require(CityGarrison.due(5, 100_000, 100), "world clock rollback must not suspend garrisons");
        require(!CityGarrison.due(99, 0, 100), "normal garrison interval remains enforced");
        System.out.println("WorldRegressionTest: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static WastelandSpreadPlacement placement(float frequency,
            Optional<StructurePlacement.ExclusionZone> exclusion) {
        return new WastelandSpreadPlacement(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT,
                frequency, 1324354657, exclusion, 24, 8, 16, 5, RandomSpreadType.LINEAR);
    }

    private static ChunkGeneratorStructureState state(long seed) throws Exception {
        return state(seed, null);
    }

    private static ChunkGeneratorStructureState state(long seed, BiomeSource biomeSource) throws Exception {
        Constructor<ChunkGeneratorStructureState> constructor = ChunkGeneratorStructureState.class
                .getDeclaredConstructor(RandomState.class, BiomeSource.class, long.class, long.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, biomeSource, seed, seed, List.of());
    }

    private static void placementFrequency() throws Exception {
        long seed = 93761L;
        ChunkGeneratorStructureState state = state(seed);
        WastelandSpreadPlacement never = placement(0, Optional.empty());
        WastelandSpreadPlacement quarter = placement(0.25F, Optional.empty());
        RandomSpreadStructurePlacement vanilla = new RandomSpreadStructurePlacement(Vec3i.ZERO,
                StructurePlacement.FrequencyReductionMethod.DEFAULT, 0.25F, 1324354657,
                Optional.empty(), 24, 8, RandomSpreadType.LINEAR);
        int kept = 0;
        for (int x = -12; x < 12; x++) {
            for (int z = -12; z < 12; z++) {
                ChunkPos candidate = vanilla.getPotentialStructureChunk(seed, x * 24, z * 24);
                require(!never.isStructureChunk(state, candidate.x, candidate.z),
                        "frequency=0 accepted a city candidate");
                boolean expected = vanilla.isStructureChunk(state, candidate.x, candidate.z);
                require(quarter.isStructureChunk(state, candidate.x, candidate.z) == expected,
                        "custom placement ignored vanilla frequency at " + candidate);
                if (expected) kept++;
            }
        }
        require(kept > 0 && kept < 576, "frequency test must exercise both accepted and rejected candidates");

        Holder<Biome> wasteland = Holder.Reference.createStandAlone(new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, WastelandSpreadPlacement.WASTELAND_BIOME));
        ChunkGeneratorStructureState denseState = state(seed, new FixedBiomeSource(wasteland));
        RandomSpreadStructurePlacement denseVanilla = new RandomSpreadStructurePlacement(Vec3i.ZERO,
                StructurePlacement.FrequencyReductionMethod.DEFAULT, 0.25F, 1324354657,
                Optional.empty(), 16, 5, RandomSpreadType.LINEAR);
        require(WastelandSpreadPlacement.isWasteland(denseState), "fixed wasteland biome must select dense spacing");
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                ChunkPos candidate = denseVanilla.getPotentialStructureChunk(seed, x * 16, z * 16);
                require(quarter.isStructureChunk(denseState, candidate.x, candidate.z)
                        == denseVanilla.isStructureChunk(denseState, candidate.x, candidate.z),
                        "dense city placement must preserve vanilla frequency at " + candidate);
            }
        }

        RandomSpreadStructurePlacement blocker = new RandomSpreadStructurePlacement(1, 0,
                RandomSpreadType.LINEAR, 7);
        StructurePlacement.ExclusionZone exclusion = new StructurePlacement.ExclusionZone(
                Holder.direct(new StructureSet(List.of(), blocker)), 1);
        ChunkPos candidate = vanilla.getPotentialStructureChunk(seed, 0, 0);
        require(!placement(1, Optional.of(exclusion)).isStructureChunk(state, candidate.x, candidate.z),
                "exclusion zone must suppress an otherwise eligible city");
    }

    @SuppressWarnings("unchecked")
    private static void buildingRotations() throws Exception {
        Method parse = CityBuildings.class.getDeclaredMethod("parse", com.google.gson.JsonObject.class);
        parse.setAccessible(true);
        Object map = parse.invoke(null, JsonParser.parseString("""
                {"size":[20,9,40],"buildings":[{"id":"asymmetric","x":2,"z":7,"w":3,"d":5}]}
                """).getAsJsonObject());
        Method mapping = CityBuildings.class.getDeclaredMethod("fromMap", map.getClass(),
                BoundingBox.class, Rotation.class);
        mapping.setAccessible(true);
        BlockPos origin = new BlockPos(-115, 63, 207);
        for (Rotation rotation : Rotation.values()) {
            BlockPos start = StructureTemplate.transform(BlockPos.ZERO, Mirror.NONE, rotation, BlockPos.ZERO)
                    .offset(origin);
            BlockPos end = StructureTemplate.transform(new BlockPos(19, 8, 39), Mirror.NONE, rotation,
                    BlockPos.ZERO).offset(origin);
            BoundingBox world = BoundingBox.fromCorners(start, end);
            List<CityBuildings.Building> mapped = (List<CityBuildings.Building>) mapping.invoke(null, map,
                    world, rotation);
            BoundingBox building = mapped.get(0).box();
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 40; z++) {
                    BlockPos placed = StructureTemplate.transform(new BlockPos(x, 4, z), Mirror.NONE,
                            rotation, BlockPos.ZERO).offset(origin);
                    require(building.isInside(placed) == (x >= 2 && x <= 4 && z >= 7 && z <= 11),
                            rotation + " building faction box disagrees with the actual transformed block");
                }
            }
        }
    }

    private static void savedCityBounds() throws Exception {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        ResourceLocation wasteland = new ResourceLocation("tarkovscav", "urban_wasteland");
        CityPlacementData firstWorld = new CityPlacementData();
        BlockPos origin = new BlockPos(-30, 63, -15);
        for (ResourceLocation dimension : List.of(overworld, wasteland)) {
            firstWorld.record(new CityStructures.Placed("city", dimension,
                    new BoundingBox(-50, 63, -25, -30, 80, -15), origin,
                    Rotation.CLOCKWISE_90, Mirror.FRONT_BACK));
        }
        require(firstWorld.isDirty(), "new placed city must be marked for saving");
        firstWorld.record(new CityStructures.Placed("CITY", overworld,
                new BoundingBox(-70, 63, -25, -30, 80, -15), origin,
                Rotation.CLOCKWISE_180, Mirror.LEFT_RIGHT));
        require(firstWorld.placed().size() == 2, "same city/origin replaces only its own dimension");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(firstWorld.save(new CompoundTag()), output);
        CityPlacementData restored = CityPlacementData.load(NbtIo.readCompressed(
                new ByteArrayInputStream(output.toByteArray())));
        require(restored.placed().size() == 2, "placed city bounds must survive compressed NBT reload");
        CityStructures.Placed restoredOverworld = restored.placed().stream()
                .filter(row -> row.dimension().equals(overworld)).findFirst().orElseThrow();
        require(restoredOverworld.box().isInside(new BlockPos(-65, 70, -20)), "rotated bounds survive reload");
        require(restoredOverworld.rotation() == Rotation.CLOCKWISE_180
                && restoredOverworld.mirror() == Mirror.LEFT_RIGHT && restoredOverworld.origin().equals(origin),
                "placement identity and transform survive reload");
        require(new CityPlacementData().placed().isEmpty(), "another world must not inherit city placements");
        require(CityPlacementData.load(new CompoundTag()).placed().isEmpty(), "old saves start with no placed rows");
    }

    private static void districtLayout() throws Exception {
        Method poolResource = CityDistrictAssembler.class.getDeclaredMethod("poolResource", String.class);
        poolResource.setAccessible(true);
        Path project = Path.of(System.getProperty("world.test.project", "."));
        for (String pool : List.of("street", "building", "decor")) {
            ResourceLocation file = (ResourceLocation) poolResource.invoke(null,
                    "tarkovscav:city_district/" + pool);
            Path path = project.resolve("src/main/resources/data").resolve(file.getNamespace())
                    .resolve(file.getPath());
            require(Files.isRegularFile(path), "district pool must resolve to its actual data resource: " + file);
            require(!JsonParser.parseString(Files.readString(path)).getAsJsonObject()
                    .getAsJsonArray("elements").isEmpty(), "district pool must contain real template entries");
        }
        Method grid = CityDistrictAssembler.class.getDeclaredMethod("gridCoordinates", int.class);
        grid.setAccessible(true);
        for (int size = 1; size <= 7; size++) {
            int[] cells = (int[]) grid.invoke(null, size);
            require(cells.length == size, "grid=" + size + " must select exactly that many tiles on each axis");
            for (int i = 1; i < cells.length; i++) {
                require(cells[i] == cells[i - 1] + 1, "district grid must contain contiguous tile cells");
            }
        }
        Method originMethod = CityDistrictAssembler.class.getDeclaredMethod("tileOrigin", BlockPos.class,
                int.class, int.class, Vec3i.class, Rotation.class);
        originMethod.setAccessible(true);
        Method boundsMethod = StructureTemplate.class.getDeclaredMethod("getBoundingBox", BlockPos.class,
                Rotation.class, BlockPos.class, Mirror.class, Vec3i.class);
        boundsMethod.setAccessible(true);
        BlockPos anchor = new BlockPos(-96, 0, 128);
        for (Vec3i size : List.of(new Vec3i(16, 12, 16), new Vec3i(16, 12, 17))) {
            for (Rotation rotation : Rotation.values()) {
                BlockPos origin = (BlockPos) originMethod.invoke(null, anchor, 80, 5, size, rotation);
                BoundingBox bounds = (BoundingBox) boundsMethod.invoke(null, origin, rotation, BlockPos.ZERO,
                        Mirror.NONE, size);
                require(bounds.minX() == anchor.getX() && bounds.minZ() == anchor.getZ()
                        && bounds.minY() == 75, "rotated district piece must retain its tile corner: " + rotation);
                if (size.getX() == size.getZ()) {
                    require(bounds.maxX() == anchor.getX() + 15 && bounds.maxZ() == anchor.getZ() + 15,
                            "rotated street tile must cover its entire cell without spilling into a neighbor");
                }
            }
        }
    }

    private static void captureOverrides() {
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        GarrisonData data = new GarrisonData();
        data.recordCity(dimension, "test", Faction.ILLAGER, List.of("a", "b"),
                new Faction[]{Faction.ILLAGER, Faction.VILLAGE});
        data.overrideCity(dimension, "test", Faction.VILLAGE);
        require(CityCapture.factionsPresent(data, dimension, "test", data.city(dimension, "test"))
                .equals(List.of(Faction.VILLAGE)), "capture pools must use the city override");
        data.overrideBuilding(dimension, "test", "a", Faction.ILLAGER);
        require(CityCapture.factionsPresent(data, dimension, "test", data.city(dimension, "test"))
                .equals(List.of(Faction.ILLAGER, Faction.VILLAGE)), "building override must take precedence");
        GarrisonData restored = GarrisonData.load(data.save(new CompoundTag()));
        require(CityCapture.factionsPresent(restored, dimension, "test", restored.city(dimension, "test"))
                .equals(List.of(Faction.ILLAGER, Faction.VILLAGE)), "capture overrides must survive save reload");
    }
}
