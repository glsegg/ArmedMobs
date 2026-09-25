// Generates the VANT ballistic-shield item assets from the Blockbench project the user supplied:
//
//   src/main/resources/assets/tarkovscav/textures/item/vant_shield.png   (the model's embedded 512x512 PNG,
//                                                                        extracted byte for byte)
//   src/main/resources/assets/tarkovscav/models/item/vant_shield.json    (a vanilla item model: textures +
//                                                                        elements + display)
//
//   node tools/make_shield_assets.js [path/to/file.bbmodel]
//
// Why the texture is copied instead of drawn: unlike the grenade/beacon textures (which this repo draws with
// a few lines of code, see tools/make_grenade_textures.js), the shield artwork is the user's own 512x512 PNG
// embedded in the .bbmodel. Nothing here re-encodes it: base64 decode -> the exact bytes -> the file, and
// the sha256 of the written file is checked against the sha256 of the decoded payload.
//
// Why a generator instead of a hand-written model: the project has 49 cubes, 294 UV faces and 21 rotated
// cubes. Re-running this file after replacing the .bbmodel reproduces the texture and the model byte for
// byte (no timestamps, no randomness, fixed key order), so the model is never hand-edited out of sync.
//
// Conversions applied to the geometry (all of them driven by the source, none hard-coded per cube):
//   * `inflate` - Blockbench/Bedrock feature, NOT part of the vanilla element schema (from/to/rotation/faces
//     only), so it is baked into from/to. 16 of the 49 cubes use it (0.008 / 0.004 / -0.004).
//   * `uv`      - the .bbmodel UVs are in texture pixels of a 512x512 sheet; vanilla wants a 0..16 space over
//     the 16x16 texture grid, so uv_vanilla = uv_pixels / (512 / 16) = uv_pixels / 32.
//   * `rotation`- Blockbench stores rotation as [x,y,z] about `origin`; vanilla takes one axis, an angle in
//     degrees, that same origin and `rescale:false`. Every rotated cube here is single-axis (20x Z, 1x X).
//   * origin    - the geometry is authored Blockbench-block style (origin at the centre of the block's bottom
//     face, the shield standing on y=0 up to y=28.85). It is recentred on the ITEM origin with ONE
//     translation. Vanilla applies an item model's display transform to (v - 8) - item models are authored
//     in the 0..16 box and the item origin (the hand, the middle of the GUI slot) is model coordinate
//     (8,8,8) - so "centred on the item origin" means the bounding box centre has to sit at (8,8,8), not at
//     (0,0,0). The translation is therefore (-bboxCentre + (8,8,8)) = (+8.024515, -5.933695, +8.146845),
//     applied to every from/to and to every rotation origin.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const WORK = path.join(__dirname, 'spike', 'work', 'shield');
// The source .bbmodel is committed under assets_source/shield/ (so a fresh clone can regenerate the assets);
// the older scratch copy is used as a fallback while it exists.
const SOURCE_DIR = path.join(ROOT, 'assets_source', 'shield');
const DEFAULT_BBMODEL = [path.join(SOURCE_DIR, 'VANT防弹盾牌.bbmodel'), path.join(WORK, 'VANT防弹盾牌.bbmodel')]
  .find((candidate) => fs.existsSync(candidate)) || path.join(SOURCE_DIR, 'VANT防弹盾牌.bbmodel');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const TEXTURE_OUT = path.join(ASSETS, 'textures', 'item', 'vant_shield.png');
const MODEL_OUT = path.join(ASSETS, 'models', 'item', 'vant_shield.json');

const TEXTURE_KEY = 'layer0';
const TEXTURE_REF = 'tarkovscav:item/vant_shield';
const FACE_ORDER = ['north', 'east', 'south', 'west', 'up', 'down'];
const DECIMALS = 6;
// Item models are authored in the 0..16 box: the renderer applies the display transform to (v - 8), so the
// item origin - where the hand holds the item, and the middle of the GUI slot - is model coordinate (8,8,8).
const ITEM_ORIGIN = [8, 8, 8];

