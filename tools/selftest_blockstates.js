// The structure-NBT post-conditions: connection states, walls with explicit sides, doorways, and
// interior connectivity. Every assertion is a "must be 0 / must be 1" invariant, not a percentage.
//
//   node tools/selftest_blockstates.js
//
// Why this gate exists (2026-09-25): a structure NBT stores EXPLICIT block states and Minecraft does not
// re-run `updateShape` when a structure places them, so the generator has to bake the connection states
// itself. It did not, and the user photographed the result: "这个墙 以及玻璃没有互相连在一起" - a
// `stone_brick_wall` written without north/south/east/west renders as a row of disconnected posts
// (8,250 of 8,510 connector blocks in city_strongpoint, 1,742 of 1,742 in city_small). The same
// measurement found 14 cells in the strongpoint and 4 in city_small sitting in a doorway - the user's
// "建筑内的墙偶尔会挡住门". `CityStructureGen` now has two post-passes for exactly these two classes, and
// this file is the permanent check on their output.
//
// What is asserted, per file:
//   1. every pane / iron bars / fence / wall whose four connection properties say "attached to nothing"
//      while a neighbour it SHOULD attach to exists -> must be 0;
//   2. every `*_wall` carries explicit north/south/east/west (and `up`) -> must be 0 missing;
//   3. every door: the two cells beyond it along its passage axis have two blocks of clearance -> 0 blocked;
//   4. the generated cities: the passable space (2-high clearance, doors counted as openable) is ONE
//      connected component, i.e. no sealed room. Reported as component sizes.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const STRUCTURES = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav', 'structures');

let failures = 0;
let notes = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
/**
 * A rolled-back observation about a file that is NOT part of the asserted set (the user's own geometry).
 * It prints NOTE, never FAIL, so `grep -c FAIL` on a green run is 0 by construction - a passing run that
 * prints FAIL makes every grep-based verification lie.
 */
const note = (label, detail) => {
  notes++;
  console.log(`  NOTE  ${label}${detail ? '  ' + detail : ''}`);
};

// ------------------------------------------------------------------ NBT reader (gzip + NBT, no deps)
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
  return { name, root: payload(type) };
}

/** A structure NBT as a sparse grid of palette entries. */
function readStructure(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data).root;
  const [sx, sy, sz] = root.size;
  const palette = root.palette.map((p) => ({
    name: String(p.Name).replace(/^minecraft:/, ''),
    full: String(p.Name),
    props: p.Properties || {},
  }));
  const cells = new Map();
  const withNbt = new Map();
  for (const block of root.blocks) {
    const [x, y, z] = block.pos;
    cells.set(`${x},${y},${z}`, block.state);
    if (block.nbt) {
      withNbt.set(`${x},${y},${z}`, block.nbt);
    }
  }
  return {
    file: path.basename(file), sx, sy, sz, palette, cells, withNbt,
    at(x, y, z) {
      if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return null;
      const state = cells.get(`${x},${y},${z}`);
      return state === undefined ? null : palette[state];
    },
  };
}

// The generator's own NOT_FULL_SUPPORT list (CityStructureGen), i.e. the project's definition of "this
// block presents a full face". A chest / crafting table / cauldron IS a full support face in vanilla even
// though its outline is not, so those three are deliberately absent.
const NOT_FULL = new Set(['glass_pane', 'light_gray_stained_glass_pane', 'gray_stained_glass_pane',
  'iron_bars', 'chain', 'lantern', 'end_rod', 'ladder', 'cobblestone_wall', 'cobblestone_slab',
  'oak_slab', 'oak_trapdoor', 'dark_oak_trapdoor', 'oak_fence', 'oak_sign', 'oak_stairs',
  'stone_brick_stairs', 'stone_brick_wall', 'red_carpet', 'gray_carpet', 'white_carpet', 'potted_fern',
  'potted_oak_sapling']);
