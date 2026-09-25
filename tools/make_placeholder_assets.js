// Generates the placeholder visuals for 塔科夫Scav: two Bedrock geometry files, two animation files
// and two entity textures. Nothing here is meant to be pretty - these exist so the mod has a real,
// loadable rig with the right bone names until the user's own Bedrock models arrive. Replacing them
// is a pure asset swap; no Java changes are needed (ScavGeoModel / GunnerPillagerGeoModel only name
// the paths, and the gun mounts on the RightHand bone).
//
//   node tools/make_placeholder_assets.js
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src/main/resources/assets/tarkovscav');

// ---------------------------------------------------------------------------- PNG writer

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

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function encodePng(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Tiny deterministic hash so the "wear" speckles are stable between runs. */
function noise(x, y, seed) {
  let h = (x * 374761393 + y * 668265263 + seed * 1274126177) | 0;
  h = (h ^ (h >>> 13)) * 1274126177;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

function makeSheet(size, seed) {
  const rgba = Buffer.alloc(size * size * 4);
  return {
    size,
    rgba,
    rect(x, y, w, h, colour) {
      const [r, g, b, a] = colour;
      for (let j = 0; j < h; j++) {
        for (let i = 0; i < w; i++) {
          const px = x + i;
          const py = y + j;
          if (px < 0 || py < 0 || px >= size || py >= size) continue;
          const shift = 0.86 + 0.28 * noise(px, py, seed);
          const offset = (py * size + px) * 4;
          rgba[offset] = Math.min(255, Math.round(r * shift));
          rgba[offset + 1] = Math.min(255, Math.round(g * shift));
          rgba[offset + 2] = Math.min(255, Math.round(b * shift));
          rgba[offset + 3] = a;
        }
      }
    },
    speckle(x, y, w, h, colour, density) {
      const [r, g, b, a] = colour;
      for (let j = 0; j < h; j++) {
        for (let i = 0; i < w; i++) {
          if (noise(x + i, y + j, seed + 7) > density) continue;
          const offset = ((y + j) * size + (x + i)) * 4;
          rgba[offset] = r;
          rgba[offset + 1] = g;
          rgba[offset + 2] = b;
          rgba[offset + 3] = a;
        }
      }
    },
  };
}

// ---------------------------------------------------------------------------- the rig

const BONES = [
  { name: 'Root', pivot: [0, 0, 0] },
  { name: 'Body', parent: 'Root', pivot: [0, 12, 0] },
  { name: 'Head', parent: 'Body', pivot: [0, 24, 0] },
  { name: 'RightArm', parent: 'Body', pivot: [-5.5, 22, 0] },
  { name: 'RightHand', parent: 'RightArm', pivot: [-5.5, 12, 0] },
  { name: 'LeftArm', parent: 'Body', pivot: [5.5, 22, 0] },
  { name: 'RightLeg', parent: 'Root', pivot: [-2, 12, 0] },
  { name: 'LeftLeg', parent: 'Root', pivot: [2, 12, 0] },
];

// origin = minimum corner, size = [w, h, d], uv = box-UV corner in the 64x64 sheet.
const CUBES = {
  Body: [{ origin: [-4, 12, -2], size: [8, 12, 4], uv: [0, 16] }],
  Head: [
    { origin: [-4, 24, -4], size: [8, 8, 8], uv: [0, 0] },
    // helmet band
    { origin: [-4.2, 30, -4.2], size: [8.4, 2.2, 8.4], uv: [0, 0], inflate: 0.0 },
  ],
  RightArm: [{ origin: [-8, 12, -2], size: [3, 12, 4], uv: [24, 16] }],
  LeftArm: [{ origin: [5, 12, -2], size: [3, 12, 4], uv: [40, 16] }],
  RightLeg: [{ origin: [-4, 0, -2], size: [4, 12, 4], uv: [0, 32] }],
  LeftLeg: [{ origin: [0, 0, -2], size: [4, 12, 4], uv: [16, 32] }],
  // A blocky stand-in firearm so the mob reads as "armed" even before TaCZ's own item renderer
  // kicks in. It points along -Z, which is the direction these rigs face.
  RightHand: [{ origin: [-6.6, 10.8, -7], size: [2.2, 2.2, 9], uv: [40, 32] }],
};

function geometry(identifier) {
  return {
    format_version: '1.12.0',
    'minecraft:geometry': [
      {
        description: {
          identifier,
          texture_width: 64,
          texture_height: 64,
          visible_bounds_width: 3,
          visible_bounds_height: 3.5,
          visible_bounds_offset: [0, 1.5, 0],
        },
        bones: BONES.map((bone) => {
          const out = { name: bone.name, pivot: bone.pivot };
          if (bone.parent) out.parent = bone.parent;
          const cubes = CUBES[bone.name];
          if (cubes) {
            out.cubes = cubes.map((cube) => ({
              origin: cube.origin,
              size: cube.size,
              uv: cube.uv,
            }));
          }
          return out;
        }),
      },
    ],
  };
}

// ---------------------------------------------------------------------------- animations

const ARM_AIM_RIGHT = [-84, -4, 0];
const ARM_AIM_LEFT = [-76, 16, -20];

function animations() {
  return {
    format_version: '1.8.0',
    animations: {
      idle: {
        loop: true,
        animation_length: 4.0,
        bones: {
          Root: { position: { 0.0: [0, 0, 0], 2.0: [0, 0.12, 0], 4.0: [0, 0, 0] } },
          Body: { rotation: { 0.0: [0, 0, 0], 2.0: [-1.5, 0, 0], 4.0: [0, 0, 0] } },
          Head: { rotation: { 0.0: [0, -8, 0], 1.5: [4, 0, 0], 3.0: [0, 8, 0], 4.0: [0, -8, 0] } },
          RightArm: { rotation: { 0.0: [0, 0, 0], 2.0: [5, 0, 0], 4.0: [0, 0, 0] } },
          LeftArm: { rotation: { 0.0: [0, 0, 0], 2.0: [-5, 0, 0], 4.0: [0, 0, 0] } },
        },
      },
      walk: {
        loop: true,
        animation_length: 1.0,
        bones: {
          Root: { position: { 0.0: [0, 0, 0], 0.25: [0, 0.7, 0], 0.5: [0, 0, 0], 0.75: [0, 0.7, 0], 1.0: [0, 0, 0] } },
          RightLeg: { rotation: { 0.0: [-40, 0, 0], 0.5: [40, 0, 0], 1.0: [-40, 0, 0] } },
          LeftLeg: { rotation: { 0.0: [40, 0, 0], 0.5: [-40, 0, 0], 1.0: [40, 0, 0] } },
          RightArm: { rotation: { 0.0: [28, 0, 0], 0.5: [-28, 0, 0], 1.0: [28, 0, 0] } },
          LeftArm: { rotation: { 0.0: [-28, 0, 0], 0.5: [28, 0, 0], 1.0: [-28, 0, 0] } },
        },
      },
      aiming: {
        loop: true,
        animation_length: 1.2,
        bones: {
          Body: { rotation: { 0.0: [0, -6, 0], 0.6: [0, -6, 0], 1.2: [0, -6, 0] } },
          Head: { rotation: { 0.0: [-4, -5, 0], 0.6: [-4, -5, 0], 1.2: [-4, -5, 0] } },
          RightArm: { rotation: { 0.0: ARM_AIM_RIGHT, 0.6: ARM_AIM_RIGHT, 1.2: ARM_AIM_RIGHT } },
          LeftArm: { rotation: { 0.0: ARM_AIM_LEFT, 0.6: ARM_AIM_LEFT, 1.2: ARM_AIM_LEFT } },
        },
      },
      firing: {
        loop: true,
        animation_length: 0.25,
        bones: {
          Root: { position: { 0.0: [0, 0, 0], 0.05: [0, 0, -0.7], 0.2: [0, 0, 0], 0.25: [0, 0, 0] } },
          Body: { rotation: { 0.0: [0, -6, 0], 0.05: [2.5, -6, 0], 0.2: [0, -6, 0], 0.25: [0, -6, 0] } },
          Head: { rotation: { 0.0: [-4, -5, 0], 0.05: [-6, -5, 0], 0.2: [-4, -5, 0], 0.25: [-4, -5, 0] } },
          RightArm: {
            rotation: { 0.0: ARM_AIM_RIGHT, 0.05: [-93, -4, 0], 0.2: ARM_AIM_RIGHT, 0.25: ARM_AIM_RIGHT },
          },
          LeftArm: { rotation: { 0.0: ARM_AIM_LEFT, 0.05: [-82, 16, -20], 0.2: ARM_AIM_LEFT, 0.25: ARM_AIM_LEFT } },
        },
      },
    },
  };
}

// ---------------------------------------------------------------------------- textures

function paintBody(sheet, colours) {
  // Head region (uv 0,0, 32x16): face at the left half, helmet on top.
  sheet.rect(0, 0, 32, 16, colours.head);
  sheet.rect(0, 0, 32, 4, colours.helmet);
  sheet.speckle(0, 4, 32, 12, colours.headShade, 0.22);
  // Body region (uv 0,16, 24x16) with a plate carrier band.
  sheet.rect(0, 16, 24, 16, colours.body);
  sheet.rect(0, 20, 24, 5, colours.vest);
  sheet.speckle(0, 16, 24, 16, colours.bodyShade, 0.25);
  // Arms.
  sheet.rect(24, 16, 14, 16, colours.body);
  sheet.rect(40, 16, 14, 16, colours.body);
  sheet.rect(24, 28, 14, 4, colours.glove);
  sheet.rect(40, 28, 14, 4, colours.glove);
  // Legs and boots.
  sheet.rect(0, 32, 16, 16, colours.legs);
  sheet.rect(16, 32, 16, 16, colours.legs);
  sheet.rect(0, 44, 16, 4, colours.boots);
  sheet.rect(16, 44, 16, 4, colours.boots);
  sheet.speckle(0, 32, 32, 16, colours.bodyShade, 0.18);
  // The placeholder firearm.
  sheet.rect(40, 32, 22, 11, colours.gun);
  sheet.rect(52, 32, 10, 3, colours.gunMetal);
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
  console.log('wrote ' + path.relative(ROOT, file));
}

function writeTexture(file, seed, colours) {
  const sheet = makeSheet(64, seed);
  paintBody(sheet, colours);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, encodePng(64, 64, sheet.rgba));
  console.log('wrote ' + path.relative(ROOT, file) + ' (64x64)');
}

const SCAV_COLOURS = {
  head: [188, 154, 122, 255],
  headShade: [140, 112, 88, 255],
  helmet: [74, 84, 58, 255],
  body: [75, 93, 58, 255],
  bodyShade: [52, 66, 40, 255],
  vest: [58, 62, 48, 255],
  glove: [40, 42, 36, 255],
  legs: [86, 88, 74, 255],
  boots: [38, 36, 32, 255],
  gun: [46, 46, 50, 255],
  gunMetal: [92, 92, 98, 255],
};

const PILLAGER_COLOURS = {
  head: [142, 142, 148, 255],
  headShade: [104, 104, 112, 255],
  helmet: [70, 72, 80, 255],
  body: [90, 95, 107, 255],
  bodyShade: [64, 68, 78, 255],
  vest: [52, 54, 62, 255],
  glove: [44, 44, 50, 255],
  legs: [80, 82, 90, 255],
  boots: [36, 36, 40, 255],
  gun: [40, 40, 44, 255],
  gunMetal: [120, 60, 60, 255],
};

// ---------------------------------------------------------------------------- main

const RIGS = [
  { name: 'scav', colours: SCAV_COLOURS, seed: 11 },
  { name: 'gunner_pillager', colours: PILLAGER_COLOURS, seed: 23 },
];

for (const rig of RIGS) {
  writeJson(path.join(ASSETS, 'geo', rig.name + '.geo.json'),
      geometry(rig.name === 'scav' ? 'geometry.tarkovscav.scav' : 'geometry.tarkovscav.gunner_pillager'));
  writeJson(path.join(ASSETS, 'animations', rig.name + '.animation.json'), animations());
  writeTexture(path.join(ASSETS, 'textures/entity', rig.name + '.png'), rig.seed, rig.colours);
}

console.log('placeholder assets generated');
