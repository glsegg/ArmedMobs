// Analyses the YSM Scav model source and prints everything needed to wire it into GeckoLib:
//
//   node tools/analyze_scav_model.js [assets_source/scav]
//
// * the bone tree, with cube counts and bounding boxes, marked upper body / lower body / root
// * the RightHand subtree in detail, so the "hand props" (water bucket, board, gun3...) can be told
//   apart from real body/clothing layers
// * every animation, with its length, loop mode, and how many bones it touches in each body region -
//   which is what decides whether a clip can drive the lower body (movement) or the upper body
//   (gun handling) without the two fighting over the same bone
// * a suggested hidden-bone list for the reference props
const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || 'assets_source/scav';
const geoFile = path.join(dir, 'models/main.json');
const geo = JSON.parse(fs.readFileSync(geoFile, 'utf8'));
const bones = geo['minecraft:geometry'][0].bones;
const byName = new Map(bones.map((b) => [b.name, b]));
const children = new Map();
for (const b of bones) {
  if (!children.has(b.parent)) children.set(b.parent, []);
  children.get(b.parent).push(b);
}

/** Everything under the given bone (inclusive). */
function subtree(name) {
  const out = new Set();
  const walk = (n) => {
    out.add(n);
    for (const kid of children.get(n) || []) walk(kid.name);
  };
  if (byName.has(name)) walk(name);
  return out;
}

const upper = subtree('UpBody');
const lower = subtree('DownBody');
const spine = new Set(['Root', 'MAllBody', 'AllBody', ...upper, ...lower]);

/** Union bounding box of a bone's own cubes plus its children's, in model space. */
function bounds(name) {
  const box = { x1: Infinity, y1: Infinity, z1: Infinity, x2: -Infinity, y2: -Infinity, z2: -Infinity };
  let cubes = 0;
  for (const n of subtree(name)) {
    for (const cube of byName.get(n)?.cubes || []) {
      cubes++;
      box.x1 = Math.min(box.x1, cube.origin[0]);
      box.y1 = Math.min(box.y1, cube.origin[1]);
      box.z1 = Math.min(box.z1, cube.origin[2]);
      box.x2 = Math.max(box.x2, cube.origin[0] + cube.size[0]);
      box.y2 = Math.max(box.y2, cube.origin[1] + cube.size[1]);
      box.z2 = Math.max(box.z2, cube.origin[2] + cube.size[2]);
    }
  }
  const size = (a, b) => (Number.isFinite(a) ? +(b - a).toFixed(2) : 0);
  return { cubes, w: size(box.x1, box.x2), h: size(box.y1, box.y2), d: size(box.z1, box.z2), box };
}

function region(name) {
  if (upper.has(name)) return 'UPPER';
  if (lower.has(name)) return 'LOWER';
  return 'ROOT';
}

console.log(`# ${geoFile}`);
console.log(`bones: ${bones.length}\n`);

console.log('## bone tree (U = upper body, L = lower body)');
const walkTree = (bone, depth) => {
  const own = (bone.cubes || []).length;
  const sub = bounds(bone.name);
  const t = [];
  if (bone.rotation && bone.rotation.some((v) => v)) t.push(`rot=${JSON.stringify(bone.rotation)}`);
  if (bone.pivot) t.push(`pivot=${JSON.stringify(bone.pivot.map((v) => +v.toFixed(2)))}`);
  const tag = `${region(bone.name).padEnd(5)}`;
  console.log(
    `  ${'  '.repeat(depth)}${tag} ${bone.name}` +
      (own ? ` [own:${own}]` : '') +
      (sub.cubes && !own ? ` [sub:${sub.cubes}]` : '') +
      (sub.cubes ? ` box=${sub.w}x${sub.h}x${sub.d}` : '') +
      (t.length ? '  ' + t.join(' ') : '')
  );
  for (const kid of children.get(bone.name) || []) walkTree(kid, depth + 1);
};
for (const root of bones.filter((b) => !b.parent)) walkTree(root, 0);

console.log('\n## RightHand subtree (the reference props live here)');
const rightHand = 'RightHand';
const rhBox = bounds(rightHand);
console.log(`RightHand chain: ${(() => {
  const chain = [];
  let current = byName.get(rightHand);
  while (current) {
    chain.unshift(current.name);
    current = current.parent ? byName.get(current.parent) : null;
  }
  return chain.join(' -> ');
})()}`);
for (const kid of children.get(rightHand) || []) {
  const b = bounds(kid.name);
  console.log(
    `  ${kid.name.padEnd(20)} parent=RightHand  pivot=${JSON.stringify(kid.pivot)}  own=${(kid.cubes || []).length} cubes  subtree=${b.cubes} cubes  box=${b.w}x${b.h}x${b.d}  children=[${(children.get(kid.name) || []).map((c) => c.name).join(',')}]`
  );
}
console.log(`  (RightHand itself: ${(byName.get(rightHand).cubes || []).length} cube(s), box ${rhBox.w}x${rhBox.h}x${rhBox.d})`);

