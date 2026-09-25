package com.gfl.tarkovscav.killfeed;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The kill feed's server half (README 5u): every death becomes at most one line, and the server decides who is
 * allowed to see it.
 *
 * <h2>Who the killer is</h2>
 * <p>The chain, in order: the damage source's causing entity (the shooter, not the arrow); the victim's
 * {@code lastHurtByMob}; the victim's {@code lastDamageSource}; then nothing at all, which is an environment
 * death. The request's {@code getKiller()}/{@code getLastHurtByPlayer()} steps cannot be written in 1.20.1
 * because {@code LivingEntity} does not expose either - see {@link #killerOf}.</p>
 *
 * <h2>Who sees it</h2>
 * <p>{@code killFeed.mode} is {@code involved} (default), {@code all} or {@code global}; on top of that a
 * per-player throttle ({@code maxPerSecond}) and a per-record dedup window keep a grenade that kills six mobs
 * from producing six simultaneous lines. The client is never asked to filter: it draws exactly what it is
 * sent.</p>
 */
public final class KillFeed {
    /** Per-player throttle: how many lines are still allowed in the current second window. */
    private static final Map<UUID, int[]> SENT = new HashMap<>();
    /** Dedup: "killer|victim|weapon" -&gt; the game tick it was last announced. */
    private static final Map<String, Long> SEEN = new HashMap<>();

    private KillFeed() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!Config.KILLFEED_ENABLED.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        DamageSource source = event.getSource();
        LivingEntity killer = killerOf(victim, source);
        boolean environment = killer == null && source.getEntity() == null && source.getDirectEntity() == null;
        if (!passesContentFilters(victim, killer, environment)) {
            return;
        }
        KillFeedWeapons.Armament armament = KillFeedWeapons.resolve(killer, victim, source);
        // README 5x: these names are rendered on the server, and a server has no language file - plus a key we
        // never shipped never resolves anywhere. safeName replaces anything that still looks like a key with the
        // entity type's readable path and WARNs once, so a raw key can never reach the screen.
        String killerName = killer == null ? "" : com.gfl.tarkovscav.entity.EntityNames.safeName(killer);
        String victimName = com.gfl.tarkovscav.entity.EntityNames.safeName(victim);
        long now = level.getGameTime();
        if (duplicate(killerName, victimName, armament.id(), now)) {
            return;
        }
        ItemStack weapon = armament.source() == KillFeedSource.ITEM ? armament.stack() : ItemStack.EMPTY;
        KillFeedNetwork.KillFeedMessage message = new KillFeedNetwork.KillFeedMessage(
                killerName, victimName, weapon, armament.source(), now);
        int receivers = 0;
        for (ServerPlayer player : recipients(level, victim, killer)) {
            if (!allow(level, player, message, now)) {
                continue;
            }
            KillFeedNetwork.send(player, message);
            receivers++;
        }
        TarkovScav.LOGGER.debug("[killfeed] {} [{}] {} -> {} receiver(s)",
                killerName.isEmpty() ? "Environment" : killerName, armament.id(), victimName, receivers);
    }

    // ------------------------------------------------------------------ the killer chain

    /**
     * The documented fallback chain; the first step that answers wins.
     *
     * <p>The chain the request described ({@code getKiller()} then {@code getLastHurtByPlayer()}) cannot be
     * written as such in 1.20.1: {@code LivingEntity} exposes <b>only</b> {@code getLastHurtByMob()} and
     * {@code getLastDamageSource()} - {@code lastHurtByPlayer} is a protected field with no public read
     * accessor, and there is no {@code getKiller()}. Verified against the mapped classes. The player cases are
     * therefore covered by asking the same two accessors and checking whether the answer <em>is</em> a
     * {@link Player}, which also gives the "player or mob?" question in the filters its answer.</p>
     */
    @Nullable
    public static LivingEntity killerOf(LivingEntity victim, DamageSource source) {
        // 1. the causing entity of the damage source - the shooter rather than the arrow, and the only step
        //    that names a MOB killer at all.
        if (source.getEntity() instanceof LivingEntity direct) {
            return direct;
        }
        // 2. the last mob that hurt it - the mob-versus-mob case, and the step that also covers "a player did
        //    it" (a Player is a LivingEntity).
        LivingEntity lastHurt = victim.getLastHurtByMob();
        if (lastHurt != null) {
            return lastHurt;
        }
        // 3. whatever the last damage came from (a source that has already been forgotten by the step above).
        DamageSource last = victim.getLastDamageSource();
        if (last != null && last.getEntity() instanceof LivingEntity late) {
            return late;
        }
        // 4. nothing: an environment death.
        return null;
    }

    // ------------------------------------------------------------------ the filters

    /** showMobKills / showPlayerKills / showEnvironmentDeaths, plus "environment deaths need somebody we know". */
    public static boolean passesContentFilters(LivingEntity victim, @Nullable LivingEntity killer,
                                               boolean environment) {
        if (environment) {
            // An environment death with nobody we know on either side is noise ("a cow fell off a cliff"), so
            // it takes both the switch AND a victim that is a player or one of our units.
            return Config.KILLFEED_SHOW_ENVIRONMENT_DEATHS.get()
                    && (victim instanceof Player || Faction.of(victim) != null);
        }
        boolean playerInvolved = victim instanceof Player || killer instanceof Player;
        return playerInvolved ? Config.KILLFEED_SHOW_PLAYER_KILLS.get() : Config.KILLFEED_SHOW_MOB_KILLS.get();
    }

    /**
     * The visibility rule. {@code involved} is the default and the only one that uses the radius: the killer,
     * the victim and everybody within {@code killFeed.radius} of the death hear about it.
     */
    public static List<ServerPlayer> recipients(ServerLevel level, LivingEntity victim,
                                                @Nullable LivingEntity killer) {
        String mode = Config.KILLFEED_MODE.get().toLowerCase(Locale.ROOT);
        List<ServerPlayer> out = new ArrayList<>();
        if (mode.equals("global")) {
            for (ServerLevel other : level.getServer().getAllLevels()) {
                out.addAll(other.players());
            }
            return out;
        }
        if (mode.equals("all")) {
            return new ArrayList<>(level.players());
        }
        double radius = Config.KILLFEED_RADIUS.get();
        for (ServerPlayer player : level.players()) {
            boolean involved = player == killer || player == victim
                    || player.distanceToSqr(victim) <= radius * radius;
            if (involved) {
                out.add(player);
            }
        }
        return out;
    }

    /** The throttle: at most {@code maxPerSecond} lines per player per second window. */
    private static boolean allow(ServerLevel level, ServerPlayer player,
                                 KillFeedNetwork.KillFeedMessage message, long now) {
        int max = Config.KILLFEED_MAX_PER_SECOND.get();
        int[] window = SENT.computeIfAbsent(player.getUUID(), key -> new int[] { 0, 0 });
        long second = now / 20L;
        if (window[1] != (int) second) {
            window[0] = 0;
            window[1] = (int) second;
        }
        if (window[0] >= max) {
            return false;
        }
        window[0]++;
        return true;
    }

    /** The dedup: the same killer, victim and weapon inside {@code dedupTicks} is one event, not two. */
    private static boolean duplicate(String killer, String victim, String weapon, long now) {
        int window = Config.KILLFEED_DEDUP_TICKS.get();
        if (window <= 0) {
            return false;
        }
        String key = killer + "|" + victim + "|" + weapon;
        Long last = SEEN.get(key);
        SEEN.put(key, now);
        // Keep the map from growing forever on a long-running server.
        if (SEEN.size() > 512) {
            SEEN.entrySet().removeIf(entry -> now - entry.getValue() > window);
        }
        return last != null && now - last <= window;
    }

    /** Forgets a player's throttle window (logout), so a recycled UUID starts clean. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }
}
