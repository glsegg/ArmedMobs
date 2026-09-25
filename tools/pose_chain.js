// Where the held gun actually hangs: the rest-rotation chain of the anchor bone, the composition of
// those rotations, and what each gun clip does to the same bones.
//
//   node tools/pose_chain.js [geo.json] [animation.json] [anchorBone]
//
// Why: "the gun's rotation is wrong" was answered for two rounds with a new set of magic numbers copied
// from another mod. The numbers cannot be transplanted because the anchor sits inside a coordinate
// system built from up to four animated rotations, and because the item itself carries a display
// transform chosen by the render context. This prints the first half as numbers, and greps the layer
// for the second half.
//
// The last section mirrors the Java: the anchor chain, the display context and the "no additive write"
// rule, so the tool fails if the layer stops agreeing with the geometry it is documented against.
const fs = require('fs');
const path = require('path');

const GEO = process.argv[2] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json');
const ANIM = process.argv[3] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json');
const ANCHOR = process.argv[4] || 'RightHandLocator';
const LAYER = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'client', 'GunInHandGeoLayer.java');
const DEG = Math.PI / 180;

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ tiny 3x3 matrix maths
const mul = (a, b) => {
  const out = [0, 0, 0, 0, 0, 0, 0, 0, 0];
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 3; c++) {
      out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
    }
  }
  return out;
};
const rotX = (d) => [1, 0, 0, 0, Math.cos(d * DEG), -Math.sin(d * DEG), 0, Math.sin(d * DEG), Math.cos(d * DEG)];
const rotY = (d) => [Math.cos(d * DEG), 0, Math.sin(d * DEG), 0, 1, 0, -Math.sin(d * DEG), 0, Math.cos(d * DEG)];
const rotZ = (d) => [Math.cos(d * DEG), -Math.sin(d * DEG), 0, Math.sin(d * DEG), Math.cos(d * DEG), 0, 0, 0, 1];
const apply = (m, v) => [
  m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
  m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
  m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
];
const fmt = (v) => `(${v.map((n) => (Math.round(n * 100) / 100).toFixed(2)).join(', ')})`;

const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const byName = new Map(geo.bones.map((b) => [b.name, b]));
if (!byName.has(ANCHOR)) {
  console.error(`no bone '${ANCHOR}' in ${GEO}`);
  process.exit(2);
}

const chain = [];
for (let bone = byName.get(ANCHOR); bone; bone = bone.parent ? byName.get(bone.parent) : null) chain.push(bone);
chain.reverse();

console.log(`${GEO}: rest-rotation chain of '${ANCHOR}' (top down)`);
for (const bone of chain) {
  const rot = bone.rotation || [0, 0, 0];
  const mark = rot.some((v) => v) ? '  <-- NON-ZERO' : '';
  console.log(`  ${bone.name.padEnd(18)} rot=[${rot.join(', ')}]${mark}`);
}
const nonZero = chain.filter((b) => (b.rotation || [0, 0, 0]).some((v) => v));
console.log(`  ${nonZero.length} bone(s) in the chain carry a rest rotation: ${nonZero.map((b) => `${b.name}=[${b.rotation.join(',')}]`).join(' ') || 'none'}`);

// GeckoLib's rotateMatrixAroundBone applies Z, then Y, then X, on top of the parent's frame.
const boneMatrix = (rot) => mul(mul(rotZ(rot[2]), rotY(rot[1])), rotX(rot[0]));
const compose = (rotations) => rotations.reduce((acc, rot) => mul(acc, boneMatrix(rot)), [1, 0, 0, 0, 1, 0, 0, 0, 1]);

const restMatrix = compose(chain.map((b) => b.rotation || [0, 0, 0]));
console.log(`\n  composed REST rotation of '${ANCHOR}' (all ancestors folded in):`);
for (let r = 0; r < 3; r++) {
  console.log(`    ${['x', 'y', 'z'][r]}-axis of the anchor maps to model ${fmt([restMatrix[r * 3], restMatrix[r * 3 + 1], restMatrix[r * 3 + 2]])}`);
}

// ------------------------------------------------------------------ what the clips do to the same chain
let animations = { animations: {} };
try {
  animations = JSON.parse(fs.readFileSync(ANIM, 'utf8'));
} catch (error) {
  console.log(`  (no readable animation file at ${ANIM})`);
}

const clipRotation = (channels) => {
  if (!channels || !channels.rotation) return null;
  const raw = channels.rotation;
  // A time-keyed object: take the first keyframe's post value. A bare array: take it directly.
  let value = Array.isArray(raw) ? raw : Object.values(raw)[0];
  if (value && typeof value === 'object' && !Array.isArray(value)) value = value.post || value.pre || value.value;
  if (!Array.isArray(value)) return null;
  return value.map((v) => (typeof v === 'number' ? v : null));
};

