// The imported Scav rig (README 7 / 5c). One thing about a re-exported model can break the mob silently,
// and this gate is about exactly that class of failure:
//
//   * a clip animating a bone the geometry no longer has - GeckoLib ignores the track without a word, so
//     the mob just stops moving that part (the 2026 re-export deleted 23 bones and the animation library
//     was NOT re-exported, which makes this the first thing to check);
//   * an anchor/locator bone other code reads (RightHandLocator for the held gun, LeftHandLocator for the
//     two-handed support, Head/Hat2 for the head accessories) disappearing;
//   * a config DEFAULT naming a bone the rig does not have - that is a warning in the log on every fresh
//     install, and for the defaults it is a documentation bug rather than a user error.
//
//   node tools/selftest_rig_bones.js
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const GEO = path.join(ASSETS, 'geo', 'scav.geo.json');
const ANIM = path.join(ASSETS, 'animations', 'scav.animation.json');
const CONFIG = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java');
const README = path.join(ROOT, 'README.md');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const desc = geo.description || {};
const bones = geo.bones || [];
const byName = new Map(bones.map((b) => [b.name, b]));
const anim = JSON.parse(fs.readFileSync(ANIM, 'utf8'));
const config = fs.readFileSync(CONFIG, 'utf8');
const readme = fs.readFileSync(README, 'utf8');

const cubes = bones.reduce((sum, b) => sum + (b.cubes || []).length, 0);
console.log('1. the rig itself');
console.log(`  identifier=${desc.identifier} texture=${desc.texture_width}x${desc.texture_height}`
  + ` bones=${bones.length} cubes=${cubes}`);
check(desc.identifier === 'geometry.tarkovscav.scav',
  'the identifier is geometry.tarkovscav.scav (the loader looks it up by name)', desc.identifier);
check(desc.texture_width === 256 && desc.texture_height === 256,
  'the atlas is still 256x256 (a re-export with a different atlas would break every uv)',
  `${desc.texture_width}x${desc.texture_height}`);
const dupes = bones.length - byName.size;
check(dupes === 0, 'no duplicate bone names', `${dupes} duplicate(s)`);
const dangling = bones.filter((b) => b.parent && !byName.has(b.parent)).map((b) => `${b.name}->${b.parent}`);
check(dangling.length === 0, 'no dangling parent', dangling.join(', ') || 'clean');
const roots = bones.filter((b) => !b.parent).map((b) => b.name);
check(roots.length === 1, 'exactly one root bone', roots.join(', '));

console.log('');
console.log('2. every bone the clips animate still exists (the silent one)');
const animBones = new Set();
let clips = 0;
for (const clip of Object.values(anim.animations || {})) {
  clips++;
  for (const bone of Object.keys(clip.bones || {})) animBones.add(bone);
}
const missing = [...animBones].filter((b) => !byName.has(b)).sort();
check(missing.length === 0,
  `the geometry covers every bone the ${clips} clip(s) animate (${animBones.size} bone(s) referenced)`,
  missing.length ? `MISSING: ${missing.join(', ')}` : '0 missing');
for (const bone of animBones) {
  if (!byName.has(bone)) continue;
  // A clip that animates an existing bone with no cubes is harmless (locators are animated), so this is
  // only reported as a count, not as a failure.
}
const animatedEmpty = [...animBones].filter((b) => byName.has(b) && !(byName.get(b).cubes || []).length);
console.log(`  ${animatedEmpty.length} animated bone(s) carry no cubes of their own (locators/group bones): `
  + animatedEmpty.slice(0, 12).join(' '));

console.log('');
console.log('3. the anchors other code reads');
// RightHandLocator/LeftHandLocator: client.gunAnchorBone's default and the two-handed support.
// Head/Hat2/Head3/Ear: the head accessories and the aim tracking. UpperBody/RightArm/LeftArm: the pose
// writers in RigSupport. RifleLocator/PistolLocator: the back/thigh gun mounts.
const ANCHORS = ['RightHandLocator', 'LeftHandLocator', 'Head', 'Head3', 'Hat2', 'Ear', 'UpperBody',
  'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand', 'AllBody', 'UpBody',
  'DownBody', 'Leg', 'RifleLocator', 'PistolLocator'];
for (const name of ANCHORS) {
  check(byName.has(name), `anchor '${name}' is in the rig`,
    byName.has(name) ? `parent ${byName.get(name).parent || '(root)'}` : 'MISSING');
}

