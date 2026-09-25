package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts how many times this mod submits a rig for drawing, per mob per frame.
 *
 * <p>The number exists because "the model is drawn twice" is a claim that has to be checkable rather
 * than argued about. A geometry pass is counted where GeckoLib enters the per-bone walk for one
 * animatable ({@link GunInHandGeoLayer#preRender}); one pass per mob per frame is the invariant, so the
 * log prints the pass count, the number of distinct mobs and the worst per-mob count for the window.
 * Anything above 1 is a WARN with the frame time it happened at, which is how a genuine second
 * submission (or a mod re-rendering the mob) would show up instead of being believed to happen.</p>
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
            PASSES_PER_MOB.clear();
            return;
        }
        if (windowStart < 0L) {
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
            String line = String.format("geometry passes=%d distinct mobs=%d worst per mob=%d",
                    passes, mobs, worstPerMob);
            if (worstPerMob > 1) {
                TarkovScav.LOGGER.warn("[rendercount] {} - more than one pass for mob {} means something"
                        + " is drawing this rig again (the glow outline is the one legitimate case)", line, worstMobId);
            } else {
                TarkovScav.LOGGER.info("[rendercount] {} - one submission per mob per frame", line);
            }
            passes = 0;
            worstPerMob = 0;
            worstMobId = -1;
            PASSES_PER_MOB.clear();
            windowStart = gameTime;
        }
    }
}
