package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.BearPillagerEntity;
import com.gfl.tarkovscav.entity.ElitePillagerEntity;
import com.gfl.tarkovscav.entity.EliteVillagerEntity;
import com.gfl.tarkovscav.entity.SniperPillagerEntity;
import com.gfl.tarkovscav.entity.SniperVillagerEntity;
import com.gfl.tarkovscav.entity.UsecVillagerEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;

/**
 * The per-tier intelligence profile (README 5aa).
 *
 * <h2>What this class is</h2>
 * <p>The user's request: "the scav/thug series is the dumbest unit; then the snipers, who ambush and
 * keep their position hidden; then the elite and troop series, who cooperate, suppress, crawl along
 * behind cover and peek - the elite likes to rush, the troop plays steadily and tactically". That is
 * four behaviour tiers over <b>nine</b> existing entity types, so the tier is a property of the mob
 * (this class) rather than a branch inside {@link GunBrain}: adding a tenth type later is one line in
 * {@link #tierFor(Mob)}, and {@code tools/selftest_ai_profiles.js} can assert the whole mapping.</p>
 *
 * <h2>The baseline rule: profiles SCALE the global keys, they do not replace them</h2>
 * <p>Everything the user could already tune stays theirs. Each {@code *Scale} knob in
 * {@code Config.AiSettings} multiplies the matching global key, so a server operator who tuned
 * {@code [tactics]} keeps their numbers as the baseline the profile is applied to. The only absolute
 * values are the ones with no global key: the reaction window, {@code holdPost},
 * {@code minHitChance}, {@code patienceTicks} and {@code coordination}. {@code [ai] enabled = false}
 * turns the whole layer off and every method here answers with the raw global value, i.e. the exact
 * pre-feature behaviour.</p>
 *
 * <p>{@code tactics.retreatSprint} is deliberately not scaled: the user tuned sprinting off globally
 * ("running away = normal 1x") and no tier may quietly switch it back on.</p>
 *
 * <h2>Pure decision helpers</h2>
 * <p>The four behaviours the gate has to reason about - a randomised reaction delay, "does this
 * decision use cover", "is the estimated hit chance enough", "does this tier advance under cover" -
 * are factored into the small static methods at the bottom of this class. They take primitives and
 * return primitives, so {@code tools/selftest_ai_profiles.js} can mirror them, compare the two
 * sources textually, and run the mirror over the real cases without a game.</p>
 *
 * <p>README 5ab added three more of them, for the same reason: {@link #isExposed} is the exact
 * definition of "the target is standing in the open", {@link #exposedRuleApplies} is the double
 * opt-in that decides whether the tier's exposed-target rule may take over the trigger, and
 * {@link #scaledRetreatHealthFraction} is the absolute-vs-scaled resolution the hurt reaction uses.
 * {@code tools/selftest_ai_fire.js} mirrors all three.</p>
 */
public final class AiProfile {
    /** The four intelligence tiers, in the order the user described them. */
    public enum Tier {
        /** Plain scavs: slow, no cover to speak of, no suppression, straight-line advance. */
        SCAV("scav"),
        /** Snipers: patient, concealed firing positions, hold the post, never advance. */
        SNIPER("sniper"),
        /** USEC/BEAR troops: fast, cover to cover, strong suppression, coordinated. */
        TROOP("troop"),
        /** The elite pair: fastest reaction, short rushes, closest engagement, coordinated. */
        ELITE("elite");

        private final String id;

        Tier(String id) {
            this.id = id;
        }

        /** The config section name, e.g. {@code [ai.scav]}. */
        public String id() {
            return this.id;
        }

        /** Case-insensitive lookup, null when the name is unknown. */
        public static Tier byId(String id) {
            for (Tier tier : values()) {
                if (tier.id.equalsIgnoreCase(id)) {
                    return tier;
                }
            }
            return null;
        }
    }

    private AiProfile() {
    }

    // ------------------------------------------------------------------ the mapping entity -> profile

    /**
     * The tier an entity belongs to. The order of the checks is load-bearing: the four subclasses
     * ({@code elite_*}, {@code usec_villager}, {@code bear_pillager}, {@code sniper_*}) are all
     * subclasses of the two base gun mobs, so a subclass check placed after the base one would never
     * fire. Anything this class has never heard of is SCAV - the safe, dumb default.
     */
    public static Tier tierFor(Mob mob) {
        if (mob instanceof EliteVillagerEntity || mob instanceof ElitePillagerEntity) {
            return Tier.ELITE;
        }
        if (mob instanceof UsecVillagerEntity || mob instanceof BearPillagerEntity) {
            return Tier.TROOP;
        }
        if (mob instanceof SniperVillagerEntity || mob instanceof SniperPillagerEntity) {
            return Tier.SNIPER;
        }
        return Tier.SCAV;
    }

