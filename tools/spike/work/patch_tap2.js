// scratch: tap semantics part 2 - LeanClient's press/release handling, start mode and the tick
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/src/main/java/com/gfl/tarkovscav/client/LeanClient.java';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 70));
    return;
  }
  text = text.replace(from, to);
};

// --- the key handler: consume on press, decide on release -----------------------------------------
sub(`    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !suppressing()) {
            return;
        }
        for (KeyMapping vanilla : occupiedVanillaKeys(minecraft)) {
            if (vanilla.getKey().getType() != InputConstants.Type.KEYSYM
                    || event.getKey() != vanilla.getKey().getValue()) {
                continue;
            }
            // Consume every queued click (a key repeat can queue more than one) and take no action.
            int swallowed = 0;
            while (vanilla.consumeClick()) {
                swallowed++;
            }
            if (swallowed > 0) {
                TarkovScav.LOGGER.debug("[lean] swallowed {} click(s) of the vanilla key {} (occupied by a"
                        + " lean binding)", swallowed, vanilla.getName());
            }
        }
    }`,
  `    @SubscribeEvent
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
    }`);

// --- the tick: use peeking(), and keep the server told about "holding" ----------------------------
sub(`        boolean left = LEAN_LEFT.isDown();
        boolean right = LEAN_RIGHT.isDown();`,
  `        clientTicks++;
        boolean left = peeking(LEAN_LEFT, leanLeftPressedAt);
        boolean right = peeking(LEAN_RIGHT, leanRightPressedAt);
        // The server's toss guard asks "is a lean key held", not "is the lean non-zero": with
        // startMode = immediate a tap produces a brief lean, and the vanilla action we replay on release must
        // not be cancelled by our own guard (README 5t).
        boolean holding = LEAN_LEFT.isDown() || LEAN_RIGHT.isDown();
        if (holding != sentHolding) {
            sentHolding = holding;
            LeanNetwork.send(lean, holding);
        }`);

// --- setLean: carry the holding flag --------------------------------------------------------------
sub(`    private static void setLean(float value, boolean send) {
        lean = LeanMath.clamp(value);
        if (send && Math.abs(lean - sent) > 0.01F) {
            sent = lean;
            LeanNetwork.send(lean);
        } else if (!send && sent != 0.0F) {
            sent = 0.0F;
            LeanNetwork.send(0.0F);
        }
    }`,
  `    private static void setLean(float value, boolean send) {
        lean = LeanMath.clamp(value);
        if (send && Math.abs(lean - sent) > 0.01F) {
            sent = lean;
            LeanNetwork.send(lean, sentHolding);
        } else if (!send && sent != 0.0F) {
            sent = 0.0F;
            sentHolding = false;
            LeanNetwork.send(0.0F, false);
        }
    }`);

sub(`    /** What the server was last told, so a steady lean costs no packets. */
    private static float sent;`,
  `    /** What the server was last told, so a steady lean costs no packets. */
    private static float sent;
    /** What the server was last told about "a lean key is held" (README 5t). */
    private static boolean sentHolding;`);

// --- describe(): the new fields -------------------------------------------------------------------
sub(`        return String.format(Locale.ROOT, "lean=%.2f (left=%s right=%s) maxOffset=%.2f roll=%.1f speed=%dt"
                        + " invertOffset=%s invertRoll=%s suppressVanillaKeys=%s cameraSlide=%s",
                lean, LEAN_LEFT.isDown(), LEAN_RIGHT.isDown(),
                Config.SPEC.isLoaded() ? Config.LEAN_MAX_OFFSET.get() : 0.0D,
                Config.SPEC.isLoaded() ? Config.LEAN_ROLL_DEGREES.get() : 0.0D,
                Config.SPEC.isLoaded() ? Config.LEAN_SPEED_TICKS.get() : 0,
                Config.SPEC.isLoaded() && Config.LEAN_INVERT_OFFSET.get(),
                Config.SPEC.isLoaded() && Config.LEAN_INVERT_ROLL.get(),
                suppressing(), cameraSlideMode());`,
  `        return String.format(Locale.ROOT, "lean=%.2f (left=%s right=%s) maxOffset=%.2f roll=%.1f speed=%dt"
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
                suppressing(), cameraSlideMode());`);

fs.writeFileSync(file, text);
console.log('changed:', before !== text);
