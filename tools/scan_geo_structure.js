// Structural self-test for a GeckoLib/Bedrock geo file. It answers the questions that a plain
// "are the animation numbers sane" scan cannot:
//
//   * bone graph integrity   - duplicate names, dangling parents, parent cycles, several roots;
//   * geometry sanity        - per-field limits on inflate/size/origin/pivot/rotation/uv, plus
//                              zero-size and very flat (billboard/plane-like) cubes, which is what
//                              a runaway or smeared quad looks like;
//   * unsupported geometry   - poly_mesh and per-face UV, which GeckoLib 4.8 silently drops;
//   * hidden-bone ancestry   - whether any name in a hide-list is an ancestor of a body bone,
//                              because hiding a bone hides its whole subtree.
//
// Exit code 1 when a hard problem is found, so it can be used as a gate.
//
//   node tools/scan_geo_structure.js <file.geo.json> [hide-list.json]
//
// The optional hide-list file is a JSON array of bone names (the config's hiddenBones value).
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node tools/scan_geo_structure.js <file.geo.json> [hide-list.json]');
  process.exit(2);
}

const HIDE_LIST = process.argv[3]
  ? JSON.parse(fs.readFileSync(process.argv[3], 'utf8'))
  : ['Gun3', 'Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money'];

// Bodies/clothing/locators that must never disappear with a hidden ancestor. Mirrors
// Config.PROTECTED_BONES.
const PROTECTED = new Set([
  'Root', 'MAllBody', 'AllBody', 'UpBody', 'DownBody', 'Body', 'UpperBody', 'Leg',
  'AllHead', 'Head', 'Head3', 'Glass', 'Eyes', 'Eyebrows', 'EyeBrowF',
  'EyeBrow_Left', 'EyeBrow_Right', 'EyeBalls', 'LeftEyeball', 'RightEyeball',
  'LeftIris', 'RightIris', 'YanJing', 'Yan', 'Ear', 'Hat1', 'Hat2', 'hat3',
  'Arm', 'RightArm', 'RightForeArm', 'bone52', 'RightHand', 'RightHandDMZ',
  'LeftArm', 'LeftForeArm', 'bone999', 'LeftHand', 'LeftHandDMZ',
  'fangdanyi_2', 'bone4', 'Bag',
  'LeftLeg', 'LeftLowerLeg', 'leftfoot', 'RightLeg', 'RightLowerLeg', 'rightfoot',
  'RightHandLocator', 'LeftHandLocator', 'RifleLocator', 'PistolLocator',
]);

const LIMITS = {
  inflate: 5,          // a few mm of padding is normal; more means a cube was scaled by hand
  size: 300,           // a torso cube is ~8 units
  origin: 300,         // model space is roughly -40..40 units
  pivot: 300,
  rotation: 180,       // Bedrock bone rotations stay well inside a full turn
  scale: 4,            // GeckoLib multiplies by this; the rig has no scale keys at all
  uv: 1024,            // generous: checked against the declared texture size below as well
};

const problems = [];
const notes = [];
const fail = (kind, msg) => problems.push({ kind, msg });
// A soft finding: worth printing and worth a human look, but it cannot stretch geometry to the sky and
// must not fail a build gate. The rig ships authored flat planes (eyeballs) and one negative-thickness
// prop plate, and they are fine.
const soft = (kind, msg) => notes.push(`${kind}: ${msg}`);

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
if (!json['minecraft:geometry']) {
  console.error(`${file}: no "minecraft:geometry" array - not a Bedrock geometry file`);
  process.exit(2);
}
if (json['minecraft:geometry'].length !== 1) {
  notes.push(`file declares ${json['minecraft:geometry'].length} geometry entries; GeckoLib renders the first`);
}

const geo = json['minecraft:geometry'][0];
const desc = geo.description || {};
const texW = Number(desc.texture_width || 64);
const texH = Number(desc.texture_height || 64);
const bones = geo.bones || [];