    /** True when the profile layer is on: the master key exists and is set. */
    public static boolean active() {
        return Config.SPEC.isLoaded() && Config.AI_ENABLED.get();
    }

    private static Config.AiSettings settings(Mob mob) {
        return Config.ai(tierFor(mob));
    }

    // ------------------------------------------------------------------ reaction

    /** The lower bound of the reaction delay, in ticks; the global key when the layer is off. */
    public static int reactionMinTicks(Mob mob) {
        return active() ? settings(mob).reactionMinTicks.get() : Config.REACTION_TICKS.get();
    }

    /** The upper bound of the reaction delay, in ticks; the global key when the layer is off. */
    public static int reactionMaxTicks(Mob mob) {
        return active() ? settings(mob).reactionMaxTicks.get() : Config.REACTION_TICKS.get();
    }

    /** A fresh randomised reaction delay for one target acquisition. */
    public static int rollReaction(RandomSource random, Mob mob) {
        return rollReactionTicks(random, reactionMinTicks(mob), reactionMaxTicks(mob));
    }

    // ------------------------------------------------------------------ accuracy / range

    /** Multiplies the gun tier's accuracy before the accuracy profile clamps the hit chance. */
    public static double accuracyScale(Mob mob) {
        return active() ? settings(mob).accuracyScale.get() : 1.0D;
    }

    /** Multiplies the gun tier's engage range; the elite closes to about 60 percent of it. */
    public static double engageRangeScale(Mob mob) {
        return active() ? settings(mob).engageRangeScale.get() : 1.0D;
    }

    // ------------------------------------------------------------------ cover

    /** 0..1: the chance that a cover decision actually looks for cover. */
    public static double coverChance(Mob mob) {
        return active() ? settings(mob).coverChance.get() : 1.0D;
    }

    /** Rolls the cover chance for one decision. Off (or full chance) always rolls true. */
    public static boolean rollsCoverUse(Mob mob) {
        return !active() || withinChance(coverChance(mob), mob.getRandom().nextDouble());
    }

    /** Scaled cover search radius, in blocks. */
    public static int coverSearchRadius(Mob mob) {
        if (!active()) {
            return Config.COVER_SEARCH_RADIUS.get();
        }
        return scaleRadius(Config.COVER_SEARCH_RADIUS.get(), settings(mob).coverRadiusScale.get());
    }

    /** Scaled cover cache lifetime, in ticks (never below 1). */
    public static int coverCacheTicks(Mob mob) {
        if (!active()) {
            return Config.COVER_CACHE_TICKS.get();
        }
        return Math.max(1, scaleTicks(Config.COVER_CACHE_TICKS.get(), settings(mob).coverCacheScale.get()));
    }

    /** Scaled minimum advance gain for a cover spot; 0 for the tiers that advance in the open. */
    public static double advanceCoverStep(Mob mob) {
        if (!active()) {
            return Config.ADVANCE_COVER_STEP.get();
        }
        return scaleValue(Config.ADVANCE_COVER_STEP.get(), settings(mob).advanceCoverScale.get());
    }

    /** False for a tier whose advance does not use cover at all (SCAV: a straight line). */
    public static boolean advancesUnderCover(Mob mob) {
        return !active() || advancesUnderCover(settings(mob).advanceCoverScale.get());
    }

    /** Multiplier for the three cover-seeking moves, on top of tactics.coverSeekSpeedModifier. */
    public static double coverSeekSpeedScale(Mob mob) {
        return active() ? settings(mob).coverSeekSpeedScale.get() : 1.0D;
    }

    /** Scaled extra score for a partially concealed (or elevated) sniper post. */
    public static double partialCoverBonus(Mob mob) {
        return active() ? settings(mob).partialCoverBonus.get() : 0.0D;
    }

    // ------------------------------------------------------------------ suppression

    /** tactics.suppressChance, multiplied by the tier's scale and clamped to 0..1. */
    public static double suppressChance(Mob mob) {
        if (!active()) {
            return Config.SUPPRESS_CHANCE.get();
        }
        return scaleChance(Config.SUPPRESS_CHANCE.get(), settings(mob).suppressChanceScale.get());
    }

