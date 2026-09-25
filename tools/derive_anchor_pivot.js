// Derives the pivot for the gunner-pillager placeholder rig's gun anchor from the scav rig.
//
// Why: the placeholder rig (gunner_pillager.geo.json) has no gun anchor at all, so with
// client.useGeckoModel = true the mob rendered empty-handed. The scav rig's RightHandLocator carries
// the authored in-hand pose, so the placeholder gets the same bone - but at a pivot the placeholder
// can actually use.
//
// Bedrock bone pivots are in MODEL space, not parent-relative, so "the same bone on a different rig"
// means: take the child's offset from its parent in the reference rig, and add it to the new rig's
// parent pivot:
//
//   offset = pivot(reference child) - pivot(reference parent)
//   pivot(new child) = pivot(new parent) + offset
//
//   node tools/derive_anchor_pivot.js <reference.geo.json> <target.geo.json> <childBone> <parentBone>
const fs = require('fs');

const [reference, target, childName, parentName] = process.argv.slice(2);
if (!reference || !target || !childName || !parentName) {
  console.error('usage: node tools/derive_anchor_pivot.js <reference.geo.json> <target.geo.json> <childBone> <parentBone>');
  process.exit(2);
}

const bonesOf = (file) => JSON.parse(fs.readFileSync(file, 'utf8'))['minecraft:geometry'][0].bones;
const find = (bones, name) => {
  const bone = bones.find((b) => b.name === name);
  if (!bone) throw new Error(`no bone named ${name} in the rig (it has: ${bones.map((b) => b.name).join(' ')})`);
  if (!bone.pivot) throw new Error(`${name} has no pivot to measure from`);
  return bone;
};

const refBones = bonesOf(reference);
const targetBones = bonesOf(target);
const refChild = find(refBones, childName);
const refParent = find(refBones, parentName);
const targetParent = find(targetBones, parentName);

const offset = refChild.pivot.map((v, i) => v - refParent.pivot[i]);
const pivot = targetParent.pivot.map((v, i) => v + offset[i]);

console.log(`reference: ${reference}`);
console.log(`  ${parentName}.pivot          = ${JSON.stringify(refParent.pivot)}`);
console.log(`  ${childName}.pivot   = ${JSON.stringify(refChild.pivot)}`);
console.log(`  offset (child - parent)              = ${JSON.stringify(offset.map((v) => Number(v.toFixed(6))))}`);
console.log(`target   : ${target}`);
console.log(`  ${parentName}.pivot          = ${JSON.stringify(targetParent.pivot)}`);
console.log(`  => ${childName}.pivot = ${JSON.stringify(pivot.map((v) => Number(v.toFixed(6))))}`);
console.log(`\n(rounded to 6 decimals; pivots are in 1/16 block units in both rigs)`);
