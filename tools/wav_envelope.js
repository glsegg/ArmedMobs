// Prints the loudness envelope of a mono 16-bit WAV and the onsets in it, so a sound can be described
// without ears: "one impact at t=0.12 s, decay by 0.9 s" is checkable, "it sounds like a bounce" is not.
//
//   node tools/wav_envelope.js <file.wav> [windowMs] [topN]
//
// Used for the delivery-11 impact sound (README 5v): the source video is a 21 s compilation, so the tool is
// what says WHERE the events are and how many there are in a given window.
'use strict';
const fs = require('fs');

const file = process.argv[2];
const windowMs = Number(process.argv[3] || 25);
const topN = Number(process.argv[4] || 12);
if (!file) {
  console.error('usage: node tools/wav_envelope.js <file.wav> [windowMs] [topN]');
  process.exit(2);
}

const buf = fs.readFileSync(file);
if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') {
  console.error('not a RIFF/WAVE file');
  process.exit(1);
}
let at = 12;
let fmt = null;
let data = null;
while (at + 8 <= buf.length) {
  const id = buf.toString('ascii', at, at + 4);
  const size = buf.readUInt32LE(at + 4);
  const body = at + 8;
  if (id === 'fmt ') {
    fmt = { channels: buf.readUInt16LE(body + 2), rate: buf.readUInt32LE(body + 4),
      bits: buf.readUInt16LE(body + 14) };
  } else if (id === 'data') {
    data = buf.subarray(body, Math.min(body + size, buf.length));
  }
  at = body + size + (size % 2);
}
if (!fmt || !data) {
  console.error('missing fmt or data chunk');
  process.exit(1);
}
if (fmt.channels !== 1 || fmt.bits !== 16) {
  console.error(`expected mono 16-bit, got ${fmt.channels}ch ${fmt.bits}bit`);
  process.exit(1);
}

const samples = new Int16Array(data.buffer, data.byteOffset, Math.floor(data.length / 2));
const perWindow = Math.max(1, Math.round((fmt.rate * windowMs) / 1000));
const windows = [];
for (let i = 0; i + perWindow <= samples.length; i += perWindow) {
  let sum = 0;
  let peak = 0;
  for (let j = i; j < i + perWindow; j++) {
    const v = samples[j] / 32768;
    sum += v * v;
    peak = Math.max(peak, Math.abs(v));
  }
  const rms = Math.sqrt(sum / perWindow);
  windows.push({ t: i / fmt.rate, rms, db: 20 * Math.log10(Math.max(1e-6, rms)),
    peakDb: 20 * Math.log10(Math.max(1e-6, peak)) });
}
const total = samples.length / fmt.rate;
const loudest = windows.reduce((a, b) => (b.rms > a.rms ? b : a), windows[0]);
const floor = windows.reduce((a, b) => (b.rms < a.rms ? b : a), windows[0]);
console.log(`file      : ${file}`);
console.log(`format    : ${fmt.channels}ch ${fmt.rate}Hz ${fmt.bits}bit, ${total.toFixed(2)}s`);
console.log(`loudest   : ${loudest.db.toFixed(1)} dBFS at ${loudest.t.toFixed(2)}s (peak ${loudest.peakDb.toFixed(1)} dBFS)`);
console.log(`quietest  : ${floor.db.toFixed(1)} dBFS at ${floor.t.toFixed(2)}s`);
console.log(`window    : ${windowMs} ms (${windows.length} windows)`);

// Onsets: a window at least 8 dB above the running floor and at least 6 dB above the window before it.
const onsets = [];
for (let i = 1; i < windows.length; i++) {
  const w = windows[i];
  if (w.db > floor.db + 8 && w.db > windows[i - 1].db + 6) {
    if (onsets.length === 0 || w.t - onsets[onsets.length - 1].t > 0.08) {
      onsets.push(w);
    }
  }
}
console.log(`onsets    : ${onsets.length}`);
for (const o of onsets) {
  console.log(`  t=${o.t.toFixed(2)}s  ${o.db.toFixed(1)} dBFS`);
}

const top = [...windows].sort((a, b) => b.rms - a.rms).slice(0, topN).sort((a, b) => a.t - b.t);
console.log(`top ${top.length} window(s) by level:`);
for (const w of top) {
  console.log(`  t=${w.t.toFixed(2)}s  ${w.db.toFixed(1)} dBFS  peak ${w.peakDb.toFixed(1)}`);
}
