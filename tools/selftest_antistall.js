// Requirement A (a mob that stands still and never fires) + requirement B (cover-seek speed), proven
// from the code that really runs.
//
//   node tools/selftest_antistall.js
//
// The bug this gate exists for was a *written but never read* watchdog: GunBrain armed
// `watchdogTicks` in tickReload (200) and in the ShootResult default branch (20), decremented it every
// tick, and never compared it to anything, so neither state had a way out. A source gate is the right
// instrument for that class of bug, because "some field is never read" is invisible to a compiler and
// to any single runtime scenario:
//
//   1. every armed watchdog is read, in the state that armed it, and the escape is logged (WARN) and
//      ends in a transition - never a silent stand-still;
//   2. the reload wait is bounded on the 'still reloading' side of the early return;
//   3. the armed pillager has a melee fallback, and the vanilla classes really do not provide one;
//   4. every PathNavigation#moveTo speed site is accounted for in an explicit table, so the three
//      cover moves are 1.5x and nothing else moved;
//   5. the runtime regression gates (/tarkovscav test watch|stall) exist and assert on movement+shots.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
/** Comments are stripped for the structural checks; the javadoc claims are checked separately. */
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const skip = (label, why) => console.log(`  SKIP  ${label}  (${why})`);

/** The `{...}` body of the method whose signature starts at `signature`. */
function methodBody(code, signature) {
  const start = code.indexOf(signature);
  if (start < 0) return null;
  const open = code.indexOf('{', start);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '{') depth++;
    else if (code[i] === '}') {
      depth--;
      if (depth === 0) return code.slice(open + 1, i);
    }
  }
  return null;
}

