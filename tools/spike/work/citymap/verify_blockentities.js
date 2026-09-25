// INDEPENDENT check of the block entities the generator now writes into structure NBTs.
//
//   node tools/spike/work/citymap/verify_blockentities.js <structure.nbt> [...]
//
// Prints, per file: spawner count, the entity id in every SpawnData / SpawnPotentials entry, the
// spawner's numeric settings, and every chest's LootTable. The point is that a second implementation
// (this one) reads the same NBT and must agree with the project's own gate.
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
  const f32 = () => { const v = buf.readFloatBE(at); at += 4; return v; };
  const f64 = () => { const v = buf.readDoubleBE(at); at += 8; return v; };
  const str = () => { const n = buf.readUInt16BE(at); at += 2; const s = buf.toString('utf8', at, at + n); at += n; return s; };
  const payload = (t) => {
    switch (t) {
      case 1: return buf.readInt8(at++);
      case 2: return i16();
      case 3: return i32();
      case 4: return i64();
      case 5: return f32();
      case 6: return f64();
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

function main(file) {
  const raw = fs.readFileSync(file);
  const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
  const root = parseNbt(data);
  const names = root.palette.map((p) => p.Name);
  const spawners = [];
  const chests = [];
  const others = new Map();
  for (const b of root.blocks) {
    if (!b.nbt) continue;
    const id = names[b.state];
    if (id === 'minecraft:spawner') {
      const data2 = b.nbt.SpawnData || {};
      const potentials = b.nbt.SpawnPotentials || [];
      const entry = {
        pos: b.pos.join(','),
        id: (data2.entity && (data2.entity.id || data2.entity.type)) || null,
        potentials: potentials.map((p) => (p.data && p.data.entity && (p.data.entity.id || p.data.entity.type)) || '?'),
        settings: ['SpawnCount', 'SpawnRange', 'Delay', 'MinSpawnDelay', 'MaxSpawnDelay', 'RequiredPlayerRange', 'MaxNearbyEntities']
          .map((k) => `${k}=${b.nbt[k]}`).join(' '),
      };
      spawners.push(entry);
    } else if (id === 'minecraft:chest') {
      chests.push({ pos: b.pos.join(','), loot: b.nbt.LootTable || null, seed: b.nbt.LootTableSeed });
    } else {
      others.set(id, (others.get(id) || 0) + 1);
    }
  }
  const ids = new Set();
  for (const s of spawners) { if (s.id) ids.add(s.id); s.potentials.forEach((p) => ids.add(p)); }
  const loots = new Set(chests.map((c) => c.loot));
  console.log(`${path.basename(file)}`);
  console.log(`  spawners=${spawners.length}  entity ids=${[...ids].join(', ') || '(none)'}`);
  if (spawners.length) console.log(`  first spawner @ ${spawners[0].pos} :: ${spawners[0].settings}`);
  console.log(`  chests=${chests.length}  loot tables=${[...loots].join(', ') || '(none)'}`);
  if (chests.length) console.log(`  first chest @ ${chests[0].pos} loot=${chests[0].loot}`);
  if (others.size) console.log(`  other block entities: ${[...others.entries()].map(([k, v]) => k + ' x' + v).join(', ')}`);
}

for (const f of process.argv.slice(2)) main(f);
