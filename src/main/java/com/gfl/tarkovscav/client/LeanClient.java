package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.lean.LeanMath;
import com.gfl.tarkovscav.lean.LeanNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Player leaning / peeking (README 5s): hold the lean keys and the first-person camera slides (and rolls) out
 * to that side, the way a first-person shooter does it.
 *
 * <h2>The keys are Q and E, which the vanilla game also uses</h2>
 * <p>The user asked for Q/E, so both vanilla actions have to be suppressed while a lean key is held, or the
 * feature would look broken the first time somebody used it:</p>
 * <ul>
 *   <li><b>drop (Q)</b>: the click on {@code options.keyDrop} is consumed in {@link #onKey} the moment the
 *       key is pressed, so it never becomes an action (and never becomes a packet) - nothing is dropped and
 *       nothing has to be given back;</li>
 *   <li><b>inventory (E)</b>: the same treatment - the click on {@code key.inventory} is consumed, so the
 *       screen is never opened by the key at all. (An earlier version cancelled {@code ScreenEvent.Opening}
 *       instead; that also blocked anything else that opened the vanilla inventory, which is exactly what the
 *       2026-09-23 fix removed.)</li>
 * </ul>
 * <p>Both are behind {@code client.leanSuppressVanillaKeys}, and both only ever touch the vanilla keys that we
 * actually OCCUPY: rebinding a lean key away from Q/E gives that vanilla action back automatically. The server
 * has a second guard ({@code LeanServerEvents#onItemToss}) for any toss that still gets through.</p>
 *
 * <h2>The camera: an access transformer, not reflection</h2>
 * <p>The lateral move needs {@code Camera#setPosition(Vec3)}, which is {@code protected}. It used to be called
 * reflectively, which could fail silently on a real client - and a silently roll-only lean looks exactly like
 * "the lean does not move me sideways". It is now a direct call enabled by
 * {@code META-INF/accesstransformer.cfg} (declared in build.gradle), which is checked by the build (a protected
 * call would not compile) and by {@code tools/selftest_lean.js} (the shipped bytecode must contain the direct
 * {@code invokevirtual} and no reflection). If the transformer were ever missing at runtime the call throws,
 * which is caught, logged at ERROR level once, and reported as {@code cameraSlide=unavailable} in
 * {@code /tarkovscav client state} - a loud degradation rather than a silent one.</p>
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID,
        bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class LeanClient {
    public static final String CATEGORY = "key.categories.tarkovscav";
    /**
     * The lean keys (README 5t, 2026-09-23 fix): <b>Q leans RIGHT, E leans LEFT</b>. The names describe the
     * ACTION and the action is what the lean maths uses; the KEYS are the user's requirement, and until this
     * fix they were the other way round - the user reported "the q and e are reversed", so the mapping was
     * swapped and the maths (offset along the right vector, roll with the head) was left as it was.
     */
    public static final KeyMapping LEAN_LEFT = new KeyMapping("key.tarkovscav.lean_left",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, CATEGORY);
    public static final KeyMapping LEAN_RIGHT = new KeyMapping("key.tarkovscav.lean_right",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, CATEGORY);

    /** The ramped lean in [-1, 1]; positive leans to the player's right. */
    private static float lean;
    /** What the server was last told, so a steady lean costs no packets. */
    private static float sent;
    /** What the server was last told about "a lean key is held" (README 5t). */
    private static boolean sentHolding;
    /** Set once if the camera move ever fails, so the log says it once and client state can report it. */
    private static boolean cameraMoveUnavailable;
    /** Client ticks since the client started; the tap/long-press threshold is measured in these. */
    private static long clientTicks;
    /** When the vanilla keys were pressed (README 5t); -1 = not held. */
    private static long dropPressedAt = -1L;
    private static long inventoryPressedAt = -1L;
    /** When our own lean keys were pressed, for startMode = afterThreshold. */
    private static long leanLeftPressedAt = -1L;
    private static long leanRightPressedAt = -1L;

    private LeanClient() {
    }

    /** Mod bus: the two key bindings, registered next to the renderers. */
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(LEAN_LEFT);
        event.register(LEAN_RIGHT);
    }

    /** The current ramped lean, for the debug command and the gate. */
    public static float lean() {
        return lean;
    }

    /** True while the camera offset is actually being applied (the access transformer is in effect). */
    public static boolean cameraMoveWorks() {
        return !cameraMoveUnavailable;
    }

    /** How the camera move is implemented, for {@code /tarkovscav client state} (README 5t). */
    public static String cameraSlideMode() {
        return cameraMoveUnavailable ? "unavailable" : "at";
    }

    // ------------------------------------------------------------------ input

    /**
     * The suppression of the two vanilla keys we occupy (README 5t): every click on {@code key.drop} or
     * {@code key.inventory} is consumed the instant it is queued, <b>whether or not a lean key is held</b>, so
     * a SHORT tap of Q or E does nothing at all.
     *
     * <p>Two things keep this honest:</p>
     * <ul>
     *   <li>it compares against the <b>current bindings</b>, so the moment the player rebinds a lean key away
     *       from Q/E, that vanilla key works normally again - only the keys we actually occupy are taken;</li>
     *   <li>it consumes the vanilla {@link KeyMapping}'s clicks rather than cancelling a screen, so nothing
     *       else that opens the inventory (a command, another mod's key, a block interaction) is affected.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !suppressing()) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS) {
            onPress(minecraft, event.getKey());
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            onRelease(minecraft, event.getKey());
        }
    }

    /**
     * Press: <b>consume the vanilla click immediately</b> (README 5t), because the vanilla game acts on the
     * press and there is no way to know yet whether this is a tap or a peek. The decision is made on release.
     */
    private static void onPress(Minecraft minecraft, int key) {
        for (KeyMapping vanilla : occupiedVanillaKeys(minecraft)) {
            if (vanilla.getKey().getType() != InputConstants.Type.KEYSYM
                    || key != vanilla.getKey().getValue()) {
                continue;
            }
            int swallowed = 0;
            while (vanilla.consumeClick()) {
                swallowed++;
            }
            if (vanilla == minecraft.options.keyDrop) {
                dropPressedAt = clientTicks;
            } else {
                inventoryPressedAt = clientTicks;
            }
            if (swallowed > 0) {
                TarkovScav.LOGGER.debug("[lean] took the press of the vanilla key {} (occupied by a lean"
                        + " binding); the tap/long-press decision happens on release", vanilla.getName());
            }
        }
        KeyMapping leanLeft = LEAN_LEFT;
        KeyMapping leanRight = LEAN_RIGHT;
        if (leanLeft.getKey().getType() == InputConstants.Type.KEYSYM
                && key == leanLeft.getKey().getValue()) {
            leanLeftPressedAt = clientTicks;
        }
        if (leanRight.getKey().getType() == InputConstants.Type.KEYSYM
                && key == leanRight.getKey().getValue()) {
            leanRightPressedAt = clientTicks;
        }
    }

    /**
     * Release: this is where a tap becomes the vanilla action and a hold does not.
     *
     * <p>The order matters and is asserted by the gate: the "I am no longer holding" packet is sent
     * <b>first</b>, so the server's toss guard ({@code LeanServerEvents#onItemToss}) has already stopped
     * guarding by the time the replayed drop arrives - otherwise a tap of Q would be swallowed by our own
     * safety net. Messages on one connection are ordered, so this is deterministic.</p>
     */
    private static void onRelease(Minecraft minecraft, int key) {
        boolean releasedDrop = false;
        boolean releasedInventory = false;
        for (KeyMapping vanilla : occupiedVanillaKeys(minecraft)) {
            if (vanilla.getKey().getType() != InputConstants.Type.KEYSYM
                    || key != vanilla.getKey().getValue()) {
                continue;
            }
            if (vanilla == minecraft.options.keyDrop && dropPressedAt >= 0L) {
                dropPressedAt = -1L;
                releasedDrop = true;
            } else if (vanilla == minecraft.options.keyInventory && inventoryPressedAt >= 0L) {
                inventoryPressedAt = -1L;
                releasedInventory = true;
            }
        }
        if (LEAN_LEFT.getKey().getValue() == key) {
            leanLeftPressedAt = -1L;
        }
        if (LEAN_RIGHT.getKey().getValue() == key) {
            leanRightPressedAt = -1L;
        }
        if (!releasedDrop && !releasedInventory) {
            return;
        }
        // 1. tell the server we stopped holding (see the method comment), then
        // 2. replay the vanilla action if this was a tap.
        setLean(lean, true);
        if (!Config.SPEC.isLoaded() || !Config.LEAN_REPLAY_VANILLA_ON_TAP.get()) {
            return;
        }
        if (releasedInventory) {
            // The same call Minecraft#handleKeybinds makes for key.inventory.
            minecraft.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(
                    minecraft.player));
            TarkovScav.LOGGER.debug("[lean] tapped the inventory key: opening the backpack for you");
        }
        if (releasedDrop && !minecraft.player.isSpectator()) {
            // Exactly one drop, exactly once: this is the vanilla key's own action (Minecraft#handleKeybinds
            // calls player.drop(false) for key.drop).
            minecraft.player.drop(false);
            TarkovScav.LOGGER.debug("[lean] tapped the drop key: dropping one item for you");
        }
    }

    /** True while a lean key counts as "peeking" - which depends on startMode (README 5t). */
    private static boolean peeking(KeyMapping key, long pressedAt) {
        if (!key.isDown()) {
            return false;
        }
        if (startMode().equals("afterthreshold")) {
            return pressedAt >= 0L && clientTicks - pressedAt >= Config.LEAN_TAP_THRESHOLD_TICKS.get();
        }
        return true;
    }

    private static String startMode() {
        return Config.SPEC.isLoaded() ? Config.LEAN_START_MODE.get().toLowerCase(Locale.ROOT) : "immediate";
    }

    /**
     * The vanilla key mappings that share a key with one of our lean bindings. Empty when the player rebound
     * the lean keys elsewhere, which is exactly when vanilla Q/E must work again.
     */
    static java.util.List<KeyMapping> occupiedVanillaKeys(Minecraft minecraft) {
        java.util.List<KeyMapping> occupied = new java.util.ArrayList<>(2);
        for (KeyMapping leanKey : new KeyMapping[] { LEAN_LEFT, LEAN_RIGHT }) {
            int key = leanKey.getKey().getValue();
            if (leanKey.getKey().getType() != InputConstants.Type.KEYSYM
                    || key == InputConstants.UNKNOWN.getValue()) {
                continue;
            }
            for (KeyMapping vanilla : new KeyMapping[] { minecraft.options.keyDrop,
                minecraft.options.keyInventory }) {
                if (vanilla.getKey().getType() == InputConstants.Type.KEYSYM
                        && vanilla.getKey().getValue() == key && !occupied.contains(vanilla)) {
                    occupied.add(vanilla);
                }
            }
        }
        return occupied;
    }

    private static boolean suppressing() {
        return Config.SPEC.isLoaded() && Config.LEAN_ENABLED.get() && Config.LEAN_SUPPRESS_VANILLA_KEYS.get();
    }

    // ------------------------------------------------------------------ the ramp

    /** Client tick: read the keys, ramp the amount, tell the server when it changes. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // The kill feed ages on the same tick (README 5u), before its early returns: its lines must keep
        // fading even while a screen is open. Same for the flashbang white-out (README 5v).
        KillFeedHud.tick();
        FlashOverlay.tick();
        Minecraft minecraft = Minecraft.getInstance();
        if (!Config.SPEC.isLoaded() || !Config.LEAN_ENABLED.get() || minecraft.player == null
                || minecraft.screen != null) {
            // A screen takes the keys (E would otherwise lean while typing in chat), and a disabled feature
            // must not leave a stale lean behind.
            setLean(0.0F, false);
            return;
        }
        clientTicks++;
        boolean left = peeking(LEAN_LEFT, leanLeftPressedAt);
        boolean right = peeking(LEAN_RIGHT, leanRightPressedAt);
        // The server's toss guard asks "is a lean key held", not "is the lean non-zero": with
        // startMode = immediate a tap produces a brief lean, and the vanilla action we replay on release must
        // not be cancelled by our own guard (README 5t).
        boolean holding = LEAN_LEFT.isDown() || LEAN_RIGHT.isDown();
        if (holding != sentHolding) {
            sentHolding = holding;
            LeanNetwork.send(lean, holding);
        }
        // Both keys cancel each other out: that is the least surprising reading of "hold both".
        float target = left == right ? 0.0F : (right ? 1.0F : -1.0F);
        int speed = Math.max(1, Config.LEAN_SPEED_TICKS.get());
        float step = 1.0F / speed;
        float next = lean;
        if (next < target) {
            next = Math.min(target, next + step);
        } else if (next > target) {
            next = Math.max(target, next - step);
        }
        setLean(next, true);
    }

    private static void setLean(float value, boolean send) {
        lean = LeanMath.clamp(value);
        if (send && Math.abs(lean - sent) > 0.01F) {
            sent = lean;
            LeanNetwork.send(lean, sentHolding);
        } else if (!send && sent != 0.0F) {
            sent = 0.0F;
            sentHolding = false;
            LeanNetwork.send(0.0F, false);
        }
    }

    // ------------------------------------------------------------------ the camera

    /**
     * Forge fires this right after {@code Camera#setup} and before the level is drawn, so the roll set here
     * and the position moved here are what the frame uses.
     */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!Config.SPEC.isLoaded() || !Config.LEAN_ENABLED.get() || lean == 0.0F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        // The two signs, each with its own escape hatch (README 5t): the lean value is shared, the offset and
        // the roll apply it to their own axis, and leanInvertOffset / leanInvertRoll flip exactly one of them.
        float signed = lean * (Config.LEAN_INVERT_OFFSET.get() ? -1.0F : 1.0F);
        float rollLean = lean * (Config.LEAN_INVERT_ROLL.get() ? -1.0F : 1.0F);
        // Roll: leaning RIGHT (+lean) tilts the view the way a head tilt does, i.e. the horizon rolls the
        // opposite way from the movement. One character, one config key, one line in the README.
        event.setRoll(event.getRoll() - (float) (Config.LEAN_ROLL_DEGREES.get() * rollLean));
        Camera camera = event.getCamera();
        Vec3 wanted = LeanMath.offsetFor(event.getYaw(), signed, Config.LEAN_MAX_OFFSET.get());
        // From the CAMERA (not the eye): this works in first and third person, and it is what keeps the camera
        // out of the wall it is leaning towards.
        Vec3 offset = LeanMath.slide(minecraft.level, camera.getPosition(), wanted);
        if (offset.lengthSqr() < 1.0E-8D) {
            return;
        }
        moveCamera(camera, camera.getPosition().add(offset));
    }

    /**
     * The lateral camera move itself (README 5t).
     *
     * <p>{@code Camera#setPosition(Vec3)} is <b>protected</b> in vanilla, so this is a direct call made possible
     * by our access transformer ({@code META-INF/accesstransformer.cfg}, declared in build.gradle). That
     * replaced an earlier reflective call which could fail silently on a real client - and a silently
     * roll-only lean is exactly what "it does not move sideways" looks like. The direct call is verified two
     * ways: the build only compiles because the transformer made the method public in the dev classes, and the
     * gate checks the shipped bytecode for a direct {@code invokevirtual} and the absence of reflection.</p>
     *
     * <p>The {@code Throwable} catch is the last line of defence: if the transformer were ever not applied at
     * runtime, this becomes an {@code IllegalAccessError} - caught, reported at ERROR level once, and
     * {@code /tarkovscav client state} then says {@code cameraSlide=unavailable} instead of pretending.</p>
     */
    private static void moveCamera(Camera camera, Vec3 position) {
        if (cameraMoveUnavailable) {
            return;
        }
        try {
            camera.setPosition(position);
        } catch (Throwable failure) {
            cameraMoveUnavailable = true;
            TarkovScav.LOGGER.error("[lean] could not move the camera even though the access transformer"
                    + " should have made Camera#setPosition public ({}). The lean is roll-only for this session;"
                    + " report this line - it means the transformer was not applied.", failure.toString());
        }
    }

    /** One-line state for {@code /tarkovscav client state}. */
    public static String describe() {
        return String.format(Locale.ROOT, "lean=%.2f (left=%s right=%s) maxOffset=%.2f roll=%.1f speed=%dt"
                        + " invertOffset=%s invertRoll=%s tapThreshold=%dt startMode=%s replayOnTap=%s"
                        + " holding=%s suppressVanillaKeys=%s cameraSlide=%s",
                lean, LEAN_LEFT.isDown(), LEAN_RIGHT.isDown(),
                Config.SPEC.isLoaded() ? Config.LEAN_MAX_OFFSET.get() : 0.0D,
                Config.SPEC.isLoaded() ? Config.LEAN_ROLL_DEGREES.get() : 0.0D,
                Config.SPEC.isLoaded() ? Config.LEAN_SPEED_TICKS.get() : 0,
                Config.SPEC.isLoaded() && Config.LEAN_INVERT_OFFSET.get(),
                Config.SPEC.isLoaded() && Config.LEAN_INVERT_ROLL.get(),
                Config.SPEC.isLoaded() ? Config.LEAN_TAP_THRESHOLD_TICKS.get() : 0,
                startMode(),
                Config.SPEC.isLoaded() && Config.LEAN_REPLAY_VANILLA_ON_TAP.get(),
                LEAN_LEFT.isDown() || LEAN_RIGHT.isDown(),
                suppressing(), cameraSlideMode());
    }
}
