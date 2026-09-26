package com.gfl.tarkovscav.worldgen;

import com.gfl.tarkovscav.TarkovScav;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * The structure placement that makes {@code tarkovscav:urban_wasteland} a city, without touching a single
 * chunk of the overworld.
 *
 * <h2>Why a custom placement exists at all</h2>
 * <p>The obvious way to make one dimension denser - a {@code structures} block in the dimension JSON -
 * does not exist in 1.20.1. {@code javap} of {@code net.minecraft.world.level.dimension.LevelStem} shows
 * the codec the datapack loader uses reads exactly two fields, {@code type} and {@code generator};
 * {@code DimensionStructuresSettings} is only used on the level.dat path for the three built-in
 * dimensions. So the only place a per-dimension spacing can live is a placement that can tell which
 * dimension it is being asked about.</p>
 *
 * <h2>How it tells</h2>
 * <p>{@link StructurePlacement#isStructureChunk} is handed a {@link ChunkGeneratorStructureState}, whose
 * biome source is the wasteland's {@link FixedBiomeSource} when - and only when - the generator is the
 * wasteland's. That single fact is the discriminator: a fixed biome source holding
 * {@code tarkovscav:urban_wasteland} means "use the dense grid", anything else means "use the shipped
 * grid, exactly as {@code minecraft:random_spread} would". The state's {@code biomeSource} field is
 * private in vanilla, so a cached reflective lookup reads it using the mapped and runtime field names.
 * No access transformer is required.</p>
 *
 * <h2>The two grids</h2>
 * <p>{@code spacing}/{@code separation} are the shipped values and are what the overworld uses;
 * {@code dense_spacing}/{@code dense_separation} are the wasteland's. Both branches run the identical
 * vanilla random-spread arithmetic - {@code cell = floorDiv(chunk, spacing)}, one candidate per cell at
 * {@code cell * spacing + spreadType.evaluate(...)} - so the overworld branch is not "similar to" vanilla,
 * it is the same formula with the same numbers.</p>
 *
 * <h2>The locate trade-off (stated, not hidden)</h2>
 * <p>{@link #spacing()} and {@link #separation()} report the DENSE pair, because {@code /locate structure}
 * walks candidates in steps of {@code spacing()} and never hands the placement a generator - it is the
 * only value that can make {@code locate} correct in the dense dimension. The cost is on the overworld
 * side: {@code locate} there now probes the dense grid, so it will not report the overworld's
 * sparse-grid cities. That was already the documented behaviour of this mod before this class existed
 * (README section 7i's TODO: "/locate structure tarkovscav:city_small -> Could not find"), so nothing
 * regresses, but the reason is now different and is written down here rather than discovered later.</p>
 */
public class WastelandSpreadPlacement extends RandomSpreadStructurePlacement {
    /** The biome that marks a dimension as the urban wasteland. */
    public static final ResourceLocation WASTELAND_BIOME =
            new ResourceLocation(TarkovScav.MOD_ID, "urban_wasteland");

    /**
     * The codec is built as one ten-field record rather than composed on top of
     * {@code StructurePlacement#placementCodec} because DFU's {@code Products.P5} only offers
     * {@code and(P1..P3)} - there is no {@code P5.and(P5)} to reach the ten fields that way. The field
     * names are the vanilla ones plus the two dense ones, so a structure_set stays readable.
     */
    public static final MapCodec<WastelandSpreadPlacement> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Vec3i.offsetCodec(16).optionalFieldOf("locate_offset", Vec3i.ZERO)
                            .forGetter(WastelandSpreadPlacement::locateOffset),
                    StructurePlacement.FrequencyReductionMethod.CODEC
                            .optionalFieldOf("frequency_reduction_method",
                                    StructurePlacement.FrequencyReductionMethod.DEFAULT)
                            .forGetter(WastelandSpreadPlacement::frequencyReductionMethod),
                    Codec.floatRange(0.0F, 1.0F).optionalFieldOf("frequency", 1.0F)
                            .forGetter(WastelandSpreadPlacement::frequency),
                    Codec.INT.fieldOf("salt").forGetter(WastelandSpreadPlacement::salt),
                    StructurePlacement.ExclusionZone.CODEC.optionalFieldOf("exclusion_zone")
                            .forGetter(WastelandSpreadPlacement::exclusionZone),
                    Codec.intRange(1, 4096).fieldOf("spacing")
                            .forGetter(WastelandSpreadPlacement::normalSpacing),
                    Codec.intRange(0, 4096).fieldOf("separation")
                            .forGetter(WastelandSpreadPlacement::normalSeparation),
                    Codec.intRange(1, 4096).fieldOf("dense_spacing")
                            .forGetter(WastelandSpreadPlacement::denseSpacing),
                    Codec.intRange(0, 4096).fieldOf("dense_separation")
                            .forGetter(WastelandSpreadPlacement::denseSeparation),
                    RandomSpreadType.CODEC.optionalFieldOf("spread_type", RandomSpreadType.LINEAR)
                            .forGetter(WastelandSpreadPlacement::spreadType))
                    .apply(instance, WastelandSpreadPlacement::new));

    private final int normalSpacing;
    private final int normalSeparation;
    private final int denseSpacing;
    private final int denseSeparation;

    public WastelandSpreadPlacement(Vec3i locateOffset,
                                    StructurePlacement.FrequencyReductionMethod frequencyReductionMethod,
                                    float frequency, int salt,
                                    Optional<StructurePlacement.ExclusionZone> exclusionZone,
                                    int normalSpacing, int normalSeparation,
                                    int denseSpacing, int denseSeparation,
                                    RandomSpreadType spreadType) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone,
                normalSpacing, normalSeparation, spreadType);
        // Validated here rather than in a codec result so a bad number names the file's field in the
        // registry error instead of silently placing overlapping cities.
        if (normalSeparation >= normalSpacing) {
            throw new IllegalArgumentException("separation " + normalSeparation
                    + " must be smaller than spacing " + normalSpacing);
        }
        if (denseSeparation >= denseSpacing) {
            throw new IllegalArgumentException("dense_separation " + denseSeparation
                    + " must be smaller than dense_spacing " + denseSpacing);
        }
        this.normalSpacing = normalSpacing;
        this.normalSeparation = normalSeparation;
        this.denseSpacing = denseSpacing;
        this.denseSeparation = denseSeparation;
    }

    /** The shipped (overworld) spacing, in chunks. */
    public int normalSpacing() {
        return this.normalSpacing;
    }

    /** The shipped (overworld) separation, in chunks. */
    public int normalSeparation() {
        return this.normalSeparation;
    }

    /** The wasteland spacing, in chunks. */
    public int denseSpacing() {
        return this.denseSpacing;
    }

    /** The wasteland separation, in chunks (never >= dense_spacing). */
    public int denseSeparation() {
        return this.denseSeparation;
    }

    public RandomSpreadType spreadType() {
        return super.spreadType();
    }

    /** Reports the dense pair on purpose: see the class comment's locate section. */
    @Override
    public int spacing() {
        return this.denseSpacing;
    }

    /** Reports the dense pair on purpose: see the class comment's locate section. */
    @Override
    public int separation() {
        return this.denseSeparation;
    }

    @Override
    public StructurePlacementType<?> type() {
        return ModWorldgen.WASTELAND_SPREAD.get();
    }

    /**
     * The whole point: dense inside the wasteland, the shipped grid everywhere else. Both branches are
     * the vanilla formula, parameterised by (spacing, separation).
     */
    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        long seed = state.getLevelSeed();
        if (isWasteland(state)) {
            return matchesAt(seed, chunkX, chunkZ, this.denseSpacing, this.denseSeparation);
        }
        return matchesAt(seed, chunkX, chunkZ, this.normalSpacing, this.normalSeparation);
    }

    /**
     * True when this state's biome source is a fixed source holding the wasteland biome - i.e. the
     * generator is the wasteland's own. Deliberately requires {@link FixedBiomeSource}: a multi-biome
     * source that merely happens to contain the biome (none does) must not turn a dimension dense.
     *
     * <p>{@code ChunkGeneratorStructureState#biomeSource} is private in vanilla and this is the only
     * reachable dimension discriminator, so it is read reflectively through a cached {@link Field}. An
     * access-transformer line was tried first and did not publish the field in the ForgeGradle dev
     * workspace (the mapped jar still showed it private), so the reflective read is the honest, working
     * route; the lookup happens once and the per-call cost is a single {@code Field#get}.</p>
     */
    public static boolean isWasteland(ChunkGeneratorStructureState state) {
        return isWastelandBiomeSource(biomeSourceOf(state));
    }

    /** The cached reflective read of {@code ChunkGeneratorStructureState#biomeSource}. */
    private static BiomeSource biomeSourceOf(ChunkGeneratorStructureState state) {
        Field field = BiomeSourceField.HOLDER;
        if (field == null) {
            return null;
        }
        try {
            return (BiomeSource) field.get(state);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    /** Resolves the field once. Both names are tried: the dev workspace uses official names, production
     * SRG names, and this class must work in both without a build-system trick. */
    private static final class BiomeSourceField {
        private static final Field HOLDER = find();

        private static Field find() {
            for (String name : new String[] { "biomeSource", "f_254681_" }) {
                try {
                    Field field = ChunkGeneratorStructureState.class.getDeclaredField(name);
                    field.setAccessible(true);
                    TarkovScav.LOGGER.info("[wasteland] structure placement reads"
                            + " ChunkGeneratorStructureState#{} to tell the wasteland apart", name);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // try the next spelling
                }
            }
            TarkovScav.LOGGER.error("[wasteland] neither biomeSource nor f_254681_ exists on"
                    + " ChunkGeneratorStructureState - the wasteland will use the shipped city spacing");
            return null;
        }
    }

    /** The same test on a bare biome source, so the gate can exercise it without a live state. */
    public static boolean isWastelandBiomeSource(BiomeSource source) {
        if (!(source instanceof FixedBiomeSource)) {
            return false;
        }
        for (Holder<Biome> holder : source.possibleBiomes()) {
            if (holder.unwrapKey().map(key -> key.location().equals(WASTELAND_BIOME)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla's {@code RandomSpreadStructurePlacement#getPotentialStructureChunk} arithmetic, verbatim
     * (including the salt-seeded {@code setLargeFeatureWithSalt}), with the spacing/separation handed in.
     * Copied rather than called because the inherited version reads this instance's {@link #spacing()} -
     * which is the dense value, and the overworld branch must use the shipped one.
     */
    private boolean matchesAt(long seed, int chunkX, int chunkZ, int spacing, int separation) {
        if (separation >= spacing) {
            return false;
        }
        int cellX = Math.floorDiv(chunkX, spacing);
        int cellZ = Math.floorDiv(chunkZ, spacing);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureWithSalt(seed, cellX, cellZ, this.salt());
        int range = spacing - separation;
        int offsetX = this.spreadType().evaluate(random, range);
        int offsetZ = this.spreadType().evaluate(random, range);
        return cellX * spacing + offsetX == chunkX && cellZ * spacing + offsetZ == chunkZ;
    }
}
