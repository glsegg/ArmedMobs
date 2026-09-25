// Scratch: where does the docx converter leave raw markdown behind?
// Reports table cells / paragraph lines whose inline markers do not balance, which is exactly what
// shows up as a literal ` or ** in the Word output.
const fs = require('fs');
const p = 'D:\\deepseek\\ArmedMobs\\docs\\COMMAND_AND_CONFIG_REFERENCE.md';
const lines = fs.readFileSync(p, 'utf8').split('\n');

const badCell = [];
const badLine = [];
let rowNo = 0;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  if (/^\s*\|/.test(line)) {
    rowNo += 1;
    if (/^\|[\s:|-]+\|$/.test(line.trim())) continue;
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/);
    cells.forEach((cell, c) => {
      const ticks = (cell.match(/`/g) || []).length;
      const stars = (cell.match(/\*\*/g) || []).length;
      if (ticks % 2 === 1 || stars % 2 === 1) {
        badCell.push(`row ${rowNo} col ${c + 1}: ticks=${ticks} stars=${stars} :: ${cell.trim().slice(0, 110)}`);
      }
    });
    continue;
  }
  if (/^\s*(#|>|-{3,})/.test(line) || !line.trim()) continue;
  const ticks = (line.match(/`/g) || []).length;
  const stars = (line.match(/\*\*/g) || []).length;
  if (ticks % 2 === 1 || stars % 2 === 1) {
    badLine.push(`line ${i + 1}: ticks=${ticks} stars=${stars} :: ${line.trim().slice(0, 110)}`);
  }
}

console.log(`unbalanced table cells: ${badCell.length}`);
badCell.slice(0, 12).forEach((s) => console.log('  ' + s));
console.log(`unbalanced other lines: ${badLine.length}`);
badLine.slice(0, 12).forEach((s) => console.log('  ' + s));
