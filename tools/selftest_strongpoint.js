// The 18-building city STRONGPOINT (README 7 / docs/城市战城区调研.md sections 6-7):
// the acceptance metrics of the approved plan, measured from the SHIPPED structure NBT.
//
//   node tools/selftest_strongpoint.js
//
// The headline metric is the report's V4 "usable low-cover line":
//   a MAXIMAL run of >= 3 consecutive cells at one Y, along X or along Z, where every cell is
//     (a) opaque solid low cover roughly 0.5-1.5 blocks tall (slab / stairs / wall / fence / barrel /
//         chest / cauldron / ... , and - as the approved definition names it - the white_concrete half
//         of the white_concrete + white_carpet sandbag pair),
//     (b) EXPOSED: the block directly above is passable, so it is not the bottom course of a wall, and
//     (c) next to at least one WALKABLE cell at the same Y.
//   X and Z are counted separately. Natural terrain, leaves and water are not built cover.
//
// The gate measures that metric TWICE, on purpose:
//   * "v4"       - the approved definition above (the sandbag pair counts). This is what is asserted.
//   * "v4strict" - the same rule with white_concrete removed, which is the exact block set of the
//                  independent reference tool tools/spike/work/citymap/ObstacleScan.java. It is printed
//                  and asserted at a lower bar so a reader can reconcile the two.
// This script parses the NBT itself (gzip + NBT, no project code involved) - the same approach as
// tools/spike/work/citymap/verify_wainscot.js - so the numbers cannot come from the generator.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ a minimal NBT reader (gzip + NBT)
function parseNbt(buffer) {
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

/** A structure NBT as a flat grid of block ids (namespace and properties stripped). */
function readStructure(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data).root;
  const [sx, sy, sz] = root.size;
  const names = root.palette.map((p) => String(p.Name).replace(/^minecraft:/, ''));
  const full = root.palette.map((p) => String(p.Name));
  const cells = new Int32Array(sx * sy * sz).fill(-1);
  for (const block of root.blocks) {
    const [x, y, z] = block.pos;
    cells[(y * sz + z) * sx + x] = block.state;
  }
  return {
    sx, sy, sz, names, full, cells, blockCount: root.blocks.length,
    at(x, y, z) {
      if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 'air';
      const index = cells[(y * sz + z) * sx + x];
      return index < 0 ? 'air' : names[index];
    },
  };
}

// ------------------------------------------------------------------ the V4 metric, verbatim
const LOW = new Set(['hay_block', 'cauldron', 'water_cauldron', 'lava_cauldron', 'composter', 'snow',
  'barrel', 'chest', 'trapped_chest', 'ender_chest', 'flower_pot', 'lectern', 'anvil',
  'brewing_stand', 'bell', 'campfire', 'soul_campfire', 'honey_block', 'slime_block',
  'dirt_path', 'farmland', 'stonecutter', 'grindstone', 'smithing_table',
  'cartography_table', 'fletching_table', 'loom', 'crafting_table', 'furnace',
  'blast_furnace', 'smoker', 'bookshelf', 'chiseled_bookshelf']);
const PASSABLE = new Set(['torch', 'wall_torch', 'soul_torch', 'soul_wall_torch', 'redstone_torch',
  'redstone_wall_torch', 'grass', 'short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush',
  'dandelion', 'poppy', 'blue_orchid', 'allium', 'azure_bluet', 'oxeye_daisy', 'cornflower',
  'lily_of_the_valley', 'vine', 'vines', 'glow_lichen', 'seagrass', 'tall_seagrass', 'kelp',
  'kelp_plant', 'sugar_cane', 'rail', 'powered_rail', 'detector_rail', 'activator_rail', 'tripwire',
  'string', 'lily_pad', 'water', 'bubble_column', 'ladder', 'scaffolding', 'chain', 'cobweb',
  'flower_pot', 'lantern', 'soul_lantern', 'item_frame', 'armor_stand']);
