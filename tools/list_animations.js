// Lists the animations inside a GeckoLib animation file, with their length, loop mode and the
// bones they touch. Handles both keyframe spellings GeckoLib accepts:
//   "rotation": { "0.0": {...}, "0.5": {...} }   (map form)
//   "rotation": [ { "time": 0.0, ... }, ... ]    (array form)
//
//   node tools/list_animations.js <file.animation.json> [--bones]
const fs = require('fs');

const file = process.argv[2];
const showBones = process.argv.includes('--bones');
if (!file) {
  console.error('usage: node tools/list_animations.js <file.animation.json> [--bones]');
  process.exit(1);
}

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const animations = json.animations || {};

const trackTimes = (track) => {
  if (!track) return [];
  if (Array.isArray(track)) return track.map((kf) => (typeof kf === 'number' ? 0 : kf.time || 0));
  return Object.keys(track).map(Number);
};

const rows = [];
for (const [name, anim] of Object.entries(animations)) {
  const bones = anim.bones || {};
  let length = 0;
  let keyframes = 0;
  for (const [bone, channels] of Object.entries(bones)) {
    for (const channel of ['rotation', 'position', 'scale']) {
      const times = trackTimes(channels[channel]);
      keyframes += times.length;
      for (const t of times) length = Math.max(length, t);
    }
  }
  rows.push({
    name,
    length: Number.isFinite(anim.animation_length) ? anim.animation_length : length,
    loop: anim.loop === undefined ? '(default)' : String(anim.loop),
    bones: Object.keys(bones).length,
    keyframes,
    boneNames: Object.keys(bones),
  });
}

rows.sort((a, b) => a.name.localeCompare(b.name));
console.log(`${file}: ${rows.length} animation(s)`);
for (const r of rows) {
  console.log(
    `  ${r.name.padEnd(30)} len=${String(r.length).padEnd(8)} loop=${r.loop.padEnd(9)} bones=${String(
      r.bones
    ).padEnd(4)} keys=${r.keyframes}`
  );
  if (showBones) console.log(`      ${r.boneNames.join(', ')}`);
}
