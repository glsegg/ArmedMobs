package com.gfl.tarkovscav.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** Uses the scav's guarded, deferred item pass with the Black Fox squad's larger authored rig. */
public final class BlackFoxRenderer extends ScavRenderer {
    public BlackFoxRenderer(EntityRendererProvider.Context context) {
        super(context, new BlackFoxGeoModel());
    }

    @Override
    protected float modelScale() {
        // Absolute model scale: multiplying the scav's default 0.77 would shrink these twice.
        // GeoEntityRenderer scales before visiting bones, so held items use the same 0.75 frame.
        return 0.75F;
    }
}
