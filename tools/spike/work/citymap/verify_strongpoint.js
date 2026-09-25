// INDEPENDENT re-measurement of the strongpoint's combat metrics.
//
// Written from the approved definition, not from the project's ObstacleScan: the point is that two
// different implementations must agree before I report a number to the user.
//
//   node tools/spike/work/citymap/verify_strongpoint.js <structure.nbt>
//
// A cell is usable low cover when
//   * it is an opaque solid block (not air / not see-through / not a plant / not a thin overlay), and
//   * the block above it is passable (so it is a 1-high ledge, not the bottom course of a wall), and
//   * it has at least one horizontally adjacent cell you can stand on at the same Y
//     (air at Y with solid below).
// A line is a maximal run of >=3 consecutive usable cells along X or along Z at ONE Y level.
//
// Two readings are printed, because the "passable above" test is the definitional fork:
//   STRICT - the block above must be air.
//   THIN   - the block above may be air or a thin overlay (carpet / snow layer / trapdoor / slab),
//            which is how a white_concrete + white_carpet "sandbag" line is allowed to count.
'use strict';
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

// ------------------------------------------------------------------ NBT
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
      default: throw new Error('tag ' + t + ' at ' + at);
    }
  };
  u8(); str();
  return payload(10);
}

const SEE_THROUGH = /(_pane$|^minecraft:(glass|tinted_glass|iron_bars|chain|torch|wall_torch|soul_torch|lantern|soul_lantern|ladder|scaffolding|cobweb|light|flower_pot|end_rod|lightning_rod)$|_leaves$|^minecraft:(short_grass|grass|fern|dandelion|poppy|azure_bluet|cornflower|oxeye_daisy|blue_orchid|allium|sunflower|lilac|rose_bush|peony|sugar_cane|kelp|seagrass|bubble_column|lily_pad|vine|glow_lichen|moss_carpet|hanging_roots|big_dripleaf|small_dripleaf|sweet_berry_bush|cave_vines|weeping_vines|twisting_vines)$|_sapling|_door$|_trapdoor$|_carpet$|_banner$|^minecraft:(water|lava|powder_snow|snow|air|cave_air|void_air|structure_void|barrier)$)/;
const THIN_OVERLAY = /(_carpet$|_slab$|_trapdoor$|^minecraft:snow$|_pressure_plate$|_button$|_sign$|_wall_sign$|_fence_gate$|_rail$|_torch$|^minecraft:lily_pad$|_plate$)/;

function main(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const size = root.size;
  const names = root.palette.map((p) => p.Name);
  const [sx, sy, sz] = size;
  const id = new Int16Array(sx * sy * sz).fill(-1);
  const at = (x, y, z) => ((y * sz) + z) * sx + x;
  for (const b of root.blocks) id[at(b.pos[0], b.pos[1], b.pos[2])] = b.state;
  const name = (x, y, z) => {
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 'minecraft:air';
    const s = id[at(x, y, z)];
    return s < 0 ? 'minecraft:air' : names[s];
  };
  const solid = (x, y, z) => !SEE_THROUGH.test(name(x, y, z));
  const cover = (x, y, z) => solid(x, y, z);
  const standable = (x, y, z) => !solid(x, y, z) && solid(x, y - 1, z);

  function measure(mode) {
    const usable = new Set();
    for (let y = 0; y < sy; y++) {
      for (let z = 0; z < sz; z++) {
        for (let x = 0; x < sx; x++) {
          if (!cover(x, y, z)) continue;
          const above = name(x, y + 1, z);
          const passableAbove = above === 'minecraft:air'
            || (mode === 'THIN' && THIN_OVERLAY.test(above));
          if (!passableAbove) continue;
          if (!(standable(x + 1, y, z) || standable(x - 1, y, z) || standable(x, y, z + 1) || standable(x, y, z - 1))) continue;
          usable.add(`${x},${y},${z}`);
        }
      }
    }
    let lines = 0, inLines = 0, longest = 0;
    const seen = new Set();
    for (const key of usable) {
      const [x, y, z] = key.split(',').map(Number);
      for (const [dx, dz] of [[1, 0], [0, 1]]) {
        const px = x - dx, pz = z - dz;
        if (usable.has(`${px},${y},${pz}`)) continue;      // not the start of a maximal run
        let len = 0;
        for (;;) {
          const cx = x + dx * len, cz = z + dz * len;
          if (!usable.has(`${cx},${y},${cz}`)) break;
          len += 1;
        }
        if (len >= 3) {
          lines += 1;
          inLines += len;
          longest = Math.max(longest, len);
          for (let i = 0; i < len; i++) seen.add(`${x + dx * i},${y},${z + dz * i}`);
        }
      }
    }
    return {
      usable: usable.size, lines, inLines, longest,
      share: usable.size ? (100 * inLines / usable.size) : 0,
    };
  }

  const strict = measure('STRICT');
  const thin = measure('THIN');
  console.log(`${path.basename(file)}  size=${size.join('x')}  nonAir=${root.blocks.length}`);
  console.log(`  STRICT (above must be air)     : usable=${strict.usable} lines=${strict.lines} blocksInLines=${strict.inLines} longest=${strict.longest} share=${strict.share.toFixed(1)}%`);
  console.log(`  THIN   (carpet above allowed)  : usable=${thin.usable} lines=${thin.lines} blocksInLines=${thin.inLines} longest=${thin.longest} share=${thin.share.toFixed(1)}%`);
}

for (const f of process.argv.slice(2)) main(f);
