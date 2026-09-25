// Ogg/Vorbis header audit for the voice clips - no decoder needed, and no ffmpeg on this machine.
//
//   node tools/voice_report.js assets_source/voice/raw
//
// Reads, per file: channels, sample rate (from the Vorbis identification header, which stores both as
// plain little-endian integers) and duration (last Ogg page's granule position / sample rate). Exits
// non-zero when any file is NOT mono, because a stereo clip is not spatialised by OpenAL - it plays
// centred and audible everywhere, which is exactly the bug the mono requirement exists to prevent.
'use strict';
const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || path.join(__dirname, '..', 'assets_source', 'voice', 'raw');
const files = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.ogg')).sort();

let failures = 0;
console.log('file'.padEnd(34) + 'channels  sampleRate  seconds   bytes');
console.log('-'.repeat(78));
for (const file of files) {
  const buf = fs.readFileSync(path.join(dir, file));
  // Vorbis identification header: packet type 1 + "vorbis", then version(4), channels(1), rate(4).
  const marker = buf.indexOf(Buffer.from([0x01, 0x76, 0x6f, 0x72, 0x62, 0x69, 0x73]));
  let channels = -1;
  let rate = -1;
  if (marker >= 0) {
    channels = buf.readUInt8(marker + 11);
    rate = buf.readUInt32LE(marker + 12);
  }
  // Duration: the granule position of the final Ogg page header ("OggS").
  let granules = -1;
  for (let at = buf.lastIndexOf(Buffer.from('OggS')); at >= 0; at = buf.lastIndexOf(Buffer.from('OggS'), at - 1)) {
    granules = Number(buf.readBigInt64LE(at + 6));
    if (granules > 0) break;
  }
  const seconds = rate > 0 && granules > 0 ? granules / rate : -1;
  const ok = channels === 1 && rate > 0;
  if (!ok) failures++;
  console.log(`${(ok ? '  ' : '! ') + file}`.padEnd(34)
    + `${String(channels).padEnd(10)}${String(rate).padEnd(12)}${seconds.toFixed(2).padEnd(10)}${buf.length}`);
}
console.log('-'.repeat(78));
const mono = files.length - failures;
console.log(`${mono}/${files.length} file(s) are mono (a stereo clip would not be spatialised at all)`);
if (failures > 0) {
  console.log(`${failures} file(s) FAILED the mono/rate check - they need a decode+re-encode pass`);
  process.exit(1);
}
console.log('all voice clips are mono');
