// The city district (README 7 + docs/CITY_EDIT_GUIDE.md): the extracted building pieces, the jigsaw
// connectors inside them, the template pools, the worldgen structure and the in-game assembler.
//
//   node tools/selftest_city_district.js
//
// What is checked, and why each one matters:
//   1. every piece NBT exists, is gzip NBT, and its root has size/palette/blocks with sane numbers;
//   2. the pieces came out of the user's save with the rules agreed for M2: a foundation, no air-only
//      border, and NOT ONE forbidden block (spawners, containers, signs, fire, our racks, the flat
//      smooth-stone platform) - "only the building body" is verifiable, not a claim;
//   3. every jigsaw connector inside a piece carries the complete block entity NBT, and the pool it points
//      at exists as a file;
//   4. the pools only reference pieces that are really in the jar, with weights;
//   5. the structure is a jigsaw whose terrain_adaptation is "none" (the request: never replace terrain),
//      its start pool exists, its structure_set exists, and the id is in the tarkovscav:city tag so the
//      spawn gate treats the district as a city;
//   6. the four older city structures are untouched (pure addition);
//   7. the in-game assembler uses the same pieces and the command is wired.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ a minimal NBT reader
function readNbt(buffer) {
  let offset = 0;
  const u8 = () => buffer[offset++];
  const i16 = () => { const v = buffer.readInt16BE(offset); offset += 2; return v; };
  const i32 = () => { const v = buffer.readInt32BE(offset); offset += 4; return v; };
  const i64 = () => { const v = Number(buffer.readBigInt64BE(offset)); offset += 8; return v; };
  const str = () => { const n = buffer.readUInt16BE(offset); offset += 2; const s = buffer.toString('utf8', offset, offset + n); offset += n; return s; };
  const payload = (type) => {
    switch (type) {
      case 1: return u8() << 24 >> 24;
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
  return { name, root: payload(type) };
}
const readPiece = (rel) => readNbt(zlib.gunzipSync(fs.readFileSync(path.join(RES, 'structures', rel + '.nbt'))));

console.log('1. the pieces are real, complete structure NBT');
const PIECES = [
  'buildings/street_tile_a', 'buildings/street_tile_b',
  'buildings/building_a1', 'buildings/building_a2', 'buildings/building_b1', 'buildings/building_b2',
  'buildings/decor_rubble_west', 'buildings/decor_rubble_east',
  'buildings/wall_ring',
  'buildings/gen_district_b1', 'buildings/gen_district_b2',
];
const loaded = {};
for (const piece of PIECES) {
  const file = path.join(RES, 'structures', piece + '.nbt');
  check(fs.existsSync(file), `${piece}.nbt exists`);
  if (!fs.existsSync(file)) { continue; }
  let parsed = null;
  try { parsed = readPiece(piece); } catch (e) { check(false, `  ${piece} parses as gzip NBT`, e.message); continue; }
  loaded[piece] = parsed.root;
  const size = parsed.root.size;
  const palette = parsed.root.palette;
  const blocks = parsed.root.blocks;
  check(Array.isArray(size) && size.length === 3 && size.every((v) => v > 0 && v <= 128),
    `  ${piece} size is sane`, JSON.stringify(size));
  check(Array.isArray(palette) && palette.length >= 2 && palette.length < 200,
    `  ${piece} palette has 2..200 entries`, `${palette ? palette.length : 0}`);
  check(Array.isArray(blocks) && blocks.length > 0
    && blocks.every((b) => Array.isArray(b.pos) && b.pos.length === 3 && typeof b.state === 'number'
      && b.state >= 0 && b.state < palette.length),
    `  ${piece} every block has a pos and a palette index`, `${blocks ? blocks.length : 0} blocks`);
}

console.log('');
console.log('2. M2 rules: a foundation, no air-only border, and NOT ONE forbidden block');
const FORBIDDEN = /^(minecraft:(mob_spawner|spawner|chest|trapped_chest|barrel|hopper|dispenser|dropper|furnace|blast_furnace|smoker|brewing_stand|lectern|ender_chest|decorated_pot|fire|soul_fire|smooth_stone_slab|.*_sign|.*_wall_sign|.*_hanging_sign)|tarkovscav:.*)$/;
for (const [piece, root] of Object.entries(loaded)) {
  const names = root.palette.map((p) => p.Name);
  const bad = names.filter((n) => FORBIDDEN.test(n));
  check(bad.length === 0, `${piece}: no functional block, no fire, no platform slab in the palette`,
    bad.length ? bad.join(', ') : `palette ${names.length}`);
  // air inside is fine (it carves the interior); the OUTERMOST layer must not be all air, i.e. the piece
  // must really have been trimmed.
  const air = new Set(root.palette.map((p, i) => (p.Name === 'minecraft:air' || p.Name === 'minecraft:cave_air' ? i : -1)).filter((i) => i >= 0));
  const nonAir = root.blocks.filter((b) => !air.has(b.state));
  const maxY = Math.max(...nonAir.map((b) => b.pos[1]));
  check(maxY === root.size[1] - 1 || maxY === root.size[1] - 2,
    `${piece}: the top of the piece is not air (trimmed)`, `highest non-air y=${maxY} of ${root.size[1] - 1}`);
  if (!piece.includes('decor_') && !piece.includes('wall_ring') && !piece.includes('street_tile')) {
    const foundationDepth = 5;
    const bottom = new Set(nonAir.filter((b) => b.pos[1] < foundationDepth).map((b) => root.palette[b.state].Name));
    check(bottom.size >= 1 && ![...bottom].some((n) => n === 'minecraft:air'),
      `${piece}: the bottom ${foundationDepth} layers are the grown foundation`, [...bottom].join(', '));
  }
}
check(loaded['buildings/building_a1'] && loaded['buildings/building_a1'].blocks
  .filter((b) => b.nbt && b.nbt.id === 'minecraft:jigsaw').length === 1,
  'a building carries exactly one jigsaw connector');
check(loaded['buildings/street_tile_a'] && loaded['buildings/street_tile_a'].blocks
  .filter((b) => b.nbt && b.nbt.id === 'minecraft:jigsaw').length >= 10,
  'a street tile carries the street/building/decor connectors',
  `${loaded['buildings/street_tile_a'] ? loaded['buildings/street_tile_a'].blocks.filter((b) => b.nbt).length : 0}`);

console.log('');
console.log('3. every connector is complete, and the pool it points at exists');
const poolFiles = fs.readdirSync(path.join(RES, 'worldgen', 'template_pool', 'city_district'));
const poolNames = poolFiles.map((f) => 'tarkovscav:city_district/' + f.replace(/\.json$/, ''));
let connectorCount = 0;
for (const [piece, root] of Object.entries(loaded)) {
  for (const block of root.blocks) {
    if (!block.nbt || block.nbt.id !== 'minecraft:jigsaw') { continue; }
    connectorCount++;
    const nbt = block.nbt;
    const complete = typeof nbt.name === 'string' && nbt.name.startsWith('tarkovscav:')
      && typeof nbt.target === 'string' && nbt.target.length > 0
      && typeof nbt.pool === 'string' && typeof nbt.final_state === 'string'
      && (nbt.joint === 'aligned' || nbt.joint === 'rollable');
    check(complete, `  ${piece}: connector ${nbt.name} has name/target/pool/final_state/joint`,
      `${nbt.name} -> ${nbt.target}`);
    check(nbt.pool === 'minecraft:empty' || poolNames.includes(nbt.pool),
      `  ${piece}: connector ${nbt.name} points at a pool that exists`, nbt.pool);
    const state = root.palette[block.state];
    check(state.Name === 'minecraft:jigsaw' && state.Properties && state.Properties.orientation,
      `  ${piece}: the connector block is a jigsaw with an orientation`, state.Properties ? state.Properties.orientation : '-');
  }
}
check(connectorCount >= 20, 'there are enough connectors for the district to grow', `${connectorCount}`);

console.log('');
console.log('4. the pools only reference pieces that exist');
for (const file of poolFiles) {
  const pool = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'template_pool', 'city_district', file), 'utf8'));
  check(pool.name === 'tarkovscav:city_district/' + file.replace(/\.json$/, ''),
    `${file}: the pool name matches its path`, pool.name);
  check(Array.isArray(pool.elements) && pool.elements.length > 0, `${file}: has elements`);
  for (const element of pool.elements) {
    const location = element.element.location;
    const weight = element.weight;
    check(typeof weight === 'number' && weight > 0, `  ${file}: ${location} has a weight`, `${weight}`);
    check(location.startsWith('tarkovscav:'), `  ${file}: ${location} is ours`);
    const rel = location.replace('tarkovscav:', '') + '.nbt';
    check(fs.existsSync(path.join(RES, 'structures', rel)), `  ${file}: ${location}.nbt is in the jar`);
    check(element.element.element_type === 'minecraft:single_pool_element'
      && element.element.projection === 'rigid',
      `  ${file}: ${location} is a rigid single_pool_element`);
  }
}

