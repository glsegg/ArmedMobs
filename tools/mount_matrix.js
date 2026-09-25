// What the held item is really doing on the rig, as numbers.
//
//   node tools/mount_matrix.js [geo.json] [animation.json] [clip] [anchorBone]
//
// The question this answers: "the gun is a flat dark slab across the chest and the arms look stubby -
// which link is wrong?" There are only three candidates (the arm pose, the mount matrix, the anchor
// point), so this prints all three for the states that matter:
//
//   * the final rotation of every bone in the chain (rest + clip keyframe);
//   * the model-space matrix the ITEM is drawn with, following GeckoLib's own call order;
//   * where that matrix puts the item's origin, and how far that is from the palm;
//   * what the two anchor modes differ by.
//
// GeckoLib's item path, from the 4.8.x sources (identical in 4.8.2 and 4.8.4):
//
//   GeoEntityRenderer#renderRecursively:
//       poseStack.pushPose();
//       RenderUtils.translateMatrixToBone      -> T(-posX/16, +posY/16, +posZ/16)
//       RenderUtils.translateToPivotPoint      -> T(pivot/16)
//       RenderUtils.rotateMatrixAroundBone     -> mulPose(Z) mulPose(Y) mulPose(X)   == Rz*Ry*Rx
//       RenderUtils.scaleMatrixForBone         -> S
//       RenderUtils.translateAwayFromPivotPoint-> T(-pivot/16)
//       renderCubesOfBone(...)
//       applyRenderLayersForBone(...)          <-- the item layer, on the transform above
//
//   BlockAndItemGeoLayer#renderForBone:
//       poseStack.pushPose();
//       RenderUtils.translateAndRotateMatrixForBone -> T(pivot/16) * R      <-- a SECOND time
//       renderStackForBone(...)  (ItemRenderer#renderStatic at the poseStack origin)
//
//   => M_item = T(-pos) * T(piv) * R * S * T(-piv) * T(piv) * R = T(-pos) * T(piv) * R * S * R
//      i.e. the bone's own rotation is applied TWICE, and the item sits at the bone's PIVOT.
//      For an anchor bone whose rotation is 0 (the usual case) nobody notices; this rig animates
//      RightHandLocator itself, so it does.
const fs = require('fs');
const path = require('path');

const GEO = process.argv[2] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json');
const ANIM = process.argv[3] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json');
const CLIP = process.argv[4] || 'tac:hold:rifle';
const ANCHOR = process.argv[5] || 'RightHandLocator';
const DEG = Math.PI / 180;

// ------------------------------------------------------------------ 4x4 helpers (row-major 16 floats)
const I4 = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const mul = (a, b) => {
  const out = new Array(16).fill(0);
  for (let r = 0; r < 4; r++) {
    for (let c = 0; c < 4; c++) {
      let sum = 0;
      for (let k = 0; k < 4; k++) sum += a[r * 4 + k] * b[k * 4 + c];
      out[r * 4 + c] = sum;
    }
  }
  return out;
};
const T = (x, y, z) => [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1];
const S = (x, y, z) => [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1];
const RX = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]; };
const RY = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]; };
const RZ = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; };
/** GeckoLib's rotateMatrixAroundBone: Z, then Y, then X -> Rz*Ry*Rx. */
const boneR = (rot) => mul(mul(RZ(rot[2]), RY(rot[1])), RX(rot[0]));
/** The inverse of the above, to cancel the duplicate application. */
const boneRinv = (rot) => mul(mul(RX(-rot[0]), RY(-rot[1])), RZ(-rot[2]));
const xform = (m, v) => [
  m[0] * v[0] + m[1] * v[1] + m[2] * v[2] + m[3],
  m[4] * v[0] + m[5] * v[1] + m[6] * v[2] + m[7],
  m[8] * v[0] + m[9] * v[1] + m[10] * v[2] + m[11],
];
const dir = (m, v) => [m[0]*v[0]+m[1]*v[1]+m[2]*v[2], m[4]*v[0]+m[5]*v[1]+m[6]*v[2], m[8]*v[0]+m[9]*v[1]+m[10]*v[2]];
const fmt = (v) => `(${v.map((n) => (Math.round(n * 1000) / 1000).toFixed(3)).join(', ')})`;
const fmtD = (v) => `${v.map((n) => Math.round(n)).join(',')}`;