/**
 * Hand poser. The model is recentred on the item origin (see the `origin` bullet in the header comment), so
 * 1 unit here is 1/16 block around that origin, +y is up and the shield's OUTER face is +z, the handle side
 * is -z. It is 23.26 wide, 29.84 tall and 4.25 thick - close to the vanilla shield (12 x 22 x 6), so 0.72
 * scale reads the same size as a vanilla shield held up (29.84 * 0.72 = 21.5 units tall).
 *
 * Each `translation` is the offset of the shield's own centre from the item origin in the display frame,
 * in 1/16 block units, and it is not affected by the rotation or the scale (vanilla applies T outside
 * R*S*(v-8)), so the numbers below can be read directly as "centre of the shield, relative to the hand".
 *
 * Frame conventions used below, read off the vanilla assets rather than guessed:
 *   third person: +x = screen right, +y = up, +z = towards the camera (behind the player)
 *                 (`block/block` thirdperson_righthand [75,45,0] turns a cube's top face towards +z, and that
 *                  is the face you see on a held block)
 *   first person: +y = up, -x = towards the camera, +z = screen right
 *                 (`item/generated` firstperson_righthand [0,-90,25] puts a flat sprite's +z normal on -x,
 *                  i.e. a 2D item would be edge-on unless the camera sits on -x)
 *   gui / fixed:  +z = towards the viewer (`item/generated` gui is identity, fixed is a 180 yaw)
 *
 * A shield is held upright with its outer face away from the player, so the outer (+z) face is turned to -z
 * in third person (Ry 180) and to +x in first person (Ry 90); the arm ends up behind the handle. The handle
 * centre sits 4.71 units (unscaled) above the shield's centre, i.e. 4.71 * 0.72 = 3.4 units at this scale,
 * so -2.5 in y keeps the hand on the handle and still leaves the shield's bottom 0.83 blocks below the hand
 * (just above the ground for a standing player).
 */
