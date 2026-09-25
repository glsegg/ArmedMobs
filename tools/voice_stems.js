// The distinct name stems per side (README 5y): what events does each bundle actually cover?
//
//   node tools/voice_stems.js
'use strict';
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, 'spike', 'work', 'voice_src');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const groups = {
  BEAR: walk(path.join(SRC, 'tarkov', 'bear_voice_bundle_01')),
  USEC: walk(path.join(SRC, 'tarkov', 'usec_voice_bundle_01')),
  ELITE: walk(path.join(SRC, 'pmc', '优质PMC')),
};

for (const [label, files] of Object.entries(groups)) {
  const stems = new Map();
  for (const file of files) {
    let name = path.basename(file, path.extname(file));
    // Strip the trailing counters/suffix flags (bear2_agony1 -> agony, usec_attention_01_n -> attention).
    name = name.replace(/^(bear|usec)\d*[_-]/i, '').replace(/_\d+[a-z]?$/i, '').replace(/\d+[a-z_]*$/i, '');
    name = name.replace(/[_\s]+$/, '');
    stems.set(name, (stems.get(name) || 0) + 1);
  }
  console.log(`\n=== ${label} (${files.length} files, ${stems.size} stems) ===`);
  const sorted = [...stems.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  for (const [stem, count] of sorted) {
    console.log(`  ${String(count).padStart(4)}  ${stem}`);
  }
}