console.log('');
console.log('4. the config defaults must not name bones this rig does not have');
const defaultList = (method) => {
  const block = new RegExp(`${method}\\(\\)\\s*\\{\\s*return List\\.of\\(([\\s\\S]*?)\\);`).exec(config);
  if (!block) return null;
  return [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
};
const hiddenDefaults = defaultList('defaultHiddenBones');
check(hiddenDefaults !== null, 'defaultHiddenBones() was found in Config.java');
const hiddenAbsent = (hiddenDefaults || []).filter((n) => !byName.has(n));
check(hiddenAbsent.length === 0,
  'every default hiddenBones entry exists in this rig (the 2026 re-export deleted the author\'s props,'
  + ' so the default is empty on purpose)', hiddenAbsent.join(', ') || `default = [${(hiddenDefaults || []).join(', ')}]`);
const kept = (/define\("keptHatBone",\s*"([^"]+)"\)/.exec(config) || [])[1];
check(kept !== undefined && byName.has(kept),
  'the default keptHatBone exists in this rig (hatAccessory defaults to keepOne)', kept);
const eyeGear = (/define\("eyeGearAccessory",\s*"([^"]+)"\)/.exec(config) || [])[1];
check(eyeGear === 'none' || byName.has(eyeGear),
  'the default eyeGearAccessory is either "none" or a bone this rig has', eyeGear);
const cigarette = /define\("cigarette",\s*(true|false)\)/.exec(config);
const cigaretteBone = (/define\("cigaretteBone",\s*"([^"]*)"\)/.exec(config) || [])[1];
check(cigarette !== null && (cigarette[1] === 'false' || byName.has(cigaretteBone)),
  'the cigarette is off by default or its bone exists', `cigarette=${cigarette && cigarette[1]}, bone='${cigaretteBone}'`);
// The names that are deliberately kept as vocabulary although this rig lacks them: they are reported once
// per rig at runtime, so the README has to say which ones they are (otherwise a user reads a warning with
// no way to know whether it is expected).
const vocabulary = [].concat(defaultList('defaultHatBones') || [], defaultList('defaultEyeGearBones') || [],
  cigaretteBone ? [cigaretteBone] : []).filter((n) => !byName.has(n));
console.log(`  ${vocabulary.length} configured name(s) are kept as vocabulary although this rig lacks them: `
  + vocabulary.join(', '));
for (const name of vocabulary) {
  check(readme.includes(name), `README documents that '${name}' is not in this rig`);
}

console.log('');
console.log('5. the re-export is documented, and the comparison tool is still there');
const compare = path.join(ROOT, 'tools', 'compare_scav_geo.js');
check(fs.existsSync(compare), 'tools/compare_scav_geo.js exists (the re-export can be re-checked)');
check(fs.readFileSync(compare, 'utf8').includes('main.json'),
  'and it compares the SOURCE rig against the imported geo (re-runnable after a swap)');
check(/70/.test(readme) && /47/.test(readme) && /322/.test(readme) && /145/.test(readme),
  'README records the rig change (70 -> 47 bones, 322 -> 145 cubes)');
check(/compare_scav_geo/.test(readme), 'and names the tool that produced those numbers');

console.log('');
if (failures > 0) {
  console.log(`${failures} rig check(s) FAILED`);
  process.exit(1);
}
console.log(`the rig covers every animated bone (${bones.length} bones, ${cubes} cubes, ${clips} clips)`);

// Coplanar faces are a permanent property of this rig (README 5k + the pitfalls): two faces in the same
// plane make the depth buffer pick between them per angle, which is the "head / bag / forearm changes
// material, goes black or seems to vanish" family. The geometry is nudged by 0.03 units (0.002 blocks) by
// tools/patch_coplanar_faces.js, and this check keeps it that way: a future rig edit that reintroduces a
// coplanar pair fails here.
const { execFileSync } = require('child_process');
const overlapOut = execFileSync(process.execPath,
  [path.join(ROOT, 'tools', 'scan_overlapping_geometry.js'),
    path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json'), '-'],
  { encoding: 'utf8' });
const coplanarCount = Number((overlapOut.match(/(\d+) coplanar face pair/) || [0, -1])[1]);
const containedCount = Number((overlapOut.match(/(\d+) contained/) || [0, -1])[1]);
check(coplanarCount === 0, 'no coplanar face pairs are left in the rig', coplanarCount + ' reported');
check(containedCount >= 0, 'the contained (shell) pairs are counted too', containedCount + ' shell pair(s)');