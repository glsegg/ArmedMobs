// Finds which class declares a member, by substring.
//   node tools/find_member.js <client_mappings.txt> <substring> [substring...]
const fs = require('fs');

const [, , mappingsPath, ...needles] = process.argv;
const lines = fs.readFileSync(mappingsPath, 'utf8').split(/\r?\n/);

let current = null;
let printedHeader = false;
for (const line of lines) {
  if (!line.startsWith('    ') && line.includes(' -> ')) {
    current = line.split(' -> ')[0].trim();
    printedHeader = false;
  } else if (current && line.startsWith('    ')) {
    for (const needle of needles) {
      if (line.includes(needle)) {
        if (!printedHeader) {
          console.log('=== ' + current);
          printedHeader = true;
        }
        console.log('    ' + line.trim().replace(/\s*->\s*\S+$/, ''));
      }
    }
  }
}
