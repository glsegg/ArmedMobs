package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.gun.SquadCoordinator;
import com.gfl.tarkovscav.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-time city garrison: "one city fixes a few squads, they never respawn, a squad is normally
 * 1-5 people".
 *
 * <h2>What it does, in order</h2>
 * <ol>
 *   <li>every {@code garrison.checkIntervalTicks} (default 100 = every 5 s) it looks at each player and
 *       asks {@link CityGate#citiesNear} which city boxes are within {@code garrison.triggerRadius}
 *       (default 64) - only <b>loaded</b> chunks are inspected, so nobody's walk loads terrain;</li>
 *   <li>the first time a city is seen its factions are decided and <b>recorded</b>: a dominant line-up for
 *       the city (which may be the whole city's faction, or just the one it leans towards), and one faction
 *       per {@link CityBuildings.Building building} - see {@link CityFactions#rollBuildings};</li>
 *   <li>the city's spawners are rewritten ONCE per building, to that building's faction (loaded chunks only;
 *       the city is recorded as done only once every chunk of its box was covered);</li>
 *   <li>if the ledger says the city has no garrison yet, the squads are rolled and placed - each unit drawn
 *       from the faction of the building its own standing spot is in, so a contested city fields a mix that
 *       matches its buildings and a unified city stays pure;</li>
 *   <li>the garrison is written to the ledger <b>only after at least one unit entered the world</b>, so a
 *       placement that found no valid standing spot is retried rather than lost.</li>
 * </ol>
 *
 * <h2>Composition, and why it is those ids</h2>
 * <p>A squad is the TROOP tier of {@code gun/AiProfile.java} for its building's faction - a village building
 * fields {@code tarkovscav:usec_villager}, an illager building {@code tarkovscav:bear_pillager} - with one
 * additional ELITE leader ({@code tarkovscav:elite_villager} / {@code tarkovscav:elite_pillager}) at
 * {@code garrison.eliteLeaderChance} per squad. Nothing else is spawned here: the garrison is a fixed
 * defending force, not a second spawner.</p>
 *
 * <h2>They are one squad, through the existing layer</h2>
 * <p>Every member of a squad is stamped with a shared {@link SquadCoordinator#NBT_SQUAD_ID} and given a
 * claimed cover spot through {@link SquadCoordinator#claimFor}, so the squad layer's focus fire, bounding
 * overwatch, flanking and cover exclusivity treat them as one element - and two squads in the same city do
 * not merge, because the id filter is what the coordinator's proximity rule now also consults. No second
 * coordination system is written here.</p>
 *
 * <h2>Deterministic core</h2>
 * <p>{@link #due}, {@link #withinRadius} and {@link #squadsForCity} take primitives and return primitives,
 * and the faction rolls live in {@link CityFactions}, so {@code tools/selftest_garrison.js} and
 * {@code tools/selftest_city_faction.js} can mirror them without a game.</p>
 */
public final class CityGarrison {
    /** Hard ceiling on the size formula, so a huge city cannot ask for an army. */
    public static final int MAX_SQUADS = 6;

    /** The two TROOP ids, named once - the gate reads THIS list to prove the composition. */
    public static final List<String> TROOP_IDS = List.of("tarkovscav:usec_villager", "tarkovscav:bear_pillager");

    /** The two ELITE ids, used only for the optional leader. */
    public static final List<String> ELITE_LEADER_IDS =
            List.of("tarkovscav:elite_villager", "tarkovscav:elite_pillager");

    /** The building id a city with no building breakdown is recorded under (a region, a runtime instance). */
    public static final String WHOLE_CITY = "city";

    /** Minimum distance between two garrison members, so a squad reads as a spread-out element. */
    private static final int MIN_SPACING = 2;

    /** Placement attempts per pass; two passes (indoors, then anywhere) per city. */
    private static final int ATTEMPTS_PER_PASS = 600;

    /** Last coarse check tick, per dimension. Never a static "spawned" flag - that is the ledger's job. */
    private static final Map<ResourceLocation, Long> LAST_CHECK = new HashMap<>();

    /** Source of squad ids for this server run, offset by game time so a restart cannot collide. */
    private static int squadIdCounter = 1;

    private CityGarrison() {
    }

    // ------------------------------------------------------------------ the deterministic core

    /** Is the coarse check due? A never-run dimension is always due. */
    public static boolean due(long now, long lastCheck, int intervalTicks) {
        return lastCheck == Long.MIN_VALUE || now - lastCheck >= Math.max(1, intervalTicks);
    }

    /** The trigger radius guard: a non-positive radius never triggers, so "off" cannot mean "everywhere". */
    public static boolean withinRadius(double distance, double radius) {
        return radius > 0.0D && distance <= radius;
    }

    /**
     * How many squads this city gets.
     *
     * <p>{@code configuredSquads >= 1} pins every city to that number. Otherwise the shipped <b>size
     * formula</b> is used: {@code 1 + max(width, depth) / 48}, clamped to 1..{@value #MAX_SQUADS}. The
     * formula spends roughly one squad per 48 blocks of the city's longest horizontal axis, so the small
     * preset gets one squad and a full district gets several, with no per-city tuning.</p>
     */
    public static int squadsForCity(int width, int depth, int configuredSquads) {
        if (configuredSquads >= 1) {
            return Math.min(configuredSquads, MAX_SQUADS);
        }
        int span = Math.max(width, depth);
        return Math.min(Math.max(1 + span / 48, 1), MAX_SQUADS);
    }

    /** The squad-size roll, with min/max swapped when a hand-edited toml put them the wrong way round. */
    public static int rollSquadSize(RandomSource random, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        low = Math.max(1, low);
        high = Math.max(1, high);
        return low >= high ? low : low + random.nextInt(high - low + 1);
    }

    /** The building ids a city is recorded under: its real buildings, or the single whole-city unit. */
    public static List<String> buildingIds(CityGate.Area city) {
        if (city.buildings().isEmpty()) {
            return List.of(WHOLE_CITY);
        }
        List<String> ids = new ArrayList<>(city.buildings().size());
        for (CityBuildings.Building building : city.buildings()) {
            ids.add(building.id());
        }
        return ids;
    }

    /** The building id at a position, or the whole-city unit when the city has no breakdown. */
    public static String buildingIdAt(CityGate.Area city, BlockPos pos) {
        int index = CityBuildings.indexAt(city.buildings(), pos);
        return index >= 0 ? city.buildings().get(index).id() : WHOLE_CITY;
    }

    // ------------------------------------------------------------------ the server tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || !Config.SPEC.isLoaded() || !Config.GARRISON_ENABLED.get()) {
            return;
        }
        long now = server.overworld().getGameTime();
        int interval = Config.GARRISON_CHECK_INTERVAL_TICKS.get();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dimension = level.dimension().location();
            long last = LAST_CHECK.getOrDefault(dimension, Long.MIN_VALUE);
            if (!due(now, last, interval)) {
                continue;
            }
            LAST_CHECK.put(dimension, now);
            checkLevel(level, now);
        }
    }

    /** One coarse pass over one dimension: find the cities near each player and apply what is missing. */
    private static void checkLevel(ServerLevel level, long now) {
        int radius = (int) Math.round(Config.GARRISON_TRIGGER_RADIUS.get());
        if (radius <= 0) {
            return;
        }
        GarrisonData data = GarrisonData.get(level.getServer());
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            for (CityGate.Area city : CityGate.citiesNear(level, pos, radius)) {
                double distance = CityGate.distanceToBox(city.box(), pos);
                if (!withinRadius(distance, radius)) {
                    continue;
                }
                applyCity(level, city, data, now);
                // The capture HUD's steady state (README 7p): the same trigger that already knows this
                // player is near this city pushes the bars, so walking away simply stops the packets and the
                // client's own hide delay takes over. Overworld-only inside the call; a wasteland city sends
                // nothing because it has no pools.
                CityCapture.syncHud(level, city, data, player);
            }
        }
    }

    // ------------------------------------------------------------------ decide, rewrite, place

    /** The whole per-city job: decide (once), rewrite the spawners (once), place the garrison (once). */
    public static void applyCity(ServerLevel level, CityGate.Area city, GarrisonData data, long now) {
        ResourceLocation dimension = level.dimension().location();
        GarrisonData.CityRow row = factionFor(level, city, data);
        rewriteSpawners(level, city, data, dimension, row);
        if (!data.hasSpawned(dimension, city.key())) {
            spawn(level, city, data, now, dimension);
        }
    }

    /**
     * The city's faction row, rolled and RECORDED the first time the city is asked about - from the trigger
     * or from a natural-spawn check. A recorded roll is never recomputed, so editing the config chances
     * cannot flip a city that already exists.
     *
     * <p>The capture pools are built here too (README 7p), for the same "the first time the city is asked
     * about" reason: this method IS the existing trigger, so pool creation needs no second scanner, and it
     * runs for a city recorded before the capture feature existed as well
     * ({@link CityCapture#ensurePools} builds the missing rows from the ledger's own building count). Once
     * pools exist the call is a hash lookup and a no-op, which is what makes a captured city impossible to
     * rebuild by walking past it.</p>
     */
    public static GarrisonData.CityRow factionFor(ServerLevel level, CityGate.Area city, GarrisonData data) {
        ResourceLocation dimension = level.dimension().location();
        GarrisonData.CityRow existing = data.city(dimension, city.key());
        if (existing == null) {
            Faction dominant = CityFactions.rollCity(level.getSeed(), dimension, city.key(),
                    Config.GARRISON_FRIENDLY_CITY_CHANCE.get());
            List<String> ids = buildingIds(city);
            Faction[] rolled = CityFactions.rollBuildings(level.getSeed(), dimension, city.key(), dominant,
                    Config.GARRISON_CITY_DOMINANT_FACTION_CHANCE.get(), ids.size());
            data.recordCity(dimension, city.key(), dominant, ids, rolled);
            TarkovScav.LOGGER.info("[garrison] {} -> faction {} decided ({} building(s))",
                    city.name(), CityFactions.name(dominant), ids.size());
            existing = data.city(dimension, city.key());
        }
        CityCapture.ensurePools(level, city, data);
        return existing;
    }

    /**
     * The faction a position inside a city is subject to: the faction of the building it belongs to,
     * falling back to the city's effective dominant faction. Null when the position is not in a city at all.
     */
    @Nullable
    public static Faction factionAt(ServerLevel level, BlockPos pos) {
        CityGate.Area city = CityGate.areaAt(level, pos);
        if (city == null) {
            return null;
        }
        GarrisonData data = GarrisonData.get(level.getServer());
        GarrisonData.CityRow row = factionFor(level, city, data);
        Faction faction = data.factionOf(level.dimension().location(), city.key(), buildingIdAt(city, pos));
        return faction != null ? faction : row.effectiveDominant();
    }

    /** The faction recorded for one building of a city (a convenience for the command and the gate). */
    public static Faction buildingFaction(GarrisonData data, ResourceLocation dimension, String cityKey,
                                          String buildingId) {
        Faction faction = data.factionOf(dimension, cityKey, buildingId);
        GarrisonData.CityRow row = data.city(dimension, cityKey);
        if (faction != null) {
            return faction;
        }
        return row != null ? row.effectiveDominant() : Faction.VILLAGE;
    }

    /** Rewrites the loaded chunks of a city to their buildings' factions, once, and records it. */
    private static void rewriteSpawners(ServerLevel level, CityGate.Area city, GarrisonData data,
                                        ResourceLocation dimension, GarrisonData.CityRow row) {
        if (row.spawnersDone() || !Config.GARRISON_REWRITE_CITY_SPAWNERS.get()) {
            return;
        }
        CityFactionSpawners.Result result = CityFactionSpawners.rewrite(level, city.box(),
                pos -> data.factionOf(dimension, city.key(), buildingIdAt(city, pos)));
        if (result.rewritten() > 0) {
            TarkovScav.LOGGER.info("[garrison] {} -> {} spawner(s) rewritten per building: {}",
                    city.name(), result.rewritten(), buildingSummary(data, city, dimension));
        }
        if (result.complete()) {
            data.markSpawnersDone(dimension, city.key());
        }
    }

    /** "0:north_west=village, 1:north_east=illager" - the per-building line the diagnostics print. */
    public static String buildingSummary(GarrisonData data, CityGate.Area city, ResourceLocation dimension) {
        StringBuilder builder = new StringBuilder();
        for (String id : buildingIds(city)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(id).append('=')
                    .append(CityFactions.name(data.factionOf(dimension, city.key(), id)));
        }
        return builder.toString();
    }

    /** Places one city's garrison, and records it in the ledger only when something was actually placed. */
    private static void spawn(ServerLevel level, CityGate.Area city, GarrisonData data, long now,
                              ResourceLocation dimension) {
        RandomSource random = level.getRandom();
        int squads = squadsForCity(city.width(), city.depth(), Config.GARRISON_SQUADS_PER_CITY.get());
        int minSize = Config.GARRISON_SQUAD_SIZE_MIN.get();
        int maxSize = Config.GARRISON_SQUAD_SIZE_MAX.get();
        double leaderChance = Config.GARRISON_ELITE_LEADER_CHANCE.get();

        // One pool of spots for the whole city: every squad draws from it, and MIN_SPACING keeps the whole
        // garrison spread out instead of stacking four squads on one roof.
        List<BlockPos> spots = pickSpots(level, city, squads * (Math.max(minSize, maxSize) + 1));
        int cursor = 0;
        int placedSquads = 0;
        int placedUnits = 0;
        int refusedByCapture = 0;
        Map<String, Integer> perFaction = new HashMap<>();

        for (int s = 0; s < squads; s++) {
            int squadId = nextSquadId(now, random);
            int size = rollSquadSize(random, minSize, maxSize);
            boolean leader = random.nextDouble() < leaderChance;
            int inThisSquad = 0;
            for (int i = 0; i < size + (leader ? 1 : 0); i++) {
                if (cursor >= spots.size()) {
                    break;
                }
                BlockPos spot = spots.get(cursor++);
                boolean isLeader = leader && i == size;
                // The unit follows the building its OWN spot is in - that is what keeps a contested city
                // mixed along building lines instead of mixing inside one building.
                String buildingId = buildingIdAt(city, spot);
                Faction faction = buildingFaction(data, dimension, city.key(), buildingId);
                // The third spawn path (README 7p): a faction whose pool is spent is not put back into the
                // city, so a city captured before its garrison could be placed does not refill itself. The
                // veto reads the same ledger row as the natural-spawn and spawner vetoes, so the three can
                // never disagree about who still has men.
                if (CityCapture.vetoGarrison(level, city.key(), faction)) {
                    refusedByCapture++;
                    continue;
                }
                if (place(level, spot, isLeader, squadId, random, faction)) {
                    inThisSquad++;
                    perFaction.merge(CityFactions.name(faction), 1, Integer::sum);
                }
            }
            if (inThisSquad > 0) {
                placedSquads++;
                placedUnits += inThisSquad;
            }
        }

        if (placedUnits <= 0) {
            // No ledger write: the next coarse check retries. The alternative - marking it done - would
            // leave the city permanently empty without a single line in the log to explain it.
            if (refusedByCapture > 0) {
                // A captured city is a legitimate reason to place nothing, so it is not the WARN above: the
                // city may be reset later, and the retry is what makes that work without a restart.
                TarkovScav.LOGGER.info("[capture] {} -> garrison not placed: {} unit(s) refused by the capture"
                        + " veto (and/or no valid standing spot); will retry", city.name(), refusedByCapture);
                return;
            }
            TarkovScav.LOGGER.warn("[garrison] {} -> no valid standing spot found, will retry",
                    city.name());
            return;
        }
        data.markSpawned(dimension, city.key(), placedSquads, placedUnits, now);
        TarkovScav.LOGGER.info("[garrison] {} -> {} squads / {} units [dominant {}: {}]",
                city.name(), placedSquads, placedUnits,
                CityFactions.name(buildingFaction(data, dimension, city.key(), WHOLE_CITY)),
                perFaction);
    }

    /** Spawns one unit, stamps the squad id and reserves its cover block. */
    private static boolean place(ServerLevel level, BlockPos pos, boolean elite, int squadId,
                                 RandomSource random, Faction faction) {
        EntityType<? extends Mob> type = pickType(random, elite, faction);
        if (type == null) {
            return false;
        }
        Mob mob = type.create(level);
        if (mob == null) {
            return false;
        }
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                random.nextFloat() * 360.0F, 0.0F);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.STRUCTURE, null, null);
        // A garrison is a permanent fixture: without this the mob would despawn the moment the player who
        // triggered it walks away, and "a fixed garrison that never respawns" would quietly become "none".
        mob.setPersistenceRequired();
        SquadCoordinator.assignSquad(mob, squadId);
        level.addFreshEntity(mob);
        SquadCoordinator.claimFor(mob, coverAnchor(level, pos));
        return true;
    }

    /**
     * The faction's TROOP unit, or its ELITE unit for a leader. The faction is the whole decision: the
     * random source only picks the facing, never the line-up, so a squad can never mix inside one building.
     */
    private static EntityType<? extends Mob> pickType(RandomSource random, boolean elite, Faction faction) {
        boolean villager = faction != Faction.ILLAGER;
        if (elite) {
            return villager ? ModEntities.ELITE_VILLAGER.get() : ModEntities.ELITE_PILLAGER.get();
        }
        return villager ? ModEntities.USEC_VILLAGER.get() : ModEntities.BEAR_PILLAGER.get();
    }

    /** A fresh squad id: game time plus a run-local counter, so a restart cannot repeat one. */
    private static int nextSquadId(long now, RandomSource random) {
        squadIdCounter = squadIdCounter == Integer.MAX_VALUE ? 1 : squadIdCounter + 1;
        return (int) (now & 0xFFFF) * 4096 + (squadIdCounter & 0xFFF);
    }

    // ------------------------------------------------------------------ placement rules

    /**
     * Valid standing spots inside or around the city: solid ground below, two blocks of headroom, never
     * in a doorway, spread at least {@value #MIN_SPACING} blocks apart. Two passes, interiors first, so
     * "prefer building interiors" is a preference and never a hard failure on an open city.
     */
    public static List<BlockPos> pickSpots(ServerLevel level, CityGate.Area city, int want) {
        List<BlockPos> out = new ArrayList<>();
        if (want <= 0) {
            return out;
        }
        RandomSource random = level.getRandom();
        BoundingBox box = city.box();
        for (int pass = 0; pass < 2 && out.size() < want; pass++) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_PASS && out.size() < want; attempt++) {
                BlockPos pos = randomIn(level, box, random);
                if (pos == null) {
                    break;
                }
                if (pass == 0 && level.canSeeSky(pos)) {
                    continue;
                }
                if (!isValidStandingSpot(level, pos)) {
                    continue;
                }
                if (tooClose(out, pos)) {
                    continue;
                }
                out.add(pos.immutable());
            }
        }
        return out;
    }

    /** A random position inside the box (one block of margin), or null when the box is degenerate. */
    private static BlockPos randomIn(ServerLevel level, BoundingBox box, RandomSource random) {
        int spanX = box.maxX() - box.minX() - 1;
        int spanZ = box.maxZ() - box.minZ() - 1;
        int spanY = box.maxY() - box.minY();
        if (spanX < 0 || spanZ < 0) {
            return null;
        }
        int x = box.minX() + 1 + (spanX <= 0 ? 0 : random.nextInt(spanX + 1));
        int z = box.minZ() + 1 + (spanZ <= 0 ? 0 : random.nextInt(spanZ + 1));
        int y = box.minY() + (spanY <= 0 ? 0 : random.nextInt(spanY + 1));
        return new BlockPos(x, y, z);
    }

    /**
     * The standing rule, in order: solid floor under it, no collision in the cell, no collision in the
     * cell above, and not a door (a garrison must never stand in a doorway it then blocks).
     */
    public static boolean isValidStandingSpot(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        BlockState here = level.getBlockState(pos);
        if (!here.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        BlockPos above = pos.above();
        BlockState head = level.getBlockState(above);
        if (!head.getCollisionShape(level, above).isEmpty()) {
            return false;
        }
        if (here.is(BlockTags.DOORS) || head.is(BlockTags.DOORS) || floor.is(BlockTags.DOORS)) {
            return false;
        }
        return true;
    }

    private static boolean tooClose(List<BlockPos> chosen, BlockPos pos) {
        int limit = MIN_SPACING * MIN_SPACING;
        for (BlockPos other : chosen) {
            int dx = other.getX() - pos.getX();
            int dz = other.getZ() - pos.getZ();
            if (dx * dx + dz * dz < limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block a member will treat as its cover: the first sturdy neighbour, so the claim the squad layer
     * holds is a wall and not thin air. Falls back to the floor block, which {@link SquadCoordinator#claimFor}
     * accepts and the coordinator's own expiry then frees.
     */
    private static BlockPos coverAnchor(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.getBlockState(side).isFaceSturdy(level, side, direction.getOpposite())) {
                return side;
            }
        }
        return pos.below();
    }

    // ------------------------------------------------------------------ diagnostics

    /** The lines {@code /armedmobs garrison} prints: the config in force, then the ledger. */
    public static List<String> describe(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        int configured = Config.GARRISON_SQUADS_PER_CITY.get();
        lines.add("garrison.enabled=" + Config.GARRISON_ENABLED.get()
                + " squadsPerCity=" + (configured >= 1 ? String.valueOf(configured)
                        : "formula (1 + max(width,depth)/48, clamp 1.." + MAX_SQUADS + ")")
                + " squadSize=" + Config.GARRISON_SQUAD_SIZE_MIN.get()
                + "-" + Config.GARRISON_SQUAD_SIZE_MAX.get()
                + " eliteLeaderChance=" + Config.GARRISON_ELITE_LEADER_CHANCE.get()
                + " triggerRadius=" + Config.GARRISON_TRIGGER_RADIUS.get()
                + " checkIntervalTicks=" + Config.GARRISON_CHECK_INTERVAL_TICKS.get());
        lines.add("city fractions: friendlyCityChance=" + Config.GARRISON_FRIENDLY_CITY_CHANCE.get()
                + " cityDominantFactionChance=" + Config.GARRISON_CITY_DOMINANT_FACTION_CHANCE.get()
                + " rewriteCitySpawners=" + Config.GARRISON_REWRITE_CITY_SPAWNERS.get()
                + " factionSpawnFilter=" + Config.GARRISON_FACTION_SPAWN_FILTER.get());
        lines.add("troops: " + TROOP_IDS + ", leaders: " + ELITE_LEADER_IDS);
        lines.addAll(describeFactions(server));
        GarrisonData data = GarrisonData.get(server);
        if (data.size() == 0) {
            lines.add("no city has placed its garrison yet");
        } else {
            lines.add("garrisons placed (" + data.size() + "):");
            for (GarrisonData.Entry entry : data.entries()) {
                lines.add("  " + entry.describe());
            }
        }
        return lines;
    }

    /**
     * The faction half of the diagnostics: every city that has had its faction decided, with every one of
     * its buildings named. This is what {@code /armedmobs city faction} prints with no arguments.
     */
    public static List<String> describeFactions(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        GarrisonData data = GarrisonData.get(server);
        if (data.cityCount() == 0) {
            lines.add("no city has had its faction decided yet");
            return lines;
        }
        lines.add("cities with a decided faction (" + data.cityCount() + "):");
        for (GarrisonData.CityRow city : data.cities()) {
            lines.add("  " + city.dimension() + " " + city.cityKey()
                    + " dominant=" + CityFactions.name(city.dominant())
                    + (city.override() != null ? " override=" + CityFactions.name(city.override()) : "")
                    + " spawners=" + (city.spawnersDone() ? "rewritten" : "pending"));
            for (GarrisonData.BuildingRow building : data.buildingsOf(city.dimension(), city.cityKey())) {
                lines.add("    " + building.describe());
            }
        }
        return lines;
    }

    /** True when this exact city already has a garrison - the one question the trigger has to answer. */
    public static boolean alreadyPlaced(MinecraftServer server, ResourceLocation dimension, String cityKey) {
        return GarrisonData.get(server).hasSpawned(dimension, cityKey);
    }
}