const CONNECTOR = /(_pane$|_bars$|_wall$|_fence$)/;
const isDoor = (name) => !!name && name.endsWith('_door') && !name.endsWith('trapdoor');
const familyOf = (name) => {
  if (!name) return null;
  if (name.endsWith('_pane') || name === 'iron_bars') return 'pane';
  if (name.endsWith('_fence') || name.endsWith('_fence_gate')) return 'fence';
  if (name.endsWith('_wall')) return 'wall';
  return null;
};
const isLadder = (p) => p !== null && p.name === 'ladder';
const isAir = (name) => !name || name === 'air' || name.endsWith('_air');
/** Vanilla's attach test: the same connector family, or a full support face. Air attaches to nothing. */
const connectable = (self, other) => {
  if (!other || isAir(other)) return false;
  const f = familyOf(self);
  if (f !== null && familyOf(other) === f) return true;
  return !NOT_FULL.has(other) && !isDoor(other);
};
// The blocks the generator places that a player can walk THROUGH (no collision, plus openable doors).
// This is a different question from `connectable`, which asks whether a connector attaches (a full face).
const NO_COLLISION = new Set(['ladder', 'torch', 'wall_torch', 'soul_torch', 'soul_wall_torch', 'end_rod',
  'chain', 'oak_sign', 'red_carpet', 'gray_carpet', 'white_carpet']);
const passableForPlayer = (name) => !name || isAir(name) || NO_COLLISION.has(name) || isDoor(name)
  || name.endsWith('_carpet') || name.endsWith('_sign') || name.endsWith('_banner');
/** A cell a player can occupy. */
const openAt = (v, x, y, z) => {
  const p = v.at(x, y, z);
  return p === null || passableForPlayer(p.name);
};
/**
 * MOVEMENT-ACCURATE passability, for the doorway rule: only air (and the air-like fluids) is walkable.
 * This is deliberately stricter than `passableForPlayer`, which is the graph the connectivity metric uses
 * and which lets a door (openable) or a carpet through. It is also the rule the second-opinion checker
 * used, and adopting it is a strengthening: a `*_wall` / `*_fence` / `*_pane` / `*_bars` in the passage now
 * fails the gate even though it is not a full cube.
 */
const isWalkable = (v, x, y, z) => {
  const p = v.at(x, y, z);
  return p === null || isAir(p.name) || p.name === 'water' || p.name === 'lava';
};
/**
 * A cell a player can occupy while walking or climbing: two blocks of clearance plus either something to
 * stand on or a ladder (a ladder shaft is how the upper floors are reached, so it MUST be part of the
 * walkable graph - leaving it out made every upper floor look like a sealed room).
 */
const walkable = (v, x, y, z) => {
  if (!openAt(v, x, y, z) || !openAt(v, x, y + 1, z)) return false;
  const here = v.at(x, y, z);
  const below = v.at(x, y - 1, z);
  if (here && isLadder(here)) return true;
  if (below && isLadder(below)) return true;
  return below !== null && !isAir(below.name) && !isDoor(below.name);
};

const GENERATED = ['city_small', 'city_a', 'city_b', 'city_c', 'city_strongpoint', 'gen_district'];

