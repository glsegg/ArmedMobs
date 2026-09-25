// Finds the faces that are (a) textured from fully transparent pixels and (b) actually EXPOSED to the
// camera in a given pose - i.e. holes you can see the world through.
//
//   node tools/scan_transparent_faces.js [geo.json] [texture.png] [animation.json]
//
// Why this exists: the report "at one 45 degree angle his upper body is replaced by a plate, picture
// frames, a sign, a flower and grass" is a *see-through* symptom, and the rig hides geometry the cheap
// way - by giving cubes UVs that point at transparent pixels - so those faces are discarded by the
// alpha test of RenderType#entityCutoutNoCull. A face nobody can see is harmless; a face that is
// exposed is a hole. Which is which depends on the pose, because the layered clothing (vest, bag,
// sleeve liners) sits on different bones and moves relative to each other when the clips play - so this
// does the test per pose: transform every cube, and for each transparent face cast a short ray outwards
// to see whether another cube covers it.
//
// Self-contained: it decodes the PNG itself (zlib + the five PNG filters), so the alpha data cannot
// drift away from the geometry it is tested against.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const GEO = process.argv[2] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json');
const TEX = process.argv[3] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'entity', 'scav.png');
const ANIM = process.argv[4] || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json');
const DEG = Math.PI / 180;
const FACES = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

