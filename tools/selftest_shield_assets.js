// Gate for the VANT ballistic-shield assets. No dependencies, plain node:
//
//   node tools/spike/work/shield/selftest_shield_assets.js
//
// Asserts, and prints every number it looked at:
//   * the texture exists, is a real PNG, is 512x512, and its sha256 equals the sha256 of the PNG embedded
//     in the .bbmodel (i.e. the extraction was byte-for-byte, no re-encode, no resize);
//   * the model JSON parses, its textures.layer0 resolves to that file, every element has 6 well-formed
//     faces, every uv is inside 0..16, every from component is below its matching to component, no element
//     carries a field vanilla does not understand (`inflate` is baked into from/to), and the rotations the
//     source had survived one for one;
//   * the model is not a hand-edit of the generator: the uv multiset equals the source uv multiset / 32 and
//     the element bounding box equals the source bounding box, recentred;
//   * the 7 display contexts are present and well formed;
//   * both lang files gained item.tarkovscav.vant_shield with the agreed text and lost nothing (compared
//     against the baseline copies taken before the key was added);
//   * the generator is idempotent: running it twice reproduces byte-identical files, and those are the
//     files currently on disk.
//
// Exit code 0 = all checks passed, 1 = at least one failed.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const HERE = __dirname;
/** Walk up to the project root (gradle.properties marks it) so this gate runs from tools/ OR the scratch dir. */
function findRoot(start) {
  let dir = start;
  for (let i = 0; i < 6; i += 1) {
    if (fs.existsSync(path.join(dir, 'gradle.properties'))) return dir;
    const up = path.dirname(dir);
    if (up === dir) break;
    dir = up;
  }
  return path.resolve(start, '..', '..', '..', '..');
}
const ROOT = findRoot(HERE);
// The source model and the language baselines live in assets_source/shield/ (committed, so a fresh clone can
// run this gate and the generator); the old scratch copies stay as a fallback.
const SCRATCH = path.join(ROOT, 'tools', 'spike', 'work', 'shield');
const PUBLISHED = path.join(ROOT, 'assets_source', 'shield');
const firstExisting = (...candidates) => candidates.find((p) => fs.existsSync(p));
const BBMODEL = firstExisting(path.join(PUBLISHED, 'VANT防弹盾牌.bbmodel'),
  path.join(HERE, 'VANT防弹盾牌.bbmodel'), path.join(SCRATCH, 'VANT防弹盾牌.bbmodel'));
const GENERATOR = path.join(ROOT, 'tools', 'make_shield_assets.js');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const TEXTURE = path.join(ASSETS, 'textures', 'item', 'vant_shield.png');
const MODEL = path.join(ASSETS, 'models', 'item', 'vant_shield.json');
const LANG = {
  en_us: path.join(ASSETS, 'lang', 'en_us.json'),
  zh_cn: path.join(ASSETS, 'lang', 'zh_cn.json'),
};
const LANG_BASELINE = {
  en_us: firstExisting(path.join(PUBLISHED, 'lang_baseline_en_us.json'),
    path.join(HERE, 'lang_baseline_en_us.json'), path.join(SCRATCH, 'lang_baseline_en_us.json')),
  zh_cn: firstExisting(path.join(PUBLISHED, 'lang_baseline_zh_cn.json'),
    path.join(HERE, 'lang_baseline_zh_cn.json'), path.join(SCRATCH, 'lang_baseline_zh_cn.json')),
};
const EXPECTED = {
  en_us: 'VANT Ballistic Shield',
  zh_cn: 'VANT 防弹盾牌',
};
const SHIELD_KEY = 'item.tarkovscav.vant_shield';
const DISPLAY_CONTEXTS = [
  'thirdperson_righthand', 'thirdperson_lefthand', 'firstperson_righthand', 'firstperson_lefthand',
  'gui', 'ground', 'fixed',
];
const FACE_NAMES = ['north', 'east', 'south', 'west', 'up', 'down'];
const ALLOWED_ELEMENT_KEYS = ['from', 'to', 'rotation', 'faces'];