const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const byName = new Map(geo.bones.map((b) => [b.name, b]));
const anims = JSON.parse(fs.readFileSync(ANIM, 'utf8')).animations;

const firstRotation = (clipName, boneName) => {
  const clip = anims[clipName];
  if (!clip || !clip.bones || !clip.bones[boneName] || !clip.bones[boneName].rotation) return null;
  const raw = clip.bones[boneName].rotation;
  let value = Array.isArray(raw) ? raw : Object.values(raw)[0];
  if (value && typeof value === 'object' && !Array.isArray(value)) value = value.post || value.pre || value.value;
  if (!Array.isArray(value)) return null;
  return value.map((v) => (typeof v === 'number' ? v : 0)); // a Molang channel collapses to 0
};

const chainOf = (boneName) => {
  const chain = [];
  for (let bone = byName.get(boneName); bone; bone = bone.parent ? byName.get(bone.parent) : null) chain.push(bone);
  return chain.reverse();
};

const finalRotation = (bone, clip) => {
  const rest = bone.rotation || [0, 0, 0];
  const clipRot = clip ? firstRotation(clip, bone.name) : null;
  return clipRot ? clipRot.map((v, i) => v + rest[i]) : rest.slice();
};

const chainMatrix = (clipped) => {
  let m = I4();
  for (const bone of clipped) {
    const pos = [0, 0, 0]; // no clip animates a bone position on this rig
    const piv = bone.pivot || [0, 0, 0];
    const scale = bone.scale || [1, 1, 1];
    const step = mul(
      mul(mul(mul(T(-pos[0] / 16, pos[1] / 16, pos[2] / 16), T(piv[0] / 16, piv[1] / 16, piv[2] / 16)), boneR(bone.rot)),
        S(scale[0], scale[1], scale[2])),
      T(-piv[0] / 16, -piv[1] / 16, -piv[2] / 16));
    m = mul(m, step);
  }
  return m;
};