console.log(`${file}`);
console.log(`  format_version=${json.format_version}  identifier=${desc.identifier}`);
console.log(`  texture=${texW}x${texH}  visible_bounds=${desc.visible_bounds_width}x${desc.visible_bounds_height}`);
console.log(`  bones=${bones.length}  texture mesh=${geo.bones ? 'yes' : 'no'}`);

// ------------------------------------------------------------------ 1. bone graph
const byName = new Map();
const dupes = [];
for (const b of bones) {
  if (byName.has(b.name)) dupes.push(b.name);
  else byName.set(b.name, b);
}
if (dupes.length) fail('duplicate-bone', `duplicate bone name(s): ${JSON.stringify(dupes)}`);

const dangling = [];
for (const b of bones) {
  if (b.parent && !byName.has(b.parent)) dangling.push(`${b.name} -> ${b.parent}`);
}
if (dangling.length) fail('dangling-parent', `parent does not exist: ${JSON.stringify(dangling)}`);

// A cycle is a bone that can never reach a root by walking up its parents.
const cycles = [];
for (const b of bones) {
  const seen = new Set();
  let cur = b;
  while (cur && cur.parent) {
    if (seen.has(cur.name)) { cycles.push(b.name); break; }
    seen.add(cur.name);
    cur = byName.get(cur.parent);
    if (!cur) break;
  }
}
if (cycles.length) fail('parent-cycle', `bone(s) inside a parent cycle: ${JSON.stringify([...new Set(cycles)])}`);

const roots = bones.filter((b) => !b.parent);
if (roots.length !== 1) notes.push(`${roots.length} root bones (no parent): ${JSON.stringify(roots.map((r) => r.name))}`);

// poly_mesh / per-face UV: GeckoLib 4.8 does not build poly_mesh geometry at all, so anything
// stored in one is invisible in game (and a plugin that tries can smear it).
const polyBones = bones.filter((b) => b.poly_mesh);
if (polyBones.length) fail('poly_mesh', `${polyBones.length} bone(s) carry poly_mesh geometry, not rendered by GeckoLib: ${JSON.stringify(polyBones.map((b) => b.name))}`);
if (geo.poly_mesh) fail('poly_mesh', 'the geometry itself is a poly_mesh');

// ------------------------------------------------------------------ 2. cube geometry
let cubes = 0;
let perFaceUv = 0;
const flat = [];
for (const b of bones) {
  for (const c of b.cubes || []) {
    cubes++;
    const where = `${b.name}${c.uuid ? ' (uuid ' + c.uuid + ')' : ''}`;

    if (c.uv && !Array.isArray(c.uv)) perFaceUv++;
    if (c.uv && Array.isArray(c.uv)) {
      for (const n of c.uv) {
        if (typeof n === 'number' && Math.abs(n) > texW) fail('uv', `${where}: uv ${n} exceeds texture_width ${texW}`);
      }
    }

    if (c.inflate !== undefined && Math.abs(Number(c.inflate)) > LIMITS.inflate) {
      fail('inflate', `${where}: inflate=${c.inflate} (limit ${LIMITS.inflate})`);
    }
    for (const field of ['size', 'origin', 'pivot', 'rotation']) {
      const v = c[field];
      if (!v) continue;
      const lim = LIMITS[field];
      for (const n of v) {
        if (typeof n !== 'number' || !isFinite(n)) { fail('nan', `${where}: ${field} contains ${n}`); continue; }
        if (Math.abs(n) > lim) fail('range', `${where}: ${field}=${JSON.stringify(v)} exceeds ${lim} on axis ${n}`);
      }
    }

    const size = c.size;
    if (Array.isArray(size) && size.length === 3 && size.every((n) => typeof n === 'number')) {
      if (size.some((n) => n < 0)) soft('negative-size', `${where}: size=${JSON.stringify(size)} has a negative axis (an inside-out cube)`);
      const zero = size.filter((n) => Math.abs(n) < 1e-6).length;
      if (zero) soft('zero-size', `${where}: size=${JSON.stringify(size)} collapses ${zero} axis/axes (a flat plane)`);
      // A cube that is flat (one tiny axis) and wide (two big axes) is a plane/billboard: the
      // classic source of a giant translucent sheet when it is stretched.
      const maxAxis = Math.max(...size.map(Math.abs));
      const minAxis = Math.min(...size.map(Math.abs));
      if (minAxis > 0 && maxAxis / minAxis >= 20) flat.push(`${where}: size=${JSON.stringify(size)}`);
    }

    if (c.rotation && c.rotation.some((n) => Math.abs(n) > LIMITS.rotation)) {
      fail('rotation', `${where}: rotation=${JSON.stringify(c.rotation)} exceeds ${LIMITS.rotation}`);
    }
  }
}

