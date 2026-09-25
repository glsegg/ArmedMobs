// Compare the friend's repo (EdDYON/tarkovscav, one "Initial import" commit, laid out as java/ + resources/)
// against our working tree (src/main/java + src/main/resources), by content hash.
//
//   node tools/spike/work/citymap/compare_friend_repo.js
//
// Prints, in this order: files only in theirs (added by the friend), files present in both but DIFFERENT
// (the interesting set), and counts for files only in ours (our newer work) - so a human can see at a
// glance where the friend's fixes could be.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const THEIRS = 'D:\\deepseek\\ArmedMobs\\_friendfix\\tarkovscav-main';
const OURS = 'D:\\deepseek\\ArmedMobs';

const MAP = [
  { their: 'java', our: 'src/main/java' },
  { their: 'resources', our: 'src/main/resources' },
  { their: 'tools', our: 'tools' },
];

const SKIP_DIR = /(^|\\)(\.git|build|\.gradle|out|voice_src|server)(\\|$)/;

function walk(dir) {
  const out = new Map();
  const rec = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      if (SKIP_DIR.test(full)) continue;
      if (e.isDirectory()) { rec(full); continue; }
      const rel = path.relative(dir, full).replace(/\\/g, '/');
      const buf = fs.readFileSync(full);
      out.set(rel, { size: buf.length, sha: crypto.createHash('sha256').update(buf).digest('hex') });
    }
  };
  if (fs.existsSync(dir)) rec(dir);
  return out;
}

const onlyTheirs = [];
const different = [];
let identical = 0;
const ourCount = new Map();

for (const m of MAP) {
  const theirs = walk(path.join(THEIRS, m.their));
  const ours = walk(path.join(OURS, m.our));
  ourCount.set(m.our, ours.size);
  for (const [rel, t] of theirs) {
    const o = ours.get(rel);
    const label = `${m.our}/${rel}`;
    if (!o) { onlyTheirs.push(`${label}  (${t.size} B)`); continue; }
    if (o.sha === t.sha) { identical += 1; continue; }
    different.push({ file: label, their: t.size, our: o.size });
  }
}

console.log(`identical files: ${identical}`);
console.log(`files only in THEIR repo (${onlyTheirs.length}):`);
onlyTheirs.slice(0, 60).forEach((f) => console.log('  + ' + f));
if (onlyTheirs.length > 60) console.log(`  ... and ${onlyTheirs.length - 60} more`);

console.log('');
console.log(`files in BOTH but different (${different.length}) - the candidate fix set:`);
different.sort((a, b) => Math.abs(b.our - b.their) - Math.abs(a.our - a.their));
different.forEach((d) => console.log(`  ~ ${d.file}  theirs=${d.their} ours=${d.our} delta=${d.our - d.their}`));

console.log('');
for (const [k, v] of ourCount) console.log(`our ${k}: ${v} file(s)`);
