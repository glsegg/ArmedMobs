// Self-test for /tarkovscav city import: the validation branches, on a real structure file and on
// synthetic ones built here.
//
//   node tools/selftest_city_import.js
//
// The rules mirror CityStructures#read: a file must exist, be non-empty, be compressed NBT, have a
// size/palette/blocks, stay within the per-axis limit, and every block entry must reference a palette
// entry that exists. Every one of those has a synthetic file that must be REJECTED, so "the import is
// validated" is a claim with a test rather than a comment.
//
// The last section greps the Java for the thresholds and for the command/gate wiring, so this file
// cannot describe a different implementation than the one that ships.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const STRUCTURE_DIR = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav', 'structures');
const SHIPPED = path.join(STRUCTURE_DIR, 'city_small.nbt');
const WORLD_STRUCTURES = path.join(ROOT, 'tools', 'spike', 'citysave', 'server', 'world', 'generated', 'minecraft', 'structures');
const SOURCE = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'world', 'CityStructures.java');
const GATE = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'world', 'CityGate.java');
const COMMANDS = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'command', 'ModCommands.java');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ a minimal NBT reader
const readTag = (buf, state) => {
  const type = buf[state.i++];
  const nameLength = buf.readUInt16BE(state.i);
  state.i += 2;
  const name = buf.toString('utf8', state.i, state.i + nameLength);
  state.i += nameLength;
  return { type, name, value: readPayload(buf, state, type) };
};
const readPayload = (buf, state, type) => {
  switch (type) {
    case 1: return buf.readInt8(state.i++);
    case 2: { const v = buf.readInt16BE(state.i); state.i += 2; return v; }
    case 3: { const v = buf.readInt32BE(state.i); state.i += 4; return v; }
    case 4: { const v = buf.readBigInt64BE(state.i); state.i += 8; return v; }
    case 5: { const v = buf.readFloatBE(state.i); state.i += 4; return v; }
    case 6: { const v = buf.readDoubleBE(state.i); state.i += 8; return v; }
    case 7: { const n = buf.readInt32BE(state.i); state.i += 4 + n; return `<byte[${n}]>`; }
    case 8: { const n = buf.readUInt16BE(state.i); state.i += 2; const v = buf.toString('utf8', state.i, state.i + n); state.i += n; return v; }
    case 9: {
      const elementType = buf[state.i++];
      const n = buf.readInt32BE(state.i);
      state.i += 4;
      const items = [];
      for (let k = 0; k < n; k++) items.push(readPayload(buf, state, elementType));
      return { list: true, elementType, items };
    }
    case 10: {
      const compound = {};
      for (;;) {
        if (buf[state.i] === 0) { state.i++; break; }
        const tag = readTag(buf, state);
        compound[tag.name] = tag.value;
      }
      return compound;
    }
    case 11: { const n = buf.readInt32BE(state.i); state.i += 4 + n * 4; return `<int[${n}]>`; }
    case 12: { const n = buf.readInt32BE(state.i); state.i += 4 + n * 8; return `<long[${n}]>`; }
    default: throw new Error(`unknown tag type ${type} at ${state.i}`);
  }
};
const readNbt = (file) => {
  const raw = fs.readFileSync(file);
  const buf = zlib.gunzipSync(raw);
  const root = readTag(buf, { i: 0 });
  return root.value;
};

// ------------------------------------------------------------------ a minimal NBT writer
const writeString = (name, value) => {
  const nameBuf = Buffer.from(name, 'utf8');
  const body = Buffer.from(value, 'utf8');
  const head = Buffer.alloc(3);
  head.writeUInt8(8, 0);
  head.writeUInt16BE(nameBuf.length, 1);
  const length = Buffer.alloc(2);
  length.writeUInt16BE(body.length, 0); // an NBT string payload is length-prefixed
  return Buffer.concat([head, nameBuf, length, body]);
};
const writeInt = (name, value) => {
  const head = Buffer.alloc(3);
  head.writeUInt8(3, 0);
  head.writeUInt16BE(Buffer.byteLength(name), 1);
  const body = Buffer.alloc(4);
  body.writeInt32BE(value, 0);
  return Buffer.concat([head, Buffer.from(name, 'utf8'), body]);
};
const writeListOfInt = (name, values) => {
  const head = Buffer.alloc(3);
  head.writeUInt8(9, 0);
  head.writeUInt16BE(Buffer.byteLength(name), 1);
  const meta = Buffer.alloc(5);
  meta.writeUInt8(3, 0);
  meta.writeInt32BE(values.length, 1);
  const items = values.map((v) => { const b = Buffer.alloc(4); b.writeInt32BE(v, 0); return b; });
  return Buffer.concat([head, Buffer.from(name, 'utf8'), meta, ...items]);
};
const writeListOfCompound = (name, compounds) => {
  const head = Buffer.alloc(3);
  head.writeUInt8(9, 0);
  head.writeUInt16BE(Buffer.byteLength(name), 1);
  const meta = Buffer.alloc(5);
  meta.writeUInt8(10, 0);
  meta.writeInt32BE(compounds.length, 1);
  return Buffer.concat([head, Buffer.from(name, 'utf8'), meta, ...compounds]);
};
const end = () => Buffer.from([0]);
const gzip = (buffers) => zlib.gzipSync(Buffer.concat(buffers));