console.log(`\n  what each gun clip rotates along the chain (a null is a Molang channel that collapses to 0):`);
for (const clip of Object.keys(animations.animations)) {
  if (!clip.startsWith('tac:')) continue;
  const bones = animations.animations[clip].bones || {};
  const parts = [];
  for (const bone of chain) {
    const rot = clipRotation(bones[bone.name]);
    if (rot) parts.push(`${bone.name}=[${rot.map((v) => (v === null ? '0(molang)' : v)).join(',')}]`);
  }
  if (parts.length) console.log(`    ${clip.padEnd(20)} ${parts.join('  ')}`);
}

const hold = animations.animations['tac:hold:rifle'];
const aim = animations.animations['tac:aim:rifle'];
if (hold && aim) {
  const clipChain = (clip) => chain.map((b) => {
    const clipRot = clipRotation((clip.bones || {})[b.name]);
    const rest = b.rotation || [0, 0, 0];
    // GeckoLib writes keyframe + initial snapshot, so the clip value *replaces* the rest rotation's
    // animated contribution and is added to it.
    return clipRot ? clipRot.map((v, i) => (v === null ? rest[i] : v + rest[i])) : rest;
  });
  const holdMatrix = compose(clipChain(hold));
  const aimMatrix = compose(clipChain(aim));
  const muzzleHold = apply(holdMatrix, [1, 0, 0]);
  const muzzleAim = apply(aimMatrix, [1, 0, 0]);
  console.log(`\n  the anchor's local +X (the barrel direction for a YSM locator) points:`);
  console.log(`    in tac:hold:rifle  -> model ${fmt(muzzleHold)}`);
  console.log(`    in tac:aim:rifle   -> model ${fmt(muzzleAim)}`);
  const angle = Math.acos(Math.max(-1, Math.min(1,
    (muzzleHold[0] * muzzleAim[0] + muzzleHold[1] * muzzleAim[1] + muzzleHold[2] * muzzleAim[2])
    / (Math.hypot(...muzzleHold) * Math.hypot(...muzzleAim))))) / DEG;
  console.log(`    the two clips differ by ${angle.toFixed(1)} degrees`);
  check(angle > 5,
    'the anchor really is animated by the clips (the mount space rotates between hold and aim)',
    `${angle.toFixed(1)} deg`);
}

// ------------------------------------------------------------------ the Java side
const layer = fs.readFileSync(LAYER, 'utf8');
console.log('\nsource guard (GunInHandGeoLayer.java):');
check(/ItemDisplayContext\.valueOf\(Config\.gunMountDisplayContext\(\)\)/.test(layer),
  'the display context comes from the config, not a hard-coded FIXED');
check(/THIRD_PERSON_RIGHT_HAND/.test(layer),
  'THIRD_PERSON_RIGHT_HAND (the context vanilla ItemInHandLayer uses) is the fallback');
check(!/return ItemDisplayContext\.FIXED;/.test(layer),
  'FIXED is no longer returned unconditionally');
check(/mountRotation|mountOffset|mountScale/.test(layer) && /Config\.triple/.test(layer),
  'rotation / offset / scale are read from the config every frame, so the tuner is live');
check(!/\.getRot[XYZ]\(\)\s*\+/.test(layer), 'no additive write on a bone in the mount layer');

// The defaults in Config.java: neutral, because TaCZ already places the gun for a hand.
const config = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java'), 'utf8');
// The defaults are single-sourced in Config's DEFAULT_* constants (so the spec and gunpose reset cannot
// drift), so the check is on the constant's value, and on the key actually reading it.
check(/DEFAULT_MOUNT_ROTATION = List\.of\("0", "0", "0"\)/.test(config)
    && /List\.of\("gunMountRifleRotation"\), \(\) -> DEFAULT_MOUNT_ROTATION/.test(config),
  'gunMountRifleRotation defaults to neutral (via the shared constant)');
check(/DEFAULT_MOUNT_SCALE = 1\.0D/.test(config)
    && /defineInRange\("gunMountRifleScale", DEFAULT_MOUNT_SCALE/.test(config),
  'gunMountRifleScale defaults to 1.0');
check(/DEFAULT_MOUNT_DISPLAY_CONTEXT = "THIRD_PERSON_RIGHT_HAND"/.test(config)
    && /\.define\("gunMountDisplayContext", DEFAULT_MOUNT_DISPLAY_CONTEXT\)/.test(config),
  'gunMountDisplayContext defaults to THIRD_PERSON_RIGHT_HAND');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