// ------------------------------------------------------------------ PNG decoder (RGBA, 8 bit)
const decodePng = (file) => {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error(`${file} is not a PNG`);
  let offset = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colourType = 0;
  const idat = [];
  while (offset < buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('ascii', offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colourType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  if (bitDepth !== 8) throw new Error(`only 8-bit PNGs are supported (got ${bitDepth})`);
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colourType];
  if (!channels) throw new Error(`unsupported PNG colour type ${colourType}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const pixels = Buffer.alloc(width * height * 4);
  let previous = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? line[i - channels] : 0;
      const b = previous[i];
      const c = i >= channels ? previous[i - channels] : 0;
      switch (filter) {
        case 0: break;
        case 1: line[i] = (line[i] + a) & 0xff; break;
        case 2: line[i] = (line[i] + b) & 0xff; break;
        case 3: line[i] = (line[i] + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          const pred = pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
          line[i] = (line[i] + pred) & 0xff;
          break;
        }
        default: throw new Error(`unknown PNG filter ${filter}`);
      }
    }
    for (let x = 0; x < width; x++) {
      const src = x * channels;
      const dst = (y * width + x) * 4;
      if (colourType === 6) {
        pixels[dst] = line[src]; pixels[dst + 1] = line[src + 1]; pixels[dst + 2] = line[src + 2]; pixels[dst + 3] = line[src + 3];
      } else if (colourType === 2) {
        pixels[dst] = line[src]; pixels[dst + 1] = line[src + 1]; pixels[dst + 2] = line[src + 2]; pixels[dst + 3] = 255;
      } else if (colourType === 0) {
        pixels[dst] = pixels[dst + 1] = pixels[dst + 2] = line[src]; pixels[dst + 3] = 255;
      } else {
        pixels[dst] = pixels[dst + 1] = pixels[dst + 2] = line[src]; pixels[dst + 3] = line[src + 1];
      }
    }
    previous = line;
  }
  return { width, height, pixels };
};

// ------------------------------------------------------------------ 4x4 helpers
const I4 = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const mul = (a, b) => {
  const out = new Array(16).fill(0);
  for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) {
    let sum = 0;
    for (let k = 0; k < 4; k++) sum += a[r * 4 + k] * b[k * 4 + c];
    out[r * 4 + c] = sum;
  }
  return out;
};
const T = (x, y, z) => [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1];
const RX = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]; };
const RY = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]; };
const RZ = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; };
const boneR = (rot) => mul(mul(RZ(rot[2]), RY(rot[1])), RX(rot[0]));
const xform = (m, v) => [
  m[0] * v[0] + m[1] * v[1] + m[2] * v[2] + m[3],
  m[4] * v[0] + m[5] * v[1] + m[6] * v[2] + m[7],
  m[8] * v[0] + m[9] * v[1] + m[10] * v[2] + m[11],
];
const xdir = (m, v) => [m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[4] * v[0] + m[5] * v[1] + m[6] * v[2], m[8] * v[0] + m[9] * v[1] + m[10] * v[2]];

const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const bones = geo.bones || [];
const byName = new Map(bones.map((b) => [b.name, b]));
const anims = JSON.parse(fs.readFileSync(ANIM, 'utf8')).animations;
const texture = decodePng(TEX);

const firstRotation = (clipName, boneName) => {
  const clip = anims[clipName];
  const channel = clip && clip.bones && clip.bones[boneName] && clip.bones[boneName].rotation;
  if (!channel) return null;
  let value = Array.isArray(channel) ? channel : Object.values(channel)[0];
  if (value && typeof value === 'object' && !Array.isArray(value)) value = value.post || value.pre || value.value;
  if (!Array.isArray(value)) return null;
  return value.map((v) => (typeof v === 'number' ? v : 0));
};

/** Fully transparent when every pixel of the face's UV rect has alpha < 26 (the cutout threshold). */
const faceIsTransparent = (cube, faceName) => {
  const face = cube.uv && cube.uv[faceName];
  if (!face) return false;
  const [u, v] = face.uv;
  const [w, h] = face.uv_size;
  const w0 = Math.max(1, Math.abs(Math.round(w)));
  const h0 = Math.max(1, Math.abs(Math.round(h)));
  for (let y = 0; y < h0; y++) {
    for (let x = 0; x < w0; x++) {
      const px = Math.min(texture.width - 1, Math.max(0, u + (w >= 0 ? x : -x)));
      const py = Math.min(texture.height - 1, Math.max(0, v + (h >= 0 ? y : -y)));
      if (texture.pixels[(py * texture.width + px) * 4 + 3] >= 26) return false;
    }
  }
  return true;
};

/** Model-space matrix per bone for one pose (rest + the clip's first keyframe). */
const chainMatrix = (boneName, clipName) => {
  const chain = [];
  for (let b = byName.get(boneName); b; b = b.parent ? byName.get(b.parent) : null) chain.push(b);
  chain.reverse();
  let m = I4();
  for (const bone of chain) {
    const rest = bone.rotation || [0, 0, 0];
    const clipRot = firstRotation(clipName, bone.name);
    const rot = clipRot ? clipRot.map((v, i) => v + rest[i]) : rest;
    const piv = bone.pivot || [0, 0, 0];
    m = mul(m, mul(T(piv[0] / 16, piv[1] / 16, piv[2] / 16), mul(boneR(rot), T(-piv[0] / 16, -piv[1] / 16, -piv[2] / 16))));
  }
  return m;
};

/** Every cube carried by the given bone, as {bone, cube, index, matrix, aabb}. */
const cubesOf = (bone, clipName) => {
  const m = chainMatrix(bone.name, clipName);
  const out = [];
  (bone.cubes || []).forEach((cube, index) => {
    const lo = cube.origin.map((n, i) => Math.min(n, n + cube.size[i]));
    const hi = cube.origin.map((n, i) => Math.max(n, n + cube.size[i]));
    const corners = [];
    for (const x of [lo[0], hi[0]]) for (const y of [lo[1], hi[1]]) for (const z of [lo[2], hi[2]]) {
      corners.push(xform(m, [x / 16, y / 16, z / 16]));
    }
    const box = {
      lo: [Math.min(...corners.map((c) => c[0])), Math.min(...corners.map((c) => c[1])), Math.min(...corners.map((c) => c[2]))],
      hi: [Math.max(...corners.map((c) => c[0])), Math.max(...corners.map((c) => c[1])), Math.max(...corners.map((c) => c[2]))],
    };
    out.push({ bone: bone.name, cube, index, matrix: m, box });
  });
  return out;
};

const HIDDEN = ['Gun3', 'Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money',
  'Hat1', 'hat3', 'YanJing', 'Yan']; // the shipped defaults, from selftest_accessories.js
const parentOf = new Map(bones.map((b) => [b.name, b.parent]));
const underHidden = (name) => {
  let cur = name;
  while (cur) {
    if (HIDDEN.includes(cur)) return true;
    cur = parentOf.get(cur);
  }
  return false;
};

const allCubes = bones.filter((b) => !underHidden(b.name)).flatMap((b) => (b.cubes || []).map((cube, index) => ({ bone: b.name, cube, index })));
console.log(`${GEO}
  texture ${texture.width}x${texture.height}, ${bones.length} bones, ${allCubes.length} rendered cube(s) after the shipped hidden set`);

// how much of the atlas is unusable (fully transparent), for context
let transparentPixels = 0;
let opaquePixels = 0;
let darkTransparent = 0;
for (let i = 0; i < texture.width * texture.height; i++) {
  if (texture.pixels[i * 4 + 3] < 26) {
    transparentPixels++;
    if (texture.pixels[i * 4] < 8 && texture.pixels[i * 4 + 1] < 8 && texture.pixels[i * 4 + 2] < 8) darkTransparent++;
  } else {
    opaquePixels++;
  }
}
console.log(`  atlas: ${opaquePixels} opaque pixel(s), ${transparentPixels} fully transparent, of which ${darkTransparent} are also black (what a no-alpha-test render type would show there)`);

/** Slab-method ray/AABB intersection; returns the distance to the first hit, or null. */
const rayBox = (origin, direction, box) => {
  let tmin = 0;
  let tmax = Infinity;
  for (let axis = 0; axis < 3; axis++) {
    const d = direction[axis];
    const lo = box.lo[axis];
    const hi = box.hi[axis];
    if (Math.abs(d) < 1e-9) {
      if (origin[axis] < lo || origin[axis] > hi) return null;
      continue;
    }
    let t1 = (lo - origin[axis]) / d;
    let t2 = (hi - origin[axis]) / d;
    if (t1 > t2) { const tmp = t1; t1 = t2; t2 = tmp; }
    tmin = Math.max(tmin, t1);
    tmax = Math.min(tmax, t2);
    if (tmin > tmax) return null;
  }
  return tmin;
};

const POSES = ['(rest)', 'tac:hold:rifle', 'tac:aim:rifle', 'tac:walk'];
const REACH = 1.5; // blocks: how far outwards another cube still counts as "covering" this face
let totalExposed = 0;
let failures = 0;
const byDirection = {};
const totals = {};
for (const pose of POSES) {
  const clip = pose === '(rest)' ? null : pose;
  const perBone = new Map();
  const perFace = {};
  let exposed = 0;
  let covered = 0;
  let interiorOnly = 0;
  const samples = [];

  const worldCubes = [];
  for (const bone of bones) {
    if (underHidden(bone.name)) continue;
    worldCubes.push(...cubesOf(bone, clip));
  }

  for (const entry of worldCubes) {
    for (const faceName of Object.keys(FACES)) {
      if (!faceIsTransparent(entry.cube, faceName)) continue;
      const normal = xdir(entry.matrix, FACES[faceName]);
      const unit = normal.map((n) => n / (Math.hypot(...normal) || 1));
      const centre = [
        (entry.box.lo[0] + entry.box.hi[0]) / 2,
        (entry.box.lo[1] + entry.box.hi[1]) / 2,
        (entry.box.lo[2] + entry.box.hi[2]) / 2,
      ];
      const origin = centre.map((c, axis) => c + unit[axis] * 0.01);
      // 1. is the face on the outer hull at all? (if another layer covers it, nobody can see it)
      let onHull = true;
      for (const other of worldCubes) {
        if (other === entry) continue;
        const hit = rayBox(origin, unit, other.box);
        if (hit !== null && hit > 1e-4 && hit < REACH) { onHull = false; break; }
      }
      if (!onHull) {
        covered++;
        continue;
      }
      // 2. looking at this face from outside, what is behind it? If the ray going INTO the model hits
      //    an opaque cube, the viewer sees the model's own interior - ugly, but not a hole. If it
      //    passes through the whole model, the viewer sees the WORLD: that is the reported symptom.
      const inward = unit.map((n) => -n);
      const inwardOrigin = centre.map((c, axis) => c + inward[axis] * 0.01);
      let interior = null;
      for (const other of worldCubes) {
        if (other === entry) continue;
        const hit = rayBox(inwardOrigin, inward, other.box);
        if (hit !== null && hit > 1e-4 && hit < REACH) { interior = other; break; }
      }
      if (interior) {
        interiorOnly++;
        continue;
      }
      exposed++;
      perBone.set(entry.bone, (perBone.get(entry.bone) || 0) + 1);
      perFace[faceName] = (perFace[faceName] || 0) + 1;
      byDirection[faceName] = (byDirection[faceName] || 0) + 1;
      if (samples.length < 10) {
        samples.push(`${entry.bone}[${entry.index}].${faceName} uv=${JSON.stringify(entry.cube.uv[faceName].uv)}`
          + ` normal=(${unit.map((n) => n.toFixed(2)).join(',')})`
          + ` centre=(${centre.map((n) => n.toFixed(2)).join(',')})`);
      }
    }
  }
  totalExposed += exposed;
  console.log(`\n  pose ${pose}: ${exposed} SEE-THROUGH face(s) (the world is visible through them),`
    + ` ${interiorOnly} transparent face(s) that only show the model's own interior,`
    + ` ${covered} buried inside the model`);
  if (exposed) {
    console.log(`      by face direction: ${Object.entries(perFace).map(([f, n]) => `${f}=${n}`).join(' ')}`);
    for (const [bone, count] of [...perBone.entries()].sort((a, b) => b[1] - a[1])) {
      console.log(`      ${bone.padEnd(16)} ${count}`);
    }
    for (const sample of samples) console.log(`        e.g. ${sample}`);
  }
  totals[pose] = exposed;
}

console.log('\nsummary:');
for (const pose of POSES) console.log(`  ${pose.padEnd(18)} holes = ${totals[pose]}`);
console.log(`  face directions over all poses: ${Object.entries(byDirection).map(([f, n]) => `${f}=${n}`).join(' ')}`);
const restExposed = totals['(rest)'];
if (restExposed > 0) {
  console.log(`  -> the rig already has ${restExposed} see-through face(s) at rest; each is only visible from the angles that look into it,`);
  console.log('     and the layers move with the clips, so the set changes per pose (which is why this is pose dependent).');
} else {
  console.log('  -> no see-through faces at rest');
}
console.log(failures ? `\n${failures} check(s) FAILED` : '');
process.exit(0);
