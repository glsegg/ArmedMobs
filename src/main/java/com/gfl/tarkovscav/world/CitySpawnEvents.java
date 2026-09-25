package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.entity.SniperPillagerEntity;
import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * City-only spawning.
 *
 * <p>The primary hook is {@link MobSpawnEvent.PositionCheck}: it is a {@code @HasResult} event fired
 * by {@code ForgeEventFactory.checkSpawnPosition}, and returning {@link Event.Result#DENY} makes the
 * caller skip the spawn outright - the mob is never added to the level. That is the path natural
 * spawns, spawners and structure spawns all take.</p>
 *
 * <p>{@link MobSpawnEvent.FinalizeSpawn} is only used for the manual cases ({@code /summon}, spawn
 * egg), and only when {@code spawn.gateCommandSpawns} asks for them to be gated too - by default a
 * server operator can always place one by hand to debug, which matters a lot while a city is being
 * built.</p>
 */
public final class CitySpawnEvents {
    private CitySpawnEvents() {
    }

    @SubscribeEvent
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        Mob mob = event.getEntity();
        boolean manual = isManualSpawn(event.getSpawnType());

        // The natural-spawn switch is a separate question from the city gate: it can take the gunner
        // villager out of the biome spawner entirely without touching the other two mobs, and it has to
        // be answered before the gate so the log says which of the two refused.
        if (mob instanceof GunnerVillagerEntity && !manual && !Config.GUNNER_VILLAGER_NATURAL_SPAWN.get()) {
            logRefusal(mob, event.getSpawnType(),
                    "spawn.gunnerVillagerNaturalSpawn = false (this mob only comes from an egg/command)");
            event.setResult(Event.Result.DENY);
            return;
        }

        // The sniper master switch, for BOTH snipers (README 5q): "false stops it spawning and stops the
        // post logic", which is what the key always claimed. A manual spawn still works, so an operator can
        // summon one to look at it.
        if (mob instanceof com.gfl.tarkovscav.gun.SniperMob && !manual && !Config.SNIPER_ENABLED.get()) {
            logRefusal(mob, event.getSpawnType(), "sniper.enabled = false");
            event.setResult(Event.Result.DENY);
            return;
        }

        // City faction purity (garrison.factionSpawnFilter): inside a city a mob of one line-up may not
        // appear in a building of the other, so a village building never spawns an illager unit and an
        // illager building never spawns a village one. SCAV is the unaligned third party and is allowed in
        // BOTH (the decision recorded in the README). This is asked BEFORE the cityOnly branch and does not
        // depend on it: a city is faction-pure whether or not the mob is city-only. A city with no ledger
        // entry yet rolls one on the spot (CityGarrison.factionFor), so this can never block everything.
        if (Config.GARRISON_FACTION_SPAWN_FILTER.get()
                && (!manual || Config.GATE_COMMAND_SPAWNS.get())
                && event.getLevel() instanceof ServerLevel factionLevel) {
            Faction mobFaction = Faction.of(mob);
            if (mobFaction == Faction.VILLAGE || mobFaction == Faction.ILLAGER) {
                BlockPos factionPos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
                Faction buildingFaction = CityGarrison.factionAt(factionLevel, factionPos);
                if (!CityFactions.allows(buildingFaction, mobFaction)) {
                    logRefusal(mob, event.getSpawnType(), "city building is "
                            + CityFactions.name(buildingFaction) + " (this is a "
                            + CityFactions.name(mobFaction) + " unit)");
                    event.setResult(Event.Result.DENY);
                    return;
                }
            }
        }

        Boolean cityOnly = cityOnlyFor(mob);
        if (cityOnly == null || !cityOnly) {
            return;
        }
        if (manual && !Config.GATE_COMMAND_SPAWNS.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // The snipers' two extra rules (README 5q), before the city gate so the log line is unambiguous.
        // Asked through SniperMob, so a third sniper is covered without touching this method.
        if (mob instanceof com.gfl.tarkovscav.gun.SniperMob && !manual) {
            String refusal = sniperRefusal(level, event.getX(), event.getY(), event.getZ());
            if (refusal != null) {
                logRefusal(mob, event.getSpawnType(), refusal);
                event.setResult(Event.Result.DENY);
                return;
            }
        }

        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        CityGate.Result result = CityGate.test(level, pos);
        log(mob, event.getSpawnType(), pos, result);
        if (!result.allowed()) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        Mob mob = event.getEntity();
        Boolean cityOnly = cityOnlyFor(mob);
        if (cityOnly == null || !cityOnly || !Config.GATE_COMMAND_SPAWNS.get()) {
            return;
        }
        if (!isManualSpawn(event.getSpawnType()) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        CityGate.Result result = CityGate.test(level, pos);
        log(mob, event.getSpawnType(), pos, result);
        if (!result.allowed()) {
            event.setSpawnCancelled(true);
            // This event only skips Mob#finalizeSpawn; nothing downstream removes the entity, so do it.
            mob.discard();
        }
    }

    /**
     * The sniper's own spawn rules (README 5q): never close to a player, and - by default - only on ground
     * that is meaningfully higher than its surroundings, which is how "snipers like high ground" is
     * implemented without a per-block list of rooftops. The elevation test samples eight columns at
     * {@code sniperElevationProbeRadius} and requires the click's Y to be at least
     * {@code sniper.minElevation} above most of them, so a rooftop, a ridge and a hill all qualify while a
     * street does not.
     */
    @Nullable
    private static String sniperRefusal(ServerLevel level, double x, double y, double z) {
        double minPlayerDistance = Config.SNIPER_MIN_SPAWN_DISTANCE.get();
        if (level.getNearestPlayer(x, y, z, minPlayerDistance, false) != null) {
            return "sniper.minSpawnDistanceFromPlayer = " + minPlayerDistance + " (a player is closer)";
        }
        if (!Config.SNIPER_PREFER_HIGH_GROUND.get()) {
            return null;
        }
        int probe = 12;
        int higher = 0;
        int sampled = 0;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int i = 0; i < 2; i++) {
                    int px = (int) Math.floor(x) + dx * (i == 0 ? probe : probe / 2);
                    int pz = (int) Math.floor(z) + dz * (i == 0 ? probe : probe / 2);
                    BlockPos ground = level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                            new BlockPos(px, (int) Math.floor(y), pz));
                    sampled++;
                    if (y - ground.getY() >= Config.SNIPER_MIN_ELEVATION.get()) {
                        higher++;
                    }
                }
            }
        }
        if (higher * 2 < sampled) {
            return "sniper.preferHighGround = true (only " + higher + "/" + sampled + " probe columns are "
                    + Config.SNIPER_MIN_ELEVATION.get() + " blocks lower)";
        }
        return null;
    }

    /** @return whether this mob type is city-only, or null when the gate does not apply to it. */
    @Nullable
    private static Boolean cityOnlyFor(Mob mob) {
        if (mob instanceof ScavEntity) {
            return Config.SCAV_CITY_ONLY.get();
        }
        // The sniper is a GunnerPillagerEntity subclass, so it must be named BEFORE it; the extra rules
        // (minimum distance from a player, elevated ground) are asked through SniperMob in onPositionCheck,
        // which has the level.
        if (mob instanceof SniperPillagerEntity) {
            return Config.GUNNER_PILLAGER_CITY_ONLY.get();
        }
        if (mob instanceof GunnerPillagerEntity) {
            return Config.GUNNER_PILLAGER_CITY_ONLY.get();
        }
        // Both villager types (the gunner and the sniper) end up here, so the villager sniper is gated by the
        // villager switch rather than the pillager one - the two families are separate on purpose.
        if (mob instanceof GunnerVillagerEntity) {
            return Config.GUNNER_VILLAGER_CITY_ONLY.get();
        }
        return null;
    }

    /** A refusal that is not the gate's: logged in the same shape so one log filter catches both. */
    private static void logRefusal(Mob mob, MobSpawnType type, String reason) {
        if (!Config.LOG_SPAWN_GATE.get()) {
            return;
        }
        TarkovScav.LOGGER.info("[spawngate] REJECT {} {}: {}", mob.getType().toShortString(), type, reason);
    }

    private static boolean isManualSpawn(MobSpawnType type) {
        return type == MobSpawnType.COMMAND || type == MobSpawnType.SPAWN_EGG;
    }

    private static void log(Mob mob, MobSpawnType type, BlockPos pos, CityGate.Result result) {
        if (!Config.LOG_SPAWN_GATE.get()) {
            return;
        }
        TarkovScav.LOGGER.info("[spawngate] {} {} at {} {}: {}", result.allowed() ? "ACCEPT" : "REJECT",
                mob.getType().toShortString(), pos, type, result.reason());
    }
}
