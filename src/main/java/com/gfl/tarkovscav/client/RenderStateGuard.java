package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.Locale;

/**
 * Saves and restores the GL state we share with everybody else, and forces the stencil test to
 * always-pass before our geometry is drawn.
 *
 * <h2>Why this exists: a measured leak in TaCZ, not a guess</h2>
 * <p>{@code com.tacz.guns.client.model.BedrockGunModel} (TaCZ 1.1.8, read with {@code javap -c -p}, see
 * {@code tools/gl_state_audit.js} for the decoded constants) manipulates the stencil buffer while it
 * draws a gun:</p>
 * <pre>
 *   render:                        stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
 *                                  RenderHelper.disableItemEntityStencilTest()   -> raw GL11.glDisable(2960)
 *                                  clearStencil(0)
 *                                  RenderSystem.clear(GL_STENCIL_BUFFER_BIT, ...)   &lt;- mid-frame clear
 *   lambda$render$28 / $renderAccelerated$29 (x2 each):
 *                                  RenderHelper.enableItemEntityStencilTest()   -> raw GL11.glEnable(2960)
 *                                  stencilFunc(GL_LEQUAL, 127, 255)
 *                                  RenderHelper.enableItemEntityStencilTest()
 *                                  stencilFunc(GL_EQUAL, 0, 255)
 *                                  stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
 * </pre>
 * <p>Three things follow, and they explain the report exactly:</p>
 * <ol>
 *   <li><b>The func is never restored to {@code GL_ALWAYS}.</b> Whoever enables the stencil test next -
 *       through Minecraft's cached state - inherits {@code GL_EQUAL, ref 0}: only fragments whose
 *       stencil value is 0 survive, and everything else is discarded. On screen that is "part of this
 *       mob vanished and I can see the entity/terrain behind it", which is exactly what "it is using
 *       the other entity's texture" looks like. It also explains why only <em>one</em> mob in a group is
 *       affected: it depends on which mob's draw triggered the gun's stencil path and on what is drawn
 *       after it.</li>
 *   <li><b>The enable/disable calls are raw {@code GL11} calls</b>, i.e. they bypass
 *       {@code GlStateManager}/{@code RenderSystem} caching, so Minecraft's own idea of the stencil state
 *       is left wrong even when the driver's is right.</li>
 *   <li><b>The stencil buffer is cleared mid-frame</b> ({@code RenderSystem.clear(GL_STENCIL_BUFFER_BIT)}),
 *       which invalidates anything that had written stencil values before it.</li>
 * </ol>
 *
 * <p>We cannot fix TaCZ. We can guarantee that <b>our</b> draws neither inherit nor leave that state:
 * snapshot on the way in, force always-pass, and restore exactly what we found on the way out. Reads use
 * {@code glGetInteger} (no cache side effect) and every write that has a {@link GlStateManager} entry
 * goes through it, so Minecraft's cache stays coherent. Writes that have no cached entry point - the
 * stencil toggle, and the four restores listed below - are raw GL calls on purpose: the cached call is
 * skipped whenever the cache already holds the value it was asked for, and that is precisely the
 * disagreement a foreign renderer leaves behind when it changes the driver with a raw call.</p>
 *
 * <h2>Four more restores, ported from the older snapshot at EdDYON/tarkovscav</h2>
 * <p>An older, independent snapshot of this mod published at {@code https://github.com/EdDYON/tarkovscav}
 * keeps its own guard (vendored read-only under {@code _friendfix/tarkovscav-main/} and analysed in the
 * friend-fix analysis under {@code docs/}). Its overall design is not ours - it preserves the incoming
 * stencil test instead of forcing always-pass, and it has neither {@link #forceAlwaysPassStencil()} nor
 * {@link #rebindTexture(net.minecraft.resources.ResourceLocation)} - but four of its restore steps close
 * gaps that were measured against our own contract, for the same two reports this class exists for
 * ("part of the mob is missing and I see the entity behind it", and "the head/backpack is a block of
 * pure black"):</p>
 * <ol>
 *   <li><b>The back face's stencil state</b>, written with the face-specific
 *       {@code GL20.glStencilFuncSeparate/glStencilMaskSeparate/glStencilOpSeparate} calls. The
 *       non-separate calls Minecraft caches ({@link GlStateManager#_stencilFunc}, {@code _stencilMask},
 *       {@code _stencilOp}) write the front values to <em>both</em> faces, so a renderer or shader pack
 *       that changed only {@code GL_STENCIL_BACK_*} - or changed one face with a raw call the cache
 *       never saw - would come back with the wrong back-face test and keep discarding our fragments.</li>
 *   <li><b>{@code GL_STENCIL_CLEAR_VALUE}.</b> TaCZ clears the shared stencil buffer mid-frame after
 *       setting this to 0 ({@code clearStencil(0)} then {@code clear(GL_STENCIL_BUFFER_BIT)}); putting
 *       the value back stops the <em>next</em> clear from using TaCZ's value instead of ours.</li>
 *   <li><b>The active texture unit and the bindings of units 0..2</b> (base texture, overlay, lightmap).
 *       TaCZ binds textures with raw GL calls and can leave a different unit active, and restoring "the
 *       binding" without selecting its unit writes the wrong unit. Units are selected through
 *       {@code GL13.glActiveTexture} <em>and</em> {@code GlStateManager._activeTexture} for the same
 *       skipped-cached-call reason as above; the raw bind is unconditional for the same reason.</li>
 *   <li><b>All 12 {@code RenderSystem} shader samplers.</b> Restoring only sampler 0 (what
 *       {@link #resyncTextureBinding()} does), left 1..11 at whatever a foreign draw set, so a later
 *       shader pass sampled the wrong texture even though the raw 2D bindings were right.</li>
 * </ol>
 * <p>Restore order is part of the contract and is asserted by the live-GL gate
 * ({@code tools/spike/gl/RenderStateGuardLiveTest.java}, registered in {@code tools/spike/selftest.ps1}):
 * the samplers are cache-only writes, the entity-unit bindings come next, and the entry unit (with its
 * binding, selected last) ends the sequence, so the driver and Minecraft's cache both hold exactly what
 * they held on entry.</p>
 */
