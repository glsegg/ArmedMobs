package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavTier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * How good a shot a mob is, over the length of an engagement (README 5o).
 *
 * <h2>The two rules the user asked for</h2>
 * <ol>
 *   <li><b>The first shots are wild.</b> A mob that just opened fire is not yet "warmed up": its accuracy is
 *       multiplied by {@code accuracy.warmupMultiplier} for its first {@code accuracy.warmupShots} shots
 *       (8 by default). This is the mob equivalent of a player who has just spotted you - and it is what
 *       gives the player the first seconds of a fight. README 5ab added one exception, and only one: while
 *       the target is standing in the OPEN (eyes AND feet visible, in range) a tier may set
 *       {@code [ai.<tier>] warmupShotsWhenExposed} to 0, so TROOP and ELITE do not waste the first burst of
 *       an engagement on an enemy who is not even behind cover. SCAV and SNIPER keep the global 8.</li>
 *   <li><b>Steady state is capped at 75 %.</b> However good the tier's {@code accuracy} is (the sniper's is
 *       0.85, deliberately better than a rifleman's), the value actually used is clamped to
 *       {@code accuracy.steadyStateCap} = 0.75. At 1.0 a mob never misses; the cap is what keeps a sniper
 *       lethal rather than unfair, and it applies to <em>every</em> tier, so no future tier can quietly
 *       bypass it.</li>
 * </ol>
 *
 * <p>The shot counter lives in the mob's persistent data (so it survives a reload) and decays: after
 * {@code accuracy.resetTicks} without firing (600 = 30 s) the counter resets, so a mob that loses you and
 * finds you again is wild again - which is the same "warm-up" idea applied to re-engagements.</p>
 *
 * <p>This class deliberately does not touch TaCZ's own spread, recoil or ballistics: it feeds the one
 * number {@code GunBrain#computeAim} already used (the share of the aim error to remove), so the whole
 * change is one multiplier and one clamp, and {@code tools/selftest_accuracy.js} can check the resulting hit
 * probability without a game.</p>
 */
public final class AccuracyProfile {
    private static final String TAG_SHOTS = "tarkovscav:shotsFired";
    private static final String TAG_LAST_SHOT = "tarkovscav:lastShotTick";

    /** The three accuracy classes. A new unit is a config line, not a new branch in this class. */
    public enum Profile {
        ROOKIE("rookie"),
        VETERAN("veteran"),
        ELITE("elite");

        private final String id;

        Profile(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        /** Case-insensitive name lookup; an unknown name is null (callers WARN once and use rookie). */
        @Nullable
        public static Profile byName(@Nullable String name) {
            if (name == null) {
                return null;
            }
            for (Profile profile : values()) {
                if (profile.id.equalsIgnoreCase(name.trim())) {
                    return profile;
                }
            }
            return null;
        }
    }

    private AccuracyProfile() {
    }

    /** Shots this mob has fired since it last went quiet. */
    public static int shotsFired(Mob mob) {
        return mob.getPersistentData().getInt(TAG_SHOTS);
    }

    /** True while the warm-up penalty applies, for the shot this mob is about to take. */
    public static boolean warmingUp(Mob mob) {
        return shotsFired(mob) < warmupShots(mob);
    }

    /**
     * README 5ab: the warm-up threshold in force for the NEXT shot.
     *
     * <p>Normally this is {@code accuracy.warmupShots} (8). While the target counts as EXPOSED -
     * eyes AND feet visible and in range, see {@link AiProfile#isExposed} - a tier may name its own
     * threshold with {@code [ai.&lt;tier&gt;] warmupShotsWhenExposed}: TROOP and ELITE ship 0, i.e. no
     * wild shots at all against an enemy standing in the open, while SCAV and SNIPER keep -1 and
     * therefore keep the global 8.</p>
     *
     * <p>The verdict is not computed here: the brain takes it once per tick, before it pulls the
     * trigger, and publishes it through {@link GunUser#targetExposedNow}. That is what makes the
     * burst-length rule (what {@code GunBrain#burstSize} fires) and this warm-up waiver agree about the
     * same tick instead of each doing its own ray casts at a slightly different moment. The profile cap
     * (rookie 0.75 / veteran 0.85 / elite 0.90) and {@code accuracy.hardCeiling} still clamp the result
     * exactly as before - a waived warm-up can never turn a mob into a never-misses shooter.</p>
     */
    public static int warmupShots(Mob mob) {
        int global = Config.ACCURACY_WARMUP_SHOTS.get();
        if (!(mob instanceof GunUser user) || !user.targetExposedNow()) {
            return global;
        }
        int override = AiProfile.warmupShotsWhenExposed(mob);
        return override < 0 ? global : override;
    }

    /**
     * The accuracy to use for this shot. Three things happen, in this order:
     * <ol>
     *   <li>the tier's own value, multiplied by the warm-up penalty while the mob is still warming up;</li>
     *   <li>clamped to the mob's own <b>profile</b> cap ({@link #capFor(Mob)}) - which is what makes 0.75 the
     *       small fry's number and lets a sniper be better without one global switch;</li>
     *   <li>and then <b>clamped again so the resulting hit chance cannot exceed the same cap</b>. This is the
     *       step that makes "75 %" mean "hits at most 75 % of the time" rather than "has a number no bigger
     *       than 0.75": at close range a 0.25 error cone still covers a player almost every time, so without
     *       this step the cap would not bind where the user actually feels it. The clamp is a bisection on
     *       the same cone model the aim path uses, so it is exact rather than a fudge factor.</li>
     * </ol>
     */
    public static double accuracyFor(Mob mob, double tierAccuracy, double distance, double targetRadius) {
        double value = warmingUp(mob)
                ? tierAccuracy * Config.ACCURACY_WARMUP_MULTIPLIER.get()
                : tierAccuracy;
        double cap = capFor(mob);
        double capped = Mth.clamp(value, 0.0D, Math.min(1.0D, cap));
        return clampToHitChance(capped, distance, targetRadius, cap);
    }

    /** The cap of a profile, never above the absolute {@code accuracy.hardCeiling}. */
    public static double capFor(Profile profile) {
        double raw = switch (profile) {
            case ROOKIE -> Config.ACCURACY_ROOKIE_CAP.get();
            case VETERAN -> Config.ACCURACY_VETERAN_CAP.get();
            case ELITE -> Config.ACCURACY_ELITE_CAP.get();
        };
        double ceiling = Mth.clamp(Config.ACCURACY_HARD_CEILING.get(), 0.0D, 1.0D);
        return Mth.clamp(raw, 0.0D, ceiling);
    }

    /**
     * The profile a mob belongs to: its <b>entity type</b> decides first, then its <b>tier</b> may promote it
     * ({@code accuracy.profileSniperTier}). "Promote" means the higher cap wins, so the extra rule can never
     * make a unit worse - and because the promotion is by tier, the sniper entity that arrives later needs no
     * entry in this class at all.
     */
    public static Profile profileFor(Mob mob) {
        // README 5y: the faction troops bring their own profile (USEC/BEAR -> veteran, elite -> elite), so the
        // "these mobs are better shots" rule lives on the entity and not in a list here.
        Profile troop = com.gfl.tarkovscav.entity.FactionTierProfile.profileFor(mob);
        if (troop != null) {
            return troop;
        }
        Profile byType;
        if (mob.getType() == com.gfl.tarkovscav.registry.ModEntities.SCAV.get()) {
            byType = named(Config.ACCURACY_PROFILE_SCAV.get(), "profileScav");
        } else if (mob.getType() == com.gfl.tarkovscav.registry.ModEntities.GUNNER_PILLAGER.get()) {
            byType = named(Config.ACCURACY_PROFILE_GUNNER_PILLAGER.get(), "profileGunnerPillager");
        } else if (mob.getType() == com.gfl.tarkovscav.registry.ModEntities.GUNNER_VILLAGER.get()) {
            byType = named(Config.ACCURACY_PROFILE_GUNNER_VILLAGER.get(), "profileGunnerVillager");
        } else {
            // Anything else - a faction member added by a data pack, or an entity this class has never heard
            // of - is rookie: the safe default is the user's 75 %, never "trust me".
            byType = Profile.ROOKIE;
        }
        if (mob instanceof GunUser user && user.scavTier() == ScavTier.SNIPER) {
            Profile sniper = named(Config.ACCURACY_PROFILE_SNIPER_TIER.get(), "profileSniperTier");
            if (capFor(sniper) > capFor(byType)) {
                return sniper;
            }
        }
        return byType;
    }

    /** The cap actually used for this mob (its profile's cap, clamped by the hard ceiling). */
    public static double capFor(Mob mob) {
        return capFor(profileFor(mob));
    }

    private static Profile named(String name, String key) {
        Profile profile = Profile.byName(name);
        if (profile == null) {
            warnOnce(name, key);
            return Profile.ROOKIE;
        }
        return profile;
    }

    private static final java.util.Set<String> WARNED_NAMES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(String name, String key) {
        if (WARNED_NAMES.add(key + "=" + name)) {
            TarkovScav.LOGGER.warn("[accuracy] {} names the unknown profile '{}'; using rookie. Valid values:"
                    + " rookie, veteran, elite", key, name);
        }
    }

    /**
     * The largest accuracy no greater than {@code accuracy} whose hit chance stays at or below {@code cap}.
     * Monotone in accuracy (a wider cone only ever misses more), so a plain bisection is exact enough at 40
     * steps - and this runs once per shot, not per tick.
     */
    public static double clampToHitChance(double accuracy, double distance, double targetRadius, double cap) {
        if (cap >= 1.0D || distance <= 0.0D || targetRadius <= 0.0D) {
            return accuracy;
        }
        if (hitChanceFor(accuracy, distance, targetRadius) <= cap) {
            return accuracy;
        }
        double low = 0.0D;
        double high = accuracy;
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) * 0.5D;
            if (hitChanceFor(mid, distance, targetRadius) > cap) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return low;
    }

    /** Called after a shot actually left the barrel (a refused shot must not count as experience). */
    public static void noteShot(Mob mob, long gameTime) {
        CompoundTag data = mob.getPersistentData();
        data.putInt(TAG_SHOTS, data.getInt(TAG_SHOTS) + 1);
        data.putLong(TAG_LAST_SHOT, gameTime);
    }

    /** Called once per tick: after a long silence the mob is "cold" again. */
    public static void tickDecay(Mob mob, long gameTime) {
        CompoundTag data = mob.getPersistentData();
        int reset = Config.ACCURACY_RESET_TICKS.get();
        long last = data.getLong(TAG_LAST_SHOT);
        if (reset > 0 && last != 0L && gameTime - last > reset && data.getInt(TAG_SHOTS) != 0) {
            data.putInt(TAG_SHOTS, 0);
        }
    }

    /** One-line report for {@code /tarkovscav debug}. */
    public static String describe(Mob mob) {
        int shots = shotsFired(mob);
        double tier = mob instanceof GunUser user && user.scavTier() != null
                ? Config.tier(user.scavTier()).accuracy.get() : 0.0D;
        // The reported accuracy uses a 20-block, player-sized target, so the number is comparable between
        // mobs rather than depending on what each one happens to be shooting at.
        double shown = accuracyFor(mob, tier, 20.0D, 0.3D);
        return "shots=" + shots + (warmingUp(mob) ? "/warmup" : "/steady")
                + " acc=" + String.format(java.util.Locale.ROOT, "%.3f", shown)
                + " (tier " + String.format(java.util.Locale.ROOT, "%.2f", tier)
                + ", profile " + profileFor(mob).id() + " cap "
                + String.format(java.util.Locale.ROOT, "%.2f", capFor(mob)) + ")";
    }

    /** The target's effective radius, used by the docs and the gate to turn degrees into a hit chance. */
    public static double hitChanceFor(double accuracy, double distance, double targetRadius) {
        if (distance <= 0.0D) {
            return 1.0D;
        }
        // The aim error is a Gaussian with the same 7-degree span GunBrain uses (see computeAim): the cone
        // half-angle at accuracy a is (1 - a) * 7 degrees, and sigma is half of that.
        double halfAngle = (1.0D - Mth.clamp(accuracy, 0.0D, 1.0D)) * 7.0D;
        double sigma = Math.max(1.0E-6D, halfAngle * 0.5D);
        double angularRadius = Math.toDegrees(Math.atan2(targetRadius, distance));
        return Mth.clamp(erf(angularRadius / (sigma * Math.sqrt(2.0D))), 0.0D, 1.0D);
    }

    /** Abramowitz-Stegun 7.1.26, plenty for a documentation-grade hit chance. */
    private static double erf(double x) {
        double sign = Math.signum(x);
        double z = Math.abs(x);
        double t = 1.0D / (1.0D + 0.3275911D * z);
        double y = 1.0D - (((((1.061405429D * t - 1.453152027D) * t) + 1.421413741D) * t - 0.284496736D)
                * t + 0.254829592D) * t * Math.exp(-z * z);
        return sign * y;
    }
}
