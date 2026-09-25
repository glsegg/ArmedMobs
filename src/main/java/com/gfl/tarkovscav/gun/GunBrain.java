package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavTier;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The shared gun brain: everything a mob does with a TaCZ firearm, plus the Scav tactics around it.
 *
 * <p>It talks to TaCZ exclusively through the public API in {@code com.tacz.guns.api.*}:</p>
 *
 * <pre>
 * IGunOperator op = IGunOperator.fromLivingEntity(mob);   // TaCZ mixes IGunOperator into LivingEntity
 * op.initialData();                                      // cache currentGunItem = () -&gt; mainhand
 * op.draw(() -&gt; mob.getMainHandItem());
 * op.aim(true);
 * ShootResult r = op.shoot(() -&gt; pitch, () -&gt; yaw, System.currentTimeMillis());
 * op.reload(); op.bolt();
 * </pre>
 *
 * <p><b>Damage is not ours.</b> This class never calls {@code hurt}. Every point of damage a scav
 * deals comes out of {@code op.shoot(...)}: TaCZ consumes a round, spawns a real
 * {@code EntityKineticBullet} with the gun's own bullet data (damage, armour penetration, headshot
 * multiplier, distance falloff) and resolves the hit itself. The only things this brain decides are
 * <em>when</em> to pull the trigger and <em>where</em> to point - plus, via the attached
 * {@code IItemHandler}, which ammunition TaCZ has to work with.</p>
 *
 * <p><b>Three non-obvious TaCZ details, all verified against the 1.1.8 bytecode:</b></p>
 * <ol>
 *   <li>The third argument of {@code shoot} is <b>not</b> the wall clock. TaCZ compares
 *       {@code System.currentTimeMillis() - data.baseTimestamp - timestamp} against
 *       {@code [-300, 300 + 2*tickTolerance]} and answers {@code NETWORK_FAIL} otherwise, and
 *       {@code baseTimestamp} is set to the wall clock by {@code ShooterDataHolder}'s constructor.
 *       A player's client is sent that base and echoes back {@code clientNow - base}; a mob must pass
 *       the same quantity, i.e. its uptime in milliseconds. {@link #shootAt} documents this in full -
 *       getting it wrong means every shot fails.</li>
 *   <li>{@code shoot} only spawns a bullet if the shooter's <em>main hand</em> still holds the exact
 *       stack TaCZ cached, so the gun is re-asserted into the main hand every tick.</li>
 *   <li>Reloading reads ammunition through {@code ForgeCapabilities.ITEM_HANDLER} on the shooter, so
 *       {@link MobAmmoInventory} is attached to every gun mob - otherwise a mob could fire the round
 *       in the chamber and then never reload again.</li>
 * </ol>
 */
public final class GunBrain {
    private final Mob mob;
    private final GunUser user;
    private final CombatTactics tactics;
    /** Doors (README 5a): the shared open-then-shut behaviour, one per gun unit. */
    private final DoorBehavior doors;
    /**
     * The squad layer (README 5aa): focus fire, overwatch, flanking and cover claims. One per mob,
     * and completely inert for a tier whose profile does not coordinate (SCAV, SNIPER).
     */
    private final SquadCoordinator squad;

    private GunAiState state = GunAiState.IDLE;
    private int stateTicks;
    private GunLoadout loadout;
    private ItemStack gunStack = ItemStack.EMPTY;
    private ShootResult lastResult;
    /** Back-off so a mob with an empty gun pool does not re-roll (and re-log) every single tick. */
    private int equipRetryTicks;

    private int reactionTicks;
    private int aimTicks;
    private int shotsInBurst;
    private int burstPause;
    /**
     * The entity id the current {@link #reactionTicks} was rolled for (README 5aa). When the target
     * changes, the delay is rolled again - which is the whole "a scav that has just acquired a target
     * does not fire for 0.6-1.2 s" rule, expressed through the existing ALERT state. An impossible id
     * means "nothing rolled yet".
     */
    private int reactionRollTargetId = Integer.MIN_VALUE;
    /**
     * Consecutive ticks spent in AIM waiting for the sniper hold-fire hit-chance threshold, and how
     * many times in a row the mob has given up waiting and moved instead. Bounded by the tier's
     * patienceTicks and by {@link #MAX_PATIENT_ESCAPES}, so the hold-fire rule can never become a new
     * stall (README 5aa): after that many windows the mob takes the shot it has.
     */
    private int patientTicks;
    private int patientEscapes;
    /** How many patience windows a hold-fire tier may spend looking for a better shot before firing. */
    private static final int MAX_PATIENT_ESCAPES = 3;
    /**
     * README 5v: the spread multiplier while panic firing (1.0 at all other times). Set for the single shot the
     * blinded branch takes, then put back, so no other state can inherit it.
     */
    private double panicSpread = 1.0D;
    /** The last position this mob actually saw its target at; what a blinded mob sprays at. */
    @Nullable
    private Vec3 lastKnownTargetPos;
    private int relocateTicks;
    private int unreachableTicks;
    private int watchdogTicks;
    private int suppressTicks;
    private int peekTicks;
    private int burstTarget;
    /** Ticks left of the fallback reload (TaCZ's own reload does not run for mobs). */
    private int manualReloadTicks;
    /** Consecutive ticks TaCZ has claimed a reload is running - bounded by {@code reloadStallTicks}. */
    private int reloadWaitTicks;
    /** Successful shots since the goal started, for {@code /tarkovscav test watch} and the debug line. */
    private int shotsFired;
    /**
     * How many times the anti-stall watchdog has had to override a held-still state. Reset by any
     * successful shot, so it only ever counts <em>consecutive</em> stalls; it is what turns "this mob
     * cannot shoot right now" into "this mob leaves" instead of an infinite stand-off.
     */
    private int stallEscapes;
    /**
     * Test-only fault injection for {@code /tarkovscav test stall}: while positive, {@link #tickFire}
     * pretends TaCZ answered {@code FORGE_EVENT_CANCEL} instead of calling it. Nothing in normal play
     * sets this.
     */
    private int simulateShotFailureTicks;
    /**
     * README 5ab: the target's exposure verdict for the CURRENT tick. Recomputed once per tick, at the
     * top of {@link #tickFire}, from one pair of ray casts
     * ({@code CombatTactics#canSeeEyes}/{@code #canSeeFeet} plus this mob's range) - and then read by
     * everything in the same tick that has to agree about it: the burst length
     * ({@link #burstSize}), the post-burst pause and the warm-up waiver
     * ({@link AccuracyProfile#warmingUp}, which asks for it through
     * {@link GunUser#targetExposedNow}). It is deliberately cleared at the start of every AI tick, so
     * a stale "exposed" can never leak from a burst into a suppression or a blinded spray.
     */
    private boolean exposedNow;
    /**
     * README 5ab: the tick ({@code Mob#tickCount}) until which a retreating mob stays in cover before
     * it may re-engage. Armed by {@link #transition} on every entry into RETREAT and re-armed by
     * {@link #onHurt} while retreating, so incoming fire extends the hold instead of shortening it.
     * 0 means "no hold", which is what a tier with {@code retreatHoldTicks = 0} (SCAV) always gets.
     */
    private int retreatHoldUntil;

    /** The cover spot the mob is currently heading for, for the log lines and the debug command. */
    @Nullable
    private CombatTactics.Spot currentSpot;

    // ------------------------------------------------------------------ per-tick cost control
    //
    // Measured with tools/spike/work/aiperf (a real city street, a crowd of real gunner_villagers
    // fighting tagged dummies, server tick time read from /forge tps): the gun AI is dominated by ray
    // casts - every hasLineOfSight / canSeeEyes / canSeeFeet is a Level#clip through the block grid -
    // and the brain used to ask the exact same "can I see my target?" question two or three times per
    // mob per tick, plus once more per tick for focus fire. The three constants below are the whole
    // throttle; each one is staggered by entity id so a crowd never samples on the same tick.

    /**
     * How many ticks one line-of-sight verdict is reused. Within a tick this is a pure de-duplication
     * (the question and the answer are literally the same); across ticks it delays "I lost sight" by at
     * most one tick, which is what {@code unreachableTicks} and {@code GIVE_UP_TICKS} already measure in
     * hundreds of ticks.
     */
    private static final int SIGHT_CACHE_TICKS = 2;
    /**
     * How often {@code AccuracyProfile#tickDecay} is read. The clock it drives is
     * {@code accuracy.resetTicks} (hundreds of ticks), so a 20-tick cadence cannot be observed.
     */
    private static final int ACCURACY_DECAY_CHECK_TICKS = 20;
    /**
     * How many ticks to wait before re-checking a squad focus target this mob could not see. The check
     * itself is a ray cast, and a mob that cannot see the squad's target will fail it every tick.
     */
    private static final int FOCUS_FIRE_RETRY_TICKS = 5;
    /**
     * How long a path is left alone before the same destination may be re-issued. Vanilla navigation
     * answers {@code isDone()} the moment the path is exhausted, and in dense geometry a cover step the
     * mob cannot reach makes that true on the very next tick - which would re-run the A* search every
     * tick for every mob pressed against a wall.
     */
    private static final int PATH_RETRY_COOLDOWN_TICKS = 10;

    private boolean sightValid;
    private boolean sightValue;
    private int sightTargetId = Integer.MIN_VALUE;
    private int focusFireAdoptedId = Integer.MIN_VALUE;
    private int focusFireRetryTicks;
    private int pathRetryCooldown;

    /**
     * The one line-of-sight answer per mob per {@link #SIGHT_CACHE_TICKS} ticks, staggered by entity id.
     * Every "can I see my target?" in this class goes through here, so the ray cast happens once and
     * every decision in the tick - the tactics memory, the state machine, the aim flag - agrees on it.
     *
     * <p>The sample tick is chosen by {@code (gameTime + entityId) % SIGHT_CACHE_TICKS}, which both
     * spaces one mob's samples out and gives two mobs different phases, so a crowd of fifty does not
     * all cast on the same tick. A change of target always samples immediately, so a mob that has just
     * acquired somebody never fights on a verdict about the previous one.</p>
     */
    private boolean seesTarget(LivingEntity target) {
        long now = this.mob.level().getGameTime();
        boolean mySampleTick = Math.floorMod(now + this.mob.getId(), SIGHT_CACHE_TICKS) == 0;
        if (this.sightValid && this.sightTargetId == target.getId() && !mySampleTick) {
            return this.sightValue;
        }
        this.sightTargetId = target.getId();
        this.sightValue = this.mob.hasLineOfSight(target);
        this.sightValid = true;
        return this.sightValue;
    }
    /** Final pitch/yaw handed to TaCZ on the last shot. */
    private float lastPitch;
    private float lastYaw;
    private double lastDistance = -1.0D;
    /**
     * Horizontal blocks travelled since the current state began, and the position sample it is
     * accumulated from. This is what makes the cover-seek speed measurable: the telemetry line prints
     * both, so the log yields blocks/second <em>for a state</em> exactly, without depending on the
     * log's one-second timestamp resolution or on how often the line happened to be emitted.
     */
    private double stateDistance;
    @Nullable
    private Vec3 previousPos;
    /**
     * Position sampled at the start of the current progress window, how many consecutive windows moved
     * less than {@code tactics.moveProgressMinBlocks}, and how many re-path attempts have been spent.
     * This is the "am I actually getting anywhere?" watchdog - see {@link #checkMovementProgress}.
     */
    @Nullable
    private Vec3 progressAnchor;
    private int noProgressWindows;
    private int noProgressRetries;

    public GunBrain(Mob mob, GunUser user) {
        this.mob = mob;
        this.user = user;
        this.tactics = new CombatTactics(mob);
        this.doors = new DoorBehavior(mob);
        this.squad = new SquadCoordinator(mob);
    }

    // ------------------------------------------------------------------ accessors

    public GunAiState state() {
        return this.state;
    }

    public GunLoadout loadout() {
        return this.loadout;
    }

    public ShootResult lastResult() {
        return this.lastResult;
    }

    public CombatTactics tactics() {
        return this.tactics;
    }

    /**
     * The door behaviour this brain owns (README 5a). Exposed so the every-tick event driver can reach
     * it on a mob whose {@code GunAttackGoal} is not running, and so the watchdog line can report how
     * many doors this unit still has to close.
     */
    public DoorBehavior doors() {
        return this.doors;
    }

    /** Successful TaCZ shots since the goal started - the fight harness asserts on this. */
    public int shotsFired() {
        return this.shotsFired;
    }

    /** How many times the anti-stall watchdog forced a state change; 20 ticks of a running state. */
    public int stallEscapes() {
        return this.stallEscapes;
    }

    /**
     * Test hook: make the next {@code ticks} of {@link #tickFire} behave as if TaCZ refused every shot
     * with {@code FORGE_EVENT_CANCEL} (a real, reachable answer - a cancelled shot event), without
     * calling TaCZ at all. Used by {@code /tarkovscav test stall} to reproduce the "aims and never
     * fires" condition on demand.
     */
    public void simulateShotFailures(int ticks) {
        this.simulateShotFailureTicks = Math.max(0, ticks);
    }

    @Nullable
    public CombatTactics.Spot currentSpot() {
        return this.currentSpot;
    }

    public boolean hasGun() {
        return this.loadout != null && !this.gunStack.isEmpty();
    }

    public ItemStack gunStack() {
        return this.gunStack;
    }

    /** Makes sure this mob has a gun. Called by {@code GunAttackGoal#canUse}. */
    public boolean ensureEquipped() {
        if (!hasGun() && this.equipRetryTicks <= 0) {
            equip(this.mob.getRandom());
        }
        return hasGun();
    }

    /**
     * Called from the mob's hurt hook: a Scav that gets shot starts thinking about cover.
     *
     * <p>README 5ab made two things explicit here. First, the chance is the TIER's
     * ({@link AiProfile#hurtRetreatChance}, which falls back to {@code combat.hurtRetreatChance} for
     * SCAV and SNIPER): the smart tiers ship 0.8/0.85 against the global 0.5, so being hit usually
     * ends the trade instead of being shrugged off.</p>
     *
     * <p>Second, and this is the rule the user asked for: <b>being hit while ALREADY retreating never
     * bounces the mob back into ADVANCE or AIM.</b> That is a structural guarantee, not a dice roll -
     * the RETREAT branch below returns before the random check, so no roll can leave the state; all it
     * does is refresh the under-fire timer and re-arm the retreat hold
     * ({@link AiProfile#retreatHoldTicks}), i.e. a mob that keeps getting shot keeps its head down for
     * longer rather than popping back up. {@code tools/selftest_ai_fire.js} asserts the early return
     * is there.</p>
     */
    public void onHurt() {
        this.tactics.markUnderFire();
        if (this.state == GunAiState.RETREAT) {
            this.retreatHoldUntil = this.mob.tickCount + AiProfile.retreatHoldTicks(this.mob);
            return;
        }
        if (this.mob.getRandom().nextDouble() < AiProfile.hurtRetreatChance(this.mob)) {
            log("{} hit - breaking contact (health {}/{})",
                    name(), fmt(this.mob.getHealth()), fmt(this.mob.getMaxHealth()));
            transition(GunAiState.RETREAT);
        }
    }

    /**
     * README 5ab: the exposure verdict for the current tick - true only while the target is standing
     * in the open (eyes AND feet visible from here) and inside this mob's effective range. Published
     * so that {@link GunUser#targetExposedNow} can hand the same verdict to the accuracy profile.
     */
    public boolean targetExposedNow() {
        return this.exposedNow;
    }

    /** One-line report for the debug command. */
    public String debugSummary() {
        ItemStack held = this.mob.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        int magazine = gun == null ? -1 : gun.getCurrentAmmoCount(held);
        boolean reserve = gun != null && gun.hasInventoryAmmo(this.mob, held, true);
        return "state=" + this.state
                + " tier=" + this.user.scavTier().id()
                + " ai=" + AiProfile.describe(this.mob)
                + " gun=" + (this.loadout == null ? "none" : this.loadout.gunId())
                + " mag=" + magazine
                + " reserve=" + reserve
                + " shots=" + this.shotsFired
                + " stalls=" + this.stallEscapes
                + " memory=" + this.tactics.memoryTicks()
                + " underFire=" + this.tactics.underFireTicks()
                + " patient=" + this.patientTicks
                // README 5ab: the two numbers the exposed-target / hurt-reaction rules turn on, so
                // "why is it not mag-dumping" and "why is it still hiding" are readable in one line.
                + " exposed=" + this.exposedNow
                + " retreatHold=" + Math.max(0, this.retreatHoldUntil - this.mob.tickCount)
                + " " + this.squad.describe()
                + " spot=" + (this.currentSpot == null ? "none" : this.currentSpot.describe())
                + " lastResult=" + this.lastResult;
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private static String fmt(Vec3 vec) {
        return String.format("(%.1f,%.1f,%.1f)", vec.x, vec.y, vec.z);
    }

    // ------------------------------------------------------------------ equipment

    /** Rolls a gun for this mob's tier, equips it and refills its ammunition. */
    public void equip(RandomSource random) {
        if (this.equipRetryTicks > 0) {
            return;
        }
        ScavTier tier = this.user.scavTier();
        GunLoadout rolled = GunPool.rollLoadout(tier, random);
        if (rolled == null) {
            if (this.loadout != null) {
                TarkovScav.LOGGER.warn("{}: no gun available for tier {} - TaCZ index empty or fully filtered",
                        this.mob.getName().getString(), tier.id());
            }
            this.loadout = null;
            this.gunStack = ItemStack.EMPTY;
            this.mob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.equipRetryTicks = 100;
            return;
        }
        applyLoadout(rolled, "equipped");
    }

    /** Arms this mob with a specific loadout - used when a saved mob is read back from disk. */
    public void equipLoadout(GunLoadout loadout) {
        if (loadout == null) {
            return;
        }
        // README 5p: a world saved before the scripted-gun rule existed can carry a Lua gun on every mob. A
        // restored loadout is therefore screened here as well, and a blocked one is replaced by a fresh roll
        // from the (already filtered) pool - which is what "/tarkovscav client reload then walk around" fixes.
        if (ScriptedGuns.isBlocked(loadout.gunId())) {
            TarkovScav.LOGGER.info("[gunsafety] {}: the saved gun {} is kept out of mob hands (script '{}');"
                            + " rolling a replacement from the pool", name(), loadout.gunId(),
                    ScriptedGuns.scriptOf(loadout.gunId()));
            equip(this.mob.getRandom());
            return;
        }
        applyLoadout(loadout, "restored");
    }

    /**
     * Ensures the gun in this mob's hand is not one the scripted-gun rule blocks, and returns true when it
     * had to be replaced.
     *
     * <p>Order matters and is the safety property: the old stack is <b>dropped from the hand and from this
     * brain's fields first</b> ({@code setItemInHand(EMPTY)}), so nothing ticks it any more, and only then is a
     * new gun built by {@code GunPool.buildGun} - i.e. the replacement path never touches the blocked gun's
     * Lua, it just stops referencing it. The old stack becomes ordinary garbage (it is not an entity).</p>
     */
    private boolean sanitizeScriptedGun(ServerLevel level) {
        int period = Config.scriptedGunRescanTicks();
        if (period <= 0) {
            return false;
        }
        // Staggered by entity id, so a group of mobs does not all check on the same tick.
        if ((level.getGameTime() + this.mob.getId()) % period != 0L) {
            return false;
        }
        ItemStack held = this.mob.getMainHandItem();
        IGun igun = IGun.getIGunOrNull(held);
        if (igun == null) {
            return false;
        }
        ResourceLocation gunId = igun.getGunId(held);
        if (!ScriptedGuns.isBlocked(gunId)) {
            return false;
        }
        this.mob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        this.gunStack = ItemStack.EMPTY;
        this.loadout = null;
        TarkovScav.LOGGER.warn("[gunsafety] {} was holding the scripted gun {} (script '{}'); it has been taken"
                        + " away and replaced from the pool (a broken script crashes the server inside TaCZ's"
                        + " tick)", name(), gunId, ScriptedGuns.scriptOf(gunId));
        equip(this.mob.getRandom());
        return true;
    }

    private void applyLoadout(GunLoadout loadout, String verb) {
        this.loadout = loadout;
        this.gunStack = GunPool.buildGun(loadout, this.mob.getRandom(), name());
        // README 5p: with attachments on it, the capacity is whatever the ITEM now says - refillAmmo below
        // reads that rather than the loadout's own magazine size.
        GunAttachments.refillToCapacity(this.gunStack);
        this.mob.setItemInHand(InteractionHand.MAIN_HAND, this.gunStack);
        // TaCZ caches `currentGunItem = () -> shooter.getMainHandItem()` here; without it the very
        // first shoot() answers NOT_DRAW for ever.
        operator().initialData();
        refillAmmo(loadout);
        this.burstTarget = burstSize(false);
        // The client cannot ask the server which clip family applies, and it cannot look the gun's
        // TaCZ type up reliably, so the answer is synced with the rest of the gun pose.
        this.user.setPistolClips(GunClips.FAMILY_PISTOL.equals(GunClips.family(loadout)));
        log("{} {} {} [clip family {}]", name(), verb, loadout, GunClips.family(loadout));
    }

    /** Puts {@code ammoItemStacks} stacks of the right ammunition into the mob's ammo inventory. */
    private void refillAmmo(GunLoadout loadout) {
        MobAmmoInventory inventory = this.user.ammoInventory();
        inventory.clear();
        ItemStack ammo = GunPool.buildAmmo(loadout.ammoId(), 64);
        if (ammo.isEmpty()) {
            TarkovScav.LOGGER.warn("{}: TaCZ has no ammo item for {} (gun {})",
                    name(), loadout.ammoId(), loadout.gunId());
            return;
        }
        int stacks = Math.min(inventory.getContainerSize(), Config.AMMO_ITEM_STACKS.get());
        for (int slot = 0; slot < stacks; slot++) {
            inventory.setItem(slot, ammo.copy());
        }
    }

    /**
     * Tops the mob's ammunition back up, but only when its inventory is genuinely empty.
     *
     * <p>Deliberately <b>not</b> keyed on {@code IGun#hasInventoryAmmo}: that returns false for every
     * magazine-fed gun (TaCZ answers false immediately when {@code useInventoryAmmo} is false), so
     * using it as the trigger would wipe and refill the mob's ammo on every reload and quietly make
     * ammunition infinite.</p>
     */
    private void refillAmmoIfEmpty() {
        if (this.loadout == null) {
            return;
        }
        MobAmmoInventory inventory = this.user.ammoInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                return;
            }
        }
        log("{} has no ammunition items left - topping up from the config", name());
        refillAmmo(this.loadout);
    }

    private IGunOperator operator() {
        return IGunOperator.fromLivingEntity(this.mob);
    }

    private String name() {
        return this.mob.getName().getString();
    }

    private void log(String format, Object... args) {
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[gunai] " + format, args);
        }
    }

    // ------------------------------------------------------------------ goal lifecycle

    public void onGoalStart() {
        // README 5aa: the delay is the TIER's, rolled fresh. A scav gets 12..24 ticks (0.6..1.2 s),
        // an elite 4..8; with [ai] enabled = false this is [combat] reactionTicks exactly.
        resetReaction();
        this.unreachableTicks = 0;
        this.reloadWaitTicks = 0;
        this.shotsFired = 0;
        this.stallEscapes = 0;
        this.patientTicks = 0;
        transition(GunAiState.ALERT);
    }

    /**
     * Rolls a fresh reaction delay for the target this mob is currently on (README 5aa). Called when
     * the goal starts and whenever the target entity changes, which is what makes "just acquired a
     * target" and "has a reaction delay pending" the same statement - the AI state stays ALERT during
     * the delay, so no parallel "surprised" state had to be added.
     */
    private void resetReaction() {
        LivingEntity target = this.mob.getTarget();
        this.reactionRollTargetId = target == null ? Integer.MIN_VALUE : target.getId();
        this.reactionTicks = AiProfile.rollReaction(this.mob.getRandom(), this.mob);
        this.patientEscapes = 0;
    }

    public void onGoalStop() {
        if (this.mob.level() instanceof ServerLevel) {
            operator().aim(false);
        }
        this.shotsInBurst = 0;
        this.burstPause = 0;
        this.currentSpot = null;
        this.patientTicks = 0;
        this.squad.release();
        transition(GunAiState.IDLE);
    }

    // ------------------------------------------------------------------ the state machine

    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        // PERFORMANCE, and the first statement of the AI tick on purpose: a unit that is dead or already
        // removed has nothing left to decide, but every path below would still run for it - the door
        // behaviour, the accuracy decay clock, the squad layer, the state machine - once per mob per
        // tick, for the whole 20-tick death animation. Measured with tools/spike/work/aiperf: killing a
        // crowd of 50 left every one of them costing a full AI tick for a second afterwards.
        if (this.mob.isRemoved() || this.mob.isDeadOrDying()) {
            this.exposedNow = false;
            return;
        }
        // README 5ab: the exposure verdict is per tick. Clearing it here (before every early return)
        // is what stops a stale "exposed" from a finished burst leaking into SUPPRESS, into the blind
        // panic fire, or into the accuracy profile's warm-up rule on a tick with no shot at all.
        this.exposedNow = false;
        // Doors (README 5a). Deliberately the first thing here, before every early return, and
        // deliberately not the only driver: this method is called from GunAttackGoal#tick, and that
        // goal is only active while the mob has a live target and a gun. DoorBehavior's own
        // LivingTickEvent handler covers the rest of the time (an idle villager strolling through a
        // building). Both calls land in the same guarded method, so the mob is ticked once.
        this.doors.tick(level);
        // README 5o: a mob that has not fired for accuracy.resetTicks is "cold" again, so the next
        // engagement starts with the wild warm-up shots. The clock it reads is measured in hundreds of
        // ticks, so it is sampled every ACCURACY_DECAY_CHECK_TICKS, staggered per entity, instead of
        // every tick - one cached config read is not worth three persistent-data lookups per mob per
        // tick for something that only ever changes once a fight.
        if (Config.ACCURACY_ENABLED.get()
                && (level.getGameTime() + this.mob.getId()) % ACCURACY_DECAY_CHECK_TICKS == 0L) {
            AccuracyProfile.tickDecay(this.mob, level.getGameTime());
        }
        if (this.pathRetryCooldown > 0) {
            this.pathRetryCooldown--;
        }
        if (this.loadout == null) {
            equip(this.mob.getRandom());
            if (this.loadout == null) {
                transition(GunAiState.IDLE);
                return;
            }
        }
        // README 5p: the runtime half of the scripted-gun rule. Spawn-time filtering covers guns that came
        // from the pool, but a mob can be holding one from somewhere else - a weapon rack, /give, or a world
        // saved before this rule existed - and a broken Lua script throws inside TaCZ's own tick, which no
        // try/catch of ours can survive. So the held gun is re-checked periodically (staggered per mob, and a
        // cached map lookup, so it is effectively free) and swapped for a pool gun if it is not allowed.
        if (sanitizeScriptedGun(level)) {
            transition(GunAiState.IDLE);
            return;
        }

        LivingEntity target = this.mob.getTarget();
        // Grenades (README 5v): a blinded mob does not stand still - it panic fires. See tickBlind.
        if (this.mob.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
            tickBlind(level);
            return;
        }
        if (target == null || !target.isAlive()) {
            this.tactics.tick(null);
            transition(GunAiState.IDLE);
            return;
        }
        // Remembered only while it can actually see something: that memory is what a blinded mob sprays at.
        this.lastKnownTargetPos = target.position();

        // README 5aa: a fresh target means a fresh reaction delay, and the state machine goes back to
        // ALERT - the existing "I have just seen you" state - so a SCAV cannot fire for 0.6-1.2 s and
        // "surprised" needs no state of its own.
        if (target.getId() != this.reactionRollTargetId) {
            resetReaction();
            this.patientTicks = 0;
            if (this.state != GunAiState.ALERT && this.state != GunAiState.IDLE) {
                transition(GunAiState.ALERT);
            }
        }
        // The squad layer (README 5aa): cached member list + role rotation, then focus fire. Both are
        // no-ops for a tier whose profile does not coordinate (SCAV, SNIPER).
        this.squad.tick(level, target);
        applyFocusFire(level, target);

        this.stateTicks++;
        if (this.watchdogTicks > 0) {
            this.watchdogTicks--;
        }
        if (this.equipRetryTicks > 0) {
            this.equipRetryTicks--;
        }
        if (this.burstPause > 0) {
            this.burstPause--;
        }
        this.tactics.tick(target, seesTarget(target));

        // TaCZ only spawns a bullet when the main hand still holds the cached stack.
        if (this.mob.getMainHandItem() != this.gunStack) {
            this.mob.setItemInHand(InteractionHand.MAIN_HAND, this.gunStack);
        }

        // Distance telemetry: the RCON test reads this to show a mob closing the gap over time. `pos`,
        // `stateTicks` and `stateDist` together make the movement speed measurable - the log yields
        // stateDist / (stateTicks / 20) blocks per second for whatever state the mob is in, which is how
        // the cover-seek multiplier is verified on a head-less server.
        Vec3 now = this.mob.position();
        if (this.previousPos != null) {
            double dx = now.x - this.previousPos.x;
            double dz = now.z - this.previousPos.z;
            this.stateDistance += Math.sqrt(dx * dx + dz * dz);
        }
        this.previousPos = now;

        double distance = this.mob.distanceTo(target);
        if (Config.LOG_GUN_AI.get() && this.stateTicks % 20 == 0) {
            TarkovScav.LOGGER.info("[gunai] {} {} dist={} los={} cover={} health={} pos={} shots={}"
                            + " ticks={} stateDist={}",
                    name(), this.state, fmt(distance), seesTarget(target),
                    this.tactics.isInCoverFrom(level, target.getEyePosition()), fmt(this.mob.getHealth()),
                    fmt(now), this.shotsFired, this.stateTicks, fmt(this.stateDistance));
        }
        this.lastDistance = distance;

        switch (this.state) {
            case IDLE, ALERT -> tickAlert(level, target);
            case ADVANCE -> tickAdvance(level, target);
            case AIM -> tickAim(level, target);
            case FIRE -> tickFire(level, target);
            case SUPPRESS -> tickSuppress(level, target);
            case RELOAD -> tickReload(level, target);
            case BOLT -> tickBolt(level, target);
            case REPOSITION -> tickReposition(level, target);
            case RETREAT -> tickRetreat(level, target);
        }

        // After the state has had its say for this tick: did it actually move the mob, or is it stepping
        // in place? See the method for what happens when it is not.
        checkMovementProgress(level, target);
    }

    /**
     * The no-progress watchdog: are the states that are <em>supposed</em> to move the mob actually moving
     * it?
     *
     * <p>The failure this exists for is "legs stepping, position unchanged" in front of a closed door or
     * a wall: {@code moveTo} succeeded as an API call, the navigation produced no usable path, and
     * nothing anywhere noticed. Every window ({@code tactics.moveProgressSampleTicks}) the position is
     * compared with the previous sample; if the mob covered less than {@code
     * tactics.moveProgressMinBlocks} it gets a first aid attempt (re-issue the current destination - a
     * fresh path can route around what the cached one could not - and hop, because a doorstep is one
     * block) and, after {@code tactics.moveProgressRetries} wasted windows, it <b>logs a WARN and changes
     * state</b> instead of standing there. Nothing about it is silent.</p>
     */
    private void checkMovementProgress(ServerLevel level, LivingEntity target) {
        // README 5ab: RETREAT still counts as a movement state - EXCEPT while the mob is deliberately
        // sitting out its retreat hold IN cover. Standing still behind a wall on purpose is not the
        // "legs stepping, position unchanged" bug this watchdog exists for, and letting it fire would
        // log a false WARN and burn the mob's unreachableTicks (i.e. cut the hold short).
        boolean shouldMove = this.state == GunAiState.ADVANCE
                || this.state == GunAiState.REPOSITION
                || (this.state == GunAiState.RETREAT && !retreatHolding(level, target));
        if (!shouldMove || !Config.SPEC.isLoaded()) {
            this.progressAnchor = null;
            this.noProgressWindows = 0;
            this.noProgressRetries = 0;
            return;
        }
        if (this.stateTicks % Config.MOVE_PROGRESS_SAMPLE_TICKS.get() != 0) {
            return;
        }

        Vec3 now = this.mob.position();
        if (this.progressAnchor == null) {
            this.progressAnchor = now;
            return;
        }
        double dx = now.x - this.progressAnchor.x;
        double dz = now.z - this.progressAnchor.z;
        double moved = Math.sqrt(dx * dx + dz * dz);
        this.progressAnchor = now;
        if (moved >= Config.MOVE_PROGRESS_MIN_BLOCKS.get()) {
            this.noProgressWindows = 0;
            this.noProgressRetries = 0;
            return;
        }

        this.noProgressWindows++;
        if (this.noProgressRetries < Config.MOVE_PROGRESS_RETRIES.get()) {
            this.noProgressRetries++;
            TarkovScav.LOGGER.warn("[gunai] {} {} covered only {} blocks in {} ticks - re-pathing"
                            + " (attempt {}/{}{})",
                    name(), this.state, fmt(moved), Config.MOVE_PROGRESS_SAMPLE_TICKS.get(),
                    this.noProgressRetries, Config.MOVE_PROGRESS_RETRIES.get(),
                    this.mob.horizontalCollision ? ", against a block" : "");
            if (this.currentSpot != null) {
                this.mob.getNavigation().moveTo(this.currentSpot.x(), this.currentSpot.y(),
                        this.currentSpot.z(), coverSeekSpeed());
            } else if (this.state == GunAiState.RETREAT) {
                Vec3 away = this.mob.position().subtract(target.position()).normalize().scale(14.0D);
                this.mob.getNavigation().moveTo(this.mob.getX() + away.x, this.mob.getY(),
                        this.mob.getZ() + away.z, coverSeekSpeed());
            } else {
                this.mob.getNavigation().moveTo(target, 1.15D);
            }
            // The cached cover list may be scoring spots the mob cannot actually reach from here.
            this.tactics.invalidate();
            if (this.mob.onGround() && this.mob.horizontalCollision) {
                this.mob.getJumpControl().jump();
            }
            return;
        }

        TarkovScav.LOGGER.warn("[gunai] {} made no progress for {} windows in {} ({} blocks) - giving up"
                        + " on this position (doors: open={} pass={} closes={} pending={})",
                name(), this.noProgressWindows, this.state, fmt(moved), doorsOpen(), doorsPass(),
                doorsClose(), doorsPending());
        this.noProgressRetries = 0;
        this.noProgressWindows = 0;
        this.progressAnchor = null;
        this.unreachableTicks += Config.MOVE_PROGRESS_SAMPLE_TICKS.get();
        if (this.state == GunAiState.RETREAT || this.unreachableTicks > Config.GIVE_UP_TICKS.get()) {
            transition(GunAiState.RETREAT);
        } else {
            transition(GunAiState.REPOSITION);
        }
    }

    /**
     * True when this mob's navigation is allowed to open closed doors. Logged by the no-progress
     * watchdog, because a mob that cannot open doors is the most common reason it gets stuck in a
     * doorway - and the answer ("doors: open=false") makes that visible in one line.
     */
    private boolean doorsOpen() {
        return this.mob.getNavigation() instanceof GroundPathNavigation ground && ground.canOpenDoors();
    }

    /** True when it may walk through a door that is already open. */
    private boolean doorsPass() {
        return this.mob.getNavigation() instanceof GroundPathNavigation ground && ground.canPassDoors();
    }

    /**
     * True when the close-behind rule is switched on for this mob (README 5a). Logged next to the two
     * navigation flags so a stuck mob's whole door state is readable in the one WARN line.
     */
    private boolean doorsClose() {
        return Config.CLOSE_DOORS_BEHIND.get();
    }

    /** How many doors this mob opened and has not closed again - the second half of the same line. */
    private int doorsPending() {
        return this.doors.pending();
    }

    private void transition(GunAiState next) {
        if (this.state == next) {
            return;
        }
        double distance = this.mob.getTarget() == null ? -1.0D : this.mob.distanceTo(this.mob.getTarget());
        log("{} {} -> {} (dist {}, mag {}, spot {})", name(), this.state, next, fmt(distance),
                magazine(), this.currentSpot == null ? "none" : this.currentSpot.describe());
        this.state = next;
        this.stateTicks = 0;
        // README 5ab: every entry into RETREAT arms the hold clock - from here, so it cannot be
        // forgotten at one of the dozen call sites that break contact. A tier with
        // retreatHoldTicks = 0 (SCAV, or the whole layer switched off) gets 0 = no hold, which is the
        // pre-5ab behaviour. Being hit while already retreating re-arms it in onHurt().
        this.retreatHoldUntil = next == GunAiState.RETREAT
                ? this.mob.tickCount + AiProfile.retreatHoldTicks(this.mob)
                : 0;
        this.shotsInBurst = 0;
        this.watchdogTicks = 0;
        this.suppressTicks = 0;
        this.peekTicks = 0;
        // README 5aa: the hold-fire patience is per position, so any state change starts it over.
        this.patientTicks = 0;
        // Per-state movement telemetry: a new state means a new measurement.
        this.stateDistance = 0.0D;
        this.previousPos = null;
        // Both the pose flags and the state itself are pushed to the client here, from the one place
        // the state machine decides anything - so a renderer that branches on the synced state (the
        // arm pose) or on the flags (the clips, the head tracking) sees the same transition.
        this.user.setGunPose(next != GunAiState.IDLE,
                next == GunAiState.FIRE || next == GunAiState.SUPPRESS,
                next == GunAiState.RELOAD);
        this.user.setGunAiState(next);
    }

    private int magazine() {
        ItemStack held = this.mob.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        return gun == null ? -1 : gun.getCurrentAmmoCount(held);
    }

    private double engageRange() {
        // README 5aa: the ELITE tier closes to about 60 percent of its weapon's range (short rushes),
        // every other tier fights at the tier's own range.
        return this.loadout == null
                ? 20.0D
                : this.loadout.tier().engageRange() * AiProfile.engageRangeScale(this.mob);
    }

    /**
     * Speed modifier for the three "get behind cover" moves: reloading behind cover, repositioning,
     * and the retreat dash. Vanilla {@code PathNavigation#moveTo(..., speedModifier)} multiplies the
     * mob's {@code MOVEMENT_SPEED} attribute, so 1.0 is exactly its normal walking pace and
     * {@code coverSeekSpeedModifier} (default 1.5) makes diving for cover half again as fast.
     *
     * <p>Read from the config on every call: {@code ForgeConfigSpec} values are looked up live, so a
     * change takes effect on the next cover decision without a restart. The other movement sites are
     * deliberately not routed through this - see README 5i for the table.</p>
     */
    private double coverSeekSpeed() {
        return Config.COVER_SEEK_SPEED_MODIFIER.get() * AiProfile.coverSeekSpeedScale(this.mob);
    }

    /**
     * Speed for the retreat fallback where no cover was found and the mob simply runs away - its own key
     * because it is the one movement the user complained about ("escapes way too fast"); default 1.0, i.e.
     * a normal walk, with {@code tactics.retreatSprint} also off by default.
     */
    private double escapeWithoutCoverSpeed() {
        return Config.ESCAPE_WITHOUT_COVER_SPEED_MODIFIER.get();
    }

    /**
     * The number of shots this burst will fire.
     *
     * <p>README 5ab's rule order, and it is deliberate that this is a small readable list rather than
     * a chain of conditions at the call sites:</p>
     * <ol>
     *   <li>while the target is EXPOSED (see {@link #targetExposed}) AND the AI tier's
     *       {@code exposedBurstShots} is not its 0 sentinel, the tier's value wins;</li>
     *   <li>otherwise - the target is in cover, or the tier ships 0 - the gun tier's
     *       {@code [tiers.&lt;gun&gt;] burstShots} wins;</li>
     *   <li>a negative winner means "until the magazine is empty or the target dies", decoded against
     *       the magazine that is actually in the gun right now ({@link #decodeBurst});</li>
     *   <li>during suppression the result is multiplied by
     *       {@code tactics.suppressBurstMultiplier x suppressBurstScale} and clamped to the magazine
     *       ({@link #suppressedBurst}) - unchanged from before 5ab, except that the clamp stops the
     *       multiplier from promising more rounds than exist.</li>
     * </ol>
     */
    private int burstSize(boolean suppressing) {
        return burstSize(this.exposedNow, suppressing);
    }

    private int burstSize(boolean exposed, boolean suppressing) {
        if (this.loadout == null) {
            return 3;
        }
        int magazine = Math.max(1, magazine());
        int gunConfigured = Config.tier(this.loadout.tier()).burstShots.get();
        int rule = burstRuleFor(AiProfile.exposedBurstShots(this.mob), gunConfigured, exposed);
        int shots = decodeBurst(rule, magazine);
        return suppressing ? suppressedBurst(shots, AiProfile.suppressBurstMultiplier(this.mob), magazine) : shots;
    }

    /**
     * The pre-5ab burst rule, kept for everything that is not "keep the trigger down": a negative
     * {@code burstShots} really means "until the magazine is empty or the target dies", which is what
     * the config comment has always promised and what {@code decodeBurst} now does (it used to be
     * silently rewritten to 30 shots, so an extended 60-round magazine stopped after 30 and paused).
     */
    public static int decodeBurst(int configured, int magazine) {
        return configured < 0 ? Math.max(1, magazine) : configured;
    }

    /**
     * Which burst rule applies to this burst: while the target is exposed AND the tier names a length
     * ({@code tierExposedShots != 0}) the AI tier wins, at every other moment the gun tier does. The
     * {@code exposed} half is load-bearing - without it a TROOP would fire the mag dump at a target
     * that is behind a wall, which is the opposite of what the rule is for.
     */
    public static int burstRuleFor(int tierExposedShots, int gunConfigured, boolean exposed) {
        return exposed && tierExposedShots != 0 ? tierExposedShots : gunConfigured;
    }

    /** A suppression burst: the tier-scaled length, never longer than the magazine that is loaded. */
    public static int suppressedBurst(int shots, double scale, int magazine) {
        return Math.min(Math.max(1, magazine), (int) Math.round(shots * scale));
    }

    /**
     * The pause after a burst, in ticks. README 5ab: the tier's {@code exposedBurstCooldownTicks}
     * wins only when it is not the sentinel -1 (-1 = "no override": use the gun tier's
     * {@code burstCooldownTicks}, which is what SCAV and SNIPER ship).
     */
    public static int burstCooldown(int tierExposedCooldown, int gunCooldown) {
        return tierExposedCooldown < 0 ? gunCooldown : tierExposedCooldown;
    }

    /**
     * README 5ab's exact exposure test: eyes AND feet AND in range, combined by
     * {@link AiProfile#isExposed}. Two ray casts
     * ({@code CombatTactics#canSeeEyes}/{@code #canSeeFeet}) plus the same {@link #engageRange} the
     * AIM/FIRE decision uses, once per FIRE tick - the same order of cost as the
     * {@code mob.hasLineOfSight} the burst already does every tick.
     */
    private boolean targetExposed(ServerLevel level, LivingEntity target) {
        boolean eyes = this.tactics.canSeeEyes(level, target);
        boolean feet = this.tactics.canSeeFeet(level, target);
        return AiProfile.isExposed(eyes, feet, this.mob.distanceTo(target) <= engageRange());
    }

    // ------------------------------------------------------------------ states

    /**
     * Focus fire (README 5aa): at the moment this mob acquires a target, adopt the squad's designated
     * one if it can actually see it. Two deliberate limits: it only runs in ALERT, so a mob in the
     * middle of a burst is never yanked onto another target, and the adopted target must pass this
     * mob's own {@code canAttack} + line of sight - squad intel alone never starts a fight (README 5m,
     * the same rule {@code tools/selftest_faction.js} enforces on the alert network).
     */
    private void applyFocusFire(ServerLevel level, LivingEntity target) {
        if (this.state != GunAiState.ALERT) {
            return;
        }
        LivingEntity focus = this.squad.focusTarget(level);
        if (focus == null || focus == target || !focus.isAlive()) {
            this.focusFireAdoptedId = Integer.MIN_VALUE;
            this.focusFireRetryTicks = 0;
            return;
        }
        // Cost control, without changing the rule below by one bit: once this mob has adopted the squad
        // target the check can never fire again for it (focus == target next tick), so the ray cast is
        // only worth paying while the squad target is new or was not visible. A rejection is retried on
        // a short cooldown instead of every single tick - which is what a mob that cannot see the
        // squad's target used to cost, per tick, for as long as that lasted.
        if (focus.getId() == this.focusFireAdoptedId) {
            return;
        }
        if (this.focusFireRetryTicks > 0) {
            this.focusFireRetryTicks--;
            return;
        }
        if (!this.mob.canAttack(focus) || !this.mob.hasLineOfSight(focus)) {
            this.focusFireRetryTicks = FOCUS_FIRE_RETRY_TICKS;
            return;
        }
        log("{} focus fire -> {} (squad target)", name(), focus.getName().getString());
        this.focusFireAdoptedId = focus.getId();
        this.mob.setTarget(focus);
        resetReaction();
    }

    /**
     * Records a chosen cover spot and claims it (README 5aa, cover exclusivity), so a squad mate's
     * next cover search skips that block while this mob is using it. The claim expires by itself.
     */
    private void useSpot(CombatTactics.Spot spot) {
        this.currentSpot = spot;
        this.squad.claim(BlockPos.containing(spot.x(), spot.y(), spot.z()));
    }

    /**
     * The candidate filter for a cover search (README 5aa): never a block another mob has claimed,
     * and - for a flanker - only spots on this mob's own side of the mob-to-target axis. When the
     * coordination layer is off this is the identity filter, i.e. the pre-feature search.
     */
    private Predicate<BlockPos> coverFilter(LivingEntity target, boolean flank) {
        boolean claimed = AiProfile.coordination(this.mob) && Config.AI_COORD_COVER_CLAIMS.get();
        int side = flank ? this.squad.flankSide() : 0;
        return pos -> {
            if (claimed && !this.squad.isFree(pos)) {
                return false;
            }
            return side == 0 || this.squad.onMyFlank(target.getX(), target.getZ(),
                    pos.getX() + 0.5D, pos.getZ() + 0.5D);
        };
    }

    private void tickAlert(ServerLevel level, LivingEntity target) {
        this.mob.getNavigation().stop();
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        this.mob.setSprinting(false);

        if (!this.mob.canAttack(target)) {
            transition(GunAiState.IDLE);
            return;
        }
        if (this.reactionTicks > 0) {
            this.reactionTicks--;
            return;
        }
        decide(level, target);
    }

    /**
     * The transition chooser: what should this mob be doing given range, line of sight and how the
     * fight is going? Every state that is not "in the middle of shooting" ends up here.
     */
    private void decide(ServerLevel level, LivingEntity target) {
        double distance = this.mob.distanceTo(target);
        double range = engageRange();
        boolean sight = seesTarget(target);

        // 1. hurt badly enough / under fire for long enough -> get out
        boolean badlyHurt = this.mob.getHealth()
                < this.mob.getMaxHealth() * AiProfile.retreatHealthFraction(this.mob);
        if ((badlyHurt || this.tactics.isUnderFire() && this.mob.getHealth() < this.mob.getMaxHealth() * 0.6F)
                && this.state != GunAiState.RETREAT) {
            transition(GunAiState.RETREAT);
            return;
        }

        // 2. can shoot from here
        if (sight && distance <= range) {
            transition(GunAiState.AIM);
            return;
        }

        // 3. too far: close the distance using cover - unless this tier HOLDS ITS POST (README 5aa).
        //    A sniper never advances toward the target; it moves to a new firing position instead,
        //    which is the same REPOSITION state the peek-out uses.
        if (distance > range) {
            transition(AiProfile.holdsPost(this.mob) ? GunAiState.REPOSITION : GunAiState.ADVANCE);
            return;
        }

        // 4. close but no line of sight: pin them down, or move to a spot that can see them.
        //    Bounding overwatch (README 5aa) comes first: while this mob is the squad's suppressor it
        //    keeps the target's head down instead of both of them moving at once. shouldSuppress()
        //    expires on its own, so the role can never park a mob.
        if (this.tactics.remembersTarget() && this.squad.shouldSuppress(level.getGameTime())) {
            transition(GunAiState.SUPPRESS);
            return;
        }
        if (this.tactics.remembersTarget()
                && this.mob.getRandom().nextDouble() < AiProfile.suppressChance(this.mob)) {
            transition(GunAiState.SUPPRESS);
            return;
        }
        transition(GunAiState.REPOSITION);
    }

    private void tickAdvance(ServerLevel level, LivingEntity target) {
        double distance = this.mob.distanceTo(target);
        double range = engageRange();

        // arrived (or close enough to start shooting)?
        if (seesTarget(target) && distance <= range) {
            this.mob.getNavigation().stop();
            this.currentSpot = null;
            transition(GunAiState.AIM);
            return;
        }

        // The path is re-planned on entry, and afterwards only when the navigation ran out - plus the
        // PATH_RETRY_COOLDOWN_TICKS guard, because a destination the mob cannot actually reach makes
        // isDone() true on the very next tick and would otherwise re-run the A* search every tick.
        if ((this.stateTicks == 1 || this.mob.getNavigation().isDone()) && this.pathRetryCooldown <= 0) {
            this.pathRetryCooldown = PATH_RETRY_COOLDOWN_TICKS;
            Vec3 threatEye = target.getEyePosition();
            // README 5aa: a scav (advanceCoverScale 0) never looks for cover on the way - it walks
            // straight at the target. A coordinated mover ignores the coverChance roll, because the
            // squad has already decided that it is the one moving while the suppressor fires.
            boolean usesCover = AiProfile.advancesUnderCover(this.mob)
                    && (this.squad.isMover() || AiProfile.rollsCoverUse(this.mob));
            CombatTactics.Spot best = usesCover
                    ? this.tactics.bestCover(level, threatEye, true, AiProfile.advanceCoverStep(this.mob),
                            true, this.coverFilter(target, true))
                    : null;
            if (best == null && usesCover && this.squad.flankSide() != 0) {
                // The flank filter may have excluded every spot; a flanker that cannot find cover on
                // its own side still advances under cover rather than walking at the target in the open.
                best = this.tactics.bestCover(level, threatEye, true, AiProfile.advanceCoverStep(this.mob),
                        true, this.coverFilter(target, false));
            }
            if (best != null) {
                useSpot(best);
                this.mob.getNavigation().moveTo(best.x(), best.y(), best.z(), 1.15D);
                log("{} advancing cover -> {} (target {} away)", name(), best.describe(), fmt(distance));
            } else {
                // no cover on the way: just walk at them, that is what the "give up" timer is for
                this.currentSpot = null;
                this.mob.getNavigation().moveTo(target, 1.15D);
            }
            this.unreachableTicks = 0;
        }

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (this.mob.getNavigation().isDone() && !seesTarget(target)) {
            this.unreachableTicks++;
            if (this.unreachableTicks > Config.GIVE_UP_TICKS.get()) {
                log("{} cannot find a way to the target after {} ticks - disengaging",
                        name(), this.unreachableTicks);
                transition(GunAiState.RETREAT);
            }
        }
    }

    private void tickAim(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(true);
        this.mob.getNavigation().stop();
        this.mob.getLookControl().setLookAt(target, 60.0F, 60.0F);
        this.mob.setSprinting(false);

        if (!seesTarget(target)) {
            this.unreachableTicks++;
            if (this.unreachableTicks > Config.GIVE_UP_TICKS.get() / 2) {
                transition(GunAiState.RETREAT);
            } else {
                decide(level, target);
            }
            return;
        }
        if (this.aimTicks > 0) {
            this.aimTicks--;
            return;
        }
        if (this.burstPause > 0) {
            return;
        }
        // README 5aa, the sniper's hold-fire rule: only start a burst when the ESTIMATED hit chance
        // clears the tier's threshold. The estimate is the steady-state cone (see estimatedHitChance),
        // and the wait is bounded twice over, so "wait for a shot I can make" can never become the new
        // "aims and never fires": after patienceTicks the mob moves to a better position, and after
        // MAX_PATIENT_ESCAPES such windows it takes the shot it has.
        double minimum = AiProfile.minHitChance(this.mob);
        if (minimum > 0.0D && !AiProfile.hitChanceIsEnough(estimatedHitChance(target), minimum)) {
            this.patientTicks++;
            int patience = AiProfile.patienceTicks(this.mob);
            if (patience > 0 && this.patientTicks > patience) {
                this.patientTicks = 0;
                this.patientEscapes++;
                if (this.patientEscapes < MAX_PATIENT_ESCAPES) {
                    log("{} waited {} ticks for a {} hit chance at {} blocks - re-evaluating the position",
                            name(), patience, fmt(minimum), fmt(this.mob.distanceTo(target)));
                    transition(GunAiState.REPOSITION);
                    return;
                }
                log("{} gave up waiting for a {} hit chance after {} window(s) - taking the shot",
                        name(), fmt(minimum), this.patientEscapes);
            } else {
                return;
            }
        }
        this.patientTicks = 0;
        this.patientEscapes = 0;
        // A provisional burst length for the transition; tickFire re-decides it on the first tick of
        // the burst, where the target's exposure for that tick is known (README 5ab).
        this.burstTarget = burstSize(false);
        this.unreachableTicks = 0;
        transition(GunAiState.FIRE);
    }

    /**
     * The accuracy this mob's next shot will be given, before any per-call multiplier: the gun tier's
     * accuracy scaled by the tier profile (README 5aa). The one place the tier value is read, so the
     * aim path and the hold-fire estimate cannot disagree about it.
     */
    private double scaledTierAccuracy() {
        return Config.tier(this.loadout.tier()).accuracy.get() * AiProfile.accuracyScale(this.mob);
    }

    /**
     * The estimated chance that the next shot connects, from the same cone model the aim path uses
     * ({@link AccuracyProfile#hitChanceFor}).
     *
     * <p><b>Deliberately the STEADY-STATE estimate, not the warm-up one.</b> The warm-up penalty
     * (README 5o) is a temporary gun-handling penalty that only clears by firing, so comparing the
     * floor against the warm-up hit chance would deadlock the rule: a mob that never fires never warms
     * up and never clears the floor. The estimate answers "is this a shot worth taking?", and the
     * actual shot still goes through the warm-up cone in {@link #computeAim}.</p>
     */
    private double estimatedHitChance(LivingEntity target) {
        double radius = Math.max(0.1D, target.getBbWidth() * 0.5D);
        double distance = this.mob.getEyePosition().distanceTo(target.getEyePosition());
        double tierAccuracy = this.loadout == null ? 0.0D : scaledTierAccuracy();
        if (!Config.ACCURACY_ENABLED.get()) {
            return AccuracyProfile.hitChanceFor(tierAccuracy, distance, radius);
        }
        double cap = AccuracyProfile.capFor(this.mob);
        double steady = Mth.clamp(tierAccuracy, 0.0D, Math.min(1.0D, cap));
        double accuracy = AccuracyProfile.clampToHitChance(steady, distance, radius, cap);
        return AccuracyProfile.hitChanceFor(accuracy, distance, radius);
    }

    private void tickFire(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(true);
        this.mob.getNavigation().stop();
        this.mob.getLookControl().setLookAt(target, 60.0F, 60.0F);
        this.mob.setSprinting(false);

        if (!seesTarget(target)) {
            // they ducked into cover: keep the burst going at where they were, or move
            decide(level, target);
            return;
        }
        // README 5ab: THE exposure verdict for this tick, taken once and reused by everything that has
        // to agree about it - the burst length, the post-burst pause, the warm-up waiver, and (via
        // GunUser#targetExposedNow) the accuracy of the shot about to leave the barrel.
        this.exposedNow = targetExposed(level, target);

        // FIRE is the one state that holds the mob completely still while it waits for something only
        // TaCZ can produce: an accepted shot. The watchdog is armed exactly once, on the first tick of
        // the burst (stateTicks == 1), and re-armed by every SUCCESS - so it measures "ticks since the
        // last shot actually happened" and never fires on a mob that is merely between rounds. It must
        // NOT be re-armed on a failure: doing that is what made the old counter a no-op, and it also
        // masks the expiry below (the check can only ever see zero if nothing refilled it first).
        if (this.stateTicks == 1) {
            this.watchdogTicks = Config.FIRE_STALL_TICKS.get();
            // README 5ab: the burst length is fixed here, on the first tick, from the exposure measured
            // above - so "empty the magazine against an exposed target" is decided with the same verdict
            // the shots use, rather than with whatever the last tick happened to see.
            this.burstTarget = burstSize(this.exposedNow, false);
        }

        ShootResult result;
        if (this.simulateShotFailureTicks > 0) {
            // Test hook, see #simulateShotFailures. Deliberately does not touch TaCZ.
            this.simulateShotFailureTicks--;
            result = ShootResult.FORGE_EVENT_CANCEL;
            this.lastResult = result;
        } else {
            result = shootAt(target.getEyePosition().subtract(0.0D, target.getBbHeight() * 0.25D, 0.0D),
                    accuracyMultiplier(false), false);
        }
        handleShootResult(result);
        // README 5o: only a shot that actually left the barrel counts as experience. A refused shot
        // (NOT_DRAW, FORGE_EVENT_CANCEL...) must not warm the mob up, or camping behind a wall would.
        if (Config.ACCURACY_ENABLED.get() && result == ShootResult.SUCCESS) {
            AccuracyProfile.noteShot(this.mob, this.mob.level().getGameTime());
        }

        if (this.state != GunAiState.FIRE) {
            return;
        }
        if (this.shotsInBurst >= this.burstTarget) {
            finishBurst();
            return;
        }

        // Nothing has come out of the barrel for a whole window: stop standing in the open aiming.
        // This is the bound that the old code was missing - `watchdogTicks` was being armed here and
        // in handleShootResult but never read, so a burst TaCZ kept refusing simply never ended.
        // Nothing re-arms the counter in this branch: transition() zeroes it and the next burst arms it
        // again at stateTicks == 1, so there is no way for a refill to hide the expiry from this read.
        if (this.watchdogTicks <= 0) {
            this.stallEscapes++;
            TarkovScav.LOGGER.warn(
                    "[gunai] {} FIRE produced no accepted shot for {} ticks (last TaCZ result {}, {} stall(s))"
                            + " - giving up on this position",
                    name(), Config.FIRE_STALL_TICKS.get(), this.lastResult, this.stallEscapes);
            if (this.stallEscapes >= 3 || this.unreachableTicks > Config.GIVE_UP_TICKS.get() / 2) {
                transition(GunAiState.RETREAT);
            } else {
                this.unreachableTicks++;
                transition(GunAiState.REPOSITION);
            }
        }
    }

    /**
     * What happens when a burst has fired its last round (README 5ab).
     *
     * <p>The pre-5ab ending - a {@code burstCooldownTicks} pause plus a REPOSITION - is still what
     * happens in every case the user did not complain about: the target is in cover, the tier has no
     * exposed rule, or the magazine is dry. The one new case is the one that made the TTK long: the
     * target is still EXPOSED, in range, the tier's exposed rule applies
     * ({@link AiProfile#exposedRuleApplies}: an explicit burst length AND an explicit pause) and there
     * are rounds left. Then the mob does not pause and does not reposition - it goes straight back into
     * AIM with the tier's {@code exposedBurstCooldownTicks} (0 for TROOP/ELITE) and fires again, which
     * is the magazine dump.</p>
     *
     * <p>Nothing here is unbounded: an empty magazine goes to RELOAD (the same state
     * {@code ShootResult#NO_AMMO} would have produced), and a burst TaCZ keeps refusing is still cut
     * off by the FIRE watchdog armed in {@link #tickFire}. The cover discipline is not "disabled" - it
     * simply is not consulted while the target is standing in the open, which is exactly what the user
     * asked for; the moment the target breaks line of sight, the early return in {@link #tickFire}
     * hands the decision back to {@link #decide}.</p>
     */
    private void finishBurst() {
        int gunCooldown = Config.tier(this.loadout.tier()).burstCooldownTicks.get();
        boolean keepFiring = this.exposedNow
                && this.magazine() > 0
                && AiProfile.exposedRuleApplies(AiProfile.exposedBurstShots(this.mob),
                        AiProfile.exposedBurstCooldownTicks(this.mob));
        if (keepFiring) {
            this.burstPause = burstCooldown(AiProfile.exposedBurstCooldownTicks(this.mob), gunCooldown);
            log("{} burst of {} done with the target EXPOSED, holding the trigger (pause {}, gun tier {})",
                    name(), this.shotsInBurst, this.burstPause, gunCooldown);
            transition(GunAiState.AIM);
            return;
        }
        if (this.magazine() <= 0) {
            log("{} burst of {} emptied the magazine", name(), this.shotsInBurst);
            transition(GunAiState.RELOAD);
            return;
        }
        this.burstPause = gunCooldown;
        log("{} burst of {} done, pausing {} ticks", name(), this.shotsInBurst, this.burstPause);
        transition(GunAiState.REPOSITION);
    }

    /**
     * Blind fire at the last known position. Lower accuracy, longer bursts - the point is to keep the
     * target's head down, not to hit it.
     */
    private void tickSuppress(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(true);
        this.mob.getNavigation().stop();

        if (seesTarget(target)) {
            transition(GunAiState.AIM);
            return;
        }
        if (!this.tactics.remembersTarget()) {
            log("{} lost the target for good after suppressing", name());
            decide(level, target);
            return;
        }
        if (this.suppressTicks == 0) {
            this.suppressTicks = AiProfile.suppressTicks(this.mob);
            this.burstTarget = burstSize(true);
            this.mob.getLookControl().setLookAt(
                    this.tactics.lastKnownTarget().x, this.tactics.lastKnownTarget().y, this.tactics.lastKnownTarget().z,
                    60.0F, 60.0F);
            log("{} suppressing {}", name(), fmt(this.tactics.lastKnownTarget()));
        }
        if (this.suppressTicks-- <= 0) {
            decide(level, target);
            return;
        }

        ShootResult result = shootAt(this.tactics.lastKnownTarget(), accuracyMultiplier(true), true);
        handleShootResult(result);
        if (this.shotsInBurst >= this.burstTarget) {
            log("{} suppression burst of {} done", name(), this.shotsInBurst);
            decide(level, target);
        }
    }

    /**
     * Reloading is a movement problem first: get behind cover, then let TaCZ do the reload. The mob
     * only leaves cover once TaCZ reports the reload is finished.
     *
     * <p><b>The retry loop is not decoration.</b> TaCZ's {@code LivingEntityReload#reload} silently
     * returns - no state change, no log, no exception - in five cases: the gun uses inventory ammo,
     * a reload is already running, {@code getShootCoolDown() != 0}, {@code getDrawCoolDown() != 0},
     * or the weapon is bolting. A reload requested on the same tick as the {@code NO_AMMO} result is
     * therefore dropped, because the shot that emptied the magazine is still cooling down. The mob
     * has to wait for a quiet moment and ask again, which is what this loop does.</p>
     */
    private void tickReload(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(false);

        if (this.stateTicks == 1) {
            Vec3 threatEye = target.getEyePosition();
            CombatTactics.Spot cover = this.tactics.bestCover(level, threatEye, false, 0.0D, true,
                    this.coverFilter(target, false));
            if (cover != null && !this.tactics.isInCoverFrom(level, threatEye)) {
                useSpot(cover);
                this.mob.getNavigation().moveTo(cover.x(), cover.y(), cover.z(), coverSeekSpeed());
                log("{} reloading behind cover -> {}", name(), cover.describe());
            } else {
                log("{} reloading in place (already in cover: {})", name(),
                        this.tactics.isInCoverFrom(level, threatEye));
            }
        }

        ReloadState reload = op.getSynReloadState();
        boolean reloading = reload != null && reload.getStateType() != null && reload.getStateType().isReloading();
        if (reloading) {
            // TaCZ ticks the reload for every LivingEntity, so this just waits it out - but only for
            // as long as a reload could plausibly take. This check has to sit on *this* side of the
            // early return: the `stateTicks > 200` check further down is only reached once TaCZ has
            // stopped reporting a reload, so it cannot bound a reload state that never clears. Before
            // this bound existed, `watchdogTicks = 200` was written here and never read anywhere, and
            // the mob stood behind cover aiming at nothing for ever.
            if (++this.reloadWaitTicks > Config.RELOAD_STALL_TICKS.get()) {
                this.stallEscapes++;
                this.reloadWaitTicks = 0;
                TarkovScav.LOGGER.warn(
                        "[gunai] {} TaCZ has reported a running reload for {} ticks without finishing"
                                + " (mag {}, reserve {}) - breaking contact",
                        name(), Config.RELOAD_STALL_TICKS.get(), magazine(), reserveAmmo());
                transition(GunAiState.RETREAT);
                return;
            }
            return;
        }
        this.reloadWaitTicks = 0;

        int magazine = magazine();
        if (magazine > 0) {
            // Loaded (TaCZ finished, or the magazine was never actually empty).
            transition(GunAiState.AIM);
            this.aimTicks = Config.tier(this.loadout.tier()).aimTicks.get() / 2;
            return;
        }

        // Our own reload, already under way.
        if (this.manualReloadTicks > 0) {
            if (--this.manualReloadTicks == 0) {
                finishManualReload();
                transition(GunAiState.AIM);
            }
            return;
        }

        // Ask TaCZ to reload, but only in a moment it will accept.
        boolean quiet = op.getSynShootCoolDown() <= 0 && !op.getSynIsBolting();
        if (quiet && this.stateTicks % 10 == 1) {
            refillAmmoIfEmpty();
            op.reload();
            log("{} requested a reload from TaCZ (mag {}, reserve {})", name(), magazine, reserveAmmo());
        }

        // TaCZ's reload does not run for mobs (see finishManualReload), so after a grace period the
        // magazine is topped up here instead of leaving the mob dry for ever.
        if (Config.MANUAL_RELOAD_FALLBACK.get() && this.stateTicks == 30) {
            beginManualReload();
            return;
        }

        if (this.stateTicks > 200) {
            log("{} still cannot reload after {} ticks - breaking contact", name(), this.stateTicks);
            transition(GunAiState.RETREAT);
        }
    }

    /**
     * Starts our own reload: TaCZ's {@code reload()} does not work for a mob, and this is the
     * documented fallback.
     *
     * <p>Everything about it still goes through TaCZ's public API, so the ammunition is real:</p>
     * <ol>
     *   <li>{@code AbstractGunItem#findAndExtractInventoryAmmo(IItemHandler, ItemStack, int)} pulls
     *       the matching rounds out of the mob's item handler - the same call TaCZ uses for a
     *       player's inventory;</li>
     *   <li>{@code IGun#setCurrentAmmoCount} / {@code setBulletInBarrel} put them in the magazine.</li>
     * </ol>
     * <p>Only the timing and the pose are ours. If the mob has no matching ammunition left, nothing is
     * extracted, the magazine stays empty and the state machine breaks contact - which is the intended
     * "out of ammo" behaviour rather than an infinite magazine.</p>
     */
    private void beginManualReload() {
        this.manualReloadTicks = Config.MANUAL_RELOAD_TICKS.get();
        log("{} TaCZ did not start a reload; reloading from its ammo items ({} ticks)",
                name(), this.manualReloadTicks);
    }

    /** Consumes real ammunition items and fills the magazine. */
    private void finishManualReload() {
        ItemStack held = this.mob.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        if (gun == null) {
            return;
        }
        // README 5p: the capacity is read from the ITEM, so an extended magazine is reloaded to its own
        // (larger) capacity instead of being capped at the loadout's unmodded magazine size.
        int capacity = capacityOf(held);
        int needed = Math.max(0, capacity - gun.getCurrentAmmoCount(held));
        int taken = 0;

        if (held.getItem() instanceof AbstractGunItem gunItem && needed > 0) {
            taken = gunItem.findAndExtractInventoryAmmo(
                    new InvWrapper(this.user.ammoInventory()), held, needed);
        }
        if (taken > 0) {
            gun.setCurrentAmmoCount(held, gun.getCurrentAmmoCount(held) + taken);
            gun.setBulletInBarrel(held, true);
        }
        log("{} reloaded from its ammo items: took {} round(s), magazine {}/{}, reserve items left {}",
                name(), taken, gun.getCurrentAmmoCount(held), capacity,
                countAmmoItems());
    }

    /** The gun's real capacity (attachments included), falling back to the loadout when TaCZ cannot say. */
    private int capacityOf(ItemStack held) {
        int fromItem = GunAttachments.capacityOf(held);
        return fromItem > 0 ? fromItem : this.loadout.magazineSize();
    }

    /** How many ammo items the mob still carries, for the log lines and the out-of-ammo decision. */
    private int countAmmoItems() {
        MobAmmoInventory inventory = this.user.ammoInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    private void tickBolt(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        this.mob.getNavigation().stop();
        this.mob.getLookControl().setLookAt(target, 40.0F, 40.0F);
        if (op.getSynIsBolting() && this.stateTicks < 100) {
            return;
        }
        transition(GunAiState.AIM);
        this.aimTicks = 2;
    }

    /**
     * Move somewhere else - a different firing position, so the target cannot simply keep aiming at
     * the spot the mob was last seen in.
     */
    private void tickReposition(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(seesTarget(target));
        Vec3 threatEye = target.getEyePosition();

        if (this.stateTicks == 1) {
            this.relocateTicks = Math.max(AiProfile.repositionTicks(this.mob),
                    Config.tier(this.loadout.tier()).burstCooldownTicks.get());
            // If we are standing in the open, cover first; if we are already hidden, move laterally
            // until we can see the target again (the peek-out). README 5aa: a tier that rarely uses
            // cover (a scav) skips the cover-seeking half of this and just takes a new firing position.
            boolean inCover = this.tactics.isInCoverFrom(level, threatEye);
            Predicate<BlockPos> allowed = this.coverFilter(target, false);
            boolean seeksCover = inCover || AiProfile.rollsCoverUse(this.mob);
            CombatTactics.Spot spot = inCover
                    ? this.tactics.peekSpot(level, threatEye, 4.0D, allowed)
                    : seeksCover ? this.tactics.bestCover(level, threatEye, true, -2.0D, true, allowed) : null;
            if (spot == null) {
                spot = this.tactics.peekSpot(level, threatEye, 6.0D, allowed);
            }
            if (spot != null) {
                useSpot(spot);
                this.mob.getNavigation().moveTo(spot.x(), spot.y(), spot.z(), coverSeekSpeed());
                log("{} repositioning -> {} (in cover: {})", name(), spot.describe(), inCover);
                this.peekTicks = this.relocateTicks;
            } else {
                this.currentSpot = null;
                this.relocateTicks = Math.min(this.relocateTicks, 10);
                log("{} found nowhere better to stand, holding position", name());
            }
        }

        if (this.stateTicks >= this.relocateTicks) {
            this.unreachableTicks = seesTarget(target) ? 0 : this.unreachableTicks + 1;
            if (this.unreachableTicks > Config.GIVE_UP_TICKS.get() / 2) {
                transition(GunAiState.RETREAT);
            } else {
                transition(GunAiState.AIM);
                this.aimTicks = Config.tier(this.loadout.tier()).aimTicks.get() / 3;
            }
        }
    }

    /**
     * True while this mob is deliberately sitting out the retreat hold behind cover (README 5ab):
     * it has broken contact, it is IN cover from the threat, and the hold clock
     * ({@link #retreatHoldUntil}) has not run out. Read by {@link #tickRetreat} (which refuses to
     * re-engage during it) and by {@link #checkMovementProgress} (which stands its watchdog down, so a
     * deliberate hold is never reported as a stall).
     */
    private boolean retreatHolding(ServerLevel level, LivingEntity target) {
        return this.state == GunAiState.RETREAT
                && this.mob.tickCount < this.retreatHoldUntil
                && this.tactics.isInCoverFrom(level, target.getEyePosition());
    }

    /**
     * Break contact: run to cover that is <em>further</em> from the threat, then - once nothing has
     * hit us for a while - decide whether to come back and fight or keep going.
     *
     * <p>README 5ab changed the "come back" half for the tiers that ship a {@code retreatHoldTicks}
     * (SNIPER 60, TROOP 80, ELITE 60): they may not re-engage before the hold clock has run out, they
     * may not re-engage while they are still being hit ({@code contact} below), and when they do
     * re-engage they go through REPOSITION - the move that uses {@code CombatTactics#peekSpot} to step
     * out from behind the cover they are actually behind and then to AIM - instead of the old
     * {@code decide()}, which could pick ADVANCE and walk straight back into the open. A tier with the
     * hold at 0 (SCAV, or the whole layer off) executes the old code path untouched.</p>
     */
    private void tickRetreat(ServerLevel level, LivingEntity target) {
        IGunOperator op = operator();
        op.aim(false);
        // Sprinting used to be unconditional here and was the hidden multiplier behind "they run away
        // absurdly fast": it adds its own speed on top of the navigation modifier. Default is now OFF, so
        // "running away = normal walking speed" holds on the actual movement, not just in the config.
        this.mob.setSprinting(Config.RETREAT_SPRINT.get());
        Vec3 threatEye = target.getEyePosition();

        if (this.stateTicks == 1) {
            CombatTactics.Spot cover = this.tactics.bestCover(level, threatEye, false, 4.0D, true,
                    this.coverFilter(target, false));
            if (cover != null) {
                useSpot(cover);
                this.mob.getNavigation().moveTo(cover.x(), cover.y(), cover.z(), coverSeekSpeed());
                log("{} RETREAT to cover {}", name(), cover.describe());
            } else {
                Vec3 away = this.mob.position().subtract(target.position()).normalize().scale(14.0D);
                this.currentSpot = null;
                this.mob.getNavigation().moveTo(this.mob.getX() + away.x, this.mob.getY(),
                        this.mob.getZ() + away.z, escapeWithoutCoverSpeed());
                log("{} RETREAT (no cover found, running) ", name());
            }
        }

        // README 5ab: the hold. While it lasts the mob stays where it is; nothing below can pull it
        // back out (no decide(), no peek), so incoming fire cannot bounce it into ADVANCE/AIM.
        if (retreatHolding(level, target)) {
            return;
        }

        // Out of contact for a while? Then decide again - a Scav that has recovered comes back.
        boolean contact = this.tactics.isUnderFire();
        boolean safe = this.tactics.isInCoverFrom(level, threatEye) && !contact;
        if (this.stateTicks > 40 && safe && this.mob.tickCount >= this.retreatHoldUntil) {
            int hold = AiProfile.retreatHoldTicks(this.mob);
            this.unreachableTicks = 0;
            if (hold > 0) {
                log("{} held cover for {} ticks (health {}), peeking back out", name(), this.stateTicks,
                        fmt(this.mob.getHealth()));
                transition(GunAiState.REPOSITION);
            } else {
                log("{} broke contact at {} (health {}), re-evaluating", name(),
                        fmt(this.mob.position()), fmt(this.mob.getHealth()));
                decide(level, target);
            }
            return;
        }
        if (this.stateTicks >= Config.GIVE_UP_TICKS.get()) {
            log("{} gave up the fight entirely", name());
            this.mob.setTarget(null);
            this.currentSpot = null;
            transition(GunAiState.IDLE);
        }
    }

    // ------------------------------------------------------------------ shooting

    /**
     * Blinded by a flashbang (README 5v): <b>panic fire</b>, not a pause.
     *
     * <p>The rules, and why each one is here:</p>
     * <ul>
     *   <li>no target is required - a flashed mob sprays at the last position it actually saw, or in a random
     *       direction if it never saw one, which is what makes a flashbang dangerous to whoever is in front of
     *       it (its own squad included: the bullets are real);</li>
     *   <li>the cone is multiplied by {@code flash.panicSpreadMultiplier} and the burst is faster, so it is
     *       suppressing fire rather than marksmanship;</li>
     *   <li>the state is SUPPRESS, the existing "blind fire to keep heads down" state, so the animation and the
     *       HUD need no new state and no client change;</li>
     *   <li>the target is <b>not</b> used for aiming while blind - the aim point is the remembered position, so
     *       a flashed mob cannot "see through" the flash by tracking a live target;</li>
     *   <li>the effect ending simply stops entering this method, and the normal target selection takes over
     *       again - which is the re-acquire the request asks for.</li>
     * </ul>
     */
    private void tickBlind(ServerLevel level) {
        this.tactics.tick(null);
        if (!Config.GRENADE_FLASH_PANIC_FIRE.get()) {
            // The old (quiet) behaviour, kept one config key away: drop the target and hold fire.
            this.panicSpread = 1.0D;
            this.mob.setTarget(null);
            transition(GunAiState.IDLE);
            return;
        }
        transition(GunAiState.SUPPRESS);
        if (this.burstPause > 0) {
            this.burstPause--;
            return;
        }
        if (this.mob.tickCount % Math.max(1, Config.GRENADE_FLASH_PANIC_BURST_TICKS.get()) != 0) {
            return;
        }
        Vec3 aim = panicAimPoint();
        this.panicSpread = Config.GRENADE_FLASH_PANIC_SPREAD_MULTIPLIER.get();
        try {
            shootAt(aim, 1.0D, true);
        } finally {
            this.panicSpread = 1.0D;
        }
        this.burstPause = Math.max(1, Config.GRENADE_FLASH_PANIC_BURST_TICKS.get() / 2);
    }

    /** Where a blinded mob shoots: the last place it saw somebody, else a random direction in front of it. */
    private Vec3 panicAimPoint() {
        if (this.lastKnownTargetPos != null) {
            return this.lastKnownTargetPos;
        }
        double angle = Math.toRadians(this.mob.getYRot() + this.mob.getRandom().nextDouble() * 120.0D - 60.0D);
        double reach = 12.0D;
        return this.mob.getEyePosition().add(-Math.sin(angle) * reach,
                (this.mob.getRandom().nextDouble() - 0.5D) * 6.0D, Math.cos(angle) * reach);
    }

    private ShootResult shootAt(Vec3 aimPoint, double accuracyMultiplier, boolean suppressing) {
        IGunOperator op = operator();
        float[] aim = computeAim(aimPoint, op.getSynAimingProgress(), accuracyMultiplier);
        this.lastPitch = aim[0];
        this.lastYaw = aim[1];

        // ------------------------------------------------------------------------------------
        // The timestamp argument, and why it is "uptime" and not the wall clock.
        //
        // TaCZ's anti-cheat sanity check inside LivingEntityShoot#shoot is
        //     delta = System.currentTimeMillis() - data.baseTimestamp - timestamp;
        //     reject with NETWORK_FAIL unless delta is within [-300, 300 + 2*tickTolerance]
        // and `ShooterDataHolder`'s CONSTRUCTOR - not initialData() - sets
        //     data.baseTimestamp = System.currentTimeMillis()
        // at the moment the entity's TaCZ data holder is created.
        //
        // A player's client is sent that base over the wire and echoes back `clientNow - base`, so
        // delta stays near zero. A mob has no client, so the caller has to supply the same quantity:
        // the milliseconds elapsed since the data holder was built. Passing the wall clock instead
        // (the obvious reading of the API) makes delta hugely negative, and every single shot comes
        // back NETWORK_FAIL - which is exactly what happened before this line was written.
        //
        // This also keeps the cool-down arithmetic right: TaCZ stores the value it is given in
        // `data.shootTimestamp` and later computes `interval - (nextTimestamp - shootTimestamp)`, so
        // any monotonic millisecond clock works as long as it is used consistently. Uptime is one.
        // ------------------------------------------------------------------------------------
        long timestamp = System.currentTimeMillis() - op.getDataHolder().baseTimestamp;
        ShootResult result = op.shoot(() -> this.lastPitch, () -> this.lastYaw, timestamp);
        this.lastResult = result;
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[gunai] {} shoot -> {} (pitch {}/yaw {}, t {}, mag {}, reserve {}, {})",
                    name(), result, String.format("%.1f", this.lastPitch), String.format("%.1f", this.lastYaw),
                    timestamp, magazine(), reserveAmmo(), suppressing ? "SUPPRESSING" : "aimed");
        }
        return result;
    }

    private boolean reserveAmmo() {
        ItemStack held = this.mob.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        return gun != null && gun.hasInventoryAmmo(this.mob, held, true);
    }

    private void handleShootResult(ShootResult result) {
        switch (result) {
            case SUCCESS -> {
                this.shotsInBurst++;
                this.shotsFired++;
                // Real progress: the mob is shooting, so it is not stalled.
                this.watchdogTicks = Config.FIRE_STALL_TICKS.get();
                this.stallEscapes = 0;
            }
            case COOL_DOWN -> {
                // TaCZ enforces the gun's real rate of fire; doing nothing is the correct response.
                // The watchdog armed in tickFire() is what keeps a cool-down that never comes down
                // from parking the mob here for ever.
            }
            case NO_AMMO -> transition(GunAiState.RELOAD);
            case NOT_DRAW, IS_DRAWING -> {
                operator().draw(this.mob::getMainHandItem);
                if (this.state != GunAiState.AIM) {
                    transition(GunAiState.AIM);
                }
            }
            case NEED_BOLT, IS_BOLTING -> {
                transition(GunAiState.BOLT);
                operator().bolt();
            }
            case IS_RELOADING -> transition(GunAiState.RELOAD);
            case OVERHEATED -> transition(GunAiState.REPOSITION);
            case IS_SPRINTING -> this.mob.setSprinting(false);
            case NOT_GUN, ID_NOT_EXIST -> {
                log("{} lost its gun ({}), re-rolling gear", name(), result);
                this.loadout = null;
                equip(this.mob.getRandom());
                transition(GunAiState.AIM);
            }
            default -> {
                // UNKNOWN_FAIL / NETWORK_FAIL / FORGE_EVENT_CANCEL: no shot happened. Nothing to do
                // here on purpose - the watchdog armed at the start of the burst is already counting
                // down, and tickFire() reads it: if TaCZ keeps answering this, the mob stops holding
                // and moves instead of aiming at the target for ever. Re-arming the counter here (as
                // the old code did) is what made the stall unbounded.
            }
        }
    }

    // ------------------------------------------------------------------ aiming maths

    /**
     * Direction to fire in: the straight line to the aim point, plus an error cone that grows with
     * distance and with how badly trained the tier is, and shrinks as TaCZ's aiming progress comes up
     * (so the first shot of a burst is the wild one, exactly like a player).
     */
    private float[] computeAim(Vec3 aimPoint, float aimingProgress, double accuracyMultiplier) {
        Vec3 eye = this.mob.getEyePosition();
        double dx = aimPoint.x - eye.x;
        double dy = aimPoint.y - eye.y;
        double dz = aimPoint.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horizontal) * (180.0D / Math.PI)));

        double tierAccuracy = scaledTierAccuracy();
        // README 5o: the warm-up penalty and the 75 % ceiling live in one place (AccuracyProfile), so every
        // tier - including any future one - goes through them. The ceiling is applied to the HIT CHANCE, not
        // just to the number: a 0.25 error cone still covers a player at knife range, so a plain clamp would
        // not bind where it matters. The target's own half-width is what the cone is compared against.
        LivingEntity aimTarget = this.mob.getTarget();
        double targetRadius = aimTarget == null ? 0.3D : Math.max(0.1D, aimTarget.getBbWidth() * 0.5D);
        double accuracy = Config.ACCURACY_ENABLED.get()
                ? AccuracyProfile.accuracyFor(this.mob, tierAccuracy, eye.distanceTo(aimPoint), targetRadius)
                : tierAccuracy;
        double skillError = 1.0D - Mth.clamp(accuracy * accuracyMultiplier, 0.0D, 1.0D);
        double distanceFactor = Mth.clamp(eye.distanceTo(aimPoint) / Math.max(4.0D, engageRange()), 0.15D, 1.5D);
        boolean moving = this.mob.getDeltaMovement().horizontalDistanceSqr() > 0.01D;
        double settle = 1.0D + (1.0D - Mth.clamp(aimingProgress, 0.0F, 1.0F));

        // 7 degrees of cone at 0 accuracy, 0 degrees at 1.0 accuracy.
        double spread = skillError * 7.0D * distanceFactor * settle * (moving ? 1.75D : 1.0D)
                // README 5v: panic fire (flashbang) widens the cone by this factor. 1.0 in every other state.
                * this.panicSpread;
        RandomSource random = this.mob.getRandom();
        yaw += (float) (random.nextGaussian() * spread * 0.5D);
        pitch += (float) (random.nextGaussian() * spread * 0.4D);
        return new float[]{pitch, yaw};
    }

    /** Accuracy multiplied by the distance band, then by the suppression penalty. */
    private double accuracyMultiplier(boolean suppressing) {
        double band = switch (distanceBand()) {
            case 0 -> Config.DISTANCE_ACCURACY_NEAR.get();
            case 1 -> Config.DISTANCE_ACCURACY_MID.get();
            default -> Config.DISTANCE_ACCURACY_FAR.get();
        };
        return suppressing ? band * AiProfile.suppressAccuracyMultiplier(this.mob) : band;
    }

    /** 0 = close, 1 = mid, 2 = far. The bands are fractions of the tier's engagement range. */
    private int distanceBand() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return 1;
        }
        double fraction = this.mob.distanceTo(target) / Math.max(1.0D, engageRange());
        if (fraction <= 0.35D) {
            return 0;
        }
        return fraction <= 0.7D ? 1 : 2;
    }

    /** Last measured distance to the target, for the debug command and the RCON test. */
    public double lastDistance() {
        return this.lastDistance;
    }

    /** The block the mob is standing on, for the log lines. */
    public BlockPos blockPos() {
        return this.mob.blockPosition();
    }
}
