// Dumps every bone of a Bedrock geo file with the facts needed to classify head accessories:
// parent chain, rest pivot, rest rotation, own cube count, the AABB of the bone's own cubes
// (bone-local units) and the union of the texture rectangles its cubes sample.
//
//   node tools/scan_accessories.js <file.geo.json> [nameFilterRegex]
//
// The texture-rect union matters because a hat/helmet samples a compact patch of the texture while
// a face layer samples a wide, flat one; the pivot and the AABB matter because they say where the
// piece actually sits relative to the head bone.
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node tools/scan_accessories.js <file.geo.json> [nameFilterRegex]');
  process.exit(2);
}
const filter = process.argv[3] ? new RegExp(process.argv[3], 'i') : null;

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const geo = json['minecraft:geometry'][0];
const desc = geo.description || {};
const bones = geo.bones || [];
const byName = new Map(bones.map((b) => [b.name, b]));
const children = new Map();
for (const b of bones) {
  if (!children.has(b.parent)) children.set(b.parent, []);
  children.get(b.parent).push(b.name);
}

const chain = (b) => {
  const out = [];
  let cur = b;
  const seen = new Set();
  while (cur) {
    if (seen.has(cur.name)) break;
    seen.add(cur.name);
    out.push(cur.name);
    cur = cur.parent ? byName.get(cur.parent) : null;
  }
  return out.reverse();
};

const fmt = (v) => (v ? `[${v.map((n) => (Math.round(n * 1000) / 1000)).join(', ')}]` : '-');

// The cube's extent in bone-local space, before the bone's own pivot/rotation: Bedrock stores
// origin = the minimum corner of the cube in model space and size = its dimensions.
const aabb = (c) => {
  if (!c.origin || !c.size) return null;
  const lo = c.origin.map((n, i) => Math.min(n, n + c.size[i]));
  const hi = c.origin.map((n, i) => n + c.size[i]).map((n, i) => Math.max(n, c.origin[i]));
  return { lo, hi };
};

const union = (a, b) => (!a ? b : !b ? a : {
  lo: a.lo.map((n, i) => Math.min(n, b.lo[i])),
  hi: a.hi.map((n, i) => Math.max(n, b.hi[i])),
});

// UV is either [u,v] with size-derived extent, or a per-face object; both are handled by pulling
// every number out and taking the min/max pair.
const uvRect = (c) => {
  if (!c.uv) return null;
  if (!Array.isArray(c.uv)) return { perFace: true, uvs: Object.values(c.uv) };
  const w = c.size ? c.size : [0, 0, 0];
  const u = c.uv[0];
  const v = c.uv[1];
  return { perFace: false, lo: [u, v], hi: [u + w[0] + w[2], v + w[2] + w[1]] };
};

console.log(`${file}: ${bones.length} bones, texture ${desc.texture_width}x${desc.texture_height}`);
for (const b of bones) {
  if (filter && !filter.test(b.name)) continue;
  let box = null;
  let uv = null;
  let uvCount = 0;
  for (const c of b.cubes || []) {
    box = union(box, aabb(c));
    const r = uvRect(c);
    if (r && !r.perFace) { uv = union(uv, r); uvCount++; }
  }
  console.log(`\n${b.name}`);
  console.log(`  parent   : ${b.parent || '(root)'}`);
  console.log(`  chain    : ${chain(b).join(' > ')}`);
  console.log(`  pivot    : ${fmt(b.pivot)}`);
  console.log(`  restRot  : ${fmt(b.rotation)}`);
  console.log(`  scale    : ${fmt(b.scale)}`);
  console.log(`  cubes    : ${(b.cubes || []).length}`);
  console.log(`  cubeAABB : ${box ? `${fmt(box.lo)} .. ${fmt(b.hi)}` : '-'}`);
  console.log(`  uvRect   : ${uv ? `${fmt(uv.lo)} .. ${fmt(uv.hi)} (${uvCount} cube(s) with a rectangular uv)` : '-'}`);
  console.log(`  subtree  : ${(children.get(b.name) || []).length ? (children.get(b.name) || []).join(' ') : '-'}`);
}
