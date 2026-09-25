// Generates the five grenade item textures (README 5v) as 16x16 PNGs, procedurally, so the mod ships no binary
// art it cannot explain. Same writer as tools/make_placeholder_assets.js (crc32 + zlib deflate, no
// dependencies).
//
//   node tools/make_grenade_textures.js
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xFF];
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([length, typeBuf, data, crcBuf]);
}

function encodePng(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0; // filter: none
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** A tiny canvas with the two calls this script needs. */
function canvas(size) {
  const rgba = Buffer.alloc(size * size * 4, 0);
  const put = (x, y, hex, alpha = 255) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 4;
    rgba[i] = (hex >> 16) & 0xFF;
    rgba[i + 1] = (hex >> 8) & 0xFF;
    rgba[i + 2] = hex & 0xFF;
    rgba[i + 3] = alpha;
  };
  const rect = (x0, y0, x1, y1, hex, alpha) => {
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) put(x, y, hex, alpha);
  };
  return { rgba, put, rect };
}

const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'item');

/**
 * Canister: x 5..10, a top cap and a pull ring, a coloured band that says which one it is, and a short-fuse
 * variant that is visibly shorter.
 */
function grenade(body, band, dark, short) {
  const { rgba, rect, put } = canvas(16);
  const top = short ? 6 : 4;
  rect(4, top - 1, 11, 14, dark);            // outline
  rect(5, top, 10, 13, body);                 // body
  rect(5, top, 6, 13, band);                  // lit edge
  rect(7, top, 10, 13, body);
  for (let y = top; y <= 13; y += 3) rect(5, y, 10, y, dark); // ridges
  rect(6, top - 3, 9, top - 1, dark);         // cap
  rect(6, top - 3, 7, top - 2, band);
  rect(9, top - 5, 11, top - 4, 0xC9C9C9);    // pull ring
  put(10, top - 3, 0x8A8A8A);
  put(5, top + 2, 0xFFFFFF, 160);             // a glint
  return rgba;
}

const textures = [
  ['frag_grenade', grenade(0x4B5D3A, 0x6E8B3D, 0x2A3322, false)],
  ['he_grenade', grenade(0x5A4B3A, 0x8C2F2F, 0x2E2418, false)],
  ['smoke_grenade', grenade(0xA8ADB2, 0xE2E6EA, 0x5A5F63, false)],
  ['flash_grenade', grenade(0xB8BDC4, 0x3D6E8B, 0x5C6167, false)],
  ['flash_grenade_short', grenade(0xB8BDC4, 0x8C2F2F, 0x5C6167, true)],
];

for (const [name, rgba] of textures) {
  const file = path.join(OUT, `${name}.png`);
  fs.writeFileSync(file, encodePng(16, 16, rgba));
  console.log(`${name}.png  ${fs.statSync(file).size} bytes`);
}
