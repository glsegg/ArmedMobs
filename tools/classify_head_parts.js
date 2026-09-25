// Classifies the bones of the scav rig that sit on or around the head, so the hat / eye-gear /
// cigarette accessory groups can be decided from data instead of from the bone names.
//
//   node tools/classify_head_parts.js <file.geo.json> [animation.json]
//
// For every descendant of the head group it prints:
//   * how deep it hangs under the head and its rest pivot / rotation;
//   * the AABB of its own cubes and of its whole subtree, in model space (Bedrock model units,
//     y up, and on this rig the head top is at y ~= 36);
//   * how many cubes it has and which texture rectangles they sample (per-face UV union);
//   * whether any clip in the animation file writes that bone (an animated "hat" is really a prop);
//   * whether its texture patch is a compact block (a hat shell samples one square region) or a
//     scattered set of small rectangles (face layers, straps, lenses).
const fs = require('fs');

const file = process.argv[2];
const animFile = process.argv[3];
if (!file) {
  console.error('usage: node tools/classify_head_parts.js <file.geo.json> [animation.json]');
  process.exit(2);
}

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const geo = json['minecraft:geometry'][0];
const bones = geo.bones || [];
const byName = new Map(bones.map((b) => [b.name, b]));
const children = new Map();
for (const b of bones) {
  if (!children.has(b.parent)) children.set(b.parent, []);
  children.get(b.parent).push(b.name);
}

// Which bones any animation clip writes, keyed by bone name.
const animated = new Set();
if (animFile && fs.existsSync(animFile)) {
  const anims = JSON.parse(fs.readFileSync(animFile, 'utf8'));
  for (const clip of Object.values(anims.animations || {})) {
    for (const boneName of Object.keys(clip.bones || {})) animated.add(boneName);
  }
}

const round = (n) => Math.round(n * 100) / 100;
const fmt = (v) => `[${v.map(round).join(', ')}]`;

const cubeBox = (c) => ({
  lo: c.origin.map((n, i) => Math.min(n, n + c.size[i])),
  hi: c.origin.map((n, i) => Math.max(n, n + c.size[i])),
});
const merge = (a, b) => (a ? b ? {
  lo: a.lo.map((n, i) => Math.min(n, b.lo[i])),
  hi: a.hi.map((n, i) => Math.max(n, b.hi[i])),
} : a : b);

const ownBox = (b) => (b.cubes || []).reduce((acc, c) => merge(acc, cubeBox(c)), null);
const subtreeBox = (name) => {
  let box = ownBox(byName.get(name));
  for (const kid of children.get(name) || []) box = merge(box, subtreeBox(kid));
  return box;
};

// Per-face UV: every face carries {uv:[u,v], uv_size:[w,h]}. The union of those rectangles is the
// texture patch the bone draws from; its area and aspect say a lot about what the piece is.
const uvUnion = (b) => {
  let box = null;
  for (const c of b.cubes || []) {
    if (!c.uv) continue;
    const faces = Array.isArray(c.uv)
      ? [{ uv: c.uv, uv_size: [c.size[0] + c.size[2], c.size[2] + c.size[1]] }]
      : Object.values(c.uv);
    for (const f of faces) {
      if (!f || !f.uv) continue;
      const sz = f.uv_size || [0, 0];
      box = merge(box, {
        lo: [Math.min(f.uv[0], f.uv[0] + sz[0]), Math.min(f.uv[1], f.uv[1] + sz[1])],
        hi: [Math.max(f.uv[0], f.uv[0] + sz[0]), Math.max(f.uv[1], f.uv[1] + sz[1])],
      });
    }
  }
  return box;
};

// The head group is the bone named Head (child of AllHead on this rig); everything below it is a
// candidate accessory.
const HEAD_ROOTS = ['Head', 'Head3'];
const seenRoot = HEAD_ROOTS.find((n) => byName.has(n));
if (!seenRoot) {
  console.error(`${file}: no Head bone`);
  process.exit(2);
}

const descendants = [];
const walk = (name, depth, viaHead3) => {
  const b = byName.get(name);
  descendants.push({ bone: b, depth, viaHead3 });
  for (const kid of children.get(name) || []) walk(kid, depth + 1, viaHead3 || name === 'Head3');
};
for (const kid of children.get('Head') || []) walk(kid, 1, kid === 'Head3');
walk('Head', 0, false);

console.log(`${file}: head root '${seenRoot}', ${descendants.length} bone(s) at or under it`);
console.log('name            depth  ownCubes  pivot                       restRot                ownAABB                    subtreeAABB                animated');
const rows = descendants.slice().sort((a, b) => (b.bone.pivot ? b.bone.pivot[1] : 0) - (a.bone.pivot ? a.bone.pivot[1] : 0));
for (const row of rows) {
  const b = row.bone;
  const own = ownBox(b);
  const sub = subtreeBox(b.name);
  const uv = uvUnion(b);
  console.log(`${b.name.padEnd(15)} ${String(row.depth).padStart(5)}  ${String((b.cubes || []).length).padStart(8)}  `
    + `${(b.pivot ? fmt(b.pivot) : '-').padEnd(26)} ${(b.rotation ? fmt(b.rotation) : '-').padEnd(22)} `
    + `${(own ? `${fmt(own.lo)}..${fmt(own.hi)}` : '-').padEnd(26)} `
    + `${(sub ? `${fmt(sub.lo)}..${fmt(sub.hi)}` : '-').padEnd(26)} `
    + `${animated.has(b.name) ? 'YES' : '-'}`);
  if (uv) console.log(`${''.padEnd(16)}uv patch ${fmt(uv.lo)}..${fmt(uv.hi)}  (${uv.hi[0] - uv.lo[0]}x${uv.hi[1] - uv.lo[1]} px)`);
}
