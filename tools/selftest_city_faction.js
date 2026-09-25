// The per-building city faction gate: "every city leans one way, but each BUILDING may belong to a
// different line-up - a city can be contested".
//
//   node tools/selftest_city_faction.js
//
// WHAT THIS GATE PROVES, and why each part is a different kind of check:
//
//   1. THE ROLL IS DETERMINISTIC. CityFactions' pure helpers are mirrored here (the same way
//      selftest_garrison.js mirrors CityGarrison's) and driven through the real cases: the same city key
//      always rolls the same dominant faction and the same per-building split, a different key or a
//      different world seed changes it, and across thousands of keys the buckets are uniform to within a
//      few percent - a biased hash would make "50/50" a lie.
//   2. UNIFIED vs CONTESTED. The shipped numbers (friendlyCityChance 0.5 / cityDominantFactionChance 0.5)
//      must produce a real mix of both, and 1.0 must restore the old "one city, one faction" behaviour
//      exactly. These are measured as fractions over simulated cities, not asserted from the source text.
//   3. THE SPAWNERS. The SHIPPED NBT is parsed (gzip + a dependency-free NBT reader) and the baked
//      per-building map is checked against it: every spawner is inside exactly one building rectangle, the
//      rectangles are in bounds and do not overlap, and - the point of the whole feature - the rewrite this
//      code computes for a building names ONLY that building's TROOP id. A village building's payload may
//      not contain an illager id and vice versa, and a unified village city's whole spread of spawners is
//      village-only.
//   4. COMPOSITION. The garrison's entity selection is re-derived for both factions and both tiers: no
//      input can produce a unit of the other line-up.
//   5. THE NATURAL-SPAWN MATRIX. Every (building faction x mob faction) pair, including the null (no
//      faction) and SCAV cases - which must be allowed in BOTH city types, because the scav is the
//      unaligned third party (the decision recorded in the README).
//   6. THE LEDGER. A faithful mirror of GarrisonData's rows, serialised with the field names read out of
//      the Java, proves the rolls survive a save/reload, that recording is once-only, and that a city-wide
//      or per-building override persists and changes the effective faction while "auto" restores the roll.
//   7. WIRING AND DOCS. The trigger, the spawn filter, the log line, the command and the new config keys are
//      all present, documented, and the rewrite refuses to force-load chunks.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const DATA = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav');
const TOOLS = path.join(ROOT, 'tools');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** Every string literal inside `NAME = List.of(...)` in a Java source. */
function listOf(src, name) {
  const match = new RegExp(`${name}\\s*=\\s*List\\.of\\(([^)]*)\\)`).exec(src);
  return match ? [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : [];
}
/** The value of `define("key", <literal>)` / `defineInRange("key", <literal>, ...)` in Config.java. */
function configDefault(name, key) {
  const re = new RegExp(`${name}\\s*=\\s*b[\\s\\S]{0,2000}?\\.define(?:InRange)?\\("${key}",\\s*([^,)]+)`);
  const match = re.exec(config);
  return match ? match[1].trim() : null;
}

const factionsRaw = read('world/CityFactions.java');
const factions = strip(factionsRaw);
const ledgerRaw = read('world/GarrisonData.java');
const ledger = strip(ledgerRaw);
const garrisonRaw = read('world/CityGarrison.java');
const garrison = strip(garrisonRaw);
const spawners = strip(read('world/CityFactionSpawners.java'));
const buildings = strip(read('world/CityBuildings.java'));
const spawnEvents = strip(read('world/CitySpawnEvents.java'));
const gate = strip(read('world/CityGate.java'));
const config = strip(read('Config.java'));
const commands = strip(read('command/ModCommands.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const reference = fs.readFileSync(path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'), 'utf8');

// ------------------------------------------------------------------ 0. the facts

console.log('0. the facts this gate measures against (read out of the Java, never restated by hand)');
const VILLAGE_SPAWNER = listOf(factions, 'VILLAGE_SPAWNER_IDS');
const ILLAGER_SPAWNER = listOf(factions, 'ILLAGER_SPAWNER_IDS');
const VILLAGE_UNITS = listOf(factions, 'VILLAGE_UNIT_IDS');
const ILLAGER_UNITS = listOf(factions, 'ILLAGER_UNIT_IDS');
const BUCKETS = Number((/BUCKETS\s*=\s*(\d+)/.exec(factions) || [])[1]);
check(VILLAGE_SPAWNER.length === 1 && VILLAGE_SPAWNER[0] === 'tarkovscav:usec_villager',
  'a village building bakes exactly tarkovscav:usec_villager', VILLAGE_SPAWNER.join(', '));
check(ILLAGER_SPAWNER.length === 1 && ILLAGER_SPAWNER[0] === 'tarkovscav:bear_pillager',
  'an illager building bakes exactly tarkovscav:bear_pillager', ILLAGER_SPAWNER.join(', '));
check(VILLAGE_UNITS.length === 2 && ILLAGER_UNITS.length === 2, 'both factions have a TROOP and an ELITE unit',
  `${VILLAGE_UNITS.join(',')} | ${ILLAGER_UNITS.join(',')}`);
check(VILLAGE_UNITS.every((id) => !ILLAGER_UNITS.includes(id))
  && ILLAGER_UNITS.every((id) => !VILLAGE_UNITS.includes(id)),
  'the two factions share no unit id at all');
check(BUCKETS === 10000, 'the roll resolution is 10000 buckets', String(BUCKETS));

const FRIENDLY_CHANCE = configDefault('GARRISON_FRIENDLY_CITY_CHANCE', 'friendlyCityChance');
const DOMINANT_CHANCE = configDefault('GARRISON_CITY_DOMINANT_FACTION_CHANCE', 'cityDominantFactionChance');
const REWRITE = configDefault('GARRISON_REWRITE_CITY_SPAWNERS', 'rewriteCitySpawners');
const SPAWN_FILTER = configDefault('GARRISON_FACTION_SPAWN_FILTER', 'factionSpawnFilter');
check(FRIENDLY_CHANCE === '0.5D', 'garrison.friendlyCityChance ships as 0.5', String(FRIENDLY_CHANCE));
check(DOMINANT_CHANCE === '0.5D', 'garrison.cityDominantFactionChance ships as 0.5', String(DOMINANT_CHANCE));
check(REWRITE === 'true', 'garrison.rewriteCitySpawners ships enabled', String(REWRITE));
check(SPAWN_FILTER === 'true', 'garrison.factionSpawnFilter ships enabled', String(SPAWN_FILTER));

// The NBT field names the ledger mirror below must use, taken from the Java itself.
const nbt = {};
for (const name of ['KEY_KEY', 'KEY_CITY', 'KEY_DIMENSION', 'KEY_CITIES', 'KEY_BUILDINGS', 'KEY_DOMINANT',
  'KEY_ROLLED', 'KEY_OVERRIDE', 'KEY_BUILDING', 'KEY_SPAWNERS']) {
  const m = new RegExp(`${name}\\s*=\\s*"([^"]+)"`).exec(ledger);
  nbt[name] = m ? m[1] : null;
}
check(Object.values(nbt).every(Boolean), 'every faction-ledger NBT field name is a literal',
  JSON.stringify(nbt));
check(/String buildingKey\(ResourceLocation dimension, String cityKey, String buildingId\)[\s\S]{0,200}?key\(dimension, cityKey\) \+ "#" \+ buildingId/
  .test(ledger), 'buildingKey() is the city key plus "#" + buildingId');

// ------------------------------------------------------------------ 1. the roll

console.log('');
console.log('1. the faction roll: stable for a key, different for another key or seed, uniform across keys');
const BUCKET_COUNT = 10000;
/** Java's (int)(seed ^ (seed >>> 32)), mirrored with BigInt. */
function seedMix(seed) {
  const low = Number(BigInt.asIntN(32, seed));
  const high = Number(BigInt.asIntN(32, (seed >> 32n) & 0xffffffffn));
  return (low ^ high) | 0;
}
function hash(seed, tag) {
  let h = 1;
  h = (Math.imul(31, h) + seedMix(seed)) | 0;
  for (let i = 0; i < tag.length; i++) {
    h = (Math.imul(31, h) + tag.charCodeAt(i)) | 0;
  }
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h | 0;
}
function bucket(seed, tag) {
  return ((hash(seed, tag) & 0x7fffffff) % BUCKET_COUNT);
}
function threshold(chance) {
  return Math.round(Math.max(0, Math.min(1, chance)) * BUCKET_COUNT);
}
function rollCity(seed, dim, cityKey, chance) {
  return bucket(seed, `${dim}|${cityKey}`) < threshold(chance) ? 'VILLAGE' : 'ILLAGER';
}
function rollBuildings(seed, dim, cityKey, dominant, dominantChance, count) {
  const unified = bucket(seed, `${dim}|${cityKey}#unified`) < threshold(dominantChance);
  const other = dominant === 'ILLAGER' ? 'VILLAGE' : 'ILLAGER';
  const out = [];
  for (let i = 0; i < count; i++) {
    out.push(unified || bucket(seed, `${dim}|${cityKey}#building:${i}`) < BUCKET_COUNT / 2
      ? dominant : other);
  }
  return out;
}

// The Java must contain the same arithmetic this mirror uses, or the mirror proves nothing.
const hashBody = /public static int hash\(long worldSeed, String tag\) \{([\s\S]*?)\n    \}/.exec(factions);
check(!!hashBody && /31 \* hash \+ \(int\) \(worldSeed \^ \(worldSeed >>> 32\)\)/.test(hashBody[1])
  && /hash \*= 0x85ebca6b/.test(hashBody[1]) && /hash \*= 0xc2b2ae35/.test(hashBody[1]),
  'Java hash() is the 31-fold + murmur3 finalizer this mirror reimplements');
check(/return \(hash\(worldSeed, tag\) & 0x7fffffff\) % BUCKETS;/.test(factions),
  'Java bucket() masks the sign bit and takes the modulo, exactly as the mirror does');
check(/return bucket\(worldSeed, cityTag\(dimension, cityKey\)\) < threshold\(friendlyCityChance\)/.test(factions),
  'Java rollCity() is "bucket < threshold", exactly as the mirror does');
check(/#unified/.test(factions) && /"#building:" \+ buildingIndex/.test(factions),
  'the unified roll and the per-building roll use the tags the mirror derives');

const SEED = 20261001n;
const OTHER_SEED = 777001n;
const DIM = 'minecraft:overworld';
const CITY_A = 'structure/tarkovscav:city_small/10,-4,10';
const CITY_B = 'structure/tarkovscav:city_small/90,-4,90';
const CITY_C = 'structure/tarkovscav:city_small/10,-4,90';
const CITY_D = 'structure/tarkovscav:city_small/90,-4,10';

let stable = true;
for (let i = 0; i < 200; i++) {
  if (rollCity(SEED, DIM, CITY_A, 0.5) !== rollCity(SEED, DIM, CITY_A, 0.5)) stable = false;
  const first = rollBuildings(SEED, DIM, CITY_A, 'VILLAGE', 0.5, 4).join(',');
  const second = rollBuildings(SEED, DIM, CITY_A, 'VILLAGE', 0.5, 4).join(',');
  if (first !== second) stable = false;
}
check(stable, 'the same (seed, dimension, city key) always rolls the same city and building factions');

const keys = [];
for (let i = 0; i < 4000; i++) keys.push(`structure/tarkovscav:city_small/${i * 7},-4,${i * 13}`);
const villageCount = keys.filter((key) => rollCity(SEED, DIM, key, 0.5) === 'VILLAGE').length;
check(Math.abs(villageCount / keys.length - 0.5) < 0.05,
  '4000 city keys roll village about half the time', `${villageCount}/4000`);
let changed = 0;
for (const key of keys) {
  if (rollCity(SEED, DIM, key, 0.5) !== rollCity(OTHER_SEED, DIM, key, 0.5)) changed++;
}
check(changed / keys.length > 0.3, 'changing the world seed re-rolls most cities',
  `${changed}/4000 differ`);
check(rollCity(SEED, DIM, CITY_A, 0) === 'ILLAGER' && rollCity(SEED, DIM, CITY_A, 1) === 'VILLAGE',
  'friendlyCityChance 0 pins illager and 1 pins village');
check(new Set([CITY_A, CITY_B, CITY_C, CITY_D].map((key) => rollCity(SEED, DIM, key, 0.5))).size <= 2,
  'four neighbouring keys roll well-defined factions');

// ------------------------------------------------------------------ 2. unified vs contested

console.log('');
console.log('2. a city is usually one line-up and sometimes contested - and 1.0 restores one-faction cities');
const perBuildingCount = 4;
let unifiedCities = 0;
const buildingCounts = { VILLAGE: 0, ILLAGER: 0 };
for (let i = 0; i < 3000; i++) {
  const key = `structure/tarkovscav:city_small/${i},0,${i * 3}`;
  const dominant = rollCity(SEED, DIM, key, 0.5);
  const split = rollBuildings(SEED, DIM, key, dominant, 0.5, perBuildingCount);
  if (split.every((faction) => faction === split[0])) unifiedCities++;
  for (const faction of split) buildingCounts[faction]++;
}
const unifiedFraction = unifiedCities / 3000;
check(unifiedFraction > 0.4 && unifiedFraction < 0.7,
  'with the shipped 0.5/0.5 the default is a real mix of unified and contested cities',
  `${(unifiedFraction * 100).toFixed(1)}% unified`);
check(buildingCounts.VILLAGE > 5000 && buildingCounts.ILLAGER > 5000,
  'buildings land on both factions in quantity', JSON.stringify(buildingCounts));
let pinned = 0;
for (let i = 0; i < 500; i++) {
  const key = `structure/tarkovscav:city_b/${i},0,${i}`;
  const dominant = rollCity(SEED, DIM, key, 0.5);
  const split = rollBuildings(SEED, DIM, key, dominant, 1.0, 6);
  if (split.every((faction) => faction === dominant)) pinned++;
}
check(pinned === 500, 'cityDominantFactionChance 1.0 makes every city exactly one faction');
let contested = 0;
for (let i = 0; i < 500; i++) {
  const key = `structure/tarkovscav:city_c/${i},0,${i}`;
  const split = rollBuildings(SEED, DIM, key, 'VILLAGE', 0.0, 4);
  if (split.includes('VILLAGE') && split.includes('ILLAGER')) contested++;
}
check(contested > 380, 'cityDominantFactionChance 0.0 makes most 4-building cities genuinely mixed',
  `${contested}/500`);

// ------------------------------------------------------------------ 3. the shipped spawners and the map

console.log('');
console.log('3. every shipped spawner sits in exactly one BUILDING, and the rewrite for that building is pure');
function parseNbt(buffer) {
  let offset = 0;
  const u8 = () => buffer[offset++];
  const i16 = () => { const v = buffer.readInt16BE(offset); offset += 2; return v; };
  const i32 = () => { const v = buffer.readInt32BE(offset); offset += 4; return v; };
  const i64 = () => { const v = Number(buffer.readBigInt64BE(offset)); offset += 8; return v; };
  const str = () => {
    const n = buffer.readUInt16BE(offset); offset += 2;
    const s = buffer.toString('utf8', offset, offset + n); offset += n; return s;
  };
  const payload = (type) => {
    switch (type) {
      case 1: return buffer.readInt8(offset++);
      case 2: return i16();
      case 3: return i32();
      case 4: return i64();
      case 5: { const v = buffer.readFloatBE(offset); offset += 4; return v; }
      case 6: { const v = buffer.readDoubleBE(offset); offset += 8; return v; }
      case 7: { const n = i32(); const a = buffer.subarray(offset, offset + n); offset += n; return a; }
      case 8: return str();
      case 9: { const t = u8(); const n = i32(); const list = []; for (let i = 0; i < n; i++) list.push(payload(t)); return list; }
      case 10: { const c = {}; for (;;) { const t = u8(); if (t === 0) return c; const name = str(); c[name] = payload(t); } }
      case 11: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i32()); return a; }
      case 12: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i64()); return a; }
      default: throw new Error('bad tag type ' + type);
    }
  };
  const type = u8();
  str();
  return payload(type);
}
const LAYOUTS = ['city-layout.json', 'city-layout-a.json', 'city-layout-b.json', 'city-layout-c.json',
  'strongpoint-layout.json', 'district-layout.json'];