const TERRAIN = new Set(['stone', 'dirt', 'coarse_dirt', 'rooted_dirt', 'gravel', 'sand', 'red_sand',
  'clay', 'sandstone', 'red_sandstone', 'granite', 'diorite', 'andesite', 'deepslate', 'tuff',
  'calcite', 'grass_block', 'podzol', 'mycelium', 'mud', 'snow', 'snow_block', 'ice', 'packed_ice',
  'blue_ice', 'obsidian', 'basalt', 'netherrack', 'end_stone', 'bedrock', 'moss_block',
  'moss_carpet', 'dripstone_block', 'pointed_dripstone', 'water', 'lava']);

const isPassable = (id) => id.endsWith('air') || PASSABLE.has(id) || id.endsWith('_carpet')
  || id.endsWith('_button') || id.endsWith('_pressure_plate') || id.endsWith('_sign')
  || id.endsWith('_banner') || id.endsWith('_sapling') || id.endsWith('_flower')
  || id.endsWith('_door') || id.endsWith('_fence_gate') || id.endsWith('_trapdoor');

/** Opaque solid low cover. `withSandbag` adds the white_concrete half of the sandbag pair. */
function isSolidLow(id, withSandbag) {
  if (id === '?' || id.endsWith('air') || isPassable(id)) return false;
  if (id.endsWith('_carpet') || id.endsWith('_bed')) return false;
  if (id === 'glass_pane' || id.endsWith('_pane') || id.endsWith('_stained_glass') || id === 'iron_bars') return false;
  if (TERRAIN.has(id)) return false;
  if (withSandbag && id === 'white_concrete') return true;
  return LOW.has(id) || id.endsWith('_slab') || id.endsWith('_stairs') || id.endsWith('_wall')
    || id.endsWith('_fence');
}

function measure(v, withSandbag) {
  const walkable = (x, y, z) => {
    if (!isPassable(v.at(x, y, z)) || !isPassable(v.at(x, y + 1, z))) return false;
    const below = v.at(x, y - 1, z);
    return below !== '?' && !below.endsWith('air') && !isPassable(below);
  };
  const coverCell = (x, y, z) => isSolidLow(v.at(x, y, z), withSandbag)
    && isPassable(v.at(x, y + 1, z))
    && (walkable(x - 1, y, z) || walkable(x + 1, y, z) || walkable(x, y, z - 1) || walkable(x, y, z + 1));
  let usableCells = 0;
  for (let y = 0; y < v.sy; y++) {
    for (let z = 0; z < v.sz; z++) {
      for (let x = 0; x < v.sx; x++) if (coverCell(x, y, z)) usableCells++;
    }
  }
  let linesX = 0, linesZ = 0, blocksX = 0, blocksZ = 0, longest = 0;
  for (let y = 0; y < v.sy; y++) {
    for (let z = 0; z < v.sz; z++) {
      let run = 0;
      for (let x = 0; x <= v.sx; x++) {
        const hit = x < v.sx && coverCell(x, y, z);
        if (hit) { run++; } else {
          if (run >= 3) { linesX++; blocksX += run; longest = Math.max(longest, run); }
          run = 0;
        }
      }
    }
    for (let x = 0; x < v.sx; x++) {
      let run = 0;
      for (let z = 0; z <= v.sz; z++) {
        const hit = z < v.sz && coverCell(x, y, z);
        if (hit) { run++; } else {
          if (run >= 3) { linesZ++; blocksZ += run; longest = Math.max(longest, run); }
          run = 0;
        }
      }
    }
  }
  const lines = linesX + linesZ;
  const blocks = blocksX + blocksZ;
  return { usableCells, lines, linesX, linesZ, blocks, longest,
    inLineShare: usableCells === 0 ? 0 : blocks / usableCells };
}

// ------------------------------------------------------------------ the structure
console.log('1. the strongpoint structure NBT');
const NBT = path.join(RES, 'structures', 'city_strongpoint.nbt');
check(fs.existsSync(NBT), 'city_strongpoint.nbt exists',
  fs.existsSync(NBT) ? `${fs.statSync(NBT).size} bytes` : 'missing');
