// Repairs the four non-ASCII lines that a PowerShell text round-trip mangled in tools/selftest_voice.js
// (and re-writes the file as plain UTF-8 without a BOM). Node reads and writes UTF-8 byte-faithfully, which
// is exactly why the project's source edits go through node/fs and not through a PowerShell text pipeline.
//
//   node tools/spike/work/fix_voice_gate_encoding.js
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', '..', 'selftest_voice.js');
const lines = fs.readFileSync(file, 'utf8').split('\n');
let fixed = 0;

const replacements = [
  // (the comment only; ASCII is fine here)
  { match: (l) => l.includes('explicitly froze'),
    text: '// explicitly froze ("do not touch those 27") - so it is REPORTED rather than gated: a few of those'
      + ' peak at 0.0 dB and' },
  { match: (l) => l.includes('third-party/i.test(readme)'),
    text: "check(/第三方|third-party/i.test(readme), 'README states the third-party/private-use licence note');" },
  { match: (l) => l.includes('voice-inventory:start') && l.includes('.test(readme),'),
    text: "check(/voice-inventory:start/.test(readme) && /第三方素材声明/.test(readme)," },
  { match: (l) => l.includes('/USEC/.test(readme)') && l.includes('/BEAR/.test(readme)'),
    text: "check(/USEC/.test(readme) && /BEAR/.test(readme) && /优质PMC/.test(readme)," },
];

for (let i = 0; i < lines.length; i++) {
  for (const replacement of replacements) {
    if (replacement.match(lines[i])) {
      if (lines[i] !== replacement.text) {
        lines[i] = replacement.text;
        fixed++;
      }
    }
  }
}

let text = lines.join('\n');
if (text.charCodeAt(0) === 0xFEFF) {
  text = text.slice(1);
  fixed++;
}
fs.writeFileSync(file, text, 'utf8');
console.log(`repaired ${fixed} line(s); wrote ${file}`);

// Report what non-ASCII is left, so the repair is checkable.
fs.readFileSync(file, 'utf8').split('\n').forEach((line, index) => {
  if (/[^\x00-\x7F]/.test(line)) {
    console.log(`  line ${index + 1}: ${line.trim()}`);
  }
});
