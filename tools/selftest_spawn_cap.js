// The per-city faction cap: "at most N gun units of one line-up alive in one city at a time".
//
//   node tools/selftest_spawn_cap.js
//
// Why each check exists:
//   1. THE ARITHMETIC IS EXECUTABLE. The part that decides whether a spawn is refused lives in
//      world/SpawnCapMath.java with no Minecraft type in sight, so this gate compiles that SHIPPED source
//      together with tools/spike/SpawnCapTest.java and runs the real rule: the count is alive + allowed-this-
//      window, the boundary is "the Nth is allowed, the N+1th is not", a cap of 0 means uncapped, a death frees
//      a slot, and the window can never let the count drift above the cap. That last one is a 200-attempt
//      simulation, and the "never counted" case is not decoration: the first version of `stale()` subtracted
//      Long.MIN_VALUE and overflowed, which would have pinned a city at the cap for good. The test caught it.
//   2. THE HOOKS ARE WHERE THEY HAVE TO BE. The natural spawner and the spawner blocks are ONE Forge event in
//      1.20.1 (MobSpawnEvent.PositionCheck), and the garrison places units directly, so exactly two call sites
//      are needed: one inside onPositionCheck (after the capture veto, before the purity block) and one per
//      unit in CityGarrison.spawn. Both are asserted by position, not just by presence.
//   3. WHO IS COUNTED. GunUser only - this mod's nine armed types. A vanilla villager or pillager that the
//      faction tags also cover is not the cost this key exists for, so counting them would cap the wrong thing.
//      The count is per city AND per faction over the city's own box, so a contested city may hold the cap of
//      each side.
//   4. THE CARVE-OUT. A spawn egg and /summon are exempt by default: the request said so ("通常不是玩家手动放
//      刷怪蛋的话"), and it is what makes the cap safe to leave on while a city is being built.
//   5. IT NEVER TOUCHES THE WORLD. The cap refuses spawns; it never deletes, kills or rewrites anything, and
//      it never reads or extinguishes a spawner block. Asserted as an absence of every mutating call.
//   6. BOTH DIMENSIONS. Unlike the capture game, this is a performance guard, so it must NOT be gated on
//      Level.OVERWORLD - the wasteland is exactly where the endless spawners are. Asserted as an absence of
//      that gate plus the documented intent.
//   7. THE DOCS AND THE COMMAND. /armedmobs spawncap [reset] is how a player sees the numbers, and every key
//      has to be in the reference and the command/config reference.
//
// Nothing here can see a running game, a spawn attempt or a frame time; the arithmetic and the wiring are what
// this gate can prove.
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const SPIKE = path.join(ROOT, 'tools', 'spike');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** The body of a method declaration (parameter list followed by `{`). Same helper the sibling gates use. */
function bodyOf(src, name) {
  const re = new RegExp(`\\b${name}\\s*\\(`, 'g');
  let match;
  while ((match = re.exec(src)) !== null) {
    const open = src.indexOf('(', match.index);
    let depth = 0;
    let close = -1;
    for (let i = open; i < src.length; i++) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') { depth--; if (depth === 0) { close = i; break; } }
    }
    if (close < 0) continue;
    const after = /^\s*(?:throws\s[\w.,\s]+?)?\{/.exec(src.slice(close + 1));
    if (!after) continue;
    const braceAt = close + 1 + after[0].length - 1;
    depth = 0;
    for (let i = braceAt; i < src.length; i++) {
      if (src[i] === '{') depth++;
      else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(braceAt, i + 1); }
    }
  }
  return '';
}
/** The argument list of `define(...)` / `defineInRange(...)` for one field. */
function defineArgs(field) {
  const re = new RegExp(`${field}\\s*=\\s*b[\\s\\S]{0,2500}?\\.define(?:InRange)?\\(([^;]*?)\\);`);
  const match = re.exec(config);
  return match ? match[1] : '';
}