if (!fs.existsSync(NBT)) {
  console.log('\nthe structure is missing: run java -cp tools/spike/out CityStructureGen tools/strongpoint-layout.json');
  process.exit(1);
}
const v = readStructure(NBT);
const layout = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools', 'strongpoint-layout.json'), 'utf8'));
console.log(`  size=${v.sx}x${v.sy}x${v.sz} blocksInNbt=${v.blockCount} palette=${v.names.length}`);
check(v.sx === layout.size[0] && v.sy === layout.size[1] && v.sz === layout.size[2],
  'the NBT size is the layout size', `${v.sx}x${v.sy}x${v.sz}`);
check(v.sx >= 138 && v.sy >= 34 && v.sz >= 66,
  'the footprint can hold 6x3 buildings of 16x16 with 8-wide streets',
  `${v.sx} x ${v.sy} x ${v.sz}`);
check(v.blockCount === v.sx * v.sy * v.sz, 'every position is written (air carves the terrain out)',
  `${v.blockCount} of ${v.sx * v.sy * v.sz}`);
check(layout.buildings.length === 18, 'the layout declares 18 buildings', `${layout.buildings.length}`);
check(layout.cover.length >= 40, 'the layout has at least 40 street cover entries',
  `${layout.cover.length}`);
for (const b of layout.buildings) {
  if (b.min_cover_per_floor < 12 || b.min_furniture_per_floor < 9 || b.min_doors < 2
    || b.min_cover_lines_per_floor < 3 || b.roof_access !== true || b.floors < 3 || b.floors > 5) {
    check(false, `building ${b.name} meets the approved per-building minimums`, JSON.stringify(b));
  }
}
check(layout.buildings.every((b) => b.min_cover_per_floor >= 12 && b.min_furniture_per_floor >= 9
  && b.min_doors >= 2 && b.min_cover_lines_per_floor >= 3 && b.roof_access === true
  && b.floors >= 3 && b.floors <= 5),
  'every building declares cover >= 12, furniture >= 9, doors >= 2, lines >= 3, roof access, 3-5 floors',
  `${layout.buildings.length} checked`);

// ------------------------------------------------------------------ 2. the V4 metric
console.log('');
console.log('2. the V4 usable low-cover metric (measured from the NBT, not from the generator)');
const v4 = measure(v, true);
const strict = measure(v, false);
const pct = (x) => `${(100 * x).toFixed(1)}%`;
console.log(`  v4        usableCells=${v4.usableCells} lines=${v4.lines} (X=${v4.linesX} Z=${v4.linesZ})`
  + ` blocksInLines=${v4.blocks} longest=${v4.longest} inLineShare=${pct(v4.inLineShare)}`);
console.log(`  v4strict  usableCells=${strict.usableCells} lines=${strict.lines}`
  + ` (X=${strict.linesX} Z=${strict.linesZ}) blocksInLines=${strict.blocks}`
  + ` longest=${strict.longest} inLineShare=${pct(strict.inLineShare)}   (white_concrete excluded -`
  + ' the block set of the reference tool ObstacleScan.java)');
check(v4.usableCells >= 1800, 'usable low-cover cells >= 1,800', `${v4.usableCells}`);
check(v4.lines >= 48, 'V4 usable cover lines >= 48', `${v4.lines}`);
check(v4.inLineShare >= 0.40, 'in-line share (cells in >= 3 runs / usable cells) >= 40%',
  pct(v4.inLineShare));
check(strict.lines >= 48, 'the same holds with the sandbag pair excluded (reference block set)',
  `${strict.lines} lines, ${pct(strict.inLineShare)} in line`);
check(v4.longest >= 3 && v4.longest <= 12, 'no line is absurdly long (a line is 3-12 cells)',
  `${v4.longest}`);

