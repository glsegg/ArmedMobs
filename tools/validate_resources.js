// Validates every resource the mod ships: JSON files must parse, lang files must have
// matching key sets, and PNG textures must have a valid header.
//
//   node tools/validate_resources.js
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'src', 'main', 'resources');

let errors = 0;
let checked = 0;

function walk(dir, filter, visit) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, filter, visit);
    else if (filter(entry.name)) visit(full);
  }
}

walk(assets, (name) => name.endsWith('.json'), (file) => {
  checked++;
  try {
    JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    errors++;
    console.log(`BAD JSON  ${path.relative(root, file)}: ${error.message}`);
  }
});

// lang key parity
const langDir = path.join(assets, 'assets', 'girlsfrontline', 'lang');
const langFiles = fs.readdirSync(langDir).filter((f) => f.endsWith('.json'));
if (langFiles.length >= 2) {
  const keys = {};
  for (const file of langFiles) {
    keys[file] = new Set(Object.keys(JSON.parse(fs.readFileSync(path.join(langDir, file), 'utf8'))));
  }
  const [first, ...rest] = langFiles;
  for (const other of rest) {
    for (const key of keys[first]) {
      if (!keys[other].has(key)) {
        errors++;
        console.log(`LANG GAP  ${key} present in ${first} but missing in ${other}`);
      }
    }
    for (const key of keys[other]) {
      if (!keys[first].has(key)) {
        errors++;
        console.log(`LANG GAP  ${key} present in ${other} but missing in ${first}`);
      }
    }
  }
}

walk(assets, (name) => name.endsWith('.png'), (file) => {
  checked++;
  const header = fs.readFileSync(file).subarray(0, 8);
  const expected = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!header.equals(expected)) {
    errors++;
    console.log(`BAD PNG   ${path.relative(root, file)}`);
  }
});

// dialogue files must only use known categories
const dialogueDir = path.join(assets, 'data', 'girlsfrontline', 'dialogue');
const known = new Set(['idle', 'interact', 'combat', 'hurt', 'sleep', 'farm', 'tame', 'low_health']);
if (fs.existsSync(dialogueDir)) {
  for (const file of fs.readdirSync(dialogueDir)) {
    const data = JSON.parse(fs.readFileSync(path.join(dialogueDir, file), 'utf8'));
    for (const key of Object.keys(data)) {
      if (!known.has(key)) {
        errors++;
        console.log(`DIALOGUE  unknown category '${key}' in ${file}`);
      }
      if (!Array.isArray(data[key]) || data[key].length === 0) {
        errors++;
        console.log(`DIALOGUE  category '${key}' in ${file} must be a non-empty array`);
      }
    }
  }
}

console.log(`\nchecked ${checked} file(s), ${errors} problem(s)`);
process.exit(errors === 0 ? 0 : 1);
