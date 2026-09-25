// Inventory of the two voice archives (README 5y): structure, counts and a first classification pass.
//
//   node tools/voice_inventory.js
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'tools', 'spike', 'work', 'voice_src');

const hasChinese = (name) => /[\u4e00-\u9fff]/.test(name);

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

function report(label, dir) {
  if (!fs.existsSync(dir)) {
    console.log(`${label}: NOT EXTRACTED (${dir})`);
    return [];
  }
  const files = walk(dir);
  const byExt = {};
  const dirs = new Set();
  for (const file of files) {
    const ext = path.extname(file).toLowerCase() || '(none)';
    byExt[ext] = (byExt[ext] || 0) + 1;
    dirs.add(path.relative(dir, path.dirname(file)) || '.');
  }
  const audio = files.filter((f) => /\.(wav|ogg|mp3|flac|m4a|aac|wma)$/i.test(f));
  const totalBytes = audio.reduce((sum, f) => sum + fs.statSync(f).size, 0);
  console.log(`\n=== ${label} ===`);
  console.log(`files: ${files.length}, audio: ${audio.length}, audio bytes: ${totalBytes}`);
  console.log('by extension:', JSON.stringify(byExt));
  console.log(`folders (${dirs.size}):`);
  for (const d of [...dirs].sort()) {
    const count = files.filter((f) => (path.relative(dir, path.dirname(f)) || '.') === d).length;
    console.log(`  ${d}  (${count})`);
  }
  return audio;
}

const tarkov = report('塔科夫音效 (tarkov_sfx.rar)', path.join(SRC, 'tarkov'));
const pmc = report('优质PMC (pmc.zip)', path.join(SRC, 'pmc'));

// Classification: USEC / BEAR / PMC(elite), and an event category from the file or folder name.
const CATEGORIES = [
  ['contact', /contact|spot|enemy|target|contact_/i],
  ['idle', /idle|mumble|self|talk|chatter|to_self/i],
  ['taunt', /taunt|insult|provoke|mock|joke/i],
  ['hurt', /hurt|pain|injured|damage|wound|hit/i],
  ['death', /death|die|dying|kill(ed)?_?me|fatal/i],
  ['grenade', /grenade|frag|flash|nade/i],
  ['reload', /reload|magazine|mag_|bolt|chamber|weapon|gun_?(check|ready)/i],
  ['order', /order|move|advance|retreat|cover|hold|go_|follow|command|squad/i],
  ['effort', /effort|breath|exhaust|tired|jump|land|fall/i],
  ['loot', /loot|search|pickup|inventory|loot_/i],
];
const SIDES = [
  ['usec', /usec/i],
  ['bear', /bear/i],
  ['pmc', /pmc|优质|elite/i],
];

function classify(files) {
  const rows = [];
  for (const file of files) {
    const rel = path.relative(SRC, file).replace(/\\/g, '/');
    const side = SIDES.find(([, re]) => re.test(rel));
    const category = CATEGORIES.find(([, re]) => re.test(rel));
    rows.push({ rel, side: side ? side[0] : 'unknown', category: category ? category[0] : 'unknown' });
  }
  return rows;
}

function summarise(label, rows) {
  console.log(`\n=== ${label}: classification ===`);
  const sides = {};
  const categories = {};
  for (const row of rows) {
    sides[row.side] = (sides[row.side] || 0) + 1;
    const key = `${row.side}/${row.category}`;
    categories[key] = (categories[key] || 0) + 1;
  }
  console.log('by side:', JSON.stringify(sides));
  for (const key of Object.keys(categories).sort()) {
    console.log(`  ${key}: ${categories[key]}`);
  }
  const unclassified = rows.filter((r) => r.side === 'unknown' || r.category === 'unknown').slice(0, 15);
  if (unclassified.length) {
    console.log('  first unclassified paths:');
    for (const row of unclassified) console.log(`    ${row.rel}`);
  }
}

summarise('tarkov', classify(tarkov));
summarise('pmc', classify(pmc));
