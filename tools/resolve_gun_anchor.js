// Checks the gun-anchor fallback chain of GunInHandGeoLayer against a real rig.
//
// What it is for: the chain "configured -> RightHandLocator -> Gun3 -> RightHand" is the difference
// between a mob holding its gun and a mob silently rendering empty-handed. This tool mirrors the Java
// rule against a geo file (so the shipped rigs can be checked without a game) and greps the layer's
// source for the chain, the WARN and the BakedGeoModel guard, so the mirror cannot drift away from the
// code it is checking.
//
//   node tools/resolve_gun_anchor.js [configuredBone]
const fs = require('fs');
const path = require('path');

const LAYER = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'client', 'GunInHandGeoLayer.java');
const RIGS = [
  'src/main/resources/assets/tarkovscav/geo/scav.geo.json',
  'src/main/resources/assets/tarkovscav/geo/gunner_pillager.geo.json',
];
const FALLBACKS = ['RightHandLocator', 'Gun3', 'RightHand'];
const configured = process.argv[2] || 'RightHandLocator';

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const boneNames = (file) => {
  const bones = JSON.parse(fs.readFileSync(file, 'utf8'))['minecraft:geometry'][0].bones;
  const byParent = new Map();
  for (const b of bones) {
    if (!byParent.has(b.parent)) byParent.set(b.parent, []);
    byParent.get(b.parent).push(b.name);
  }
  const out = [];
  const walk = (name) => {
    out.push(name);
    for (const kid of byParent.get(name) || []) walk(kid);
  };
  for (const b of bones) if (!b.parent) walk(b.name);
  return out;
};

const candidates = [];
if (configured && configured.trim()) candidates.push(configured.trim());
for (const f of FALLBACKS) if (!candidates.some((c) => c.toLowerCase() === f.toLowerCase())) candidates.push(f);

console.log(`configured anchor: '${configured.trim()}'`);
console.log(`candidate chain  : ${candidates.join(' -> ')}`);

for (const rig of RIGS) {
  const present = boneNames(rig);
  const chosen = candidates
    .map((c) => present.find((n) => n.toLowerCase() === c.toLowerCase()))
    .find((n) => n !== undefined);
  const label = path.basename(rig);
  console.log(`\n${label} (${present.length} bones)`);
  console.log(`  -> ${chosen ? `mounts on '${chosen}'` : 'NO CANDIDATE: the layer logs a WARN and the gun is not displayed'}`);
  if (chosen) {
    const parent = JSON.parse(fs.readFileSync(rig, 'utf8'))['minecraft:geometry'][0].bones.find((b) => b.name === chosen).parent;
    console.log(`     parent: ${parent || '(root)'}`);
    check(true, `${label}: the gun has an anchor to mount on`, `('${chosen}')`);
  } else {
    check(false, `${label}: the gun has an anchor to mount on`);
  }
}

// The source guard: the fallback order, the silent-failure warning and the rebake-safe guard.
const source = fs.readFileSync(LAYER, 'utf8');
console.log('\nsource guard (GunInHandGeoLayer.java):');
check(/ANCHOR_FALLBACKS\s*=\s*List\.of\(\s*RigSupport\.DEFAULT_GUN_ANCHOR,\s*RigSupport\.PLACEHOLDER_RIFLE_BONE,\s*"RightHand"\)/
  .test(source.replace(/\s+/g, ' ')), 'the fallback order is RightHandLocator -> Gun3 -> RightHand');
check(/LOGGER\.warn\([\s\S]{0,400}?_displayed|LOGGER\.warn\([\s\S]{0,400}?NOT be displayed/.test(source),
  'a rig without any candidate produces a WARN (no silent empty hands)');
check(/bakedModel\s*!=\s*this\.anchorResolvedOn/.test(source),
  'the resolution is keyed on the BakedGeoModel object, so a rebake re-resolves');
check(/public\s+void\s+preRender\s*\(/.test(source),
  'the resolution runs in preRender, i.e. before the per-bone pass of the same frame');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
