// Cost diagnosis: how much block work does one 512x512 wasteland window carry?
//   node tools/spike/work/citymap/wasteland_cost.js
'use strict';
const fs = require('fs');
const path = require('path');
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

const DIR = path.join(__dirname, '..', '..', '..', '..', 'src', 'main', 'resources', 'data', 'tarkovscav', 'structures');
const FILES = ['city_small', 'city_a', 'city_b', 'city_c', 'gen_district', 'city_strongpoint'];
// measured structure counts in one 512x512 wasteland window (from the dimension task's region-file read)
const COUNT = { city_small: 67, city_a: 18, city_b: 18, city_c: 12, gen_district: 4, city_strongpoint: 0 };

let totalBlocks = 0;
let totalBe = 0;
console.log('structure            size            non-air   block-entities   x count  = blocks placed');
for (const f of FILES) {
  const raw = fs.readFileSync(path.join(DIR, f + '.nbt'));
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const names = root.palette.map((p) => p.Name);
  let nonAir = 0;
  let be = 0;
  for (const b of root.blocks) {
    if (names[b.state] !== 'minecraft:air') nonAir += 1;
    if (b.nbt) be += 1;
  }
  const n = COUNT[f] || 0;
  const placed = nonAir * n;
  totalBlocks += placed;
  totalBe += be * n;
  console.log(`${f.padEnd(18)} ${root.size.join('x').padEnd(14)} ${String(nonAir).padStart(7)} ${String(be).padStart(14)}   x ${String(n).padStart(3)}  = ${placed}`);
}
const chunks = 32 * 32; // a 512x512 window is 32x32 chunks
console.log('');
console.log(`one 512x512 wasteland window (1024 chunks):`);
console.log(`  structure blocks placed : ${totalBlocks.toLocaleString()}  (${Math.round(totalBlocks / chunks).toLocaleString()} per chunk!)`);
console.log(`  block entities placed   : ${totalBe.toLocaleString()}  (${(totalBe / chunks).toFixed(1)} per chunk)`);
console.log(`  a vanilla chunk has ~10k-30k blocks TOTAL, so structures alone are ~${(totalBlocks / chunks / 20000 * 100).toFixed(0)}% of that per chunk`);
console.log('');
console.log('for comparison, the overworld (frequency 0.25, shipped spacing 24/32/48/192):');
console.log('  city_small grid 24 chunks => ~1 candidate per 576 chunks, x0.25 acceptance => ~1 city per ~2,300 chunks');
