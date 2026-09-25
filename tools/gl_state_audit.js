// Decodes the GL constants around every RenderSystem stencil/clear call in a javap dump, so "does TaCZ
// put the stencil state back?" is answered with numbers instead of adjectives.
//
//   javap -p -c -classpath <tacz.jar> com.tacz.guns.client.model.BedrockGunModel > bgm.txt
//   node tools/gl_state_audit.js bgm.txt "BedrockGunModel"
//
// The javap text is scanned flat (no method segmentation): every GL-state call is printed with the
// integer constants pushed for it, decoded to GL names, and with the nearest preceding method header
// for context - which is what makes "is this call paired with a restore?" readable.
'use strict';
const fs = require('fs');

const GL = {
  2960: 'GL_STENCIL_TEST', 2929: 'GL_DEPTH_TEST', 2884: 'GL_CULL_FACE', 3042: 'GL_BLEND',
  519: 'GL_ALWAYS', 512: 'GL_NEVER', 513: 'GL_LESS', 514: 'GL_EQUAL', 515: 'GL_LEQUAL',
  516: 'GL_GREATER', 517: 'GL_NOTEQUAL', 518: 'GL_GEQUAL',
  7680: 'GL_KEEP', 7681: 'GL_REPLACE', 7682: 'GL_INCR', 7683: 'GL_DECR', 5386: 'GL_INVERT',
  771: 'GL_INCR_WRAP', 772: 'GL_DECR_WRAP',
  1024: 'GL_STENCIL_BUFFER_BIT', 256: 'GL_DEPTH_BUFFER_BIT', 16384: 'GL_COLOR_BUFFER_BIT',
};

const file = process.argv[2];
const label = process.argv[3] || file;
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);

const HEADER = /^\s+(?:public|protected|private|static|final|abstract|synchronized|native|strictfp)[\w\s$.<>\[\],?]*?\s([\w$<>]+)\(/;
const WATCH = /RenderSystem\.(\w+):|GlStateManager\.(_?\w+):/;

function decode(from, to) {
  const values = [];
  for (let i = from; i <= to; i++) {
    let m = /iconst_m?(\d+)/.exec(lines[i]);
    if (m) { values.push(lines[i].includes('iconst_m1') ? -1 : Number(m[1])); continue; }
    m = /\b(bipush|sipush)\s+(-?\d+)/.exec(lines[i]);
    if (m) { values.push(Number(m[2])); continue; }
    m = /ldc\s+#\d+\s+\/\/\s+int\s+(-?\d+)/.exec(lines[i]);
    if (m) { values.push(Number(m[1])); }
  }
  return values;
}

const found = [];
let method = '(top level)';
lines.forEach((line, index) => {
  const header = HEADER.exec(line);
  if (header && !line.includes('//')) method = header[1];
  const call = WATCH.exec(line);
  if (!call) return;
  // constants pushed since the previous invoke
  let back = index;
  while (back > 0 && !/invoke/.test(lines[back])) back--;
  const args = decode(back, index).map((v) => (GL[v] !== undefined ? `${v} (${GL[v]})` : String(v)));
  found.push({ method, name: call[1] || call[2], args, line: index + 1 });
});

console.log(`GL state audit: ${label}`);
console.log(`call sites: ${found.length}`);
console.log('');
for (const entry of found) {
  console.log(`  line ${String(entry.line).padStart(5)}  ${entry.method.padEnd(34)} ${entry.name}(${entry.args.join(', ')})`);
}

const byName = (name) => found.filter((e) => e.name === name);
const stencilTestOn = byName('enableStencilTest');
const stencilTestOff = byName('disableStencilTest');
console.log('');
console.log('audit answers:');
console.log(`  enableStencilTest  : ${stencilTestOn.length}`);
console.log(`  disableStencilTest : ${stencilTestOff.length}`);
console.log(`  => stencil test is ${stencilTestOn.length && !stencilTestOff.length
  ? 'ENABLED AND NEVER DISABLED in this class  <-- the leak shape'
  : stencilTestOn.length && stencilTestOff.length ? 'paired here' : 'not toggled here'}`);
const funcs = byName('stencilFunc');
console.log(`  stencilFunc        : ${funcs.length}`);
funcs.forEach((e) => console.log(`      ${e.method}: stencilFunc(${e.args.join(', ')})`
  + (/GL_ALWAYS/.test(e.args.join(',')) ? '   <- always-pass' : '   <- conditional, NOT a restore')));
const masks = byName('stencilMask');
console.log(`  stencilMask        : ${masks.length}`);
masks.forEach((e) => console.log(`      ${e.method}: stencilMask(${e.args.join(', ')})`));
const ops = byName('stencilOp');
console.log(`  stencilOp          : ${ops.length}`);
ops.forEach((e) => console.log(`      ${e.method}: stencilOp(${e.args.join(', ')})`));
console.log(`  clearStencil       : ${byName('clearStencil').length}`);
console.log(`  clear              : ${byName('clear').length}`);
byName('clear').forEach((e) => console.log(`      ${e.method}: clear(${e.args.join(', ')})`
  + (/GL_STENCIL_BUFFER_BIT/.test(e.args.join(',')) ? '   <- touches the stencil buffer' : '')));
const depth = [...byName('depthMask'), ...byName('enableDepthTest'), ...byName('disableDepthTest')];
console.log(`  depth state calls  : ${depth.length}`);
depth.forEach((e) => console.log(`      ${e.method}: ${e.name}(${e.args.join(', ')})`));
const misc = [...byName('enableBlend'), ...byName('disableBlend'), ...byName('enableCull'),
  ...byName('disableCull'), ...byName('colorMask')];
console.log(`  blend/cull/color   : ${misc.length}`);
misc.forEach((e) => console.log(`      ${e.method}: ${e.name}(${e.args.join(', ')})`));
