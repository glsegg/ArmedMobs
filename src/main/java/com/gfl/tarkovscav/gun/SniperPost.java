package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * The sniper's "post" state (README 5q): is it holding a firing position, how many shots has it taken from
 * here, and when did it last move.
 *
 * <p>The state lives in the mob's persistent data, so a reload does not turn a sniper that is dug in into one
 * that thinks it just arrived. Only the sniper entity writes it; {@link #holdingPost} is read by the entity's
 * own tick, which is also what keeps the whole feature out of {@code GunBrain}: the sniper pins itself
 * (navigation stopped, no strafe) instead of the shared brain learning about sniping.</p>
 */
public final class SniperPost {
    private static final String TAG_HOLDING = "tarkovscav:sniperHolding";
    private static final String TAG_POST_X = "tarkovscav:sniperPostX";
    private static final String TAG_POST_Z = "tarkovscav:sniperPostZ";
    private static final String TAG_SHOTS_HERE = "tarkovscav:sniperShotsHere";
    private static final String TAG_LAST_MOVE = "tarkovscav:sniperMovedAt";
    private static final String TAG_REASON = "tarkovscav:sniperLastReason";

    private SniperPost() {
    }

    public static boolean holdingPost(Mob mob) {
        return mob.getPersistentData().getBoolean(TAG_HOLDING);
    }

    public static void setHoldingPost(Mob mob, boolean holding) {
        mob.getPersistentData().putBoolean(TAG_HOLDING, holding);
    }

    /** The post's XZ, or null when the mob has never dug in. */
    @Nullable
    public static BlockPos post(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(TAG_POST_X)) {
            return null;
        }
        return new BlockPos(data.getInt(TAG_POST_X), mob.blockPosition().getY(), data.getInt(TAG_POST_Z));
    }

    public static void setPost(Mob mob, BlockPos pos) {
        CompoundTag data = mob.getPersistentData();
        data.putInt(TAG_POST_X, pos.getX());
        data.putInt(TAG_POST_Z, pos.getZ());
    }

    public static int shotsFromHere(Mob mob) {
        return mob.getPersistentData().getInt(TAG_SHOTS_HERE);
    }

    /** Counts one shot taken from the current post (condition 3 of the relocation rules). */
    public static void noteShot(Mob mob, long gameTime) {
        CompoundTag data = mob.getPersistentData();
        data.putInt(TAG_SHOTS_HERE, data.getInt(TAG_SHOTS_HERE) + 1);
        data.putLong(TAG_LAST_MOVE, gameTime);
    }

    public static void clearShots(Mob mob) {
        mob.getPersistentData().putInt(TAG_SHOTS_HERE, 0);
    }

    public static long movedAt(Mob mob) {
        return mob.getPersistentData().getLong(TAG_LAST_MOVE);
    }

    public static void setMovedAt(Mob mob, long gameTime) {
        mob.getPersistentData().putLong(TAG_LAST_MOVE, gameTime);
    }

    public static void setReason(Mob mob, String reason) {
        mob.getPersistentData().putString(TAG_REASON, reason);
    }

    public static String reason(Mob mob) {
        return mob.getPersistentData().getString(TAG_REASON);
    }

    /** One-line report for {@code /tarkovscav debug} and {@code test sniper}. */
    public static String describe(Mob mob) {
        BlockPos post = post(mob);
        return "sniper=" + (holdingPost(mob) ? "holding" : "moving")
                + " post=" + (post == null ? "none" : post.getX() + "," + post.getZ())
                + " shotsHere=" + shotsFromHere(mob) + "/" + Config.SNIPER_SHOTS_BEFORE_MOVE.get()
                + " lastReason=" + (reason(mob).isEmpty() ? "none" : reason(mob));
    }
}