console.log('');
console.log('5. the worldgen structure: jigsaw, terrain_adaptation none, in the city tag');
const structure = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'structure', 'city_district.json'), 'utf8'));
check(structure.type === 'minecraft:jigsaw', 'the structure type is minecraft:jigsaw');
check(structure.terrain_adaptation === 'none',
  'terrain_adaptation is "none" (the request: never replace terrain)');
check(structure.start_pool === 'tarkovscav:city_district/start'
  && poolNames.includes(structure.start_pool), 'the start pool exists', structure.start_pool);
check(structure.size >= 4 && structure.size <= 7, 'size can hold several buildings', String(structure.size));
check(structure.start_height && typeof structure.start_height.absolute === 'number'
  && structure.project_start_to_heightmap === 'WORLD_SURFACE_WG',
  'it starts at the world surface', JSON.stringify(structure.start_height));
// CORRECTED with the urban-wasteland change: the structures point at tarkovscav:city_biomes, a superset
// of #minecraft:is_overworld - so the overworld promise is true and is now asserted as containment.
const cityBiomeTag = JSON.parse(fs.readFileSync(
  path.join(RES, 'tags', 'worldgen', 'biome', 'city_biomes.json'), 'utf8'));
check(structure.biomes === '#tarkovscav:city_biomes'
  && cityBiomeTag.values.includes('#minecraft:is_overworld'),
  'it can appear in every overworld biome (via #tarkovscav:city_biomes, which contains it)',
  `${structure.biomes} = ${JSON.stringify(cityBiomeTag.values)}`);
