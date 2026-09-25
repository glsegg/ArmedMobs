// Census of every Molang variable and query the rig's keyframes use, with a verdict per symbol.
//
//   node tools/scan_molang_variables.js [animation.json]
//
// GeckoLib resolves a symbol it does not know to 0 (MolangParser -> a LazyVariable whose value is 0), so
// an author's expression that references YSM's own variables silently becomes a constant. Deciding
// whether those poses can be revived needs three things, and this prints all three:
//
//   * which symbols are used, how often, and by which clips/bones/channels;
//   * which of them this mod can supply from the entity (head yaw/pitch, sneaking, ...) and which it
//     cannot - a symbol that cannot be supplied must stay a WARN, never a silent 0;
//   * what every affected expression collapses to today, so the "revived vs collapsed" table in the
//     report can be produced from data instead of by hand.
//
// Symbols GeckoLib already provides through GeoModel#applyMolangQueries are listed as BUILT-IN: they work
// today (query.anim_time, query.life_time, query.ground_speed, ...), so they are not part of the problem.
const fs = require('fs');
const path = require('path');

const ANIM = process.argv[2]
  || path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json');

const BUILT_IN = new Set([
  'life_time', 'actor_count', 'time_of_day', 'moon_phase', 'distance_from_camera',
  'is_on_ground', 'is_in_water', 'is_in_water_or_rain', 'health', 'max_health',
  'is_on_fire', 'ground_speed', 'yaw_speed', 'controller_speed', 'anim_time', 'all_animations_finished',
]);
// What this mod can feed from the live entity. The right-hand column is the YSM semantic the rig was
// authored against: head yaw/pitch are the head's rotation RELATIVE TO THE BODY in degrees, which is
// exactly what GeckoLib hands a model as EntityModelData#netHeadYaw / #headPitch (negated).
const SUPPLIABLE = {
  'ysm.head_yaw': 'netHeadYaw (head yaw relative to the body, degrees; GeckoLib gives EntityModelData#netHeadYaw already negated)',
  'ysm.head_pitch': 'headPitch (view pitch; EntityModelData#headPitch)',
  'query.head_y_rotation': 'same quantity as ysm.head_yaw',
  'query.head_x_rotation': 'same quantity as ysm.head_pitch',
  'query.is_sneaking': 'entity.isShiftKeyDown() / pose == CROUCHING (mobs: false unless a mod crouches them)',
};

// NOT variables: function calls. GeckoLib's own MolangParser#doCoreRemaps moves every mclib function
// registration from its bare name to the math.* one the Bedrock syntax uses
// (`functions.put("math.min", functions.remove("min"))`, and the same for abs, clamp, floor, round,
// sqrt, pow, lerp, ... - read out of geckolib-forge-1.20.1-4.8.4 with javap). So `math.min(a, b)` is
// FOUND and EVALUATED rather than defaulted to 0, and it must not be reported as a missing variable.
// `node tools/selftest_pose_writers.js` evaluates them for real (its Molang parser implements the same
// table) and prints the arm angles they produce.
const FUNCTIONS = new Set([
  'min', 'max', 'abs', 'clamp', 'floor', 'ceil', 'round', 'trunc', 'sqrt', 'pow', 'mod', 'exp', 'ln',
  'sin', 'cos', 'tan', 'acos', 'asin', 'atan', 'atan2', 'random', 'random_integer', 'die_roll',
  'die_roll_integer', 'hermite_blend', 'lerp', 'lerprotate',
]);

const json = JSON.parse(fs.readFileSync(ANIM, 'utf8'));

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

const symbols = new Map();
const expressions = new Map();
const byClip = new Map();
let keyframesWithStrings = 0;
let stringValues = 0;