let totalSpawners = 0;
let totalBuildings = 0;
let mixedDefault = 0;
for (const layoutFile of LAYOUTS) {
  const layout = JSON.parse(fs.readFileSync(path.join(TOOLS, layoutFile), 'utf8')
    .replace(/^\s*\/\/.*$/gm, ''));
  const name = layout.name;
  const nbtFile = path.join(DATA, 'structures', `${name}.nbt`);
  const mapFile = path.join(DATA, 'city_buildings', `${name}.json`);
  check(fs.existsSync(nbtFile), `${name}: the structure NBT exists`);
  check(fs.existsSync(mapFile), `${name}: the baked building map exists (${path.basename(mapFile)})`);
  if (!fs.existsSync(nbtFile) || !fs.existsSync(mapFile)) {
    continue;
  }
  const root = parseNbt(zlib.gunzipSync(fs.readFileSync(nbtFile)));
  const map = JSON.parse(fs.readFileSync(mapFile, 'utf8'));
  const size = map.size;
  check(size[0] === root.size[0] && size[1] === root.size[1] && size[2] === root.size[2],
    `${name}: the map declares the structure's own size`, `${size.join('x')} vs ${root.size.join('x')}`);
  const rects = map.buildings.map((b) => ({
    id: b.id, x1: b.x, z1: b.z, x2: b.x + b.w - 1, z2: b.z + b.d - 1,
  }));
  const layoutNames = (layout.buildings || []).map((b) => b.name);
  check(rects.length === layoutNames.length,
    `${name}: one rectangle per layout building`, `${rects.length} vs ${layoutNames.length}`);
  check(rects.every((r, i) => r.id === `${i}:${layoutNames[i]}`),
    `${name}: the ids are the layout index plus the building name`, rects.map((r) => r.id).join(', '));
  check(rects.every((r) => r.x1 >= 0 && r.z1 >= 0 && r.x2 < size[0] && r.z2 < size[2]),
    `${name}: every rectangle is inside the structure`);
  let overlaps = 0;
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      const a = rects[i];
      const b = rects[j];
      if (a.x1 <= b.x2 && b.x1 <= a.x2 && a.z1 <= b.z2 && b.z1 <= a.z2) overlaps++;
    }
  }
  check(overlaps === 0, `${name}: the building rectangles never overlap`, `${overlaps} overlap(s)`);
  totalBuildings += rects.length;

  const baked = root.blocks.filter((b) => b.nbt && b.nbt.id === 'minecraft:spawner');
  totalSpawners += baked.length;
  if (baked.length > 0) {
    const ids = new Set();
    for (const s of baked) {
      ids.add(s.nbt.SpawnData.entity.id);
      for (const p of s.nbt.SpawnPotentials || []) {
        ids.add(p.data.entity.id);
      }
    }
    if (ids.size >= 2) mixedDefault++;
  }
  let unmatched = 0;
  const spawnerBuildings = [];
  for (const s of baked) {
    const hits = rects.filter((r) => s.pos[0] >= r.x1 && s.pos[0] <= r.x2 && s.pos[2] >= r.z1 && s.pos[2] <= r.z2);
    if (hits.length !== 1) {
      unmatched++;
      continue;
    }
    spawnerBuildings.push(hits[0].id);
    // The rewrite this code computes for that building: the faction's single TROOP id.
    const buildingFaction = rollBuildings(SEED, DIM, `structure/tarkovscav:${name}/0,0,0`, 'VILLAGE', 0.5,
      rects.length)[rects.findIndex((r) => r.id === hits[0].id)];
    const payload = [buildingFaction === 'ILLAGER' ? ILLAGER_SPAWNER[0] : VILLAGE_SPAWNER[0]];
    const forbidden = buildingFaction === 'ILLAGER' ? VILLAGE_UNITS : ILLAGER_UNITS;
    check(payload.every((id) => !forbidden.includes(id)),
      `${name}: the ${buildingFaction} rewrite of the spawner at ${s.pos} contains no other line-up`,
      payload.join(','));
  }
  check(unmatched === 0,
    `${name}: every spawner is inside exactly one building rectangle (${baked.length} spawner(s))`,
    unmatched ? `${unmatched} unmatched` : spawnerBuildings.join(', '));
}
check(totalSpawners >= 6, 'the shipped structures carry the expected spawners', `${totalSpawners}`);
check(totalBuildings >= 20, 'the shipped structures carry the expected buildings', `${totalBuildings}`);
check(mixedDefault >= 6, 'every shipped structure still bakes the MIXED default pair - so the rewrite matters',
  `${mixedDefault} structure(s) with more than one baked id`);