// ------------------------------------------------------------------ 3. bone transforms
for (const b of bones) {
  if (b.scale) {
    const s = b.scale;
    for (const n of s) {
      if (typeof n !== 'number' || !isFinite(n)) fail('nan', `${b.name}: scale contains ${n}`);
      else if (Math.abs(n) > LIMITS.scale) fail('scale', `${b.name}: scale=${JSON.stringify(s)} exceeds ${LIMITS.scale}`);
      else if (Math.abs(n) < 1e-6) fail('scale-zero', `${b.name}: scale=${JSON.stringify(s)} has a zero axis (kills the subtree)`);
    }
  }
  if (b.rotation) {
    for (const n of b.rotation) {
      if (typeof n !== 'number' || !isFinite(n)) fail('nan', `${b.name}: rotation contains ${n}`);
    }
  }
  if (b.pivot === undefined) notes.push(`${b.name}: no pivot (Bedrock defaults it to 0,0,0)`);
}

if (flat.length) soft('flat-cube', `${flat.length} plane-like cube(s): ${JSON.stringify(flat.slice(0, 10))}`);

console.log(`  cubes=${cubes}  per-face-UV cubes=${perFaceUv}  empty (locator) bones=${bones.filter((b) => !b.cubes || !b.cubes.length).length}`);

// ------------------------------------------------------------------ 4. hidden-bone ancestry
const children = new Map();
for (const b of bones) {
  if (!children.has(b.parent)) children.set(b.parent, []);
  children.get(b.parent).push(b.name);
}
const under = (root, name) => {
  const stack = [...(children.get(root) || [])];
  const seen = new Set();
  while (stack.length) {
    const n = stack.pop();
    if (seen.has(n)) continue;
    seen.add(n);
    if (n === name) return true;
    stack.push(...(children.get(n) || []));
  }
  return false;
};

console.log('  hiddenBones ancestry:');
let ancestryHit = false;
for (const hide of HIDE_LIST) {
  if (!byName.has(hide)) { console.log(`    ${hide}: NOT IN THE RIG (config entry matches nothing)`); continue; }
  const subtree = [];
  const stack = [hide];
  while (stack.length) {
    const n = stack.pop();
    for (const kid of children.get(n) || []) { subtree.push(kid); stack.push(kid); }
  }
  const bad = subtree.filter((n) => PROTECTED.has(n) || Array.from(PROTECTED).some((p) => p.toLowerCase() === n.toLowerCase()));
  const count = (byName.get(hide).cubes || []).length;
  console.log(`    ${hide}: ${count} own cube(s), ${subtree.length} descendant bone(s)`
    + (subtree.length ? ` [${subtree.slice(0, 12).join(' ')}${subtree.length > 12 ? ' ...' : ''}]` : ''));
  if (bad.length) {
    ancestryHit = true;
    fail('hidden-ancestor', `hiddenBones entry '${hide}' is an ANCESTOR of body bone(s) ${JSON.stringify(bad)} - hiding it removes them`);
  }
}
if (!ancestryHit) console.log('    -> no hidden entry has a protected/body bone underneath it');

// ------------------------------------------------------------------ report
console.log(`  --- ${problems.length} problem(s), ${notes.length} note(s) ---`);
for (const p of problems) console.log(`  [FAIL ${p.kind}] ${p.msg}`);
for (const n of notes) console.log(`  [note] ${n}`);
process.exit(problems.length ? 1 : 0);
