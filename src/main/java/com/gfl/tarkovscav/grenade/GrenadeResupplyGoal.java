package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.block.WeaponRackArmament;
import com.gfl.tarkovscav.block.WeaponRackBlockEntity;
import com.gfl.tarkovscav.gun.GunUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;

/**
 * "I am out of grenades - is there a rack with some?" (README 5v).
 *
 * <h2>The new truth table, and why it is a separate goal</h2>
 * <p>The rack has always had one rule: an <b>unarmed</b> villager or pillager walks up and takes the weapon,
 * and an armed unit is not a recruit ({@code WeaponRackTaker.isRecruit} requires an empty main hand). That rule
 * stays exactly as it was - it is what stops a rack from re-arming the same mob forever. What this goal adds is a
 * <b>parallel</b> path for a mob that is <em>already</em> armed:</p>
 * <table border="1">
 *   <caption>who comes to a rack, and for what</caption>
 *   <tr><th>Mob</th><th>Rack holds</th><th>What happens</th></tr>
 *   <tr><td>unarmed villager/pillager</td><td>a weapon</td><td>takes it and is converted (unchanged)</td></tr>
 *   <tr><td>unarmed villager/pillager</td><td>a throwable</td><td>nothing - a grenade does not arm anybody</td></tr>
 *   <tr><td>armed gun unit, pouch full</td><td>anything</td><td>nothing - it is not hungry</td></tr>
 *   <tr><td>armed gun unit, room in the pouch</td><td>a throwable</td><td><b>walks over and takes one</b> (this
 *       goal). It never touches a rack holding a gun: it is already armed</td></tr>
 *   <tr><td>armed gun unit, room in the pouch</td><td>a weapon</td><td>nothing - it is already armed</td></tr>
 * </table>
 * <p>With {@code grenades.rackPriority = grenade} (the default) a mob heading for a rack prefers one that
 * actually holds a throwable over a closer one holding a gun. A rack holds one item, so "a rack with both" does
 * not exist; the setting chooses between racks.</p>
 */
public class GrenadeResupplyGoal extends Goal {
    private static final String TAG_COOLDOWN = "tarkovscav:grenadeResupplyCooldown";
    /** How close the mob has to be to reach the rack. */
    private static final double REACH = 2.5D;
    /** The old scan's vertical band, kept exactly: 8 blocks up and down from the mob's own feet. */
    private static final int VERTICAL_REACH = 8;

    private final Mob mob;
    private final GunUser user;
    @Nullable
    private WeaponRackBlockEntity target;
    private int searchCooldown;