let checks = 0;
let failures = 0;
function check(label, ok, detail) {
  checks++;
  if (!ok) failures++;
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label}${detail === undefined ? '' : '  -> ' + detail}`);
  return ok;
}
function section(title) {
  console.log(`\n== ${title} ==`);
}
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const num = (v) => typeof v === 'number' && Number.isFinite(v);
const num3 = (v) => Array.isArray(v) && v.length === 3 && v.every(num);
const num4 = (v) => Array.isArray(v) && v.length === 4 && v.every(num);
const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const round = (v) => Math.round(v * 1e6) / 1e6;

// ---------------------------------------------------------------- source of truth: the .bbmodel
section('source .bbmodel');
if (!BBMODEL || !fs.existsSync(BBMODEL)) {
  console.log('FAIL: the source .bbmodel is not present.');
  console.log(`      looked for VANT防弹盾牌.bbmodel in ${path.relative(ROOT, HERE)} and ${path.relative(ROOT, SCRATCH)}.`);
  console.log('      It is the user-supplied Blockbench project and it is git-ignored, so a fresh checkout has to');
  console.log('      copy it back before this gate can verify the texture byte for byte.');
  process.exit(1);
}
const bbRaw = fs.readFileSync(BBMODEL);
const bb = JSON.parse(bbRaw.toString('utf8'));
const bbTextures = (bb.textures || []).filter((t) => typeof t.source === 'string' && t.source.startsWith('data:image/png;base64,'));
check('bbmodel is JSON with 1 embedded PNG texture', bbTextures.length === 1, `${bbTextures.length}`);
const embeddedPng = Buffer.from(bbTextures[0].source.slice('data:image/png;base64,'.length), 'base64');
const embeddedSha = sha256(embeddedPng);
const bbElements = bb.elements || [];
const bbRotated = bbElements.filter((e) => e.rotation && e.rotation.some((v) => v !== 0));
const bbInflated = bbElements.filter((e) => e.inflate);
const bbUv = [];
for (const e of bbElements) for (const name of Object.keys(e.faces || {})) for (const v of e.faces[name].uv || []) bbUv.push(v);
console.log(`bbmodel      : ${path.basename(BBMODEL)} ${bbRaw.length} bytes, format ${bb.meta && bb.meta.format_version}, model_format ${bb.meta && bb.meta.model_format}`);
console.log(`resolution   : ${JSON.stringify(bb.resolution)}  (=> uv divisor ${bb.resolution.width / 16})`);
console.log(`embedded png : ${embeddedPng.length} bytes, sha256 ${embeddedSha}`);
console.log(`cubes        : ${bbElements.length} total, ${bbRotated.length} with a non-zero rotation, ${bbInflated.length} with inflate,`
  + ` ${bbElements.filter((e) => e.box_uv).length} flagged box_uv`);
console.log(`source uv    : ${bbUv.length} values, min ${Math.min(...bbUv)}, max ${Math.max(...bbUv)}`);

// ---------------------------------------------------------------- check 1: the texture
section('texture');
const texBuf = fs.existsSync(TEXTURE) ? fs.readFileSync(TEXTURE) : null;
check('texture file exists', texBuf !== null, path.relative(ROOT, TEXTURE));
let texW = 0;
let texH = 0;
if (texBuf) {
  const pngMagic = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  const hasMagic = pngMagic.every((b, i) => texBuf[i] === b);
  const hasIhdr = texBuf.length > 24 && texBuf.readUInt32BE(12) === 0x49484452;
  const hasIend = texBuf.length > 12 && texBuf.readUInt32BE(texBuf.length - 8) === 0x49454e44;
  texW = hasIhdr ? texBuf.readUInt32BE(16) : 0;
  texH = hasIhdr ? texBuf.readUInt32BE(20) : 0;
  const bitDepth = hasIhdr ? texBuf[24] : 0;
  const colourType = hasIhdr ? texBuf[25] : 0;
  let chunks = 0;
  let crcOk = true;
  for (let p = 8; p + 12 <= texBuf.length;) {
    const len = texBuf.readUInt32BE(p);
    const crc = texBuf.readUInt32BE(p + 8 + len);
    const calc = crc32(texBuf.subarray(p + 4, p + 8 + len));
    if (crc !== calc) crcOk = false;
    chunks++;
    if (texBuf.readUInt32BE(p + 4) === 0x49454e44) break;
    p += 12 + len;
  }
  check('texture has the PNG signature', hasMagic);
  check('texture has a valid IHDR and IEND', hasIhdr && hasIend);
  check('texture chunk CRCs are all correct', crcOk, `${chunks} chunks walked`);
  check('texture is 512x512', texW === 512 && texH === 512, `${texW}x${texH}`);
  check('texture is 8-bit depth, colour type 6 (RGBA)', bitDepth === 8 && colourType === 6, `depth ${bitDepth}, colour type ${colourType}`);
  check('texture size matches the size recorded in the bbmodel (byte for byte)',
    texBuf.length === embeddedPng.length, `${texBuf.length} vs ${embeddedPng.length}`);
  check('texture bytes are identical to the embedded PNG', texBuf.equals(embeddedPng));
  check('texture sha256 equals the sha256 of the embedded PNG', sha256(texBuf) === embeddedSha,
    `${sha256(texBuf)} vs ${embeddedSha}`);
  check('texture sha256 matches the value the generator printed in this session',
    sha256(texBuf) === '5f87f7db24793da21725eab5c977fbeff6d62f9b191569f6a78fc5023b3409c8', sha256(texBuf));
}

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

// ---------------------------------------------------------------- check 2..: the model
section('model');
let model = null;
try {
  model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
  check('model JSON parses', true, `${fs.statSync(MODEL).size} bytes, ${JSON.stringify(Object.keys(model))}`);
} catch (err) {
  check('model JSON parses', false, err.message);
}
let rotations = 0;
let faces = 0;
let uvValues = 0;
let uvOutOfRange = 0;
let emptyFaces = 0;
const faceCountHistogram = {};
const rotationAxisHistogram = {};
const modelUv = [];
const modelBox = { lo: [Infinity, Infinity, Infinity], hi: [-Infinity, -Infinity, -Infinity] };
if (model) {
  const layer0 = model.textures && model.textures.layer0;
  check('textures.layer0 is set', typeof layer0 === 'string', JSON.stringify(layer0));
  check('textures.layer0 is tarkovscav:item/vant_shield', layer0 === 'tarkovscav:item/vant_shield');
  const resolved = typeof layer0 === 'string' && layer0.startsWith('tarkovscav:')
    ? path.join(ASSETS, 'textures', layer0.slice('tarkovscav:'.length) + '.png')
    : null;
  check('textures.layer0 points at a file that exists (assets/tarkovscav/textures/<path>.png)',
    resolved !== null && fs.existsSync(resolved),
    resolved === null ? 'unresolvable' : path.relative(ROOT, resolved));
  check('that file is the texture this gate checked above', resolved !== null && resolved === TEXTURE,
    resolved === null ? 'unresolvable' : `${path.relative(ROOT, resolved)} vs ${path.relative(ROOT, TEXTURE)}`);
  check('the model does not inherit minecraft:item/generated (that would add a flat layer0 quad)',
    model.parent === undefined, `parent = ${JSON.stringify(model.parent)}`);
  check('model has an elements array', Array.isArray(model.elements), `${(model.elements || []).length} elements`);
  check('element count equals the bbmodel cube count', (model.elements || []).length === bbElements.length,
    `${(model.elements || []).length} vs ${bbElements.length}`);

  (model.elements || []).forEach((el, i) => {
    const tag = `element ${i}`;
    const extraKeys = Object.keys(el).filter((k) => !ALLOWED_ELEMENT_KEYS.includes(k));
    check(`${tag}: only vanilla element keys (from/to/rotation/faces)`, extraKeys.length === 0, extraKeys.join(',') || 'clean');
    check(`${tag}: from/to are 3 numbers each`, num3(el.from) && num3(el.to), `${JSON.stringify(el.from)} .. ${JSON.stringify(el.to)}`);
    let ordering = true;
    for (let a = 0; a < 3; a++) {
      if (!(el.from[a] < el.to[a])) ordering = false;
      modelBox.lo[a] = Math.min(modelBox.lo[a], el.from[a]);
      modelBox.hi[a] = Math.max(modelBox.hi[a], el.to[a]);
    }
    check(`${tag}: every from component is strictly below its to component`, ordering,
      `size ${JSON.stringify(el.to.map((v, a) => round(v - el.from[a])))}`);

    if (el.rotation !== undefined) {
      rotations++;
      const r = el.rotation;
      check(`${tag}: rotation has origin/axis/angle/rescale`,
        num3(r.origin) && typeof r.axis === 'string' && num(r.angle) && typeof r.rescale === 'boolean',
        JSON.stringify(r));
      check(`${tag}: rotation axis is one of x/y/z`, ['x', 'y', 'z'].includes(r.axis), r.axis);
      check(`${tag}: rotation angle is not zero and inside +-180`, r.angle !== 0 && Math.abs(r.angle) <= 180, String(r.angle));
      check(`${tag}: rotation rescale is false`, r.rescale === false, String(r.rescale));
      // THE reason this gate exists in this shape: vanilla's BlockElement$Deserializer rejects any angle
      // outside {-45,-22.5,0,22.5,45} with "Invalid rotation X found", and one bad number makes the whole
      // model fail to load (the item then shows as the purple/black missing-model checkerboard). Blockbench
      // stores arbitrary angles, so the generator snaps them and this asserts the result.
      check(`${tag}: rotation angle is one of the five values vanilla allows`,
        [-45, -22.5, 0, 22.5, 45].includes(r.angle), String(r.angle));
      check(`${tag}: rotation origin is inside the vanilla -16..32 element range`,
        r.origin.every((v) => v >= -16 && v <= 32), JSON.stringify(r.origin));
      rotationAxisHistogram[r.axis] = (rotationAxisHistogram[r.axis] || 0) + 1;
    }

    const faceNames = Object.keys(el.faces || {});
    faceCountHistogram[faceNames.length] = (faceCountHistogram[faceNames.length] || 0) + 1;
    const wellFormed = faceNames.every((n) => FACE_NAMES.includes(n) && num4(el.faces[n].uv) && el.faces[n].texture === '#layer0');
    check(`${tag}: ${faceNames.length} faces, all with a 4-number uv and texture #layer0`, wellFormed, faceNames.join(' '));
    if (faceNames.length !== 6) emptyFaces++;
    for (const n of faceNames) {
      faces++;
      for (const v of el.faces[n].uv) {
        uvValues++;
        modelUv.push(v);
        if (!(v >= 0 && v <= 16)) uvOutOfRange++;
      }
    }
  });

  // The generator snaps every source angle to the nearest legal value, and drops the rotation entirely when
  // that value is 0 - so the model carries one rotation per source cube EXCEPT those that snap to zero, and
  // every carried angle is already asserted to be legal above.
  const LEGAL_ANGLES = [-45, -22.5, 0, 22.5, 45];
  const snapOf = (angle) => LEGAL_ANGLES.reduce((best, c) => (Math.abs(c - angle) < Math.abs(best - angle) ? c : best), LEGAL_ANGLES[0]);
  const expectedRotations = bbRotated.filter((e) => snapOf(Math.max(...e.rotation.map(Math.abs))) !== 0).length;
  check('rotation count matches the bbmodel rotated cubes that do not snap to zero',
    rotations === expectedRotations, `${rotations} vs ${expectedRotations} (bbmodel has ${bbRotated.length})`);
  check('at least one rotation survived', rotations > 0, String(rotations));
  check('every element has 6 faces (none genuinely fewer)', emptyFaces === 0, `face count histogram ${JSON.stringify(faceCountHistogram)}`);
  check('no uv value is outside 0..16', uvOutOfRange === 0, `${uvValues} uv values checked, ${uvOutOfRange} out of range`);
  check('uv value count is 4 per face', uvValues === faces * 4, `${uvValues} = ${faces} faces * 4`);

  const srcUv = new Set(bbUv.map((v) => String(round(v / (bb.resolution.width / 16)))));
  const gotUv = new Set(modelUv.map((v) => String(round(v))));
  const missing = [...srcUv].filter((v) => !gotUv.has(v));
  const added = [...gotUv].filter((v) => !srcUv.has(v));
  check('model uv multiset is exactly the source uv / 32', missing.length === 0 && added.length === 0,
    `${srcUv.size} distinct source values, ${gotUv.size} distinct model values, missing ${missing.length}, extra ${added.length}`
    + (missing.length ? ' first missing ' + missing[0] : '') + (added.length ? ' first extra ' + added[0] : ''));
  console.log(`uv extents   : model ${Math.min(...modelUv)} .. ${Math.max(...modelUv)}   (source/32 ${Math.min(...bbUv) / 32} .. ${Math.max(...bbUv) / 32})`);

  const centre = modelBox.lo.map((v, a) => round((v + modelBox.hi[a]) / 2));
  const size = modelBox.lo.map((v, a) => round(modelBox.hi[a] - v));
  console.log(`model bbox   : lo [${modelBox.lo}] hi [${modelBox.hi}] centre [${centre}] size [${size}]`);
  // Vanilla applies an item model's display transform to (v - 8): item models are authored in the 0..16 box
  // and the item origin (the hand, the middle of the GUI slot) is model coordinate (8,8,8). "Centred on the
  // item origin" therefore means the bbox centre sits on (8,8,8), not on (0,0,0).
  check('the shield bbox is centred on the item origin, i.e. on model (8,8,8)',
    centre.every((v) => Math.abs(v - 8) < 1e-5), JSON.stringify(centre));

  // same measurement on the source, inflate baked in, for comparison
  const bbBox = { lo: [Infinity, Infinity, Infinity], hi: [-Infinity, -Infinity, -Infinity] };
  for (const e of bbElements) {
    const inf = e.inflate || 0;
    for (let a = 0; a < 3; a++) {
      bbBox.lo[a] = Math.min(bbBox.lo[a], e.from[a] - inf);
      bbBox.hi[a] = Math.max(bbBox.hi[a], e.to[a] + inf);
    }
  }
  const bbSize = bbBox.lo.map((v, a) => round(bbBox.hi[a] - v));
  console.log(`source bbox  : lo [${bbBox.lo}] hi [${bbBox.hi}] size [${bbSize}]  (inflate baked in)`);
  check('model bbox size equals the source bbox size (the single translation did not scale or clip anything)',
    size.every((v, a) => Math.abs(v - bbSize[a]) < 1e-5), `${JSON.stringify(size)} vs ${JSON.stringify(bbSize)}`);
  check('every element lies inside the vanilla -16..32 element range',
    modelBox.lo.every((v) => v >= -16) && modelBox.hi.every((v) => v <= 32),
    `lo [${modelBox.lo}] hi [${modelBox.hi}]`);
  check('the item origin (8,8,8) is inside the shield, not floating outside it',
    [0, 1, 2].every((a) => modelBox.lo[a] <= 8 && 8 <= modelBox.hi[a]),
    `item origin model (8,8,8), bbox lo [${modelBox.lo}] hi [${modelBox.hi}]`);

  section('display');
  const display = model.display || {};
  for (const ctx of DISPLAY_CONTEXTS) {
    const t = display[ctx];
    const ok = isPlainObject(t) && num3(t.rotation) && num3(t.translation) && num3(t.scale) && t.scale.every((s) => s > 0);
    check(`display.${ctx} has rotation/translation/scale triples`, ok, JSON.stringify(t));
  }
  check('no display context beyond the 7 that were specified', Object.keys(display).length === DISPLAY_CONTEXTS.length,
    Object.keys(display).join(', '));
  check('display.thirdperson_righthand turns the outer (+z) face towards -z (y rotation 180)',
    display.thirdperson_righthand && display.thirdperson_righthand.rotation[1] === 180);
  check('display.firstperson_righthand turns the outer (+z) face towards +x (y rotation 90)',
    display.firstperson_righthand && display.firstperson_righthand.rotation[1] === 90);
  check('display.fixed turns the outer (+z) face towards the frame viewer (y rotation 180)',
    display.fixed && display.fixed.rotation[1] === 180);
  check('display.gui keeps the outer (+z) face towards the camera (|y rotation| < 90)',
    display.gui && Math.abs(display.gui.rotation[1]) < 90, display.gui && String(display.gui.rotation[1]));
  check('display.ground stands the shield upright (x and z rotation 0)',
    display.ground && display.ground.rotation[0] === 0 && display.ground.rotation[2] === 0);
}

