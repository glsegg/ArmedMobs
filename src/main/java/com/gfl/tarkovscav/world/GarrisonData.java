package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The city ledger: which cities have already placed their garrison, and what faction each city and each of
 * its buildings is.
 *
 * <h2>What this is, and what it is not</h2>
 * <p>It is a single {@link SavedData} file per world ({@code data/tarkovscav_garrison.dat}) holding three
 * maps, all keyed by <b>dimension + city identity</b>:</p>
 * <ul>
 *   <li>{@link Entry} - a city that has already placed its one-time garrison, and how much;</li>
 *   <li>{@link CityRow} - the city's dominant faction and an optional operator override;</li>
 *   <li>{@link BuildingRow} - one row per building of that city, with its own rolled faction and an
 *       optional per-building override.</li>
 * </ul>
 *
 * <p>It is deliberately NOT a static field and NOT an entity flag: the whole requirement is that a garrison
 * spawns exactly once, that its dead stay dead across a restart, and that a city's and a building's faction
 * can never change once decided - so the only place that can hold those answers is the world save.</p>
 *
 * <p>The city key is {@link #key(ResourceLocation, String)} - {@code <dimension>|<city>} - and the dimension
 * is part of it on purpose. A structure id alone is not an identity: the same
 * {@code tarkovscav:city_small} can exist at ten places in the overworld and at ten more in
 * {@code tarkovscav:urban_wasteland}, and each of those twenty instances gets its own one-time garrison and
 * its own faction split. Two cities in one dimension are told apart by {@link CityGate#centreKey}; a
 * building inside one city is keyed by {@link #buildingKey} - {@code <dimension>|<city>#<buildingId>}.</p>
 *
 * <h2>The faction strength pools (the slot this comment always reserved)</h2>
 * <p>A later batch adds faction STRENGTH POOLS (20..100, scaled by city size), a HUD per faction present,
 * kills draining a pool and a pool at 0 stopping that faction's armed units in the area. That batch is
 * implemented: {@link PoolRow} below is the per-city, per-faction row, its key is
 * {@code <dimension>|<city>|<faction>} - i.e. {@link #key(ResourceLocation, String)} plus
 * {@code "|" + CityFactions.name(faction)} - and it carries {@code strength} (int), {@code max} (int) and the
 * <b>captured</b> flag, exactly as this comment predicted. Nothing in this class changed shape for it: it is
 * one more map in the same file. TODO: a <b>building-local</b> pool (one strength per building instead of per
 * faction) is deliberately NOT implemented - the design asks for one bar per faction in the city, and a
 * per-building pool would instead hang off {@link #buildingKey}.</p>
 *
 * <h2>Why "mark only when something was placed"</h2>
 * <p>{@link CityGarrison} marks a city only after at least one unit actually entered the world. A garrison
 * that could not find a single valid standing spot is therefore retried on the next coarse check instead of
 * being silently recorded as done - the failure mode this avoids is "the city is permanently empty because
 * the one attempt happened while the chunk was half loaded". The FACTIONS are different: they are recorded
 * the first moment they are asked for (including from a natural-spawn check), so they can never be
 * re-rolled.</p>
 */
public class GarrisonData extends SavedData {
    /** The file name under {@code <world>/data/}; the gate pins it so a rename cannot orphan old saves. */
    public static final String DATA_NAME = "tarkovscav_garrison";

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_KEY = "key";
    private static final String KEY_CITY = "city";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_SQUADS = "squads";
    private static final String KEY_UNITS = "units";
    private static final String KEY_TICK = "tick";

    /** The city-faction list and the building-faction list; same key, same dimension field. */
    private static final String KEY_CITIES = "cities";
    private static final String KEY_BUILDINGS = "buildings";
    private static final String KEY_DOMINANT = "dominant";
    private static final String KEY_ROLLED = "rolled";
    private static final String KEY_OVERRIDE = "override";
    private static final String KEY_BUILDING = "building";
    /** The per-city spawner-rewrite flag: false means "rewrite the loaded chunks again next trigger". */
    private static final String KEY_SPAWNERS = "spawners";

    /**
     * The city-capture pools (README 7p): the follow-up the class comment above always reserved a slot for.
     * One row per (city, faction), keyed {@code <dimension>|<city>|<faction>}.
     */
    private static final String KEY_POOLS = "pools";
    private static final String KEY_FACTION = "faction";
    private static final String KEY_STRENGTH = "strength";
    private static final String KEY_MAX = "max";
    private static final String KEY_CAPTURED = "captured";

    /** One spawned garrison: which city, in which dimension, how much of it, and when. */
    public record Entry(String cityKey, ResourceLocation dimension, int squads, int units, long tick) {
        /** The one-line report {@code /armedmobs garrison} prints. */
        public String describe() {
            return dimension + " " + cityKey + " -> " + squads + " squad(s) / " + units
                    + " unit(s) at tick " + tick;
        }
    }

    /**
     * One city's faction record: the dominant line-up the whole city leans towards, and an optional
     * operator override that forces every building of the city at once.
     */
    public record CityRow(String cityKey, ResourceLocation dimension, Faction dominant,
                          @Nullable Faction override, boolean spawnersDone) {
        /** The faction in force for buildings with no override of their own. */
        public Faction effectiveDominant() {
            return this.override != null ? this.override : this.dominant;
        }

        public CityRow withOverride(@Nullable Faction newOverride) {
            return new CityRow(this.cityKey, this.dimension, this.dominant, newOverride, this.spawnersDone);
        }

        public CityRow withSpawnersDone(boolean done) {
            return new CityRow(this.cityKey, this.dimension, this.dominant, this.override, done);
        }
    }

    /** One building's faction record: the roll it was given and an optional per-building override. */
    public record BuildingRow(String cityKey, ResourceLocation dimension, String buildingId,
                              Faction rolled, @Nullable Faction override) {
        public String describe() {
            return this.buildingId + " -> " + CityFactions.name(effectiveRoll())
                    + (this.override != null ? " (override; rolled " + CityFactions.name(this.rolled) + ")" : "");
        }

        /** The faction this building rolls on its own, ignoring the city override. */
        public Faction effectiveRoll() {
            return this.override != null ? this.override : this.rolled;
        }

        public BuildingRow withOverride(@Nullable Faction newOverride) {
            return new BuildingRow(this.cityKey, this.dimension, this.buildingId, this.rolled, newOverride);
        }
    }

    /**
     * One faction's capture pool in one city (README 7p): how many men it still has, how many it started
     * with, and whether it has been spent.
     *
     * <p>{@code max} is stored next to {@code strength} rather than recomputed from the building count: the
     * HUD shows "current/initial", and a city whose buildings were edited after it was recorded must keep
     * showing the bar it was captured on. The pair is also what survives a restart - see {@link #save}.</p>
     */
    public record PoolRow(String cityKey, ResourceLocation dimension, Faction faction, int strength, int max,
                          boolean captured) {
        public String describe() {
            return CityFactions.name(this.faction) + " " + this.strength + "/" + this.max
                    + (this.captured ? " (captured)" : "");
        }

        public PoolRow withStrength(int newStrength) {
            return new PoolRow(this.cityKey, this.dimension, this.faction, newStrength, this.max,
                    CapturePools.isCaptured(newStrength));
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, CityRow> cities = new LinkedHashMap<>();
    private final Map<String, BuildingRow> buildings = new LinkedHashMap<>();

    /**
     * The capture pools, keyed by the city ledger key and then by the faction name - a nested map rather than
     * a flat one so "does this city have pools / what are they" is one hash lookup. That matters because
     * {@link CityGarrison#factionFor} asks it on every natural-spawn check inside a city; a flat scan of every
     * pool row in the world on that path would be a per-spawn cost the city ledger has never had.
     */
    private final Map<String, Map<String, PoolRow>> pools = new LinkedHashMap<>();

    public static GarrisonData get(MinecraftServer server) {
        // The same Forge overload RetreatData uses: (loader, creator, fileName). Kept identical on purpose
        // so both ledger files are read and written the same way.
        return server.overworld().getDataStorage()
                .computeIfAbsent(GarrisonData::load, GarrisonData::new, DATA_NAME);
    }

    /**
     * The dimension-aware ledger key. A city is {@code (dimension, city identity)} and nothing else, so the
     * overworld's city and the wasteland's city at the same coordinates are two different entries.
     */
    public static String key(ResourceLocation dimension, String cityKey) {
        return dimension + "|" + cityKey;
    }

    /** The key of one building inside one city. The building id is positional (see {@link CityFactions}). */
    public static String buildingKey(ResourceLocation dimension, String cityKey, String buildingId) {
        return key(dimension, cityKey) + "#" + buildingId;
    }

    /**
     * The key of one faction's capture pool: {@code <dimension>|<city>|<faction>}. The shape is defined once,
     * in {@link CapturePools#poolKey}, so the spike can pin it without a registry.
     */
    public static String poolKey(ResourceLocation dimension, String cityKey, Faction faction) {
        return CapturePools.poolKey(dimension.toString(), cityKey, CityFactions.name(faction));
    }

    public static GarrisonData load(CompoundTag tag) {
        GarrisonData data = new GarrisonData();
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            String entryKey = row.getString(KEY_KEY);
            String cityKey = row.getString(KEY_CITY);
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString(KEY_DIMENSION));
            if (entryKey.isEmpty() || dimension == null) {
                continue;
            }
            data.entries.put(entryKey, new Entry(cityKey, dimension,
                    row.getInt(KEY_SQUADS), row.getInt(KEY_UNITS), row.getLong(KEY_TICK)));
        }
        ListTag cityList = tag.getList(KEY_CITIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < cityList.size(); i++) {
            CompoundTag row = cityList.getCompound(i);
            String entryKey = row.getString(KEY_KEY);
            String cityKey = row.getString(KEY_CITY);
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString(KEY_DIMENSION));
            Faction dominant = CityFactions.parse(row.getString(KEY_DOMINANT));
            if (entryKey.isEmpty() || dimension == null || dominant == null) {
                continue;
            }
            data.cities.put(entryKey, new CityRow(cityKey, dimension, dominant,
                    CityFactions.parse(row.getString(KEY_OVERRIDE)), row.getBoolean(KEY_SPAWNERS)));
        }
        ListTag buildingList = tag.getList(KEY_BUILDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            CompoundTag row = buildingList.getCompound(i);
            String entryKey = row.getString(KEY_KEY);
            String cityKey = row.getString(KEY_CITY);
            String buildingId = row.getString(KEY_BUILDING);
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString(KEY_DIMENSION));
            Faction rolled = CityFactions.parse(row.getString(KEY_ROLLED));
            if (entryKey.isEmpty() || dimension == null || rolled == null || buildingId.isEmpty()) {
                continue;
            }
            data.buildings.put(entryKey, new BuildingRow(cityKey, dimension, buildingId, rolled,
                    CityFactions.parse(row.getString(KEY_OVERRIDE))));
        }
        ListTag poolList = tag.getList(KEY_POOLS, Tag.TAG_COMPOUND);
        for (int i = 0; i < poolList.size(); i++) {
            CompoundTag row = poolList.getCompound(i);
            String entryKey = row.getString(KEY_KEY);
            String cityKey = row.getString(KEY_CITY);
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString(KEY_DIMENSION));
            Faction faction = CityFactions.parse(row.getString(KEY_FACTION));
            // A row whose city or faction cannot be read is dropped rather than guessed: a pool attributed to
            // the wrong faction would drain the wrong bar and stop the wrong line-up from spawning. Same rule
            // the faction rows above already follow.
            if (entryKey.isEmpty() || dimension == null || faction == null) {
                continue;
            }
            data.pools.computeIfAbsent(key(dimension, cityKey), unused -> new LinkedHashMap<>())
                    .put(CityFactions.name(faction), new PoolRow(cityKey, dimension, faction,
                            row.getInt(KEY_STRENGTH), row.getInt(KEY_MAX), row.getBoolean(KEY_CAPTURED)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Entry> entry : this.entries.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString(KEY_KEY, entry.getKey());
            row.putString(KEY_CITY, entry.getValue().cityKey());
            row.putString(KEY_DIMENSION, entry.getValue().dimension().toString());
            row.putInt(KEY_SQUADS, entry.getValue().squads());
            row.putInt(KEY_UNITS, entry.getValue().units());
            row.putLong(KEY_TICK, entry.getValue().tick());
            list.add(row);
        }
        tag.put(KEY_ENTRIES, list);

        ListTag cityList = new ListTag();
        for (Map.Entry<String, CityRow> entry : this.cities.entrySet()) {
            CityRow value = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putString(KEY_KEY, entry.getKey());
            row.putString(KEY_CITY, value.cityKey());
            row.putString(KEY_DIMENSION, value.dimension().toString());
            row.putString(KEY_DOMINANT, CityFactions.name(value.dominant()));
            if (value.override() != null) {
                row.putString(KEY_OVERRIDE, CityFactions.name(value.override()));
            }
            row.putBoolean(KEY_SPAWNERS, value.spawnersDone());
            cityList.add(row);
        }
        tag.put(KEY_CITIES, cityList);

        ListTag buildingList = new ListTag();
        for (Map.Entry<String, BuildingRow> entry : this.buildings.entrySet()) {
            BuildingRow value = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putString(KEY_KEY, entry.getKey());
            row.putString(KEY_CITY, value.cityKey());
            row.putString(KEY_DIMENSION, value.dimension().toString());
            row.putString(KEY_BUILDING, value.buildingId());
            row.putString(KEY_ROLLED, CityFactions.name(value.rolled()));
            if (value.override() != null) {
                row.putString(KEY_OVERRIDE, CityFactions.name(value.override()));
            }
            buildingList.add(row);
        }
        tag.put(KEY_BUILDINGS, buildingList);

        ListTag poolList = new ListTag();
        for (Map<String, PoolRow> perFaction : this.pools.values()) {
            for (PoolRow value : perFaction.values()) {
                CompoundTag row = new CompoundTag();
                row.putString(KEY_KEY, poolKey(value.dimension(), value.cityKey(), value.faction()));
                row.putString(KEY_CITY, value.cityKey());
                row.putString(KEY_DIMENSION, value.dimension().toString());
                row.putString(KEY_FACTION, CityFactions.name(value.faction()));
                row.putInt(KEY_STRENGTH, value.strength());
                row.putInt(KEY_MAX, value.max());
                row.putBoolean(KEY_CAPTURED, value.captured());
                poolList.add(row);
            }
        }
        tag.put(KEY_POOLS, poolList);
        return tag;
    }

    /** True when this city has already placed its garrison - the answer that stops the second spawn. */
    public boolean hasSpawned(ResourceLocation dimension, String cityKey) {
        return this.entries.containsKey(key(dimension, cityKey));
    }

    /** Records a finished placement and marks the data dirty so it reaches disk on the next save. */
    public void markSpawned(ResourceLocation dimension, String cityKey, int squads, int units, long tick) {
        this.entries.put(key(dimension, cityKey),
                new Entry(cityKey, dimension, squads, units, tick));
        this.setDirty();
    }

    /** Every spawned garrison, in insertion order - what {@code /armedmobs garrison} lists. */
    public List<Entry> entries() {
        return new ArrayList<>(this.entries.values());
    }

    /** How many cities have a garrison - printed so the state is never a guess. */
    public int size() {
        return this.entries.size();
    }

    // ------------------------------------------------------------------ the city faction ledger

    /** This city's faction row, or null when its faction has not been decided yet. */
    @Nullable
    public CityRow city(ResourceLocation dimension, String cityKey) {
        return this.cities.get(key(dimension, cityKey));
    }

    /**
     * Records the city's dominant roll and every building's roll the FIRST time the city is asked about,
     * and never again: the whole point of the ledger is that a city's and a building's faction can never
     * change, not even when the config chances are edited later.
     */
    public void recordCity(ResourceLocation dimension, String cityKey, Faction dominant,
                           List<String> buildingIds, Faction[] rolled) {
        String entryKey = key(dimension, cityKey);
        if (this.cities.containsKey(entryKey)) {
            return;
        }
        this.cities.put(entryKey, new CityRow(cityKey, dimension, dominant, null, false));
        for (int i = 0; i < buildingIds.size() && i < rolled.length; i++) {
            String buildingId = buildingIds.get(i);
            if (buildingId == null || buildingId.isEmpty()) {
                continue;
            }
            this.buildings.put(buildingKey(dimension, cityKey, buildingId),
                    new BuildingRow(cityKey, dimension, buildingId, rolled[i], null));
        }
        this.setDirty();
    }

    /** Sets or clears the city-wide override (every building with no override of its own follows it). */
    public boolean overrideCity(ResourceLocation dimension, String cityKey, @Nullable Faction override) {
        String entryKey = key(dimension, cityKey);
        CityRow row = this.cities.get(entryKey);
        if (row == null) {
            return false;
        }
        // The spawners were written for the old faction; the next trigger or command must rewrite them.
        this.cities.put(entryKey, row.withOverride(override).withSpawnersDone(false));
        this.setDirty();
        return true;
    }

    /** Sets or clears one building's override - the finest-grained lever the command offers. */
    public boolean overrideBuilding(ResourceLocation dimension, String cityKey, String buildingId,
                                    @Nullable Faction override) {
        String entryKey = buildingKey(dimension, cityKey, buildingId);
        BuildingRow row = this.buildings.get(entryKey);
        if (row == null) {
            return false;
        }
        this.buildings.put(entryKey, row.withOverride(override));
        CityRow city = this.cities.get(key(dimension, cityKey));
        if (city != null) {
            this.cities.put(key(dimension, cityKey), city.withSpawnersDone(false));
        }
        this.setDirty();
        return true;
    }

    /** Records that every loaded chunk of this city was rewritten; the trigger then skips it. */
    public void markSpawnersDone(ResourceLocation dimension, String cityKey) {
        String entryKey = key(dimension, cityKey);
        CityRow row = this.cities.get(entryKey);
        if (row == null) {
            return;
        }
        this.cities.put(entryKey, row.withSpawnersDone(true));
        this.setDirty();
    }

    /**
     * The faction in force for one building: its own override, else the city override, else its own roll,
     * else the city's dominant faction. Null only when the city has no row at all.
     */
    @Nullable
    public Faction factionOf(ResourceLocation dimension, String cityKey, String buildingId) {
        CityRow city = this.cities.get(key(dimension, cityKey));
        if (city == null) {
            return null;
        }
        BuildingRow building = this.buildings.get(buildingKey(dimension, cityKey, buildingId));
        if (building != null && building.override() != null) {
            return building.override();
        }
        if (city.override() != null) {
            return city.override();
        }
        return building != null ? building.rolled() : city.dominant();
    }

    /** Every building row of one city, in insertion order. */
    public List<BuildingRow> buildingsOf(ResourceLocation dimension, String cityKey) {
        List<BuildingRow> out = new ArrayList<>();
        for (BuildingRow row : this.buildings.values()) {
            if (row.cityKey().equals(cityKey) && row.dimension().equals(dimension)) {
                out.add(row);
            }
        }
        return out;
    }

    /** Every city whose faction has been decided, in insertion order. */
    public List<CityRow> cities() {
        return new ArrayList<>(this.cities.values());
    }

    /** Every building row, in insertion order - the per-building half of {@code /armedmobs garrison}. */
    public List<BuildingRow> buildings() {
        return new ArrayList<>(this.buildings.values());
    }

    /** How many cities have a decided faction - printed so the state is never a guess. */
    public int cityCount() {
        return this.cities.size();
    }

    // ------------------------------------------------------------------ the capture pools

    /**
     * True when this city already has its pools. This is the answer that makes pool creation once-only: a
     * captured city has rows (one of them at zero), so a later approach rebuilds nothing and re-rolls nothing.
     */
    public boolean hasPools(ResourceLocation dimension, String cityKey) {
        return this.pools.containsKey(key(dimension, cityKey));
    }

    /** One faction's pool in one city, or null when this city has no pool for it. */
    @Nullable
    public PoolRow pool(ResourceLocation dimension, String cityKey, Faction faction) {
        Map<String, PoolRow> rows = this.pools.get(key(dimension, cityKey));
        return rows == null ? null : rows.get(CityFactions.name(faction));
    }

    /** Every pool of one city, in the order the factions were recorded. */
    public List<PoolRow> poolsOf(ResourceLocation dimension, String cityKey) {
        Map<String, PoolRow> rows = this.pools.get(key(dimension, cityKey));
        return rows == null ? List.of() : new ArrayList<>(rows.values());
    }

    /**
     * Creates one pool per faction of one city, all at {@code size}, the FIRST time the city is asked about -
     * and never again. A second call for a city that already has pools is a no-op and returns false, which is
     * the "not reversible by accident" half of the design: approaching a captured city cannot rebuild it.
     */
    public boolean createPools(ResourceLocation dimension, String cityKey, List<Faction> factions, int size) {
        String entryKey = key(dimension, cityKey);
        if (this.pools.containsKey(entryKey) || factions == null || factions.isEmpty()) {
            return false;
        }
        Map<String, PoolRow> rows = new LinkedHashMap<>();
        for (Faction faction : factions) {
            rows.put(CityFactions.name(faction),
                    new PoolRow(cityKey, dimension, faction, size, size, CapturePools.isCaptured(size)));
        }
        this.pools.put(entryKey, rows);
        this.setDirty();
        return true;
    }

    /**
     * Drains {@code amount} from one faction's pool for one death.
     *
     * <p>Returns the pool's strength <b>after</b> the drain, or {@code -1} when there was nothing to drain -
     * no pool for that faction in that city, or the pool was already spent. The caller needs both facts: a
     * return of exactly 0 is the moment a capture is decided and the one-line capture log belongs, while
     * {@code -1} means "not this faction's problem". A spent pool is left alone: the floor is the floor.</p>
     */
    public int drainPool(ResourceLocation dimension, String cityKey, Faction faction, int amount) {
        PoolRow row = pool(dimension, cityKey, faction);
        Map<String, PoolRow> rows = this.pools.get(key(dimension, cityKey));
        if (row == null || rows == null || row.captured()) {
            return -1;
        }
        PoolRow drained = row.withStrength(CapturePools.drain(row.strength(), amount));
        rows.put(CityFactions.name(faction), drained);
        this.setDirty();
        return drained.strength();
    }

    /**
     * Forces one faction's pool to a value - the end-game testing lever the command exposes. {@code max} is
     * deliberately NOT touched: the HUD keeps showing the bar the city was recorded with, so forcing a pool
     * to 1 and then killing the last man shows "0/28", not "0/1".
     */
    public boolean setPoolStrength(ResourceLocation dimension, String cityKey, Faction faction, int value) {
        PoolRow row = pool(dimension, cityKey, faction);
        Map<String, PoolRow> rows = this.pools.get(key(dimension, cityKey));
        if (row == null || rows == null) {
            return false;
        }
        rows.put(CityFactions.name(faction), row.withStrength(Math.max(0, value)));
        this.setDirty();
        return true;
    }

    /** Drops every pool of one city so {@code /armedmobs capture reset} can rebuild them from the line-up. */
    public boolean clearPools(ResourceLocation dimension, String cityKey) {
        if (this.pools.remove(key(dimension, cityKey)) == null) {
            return false;
        }
        this.setDirty();
        return true;
    }

    /** Who has taken this city: the factions that still have men, in ledger order. Never null. */
    public List<Faction> survivorsOf(ResourceLocation dimension, String cityKey) {
        List<Faction> out = new ArrayList<>();
        for (PoolRow row : poolsOf(dimension, cityKey)) {
            if (!row.captured()) {
                out.add(row.faction());
            }
        }
        return out;
    }

    /**
     * How many pools in the whole world are spent. Used as a cheap fast path before any city lookup: while
     * this is zero no faction can be refused a spawn, so the spawn gate does not pay for an area lookup.
     */
    public int capturedPoolCount() {
        int count = 0;
        for (Map<String, PoolRow> perFaction : this.pools.values()) {
            for (PoolRow row : perFaction.values()) {
                if (row.captured()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Every pool row, in insertion order - the capture half of {@code /armedmobs capture}. */
    public List<PoolRow> allPools() {
        List<PoolRow> out = new ArrayList<>();
        for (Map<String, PoolRow> perFaction : this.pools.values()) {
            out.addAll(perFaction.values());
        }
        return out;
    }

    /** How many cities have pools - printed so the state is never a guess. */
    public int poolCityCount() {
        return this.pools.size();
    }
}