    public GrenadeResupplyGoal(Mob mob, GunUser user) {
        this.mob = mob;
        this.user = user;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!Config.GRENADES_ENABLED.get() || !Config.MOB_GRENADES_ENABLED.get()
                || !Config.GRENADES_RESUPPLY_ENABLED.get()) {
            return false;
        }
        if (this.mob.level().isClientSide) {
            return false;
        }
        if (cooldown() > 0) {
            setCooldown(cooldown() - 1);
            return false;
        }
        if (!MobGrenades.hasRoom(this.mob)) {
            return false;
        }
        if (this.searchCooldown > 0) {
            this.searchCooldown--;
            return false;
        }
        // Only while there is nothing to shoot at: a firefight outranks a shopping trip.
        if (this.mob.getTarget() != null) {
            return false;
        }
        this.target = findRack();
        if (this.target == null) {
            // Nothing worth walking to. Without this the goal selector ran the search again on the very
            // next tick, for as long as the mob had no target - which is most of an armed villager's life.
            // The search is cheap now (see findRack), but "every tick" was never the intent.
            this.searchCooldown = Math.max(1, Config.GRENADES_RESUPPLY_SEARCH_COOLDOWN_TICKS.get());
        }
        return this.target != null;
    }

    @Override
    public void start() {
        if (this.target != null) {
            BlockPos pos = this.target.getBlockPos();
            this.mob.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null && MobGrenades.hasRoom(this.mob)
                && !this.mob.getNavigation().isDone()
                && this.mob.distanceToSqr(this.target.getBlockPos().getX() + 0.5D,
                        this.target.getBlockPos().getY() + 0.5D, this.target.getBlockPos().getZ() + 0.5D)
                    > REACH * REACH;
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        double distance = Math.sqrt(this.mob.distanceToSqr(this.target.getBlockPos().getX() + 0.5D,
                this.target.getBlockPos().getY() + 0.5D, this.target.getBlockPos().getZ() + 0.5D));
        if (distance > REACH) {
            return;
        }
        take();
    }

    @Override
    public void stop() {
        this.target = null;
        this.searchCooldown = 40;
    }

    /** Takes ONE throwable off the rack, if it is still holding one. */
    private void take() {
        if (this.target == null || !(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack held = this.target.held();
        if (!(held.getItem() instanceof GrenadeItem grenade)) {
            // Somebody swapped it for a gun (or took it): this trip was wasted.
            setCooldown(Config.GRENADES_RESUPPLY_COOLDOWN_TICKS.get());
            return;
        }
        if (!MobGrenades.add(this.mob, grenade.kind())) {
            return;
        }
        // claim(): a normal rack empties (never duplicated), a creative rack keeps its endless template.
        ItemStack taken = this.target.claim();
        if (taken.isEmpty()) {
            // Lost the race against another mob: undo the pouch entry so nothing is duplicated.
            MobGrenades.take(this.mob, grenade.kind());
            return;
        }
        setCooldown(Config.GRENADES_RESUPPLY_COOLDOWN_TICKS.get());
        this.target.setChanged();
        TarkovScav.LOGGER.info("[grenade] {} resupplied {} from the rack at {}: pouch now {}",
                this.mob.getName().getString(), grenade.kind().id(),
                this.target.getBlockPos().toShortString(), MobGrenades.logLine(this.mob));
        this.mob.getNavigation().stop();
        this.target = null;
    }

    /**
     * The nearest rack that is worth the walk, ordered by {@code grenades.rackPriority}.
     *
     * <h2>Cost, and why this method was rewritten (the user's "villagers stutter when they move")</h2>
     * <p>It used to walk {@code BlockPos.betweenClosed} over a {@code (2r+1) x 17 x (2r+1)} box and call
     * {@code level.getBlockEntity(pos)} on every position. At the shipped {@code resupplyRadius = 24}
     * that is 49 x 17 x 49 = <b>40,817 lookups per search</b>, and - worse - {@code Level#getBlockEntity}
     * goes through {@code getChunkAt}, so a search <b>synchronously loaded up to 25 chunks</b> every tick.
     * {@code canUse()} reached it on every tick for every armed unit with no target. Measured on the city
     * server: 25 idle armed villagers cost 31.3 ms of tick, i.e. <b>1.25 ms per mob per tick</b>, against
     * 0.32 ms for an engaged one.</p>
     *
     * <p>It now walks the <b>chunks</b> in the box and reads each loaded chunk's own block-entity map, so
     * the work is proportional to the block entities that actually exist instead of to the volume: at most
     * four chunk lookups per axis (the box is 49 blocks wide, so it can straddle at most four 16-block
     * chunks), i.e. <b>at most 16 chunk lookups</b> at the default radius and 4 at radius 4, plus one
     * iteration step per block entity in those chunks - and it runs once per
     * {@code grenades.resupplySearchCooldownTicks} (40) instead of once per tick. A chunk that is not
     * loaded is skipped without touching it, and a chunk with no block entities is one
     * {@code isEmpty()} call. Against 40,817 lookups per tick before.</p>
     *
     * <p>Behaviour is unchanged in every case that can actually happen: a mob only exists in a loaded
     * chunk, and a chunk within 24 blocks of it is inside its own ticking radius, so a rack it could have
     * walked to is in a loaded chunk. The one thing that changes is that the search no longer
     * force-loads (and generates) chunks on behalf of a mob that is standing still.</p>
     */
    @Nullable
    private WeaponRackBlockEntity findRack() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        double radius = Config.GRENADES_RESUPPLY_RADIUS.get();
        BlockPos centre = this.mob.blockPosition();
        String priority = Config.GRENADES_RACK_PRIORITY.get().toLowerCase(Locale.ROOT);
        int reach = (int) Math.ceil(radius);
        int minY = centre.getY() - VERTICAL_REACH;
        int maxY = centre.getY() + VERTICAL_REACH;
        WeaponRackBlockEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (int chunkX = (centre.getX() - reach) >> 4; chunkX <= (centre.getX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (centre.getZ() - reach) >> 4; chunkZ <= (centre.getZ() + reach) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null || chunk.getBlockEntities().isEmpty()) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof WeaponRackBlockEntity rack) || rack.isEmpty()) {
                        continue;
                    }
                    BlockPos pos = rack.getBlockPos();
                    if (pos.getY() < minY || pos.getY() > maxY) {
                        continue;
                    }
                    WeaponRackArmament armament = WeaponRackArmament.armamentOf(rack.held());
                    boolean hasGrenade = armament.resupply();
                    if (priority.equals("weapon") ? !hasGrenade && !armament.usable()
                            : priority.equals("nearest") ? false : !hasGrenade) {
                        // grenade (default): only racks with a throwable; nearest: any; weapon: only gun racks
                        // (a test path: the mob walks there and finds nothing to take, which is the refusal in
                        // action).
                        continue;
                    }
                    double distance = this.mob.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                            pos.getZ() + 0.5D);
                    if (distance > radius * radius) {
                        continue;
                    }
                    if (distance < bestScore) {
                        bestScore = distance;
                        best = rack;
                    }
                }
            }
        }
        return best;
    }

    private int cooldown() {
        return this.mob.getPersistentData().getInt(TAG_COOLDOWN);
    }

    private void setCooldown(int ticks) {
        this.mob.getPersistentData().putInt(TAG_COOLDOWN, Math.max(0, ticks));
    }
}