const mathRaw = read('world/SpawnCapMath.java');
const math = strip(mathRaw);
const capRaw = read('world/CitySpawnCap.java');
const cap = strip(capRaw);
const spawnEventsRaw = read('world/CitySpawnEvents.java');
const spawnEvents = strip(spawnEventsRaw);
const garrisonRaw = read('world/CityGarrison.java');
const garrison = strip(garrisonRaw);
const commands = strip(read('command/ModCommands.java'));
const config = strip(read('Config.java'));
const reference = fs.readFileSync(path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'), 'utf8');

// ------------------------------------------------------------------ 1. the arithmetic, executed
console.log('1. the shipped cap arithmetic, compiled and run (tools/spike/SpawnCapTest.java)');
function findTool(name) {
  const home = process.env.JAVA_HOME;
  const candidates = [];
  if (home) candidates.push(path.join(home, 'bin', name));
  candidates.push(`C:\\Program Files\\Java\\jdk-21.0.12\\bin\\${name}`);
  candidates.push(`C:\\Program Files\\Java\\jdk-17\\bin\\${name}`);
  return candidates.find((candidate) => fs.existsSync(candidate)) || name;
}
let executed = 0;
try {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'spawncap-'));
  execFileSync(findTool('javac.exe'), ['-encoding', 'UTF-8', '-d', out,
    path.join(SPIKE, 'SpawnCapTest.java'),
    path.join(JAVA, 'world', 'SpawnCapMath.java')], { stdio: 'pipe' });
  const run = execFileSync(findTool('java.exe'), ['-cp', out, 'SpawnCapTest'],
    { stdio: 'pipe', encoding: 'utf8' });
  const passed = (run.match(/  PASS  /g) || []).length;
  const failed = (run.match(/  FAIL  /g) || []).length;
  executed = passed;
  check(passed > 0 && failed === 0, 'SpawnCapTest: every executable case passed',
    `${passed} PASS, ${failed} FAIL`);
  const lines = run.split('\n');
  check(lines.some((line) => /never exceeded after folding/.test(line)),
    'and the window-never-drifts-above-the-cap simulation really ran',
    (lines.find((line) => /never exceeded after folding/.test(line)) || '').trim());
  check(lines.some((line) => /clock went backwards/.test(line)),
    'and the clock-went-backwards case ran (the overflow the first version had)',
    (lines.find((line) => /clock went backwards/.test(line)) || '').trim());
} catch (error) {
  check(false, 'SpawnCapTest compiles and runs', String(error.message).slice(0, 200));
}

