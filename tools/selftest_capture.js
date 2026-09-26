// The city-capture gate (README 7p): "occupying cities only exists in the overworld"; "units of a few
// line-ups keep spawning until one side's strength is gone"; "the wasteland is a free-for-all, no capture
// needed".
//
//   node tools/selftest_capture.js
//
// This gate EXTENDS tools/selftest_garrison.js and tools/selftest_city_faction.js: it reuses their city
// fixtures (the seed, the city key and the building ids are parsed straight out of selftest_city_faction.js)
// and their ledger shape, and adds the one thing neither of them covers - the strength pools. It does not
// edit either file.
//
// WHAT IS EXECUTABLE EVIDENCE HERE
//   Section 2 compiles and RUNS tools/spike/CaptureTest.java against the SHIPPED
//   src/main/java/com/gfl/tarkovscav/world/CapturePools.java and folds every PASS/FAIL line it prints into
//   this report. That is the real pool arithmetic - size from building count, both clamps, the drain floor,
//   the exact-zero capture decision and the ledger key shape - with no world present.
//
// WHAT IS STRUCTURAL EVIDENCE, AND LABELLED AS SUCH
//   Sections 3-8 read the sources. They prove the call sites and the ledger fields, not behaviour: the
//   overworld gate on every entry point, the three spawn-path vetoes, the no-re-roll rule, the ledger
//   persistence, the HUD field sync and the command wiring. Nothing here can see a running game, a HUD or a
//   firefight.
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
const skip = (label, detail) => console.log(`  SKIP  ${label}${detail ? '  ' + detail : ''}`);

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
/** The body of the first `name(...) {` at or after `marker` - for classes with two same-named methods. */
function bodyAfter(src, marker, name) {
  const at = src.indexOf(marker);
  return at < 0 ? '' : bodyOf(src.slice(at), name);
}
/** The value of `define("key", <literal>)` / `defineInRange("key", <literal>, ...)` in Config.java. */
function configDefault(name, key) {
  const re = new RegExp(`${name}\\s*=\\s*b[\\s\\S]{0,2000}?\\.define(?:InRange)?\\("${key}",\\s*([^,)]+)`);
  const match = re.exec(config);
  return match ? match[1].trim() : null;
}

const capturePoolsRaw = read('world/CapturePools.java');
const capturePools = strip(capturePoolsRaw);
const captureRaw = read('world/CityCapture.java');
const capture = strip(captureRaw);
const network = strip(read('world/CaptureHudNetwork.java'));
const ledgerRaw = read('world/GarrisonData.java');
const ledger = strip(ledgerRaw);
const garrison = strip(read('world/CityGarrison.java'));
const spawnEventsRaw = read('world/CitySpawnEvents.java');
const spawnEvents = strip(spawnEventsRaw);
const hud = strip(read('client/CaptureHud.java'));
const clientSetup = strip(read('client/ClientSetup.java'));
const commands = strip(read('command/ModCommands.java'));
const config = strip(read('Config.java'));
const mod = strip(read('TarkovScav.java'));

// The fixtures are read out of the two gates this one extends, so the three cannot drift apart.
const factionGate = fs.readFileSync(path.join(ROOT, 'tools', 'selftest_city_faction.js'), 'utf8');
const garrisonGate = fs.readFileSync(path.join(ROOT, 'tools', 'selftest_garrison.js'), 'utf8');
const fixture = (src, name) => {
  const match = new RegExp(`const\\s+${name}\\s*=\\s*'([^']+)'`).exec(src);
  return match ? match[1] : null;
};
const CITY_A = fixture(factionGate, 'CITY_A');
const DIM = fixture(factionGate, 'DIM');
const SEED = Number((/const SEED = (\d+)n/.exec(factionGate) || [])[1]);
const BUILDING_IDS = ((/const BUILDING_IDS = \[([^\]]+)\]/.exec(factionGate) || [])[1] || '')
  .split(',').map((entry) => entry.trim().replace(/^'|'$/g, '')).filter(Boolean);
const WASTELAND = fixture(garrisonGate, 'wasteland');

// ------------------------------------------------------------------ 1. the config keys

console.log('1. the capture config keys exist, with the shipped defaults (structural)');
check(configDefault('CAPTURE_ENABLED', 'enabled') === 'true', 'capture.enabled ships true',
  String(configDefault('CAPTURE_ENABLED', 'enabled')));
