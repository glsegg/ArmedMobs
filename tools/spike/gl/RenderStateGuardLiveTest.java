import com.gfl.tarkovscav.client.RenderStateGuard;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.Arrays;

/**
 * The only gate that measures the DRIVER instead of the source: it opens a hidden GLFW window, makes a
 * real GL context current, and round-trips {@link RenderStateGuard} against it.
 *
 * <p>Why it exists: {@code tools/selftest_texture_state.js} reads the guard's source and
 * {@code tools/gl_state_audit.js} reads TaCZ's bytecode, so neither can tell whether the guard's restore
 * really puts the driver back. This test reproduces the foreign leak with the same mixture TaCZ uses
 * (raw {@code glActiveTexture}/glBindTexture/glEnable for the state it bypasses, cached
 * {@code RenderSystem}/GlStateManager calls for the rest), then asserts the guard's contract by reading
 * the values back:</p>
 *
 * <ol>
 *   <li>the active texture unit (raw) and the unit GlStateManager caches;</li>
 *   <li>the bindings of units 0..2 (base texture, overlay, lightmap);</li>
 *   <li>the binding of the shader-owned unit when the entry unit is 3 or higher (7 and 15 here, which
 *       also proves that a unit outside GlStateManager's 12-slot cache is not indexed into it);</li>
 *   <li>all 12 {@code RenderSystem} shader samplers;</li>
 *   <li>the FRONT and BACK stencil state - func, ref, value mask, write mask and the three op values -
 *       plus the enable, which is what the two original reports ("part of the mob is missing" and "the
 *       head/backpack is a block of pure black") come down to;</li>
 *   <li>the stencil clear value TaCZ zeroes mid-frame;</li>
 *   <li>depth test, depth write, blend and cull;</li>
 *   <li>{@code GL_NO_ERROR} after the restore; and</li>
 *   <li>idempotency: enter then leave changes nothing, a second enter/leave changes nothing, and a nested
 *       guard restores its own entry (not the outer one's).</li>
 * </ol>
 *
 * <p>The guard's other two entry points are checked too: {@code forceAlwaysPassStencil()} must leave
 * BOTH faces at {@code GL_ALWAYS/0/0xFF} with the test enabled, and {@code resyncTextureBinding()} must
 * bind the cached sampler id back over a raw-only binding. {@code rebindTexture(ResourceLocation)} is
 * NOT called here on purpose: it resolves the location through {@code Minecraft.getInstance()}, which does
 * not exist in a bare JVM (its source is asserted by the texture-state gate instead).</p>
 *
 * <p>Scenarios are the cross product {@code entryUnit in {0,2,7,15}} x {@code stencil on/off} x
 * {@code base texture bound/0} = 16 round trips.</p>
 *
 * <p>Exit codes: 0 = every scenario passed; 1 = a real failure; 2 = SKIPPED because this machine could
 * not give the test a GL context (no display, no GLFW natives, no GL driver). The skip prints exactly one
 * clearly worded line on stdout (stderr is silenced for the run - see {@code silenceNativeStderr}), and
 * the runner in {@code tools/spike/selftest.ps1} treats only 0 and 2 as non-failures - so the suite never
 * fails on a machine without a usable GL context, and never silently passes on one either.</p>
 *
 * <p>Its classpath is the Minecraft dependency set the build already resolved
 * ({@code build/classpath/runClient_minecraftClasspath.txt}, or the server one), plus the real guard
 * source and the two-field stubs in {@code tools/spike/gl/stubs}. Plain {@code javac}/{@code java} run it;
 * no Forge bootstrap, world, resource pack or Minecraft instance is involved.</p>
 */
public final class RenderStateGuardLiveTest {
    /** The runner treats this as "skipped", never as a pass. */
    private static final int EXIT_SKIP = 2;
    private static final int ENTITY_UNITS = 3;
    private static final int SAMPLERS = 12;
    private static final int[] ENTRY_UNITS = {0, 2, 7, 15};

    private static int scenarios;