const brain = strip(read('gun/GunBrain.java'));
const config = strip(read('Config.java'));
const pillager = strip(read('entity/GunnerPillagerEntity.java'));
const scav = strip(read('entity/ScavEntity.java'));
const commands = strip(read('command/ModCommands.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

const tickFire = methodBody(brain, 'private void tickFire(');
const tickReload = methodBody(brain, 'private void tickReload(');
const tickRetreat = methodBody(brain, 'private void tickRetreat(');
const handleShootResult = methodBody(brain, 'private void handleShootResult(');

console.log('A1. the anti-stall watchdog is read, not just written');
check(tickFire !== null && tickReload !== null && handleShootResult !== null,
  'GunBrain still has tickFire / tickReload / handleShootResult', 'found all three');
check(tickFire.includes('this.watchdogTicks <= 0'), 'tickFire compares the watchdog to zero', 'the read the old code was missing');
check(/if \(this\.stateTicks == 1\) \{\s*this\.watchdogTicks = Config\.FIRE_STALL_TICKS\.get\(\);/.test(tickFire),
  'the watchdog is armed exactly once per burst (stateTicks == 1)');
{
  // The only place tickFire may assign to the watchdog is that one arming line: any second assignment
  // in the method would be capable of refilling the counter before the expiry check reads it.
  const assignments = (tickFire.match(/this\.watchdogTicks\s*=/g) || []).length;
  check(assignments === 1, 'tickFire assigns the watchdog exactly once',
    `${assignments} assignment(s) - a second one can hide the expiry from the check`);
}
check(/transition\(GunAiState\.(REPOSITION|RETREAT)\)/.test(tickFire), 'the expired watchdog transitions out of FIRE');
check(tickFire.includes('LOGGER.warn') && tickFire.includes('[gunai]'), 'the escape is logged at WARN (never silent)');
check(handleShootResult.includes('this.shotsFired++'), 'handleShootResult counts accepted shots');
check(/case SUCCESS[\s\S]{0,260}this\.watchdogTicks = Config\.FIRE_STALL_TICKS\.get\(\)/.test(handleShootResult),
  'a SUCCESS re-arms the watchdog (so it measures "since the last real shot")');
check(/default ->[\s\S]{0,700}\}/.test(handleShootResult) && !/default ->[\s\S]{0,400}this\.watchdogTicks =/.test(handleShootResult),
  'the UNKNOWN_FAIL/NETWORK_FAIL/FORGE_EVENT_CANCEL branch deliberately does NOT re-arm it',
  'this is the difference between a bounded and an unbounded stall');
check(!tickReload.includes('this.watchdogTicks ='), 'tickReload no longer writes the watchdog it does not read');
check(tickReload.includes('Config.RELOAD_STALL_TICKS.get()'), 'tickReload bounds the TaCZ-reloading wait');
check(/\+\+this\.reloadWaitTicks > Config\.RELOAD_STALL_TICKS\.get\(\)[\s\S]{0,700}transition\(GunAiState\.RETREAT\)/.test(tickReload),
  'the reload bound breaks contact instead of waiting for ever');
check(tickReload.includes('LOGGER.warn'), 'the reload bound is logged at WARN');
// The old bug in one line: the timeout lived *after* the `if (reloading) return;` early return, so it
// could only ever fire when TaCZ had already stopped reporting a reload.
const reloadingReturn = tickReload.indexOf('if (reloading)');
const reloadBounds = tickReload.indexOf('Config.RELOAD_STALL_TICKS.get()');
check(reloadingReturn >= 0 && reloadBounds > reloadingReturn,
  'the bound sits inside the reloading branch, before the early return');

console.log('');
console.log('A2. the armed pillager has a fallback when TaCZ has no gun for it');
check(/addGoal\(1, new GunAttackGoal\(this\)\)/.test(pillager), 'the gun goal still outranks everything', 'priority 1');
check(/addGoal\(2, new NoGunMeleeGoal\(this, 1\.1D, false\)\)/.test(pillager),
  'GunnerPillagerEntity registers NoGunMeleeGoal at priority 2', 'same numbers as ScavEntity');
check(pillager.includes('import com.gfl.tarkovscav.gun.NoGunMeleeGoal;'), 'the import is there');
check(/addGoal\(2, new com\.gfl\.tarkovscav\.gun\.NoGunMeleeGoal\(this, 1\.1D, false\)\)/.test(scav),
  'ScavEntity uses the same fallback', 'it is the pattern this copies');
// Mutual exclusion, in one predicate: exactly one of the two goals can ever be usable.
const meleeGoal = strip(read('gun/NoGunMeleeGoal.java'));
check(/canUse\(\)\s*\{\s*return usable\(\) && super\.canUse\(\);/.test(meleeGoal)
  && /private boolean usable\(\)\s*\{\s*return !this\.user\.gunBrain\(\)\.hasGun\(\)/.test(meleeGoal),
  'the melee fallback is false whenever a gun is held',
  'so MeleeAttackGoal and GunAttackGoal never issue competing navigation in one tick. Since the weapon rack'
  + ' (5n) the same predicate also stands the fallback down while a bow/crossbow is held');
check(/canContinueToUse\(\)\s*\{\s*return usable\(\) && super\.canContinueToUse\(\);/.test(meleeGoal),
  'and it stops the moment a gun is picked up');
check(!/new MeleeAttackGoal\(/.test(pillager) && !/new MeleeAttackGoal\(/.test(scav),
  'no gun mob uses the plain MeleeAttackGoal any more');

console.log('');
console.log('A3. doors and the no-progress watchdog');
for (const [name, source] of [['ScavEntity', scav], ['GunnerPillagerEntity', pillager]]) {
  check(/GunUser\.allowDoors\(this\);/.test(source), `${name} calls GunUser.allowDoors in its constructor`);
}
const gunUser = strip(read('gun/GunUser.java'));
check(/setCanOpenDoors\(true\)/.test(gunUser) && /setCanPassDoors\(true\)/.test(gunUser),
  'allowDoors enables both door flags', 'vanilla Villager/Vindicator/Zombie do; Raider/Pillager do not');
const progress = methodBody(brain, 'private void checkMovementProgress(');
check(progress !== null, 'GunBrain has the no-progress watchdog');
check(progress.includes('Config.MOVE_PROGRESS_SAMPLE_TICKS.get()'), 'it samples on a config interval');
check(progress.includes('Config.MOVE_PROGRESS_MIN_BLOCKS.get()'), 'it compares against a minimum distance');
check(/LOGGER\.warn/.test(progress), 'both outcomes are logged at WARN (never silent)');
check(/getJumpControl\(\)\.jump\(\)/.test(progress), 'it tries to hop a one-block obstacle (a doorstep)');
check(/tactics\.invalidate\(\)/.test(progress), 'it drops the cached cover list, which may be unreachable from here');
check(/transition\(GunAiState\.(REPOSITION|RETREAT)\)/.test(progress), 'and it ends in a state change, not in standing still');
for (const key of ['moveProgressSampleTicks', 'moveProgressMinBlocks', 'moveProgressRetries']) {
  check(config.includes(`"${key}"`), `Config declares ${key}`);
  check(readme.includes(key), `README documents ${key}`);
}
// The walk animation is driven by measured displacement, not by intent.
const walk = strip(read('entity/WalkTelemetry.java'));
check(/isMoving\(\)\s*\{\s*return average\(\) >= MOVING_THRESHOLD;/.test(walk),
  'WalkTelemetry answers from the average of measured per-tick displacement');
check(/use.*walk\.isMoving\(\)|this\.walk\.isMoving\(\)/.test(brain) === false
    && /this\.walk\.isMoving\(\)/.test(scav) && /this\.walk\.isMoving\(\)/.test(pillager),
  'both controllers select the walk/run clip from that telemetry',
  'so a pinned mob animates as standing still instead of stepping in place');

// The claim in that comment - "a vanilla Pillager has no melee goal at all" - is checked against the
// real class files when the mapped jar is available. A class file only names a type it references, and
// registerGoals would have to reference MeleeAttackGoal to add one, so the type name is absent from a
// class that does not use it. (Checked on the inflated class body, not on the compressed bytes.)
const zlib = require('zlib');
/** Reads one entry out of a jar/zip by exact name, without third-party code. */
function zipEntry(jar, name) {
  const eocd = jar.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = jar.readUInt16LE(eocd + 10);
  let at = jar.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (jar.readUInt32LE(at) !== 0x02014b50) return null;
    const method = jar.readUInt16LE(at + 10);
    const compressed = jar.readUInt32LE(at + 20);
    const nameLen = jar.readUInt16LE(at + 28);
    const extraLen = jar.readUInt16LE(at + 30);
    const commentLen = jar.readUInt16LE(at + 32);
    const localAt = jar.readUInt32LE(at + 42);
    const entryName = jar.toString('utf8', at + 46, at + 46 + nameLen);
    if (entryName === name) {
      const localNameLen = jar.readUInt16LE(localAt + 26);
      const localExtraLen = jar.readUInt16LE(localAt + 28);
      const dataAt = localAt + 30 + localNameLen + localExtraLen;
      const raw = jar.slice(dataAt, dataAt + compressed);
      return method === 0 ? raw : zlib.inflateRawSync(raw);
    }
    at += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}
const mappedJar = path.join(ROOT, '..', 'GirlsFrontline', '.gradle-home', 'caches', 'forge_gradle',
  'minecraft_user_repo', 'net', 'minecraftforge', 'forge', '1.20.1-47.3.0_mapped_official_1.20.1',
  'forge-1.20.1-47.3.0_mapped_official_1.20.1.jar');
if (fs.existsSync(mappedJar)) {
  const jar = fs.readFileSync(mappedJar);
  // expectHold is a sanity check that the right entry was read: Pillager and Raider are the classes
  // that do register the crossbow/hold-ground goals, AbstractIllager is the one that registers none.
  for (const { entry, expectHold } of [
    { entry: 'net/minecraft/world/entity/monster/Pillager.class', expectHold: true },
    { entry: 'net/minecraft/world/entity/monster/AbstractIllager.class', expectHold: false },
    { entry: 'net/minecraft/world/entity/raid/Raider.class', expectHold: true },
  ]) {
    const body = zipEntry(jar, entry);
    if (!body) { skip(`${entry} references no MeleeAttackGoal`, 'entry not found in the mapped jar'); continue; }
    const hasIt = body.includes(Buffer.from('MeleeAttackGoal'));
    const hasHold = body.includes(Buffer.from('HoldGroundAttackGoal')) || body.includes(Buffer.from('RangedCrossbowAttackGoal'));
    check(!hasIt, `${entry.split('/').pop()} references no MeleeAttackGoal`,
      hasIt ? 'the fallback would already be there' : 'so the fallback really is missing in vanilla');
    check(expectHold ? hasHold : !hasHold,
      `${entry.split('/').pop()} ${expectHold ? 'does' : 'does not'} reference the crossbow goals`,
      'confirms the entry read is the right class');
  }
} else {
  skip('vanilla Pillager has no melee goal (class-file check)', 'mapped jar not present');
}

console.log('');
console.log('B. every moveTo speed site is accounted for');
// The table the README documents. `coverSeekSpeed()` reads tactics.coverSeekSpeedModifier (default 1.0
// after the user asked for "running away = normal 1x"), `escapeWithoutCoverSpeed()` its own key (1.0).
const EXPECTED_SPEEDS = [
  { speed: '1.15D', count: 3, why: 'ADVANCE (towards cover / straight at the target) + the watchdog re-path straight at the target - deliberately unchanged' },
  { speed: 'coverSeekSpeed()', count: 5, why: 'the three cover moves + the two watchdog re-paths; default is 1.0, i.e. a normal walk' },
  { speed: 'escapeWithoutCoverSpeed()', count: 1, why: 'retreat with no cover found: its own key, default 1.0, and no sprint unless retreatSprint=true' },
];
/** Every `getNavigation().moveTo(...)` call, with its full argument list bracketed out. */
function moveToCalls(code) {
  const calls = [];
  const needle = 'getNavigation().moveTo(';
  for (let at = code.indexOf(needle); at >= 0; at = code.indexOf(needle, at + 1)) {
    let depth = 1;
    let i = at + needle.length;
    for (; i < code.length && depth > 0; i++) {
      if (code[i] === '(') depth++;
      else if (code[i] === ')') depth--;
    }
    calls.push(code.slice(at + needle.length, i - 1));
  }
  return calls;
}
const calls = moveToCalls(brain);
console.log(`  (${calls.length} moveTo call sites)`);
for (const { speed, count, why } of EXPECTED_SPEEDS) {
  const actual = calls.filter((args) => args.trim().endsWith(speed)).length;
  check(actual === count, `${count}x speed ${speed}`, `found ${actual} - ${why}`);
}
const known = new Set(EXPECTED_SPEEDS.map((e) => e.speed));
const unknown = calls.filter((args) => {
  const last = args.split(',').pop().trim();
  return !known.has(last);
});
check(unknown.length === 0, 'no undocumented moveTo speed',
  unknown.length === 0 ? 'every call site is in the table' : `undocumented: ${unknown.join(' | ')}`);
check(/private double coverSeekSpeed\(\)\s*\{\s*return Config\.COVER_SEEK_SPEED_MODIFIER\.get\(\) \* AiProfile\.coverSeekSpeedScale\(this\.mob\);\s*\}/.test(brain),
  'coverSeekSpeed() is the only reader of the multiplier',
  'since README 5aa it is scaled by the tier profile (scale 1.0 for every tier except ELITE 1.15)');
const multiplierReads = (brain.match(/Config\.COVER_SEEK_SPEED_MODIFIER\.get\(\)/g) || []).length;
check(multiplierReads === 1, 'no other code path reads coverSeekSpeedModifier',
  `${multiplierReads} read site(s) in GunBrain`);
const sprintTrue = (brain.match(/setSprinting\(true\)/g) || []).length;
const sprintFalse = (brain.match(/setSprinting\(false\)/g) || []).length;
check(sprintTrue === 0 && sprintFalse === 4, 'sprinting is never forced on',
  `setSprinting(true)=${sprintTrue}, setSprinting(false)=${sprintFalse} (ALERT/AIM/FIRE + the IS_SPRINTING ShootResult)`);
check(/setSprinting\(Config\.RETREAT_SPRINT\.get\(\)\)/.test(tickRetreat),
  'the retreat sprint is config-driven and off by default',
  'unconditional sprinting was the hidden multiplier behind "they escape absurdly fast"');
check(/escapeWithoutCoverSpeed\(\)/.test(tickRetreat) && !/1\.25D/.test(tickRetreat),
  'the no-cover escape uses its own key, not the old hard-coded 1.25');

console.log('');
console.log('the config keys, with the documented defaults');
const keyDefaults = [
  ['fireStallTicks', /defineInRange\("fireStallTicks", 100,/],
  ['reloadStallTicks', /defineInRange\("reloadStallTicks", 200,/],
  ['coverSeekSpeedModifier', /defineInRange\("coverSeekSpeedModifier", 1\.0D,/],
  ['escapeWithoutCoverSpeedModifier', /defineInRange\("escapeWithoutCoverSpeedModifier", 1\.0D,/],
  ['retreatSprint', /\.define\("retreatSprint", false\)/],
];
for (const [key, re] of keyDefaults) {
  check(re.test(config), `Config defines ${key} with the documented default`);
  check(config.includes(`"${key}"`), `Config declares the ${key} spec value`);
  check(readme.includes(key), `README documents ${key}`, 'AssetTest requires every config key to be documented');
}

console.log('');
console.log('the runtime regression gates (what the dedicated-server test runs)');
check(/Commands\.literal\("watch"\)/.test(commands) && /Commands\.literal\("stall"\)/.test(commands),
  '/tarkovscav test watch and test stall are registered');
check(commands.includes('WATCH_MIN_MOVED_BLOCKS') && commands.includes('WATCH_MIN_SHOTS'),
  'the watch gate asserts on moved blocks and shots');
check(/WATCH_MIN_MOVED_BLOCKS = 1\.0D/.test(commands), 'the movement floor is 1.0 blocks',
  'the bug it catches is a mob that moves 0.00');
check(/shotsFired\(\)/.test(commands) && /stallEscapes\(\)/.test(commands), 'it reads the brain counters');
check(/simulateShotFailures\(/.test(commands) && brain.includes('simulateShotFailureTicks'),
  'the stall gate injects the refused-shot condition');
check(commands.includes('[test] WATCH'), 'the watch verdict is written to the server log');
check(commands.includes('[test] STALL'), 'the stall verdict is written to the server log');

console.log('');
if (failures > 0) {
  console.log(`${failures} anti-stall check(s) FAILED`);
  process.exit(1);
}
console.log('anti-stall + cover-speed invariants all hold');
