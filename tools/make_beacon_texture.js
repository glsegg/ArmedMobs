// Generates the deployment-beacon item texture (16x16 PNG) procedurally, the same way
// tools/make_grenade_textures.js draws the throwables: no binary art, no third-party asset, and the
// picture is a few lines of code anyone can read and re-run.
//
//   node tools/make_beacon_texture.js
//
// The read is "flare canister": a grey signal body, an amber band that says "this is the way in", a lit
// emitter cap and a dark base. The background is fully transparent, which is what an item/generated
// layer needs.
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

/** A tiny canvas with the three calls this script needs. */
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

const OUTLINE = 0x23262B;
const BODY = 0x6B6F76;
const BODY_LIT = 0x8A8F97;
const BODY_DARK = 0x4A4E55;
const BAND = 0xE0A32E;
const GLOW = 0xFFD873;
const TIP = 0xC0392B;

function beacon() {
  const { rgba, rect, put } = canvas(16);
  // body: a 6-wide canister from y=5 down to y=13
  rect(4, 5, 11, 14, OUTLINE);
  rect(5, 6, 10, 13, BODY);
  rect(5, 6, 6, 13, BODY_LIT);          // lit left edge
  rect(9, 6, 10, 13, BODY_DARK);        // shaded right edge
  // the amber signal band, the "this is the way in" stripe
  rect(5, 8, 10, 9, BAND);
  put(6, 8, GLOW);
  // grip ridges below the band
  for (let y = 11; y <= 12; y++) rect(5, y, 10, y, BODY_DARK);
  // emitter cap and its lit core
  rect(5, 3, 10, 4, OUTLINE);
  rect(6, 3, 9, 4, BODY_LIT);
  rect(7, 2, 8, 3, TIP);
  put(7, 1, GLOW, 220);
  put(8, 1, GLOW, 160);
  // dark base so the canister reads as standing
  rect(4, 14, 11, 14, OUTLINE);
  // a single glint on the lit edge
  put(6, 7, 0xFFFFFF, 150);
  return rgba;
}

const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'item');
const file = path.join(OUT, 'deployment_beacon.png');
fs.writeFileSync(file, encodePng(16, 16, beacon()));
console.log(`deployment_beacon.png  ${fs.statSync(file).size} bytes`);