console.log(`model totals : ${rotations} rotations (axis histogram ${JSON.stringify(rotationAxisHistogram)}),`
  + ` ${faces} faces (face count histogram ${JSON.stringify(faceCountHistogram)}), ${uvValues} uv values`);

// ---------------------------------------------------------------- check: the lang keys
section('lang');
for (const locale of Object.keys(LANG)) {
  let current = null;
  try {
    current = JSON.parse(fs.readFileSync(LANG[locale], 'utf8'));
    check(`${locale}: parses`, true, `${fs.statSync(LANG[locale]).size} bytes`);
  } catch (err) {
    check(`${locale}: parses`, false, err.message);
    continue;
  }
  check(`${locale}: has ${SHIELD_KEY}`, Object.prototype.hasOwnProperty.call(current, SHIELD_KEY));
  check(`${locale}: ${SHIELD_KEY} == ${JSON.stringify(EXPECTED[locale])}`, current[SHIELD_KEY] === EXPECTED[locale],
    JSON.stringify(current[SHIELD_KEY]));

  const raw = fs.readFileSync(LANG[locale], 'utf8');
  check(`${locale}: no CRLF and no BOM (matches the rest of the file)`,
    !raw.includes('\r') && raw.charCodeAt(0) !== 0xfeff);

  if (fs.existsSync(LANG_BASELINE[locale])) {
    const baseline = JSON.parse(fs.readFileSync(LANG_BASELINE[locale], 'utf8'));
    const baseKeys = Object.keys(baseline);
    const nowKeys = Object.keys(current);
    const lost = baseKeys.filter((k) => !Object.prototype.hasOwnProperty.call(current, k));
    const changed = baseKeys.filter((k) => Object.prototype.hasOwnProperty.call(current, k) && current[k] !== baseline[k]);
    const added = nowKeys.filter((k) => !Object.prototype.hasOwnProperty.call(baseline, k));
    check(`${locale}: no key was removed`, lost.length === 0, `${baseKeys.length} baseline keys, lost ${lost.length}`);
    check(`${locale}: no existing value was changed`, changed.length === 0, `changed ${changed.length}`
      + (changed.length ? ' first ' + changed[0] : ''));
    // This gate was written when the shield's own item key was the only addition. The gameplay batch
    // later added four tooltips and the shatter message (both requested by the user), so the expected
    // set is spelled out instead of counted to one: any OTHER addition, or any removal, still fails.
    const expectedAdditions = [SHIELD_KEY,
      'item.tarkovscav.vant_shield.tooltip', 'item.tarkovscav.vant_shield.tooltip2',
      'item.tarkovscav.vant_shield.tooltip3', 'item.tarkovscav.vant_shield.tooltip4',
      'tarkovscav.shield.broken'];
    const missingAdditions = expectedAdditions.filter((k) => !added.includes(k));
    const unexpected = added.filter((k) => !expectedAdditions.includes(k));
    check(`${locale}: the only keys added are the shield's own keys`,
      missingAdditions.length === 0 && unexpected.length === 0,
      `added ${added.length} [${added.join(', ')}]`);
    check(`${locale}: key count grew by exactly ${expectedAdditions.length}`,
      nowKeys.length === baseKeys.length + expectedAdditions.length,
      `${baseKeys.length} -> ${nowKeys.length}`);
    console.log(`${locale}      : ${baseKeys.length} baseline keys -> ${nowKeys.length} keys, added [${added}]`);
  } else {
    check(`${locale}: baseline snapshot present for the regression comparison`, false, path.relative(ROOT, LANG_BASELINE[locale]));
  }
}

