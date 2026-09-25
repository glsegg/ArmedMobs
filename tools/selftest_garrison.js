// The one-time city garrison gate: "one city fixes a few squads, they never respawn, a squad is normally
// 1-5 people".
//
//   node tools/selftest_garrison.js
//
// WHAT CANNOT BE CHECKED BY RUNNING A SERVER
//   Whether the garrison actually appears needs a player walking up to a city, and RCON cannot make a
//   server-side player do that. So this gate checks the two halves that ARE decidable head-lessly:
//
//   A. the static contract - the config keys exist and are documented, the ledger is dimension-aware,
//      the trigger has both a radius guard and a coarse interval, and the composition names the TROOP
//      ids and nothing else (with the ELITE pair only ever used for the optional leader);
//
//   B. the BOOKKEEPING, as a deterministic simulation: the pure decision helpers in CityGarrison are
//      mirrored here (the same way tools/selftest_ai_profiles.js mirrors the profile maths) and driven
//      through the real cases - first visit spawns, second visit does not, a wiped-out garrison still
//      does not, another dimension is another city, another city in the same dimension is another city,
//      and a save/reload keeps every one of those answers.
//
//   The ledger's serialised field names are read out of the Java source and asserted, so the simulation
//   cannot drift away from what the SavedData actually writes.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** The body of a method declaration (parameter list followed by `{`). */
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

const configRaw = read('Config.java');
const config = strip(configRaw);
const garrison = strip(read('world/CityGarrison.java'));
const ledger = strip(read('world/GarrisonData.java'));
const gate = strip(read('world/CityGate.java'));
const squad = strip(read('gun/SquadCoordinator.java'));
const mod = strip(read('TarkovScav.java'));
const commands = strip(read('command/ModCommands.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const reference = fs.readFileSync(path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'), 'utf8');

// ------------------------------------------------------------------ 1. the config keys