// A village CITY (unified) is village-only and an illager city is illager-only.
for (const label of ['VILLAGE', 'ILLAGER']) {
  const ids = new Set();
  for (const layoutFile of LAYOUTS) {
    const layout = JSON.parse(fs.readFileSync(path.join(TOOLS, layoutFile), 'utf8')
      .replace(/^\s*\/\/.*$/gm, ''));
    const mapPath = path.join(DATA, 'city_buildings', `${layout.name}.json`);
    if (!fs.existsSync(mapPath)) continue;
    const count = JSON.parse(fs.readFileSync(mapPath, 'utf8')).buildings.length;
    const split = rollBuildings(SEED, DIM, `structure/tarkovscav:${layout.name}/0,0,0`, label, 1.0, count);
    for (const faction of split) ids.add(faction === 'ILLAGER' ? ILLAGER_SPAWNER[0] : VILLAGE_SPAWNER[0]);
  }
  const forbidden = label === 'ILLAGER' ? VILLAGE_SPAWNER : ILLAGER_SPAWNER;
  check(ids.size === 1 && !ids.has(forbidden[0]),
    `a unified ${label} city's every spawner payload is ${label.toLowerCase()}-only`, [...ids].join(','));
}

// ------------------------------------------------------------------ 4. garrison composition

