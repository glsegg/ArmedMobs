// Dumps the skeleton of a GeckoLib geo file (bone -> parent) and/or the keyframes of named bones
// inside an animation file. Used to work out which bone actually carries the body orientation.
//
//   node tools/inspect_animation.js geo  <file.geo.json> [top]
//   node tools/inspect_animation.js anim <file.animation.json> <animation> [bone ...]
const fs = require('fs');

const mode = process.argv[2];
const file = process.argv[3];

const keyframes = (track) => {
  if (!track) return [];
  if (Array.isArray(track)) {
    return track.map((kf) =>
      typeof kf === 'number' ? { time: 0, value: [kf, 0, 0] } : { time: kf.time || 0, value: kf }
    );
  }
  return Object.keys(track).map((t) => ({ time: Number(t), value: track[t] }));
};

const json = JSON.parse(fs.readFileSync(file, 'utf8'));

if (mode === 'geo') {
  const geo = json['minecraft:geometry'][0];
  const bones = geo.bones || [];
  const parents = new Map(bones.map((b) => [b.name, b.parent]));
  const children = new Map();
  for (const b of bones) {
    if (!children.has(b.parent)) children.set(b.parent, []);
    children.get(b.parent).push(b.name);
  }
  console.log(`${file}: ${bones.length} bones, root=${geo.description.identifier}`);
  const onlyTop = process.argv[4] === 'top';
  for (const b of bones) {
    const isTop = !parents.get(b.name);
    if (onlyTop && !isTop) continue;
    const kids = children.get(b.name) || [];
    const pivot = (b.pivot || []).join(',');
    console.log(
      `  ${isTop ? '*' : ' '} ${b.name.padEnd(24)} parent=${String(b.parent).padEnd(20)} ` +
        `pivot=[${pivot}] rot=${JSON.stringify(b.rotation || [])} kids=${kids.length}`
    );
  }
} else if (mode === 'anim') {
  const animName = process.argv[4];
  const filter = process.argv.slice(5);
  const anim = (json.animations || {})[animName];
  if (!anim) {
    console.error(`no animation "${animName}"; have: ${Object.keys(json.animations || {}).join(', ')}`);
    process.exit(1);
  }
  console.log(`${file} :: ${animName}  loop=${anim.loop} length=${anim.animation_length}`);
  for (const [bone, channels] of Object.entries(anim.bones || {})) {
    if (filter.length && !filter.includes(bone)) continue;
    for (const channel of ['rotation', 'position', 'scale']) {
      const frames = keyframes(channels[channel]);
      if (!frames.length) continue;
      console.log(`  ${bone}.${channel}`);
      for (const f of frames.slice(0, 8)) {
        console.log(`      t=${f.time}  ${JSON.stringify(f.value)}`);
      }
      if (frames.length > 8) console.log(`      ... ${frames.length - 8} more`);
    }
  }
} else {
  console.error('usage: node tools/inspect_animation.js geo|anim <file> [...]');
  process.exit(1);
}
