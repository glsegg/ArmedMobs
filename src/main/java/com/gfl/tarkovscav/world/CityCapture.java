package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.killfeed.KillFeed;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The city capture game (README 7p): the overworld-only tug-of-war the design doc asks for, in one place.
 *
 * <h2>The rule, in the user's words</h2>
 * <p>"Occupying cities only exists in the overworld"; "if it is not captured, units of a few line-ups keep
 * spawning until one side's strength is gone"; "the wasteland is a free-for-all, no capture needed". The whole
 * class therefore hangs behind one predicate, {@link #isOverworld(Level)}, and the wasteland keeps exactly
 * what it had: per-building factions and a brawl, with no pools, no HUD and no winner.</p>
 *
 * <h2>The four things this class does</h2>
 * <ol>
 *   <li><b>{@link #ensurePools}</b> - at the first approach (called from
 *       {@link CityGarrison#factionFor}, i.e. the EXISTING trigger, not a second scanner) it gives each
 *       faction present in the city one pool, sized from the city's building count by
 *       {@link CapturePools#poolSize}, and writes it to the ledger permanently.</li>
 *   <li><b>{@link #onDeath}</b> - every death of a VILLAGE or ILLAGER unit inside a city drains that
 *       faction's pool by {@code capture.drainPerKill}, floored at zero. With
 *       {@code capture.playerKillsOnly} only kills a player is behind count, and "a player is behind it" is
 *       read from the codebase's existing killer chain ({@link KillFeed#killerOf}) rather than a new one.</li>
 *   <li><b>The three vetoes</b> - {@link #vetoSpawn} (natural spawns AND spawner spawns, see below),
 *       {@link #vetoGarrison} (garrison placement) and the pool-at-zero decision itself. All three read the
 *       same ledger row, so they cannot disagree.</li>
 *   <li><b>{@link #syncHud}</b> - the server half of the strength bars: it sends numbers, never rendering.
 *       The client ({@code client/CaptureHud}) owns the look and the hide delay.</li>
 * </ol>
 *
 * <h2>The spawner veto, and the event that actually fires in 1.20.1</h2>
 * <p>The design doc names {@code LivingSpawnEvent.SpecialSpawn}. <b>That class does not exist in Forge
 * 1.20.1.</b> It was replaced by {@code MobSpawnEvent} (the three subclasses {@code SpawnPlacementCheck},
 * {@code PositionCheck} and {@code FinalizeSpawn}); the Forge 1.20.1 universal jar contains no
 * {@code LivingSpawnEvent} at all, and the {@code BaseSpawner.java.patch} in the Forge sources shows the
 * spawner path calling {@code ForgeEventFactory.checkSpawnPositionSpawner(...)}, which fires
 * {@code MobSpawnEvent.PositionCheck} with {@link MobSpawnType#SPAWNER}. So the honest implementation is the
 * same event the natural-spawn filter already uses: <b>one veto covers both paths</b>, and a DENY makes the
 * spawner {@code continue} before the entity is ever added to the level. The spawner <em>block</em> is never
 * touched - no rewrite, no extinguish - so the user's world is not altered and a reset is complete.</p>
 *
 * <p>{@link #vetoSpawn} refuses before it resolves a city whenever no pool anywhere is spent
 * ({@link GarrisonData#capturedPoolCount()}), so the extra area lookup is paid only in the end-game state
 * where it can actually deny something. That fast path is why this veto can sit on the hot spawn path.</p>
 *
 * <h2>What is deliberately NOT here</h2>
 * <ul>
 *   <li>Manual spawns ({@code /summon}, a spawn egg) are not vetoed, exactly like the existing city gate:
 *       an operator must always be able to put one unit down to look at it.</li>
 *   <li>A spent pool is never rebuilt by walking near the city again: {@link GarrisonData#createPools} is a
 *       no-op once rows exist, and {@link GarrisonData#recordCity} never re-rolls. Only
 *       {@code /armedmobs capture reset} clears and rebuilds.</li>
 * </ul>
 */
public final class CityCapture {
    /** The one prefix every line this feature writes uses, so one log filter catches the whole game. */
    private static final String LOG_PREFIX = "[capture]";

    private CityCapture() {
    }

    /** The one dimension gate every entry point in this class starts with. */
    public static boolean isOverworld(@Nullable Level level) {
        return level != null && level.dimension().equals(Level.OVERWORLD);
    }

    /** The two factions a city can be contested between. The SCAV third party has no pool (see CityFactions). */
    public static boolean isPoolFaction(@Nullable Faction faction) {
        return faction == Faction.VILLAGE || faction == Faction.ILLAGER;
    }

    // ------------------------------------------------------------------ 1. pool creation

    /**
     * Builds this city's pools the first time it is asked about, and never again. Called from
     * {@link CityGarrison#factionFor}, so it runs on the existing trigger and on the existing natural-spawn
     * path - no second scanner.
     *
     * <p><b>The city-that-existed-before-this-feature case.</b> A save written before this batch has faction
     * rows and building rows but no pool rows. The rule "create pools when the city's entry is first written"
     * would never fire for it, so instead this method builds them the first time the city is <em>asked
     * about</em> after the upgrade, from the building count already recorded in the ledger. The choice is the
     * least surprising one: the city keeps the line-up it was given (nothing is re-rolled) and simply gains
     * the pools it never had, sized exactly as a fresh city would be. The alternative - leaving old cities
     * permanently pool-less - would silently exempt every existing world from the whole feature.</p>
     *
     * @return true when rows were created, false when there was nothing to do (wasteland, capture off,
     *         already has pools, or no faction to build one for).
     */
    public static boolean ensurePools(ServerLevel level, CityGate.Area city, GarrisonData data) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()) {
            return false;
        }
        ResourceLocation dimension = level.dimension().location();
        if (data.hasPools(dimension, city.key())) {
            return false;
        }
        return buildPools(level, data, city.key(), city.name(), CityGarrison.buildingIds(city).size());
    }

    /** The shared sizing + write, used by both the first approach and the reset command. */
    private static boolean buildPools(ServerLevel level, GarrisonData data, String cityKey, String cityName,
                                      int liveBuildings) {
        ResourceLocation dimension = level.dimension().location();
        GarrisonData.CityRow row = data.city(dimension, cityKey);
        if (row == null) {
            // No faction was ever decided for this city, which cannot happen from factionFor (it records
            // before returning) but can from a hand-written key. Say so rather than inventing a line-up.
            TarkovScav.LOGGER.warn("{} {} -> no faction row, cannot build pools", LOG_PREFIX, cityKey);
            return false;
        }
        List<Faction> factions = factionsPresent(data, dimension, cityKey, row);
        if (factions.isEmpty()) {
            TarkovScav.LOGGER.warn("{} {} -> no village/illager building, cannot build pools", LOG_PREFIX,
                    cityKey);
            return false;
        }
        int recorded = data.buildingsOf(dimension, cityKey).size();
        int buildings = Math.max(1, recorded > 0 ? recorded : liveBuildings);
        int size = CapturePools.poolSize(buildings, Config.CAPTURE_POOL_MIN.get(),
                Config.CAPTURE_POOL_MAX.get(), Config.CAPTURE_POOL_PER_BUILDING.get());
        if (!data.createPools(dimension, cityKey, factions, size)) {
            return false;
        }
        TarkovScav.LOGGER.info("{} {} -> pools built: {} ({} building(s), {} each)", LOG_PREFIX, cityName,
                summary(data, dimension, cityKey), buildings, size);
        return true;
    }

    /**
     * The factions that actually appear in one city: the effective roll (own override, then city override,
     * then the roll) of every building, deduplicated. Falls back to the city's dominant faction when there
     * is no building row at all - a region or a runtime instance, whose whole box is one unit.
     */
    public static List<Faction> factionsPresent(GarrisonData data, ResourceLocation dimension, String cityKey,
                                                @Nullable GarrisonData.CityRow row) {
        List<Faction> out = new ArrayList<>(2);
        for (GarrisonData.BuildingRow building : data.buildingsOf(dimension, cityKey)) {
            Faction faction = data.factionOf(dimension, cityKey, building.buildingId());
            if (isPoolFaction(faction) && !out.contains(faction)) {
                out.add(faction);
            }
        }
        if (out.isEmpty() && row != null && isPoolFaction(row.effectiveDominant())) {
            out.add(row.effectiveDominant());
        }
        return out;
    }

    // ------------------------------------------------------------------ 2. the kill drain

    /**
     * One death. A VILLAGE or ILLAGER unit that dies inside an overworld city drains its own faction's pool
     * there; nothing else in the mod does.
     *
     * <p>The optional player gate reuses {@link KillFeed#killerOf} - the documented killer chain the kill
     * feed already trusts (damage source's causing entity, then the victim's last attacker, then its last
     * damage source) - instead of inventing a second attribution. A grenade thrown by a player therefore
     * counts, exactly as it does in the feed.</p>
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !isOverworld(level)) {
            return;
        }
        Faction faction = Faction.of(victim);
        if (!isPoolFaction(faction)) {
            return;
        }
        if (Config.CAPTURE_PLAYER_KILLS_ONLY.get()
                && !(KillFeed.killerOf(victim, event.getSource()) instanceof Player)) {
            return;
        }
        CityGate.Area city = CityGate.areaAt(level, victim.blockPosition());
        if (city == null) {
            return;
        }
        drain(level, city, GarrisonData.get(level.getServer()), faction,
                Config.CAPTURE_DRAIN_PER_KILL.get());
    }

    /**
     * Applies one pool drain and everything that follows from it. Returns true when the pool was <b>spent</b>
     * by this call - the exact zero the design makes the capture boundary.
     */
    public static boolean drain(ServerLevel level, CityGate.Area city, GarrisonData data, Faction faction,
                                int amount) {
        if (!isOverworld(level) || !isPoolFaction(faction)) {
            return false;
        }
        ResourceLocation dimension = level.dimension().location();
        List<GarrisonData.PoolRow> before = data.poolsOf(dimension, city.key());
        boolean firstSpend = before.size() >= 2
                && before.stream().noneMatch(GarrisonData.PoolRow::captured);
        int after = data.drainPool(dimension, city.key(), faction, amount);
        if (after < 0) {
            return false;
        }
        boolean spent = CapturePools.isCaptured(after);
        if (spent && firstSpend) {
            // ONE line, and only on the transition: the ledger's captured flag is what stops a restart (or
            // the second pool also reaching zero) from repeating it.
            logCapture(city, data, dimension, faction);
        }
        // Every drain is pushed immediately: a firefight is the one moment the bar must not lag a coarse
        // tick behind. syncHudNearby sends to the players inside the city radius only, and sends nothing at
        // all when capture.hudEnabled is off.
        syncHudNearby(level, city, data);
        return spent;
    }

    /** The single capture line: the city, who took it, and whose strength ran out. */
    private static void logCapture(CityGate.Area city, GarrisonData data, ResourceLocation dimension,
                                   Faction drained) {
        List<Faction> survivors = data.survivorsOf(dimension, city.key());
        String winner = survivors.isEmpty() ? "nobody (both sides exhausted)" : names(survivors);
        TarkovScav.LOGGER.info("{} {} captured by {} ({} strength exhausted)", LOG_PREFIX, city.name(), winner,
                CityFactions.name(drained));
    }

    // ------------------------------------------------------------------ 3. the three vetoes

    /**
     * The spawn veto for the natural-spawn path AND the spawner path - they are the SAME Forge event,
     * {@code MobSpawnEvent.PositionCheck}; see the class comment for the 1.20.1 evidence. Returns true when
     * this spawn must be denied.
     */
    public static boolean vetoSpawn(ServerLevel level, BlockPos pos, @Nullable Faction faction,
                                    MobSpawnType type) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()) {
            return false;
        }
        if (!isPoolFaction(faction)) {
            return false;
        }
        GarrisonData data = GarrisonData.get(level.getServer());
        if (data.capturedPoolCount() == 0) {
            // Fast path: no pool in the world has been spent, so no faction can be refused. This keeps the
            // extra city lookup off the hot spawn path for the whole game until a capture has happened.
            return false;
        }
        CityGate.Area city = CityGate.areaAt(level, pos);
        if (city == null) {
            return false;
        }
        GarrisonData.PoolRow pool = data.pool(level.dimension().location(), city.key(), faction);
        if (pool == null || !pool.captured()) {
            return false;
        }
        if (Config.LOG_SPAWN_GATE.get()) {
            TarkovScav.LOGGER.info("[spawngate] REJECT {} {} at {}: capture: {} has no strength left in {}",
                    CityFactions.name(faction), type, pos, CityFactions.name(faction), city.name());
        }
        return true;
    }

    /**
     * The garrison-placement veto: the third path. A unit whose faction has been driven out of this city is
     * not placed, so a city captured before its garrison could be placed does not quietly refill itself.
     */
    public static boolean vetoGarrison(ServerLevel level, String cityKey, Faction faction) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()) {
            return false;
        }
        if (!isPoolFaction(faction)) {
            return false;
        }
        GarrisonData.PoolRow pool = GarrisonData.get(level.getServer())
                .pool(level.dimension().location(), cityKey, faction);
        return pool != null && pool.captured();
    }

    // ------------------------------------------------------------------ 4. the HUD sync

    /**
     * Sends one player the current bars of one city - or a "hide" when the city is no longer contested.
     * The server sends names and numbers and nothing else; the client decides how it looks.
     *
     * <p>A city with fewer than two pools has no contest and is never sent at all, so a unified city never
     * produces a HUD. Once a pool is spent the city stops being contested and the client is told to hide
     * <b>immediately</b> (the design's "only one faction left" rule); the delayed hide is only for a player
     * who walked away, which the client times itself with {@code capture.hudHideDelaySeconds}.</p>
     */
    public static void syncHud(ServerLevel level, CityGate.Area city, GarrisonData data, ServerPlayer player) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()
                || !Config.CAPTURE_HUD_ENABLED.get()) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        List<GarrisonData.PoolRow> pools = data.poolsOf(dimension, city.key());
        if (pools.size() < 2) {
            return;
        }
        boolean contested = pools.stream().noneMatch(GarrisonData.PoolRow::captured);
        if (!contested) {
            CaptureHudNetwork.send(player, new CaptureHudNetwork.CaptureHudMessage(city.key(), city.name(),
                    List.of(), true));
            return;
        }
        List<CaptureHudNetwork.Bar> bars = new ArrayList<>(pools.size());
        for (GarrisonData.PoolRow pool : pools) {
            bars.add(new CaptureHudNetwork.Bar(CityFactions.name(pool.faction()), pool.strength(), pool.max(),
                    pool.captured()));
        }
        CaptureHudNetwork.send(player,
                new CaptureHudNetwork.CaptureHudMessage(city.key(), city.name(), bars, false));
    }

    /**
     * Sends the state to every player within the existing garrison trigger radius of the city. Called on a
     * pool change (a death) and from the coarse trigger, so a player standing in a city sees the bar move
     * within a tick of the kill that moved it, and a player who walks away simply stops receiving.
     */
    public static void syncHudNearby(ServerLevel level, CityGate.Area city, GarrisonData data) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()
                || !Config.CAPTURE_HUD_ENABLED.get()) {
            return;
        }
        double radius = Math.max(0.0D, Config.GARRISON_TRIGGER_RADIUS.get());
        if (radius <= 0.0D) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (CityGate.distanceToBox(city.box(), player.blockPosition()) <= radius) {
                syncHud(level, city, data, player);
            }
        }
    }

    // ------------------------------------------------------------------ the command's two levers

    /**
     * Rebuilds one city's pools from the line-up recorded in the ledger and clears every captured flag -
     * {@code /armedmobs capture reset}. Works by ledger key, so it needs no loaded city box and no second
     * lookup; the factions come from the building rows, which the reset does not touch, so nothing re-rolls.
     */
    public static boolean resetPools(ServerLevel level, GarrisonData data, String cityKey) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()) {
            return false;
        }
        ResourceLocation dimension = level.dimension().location();
        if (data.city(dimension, cityKey) == null) {
            return false;
        }
        data.clearPools(dimension, cityKey);
        boolean built = buildPools(level, data, cityKey, cityKey, 0);
        if (!built) {
            return false;
        }
        // The city is contested again: tell anyone standing in it now, not on the next coarse trigger.
        CityGate.Area city = cityAt(level, data, cityKey);
        if (city != null) {
            syncHudNearby(level, city, data);
        }
        TarkovScav.LOGGER.info("{} {} reset by an operator -> {}", LOG_PREFIX, cityKey,
                summary(data, dimension, cityKey));
        return true;
    }

    /**
     * Forces one faction's pool in one city to a value - {@code /armedmobs capture set}. Forcing it to zero
     * goes through the same capture decision as a real kill (log, HUD, spawn veto); forcing it back above
     * zero un-captures that faction and makes the city contested again.
     */
    public static boolean setPool(ServerLevel level, CityGate.Area city, GarrisonData data, Faction faction,
                                  int value) {
        if (!isOverworld(level) || !Config.SPEC.isLoaded() || !Config.CAPTURE_ENABLED.get()
                || !isPoolFaction(faction)) {
            return false;
        }
        ResourceLocation dimension = level.dimension().location();
        GarrisonData.PoolRow before = data.pool(dimension, city.key(), faction);
        if (before == null) {
            return false;
        }
        List<GarrisonData.PoolRow> pools = data.poolsOf(dimension, city.key());
        boolean firstSpend = pools.size() >= 2
                && pools.stream().noneMatch(GarrisonData.PoolRow::captured);
        if (!data.setPoolStrength(dimension, city.key(), faction, value)) {
            return false;
        }
        boolean spent = CapturePools.isCaptured(Math.max(0, value));
        if (spent && !before.captured() && firstSpend) {
            logCapture(city, data, dimension, faction);
        }
        syncHudNearby(level, city, data);
        return true;
    }

    /** The live city box behind a ledger key, or null when it is not near any loaded chunk. */
    @Nullable
    private static CityGate.Area cityAt(ServerLevel level, GarrisonData data, String cityKey) {
        int radius = (int) Math.round(Math.max(0.0D, Config.GARRISON_TRIGGER_RADIUS.get()));
        if (radius <= 0) {
            return null;
        }
        for (ServerPlayer player : level.players()) {
            for (CityGate.Area area : CityGate.citiesNear(level, player.blockPosition(), radius)) {
                if (area.key().equals(cityKey)) {
                    return area;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ diagnostics

    /** The lines {@code /armedmobs capture} prints: the config in force, then every known city. */
    public static List<String> describe(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add("capture.enabled=" + Config.CAPTURE_ENABLED.get()
                + " hudEnabled=" + Config.CAPTURE_HUD_ENABLED.get()
                + " poolMin=" + Config.CAPTURE_POOL_MIN.get()
                + " poolMax=" + Config.CAPTURE_POOL_MAX.get()
                + " poolPerBuilding=" + Config.CAPTURE_POOL_PER_BUILDING.get()
                + " drainPerKill=" + Config.CAPTURE_DRAIN_PER_KILL.get()
                + " playerKillsOnly=" + Config.CAPTURE_PLAYER_KILLS_ONLY.get()
                + " hudHideDelaySeconds=" + Config.CAPTURE_HUD_HIDE_DELAY_SECONDS.get());
        lines.add("pool = clamp(poolMin + buildings * poolPerBuilding, poolMin, poolMax);"
                + " overworld only; the spawner blocks are never rewritten or extinguished");
        GarrisonData data = GarrisonData.get(server);
        if (data.cityCount() == 0) {
            lines.add("no city has had its faction decided yet");
            return lines;
        }
        lines.add("cities (" + data.cityCount() + "):");
        for (GarrisonData.CityRow city : data.cities()) {
            lines.add("  " + city.dimension() + " " + city.cityKey()
                    + " dominant=" + CityFactions.name(city.dominant())
                    + (city.override() != null ? " override=" + CityFactions.name(city.override()) : "")
                    + " " + summary(data, city.dimension(), city.cityKey()));
        }
        lines.add("pools built in " + data.poolCityCount() + " city/cities, "
                + data.capturedPoolCount() + " pool(s) spent");
        return lines;
    }

    /** "pools=village 28/28, illager 0/28 captured by village" - the per-city capture half. */
    public static String summary(GarrisonData data, ResourceLocation dimension, String cityKey) {
        List<GarrisonData.PoolRow> pools = data.poolsOf(dimension, cityKey);
        if (pools.isEmpty()) {
            return "pools=none (wasteland, capture off, or not approached yet)";
        }
        StringBuilder builder = new StringBuilder("pools=");
        for (GarrisonData.PoolRow pool : pools) {
            if (builder.length() > "pools=".length()) {
                builder.append(", ");
            }
            builder.append(pool.describe());
        }
        if (pools.stream().anyMatch(GarrisonData.PoolRow::captured)) {
            List<Faction> survivors = data.survivorsOf(dimension, cityKey);
            builder.append(" captured by ")
                    .append(survivors.isEmpty() ? "nobody (both sides exhausted)" : names(survivors));
        }
        return builder.toString();
    }

    /** "village+illager" - the faction names of a survivor list. */
    private static String names(List<Faction> factions) {
        StringBuilder builder = new StringBuilder();
        for (Faction faction : factions) {
            if (builder.length() > 0) {
                builder.append('+');
            }
            builder.append(CityFactions.name(faction));
        }
        return builder.toString();
    }
}
