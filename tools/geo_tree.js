// Prints the bone tree of a GeckoLib geo file, so it is obvious which bone carries the body and
// which subtrees are accessories. Bones whose rest transform is not identity are marked.
//
//   node tools/geo_tree.js <file.geo.json> [rootBone] [maxDepth]
const fs = require('fs');

const file = process.argv[2];
const rootName = process.argv[3] || null;
const maxDepth = Number(process.argv[4] || 4);

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const bones = json['minecraft:geometry'][0].bones;
const byName = new Map(bones.map((b) => [b.name, b]));
const children = new Map();
for (const b of bones) {
  if (!children.has(b.parent)) children.set(b.parent, []);
  children.get(b.parent).push(b);
}

const transform = (b) => {
  const parts = [];
  if (b.rotation && b.rotation.some((v) => v)) parts.push(`rot=${JSON.stringify(b.rotation)}`);
  if (b.pivot && b.pivot.some((v) => v)) parts.push(`pivot=${JSON.stringify(b.pivot)}`);
  if (b.scale) parts.push(`scale=${JSON.stringify(b.scale)}`);
  return parts.join(' ');
};

const roots = rootName ? [byName.get(rootName)].filter(Boolean) : bones.filter((b) => !b.parent);
if (!roots.length) {
  console.error(`no such bone: ${rootName}`);
  process.exit(1);
}

const walk = (bone, depth) => {
  const indent = '  '.repeat(depth);
  const t = transform(bone);
  console.log(`${indent}${bone.name}${t ? '   ' + t : ''}`);
  if (depth >= maxDepth) {
    const kids = children.get(bone.name) || [];
    if (kids.length) console.log(`${indent}  ... ${kids.length} child bone(s)`);
    return;
  }
  for (const kid of children.get(bone.name) || []) walk(kid, depth + 1);
};

console.log(`${file}: ${bones.length} bones`);
for (const r of roots) walk(r, 0);