// ------------------------------------------------------------------ 3. the other combat counts
console.log('');
console.log('3. doors, ladders, interiors and the 18 separate buildings');
const countBy = (predicate) => {
  let n = 0;
  for (let y = 0; y < v.sy; y++) {
    for (let z = 0; z < v.sz; z++) {
      for (let x = 0; x < v.sx; x++) if (predicate(v.at(x, y, z), x, y, z)) n++;
    }
  }
  return n;
};
const plainDoors = countBy((id) => id.endsWith('_door') && !id.endsWith('trapdoor'));
const doorFamily = countBy((id) => id.endsWith('_door') || id.endsWith('_trapdoor') || id.endsWith('_fence_gate'));
const ladders = countBy((id) => id === 'ladder');
const ladderColumns = new Set();
for (let x = 0; x < v.sx; x++) {
  for (let z = 0; z < v.sz; z++) {
    for (let y = 0; y < v.sy; y++) if (v.at(x, y, z) === 'ladder') { ladderColumns.add(`${x},${z}`); break; }
  }
}
const INTERIOR = ['chest', 'barrel', 'white_bed', 'red_bed', 'torch', 'wall_torch', 'lantern',
  'creative_weapon_rack'];
const interiorHist = new Map();
let interior = 0;
for (let y = 0; y < v.sy; y++) {
  for (let z = 0; z < v.sz; z++) {
    for (let x = 0; x < v.sx; x++) {
      const id = v.at(x, y, z);
      if (INTERIOR.includes(id) || id.endsWith('_bed')) { interior++; interiorHist.set(id, (interiorHist.get(id) || 0) + 1); }
    }
  }
}
const interiorTop = [...interiorHist.entries()].sort((a, b) => b[1] - a[1])
  .map(([k, n]) => `${k} ${n}`).join(', ');
console.log(`  doors(plain _door)=${plainDoors} doors(+trapdoor/fence_gate)=${doorFamily}`
  + ` ladders=${ladders} ladderColumns=${ladderColumns.size}`);
console.log(`  interior(${INTERIOR.join('/')}) = ${interior}   [${interiorTop}]`);
check(plainDoors >= 72, 'door blocks >= 72 (18 buildings x 2 double doors x 2 halves)', `${plainDoors}`);
check(doorFamily >= 72, 'the whole door family (incl. trapdoors) is >= 72', `${doorFamily}`);
check(ladders >= 18 && ladderColumns.size >= 18,
  'at least one ladder column per building (18 columns, >= 18 ladder blocks)',
  `${ladders} blocks in ${ladderColumns.size} columns`);
check(interior >= 360, 'interior objects (chest/barrel/bed/torch/lantern/rack) >= 360',
  `${interior} [${interiorTop}]`);

// 18 separate buildings: flood fill every non-air cell above the street, skipping the street cover
// families, and count the components. Street cover is placed inside the street bands, so it can never
// join two buildings - and the layout cross-check below proves the 18 records are all really there.
const COVER_NOISE = new Set(['cobblestone_wall', 'white_concrete', 'white_carpet', 'black_concrete',
  'tinted_glass', 'cobblestone', 'cobblestone_slab', 'iron_bars', 'oak_slab', 'dark_oak_trapdoor',
  'oak_trapdoor', 'barrel']);
