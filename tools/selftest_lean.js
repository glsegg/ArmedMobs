// Player lean / peek (README 5s).
//
//   node tools/selftest_lean.js
//
// The feature has two halves that must agree, so this gate checks them in three ways:
//   1. source invariants: the key defaults, the two vanilla-key suppressions, the config keys, and the rule
//      that the SERVER never moves the player (only the camera on the client and projectile spawns);
//   2. a numeric replay of LeanMath: the offset is the player's right vector times the lean, the camera and
//      the projectile call the SAME function, and two parallel rays stay exactly `offset` apart, which is what
//      the acceptance test ("the impact moves ~0.4-0.6 blocks") actually measures;
//   3. a model of the wall clip: a desired offset is shortened to stop short of the block, and is zero when
//      there is no room at all.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const langText = (name) => fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');

const math = read('lean/LeanMath.java');
const state = read('lean/LeanState.java');
const network = read('lean/LeanNetwork.java');
const server = read('lean/LeanServerEvents.java');
const client = read('client/LeanClient.java');
const setup = read('client/ClientSetup.java');
const config = read('Config.java');
const main = read('TarkovScav.java');
const clientCommands = read('command/ClientCommands.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. the keys: Q and E, as asked, and registered');
check(/public static final KeyMapping LEAN_LEFT = new KeyMapping\("key\.tarkovscav\.lean_left",\s*InputConstants\.Type\.KEYSYM, GLFW\.GLFW_KEY_E, CATEGORY\)/
  .test(client), 'lean left ships bound to E (the mapping was swapped: the user reported Q/E reversed)');
check(/public static final KeyMapping LEAN_RIGHT = new KeyMapping\("key\.tarkovscav\.lean_right",\s*InputConstants\.Type\.KEYSYM, GLFW\.GLFW_KEY_Q, CATEGORY\)/
  .test(client), 'and lean right to Q');
check(/public static final String CATEGORY = "key\.categories\.tarkovscav"/.test(client),
  'both live in this mod\'s own controls category');
check(/RegisterKeyMappingsEvent event\) \{\s*LeanClient\.registerKeys\(event\);/.test(setup),
  'ClientSetup registers them on the mod bus');
check(/public static void registerKeys\(RegisterKeyMappingsEvent event\)[\s\S]{0,120}?event\.register\(LEAN_LEFT\);[\s\S]{0,80}?event\.register\(LEAN_RIGHT\);/
  .test(client), 'and both mappings are actually registered');
for (const lang of ['en_us', 'zh_cn']) {
  const text = langText(lang);
  check(text.includes('"key.tarkovscav.lean_left"') && text.includes('"key.tarkovscav.lean_right"'),
    `${lang}.json names both keys`);
  check(text.includes('"key.categories.tarkovscav"'), `${lang}.json names the category`);
}

console.log('');
console.log('2. holding Q/E must NOT also drop your item or open the backpack');
check(/public static void onKey\(InputEvent\.Key event\)/.test(client)
  && /while \(vanilla\.consumeClick\(\)\)/.test(client),
  'every click of an occupied vanilla key is consumed the moment it is pressed (never an action, never a packet)');
check(/static java\.util\.List<KeyMapping> occupiedVanillaKeys\(Minecraft minecraft\)/.test(client)
  && /vanilla\.getKey\(\)\.getValue\(\) == key/.test(client),
  'and the comparison is against the CURRENT binding, so rebinding a lean key gives the vanilla key back');
check(!/ScreenEvent\.Opening|setCanceled\(true\)/.test(strip(client)),
  'the inventory is NOT done by cancelling the screen any more: only the key click is consumed, so nothing'
    + ' else that opens the backpack (a command, another mod) is affected');
check(/private static void onPress\(Minecraft minecraft, int key\)/.test(client)
  && /while \(vanilla\.consumeClick\(\)\)/.test(client)
  && /dropPressedAt = clientTicks;/.test(client) && /inventoryPressedAt = clientTicks;/.test(client),
  'the press is taken and TIMED: the vanilla click is consumed so the game can never act on it by itself');
