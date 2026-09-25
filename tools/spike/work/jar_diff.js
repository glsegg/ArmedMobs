// One-off entry-level diff of two jars (baseline vs freshly built), for the README-5ab report.
//
//   node tools/spike/work/jar_diff.js <baseline.jar> <new.jar>
//
// Prints ADDED / REMOVED / CHANGED with each entry's uncompressed byte size, plus a summary.
'use strict';
const fs = require('fs');
const zlib = require('zlib');

/** Every central-directory entry: name -> { method, compressed, size, crc }. */
function entries(jar) {
  const eocd = jar.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw new Error('no EOCD');
  const count = jar.readUInt16LE(eocd + 10);
  let at = jar.readUInt32LE(eocd + 16);
  const map = new Map();
  for (let i = 0; i < count; i++) {
    if (jar.readUInt32LE(at) !== 0x02014b50) throw new Error('bad central header at ' + at);
    const method = jar.readUInt16LE(at + 10);
    const crc = jar.readUInt32LE(at + 16);
    const compressed = jar.readUInt32LE(at + 20);
    const size = jar.readUInt32LE(at + 24);
    const nameLen = jar.readUInt16LE(at + 28);
    const extraLen = jar.readUInt16LE(at + 30);
    const commentLen = jar.readUInt16LE(at + 32);
    const name = jar.toString('utf8', at + 46, at + 46 + nameLen);
    map.set(name, { method, crc, compressed, size });
    at += 46 + nameLen + extraLen + commentLen;
  }
  return map;
}

const [, , basePath, newPath] = process.argv;
const base = entries(fs.readFileSync(basePath));
const next = entries(fs.readFileSync(newPath));

const added = [];
const removed = [];
const changed = [];
for (const [name, e] of next) {
  const b = base.get(name);
  if (!b) added.push({ name, size: e.size });
  else if (b.crc !== e.crc) changed.push({ name, from: b.size, to: e.size, delta: e.size - b.size });
}
for (const [name, e] of base) {
  if (!next.has(name)) removed.push({ name, size: e.size });
}
const byName = (a, b) => a.name.localeCompare(b.name);
added.sort(byName);
removed.sort(byName);
changed.sort(byName);

console.log(`baseline : ${basePath}  (${base.size} entries)`);
console.log(`new      : ${newPath}  (${next.size} entries)`);
console.log('');
console.log(`ADDED (${added.length})`);
for (const e of added) console.log(`  + ${e.name}  ${e.size}`);
console.log(`REMOVED (${removed.length})`);
for (const e of removed) console.log(`  - ${e.name}  ${e.size}`);
console.log(`CHANGED (${changed.length})`);
let sumTo = 0;
let sumFrom = 0;
for (const e of changed) {
  sumTo += e.to;
  sumFrom += e.from;
  console.log(`  ~ ${e.name}  ${e.from} -> ${e.to}  (${e.delta >= 0 ? '+' : ''}${e.delta})`);
}
console.log('');
console.log(`summary: +${added.length} added, ~${changed.length} changed, -${removed.length} removed;`
  + ` changed bytes ${sumFrom} -> ${sumTo} (${sumTo - sumFrom >= 0 ? '+' : ''}${sumTo - sumFrom})`);

// How different are the changed-but-same-size entries? Inflate both and count differing bytes.
if (process.argv[4] === '--detail') {
  const baseBuf = fs.readFileSync(basePath);
  const newBuf = fs.readFileSync(newPath);
  function inflate(jar, name, e) {
    const localAt = (() => {
      // Re-walk to find the local header offset, same traversal as above.
      const eocd = jar.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
      const count = jar.readUInt16LE(eocd + 10);
      let at = jar.readUInt32LE(eocd + 16);
      for (let i = 0; i < count; i++) {
        const nameLen = jar.readUInt16LE(at + 28);
        const extraLen = jar.readUInt16LE(at + 30);
        const commentLen = jar.readUInt16LE(at + 32);
        if (jar.toString('utf8', at + 46, at + 46 + nameLen) === name) return jar.readUInt32LE(at + 42);
        at += 46 + nameLen + extraLen + commentLen;
      }
      return -1;
    })();
    const nameLen = jar.readUInt16LE(localAt + 26);
    const extraLen = jar.readUInt16LE(localAt + 28);
    const dataAt = localAt + 30 + nameLen + extraLen;
    const raw = jar.slice(dataAt, dataAt + e.compressed);
    return e.method === 0 ? raw : zlib.inflateRawSync(raw);
  }
  console.log('');
  console.log('detail (differing byte count / first offsets)');
  for (const e of changed) {
    const a = inflate(baseBuf, e.name, base.get(e.name));
    const b = inflate(newBuf, e.name, next.get(e.name));
    let differing = 0;
    const first = [];
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      if (a[i] !== b[i]) {
        differing++;
        if (first.length < 6) first.push(i);
      }
    }
    console.log(`  ${e.name}: ${differing} byte(s) differ, first at [${first.join(', ')}]`
      + `  (uncompressed ${a.length} -> ${b.length})`);
  }
}