const run = (clipName) => {
  const chain = chainOf(ANCHOR);
  const clipped = chain.map((bone) => {
    const rot = finalRotation(bone, clipName);
    return { ...bone, rot };
  });
  const anchor = clipped[clipped.length - 1];
  const piv = anchor.pivot || [0, 0, 0];

  console.log(`\n=== clip '${clipName}', anchor '${ANCHOR}' ===`);
  for (const bone of clipped) {
    const rest = bone.rotation || [0, 0, 0];
    const clipRot = firstRotation(clipName, bone.name);
    console.log(`  ${bone.name.padEnd(18)} rest=[${fmtD(rest)}] clip=${clipRot ? `[${fmtD(clipRot)}]` : '(not animated)'}`
      + ` final=[${fmtD(bone.rot)}] pivot=[${piv === null ? '' : fmtD(bone.pivot || [0, 0, 0])}]`);
  }

  const parent = chainMatrix(clipped.slice(0, -1));
  const renderM = chainMatrix(clipped);
  // What BlockAndItemGeoLayer adds on top of renderRecursively's transform:
  const layerAdd = mul(T(piv[0] / 16, piv[1] / 16, piv[2] / 16), boneR(anchor.rot));
  const itemLocatorAnimated = mul(renderM, layerAdd);
  // normalisedHand: cancel the duplicate bone rotation and use vanilla's held-item hand frame.
  const itemNormalised = mul(mul(itemLocatorAnimated, boneRinv(anchor.rot)), mul(mul(RX(-90), RY(180)), T(1 / 16, 0.125, -0.625)));

  // The palm: the hand cube's centre, carried by the same chain.
  const handBone = byName.get('RightHand');
  let palm = null;
  if (handBone && (handBone.cubes || []).length) {
    const cage = chainOf('RightHand').map((bone) => ({ ...bone, rot: finalRotation(bone, clipName) }));
    const handM = chainMatrix(cage);
    const cube = handBone.cubes[0];
    const c = [cube.origin[0] + cube.size[0] / 2, cube.origin[1] + cube.size[1] / 2, cube.origin[2] + cube.size[2] / 2]
      .map((n) => n / 16);
    palm = xform(handM, c);
  }

  for (const [label, m] of [['locatorAnimated', itemLocatorAnimated], ['normalisedHand', itemNormalised]]) {
    const origin = xform(m, [0, 0, 0]);
    const axes = [[1, 0, 0], [0, 1, 0], [0, 0, 1]].map((v) => dir(m, v));
    console.log(`  ${label.padEnd(17)} item origin=${fmt(origin)}  item x-axis(barrel)=${fmt(axes[0])}`
      + `  y-axis=${fmt(axes[1])}  z-axis=${fmt(axes[2])}`);
    if (palm) {
      const d = Math.hypot(origin[0] - palm[0], origin[1] - palm[1], origin[2] - palm[2]);
      console.log(`  ${''.padEnd(17)} palm centre=${fmt(palm)}  distance item-origin -> palm = ${d.toFixed(3)} blocks`
        + ` (${(d * 16).toFixed(1)} model units)`);
    }
  }

  // How much the bone's own rotation is applied: the double-R makes the anchor's frame differ a lot.
  const doubled = mul(parent, mul(T(piv[0] / 16, piv[1] / 16, piv[2] / 16), mul(boneR(anchor.rot), boneR(anchor.rot))));
  const single = mul(parent, mul(T(piv[0] / 16, piv[1] / 16, piv[2] / 16), boneR(anchor.rot)));
  const a = dir(doubled, [1, 0, 0]);
  const b = dir(single, [1, 0, 0]);
  const na = Math.hypot(...a); const nb = Math.hypot(...b);
  const dot = Math.max(-1, Math.min(1, (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)));
  console.log(`  the anchor's own clip rotation is applied TWICE by GeckoLib's item layer:`);
  console.log(`    barrel with R applied twice : ${fmt(a)}`);
  console.log(`    barrel with R applied once  : ${fmt(b)}`);
  console.log(`    difference                  : ${(Math.acos(dot) / DEG).toFixed(1)} degrees`);
  return { itemLocatorAnimated, itemNormalised, duplicateAngle: Math.acos(dot) / DEG, palmDistance: palm ? Math.hypot(xform(itemLocatorAnimated,[0,0,0])[0]-palm[0], xform(itemLocatorAnimated,[0,0,0])[1]-palm[1], xform(itemLocatorAnimated,[0,0,0])[2]-palm[2]) : -1 };
};

const runs = [CLIP];
for (const extra of ['tac:aim:rifle', 'tac:aim:fire:rifle']) {
  if (anims[extra] && !runs.includes(extra)) runs.push(extra);
}
const results = {};
for (const clip of runs) results[clip] = run(clip);

console.log('\nLeftHandLocator:');
const left = byName.get('LeftHandLocator');
if (left) {
  console.log(`  exists, parent=${left.parent}, pivot=[${fmtD(left.pivot || [0, 0, 0])}], cubes=${(left.cubes || []).length}`);
  const chain = chainOf('LeftHandLocator').map((b) => b.name);
  console.log(`  chain: ${chain.join(' > ')}`);
  const animatedIn = Object.keys(anims).filter((c) => firstRotation(c, 'LeftHandLocator'));
  console.log(`  animated by ${animatedIn.length} clip(s)${animatedIn.length ? ': ' + animatedIn.join(', ') : ''}`);
} else {
  console.log('  MISSING - the offhand anchor must fall back or warn');
}

