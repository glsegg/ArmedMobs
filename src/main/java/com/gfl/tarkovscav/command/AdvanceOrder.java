package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.gun.SquadCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * The command layer a unit carries: "go to this mark, at this speed, from this tick until that one".
 *
 * <h2>It only covers where and how fast</h2>
 * <p>An order never touches the fight. Aiming, firing, cover, suppression, reloading and retreating all
 * stay exactly where they were - the order adds a destination that {@code AdvanceOrderGoal} walks towards
 * <b>when nothing more important is happening</b>. That is why "combat takes priority" and "self-preservation
 * takes priority" need no code in the fight itself: the goal simply cannot run while
 * {@code GunAttackGoal} holds the mob's MOVE flag.</p>
 *
 * <h2>On the entity, in persistent NBT</h2>
 * <p>{@code {targetX, targetY, targetZ, dimension, letter, issuedTick, expiresTick, index, size}} under
 * {@code tarkovscav:advanceOrder} in Forge's per-entity persistent data. So the order survives a chunk
 * unload, a save, a server restart and a re-login, and an order is cleared the moment the mark it names is
 * gone ({@link MarkData#stillExists}) - which is how "the order expires with the mark" is implemented
 * without either side having to know about the other.</p>
 *
 * <h2>Deterministic core</h2>
 * <p>Everything the acceptance list asks to prove is a pure static method here - {@link #valid},
 * {@link #arrived}, {@link #startDelay}, {@link #holds}, {@link #combatOverrides},
 * {@link #retreatOverrides} - so {@code tools/selftest_command_marks.js} mirrors them and simulates
 * arrival, expiry, off/on coordination, the leapfrog rotation and the retreat rule with no game.</p>
 *
 * @param target      the mark's block
 * @param letter      the mark's letter, so the order can be tied back to it
 * @param dimension   the level the mark is in; an order never crosses dimensions
 * @param issuedTick  game time it was given
 * @param expiresTick game time it dies at, or {@link CommandMark#PERMANENT}
 * @param index       this unit's position in the ordered element, by entity id (0-based)
 * @param size        how many units the order covers, so the overwatch rotation has a count
 */
public record AdvanceOrder(BlockPos target, String letter, ResourceLocation dimension, long issuedTick,
                           long expiresTick, int index, int size) {
    /** The persistent-data key; namespaced because the tag is shared with every other mod. */
    public static final String TAG = "tarkovscav:advanceOrder";

    private static final String KEY_X = "targetX";
    private static final String KEY_Y = "targetY";
    private static final String KEY_Z = "targetZ";
    private static final String KEY_LETTER = "letter";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_ISSUED = "issuedTick";
    private static final String KEY_EXPIRES = "expiresTick";
    private static final String KEY_INDEX = "index";
    private static final String KEY_SIZE = "size";

    /** How far apart the staggered departures are spread, in ticks. */
    public static final int STAGGER_SPREAD_TICKS = 40;

    // ------------------------------------------------------------------ persistence

    /** Writes the order onto the mob. Always overwrites: a new order replaces the old one. */
    public static void write(Mob mob, AdvanceOrder order) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_X, order.target().getX());
        tag.putInt(KEY_Y, order.target().getY());
        tag.putInt(KEY_Z, order.target().getZ());
        tag.putString(KEY_LETTER, order.letter());
        tag.putString(KEY_DIMENSION, order.dimension().toString());
        tag.putLong(KEY_ISSUED, order.issuedTick());
        tag.putLong(KEY_EXPIRES, order.expiresTick());
        tag.putInt(KEY_INDEX, order.index());
        tag.putInt(KEY_SIZE, order.size());
        mob.getPersistentData().put(TAG, tag);
    }

    /** The order this mob carries, or null when it has none / the stored one is unreadable. */
    @Nullable
    public static AdvanceOrder read(Mob mob) {
        CompoundTag tag = mob.getPersistentData().getCompound(TAG);
        if (tag.isEmpty()) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(KEY_DIMENSION));
        if (dimension == null) {
            return null;
        }
        return new AdvanceOrder(
                new BlockPos(tag.getInt(KEY_X), tag.getInt(KEY_Y), tag.getInt(KEY_Z)),
                tag.getString(KEY_LETTER), dimension, tag.getLong(KEY_ISSUED),
                tag.contains(KEY_EXPIRES) ? tag.getLong(KEY_EXPIRES) : CommandMark.PERMANENT,
                tag.getInt(KEY_INDEX), Math.max(1, tag.getInt(KEY_SIZE)));
    }

    /** Removes the order from the mob. Safe to call on a mob that has none. */
    public static void clear(Mob mob) {
        mob.getPersistentData().remove(TAG);
    }

    /** True when this mob carries an order at all - used by the debug readout. */
    public static boolean has(Mob mob) {
        return !mob.getPersistentData().getCompound(TAG).isEmpty();
    }

    // ------------------------------------------------------------------ the deterministic core

    /** The order's own clock: a permanent mark's order never expires on time alone. */
    public static boolean valid(long expiresTick, long now) {
        return expiresTick == CommandMark.PERMANENT || now < expiresTick;
    }

    /** The arrival rule: inside {@code command.arrivalRadius} clears the order. */
    public static boolean arrived(double distanceSqr, double arrivalRadius) {
        return distanceSqr <= arrivalRadius * arrivalRadius;
    }

    /**
     * This unit's departure tick offset. With {@code command.coordination} off every unit starts on the
     * same tick (0); with it on, the offset is spread by entity id, so a squad leaves as a ragged file
     * instead of one solid block stepping off together.
     */
    public static int startDelay(int entityId, boolean coordination) {
        if (!coordination) {
            return 0;
        }
        return Math.floorMod(entityId, STAGGER_SPREAD_TICKS);
    }

    /**
     * Bounding overwatch while advancing: hold this window when this unit is the element's suppressor.
     *
     * <p>This is deliberately {@link SquadCoordinator#isSuppressor} - the <b>existing</b> rotation - so the
     * leapfrog is the same mechanism the firefight uses and not a second coordination system. Exactly one
     * member of an element of two or more holds per window, and the role rotates, so no unit is ever
     * parked.</p>
     */
    public static boolean holds(int index, long windowIndex, int size, boolean coordination) {
        return coordination && SquadCoordinator.isSuppressor(index, windowIndex, size);
    }

    /** Combat wins: a unit with a live target is fighting, not walking to a mark. */
    public static boolean combatOverrides(boolean hasLiveTarget) {
        return hasLiveTarget;
    }

    /** Self-preservation wins, and it also refuses NEW orders while it lasts. */
    public static boolean retreatOverrides(boolean retreating) {
        return retreating;
    }

    /**
     * The retreat trigger, mirrored from {@code GunBrain.decide}: health below the tier's fraction. Kept
     * here as a pure function so the acceptance case "order in progress + health below the threshold must
     * enter RETREAT and stop advancing" is a deterministic simulation rather than an in-game observation.
     */
    public static boolean retreatThresholdBreached(double health, double maxHealth, double fraction) {
        return maxHealth > 0.0D && health < maxHealth * fraction;
    }

    /** Should this unit be walking towards the mark right now? The whole rule, in one place. */
    public static boolean advances(boolean orderValid, boolean arrived, boolean hasLiveTarget,
                                  boolean retreating) {
        return orderValid && !arrived && !combatOverrides(hasLiveTarget) && !retreatOverrides(retreating);
    }

    /** One-line readout for {@code /armedmobs debug}. */
    public String describe(long now) {
        String left = expiresTick == CommandMark.PERMANENT ? "permanent"
                : Math.max(0L, expiresTick - now) + "t";
        return "order=" + this.letter + " -> " + this.target.toShortString()
                + " in " + this.dimension + " (" + left + ") element " + (this.index + 1) + "/" + this.size;
    }
}