const seen = new Uint8Array(v.sx * v.sy * v.sz);
let components = 0;
const sizes = [];
const small = [];
const index = (x, y, z) => (y * v.sz + z) * v.sx + x;
for (let y = 1; y < v.sy; y++) {
  for (let z = 0; z < v.sz; z++) {
    for (let x = 0; x < v.sx; x++) {
      const id = v.at(x, y, z);
      if (id.endsWith('air') || COVER_NOISE.has(id) || seen[index(x, y, z)]) continue;
      components++;
      let size = 0;
      const first = [x, y, z];
      const stack = [[x, y, z]];
      seen[index(x, y, z)] = 1;
      while (stack.length) {
        const [cx, cy, cz] = stack.pop();
        size++;
        for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
          const nx = cx + dx, ny = cy + dy, nz = cz + dz;
          if (nx < 0 || ny < 1 || nz < 0 || nx >= v.sx || ny >= v.sy || nz >= v.sz) continue;
          if (seen[index(nx, ny, nz)]) continue;
          const nid = v.at(nx, ny, nz);
          if (nid.endsWith('air') || COVER_NOISE.has(nid)) continue;
          seen[index(nx, ny, nz)] = 1;
          stack.push([nx, ny, nz]);
        }
      }
      sizes.push(size);
      if (size < 500) {
        small.push(`${size} cells of ${v.at(first[0], first[1], first[2])} at ${first.join(',')}`);
      }
    }
  }
}
sizes.sort((a, b) => b - a);
const bigComponents = sizes.filter((s) => s >= 500).length;
console.log(`  building components(total)=${components} of size >= 500: ${bigComponents}`);
console.log(`  component sizes: ${sizes.slice(0, 20).join(',')}`);
if (small.length) console.log(`  small (street-cover sized) components: ${small.join(' | ')}`);
check(bigComponents === 18 && components === 18,
  'the volume is exactly 18 separate buildings (connected components, no orphan cluster)',
  `${bigComponents} big of ${components} total: ${sizes.slice(0, 20).join(',')}`);
const ROOFS = ['deepslate_tiles', 'polished_blackstone_bricks', 'brown_terracotta'];
let recordsOk = 0;
for (const b of layout.buildings) {
  const x0 = b.x, z0 = b.z;
  const topY = 1 + b.floors * b.floor_height;
  const slab = v.at(x0, 1, z0);
  const corner = v.at(x0, 2, z0);
  const roof = v.at(x0 + 2, topY, z0 + 2);
  if (slab.endsWith('andesite') || slab.endsWith('diorite') || slab.endsWith('granite')) recordsOk++;
  else console.log(`    FAIL ${b.name}: slab at (${x0},1,${z0}) is ${slab}`);
  if (corner !== 'polished_deepslate') console.log(`    FAIL ${b.name}: corner accent is ${corner}`);
  if (!ROOFS.includes(roof)) console.log(`    FAIL ${b.name}: roof at (${x0 + 2},${topY},${z0 + 2}) is ${roof}`);
}
check(recordsOk === 18, 'every one of the 18 layout records is a real building in the NBT'
  + ' (slab + accent corner + roof block present)', `${recordsOk} of 18`);

// ------------------------------------------------------------------ 4. the four presets did not move
console.log('');
console.log('4. the four shipped city presets are byte-identical (pure addition)');
// 2026-09-25: these four presets were DELIBERATELY regenerated. The generator gained two post-passes -
// connection-state baking (the user's "这个墙 以及玻璃没有互相连在一起": 1,742 of 1,742 connector blocks in
// city_small were written with "attached to nothing") and a doorway/connectivity pass (the user's
// "建筑内的墙偶尔会挡住门") - so every structure NBT the generator produces necessarily changes. The pins
// below exist to catch ACCIDENTAL drift; this is the one recorded intentional change, and
// tools/selftest_blockstates.js is the gate that now holds the new invariants (zero unconnected
// connectors, every wall with explicit sides, every doorway clear) to 0.
//
// 2026-09-26, DELIBERATE REGENERATION #2 (deliverables B, C and G). Everything the generator writes changed
// again, on purpose:
//   * block entity NBT: 1-2 `minecraft:spawner` per building (TROOP tier only) and 1-3 `minecraft:chest`
//     loot chests, each with its own `nbt` compound in the structure (see selftest_blockentities.js);
//   * interior variation: seeded per-floor room subdivision, 0..N extra 1x2 openings between rooms and
//     1..N floor holes per upper floor, with the vertical invariant (every floor reachable through a hole
//     with the ladder shaft treated as a wall) enforced and reported (see selftest_interior_variation.js);
//   * the cover-line builder now keeps doorway passages and floor-hole columns free, and the doorway repair
//     treats a carpet as a blocking block (AIR is the rule the blockstate gate measures).
// The four pins below are the new, deliberately re-measured values; the generator is deterministic (a second
// full regeneration is byte-identical - see the comment in tools/spike/selftest.ps1).
const PRESETS = {
  city_small: 'cd8957808e0047f9013a986005e9f654a0c4301c137eadd91be94083aa0167ac',
  city_a: '9774d3edee49eb28b89f4e96831975bebae53cca8c0c456e287fe65c439689eb',
  city_b: 'e73be3eb8d741d7082bf0871c292814b8ca080c8679e09289da296e3c607f78e',
  city_c: '0541fa0de7c3bd4969f48fbce6e95191335a3f7d603b10cbb8c0bc33c1187725',
};
for (const [name, want] of Object.entries(PRESETS)) {
  const file = path.join(RES, 'structures', name + '.nbt');
  const got = fs.existsSync(file)
    ? crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') : 'missing';
  check(got === want, `${name}.nbt sha256 is unchanged`, `sha256=${got} bytes=${fs.existsSync(file) ? fs.statSync(file).size : 0}`);
}

