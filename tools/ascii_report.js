// "Is the non-ASCII in this Java file inside a comment, or in real code?" - the question the ASCII gate
// needs answered, and the reason a plain "file must be ASCII" rule cannot be turned on yet.
//
//   node tools/ascii_report.js            # list every non-ASCII site, classified
//
// The classifier strips comments (block and line) byte-safely and then looks at what is left: anything
// still non-ASCII there is in code or in a string literal, and *that* is what must move into lang files.
// Comment-only occurrences are the ones a whitelist can carry.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');

function javaFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) javaFiles(full, out);
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

/** Removes block and line comments, preserving line structure so reported line numbers stay right. */
function stripComments(text) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    const two = text.slice(i, i + 2);
    if (two === '/*') {
      const end = text.indexOf('*/', i + 2);
      const stop = end < 0 ? text.length : end + 2;
      // keep the newlines so line numbers do not shift
      out += text.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
    } else if (two === '//') {
      const end = text.indexOf('\n', i);
      const stop = end < 0 ? text.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
    } else if (text[i] === '"') {
      // keep string literals in the output: non-ASCII inside one is exactly what must be found
      let j = i + 1;
      while (j < text.length && text[j] !== '"') {
        if (text[j] === '\\') j++;
        j++;
      }
      out += text.slice(i, j + 1);
      i = j + 1;
    } else {
      out += text[i];
      i++;
    }
  }
  return out;
}

const codeHits = [];
const commentHits = [];
for (const file of javaFiles(SRC)) {
  const relative = path.relative(ROOT, file);
  const text = fs.readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  const stripped = stripComments(text).split(/\r?\n/);
  lines.forEach((line, index) => {
    if (!/[^\x00-\x7F]/.test(line)) return;
    const characters = [...new Set((line.match(/[^\x00-\x7F]/g) || []))].join('');
    const inCode = /[^\x00-\x7F]/.test(stripped[index] || '');
    const entry = { file: relative, line: index + 1, characters, text: line.trim() };
    (inCode ? codeHits : commentHits).push(entry);
  });
}

const show = (entry) => `  ${entry.file}:${entry.line}  [${entry.characters}]  ${entry.text.slice(0, 100)}`;
console.log(`non-ASCII in CODE or STRING LITERALS: ${codeHits.length}`);
codeHits.forEach((entry) => console.log(show(entry)));
console.log('');
console.log(`non-ASCII only inside COMMENTS: ${commentHits.length}`);
commentHits.forEach((entry) => console.log(show(entry)));
console.log('');
const files = new Set([...codeHits, ...commentHits].map((entry) => entry.file));
console.log(`${files.size} file(s) affected in total`);
process.exit(codeHits.length > 0 ? 1 : 0);
