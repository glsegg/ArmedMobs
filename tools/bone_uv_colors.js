// Maps every bone of a Bedrock geo file onto the colours of the texture it samples.
//
// Why: a screenshot can show geometry that is obvious to the eye but not to the json ("a red cube on
// the head", "a blue striped box"). Sampling the texture inside each cube's face-UV rect names that
// geometry in the data, so a visual report can be traced to a bone.
//
// Two steps, because Node has no PNG decoder:
//   node tools/bone_uv_colors.js dump  <file.geo.json> <out.json>
//   powershell: tools/bone_uv_colors.ps1 <texture.png> <rects.json> <out.json>
//   node tools/bone_uv_colors.js report <out.json>
const fs = require('fs');

const mode = process.argv[2];
const DIRS = ['north', 'east', 'south', 'west', 'up', 'down'];

if (mode === 'dump') {
  const geo = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'))['minecraft:geometry'][0];
  const rects = [];
  for (const bone of geo.bones) {
    (bone.cubes || []).forEach((cube, index) => {
      if (Array.isArray(cube.uv)) {
        // Box UV: one rect for the whole cube; sample it once.
        rects.push({ bone: bone.name, index, face: 'box', u: cube.uv[0], v: cube.uv[1], w: 4, h: 4, size: cube.size });
        return;
      }
      for (const dir of DIRS) {
        const face = cube.uv[dir];
        if (!face) {
          rects.push({ bone: bone.name, index, face: dir, missing: true, size: cube.size });
          continue;
        }
        rects.push({
          bone: bone.name, index, face: dir,
          u: face.uv[0], v: face.uv[1],
          w: Math.abs(face.uv_size[0]), h: Math.abs(face.uv_size[1]),
          size: cube.size,
        });
      }
    });
  }
  fs.writeFileSync(process.argv[4], JSON.stringify({ texture: geo.description.texture_width, rects }, null, 0));
  console.log(`dumped ${rects.length} face rects, ${rects.filter((r) => r.missing).length} of them MISSING (no UV for that direction)`);
  const missing = [...new Set(rects.filter((r) => r.missing).map((r) => `${r.bone}[${r.index}] ${r.face} size=${JSON.stringify(r.size)}`))];
  for (const m of missing.slice(0, 40)) console.log(`  missing UV: ${m}`);
  process.exit(0);
}

if (mode === 'report') {
  const parsed = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
  // The PowerShell half writes a bare array; accept both shapes.
  const rects = Array.isArray(parsed) ? parsed : parsed.rects;
  const byBone = new Map();
  for (const r of rects) {
    if (!byBone.has(r.bone)) byBone.set(r.bone, []);
    byBone.get(r.bone).push(r);
  }
  const counts = new Map();
  for (const [bone, rects] of byBone) {
    const tally = new Map();
    for (const r of rects) {
      const key = r.missing ? 'MISSING-UV' : r.color || '?';
      tally.set(key, (tally.get(key) || 0) + 1);
    }
    const summary = [...tally.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}x${v}`).join(' ');
    counts.set(bone, summary);
  }
  for (const [bone, summary] of counts) console.log(`${bone.padEnd(16)} ${summary}`);
  process.exit(0);
}

console.error('usage: node tools/bone_uv_colors.js dump <geo.json> <out.json> | report <colors.json>');
process.exit(2);
