// Cover-seek speed, measured from the server log - the only honest way to report a
// PathNavigation#moveTo speedModifier without a client (it multiplies the mob's MOVEMENT_SPEED
// attribute, and what that means in blocks/second depends on the path the mob actually took).
//
//   node tools/spike/speed_from_log.js <log-or-telemetry-file> [...]
//
// The `[gunai]` telemetry line carries `pos`, `ticks` (ticks in the current state) and `stateDist`
// (horizontal blocks travelled in the current state), so any single line yields an exact
// blocks/second figure for that state; the largest `ticks` sample per state is the least noisy one.
'use strict';
const fs = require('fs');

const LINE = /\[gunai\].*? (\w+) dist=.*? pos=\(([-\d.]+),([-\d.]+),([-\d.]+)\) shots=(\d+) ticks=(\d+) stateDist=([-\d.]+)/;

function parse(file) {
  const per = {};
  let lines = 0;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = LINE.exec(line);
    if (!m) continue;
    lines++;
    const state = m[1];
    const ticks = Number(m[6]);
    const moved = Number(m[7]);
    if (ticks <= 0) continue;
    const speed = moved / (ticks / 20); // blocks per second
    // Keep the sample with the most ticks in the state: longest run, least boundary noise.
    if (!per[state] || ticks > per[state].ticks) per[state] = { ticks, moved, speed };
  }
  return { per, lines };
}

const files = process.argv.slice(2);
const results = [];
for (const file of files) {
  const { per, lines } = parse(file);
  console.log(`${file}  (${lines} telemetry lines)`);
  const states = Object.keys(per).sort();
  if (!states.length) console.log('  no parseable telemetry');
  for (const state of states) {
    const s = per[state];
    console.log(`  ${state.padEnd(11)} best sample: ${s.moved.toFixed(2)} blocks over ${s.ticks} ticks`
      + ` = ${s.speed.toFixed(2)} blocks/s`);
  }
  results.push(per);
  console.log('');
}

const SHARED = ['REPOSITION', 'RELOAD', 'RETREAT'];
if (results.length === 2) {
  console.log('speed ratio (second file / first file), per shared state:');
  for (const state of SHARED) {
    const a = results[0][state];
    const b = results[1][state];
    if (!a || !b) { console.log(`  ${state.padEnd(11)} n/a`); continue; }
    console.log(`  ${state.padEnd(11)} ${a.speed.toFixed(2)} -> ${b.speed.toFixed(2)} blocks/s`
      + `  ratio ${(b.speed / a.speed).toFixed(3)}`);
  }
}