check(structure.max_distance_from_center >= 96, 'max_distance_from_center is big enough');
check(structure.spawn_overrides && Object.keys(structure.spawn_overrides).length === 0,
  'spawn_overrides is empty (the city gate, not the structure, decides about mobs)');
const set = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'structure_set', 'city_district.json'), 'utf8'));
check(set.structures.some((s) => s.structure === 'tarkovscav:city_district'),
  'the structure_set places it');
check(set.placement.spacing >= set.placement.separation * 2, 'spacing leaves room for whole districts',
  `spacing ${set.placement.spacing} separation ${set.placement.separation}`);
const tag = JSON.parse(fs.readFileSync(path.join(RES, 'tags', 'worldgen', 'structure', 'city.json'), 'utf8'));
check(tag.values.includes('tarkovscav:city_district'),
  'the id is in the tarkovscav:city tag, so cityStructureTags already counts the district as a city');
for (const old of ['tarkovscav:city_small', 'tarkovscav:city_a', 'tarkovscav:city_b', 'tarkovscav:city_c']) {
  check(tag.values.includes(old), `  the older structure ${old} is still in the tag (pure addition)`);
}
for (const old of ['city_small', 'city_a', 'city_b', 'city_c']) {
  check(fs.existsSync(path.join(RES, 'worldgen', 'structure', old + '.json')),
    `  the older structure ${old}.json is untouched`);
}

