// INDEPENDENT connectivity check: can a player actually get from outside into every room?
//
//   node tools/spike/work/citymap/verify_reachability.js <structure.nbt> [...]
//
// Method: build the "a player can occupy this cell" grid (the cell and the one above it are air-like /
// non-colliding / an openable door), seed a flood from every occupiable cell on the bounding-box border
// (i.e. "outside"), flood orthogonally in 6 directions, then report the occupiable components that the
// flood never reached and that are big enough to be a room (>= 12 cells, the same threshold the project's
// own gate uses). A component the flood cannot reach and that has no door is a genuinely sealed space.
//
// This is deliberately a different method from `tools/selftest_blockstates.js` (which only knows doors,
// ladders and box edges, so a room joined to the rest by a plain air opening looks "sealed" to it).
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

const NON_COLLIDING = /(^minecraft:(air|cave_air|void_air|water|lava|torch|wall_torch|soul_torch|soul_wall_torch|redstone_torch|ladder|chain|lantern|soul_lantern|end_rod|light|flower_pot|structure_void|short_grass|grass|fern|dandelion|poppy|azure_bluet|cornflower|dead_bush|sugar_cane|kelp|seagrass|sweet_berry_bush|vine|glow_lichen|fire|soul_fire)$|_carpet$|_sign$|_banner$|_button$|_pressure_plate$|_rail$|_trapdoor$|_door$|_sapling$|_flower$)/;
const isAirLike = (n) => /^minecraft:(air|cave_air|void_air)$/.test(n);

const file = process.argv[2];
const raw = fs.readFileSync(process.argv[2]);
const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
const root = parseNbt(data);
const [sx, sy, sz] = root.size;
const names = root.palette.map((p) => p.Name);
const id = new Int16Array(sx * sy * sz).fill(-1);
const idx = (x, y, z) => ((y * sz) + z) * sx + x;
for (const b of root.blocks) id[idx(b.pos[0], b.pos[1], b.pos[2])] = b.state;
const name = (x, y, z) => {
  if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 'minecraft:air';
  const s = id[idx(x, y, z)];
  return s < 0 ? 'minecraft:air' : names[s];
};
const occupiable = (x, y, z) => {
  if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false;
  const here = name(x, y, z);
  const above = name(x, y + 1, z);
  return (isAirLike(here) || NON_COLLIDING.test(here)) && (isAirLike(above) || NON_COLLIDING.test(above))
    && !name(x, y - 1, z).startsWith('minecraft:air');
};

const seen = new Int8Array(sx * sy * sz);
const queue = [];
const push = (x, y, z) => { const i = idx(x, y, z); if (!seen[i]) { seen[i] = 1; queue.push([x, y, z]); } };
// seeds: every occupiable cell on the box border ("outside")
for (let y = 0; y < sy; y++) {
  for (let z = 0; z < sz; z++) {
    if (occupiable(0, y, z)) push(0, y, z);
    if (occupiable(sx - 1, y, z)) push(sx - 1, y, z);
  }
  for (let x = 0; x < sx; x++) {
    if (occupiable(x, y, 0)) push(x, y, 0);
    if (occupiable(x, y, sz - 1)) push(x, y, sz - 1);
  }
}
while (queue.length) {
  const [x, y, z] = queue.pop();
  for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0], [0, -1, 0]]) {
    if (occupiable(x + dx, y + dy, z + dz)) push(x + dx, y + dy, z + dz);
  }
}
// now walk the components the flood did NOT reach
const unreached = [];
const compSeen = new Int8Array(sx * sy * sz);
for (let y = 0; y < sy; y++) {
  for (let z = 0; z < sz; z++) {
    for (let x = 0; x < sx; x++) {
      if (!occupiable(x, y, z) || seen[idx(x, y, z)] || compSeen[idx(x, y, z)]) continue;
      const cells = [];
      const q = [[x, y, z]];
      compSeen[idx(x, y, z)] = 1;
      while (q.length) {
        const [cx, cy, cz] = q.pop();
        cells.push([cx, cy, cz]);
        for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0], [0, -1, 0]]) {
          const nx = cx + dx, ny = cy + dy, nz = cz + dz;
          if (occupiable(nx, ny, nz) && !seen[idx(nx, ny, nz)] && !compSeen[idx(nx, ny, nz)]) {
            compSeen[idx(nx, ny, nz)] = 1;
            q.push([nx, ny, nz]);
          }
        }
      }
      if (cells.length >= 12) {
        const hasDoor = cells.some(([cx, cy, cz]) => name(cx, cy, cz).endsWith('_door') || name(cx, cy + 1, cz).endsWith('_door'));
        unreached.push({ size: cells.length, hasDoor, first: cells[0] });
      }
    }
  }
}
console.log(`${path.basename(file)}  size=${sx}x${sy}x${sz}`);
console.log(`  unreachable room-sized areas (>=12 cells): ${unreached.length}`
  + (unreached.length ? `  sizes=${unreached.slice(0, 8).map((u) => u.size).join(',')}` : '')
  + (unreached.length ? `  with a door: ${unreached.filter((u) => u.hasDoor).length}  e.g. at ${unreached[0].first.join(',')}` : ''));
