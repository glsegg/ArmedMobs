package com.gfl.tarkovscav.faction;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Friendly fire, its consequences, and the "renegade" brand (README 5m).
 *
 * <h2>Where the state lives, and why nothing needed a field on the entity</h2>
 * <p>Everything (the flag, the hit counter, the window start, the victims) is stored in
 * {@code Entity#getPersistentData()}, which Forge saves and loads with the entity. That gives NBT
 * persistence for free, works for any future faction member without touching its class, and keeps the
 * marker visible to a command or a data pack.</p>
 *
 * <h2>The rules, exactly</h2>
 * <ol>
 *   <li>Only damage between two members of the <b>same</b> faction counts. Attacking a player, another
 *       faction, or a mob with no faction is never friendly fire.</li>
 *   <li>One hit counts as <b>one</b> hit - huge damage does not count double (the user's rule, and the
 *       simplest one to reason about).</li>
 *   <li>Hits are counted inside a sliding window ({@code faction.friendlyFireWindowTicks}); when the
 *       window lapses the counter restarts.</li>
 *   <li>Attacking a <b>renegade</b> never counts - once somebody is an outlaw, shooting them is public
 *       service.</li>
 *   <li><b>One free retaliation</b>: if the attacker was itself already hit by the victim (i.e. it is
 *       hitting back), that hit does not count towards the attacker's betrayal total. A victim may answer
 *       once without becoming a traitor itself.</li>
 *   <li>{@code faction.friendlyFireHitsToAnger} hits from the <b>same</b> attacker inside the window make
 *       the victim turn on <em>that attacker by UUID</em> - nobody else, and only that pair.</li>
 *   <li>{@code faction.betrayalThreshold} hits from the same attacker mark the <b>attacker</b> as a
 *       RENEGADE: every ally within {@code faction.renegadeBroadcastRadius} treats it as an enemy, it
 *       treats everybody as an enemy, the marker is written to NBT, and (by default) it never decays.</li>
 * </ol>
 */
public final class Renegade {
    private static final String TAG_RENEGADE = "TarkovScavRenegade";
    private static final String TAG_HITS = "TarkovScavFriendlyHits";
    private static final String TAG_WINDOW_START = "TarkovScavFriendlyWindow";
    private static final String TAG_SINCE = "TarkovScavRenegadeSince";
    private static final String TAG_VICTIM = "TarkovScavFriendlyVictim";

    private Renegade() {
    }

    /** True when this entity has been branded (NBT-backed, so it survives save/load). */
    public static boolean is(@Nullable Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(TAG_RENEGADE);
    }

    private static void mark(Entity entity) {
        entity.getPersistentData().putBoolean(TAG_RENEGADE, true);
    }

    /**
     * Records one friendly-fire hit and applies the two thresholds. Returns true when this hit made the
     * attacker a renegade (the caller does not need to do anything else; everything is done here).
     */
    public static boolean noteFriendlyFire(ServerLevel level, Mob attacker, LivingEntity victim) {
        if (!Config.FACTION_ENABLED.get() || attacker == victim) {
            return false;
        }
        // Rule 1: same faction only. Rule 4: shooting a renegade is not friendly fire.
        if (Faction.of(attacker) == null || Faction.of(attacker) != Faction.of(victim) || is(victim)) {
            return false;
        }
        // Rule 5: a single retaliation is free.
        if (attacker.getLastHurtByMob() == victim) {
            return false;
        }
        long now = level.getGameTime();
        var data = attacker.getPersistentData();
        int window = Config.FACTION_FRIENDLY_FIRE_WINDOW_TICKS.get();
        long windowStart = data.getLong(TAG_WINDOW_START);
        int hits = now - windowStart > window ? 0 : data.getInt(TAG_HITS);
        // Rule 6: anger is per attacker/victim pair, so a hit on somebody else resets the pairing.
        UUID previousVictim = data.hasUUID(TAG_VICTIM) ? data.getUUID(TAG_VICTIM) : null;
        if (previousVictim != null && !previousVictim.equals(victim.getUUID())) {
            hits = 0;
        }
        hits++;
        data.putInt(TAG_HITS, hits);
        data.putLong(TAG_WINDOW_START, now);
        data.putUUID(TAG_VICTIM, victim.getUUID());

        TarkovScav.LOGGER.info("[faction] {} hit friendly {} ({}/{})", attacker.getName().getString(),
                victim.getName().getString(), hits, Config.FACTION_BETRAYAL_THRESHOLD.get());

        if (hits >= Config.FACTION_FRIENDLY_FIRE_HITS_TO_ANGER.get()) {
            victim.setTarget(attacker);
            TarkovScav.LOGGER.warn("[faction] {} is now hostile to {} ({} friendly hit(s))",
                    victim.getName().getString(), attacker.getName().getString(), hits);
        }
        if (hits >= Config.FACTION_BETRAYAL_THRESHOLD.get() && !is(attacker)) {
            brand(level, attacker, "friendly fire x" + hits);
            return true;
        }
        return false;
    }

    /** Brands an entity as a renegade: NBT, name, optional glow, and the shout to every nearby ally. */
    public static void brand(ServerLevel level, Mob attacker, String reason) {
        mark(attacker);
        attacker.getPersistentData().putLong(TAG_SINCE, level.getGameTime());

        if (Config.FACTION_RENEGADE_GLOW.get()) {
            attacker.setGlowingTag(true);
        }
        Component name = Component.translatable("tarkovscav.faction.renegade")
                .append(" ").append(attacker.getName());
        attacker.setCustomName(name);

        var allies = level.getEntitiesOfClass(Mob.class,
                attacker.getBoundingBox().inflate(Config.FACTION_RENEGADE_BROADCAST_RADIUS.get()));
        int told = 0;
        for (Mob ally : allies) {
            if (ally != attacker && Faction.of(ally) == Faction.of(attacker)) {
                told++;
            }
        }
        TarkovScav.LOGGER.warn("[faction] {} is now a RENEGADE ({}), broadcast to {} ally/ies within {}",
                attacker.getName().getString(), reason, told,
                Config.FACTION_RENEGADE_BROADCAST_RADIUS.get());
        AlertNetwork.forget(attacker);
    }

    /**
     * Optional decay: with {@code faction.renegadeDecayTicks = 0} (the default) a brand is permanent - the
     * user asked for that, and "the traitor stays a traitor" is the more interesting rule. A positive
     * value forgives after that many ticks.
     */
    public static void tickDecay(ServerLevel level, Mob mob) {
        int decay = Config.FACTION_RENEGADE_DECAY_TICKS.get();
        if (decay <= 0 || !is(mob)) {
            return;
        }
        long since = mob.getPersistentData().getLong(TAG_SINCE);
        if (level.getGameTime() - since > decay) {
            mob.getPersistentData().remove(TAG_RENEGADE);
            mob.getPersistentData().remove(TAG_SINCE);
            mob.getPersistentData().remove(TAG_HITS);
            mob.setGlowingTag(false);
            mob.setCustomName(null);
            TarkovScav.LOGGER.info("[faction] {} is no longer a renegade (decayed after {} ticks)",
                    mob.getName().getString(), decay);
        }
    }

    /** One-line report for {@code /tarkovscav debug}. */
    public static String describe(Mob mob) {
        if (!is(mob)) {
            return "renegade=false";
        }
        return "renegade=true hits=" + mob.getPersistentData().getInt(TAG_HITS);
    }
}