console.log('\n## gun / holster / hand locator bones');
for (const b of bones) {
  if (/gun|locator|weapon|hand|dmz/i.test(b.name)) {
    const chain = [];
    let current = b;
    while (current) {
      chain.unshift(current.name);
      current = current.parent ? byName.get(current.parent) : null;
    }
    console.log(
      `  ${b.name.padEnd(18)} region=${region(b.name).padEnd(5)} pivot=${JSON.stringify(b.pivot)} own=${(b.cubes || []).length} cubes\n      ${chain.join(' -> ')}`
    );
  }
}

console.log('\n## animations');
const files = fs.readdirSync(path.join(dir, 'animations')).filter((f) => f.endsWith('.json')).sort();
const all = new Map();
for (const file of files) {
  const json = JSON.parse(fs.readFileSync(path.join(dir, 'animations', file), 'utf8'));
  for (const [name, anim] of Object.entries(json.animations || {})) {
    all.set(name, { ...anim, file });
  }
}

const trackTimes = (track) => {
  if (!track) return [];
  if (Array.isArray(track)) return track.map((kf) => (typeof kf === 'number' ? 0 : kf.time || 0));
  return Object.keys(track).map(Number);
};

const rows = [];
for (const [name, anim] of all) {
  const touched = Object.keys(anim.bones || {});
  let length = 0;
  for (const channels of Object.values(anim.bones || {})) {
    for (const channel of ['rotation', 'position', 'scale']) {
      for (const t of trackTimes(channels[channel])) length = Math.max(length, t);
    }
  }
  const regionCount = { UPPER: 0, LOWER: 0, ROOT: 0 };
  for (const bone of touched) regionCount[region(bone)]++;
  let kind = 'none';
  if (regionCount.UPPER && regionCount.LOWER) kind = 'MIXED';
  else if (regionCount.UPPER) kind = 'upper-only';
  else if (regionCount.LOWER) kind = 'lower-only';
  else if (regionCount.ROOT) kind = 'root-only';
  rows.push({
    name,
    file: anim.file,
    length: Number.isFinite(anim.animation_length) ? anim.animation_length : +length.toFixed(4),
    loop: anim.loop === undefined ? '(default)' : String(anim.loop),
    bones: touched.length,
    kind,
    regionCount,
    touched,
  });
}

const filter = process.argv[3];
rows.sort((a, b) => a.file.localeCompare(b.file) || a.name.localeCompare(b.name));
console.log(`total animations: ${rows.length} (in ${files.length} files)`);
console.log(
  '  ' +
    ['file', 'name', 'len', 'loop', 'bones', 'kind'].map((h, i) => h.padEnd([22, 34, 9, 20, 6, 11][i])).join('')
);
for (const row of rows) {
  if (filter && !row.name.includes(filter) && !row.file.includes(filter)) continue;
  console.log(
    '  ' +
      [
        row.file.replace('.animation.json', '').padEnd(22),
        row.name.padEnd(34),
        String(row.length).padEnd(9),
        row.loop.padEnd(20),
        String(row.bones).padEnd(6),
        row.kind.padEnd(11),
      ].join('') +
      `  U:${row.regionCount.UPPER} L:${row.regionCount.LOWER} R:${row.regionCount.ROOT}`
  );
}

// Which clips touch a bone we care about, e.g. the gun anchor.
const probe = process.argv[4];
if (probe) {
  console.log(`\n## animations touching "${probe}"`);
  for (const row of rows) {
    if (row.touched.includes(probe)) {
      console.log(`  ${row.file.padEnd(24)} ${row.name}`);
    }
  }
}

console.log('\n## non-animated bones (never touched by any clip)');
const touchedAnywhere = new Set();
for (const row of rows) for (const b of row.touched) touchedAnywhere.add(b);
const untouched = bones.map((b) => b.name).filter((n) => !touchedAnywhere.has(n));
console.log(`  ${untouched.join(', ') || '(none)'}`);

console.log('\n## suggested hidden list (reference props in the right hand)');
const PROPS = ['Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money', 'Gun3', 'bone3'];
const suggested = PROPS.filter((n) => byName.has(n));
console.log(`  ${suggested.join(', ')}`);
console.log('  protected (never hidden): RightHand, RightHandDMZ, RightForeArm, bone52, LeftHand, LeftHandDMZ, LeftForeArm, + all UPPER/LOWER body bones');
void spine;
