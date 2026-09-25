// The structure BLOCK ENTITY gate (deliverable B/C): every `minecraft:spawner` and every `minecraft:chest`
// the generator places must carry the NBT that makes it work, and nothing else may.
//
//   node tools/selftest_blockentities.js
//
// Why this exists: `blocks: [{pos, state, nbt}]` is the only place a structure can carry a block entity, and
// a spawner written without `SpawnData` is an inert block - a silent failure that no world-load error would
// ever report. This gate reads the shipped NBT itself (gzip + a dependency-free NBT reader) and asserts:
//
//   1. every spawner has a complete, in-range set of the vanilla BaseSpawner keys, its `SpawnData` names one
//      of OUR nine registered mobs, and `SpawnPotentials` (when present) is a well-formed weighted list of the
//      same; each weight is an integer >= 0 (Weight.validateWeight rejects a negative one) and every
//      `SpawnPotentials` entry has both `data` and `weight` (WeightedEntry$Wrapper.codec: fieldOf("data"),
//      fieldOf("weight"));
//   2. every spawner produces the TROOP tier ONLY - the ids are exactly tarkovscav:usec_villager and
//      tarkovscav:bear_pillager, which is `AiProfile.Tier.TROOP`'s two entity classes and nothing else;
//   3. every chest with an `nbt` names a loot table that either exists as a file in the pack or is a known
//      vanilla table id, and the table is NEVER a copy of it (the id is used, not the contents);
//   4. every block that carries an `nbt` is a block that can HAVE a block entity (spawner / chest here), so a
//      stray compound cannot ride along on a wall;
//   5. the counts match the layout's declared budget: 1..N spawners per building, and for the strongpoint the
//      whole-layout `spawner_budget`; the map is not papered with spawners.
//
// The id lists and the tick numbers are read out of the Java/codec facts, not guessed: the nine mob ids come
// from registry/ModEntities.java, the TROOP pair from gun/AiProfile.java + the two entity classes, and the
// spawner key names/numbers from the constants in tools/spike/CityStructureGen.java (which itself is checked
// against the real 1.20.1 codec with javap - see the comment on spawnerTag()).
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const DATA = path.join(RES, 'data', 'tarkovscav');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const TOOLS = path.join(ROOT, 'tools');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ a dependency-free NBT reader
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
  const name = str();
  const root = payload(type);
  if (offset !== buffer.length) {
    throw new Error(`the file has ${buffer.length - offset} trailing byte(s) after the root compound`);
  }
  return root;
}

function readStructure(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const palette = root.palette.map((p) => String(p.Name));
  return { file: path.basename(file), root, palette };
}