// ------------------------------------------------------------------ 5. the worldgen wiring
console.log('');
console.log('5. the worldgen wiring and the spawn-rate change');
const structure = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'structure', 'city_strongpoint.json'), 'utf8'));
const pool = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'template_pool', 'city_strongpoint', 'start.json'), 'utf8'));
const set = JSON.parse(fs.readFileSync(path.join(RES, 'worldgen', 'structure_set', 'city_strongpoint.json'), 'utf8'));
const tag = JSON.parse(fs.readFileSync(path.join(RES, 'tags', 'worldgen', 'structure', 'city.json'), 'utf8'));
const config = fs.readFileSync(path.join(JAVA, 'Config.java'), 'utf8');
const biome = JSON.parse(fs.readFileSync(path.join(RES, 'forge', 'biome_modifier', 'add_scavs.json'), 'utf8'));

check(structure.type === 'minecraft:jigsaw', 'the structure is a jigsaw');
check(structure.start_pool === 'tarkovscav:city_strongpoint/start'
  && pool.name === 'tarkovscav:city_strongpoint/start', 'the start pool is the strongpoint pool',
  structure.start_pool);
// CORRECTED twice, and the second correction is the one that matters. The original assertion
// ("max_distance_from_center >= 138", the footprint) could never be satisfied by a value that loads:
// vanilla validates `max_distance_from_center + terrain-adaptation padding <= 128`, and the shipped 224
// made the client refuse EVERY world with
//   "Failed to parse tarkovscav:worldgen/structure/city_strongpoint.json from pack armedmobs-0.1.0-all.jar"
// and the dedicated server with
//   "Structure size including terrain adaptation must not exceed 128".
// javap of the shipped server jar gives the exact rule: the switch map in JigsawStructure$1 sends NONE
// to padding 0 and BURY / BEARD_THIN / BEARD_BOX to padding 12, against a hard 128. A size-1 jigsaw
// performs no expansion steps at all, so this field never had to cover the 138-wide footprint - it only
// has to be legal. The assertion is the real rule, which the old one did not check at all.
const TERRAIN_PADDING = { none: 0, bury: 12, beard_thin: 12, beard_box: 12 };
const terrainPadding = TERRAIN_PADDING[structure.terrain_adaptation];
check(structure.size === 1 && Number.isInteger(structure.max_distance_from_center)
  && structure.max_distance_from_center >= 1
  && terrainPadding !== undefined && structure.max_distance_from_center + terrainPadding <= 128,
  'max_distance_from_center + the terrain-adaptation padding is inside vanilla\'s 128 limit'
  + ' (size-1 jigsaw: no expansion, so the footprint need not fit)',
  `size=${structure.size} max_distance_from_center=${structure.max_distance_from_center}`
  + ` terrain_adaptation=${structure.terrain_adaptation} padding=${terrainPadding}`
  + ` total=${structure.max_distance_from_center + terrainPadding} <= 128`);