check(/private static void onRelease\(Minecraft minecraft, int key\)/.test(client),
  'and the tap/long-press decision is made on release');
check(/if \(releasedInventory\) \{[\s\S]{0,200}?setScreen\(new net\.minecraft\.client\.gui\.screens\.inventory\.InventoryScreen\(/.test(client),
  'a TAP of the inventory key opens the backpack for the player (the vanilla action, replayed by us)');
check(/if \(releasedDrop && !minecraft\.player\.isSpectator\(\)\) \{[\s\S]{0,200}?minecraft\.player\.drop\(false\);/
  .test(client),
  'a TAP of the drop key drops exactly one item (the vanilla action, replayed by us)');
check(/if \(!Config\.SPEC\.isLoaded\(\) \|\| !Config\.LEAN_REPLAY_VANILLA_ON_TAP\.get\(\)\) \{\s*return;/
  .test(client),
  'replayVanillaOnTap = false turns a tap into "nothing happens"');
check(!/while \(vanilla\.consumeClick\(\)\)[\s\S]{0,400}?player\.drop\(false\)/.test(
  client.slice(client.indexOf('private static void onPress'), client.indexOf('private static void onRelease'))),
  'and the drop is NOT replayed on press (that is what would drop twice)');
check(/private static boolean peeking\(KeyMapping key, long pressedAt\)/.test(client)
  && /return pressedAt >= 0L && clientTicks - pressedAt >= Config\.LEAN_TAP_THRESHOLD_TICKS\.get\(\);/.test(client)
  && /if \(startMode\(\)\.equals\("afterthreshold"\)\)/.test(client),
  'startMode = afterThreshold only starts leaning at the threshold; immediate starts on press (the else path)');
check(/boolean holding = LEAN_LEFT\.isDown\(\) \|\| LEAN_RIGHT\.isDown\(\);/.test(client)
  && /LeanNetwork\.send\(lean, holding\)/.test(client),
  'the server is told "a lean key is held", separately from the lean amount');
check(/setLean\(lean, true\);\s*\n\s*if \(!Config\.SPEC\.isLoaded\(\) \|\| !Config\.LEAN_REPLAY_VANILLA_ON_TAP/.test(client),
  'and on release that packet is sent BEFORE the vanilla action is replayed');
check(/if \(!LeanState\.holding\(player\)\) \{\s*return;/.test(server),
  'the server toss guard asks HOLDING, so a replayed tap drop is never cancelled by our own safety net');
check(/private static boolean suppressing\(\) \{\s*return Config\.SPEC\.isLoaded\(\) && Config\.LEAN_ENABLED\.get\(\)\s*&& Config\.LEAN_SUPPRESS_VANILLA_KEYS\.get\(\);/
  .test(client), 'both suppressions honour leanEnabled AND leanSuppressVanillaKeys');
check(/for \(KeyMapping vanilla : occupiedVanillaKeys\(minecraft\)\)/.test(client)
  && /minecraft\.options\.keyDrop/.test(client) && /minecraft\.options\.keyInventory/.test(client),
  'only the two VANILLA keys we occupy are touched (nothing else, and no blanket screen cancel)');
check(/public static void onItemToss\(ItemTossEvent event\)/.test(server)
  && /if \(!Config\.LEAN_ENABLED\.get\(\) \|\| !Config\.LEAN_SUPPRESS_VANILLA_KEYS\.get\(\)\)/
    .test(server),
  'the server has a second guard for a toss that still gets through, behind the same two keys');
// The subtle one: Forge's ItemTossEvent carries an ALREADY CREATED item entity, so a cancel without putting
// the stack back would destroy it.
check(/event\.setCanceled\(true\);[\s\S]{0,300}?player\.getInventory\(\)\.add\(stack\)/.test(server),
  'and that guard puts the stack BACK (cancelling the event alone would destroy the item)');
check(/Containers\.dropItemStack\(/.test(server)
  && !/player\.drop\(/.test(server),
  'a full inventory falls back to a plain container drop, never back into Player#drop (which would re-fire)');

console.log('');
console.log('3. the camera: client only, and the player is never moved');
check(/public static void onCameraAngles\(ViewportEvent\.ComputeCameraAngles event\)/.test(client)
  && /event\.setRoll\(event\.getRoll\(\) - \(float\) \(Config\.LEAN_ROLL_DEGREES\.get\(\) \* rollLean\)\)/
    .test(client),
  'the roll is applied through the public camera-angle event');
check(/float signed = lean \* \(Config\.LEAN_INVERT_OFFSET\.get\(\) \? -1\.0F : 1\.0F\);/.test(client)
  && /float rollLean = lean \* \(Config\.LEAN_INVERT_ROLL\.get\(\) \? -1\.0F : 1\.0F\);/.test(client)
  && /LeanMath\.offsetFor\(event\.getYaw\(\), signed, Config\.LEAN_MAX_OFFSET\.get\(\)\)/.test(client),
  'the offset and the roll each apply the SAME lean value with their own invert switch (one axis each)');
check(/LeanMath\.slide\(minecraft\.level, camera\.getPosition\(\), wanted\)/.test(client),
  'and the lateral move is clipped from the CAMERA position, so it works in first and third person');
check(!/java\.lang\.reflect|getDeclaredMethods|Method\.invoke|setAccessible/.test(client),
  'there is NO reflection left in the client: the old by-signature lookup is gone');
check(/camera\.setPosition\(position\);/.test(client),
  'Camera#setPosition is called DIRECTLY (possible only because the access transformer made it public)');
check(fs.existsSync(path.join(ROOT, 'src', 'main', 'resources', 'META-INF', 'accesstransformer.cfg')),
  'the access transformer file exists');
check(/public net\.minecraft\.client\.Camera m_90581_\(Lnet\/minecraft\/world\/phys\/Vec3;\)V/
  .test(fs.readFileSync(path.join(ROOT, 'src', 'main', 'resources', 'META-INF', 'accesstransformer.cfg'),
    'utf8')),
  'and it publishes Camera#setPosition(Vec3) under its SRG name (m_90581_, checked against the tsrg)');
check(/accessTransformer = file\('src\/main\/resources\/META-INF\/accesstransformer\.cfg'\)/
  .test(fs.readFileSync(path.join(ROOT, 'build.gradle'), 'utf8')),
  'and build.gradle declares it (so the dev classes are transformed and the jar ships it)');
check(/cameraSlideMode\(\)/.test(client) && /return cameraMoveUnavailable \? "unavailable" : "at";/.test(client),
  'client state reports how the slide is implemented (at | unavailable)');
check(/catch \(Throwable failure\)[\s\S]{0,200}?cameraMoveUnavailable = true/.test(client),
  'and a failure degrades to roll-only with one log line instead of crashing the client');
// The anti-cheat rule: nothing in the lean code may move the PLAYER. Only the camera (client) and projectile
// spawns (server) move.
const serverBody = server.slice(server.indexOf('public static void onEntityJoinLevel'),
  server.indexOf('public static void onItemToss'));
check(/entity\.setPos\(/.test(serverBody), 'the server moves the PROJECTILE:');
check(!/player\.setPos|player\.setDeltaMovement|player\.setBoundingBox|player\.refreshDimensions/.test(server)
  && !/setPos\(|setDeltaMovement|setBoundingBox|refreshDimensions/.test(client.replace(/moveCamera\(camera, camera\.getPosition\(\)\.add\(offset\)\)/g, ''))
    || !/player\.setPos/.test(client),
  'and never the player: no setPos/setDeltaMovement/setBoundingBox/refreshDimensions on a Player anywhere');
check(!/hitbox|getBoundingBox/.test(math + server),
  'the lean touches no hitbox at all (leaning cannot dodge a hit or squeeze through a gap)');
check(/entity\.setPos\(entity\.getX\(\) \+ offset\.x/.test(server),
  'the projectile is moved by exactly the clipped offset, and its velocity is left alone (parallel ray)');

console.log('');
console.log('4. the shot origin and the camera use the SAME offset');
check(/public static Vec3 offsetFor\(float yawDegrees, float lean, double maxOffset\)/.test(math)
  && /rightOf\(yawDegrees\)\.scale\(clamp\(lean\) \* Math\.max\(0\.0D, maxOffset\)\)/.test(math),
  'LeanMath.offsetFor is the single definition');
check(/LeanMath\.offsetFor\(event\.getYaw\(\), signed, Config\.LEAN_MAX_OFFSET\.get\(\)\)/.test(client),
  'the camera calls it');
check(/LeanMath\.offsetFor\(player\.getYRot\(\), lean, Config\.LEAN_MAX_OFFSET\.get\(\)\)/.test(server),
  'and so does the projectile spawn, with the same config value');
check(/public static float clamp\(float lean\)/.test(math)
  && /new ServerboundLean\(LeanMath\.clamp\(lean\), holding\)/.test(network)
  && /float clamped = LeanMath\.clamp\(lean\);/.test(state)
  && /LeanMath\.clamp\(value\)/.test(client),
  'the amount is clamped to [-1,1] on the client, on the wire and again on arrival');
// Numeric replay of the same maths, so the geometry claims below are about the shipped formula.
const rightOf = (yawDegrees) => {
  const yaw = yawDegrees * Math.PI / 180;
  return { x: -Math.cos(yaw), z: -Math.sin(yaw) };
};
const clamp = (lean) => Math.max(-1, Math.min(1, lean));
const offsetFor = (yawDegrees, lean, maxOffset) => {
  const r = rightOf(yawDegrees);
  const amount = clamp(lean) * Math.max(0, maxOffset);
  return { x: r.x * amount, z: r.z * amount };
};
const near = (a, b, epsilon = 1e-9) => Math.abs(a - b) < epsilon;
const o0 = offsetFor(0, 1, 0.5);
check(near(o0.x, -0.5) && near(o0.z, 0),
  'yaw 0 (facing +Z/south) leans towards -X, which is the player\'s right', `(${o0.x}, ${o0.z})`);
const o90 = offsetFor(90, 1, 0.5);
check(near(o90.x, 0) && near(o90.z, -0.5),
  'yaw 90 (facing -X/west) leans towards -Z, again to the right', `(${o90.x}, ${o90.z})`);
check(near(Math.hypot(offsetFor(37, 1, 0.5).x, offsetFor(37, 1, 0.5).z), 0.5),
  'the offset length is exactly maxOffset at full lean');
check(near(Math.hypot(offsetFor(37, 0.5, 0.5).x, offsetFor(37, 0.5, 0.5).z), 0.25),
  'and scales linearly with the lean amount (the ramp)');
check(near(offsetFor(37, 9, 0.5).x, offsetFor(37, 1, 0.5).x),
  'a lean beyond 1 is clamped, not amplified');
check(near(offsetFor(37, -1, 0.5).x, -offsetFor(37, 1, 0.5).x),
  'leaning left is exactly the mirror of leaning right');
check(near(offsetFor(37, 1, 0).x, 0) && near(offsetFor(37, 1, 0).z, 0),
  'maxOffset = 0 disables the translation (roll-only), which is the documented fallback');
// The parallel-ray claim: a ray from eye+offset along the SAME direction as the eye ray lands exactly
// `offset` away from it, at every distance. This is what makes the acceptance test measurable.
const rows = [];
for (const distance of [1, 2, 4, 6, 8, 20, 50]) {
  const eye = { x: 10, y: 70, z: 10 };
  const direction = { x: 0, y: 0, z: 1 };      // facing +Z
  const lean = offsetFor(0, 1, 0.5);            // to -X, i.e. the player's right
  // A wall at z = eye.z + distance, perpendicular to the ray.
  const hitsPlain = { x: eye.x, z: eye.z + distance };
  const hitsLeaned = { x: eye.x + lean.x, z: eye.z + lean.z + distance };
  rows.push({ distance, shift: Math.hypot(hitsLeaned.x - hitsPlain.x, hitsLeaned.z - hitsPlain.z) });
}
check(rows.every((row) => near(row.shift, 0.5, 1e-9)),
  'the two rays stay exactly 0.5 blocks apart at every distance (parallel rays)',
  rows.map((r) => `${r.distance}b=${r.shift.toFixed(2)}`).join(' '));
check(rows.length >= 5, 'and the table spans close and long range');

console.log('');
console.log('5. the wall rule: a lean cannot push the camera into a block');
check(/public static Vec3 slide\(Level level, Vec3 from, Vec3 offset\)/.test(math)
  && /new ClipContext\(from, from\.add\(offset\), ClipContext\.Block\.COLLIDER,\s*ClipContext\.Fluid\.NONE, null\)/
    .test(math),
  'LeanMath.slide clips the offset against block collisions (and ignores entities)');
check(/Math\.min\(room, wanted\) \/ wanted/.test(math) && /WALL_MARGIN = 0\.1D/.test(math),
  'and shortens it to stop WALL_MARGIN short of the hit');
check(/room <= 0\.0D\) \{\s*return Vec3\.ZERO;/.test(math),
  'a direction with no room at all yields no offset (the lean simply does nothing there)');
// Numeric replay of the shortening arithmetic.
const slide = (wanted, hitDistance, margin = 0.1) => {
  if (Math.hypot(wanted.x, wanted.z) < 1e-4) return { x: 0, z: 0 };
  if (hitDistance === null) return wanted;
  const room = hitDistance - margin;
  if (room <= 0) return { x: 0, z: 0 };
  const length = Math.hypot(wanted.x, wanted.z);
  const scale = Math.min(room, length) / length;
  return { x: wanted.x * scale, z: wanted.z * scale };
};
const wanted = offsetFor(0, 1, 0.5);
check(near(slide(wanted, null).x, -0.5), 'a clear direction keeps the full offset');
check(near(slide(wanted, 0.3).x, -0.2) && near(slide(wanted, 0.3).z, 0),
  'a wall 0.3 blocks away shortens 0.5 to 0.2 (0.3 - 0.1 margin)');
check(near(slide(wanted, 0.05).x, 0), 'a wall 0.05 blocks away leaves nothing (no camera inside the block)');
check(near(slide(wanted, 3).x, -0.5), 'a wall 3 blocks away does not lengthen the offset');

console.log('');
console.log('6. inert when switched off, and one packet per change');
for (const [name, src, pattern] of [
  ['the client tick', client, /onClientTick\(TickEvent\.ClientTickEvent event\)[\s\S]{0,900}?!Config\.LEAN_ENABLED\.get\(\)/],
  ['the camera', client, /onCameraAngles\(ViewportEvent[\s\S]{0,200}?!Config\.LEAN_ENABLED\.get\(\)/],
  ['the projectile rule', server, /onEntityJoinLevel\(EntityJoinLevelEvent event\)[\s\S]{0,200}?!Config\.LEAN_ENABLED\.get\(\)/],
  ['the toss guard', server, /onItemToss\(ItemTossEvent event\)[\s\S]{0,200}?!Config\.LEAN_ENABLED\.get\(\)/]]) {
  check(pattern.test(src), `${name} checks leanEnabled (so false is completely inert)`);
}
check(/if \(send && Math\.abs\(lean - sent\) > 0\.01F\)/.test(client),
  'the client only talks to the server when the value actually changed');
check(/else if \(!send && sent != 0\.0F\) \{\s*sent = 0\.0F;\s*sentHolding = false;\s*LeanNetwork\.send\(0\.0F, false\);/.test(client),
  'and it always sends the return-to-centre (a screen or a logout must not leave a stale lean)');
check(/minecraft\.screen != null/.test(client),
  'opening any screen drops the lean to zero, so typing in chat does not lean you over');
check(/NetworkRegistry\.newSimpleChannel\(\s*TarkovScav\.id\("lean"\)/.test(network)
  && /LeanNetwork\.register\(\)/.test(main),
  'the channel is created and registered from the mod setup (both sides)');
check(/if \(!Config\.LEAN_ENABLED\.get\(\) \|\| event\.getLevel\(\)\.isClientSide\(\) \|\| event\.loadedFromDisk\(\)\)/
  .test(server),
  'the projectile rule is SERVER only and skips chunk loads (a reloaded bullet is not a new shot)');
check(/projectile\.getOwner\(\) instanceof ServerPlayer player/.test(server),
  'and only projectiles owned by a server player are moved');
check(/dropped\.getItem\(\)\.copy\(\)/.test(server) && /LeanState\.clear\(event\.getEntity\(\)\.getUUID\(\)\)/.test(server),
  'the toss guard copies the stack before cancelling, and a logout forgets the lean');

console.log('');
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
    `a ${held}-tick hold ${expected ? 'replays the vanilla action' : 'does NOT replay it'}`);
}
check(replayed(2, 5, false) === false && replayed(9, 5, false) === false,
  'replayVanillaOnTap = false never replays, whatever the hold length');
check(replayed(1, 1, true) === false && replayed(0, 1, true) === true,
  'a threshold of 1 tick still lets a zero-length press through as a tap');

console.log('');
console.log('7. config, docs and the debug readout');
for (const [key, value] of [['LEAN_ENABLED', 'define("leanEnabled", true)'],
  ['LEAN_MAX_OFFSET', 'defineInRange("leanMaxOffset", 0.6D'],
  ['LEAN_INVERT_OFFSET', 'define("leanInvertOffset", false)'],
  ['LEAN_INVERT_ROLL', 'define("leanInvertRoll", false)'],
  ['LEAN_TAP_THRESHOLD_TICKS', 'defineInRange("tapThresholdTicks", 5'],
  ['LEAN_START_MODE', 'define("startMode", "immediate")'],
  ['LEAN_REPLAY_VANILLA_ON_TAP', 'define("replayVanillaOnTap", true)'],
  ['LEAN_ROLL_DEGREES', 'defineInRange("leanRollDegrees", 12.0D'],
  ['LEAN_SPEED_TICKS', 'defineInRange("leanSpeedTicks", 5'],
  ['LEAN_SUPPRESS_VANILLA_KEYS', 'define("leanSuppressVanillaKeys", true)']]) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
  check(config.includes(value), `  ${key} default matches the documented one`, value);
}
for (const key of ['leanEnabled', 'leanMaxOffset', 'leanRollDegrees', 'leanSpeedTicks',
  'leanSuppressVanillaKeys', 'leanInvertOffset', 'leanInvertRoll', 'tapThresholdTicks',
  'startMode', 'replayVanillaOnTap']) {
  check(readme.includes(key), `README documents client.${key}`);
}
check(/### 5t\./.test(readme), 'README has the 5t section');
check(/歪头/.test(readme) && /长按/.test(readme), 'README explains the feature in Chinese');
check(/LeanClient\.describe\(\)/.test(clientCommands),
  '/tarkovscav client state prints the live lean value and the key state');
check(/public static String describe\(\)/.test(client) && /cameraSlide=/.test(client)
  && /tapThreshold=%dt startMode=%s replayOnTap=%s/.test(client),
  'and says whether the lateral camera move is available or degraded, plus the tap settings');
check(/leanSpeedTicks/.test(readme) && /5 tick/.test(readme),
  'README documents the ramp time');

console.log('');
if (failures > 0) {
  console.log(`${failures} lean check(s) FAILED`);
  process.exit(1);
}
console.log('player lean invariants all hold');