check(configDefault('CAPTURE_HUD_ENABLED', 'hudEnabled') === 'true', 'capture.hudEnabled ships true');
check(configDefault('CAPTURE_POOL_MIN', 'poolMin') === '20', 'capture.poolMin ships 20',
  String(configDefault('CAPTURE_POOL_MIN', 'poolMin')));
check(configDefault('CAPTURE_POOL_MAX', 'poolMax') === '100', 'capture.poolMax ships 100',
  String(configDefault('CAPTURE_POOL_MAX', 'poolMax')));
check(configDefault('CAPTURE_POOL_PER_BUILDING', 'poolPerBuilding') === '2',
  'capture.poolPerBuilding ships 2');
check(configDefault('CAPTURE_DRAIN_PER_KILL', 'drainPerKill') === '1', 'capture.drainPerKill ships 1');
check(configDefault('CAPTURE_PLAYER_KILLS_ONLY', 'playerKillsOnly') === 'false',
  'capture.playerKillsOnly ships false (any death drains)');
check(configDefault('CAPTURE_HUD_HIDE_DELAY_SECONDS', 'hudHideDelaySeconds') === '8',
  'capture.hudHideDelaySeconds ships 8');
check(/push\("capture"\)/.test(config), 'all eight live in their own [capture] section');

// ------------------------------------------------------------------ 2. the executable arithmetic

