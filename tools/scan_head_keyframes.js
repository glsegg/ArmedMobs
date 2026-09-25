// Prints, per clip, what the head bone of a Bedrock rig actually ends up holding - and what the
// author's Molang keyframes collapse to once GeckoLib resolves the YSM-only variables to 0.
//
//   node tools/scan_head_keyframes.js <file.animation.json> [bone ...]
//
// Why: "the head only looks wrong while the mob is idle" is a statement about *values*, and the json
// does not show them - a keyframe like "-0.7*ysm.head_pitch" is a string, and GeckoLib's MolangParser
// answers 0 for every variable it does not know, so the expression silently becomes the constant it
// collapses to. This prints both sides: the expressions found on the head chain, and the constant each
// one collapses to.
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node tools/scan_head_keyframes.js <file.animation.json> [bone ...]');
  process.exit(2);
}
const BONES = process.argv.length > 3 ? process.argv.slice(3) : ['Head', 'Head3', 'AllHead'];

const json = JSON.parse(fs.readFileSync(file, 'utf8'));

const keyframesOf = (channel) => {
  if (Array.isArray(channel)) return channel;
  if (Array.isArray(channel.keyframes)) return channel.keyframes;
  if (channel && typeof channel === 'object') return Object.values(channel);
  return [];
};
const valuesOf = (kf) => {
  const out = [];
  if (typeof kf === 'number' || typeof kf === 'string') { out.push(kf); return out; }
  if (Array.isArray(kf)) { kf.forEach((v) => out.push(...valuesOf(v))); return out; }
  if (kf && typeof kf === 'object') {
    for (const field of ['pre', 'post', 'value']) if (kf[field] !== undefined) out.push(...valuesOf(kf[field]));
  }
  return out;
};

// GeckoLib's MolangParser resolves an unregistered variable to 0 (see README section 10). Substitute 0
// for every variable/query, then evaluate what is left with JS semantics - Molang's `? :`, comparisons
// and `!` are all numeric 0/1 there too, which is what makes this a faithful model of the collapse.
const math = {
  min: Math.min, max: Math.max, abs: Math.abs, round: Math.round, floor: Math.floor,
  ceil: Math.ceil, sqrt: Math.sqrt, pow: Math.pow, sin: Math.sin, cos: Math.cos, tan: Math.tan,
  random: () => 0, clamp: (v, lo, hi) => Math.min(Math.max(v, lo), hi),
};
const collapse = (expression) => {
  if (typeof expression === 'number') return expression;
  const substituted = expression.replace(/\b(?:ysm|variable|v|query|q|math\.mod)\.([A-Za-z_][A-Za-z0-9_]*)/g,
    (match) => (match.startsWith('math.') ? match : '0'));
  try {
    // eslint-disable-next-line no-new-func
    return new Function('math', `return (${substituted});`)(math);
  } catch (error) {
    return `UNKNOWN(${error.message})`;
  }
};

const fmt = (n) => (typeof n === 'number' ? (Math.round(n * 10000) / 10000).toString() : String(n));

let collapsedClips = 0;
let collapsedKeyframes = 0;
console.log(`${file}: head-chain keyframe collapse report (bones: ${BONES.join(', ')})`);
for (const [clipName, clip] of Object.entries(json.animations || {})) {
  const bones = clip.bones || {};
  const lines = [];
  let numericOnly = true;
  for (const bone of BONES) {
    const channels = bones[bone];
    if (!channels) continue;
    for (const kind of ['rotation', 'position', 'scale']) {
      if (!channels[kind]) continue;
      const parts = [];
      for (const kf of keyframesOf(channels[kind])) {
        const values = valuesOf(kf);
        const collapsed = values.map((v) => (typeof v === 'string' ? collapse(v) : v));
        const isCollapsed = values.some((v) => typeof v === 'string');
        if (isCollapsed) numericOnly = false;
        if (kind === 'rotation') {
          parts.push(values.some((v) => typeof v === 'string')
            ? `[${values.map((v, i) => (typeof v === 'string' ? `${v} => ${fmt(collapsed[i])}` : fmt(v))).join(' | ')}]`
            : `[${values.map(fmt).join(', ')}]`);
        }
      }
      if (parts.length) {
        lines.push(`    ${bone}.${kind}: ${parts.join('  ')}`);
        if (parts.some((p) => p.includes('=>'))) collapsedKeyframes += parts.filter((p) => p.includes('=>')).length;
      }
    }
  }
  if (!lines.length) continue;
  if (!numericOnly) collapsedClips++;
  console.log(`  ${clipName}${numericOnly ? '' : '   <-- contains Molang that collapses'}`);
  lines.forEach((line) => console.log(line));
}
console.log(`  --- ${collapsedClips} clip(s) with collapsing head keyframes, ${collapsedKeyframes} keyframe value(s) ---`);
