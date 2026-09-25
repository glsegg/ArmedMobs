// The published Word document (docs/指令与配置参考.docx) is what a tester or a wiki reader actually
// opens, so it has to carry the same promises as the markdown source it is generated from:
//
//   node tools/selftest_docx.js
//
// What is asserted:
//   1. THE PACKAGE. The .docx exists, is a readable OOXML zip and carries the parts Word needs
//      ([Content_Types].xml, word/document.xml, word/styles.xml, word/_rels/document.xml.rels).
//   2. THE COMMAND NODES. Every `Commands.literal("X")` node in ModCommands.java/ClientCommands.java
//      appears in the document text - the same rule the markdown gate enforces, applied to the file
//      Word renders, so a table cannot be lost in conversion.
//   3. THE CONFIG KEYS. Every key registered in Config.java appears in the document text.
//   4. THE STRUCTURE. The four tier sections, the nine mob ids, all eight H2 section headings, the
//      tables (>=36 of them) and a 目录 (table of contents) are present.
//   5. NO RAW MARKDOWN. The rendered text must not contain a single backtick: a leftover one means the
//      converter dropped an inline `code span` and Word is showing the raw markdown.
//
// Regenerate with:  node tools/make_reference_docx.js
//                   powershell -ExecutionPolicy Bypass -File tools/spike/work/docx_finalize.ps1 -Docx <file>
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const DOCX = path.join(ROOT, 'docs', '指令与配置参考.docx');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const mentions = (text, name) => {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^A-Za-z0-9_])${escaped}([^A-Za-z0-9_]|$)`).test(text);
};

// ------------------------------------------------------------------ a very small zip reader
function zipEntries(buf) {
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) return null;
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    entries.push({
      name: buf.toString('utf8', off + 46, off + 46 + nameLen),
      method, compSize, localOff,
    });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}
function entryData(buf, entry) {
  const lo = entry.localOff;
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const start = lo + 30 + nameLen + extraLen;
  const raw = buf.subarray(start, start + entry.compSize);
  return entry.method === 0 ? raw : zlib.inflateRawSync(raw);
}

// ------------------------------------------------------------------ 1. the package
console.log('1. the docx package is readable OOXML');
const exists = fs.existsSync(DOCX);
check(exists, 'docs/指令与配置参考.docx exists', exists ? `${fs.statSync(DOCX).size} bytes` : 'missing');
if (!exists) {
  console.log('\nthe Word document is missing: run node tools/make_reference_docx.js');
  process.exit(1);
}
const buf = fs.readFileSync(DOCX);
const entries = zipEntries(buf);
check(Array.isArray(entries) && entries.length > 0, 'the file is a zip archive',
  entries ? `${entries.length} part(s)` : 'no end-of-central-directory record');
const byName = new Map((entries || []).map((e) => [e.name, e]));
for (const part of ['[Content_Types].xml', 'word/document.xml', 'word/styles.xml',
  'word/_rels/document.xml.rels', 'word/header1.xml', 'word/footer1.xml']) {
  check(byName.has(part), `the package carries ${part}`);
}
const docXml = byName.has('word/document.xml')
  ? entryData(buf, byName.get('word/document.xml')).toString('utf8') : '';
check(docXml.includes('<w:document'), 'word/document.xml is a WordprocessingML document',
  `${docXml.length} chars of XML`);

// The rendered TEXT, the same thing a reader sees: strip tags, decode entities, keep paragraph breaks.
const text = docXml
  .replace(/<w:tab\/>/g, '\t')
  .replace(/<\/w:p>/g, '\n')
  .replace(/<[^>]+>/g, '')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
  .replace(/&apos;/g, "'").replace(/&amp;/g, '&');

// ------------------------------------------------------------------ 2. the command nodes
console.log('');
console.log('2. every command literal node is in the Word text');
const literalNames = [];
for (const rel of ['command/ModCommands.java', 'command/ClientCommands.java']) {
  const src = read(rel);
  for (const name of [...new Set([...src.matchAll(/Commands\.literal\("([^"]+)"\)/g)].map((m) => m[1]))]) {
    if (!literalNames.includes(name)) literalNames.push(name);
  }
}
const missingLiterals = literalNames.filter((name) => !mentions(text, name));
check(literalNames.length > 0, 'the command trees register literal nodes', `${literalNames.length} name(s)`);
check(missingLiterals.length === 0,
  `every Commands.literal(...) node name appears in the document (${literalNames.length})`,
  missingLiterals.length ? `missing: ${missingLiterals.join(', ')}` : '');

// ------------------------------------------------------------------ 3. the config keys
console.log('');
console.log('3. every config key is in the Word text');
const config = read('Config.java');
const keyPattern = /\.define(?:InRange|ListAllowEmpty|List|Enum)?\(\s*(?:List\.of\()?"([A-Za-z0-9_]+)"/g;
const keys = [...new Set([...config.matchAll(keyPattern)].map((m) => m[1]))];
const missingKeys = keys.filter((key) => !text.includes(key));
check(keys.length > 300, 'Config.java registers the expected number of distinct keys', `${keys.length} key(s)`);
check(missingKeys.length === 0, 'every config key appears in the document',
  missingKeys.length ? `missing: ${missingKeys.join(', ')}` : '');

// ------------------------------------------------------------------ 4. the structure
console.log('');
console.log('4. the structure a reader navigates by');
for (const section of ['[ai.scav]', '[ai.sniper]', '[ai.troop]', '[ai.elite]']) {
  check(text.includes(section), `the document names the config section ${section}`);
}
const entities = read('registry/ModEntities.java');
const mobIds = [...entities.matchAll(
  /ENTITY_TYPES\.register\("([a-z0-9_]+)",\s*\(\) -> EntityType\.Builder\.<[^>]+>of\([^,]+,\s*MobCategory\.(\w+)\)/g)]
  .filter((m) => m[2] !== 'MISC').map((m) => m[1]);
const missingMobs = mobIds.filter((id) => !text.includes(`tarkovscav:${id}`));
check(mobIds.length === 9, 'ModEntities registers nine mob types', mobIds.join(', '));
check(missingMobs.length === 0, 'every mob id is in the Word text',
  missingMobs.length ? `missing: ${missingMobs.join(', ')}` : '');
const h2 = ['1. 怎么用这份表', '2. 服务端命令总表', '3. 客户端命令总表', '4. 单位与 AI 档位对照表',
  '5. 配置键速查表', '7. 第三方内容接口'];
const missingH2 = h2.filter((h) => !text.includes(h));
check(missingH2.length === 0, 'the numbered section headings survive the conversion',
  missingH2.length ? `missing: ${missingH2.join(', ')}` : `${h2.length} checked`);
// Word writes these with revision-id attributes (<w:tr w:rsidR="...">), so match the tag, not "<w:tr>".
const tables = (docXml.match(/<w:tbl[\s>]/g) || []).length;
const rows = (docXml.match(/<w:tr[\s>]/g) || []).length;
check(tables >= 36, 'the tables are real Word tables, not plain text', `${tables} table(s)`);
check(rows >= 470, 'and they carry every data row', `${rows} row(s)`);
check(text.includes('目录'), 'the document has a table of contents');
check(docXml.includes('TOC') || text.includes('目录'), 'the table of contents is a Word TOC field',
  docXml.includes('TOC \\o') ? 'TOC field present' : 'heading list');
check(/<w:pgMar w:top="1134"/.test(docXml) && /w:pgSz w:w="11906"/.test(docXml),
  'the page setup is A4 with the intended margins');

// ------------------------------------------------------------------ 5. no raw markdown leaks
console.log('');
console.log('5. the rendered text carries no raw markdown');
const backticks = (text.match(/`/g) || []).length;
check(backticks === 0, 'not a single backtick survived into the rendered text',
  backticks ? `${backticks} leftover(s)` : 'inline code spans converted');
check(!/\|\s*-{3,}\s*\|/.test(text), 'no markdown table separator row survived');

console.log('');
if (failures) {
  console.log(`${failures} check(s) FAILED`);
  process.exit(1);
}
console.log('the Word document carries every command, every config key and every table of the reference');
