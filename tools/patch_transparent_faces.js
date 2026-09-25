// One-off, re-runnable asset patch: closes the rig's SEE-THROUGH faces.
//
//   node tools/patch_transparent_faces.js [--dry]
//
// The author hides spare geometry the cheap way - a cube's face points at an empty (alpha 0) spot of
// the atlas, so RenderType#entityCutoutNoCull discards it. That is fine for a face buried inside the
// model, and wrong for a face on the outer hull: the pixel is dropped and the viewer sees straight
// through the model to the world behind. tools/scan_transparent_faces.js finds those, per pose, by
// ray casting; this rewrites ONLY those faces' UVs, taking the replacement from another face of the
// SAME cube (preferring the opposite face, which has the same uv_size orientation), and prints a
// before/after table. Nothing else about the geometry is touched: no cube moves, no bone changes, no
// bone is hidden.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const GEO = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json');
const TEX = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'entity', 'scav.png');
const ANIM = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json');
const BACKUP = path.join(__dirname, 'spike', 'work', 'scav.geo.before-transparent-patch.json');
const DRY = process.argv.includes('--dry');
const DEG = Math.PI / 180;
const FACES = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
const OPPOSITE = { north: 'south', south: 'north', east: 'west', west: 'east', up: 'down', down: 'up' };

// ------------------------------------------------------------------ PNG alpha (same decoder as the scan)
const decodePng = (file) => {
  const buf = fs.readFileSync(file);
  let offset = 8;
  let width = 0; let height = 0; let bitDepth = 0; let colourType = 0;
  const idat = [];
  while (offset < buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('ascii', offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); bitDepth = data[8]; colourType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    offset += 12 + length;
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colourType];
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
      if (filter === 1) line[i] = (line[i] + a) & 0xff;
      else if (filter === 2) line[i] = (line[i] + b) & 0xff;
      else if (filter === 3) line[i] = (line[i] + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a); const pb = Math.abs(p - b); const pc = Math.abs(p - c);
        line[i] = (line[i] + (pa <= pb && pa <= pc ? a : (pb <= pc ? b : c))) & 0xff;
      }
    }
    for (let x = 0; x < width; x++) {
      const src = x * channels;
      const dst = (y * width + x) * 4;
      if (colourType === 6) { pixels[dst] = line[src]; pixels[dst + 1] = line[src + 1]; pixels[dst + 2] = line[src + 2]; pixels[dst + 3] = line[src + 3]; }
      else { pixels[dst] = pixels[dst + 1] = pixels[dst + 2] = line[src]; pixels[dst + 3] = colourType === 4 ? line[src + 1] : 255; }
    }
    previous = line;
  }
  return { width, height, pixels };
};
const texture = decodePng(TEX);

// ------------------------------------------------------------------ matrix maths (same as the scan)
const I4 = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const mul = (a, b) => { const o = new Array(16).fill(0); for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) { let s = 0; for (let k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c]; o[r * 4 + c] = s; } return o; };
const T = (x, y, z) => [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1];
const RX = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]; };
const RY = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]; };
const RZ = (d) => { const c = Math.cos(d * DEG), s = Math.sin(d * DEG); return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; };
const boneR = (rot) => mul(mul(RZ(rot[2]), RY(rot[1])), RX(rot[0]));
const xform = (m, v) => [m[0] * v[0] + m[1] * v[1] + m[2] * v[2] + m[3], m[4] * v[0] + m[5] * v[1] + m[6] * v[2] + m[7], m[8] * v[0] + m[9] * v[1] + m[10] * v[2] + m[11]];
const xdir = (m, v) => [m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[4] * v[0] + m[5] * v[1] + m[6] * v[2], m[8] * v[0] + m[9] * v[1] + m[10] * v[2]];
const rayBox = (origin, direction, box) => {
  let tmin = 0; let tmax = Infinity;
  for (let axis = 0; axis < 3; axis++) {
    const d = direction[axis];
    if (Math.abs(d) < 1e-9) { if (origin[axis] < box.lo[axis] || origin[axis] > box.hi[axis]) return null; continue; }
    let t1 = (box.lo[axis] - origin[axis]) / d;
    let t2 = (box.hi[axis] - origin[axis]) / d;
    if (t1 > t2) { const tmp = t1; t1 = t2; t2 = tmp; }
    tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
    if (tmin > tmax) return null;
  }
  return tmin;
};

