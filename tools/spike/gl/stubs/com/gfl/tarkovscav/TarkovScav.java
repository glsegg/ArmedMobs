package com.gfl.tarkovscav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The other mod symbol {@code RenderStateGuard} touches: {@code TarkovScav.LOGGER}, a
 * {@code public static final org.slf4j.Logger} just like the real class, so the guard compiles against
 * this stub with an identical field descriptor. With {@code Config.logGlState()} false (see that stub)
 * the guard never reaches it, so nothing is logged and slf4j/log4j is never initialised.
 */
public final class TarkovScav {
    public static final Logger LOGGER = LoggerFactory.getLogger("RenderStateGuardLiveTest");

    private TarkovScav() {
    }
}
