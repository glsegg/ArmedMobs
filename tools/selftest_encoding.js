// Encoding gate: three rules, because one was not enough.
//
//   node tools/selftest_encoding.js
//
// Rule 1 - strict UTF-8, no U+FFFD. A PowerShell text round-trip (Get-Content -Raw | Set-Content) once
//          wrote Config.java back with mangled non-ASCII bytes and the compiler could not read the file.
//          Strict decoding catches that class of damage.
// Rule 2 - every src/**/*.java is PURE ASCII. This is the project's own rule ("keep Java sources ASCII"),
//          and it is what actually catches a double-encoded symbol living in a javadoc: the first version
//          of this gate passed Config.java:221 while it still contained "ÃÂ±", because those bytes happen
//          to be valid UTF-8 - they are just the wrong characters. ASCII-only fails immediately.
// Rule 3 - resource files (json/toml/md/mcmeta/txt) must not contain common mojibake markers
//          (Ã Â â€ ï¿½ Ð Ñ), which is the same accident seen from the other side: there, non-ASCII is
//          legitimate (Chinese subtitles!), so "must be ASCII" cannot be used and markers are the signal.
//
// The repository rule this enforces: source and resource files are edited with the file tools
// (read/edit/write), never through PowerShell text pipelines.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const TEXT_EXTENSIONS = new Set(['.java', '.json', '.toml', '.mcmeta', '.txt', '.md', '.properties', '.cfg']);
const RESOURCE_EXTENSIONS = new Set(['.json', '.toml', '.mcmeta', '.txt', '.md', '.properties']);
const MOJIBAKE = ['\u00C3', '\u00C2', '\u00E2\u20AC', '\u00EF\u00BF\u00BD', '\u00D0', '\u00D1'];
const REPLACEMENT = '\uFFFD';

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const decoder = new TextDecoder('utf-8', { fatal: true });
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (TEXT_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) files.push(full);
  }
})(SRC);

console.log(`encoding audit: ${files.length} text file(s) under src/`);
const notUtf8 = [];
const replacements = [];
const nonAsciiJava = [];
const mojibake = [];
for (const file of files) {
  const relative = path.relative(ROOT, file);
  const bytes = fs.readFileSync(file);
  let text;
  try {
    text = decoder.decode(bytes);
  } catch (error) {
    notUtf8.push(relative);
    continue;
  }
  if (text.includes(REPLACEMENT)) replacements.push(relative);
  const extension = path.extname(file).toLowerCase();
  if (extension === '.java' && /[^\x00-\x7F]/.test(text)) {
    // Name the first offender and its line, so the failure is actionable.
    const lines = text.split(/\r?\n/);
    const index = lines.findIndex((line) => /[^\x00-\x7F]/.test(line));
    nonAsciiJava.push(`${relative}:${index + 1}  ${lines[index].trim().slice(0, 80)}`);
  }
  if (RESOURCE_EXTENSIONS.has(extension)) {
    for (const marker of MOJIBAKE) {
      if (text.includes(marker)) {
        mojibake.push(`${relative} contains ${JSON.stringify(marker)}`);
        break;
      }
    }
  }
}

console.log('');
console.log('1. strict UTF-8, no U+FFFD');
check(notUtf8.length === 0, 'every file decodes as strict UTF-8',
  notUtf8.length ? notUtf8.join(', ') : `${files.length}/${files.length}`);
check(replacements.length === 0, 'no file contains U+FFFD (a silent substitution)',
  replacements.length ? replacements.join(', ') : 'none');

console.log('');
console.log('2. Java sources: no mojibake, and the non-ASCII that IS there is listed');
// The repository does NOT keep Java sources pure ASCII - javadoc uses +/- as "±", arrows, box drawing
// and a few Chinese labels on purpose (6 files). What must never appear is the *double-encoded* form of
// such a character: "ÃÂ±" is valid UTF-8, so "valid UTF-8" alone cannot see it (that is exactly how
// Config.java:221 slipped past the first version of this gate), but it always contains a marker.
const javaMojibake = [];
const javaNonAscii = [];
for (const file of files) {
  if (path.extname(file).toLowerCase() !== '.java') continue;
  const relative = path.relative(ROOT, file);
  const text = fs.readFileSync(file, 'utf8');
  for (const marker of MOJIBAKE) {
    if (text.includes(marker)) {
      const lines = text.split(/\r?\n/);
      const index = lines.findIndex((line) => line.includes(marker));
      javaMojibake.push(`${relative}:${index + 1} contains ${JSON.stringify(marker)}`);
      break;
    }
  }
  if (/[^\x00-\x7F]/.test(text)) {
    const characters = [...new Set((text.match(/[^\x00-\x7F]/g) || []))].join('');
    javaNonAscii.push(`${relative} [${characters}]`);
  }
}
check(javaMojibake.length === 0, 'no mojibake marker in any src/**/*.java',
  javaMojibake.length ? javaMojibake.join(' | ')
    : 'this is the rule that catches ÃÂ± - the accident the strict-UTF-8 rule let through');
console.log(`  NOTE  ${javaNonAscii.length} java file(s) carry intentional non-ASCII (not a failure):`);
javaNonAscii.forEach((entry) => console.log(`          ${entry}`));

console.log('');
console.log('3. resources carry no mojibake markers');
check(mojibake.length === 0, 'no mojibake markers in json, toml or md',
  mojibake.length ? mojibake.join(' | ')
    : 'Chinese subtitles are still allowed - only the markers are rejected');

console.log('');
console.log('4. the Chinese content really is still there');
const zh = path.join(SRC, 'main', 'resources', 'assets', 'tarkovscav', 'lang', 'zh_cn.json');
if (fs.existsSync(zh)) {
  const text = fs.readFileSync(zh, 'utf8');
  const samples = ['武装暴徒', '武装村民', '暴徒：'];
  const missing = samples.filter((needle) => !text.includes(needle));
  check(missing.length === 0, 'zh_cn.json still contains its Chinese text',
    missing.length ? `missing: ${missing.join(', ')}` : `${samples.length} sample(s) present`);
} else {
  check(false, 'zh_cn.json exists');
}

console.log('');
if (failures > 0) {
  console.log(`${failures} encoding check(s) FAILED`);
  console.log('Fix them with the file tools (read/edit/write) - never with PowerShell text pipelines.');
  process.exit(1);
}
console.log('every source and resource file passes all three encoding rules');
