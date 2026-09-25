package com.gfl.tarkovscav.faction;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Faction intel sharing: "somebody over there saw something" (README 5m).
 *
 * <h2>The one rule that matters: intel never becomes a target</h2>
 * <p>A report is a <b>direction and a distance band</b>, and that is all it can ever be:</p>
 * <ul>
 *   <li>the bearing is quantised to one of eight 45-degree sectors and then fuzzed by up to
 *       {@code alert.bearingNoiseDegrees} (22.5) inside that sector - the receiver genuinely cannot know
 *       where the enemy is, only roughly which way;</li>
 *   <li>the distance is one of three bands, so the "contact point" is the band edge in that direction,
 *       which can be tens of blocks off;</li>
 *   <li>a report is held in {@link SharedContact} - <b>it is never written to {@code Mob#getTarget()}</b>.
 *       Only line of sight can start a fight: {@code GunBrain} still requires
 *       {@code hasLineOfSight(target)} before it aims or fires, so a mob that follows a report around a
 *       wall arrives, looks, and only then shoots. That "no {@code setTarget} in this class or in
 *       {@link Faction}" rule is enforced by {@code tools/selftest_faction.js}, not by this comment.</li>
 * </ul>
 *
 * <p>The three receiver behaviours (converge / hold / ignore) are chosen by distance and by how many
 * allies are already converging, and a single receiver only re-paths every
 * {@code alert.minRepathIntervalTicks} so a contact report cannot turn into a pathfinding storm.</p>
 */
public final class AlertNetwork {
    /** Reports, keyed by the receiving mob, dropped when they expire. */
    private static final Map<Mob, SharedContact> RECEIVED = new WeakHashMap<>();
    /** When each mob last broadcast, and when each mob last re-pathed for a report. */
    private static final Map<Mob, Long> LAST_BROADCAST = new WeakHashMap<>();
    private static final Map<Mob, Long> LAST_REPATH = new WeakHashMap<>();

    /** A report: which way, how far, and when it stops being news. Deliberately has no coordinates. */
    public record SharedContact(Faction faction, int sector, int distanceBand, long expiresAt,
                                int sectorCentre, int noiseDegrees, int sourceEntityId) {
        /** The bearing the receiver believes, in degrees (sector centre + the noise that was rolled). */
        public double bearingDegrees() {
            return this.sectorCentre + this.noiseDegrees;
        }
    }

    private AlertNetwork() {
    }

    /** Distance band index: 0 near, 1 mid, 2 far - the receiver is told the band, never the distance. */
    public static int distanceBand(double distance) {
        if (distance <= Config.ALERT_NEAR_DISTANCE.get()) {
            return 0;
        }
        if (distance <= Config.ALERT_MID_DISTANCE.get()) {
            return 1;
        }
        return 2;
    }

    /** Upper edge of a band, i.e. how far away the receiver should assume the contact is. */
    public static double bandDistance(int band) {
        return switch (band) {
            case 0 -> Config.ALERT_NEAR_DISTANCE.get();
            case 1 -> Config.ALERT_MID_DISTANCE.get();
            default -> Config.ALERT_RADIUS.get();
        };
    }

    /** 8 sectors of 45 degrees, measured clockwise from south, matching Minecraft's yaw convention. */
    public static int sectorOf(double bearingDegrees) {
        double wrapped = Mth.wrapDegrees(bearingDegrees);
        return Mth.floor((wrapped + 180.0D + 22.5D) / 45.0D) & 7;
    }

    /** The centre bearing of a sector, in Minecraft yaw degrees. */
    public static double sectorCentre(int sector) {
        return -180.0D + sector * 45.0D;
    }

    /** The eight compass names, for the log lines. */
    public static String sectorName(int sector) {
        return switch (sector & 7) {
            case 0 -> "S";
            case 1 -> "SW";
            case 2 -> "W";
            case 3 -> "NW";
            case 4 -> "N";
            case 5 -> "NE";
            case 6 -> "E";
            default -> "SE";
        };
    }

    /**
     * Broadcasts a contact to this mob's faction. Called when the mob itself has a live target; the
     * throttle means a mob cannot broadcast more than once per {@code alert.broadcastCooldownTicks}, and
     * only the first {@code alert.maxRecipients} armed allies in range are told (sorted by entity id so
     * the choice is stable rather than random).
     */
    public static void broadcastIfDue(Mob source, LivingEntity target) {
        if (source.isRemoved() || source.isDeadOrDying()) {
            // A dying unit does not report contacts (and must not seed a LAST_BROADCAST entry the
            // network would then keep for a mob that is already gone).
            return;
        }
        if (!(source.level() instanceof ServerLevel level)
                || !Config.FACTION_ENABLED.get() || !Config.ALERT_ENABLED.get()
                || !Faction.isArmedMember(source)) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_BROADCAST.get(source);
        int cooldown = Config.ALERT_BROADCAST_COOLDOWN_TICKS.get();
        if (last != null && now - last < cooldown) {
            return;
        }
        Faction faction = Faction.of(source);
        if (faction == null) {
            return;
        }
        LAST_BROADCAST.put(source, now);

        // bearing from the source to the target, then quantised + fuzzed
        double dx = target.getX() - source.getX();
        double dz = target.getZ() - source.getZ();
        double bearing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));
        int sector = sectorOf(bearing);
        int band = distanceBand(source.distanceTo(target));
        int maxNoise = Config.ALERT_BEARING_NOISE_DEGREES.get();
        int noise = maxNoise <= 0 ? 0 : source.getRandom().nextInt(2 * maxNoise + 1) - maxNoise;
        SharedContact contact = new SharedContact(faction, sector, band,
                now + Config.ALERT_MEMORY_TICKS.get(), (int) Math.round(sectorCentre(sector)), noise,
                source.getId());

        List<Mob> allies = level.getEntitiesOfClass(Mob.class,
                        source.getBoundingBox().inflate(Config.ALERT_RADIUS.get()))
                .stream()
                .filter(candidate -> candidate != source)
                .filter(Faction::isArmedMember)
                .filter(candidate -> Faction.of(candidate) == faction)
                .sorted(Comparator.comparingInt(Mob::getId))
                .limit(Config.ALERT_MAX_RECIPIENTS.get())
                .toList();
        for (Mob ally : allies) {
            RECEIVED.put(ally, contact);
        }
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[alert] {} broadcast contact dir={} dist={} (source={}, told {} ally/ies)",
                    source.getName().getString(), sectorName(sector), bandName(band),
                    source.getId(), allies.size());
        }
    }

    /** The current report for this mob, or null when there is none / it expired. */
    @Nullable
    public static SharedContact current(Mob mob) {
        if (mob.isRemoved() || mob.isDeadOrDying()) {
            // A dead unit is not a receiver any more. Cheap, and it stops a corpse from being counted as
            // a converger by every ally that asks convergerIndex() during its death animation.
            RECEIVED.remove(mob);
            return null;
        }
        if (!(mob.level() instanceof ServerLevel level) || !Config.ALERT_ENABLED.get()
                || !Faction.isArmedMember(mob)) {
            return null;
        }
        SharedContact contact = RECEIVED.get(mob);
        if (contact == null) {
            return null;
        }
        if (level.getGameTime() > contact.expiresAt() || Faction.of(mob) != contact.faction()) {
            RECEIVED.remove(mob);
            return null;
        }
        // A renegade must not benefit from the network even if a report was cached before it turned.
        if (Renegade.is(mob)) {
            RECEIVED.remove(mob);
            return null;
        }
        return contact;
    }

    /**
     * Acts on the current report. <b>This never touches {@code getTarget()}</b>: it either walks the mob
     * towards the reported area (converge, with a spread-out stand-off ring so allies do not pile onto one
     * block) or turns its head that way and holds (the vanilla {@code LookControl} path, which is
     * completely separate from the gun pose / aim tracking - see README 5m).
     */
    public static void act(Mob mob, boolean hasTarget, int indexAmongConvergers) {
        SharedContact contact = current(mob);
        if (contact == null || hasTarget) {
            return;
        }
        double bearing = Math.toRadians(contact.bearingDegrees());
        double distance = bandDistance(contact.distanceBand());
        double x = mob.getX() - Math.sin(bearing) * distance;
        double z = mob.getZ() + Math.cos(bearing) * distance;

        boolean converge = mob.distanceToSqr(x, mob.getY(), z) <= Config.ALERT_CONVERGE_RADIUS.get()
                * Config.ALERT_CONVERGE_RADIUS.get()
                && indexAmongConvergers >= 0
                && indexAmongConvergers < Config.ALERT_CONVERGE_MAX_ALLIES.get();
        if (converge) {
            // Spread the surround: ally i of n takes the ring angle 2*pi*i/n, at a fixed stand-off.
            int convergers = Math.max(1, Config.ALERT_CONVERGE_MAX_ALLIES.get());
            double ring = Math.PI * 2.0D * indexAmongConvergers / convergers;
            double standoff = Config.ALERT_SURROUND_STANDOFF.get();
            double tx = x + Math.cos(ring) * standoff;
            double tz = z + Math.sin(ring) * standoff;
            long now = mob.level().getGameTime();
            Long lastRepath = LAST_REPATH.get(mob);
            if (lastRepath == null || now - lastRepath >= Config.ALERT_MIN_REPATH_INTERVAL_TICKS.get()) {
                LAST_REPATH.put(mob, now);
                mob.getNavigation().moveTo(tx, mob.getY(), tz, 1.15D);
                log(mob, "converge", contact);
            }
            return;
        }
        // Hold: face the reported direction, do not move, and do not touch any gun state.
        mob.getLookControl().setLookAt(x, mob.getEyeY(), z, 20.0F, 20.0F);
        log(mob, "hold", contact);
    }

    /** How many allies are already converging on this mob's report, for the ring index. */
    public static int convergerIndex(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return -1;
        }
        Faction faction = Faction.of(mob);
        if (faction == null) {
            return -1;
        }
        List<Mob> allies = new ArrayList<>(level.getEntitiesOfClass(Mob.class,
                mob.getBoundingBox().inflate(Config.ALERT_RADIUS.get())));
        allies.removeIf(candidate -> candidate == mob || !Faction.isArmedMember(candidate)
                || Faction.of(candidate) != faction || current(candidate) == null);
        allies.sort(Comparator.comparingInt(Mob::getId));
        return allies.indexOf(mob);
    }

    /** Forgets everything about a mob (death, dimension change). */
    public static void forget(Mob mob) {
        RECEIVED.remove(mob);
        LAST_BROADCAST.remove(mob);
        LAST_REPATH.remove(mob);
    }

    /**
     * Drops only the cached report - used when the mob's own eyes beat the rumour (it has a live target),
     * so it stops walking to a report it no longer needs. The throttle history stays, which is what keeps a
     * freshly engaged mob from broadcasting on the same tick it already reported.
     */
    public static void forgetReport(Mob mob) {
        RECEIVED.remove(mob);
    }

    /** One-line report for {@code /tarkovscav debug}. */
    public static String describe(Mob mob) {
        SharedContact contact = current(mob);
        if (contact == null) {
            return "alert=none";
        }
        return "alert=" + sectorName(contact.sector()) + "/" + bandName(contact.distanceBand())
                + "/" + contact.noiseDegrees() + "deg";
    }

    private static String bandName(int band) {
        return switch (band) {
            case 0 -> "near";
            case 1 -> "mid";
            default -> "far";
        };
    }

    private static void log(Mob mob, String behaviour, SharedContact contact) {
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[alert] {} received dir={} -> behaviour={}",
                    mob.getName().getString(), sectorName(contact.sector()), behaviour);
        }
    }
}