check(structure.terrain_adaptation === 'none' || structure.terrain_adaptation === 'beard_thin',
  'terrain_adaptation matches the shipped presets', String(structure.terrain_adaptation));
// CORRECTED with the urban-wasteland change: the six city structures now point at
// tarkovscav:city_biomes, which is a SUPERSET of #minecraft:is_overworld. The overworld promise is
// therefore kept by asserting the containment, which is strictly more than the old string comparison
// checked (it now also proves the tag file exists and is what it claims to be).
const cityBiomeTag = JSON.parse(fs.readFileSync(
  path.join(RES, 'tags', 'worldgen', 'biome', 'city_biomes.json'), 'utf8'));
check(structure.biomes === '#tarkovscav:city_biomes'
  && cityBiomeTag.values.includes('#minecraft:is_overworld'),
  'it can appear in every overworld biome (via #tarkovscav:city_biomes, which contains it)',
  `${structure.biomes} = ${JSON.stringify(cityBiomeTag.values)}`);
check(pool.elements.length === 1 && pool.elements[0].element.location === 'tarkovscav:city_strongpoint'
  && pool.elements[0].element.element_type === 'minecraft:single_pool_element'
  && pool.elements[0].element.projection === 'rigid',
  'the pool places the strongpoint NBT as a rigid single element',
  pool.elements[0] && pool.elements[0].element.location);
check(set.structures.some((s) => s.structure === 'tarkovscav:city_strongpoint'),
  'the structure_set places it');
check(set.placement.spacing >= 138,
  'spacing >= the 138-wide footprint, so two strongpoints cannot overlap',
  `spacing ${set.placement.spacing} separation ${set.placement.separation}`);
check(set.placement.spacing >= set.placement.separation * 2, 'spacing leaves room for whole strongpoints',
  `spacing ${set.placement.spacing} separation ${set.placement.separation}`);
check(tag.values.includes('tarkovscav:city_strongpoint'),
  'the id is in the tarkovscav:city tag (the spawn gate counts it as a city)');
for (const old of ['tarkovscav:city_small', 'tarkovscav:city_a', 'tarkovscav:city_b',
  'tarkovscav:city_c', 'tarkovscav:city_district']) {
  check(tag.values.includes(old), `  the older structure ${old} is still in the tag`);
}
check(/defaultCityStructureIds[\s\S]{0,400}?tarkovscav:city_strongpoint/.test(config),
  'Config.defaultCityStructureIds() names the strongpoint');
const weights = biome.spawners.map((s) => s.weight);
const total = weights.reduce((a, b) => a + b, 0);
console.log(`  biome spawner weights=${weights.join(',')} total=${total}`);
check(weights.join(',') === '5,2,2,1,1,1,1,1,1',
  'the city spawn weights are the approved 5,2,2,1,1,1,1,1,1', weights.join(','));
check(total === 15, 'their total is 15 (it was 33)', `${total}`);
const padding = /defineInRange\("cityRegionPadding",\s*(\d+)/.exec(config);
console.log(`  spawn.cityRegionPadding default = ${padding ? padding[1] : '?'}`);
check(padding && Number(padding[1]) === 4, 'spawn.cityRegionPadding ships as 4 (it was 24)',
  padding ? padding[1] : 'missing');

// ------------------------------------------------------------------ the map picture
console.log('');
const map = path.join(ROOT, 'docs', 'maps', 'strongpoint.png');
if (fs.existsSync(map)) {
  const png = fs.readFileSync(map);
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  console.log(`  docs/maps/strongpoint.png ${width}x${height} ${png.length} bytes`);
  check(width > 400 && height > 400, 'the rendered map exists and is not a thumbnail',
    `${width}x${height}`);
} else {
  check(false, 'docs/maps/strongpoint.png exists (CityMap structure --nbt ...)', 'missing');
}

console.log('');
if (failures > 0) {
  console.log(`${failures} strongpoint check(s) FAILED`);
  process.exit(1);
}
console.log('the 18-building strongpoint holds: cover lines, doors, ladders, interiors, wiring and the'
  + ' unchanged presets');