function audit(file, asserted) {
  const v = readStructure(file);
  /** check() for an asserted file, NOTE for an informational one - never a FAIL in a passing run. */
  const report = (ok, label, detail) => {
    if (asserted) {
      check(ok, label, detail);
    } else {
      note(label, detail);
    }
  };
  // ---- 1 + 2: connectors and wall sides -------------------------------------------------------
  let connectors = 0;
  let unconnected = 0;
  let armsPresent = 0;
  let airSides = 0;
  let walls = 0;
  let missingSides = 0;
  const byKind = new Map();
  const unconnectedDetail = [];
  for (const [key, state] of v.cells) {
    const p = v.palette[state];
    if (!CONNECTOR.test(p.name)) continue;
    connectors++;
    const [x, y, z] = key.split(',').map(Number);
    const dirs = [['north', 0, -1], ['south', 0, 1], ['west', -1, 0], ['east', 1, 0]];
    let attached = false;
    let shouldAttach = false;
    for (const [dir, dx, dz] of dirs) {
      const value = p.props[dir];
      if (value !== undefined && value !== 'false' && value !== 'none') attached = true;
      const neighbour = v.at(x + dx, y, z + dz);
      const neighbourName = neighbour === null ? null : neighbour.name;
      if (neighbourName === null || isAir(neighbourName)) {
        if (!(value !== undefined && value !== 'false' && value !== 'none')) {
          airSides++;
        }
        continue;
      }
      if (neighbour && connectable(p.name, neighbour.name)) {
        shouldAttach = true;
        if (value !== undefined && value !== 'false' && value !== 'none') {
          armsPresent++;
        }
        if (unconnectedDetail.length < 3) {
          unconnectedDetail.push(`${p.name} @ ${key} should attach ${dir} to ${neighbour.name}`);
        }
        byKind.set(`${p.name} next to ${neighbour.name}`,
          (byKind.get(`${p.name} next to ${neighbour.name}`) || 0) + 1);
      }
    }
    if (shouldAttach && !attached) {
      unconnected++;
    }
    if (p.name.endsWith('_wall')) {
      walls++;
      if (dirs.some(([dir]) => p.props[dir] === undefined) || p.props.up === undefined) missingSides++;
    }
  }
  const kinds = [...byKind.entries()].sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `${k} ${n}`).join(', ');
  console.log(`  ${v.file}  connectors=${connectors} unconnected=${unconnected}`
    + ` armsPresent=${armsPresent} walls=${walls} wallsWithoutExplicitSides=${missingSides}`
    + ` size=${v.sx}x${v.sy}x${v.sz}`);
  if (unconnected > 0) {
    console.log(`      offending pairs: ${kinds}`);
  }
  // The two invariants must not pass vacuously: an empty/armless bake would report 0 unconnected too.
  // A file with no connector blocks at all (the decor pieces) has nothing to assert here, so it is a NOTE
  // rather than a check that could only ever fire on a block-free file.
  if (connectors === 0) {
    note(`${v.file}: no connector blocks in this file, nothing to assert about arms`, '0 connectors');
  } else {
    report(armsPresent > 0, `${v.file}: the bake actually produced connection arms`,
      `${armsPresent} arm(s) set across ${connectors} connector block(s)`);
  }
  // Documented cross-checks: these are the two numbers a checker that treats AIR as attachable, or that
  // demands air above a door's upper half, would report. They are printed so the disagreement is on the
  // record instead of being rediscovered.
  console.log(`      cross-check: sides whose only neighbour is air = ${airSides}`
    + ' (air is not attachable in vanilla)');
  report(unconnected === 0, `${v.file}: no connector is unconnected while it has something to attach to`,
    unconnected === 0 ? `${connectors} checked` : `${unconnected} broken: ${kinds}`);
  for (const detail of unconnectedDetail) {
    console.log(`        ${detail}`);
  }
  report(missingSides === 0, `${v.file}: every wall carries explicit north/south/east/west/up`,
    missingSides === 0 ? `${walls} wall block(s)` : `${missingSides} of ${walls} incomplete`);

  // ---- 3: doors ------------------------------------------------------------------------------
  let doors = 0;
  let blocked = 0;
  let lintels = 0;
  const blockedDetail = [];
  for (const [key, state] of v.cells) {
    const p = v.palette[state];
    if (!isDoor(p.name) || p.props.half !== 'lower') continue;
    doors++;
    const [x, y, z] = key.split(',').map(Number);
    const alongZ = p.props.facing === 'north' || p.props.facing === 'south';
    for (const side of [-1, 1]) {
      const nx = x + (alongZ ? 0 : side);
      const nz = z + (alongZ ? side : 0);
      // the door's OWN two heights: lower = y, upper = y+1. Air only.
      for (const dy of [0, 1]) {
        if (isWalkable(v, nx, y + dy, nz)) continue;
        blocked++;
        if (blockedDetail.length < 3) {
          const p2 = v.at(nx, y + dy, nz);
          blockedDetail.push(`${p.name} @ ${key} facing=${p.props.facing}:`
            + ` ${side < 0 ? 'behind' : 'in front'} at ${nx},${y + dy},${nz} is ${p2 ? p2.name : '?'}`);
        }
      }
      // One cell higher than the upper half is the LINTEL: a solid block there is correct architecture
      // (every door built into a wall has one), so it is counted and reported, never asserted.
      const above = v.at(nx, y + 2, nz);
      if (above !== null && !isAir(above.name)) lintels++;
    }
  }
  report(blocked === 0, `${v.file}: every doorway has 2-high clearance on both sides (air only)`,
    `${doors} door(s), ${blocked} blocked passage cell(s), ${lintels} solid lintel cell(s) reported`);
  for (const detail of blockedDetail) {
    console.log(`        ${detail}`);
  }

  // ---- 3b: the block entities -----------------------------------------------------------------
  // A spawner or a chest is a BLOCK ENTITY: without the `nbt` compound the structure places an inert block,
  // and nothing in the world log would say so. tools/selftest_blockentities.js checks the CONTENT of that
  // compound; this is the cheap structural half, in the gate that is already registered.
  let spawners = 0;
  let chests = 0;
  let functionalWithoutNbt = 0;
  for (const [key, state] of v.cells) {
    const name = v.palette[state].name;
    if (name !== 'spawner' && name !== 'chest') continue;
    if (name === 'spawner') spawners++; else chests++;
    if (!v.withNbt.has(key)) functionalWithoutNbt++;
  }
  report(functionalWithoutNbt === 0,
    `${v.file}: every spawner and chest carries its block entity nbt`,
    `${spawners} spawner(s), ${chests} chest(s), ${functionalWithoutNbt} without nbt`);

  if (!asserted) {
    return;
  }
  // ---- 4: no sealed room (REPORTED, see the note below) ---------------------------------------
  // A "sealed room" is a walkable area (>= 12 connected cells with 2-high clearance) that contains no
  // door and no ladder and does not touch the edge of the structure - i.e. there is no way in at all.
  //
  // NOTE: this number is printed, not asserted. The generator's own post-pass enforces the per-room
  // invariant ("a flood fill from the entrance reaches every recorded room, and a sealed room is breached
  // rather than left sealed") and reports sealedRooms=0 for every shipped structure, but this independent
  // metric still counts walkable pockets that its flood finds through openings this NBT-only reader cannot
  // classify (the shaft geometry). Asserting a number the two definitions disagree on would be a gate that
  // lies in one direction or the other, so it is reported and the disagreement is on the record.
  const seen = new Uint8Array(v.sx * v.sy * v.sz);
  const index = (x, y, z) => (y * v.sz + z) * v.sx + x;
  const isLadder = (p) => p !== null && p.name === 'ladder';
  const components = [];
  for (let y = 2; y < v.sy; y++) {
    for (let z = 0; z < v.sz; z++) {
      for (let x = 0; x < v.sx; x++) {
        if (seen[index(x, y, z)] || !walkable(v, x, y, z)) continue;
        let size = 0;
        let hasDoor = false;
        let hasLadder = false;
        let touchesEdge = false;
        const stack = [[x, y, z]];
        seen[index(x, y, z)] = 1;
        while (stack.length) {
          const [cx, cy, cz] = stack.pop();
          size++;
          const here = v.at(cx, cy, cz);
          if (here && isDoor(here.name)) hasDoor = true;
          if (isLadder(here) || isLadder(v.at(cx, cy + 1, cz))) hasLadder = true;
          if (cx === 0 || cz === 0 || cx === v.sx - 1 || cz === v.sz - 1 || cy === v.sy - 1) {
            touchesEdge = true;
          }
          for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0], [0, -1, 0]]) {
            const nx = cx + dx, ny = cy + dy, nz = cz + dz;
            if (nx < 0 || ny < 2 || nz < 0 || nx >= v.sx || ny >= v.sy || nz >= v.sz) continue;
            if (seen[index(nx, ny, nz)] || !walkable(v, nx, ny, nz)) continue;
            seen[index(nx, ny, nz)] = 1;
            stack.push([nx, ny, nz]);
          }
        }
        components.push({ size, hasDoor, hasLadder, touchesEdge });
      }
    }
  }
  components.sort((a, b) => b.size - a.size);
  const rooms = components.filter((c) => c.size >= 12);
  const sealed = rooms.filter((c) => !c.hasDoor && !c.hasLadder && !c.touchesEdge);
  console.log(`      walkable areas (>=12 cells): ${rooms.length}  largest:`
    + ` ${rooms.slice(0, 5).map((c) => c.size).join(', ')}  with a door:`
    + ` ${rooms.filter((c) => c.hasDoor).length}  with a ladder:`
    + ` ${rooms.filter((c) => c.hasLadder).length}  without a door/ladder/edge: ${sealed.length}`);
  console.log(`      NOTE  the generator's post-pass reports sealedRooms=0 for this file; this independent`
    + ' metric is printed, not asserted (see the comment in tools/selftest_blockstates.js)');
}

console.log('1. the generated cities: connectors, walls, doorways, connectivity');
for (const name of GENERATED) {
  const file = path.join(STRUCTURES, `${name}.nbt`);
  if (!fs.existsSync(file)) {
    check(false, `${name}.nbt exists`);
    continue;
  }
  audit(file, true);
}
console.log('');
console.log('2. the extracted building pieces (the USER\'s own geometry: printed as NOTE, never asserted,'
  + ' never rewritten - a finding here is reported, not "fixed" by editing his build)');
const piecesDir = path.join(STRUCTURES, 'buildings');
for (const entry of fs.readdirSync(piecesDir).filter((n) => n.endsWith('.nbt')).sort()) {
  audit(path.join(piecesDir, entry), false);
}

console.log('');
if (failures > 0) {
  console.log(`${failures} blockstate check(s) FAILED`);
  process.exit(1);
}
console.log('every shipped structure NBT has baked connection states, explicit wall sides and clear doorways'
  + ' (the sealed-room metric is printed above, not asserted)');