for (const [clipName, clip] of Object.entries(json.animations || {})) {
  for (const [bone, channels] of Object.entries(clip.bones || {})) {
    for (const kind of ['rotation', 'position', 'scale']) {
      if (!channels[kind]) continue;
      for (const kf of keyframesOf(channels[kind])) {
        const values = valuesOf(kf);
        const strings = values.filter((v) => typeof v === 'string');
        if (strings.length) {
          keyframesWithStrings++;
          stringValues += strings.length;
          for (const text of strings) expressions.set(text, (expressions.get(text) || 0) + 1);
          if (!byClip.has(clipName)) byClip.set(clipName, new Map());
          const inner = byClip.get(clipName);
          const key = `${bone}.${kind}`;
          inner.set(key, (inner.get(key) || 0) + strings.length);
        }
        for (const value of values) {
          if (typeof value !== 'string') continue;
          for (const m of value.matchAll(/([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)/g)) {
            const symbol = `${m[1]}.${m[2]}`;
            if (!symbols.has(symbol)) symbols.set(symbol, { count: 0, clips: new Set(), bones: new Set(), examples: [] });
            const entry = symbols.get(symbol);
            entry.count++;
            entry.clips.add(clipName);
            entry.bones.add(bone);
            if (entry.examples.length < 2) entry.examples.push(value);
          }
        }
      }
    }
  }
}

console.log(`${ANIM}
  keyframes with Molang strings: ${keyframesWithStrings} (${stringValues} string value(s)), ${expressions.size} distinct expression(s), ${symbols.size} distinct symbol(s)`);

console.log('\nsymbols, by how often they are used:');
for (const [symbol, entry] of [...symbols.entries()].sort((a, b) => b[1].count - a[1].count)) {
  const namespace = symbol.split('.')[0];
  const name = symbol.split('.')[1];
  let verdict;
  if (FUNCTIONS.has(name) && (namespace === 'math' || namespace === 'query' || namespace === 'q')) {
    verdict = `FUNCTION (GeckoLib registers it as ${namespace}.${name} via doCoreRemaps; it is evaluated,`
      + ' NOT a missing variable - no warning)';
  } else if (BUILT_IN.has(name) && (namespace === 'query' || namespace === 'q')) {
    verdict = 'BUILT-IN (GeckoLib already supplies it; works today)';
  } else if (SUPPLIABLE[symbol]) {
    verdict = `SUPPLIABLE -> ${SUPPLIABLE[symbol]}`;
  } else if (namespace === 'variable' || namespace === 'v') {
    verdict = 'NOT SUPPLIABLE (an author/YSM controller variable; must WARN, not default to 0)';
  } else {
    verdict = 'NOT SUPPLIABLE (unknown symbol; must WARN, not default to 0)';
  }
  console.log(`  ${symbol.padEnd(24)} ${String(entry.count).padStart(3)}x  ${entry.clips.size} clip(s)  ${entry.bones.size} bone(s)  ${verdict}`);
  if (entry.examples.length) console.log(`      e.g. "${entry.examples[0]}"`);
}

const suppliable = [...symbols.keys()].filter((s) => SUPPLIABLE[s]);
const builtIn = [...symbols.keys()].filter((s) => {
  const [ns, n] = s.split('.');
  return BUILT_IN.has(n) && (ns === 'query' || ns === 'q');
});
const functions = [...symbols.keys()].filter((s) => {
  const [ns, n] = s.split('.');
  return FUNCTIONS.has(n) && (ns === 'math' || ns === 'query' || ns === 'q');
});
const impossible = [...symbols.keys()].filter((s) => !SUPPLIABLE[s] && !builtIn.includes(s)
  && !functions.includes(s));
console.log(`\n  summary: ${suppliable.length} suppliable, ${builtIn.length} already built in, `
  + `${functions.length} function(s) GeckoLib already registers, ${impossible.length} that cannot be supplied`);
if (functions.length) {
  console.log(`  -> ${functions.join(', ')} are functions, not variables: GeckoLib's MolangParser`
    + ' registers them under their math.* names, so the expression evaluates. Nothing to feed, nothing to'
    + ' warn about.');
}
if (impossible.length) {
  console.log(`  -> ${impossible.join(', ')} must be reported with a WARN when Molang support is enabled, never silently 0`);
}

console.log('\nwhich clip/channel carries strings (this is what would come alive):');
for (const [clip, map] of byClip) {
  const parts = [...map.entries()].map(([k, n]) => `${k}x${n}`);
  console.log(`  ${clip.padEnd(22)} ${parts.join(' ')}`);
}

// What each expression collapses to today, for the report's before/after table.
const math = { min: Math.min, max: Math.max, abs: Math.abs, round: Math.round, floor: Math.floor, ceil: Math.ceil, sqrt: Math.sqrt, pow: Math.pow, sin: Math.sin, cos: Math.cos, tan: Math.tan, clamp: (v, lo, hi) => Math.min(Math.max(v, lo), hi) };
const collapse = (text, values) => {
  const substituted = text.replace(/\b(?:ysm|variable|v|query|q)\.([A-Za-z_][A-Za-z0-9_]*)/g, (match, name) => {
    const key = `query.${name}`;
    if (values && values[name] !== undefined) return String(values[name]);
    return BUILT_IN.has(name) ? `(${math.clamp ? 0 : 0})` : '0';
  });
  try {
    // eslint-disable-next-line no-new-func
    return new Function('math', `return (${substituted});`)(math);
  } catch (error) {
    return `UNKNOWN(${error.message})`;
  }
};

if (process.argv.includes('--examples')) {
  console.log('\ncollapsed values with all variables = 0 (today) and with a sample head pose (yaw 20, pitch -10):');
  const sample = { head_yaw: 20, head_pitch: -10, is_sneaking: 0 };
  for (const [text, count] of [...expressions.entries()].sort((a, b) => b[1] - a[1]).slice(0, 20)) {
    console.log(`  ${String(count).padStart(2)}x  ${text}`);
    console.log(`        -> 0 everywhere   = ${collapse(text, null)}`);
    console.log(`        -> sample pose    = ${collapse(text, sample)}`);
  }
}
process.exit(0);