    /** tactics.suppressTicks, multiplied by the tier's scale. */
    public static int suppressTicks(Mob mob) {
        if (!active()) {
            return Config.SUPPRESS_TICKS.get();
        }
        return scaleTicks(Config.SUPPRESS_TICKS.get(), settings(mob).suppressTicksScale.get());
    }

    /** tactics.suppressAccuracyMultiplier, multiplied by the tier's scale. */
    public static double suppressAccuracyMultiplier(Mob mob) {
        if (!active()) {
            return Config.SUPPRESS_ACCURACY_MULTIPLIER.get();
        }
        return scaleValue(Config.SUPPRESS_ACCURACY_MULTIPLIER.get(), settings(mob).suppressAccuracyScale.get());
    }

    /** tactics.suppressBurstMultiplier, multiplied by the tier's scale. */
    public static double suppressBurstMultiplier(Mob mob) {
        if (!active()) {
            return Config.SUPPRESS_BURST_MULTIPLIER.get();
        }
        return scaleValue(Config.SUPPRESS_BURST_MULTIPLIER.get(), settings(mob).suppressBurstScale.get());
    }

    // ------------------------------------------------------------------ movement / survival

    /** combat.repositionTicks, multiplied by the tier's scale. */
    public static int repositionTicks(Mob mob) {
        if (!active()) {
            return Config.REPOSITION_TICKS.get();
        }
        return scaleTicks(Config.REPOSITION_TICKS.get(), settings(mob).repositionScale.get());
    }

    /** combat.retreatHealthFraction, multiplied by the tier's scale. */
    public static float retreatHealthFraction(Mob mob) {
        if (!active()) {
            return Config.RETREAT_HEALTH_FRACTION.get().floatValue();
        }
        return (float) scaledRetreatHealthFraction(Config.RETREAT_HEALTH_FRACTION.get(),
                settings(mob).retreatHealthFraction.get(), settings(mob).retreatHealthScale.get());
    }

    /** True while this tier fights from a post instead of advancing (SNIPER). */
    public static boolean holdsPost(Mob mob) {
        return active() && settings(mob).holdPost.get();
    }

    // ------------------------------------------------------------------ lethal fire vs an exposed target (5ab)

    /**
     * Shots per burst while the target is exposed; 0 means "no override, the gun tier decides".
     * The sentinel is deliberately 0 and not -1, because -1 is the meaningful "-1 = empty the
     * magazine" the [tiers] burstShots key already uses.
     */
    public static int exposedBurstShots(Mob mob) {
        return active() ? settings(mob).exposedBurstShots.get() : 0;
    }

    /** Ticks to pause after a burst while the target is exposed; -1 = no override (gun tier's pause). */
    public static int exposedBurstCooldownTicks(Mob mob) {
        return active() ? settings(mob).exposedBurstCooldownTicks.get() : -1;
    }

    /** accuracy.warmupShots while the target is exposed; -1 = keep the global value. */
    public static int warmupShotsWhenExposed(Mob mob) {
        return active() ? settings(mob).warmupShotsWhenExposed.get() : -1;
    }

    /** combat.hurtRetreatChance for this tier; -1 = no override (the global key). */
    public static double hurtRetreatChance(Mob mob) {
        if (!active()) {
            return Config.HURT_RETREAT_CHANCE.get();
        }
        double absolute = settings(mob).hurtRetreatChance.get();
        return absolute < 0.0D ? Config.HURT_RETREAT_CHANCE.get() : absolute;
    }

    /**
     * How long this tier stays in cover after it has broken contact, in ticks; 0 = no hold (the
     * pre-5ab "re-evaluate after 40 ticks" rule).
     */
    public static int retreatHoldTicks(Mob mob) {
        return active() ? settings(mob).retreatHoldTicks.get() : 0;
    }

    // ------------------------------------------------------------------ sniper hold-fire

    /** 0 = off; otherwise the minimum estimated hit chance before a burst is started. */
    public static double minHitChance(Mob mob) {
        return active() ? settings(mob).minHitChance.get() : 0.0D;
    }

    /** How long a mob may wait for that hit chance before it re-decides; 0 = unbounded. */
    public static int patienceTicks(Mob mob) {
        return active() ? settings(mob).patienceTicks.get() : 0;
    }

    // ------------------------------------------------------------------ coordination

    /** True when this tier takes part in the squad layer and the co-ordination master key is on. */
    public static boolean coordination(Mob mob) {
        return active() && settings(mob).coordination.get()
                && Config.SPEC.isLoaded() && Config.AI_COORD_ENABLED.get();
    }