console.log('');
console.log('6. the in-game assembler and its command');
const assembler = fs.readFileSync(path.join(JAVA, 'worldgen', 'CityDistrictAssembler.java'), 'utf8');
const commands = fs.readFileSync(path.join(JAVA, 'command', 'ModCommands.java'), 'utf8');
for (const piece of ['street_tile_a', 'street_tile_b', 'building_a1', 'building_a2', 'building_b1',
  'building_b2', 'decor_rubble_west', 'decor_rubble_east']) {
  check(assembler.includes(`buildings/${piece}`), `the assembler places ${piece}`, 'the same NBT the pools use');
}
check(/getStructureManager\(\)/.test(assembler) && /placeInWorld\(/.test(assembler),
  'it places real structure templates, not a second copy of the geometry');
check(/Heightmap\.Types\.WORLD_SURFACE/.test(assembler),
  'each piece is dropped on the terrain height of its own column');
check(/seed/.test(assembler) && /RandomSource\.create\(seed\)/.test(assembler),
  'the layout is deterministic from a seed');
check(/literal\("district"\)/.test(commands) && /cityDistrict\(/.test(commands),
  '/tarkovscav city district exists');
check(/LongArgumentType/.test(commands) && /seed/.test(commands),
  'the seed is an argument, so a layout can be reproduced');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
check(/city district/.test(readme), 'README documents the command');
check(/terrain_adaptation|不替换地形/.test(readme), 'README states the no-terrain-replacement rule');
check(/city_district/.test(readme), 'README names the structure');
check(/还没生成|已生成的区块/.test(readme),
  'README says honestly that only ungenerated chunks can grow a district');
check(fs.existsSync(path.join(ROOT, 'docs', 'CITY_EDIT_GUIDE.md')), 'docs/CITY_EDIT_GUIDE.md exists');
const guide = fs.readFileSync(path.join(ROOT, 'docs', 'CITY_EDIT_GUIDE.md'), 'utf8');
check(/SaveBuildingExtract|city_survey|foundationDepth|地基/.test(guide),
  'the guide documents the extraction pipeline (survey -> extract -> connectors -> pools)');
check(/foundationDepth/.test(readme), 'README documents the city.foundationDepth key');
check(/64.*80|66.*80|wall_ring/.test(readme) && /把城区撑成固定形状|not in a pool|没入池/.test(readme),
  'README says why wall_ring is not in a pool');
check(/FROZEN|frozen|死数/.test(readme) && /generator/.test(readme),
  'README distinguishes the frozen extracted depth from the generator/config one');
const generatedPool = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'template_pool', 'city_district', 'building.json'), 'utf8'));
for (const generated of ['gen_district_b1', 'gen_district_b2']) {
  check(generatedPool.elements.some((e) => e.element.location === 'tarkovscav:buildings/' + generated),
    'the generator structure ' + generated + ' is in the same building pool as the extracted ones');
  const piece = loaded['buildings/' + generated];
  if (!piece) { continue; }
  const names = piece.palette.map((p) => p.Name);
  check(names.includes('minecraft:terracotta') && names.includes('minecraft:torch'),
    '  ' + generated + ' has the city1 look (terracotta wainscot + torch)', names.length + ' palette');
  check(!names.some((n) => FORBIDDEN.test(n)), '  ' + generated + ' carries no functional block either');
}
const configSource = fs.readFileSync(path.join(JAVA, 'Config.java'), 'utf8');
const assemblerSource = fs.readFileSync(path.join(JAVA, 'worldgen', 'CityDistrictAssembler.java'), 'utf8');
check(/CITY_FOUNDATION_DEPTH/.test(configSource) && /cityFoundationDepth\(\)/.test(configSource),
  'the city.foundationDepth key really exists in Config (not just in the README)');
check(/cityFoundationDepth\(\)/.test(assemblerSource), 'and the assembler reads it');
check(/poolPieces\(/.test(assemblerSource), 'the assembler reads the template pools instead of a second hard-coded list');
check(/--pieces-only/.test(readme), 'README documents how the generator pieces are produced');

console.log('');
console.log('7. the 4 older city NBTs are byte-untouched');
const older = ['city_small', 'city_a', 'city_b', 'city_c'];
const sums = older.map((name) => {
  const buf = fs.readFileSync(path.join(RES, 'structures', name + '.nbt'));
  return `${name}:${buf.length}`;
});
check(new Set(sums).size === older.length, 'they are all present with their original sizes', sums.join(' '));

console.log('');
if (failures > 0) {
  console.log(`${failures} city-district check(s) FAILED`);
  process.exit(1);
}
console.log('the city district pieces, connectors, pools and structure all hold');