const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'));
const model = geo['minecraft:geometry'][0];
const bones = model.bones;
const byName = new Map(bones.map((b) => [b.name, b]));
const anims = JSON.parse(fs.readFileSync(ANIM, 'utf8')).animations;
const HIDDEN = ['Gun3', 'Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money', 'Hat1', 'hat3', 'YanJing', 'Yan'];
const parentOf = new Map(bones.map((b) => [b.name, b.parent]));
const underHidden = (name) => { let cur = name; while (cur) { if (HIDDEN.includes(cur)) return true; cur = parentOf.get(cur); } return false; };

const faceTransparent = (cube, faceName) => {
  const face = cube.uv && cube.uv[faceName];
  if (!face) return false;
  const [u, v] = face.uv;
  const [w, h] = face.uv_size;
  for (let y = 0; y < Math.max(1, Math.abs(Math.round(h))); y++) {
    for (let x = 0; x < Math.max(1, Math.abs(Math.round(w))); x++) {
      const px = Math.min(texture.width - 1, Math.max(0, u + (w >= 0 ? x : -x)));
      const py = Math.min(texture.height - 1, Math.max(0, v + (h >= 0 ? y : -y)));
      if (texture.pixels[(py * texture.width + px) * 4 + 3] >= 26) return false;
    }
  }
  return true;
};

const firstRotation = (clipName, boneName) => {
  const channel = clipName && anims[clipName] && anims[clipName].bones && anims[clipName].bones[boneName]
    && anims[clipName].bones[boneName].rotation;
  if (!channel) return null;
  let value = Array.isArray(channel) ? channel : Object.values(channel)[0];
  if (value && typeof value === 'object' && !Array.isArray(value)) value = value.post || value.pre || value.value;
  return Array.isArray(value) ? value.map((v) => (typeof v === 'number' ? v : 0)) : null;
};

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

/** The faces that are see-through in at least one tested pose (see the gate for the definition). */
const findHoles = () => {
  const poses = [null, 'tac:hold:rifle', 'tac:aim:rifle', 'tac:walk'];
  const REACH = 1.5;
  const holes = new Map();
  for (const pose of poses) {
    const world = [];
    for (const bone of bones) {
      if (underHidden(bone.name)) continue;
      const m = chainMatrix(bone.name, pose);
      (bone.cubes || []).forEach((cube, index) => {
        const lo = cube.origin.map((n, i) => Math.min(n, n + cube.size[i]));
        const hi = cube.origin.map((n, i) => Math.max(n, n + cube.size[i]));
        const corners = [];
        for (const x of [lo[0], hi[0]]) for (const y of [lo[1], hi[1]]) for (const z of [lo[2], hi[2]]) corners.push(xform(m, [x / 16, y / 16, z / 16]));
        world.push({
          bone: bone.name, cube, index, matrix: m,
          box: {
            lo: [0, 1, 2].map((a) => Math.min(...corners.map((c) => c[a]))),
            hi: [0, 1, 2].map((a) => Math.max(...corners.map((c) => c[a]))),
          },
        });
      });
    }
    for (const entry of world) {
      for (const faceName of Object.keys(FACES)) {
        if (!faceTransparent(entry.cube, faceName)) continue;
        const normal = xdir(entry.matrix, FACES[faceName]);
        const unit = normal.map((n) => n / (Math.hypot(...normal) || 1));
        const centre = [0, 1, 2].map((a) => (entry.box.lo[a] + entry.box.hi[a]) / 2);
        const outward = centre.map((c, a) => c + unit[a] * 0.01);
        const hullClear = !world.some((o) => o !== entry && (() => { const hit = rayBox(outward, unit, o.box); return hit !== null && hit > 1e-4 && hit < REACH; })());
        if (!hullClear) continue;
        const inward = unit.map((n) => -n);
        const inwardOrigin = centre.map((c, a) => c + inward[a] * 0.01);
        const blocked = world.some((o) => o !== entry && (() => { const hit = rayBox(inwardOrigin, inward, o.box); return hit !== null && hit > 1e-4 && hit < REACH; })());
        if (blocked) continue;
        const key = `${entry.bone}#${entry.index}.${faceName}`;
        if (!holes.has(key)) holes.set(key, { bone: entry.bone, index: entry.index, faceName, pose: pose || '(rest)' });
      }
    }
  }
  return holes;
};