// ------------------------------------------------------------------ checks
console.log('\nchecks:');
let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// 1. the data the offhand anchor is documented against
check(!!left && left.parent === 'LeftHand',
  'LeftHandLocator exists and is a child of LeftHand', left ? `parent=${left.parent}` : 'missing');
if (left && byName.get('RightHandLocator')) {
  const r = byName.get('RightHandLocator').pivot;
  const l = left.pivot;
  const mirrored = Math.abs(r[0] + l[0]) < 0.01 && Math.abs(r[1] - l[1]) < 0.01 && Math.abs(r[2] - l[2]) < 0.01;
  check(mirrored, 'its pivot mirrors RightHandLocator (so it is inside the left palm)',
    `left=[${fmtD(l)}] right=[${fmtD(r)}]`);
}
const leftAnimated = Object.keys(anims).filter((c) => firstRotation(c, 'LeftHandLocator'));
check(leftAnimated.length === 0, 'no clip animates LeftHandLocator (a clean hand anchor)',
  leftAnimated.join(', ') || 'none');

// 2. the duplicate-rotation claim, measured
const holdRun = results[CLIP];
check(holdRun.duplicateAngle > 20,
  `in ${CLIP} the anchor's own rotation is applied twice and tilts the barrel measurably`,
  `${holdRun.duplicateAngle.toFixed(1)} deg`);
check(holdRun.palmDistance >= 0 && holdRun.palmDistance < 0.05,
  'the item origin is in the palm (so the anchor POINT was never the problem)',
  `${holdRun.palmDistance.toFixed(3)} blocks from the palm centre`);
for (const clip of Object.keys(results)) {
  const normalised = results[clip].itemNormalised;
  const barrel = [normalised[0], normalised[4], normalised[8]];
  const length = Math.hypot(...barrel);
  check(Math.abs(length - 1) < 0.001,
    `${clip}: normalisedHand mode is a pure rotation (barrel axis stays unit length)`,
    `|barrel|=${length.toFixed(4)}`);
}

// 3. the source guards
const LAYER = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'client', 'GunInHandGeoLayer.java');
const layer = fs.readFileSync(LAYER, 'utf8').replace(/\/\/[^\n]*/g, '');
const config = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java'), 'utf8');
check(/cancelDuplicateBoneRotation\(poseStack, bone\)/.test(layer),
  'the layer cancels the duplicate bone rotation in normalisedHand mode');
check(/applyVanillaHandFrame\(poseStack, offhand\)/.test(layer),
  'the layer applies vanilla\'s held-item hand frame');
check(/Axis\.XP\.rotationDegrees\(-90\.0F\)/.test(layer) && /Axis\.YP\.rotationDegrees\(180\.0F\)/.test(layer)
    && /0\.125F, -0\.625F/.test(layer),
  'the hand frame is mulPose(X,-90) * mulPose(Y,180) * translate(±1/16, 0.125, -0.625)');
