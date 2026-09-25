package com.gfl.tarkovscav.lean;

import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's copy of "how far is this player leaning right now" (README 5s).
 *
 * <p>The lean itself is a client-side camera effect, so the server learns about it from
 * {@link LeanNetwork}: the client sends its ramped value whenever it changes, and this map is what the
 * shot-origin rule ({@link LeanServerEvents}) and the item-toss guard read. It is deliberately a plain value
 * and not a player capability:</p>
 * <ul>
 *   <li>it is <b>not</b> saved - a player who logs out leaning is simply not leaning any more;</li>
 *   <li>it is <b>clamped</b> on arrival, so a modified client cannot push its own shots further than
 *       {@code leanMaxOffset} (the wall clip then shortens it further);</li>
 *   <li>it is dropped when the player disconnects, so a recycled {@link UUID} cannot inherit a lean.</li>
 * </ul>
 */
public final class LeanState {
    private static final Map<UUID, Float> LEAN = new ConcurrentHashMap<>();
    /** Players who are holding a lean key right now (the item-toss guard asks this, not the amount). */
    private static final java.util.Set<UUID> HOLDING = ConcurrentHashMap.newKeySet();

    private LeanState() {
    }

    /** Records the synced lean amount of a player. Everything is clamped to [-1, 1]. */
    public static void set(UUID player, float lean) {
        float clamped = LeanMath.clamp(lean);
        if (clamped == 0.0F) {
            LEAN.remove(player);
        } else {
            LEAN.put(player, clamped);
        }
    }

    /** Records whether the player is holding a lean key (README 5t): the toss guard's only question. */
    public static void setHolding(UUID player, boolean holding) {
        if (holding) {
            HOLDING.add(player);
        } else {
            HOLDING.remove(player);
        }
    }

    /** True while the player is holding a lean key. A tap releases it, so a replayed drop is not cancelled. */
    public static boolean holding(@Nullable Player player) {
        return player != null && HOLDING.contains(player.getUUID());
    }

    /** The synced lean amount, or 0 when the player is not leaning (including "never sent anything"). */
    public static float leanOf(@Nullable Player player) {
        if (player == null) {
            return 0.0F;
        }
        Float lean = LEAN.get(player.getUUID());
        return lean == null ? 0.0F : lean;
    }

    /** Forgets a player (logout). */
    public static void clear(UUID player) {
        LEAN.remove(player);
        HOLDING.remove(player);
    }

    /** How many players are currently leaning - for the debug output and the gate. */
    public static int leaningCount() {
        return LEAN.size();
    }
}