const holes = findHoles();
console.log(`${GEO}\n  see-through faces found across [rest, tac:hold:rifle, tac:aim:rifle, tac:walk]: ${holes.size}`);
for (const hole of holes.values()) console.log(`    ${hole.bone}[${hole.index}].${hole.faceName} (first seen in ${hole.pose})`);
if (!holes.size) {
  console.log('  nothing to patch - the rig has no see-through faces');
  process.exit(0);
}

// ------------------------------------------------------------------ pick a replacement UV per face
const patches = [];
for (const hole of holes.values()) {
  const bone = byName.get(hole.bone);
  const cube = bone.cubes[hole.index];
  // 1. another face of the same cube (the opposite one first: same uv_size orientation).
  let donor = null;
  for (const candidate of [OPPOSITE[hole.faceName], ...Object.keys(FACES)]) {
    if (candidate === hole.faceName) continue;
    const face = cube.uv && cube.uv[candidate];
    if (!face || faceTransparent(cube, candidate)) continue;
    donor = { cube, face: candidate };
    break;
  }
  // 2. a cube the whole of which is hidden by its UVs (a "UV-hidden duplicate") has nothing to copy
  //    from itself, so borrow the same face direction from the nearest opaque cube of the same bone.
  if (!donor) {
    const centre = (c) => [0, 1, 2].map((a) => c.origin[a] + c.size[a] / 2);
    const mine = centre(cube);
    const ranked = bone.cubes
      .map((other, index) => ({ other, index }))
      .filter(({ other }) => other !== cube && other.uv && other.uv[hole.faceName] && !faceTransparent(other, hole.faceName))
      .map(({ other, index }) => {
        const theirs = centre(other);
        return { other, index, distance: Math.hypot(...[0, 1, 2].map((a) => theirs[a] - mine[a])) };
      })
      .sort((a, b) => a.distance - b.distance);
    if (ranked.length) {
      donor = { cube: ranked[0].other, face: hole.faceName, fromCubeIndex: ranked[0].index, distance: ranked[0].distance };
    }
  }
  patches.push({ ...hole, cube, donor });
}

console.log('\n  patch plan:');
for (const patch of patches) {
  const before = patch.cube.uv[patch.faceName];
  const donorFace = patch.donor ? patch.donor.cube.uv[patch.donor.face] : null;
  console.log(`    ${patch.bone}[${patch.index}].${patch.faceName}`
    + `  uv=${JSON.stringify(before.uv)} size=${JSON.stringify(before.uv_size)}`
    + (donorFace
      ? `  ->  from ${patch.donor.fromCubeIndex === undefined ? `same cube .${patch.donor.face}` : `${patch.bone}[${patch.donor.fromCubeIndex}].${patch.donor.face} (${patch.donor.distance.toFixed(1)} units away)`}`
        + ` uv=${JSON.stringify(donorFace.uv)} size=${JSON.stringify(donorFace.uv_size)}`
      : '  -> NO OPAQUE FACE ANYWHERE ON THIS BONE (left alone, reported)'));
}

const applied = patches.filter((patch) => patch.donor);
if (DRY) {
  console.log(`\n  --dry: would patch ${applied.length} face(s), ${patches.length - applied.length} left alone`);
  process.exit(0);
}
if (patches.length !== applied.length) {
  console.error(`\n  refusing to patch: ${patches.length - applied.length} face(s) have no opaque donor`);
  process.exit(1);
}

for (const patch of applied) {
  const source = patch.donor.cube.uv[patch.donor.face];
  patch.cube.uv[patch.faceName] = { uv: [...source.uv], uv_size: [...source.uv_size] };
}

fs.mkdirSync(path.dirname(BACKUP), { recursive: true });
if (!fs.existsSync(BACKUP)) {
  fs.copyFileSync(GEO, BACKUP);
  console.log(`\n  backup of the unpatched geometry: ${BACKUP}`);
}
fs.writeFileSync(GEO, JSON.stringify(geo, null, 2) + '\n');
console.log(`  patched ${applied.length} face(s) in ${GEO}`);
console.log('  verify with: node tools/scan_transparent_faces.js   (expect 0 see-through faces)');