const structure = (size, palette, blocks, entities = []) => gzip([
  Buffer.from([10, 0, 0]), // compound, empty name
  writeListOfInt('size', size),
  writeListOfCompound('palette', palette.map((name) => Buffer.concat([writeString('Name', name), end()]))),
  writeListOfCompound('blocks', blocks.map((state) => Buffer.concat([writeInt('state', state), writeListOfInt('pos', [0, 0, 0]), end()]))),
  writeListOfCompound('entities', entities),
  end(),
]);

// ------------------------------------------------------------------ the rules, as CityStructures has them
const MAX_AXIS = Number(/MAX_AXIS = (\d+)/.exec(fs.readFileSync(SOURCE, 'utf8'))[1]);
const MAX_BLOCKS = Number(/MAX_BLOCKS = ([\d_]+)/.exec(fs.readFileSync(SOURCE, 'utf8'))[1].replace(/_/g, ''));
const MIN_BLOCKS = Number(/MIN_BLOCKS = (\d+)/.exec(fs.readFileSync(SOURCE, 'utf8'))[1]);

const validate = (file) => {
  if (!fs.existsSync(file)) return 'no such file';
  const bytes = fs.statSync(file).size;
  if (bytes <= 0) return 'empty (0 bytes)';
  let tag;
  try {
    tag = readNbt(file);
  } catch (error) {
    return `not a readable compressed NBT structure: ${error.message}`;
  }
  const size = tag.size && tag.size.items;
  if (!size || size.length !== 3) return 'has no size tag';
  for (const axis of size) {
    if (axis <= 0) return `non-positive size (${size.join('x')})`;
    if (axis > MAX_AXIS) return `size ${size.join('x')} exceeds the ${MAX_AXIS}-block limit per axis`;
  }
  const palette = (tag.palette && tag.palette.items) || [];
  const blocks = (tag.blocks && tag.blocks.items) || [];
  if (!palette.length) return 'empty palette';
  if (blocks.length < MIN_BLOCKS) return `no blocks (palette of ${palette.length}, 0 block entries)`;
  if (blocks.length > MAX_BLOCKS) return `${blocks.length} blocks, above the ${MAX_BLOCKS}-block limit`;
  for (let i = 0; i < blocks.length; i++) {
    const state = blocks[i].state;
    if (typeof state !== 'number' || state < 0 || state >= palette.length) {
      return `block ${i} references palette entry ${state} but the palette has ${palette.length} entries`;
    }
  }
  return null; // valid
};

console.log(`rules read from CityStructures.java: MAX_AXIS=${MAX_AXIS} MAX_BLOCKS=${MAX_BLOCKS} MIN_BLOCKS=${MIN_BLOCKS}`);

// ------------------------------------------------------------------ the real files
console.log('\nreal structure files:');
const realFiles = [];
// Every city preset the jar ships, taken from CityStructures.SHIPPED so a new variant cannot be added to
// the generator and forgotten here.
const shippedNames = (() => {
  const sourceText = fs.readFileSync(SOURCE, 'utf8');
  const match = /SHIPPED = List\.of\(([^)]*)\)/.exec(sourceText);
  return match ? [...match[1].matchAll(/"([a-z0-9_]+)"/g)].map((m) => m[1]) : ['city_small'];
})();
for (const name of shippedNames) {
  const file = path.join(STRUCTURE_DIR, `${name}.nbt`);
  if (fs.existsSync(file)) realFiles.push([`shipped ${name}`, file]);
  else realFiles.push([`shipped ${name} MISSING`, null]);
}
if (fs.existsSync(WORLD_STRUCTURES)) {
  for (const entry of fs.readdirSync(WORLD_STRUCTURES).filter((f) => f.endsWith('.nbt'))) {
    realFiles.push([`world ${entry}`, path.join(WORLD_STRUCTURES, entry)]);
  }
}
check(realFiles.length > 0 && realFiles.every(([, file]) => file),
  'every city preset CityStructures.SHIPPED names is on disk',
  shippedNames.join(', '));
