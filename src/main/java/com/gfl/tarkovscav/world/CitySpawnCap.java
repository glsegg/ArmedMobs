package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.gun.GunUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-city limit on how many gun-armed units of one line-up may be alive at the same time (README 7q).
 *
 * <h2>The problem it answers</h2>
 * <p>The user's report (verbatim in README 7q; this file is ASCII-only by the project's rule for Java
 * sources): spawners keep spawning endlessly and it causes serious stutter, so unless a unit was placed by
 * hand with a spawn egg, a city should hold at most twelve gun-armed units of one line-up alive at the same
 * time. The city spawners are real spawner blocks, the natural spawner keeps working, and the capture game
 * keeps reinforcing both sides while their pools are above zero - so a city the player is not clearing turns
 * into a standing army, and every one of those units runs the full gun AI. The pools bound the
 * <em>total</em> number of deaths, not how many stand in the streets at once; this is the missing bound.</p>
 *
 * <h2>Who is counted, and who is exempt</h2>
 * <ul>
 *   <li>Counted: {@link GunUser} mobs - this mod's nine armed types and nothing else. A vanilla villager or
 *       pillager that the faction tags also cover is not a gun unit and is not the cost this key exists for.</li>
 *   <li>Per city and per faction: the count is taken over {@link CityGate.Area#box()} of the city the spawn is
 *       in, for the mob's own faction. So a contested city holds up to N villagers AND up to N illagers, which
 *       is what makes the tug-of-war readable instead of a wall of one side.</li>
 *   <li>Exempt: {@code MobSpawnType.SPAWN_EGG} and {@code MobSpawnType.COMMAND} (i.e. "the player placed it by
 *       hand", exactly the user's carve-out), unless {@code spawn.cityFactionCapIgnoreManual} says otherwise.</li>
 *   <li>Both dimensions: it is a performance guard, so it applies wherever a city region is recognised
 *       (overworld and {@code tarkovscav:urban_wasteland}). The capture game is still overworld-only.</li>
 * </ul>
 *
 * <h2>The three spawn paths</h2>
 * <p>The natural spawner and the spawner blocks are the same Forge event
 * ({@link net.minecraftforge.event.entity.living.MobSpawnEvent.PositionCheck}, see {@link CitySpawnEvents}),
 * and the third path - the garrison - places units directly, so it asks
 * {@link #vetoGarrison} per unit. Nothing here reads, rewrites or extinguishes a spawner block.</p>
 *
 * <h2>Cost</h2>
 * <p>One level query per city+faction per {@code spawn.cityFactionCapCountTicks} (default 20 ticks), and one
 * hash lookup per spawn attempt. The fast path - no cache entry yet, or a fresh entry below the cap - never
 * scans. Kill it with {@code spawn.cityFactionCapEnabled = false} and the whole thing is skipped before the
 * first lookup.</p>
 */
public final class CitySpawnCap {
    /** One city's one faction: the last measured live count, this window's accepts, and when it was measured. */
    private static final class Window {
        private int live;
        private int accepted;
        private long countedAt = Long.MIN_VALUE;
    }

    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private CitySpawnCap() {
    }

    /**
     * The spawn veto for the natural and spawner paths. Returns true when this spawn must be denied because the
     * city already holds the cap for this faction; a denied spawn is logged once, in the same shape as the
     * city gate's refusals so one log filter catches both.
     */
    public static boolean vetoSpawn(ServerLevel level, BlockPos pos, Mob mob, @Nullable MobSpawnType type) {
        if (!Config.SPEC.isLoaded() || !Config.CITY_FACTION_CAP_ENABLED.get()) {
            return false;
        }
        boolean manual = type == MobSpawnType.SPAWN_EGG || type == MobSpawnType.COMMAND;
        if (manual && !Config.CITY_FACTION_CAP_IGNORE_MANUAL.get()) {
            return false;                       // the user's carve-out: a hand-placed unit is never capped
        }
        if (!counted(mob)) {
            return false;                       // not a gun unit: vanilla villagers/pillagers are not our cost
        }
        Faction faction = Faction.of(mob);
        if (faction == null) {
            return false;
        }
        CityGate.Area city = CityGate.areaAt(level, pos);
        if (city == null) {
            return false;                       // not in a city: no cap (the wasteland's open ground is uncapped)
        }
        return decide(level, city, faction, mob.getType().toShortString());
    }

    /**
     * The garrison's veto, asked once per unit before it is placed. The garrison is the third spawn path and
     * does not go through the spawn event, so it has to ask this itself.
     */
    public static boolean vetoGarrison(ServerLevel level, CityGate.Area city, @Nullable Faction faction) {
        if (!Config.SPEC.isLoaded() || !Config.CITY_FACTION_CAP_ENABLED.get()) {
            return false;
        }
        if (faction == null) {
            return false;
        }
        return decide(level, city, faction, "garrison");
    }

    /** The shared decision: refresh the window if it aged out, compare, and book an accept when it passes. */
    private static boolean decide(ServerLevel level, CityGate.Area city, Faction faction, String source) {
        int cap = Config.CITY_FACTION_CAP.get();
        Window window = WINDOWS.computeIfAbsent(cacheKey(level, city, faction), key -> new Window());
        synchronized (window) {
            long now = level.getGameTime();
            if (SpawnCapMath.stale(now, window.countedAt, Config.CITY_FACTION_CAP_COUNT_TICKS.get())) {
                window.live = countLive(level, city, faction);
                window.accepted = 0;
                window.countedAt = now;
            }
            int effective = SpawnCapMath.effectiveCount(window.live, window.accepted);
            if (SpawnCapMath.overCap(window.live, window.accepted, cap)) {
                if (Config.LOG_SPAWN_GATE.get()) {
                    TarkovScav.LOGGER.info("[spawncap] REJECT {} {} in {} ({} already there: live={} accepted{}"
                                    + "  this window, cap={})", source, CityFactions.name(faction), city.name(),
                            effective, window.live, window.accepted, cap);
                }
                return true;
            }
            window.accepted++;                  // this one is about to be allowed; see the class javadoc
            return false;
        }
    }

    /** True for this mod's nine armed types (and nothing else). */
    private static boolean counted(Mob mob) {
        return mob instanceof GunUser;
    }

    private static String cacheKey(ServerLevel level, CityGate.Area city, Faction faction) {
        return level.dimension().location() + "|" + city.key() + "|" + faction.name();
    }

    /**
     * How many of this faction's gun units are alive inside the city's box right now.
     *
     * <p>The AABB is built from the bounding box by hand rather than through {@code AABB.of(BoundingBox)},
     * because a bounding box's maximum corner is an inclusive block coordinate while an AABB's is a real
     * coordinate: the upper corner is extended by one so an entity standing on the box's last block row is
     * inside the count.</p>
     */
    private static int countLive(ServerLevel level, CityGate.Area city, Faction faction) {
        BoundingBox box = city.box();
        AABB area = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        return level.getEntitiesOfClass(Mob.class, area,
                mob -> mob.isAlive() && counted(mob) && Faction.of(mob) == faction).size();
    }

    /**
     * The runtime state, for {@code /armedmobs spawncap}: the config in force, then every city+faction the cap
     * has measured. Deliberately reads the CACHE and never scans - a diagnostic that costs a level query per
     * city every time it is typed would be its own performance problem.
     */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("spawn.cityFactionCapEnabled = " + Config.CITY_FACTION_CAP_ENABLED.get()
                + ", cap = " + Config.CITY_FACTION_CAP.get()
                + ", countTicks = " + Config.CITY_FACTION_CAP_COUNT_TICKS.get()
                + ", ignoreManual = " + Config.CITY_FACTION_CAP_IGNORE_MANUAL.get());
        if (WINDOWS.isEmpty()) {
            out.add("  nothing measured yet - a city's count is taken on the first spawn attempt inside it");
            return out;
        }
        int cap = Config.CITY_FACTION_CAP.get();
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Window> entry : WINDOWS.entrySet()) {
            Window window = entry.getValue();
            int effective = SpawnCapMath.effectiveCount(window.live, window.accepted);
            sorted.put(entry.getKey(), "  " + entry.getKey() + " live=" + window.live
                    + " acceptedThisWindow=" + window.accepted + " counted=" + effective
                    + (SpawnCapMath.overCap(window.live, window.accepted, cap)
                            ? "  AT/OVER CAP " + cap : "  below cap " + cap));
        }
        out.addAll(sorted.values());
        return out;
    }

    /** Forgets every measurement, so the next spawn attempt recounts. Backs {@code /armedmobs spawncap reset}. */
    public static int reset() {
        int size = WINDOWS.size();
        WINDOWS.clear();
        return size;
    }
}
