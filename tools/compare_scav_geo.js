// Compares a Scav rig source against the imported GeckoLib geometry (or any two geo files).
//
//   node tools/compare_scav_geo.js [newJson] [oldJson]
//
// Defaults after the 2026 re-export:
//   new = assets_source/scav/models/main.json   (the rig the user supplied)
//   old = tools/spike/work/scav.geo.old.json    (the previous imported geo, kept as the "before" picture)
//
// It prints the scale of the two rigs, which bones were added/removed, whether the ANIMATION LIBRARY still
// resolves against the new rig (this is the part that fails silently in game), and whether the anchor bones
// other code reads survived. The numbers it printed for the 2026 re-export are in README 7.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const NEW = process.argv[2] || path.join(ROOT, 'assets_source/scav/models/main.json');
const OLD = process.argv[3] || path.join(ROOT, 'tools/spike/work/scav.geo.old.json');
const ANIM = path.join(ROOT, 'src/main/resources/assets/tarkovscav/animations/scav.animation.json');

function bones(file) {
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const geo = (j['minecraft:geometry'] || j.geometry || [j])[0];
  const out = new Map();
  const walk = (list, parent) => {
    for (const b of list || []) {
      out.set(b.name, { parent: parent || b.parent || null, cubes: (b.cubes || []).length, pivot: b.pivot || null });
      walk(b.children, b.name);
    }
  };
  walk(geo.bones, null);
  return { desc: geo.description || {}, bones: out };
}

const n = bones(NEW);
const o = bones(OLD);
const a = JSON.parse(fs.readFileSync(ANIM, 'utf8'));
const nb = new Set(n.bones.keys());
const ob = new Set(o.bones.keys());
const animBones = new Set();
let clips = 0;
for (const clip of Object.values(a.animations || {})) {
  clips++;
  for (const b of Object.keys(clip.bones || {})) animBones.add(b);
}
const critical = ['Root', 'UpBody', 'UpperBody', 'Body', 'AllBody', 'AllHead', 'Head', 'Head3', 'Arm',
  'RightArm', 'RightForeArm', 'RightHand', 'RightHandLocator', 'LeftHand', 'LeftHandLocator', 'Glass',
  'Hat1', 'Hat2', 'hat3', 'YanJing', 'Yan', 'Ear', 'DownBody', 'LeftLeg', 'RightLeg'];
const fmt = (s) => [...s].sort().join(', ');
const cubes = (m) => [...m.values()].reduce((s, b) => s + b.cubes, 0);

console.log(`new: ${path.relative(ROOT, NEW)}`);
console.log(`old: ${path.relative(ROOT, OLD)}`);
console.log('=== 模型规模 ===');
console.log(`  新版: 骨骼 ${nb.size}  立方体 ${cubes(n.bones)}  texture ${n.desc.texture_width}x${n.desc.texture_height}  identifier=${n.desc.identifier || '-'}`);
console.log(`  旧版: 骨骼 ${ob.size}  立方体 ${cubes(o.bones)}  texture ${o.desc.texture_width}x${o.desc.texture_height}  identifier=${o.desc.identifier || '-'}`);
console.log('=== 骨骼名差异 ===');
const removed = [...ob].filter((x) => !nb.has(x));
const added = [...nb].filter((x) => !ob.has(x));
console.log(`  新版删掉的骨骼 (${removed.length}): ${fmt(removed)}`);
console.log(`  新版新增的骨骼 (${added.length}): ${fmt(added)}`);
console.log(`=== 动画引用覆盖 (${animBones.size} 根 / ${clips} 段) ===`);
const missing = [...animBones].filter((b) => !nb.has(b));
console.log(`  动画用到但新模型没有的骨骼 (${missing.length}): ${fmt(missing)}`);
console.log('=== 关键骨骼是否保留 ===');
console.log(`  缺失的关键骨骼: ${fmt(critical.filter((b) => !nb.has(b)))}`);
console.log('  每根关键骨骼的立方体数:');
for (const b of critical) {
  if (nb.has(b)) console.log(`    ${b.padEnd(20)} cubes=${n.bones.get(b).cubes}  parent=${n.bones.get(b).parent}`);
}
process.exit(missing.length === 0 ? 0 : 1);