for (const [label, file] of realFiles) {
  if (!file) {
    check(false, `${label} exists`, 'file is missing - run java -cp tools/spike/out CityStructureGen');
    continue;
  }
  const verdict = validate(file);
  let detail = '';
  try {
    const tag = readNbt(file);
    detail = `size=${tag.size.items.join('x')} palette=${(tag.palette && tag.palette.items || []).length}`
      + ` blocks=${(tag.blocks && tag.blocks.items || []).length}`
      + ` entities=${(tag.entities && tag.entities.items || []).length}`;
  } catch (error) {
    detail = `unreadable: ${error.message}`;
  }
  check(verdict === null, `${label} passes validation`, `${detail}${verdict ? ' -> ' + verdict : ''}`);
}

// ------------------------------------------------------------------ the synthetic branches
console.log('\nevery rejection branch:');
const dir = path.join(ROOT, 'tools', '_city_import_cases');
fs.mkdirSync(dir, { recursive: true });
const cases = [
  ['missing file', path.join(dir, 'does-not-exist.nbt'), null, false],
  ['empty file', path.join(dir, 'empty.nbt'), Buffer.alloc(0), false],
  ['not compressed', path.join(dir, 'plain.nbt'), Buffer.from('this is not nbt'), false],
  ['no size tag', path.join(dir, 'nosize.nbt'), gzip([Buffer.from([10, 0, 0]), end()]), false],
  ['zero size', path.join(dir, 'zerosize.nbt'), structure([0, 5, 5], ['minecraft:stone'], [0]), false],
  ['oversized', path.join(dir, 'huge.nbt'), structure([MAX_AXIS + 1, 5, 5], ['minecraft:stone'], [0]), false],
  ['empty palette', path.join(dir, 'nopalette.nbt'), structure([4, 4, 4], [], []), false],
  ['no blocks', path.join(dir, 'noblocks.nbt'), structure([4, 4, 4], ['minecraft:stone'], []), false],
  ['too many blocks', path.join(dir, 'toomany.nbt'),
    structure([4, 4, 4], ['minecraft:stone'], Array.from({ length: MAX_BLOCKS + 1 }, () => 0)), false],
  ['bad palette index', path.join(dir, 'badindex.nbt'), structure([4, 4, 4], ['minecraft:stone'], [0, 3]), false],
  ['valid 1x1x1', path.join(dir, 'tiny.nbt'), structure([1, 1, 1], ['minecraft:stone'], [0]), true],
  ['valid with entities', path.join(dir, 'entities.nbt'),
    structure([8, 6, 8], ['minecraft:stone', 'minecraft:oak_planks'], [0, 1, 1, 1],
      [Buffer.concat([writeString('id', 'minecraft:pig'), end()])]), true],
];
for (const [label, file, contents, shouldPass] of cases) {
  if (contents !== null) fs.writeFileSync(file, contents);
  const verdict = validate(file);
  const passed = verdict === null;
  check(passed === shouldPass, `${label} -> ${shouldPass ? 'ACCEPTED' : 'REJECTED'}`,
    shouldPass ? (verdict || '') : (verdict || 'NOT REJECTED'));
}
fs.rmSync(dir, { recursive: true, force: true });

// ------------------------------------------------------------------ source guards
console.log('\nsource guard:');
const source = fs.readFileSync(SOURCE, 'utf8').replace(/\/\/[^\n]*/g, '');
const commands = fs.readFileSync(COMMANDS, 'utf8');
const gate = fs.readFileSync(GATE, 'utf8').replace(/\/\/[^\n]*/g, '');

check(/Files\.copy\(source, target, StandardCopyOption\.REPLACE_EXISTING\)/.test(source),
  'import copies the world file into tarkovscav/city/');
check(/getWorldPath\(net\.minecraft\.world\.level\.storage\.LevelResource\.GENERATED_DIR\)/.test(source),
  'the source is <world>/generated/minecraft/structures');
check(/static Loaded read\(Path file, String name\)/.test(source),
  'validation lives in one place (read) so reload and import agree');
check(/NbtIo\.readCompressed/.test(source) && /Instance of|template\.load\(blocks, tag\)/.test(source),
  'the template is loaded through vanilla StructureTemplate#load');
check(/placeInWorld\(level, pos, pos, settings, level\.getRandom\(\), 2\)/.test(source),
  'place uses StructureTemplate#placeInWorld');
check(/record\(level, loaded\.name\(\), pos, rotation, mirror, box\)/.test(source),
  'a placed instance is remembered as a box');
check(/instanceAt\(level, pos\)/.test(gate) === false || /CityStructures\.instanceAt\(level, pos\)/.test(gate),
  'the gate consults the runtime instances before anything else');
check(/inside placed city structure/.test(gate), 'the gate names the instance in its reason');
for (const sub of ['literal("import")', 'literal("reload")', 'literal("place")', 'literal("structures")']) {
  check(commands.includes(sub), `ModCommands registers city ${sub}`);
}
check(/CITY_STRUCTURE_IDS/.test(gate) && /CITY_STRUCTURE_TAGS/.test(gate),
  'the structure-id/tag gate path is untouched (the runtime pool is additive)');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
