// Scans a Bedrock animation file for keyframe values that are NOT plain numbers.
//
// Why this exists: an earlier scan (scan_ranges2.js) only inspected numeric pre/post values, so a
// Molang expression keyframe - which is stored as a STRING - was invisible to it. Molang is
// evaluated at runtime by GeckoLib's MolangParser, so a string keyframe can produce a value the
// json never shows: a division by zero, an unknown YSM variable (resolved to 0 or NaN), or a
// query GeckoLib does not register (GeckoLib only wires up the handful in GeoModel#applyMolangQueries).
//
//   node tools/scan_molang.js <file.animation.json>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node tools/scan_molang.js <file.animation.json>');
  process.exit(2);
}

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const KINDS = ['rotation', 'position', 'scale'];

// A channel is one of: a bare keyframe array, {"keyframes":[...]}, or - as this rig uses - a
// time-keyed object {"0.0": {"post":[...], "lerp_mode":"catmullrom"}, "0.5": {...}}. All three have
// to be understood, or the string keyframes are simply not seen.
const keyframesOf = (channel) => {
  if (Array.isArray(channel)) return channel;
  if (Array.isArray(channel.keyframes)) return channel.keyframes;
  if (channel && typeof channel === 'object') return Object.values(channel);
  return [];
};

// A keyframe's value is either numeric, a Molang string, an array of the two, or {pre, post, value}.
const valuesOf = (kf) => {
  const out = [];
  if (typeof kf === 'number' || typeof kf === 'string') { out.push(kf); return out; }
  if (Array.isArray(kf)) { kf.forEach((v) => out.push(...valuesOf(v))); return out; }
  if (kf && typeof kf === 'object') {
    for (const field of ['pre', 'post', 'value']) if (kf[field] !== undefined) out.push(...valuesOf(kf[field]));
  }
  return out;
};

let keyframes = 0;
let keyframesWithStrings = 0;
let numeric = 0;
let nonNumeric = 0;
const expressions = new Map();
const byAnimation = new Map();
const clipsWithStrings = new Set();

const variableRe = /(?:variable|v)\.([A-Za-z_][A-Za-z0-9_.]*)/g;
const queryRe = /query\.([A-Za-z_][A-Za-z0-9_.]*)/g;

const note = (anim, bone, kind, text) => {
  expressions.set(text, (expressions.get(text) || 0) + 1);
  const key = `${anim} | ${bone} | ${kind}`;
  if (!byAnimation.has(key)) byAnimation.set(key, new Set());
  byAnimation.get(key).add(text);
};

for (const [anim, a] of Object.entries(json.animations || {})) {
  for (const [bone, channels] of Object.entries(a.bones || {})) {
    for (const kind of KINDS) {
      const channel = channels[kind];
      if (!channel) continue;
      for (const kf of keyframesOf(channel)) {
        keyframes++;
        let foundString = false;
        for (const value of valuesOf(kf)) {
          if (typeof value === 'number') { numeric++; continue; }
          nonNumeric++;
          foundString = true;
          note(anim, bone, kind, typeof value === 'string' ? value : JSON.stringify(value));
        }
        if (foundString) { keyframesWithStrings++; clipsWithStrings.add(anim); }
      }
    }
  }
}

console.log(`${file}`);
console.log(`  animations=${Object.keys(json.animations || {}).length}  keyframes=${keyframes}`
  + `  (with Molang strings=${keyframesWithStrings}, in ${clipsWithStrings.size} clip(s): ${[...clipsWithStrings].sort().join(', ')})`);
console.log(`  numeric values=${numeric}  non-numeric values=${nonNumeric}  distinct expressions=${expressions.size}`);

if (expressions.size) {
  console.log('  --- expressions (count, text) ---');
  for (const [text, count] of [...expressions.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`    ${count}x  ${text}`);
  }
  console.log('  --- per bone/channel ---');
  for (const [key, set] of [...byAnimation.entries()].slice(0, 40)) {
    console.log(`    ${key}: ${[...set].slice(0, 4).join(' ; ')}${set.size > 4 ? ' ...' : ''}`);
  }
}

// Hazards: expressions GeckoLib cannot resolve the way YSM would.
const knownQueries = new Set([
  'life_time', 'actor_count', 'time_of_day', 'moon_phase', 'distance_from_camera',
  'is_on_ground', 'is_in_water', 'is_in_water_or_rain', 'health', 'max_health',
  'is_on_fire', 'ground_speed', 'yaw_speed', 'controller_speed', 'anim_time', 'all_animations_finished',
]);
const hazards = [];
for (const text of expressions.keys()) {
  for (const m of text.matchAll(queryRe)) if (!knownQueries.has(m[1])) hazards.push(`unregistered query.${m[1]} in "${text}"`);
  for (const m of text.matchAll(variableRe)) hazards.push(`variable.${m[1]} in "${text}"`);
  if (/\/\s*0(?!\.)/.test(text)) hazards.push(`division by literal zero in "${text}"`);
  if (/\bmath\.random/.test(text)) hazards.push(`math.random in "${text}"`);
}
console.log(`  --- ${hazards.length} hazard(s) ---`);
for (const h of [...new Set(hazards)]) console.log(`    ${h}`);
process.exit(expressions.size ? 1 : 0);
