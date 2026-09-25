// Dump a small 3D window of a structure NBT so a human can judge a doorway by eye.
//
//   node tools/spike/work/citymap/dump_window.js <structure.nbt> <x> <y> <z> [radius]
//
// Prints horizontal slices from y-1 to y+3, abbreviating block names, with the centre cell marked `>>`.
'use strict';
const fs = require('fs');
const zlib = require('zlib');

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

const ABBR = [
  ['minecraft:stone_brick_wall', 'sbw'],
  ['minecraft:cobblestone_wall', 'cw'],
  ['minecraft:dark_oak_door', 'DOOR'],
  ['minecraft:spruce_door', 'DOOR'],
  ['minecraft:oak_door', 'DOOR'],
  ['minecraft:iron_door', 'DOOR'],
  ['minecraft:dark_oak_trapdoor', 'tdoor'],
  ['minecraft:oak_trapdoor', 'tdoor'],
  ['minecraft:light_gray_stained_glass_pane', 'lpan'],
  ['minecraft:gray_stained_glass_pane', 'gpan'],
  ['minecraft:glass_pane', 'pane'],
  ['minecraft:iron_bars', 'bars'],
  ['minecraft:oak_fence', 'fence'],
  ['minecraft:air', '.'],
  ['minecraft:light_gray_concrete', 'LGC'],
  ['minecraft:gray_concrete', 'GC'],
  ['minecraft:black_concrete', 'BLC'],
  ['minecraft:brown_concrete', 'BRC'],
  ['minecraft:white_concrete', 'WC'],
  ['minecraft:terracotta', 'TER'],
  ['minecraft:white_terracotta', 'wTER'],
  ['minecraft:orange_terracotta', 'oTER'],
  ['minecraft:brown_terracotta', 'bTER'],
  ['minecraft:light_gray_terracotta', 'lTER'],
  ['minecraft:yellow_terracotta', 'yTER'],
  ['minecraft:red_terracotta', 'rTER'],
  ['minecraft:deepslate_tiles', 'dtile'],
  ['minecraft:polished_andesite', 'pAND'],
  ['minecraft:smooth_stone', 'smooth'],
  ['minecraft:oak_slab', 'slab'],
  ['minecraft:stone_brick_stairs', 'stair'],
  ['minecraft:oak_sign', 'sign'],
  ['minecraft:barrel', 'brl'],
  ['minecraft:chest', 'chest'],
  ['minecraft:white_bed', 'bed'],
  ['minecraft:torch', 'torch'],
  ['minecraft:lantern', 'lant'],
];
const abbr = (n) => {
  for (const [full, a] of ABBR) if (n === full) return a;
  return n.replace('minecraft:', '').slice(0, 5);
};

const file = process.argv[2];
const cx = Number(process.argv[3]), cy = Number(process.argv[4]), cz = Number(process.argv[5]);
const r = Number(process.argv[6] || 4);
const raw = fs.readFileSync(file);
const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
const root = parseNbt(data);
const palette = root.palette.map((p) => ({ name: p.Name, props: p.Properties || {} }));
const cells = new Map();
for (const b of root.blocks) cells.set(b.pos.join(','), b.state);
const at = (x, y, z) => {
  const s = cells.get(`${x},${y},${z}`);
  return s === undefined ? { name: 'minecraft:air', props: {} } : palette[s];
};
console.log(`window around (${cx},${cy},${cz}) in ${file.split(/[\\/]/).pop()}  [+X right, +Z down]`);
console.log(`centre props: ${JSON.stringify(at(cx, cy, cz).props)}`);
for (let y = cy - 1; y <= cy + 3; y++) {
  console.log(`--- y=${y} ${y === cy ? '(door lower half)' : y === cy + 1 ? '(door upper half)' : ''}`);
  for (let z = cz - r; z <= cz + r; z++) {
    let row = '';
    for (let x = cx - r; x <= cx + r; x++) {
      const cell = at(x, y, z);
      const tag = (x === cx && z === cz) ? '>>' : '  ';
      row += tag + abbr(cell.name).padEnd(6);
    }
    console.log(row);
  }
}