    /** One-line readout for {@code /tarkovscav debug} - "scav", "elite/off" when the layer is off. */
    public static String describe(Mob mob) {
        return tierFor(mob).id() + (active() ? "" : "/off");
    }

    // ------------------------------------------------------------------ pure decision helpers
    // Everything below takes primitives and returns primitives. tools/selftest_ai_profiles.js mirrors
    // these bodies and compares them with the Java text, so the simulation cannot drift from the ship.

    /** A reaction delay in [min, max], inclusive; max &lt;= min means "exactly min". */
    public static int rollReactionTicks(RandomSource random, int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return min + random.nextInt(max - min + 1);
    }

    /** A probability scale, clamped into 0..1. */
    public static double scaleChance(double value, double scale) {
        return Math.min(Math.max(value * scale, 0.0D), 1.0D);
    }

    /** A plain value scale, floored at zero. */
    public static double scaleValue(double value, double scale) {
        return Math.max(0.0D, value * scale);
    }

    /** A tick count scale, rounded and floored at zero. */
    public static int scaleTicks(int value, double scale) {
        return Math.max(0, (int) Math.round(value * scale));
    }

    /** A radius scale, rounded and floored at 1 (a search of zero candidates finds nothing). */
    public static int scaleRadius(int radius, double scale) {
        return Math.max(1, (int) Math.round(radius * scale));
    }

    /** One 0..1 roll against a 0..1 chance. */
    public static boolean withinChance(double chance, double roll) {
        return roll < chance;
    }

    /** A tier whose advance does not use cover has an advanceCoverScale of exactly zero. */
    public static boolean advancesUnderCover(double advanceCoverScale) {
        return advanceCoverScale > 0.0D;
    }

    /** The hold-fire rule: 0 (or less) means "no threshold", i.e. always allowed to shoot. */
    public static boolean hitChanceIsEnough(double hitChance, double minimum) {
        return minimum <= 0.0D || hitChance >= minimum;
    }

    /**
     * README 5ab, the exposure truth table, and the whole definition of "exposed" in one line.
     *
     * <p>{@code exposed} means: this mob can trace an unobstructed line from its own eyes to the
     * target's <b>eyes</b> AND to the target's <b>feet</b> (so the target is not behind cover from
     * here - neither its head nor its legs are hidden), AND the target is inside this mob's effective
     * range. A target that has tucked its head behind a wall but kept its legs in the open is
     * {@code eyes=false}, therefore NOT exposed; a target that is fully visible but beyond
     * {@code engageRange} is {@code inRange=false}, therefore not exposed either (that is a shot the
     * mob's own range rule already refuses). The two visibility halves are
     * {@code CombatTactics#canSeeEyes} / {@code #canSeeFeet}; the range half is
     * {@code GunBrain#engageRange}. {@code tools/selftest_ai_fire.js} prints and asserts this table.</p>
     */
    public static boolean isExposed(boolean eyesVisible, boolean feetVisible, boolean inRange) {
        return eyesVisible && feetVisible && inRange;
    }

    /**
     * README 5ab: does the exposed-target rule take over the trigger for this tier? It needs BOTH an
     * explicit burst length ({@code exposedBurstShots != 0}) and an explicit post-burst pause
     * ({@code exposedBurstCooldownTicks >= 0}). That double opt-in is what keeps the two non-lethal
     * tiers honest: SCAV ships both sentinels (the gun tier's short burst and its pause), and SNIPER
     * ships a burst length of 1 but no pause override, so its single shot is still followed by the gun
     * tier's cooldown and a reposition - the hold-post rhythm, not a spray.
     */
    public static boolean exposedRuleApplies(int exposedBurstShots, int exposedBurstCooldownTicks) {
        return exposedBurstShots != 0 && exposedBurstCooldownTicks >= 0;
    }

    /**
     * README 5ab: the health fraction below which a mob breaks contact, given the three numbers that
     * decide it. An ABSOLUTE per-tier value ({@code >= 0}) wins over the scaled global one; {@code -1}
     * is the sentinel meaning "no override", so the default tiers that only ship
     * {@code retreatHealthScale} keep working exactly as before, and the revert-to-dumb recipe (which
     * copies SCAV's -1 everywhere) is a no-op here. {@code 0.0} is a real value: "never retreat on
     * health alone".
     */
    public static double scaledRetreatHealthFraction(double global, double absolute, double scale) {
        return absolute >= 0.0D ? absolute : scaleValue(global, scale);
    }
}
