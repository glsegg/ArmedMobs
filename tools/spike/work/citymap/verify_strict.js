// STRICT, self-contained re-verification of the regenerated structures. Deliberately does NOT reuse the
// generator's `NOT_FULL_SUPPORT` list (that would make the checker circular): the "full support face"
// decision here is derived independently from the block name, the way vanilla's isFaceSturdy(FULL) behaves
// for the palette this project actually uses.
//
//   node tools/spike/work/citymap/verify_strict.js <structure.nbt> [...]
//
// Three independent invariants:
//   A) every `*_pane` / `iron_bars` / `*_fence` / `*_fence_gate` / `*_wall` that has something it should
//      attach to must carry at least one connection flag;
//   B) every `*_wall` must carry explicit north/south/east/west + up;
//   C) every `*_door` must have 2 blocks of headroom on BOTH sides along its facing axis.
'use strict';
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

function parseNbt(buf) {
  let at = 0;
  const u8 = () => buf[at++];
  const i16 = () => { const v = buf.readInt16BE(at); at += 2; return v; };
  const i32 = () => { const v = buf.readInt32BE(at); at += 4; return v; };
  const i64 = () => { const v = buf.readBigInt64BE(at); at += 8; return v; };
  const str = () => { const n = buf.readUInt16BE(at); at += 2; const s = buf.toString('utf8', at, at + n); at += n; return s; };
  const payload = (t) => {
    switch (t) {
      case 1: return buf.readInt8(at++);
      case 2: return i16();
      case 3: return i32();
      case 4: return i64();
      case 5: { const v = buf.readFloatBE(at); at += 4; return v; }
      case 6: { const v = buf.readDoubleBE(at); at += 8; return v; }
      case 7: { const n = i32(); const a = buf.subarray(at, at + n); at += n; return a; }
      case 8: return str();
      case 9: { const item = u8(); const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(payload(item)); return out; }
      case 10: { const out = {}; for (;;) { const tt = u8(); if (tt === 0) break; const nm = str(); out[nm] = payload(tt); } return out; }
      case 11: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i32()); return out; }
      case 12: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i64()); return out; }
      default: throw new Error('tag ' + t);
    }
  };
  u8(); str();
  return payload(10);
}

// My own view of "not a full face": anything whose model does not fill the whole face. Deliberately
// explicit rather than read from the generator.
const NOT_FULL = new Set([
  'minecraft:glass_pane', 'minecraft:light_gray_stained_glass_pane', 'minecraft:gray_stained_glass_pane',
  'minecraft:iron_bars', 'minecraft:chain', 'minecraft:lantern', 'minecraft:soul_lantern',
  'minecraft:end_rod', 'minecraft:ladder', 'minecraft:scaffolding', 'minecraft:cobweb',
  'minecraft:oak_sign', 'minecraft:wall_torch', 'minecraft:torch', 'minecraft:red_carpet',
  'minecraft:gray_carpet', 'minecraft:white_carpet', 'minecraft:potted_fern',
  'minecraft:potted_oak_sapling', 'minecraft:fire', 'minecraft:barrel',
  'minecraft:white_bed', 'minecraft:bell', 'minecraft:cauldron', 'minecraft:composter',
  // slabs / stairs / trapdoors do not fill a side face either (my first version missed these, which
  // produced two false "unconnected wall" hits in city_c).
  'minecraft:cobblestone_slab', 'minecraft:oak_slab', 'minecraft:oak_stairs',
  'minecraft:stone_brick_stairs', 'minecraft:oak_trapdoor', 'minecraft:dark_oak_trapdoor',
]);
const isLowShape = (n) => /_(pane|bars|fence|fence_gate|wall|slab|stairs|trapdoor|door|carpet|button|pressure_plate|sign|banner|torch)$/.test(n);
const familyOf = (n) => {
  if (!n) return null;
  if (n.endsWith('_pane') || n === 'minecraft:iron_bars') return 'pane';
  if (n.endsWith('_fence') || n.endsWith('_fence_gate')) return 'fence';
  if (n.endsWith('_wall')) return 'wall';
  return null;
};
const isDoor = (n) => !!n && n.endsWith('_door') && !n.endsWith('trapdoor');
const passable = (p) => !p || p.name === 'minecraft:air' || p.name === 'minecraft:cave_air'
  || /(^|:)(water|lava)$/.test(p.name) || isLowShape(p.name) === false && false;

