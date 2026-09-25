// One-shot evidence check for the aim-tracking fix: which bones does each clip actually animate?
//
// RigSupport.applyAimTracking writes UpperBody. If any clip animated that bone, GeckoLib would rewrite
// it from keyframes every frame and there would be nothing to accumulate; the bug exists precisely
// because the clips animate its parent UpBody instead. This prints that, so the claim is checkable.
//
//   node tools/list_animated_bones.js <file.animation.json> [boneName]
const fs = require('fs');

const file = process.argv[2];
const wanted = process.argv[3] || 'UpperBody';
const json = JSON.parse(fs.readFileSync(file, 'utf8'));

const all = new Set();
for (const [anim, a] of Object.entries(json.animations || {})) {
  const bones = Object.keys(a.bones || {});
  bones.forEach((b) => all.add(b));
  const flag = bones.includes(wanted) ? `  <-- ANIMATES ${wanted}` : '';
  console.log(`${anim.padEnd(20)} ${bones.length} bone(s): ${bones.join(' ')}${flag}`);
}

console.log(`\ndistinct animated bones across ${Object.keys(json.animations || {}).length} clips: ${all.size}`);
console.log(`${wanted} is animated by ${all.has(wanted) ? 'at least one clip' : 'NO clip'}`);
const parents = ['UpBody', 'AllBody', 'Head', 'Head3', 'AllHead'];
for (const p of parents) console.log(`  ${p.padEnd(10)} animated: ${all.has(p)}`);
