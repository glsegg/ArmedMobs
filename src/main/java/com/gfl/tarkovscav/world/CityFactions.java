package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The faction of a city and of each of its buildings, and the rules that follow from them.
 *
 * <h2>A city is a set of buildings, and each building has its own faction</h2>
 * <p>The user's rule: different buildings - even two in the same chunk - may belong to different
 * line-ups, so a city CAN be contested. A city still leans one way: it rolls a <b>dominant</b> faction,
 * and with {@code garrison.cityDominantFactionChance} the whole city may come out unified instead of
 * contested. When it is contested, every building rolls its own faction independently.</p>
 *
 * <h2>Why this is decided at runtime and not baked into the structure</h2>
 * <p>A structure NBT is a SHARED TEMPLATE: the same {@code city_small.nbt} is placed many times in one
 * world, so a faction baked into the template would make every city in the world the same faction. The
 * shipped spawners therefore keep their default mixed TROOP pair (they are the visible default), and
 * every faction is decided ONCE at runtime and recorded in {@link GarrisonData} - see
 * {@link CityGarrison}. Once recorded it can never change.</p>
 *
 * <h2>The deterministic rolls</h2>
 * <p>{@link #rollCity} is seeded by the world seed and the dimension-aware city key; {@link #rollBuildings}
 * adds each building's own id. The same city therefore always rolls the same dominant faction and the same
 * per-building split, even before the ledger entry exists. The rolls are still recorded the first time they
 * are asked for, because "once decided, never changes" must not be breakable by editing the config chance
 * afterwards.</p>
 *
 * <p>Everything in this class is pure: no level, no registry, no SavedData. That is what lets
 * {@code tools/selftest_city_faction.js} mirror it exactly and drive the real cases without a game.</p>
 */
public final class CityFactions {
    /** Roll resolution: chances are compared against a bucket in 0..{@value #BUCKETS}-1. */
    public static final int BUCKETS = 10000;

    /** The literal names the faction roll and the override command use. */
    public static final String VILLAGE_NAME = "village";
    public static final String ILLAGER_NAME = "illager";
    public static final String AUTO_NAME = "auto";

    /**
     * What a VILLAGE building's spawners may produce: the TROOP tier of that faction and nothing else. The
     * shipped NBT keeps the mixed default pair; this is the payload the runtime rewrite writes.
     */
    public static final List<String> VILLAGE_SPAWNER_IDS = List.of("tarkovscav:usec_villager");

    /** The ILLAGER building's spawner payload - the other half of the TROOP tier. */
    public static final List<String> ILLAGER_SPAWNER_IDS = List.of("tarkovscav:bear_pillager");

    /** Every unit (TROOP + ELITE) a village building may contain - a garrison squad's whole repertoire. */
    public static final List<String> VILLAGE_UNIT_IDS =
            List.of("tarkovscav:usec_villager", "tarkovscav:elite_villager");

    /** Every unit an illager building may contain. */
    public static final List<String> ILLAGER_UNIT_IDS =
            List.of("tarkovscav:bear_pillager", "tarkovscav:elite_pillager");

    private CityFactions() {
    }

    /** {@code "village"}/{@code "illager"} to the {@link Faction}; null for anything else (incl. "auto"). */
    @Nullable
    public static Faction parse(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        for (Faction faction : List.of(Faction.VILLAGE, Faction.ILLAGER)) {
            if (name(faction).equalsIgnoreCase(trimmed)) {
                return faction;
            }
        }
        return null;
    }

    /** The city-facing name of a faction: "village" / "illager" (and "scav" for completeness). */
    public static String name(@Nullable Faction faction) {
        if (faction == Faction.VILLAGE) {
            return VILLAGE_NAME;
        }
        if (faction == Faction.ILLAGER) {
            return ILLAGER_NAME;
        }
        return "scav";
    }

    /** The opposite line-up; SCAV has no opposite and is returned unchanged. */
    public static Faction opposite(Faction faction) {
        return faction == Faction.ILLAGER ? Faction.VILLAGE : Faction.ILLAGER;
    }

    /** True when {@code name} is the auto keyword - "roll the city's own faction, no override". */
    public static boolean isAuto(String name) {
        return name != null && AUTO_NAME.equalsIgnoreCase(name.trim());
    }

    /** The chance a roll lands on the first option, clamped and quantised to the bucket grid. */
    public static int threshold(double chance) {
        double clamped = Math.max(0.0D, Math.min(1.0D, chance));
        return (int) Math.round(clamped * (double) BUCKETS);
    }

    // ------------------------------------------------------------------ the deterministic rolls

    /**
     * The roll bucket for one tag, in 0..{@value #BUCKETS}-1. Stable for a given world seed and tag.
     *
     * <p>The hash is a 31-multiplier fold over the seed's two 32-bit halves and the tag, finished with the
     * murmur3 32-bit finalizer. That combination is what makes the buckets look uniform across city and
     * building keys (the gate measures the distribution) while staying trivially mirrorable in JavaScript -
     * the finalizer is only shifts and 32-bit multiplies, all of which JS reproduces with the unsigned
     * {@code >>>} operator and {@code Math.imul}.</p>
     */
    public static int bucket(long worldSeed, String tag) {
        return (hash(worldSeed, tag) & 0x7fffffff) % BUCKETS;
    }

    /** The raw 32-bit hash behind {@link #bucket}; exposed so the gate can pin the exact arithmetic. */
    public static int hash(long worldSeed, String tag) {
        int hash = 1;
        hash = 31 * hash + (int) (worldSeed ^ (worldSeed >>> 32));
        for (int i = 0; i < tag.length(); i++) {
            hash = 31 * hash + tag.charAt(i);
        }
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;
        return hash;
    }

    /**
     * The city's <b>dominant</b> faction: VILLAGE while the bucket is below {@code friendlyCityChance},
     * ILLAGER above.
     *
     * <p>{@code friendlyCityChance = 0.5} is the shipped default (a 50/50 split); 0 pins every city to
     * ILLAGER and 1 pins every city to VILLAGE, which is the "make this world all hostile / all friendly"
     * lever. The chance is clamped, so a hand-edited toml cannot produce a nonsense threshold.</p>
     */
    public static Faction rollCity(long worldSeed, ResourceLocation dimension, String cityKey,
                                   double friendlyCityChance) {
        return bucket(worldSeed, cityTag(dimension, cityKey)) < threshold(friendlyCityChance)
                ? Faction.VILLAGE : Faction.ILLAGER;
    }

    /**
     * The per-building split.
     *
     * <p>First the city rolls "unified": with probability {@code cityDominantFactionChance} every building
     * takes the dominant faction. Otherwise the city is <b>contested</b> and each building independently
     * rolls the dominant faction with probability 0.5, so a contested city is a genuine mix rather than a
     * slightly-noisy copy of a unified one.</p>
     *
     * <p>Building {@code i} is seeded by its own id, so deleting or reordering one building cannot move any
     * other building's faction within a contested city... except that the ids are positional, which is why
     * a layout edit is expected to re-roll. That is correct: a changed building is a different building.</p>
     */
    public static Faction[] rollBuildings(long worldSeed, ResourceLocation dimension, String cityKey,
                                          Faction dominant, double cityDominantFactionChance, int count) {
        Faction[] out = new Faction[Math.max(0, count)];
        boolean unified = bucket(worldSeed, cityTag(dimension, cityKey) + "#unified")
                < threshold(cityDominantFactionChance);
        Faction other = opposite(dominant);
        for (int i = 0; i < out.length; i++) {
            out[i] = unified || bucket(worldSeed, buildingTag(dimension, cityKey, i)) < BUCKETS / 2
                    ? dominant : other;
        }
        return out;
    }

    /** The exact string the city roll is seeded with - the same shape {@link GarrisonData#key} uses. */
    public static String cityTag(ResourceLocation dimension, String cityKey) {
        return dimension + "|" + cityKey;
    }

    /** The tag a single building's roll is seeded with. Positional on purpose; see {@link #rollBuildings}. */
    public static String buildingTag(ResourceLocation dimension, String cityKey, int buildingIndex) {
        return cityTag(dimension, cityKey) + "#building:" + buildingIndex;
    }

    // ------------------------------------------------------------------ the rules that follow

    /**
     * May a mob of {@code mobFaction} exist inside a building of {@code buildingFaction}?
     *
     * <p>SCAV is the <b>third party</b> and is deliberately allowed in BOTH line-ups (the decision recorded
     * in the README): an unaligned scav walking through a village or an illager building is the point of the
     * third faction. A mob with no faction at all (a zombie, an animal) is never filtered here - that is the
     * biome's business, not the building's.</p>
     */
    public static boolean allows(@Nullable Faction buildingFaction, @Nullable Faction mobFaction) {
        if (buildingFaction == null || mobFaction == null) {
            return true;
        }
        if (mobFaction == Faction.SCAV) {
            return true;
        }
        return mobFaction == buildingFaction;
    }

    /** The TROOP ids that may be baked into this faction's spawners (TROOP tier only, never ELITE). */
    public static List<String> spawnerIds(Faction faction) {
        return faction == Faction.ILLAGER ? ILLAGER_SPAWNER_IDS : VILLAGE_SPAWNER_IDS;
    }

    /** Every id this faction may use - the TROOP unit (index 0) and the ELITE leader (index 1). */
    public static List<String> unitIds(Faction faction) {
        return faction == Faction.ILLAGER ? ILLAGER_UNIT_IDS : VILLAGE_UNIT_IDS;
    }

    /** The TROOP unit of a faction - the only thing a building spawner may produce. */
    public static String troopId(Faction faction) {
        return unitIds(faction).get(0);
    }

    /** The ELITE leader of a faction - garrison only, at {@code garrison.eliteLeaderChance}. */
    public static String eliteId(Faction faction) {
        return unitIds(faction).get(1);
    }
}