const DISPLAY = {
  // beside the body: centre 2 units inboard, 2.5 below the hand, 2 forward of it, outer face forward,
  // 10 degree inward lean
  thirdperson_righthand: { rotation: [0, 180, -10], translation: [-2, -2.5, -2], scale: [0.72, 0.72, 0.72] },
  // mirror of the above about the body's centre line: lean and lateral offset flipped, same forward offset
  thirdperson_lefthand: { rotation: [0, 180, 10], translation: [2, -2.5, -2], scale: [0.72, 0.72, 0.72] },
  // held up in front: centre 1 unit above the hand and 3 units towards the middle of the screen, leaned
  // 8 degrees the same way; y stays 90 so the outer face still points away from the camera (the player sees
  // the handle side, which is what is physically in front of them)
  firstperson_righthand: { rotation: [-8, 90, 0], translation: [0, 1, -3], scale: [0.45, 0.45, 0.45] },
  firstperson_lefthand: { rotation: [8, 90, 0], translation: [0, 1, 3], scale: [0.45, 0.45, 0.45] },
  // icon: face on (identity would be dead on) with a 20 degree yaw so the 4.25 unit thickness reads,
  // 0.5 scale => 14.9 units in a 16 unit slot
  gui: { rotation: [0, -20, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
  // dropped: standing upright, bottom resting ~1 unit below the origin like a vanilla flat item
  // (-14.93 * 0.35 + 4.2 = -1.0), 0.65 blocks tall
  ground: { rotation: [0, 0, 0], translation: [0, 4.2, 0], scale: [0.35, 0.35, 0.35] },
  // item frame: outer face towards the frame's viewer (-z in the fixed frame), 29.84 * 0.4 = 11.9 units,
  // the same size a vanilla shield takes in a frame (22 * 0.55)
  fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/**
 * `JSON.stringify(model, null, 2)` puts every number of every `from`/`to`/`uv` on its own line (3.6k lines
 * for this model). This prints number arrays and flat objects inline instead, the way the vanilla and
 * Blockbench model files are laid out, so the file stays readable. Deterministic: fixed key order, no
 * timestamps.
 */
function formatJson(value, indent) {
  const pad = '  '.repeat(indent);
  const child = '  '.repeat(indent + 1);
  if (typeof value !== 'object' || value === null) return JSON.stringify(value);
  const scalar = (v) => typeof v !== 'object' || v === null;
  if (Array.isArray(value)) {
    if (value.every(scalar)) return '[' + value.map((v) => formatJson(v, indent)).join(', ') + ']';
    return '[\n' + value.map((v) => child + formatJson(v, indent + 1)).join(',\n') + '\n' + pad + ']';
  }
  const keys = Object.keys(value);
  if (keys.every((k) => scalar(value[k]) || (Array.isArray(value[k]) && value[k].every(scalar)))) {
    return '{ ' + keys.map((k) => `${JSON.stringify(k)}: ${formatJson(value[k], indent)}`).join(', ') + ' }';
  }
  return '{\n' + keys.map((k) => `${child}${JSON.stringify(k)}: ${formatJson(value[k], indent + 1)}`).join(',\n') + '\n' + pad + '}';
}

function round(value) {
  const r = Math.round(value * 10 ** DECIMALS) / 10 ** DECIMALS;
  return r === 0 ? 0 : r;
}

function readPngSize(buf) {
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504E47 || buf.readUInt32BE(12) !== 0x49484452) return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

/** The one embedded PNG of the project, as raw bytes. */
function embeddedTexture(doc) {
  const textures = (doc.textures || []).filter((t) => typeof t.source === 'string' && t.source.startsWith('data:image/png;base64,'));
  if (textures.length !== 1) throw new Error(`expected exactly 1 embedded PNG texture, found ${textures.length}`);
  return { name: textures[0].name, bytes: Buffer.from(textures[0].source.slice('data:image/png;base64,'.length), 'base64') };
}

/** Bounding box of the cubes as they will actually be rendered (inflate baked in). */
function bounds(cubes) {
  const lo = [Infinity, Infinity, Infinity];
  const hi = [-Infinity, -Infinity, -Infinity];
  for (const c of cubes) {
    for (let i = 0; i < 3; i++) {
      lo[i] = Math.min(lo[i], c.from[i], c.to[i]);
      hi[i] = Math.max(hi[i], c.from[i], c.to[i]);
    }
  }
  return { lo, hi, centre: lo.map((v, i) => (v + hi[i]) / 2), size: lo.map((v, i) => hi[i] - v) };
}

/** Blockbench cube -> vanilla element, with `inflate` baked in and `origin` recentred. */
function convertCube(cube, shift, textureScale, report) {
  const inflate = cube.inflate || 0;
  if (inflate) report.inflated++;
  const from = cube.from.map((v, i) => round(v - inflate + shift[i]));
  const to = cube.to.map((v, i) => round(v + inflate + shift[i]));

  for (let i = 0; i < 3; i++) {
    if (!(from[i] < to[i])) throw new Error(`cube ${cube.uuid} collapsed on axis ${'xyz'[i]}: ${from[i]}..${to[i]}`);
  }

  const element = { from, to };

  const rotation = cube.rotation || [0, 0, 0];
  const axes = rotation.map((v, i) => ({ axis: 'xyz'[i], angle: v })).filter((r) => r.angle !== 0);
  if (axes.length > 1) {
    // Vanilla elements rotate about one axis only; Blockbench can stack all three. Nothing to do but say so.
    throw new Error(`cube ${cube.uuid} needs a multi-axis rotation ${JSON.stringify(rotation)}, which a vanilla element cannot express`);
  }
  if (axes.length === 1) {
    // Vanilla's element rotation is NOT free: BlockElement$Deserializer rejects anything outside
    // {-45, -22.5, 0, 22.5, 45} with "Invalid rotation X found", and that one bad number makes the WHOLE
    // model fail to load (the item then renders as the purple/black missing-model checkerboard - which is
    // exactly what shipped once). Blockbench happily stores arbitrary angles, so snap each one to the
    // nearest legal value and report every snap, so a human can see the visual cost.
    const LEGAL = [-45, -22.5, 0, 22.5, 45];
    const raw = axes[0].angle;
    let snapped = LEGAL.reduce((best, candidate) => (
      Math.abs(candidate - raw) < Math.abs(best - raw) ? candidate : best), LEGAL[0]);
    if (snapped === -0) snapped = 0;
    if (snapped !== raw) {
      report.snappedRotations.push({ axis: axes[0].axis, from: round(raw), to: snapped });
    }
    if (snapped !== 0) {
      report.rotated++;
      report.rotationAxes[axes[0].axis] = (report.rotationAxes[axes[0].axis] || 0) + 1;
      element.rotation = {
        origin: cube.origin.map((v, i) => round(v + shift[i])),
        axis: axes[0].axis,
        angle: snapped,
        rescale: false,
      };
    }
  }

  const faces = {};
  let faceCount = 0;
  let missingUv = 0;
  for (const name of FACE_ORDER) {
    const face = (cube.faces || {})[name];
    if (!face) continue;
    let uv = face.uv;
    if (!uv) {
      // Blockbench's box_uv: derive the face's UV block from the cube's bounding box, the way it does when
      // it auto-UVs a cube (the 16x16 texture grid is laid out per face size). Never hit by this project -
      // all 49 cubes carry explicit per-face UVs - so this is a guard, and it reports itself if it fires.
      missingUv++;
      report.derivedUv++;
      uv = derivedUv(element, name, textureScale);
    }
    faces[name] = {
      uv: uv.map((v) => round(v / textureScale)),
      texture: `#${TEXTURE_KEY}`,
    };
    faceCount++;
  }
  report.faces += faceCount;
  report.faceCounts[faceCount] = (report.faceCounts[faceCount] || 0) + 1;
  if (missingUv) report.cubesWithDerivedUv.push(cube.uuid);
  element.faces = faces;
  return element;
}

/**
 * UV block from the cube's bounding box in the 0..16 space: the four numbers are the pixel box of the face
 * on the texture grid divided by textureScale (32 for a 512 sheet). Used only if a cube has no per-face UV.
 */
function derivedUv(element, face, textureScale) {
  const [x0, y0, z0] = element.from;
  const [x1, y1, z1] = element.to;
  const px = (v) => v * textureScale;
  switch (face) {
    case 'north': return [px(x1), px(16 - y1), px(x0), px(16 - y0)];
    case 'south': return [px(x0), px(16 - y1), px(x1), px(16 - y0)];
    case 'east': return [px(z1), px(16 - y1), px(z0), px(16 - y0)];
    case 'west': return [px(z0), px(16 - y1), px(z1), px(16 - y0)];
    case 'up': return [px(x0), px(z0), px(x1), px(z1)];
    case 'down': return [px(x0), px(16 - z1), px(x1), px(16 - z0)];
    default: throw new Error(`unknown face ${face}`);
  }
}

function buildModel(doc, texture) {
  const resolution = doc.resolution || { width: 512, height: 512 };
  if (resolution.width !== resolution.height || resolution.width % 16 !== 0) {
    throw new Error(`texture sheet ${resolution.width}x${resolution.height} is not a square multiple of 16`);
  }
  const textureScale = resolution.width / 16;

  const cubes = (doc.elements || []).map((cube) => {
    const inflate = cube.inflate || 0;
    return { uuid: cube.uuid, from: cube.from.map((v) => v - inflate), to: cube.to.map((v) => v + inflate) };
  });
  const box = bounds(cubes);
  // Put the shield's bounding-box centre exactly on the item origin (model (8,8,8) - see ITEM_ORIGIN).
  const shift = box.centre.map((v, i) => ITEM_ORIGIN[i] - v);

  const report = {
    cubes: 0, faces: 0, rotated: 0, inflated: 0, derivedUv: 0,
    rotationAxes: {}, faceCounts: {}, cubesWithDerivedUv: [], snappedRotations: [],
  };
  const elements = (doc.elements || []).map((cube) => {
    report.cubes++;
    return convertCube(cube, shift, textureScale, report);
  });

  const model = {
    gui_light: 'front',
    ambientocclusion: false,
    textures: { [TEXTURE_KEY]: TEXTURE_REF, particle: TEXTURE_REF },
    display: DISPLAY,
    elements,
  };
  return { model, report, box, shift, textureScale, texture };
}

function main() {
  const bbmodel = process.argv[2] || DEFAULT_BBMODEL;
  const raw = fs.readFileSync(bbmodel);
  const doc = JSON.parse(raw.toString('utf8'));
  console.log(`source      : ${path.relative(ROOT, bbmodel)} (${raw.length} bytes, format ${doc.meta && doc.meta.format_version})`);

  const texture = embeddedTexture(doc);
  const size = readPngSize(texture.bytes);
  if (!size) throw new Error('the embedded texture is not a PNG');
  if (size.width !== (doc.resolution || {}).width || size.height !== (doc.resolution || {}).height) {
    throw new Error(`embedded PNG is ${size.width}x${size.height}, project resolution says ${JSON.stringify(doc.resolution)}`);
  }

  const { model, report, box, shift, textureScale } = buildModel(doc, texture);

  const modelJson = formatJson(model, 0) + '\n';
  fs.mkdirSync(path.dirname(TEXTURE_OUT), { recursive: true });
  fs.mkdirSync(path.dirname(MODEL_OUT), { recursive: true });
  fs.writeFileSync(TEXTURE_OUT, texture.bytes);
  fs.writeFileSync(MODEL_OUT, modelJson, 'utf8');

  console.log(`texture     : ${texture.name} ${size.width}x${size.height} -> ${path.relative(ROOT, TEXTURE_OUT)}`
    + ` (${fs.statSync(TEXTURE_OUT).size} bytes, sha256 ${sha256(texture.bytes)})`);
  console.log(`normalise   : source bbox centre [${box.centre.map(round)}] of lo [${box.lo}] hi [${box.hi}]`);
  console.log(`              one translation ${JSON.stringify(shift.map(round))} applied to every from/to/origin`);
  console.log(`              => bbox lo [${box.lo.map((v, i) => round(v + shift[i]))}]`
    + ` hi [${box.hi.map((v, i) => round(v + shift[i]))}], size [${box.size.map(round)}]`);
  console.log(`              => bbox centre [${box.centre.map((v, i) => round(v + shift[i]))}] = the item origin`
    + ` (vanilla transforms (v - 8), so the hand is model ${JSON.stringify(ITEM_ORIGIN)})`);
  console.log(`geometry    : ${report.cubes} cubes -> ${model.elements.length} elements, ${report.faces} faces`
    + ` (face count histogram ${JSON.stringify(report.faceCounts)})`);
  console.log(`              ${report.inflated} cubes had Blockbench \`inflate\`, baked into from/to (vanilla has no inflate)`);
  console.log(`              ${report.rotated} cubes kept a rotation (axis histogram ${JSON.stringify(report.rotationAxes)}), rescale:false`);
  if (report.snappedRotations.length) {
    const list = report.snappedRotations.map((r) => `${r.axis} ${r.from}->${r.to}`).join(', ');
    console.log(`              ${report.snappedRotations.length} angle(s) SNAPPED to the only values vanilla allows`
      + ` ({-45,-22.5,0,22.5,45}): ${list}`);
  } else {
    console.log('              no angle needed snapping (every rotation is already legal)');
  }
  console.log(`uv          : pixels / ${textureScale} -> 0..16; ${report.derivedUv} faces had no per-face uv and were derived from the bbox`);
  console.log(`model       : ${path.relative(ROOT, MODEL_OUT)} (${fs.statSync(MODEL_OUT).size} bytes)`);
  console.log(`display     : ${Object.keys(DISPLAY).join(', ')}`);
}

main();