public final class RenderStateGuard {
    private static final int GL_ALWAYS = 519;
    private static final int GL_STENCIL_TEST = 2960;
    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_BLEND = 3042;
    private static final int GL_CULL_FACE = 2884;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    /** The units an entity draw owns: base texture, overlay, lightmap. */
    private static final int ENTITY_TEXTURE_UNITS = 3;
    /** RenderSystem's 1.20.1 sampler array; its size and GlStateManager's cache size are both 12. */
    private static final int SHADER_SAMPLERS = 12;

    private final boolean stencilTest;
    private final int stencilFunc;
    private final int stencilRef;
    private final int stencilValueMask;
    private final int stencilWriteMask;
    private final int stencilFail;
    private final int stencilDepthFail;
    private final int stencilDepthPass;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final boolean blend;
    private final boolean cull;
    private final int texture;
    /** The raw unit that was active on entry, as a {@code GL13.GL_TEXTURE0 + n} constant. */
    private final int activeTexture;
    /** The incoming bindings of the entity units (base, overlay, lightmap). */
    private final int[] entityTextureBindings = new int[ENTITY_TEXTURE_UNITS];
    /** The incoming value of every RenderSystem shader sampler. */
    private final int[] shaderTextures = new int[SHADER_SAMPLERS];
    /** The incoming stencil state of each face; only the front one has a GlStateManager cache slot. */
    private final StencilFace frontStencil;
    private final StencilFace backStencil;
    /** The incoming {@code GL_STENCIL_CLEAR_VALUE}. */
    private final int clearStencil;

    private final String where;