function main(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const palette = root.palette.map((p) => ({ name: p.Name, props: p.Properties || {} }));
  const cells = new Map();
  for (const b of root.blocks) cells.set(b.pos.join(','), b.state);
  const at = (x, y, z) => {
    const s = cells.get(`${x},${y},${z}`);
    return s === undefined ? null : palette[s];
  };
  const attachable = (selfName, other) => {
    if (!other) return false;
    // Explicit air is written into these NBTs: it is not a support. (This was a bug in my first version.)
    if (/^minecraft:(air|cave_air|void_air|water|lava|structure_void)$/.test(other.name)) return false;
    const f = familyOf(selfName);
    const of = familyOf(other.name);
    if (of && of === f) return true;                       // same family always attaches
    if (of) return false;                                  // a different connector family does not
    if (isDoor(other.name)) return false;                   // doors are not supports
    return !NOT_FULL.has(other.name);                       // full face (chest/cauldron/barrel per vanilla)
  };

  let connectors = 0, brokenA = 0, wallsMissing = 0, doors = 0, brokenC = 0, lintels = 0;
  const examplesA = [], examplesB = [], examplesC = [];
  for (const [key, state] of cells) {
    const p = palette[state];
    const [x, y, z] = key.split(',').map(Number);
    const dirs = [['north', 0, 0, -1], ['south', 0, 0, 1], ['west', -1, 0, 0], ['east', 1, 0, 0]];
    if (/_pane$|_bars$|_fence$|_fence_gate$|_wall$/.test(p.name)) {
      connectors += 1;
      let flag = false, should = false, why = '';
      for (const [dir, dx, dy, dz] of dirs) {
        const v = p.props[dir];
        if (v !== undefined && v !== 'false' && v !== 'none') flag = true;
        const nb = at(x + dx, y + dy, z + dz);
        if (attachable(p.name, nb)) { should = true; if (!why) why = `${dir}->${nb.name}`; }
      }
      if (should && !flag) { brokenA += 1; if (examplesA.length < 3) examplesA.push(`${p.name} at ${key} ${JSON.stringify(p.props)}  triggered by: ${why}`); }
      if (p.name.endsWith('_wall')) {
        const missing = dirs.some(([dir]) => p.props[dir] === undefined) || p.props.up === undefined;
        if (missing) { wallsMissing += 1; if (examplesB.length < 3) examplesB.push(`${p.name} at ${key} ${JSON.stringify(p.props)}`); }
      }
    }
    if (isDoor(p.name)) {
      // Only the LOWER half defines the passage: iterating both halves and testing y+1 against the upper
      // half lands on the LINTEL cell above the doorway, which is supposed to be solid. (Bug in my first
      // version - it produced the false "18 blocked doors" report.)
      if (p.props.half !== 'lower') continue;
      doors += 1;
      const facing = p.props.facing;
      const axis = (facing === 'north' || facing === 'south') ? [0, 1] : (facing === 'east' || facing === 'west') ? [1, 0] : null;
      if (!axis) continue;
      for (const sign of [1, -1]) {
        for (const dy of [0, 1]) {
          const q = at(x + axis[0] * sign, y + dy, z + axis[1] * sign);
          const ok = !q || q.name === 'minecraft:air' || q.name === 'minecraft:cave_air';
          if (!ok) {
            brokenC += 1;
            if (examplesC.length < 4) examplesC.push(`${p.name} at ${key} facing=${facing}: side ${sign > 0 ? '+' : '-'} y+${dy} = ${q.name}`);
            break;
          }
        }
      }
      // the cell above the door's top, one step to the side: a lintel, reported but not a violation
      for (const sign of [1, -1]) {
        const q = at(x + axis[0] * sign, y + 2, z + axis[1] * sign);
        if (q && q.name !== 'minecraft:air' && q.name !== 'minecraft:cave_air') lintels += 1;
      }
    }
  }
  console.log(`${path.basename(file)}`);
  console.log(`  A connectors=${connectors} unconnected-with-neighbour=${brokenA}`);
  examplesA.forEach((e) => console.log('      ' + e));
  console.log(`  B walls missing explicit sides=${wallsMissing}`);
  examplesB.forEach((e) => console.log('      ' + e));
  console.log(`  C doors=${doors} with a blocked side=${brokenC}`);
  examplesC.forEach((e) => console.log('      ' + e));
}

for (const f of process.argv.slice(2)) main(f);

