package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.gun.GunUser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * "Throw a grenade at the man behind that wall" (README 5v): the mob half of the grenade batch.
 *
 * <h2>When it throws</h2>
 * <p>The target has to be <b>between {@code minRange} and {@code maxRange}</b> and <b>out of sight</b> - i.e.
 * behind cover - because a grenade is the answer to "I cannot shoot him", not to "I can". On top of that:</p>
 * <ul>
 *   <li>a per-mob cooldown ({@code cooldownTicks});</li>
 *   <li>a finite supply: the mob carries {@code 1..maxPerMob} grenades, rolled once at {@code carryChance};</li>
 *   <li>a <b>solved arc</b>: {@link GrenadeBallistics} sweeps launch pitches and returns the one that lands on
 *       the target without hitting cover. With {@code requireClearArc} (default) a throw that would be eaten
 *       by a wall or by the ground is <b>held back</b> - no grenade spent, no throw cooldown, only a short
 *       {@code retryCooldownTicks} before the mob solves again;</li>
 *   <li>and a <b>safety check</b>: the solved landing point is inspected and the throw is abandoned if more
 *       than {@code allySafetyMax} allies are inside {@code allySafetyRadius} - by default a single ally is
 *       enough to cancel it, so a squad never bombs itself.</li>
 * </ul>
 *
 * <p>The throw itself calls the same {@link GrenadeEntity}/{@link GrenadeBlast} code the player's does, flying
 * exactly the solved direction (inaccuracy 0, or the prediction and the flight would disagree again), and the
 * mob shouts one of the existing {@code grenade_1}/{@code grenade_2} voice lines as it lets go.</p>
 */
public class GrenadeThrowGoal extends Goal {
    private static final String TAG_CARRY = "tarkovscav:grenadeCount";
    private static final String TAG_ROLLED = "tarkovscav:grenadeRolled";

    private final Mob mob;
    private final GunUser user;
    /** How often the throw decision's line-of-sight ray cast is resampled (see canUse). */
    private static final int THROW_SIGHT_CACHE_TICKS = 3;
    private int cooldown;
    /** Ticks to wait before solving another arc after a throw was held back (see canUse). */
    private int retryCooldown;
    /** The arc {@link #canUse()} solved and {@link #start()} consumes; null when nothing was solved. */
    @Nullable
    private GrenadeBallistics.Solution plan;

    public GrenadeThrowGoal(Mob mob, GunUser user) {
        this.mob = mob;
        this.user = user;
    }

    @Override
    public boolean canUse() {
        if (!Config.GRENADES_ENABLED.get() || !Config.MOB_GRENADES_ENABLED.get()) {
            return false;
        }
        // The goal selector polls canUse() every tick while this goal is not running, which is exactly where a
        // cooldown belongs.
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        // Solving an arc means simulating a dozen flights, so a held throw sleeps instead of re-solving every
        // tick (the old ally check did re-run every tick, and filled the log with one line per tick).
        if (this.retryCooldown > 0) {
            this.retryCooldown--;
            return false;
        }
        if (this.mob.level().isClientSide) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // First time this mob is asked, roll what it spawned with (see rollCarry) - before the emptiness check,
        // or a mob that rolled grenades at spawn would never be seen to have any.
        rollCarry();
        if (MobGrenades.total(this.mob) <= 0) {
            return false;
        }
        double distance = this.mob.distanceTo(target);
        if (distance < Config.MOB_GRENADE_MIN_RANGE.get() || distance > Config.MOB_GRENADE_MAX_RANGE.get()) {
            return false;
        }
        // The line-of-sight test is the only ray cast in this decision, and the goal selector asks for it
        // every tick. The held-throw path already tolerates waiting retryCooldownTicks ticks for a better
        // answer, so sampling it every THROW_SIGHT_CACHE_TICKS ticks - staggered by entity id, after both
        // cooldowns have been serviced above - is invisible to the throw and drops two thirds of the casts.
        if ((this.mob.tickCount + this.mob.getId()) % THROW_SIGHT_CACHE_TICKS != 0) {
            return false;
        }
        // Only when it cannot shoot: the target is behind cover.
        if (this.mob.hasLineOfSight(target)) {
            return false;
        }
        GrenadeBallistics.Solution solution = solveArc(target);
        if (Config.MOB_GRENADE_REQUIRE_CLEAR_ARC.get() && !solution.clear()) {
            // Not one launch pitch puts the grenade on the target without eating cover. Hold it: no grenade is
            // spent and the throw cooldown is NOT set, because this is "look again in a moment", not a throw.
            this.retryCooldown = Config.MOB_GRENADE_RETRY_COOLDOWN_TICKS.get();
            logHeld(solution);
            return false;
        }
        if (!safeToThrow(target, solution)) {
            this.retryCooldown = Config.MOB_GRENADE_RETRY_COOLDOWN_TICKS.get();
            return false;
        }
        this.plan = solution;
        return true;
    }

