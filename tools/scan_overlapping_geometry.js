// Finds geometry that is drawn twice in the same place - the one failure mode that makes a model
// render as "a jumble of blocks from certain angles" without any UV being wrong.
//
//   node tools/scan_overlapping_geometry.js <file.geo.json> [hideList...]
//
// Two tests are reported:
//   * CONTAINS - one cube's box (nearly) swallows another cube's box while belonging to a different
//     bone. That is a shell drawn on top of a body part, and it is the classic z-fighting pair.
//   * COPLANAR - two different bones keep a face (nearly) in the same plane; the depth buffer then
//     picks a winner per pixel and per camera angle, so the surface flickers between two UVs.
//
// Default hide list is the shipped client.hiddenBones value, because a hidden bone cannot fight with
// anything. Pass an explicit list to override, or "-" for "nothing is hidden".
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node tools/scan_overlapping_geometry.js <file.geo.json> [hideList... | -]');
  process.exit(2);
}
const DEFAULT_HIDDEN = ['Gun3', 'Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money'];
const hidden = process.argv.length > 3
  ? (process.argv[3] === '-' ? [] : process.argv.slice(3))
  : DEFAULT_HIDDEN;

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const geo = json['minecraft:geometry'][0];
const bones = geo.bones || [];
const byName = new Map(bones.map((b) => [b.name, b]));

const parent = new Map(bones.map((b) => [b.name, b.parent]));
const under = (name, root) => {
  let cur = name;
  while (cur) {
    if (cur === root) return true;
    cur = parent.get(cur);
  }
  return false;
};
const isHidden = (name) => hidden.some((h) => under(name, h));
const side = (name) => {
  const chain = [];
  let cur = name;
  while (cur) {
    chain.push(cur);
    cur = parent.get(cur);
  }
  const inHead = chain.includes('Head') || chain.includes('AllHead');
  const inArms = chain.includes('Arm');
  const inLegs = chain.includes('Leg') || chain.includes('DownBody');
  if (inHead) return 'head';
  if (inArms) return 'arm';
  if (inLegs) return 'leg';
  return 'torso';
};

const box = (c) => {
  const lo = c.origin.map((n, i) => Math.min(n, n + c.size[i]));
  const hi = c.origin.map((n, i) => Math.max(n, n + c.size[i]));
  return { lo, hi };
};
const volume = (b) => (b.hi[0] - b.lo[0]) * (b.hi[1] - b.lo[1]) * (b.hi[2] - b.lo[2]);
const overlap = (a, b) => {
  const lo = a.lo.map((n, i) => Math.max(n, b.lo[i]));
  const hi = a.hi.map((n, i) => Math.min(n, b.hi[i]));
  return volume({ lo, hi: hi.map((n, i) => Math.max(n, lo[i])) });
};
const fmt = (v) => `[${v.map((n) => Math.round(n * 100) / 100).join(', ')}]`;

// Every cube, with the bone it belongs to, skipping anything a hidden ancestor removes.
const cubes = [];
for (const bone of bones) {
  if (isHidden(bone.name)) continue;
  (bone.cubes || []).forEach((c, index) => cubes.push({ bone: bone.name, index, box: box(c), size: c.size, origin: c.origin }));
}
console.log(`${file}: ${cubes.length} rendered cube(s) after hiding [${hidden.join(' ')}]`);

let contains = 0;
let coplanar = 0;
const EPS = 0.02;
for (let i = 0; i < cubes.length; i++) {
  for (let j = i + 1; j < cubes.length; j++) {
    const a = cubes[i];
    const b = cubes[j];
    if (a.bone === b.bone) continue;
    const vol = overlap(a.box, b.box);
    if (vol <= 0) continue;
    const smaller = Math.min(volume(a.box), volume(b.box));
    // A near-total overlap inside a different bone is a duplicated shell.
    if (smaller > 0 && vol / smaller > 0.85) {
      contains++;
      console.log(`  CONTAINS ${a.bone}[${a.index}] ${side(a.bone)} ${fmt(a.box.lo)}..${fmt(a.box.hi)}`);
      console.log(`        by ${b.bone}[${b.index}] ${side(b.bone)} ${fmt(b.box.lo)}..${fmt(b.box.hi)} (${(vol / smaller * 100).toFixed(0)}% of the smaller box)`);
      continue;
    }
    // Otherwise: do two faces share a plane and overlap in the other two axes?
    for (let axis = 0; axis < 3; axis++) {
      const others = [0, 1, 2].filter((k) => k !== axis);
      const shareLo = Math.abs(a.box.lo[axis] - b.box.lo[axis]) < EPS;
      const shareHi = Math.abs(a.box.hi[axis] - b.box.hi[axis]) < EPS;
      if (!shareLo && !shareHi) continue;
      const overlapArea = others.every((k) => Math.min(a.box.hi[k], b.box.hi[k]) - Math.max(a.box.lo[k], b.box.lo[k]) > 0.25);
      if (!overlapArea) continue;
      coplanar++;
      console.log(`  COPLANAR axis=${'xyz'[axis]}${shareLo ? '-' : '+'} ${a.bone}[${a.index}] ${side(a.bone)} ${fmt(a.box.lo)}..${fmt(a.box.hi)}`
        + `  ||  ${b.bone}[${b.index}] ${side(b.bone)} ${fmt(b.box.lo)}..${fmt(b.box.hi)}`);
    }
  }
}
console.log(`  --- ${contains} contained (duplicated shell) pair(s), ${coplanar} coplanar face pair(s) ---`);
