// One-off: rewrite the old absolute project path after the folder move
//   D:\deepseek\ArmedMobs  ->  D:\deepseek\ArmedMobs
// Only touches live text files (scripts, docs, configs). Build/run logs, jars, audio, the bundled
// city-save server install and the scratch voice sources are left alone: they are historical
// evidence, not inputs to anything.
const fs = require('fs');
const path = require('path');

const ROOT = 'D:\\deepseek\\ArmedMobs';
const REPLACEMENTS = [
  ['D:\\deepseek\\ArmedMobs', 'D:\\deepseek\\ArmedMobs'],
  ['D:/deepseek/ArmedMobs', 'D:/deepseek/ArmedMobs'],
  ['D:\\\\deepseek\\\\TarkovScav', 'D:\\\\deepseek\\\\ArmedMobs'],
  ['deepseek\\\\TarkovScav', 'deepseek\\\\ArmedMobs'],
];

const SKIP_DIRS = new Set([
  'build', 'run', 'libs', '.gradle', '.git', 'voice_src', 'geckolib-inspect',
  'server', // tools/spike/citysave/server is a whole dedicated-server install
  'ysm', 'javap', 'probe-out',
]);
const TEXT_EXT = new Set([
  '.js', '.ps1', '.md', '.txt', '.json', '.cmd', '.bat', '.java', '.gradle', '.properties', '.cfg', '.toml',
]);

const changed = [];
const skippedLarge = [];

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      walk(full);
      continue;
    }
    if (!TEXT_EXT.has(path.extname(entry.name).toLowerCase())) continue;
    if (entry.name.endsWith('.log')) continue;
    const stat = fs.statSync(full);
    if (stat.size > 2 * 1024 * 1024) { skippedLarge.push(full); continue; }
    let text = fs.readFileSync(full, 'utf8');
    const before = text;
    let hits = 0;
    for (const [from, to] of REPLACEMENTS) {
      const parts = text.split(from);
      hits += parts.length - 1;
      text = parts.join(to);
    }
    if (hits > 0 && text !== before) {
      fs.writeFileSync(full, text, 'utf8');
      changed.push([path.relative(ROOT, full), hits]);
    }
  }
}

walk(ROOT);
changed.sort((a, b) => a[0].localeCompare(b[0]));
console.log(`changed ${changed.length} file(s):`);
for (const [file, hits] of changed) console.log(`  ${hits.toString().padStart(3)}  ${file}`);
if (skippedLarge.length) console.log(`skipped (${skippedLarge.length} large file(s)): ${skippedLarge.join(', ')}`);
