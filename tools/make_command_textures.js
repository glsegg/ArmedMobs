// Generates the command system's textures (16x16 PNG, procedurally) the same way
// tools/make_beacon_texture.js and tools/make_grenade_textures.js draw theirs: no binary art, no
// third-party asset, and every picture is a few readable lines anyone can re-run.
//
//   node tools/make_command_textures.js
//
// What it draws:
//   village_command_tool  a green field radio with a wheat-coloured aerial (the village side)
//   illager_command_tool  a dark grey radio with a red banner stripe (the pillager side)
//   scav_command_tool     a rust-brown radio with a bare-metal patch (the scav side)
//   signal_stick          a red flare stick with a lit tip and a grey grip
//   signal_point          the signal point's block texture: a metal post top with a lit amber core
//
// All five share one silhouette so the three tools read as one family and differ only by their accent
// colour and motif - which is the only difference between them in code, too.
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
  ihdr[8] = 8;
  ihdr[9] = 6;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

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

const OUTLINE = 0x1E2126;
const BODY = 0x5A5F68;
const BODY_LIT = 0x7B818B;
const BODY_DARK = 0x3C4046;
const GLASS = 0x2B3A46;
const GLASS_LIT = 0x4E7C93;

/**
 * The shared command-tool silhouette: a field radio with an aerial and a display, tinted by the
 * faction's accent. Keeping the geometry here is what makes the three tools a family.
 */
function commandTool(accent, accentLit, motif) {
  const { rgba, rect, put } = canvas(16);
  // case: 10 wide from x=3..12, y=4..14
  rect(3, 4, 12, 14, OUTLINE);
  rect(4, 5, 11, 13, BODY);
  rect(4, 5, 5, 13, BODY_LIT);
  rect(10, 5, 11, 13, BODY_DARK);
  // display window
  rect(5, 6, 10, 8, GLASS);
  rect(5, 6, 6, 8, GLASS_LIT);
  put(9, 7, accentLit);
  // the faction stripe down the right of the case
  rect(11, 9, 11, 13, accent);
  // speaker grille
  for (let y = 10; y <= 12; y += 2) rect(5, y, 8, y, BODY_DARK);
  // aerial: a three-pixel mast with the faction tip
  rect(7, 1, 8, 3, BODY_DARK);
  put(7, 1, accentLit);
  put(8, 0, accentLit);
  // a strap loop on the left, so it reads as carried rather than floating
  rect(2, 6, 2, 10, OUTLINE);
  // the per-faction motif in the display
  for (const [x, y] of motif) put(x, y, accentLit);
  return rgba;
}

function signalStick() {
  const { rgba, rect, put } = canvas(16);
  // a diagonal-ish flare: body from top-right to bottom-left
  rect(6, 2, 9, 4, OUTLINE);
  rect(7, 1, 8, 3, 0xC0392B);          // lit red tip
  put(7, 0, 0xFF8A5C, 230);
  put(8, 0, 0xFFC46B, 200);
  rect(5, 4, 9, 6, 0x8C3A2A);          // upper body, hot
  rect(5, 6, 10, 12, 0xD9DDE3);        // white signal body
  rect(5, 6, 6, 12, 0xF2F5F8);
  rect(9, 6, 10, 12, 0xA9AEB6);
  rect(5, 8, 10, 9, 0xC0392B);         // red band
  rect(5, 12, 10, 13, 0x4A4E55);       // grip
  rect(5, 13, 10, 13, OUTLINE);
  // a couple of grip ridges
  for (let x = 5; x <= 10; x += 2) put(x, 11, 0xA9AEB6, 180);
  return rgba;
}

function signalPoint() {
  const { rgba, rect, put } = canvas(16);
  // a dark metal plate with a lit amber core: it is a light source in the world (lightLevel 10)
  rect(0, 0, 15, 15, 0x33383F);
  rect(1, 1, 14, 14, 0x454B54);
  rect(2, 2, 13, 13, 0x59606A);
  rect(3, 3, 12, 12, 0x2A2E34);
  rect(5, 5, 10, 10, 0xE0A32E);
  rect(6, 6, 9, 9, 0xFFD873);
  rect(7, 7, 8, 8, 0xFFF3C4);
  // four bolts, so the block reads as a fixture
  for (const [x, y] of [[1, 1], [14, 1], [1, 14], [14, 14]]) {
    put(x, y, 0x9AA3AD);
    put(x, y, 0x9AA3AD);
  }
  // a corner shading pass so tiling is visible
  for (let i = 0; i < 16; i++) {
    put(i, 15, 0x22262B);
    put(15, i, 0x22262B);
  }
  return rgba;
}

const ITEM_OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'item');
const BLOCK_OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'textures', 'block');

const files = [
  // name, rgba, directory
  ['village_command_tool', commandTool(0x4B7A3A, 0xA8C86B, [[6, 7], [7, 7]]), ITEM_OUT],
  ['illager_command_tool', commandTool(0x8C2F2F, 0xE06A5A, [[6, 7], [7, 7], [8, 7]]), ITEM_OUT],
  ['scav_command_tool', commandTool(0x7A5A2E, 0xD8A657, [[7, 7]]), ITEM_OUT],
  ['signal_stick', signalStick(), ITEM_OUT],
  ['signal_point', signalPoint(), BLOCK_OUT],
];

for (const [name, rgba, dir] of files) {
  const file = path.join(dir, `${name}.png`);
  fs.writeFileSync(file, encodePng(16, 16, rgba));
  console.log(`${name}.png  ${fs.statSync(file).size} bytes`);
}