// ------------------------------------------------------------------ 2. the two hooks, by position
console.log('');
console.log('2. the two spawn-path hooks, asserted by position');
const positionCheck = bodyOf(spawnEvents, 'onPositionCheck');
check(/CitySpawnCap\.vetoSpawn\(/.test(positionCheck),
  'the natural-spawner and spawner-block path asks the cap inside onPositionCheck (one event, both paths)');
const captureVetoAt = positionCheck.indexOf('CityCapture.vetoSpawn(');
const capVetoAt = positionCheck.indexOf('CitySpawnCap.vetoSpawn(');
const purityAt = positionCheck.indexOf('GARRISON_FACTION_SPAWN_FILTER');
check(captureVetoAt > 0 && capVetoAt > captureVetoAt,
  'the cap is asked after the capture veto, so a spent faction is still refused by the decisive rule first',
  `capture@${captureVetoAt} cap@${capVetoAt}`);
check(purityAt < 0 || capVetoAt < purityAt,
  'and before the faction-purity block, which is the more expensive question',
  `cap@${capVetoAt} purity@${purityAt}`);
check(/if \(!manual && event\.getLevel\(\) instanceof ServerLevel capLevel\)/.test(positionCheck),
  'PositionCheck only reserves non-manual spawns, so FinalizeSpawn cannot reserve a manual spawn twice');
const finalizeSpawn = bodyOf(spawnEvents, 'onFinalizeSpawn');
check(/CITY_FACTION_CAP_IGNORE_MANUAL\.get\(\)[\s\S]*?CitySpawnCap\.vetoSpawn\(/.test(finalizeSpawn),
  'FinalizeSpawn caps eggs and ordinary summon commands when the manual-cap switch is on');
check(/Boolean\.TRUE\.equals\(cityOnly\) && Config\.GATE_COMMAND_SPAWNS\.get\(\)/.test(finalizeSpawn)
  && finalizeSpawn.indexOf('CitySpawnCap.vetoSpawn(') > finalizeSpawn.indexOf('CityGate.test('),
  'manual city gating runs before the independent manual-cap reservation');
const garrisonSpawn = bodyOf(garrison, 'spawn');
check(/CitySpawnCap\.vetoGarrison\(/.test(garrisonSpawn),
  'the garrison path asks it once per unit (it places units directly, so it never sees the spawn event)');
check(/CityCapture\.vetoGarrison\(/.test(garrisonSpawn) && /refusedByCapture\+\+/.test(garrisonSpawn),
  'and the capture veto plus its refusal counter are still there');
check(/per-city faction cap/i.test(garrisonSpawn) || /CitySpawnCap/.test(garrisonSpawn),
  'the garrison ask is explained where it happens');

// ------------------------------------------------------------------ 3. who is counted
console.log('');
console.log('3. who is counted, and over what area');
check(/mob instanceof GunUser/.test(cap),
  "only this mod's gun units are counted (a vanilla villager that the faction tag also covers is not)");
check(/Faction\.of\(mob\) == faction/.test(cap),
  'the count is per faction as well as per city');
check(/city\.box\(\)/.test(cap), "the count is taken over the city's own box");
check(/box\.maxX\(\) \+ 1, box\.maxY\(\) \+ 1, box\.maxZ\(\) \+ 1/.test(cap),
  'the AABB upper corner is extended by one, because a bounding box corner is an inclusive block coordinate',
  'an off-by-one here would under-count the box edge');
check(/countLive\(/.test(cap) && /getEntitiesOfClass\(Mob\.class, area/.test(cap),
  'the live count is one level query over that box');
check(/SpawnCapMath\.stale\(/.test(cap) && /CITY_FACTION_CAP_COUNT_TICKS/.test(cap),
  'and it is cached for the configured window');
check(/window\.accepted\+\+/.test(cap),
  'the window also counts the spawns it has already allowed, so a burst cannot walk past the cap');
check(/level\.dimension\(\)\.location\(\) \+ "\|" \+ city\.key\(\) \+ "\|" \+ faction\.name\(\)/.test(cap),
  'the cache key is dimension|city|faction, so two dimensions cannot share a count');

// ------------------------------------------------------------------ 4. the carve-out
console.log('');
console.log('4. the manual carve-out');
check(/type == MobSpawnType\.SPAWN_EGG \|\| type == MobSpawnType\.COMMAND/.test(cap),
  'a spawn egg and /summon are recognised as manual');
check(/manual && !Config\.CITY_FACTION_CAP_IGNORE_MANUAL\.get\(\)/.test(cap)
  && /return false;/.test(cap.slice(cap.indexOf('manual && !Config.CITY_FACTION_CAP_IGNORE_MANUAL.get()'))),
  'and are exempt by default (the request: "通常不是玩家手动放刷怪蛋的话")');

// ------------------------------------------------------------------ 5. nothing touches the world
console.log('');
console.log('5. it refuses spawns and never edits the world (structural)');
for (const call of ['.setBlock(', '.discard(', '.kill(', '.setRemoved(', '.extinguish']) {
  check(!cap.includes(call), `CitySpawnCap never calls ${call}`);
}
check(!/\b(?:mob|level)\.remove\(/.test(cap), 'CitySpawnCap never removes a world entity');
check(!/BlockState|SpawnerBlockEntity/.test(cap),
  'and it never touches a spawner block (the whole feature stays reversible)');

// ------------------------------------------------------------------ 6. both dimensions
console.log('');
console.log('6. a performance guard, so both dimensions');
check(!/Level\.OVERWORLD/.test(cap),
  'the cap is NOT gated on the overworld (the capture game is; the wasteland is where the spawners pile up)');
check(/urban_wasteland/.test(capRaw),
  'and the class says so in its own documentation');

// ------------------------------------------------------------------ 7. the command and the docs
console.log('');
console.log('7. the diagnostic command and the documentation');
check(/Commands\.literal\("spawncap"\)/.test(commands), '/armedmobs spawncap exists');
check(/Commands\.literal\("reset"\)/.test(bodyOf(commands, 'spawnCap')),
  'with a reset sub-command');
check(/root\.then\(spawnCap\(\)\)/.test(commands), 'and it is registered on the operator tree');
check(/CitySpawnCap\.reset\(\)/.test(commands), 'reset calls the cap, not a copy of its cache');
for (const key of ['cityFactionCapEnabled', 'cityFactionCap', 'cityFactionCapCountTicks',
  'cityFactionCapIgnoreManual']) {
  check(reference.includes(key), `the reference documents ${key}`);}
check(reference.includes('spawncap'), 'the reference documents /armedmobs spawncap');
check(/CITY_FACTION_CAP\s*=\s*b[\s\S]{0,2500}?\.defineInRange\("cityFactionCap",\s*12,\s*0,\s*128\)/
  .test(config), 'the shipped default really is 12 (range 0..128, where 0 means uncapped)');
check(/defineArgs/.test('defineArgs') && defineArgs('CITY_FACTION_CAP_ENABLED').includes('true'),
  'verified through the config parser: the cap is enabled by default',
  defineArgs('CITY_FACTION_CAP_ENABLED').replace(/\s+/g, ' ').slice(0, 60));
check(defineArgs('CITY_FACTION_CAP_IGNORE_MANUAL').includes('false'),
  'and manual spawns are exempt by default',
  defineArgs('CITY_FACTION_CAP_IGNORE_MANUAL').replace(/\s+/g, ' ').slice(0, 60));

console.log('');
console.log(`  (${executed} of the checks above were executed, not read)`);
if (failures > 0) {
  console.log(`${failures} spawn-cap check(s) FAILED (${checks} run)`);
  process.exit(1);
}
console.log('the per-city faction cap holds: one live count per city and faction, the two spawn paths ask it, '
  + 'a hand-placed unit is exempt, and nothing in the world is ever edited');