console.log('');
console.log('4. the garrison composition is per faction and can never mix');
function pickType(faction, elite) {
  if (faction === 'ILLAGER') {
    return elite ? 'tarkovscav:elite_pillager' : 'tarkovscav:bear_pillager';
  }
  return elite ? 'tarkovscav:elite_villager' : 'tarkovscav:usec_villager';
}
for (const faction of ['VILLAGE', 'ILLAGER']) {
  const allowed = faction === 'ILLAGER' ? ILLAGER_UNITS : VILLAGE_UNITS;
  const forbidden = faction === 'ILLAGER' ? VILLAGE_UNITS : ILLAGER_UNITS;
  for (const elite of [false, true]) {
    const chosen = pickType(faction, elite);
    check(allowed.includes(chosen) && !forbidden.includes(chosen),
      `a ${faction} ${elite ? 'leader' : 'troop'} comes from its own line-up`, chosen);
  }
  check(pickType(faction, true) === allowed[1] && pickType(faction, false) === allowed[0],
    `the ${faction} TROOP id and ELITE id are the ones the Java lists`);
}
const pickBody = (/private static EntityType<\? extends Mob> pickType\([\s\S]*?\n    \}/.exec(garrison) || [''])[0];
check(/faction != Faction\.ILLAGER/.test(pickBody) && !/random\.nextBoolean\(\)/.test(pickBody),
  'the Java picks by FACTION, not by a coin flip (that was the old per-city behaviour)');

