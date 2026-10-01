package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts model submissions over a five-second window. These are window totals, not frame counts:
 * rendering the same mob on successive frames is normal and is not evidence of duplicate rendering.
 *
 * <p>Turn it on with {@code client.logRenderStats = true}. It is a diagnostic, off by default.</p>
 */
public final class RenderStats {
    /** Ticks between reports (100 = 5 s). */
    private static final long WINDOW_TICKS = 100L;

    private static final Map<Integer, Integer> PASSES_PER_MOB = new HashMap<>();
    private static long windowStart = -1L;
    private static int passes;
    private static int worstPerMob;
    private static int worstMobId = -1;

    private RenderStats() {
    }

    /** Called once per geometry pass, for every mob using the rig. */
    public static void onGeometryPass(Entity entity, long gameTime) {
        if (entity == null) {
            return;
        }
        if (!Config.SPEC.isLoaded() || !Config.LOG_RENDER_STATS.get()) {
            clear();
            return;
        }
        if (windowStart < 0L || gameTime < windowStart) {
            clear();
            windowStart = gameTime;
        }
        passes++;
        int count = PASSES_PER_MOB.merge(entity.getId(), 1, Integer::sum);
        if (count > worstPerMob) {
            worstPerMob = count;
            worstMobId = entity.getId();
        }
        if (gameTime - windowStart >= WINDOW_TICKS) {
            int mobs = PASSES_PER_MOB.size();
            TarkovScav.LOGGER.info("[rendercount] over {} ticks: geometry passes={}, distinct mobs={},"
                            + " largest per-mob total={} (entity {}). These totals include successive frames.",
                    gameTime - windowStart, passes, mobs, worstPerMob, worstMobId);
            clear();
            windowStart = gameTime;
        }
    }

    public static void clear() {
        passes = 0;
        worstPerMob = 0;
        worstMobId = -1;
        PASSES_PER_MOB.clear();
        windowStart = -1L;
    }
}
