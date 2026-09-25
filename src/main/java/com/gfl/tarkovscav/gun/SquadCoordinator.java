package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.faction.AlertNetwork;
import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The squad layer for the coordinated tiers (README 5aa): focus fire, bounding overwatch, flanking
 * and cover exclusivity. TROOP and ELITE units own one instance each; a SCAV or SNIPER never does
 * any work here ({@link AiProfile#coordination(Mob)} is false, and every method answers safely).
 *
 * <h2>What a squad is</h2>
 * <p>Same faction + inside {@code ai.coordination.squadRadius} + alert-shared. "Alert-shared" means
 * the candidate has a live target or a current contact report ({@link AlertNetwork#current(Mob)}),
 * i.e. it is in the fight rather than strolling past it. The list is <b>cached</b> for
 * {@code squadCacheTicks} and only refreshed for a mob whose tier coordinates, so the cost is one
 * entity query per mob per window - never a per-tick world scan.</p>
 *
 * <h2>The garrison's squad id (the one-time city garrison)</h2>
 * <p>The city garrison spawns a squad as a <b>group</b> and needs it to stay a group: two squads
 * placed twenty blocks apart in the same city would otherwise be one squad by the proximity rule
 * alone. So a mob may carry {@code tarkovscav:squadId} in its persistent data, and when it does,
 * {@link #refresh} accepts only candidates carrying the <b>same</b> id. The rule is
 * <b>opt-in by construction</b>: a mob with no id (every mob that is not part of a garrison, and every
 * world saved before this feature existed) behaves exactly as before, because an id of 0 disables the
 * filter instead of matching every other zero.</p>
 *
 * <h2>No deadlock, by construction</h2>
 * <p>The coordinator can only ever bias a decision the mob was already able to make:</p>
 * <ul>
 *   <li>the overwatch role rotates every {@code overwatchWindowTicks}, so "you are the suppressor"
 *       is temporary;</li>
 *   <li>{@link #shouldSuppress(long)} additionally expires after
 *       {@code overwatchTimeoutTicks}, so even a window longer than the timeout cannot park a mob -
 *       it falls back to its own behaviour;</li>
 *   <li>the existing {@code GunBrain} SUPPRESS state already ends by itself after
 *       {@code tactics.suppressTicks}, and the mover role only chooses cover while the mob is
 *       already in ADVANCE.</li>
 * </ul>
 *
 * <h2>Pure decisions for the gate</h2>
 * <p>{@link #chooseFocusTarget(int[])}, {@link #isSuppressor(int, long, int)},
 * {@link #flankerCount(int, double)}, {@link #isFlanker(int, int, double)},
 * {@link #onFlankSide(double, double, double, double, int, double, double)} and
 * {@link #claimIsFree(int, int, long, long)} take primitives and return primitives, so
 * {@code tools/selftest_ai_profiles.js} mirrors them, compares the sources textually and simulates
 * the real cases (two mobs cannot claim one spot, the suppressor rotates, a squad picks one target).
 */
public final class SquadCoordinator {
    /**
     * One cover claim: which entity took the position and when it stops being reserved. Keyed by the
     * exact block, so two mobs aiming at the same wall block cannot both pick it.
     */
    private record Claim(int ownerId, long expiresAt) {
    }

    /** The shared claim table; entries expire, so a dead owner frees its spot after coverClaimTicks. */
    private static final Map<BlockPos, Claim> CLAIMS = new HashMap<>();

    /**
     * The persistent-data key a garrison squad's shared id lives under. Namespaced because
     * {@code getPersistentData()} is shared with Forge and every other mod, exactly like the voice pitch.
     */
    public static final String NBT_SQUAD_ID = "tarkovscav:squadId";

    private final Mob mob;
    private final List<Mob> members = new ArrayList<>();
    private long refreshedAt = Long.MIN_VALUE;
    private long roleWindow = Long.MIN_VALUE;
    private boolean suppressor;
    private long suppressorSince;
    private int flankSide;

    public SquadCoordinator(Mob mob) {
        this.mob = mob;
    }

    // ------------------------------------------------------------------ per-tick cost control

    /**
     * Once per brain tick: refresh the member list when it is stale, and rotate the overwatch role
     * when the window changes. Everything else here is a map or list lookup.
     */
    public void tick(ServerLevel level, @Nullable LivingEntity target) {
        if (!AiProfile.coordination(this.mob)) {
            this.members.clear();
            this.refreshedAt = Long.MIN_VALUE;
            this.roleWindow = Long.MIN_VALUE;
            this.suppressor = false;
            this.flankSide = 0;
            return;
        }
        long now = level.getGameTime();
        int cache = Math.max(1, Config.AI_COORD_SQUAD_CACHE_TICKS.get());
        if (this.members.isEmpty() || now - this.refreshedAt >= cache) {
            refresh(level, now);
        }
        int window = Math.max(1, Config.AI_COORD_OVERWATCH_WINDOW_TICKS.get());
        long windowIndex = now / window;
        if (windowIndex != this.roleWindow) {
            this.roleWindow = windowIndex;
            boolean next = isSuppressor(memberIndex(), windowIndex, this.members.size());
            if (next && !this.suppressor) {
                this.suppressorSince = now;
            }
            this.suppressor = next;
        }
    }

    /** The (cached) squad list, this mob first-in-order; refreshed on the configured interval. */
    private void refresh(ServerLevel level, long now) {
        this.members.clear();
        this.members.add(this.mob);
        Faction faction = Faction.of(this.mob);
        if (faction != null) {
            double radius = Config.AI_COORD_SQUAD_RADIUS.get();
            int mySquadId = squadIdOf(this.mob);
            for (Mob candidate : level.getEntitiesOfClass(Mob.class,
                    this.mob.getBoundingBox().inflate(radius))) {
                if (candidate == this.mob || !Faction.isArmedMember(candidate)) {
                    continue;
                }
                if (Faction.of(candidate) != faction || !alertShared(candidate)) {
                    continue;
                }
                // A garrison squad stays its own squad (see the class comment): only when THIS mob has an
                // id does the id have to match, so every pre-existing mob keeps the pure-proximity rule.
                if (mySquadId != 0 && squadIdOf(candidate) != mySquadId) {
                    continue;
                }
                this.members.add(candidate);
            }
        }
        this.members.sort(Comparator.comparingInt(Mob::getId));
        this.refreshedAt = now;
        int index = this.members.indexOf(this.mob);
        int size = this.members.size();
        this.flankSide = isFlanker(index, size, Config.AI_COORD_FLANK_FRACTION.get())
                ? (index % 2 == 0 ? 1 : -1) : 0;
        pruneClaims(now);
    }

    // ------------------------------------------------------------------ garrison squad identity

    /**
     * Stamps a shared squad id onto a garrison member. Written into the entity's <b>persistent</b> data,
     * so it survives a chunk unload, a server restart and a world reload: a garrison that comes back
     * after a reboot is still one squad.
     */
    public static void assignSquad(Mob mob, int squadId) {
        mob.getPersistentData().putInt(NBT_SQUAD_ID, squadId);
    }

    /** This mob's garrison squad id, or 0 when it is not part of one. */
    public static int squadIdOf(Mob mob) {
        return mob.getPersistentData().getInt(NBT_SQUAD_ID);
    }

    /**
     * Reserves a cover block on behalf of a garrison member at spawn time. The claim goes into the same
     * table {@link #claim(BlockPos)} writes to, so the squad layer's own cover search refuses a spot that
     * a sibling has already been given - the garrison is a squad through the existing mechanism, not a
     * second one.
     */
    public static void claimFor(Mob mob, @Nullable BlockPos pos) {
        if (pos == null || !Config.SPEC.isLoaded() || !Config.AI_COORD_COVER_CLAIMS.get()) {
            return;
        }
        long expires = mob.level().getGameTime() + Math.max(1, Config.AI_COORD_COVER_CLAIM_TICKS.get());
        CLAIMS.put(pos.immutable(), new Claim(mob.getId(), expires));
    }

    /** "Alert-shared": the candidate is actually in this fight, not walking past it. */
    private static boolean alertShared(Mob candidate) {
        LivingEntity target = candidate.getTarget();
        return (target != null && target.isAlive()) || AlertNetwork.current(candidate) != null;
    }

    private int memberIndex() {
        int index = this.members.indexOf(this.mob);
        return index < 0 ? 0 : index;
    }

    /** True when at least one other alert-shared ally is in range. */
    public boolean hasSquad() {
        return this.members.size() > 1;
    }

    // ------------------------------------------------------------------ overwatch

    /**
     * Bounding overwatch: true while this mob is the squad's suppressor and the role has not timed
     * out. The timeout is the anti-deadlock bound - when it expires the mob moves like everybody else.
     */
    public boolean shouldSuppress(long now) {
        if (!this.suppressor || !hasSquad() || !Config.AI_COORD_OVERWATCH.get()) {
            return false;
        }
        int timeout = Config.AI_COORD_OVERWATCH_TIMEOUT_TICKS.get();
        return timeout <= 0 || now - this.suppressorSince <= timeout;
    }

    /** True when this mob is one of the movers (the squad exists and overwatch is on). */
    public boolean isMover() {
        return hasSquad() && Config.AI_COORD_OVERWATCH.get() && !this.suppressor;
    }

    // ------------------------------------------------------------------ focus fire

    /**
     * The squad's designated target: the one most members are already fighting, ties broken by the
     * lowest entity id so the answer is stable. The caller still has to see it - this returns an
     * entity, and {@code GunBrain#applyFocusFire} refuses to adopt one without line of sight.
     */
    @Nullable
    public LivingEntity focusTarget(ServerLevel level) {
        if (!hasSquad() || !Config.AI_COORD_FOCUS_FIRE.get()) {
            return null;
        }
        int[] ids = new int[this.members.size()];
        for (int i = 0; i < ids.length; i++) {
            LivingEntity target = this.members.get(i).getTarget();
            ids[i] = target != null && target.isAlive() ? target.getId() : -1;
        }
        int chosen = chooseFocusTarget(ids);
        if (chosen < 0) {
            return null;
        }
        Entity entity = level.getEntity(chosen);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    // ------------------------------------------------------------------ flanking

    /** +1 / -1 when this mob is a flanker (which side of the axis), 0 when it holds the front. */
    public int flankSide() {
        if (!hasSquad() || !Config.AI_COORD_FLANKING.get()) {
            return 0;
        }
        return this.flankSide;
    }

    /** Is a cover candidate on this flanker's side of the mob-to-target axis? */
    public boolean onMyFlank(double targetX, double targetZ, double x, double z) {
        if (this.flankSide == 0) {
            return true;
        }
        return onFlankSide(this.mob.getX(), this.mob.getZ(), targetX, targetZ, this.flankSide, x, z);
    }

    // ------------------------------------------------------------------ cover claims

    /** Reserves a cover block for this mob, if claims are on. */
    public void claim(@Nullable BlockPos pos) {
        if (pos == null || !AiProfile.coordination(this.mob) || !Config.AI_COORD_COVER_CLAIMS.get()) {
            return;
        }
        long expires = this.mob.level().getGameTime() + Config.AI_COORD_COVER_CLAIM_TICKS.get();
        CLAIMS.put(pos.immutable(), new Claim(this.mob.getId(), expires));
    }

    /** True when nobody else holds this block. */
    public boolean isFree(BlockPos pos) {
        if (!AiProfile.coordination(this.mob) || !Config.AI_COORD_COVER_CLAIMS.get()) {
            return true;
        }
        return isCoverFree(pos, this.mob.getId(), this.mob.level().getGameTime());
    }

    /** Drops every claim this mob owns (goal stopped, mob died, engagement over). */
    public void release() {
        int id = this.mob.getId();
        CLAIMS.entrySet().removeIf(entry -> entry.getValue().ownerId() == id);
    }

    // ------------------------------------------------------------------ pure decision helpers

    /**
     * Is {@code index} the squad's suppressor for this window? Exactly one member at a time (for a
     * squad of two or more), and the member rotates: index i suppresses on every window where
     * {@code window % count == i}. A one-mob squad never suppresses - there is nobody to move.
     */
    public static boolean isSuppressor(int index, long windowIndex, int count) {
        if (count <= 1) {
            return false;
        }
        return ((windowIndex % count) + count) % count == index;
    }

    /** How many members flank: at least one and never the whole squad, for a squad of 2 or more. */
    public static int flankerCount(int count, double fraction) {
        if (count < 2) {
            return 0;
        }
        double clamped = Math.min(Math.max(fraction, 0.0D), 1.0D);
        int wanted = (int) Math.round(count * clamped);
        return Math.min(Math.max(wanted, 1), count - 1);
    }

    /** The highest-indexed members flank, so the assignment is deterministic from entity ids. */
    public static boolean isFlanker(int index, int count, double fraction) {
        int flankers = flankerCount(count, fraction);
        return flankers > 0 && index >= count - flankers;
    }

    /**
     * The side test: the sign of the cross product of the mob-to-target axis with the mob-to-spot
     * offset. A candidate exactly on the axis counts as being on either side.
     */
    public static boolean onFlankSide(double mobX, double mobZ, double targetX, double targetZ,
                                      int side, double x, double z) {
        double axisX = targetX - mobX;
        double axisZ = targetZ - mobZ;
        double offsetX = x - mobX;
        double offsetZ = z - mobZ;
        return (axisX * offsetZ - axisZ * offsetX) * side >= 0.0D;
    }

    /** Most-voted target id, ties by lowest id; -1 when nobody has a live target. */
    public static int chooseFocusTarget(int[] targetIds) {
        int best = -1;
        int bestVotes = 0;
        for (int id : targetIds) {
            if (id < 0) {
                continue;
            }
            int votes = 0;
            for (int other : targetIds) {
                if (other == id) {
                    votes++;
                }
            }
            if (votes > bestVotes || (votes == bestVotes && (best < 0 || id < best))) {
                bestVotes = votes;
                best = id;
            }
        }
        return best;
    }

    /** The claim rule: a spot is free when it is unclaimed, expired, or claimed by the asker. */
    public static boolean claimIsFree(int ownerId, int claimantId, long expiresAt, long now) {
        return ownerId == claimantId || expiresAt <= now;
    }

    private static boolean isCoverFree(BlockPos pos, int claimantId, long now) {
        Claim claim = CLAIMS.get(pos);
        if (claim == null) {
            return true;
        }
        return claimIsFree(claim.ownerId(), claimantId, claim.expiresAt(), now);
    }

    private static void pruneClaims(long now) {
        CLAIMS.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    /** One-line readout for {@code /tarkovscav debug}. */
    public String describe() {
        if (!AiProfile.coordination(this.mob)) {
            return "squad=off";
        }
        return "squad=" + this.members.size()
                + (this.suppressor ? "/SUPPRESS" : "/move")
                + (this.flankSide == 0 ? "" : this.flankSide > 0 ? "/flank+" : "/flank-");
    }
}
