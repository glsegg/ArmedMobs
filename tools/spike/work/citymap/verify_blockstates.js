// Are the generated structures' CONNECTION STATES baked correctly?
//
// A structure NBT stores explicit block states, and the game does NOT re-run `updateShape` when it places
// them. So a `glass_pane` written with the default properties renders as a lone post in the middle of a
// window, and a `*_wall` written without its north/south/east/west values renders as disconnected posts -
// which is exactly what the user photographed.
//
// This counts, per structure NBT, every pane / iron bars / wall / fence block whose connection properties
// say "connected to nothing" while it actually has a neighbour it should connect to.
//
//   node tools/spike/work/citymap/verify_blockstates.js <structure.nbt> [more ...]
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

// The generator's own "this block is not a full cube" list, kept in sync with
// CityStructureGen.NOT_FULL_SUPPORT (it is the project's definition of a full face, i.e. what a connector
// attaches to). A chest / crafting table / cauldron IS a full support face in vanilla even though its
// outline is not, so those three are NOT in this list.
const NOT_FULL = new Set(['minecraft:glass_pane', 'minecraft:light_gray_stained_glass_pane',
  'minecraft:gray_stained_glass_pane', 'minecraft:iron_bars', 'minecraft:chain', 'minecraft:lantern',
  'minecraft:end_rod', 'minecraft:ladder', 'minecraft:cobblestone_wall', 'minecraft:cobblestone_slab',
  'minecraft:oak_slab', 'minecraft:oak_trapdoor', 'minecraft:dark_oak_trapdoor', 'minecraft:oak_fence',
  'minecraft:oak_sign', 'minecraft:oak_stairs', 'minecraft:stone_brick_stairs',
  'minecraft:stone_brick_wall', 'minecraft:red_carpet', 'minecraft:gray_carpet',
  'minecraft:white_carpet', 'minecraft:potted_fern', 'minecraft:potted_oak_sapling']);
const CONNECTOR = /(_pane$|_bars$|_wall$|_fence$)/;

/** The connector family a block belongs to, or null: only the SAME family attaches to each other. */
function familyOf(name) {
  if (!name) return null;
  if (name.endsWith('_pane') || name === 'iron_bars') return 'pane';
  if (name.endsWith('_fence') || name.endsWith('_fence_gate')) return 'fence';
  if (name.endsWith('_wall')) return 'wall';
  return null;
}

function isDoor(name) {
  return !!name && name.endsWith('_door') && !name.endsWith('trapdoor');
}

/** air attaches to nothing - the original SOLID regex excluded it implicitly, the family rule must say so. */
function isAir(name) {
  return !name || name === 'minecraft:air' || name === 'air' || name.endsWith('_air');
}

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
  /** Vanilla's attach test: the same connector family, or a full support face. Air attaches to nothing. */
  const connectable = (self, other) => {
    if (!other || isAir(other)) return false;
    const f = familyOf(self);
    if (familyOf(other) !== null && familyOf(other) === f) return true;
    return !NOT_FULL.has(other) && !isDoor(other);
  };

  let total = 0;
  const broken = [];
  const byKind = new Map();
  for (const [key, state] of cells) {
    const p = palette[state];
    if (!CONNECTOR.test(p.name)) continue;
    total += 1;
    const [x, y, z] = key.split(',').map(Number);
    const dirs = [['north', 0, 0, -1], ['south', 0, 0, 1], ['west', -1, 0, 0], ['east', 1, 0, 0]];
    let connectedFlag = false;
    let shouldConnect = false;
    for (const [dir, dx, dy, dz] of dirs) {
      const v = p.props[dir];
      if (v !== undefined && v !== 'false' && v !== 'none') connectedFlag = true;
      const neighbour = at(x + dx, y + dy, z + dz);
      if (neighbour && connectable(p.name, neighbour.name)) shouldConnect = true;
    }
    if (shouldConnect && !connectedFlag) {
      broken.push(`${p.name} at ${key} props=${JSON.stringify(p.props)}`);
      const short = p.name.replace('minecraft:', '');
      byKind.set(short, (byKind.get(short) || 0) + 1);
    }
  }
  const kinds = [...byKind.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${v}`).join(', ');
  console.log(`${path.basename(file)}  connector blocks=${total}  unconnected-but-has-neighbour=${broken.length}  ${kinds ? '(' + kinds + ')' : ''}`);
  broken.slice(0, 4).forEach((b) => console.log('    e.g. ' + b));
}

for (const f of process.argv.slice(2)) main(f);
