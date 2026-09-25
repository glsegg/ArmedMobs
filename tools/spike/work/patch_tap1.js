// scratch: apply the tap/long-press lean semantics to the Java sources (absolute paths)
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/src/main/java/com/gfl/tarkovscav/';

// ---------------------------------------------------------------- Config ---------------------------------
{
  const file = R + 'Config.java';
  let text = fs.readFileSync(file, 'utf8');
  const before = text;
  text = text.replace(
    '    public static final ForgeConfigSpec.BooleanValue LEAN_SUPPRESS_VANILLA_KEYS;',
    '    public static final ForgeConfigSpec.BooleanValue LEAN_SUPPRESS_VANILLA_KEYS;\n'
    + '    public static final ForgeConfigSpec.IntValue LEAN_TAP_THRESHOLD_TICKS;\n'
    + '    public static final ForgeConfigSpec.ConfigValue<String> LEAN_START_MODE;\n'
    + '    public static final ForgeConfigSpec.BooleanValue LEAN_REPLAY_VANILLA_ON_TAP;');
  text = text.replace(`                .define("leanSuppressVanillaKeys", true);
        b.pop();`,
    `                .define("leanSuppressVanillaKeys", true);
        LEAN_TAP_THRESHOLD_TICKS = b
                .comment("How long Q/E has to be HELD before it counts as a peek instead of a tap, in ticks",
                        "(5 = 250 ms). Below it the press is replayed as the vanilla action on release; at or",
                        "above it the vanilla action is dropped and the key was a lean.")
                .defineInRange("tapThresholdTicks", 5, 1, 40);
        LEAN_START_MODE = b
                .comment("When the lean starts:",
                        "  immediate     - on press, so it feels instant (a quick tap shows a very short lean",
                        "                  flash, which snaps back the moment you let go);",
                        "  afterThreshold - only once the key has been held for tapThresholdTicks, so a tap",
                        "                  never leans at all.")
                .define("startMode", "immediate");
        LEAN_REPLAY_VANILLA_ON_TAP = b
                .comment("Whether a TAP (shorter than tapThresholdTicks) still does the vanilla action, done by",
                        "us on release: E opens the backpack, Q drops one item. false = a tap does nothing at",
                        "all (the previous behaviour). Either way the vanilla key itself is consumed, so the",
                        "action can never happen twice or at the wrong time.")
                .define("replayVanillaOnTap", true);
        b.pop();`);
  fs.writeFileSync(file, text);
  console.log('Config changed:', before !== text);
}

// ---------------------------------------------------------------- LeanState ------------------------------
{
  const file = R + 'lean/LeanState.java';
  let text = fs.readFileSync(file, 'utf8');
  const before = text;
  text = text.replace('    private static final Map<UUID, Float> LEAN = new ConcurrentHashMap<>();',
    '    private static final Map<UUID, Float> LEAN = new ConcurrentHashMap<>();\n'
    + '    /** Players who are holding a lean key right now (the item-toss guard asks this, not the amount). */\n'
    + '    private static final java.util.Set<UUID> HOLDING = ConcurrentHashMap.newKeySet();');
  text = text.replace(`    /** The synced lean amount, or 0 when the player is not leaning (including "never sent anything"). */`,
    `    /** Records whether the player is holding a lean key (README 5t): the toss guard's only question. */
    public static void setHolding(UUID player, boolean holding) {
        if (holding) {
            HOLDING.add(player);
        } else {
            HOLDING.remove(player);
        }
    }

    /** True while the player is holding a lean key. A tap releases it, so a replayed drop is not cancelled. */
    public static boolean holding(@Nullable Player player) {
        return player != null && HOLDING.contains(player.getUUID());
    }

    /** The synced lean amount, or 0 when the player is not leaning (including "never sent anything"). */`);
  text = text.replace(`    public static void clear(UUID player) {
        LEAN.remove(player);
    }`,
    `    public static void clear(UUID player) {
        LEAN.remove(player);
        HOLDING.remove(player);
    }`);
  fs.writeFileSync(file, text);
  console.log('LeanState changed:', before !== text);
}