console.log('');
console.log('2. the pool arithmetic, compiled and run from the shipped CapturePools (EXECUTABLE)');
const JDKS = [
  process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin'),
  'C:\\Program Files\\Java\\jdk-21.0.12\\bin',
  'C:\\Program Files\\Java\\jdk-21.0.11\\bin',
  'C:\\Program Files\\Common Files\\Oracle\\Java\\javapath',
  'D:\\deepseek\\GirlsFrontline\\.toolchain\\jdk-17\\bin',
].filter(Boolean);
function tool(name) {
  for (const dir of JDKS) {
    const candidate = path.join(dir, `${name}.exe`);
    if (fs.existsSync(candidate)) return candidate;
  }
  return name;
}
const capturePoolsSource = path.join(JAVA, 'world', 'CapturePools.java');
if (!fs.existsSync(path.join(SPIKE, 'CaptureTest.java')) || !fs.existsSync(capturePoolsSource)) {
  skip('the executable pool arithmetic', 'tools/spike/CaptureTest.java or CapturePools.java is missing');
} else {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'tarkovscav-capture-'));
  try {
    execFileSync(tool('javac'), ['-encoding', 'UTF-8', '-d', out,
      path.join(SPIKE, 'CaptureTest.java'), capturePoolsSource], {
      cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    const output = execFileSync(tool('java'), ['-cp', out, 'CaptureTest'], {
      cwd: ROOT, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
    let ran = 0;
    // Split on \r?\n: Java on Windows writes CRLF, and a trailing \r would defeat the `$` anchor below.
    for (const line of output.split(/\r?\n/)) {
      const match = /^\s*(PASS|FAIL)\s+(.*)$/.exec(line);
      if (match) {
        ran++;
        check(match[1] === 'PASS', match[2], 'CaptureTest (executable)');
      }
    }
    check(ran > 0, 'CaptureTest actually ran and printed cases', `${ran} case(s)`);
    if (ran === 0) {
      console.log(output.slice(0, 2000));
    }
  } catch (error) {
    const detail = String(error.stderr || error.stdout || error.message).replace(/\s+/g, ' ').slice(0, 600);
    check(false, 'CaptureTest compiles and runs the shipped CapturePools', detail);
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
}
// The source the spike ran must be the formula the design names, not a coincidence.
const sizeBody = bodyOf(capturePools, 'poolSize').replace(/\s+/g, ' ');
check(/floor \+ buildings \* perBuilding/.test(sizeBody),
  'poolSize is poolMin + buildingCount * poolPerBuilding', sizeBody.slice(0, 140));
check(/Math\.max\(low, Math\.min\(high, value\)\)/.test(sizeBody),
  'and it clamps into the normalised [low, high] range');
check(/Math\.min\(floor, ceiling\)/.test(sizeBody) && /Math\.max\(floor, ceiling\)/.test(sizeBody),
  'a hand-edited toml with poolMin > poolMax is normalised, never inverted');
const drainBody = bodyOf(capturePools, 'drain').replace(/\s+/g, ' ');
check(/Math\.max\(0L, \(long\) strength - per\)/.test(drainBody),
  'drain() floors at zero in long arithmetic', drainBody.slice(0, 140));
check(/strength <= 0/.test(bodyOf(capturePools, 'isCaptured')),
  'isCaptured() decides at EXACTLY zero (<= 0, so a negative ledger row still counts)');
check(/dimension \+ "\|" \+ cityKey \+ "\|" \+ factionName/.test(bodyOf(capturePools, 'poolKey')),
  'the pool key is <dimension>|<city>|<faction>');

// ------------------------------------------------------------------ 3. the ledger

console.log('');
console.log('3. the pools and the captured flag live in the same SavedData file (structural)');
check(/class GarrisonData extends SavedData/.test(ledger), 'GarrisonData is still the SavedData');
check(/DATA_NAME\s*=\s*"tarkovscav_garrison"/.test(ledger),
  'the file name is unchanged, so the pools land in the world\'s existing ledger file');
check(/server\.overworld\(\)\.getDataStorage\(\)/.test(ledger),
  'the ledger is still fetched from the world data storage (so it survives a restart)');
const nbt = {};
for (const name of ['KEY_KEY', 'KEY_CITY', 'KEY_DIMENSION', 'KEY_POOLS', 'KEY_FACTION', 'KEY_STRENGTH',
  'KEY_MAX', 'KEY_CAPTURED']) {
  const match = new RegExp(`${name}\\s*=\\s*"([^"]+)"`).exec(ledger);
  nbt[name] = match ? match[1] : null;
}
check(Object.values(nbt).every(Boolean), 'every pool NBT field name is a literal', JSON.stringify(nbt));
check(nbt.KEY_POOLS === 'pools' && nbt.KEY_STRENGTH === 'strength' && nbt.KEY_MAX === 'max'
  && nbt.KEY_CAPTURED === 'captured' && nbt.KEY_FACTION === 'faction',
  'the field names are pools/faction/strength/max/captured', JSON.stringify(nbt));
const saveBody = bodyOf(ledger, 'save');
check(/tag\.put\(KEY_POOLS, poolList\)/.test(saveBody), 'save() writes the pool list');
for (const field of ['KEY_FACTION', 'KEY_STRENGTH', 'KEY_MAX', 'KEY_CAPTURED']) {
  check(new RegExp(`row\\.put(Boolean|Int|String)\\(${field}`).test(saveBody),
    `save() writes ${field}`);
}
const loadBody = bodyOf(ledger, 'load');
check(/tag\.getList\(KEY_POOLS, Tag\.TAG_COMPOUND\)/.test(loadBody), 'load() reads the pool list back');
for (const field of ['KEY_FACTION', 'KEY_STRENGTH', 'KEY_MAX', 'KEY_CAPTURED']) {
  check(new RegExp(`row\\.get(Boolean|Int|String)\\(${field}`).test(loadBody),
    `load() reads ${field}`);
}
check(/data\.pools\.computeIfAbsent/.test(loadBody),
  'load() rebuilds the per-city pool map, so a restart keeps every pool');
check(/public static String poolKey\(ResourceLocation dimension, String cityKey, Faction faction\)[\s\S]{0,200}?CapturePools\.poolKey/
  .test(ledger), 'GarrisonData.poolKey delegates to the one key shape the spike pins');
const createBody = bodyOf(ledger, 'createPools');
check(/this\.pools\.containsKey\(entryKey\)/.test(createBody) && /return false;/.test(createBody),
  'createPools() refuses a city that already has pools (no accidental rebuild)');
check(/setDirty\(\)/.test(createBody), 'createPools() marks the ledger dirty');
const drainPoolBody = bodyOf(ledger, 'drainPool');
check(/row\.captured\(\)/.test(drainPoolBody) && /return -1;/.test(drainPoolBody),
  'drainPool() refuses to drain an already spent pool');
check(/CapturePools\.drain\(row\.strength\(\), amount\)/.test(drainPoolBody),
  'drainPool() uses the shipped drain arithmetic, not a second copy');
check(/setDirty\(\)/.test(drainPoolBody), 'drainPool() marks the ledger dirty');
check(/setDirty\(\)/.test(bodyOf(ledger, 'setPoolStrength'))
  && /setDirty\(\)/.test(bodyOf(ledger, 'clearPools')),
  'the force and the reset both mark the ledger dirty');
check(/if \(this\.cities\.containsKey\(entryKey\)\) \{\s*return;/.test(ledger),
  'recordCity() still early-returns, so the line-up can never be re-rolled');

// The persistence mirror, driven with the Java's own field names: a captured pool must survive a restart.
console.log('');
console.log('   the pool persistence, as a mirror driven through a save/reload');
const poolKeyOf = (dimension, city, faction) => `${dimension}|${city}|${faction}`;
function newLedger() { return { pools: new Map(), citiesWithPools: new Set() }; }
function createPools(l, dim, city, factions, size) {
  // The real ledger keys the pool rows by faction inside a per-city map; the mirror keeps the same
  // "does this city have pools" answer in a set so the check is the same one the Java makes.
  if (l.citiesWithPools.has(`${dim}|${city}`)) return false;
  for (const faction of factions) {
    l.pools.set(poolKeyOf(dim, city, faction), { dim, city, faction, strength: size, max: size, captured: false });
  }
  l.citiesWithPools.add(`${dim}|${city}`);
  return true;
}
function drain(l, dim, city, faction, amount) {
  const row = l.pools.get(poolKeyOf(dim, city, faction));
  if (!row || row.captured) return -1;
  row.strength = Math.max(0, row.strength - amount);
  row.captured = row.strength <= 0;
  return row.strength;
}
function serialise(l) {
  return [...l.pools.values()].map((row) => ({
    [nbt.KEY_KEY]: poolKeyOf(row.dim, row.city, row.faction),
    [nbt.KEY_CITY]: row.city,
    [nbt.KEY_DIMENSION]: row.dim,
    [nbt.KEY_FACTION]: row.faction,
    [nbt.KEY_STRENGTH]: row.strength,
    [nbt.KEY_MAX]: row.max,
    [nbt.KEY_CAPTURED]: row.captured,
  }));
}
function deserialise(rows) {
  const l = newLedger();
  for (const row of rows) {
    l.pools.set(row[nbt.KEY_KEY], {
      dim: row[nbt.KEY_DIMENSION], city: row[nbt.KEY_CITY], faction: row[nbt.KEY_FACTION],
      strength: row[nbt.KEY_STRENGTH], max: row[nbt.KEY_MAX], captured: row[nbt.KEY_CAPTURED],
    });
    l.citiesWithPools.add(`${row[nbt.KEY_DIMENSION]}|${row[nbt.KEY_CITY]}`);
  }
  return l;
}
check(CITY_A && DIM && SEED && BUILDING_IDS.length >= 4,
  'the city fixtures were reused from selftest_city_faction.js',
  `${DIM} ${CITY_A} seed=${SEED} buildings=${BUILDING_IDS.join(',')}`);

const FACTIONS = ['village', 'illager'];
let book = newLedger();
check(createPools(book, DIM, CITY_A, FACTIONS, 28) === true, 'the first approach builds one pool per faction');
check(createPools(book, DIM, CITY_A, FACTIONS, 999) === false,
  'a second approach rebuilds nothing (and would not resize anything even if it tried)');
check(book.pools.get(poolKeyOf(DIM, CITY_A, 'village')).strength === 28,
  'the recorded pool is the first approach\'s size');
for (let kill = 0; kill < 28; kill++) drain(book, DIM, CITY_A, 'village', 1);
const spent = book.pools.get(poolKeyOf(DIM, CITY_A, 'village'));
check(spent.strength === 0 && spent.captured === true,
  '28 kills take the village pool to exactly zero and mark it captured');
check(drain(book, DIM, CITY_A, 'village', 5) === -1, 'a spent pool refuses further draining');
check(book.pools.get(poolKeyOf(DIM, CITY_A, 'village')).strength === 0,
  'and stays at zero (never negative)');
const reloaded = deserialise(serialise(book));
const afterRestart = reloaded.pools.get(poolKeyOf(DIM, CITY_A, 'village'));
check(afterRestart.strength === 0 && afterRestart.captured === true,
  'the spent pool and its captured flag survive a save + reload');
check(reloaded.pools.get(poolKeyOf(DIM, CITY_A, 'illager')).strength === 28,
  'the surviving faction keeps its strength across the reload');
check(createPools(reloaded, DIM, CITY_A, FACTIONS, 28) === false,
  'approaching the captured city again rebuilds nothing (not reversible by accident)');
check(!reloaded.pools.has(poolKeyOf(WASTELAND, CITY_A, 'village')),
  'the wasteland city key has no pool of its own (the ledger is dimension-aware)');

// ------------------------------------------------------------------ 4. the overworld gate

console.log('');
console.log('4. every entry point is behind the overworld check (structural)');
check(/level\.dimension\(\)\.equals\(Level\.OVERWORLD\)/.test(bodyOf(capture, 'isOverworld')),
  'isOverworld() is a real dimension comparison, not a name match');
const entryPoints = ['ensurePools', 'drain', 'vetoSpawn', 'vetoGarrison', 'syncHud',
  'syncHudNearby', 'resetPools', 'setPool'];
for (const name of entryPoints) {
  check(/isOverworld\(/.test(bodyOf(capture, name)), `${name}() checks isOverworld`);
}
check(/isOverworld\(level\)/.test(bodyOf(capture, 'onDeath')),
  'the kill drain checks isOverworld');
check(!/urban_wasteland|WASTELAND/.test(capture),
  'CityCapture has no wasteland special case: the wasteland city simply never gets a pool');
check(/!minecraft\.level\.dimension\(\)\.equals\(Level\.OVERWORLD\)/.test(bodyOf(hud, 'render')),
  'the HUD render also refuses to draw outside the overworld (the client-side half of the rule)');
check(/CityCapture\.ensurePools\(level, city, data\)/.test(bodyOf(garrison, 'factionFor')),
  'pool creation rides the existing garrison trigger (factionFor), with the gate inside CityCapture');

// ------------------------------------------------------------------ 5. the three vetoes

console.log('');
console.log('5. the three spawn paths are each vetoed (structural)');
const positionCheck = bodyOf(spawnEvents, 'onPositionCheck');
check(/MobSpawnEvent\.PositionCheck/.test(spawnEventsRaw),
  'the natural/spawner hook is MobSpawnEvent.PositionCheck');
const vetoAt = positionCheck.indexOf('CityCapture.vetoSpawn(');
const denyAt = positionCheck.indexOf('event.setResult(Event.Result.DENY)', vetoAt);
check(vetoAt >= 0, 'onPositionCheck calls CityCapture.vetoSpawn');
check(denyAt > vetoAt, 'and the very next decision denies the spawn');
check(/!manual && event\.getLevel\(\) instanceof ServerLevel captureLevel/.test(positionCheck),
  'the veto is skipped for a manual /summon or spawn egg, exactly like the city gate');
check(/event\.getSpawnType\(\)/.test(positionCheck),
  'the spawn type travels into the veto, so a spawner refusal can be told from a natural one');
check(!/LivingSpawnEvent|SpecialSpawn/.test(spawnEvents),
  'the executed spawn code does not use LivingSpawnEvent.SpecialSpawn (it does not exist in Forge 1.20.1)');
const spawnBody = bodyOf(garrison, 'spawn');
const garrisonVetoAt = spawnBody.indexOf('CityCapture.vetoGarrison(');
const placeAt = spawnBody.indexOf('place(level, spot, isLeader, squadId, random, faction)');
check(garrisonVetoAt >= 0, 'the garrison path calls CityCapture.vetoGarrison');
check(placeAt > garrisonVetoAt, 'and the veto is asked BEFORE the unit is placed');
check(/refusedByCapture\+\+/.test(spawnBody),
  'a refused garrison unit is counted, so "nothing could be placed" says which rule did it');
const vetoBody = bodyOf(capture, 'vetoSpawn');
check(/pool\.captured\(\)/.test(vetoBody), 'the spawn veto reads the recorded captured flag');
check(/capturedPoolCount\(\) == 0/.test(vetoBody),
  'and short-circuits while nothing is spent, so the hot spawn path pays no city lookup early on');
check(/pool\(level\.dimension\(\)\.location\(\), city\.key\(\), faction\)/.test(vetoBody),
  'the veto reads the same ledger row the drain writes, per dimension and per faction');
check(/CityCapture\.vetoGarrison\(level, city\.key\(\), faction\)/.test(spawnBody),
  'the garrison veto is asked per unit with the unit\'s own faction');

console.log('');
console.log('   the spawner half of that veto: Forge 1.20.1 evidence (executable, if the jars are present)');
/**
 * The design doc names LivingSpawnEvent.SpecialSpawn. This proves, from the Forge artifacts the build
 * actually resolved, what fires for a spawner in this version: the class is gone, and the patched
 * BaseSpawner calls checkSpawnPositionSpawner -> MobSpawnEvent.PositionCheck.
 */
const forgeHomes = [
  process.env.GRADLE_USER_HOME,
  'D:\\deepseek\\GirlsFrontline\\.gradle-home',
  path.join(ROOT, 'build'),
].filter(Boolean);
function findForgeArtifacts() {
  const found = { universal: null, sources: null };
  const roots = [];
  for (const home of forgeHomes) {
    roots.push(path.join(home, 'caches', 'forge_gradle', 'maven_downloader', 'net', 'minecraftforge', 'forge'));
    roots.push(path.join(home, 'caches', 'forge_gradle', 'minecraft_user_repo', 'net', 'minecraftforge', 'forge'));
    roots.push(path.join(home, 'fg_cache', 'net', 'minecraftforge', 'forge'));
  }
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    for (const versionDir of fs.readdirSync(root)) {
      const dir = path.join(root, versionDir);
      if (!fs.statSync(dir).isDirectory()) continue;
      for (const file of fs.readdirSync(dir)) {
        if (file.endsWith('-universal.jar')) found.universal = path.join(dir, file);
        if (file.endsWith('-sources.jar')) found.sources = path.join(dir, file);
      }
    }
  }
  return found;
}
const forge = findForgeArtifacts();
if (!forge.universal) {
  skip('the Forge jar check', 'no forge-*-universal.jar found under the known gradle homes');
} else {
  try {
    const listing = execFileSync('tar', ['-tf', forge.universal], {
      encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
    check(!/LivingSpawnEvent/.test(listing),
      'Forge 1.20.1 has no LivingSpawnEvent class at all (so SpecialSpawn cannot be the hook)',
      path.basename(forge.universal));
    check(/MobSpawnEvent\$PositionCheck\.class/.test(listing),
      'but MobSpawnEvent$PositionCheck does exist - the event the veto actually uses');
  } catch (error) {
    skip('the Forge jar check', String(error.message).slice(0, 120));
  }
}
if (!forge.sources) {
  skip('the BaseSpawner patch check', 'no forge-*-sources.jar found');
} else {
  try {
    const patch = execFileSync('tar',
      ['-xOf', forge.sources, 'patches/net/minecraft/world/level/BaseSpawner.java.patch'], {
        encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
      });
    check(/checkSpawnPositionSpawner\(mob, p_151312_, MobSpawnType\.SPAWNER/.test(patch),
      'the Forge BaseSpawner patch routes a spawner spawn through checkSpawnPositionSpawner',
      'which fires MobSpawnEvent.PositionCheck with MobSpawnType.SPAWNER');
    check(/continue;/.test(patch),
      'and a denied PositionCheck makes the spawner skip the entity before it is added to the level');
    check(!/LivingSpawnEvent/.test(patch),
      'the patch itself names no LivingSpawnEvent');
    const naturalPatch = execFileSync('tar',
      ['-xOf', forge.sources, 'patches/net/minecraft/world/level/NaturalSpawner.java.patch'], {
        encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
      });
    check(/checkSpawnPosition\([^)]*MobSpawnType\.NATURAL\)/.test(naturalPatch),
      'and the NaturalSpawner patch routes a natural spawn through the SAME checkSpawnPosition',
      'so one PositionCheck veto covers both paths');
  } catch (error) {
    skip('the BaseSpawner patch check', String(error.message).slice(0, 120));
  }
}

// ------------------------------------------------------------------ 6. no re-roll, no rebuild

console.log('');
console.log('6. a capture cannot be undone by walking past the city again (structural + mirror)');
check(/#unified/.test(strip(read('world/CityFactions.java'))),
  'the faction roll is still deterministic and ledger-recorded (unchanged by this feature)');
check(createBody.indexOf('this.pools.containsKey(entryKey)') < createBody.indexOf('this.pools.put('),
  'createPools checks for existing rows BEFORE it writes any');
const ensureBody = bodyOf(capture, 'ensurePools');
check(ensureBody.indexOf('data.hasPools(') < ensureBody.indexOf('buildPools('),
  'ensurePools returns before buildPools when the city already has pools');
check(/recorded > 0 \? recorded : liveBuildings/.test(bodyOf(capture, 'buildPools')),
  'a pre-existing city is sized from the building count already recorded in the ledger');
check(/data\.clearPools\(dimension, cityKey\)/.test(bodyOf(capture, 'resetPools')),
  'only an explicit reset clears the pools');
check(/data\.city\(dimension, cityKey\) == null/.test(bodyOf(capture, 'resetPools')),
  'a reset of an unknown city is refused rather than invented');
// The mirror: two approaches to a captured city create nothing, and a reset is the only rebuild.
const capturedAgain = newLedger();
createPools(capturedAgain, DIM, CITY_A, FACTIONS, 28);
for (let i = 0; i < 28; i++) drain(capturedAgain, DIM, CITY_A, 'illager', 1);
createPools(capturedAgain, DIM, CITY_A, FACTIONS, 28);
check(capturedAgain.pools.get(poolKeyOf(DIM, CITY_A, 'illager')).captured === true,
  'walking past a captured city again leaves it captured (the mirror)');
capturedAgain.pools.delete(poolKeyOf(DIM, CITY_A, 'village'));
capturedAgain.pools.delete(poolKeyOf(DIM, CITY_A, 'illager'));
capturedAgain.citiesWithPools.delete(`${DIM}|${CITY_A}`);
createPools(capturedAgain, DIM, CITY_A, FACTIONS, 28);
check(capturedAgain.pools.get(poolKeyOf(DIM, CITY_A, 'illager')).strength === 28
  && capturedAgain.pools.get(poolKeyOf(DIM, CITY_A, 'illager')).captured === false,
  'only the reset path (clear + create) restores a contested city');

// ------------------------------------------------------------------ 7. the HUD

console.log('');
console.log('7. the HUD is client-side rendering of server numbers (structural)');
const messageDecl = (/record CaptureHudMessage\(([^)]*)\)/.exec(network) || [])[1] || '';
const barDecl = (/record Bar\(([^)]*)\)/.exec(network) || [])[1] || '';
check(/String cityKey/.test(messageDecl) && /String cityName/.test(messageDecl)
  && /List<Bar> bars/.test(messageDecl) && /boolean hide/.test(messageDecl),
  'the message syncs exactly cityKey, cityName, bars and hide', messageDecl.trim());
check(/String faction/.test(barDecl) && /int strength/.test(barDecl) && /int max/.test(barDecl)
  && /boolean captured/.test(barDecl),
  'each bar syncs exactly faction, strength, max and captured', barDecl.trim());
const messageEncode = bodyAfter(network, 'record CaptureHudMessage', 'encode');
const messageDecode = bodyAfter(network, 'record CaptureHudMessage', 'decode');
const barEncode = bodyAfter(network, 'record Bar', 'encode');
const barDecode = bodyAfter(network, 'record Bar', 'decode');
for (const accessor of ['cityKey()', 'cityName()', 'bars()', 'hide()']) {
  check(messageEncode.includes(accessor), `the wire carries message.${accessor}`);
}
check(/new CaptureHudMessage\(/.test(messageDecode) && /readUtf/.test(messageDecode)
  && /readVarInt/.test(messageDecode) && /readBoolean/.test(messageDecode),
  'the message decode rebuilds all four fields from the wire');
for (const accessor of ['faction()', 'strength()', 'max()', 'captured()']) {
  check(barEncode.includes(accessor), `each bar carries bar.${accessor}`);
}
check(/new Bar\(/.test(barDecode) && /readUtf/.test(barDecode) && /readVarInt/.test(barDecode)
  && /readBoolean/.test(barDecode),
  'the bar decode rebuilds all four fields from the wire');
check(/writeVarInt/.test(messageEncode) && /readUtf/.test(messageDecode),
  'the numbers are var-ints and the names are length-bounded utf, as the kill feed does it');
check(!/drawString|GuiGraphics|Font|ResourceLocation/.test(network),
  'the packet carries no rendering instruction and no resource, so it needs no resource pack');
const acceptBody = bodyOf(hud, 'accept');
for (const accessor of ['hide()', 'bars()', 'cityKey()', 'cityName()']) {
  check(acceptBody.includes(accessor), `CaptureHud.accept reads message.${accessor}`);
}
check(/message\.bars\(\)\.size\(\) < 2/.test(acceptBody),
  'a one-faction state clears the bars at once (the "only one faction left" rule)');
check(/Config\.CAPTURE_HUD_ENABLED\.get\(\)/.test(acceptBody)
  && /Config\.CAPTURE_HUD_ENABLED\.get\(\)/.test(bodyOf(hud, 'render')),
  'capture.hudEnabled is checked both when a message arrives and when the frame is drawn');
const labelBody = bodyOf(hud, 'label');
check(/bar\.strength\(\) \+ "\/" \+ bar\.max\(\)/.test(labelBody),
  'the label shows current/initial as numbers');
check(/bar\.captured\(\)/.test(labelBody) && /captured/.test(labelBody),
  'a spent bar is labelled captured');
check(/bar\.captured\(\) \? CAPTURED_COLOUR/.test(bodyOf(hud, 'render')),
  'and drawn in the grey captured colour');
check(/bar\.strength\(\)/.test(bodyOf(hud, 'render')) && /bar\.max\(\)/.test(bodyOf(hud, 'render')),
  'the bar length is the ratio the server sent');
const tickBody = bodyOf(hud, 'onClientTick');
check(/Config\.CAPTURE_HUD_HIDE_DELAY_SECONDS\.get\(\)/.test(tickBody),
  'the hide delay comes from capture.hudHideDelaySeconds');
check(/\* 20/.test(tickBody), 'and is counted in ticks, client-side');
check(/state\.age/.test(tickBody), 'a player who walks away stops receiving and the last state ages out');
check(/event\.registerAboveAll\("capture", CaptureHud\.INSTANCE\)/.test(clientSetup),
  'the overlay is registered above everything, like the kill feed');
check(/@net\.minecraftforge\.fml\.common\.Mod\.EventBusSubscriber[\s\S]{0,200}?Dist\.CLIENT/.test(hud),
  'and its client tick is a CLIENT-only subscriber, so a dedicated server never loads it');

// ------------------------------------------------------------------ 8. the command

console.log('');
console.log('8. /armedmobs capture, and the fillwater command is untouched (structural)');
check(/Commands\.literal\("capture"\)/.test(commands), 'the capture subcommand is registered');
check(/root\.then\(capture\(\)\)/.test(commands), 'and it hangs off the shared operator tree');
check(/CityCapture\.describe\(source\.getServer\(\)\)/.test(commands),
  'capture (no argument) prints the capture report, including the config in force');
check(/literal\("reset"\)/.test(commands), 'capture reset is wired');
check(/literal\("set"\)/.test(commands), 'capture set is wired (the end-game test lever)');
check(/CityCapture\.resetPools\(/.test(commands) && /CityCapture\.setPool\(/.test(commands),
  'the command calls the two levers, not a copy of them');
check(/literal\("at"\)/.test(commands), 'capture reset at [pos] is wired for the console path');
check(/data\.city\(dimension, cityKey\) == null/.test(commands),
  'the by-key reset refuses an unknown city instead of inventing one');
// The regression guard: this file already had fillwater and it must still be exactly there.
check(/Commands\.literal\("fillwater"\)/.test(commands),
  'fillwater is still registered');
check(/com\.gfl\.tarkovscav\.world\.WaterCleanup\.run\(level, center, radius, target\)/.test(commands),
  'and still calls WaterCleanup.run with the same arguments');
check(/Config\.CITY_REGIONS\.set/.test(commands) && /Commands\.literal\("garrison"\)/.test(commands),
  'the rest of the command tree is untouched');

// ------------------------------------------------------------------ 9. wiring

console.log('');
console.log('9. the feature is registered on the right buses (structural)');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.world\.CityCapture\.class\)/.test(mod),
  'CityCapture is registered on the Forge event bus (the kill drain)');
check(/com\.gfl\.tarkovscav\.world\.CaptureHudNetwork\.register\(\)/.test(mod),
  'and the HUD packet is registered on both sides');
const subscribeCount = (capture.match(/@SubscribeEvent/g) || []).length;
check(subscribeCount === 1 && /@SubscribeEvent[\s\S]{0,120}?onDeath/.test(capture),
  'CityCapture adds exactly one handler (the death drain) - no second scanner',
  `${subscribeCount} handler(s)`);
const worldSources = fs.readdirSync(path.join(JAVA, 'world'))
  .filter((file) => file.endsWith('.java'))
  .map((file) => strip(read(path.join('world', file))))
  .join('\n');
const ensureCalls = worldSources.match(/\.ensurePools\(/g) || [];
check(ensureCalls.length === 1,
  'pool creation has exactly one call site (CityGarrison.factionFor)',
  `${ensureCalls.length} call site(s)`);

// ------------------------------------------------------------------ summary

console.log('');
console.log(`${checks - failures}/${checks} capture check(s) passed`);
if (failures > 0) {
  console.log(`${failures} capture check(s) FAILED`);
  process.exit(1);
}
console.log('overworld cities build strength pools on first approach, deaths drain them to exactly zero, '
  + 'all three spawn paths refuse a spent faction, and nothing in the wasteland ever has a pool');
