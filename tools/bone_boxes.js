// Reports, for a set of bones of a Bedrock geo file, where their meshes actually sit (bounding box
// built from the cubes, in the parent's space) and how many cubes they hold.
//
// Why: rigs often ship two variants of one part ("Hair" / "Hair2"). Comparing the boxes tells
// whether the second one is an alternative outfit (same place, same size -> hide one of them) or a
// layer/extension of the same part (different place -> both are needed).
//
//   node tools/bone_boxes.js <file.geo.json> <bone> [bone ...]
const fs = require('fs');

const file = process.argv[2];
const wanted = process.argv.slice(3);
if (!file || wanted.length === 0) {
  console.error('usage: node tools/bone_boxes.js <file.geo.json> <bone> [bone ...]');
  process.exit(1);
}

const geo = JSON.parse(fs.readFileSync(file, 'utf8'))['minecraft:geometry'][0];
const bones = geo.bones;
const byName = new Map(bones.map((b) => [b.name, b]));
const kids = new Map();
for (const b of bones) {
  if (!kids.has(b.parent)) kids.set(b.parent, []);
  kids.get(b.parent).push(b.name);
}

/** Union of the cube boxes, in the bone's own space (before its own pivot/rotation is applied). */
function localBox(bone) {
  const box = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  const add = (cube) => {
    const o = cube.origin || [0, 0, 0];
    const s = cube.size || [0, 0, 0];
    for (let i = 0; i < 3; i++) {
      const lo = Math.min(o[i], o[i] + s[i]);
      const hi = Math.max(o[i], o[i] + s[i]);
      box[i] = Math.min(box[i], lo);
      box[i + 3] = Math.max(box[i + 3], hi);
    }
  };
  if (bone.cubes) bone.cubes.forEach(add);
  for (const child of kids.get(bone.name) || []) {
    const b = byName.get(child);
    if (!b) continue;
    const pivot = b.pivot || [0, 0, 0];
    const bonePivot = bone.pivot || [0, 0, 0];
    const childBox = localBox(b);
    if (!Number.isFinite(childBox[0])) continue;
    // bring the child's box into this bone's space (ignoring rotations, good enough for "same place?")
    const offset = [0, 1, 2].map((i) => bonePivot[i] - pivot[i]);
    for (let i = 0; i < 3; i++) {
      box[i] = Math.min(box[i], childBox[i] + offset[i]);
      box[i + 3] = Math.max(box[i + 3], childBox[i + 3] + offset[i]);
    }
  }
  return box;
}

const fmt = (v) => (Number.isFinite(v) ? v.toFixed(1) : '?');
for (const name of wanted) {
  const bone = byName.get(name);
  if (!bone) {
    console.log(`${name.padEnd(16)} MISSING from this geo`);
    continue;
  }
  const box = localBox(bone);
  const cubes = bone.cubes ? bone.cubes.length : 0;
  const total = (() => {
    let n = cubes;
    for (const child of kids.get(name) || []) n += (byName.get(child)?.cubes || []).length;
    return n;
  })();
  console.log(
    `${name.padEnd(16)} cubes=${String(cubes).padEnd(3)} (+children=${String(total).padEnd(3)}) ` +
      `box=[${fmt(box[0])}..${fmt(box[3])}, ${fmt(box[1])}..${fmt(box[4])}, ${fmt(box[2])}..${fmt(box[5])}] ` +
      `parent=${String(bone.parent).padEnd(14)} pivot=${JSON.stringify(bone.pivot || [])}`
  );
}