    @Override
    public void start() {
        LivingEntity target = this.mob.getTarget();
        GrenadeBallistics.Solution solution = this.plan;
        this.plan = null;
        if (target == null || solution == null) {
            return;
        }
        // What it throws is what it actually collected (README 5v): the pouch is the source of truth, and the
        // entry is only removed once the grenade is in the air.
        GrenadeKind kind = MobGrenades.next(this.mob);
        if (kind == null) {
            return;
        }
        double distance = this.mob.distanceTo(target);
        double speed = throwSpeed();
        Vec3 from = launchPoint();
        Vec3 direction = solution.direction();
        GrenadeEntity grenade = new GrenadeEntity(this.mob.level(), this.mob, kind, kind.fuseTicks());
        grenade.setPos(from.x, from.y, from.z);
        // Inaccuracy 0 on purpose: the whole point of the solver is that the predicted arc IS the flown arc.
        // The old 3.0 spread made a straight-line throw feel organic; with it back, the solved direction is
        // only an average again and a lob can clip the very wall it was aimed over.
        grenade.shoot(direction.x, direction.y, direction.z, (float) speed, 0.0F);
        this.mob.level().addFreshEntity(grenade);
        MobGrenades.take(this.mob, kind);
        this.cooldown = Config.MOB_GRENADE_COOLDOWN_TICKS.get();
        this.user.asMob().swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        shout();
        TarkovScav.LOGGER.info("[grenade] {} threw a {} at {} ({} blocks, target out of sight, pitch {} deg, {})",
                this.mob.getName().getString(), kind.id(), target.getName().getString(),
                String.format(Locale.ROOT, "%.1f", distance),
                String.format(Locale.ROOT, "%.1f", solution.pitchDegrees()),
                describeArc(solution));
    }

