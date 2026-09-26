package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * The one place that decides which stock {@link RenderType} the Bedrock rigs are drawn with, so the
 * A/B for the "at one angle I see the world through the mob" reports is a config value and not a
 * rebuild.
 *
 * <p>All four values are vanilla types; nothing here builds a custom render state, which is deliberate:
 * a hand-built {@code RenderType} would need {@code RenderStateShard} internals (protected, and not
 * covered by this project's access transformer) and would have to re-implement the lightmap/overlay
 * state that entity shaders expect. The trade-offs of each value are documented on the config key.</p>
 *
 * <p>Foreign item draws additionally use {@link RenderStateGuard}: stock render types alone do not
 * restore every stencil or texture state changed by another renderer.</p>
     * <p><b>Do not use {@code zOffset} to fix "at some angles a part changes material or goes black".</b>
     * {@code entityCutoutNoCullZOffset} applies {@code POLYGON_OFFSET(-1.0, -10.0)}, which is a
     * <em>slope-dependent</em> depth offset: the bias changes with the view angle, and at some angles the
     * depth order between the layers flips - the inner cube wins over the shell and the model renders as a
     * pile of scrambled blocks (measured on the user's client, 2026-09-24). Both {@code cutout} and
     * {@code zOffset} are NoCull, so culling was never the question. The real cause of that family of reports
     * is <b>coplanar faces</b>; fix them geometrically with {@code tools/patch_coplanar_faces.js} and keep them
     * at zero (asserted by {@code selftest_rig_bones.js}).</p>
 */
public final class ModelRenderTypes {
    private static final Set<String> WARNED = new HashSet<>();

    private ModelRenderTypes() {
    }

    /** The render type for this bake, from {@code client.modelRenderType}. */
    public static RenderType of(ResourceLocation texture) {
        String mode = Config.modelRenderType();
        return switch (mode) {
            case "zOffset" -> RenderType.entityCutoutNoCullZOffset(texture);
            case "translucent" -> {
                warnOnce("translucent", "client.modelRenderType = translucent: the rig no longer writes"
                        + " depth, so it can be drawn behind water, glass and other translucent world"
                        + " geometry while sorting. Use it to test, not to ship.");
                yield RenderType.entityTranslucent(texture);
            }
            case "solid" -> {
                warnOnce("solid", "client.modelRenderType = solid: alpha is ignored, so every face the"
                        + " author emptied (50236 of the 65536 atlas pixels are fully transparent and"
                        + " black) is drawn with its stored RGB - expect black patches. Diagnostic only.");
                yield RenderType.entitySolid(texture);
            }
            default -> RenderType.entityCutoutNoCull(texture);
        };
    }

    /** Called when the config is re-read, so the next report is logged again. */
    static void forgetLogGuards() {
        WARNED.clear();
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            TarkovScav.LOGGER.warn("[model] {}", message);
        }
    }
}