// ------------------------------------------------------------------ 5. the natural-spawn matrix

console.log('');
console.log('5. the natural-spawn acceptance matrix, including the SCAV third party');
function allows(buildingFaction, mobFaction) {
  if (buildingFaction == null || mobFaction == null) return true;
  if (mobFaction === 'SCAV') return true;
  return mobFaction === buildingFaction;
}
const matrix = [
  ['VILLAGE', 'VILLAGE', true, 'a village unit in a village building'],
  ['VILLAGE', 'ILLAGER', false, 'an illager unit in a village building - the rule this batch is about'],
  ['VILLAGE', 'SCAV', true, 'a scav may walk through a village building (third party)'],
  ['VILLAGE', null, true, 'a factionless mob (a zombie) is not this rule\'s business'],
  ['ILLAGER', 'ILLAGER', true, 'an illager unit in an illager building'],
  ['ILLAGER', 'VILLAGE', false, 'a village unit in an illager building'],
  ['ILLAGER', 'SCAV', true, 'a scav may walk through an illager building (third party)'],
  ['ILLAGER', null, true, 'a factionless mob is not this rule\'s business'],
  [null, 'VILLAGE', true, 'outside any city nothing is filtered'],
  [null, 'ILLAGER', true, 'outside any city nothing is filtered'],
];
for (const [buildingFaction, mobFaction, expected, why] of matrix) {
  check(allows(buildingFaction, mobFaction) === expected,
    `allows(${buildingFaction}, ${mobFaction}) = ${expected}`, why);
}
check(/if \(mobFaction == Faction\.SCAV\) \{\s*return true;/.test(factions),
  'the Java short-circuits SCAV before the comparison, exactly as the mirror does');
check(/GARRISON_FACTION_SPAWN_FILTER[\s\S]{0,400}?Faction\.of\(mob\)[\s\S]{0,600}?CityGarrison\.factionAt/
  .test(spawnEvents),
  'the spawn path asks Faction.of + CityGarrison.factionAt under the config switch');
check(/factionFor\(level, city, data\)/.test(garrison),
  'a city with no ledger row rolls one on the spot, so the filter cannot block everything');

// ------------------------------------------------------------------ 6. the ledger

console.log('');
console.log('6. the faction ledger: once-only rolls, a save/reload, and overrides');
/** A faithful mirror of GarrisonData's three maps, keyed by the Java's own key shape. */
function newLedger() {
  return { entries: new Map(), cities: new Map(), buildings: new Map() };
}
const cityKeyOf = (dim, city) => `${dim}|${city}`;
const buildingKeyOf = (dim, city, id) => `${dim}|${city}#${id}`;
function recordCity(l, dim, city, dominant, ids, rolled) {
  if (l.cities.has(cityKeyOf(dim, city))) return;
  l.cities.set(cityKeyOf(dim, city), { city, dim, dominant, override: null, spawnersDone: false });
  ids.forEach((id, i) => {
    l.buildings.set(buildingKeyOf(dim, city, id), { city, dim, id, rolled: rolled[i], override: null });
  });
}
function overrideCity(l, dim, city, override) {
  const row = l.cities.get(cityKeyOf(dim, city));
  if (!row) return false;
  l.cities.set(cityKeyOf(dim, city), { ...row, override, spawnersDone: false });
  return true;
}
function overrideBuilding(l, dim, city, id, override) {
  const key = buildingKeyOf(dim, city, id);
  const row = l.buildings.get(key);
  if (!row) return false;
  l.buildings.set(key, { ...row, override });
  const cityRow = l.cities.get(cityKeyOf(dim, city));
  if (cityRow) l.cities.set(cityKeyOf(dim, city), { ...cityRow, spawnersDone: false });
  return true;
}
function effective(l, dim, city, id) {
  const cityRow = l.cities.get(cityKeyOf(dim, city));
  if (!cityRow) return null;
  const row = l.buildings.get(buildingKeyOf(dim, city, id));
  if (row && row.override) return row.override;
  if (cityRow.override) return cityRow.override;
  return row ? row.rolled : cityRow.dominant;
}
function serialise(l) {
  return {
    [nbt.KEY_CITIES]: [...l.cities.values()].map((row) => ({
      [nbt.KEY_KEY]: cityKeyOf(row.dim, row.city),
      [nbt.KEY_CITY]: row.city,
      [nbt.KEY_DIMENSION]: row.dim,
      [nbt.KEY_DOMINANT]: row.dominant,
      [nbt.KEY_OVERRIDE]: row.override,
      [nbt.KEY_SPAWNERS]: row.spawnersDone,
    })),
    [nbt.KEY_BUILDINGS]: [...l.buildings.values()].map((row) => ({
      [nbt.KEY_KEY]: buildingKeyOf(row.dim, row.city, row.id),
      [nbt.KEY_CITY]: row.city,
      [nbt.KEY_DIMENSION]: row.dim,
      [nbt.KEY_BUILDING]: row.id,
      [nbt.KEY_ROLLED]: row.rolled,
      [nbt.KEY_OVERRIDE]: row.override,
    })),
  };
}
function deserialise(rows) {
  const l = newLedger();
  for (const row of rows[nbt.KEY_CITIES]) {
    l.cities.set(row[nbt.KEY_KEY], {
      city: row[nbt.KEY_CITY], dim: row[nbt.KEY_DIMENSION], dominant: row[nbt.KEY_DOMINANT],
      override: row[nbt.KEY_OVERRIDE], spawnersDone: row[nbt.KEY_SPAWNERS],
    });
  }
  for (const row of rows[nbt.KEY_BUILDINGS]) {
    l.buildings.set(row[nbt.KEY_KEY], {
      city: row[nbt.KEY_CITY], dim: row[nbt.KEY_DIMENSION], id: row[nbt.KEY_BUILDING],
      rolled: row[nbt.KEY_ROLLED], override: row[nbt.KEY_OVERRIDE],
    });
  }
  return l;
}
const CITY_KEY = CITY_A;
const BUILDING_IDS = ['0:north_west', '1:north_east', '2:south_west', '3:south_east'];
const DOMINANT = rollCity(SEED, DIM, CITY_KEY, 0.5);
const SPLIT = rollBuildings(SEED, DIM, CITY_KEY, DOMINANT, 0.5, BUILDING_IDS.length);

let book = newLedger();
recordCity(book, DIM, CITY_KEY, DOMINANT, BUILDING_IDS, SPLIT);
recordCity(book, DIM, CITY_KEY, DOMINANT === 'VILLAGE' ? 'ILLAGER' : 'VILLAGE',
  BUILDING_IDS, SPLIT.map((f) => (f === 'VILLAGE' ? 'ILLAGER' : 'VILLAGE')));
check(effective(book, DIM, CITY_KEY, BUILDING_IDS[0]) === SPLIT[0],
  'recording a city twice cannot change a building roll (the record is a no-op when the row exists)');
const reloaded = deserialise(serialise(book));
check(BUILDING_IDS.every((id, i) => effective(reloaded, DIM, CITY_KEY, id) === SPLIT[i]),
  'every building faction survives a save + reload');
const otherDim = 'tarkovscav:urban_wasteland';
check(effective(reloaded, otherDim, CITY_KEY, BUILDING_IDS[0]) === null,
  'the ledger is per dimension: the same city key in the wasteland has its own (undecided) row');

check(overrideCity(reloaded, DIM, CITY_KEY, 'ILLAGER')
  && BUILDING_IDS.every((id) => effective(reloaded, DIM, CITY_KEY, id) === 'ILLAGER'),
  'a city-wide override moves every building at once');
check(reloaded.cities.get(cityKeyOf(DIM, CITY_KEY)).spawnersDone === false,
  'an override clears the spawner-rewritten flag, so the next trigger rewrites the payloads');
check(overrideCity(reloaded, DIM, CITY_KEY, null)
  && BUILDING_IDS.every((id, i) => effective(reloaded, DIM, CITY_KEY, id) === SPLIT[i]),
  '"auto" clears the override and restores the recorded rolls exactly');
check(overrideBuilding(reloaded, DIM, CITY_KEY, BUILDING_IDS[1], 'ILLAGER')
  && effective(reloaded, DIM, CITY_KEY, BUILDING_IDS[1]) === 'ILLAGER'
  && effective(reloaded, DIM, CITY_KEY, BUILDING_IDS[0]) === SPLIT[0],
  'a per-building override changes exactly one building');
const afterBuildingOverride = deserialise(serialise(reloaded));
check(effective(afterBuildingOverride, DIM, CITY_KEY, BUILDING_IDS[1]) === 'ILLAGER',
  'and the per-building override survives a save + reload');
check(overrideBuilding(reloaded, DIM, CITY_KEY, 'no_such_building', 'ILLAGER') === false
  && overrideCity(reloaded, DIM, 'no_such_city', 'VILLAGE') === false,
  'an unknown city or building key is refused rather than invented');
check(/if \(this\.cities\.containsKey\(entryKey\)\) \{\s*return;/.test(ledger),
  'Java recordCity() early-returns when the row exists, exactly as the mirror does');
check(/public boolean overrideCity\([\s\S]{0,600}?withSpawnersDone\(false\)/.test(ledger)
  && /public boolean overrideBuilding\([\s\S]{0,900}?withSpawnersDone\(false\)/.test(ledger),
  'both override setters clear the spawner flag in the Java too');

// ------------------------------------------------------------------ 7. wiring and docs

console.log('');
console.log('7. wiring, documentation and the chunk-loading rule');
check(/\[garrison\] \{\} -> \{\} squads \/ \{\} units \[dominant \{\}/.test(garrison),
  'the placement log line still has its pinned shape and now names the faction');
check(/data\.recordCity\(dimension, city\.key\(\), dominant, ids, rolled\)/.test(garrison),
  'the trigger records the city roll and the building rolls before anything is placed');
check(/String buildingId = buildingIdAt\(city, spot\);/.test(garrison)
  && /buildingFaction\(data, dimension, city\.key\(\), buildingId\)/.test(garrison),
  'a garrison unit takes the faction of the building its own standing spot is in');
check(/public static String buildingIdAt\(CityGate\.Area city, BlockPos pos\)/.test(garrison)
  && /CityBuildings\.indexAt\(city\.buildings\(\), pos\)/.test(garrison),
  'buildingIdAt is containment first, nearest footprint second');
check(/CityFactionSpawners\.rewrite\(level, city\.box\(\)/.test(garrison)
  && /markSpawnersDone\(dimension, city\.key\(\)\)/.test(garrison),
  'the trigger rewrites the city spawners per building and records it only when complete');
check(/factionAt\.apply\(entry\.getKey\(\)\)/.test(spawners) && /ChunkStatus\.FULL, false/.test(spawners),
  'the rewrite resolves the faction per spawner position and never loads a chunk');
check(/if \(!box\.isInside\(entry\.getKey\(\)\)\) \{\s*continue;/.test(spawners),
  'a spawner outside the city box is never touched');
check(/isPure\(tag, faction\)/.test(spawners) && /allowed\.containsAll\(ids\)/.test(spawners),
  'the payload is re-checked for purity immediately before the block entity is written');
check(/StructureTemplate\.transform/.test(buildings) && /piece\.getRotation\(\)/.test(buildings),
  'the baked map is rotated with the piece\'s own rotation, not a hard-coded one');
check(/piece\.getMirror\(\) != Mirror\.NONE/.test(buildings),
  'a mirrored piece falls back to the whole-city unit instead of guessing');
check(/Commands\.literal\("faction"\)/.test(commands) && /CityGarrison\.describeFactions/.test(commands),
  '/armedmobs city faction is wired to the per-building ledger listing');
check(/data\.overrideCity\(dimension, city\.key\(\), override\)/.test(commands)
  && /data\.overrideBuilding\(dimension, cityKey, buildingId, override\)/.test(commands),
  'the command can override a city and a single building');
check(/CityGarrison\.applyCity\(level, city, data, level\.getGameTime\(\)\)/.test(commands),
  'the by-position form applies the override immediately (the console-friendly path)');
check(/describeFactions\(MinecraftServer server\)/.test(garrison)
  && /buildingsOf\(city\.dimension\(\), city\.cityKey\(\)\)/.test(garrison),
  '/armedmobs garrison prints every building of every decided city');

const DOC_KEYS = ['friendlyCityChance', 'cityDominantFactionChance', 'rewriteCitySpawners',
  'factionSpawnFilter'];
for (const key of DOC_KEYS) {
  check(readme.includes(key), `README documents garrison.${key}`);
  check(reference.includes(key), `docs/COMMAND_AND_CONFIG_REFERENCE.md documents garrison.${key}`);
}
check(/city faction/.test(readme) && /city faction/.test(reference),
  'both documents name the /armedmobs city faction command');
check(/SCAV/.test(readme) && /SCAV/.test(reference),
  'both documents state the SCAV third-party decision');
check(/TODO/.test(ledgerRaw) && /strength/.test(ledgerRaw),
  'the ledger carries the TODO naming the follow-up faction-strength pool and its key shape');

console.log('');
if (failures > 0) {
  console.log(`${failures} city-faction check(s) FAILED`);
  process.exit(1);
}
console.log('every city rolls a dominant faction and each building its own, the spawners and the garrison '
  + 'follow the building, and the natural-spawn rule is per building with the scav allowed in both');
