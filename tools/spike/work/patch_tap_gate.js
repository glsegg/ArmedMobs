// scratch: lean gate - the tap/long-press semantics (README 5t, 2026-09-23 second revision)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_lean.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 70));
    return;
  }
  text = text.replace(from, to);
};

// Replace the whole "short tap is swallowed" block with the tap/long-press contract.
sub(`check(/if \\(minecraft\\.player == null \\|\\| !suppressing\\(\\)\\)/.test(client)
  && !/lean == 0\\.0F\\) \\{\\s*return;\\s*\\}\\s*KeyMapping/m.test(client),
  'and the consumption does NOT depend on a lean being active: a SHORT TAP of Q/E is swallowed too');`,
  `check(/private static void onPress\\(Minecraft minecraft, int key\\)/.test(client)
  && /while \\(vanilla\\.consumeClick\\(\\)\\)/.test(client)
  && /dropPressedAt = clientTicks;/.test(client) && /inventoryPressedAt = clientTicks;/.test(client),
  'the press is taken and TIMED: the vanilla click is consumed so the game can never act on it by itself');
check(/private static void onRelease\\(Minecraft minecraft, int key\\)/.test(client),
  'and the tap/long-press decision is made on release');
check(/if \\(releasedInventory\\) \\{[\\s\\S]{0,200}?setScreen\\(new net\\.minecraft\\.client\\.gui\\.screens\\.inventory\\.InventoryScreen\\(/.test(client),
  'a TAP of the inventory key opens the backpack for the player (the vanilla action, replayed by us)');
check(/if \\(releasedDrop && !minecraft\\.player\\.isSpectator\\(\\)\\) \\{[\\s\\S]{0,200}?minecraft\\.player\\.drop\\(false\\);/
  .test(client),
  'a TAP of the drop key drops exactly one item (the vanilla action, replayed by us)');
check(/if \\(!Config\\.SPEC\\.isLoaded\\(\\) \\|\\| !Config\\.LEAN_REPLAY_VANILLA_ON_TAP\\.get\\(\\)\\) \\{\\s*return;/
  .test(client),
  'replayVanillaOnTap = false turns a tap into "nothing happens"');
check(!/while \\(vanilla\\.consumeClick\\(\\)\\)[\\s\\S]{0,400}?player\\.drop\\(false\\)/.test(
  client.slice(client.indexOf('private static void onPress'), client.indexOf('private static void onRelease'))),
  'and the drop is NOT replayed on press (that is what would drop twice)');
check(/private static boolean peeking\\(KeyMapping key, long pressedAt\\)/.test(client)
  && /return pressedAt >= 0L && clientTicks - pressedAt >= Config\\.LEAN_TAP_THRESHOLD_TICKS\\.get\\(\\);/.test(client)
  && /if \\(startMode\\(\\)\\.equals\\("afterthreshold"\\)\\)/.test(client),
  'startMode = afterThreshold only starts leaning at the threshold; immediate starts on press (the else path)');
check(/boolean holding = LEAN_LEFT\\.isDown\\(\\) \\|\\| LEAN_RIGHT\\.isDown\\(\\);/.test(client)
  && /LeanNetwork\\.send\\(lean, holding\\)/.test(client),
  'the server is told "a lean key is held", separately from the lean amount');
check(/setLean\\(lean, true\\);\\s*\\n\\s*if \\(!Config\\.SPEC\\.isLoaded\\(\\) \\|\\| !Config\\.LEAN_REPLAY_VANILLA_ON_TAP/.test(client),
  'and on release that packet is sent BEFORE the vanilla action is replayed');
check(/if \\(!LeanState\\.holding\\(player\\)\\) \\{\\s*return;/.test(server),
  'the server toss guard asks HOLDING, so a replayed tap drop is never cancelled by our own safety net');`);

// The threshold boundary + startMode behaviour, simulated with the same arithmetic.
sub(`console.log('');
console.log('7. config, docs and the debug readout');`,
  `console.log('');
console.log('6b. the tap threshold, simulated');
const peeking = (down, pressedAt, now, mode, threshold) => {
  if (!down) return false;
  if (mode === 'afterthreshold') return pressedAt >= 0 && now - pressedAt >= threshold;
  return true;
};
const replayed = (held, threshold, replayOnTap) => held < threshold && replayOnTap;
check(peeking(true, 100, 100, 'immediate', 5) === true
  && peeking(true, 100, 100, 'afterthreshold', 5) === false
  && peeking(true, 100, 105, 'afterthreshold', 5) === true,
  'startMode: immediate leans on press, afterThreshold only at the threshold (5 ticks)');
check(peeking(true, 100, 104, 'afterthreshold', 5) === false,
  'the boundary is inclusive: 4 ticks is still a tap');
for (const [held, expected] of [[0, true], [1, true], [4, true], [5, false], [12, false], [200, false]]) {
  check(replayed(held, 5, true) === expected,
    \`a \${held}-tick hold \${expected ? 'replays the vanilla action' : 'does NOT replay it'}\`);
}
check(replayed(2, 5, false) === false && replayed(9, 5, false) === false,
  'replayVanillaOnTap = false never replays, whatever the hold length');
check(replayed(1, 1, true) === false && replayed(0, 1, true) === true,
  'a threshold of 1 tick still lets a zero-length press through as a tap');

console.log('');
console.log('7. config, docs and the debug readout');`);

// config keys + README
sub("  ['LEAN_INVERT_ROLL', 'define(\"leanInvertRoll\", false)'],",
  "  ['LEAN_INVERT_ROLL', 'define(\"leanInvertRoll\", false)'],\n"
  + "  ['LEAN_TAP_THRESHOLD_TICKS', 'defineInRange(\"tapThresholdTicks\", 5'],\n"
  + "  ['LEAN_START_MODE', 'define(\"startMode\", \"immediate\")'],\n"
  + "  ['LEAN_REPLAY_VANILLA_ON_TAP', 'define(\"replayVanillaOnTap\", true)'],");
sub("for (const key of ['leanEnabled', 'leanMaxOffset', 'leanRollDegrees', 'leanSpeedTicks',\n  'leanSuppressVanillaKeys', 'leanInvertOffset', 'leanInvertRoll']) {",
  "for (const key of ['leanEnabled', 'leanMaxOffset', 'leanRollDegrees', 'leanSpeedTicks',\n  'leanSuppressVanillaKeys', 'leanInvertOffset', 'leanInvertRoll', 'tapThresholdTicks',\n  'startMode', 'replayVanillaOnTap']) {");
sub("check(/public static String describe\\(\\)/.test(client) && /cameraSlide=/.test(client),",
  "check(/public static String describe\\(\\)/.test(client) && /cameraSlide=/.test(client)\n  && /tapThreshold=%dt startMode=%s replayOnTap=%s/.test(client),");
sub("'and says whether the lateral camera move is available or degraded');",
  "'and says whether the lateral camera move is available or degraded, plus the tap settings');");

fs.writeFileSync(file, text);
console.log('changed:', before !== text);
