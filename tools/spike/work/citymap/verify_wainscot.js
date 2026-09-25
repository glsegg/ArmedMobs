// Independent check of the ONE number the city-survey headline rests on:
//   "the shipped preset city_small has ~300 'low cover lines' that are really the bottom course of every
//    building wall (stone_brick_wall wainscot), so they are not usable cover."
//
// This decodes the structure NBT itself (gzip + NBT, no project code involved) and reports, for one
// preset: the per-block-id counts, and for stone_brick_wall specifically how many of them have a SOLID
// block directly above (i.e. they are the bottom course of a wall) versus open sky above them (i.e. they
// are a free-standing 1-high ledge somebody could crouch behind).
//
//   node tools/spike/work/citymap/verify_wainscot.js <structure.nbt> [more.nbt ...]
'use strict';
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

// ------------------------------------------------------------------ NBT (big-endian, standard tags)
function parseNbt(buf) {
  let at = 0;
  const u8 = () => buf[at++];
  const i16 = () => { const v = buf.readInt16BE(at); at += 2; return v; };
  const i32 = () => { const v = buf.readInt32BE(at); at += 4; return v; };
  const i64 = () => { const v = buf.readBigInt64BE(at); at += 8; return v; };
  const f32 = () => { const v = buf.readFloatBE(at); at += 4; return v; };
  const f64 = () => { const v = buf.readDoubleBE(at); at += 8; return v; };
  const str = () => { const n = buf.readUInt16BE(at); at += 2; const s = buf.toString('utf8', at, at + n); at += n; return s; };
  const payload = (type) => {
    switch (type) {
      case 1: return buf.readInt8(at++);
      case 2: return i16();
      case 3: return i32();
      case 4: return i64();
      case 5: return f32();
      case 6: return f64();
      case 7: { const n = i32(); const a = buf.subarray(at, at + n); at += n; return a; }
      case 8: return str();
      case 9: {
        const item = u8();
        const n = i32();
        const out = [];
        for (let i = 0; i < n; i++) out.push(payload(item));
        return out;
      }
      case 10: {
        const out = {};
        for (;;) {
          const t = u8();
          if (t === 0) break;
          const name = str();
          out[name] = payload(t);
        }
        return out;
      }
      case 11: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i32()); return out; }
      case 12: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i64()); return out; }
      default: throw new Error('unsupported tag type ' + type + ' at ' + at);
    }
  };
  const rootType = u8();
  if (rootType !== 10) throw new Error('root is not a compound');
  str(); // root name
  return payload(10);
}

function readStructure(file) {
  const raw = fs.readFileSync(file);
  // gzip magic, else assume raw (zlib) / uncompressed
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const size = root.size;
  const names = root.palette.map((p) => p.Name);
  const cells = new Map();
  for (const b of root.blocks) {
    const [x, y, z] = b.pos;
    cells.set(`${x},${y},${z}`, names[b.state] || '?');
  }
  return { size, names, cells };
}

const SOLID_ISH = /_wall$|_fence$|_planks$|_log$|_concrete$|terracotta$|_bricks$|deepslate_tiles$|_pane$|_leaves$|^minecraft:(stone|glass|iron_bars|bricks|cobblestone|deepslate|blackstone|sandstone|obsidian|crying_obsidian|smooth_stone|polished_andesite|andesite|diorite|granite)$/;

for (const file of process.argv.slice(2)) {
  const { size, names, cells } = readStructure(file);
  const counts = new Map();
  for (const id of cells.values()) counts.set(id, (counts.get(id) || 0) + 1);
  const wall = counts.get('minecraft:stone_brick_wall') || 0;
  let wainscot = 0; let free = 0; let unknownAbove = 0;
  for (const [key, id] of cells) {
    if (id !== 'minecraft:stone_brick_wall') continue;
    const [x, y, z] = key.split(',').map(Number);
    const above = cells.get(`${x},${y + 1},${z}`);
    if (above === undefined) { /* air */ free += 1; }
    else if (SOLID_ISH.test(above)) wainscot += 1;
    else unknownAbove += 1;
  }
  const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
  console.log(`${path.basename(file)}  size=${size.join('x')}  blocks=${cells.size}  ids=${counts.size}`);
  console.log(`  stone_brick_wall=${wall}  with solid block above (wall course)=${wainscot}  open above=${free}  other above=${unknownAbove}`);
  console.log(`  top ids: ${top.map(([k, v]) => `${k.replace('minecraft:', '')} ${v}`).join(', ')}`);
}