check(/rotationDegrees\(-bone\.getRotX\(\)/.test(layer) && /rotationDegrees\(-bone\.getRotZ\(\)/.test(layer),
  'the cancellation applies the inverse ZYX rotation (X, Y, Z with negated angles)');
check(/DEFAULT_OFFHAND_ANCHOR/.test(layer) && /OFFHAND_FALLBACKS/.test(layer),
  'the offhand anchor has its own fallback chain');
check(/none of the \{\} bones/.test(layer) && /will NOT be displayed/.test(layer),
  'a missing anchor bone is a WARN, never a silent empty hand');
check(/THIRD_PERSON_LEFT_HAND/.test(layer) && /mirrored to \{\}/.test(layer),
  'a TaCZ gun in the left hand is mirrored to the right-hand context with a WARN');
check(!/anchorBone == null \|\| !bone\.getName\(\)\.equals\(this\.anchorBone\)/.test(layer),
  'the main and offhand anchors are resolved independently');
check(/\.define\("gunAnchorMode", DEFAULT_ANCHOR_MODE\)/.test(config)
    && /\.define\("gunOffhandAnchorBone", "LeftHandLocator"\)/.test(config)
    && /\.define\("renderOffhandItem", true\)/.test(config)
    && /\.define\("gunTwoHandedSupport", false\)/.test(config),
  'the new config keys exist with the documented defaults');

// ------------------------------------------------------------------ the shipped forward slide
// The offset default is the USER'S in-game measurement (z = -0.7 under normalisedHand), not a guess.
// The simulation can only explain it: a pure translation cannot rotate anything, and this prints where
// it moves the anchor in model space.
const DEFAULT_OFFSET = (() => {
  const m = /DEFAULT_MOUNT_OFFSET = List\.of\(([^)]*)\)/.exec(config);
  return m ? m[1].split(',').map((s) => Number(s.trim().replace(/"/g, ''))) : null;
})();
console.log(`\nthe shipped baseline (client.gunMountRifleOffset / gunMountPistolOffset): ${JSON.stringify(DEFAULT_OFFSET)}`);
check(JSON.stringify(DEFAULT_OFFSET) === JSON.stringify([0, 0, -0.7]),
  'the default offset is the user-measured [0, 0, -0.7]', JSON.stringify(DEFAULT_OFFSET));
check(/DEFAULT_MOUNT_OFFSET/.test(config) && (config.match(/DEFAULT_MOUNT_OFFSET/g) || []).length >= 3,
  'rifle, pistol and gunpose reset all read that ONE constant (no drift between spec and command)');
const commands = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'command', 'ClientCommands.java'), 'utf8');
check(/case "forward", "barrel" -> nudgeForward = -Float\.parseFloat\(value\)/.test(commands),
  'forward is -Z (the user-measured muzzle axis), back is +Z');
check(/if \(nudgeForward != null\) \{\s*offset\[2\] \+= nudgeForward;/.test(commands.replace(/\r\n/g, '\n')),
  'the forward nudge adds to the Z component (so repeated taps walk it)');
check(/offset\[0\] \+= nudgeRight/.test(commands) && /offset\[1\] \+= nudgeUp/.test(commands),
  'right/left map to X and up/down to Y');
check(/Config\.triple\(Config\.DEFAULT_MOUNT_OFFSET/.test(commands) && /Config\.DEFAULT_ANCHOR_MODE/.test(commands),
  'gunpose reset restores the shipped baseline (including the -0.7 slide), not zero');

for (const clip of Object.keys(results)) {
  const frame = results[clip].itemNormalised;
  const basis = [[frame[0], frame[4], frame[8]], [frame[1], frame[5], frame[9]], [frame[2], frame[6], frame[10]]];
  const delta = [0, 1, 2].map((axis) => basis[0][axis] * DEFAULT_OFFSET[0]
    + basis[1][axis] * DEFAULT_OFFSET[1] + basis[2][axis] * DEFAULT_OFFSET[2]);
  const origin = [frame[3], frame[7], frame[11]];
  console.log(`  ${clip}: the -0.7 slide moves the anchor by (${delta.map((n) => n.toFixed(2)).join(', ')}) blocks`
    + ` in model space -> forward ${(-delta[2]).toFixed(2)}, to the character's left ${delta[0].toFixed(2)}, up ${delta[1].toFixed(2)}`);
  console.log(`      anchor before (${origin.map((n) => n.toFixed(2)).join(', ')}) -> after`
    + ` (${origin.map((n, axis) => (n + delta[axis]).toFixed(2)).join(', ')})`);
  check(-delta[2] > 0.2,
    `${clip}: the default slide moves the gun forward (model -Z), which is what the user reported`,
    `${(-delta[2]).toFixed(2)} blocks forward`);
}
const barrelBefore = [results[CLIP].itemNormalised[0], results[CLIP].itemNormalised[4], results[CLIP].itemNormalised[8]];
console.log(`  the barrel direction is (${barrelBefore.map((n) => n.toFixed(3)).join(', ')}) and is UNCHANGED by an offset:`
  + ' it is a translation, which is why the orientation the user already accepted stays put');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