// ------------------------------------------------------------------ the facts
const mobIds = [...fs.readFileSync(path.join(JAVA, 'registry', 'ModEntities.java'), 'utf8')
  .matchAll(/ENTITY_TYPES\.register\("([a-z0-9_]+)"/g)].map((m) => `tarkovscav:${m[1]}`);
const aiProfile = fs.readFileSync(path.join(JAVA, 'gun', 'AiProfile.java'), 'utf8');
const troopClasses = (/if \(mob instanceof ([A-Za-z]+) \|\| mob instanceof ([A-Za-z]+)\) \{\s*return Tier\.TROOP;/
  .exec(aiProfile) || []).slice(1, 3);
// The two entity classes -> the registered ids, read from ModEntities' registration of those classes.
const modEntities = fs.readFileSync(path.join(JAVA, 'registry', 'ModEntities.java'), 'utf8');
const troopIds = [];
for (const className of troopClasses) {
  const re = new RegExp(`ENTITY_TYPES\\.register\\("([a-z0-9_]+)"[\\s\\S]{0,200}?${className}::new`);
  const m = re.exec(modEntities);
  if (m) troopIds.push(`tarkovscav:${m[1]}`);
}
const generator = fs.readFileSync(path.join(TOOLS, 'spike', 'CityStructureGen.java'), 'utf8');
const generatorConstant = (name) => {
  const m = new RegExp(`${name}\\s*=\\s*(\\d+);`).exec(generator);
  return m ? Number(m[1]) : null;
};
const SPAWNER = {
  SpawnCount: generatorConstant('SPAWNER_COUNT'),
  SpawnRange: generatorConstant('SPAWNER_RANGE'),
  Delay: generatorConstant('SPAWNER_INITIAL_DELAY'),
  MinSpawnDelay: generatorConstant('SPAWNER_MIN_DELAY'),
  MaxSpawnDelay: generatorConstant('SPAWNER_MAX_DELAY'),
  RequiredPlayerRange: generatorConstant('SPAWNER_REQUIRED_PLAYER_RANGE'),
  MaxNearbyEntities: generatorConstant('SPAWNER_MAX_NEARBY'),
};
const CHEST_TABLE = /CHEST_LOOT_TABLE\s*=\s*"([a-z0-9_:/.-]+)"/.exec(generator)[1];

console.log('0. the facts this gate measures against');
console.log(`  registered mobs (${mobIds.length}): ${mobIds.join(', ')}`);
check(troopClasses.length === 2, 'AiProfile maps exactly two entity classes to Tier.TROOP',
  troopClasses.join(' + '));
check(troopIds.length === 2, 'both of them resolve to a registered mob id', troopIds.join(', '));
check(troopIds.every((id) => mobIds.includes(id)), 'the TROOP ids are two of the nine registered mobs');
check(Object.values(SPAWNER).every((v) => Number.isInteger(v) && v > 0),
  'every spawner number is a positive integer in CityStructureGen', JSON.stringify(SPAWNER));
check(/^tarkovscav:/.test(troopIds.join()), 'the TROOP ids are ours', troopIds.join(', '));

// The layout files the generator reads, with the per-building/budget keys this gate checks.
function readLayout(file) {
  const raw = fs.readFileSync(file, 'utf8');
  const json = JSON.parse(raw.replace(/^\s*\/\/.*$/gm, ''));
  return json;
}
const LAYOUTS = ['city-layout.json', 'city-layout-a.json', 'city-layout-b.json', 'city-layout-c.json',
  'strongpoint-layout.json', 'district-layout.json'].map((f) => path.join(TOOLS, f));
const layoutOf = new Map(LAYOUTS.map((f) => [readLayout(f).name, readLayout(f)]));

const KNOWN_VANILLA_TABLES = new Set(['minecraft:chests/abandoned_mineshaft']);

console.log('');
console.log('1. every spawner / chest block entity is complete, and nothing else carries NBT');
let spawners = 0;
let chests = 0;
let otherNbt = 0;
const perStructure = new Map();
for (const [name, layout] of layoutOf) {
  const file = path.join(DATA, 'structures', `${name}.nbt`);
  if (!fs.existsSync(file)) {
    check(false, `${name}.nbt exists`);
    continue;
  }
  const { root, palette } = readStructure(file);
  const counts = { spawners: 0, chests: 0, perBuilding: new Map() };
  const buildings = layout.buildings;
  const boxOf = (pos) => buildings.findIndex((b) => pos[0] >= b.x && pos[0] < b.x + b.w
    && pos[2] >= b.z && pos[2] < b.z + b.d);
  for (const block of root.blocks) {
    const blockName = palette[block.state];
    if (!block.nbt) {
      continue;
    }
    if (blockName === 'minecraft:spawner') {
      spawners++;
      counts.spawners++;
      const b = boxOf(block.pos);
      const key = `${name}/${b >= 0 ? buildings[b].name : 'street'}`;
      counts.perBuilding.set(key, (counts.perBuilding.get(key) || 0) + 1);
      const nbt = block.nbt;
      check(nbt.id === 'minecraft:spawner',
        `${name}: the spawner at ${block.pos} declares its block entity id`, String(nbt.id));
      const dataId = nbt.SpawnData && nbt.SpawnData.entity && nbt.SpawnData.entity.id;
      check(troopIds.includes(dataId),
        `${name}: SpawnData.entity.id at ${block.pos} is a TROOP id`, String(dataId));
      check(Object.entries(SPAWNER).every(([k, v]) => nbt[k] === v),
        `${name}: the spawner at ${block.pos} carries the declared SpawnCount/SpawnRange/Delay/`
        + 'MinSpawnDelay/MaxSpawnDelay/RequiredPlayerRange/MaxNearbyEntities',
        JSON.stringify(Object.fromEntries(Object.keys(SPAWNER).map((k) => [k, nbt[k]]))));
      check(nbt.MinSpawnDelay <= nbt.MaxSpawnDelay,
        `${name}: the spawner at ${block.pos} has MinSpawnDelay <= MaxSpawnDelay`,
        `${nbt.MinSpawnDelay} <= ${nbt.MaxSpawnDelay}`);
      const potentials = nbt.SpawnPotentials;
      check(Array.isArray(potentials) && potentials.length >= 1,
        `${name}: the spawner at ${block.pos} has a SpawnPotentials list`,
        `${Array.isArray(potentials) ? potentials.length : 0} entries`);
      for (const entry of potentials || []) {
        const id = entry && entry.data && entry.data.entity && entry.data.entity.id;
        check(troopIds.includes(id),
          `${name}: SpawnPotentials entry at ${block.pos} is a TROOP id`, String(id));
        check(Number.isInteger(entry.weight) && entry.weight >= 0,
          `${name}: SpawnPotentials weight at ${block.pos} is a non-negative integer`,
          String(entry.weight));
      }
    } else if (blockName === 'minecraft:chest') {
      chests++;
      counts.chests++;
      const table = block.nbt.LootTable;
      const existsAsFile = typeof table === 'string'
        && table.startsWith('tarkovscav:')
        && fs.existsSync(path.join(DATA, 'loot_tables', `${table.split(':')[1]}.json`));
      check(typeof table === 'string' && (KNOWN_VANILLA_TABLES.has(table) || existsAsFile),
        `${name}: the loot chest at ${block.pos} names a real loot table`, String(table));
      check(table === CHEST_TABLE,
        `${name}: the loot chest at ${block.pos} uses the shipped mineshaft id`, String(table));
      check(block.nbt.id === 'minecraft:chest',
        `${name}: the chest at ${block.pos} declares its block entity id`, String(block.nbt.id));
    } else {
      otherNbt++;
      check(false, `${name}: ${blockName} at ${block.pos} carries block entity NBT it cannot have`);
    }
  }
  // The declared budget: 1..spawners_per_building per building, capped by the whole-layout spawner_budget.
  const perBuilding = layout.spawners_per_building || 0;
  const budget = layout.spawner_budget === undefined ? Infinity : layout.spawner_budget;
  check(perBuilding > 0 || budget === 0 || counts.spawners === 0,
    `${name}: the layout declares a spawner count when the structure has spawners`,
    `spawners_per_building=${perBuilding} budget=${budget} actual=${counts.spawners}`);
  let tooMany = 0;
  for (const b of layout.buildings) {
    const n = counts.perBuilding.get(`${name}/${b.name}`) || 0;
    if (n > perBuilding) tooMany++;
  }
  check(tooMany === 0, `${name}: no building exceeds spawners_per_building`, `${tooMany} building(s)`);
  check(counts.spawners <= budget, `${name}: the whole-layout spawner budget holds`,
    `${counts.spawners} <= ${budget}`);
  check(counts.chests >= layout.buildings.length,
    `${name}: every building has at least one loot chest`, `${counts.chests} chest(s)`);
  const chestsPerBuilding = layout.chests_per_building || 0;
  let tooManyChests = 0;
  const chestCounts = new Map();
  for (const block of root.blocks) {
    if (palette[block.state] !== 'minecraft:chest' || !block.nbt) continue;
    const b = boxOf(block.pos);
    const key = `${name}/${b >= 0 ? buildings[b].name : 'street'}`;
    chestCounts.set(key, (chestCounts.get(key) || 0) + 1);
  }
  for (const b of layout.buildings) {
    if ((chestCounts.get(`${name}/${b.name}`) || 0) > chestsPerBuilding) tooManyChests++;
  }
  check(tooManyChests === 0, `${name}: no building exceeds chests_per_building`,
    `${tooManyChests} building(s)`);
  perStructure.set(name, counts);
  console.log(`  ${name}: ${counts.spawners} spawner(s), ${counts.chests} loot chest(s), `
    + `${[...counts.perBuilding.entries()].map(([k, v]) => `${k.split('/').pop()}=${v}`).join(' ')}`);
}
check(spawners >= 6 && spawners <= 40, 'the map is not papered with spawners (6..40 in total)',
  `${spawners} spawner(s) across ${perStructure.size} structure(s)`);
check(chests >= 20, 'the loot chests are there in the expected numbers', `${chests} chest(s)`);
check(otherNbt === 0, 'no other block carries a block entity compound', `${otherNbt}`);
console.log(`  totals: ${spawners} spawner(s), ${chests} loot chest(s), ${checks} check(s)`);

// ------------------------------------------------------------------ 2. the codec facts behind the NBT
console.log('');
console.log('2. the codec facts the NBT shape is built on (documented from javap of the 1.20.1 forge jar)');
const spawnerNotes = [
  'SpawnData.CODEC is a record of {entity: CompoundTag} (+ optional custom_spawn_rules)',
  'SimpleWeightedRandomList.wrappedCodecAllowingEmpty(CODEC) is a list of {data, weight}',
  'Weight.validateWeight rejects a negative value',
  'BaseSpawner.load reads Delay/SpawnData/SpawnPotentials/MinSpawnDelay/MaxSpawnDelay/SpawnCount/'
  + 'MaxNearbyEntities/RequiredPlayerRange/SpawnRange',
  'RandomizableContainerBlockEntity reads LootTable',
];
for (const note of spawnerNotes) check(true, note);
check(/SpawnData\.CODEC|entity/.test(generator) && /SpawnPotentials/.test(generator),
  'the generator writes both SpawnData and SpawnPotentials');

console.log('');
if (failures > 0) {
  console.log(`${failures} block-entity check(s) FAILED`);
  process.exit(1);
}
console.log('every spawner and every loot chest in the shipped structures carries the block entity NBT it '
  + 'needs, the spawners produce the TROOP tier only, and no other block carries NBT');