// ---------------------------------------------------------------- LeanNetwork ----------------------------
{
  const file = R + 'lean/LeanNetwork.java';
  let text = fs.readFileSync(file, 'utf8');
  const before = text;
  text = text.replace('private static final String VERSION = "1";', 'private static final String VERSION = "2";');
  text = text.replace(`    /** Client only: tells the server the current lean amount. */
    public static void send(float lean) {
        CHANNEL.sendToServer(new ServerboundLean(LeanMath.clamp(lean)));
    }`,
    `    /**
     * Client only: tells the server the current lean amount AND whether a lean key is being held.
     *
     * <p>The second field is what the item-toss guard reads (README 5t). It has to be a separate question from
     * the amount: with {@code startMode = immediate} a tap produces a brief non-zero lean, and the guard must
     * not swallow the vanilla action we replay on release.</p>
     */
    public static void send(float lean, boolean holding) {
        CHANNEL.sendToServer(new ServerboundLean(LeanMath.clamp(lean), holding));
    }`);
  text = text.replace(`    public record ServerboundLean(float lean) {
        static void encode(ServerboundLean message, FriendlyByteBuf buffer) {
            buffer.writeFloat(message.lean());
        }

        static ServerboundLean decode(FriendlyByteBuf buffer) {
            return new ServerboundLean(buffer.readFloat());
        }`,
    `    public record ServerboundLean(float lean, boolean holding) {
        static void encode(ServerboundLean message, FriendlyByteBuf buffer) {
            buffer.writeFloat(message.lean());
            buffer.writeBoolean(message.holding());
        }

        static ServerboundLean decode(FriendlyByteBuf buffer) {
            return new ServerboundLean(buffer.readFloat(), buffer.readBoolean());
        }`);
  text = text.replace(`                    LeanState.set(player.getUUID(), message.lean());`,
    `                    LeanState.set(player.getUUID(), message.lean());
                    LeanState.setHolding(player.getUUID(), message.holding());`);
  fs.writeFileSync(file, text);
  console.log('LeanNetwork changed:', before !== text);
}

// ---------------------------------------------------------------- LeanServerEvents -----------------------
{
  const file = R + 'lean/LeanServerEvents.java';
  let text = fs.readFileSync(file, 'utf8');
  const before = text;
  text = text.replace(`        Player player = event.getPlayer();
        if (LeanState.leanOf(player) == 0.0F) {
            return;
        }`,
    `        Player player = event.getPlayer();
        // HOLDING, not the lean amount (README 5t): a tap releases the key, so the vanilla drop we replay on
        // release arrives while holding == false and is never cancelled. A toss that happens WHILE the key is
        // held is an accident (or a leftover) and gets cancelled and given back.
        if (!LeanState.holding(player)) {
            return;
        }`);
  fs.writeFileSync(file, text);
  console.log('LeanServerEvents changed:', before !== text);
}

// ---------------------------------------------------------------- LeanClient -----------------------------
{
  const file = R + 'client/LeanClient.java';
  let text = fs.readFileSync(file, 'utf8');
  const before = text;
  text = text.replace(`    /** Set once if the camera move ever fails, so the log says it once and client state can report it. */
    private static boolean cameraMoveUnavailable;`,
    `    /** Set once if the camera move ever fails, so the log says it once and client state can report it. */
    private static boolean cameraMoveUnavailable;
    /** Client ticks since the client started; the tap/long-press threshold is measured in these. */
    private static long clientTicks;
    /** When the vanilla keys were pressed (README 5t); -1 = not held. */
    private static long dropPressedAt = -1L;
    private static long inventoryPressedAt = -1L;
    /** When our own lean keys were pressed, for startMode = afterThreshold. */
    private static long leanLeftPressedAt = -1L;
    private static long leanRightPressedAt = -1L;`);
  fs.writeFileSync(file, text);
  console.log('LeanClient fields:', before !== text);
}
