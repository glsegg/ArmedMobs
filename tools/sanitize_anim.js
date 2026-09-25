// Strips bone tracks that do not exist in a Bedrock geometry from an animation file.
// The G36C animation ships from a different rig revision than the geometry, so it still
// references bones such as "74m", "wei_ba" or "MRoot" that the model does not have.
// GeckoLib would throw (or silently skip) those tracks, so they are removed up front.
//
// Usage: node sanitize_anim.js <geo.json> <animation.json> [output.json]
const fs = require('fs');

function sanitize(geometry, animation) {
  const geoRoot = geometry['minecraft:geometry'] ? geometry['minecraft:geometry'][0] : geometry;
  const boneNames = new Set(geoRoot.bones.map((b) => b.name));

  const out = JSON.parse(JSON.stringify(animation));
  const report = [];

  for (const clip of Object.keys(out.animations)) {
    const bones = out.animations[clip].bones || {};
    let dropped = 0;
    for (const bone of Object.keys(bones)) {
      if (!boneNames.has(bone)) {
        delete bones[bone];
        dropped++;
      }
    }
    if (dropped > 0) report.push(`${clip}: dropped ${dropped} unknown bone track(s)`);
  }

  return { animation: out, boneNames, report };
}

function main() {
  const [, , geoPath, animPath, outPath] = process.argv;
  const geo = JSON.parse(fs.readFileSync(geoPath, 'utf8'));
  const anim = JSON.parse(fs.readFileSync(animPath, 'utf8'));
  const result = sanitize(geo, anim);
  const json = JSON.stringify(result.animation, null, '\t');

  if (outPath) fs.writeFileSync(outPath, json, 'utf8');

  console.log(`geometry bones: ${result.boneNames.size}`);
  console.log(result.report.join('\n') || 'nothing dropped');
  if (outPath) console.log(`wrote ${outPath} (${(Buffer.byteLength(json) / 1024).toFixed(1)} KiB)`);
}

module.exports = { sanitize };

if (require.main === module) main();