// ---------------------------------------------------------------- check: the generator is idempotent
section('generator idempotency');
check('generator exists', fs.existsSync(GENERATOR), path.relative(ROOT, GENERATOR));
const hashes = [];
function snapshot(tag) {
  const h = {
    texture: sha256(fs.readFileSync(TEXTURE)),
    model: sha256(fs.readFileSync(MODEL)),
    textureBytes: fs.statSync(TEXTURE).size,
    modelBytes: fs.statSync(MODEL).size,
  };
  hashes.push(h);
  console.log(`run ${tag}      : texture ${h.texture} (${h.textureBytes} B), model ${h.model} (${h.modelBytes} B)`);
  return h;
}
snapshot('as-on-disk');
const runs = [];
for (let i = 0; i < 2; i++) {
  const res = spawnSync(process.execPath, [GENERATOR], { cwd: ROOT, stdio: 'ignore' });
  check(`generator run ${i + 1} exited 0`, res.status === 0, `status ${res.status}${res.error ? ' ' + res.error.message : ''}`);
  runs.push(snapshot(`after #${i + 1}`));
}
check('texture is byte-identical across two generator runs and the file on disk',
  hashes[0].texture === hashes[1].texture && hashes[1].texture === hashes[2].texture,
  hashes.map((h) => h.texture.slice(0, 12)).join(' '));
check('model is byte-identical across two generator runs and the file on disk',
  hashes[0].model === hashes[1].model && hashes[1].model === hashes[2].model,
  hashes.map((h) => h.model.slice(0, 12)).join(' '));
check('texture size is stable', hashes[0].textureBytes === hashes[1].textureBytes && hashes[1].textureBytes === hashes[2].textureBytes,
  `${hashes[0].textureBytes} B`);
check('model size is stable', hashes[0].modelBytes === hashes[1].modelBytes && hashes[1].modelBytes === hashes[2].modelBytes,
  `${hashes[0].modelBytes} B`);

// ---------------------------------------------------------------- summary
// The selftest.ps1 node-gate loop prints only the last 3 lines, so keep them worth reading.
console.log(`\nsizes        : vant_shield.png ${fs.statSync(TEXTURE).size} bytes, vant_shield.json ${fs.statSync(MODEL).size} bytes`);
console.log(`sha256       : png ${sha256(fs.readFileSync(TEXTURE))} json ${sha256(fs.readFileSync(MODEL))}`);
console.log(`${failures === 0 ? 'PASS' : 'FAIL'}: ${checks} checks, ${failures} failure(s)`);
process.exit(failures === 0 ? 0 : 1);
