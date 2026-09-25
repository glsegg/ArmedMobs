package com.gfl.tarkovscav;

/**
 * The one thing {@code RenderStateGuard} reads from the mod's Config: the {@code client.logGlState}
 * debug switch. Supplying it here is the same trick {@code GrenadeBallisticsTest} uses for Minecraft -
 * the REAL guard source is compiled and executed against a minimal stand-in, so the live-GL test can run
 * in a bare JVM instead of a Minecraft instance.
 *
 * <p>The stub answers exactly what the shipped default answers before the config is loaded
 * ({@code Config.logGlState()} is {@code SPEC.isLoaded() && LOG_GL_STATE.get()}, and the spec is not
 * loaded here), so the guard's logging branches stay off and the test exercises pure GL state.</p>
 */
public final class Config {
    private Config() {
    }

    /** The shipped default: logging off. */
    public static boolean logGlState() {
        return false;
    }
}