    private RenderStateGuard(String where) {
        this.where = where;
        this.stencilTest = GL11.glIsEnabled(GL_STENCIL_TEST);
        this.stencilFunc = GlStateManager._getInteger(GL11.GL_STENCIL_FUNC);
        this.stencilRef = GlStateManager._getInteger(GL11.GL_STENCIL_REF);
        this.stencilValueMask = GlStateManager._getInteger(GL11.GL_STENCIL_VALUE_MASK);
        this.stencilWriteMask = GlStateManager._getInteger(GL11.GL_STENCIL_WRITEMASK);
        this.stencilFail = GlStateManager._getInteger(GL11.GL_STENCIL_FAIL);
        this.stencilDepthFail = GlStateManager._getInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        this.stencilDepthPass = GlStateManager._getInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        // The seven values above were read through GlStateManager, which is a raw glGetInteger plus the
        // render-thread assertion, so they ARE the front face. Reuse them for the separate front restore
        // instead of reading the same state twice.
        this.frontStencil = new StencilFace(this.stencilFunc, this.stencilRef, this.stencilValueMask,
                this.stencilWriteMask, this.stencilFail, this.stencilDepthFail, this.stencilDepthPass);
        this.backStencil = StencilFace.captureBack();
        this.clearStencil = GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
        this.depthTest = GL11.glIsEnabled(GL_DEPTH_TEST);
        this.depthWrite = GlStateManager._getInteger(GL11.GL_DEPTH_WRITEMASK) != 0;
        this.blend = GL11.glIsEnabled(GL_BLEND);
        this.cull = GL11.glIsEnabled(GL_CULL_FACE);
        this.texture = GlStateManager._getInteger(GL_TEXTURE_BINDING_2D);
        // The unit the binding above belongs to, then the three entity units. Reading a unit means
        // selecting it, so the entry unit is put back in a finally: a snapshot must not change anything
        // by itself, which is the first half of the idempotency the live-GL gate asserts.
        this.activeTexture = GlStateManager._getInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            for (int i = 0; i < this.entityTextureBindings.length; i++) {
                activateTexture(GL13.GL_TEXTURE0 + i);
                this.entityTextureBindings[i] = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            }
        } finally {
            activateTexture(this.activeTexture);
        }
        for (int i = 0; i < this.shaderTextures.length; i++) {
            this.shaderTextures[i] = RenderSystem.getShaderTexture(i);
        }
    }

    /** Reads the shared GL state and remembers it. Always pair with {@link #restore()} in a finally. */
    public static RenderStateGuard snapshot(String where) {
        return new RenderStateGuard(where);
    }

    /**
     * Makes the stencil test pass everywhere, so nothing another mod left behind can cull our geometry.
     * Kept enabled (with the always-pass func) rather than disabled, because that is the state a
     * subsequent {@code RenderType} setup expects to find.
     */
    public static void forceAlwaysPassStencil() {
        // 1.20.1 has no GlStateManager/RenderSystem API for toggling the stencil TEST itself (only
        // stencilFunc/Mask/Op, which are cached) - so the toggle has to be the raw call, exactly as
        // TaCZ's RenderHelper does it. Nothing in Minecraft caches this flag, so there is no cache to
        // desync; the func, which IS cached, is set through GlStateManager below.
        GL11.glEnable(GL_STENCIL_TEST);
        GlStateManager._stencilFunc(GL_ALWAYS, 0, 0xFF);
    }

    /** Puts everything back the way we found it, and reports a mismatch when asked to. */
    public void restore() {
        if (Config.logGlState()) {
            int func = GlStateManager._getInteger(GL11.GL_STENCIL_FUNC);
            int ref = GlStateManager._getInteger(GL11.GL_STENCIL_REF);
            int mask = GlStateManager._getInteger(GL11.GL_STENCIL_VALUE_MASK);
            boolean test = GL11.glIsEnabled(GL_STENCIL_TEST);
            TarkovScav.LOGGER.info("[gldebug] {} end: stencilTest={} func={} ref={} mask={} tex={}",
                    this.where, test, func, ref, mask, GlStateManager._getInteger(GL_TEXTURE_BINDING_2D));
            if (func != this.stencilFunc || ref != this.stencilRef || mask != this.stencilValueMask) {
                TarkovScav.LOGGER.warn("[gldebug] {} changed the stencil func while it ran:"
                                + " entry=({},{},{}) exit=({},{},{}) - this is the leak that makes"
                                + " geometry disappear (see README 5k)",
                        this.where, this.stencilFunc, this.stencilRef, this.stencilValueMask, func, ref, mask);
            }
        }
        if (this.stencilTest) {
            GL11.glEnable(GL_STENCIL_TEST);
        } else {
            GL11.glDisable(GL_STENCIL_TEST);
        }
        GlStateManager._stencilFunc(this.stencilFunc, this.stencilRef, this.stencilValueMask);
        GlStateManager._stencilMask(this.stencilWriteMask);
        GlStateManager._stencilOp(this.stencilFail, this.stencilDepthFail, this.stencilDepthPass);
        // ---------------------------------------------------------------------------------------------
        // BOTH FACES, EXPLICITLY (ported from the older EdDYON/tarkovscav snapshot - restore item 1).
        // The three cached calls above each skip their GL call when Minecraft's cache already holds the
        // value they were handed, and they write the front values to both faces. A renderer or shader
        // pack that changed one face with a raw call therefore survives them. These GL20 calls are
        // unconditional and per-face, so the driver ends at the entry state regardless of what the cache
        // believes. The back face is the one that matters for the leak: left at TaCZ's GL_EQUAL/0 it
        // keeps discarding our fragments ("part of the mob is missing").
        // ---------------------------------------------------------------------------------------------
        this.frontStencil.restore(GL11.GL_FRONT);
        this.backStencil.restore(GL11.GL_BACK);
        // GL_STENCIL_CLEAR_VALUE (ported - restore item 2): TaCZ clears the shared stencil buffer
        // mid-frame after setting this to 0, so without this the next clear uses TaCZ's value.
        GL11.glClearStencil(this.clearStencil);
        if (this.depthWrite) {
            GlStateManager._depthMask(true);
        } else {
            GlStateManager._depthMask(false);
        }
        if (this.depthTest) {
            GlStateManager._enableDepthTest();
        } else {
            GlStateManager._disableDepthTest();
        }
        if (this.blend) {
            GlStateManager._enableBlend();
        } else {
            GlStateManager._disableBlend();
        }
        if (this.cull) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }
        // ---------------------------------------------------------------------------------------------
        // THE SHADER SAMPLERS, THE ENTITY UNITS AND THE ENTRY UNIT (ported from the older
        // EdDYON/tarkovscav snapshot - restore items 3 and 4). Order is the contract: the samplers are
        // cache-only writes with no raw GL behind them, the three entity units (base, overlay, lightmap)
        // are cache+raw, and the entry unit is selected last so the active unit ends where it started.
        // ---------------------------------------------------------------------------------------------
        for (int i = 0; i < this.shaderTextures.length; i++) {
            RenderSystem.setShaderTexture(i, this.shaderTextures[i]);
        }
        for (int i = 0; i < this.entityTextureBindings.length; i++) {
            restoreUnitBinding(GL13.GL_TEXTURE0 + i, this.entityTextureBindings[i]);
        }
        // ---------------------------------------------------------------------------------------------
        // THE TEXTURE. It was captured at entry and then only ever logged, which is exactly the hole that
        // made a mob's head, backpack and legs render as a block of PURE BLACK (README 5w): TaCZ's gun
        // renderer binds textures with raw GlStateManager calls, so after the item draw the raw GL binding
        // and Minecraft's own texture cache disagree. The next RenderType#setupRenderState then sees its
        // cache saying "my texture is already bound" and SKIPS the bind, so the following geometry samples
        // texture 0 - black, with cutout edges, and only for the bones drawn after the gun anchor.
        //
        // Three things, because the two owners of this state have to be made to agree again:
        //   1. the unit the binding belongs to is selected first (a foreign renderer may have moved it, and
        //      an unselected unit would take the bind meant for this one);
        //   2. the cache gets the id that was bound when we entered (skipped when the unit has no cache
        //      slot at all - a shader pack can leave units outside the 12 GlStateManager tracks);
        //   3. raw GL gets the same id unconditionally, because _bindTexture above skips its GL call when
        //      the cache already holds it - the exact raw/cache disagreement this restore repairs.
        // ---------------------------------------------------------------------------------------------
        activateTexture(this.activeTexture);
        if (cacheHasSlot(this.activeTexture)) {
            GlStateManager._bindTexture(this.texture);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
        if (Config.logGlState()) {
            TarkovScav.LOGGER.info("[gldebug] {} restored texture {} -> {}",
                    this.where, this.texture, GlStateManager._getInteger(GL_TEXTURE_BINDING_2D));
        }
    }

    /**
     * Selects a texture unit in the driver and in Minecraft's cache. Both calls are needed: the cached one
     * is skipped when its cache already holds the unit (a foreign renderer that called raw
     * {@code glActiveTexture} leaves exactly that disagreement behind), and the raw one has no cache to
     * update.
     */
    private static void activateTexture(int unit) {
        GlStateManager._activeTexture(unit);
        GL13.glActiveTexture(unit);
    }

    /** True when GlStateManager has a cache slot for this unit; its array is {@code TEXTURE_COUNT} long. */
    private static boolean cacheHasSlot(int unit) {
        int index = unit - GL13.GL_TEXTURE0;
        return index >= 0 && index < GlStateManager.TEXTURE_COUNT;
    }

    /**
     * Restores one unit's 2D binding through both owners of the state, selecting the unit first. The raw
     * bind is unconditional for the reason in {@link #activateTexture(int)}: a cache-aware bind is skipped
     * when the cache already holds the id, which is what a foreign raw bind leaves behind. The cache is
     * only written for units it has a slot for, so a shader-pack unit outside 0..11 cannot index past the
     * array.
     */
    private static void restoreUnitBinding(int unit, int texture) {
        activateTexture(unit);
        if (cacheHasSlot(unit)) {
            GlStateManager._bindTexture(texture);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    /**
     * Makes the raw GL texture binding and Minecraft's cached one the same value again.
     *
     * <p>Call it after any foreign draw that touches textures outside {@code RenderSystem} (TaCZ does). It
     * binds the id Minecraft <em>thinks</em> is current, so that a later
     * {@code RenderType#setupRenderState} which compares against its cache cannot conclude "already bound"
     * while the GPU is actually sampling something else - the mismatch that shows up as untextured black
     * geometry (README 5w).</p>
     */
    public static void resyncTextureBinding() {
        int cached = RenderSystem.getShaderTexture(0);
        int bound = GlStateManager._getInteger(GL_TEXTURE_BINDING_2D);
        if (cached != 0 && cached != bound) {
            GlStateManager._bindTexture(cached);
            if (Config.logGlState()) {
                TarkovScav.LOGGER.info("[gldebug] texture cache resync: raw {} -> cached {}", bound, cached);
            }
        }
    }

    /**
     * Puts the model's texture back on the shader after a foreign draw (README 5w). This is the explicit
     * rebind the per-bone item layer needs: {@code RenderSystem} is cache-aware, so this is a no-op when
     * nothing changed and a real bind when the foreign renderer desynced the cache. Kept in the guard so
     * that every GL/texture write in the client package still lives in exactly one file.
     */
    public static void rebindTexture(net.minecraft.resources.ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);
        resyncTextureBinding();
    }

    /**
     * One-line state dump for the log, used around the foreign (TaCZ) item draw. The four ported restores
     * each show up here too ({@code backFunc}, {@code clear}, {@code activeUnit}, {@code shaderTex0}), so
     * a log read in game can confirm they are put back and not just written.
     */
    public static String describe(String phase) {
        return String.format(Locale.ROOT,
                "[gldebug] %s: stencilTest=%s func=%d ref=%d valueMask=%d writeMask=%d op=(%d,%d,%d)"
                        + " backFunc=%d clear=%d depthTest=%s depthWrite=%s blend=%s cull=%s"
                        + " activeUnit=%d tex=%d shaderTex0=%d",
                phase, GL11.glIsEnabled(GL_STENCIL_TEST),
                GlStateManager._getInteger(GL11.GL_STENCIL_FUNC),
                GlStateManager._getInteger(GL11.GL_STENCIL_REF),
                GlStateManager._getInteger(GL11.GL_STENCIL_VALUE_MASK),
                GlStateManager._getInteger(GL11.GL_STENCIL_WRITEMASK),
                GlStateManager._getInteger(GL11.GL_STENCIL_FAIL),
                GlStateManager._getInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL),
                GlStateManager._getInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS),
                GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC),
                GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE),
                GL11.glIsEnabled(GL_DEPTH_TEST),
                GlStateManager._getInteger(GL11.GL_DEPTH_WRITEMASK) != 0,
                GL11.glIsEnabled(GL_BLEND), GL11.glIsEnabled(GL_CULL_FACE),
                GlStateManager._getInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0,
                GlStateManager._getInteger(GL_TEXTURE_BINDING_2D),
                RenderSystem.getShaderTexture(0));
    }

    /** The entry values, for the log line that shows what we are about to restore. */
    public String entryDescription() {
        return String.format(Locale.ROOT, "%s entered with stencilTest=%s func=%d ref=%d valueMask=%d"
                        + " backFunc=%d clear=%d activeUnit=%d tex=%d",
                this.where, this.stencilTest, this.stencilFunc, this.stencilRef, this.stencilValueMask,
                this.backStencil.func(), this.clearStencil, this.activeTexture - GL13.GL_TEXTURE0,
                this.texture);
    }

    /**
     * One stencil face's full state. Minecraft's cache only covers the front face, so the back values are
     * read and written with the raw face-specific GL20 calls (ported from the older EdDYON/tarkovscav
     * snapshot); the front instance is built from the values already read for the cached restore.
     */
    private record StencilFace(int func, int ref, int valueMask, int writeMask,
                               int fail, int depthFail, int depthPass) {
        private static StencilFace captureBack() {
            return new StencilFace(
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_REF),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_VALUE_MASK),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_FAIL),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL),
                    GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS));
        }

        /** Unconditional and per-face: the cached calls are skipped when their cache already matches. */
        private void restore(int face) {
            GL20.glStencilFuncSeparate(face, this.func, this.ref, this.valueMask);
            GL20.glStencilMaskSeparate(face, this.writeMask);
            GL20.glStencilOpSeparate(face, this.fail, this.depthFail, this.depthPass);
        }
    }
}