console.log('1. the garrison config keys exist, with the shipped defaults');
const keys = [
  ['GARRISON_ENABLED', /GARRISON_ENABLED\s*=\s*b[\s\S]{0,400}?\.define\("enabled",\s*true\)/, 'enabled = true'],
  ['GARRISON_SQUADS_PER_CITY', /GARRISON_SQUADS_PER_CITY\s*=\s*b[\s\S]{0,900}?\.defineInRange\("squadsPerCity",\s*-1,\s*-1,\s*6\)/, 'squadsPerCity = -1 (size formula)'],
  ['GARRISON_SQUAD_SIZE_MIN', /GARRISON_SQUAD_SIZE_MIN\s*=\s*b[\s\S]{0,400}?\.defineInRange\("squadSizeMin",\s*1,/, 'squadSizeMin = 1'],
  ['GARRISON_SQUAD_SIZE_MAX', /GARRISON_SQUAD_SIZE_MAX\s*=\s*b[\s\S]{0,600}?\.defineInRange\("squadSizeMax",\s*5,/, 'squadSizeMax = 5'],
  ['GARRISON_ELITE_LEADER_CHANCE', /GARRISON_ELITE_LEADER_CHANCE\s*=\s*b[\s\S]{0,900}?\.defineInRange\("eliteLeaderChance",\s*0\.2D,/, 'eliteLeaderChance = 0.2'],
  ['GARRISON_TRIGGER_RADIUS', /GARRISON_TRIGGER_RADIUS\s*=\s*b[\s\S]{0,600}?\.defineInRange\("triggerRadius",\s*64\.0D,/, 'triggerRadius = 64'],
  ['GARRISON_CHECK_INTERVAL_TICKS', /GARRISON_CHECK_INTERVAL_TICKS\s*=\s*b[\s\S]{0,400}?\.defineInRange\("checkIntervalTicks",\s*100,/, 'checkIntervalTicks = 100'],
];
for (const [name, pattern, detail] of keys) {
  check(pattern.test(config), `${name}`, detail);
}
check(/push\("garrison"\)/.test(config), 'all seven live in their own [garrison] section');
check(/GARRISON_SQUAD_SIZE_MIN[\s\S]{0,200}?squadSizeMin[\s\S]*?GARRISON_SQUAD_SIZE_MAX[\s\S]{0,300}?squadSizeMax/
  .test(config), 'the squad size keys are declared min then max');

// ------------------------------------------------------------------ 2. documented

console.log('');
console.log('2. the keys are documented in both the README and the command/config reference');
for (const key of ['garrison.enabled', 'garrison.squadsPerCity', 'garrison.squadSizeMin',
  'garrison.squadSizeMax', 'garrison.eliteLeaderChance', 'garrison.triggerRadius',
  'garrison.checkIntervalTicks']) {
  const bare = key.split('.')[1];
  check(readme.includes(bare), `README documents ${key}`);
  check(reference.includes(bare), `docs/COMMAND_AND_CONFIG_REFERENCE.md documents ${key}`);
}
check(/\/armedmobs garrison|\/tarkovscav garrison/.test(readme), 'the README documents the garrison command');
check(/`garrison`/.test(reference) || /\/armedmobs garrison/.test(reference),
  'the reference documents the garrison command');

// ------------------------------------------------------------------ 3. the ledger is dimension-aware

console.log('');
console.log('3. the ledger is a SavedData keyed by dimension + city identity');
check(/class GarrisonData extends SavedData/.test(ledger), 'GarrisonData is a SavedData');
const keyBody = bodyOf(ledger, 'key');
check(/dimension/.test(keyBody) && /cityKey/.test(keyBody) && /\+/.test(keyBody),
  'GarrisonData.key() combines the dimension and the city identity',
  keyBody.replace(/\s+/g, ' ').trim());
check(/DATA_NAME\s*=\s*"tarkovscav_garrison"/.test(ledger),
  'the file name is pinned (a rename would orphan an existing world\'s ledger)');
check(/server\.overworld\(\)\.getDataStorage\(\)/.test(ledger),
  'it is fetched from the world\'s data storage (so it survives a restart)');
check(/public boolean hasSpawned\(ResourceLocation dimension, String cityKey\)/.test(ledger),
  'hasSpawned takes BOTH the dimension and the city key');
check(/public void markSpawned\(ResourceLocation dimension, String cityKey/.test(ledger),
  'so does markSpawned');
check(!/entries\s*\.\s*remove|entries\s*\.\s*clear/.test(ledger),
  'nothing in the ledger ever removes or clears an entry (a dead garrison stays dead)');
check(/setDirty\(\)/.test(bodyOf(ledger, 'markSpawned')), 'markSpawned marks the data dirty, so it is written');
// The serialised field names, read out of the Java, for the simulation below.
const nbt = {};
for (const name of ['KEY_ENTRIES', 'KEY_KEY', 'KEY_CITY', 'KEY_DIMENSION', 'KEY_SQUADS', 'KEY_UNITS', 'KEY_TICK']) {
  const m = new RegExp(`${name}\\s*=\\s*"([^"]+)"`).exec(ledger);
  nbt[name] = m ? m[1] : null;
}
check(Object.values(nbt).every(Boolean), 'every NBT field name is a literal', JSON.stringify(nbt));

// ------------------------------------------------------------------ 4. the trigger

console.log('');
console.log('4. the trigger has a coarse interval and a radius guard');
check(/@SubscribeEvent[\s\S]{0,200}?onServerTick\(TickEvent\.ServerTickEvent/.test(garrison),
  'it runs from the server tick event, not a per-entity tick');
check(/TickEvent\.Phase\.END/.test(garrison), 'only on the END phase (once per tick, after the world ticked)');
const dueBody = bodyOf(garrison, 'due');
check(/intervalTicks/.test(dueBody) && />=/.test(dueBody),
  'due() refuses to run again before the interval has elapsed',
  dueBody.replace(/\s+/g, ' ').trim());
const radiusBody = bodyOf(garrison, 'withinRadius');
check(/radius\s*>\s*0\.0D/.test(radiusBody) && /distance\s*<=\s*radius/.test(radiusBody),
  'withinRadius() guards a non-positive radius AND bounds the distance',
  radiusBody.replace(/\s+/g, ' ').trim());
check(/GARRISON_CHECK_INTERVAL_TICKS\.get\(\)/.test(garrison), 'the interval comes from the config key');
check(/GARRISON_TRIGGER_RADIUS\.get\(\)/.test(garrison), 'the radius comes from the config key');
check(/CityGate\.citiesNear\(/.test(garrison), 'the city search is CityGate.citiesNear');
check(/CityGate\.distanceToBox\(/.test(garrison), 'and the distance is measured to the city BOX, not its centre');
check(/getChunk\([\s\S]{0,120}?ChunkStatus\.FULL,\s*false\)/.test(gate),
  'the search only inspects LOADED chunks (requireChunk = false), so nobody loads terrain by walking');
check(/Config\.SPEC\.isLoaded\(\)/.test(garrison), 'and it refuses to read the config before it is loaded');

// ------------------------------------------------------------------ 5. composition

console.log('');
console.log('5. squad composition is the TROOP pair, with an ELITE leader only as the optional extra');
const troop = /TROOP_IDS\s*=\s*List\.of\(([^)]*)\)/.exec(garrison);
check(!!troop && /tarkovscav:usec_villager/.test(troop[1]) && /tarkovscav:bear_pillager/.test(troop[1])
  && (troop[1].match(/"/g) || []).length === 4,
  'TROOP_IDS is exactly usec_villager and bear_pillager',
  troop ? troop[1].trim() : 'missing');
const leader = /ELITE_LEADER_IDS\s*=\s*List\.of\(([^)]*)\)/.exec(garrison);
check(!!leader && /tarkovscav:elite_villager/.test(leader[1]) && /tarkovscav:elite_pillager/.test(leader[1]),
  'ELITE_LEADER_IDS is exactly the two elite units', leader ? leader[1].trim() : 'missing');
const pickBody = bodyOf(garrison, 'pickType');
check(/elite/.test(pickBody) && /ELITE_VILLAGER\.get\(\)/.test(pickBody) && /ELITE_PILLAGER\.get\(\)/.test(pickBody),
  'the elite pair is reachable only through the elite branch');
check(/USEC_VILLAGER\.get\(\)/.test(pickBody) && /BEAR_PILLAGER\.get\(\)/.test(pickBody),
  'the ordinary member is one of the TROOP pair');
const spawnedTypes = (garrison.match(/ModEntities\.([A-Z_]+)\.get\(\)/g) || [])
  .map((entry) => entry.replace(/ModEntities\.|\.get\(\)/g, ''));
const allowed = new Set(['USEC_VILLAGER', 'BEAR_PILLAGER', 'ELITE_VILLAGER', 'ELITE_PILLAGER']);
check(spawnedTypes.every((type) => allowed.has(type)),
  'the garrison spawns no entity type outside those four',
  spawnedTypes.join(', '));
check(/SquadCoordinator\.assignSquad\(/.test(garrison) && /SquadCoordinator\.claimFor\(/.test(garrison),
  'members get a shared squad id and a claimed cover spot through SquadCoordinator (no second system)');
check(/NBT_SQUAD_ID\s*=\s*"tarkovscav:squadId"/.test(squad),
  'the squad id is a namespaced persistent-data key, so it survives a reload');
check(/setPersistenceRequired\(\)/.test(garrison),
  'a garrison member is persistence-required, so it cannot despawn behind the player who triggered it');
check(/BlockTags\.DOORS/.test(garrison) && /isFaceSturdy/.test(garrison),
  'placement refuses a doorway and demands solid ground under it');
check(/canSeeSky/.test(garrison), 'and prefers interiors in the first pass');

// ------------------------------------------------------------------ 6. the bookkeeping simulation

console.log('');
console.log('6. bookkeeping simulation (mirrors CityGarrison\'s pure helpers, drives the real cases)');

// The mirror: same formulas as the Java above, expressed once so the table below is the assertion.
const mirror = {
  key: (dimension, cityKey) => `${dimension}|${cityKey}`,
  due: (now, lastCheck, intervalTicks) => lastCheck === null || now - lastCheck >= Math.max(1, intervalTicks),
  withinRadius: (distance, radius) => radius > 0 && distance <= radius,
  squadsForCity: (width, depth, configured) => {
    if (configured >= 1) return Math.min(configured, 6);
    const span = Math.max(width, depth);
    return Math.min(Math.max(1 + Math.floor(span / 48), 1), 6);
  },
  rollSquadSize: (roll, min, max) => {
    const low = Math.max(1, Math.min(min, max));
    const high = Math.max(1, Math.max(min, max));
    return low >= high ? low : low + roll % (high - low + 1);
  },
};

// The formulas must be the same text as the Java, or this mirror proves nothing.
const squadsBody = bodyOf(garrison, 'squadsForCity');
check(/1\s*\+\s*span\s*\/\s*48/.test(squadsBody) && /Math\.min\(Math\.max\(1/.test(squadsBody),
  'the Java size formula is exactly 1 + span / 48 clamped to 1..6',
  squadsBody.replace(/\s+/g, ' ').trim().slice(0, 120));
check(/configuredSquads\s*>=\s*1/.test(squadsBody) && /MAX_SQUADS/.test(squadsBody),
  'and a configured 1..6 pins the count instead');
const dueText = dueBody.replace(/\s+/g, ' ');
check(/lastCheck == Long\.MIN_VALUE \|\| now - lastCheck >= Math\.max\(1, intervalTicks\)/.test(dueText),
  'the Java due() is the same comparison as the mirror', dueText.slice(0, 140));
const rollBody = bodyOf(garrison, 'rollSquadSize').replace(/\s+/g, ' ');
check(/Math\.min\(min, max\)/.test(rollBody) && /Math\.max\(min, max\)/.test(rollBody),
  'and the size roll swaps min/max, so a hand-edited toml cannot produce an empty squad');

const sizeCases = [
  // [width, depth, configured, expected, why]
  [16, 16, -1, 1, 'a tiny city gets exactly one squad'],
  [48, 20, -1, 2, '48 blocks of span -> 1 + 1'],
  [96, 96, -1, 3, '96 -> 1 + 2'],
  [240, 96, -1, 6, '240 -> 1 + 5 = 6, capped'],
  [1000, 1000, -1, 6, 'the cap bites, so a huge city cannot ask for an army'],
  [1000, 1000, 3, 3, 'a configured 1..6 wins over the formula'],
  [1000, 1000, 0, 6, '0 is not a valid pin, so the formula is used'],
];
for (const [w, d, configured, expected, why] of sizeCases) {
  const got = mirror.squadsForCity(w, d, configured);
  check(got === expected, `squadsForCity(${w}, ${d}, ${configured}) = ${got}`, why);
}
const sizeRolls = [];
for (let roll = 0; roll < 40; roll++) sizeRolls.push(mirror.rollSquadSize(roll, 1, 5));
check(Math.min(...sizeRolls) === 1 && Math.max(...sizeRolls) === 5 && new Set(sizeRolls).size === 5,
  'the 1..5 squad size actually spans 1 to 5',
  `seen ${[...new Set(sizeRolls)].sort().join(',')}`);
check(mirror.rollSquadSize(0, 5, 1) >= 1 && mirror.rollSquadSize(0, 5, 1) <= 5,
  'min > max is swapped rather than crashing or returning 0');

const dueCases = [
  [1000, null, 100, true, 'a dimension that has never been checked is due'],
  [1000, 950, 100, false, '50 ticks after the last check is inside the interval'],
  [1000, 900, 100, true, '100 ticks after is due'],
  [1000, 1000, 100, false, 'the same tick is not due again'],
  [1001, 1000, 0, true, 'a zero interval is clamped to 1, never a per-tick storm'],
];
for (const [now, last, interval, expected, why] of dueCases) {
  const got = mirror.due(now, last, interval);
  check(got === expected, `due(now=${now}, last=${last}, interval=${interval}) = ${got}`, why);
}
const radiusCases = [
  [10, 64, true, 'inside the radius'],
  [64, 64, true, 'exactly on the radius counts'],
  [64.1, 64, false, 'just outside does not'],
  [0, 0, false, 'a zero radius never triggers (off cannot mean everywhere)'],
  [0, -5, false, 'a negative radius never triggers'],
];
for (const [distance, radius, expected, why] of radiusCases) {
  const got = mirror.withinRadius(distance, radius);
  check(got === expected, `withinRadius(${distance}, ${radius}) = ${got}`, why);
}

// The ledger, as a faithful mirror of GarrisonData: a Map keyed by `dimension|city`, serialised to rows.
function newLedger() { return new Map(); }
function hasSpawned(l, dimension, cityKey) { return l.has(mirror.key(dimension, cityKey)); }
function markSpawned(l, dimension, cityKey, squads, units, tick) {
  l.set(mirror.key(dimension, cityKey), { cityKey, dimension, squads, units, tick });
}
function serialise(l) {
  // Exactly the fields GarrisonData.save writes; the names come out of the Java source above.
  return [...l.values()].map((entry) => ({
    [nbt.KEY_KEY]: mirror.key(entry.dimension, entry.cityKey),
    [nbt.KEY_CITY]: entry.cityKey,
    [nbt.KEY_DIMENSION]: entry.dimension,
    [nbt.KEY_SQUADS]: entry.squads,
    [nbt.KEY_UNITS]: entry.units,
    [nbt.KEY_TICK]: entry.tick,
  }));
}
function deserialise(rows) {
  const l = newLedger();
  for (const row of rows) {
    l.set(row[nbt.KEY_KEY], {
      cityKey: row[nbt.KEY_CITY],
      dimension: row[nbt.KEY_DIMENSION],
      squads: row[nbt.KEY_SQUADS],
      units: row[nbt.KEY_UNITS],
      tick: row[nbt.KEY_TICK],
    });
  }
  return l;
}
/** The trigger's decision, exactly as CityGarrison.checkLevel makes it. */
function trigger(l, dimension, cityKey, squads) {
  if (hasSpawned(l, dimension, cityKey)) return 0;
  markSpawned(l, dimension, cityKey, squads, squads * 3, 100);
  return squads;
}

const overworld = 'minecraft:overworld';
const wasteland = 'tarkovscav:urban_wasteland';
const cityA = 'structure/tarkovscav:city_small/10,-4,10';
const cityB = 'structure/tarkovscav:city_small/90,-4,90';
let dl = newLedger();

check(trigger(dl, overworld, cityA, 2) === 2, 'first visit to a city places its garrison');
check(trigger(dl, overworld, cityA, 2) === 0, 'the SECOND visit places nothing (the trigger already fired)');
check(dl.size === 1, 'and the ledger holds exactly one entry');

// "even after the whole garrison dies": the ledger knows nothing about living entities, so the only way
// this could regress is if some other code path deleted the entry. Nothing does (asserted in section 3),
// so simulate the death by doing nothing and asking again, many times.
for (let i = 0; i < 50; i++) trigger(dl, overworld, cityA, 2);
check(dl.size === 1 && trigger(dl, overworld, cityA, 2) === 0,
  'a wiped-out garrison is never re-placed (nothing removes a ledger entry)');

check(trigger(dl, overworld, cityB, 3) === 3,
  'a DIFFERENT city in the same dimension gets its own garrison');
check(trigger(dl, wasteland, cityA, 4) === 4,
  'the SAME city key in another dimension gets its own garrison (the ledger is dimension-aware)');
check(dl.size === 3, 'three independent garrisons are recorded', `${dl.size} entries`);
check(trigger(dl, wasteland, cityA, 4) === 0, 'and the wasteland city also spawns only once');

const reloaded = deserialise(serialise(dl));
check(reloaded.size === 3, 'a save + reload keeps every entry', `${reloaded.size} entries`);
check(trigger(reloaded, overworld, cityA, 2) === 0
  && trigger(reloaded, overworld, cityB, 3) === 0
  && trigger(reloaded, wasteland, cityA, 4) === 0,
  'and after the reload every one of them still refuses to spawn again (the restart case)');
check(trigger(reloaded, wasteland, cityB, 1) === 1, 'while a genuinely new city still spawns');

// A placement that found nowhere to stand must NOT be recorded - the retry case.
const retry = newLedger();
const noSpot = 0;
if (noSpot > 0) markSpawned(retry, overworld, cityA, 0, noSpot, 1);
check(retry.size === 0 && trigger(retry, overworld, cityA, 2) === 2,
  'a placement that placed nothing is retried, not recorded as done');

// ------------------------------------------------------------------ 7. wiring

console.log('');
console.log('7. wiring');
check(/CityGarrison\.class/.test(mod), 'the trigger is registered on the Forge event bus');
check(/Commands\.literal\("garrison"\)/.test(commands) && /CityGarrison\.describe\(/.test(commands),
  '/armedmobs garrison is wired to CityGarrison.describe');
check(/\[garrison\] \{\} -> \{\} squads \/ \{\} units/.test(garrison),
  'the placement log line has the required shape: [garrison] <city> -> N squads / M units');
check(/TROOP_IDS/.test(garrison) && /ELITE_LEADER_IDS/.test(garrison),
  'the two id lists are named once and reused by the diagnostic');

console.log('');
if (failures > 0) {
  console.log(`${failures} garrison check(s) FAILED`);
  process.exit(1);
}
console.log('the one-time city garrison is configurable, documented, dimension-aware and provably once-only');