    @Override
    public boolean canContinueToUse() {
        // One throw, then the goal is done; the cooldown keeps it from repeating immediately.
        return false;
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    // ------------------------------------------------------------------ the arc

    /**
     * The arc this mob would throw right now: aim at the (led) target eye and let {@link GrenadeBallistics}
     * pick the launch pitch. Never null - a not-clear result is a solution too, so the caller decides whether
     * {@code requireClearArc} forbids throwing it.
     */
    private GrenadeBallistics.Solution solveArc(LivingEntity target) {
        double distance = this.mob.distanceTo(target);
        double speed = throwSpeed();
        Vec3 from = launchPoint();
        // Lead so a walking target is still hit.
        Vec3 aim = target.getEyePosition().add(target.getDeltaMovement().scale(distance / Math.max(0.2D, speed)));
        return GrenadeBallistics.solve(from, aim, speed,
                Config.MOB_GRENADE_ARC_SAMPLES.get(),
                Config.MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES.get(),
                GrenadeBallistics.levelBlockTest(this.mob.level()));
    }

    /** The mob's throw strength: 85 % of a full player charge, the value the old straight-line throw used. */
    private static double throwSpeed() {
        return Config.GRENADES_MAX_THROW_SPEED.get() * 0.85D;
    }

    /** The muzzle: eye height minus 0.2, exactly where the grenade entity is spawned. */
    private Vec3 launchPoint() {
        return this.mob.getEyePosition().add(0.0D, -0.2D, 0.0D);
    }

    /** "arc clear" / "arc NOT clear, blocked at (x, y, z)" for the throw log. */
    private static String describeArc(GrenadeBallistics.Solution solution) {
        if (solution.clear()) {
            return "arc clear";
        }
        BlockPos cover = solution.blockedAt();
        return cover == null ? "arc NOT clear, no landing"
                : "arc NOT clear, blocked at (" + cover.getX() + ", " + cover.getY() + ", " + cover.getZ() + ")";
    }

    /** The log line a held throw leaves behind - the one that says WHY the grenade stayed in the pouch. */
    private void logHeld(GrenadeBallistics.Solution solution) {
        BlockPos cover = solution.blockedAt();
        if (cover == null) {
            TarkovScav.LOGGER.info("[grenade] {} held the throw: no arc reaches the target",
                    this.mob.getName().getString());
            return;
        }
        TarkovScav.LOGGER.info("[grenade] {} held the throw: arc blocked by cover at ({}, {}, {})",
                this.mob.getName().getString(), cover.getX(), cover.getY(), cover.getZ());
    }

    // ------------------------------------------------------------------ the safety check

    /**
     * True when the solved landing point is clear of allies (and of the thrower).
     *
     * <p>It takes the arc instead of recomputing one: the landing the safety check inspects is exactly the
     * landing the throw will produce, which is the whole reason there is only one solver.</p>
     */
    boolean safeToThrow(LivingEntity target, GrenadeBallistics.Solution solution) {
        if (!(this.mob.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        Vec3 landing = solution.landing();
        if (landing == null) {
            return false;
        }
        double radius = Config.MOB_GRENADE_ALLY_SAFETY_RADIUS.get();
        int allowed = Config.MOB_GRENADE_ALLY_SAFETY_MAX.get();
        List<LivingEntity> nearby = GrenadeEntity.candidates(level, landing, radius);
        int allies = 0;
        for (LivingEntity other : nearby) {
            if (other == this.mob) {
                continue;
            }
            if (Faction.allies(this.mob, other)) {
                allies++;
            }
        }
        if (allies > allowed) {
            TarkovScav.LOGGER.debug("[grenade] {} held the throw: {} ally/allies at the landing point",
                    this.mob.getName().getString(), allies);
            return false;
        }
        return this.mob.distanceTo(target) > 0.0D;
    }

    // ------------------------------------------------------------------ supply

    /**
     * Rolls the starting pouch once per mob (persistent data, so a reload does not re-roll it).
     *
     * <p>Called on spawn - {@link #canUse} calls it, and a mob that has never been asked is the same as a mob
     * that has just spawned - and the RACK path tops it up afterwards. The rolled kind is a frag most of the
     * time, because that is the common grenade.</p>
     */
    int rollCarry() {
        var data = this.mob.getPersistentData();
        if (!data.getBoolean(TAG_ROLLED)) {
            data.putBoolean(TAG_ROLLED, true);
            if (this.mob.getRandom().nextDouble() < Config.MOB_GRENADE_CARRY_CHANCE.get()) {
                MobGrenades.add(this.mob, pickKind());
            }
        }
        return MobGrenades.total(this.mob);
    }

    /** Which grenade a mob starts with. Weighted towards the frag, because that is the common one. */
    static GrenadeKind pickKind() {
        double roll = Math.random();
        if (roll < 0.6D) {
            return GrenadeKind.FRAG;
        }
        if (roll < 0.8D) {
            return GrenadeKind.HE;
        }
        return GrenadeKind.FLASH;
    }

    /** The thrower's shout: the same two voice lines the "a grenade landed near me" reaction uses. */
    private void shout() {
        if (this.mob instanceof com.gfl.tarkovscav.entity.GunnerPillagerEntity pillager) {
            pillager.voice().sayGrenade();
        } else if (this.mob instanceof com.gfl.tarkovscav.entity.GunnerVillagerEntity villager) {
            villager.voice().sayGrenade();
        } else if (this.mob instanceof com.gfl.tarkovscav.entity.ScavEntity scav) {
            scav.voice().sayGrenade();
        }
    }

    /** True when this mob type may carry grenades at all (the goal is added to every gun mob). */
    public static boolean appliesTo(Mob mob) {
        return mob instanceof GunUser && !(mob instanceof com.gfl.tarkovscav.gun.SniperMob);
    }
}