    private RenderStateGuardLiveTest() {
    }

    // ------------------------------------------------------------------ assertions

    private static void equal(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    /** Field-by-field, so a failure names the field instead of dumping two blobs. */
    private static void assertSame(String label, State expected, State actual) {
        if (expected.active != actual.active) {
            throw new AssertionError(label + ": active unit " + expected.active + " -> " + actual.active);
        }
        if (expected.cachedActive != actual.cachedActive) {
            throw new AssertionError(label + ": cached active unit " + expected.cachedActive + " -> "
                    + actual.cachedActive);
        }
        if (expected.activeBinding != actual.activeBinding) {
            throw new AssertionError(label + ": binding of the active unit " + expected.activeBinding + " -> "
                    + actual.activeBinding);
        }
        for (int i = 0; i < ENTITY_UNITS; i++) {
            if (expected.units[i] != actual.units[i]) {
                throw new AssertionError(label + ": binding of texture unit " + i + " " + expected.units[i]
                        + " -> " + actual.units[i]);
            }
        }
        for (int i = 0; i < SAMPLERS; i++) {
            if (expected.samplers[i] != actual.samplers[i]) {
                throw new AssertionError(label + ": RenderSystem sampler " + i + " " + expected.samplers[i]
                        + " -> " + actual.samplers[i]);
            }
        }
        if (expected.stencilTest != actual.stencilTest) {
            throw new AssertionError(label + ": stencil test " + expected.stencilTest + " -> " + actual.stencilTest);
        }
        for (int i = 0; i < expected.front.length; i++) {
            if (expected.front[i] != actual.front[i]) {
                throw new AssertionError(label + ": FRONT stencil field " + i + " " + expected.front[i]
                        + " -> " + actual.front[i]);
            }
            if (expected.back[i] != actual.back[i]) {
                throw new AssertionError(label + ": BACK stencil field " + i + " " + expected.back[i]
                        + " -> " + actual.back[i]);
            }
        }
        if (expected.clear != actual.clear) {
            throw new AssertionError(label + ": stencil clear value " + expected.clear + " -> " + actual.clear);
        }
        if (expected.depthTest != actual.depthTest || expected.depthWrite != actual.depthWrite
                || expected.blend != actual.blend || expected.cull != actual.cull) {
            throw new AssertionError(label + ": depthTest=" + expected.depthTest + "->" + actual.depthTest
                    + " depthWrite=" + expected.depthWrite + "->" + actual.depthWrite
                    + " blend=" + expected.blend + "->" + actual.blend
                    + " cull=" + expected.cull + "->" + actual.cull);
        }
    }

    // ------------------------------------------------------------------ the state under test

    /**
     * Everything the guard promises to put back, read with raw GL calls so Minecraft's cache cannot answer
     * for the driver. {@code cachedActive} is the one exception and is read from GlStateManager on purpose:
     * the guard's contract covers the cache too, and after a restore the two must agree on the entry unit.
     */
    private record State(int active, int cachedActive, int activeBinding, int[] units, int[] samplers,
                         boolean stencilTest, int[] front, int[] back, int clear,
                         boolean depthTest, boolean depthWrite, boolean blend, boolean cull) {
        static State capture() {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] units = new int[ENTITY_UNITS];
            for (int i = 0; i < units.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                units[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            GL13.glActiveTexture(active);
            int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int[] samplers = new int[SAMPLERS];
            for (int i = 0; i < samplers.length; i++) {
                samplers[i] = RenderSystem.getShaderTexture(i);
            }
            return new State(active, GlStateManager._getActiveTexture(), binding, units, samplers,
                    GL11.glIsEnabled(GL11.GL_STENCIL_TEST),
                    new int[]{GL11.glGetInteger(GL11.GL_STENCIL_FUNC),
                            GL11.glGetInteger(GL11.GL_STENCIL_REF),
                            GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK),
                            GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK),
                            GL11.glGetInteger(GL11.GL_STENCIL_FAIL),
                            GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL),
                            GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)},
                    new int[]{GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_REF),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_VALUE_MASK),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_FAIL),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL),
                            GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS)},
                    GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE),
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) != 0,
                    GL11.glIsEnabled(GL11.GL_BLEND),
                    GL11.glIsEnabled(GL11.GL_CULL_FACE));
        }

        /** The real values, printed on the PASS line - this is the evidence the gate ran on a driver. */
        String describe() {
            return "active=" + this.active + " cachedActive=" + this.cachedActive
                    + " activeBinding=" + this.activeBinding + " units=" + Arrays.toString(this.units)
                    + " samplers=" + Arrays.toString(this.samplers)
                    + " stencilTest=" + this.stencilTest
                    + " front(func,ref,valMask,writeMask,fail,zfail,zpass)=" + Arrays.toString(this.front)
                    + " back=" + Arrays.toString(this.back) + " clear=" + this.clear
                    + " depthTest=" + this.depthTest + " depthWrite=" + this.depthWrite
                    + " blend=" + this.blend + " cull=" + this.cull;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Selects a unit in the driver AND in Minecraft's cache, exactly as the guard's own helper does. */
    private static void activate(int unit) {
        GlStateManager._activeTexture(unit);
        GL13.glActiveTexture(unit);
    }

    private static void drainGlErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // Draining only; the post-restore assertion below is the one that must see GL_NO_ERROR.
        }
    }

    /**
     * The foreign renderer's leak, reproduced. The texture calls and the stencil toggle are RAW (TaCZ
     * bypasses the cache for those, which is the whole cause of both reports); the stencil func and the
     * depth/blend/cull flips go through the cached API (RenderSystem.stencilFunc is cache-aware), so the
     * cache and the driver agree on those and the guard's cached writes can repair them.
     */
    private static void perturb(int entryUnit, int[] textures) {
        for (int i = 0; i < ENTITY_UNITS; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[(i + 1) % textures.length]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + (entryUnit == 0 ? 1 : 0));
        for (int i = 0; i < SAMPLERS; i++) {
            RenderSystem.setShaderTexture(i, 0);
        }
        GlStateManager._stencilFunc(GL11.GL_LEQUAL, 127, 0xFF);
        GL20.glStencilFuncSeparate(GL11.GL_BACK, GL11.GL_NOTEQUAL, 9, 0x3F);
        GL20.glStencilMaskSeparate(GL11.GL_BACK, 0x11);
        GL20.glStencilOpSeparate(GL11.GL_BACK, GL11.GL_INCR, GL11.GL_INCR, GL11.GL_INCR);
        GL11.glClearStencil(0);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._disableBlend();
        GlStateManager._enableCull();
    }

    // ------------------------------------------------------------------ one round trip

    private static void scenario(int entryUnit, boolean stencilEnabled, boolean baseBound) {
        int[] textures = new int[4];
        GL11.glGenTextures(textures);
        try {
            drainGlErrors();
            // ---- the incoming state, as a surrounding renderer would leave it
            for (int i = 0; i < ENTITY_UNITS; i++) {
                activate(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, i == 0 && !baseBound ? 0 : textures[i]);
            }
            activate(GL13.GL_TEXTURE0 + entryUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,
                    entryUnit < ENTITY_UNITS ? (entryUnit == 0 && !baseBound ? 0 : textures[entryUnit]) : textures[3]);
            for (int i = 0; i < SAMPLERS; i++) {
                RenderSystem.setShaderTexture(i, 100 + i);
            }
            // Front through the cache (so GlStateManager holds the front values) ...
            GlStateManager._stencilFunc(GL11.GL_EQUAL, 3, 0x55);
            GlStateManager._stencilMask(0x33);
            GlStateManager._stencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_INCR);
            // ... then the BACK face alone is moved with raw calls, so the two faces differ and the
            // capture has to read the back face (the four ported restores exist for this).
            GL20.glStencilFuncSeparate(GL11.GL_BACK, GL11.GL_NOTEQUAL, 4, 0x7F);
            GL20.glStencilMaskSeparate(GL11.GL_BACK, 0x77);
            GL20.glStencilOpSeparate(GL11.GL_BACK, GL11.GL_ZERO, GL11.GL_DECR, GL11.GL_KEEP);
            GL11.glClearStencil(5);
            if (stencilEnabled) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
            } else {
                GL11.glDisable(GL11.GL_STENCIL_TEST);
            }
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._enableBlend();
            GlStateManager._disableCull();
            State before = State.capture();
            equal("the entry unit is the one under test", GL13.GL_TEXTURE0 + entryUnit, before.active);

            // ---- the guard, exactly as the renderers use it
            RenderStateGuard guard = RenderStateGuard.snapshot("live scenario");
            assertSame("the snapshot itself must not change the state", before, State.capture());
            try {
                perturb(entryUnit, textures);
                RenderStateGuard.forceAlwaysPassStencil();
                equal("forceAlwaysPassStencil enables the stencil test", 1,
                        GL11.glIsEnabled(GL11.GL_STENCIL_TEST) ? 1 : 0);
                equal("forceAlwaysPassStencil sets the FRONT func", GL11.GL_ALWAYS,
                        GL11.glGetInteger(GL11.GL_STENCIL_FUNC));
                equal("forceAlwaysPassStencil sets the BACK func", GL11.GL_ALWAYS,
                        GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC));
                equal("forceAlwaysPassStencil sets ref 0", 0, GL11.glGetInteger(GL11.GL_STENCIL_REF));
                equal("forceAlwaysPassStencil sets value mask 0xFF", 0xFF,
                        GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK));
            } finally {
                guard.restore();
            }

            // ---- every value the guard promised, read back off the driver
            State after = State.capture();
            assertSame("restore puts everything back", before, after);
            equal("the cached active unit follows the driver", GL13.GL_TEXTURE0 + entryUnit,
                    GlStateManager._getActiveTexture());
            equal("the stencil clear value is back", 5, after.clear);
            equal("the FRONT stencil func is back", GL11.GL_EQUAL, after.front[0]);
            equal("the BACK stencil func is back", GL11.GL_NOTEQUAL, after.back[0]);
            equal("the BACK value mask is back", 0x7F, after.back[2]);
            equal("the BACK write mask is back", 0x77, after.back[3]);
            if (entryUnit >= ENTITY_UNITS) {
                equal("the shader-owned unit " + entryUnit + " keeps its binding", textures[3], after.activeBinding);
            }
            for (int i = 0; i < SAMPLERS; i++) {
                equal("RenderSystem sampler " + i + " is back", 100 + i, after.samplers[i]);
            }
            equal("GL_NO_ERROR after restore", GL11.GL_NO_ERROR, GL11.glGetError());

            // ---- idempotent: enter and leave again, changing nothing
            RenderStateGuard again = RenderStateGuard.snapshot("live scenario again");
            again.restore();
            assertSame("a second enter/leave is a no-op", before, State.capture());

            // ---- nested: the inner guard restores ITS entry, not the outermost one's
            RenderStateGuard outer = RenderStateGuard.snapshot("live outer");
            perturb(entryUnit, textures);
            // The inner guard's entry has the driver and the cache agreeing on a unit that is not the
            // outer guard's. That is what a renderer using the cached API leaves behind, and it is the
            // state the inner restore has to reproduce (a mid-leak state where cache and driver disagree
            // could not be reproduced by a guard whose contract is "leave them agreeing").
            activate(GL13.GL_TEXTURE0 + 3);
            State between = State.capture();
            RenderStateGuard inner = RenderStateGuard.snapshot("live inner");
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[3]);
            RenderSystem.setShaderTexture(0, 7);
            GlStateManager._stencilFunc(GL11.GL_ALWAYS, 1, 0x0F);
            GL11.glClearStencil(1);
            inner.restore();
            assertSame("the inner guard restores the outer guard's entry", between, State.capture());
            outer.restore();
            assertSame("the outer guard restores the original entry", before, State.capture());
            equal("the cached active unit is back after nesting", GL13.GL_TEXTURE0 + entryUnit,
                    GlStateManager._getActiveTexture());

            // ---- resyncTextureBinding: the raw binding is made to match the cached sampler again
            activate(GL13.GL_TEXTURE0);
            RenderSystem.setShaderTexture(0, textures[2]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[3]);
            RenderStateGuard.resyncTextureBinding();
            equal("resyncTextureBinding binds the cached id on the driver", textures[2],
                    GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            equal("GL_NO_ERROR after resync", GL11.GL_NO_ERROR, GL11.glGetError());

            scenarios++;
            System.out.println("PASS scenario " + scenarios + " entryUnit=" + entryUnit
                    + " stencilTest=" + stencilEnabled + " baseBound=" + baseBound + ": " + after.describe());
        } finally {
            for (int texture : textures) {
                GlStateManager._deleteTexture(texture);
            }
        }
    }

    // ------------------------------------------------------------------ runner

    private static void skip(String why) {
        System.out.println("SKIP  RenderStateGuardLiveTest: " + why
                + " - not a failure, and not a pass either");
        System.exit(EXIT_SKIP);
    }

    /**
     * Drops the JVM's stderr for this run. LWJGL 3.3.1 prints "[LWJGL] [ThreadLocalUtil] Unsupported JNI
     * version detected..." straight to {@code System.err}; it is harmless, but on Windows PowerShell 5.1 a
     * native command that writes to stderr while its streams are merged and
     * {@code $ErrorActionPreference = 'Stop'} raises a NativeCommandError and can abort the suite. This
     * gate speaks through stdout and its exit code, and a SKIP or FAIL line still carries the reason.
     * (Minecraft's log4j2 config from client-extra.jar also emits one "Advanced terminal features" status
     * line; that one is on the runner's stdout and is left alone.)
     */
    private static void silenceNativeStderr() {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    }

    public static void main(String[] args) {
        silenceNativeStderr();
        long window = 0;
        boolean glfwUp = false;
        try {
            try {
                glfwUp = GLFW.glfwInit();
            } catch (Throwable noGlfw) {
                skip("GLFW could not be initialised here (" + noGlfw + ")");
            }
            if (!glfwUp) {
                skip("GLFW.glfwInit() returned false (no display, or no GLFW natives on the classpath)");
            }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            window = GLFW.glfwCreateWindow(32, 32, "RenderStateGuard live check", 0, 0);
            if (window == 0) {
                skip("GLFW could not create a hidden window (no usable GL context here)");
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
        } catch (Throwable noContext) {
            window = 0;
            skip("no usable GL context here (" + noContext + ")");
        }
        System.out.println("RenderStateGuard live GL: context on " + GL11.glGetString(GL11.GL_RENDERER)
                + ", GL " + GL11.glGetString(GL11.GL_VERSION)
                + ", GlStateManager texture cache slots=" + GlStateManager.TEXTURE_COUNT);
        try {
            for (int entryUnit : ENTRY_UNITS) {
                for (boolean stencilEnabled : new boolean[]{false, true}) {
                    for (boolean baseBound : new boolean[]{false, true}) {
                        scenario(entryUnit, stencilEnabled, baseBound);
                    }
                }
            }
        } catch (AssertionError failure) {
            System.out.println("FAIL  RenderStateGuardLiveTest: " + failure.getMessage());
            System.exit(1);
        } catch (Throwable crash) {
            crash.printStackTrace();
            System.out.println("FAIL  RenderStateGuardLiveTest: " + crash);
            System.exit(1);
        } finally {
            if (window != 0) {
                GLFW.glfwDestroyWindow(window);
            }
            if (glfwUp) {
                GLFW.glfwTerminate();
            }
        }
        System.out.println("RenderStateGuard live GL: " + scenarios + " scenario(s) round-tripped,"
                + " both stencil faces and every sampler/unit identical to entry");
    }
}
