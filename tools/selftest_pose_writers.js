// The pose-writer gate: who writes which bone, and what the angle comes out as.
//
//   node tools/selftest_pose_writers.js
//
// Why this exists. The report is "the upper body twists about while the mob walks". GeckoLib's
// controllers write a bone first (AnimationProcessor#tickAnimation) and this mod's code writes it
// afterwards (GeoModel#setCustomAnimations), so the later writer WINS - the visible angle is not a sum,
// it is whichever writer ran last. The failure therefore has two shapes and no screenshot can tell them
// apart:
//
//   1. TWO EXPRESSIONS OF THE SAME AIM ON ONE BONE. A look-driven clip track and the code's aim write
//      both drive the bone; the winner flips with the AI state (aiming / not aiming, this clip / that
//      clip), so the pose alternates between two values instead of following the look. Its first
//      difference does not correlate with the input's, and its zero-crossing rate is unrelated to it.
//   2. A GAIN THAT IS NOT 1. The total head-chain yaw follows netHeadYaw by 0.7x, 1.4x, 0x - the head
//      stops landing where the mob looks. Detected by regressing the chain yaw on netHeadYaw.
//
// This tool replays the real pipeline over the real clip data and prints, for each configuration, the
// writers per frame, the gain (and its symmetry for +/- yaw), the correlation and zero-crossing rate of
// the differenced series, and the largest frame-to-frame step. It then asserts the acceptance criteria
// of the round.
//
// The clip evaluation is a faithful re-implementation of GeckoLib's per-frame bone write for the bones
// of this chain:
//   * a bone with a rotation track is set to its interpolated keyframe value (plus the bone's rest
//     rotation, which is 0 for every bone in this chain - read out of the geo file);
//   * controllers are applied in registration order, so the later one wins per bone (the movement
//     controller, then the gun controller - ScavEntity#registerControllers);
//   * the code path then runs exactly as RigSupport.applyAimTracking describes, share fold included;
//   * a bone is 'look-driven' when at least one of its keyframe values is a Molang expression rather
//     than a parsed number - the same rule ClipPose applies at runtime (MolangValue#isConstant), read
//     here from the JSON the same way GeckoLib's loader reads it (a string that parses as a number is a
//     constant, anything else is an expression).
//
// The Molang evaluator implements the subset the rig uses, math.* functions included. That is also the
// evidence for "are math.min / math.abs supported?": GeckoLib's MolangParser#doCoreRemaps registers
// every mclib function under its math.* name (min -> math.min, abs -> math.abs, clamp, floor, round,
// sqrt, pow, lerp, ...) - read out of geckolib-forge-1.20.1-4.8.4 with javap - so math.min(...) is
// evaluated rather than defaulted to 0, and no warning is emitted for it.
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const ANIM = path.join(ASSETS, 'animations', 'scav.animation.json');
const GEO = path.join(ASSETS, 'geo', 'scav.geo.json');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const num = (value, digits = 4) => (Number.isFinite(value) ? Number(value).toFixed(digits) : 'n/a');

// ==================================================================== Molang (the subset the rig uses)
// Function table: the bare mclib name and the math.* name GeckoLib remaps it to. Only min and abs are
// used by this rig; the rest are here so the tool documents the remap rather than hard-coding one call.
const FUNCTIONS = {
  min: Math.min, max: Math.max, abs: Math.abs, clamp: (v, lo, hi) => Math.min(Math.max(v, lo), hi),
  floor: Math.floor, ceil: Math.ceil, round: Math.round, sqrt: Math.sqrt, pow: Math.pow,
  sin: Math.sin, cos: Math.cos, tan: Math.tan, mod: (a, b) => a % b, ln: Math.log, exp: Math.exp,
  lerp: (a, b, t) => a + (b - a) * t,
};

function compileMolang(text) {
  const source = String(text);
  let index = 0;
  const skip = () => { while (index < source.length && /\s/.test(source[index])) index++; };
  const eat = (token) => {
    skip();
    if (source.startsWith(token, index)) { index += token.length; return true; }
    return false;
  };

  const parsePrimary = () => {
    skip();
    if (eat('(')) {
      const inner = parseExpression();
      if (!eat(')')) throw new Error(`missing ) in "${source}"`);
      return inner;
    }
    if (eat('!')) {
      const inner = parseUnary();
      return (vars) => (inner(vars) > 0 ? 0 : 1);
    }
    const number = /^\d+(\.\d+)?/.exec(source.slice(index));
    if (number) {
      index += number[0].length;
      const value = Number(number[0]);
      return () => value;
    }
    const name = /^[A-Za-z_][A-Za-z0-9_.]*/.exec(source.slice(index));
    if (!name) throw new Error(`cannot read an operand at ${index} in "${source}"`);
    index += name[0].length;
    if (source[index] === '(') {
      index++;
      const args = [];
      skip();
      if (!eat(')')) {
        do { args.push(parseExpression()); } while (eat(','));
        if (!eat(')')) throw new Error(`missing ) after the arguments of ${name[0]}`);
      }
      const bare = name[0].replace(/^math\./, '');
      const fn = FUNCTIONS[bare];
      if (!fn) throw new Error(`unknown function ${name[0]}`);
      return (vars) => fn(...args.map((arg) => arg(vars)));
    }
    const key = name[0];
    return (vars) => {
      if (!Object.prototype.hasOwnProperty.call(vars, key)) throw new Error(`unbound variable ${key}`);
      return vars[key];
    };
  };

  const parseUnary = () => {
    skip();
    if (eat('-')) {
      const inner = parseUnary();
      return (vars) => -inner(vars);
    }
    if (eat('+')) return parseUnary();
    return parsePrimary();
  };

  const parseBinary = (next, operators) => () => {
    let left = next();
    for (;;) {
      skip();
      const operator = operators.find((candidate) => source.startsWith(candidate, index));
      if (!operator) return left;
      index += operator.length;
      const lhs = left;
      const rhs = next();
      left = (vars) => {
        const a = lhs(vars);
        const b = rhs(vars);
        switch (operator) {
          case '*': return a * b;
          case '/': return b === 0 ? 0 : a / b;
          case '%': return b === 0 ? 0 : a % b;
          case '+': return a + b;
          case '-': return a - b;
          case '<': return a < b ? 1 : 0;
          case '>': return a > b ? 1 : 0;
          case '<=': return a <= b ? 1 : 0;
          case '>=': return a >= b ? 1 : 0;
          case '==': return a === b ? 1 : 0;
          case '!=': return a !== b ? 1 : 0;
          case '&&': return a > 0 && b > 0 ? 1 : 0;
          case '||': return a > 0 || b > 0 ? 1 : 0;
          default: throw new Error(`unhandled operator ${operator}`);
        }
      };
    }
  };

  const parseMul = parseBinary(parseUnary, ['*', '/', '%']);
  const parseAdd = parseBinary(parseMul, ['+', '-']);
  const parseCompare = parseBinary(parseAdd, ['<=', '>=', '<', '>', '==', '!=']);
  const parseAnd = parseBinary(parseCompare, ['&&']);
  const parseOr = parseBinary(parseAnd, ['||']);

  const parseExpression = () => {
    const condition = parseOr();
    skip();
    if (source[index] === '?') {
      index++;
      const ifTrue = parseExpression();
      if (!eat(':')) throw new Error(`missing : in "${source}"`);
      const ifFalse = parseExpression();
      // mclib's Ternary: the condition is true when it is greater than 0.
      return (vars) => (condition(vars) > 0 ? ifTrue(vars) : ifFalse(vars));
    }
    return condition;
  };

  const expression = parseExpression();
  skip();
  if (index < source.length) throw new Error(`unparsed tail "${source.slice(index)}" in "${source}"`);
  return expression;
}

const COMPILED = new Map();
const evaluate = (value, vars) => {
  if (typeof value === 'number') return value;
  const text = String(value).trim();
  if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
  if (!COMPILED.has(text)) COMPILED.set(text, compileMolang(text));
  return COMPILED.get(text)(vars);
};

// ==================================================================== the clip data
const animations = JSON.parse(fs.readFileSync(ANIM, 'utf8')).animations;
const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const boneByName = new Map(geo.bones.map((bone) => [bone.name, bone]));

// The aim chain, root first: the bones whose Y rotation adds up into where the head points. AllHead is
// in the list because it sits between UpperBody and Head; it carries no rotation in any clip (checked
// below) but leaving it out would make the "is this really the chain" check pass for the wrong reason.
const CHAIN = ['AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Head'];
const WATCHED = ['Root', 'AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Body', 'Head', 'Arm', 'RightArm',
  'LeftArm'];

console.log('rig assumptions (read from the geo file, not assumed):');
const headChain = [];
for (let bone = boneByName.get('Head'); bone; bone = bone.parent ? boneByName.get(bone.parent) : null) {
  headChain.unshift(bone.name);
}
check(JSON.stringify(headChain) === JSON.stringify(['Root', 'MAllBody', ...CHAIN]),
  'the head bone chain is Root -> MAllBody -> AllBody -> UpBody -> UpperBody -> AllHead -> Head',
  headChain.join(' -> '));
for (const bone of CHAIN) {
  const rest = (boneByName.get(bone).rotation || [0, 0, 0]);
  check(rest.every((value) => value === 0), `${bone} carries no rest rotation (this tool needs none)`,
    JSON.stringify(rest));
}
check(!Object.keys(animations['tac:walk'].bones || {})
  .some((bone) => ['UpBody', 'UpperBody', 'Head'].includes(bone)),
  'the armed movement clips carry no torso/head track (that is why modelLayering works)');
// AllHead is animated - with a fixed authored offset (yaw -33.09 on the rifle clips, -27.5 on the
// pistol ones, the rest of the 35.16 degree UpBody blading). It is a constant, it is inside the chain
// summed below, and because no keyframe of it is Molang it cannot contribute a look response - only a
// baseline. Both halves matter: a Molang AllHead would silently invalidate the gain measured below.

/** The keyframes of one channel, each as {time, value:[x,y,z]} with Molang strings kept as strings. */
function keyframesOf(channel) {
  if (channel === undefined || channel === null) return [];
  if (Array.isArray(channel)) return [{ time: 0, value: channel }];
  return Object.entries(channel).map(([time, raw]) => {
    let value = raw;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      value = value.post !== undefined ? value.post : value.pre !== undefined ? value.pre : value.value;
    }
    return { time: Number(time), value: Array.isArray(value) ? value : [0, 0, 0] };
  }).sort((a, b) => a.time - b.time);
}

const isMolangValue = (value) => typeof value === 'string' && !/^-?\d+(\.\d+)?$/.test(value.trim());

/** Per clip, per bone: does it have any track, and is any of its keyframe values a Molang expression? */
const CLIP_INFO = new Map();
for (const [clipName, clip] of Object.entries(animations)) {
  const info = new Map();
  for (const [bone, channels] of Object.entries(clip.bones || {})) {
    let any = false;
    let molang = false;
    for (const kind of ['rotation', 'position', 'scale']) {
      for (const keyframe of keyframesOf(channels[kind])) {
        any = true;
        if (keyframe.value.some(isMolangValue)) molang = true;
      }
    }
    info.set(bone, { any, molang });
  }
  CLIP_INFO.set(clipName, info);
}

const allHeadTracks = Object.entries(animations)
  .map(([name, clip]) => [name, (clip.bones || {}).AllHead])
  .filter(([, bone]) => bone && bone.rotation);
check(allHeadTracks.length > 0 && allHeadTracks.every(([, bone]) => keyframesOf(bone.rotation)
  .every((keyframe) => keyframe.value.every((value) => !isMolangValue(value)))),
  'AllHead is a fixed authored offset in every clip (a baseline in the chain, never a look response)',
  `${allHeadTracks.length} clip(s), e.g. ${allHeadTracks[0][0]} y=`
  + `${keyframesOf(allHeadTracks[0][1].rotation)[0].value[1]}`);

/** The union of what the given clips animate, the way ClipPose#of unions the playing controllers. */function unionInfo(clips) {
  const merged = new Map();
  for (const clipName of clips) {
    const info = CLIP_INFO.get(clipName);
    if (!info) throw new Error(`the animation file has no clip '${clipName}'`);
    for (const [bone, entry] of info) {
      const existing = merged.get(bone) || { any: false, molang: false };
      merged.set(bone, { any: existing.any || entry.any, molang: existing.molang || entry.molang });
    }
  }
  return merged;
}

/** The clip's rotation for a bone at a time, in degrees, or null when the clip does not animate it. */
function clipRotation(clipName, bone, time, vars) {
  const channels = (animations[clipName].bones || {})[bone];
  if (!channels || channels.rotation === undefined) return null;
  const frames = keyframesOf(channels.rotation);
  if (frames.length === 0) return null;
  const valueAt = (frame) => frame.value.map((value) => evaluate(value, vars));
  if (frames.length === 1 || time <= frames[0].time) return valueAt(frames[0]);
  if (time >= frames[frames.length - 1].time) return valueAt(frames[frames.length - 1]);
  let upper = 1;
  while (upper < frames.length - 1 && frames[upper].time < time) upper++;
  const before = frames[upper - 1];
  const after = frames[upper];
  const span = after.time - before.time;
  const t = span <= 0 ? 0 : (time - before.time) / span;
  const a = valueAt(before);
  const b = valueAt(after);
  // Linear between the surrounding keyframes. GeckoLib would ease with the track's lerp_mode
  // (catmullrom in this rig); both endpoints are affine in the variables, so the gain measured below is
  // unaffected and only the in-between shape differs.
  return a.map((value, axis) => value + (b[axis] - value) * t);
}

const clipLength = (clipName) => {
  let last = 0;
  for (const channels of Object.values(animations[clipName].bones || {})) {
    for (const kind of ['rotation', 'position', 'scale']) {
      for (const keyframe of keyframesOf(channels[kind])) last = Math.max(last, keyframe.time);
    }
  }
  return last > 0 ? last : 1;
};

// ==================================================================== the arbitration, as Java has it
/** The three cases of RigSupport#clipOwns, including auto's dependency on the yaw variable feed. */
function clipOwns(info, bone, poseSource, feed) {
  if (poseSource === 'code') return false;
  if (poseSource === 'clips') return true;
  const entry = info.get(bone);
  return Boolean(feed === 'all' && entry && entry.molang);
}

/** The Molang variables one feed mode supplies, exactly as RigSupport#supplyMolangVariables sets them. */
function variablesFor(feed, netHeadYaw, headPitch) {
  return {
    'ysm.head_yaw': feed === 'all' ? netHeadYaw : 0,
    'query.head_y_rotation': feed === 'all' ? netHeadYaw : 0,
    'ysm.head_pitch': feed === 'off' ? 0 : headPitch,
    'query.head_x_rotation': feed === 'off' ? 0 : headPitch,
    'query.is_sneaking': 0,
  };
}

// ==================================================================== the pipeline
function simulate(options) {
  const { clipMove, clipGun, poseSource, feed, torso, aiming, frames, yawOf, pitchOf, restPitch = 0 } = options;
  const active = [clipMove, clipGun].filter(Boolean);
  const info = unionInfo(active);
  const series = [];
  let lookConflicts = 0;
  let fixedOverrides = 0;
  const conflictBones = new Map();

  for (let frame = 0; frame < frames; frame++) {
    const time = frame / 60;
    const netHeadYaw = yawOf(time);
    const headPitch = pitchOf(time);
    const vars = variablesFor(feed, netHeadYaw, headPitch);

    // ---- 1. the controllers, in registration order (movement first, gun second: the later one wins)
    const bones = new Map(WATCHED.map((bone) => [bone, { x: 0, y: 0, z: 0 }]));
    const writers = new Map();
    for (const clipName of active) {
      const clipTime = time % clipLength(clipName);
      for (const bone of WATCHED) {
        const rotation = clipRotation(clipName, bone, clipTime, vars);
        if (!rotation) continue;
        bones.set(bone, { x: rotation[0], y: rotation[1], z: rotation[2] });
        const entry = info.get(bone);
        writers.set(bone, feed === 'all' && entry.molang ? 'clips(molang)' : 'clips(fixed)');
      }
    }

    // ---- 2. the code (RigSupport.applyAimTracking, share fold included)
    let torsoShare = aiming ? torso : 0.0;
    let pitchShare = aiming ? 0.4 : 0.0;
    const torsoOwns = aiming && !clipOwns(info, 'UpperBody', poseSource, feed);
    const headOwns = !clipOwns(info, 'Head', poseSource, feed);
    let headYawShare = 1.0 - torsoShare;
    let headPitchShare = 1.0 - pitchShare;
    if (torsoOwns && !headOwns) {
      torsoShare = 1.0;
      pitchShare = 1.0;
    } else if (!torsoOwns && headOwns) {
      headYawShare = 1.0;
      headPitchShare = 1.0;
    }
    for (const bone of ['UpperBody', 'Head']) {
      const owns = bone === 'UpperBody' ? torsoOwns : headOwns;
      if (!owns) continue;
      const current = bones.get(bone);
      const existing = writers.get(bone);
      // the writer bookkeeping, exactly as PoseWriters#note classifies it
      if (existing === 'clips(molang)') {
        lookConflicts++;
        conflictBones.set(bone, (conflictBones.get(bone) || 0) + 1);
      } else if (existing === 'clips(fixed)') {
        fixedOverrides++;
      }
      writers.set(bone, 'code');
      bones.set(bone, bone === 'UpperBody'
        ? { x: headPitch * pitchShare, y: netHeadYaw * torsoShare, z: current.z }
        : { x: headPitch * headPitchShare + restPitch, y: netHeadYaw * headYawShare, z: current.z });
    }

    const chainYaw = CHAIN.reduce((sum, bone) => sum + bones.get(bone).y, 0);
    // The torso below the neck, and the part the lower body sees: AllBody is the parent of BOTH UpBody
    // and DownBody, so a yaw on it turns the legs and the hips as well.
    const torsoYaw = bones.get('UpBody').y + bones.get('UpperBody').y;
    const bodyYaw = bones.get('AllBody').y;
    series.push({ frame, time, netHeadYaw, headPitch, chainYaw, torsoYaw, bodyYaw, writers, bones,
      torsoOwns, headOwns });
  }

  return { series, lookConflicts, fixedOverrides, conflictBones, info };
}

// ==================================================================== statistics
const mean = (values) => values.reduce((a, b) => a + b, 0) / values.length;
const slope = (xs, ys) => {
  const mx = mean(xs);
  const my = mean(ys);
  let numerator = 0;
  let denominator = 0;
  for (let i = 0; i < xs.length; i++) {
    numerator += (xs[i] - mx) * (ys[i] - my);
    denominator += (xs[i] - mx) ** 2;
  }
  return denominator === 0 ? 0 : numerator / denominator;
};
const correlation = (a, b) => {
  const ma = mean(a);
  const mb = mean(b);
  let num = 0;
  let da = 0;
  let db = 0;
  for (let i = 0; i < a.length; i++) {
    num += (a[i] - ma) * (b[i] - mb);
    da += (a[i] - ma) ** 2;
    db += (b[i] - mb) ** 2;
  }
  return da === 0 || db === 0 ? 0 : num / Math.sqrt(da * db);
};
const zeroCrossingRate = (values) => {
  let crossings = 0;
  for (let i = 1; i < values.length; i++) {
    if ((values[i] > 0 && values[i - 1] < 0) || (values[i] < 0 && values[i - 1] > 0)) crossings++;
  }
  return values.length < 2 ? 0 : crossings / (values.length - 1);
};
const maxAbs = (values) => values.reduce((best, value) => Math.max(best, Math.abs(value)), 0);

/**
 * The yaw response, with the clips' own time-varying animation removed.
 *
 * A clip that animates the torso over time (the walk cycle does) contributes a yaw that has nothing to
 * do with the look direction, and it would drown the statistics. So the same scenario is replayed with
 * netHeadYaw = headPitch = 0 and subtracted: what is left is exactly the chain's response to the look.
 */
function metrics(result, baseline) {
  const series = result.series;
  const chain = series.map((entry, index) => entry.chainYaw - baseline[index].chainYaw);
  const torso = series.map((entry, index) => entry.torsoYaw - baseline[index].torsoYaw);
  const body = series.map((entry, index) => entry.bodyYaw - baseline[index].bodyYaw);
  const yaw = series.map((entry) => entry.netHeadYaw);
  const positive = [];
  const negative = [];
  series.forEach((entry, index) => {
    if (entry.netHeadYaw > 1) positive.push(index);
    if (entry.netHeadYaw < -1) negative.push(index);
  });
  const dChain = chain.slice(1).map((value, index) => value - chain[index]);
  const dYaw = yaw.slice(1).map((value, index) => value - yaw[index]);
  return {
    gain: slope(yaw, chain),
    gainPositive: positive.length > 5 ? slope(positive.map((i) => yaw[i]), positive.map((i) => chain[i])) : NaN,
    gainNegative: negative.length > 5 ? slope(negative.map((i) => yaw[i]), negative.map((i) => chain[i])) : NaN,
    differenceCorrelation: correlation(dChain, dYaw),
    dChainZeroCross: zeroCrossingRate(dChain),
    dYawZeroCross: zeroCrossingRate(dYaw),
    maxStep: maxAbs(dChain),
    maxInputStep: maxAbs(dYaw),
    torsoSwing: Math.max(...torso) - Math.min(...torso),
    bodySwing: Math.max(...body) - Math.min(...body),
    lookConflicts: result.lookConflicts,
    fixedOverrides: result.fixedOverrides,
    conflictBones: [...result.conflictBones.entries()].map(([bone, n]) => `${bone}x${n}`).join(' ') || '-',
  };
}

/** Replays a scenario with no look input at all, to subtract the clips' own animation. */
function baselineOf(scenario, config) {
  return simulate({ frames: FRAMES, yawOf: () => 0, pitchOf: () => 0, ...scenario, ...config }).series;
}

// ---- the scenarios. `持枪走 10 秒` is the first one.
const FRAMES = 600;
// The mob weaves as it walks while its head stays on the target: netHeadYaw (GeckoLib's
// EntityModelData#netHeadYaw = bodyYaw - headYaw) swings +/-25 degrees at 0.4 Hz plus a slow drift.
const walkYaw = (time) => 25 * Math.sin(2 * Math.PI * 0.4 * time) + 8 * Math.sin(2 * Math.PI * 0.11 * time + 1.1);
const walkPitch = (time) => 9 * Math.sin(2 * Math.PI * 0.17 * time + 0.4);

const SCENARIOS = [
  {
    name: 'armed, walking, gun up (tac:walk + tac:aim:rifle)',
    clipMove: 'tac:walk', clipGun: 'tac:aim:rifle', aiming: true, asserted: true,
  },
  {
    name: 'unarmed, walking (walk)',
    clipMove: 'walk', clipGun: null, aiming: false, asserted: true,
  },
  {
    name: 'armed, retreating/holding (tac:walk + tac:hold:rifle, aiming)',
    clipMove: 'tac:walk', clipGun: 'tac:hold:rifle', aiming: true, asserted: true,
  },
  {
    name: 'armed, walking, pistol up (tac:walk + tac:aim:pistol)',
    clipMove: 'tac:walk', clipGun: 'tac:aim:pistol', aiming: true, asserted: false,
  },
  {
    name: 'armed, standing, state IDLE (tac:idle + tac:hold:rifle, rest pitch +20)',
    clipMove: 'tac:idle', clipGun: 'tac:hold:rifle', aiming: false, restPitch: 20, asserted: false,
  },
];

// The shipped value of client.torsoYawShare, read out of Config.java so this tool cannot drift from it.
const SHIPPED_TORSO_SHARE = (() => {
  const match = /DEFAULT_TORSO_YAW_SHARE\s*=\s*([0-9.]+)D/.exec(read('Config.java'));
  return match ? Number(match[1]) : 0.25;
})();
// The value this mod shipped before the round, kept here as the "before" of every comparison.
const OLD_TORSO_SHARE = 0.7;

const CONFIGS = [
  { feed: 'off', poseSource: 'code', torso: OLD_TORSO_SHARE,
    label: 'BEFORE: code + molang off + torso 0.7' },
  { feed: 'off', poseSource: 'code', torso: SHIPPED_TORSO_SHARE,
    label: 'code + off + shipped torso share' },
  { feed: 'pitch', poseSource: 'code', torso: SHIPPED_TORSO_SHARE, label: 'code + pitch + shipped torso' },
  { feed: 'pitch', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE,
    label: `AFTER: auto + pitch + torso ${SHIPPED_TORSO_SHARE} (shipped)` },
  { feed: 'all', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE, label: 'auto + all + shipped torso' },
  { feed: 'all', poseSource: 'clips', torso: SHIPPED_TORSO_SHARE, label: 'clips + all (author owns all)' },
];

const key = (scenario, config) => `${scenario.name}|${config.label}`;
const BEFORE = (scenario) => ({ ...scenario, feed: 'off', poseSource: 'code', torso: OLD_TORSO_SHARE });
const AFTER = (scenario) => ({ ...scenario, feed: 'pitch', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE });
const OLD = (scenario) => ({ ...scenario, feed: 'off', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE });

console.log(`\npose pipeline simulation: the real clips, the real arbitration, netHeadYaw swinging +/-25deg`
  + ` over ${FRAMES / 60}s at 60 fps`);

const results = new Map();
for (const scenario of SCENARIOS) {
  console.log(`\n--- ${scenario.name}`);
  console.log('    config                                        gain   gain+  gain-  corr(d)  zc(d)/zc(in)'
    + '  maxStep  torsoSwing  bodySwing  lookConf  fixedOvr');
  for (const config of CONFIGS) {
    const run = { frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...scenario, ...config };
    const stat = metrics(simulate(run), baselineOf(scenario, config));
    results.set(key(scenario, config), stat);
    console.log(`    ${config.label.padEnd(44)} ${num(stat.gain, 3).padStart(6)}  `
      + `${num(stat.gainPositive, 3).padStart(6)}  ${num(stat.gainNegative, 3).padStart(6)}  `
      + `${num(stat.differenceCorrelation, 3).padStart(6)}  `
      + `${num(stat.dChainZeroCross, 3)}/${num(stat.dYawZeroCross, 3)}   `
      + `${num(stat.maxStep, 2).padStart(6)}  ${num(stat.torsoSwing, 1).padStart(10)}  `
      + `${num(stat.bodySwing, 1).padStart(9)}  ${String(stat.lookConflicts).padStart(8)}  `
      + `${String(stat.fixedOverrides).padStart(8)}`);
  }

  const before = metrics(simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...BEFORE(scenario) }),
    baselineOf(scenario, BEFORE(scenario)));
  const after = metrics(simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...AFTER(scenario) }),
    baselineOf(scenario, AFTER(scenario)));
  const autoOff = metrics(simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...OLD(scenario) }),
    baselineOf(scenario, OLD(scenario)));
  const all = metrics(simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...scenario,
    feed: 'all', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE }),
  baselineOf(scenario, { feed: 'all', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE }));
  const autoPitch = results.get(key(scenario, CONFIGS[3]));
  const codePitch = results.get(key(scenario, CONFIGS[2]));

  if (scenario.asserted) {
    console.log('    acceptance:');
    check(Math.abs(after.gain - 1.0) <= 0.05,
      'the yaw chain follows netHeadYaw exactly once under the shipped default',
      `gain ${num(after.gain, 3)}`);
    check(Math.abs(after.gainPositive - after.gainNegative) <= 0.05,
      'the gain is symmetric for positive and negative yaw (the author uses ternaries)',
      `+${num(after.gainPositive, 3)} vs ${num(after.gainNegative, 3)}`);
    check(after.lookConflicts === 0,
      'no frame lets a look-driven clip track and the code both write one bone',
      `${after.lookConflicts} frame(s)`);
    check(after.differenceCorrelation >= 0.9,
      'the frame-to-frame change tracks the input\'s (no alternation)',
      `corr ${num(after.differenceCorrelation, 3)}`);
    check(after.dChainZeroCross <= Math.max(4 * after.dYawZeroCross, 0.02),
      'the yaw does not flip direction more often than the input does',
      `${num(after.dChainZeroCross, 3)} vs ${num(after.dYawZeroCross, 3)}`);
    check(after.maxStep <= 1.6 * after.maxInputStep + 1e-6,
      'no frame-to-frame jump is larger than the input step (no double angle)',
      `${num(after.maxStep, 2)} deg vs ${num(after.maxInputStep, 2)} deg`);
    if (scenario.aiming) {
      check(before.torsoSwing - after.torsoSwing > 20,
        'the torso swing really is what the shipped default takes out (the report, reproduced)',
        `${num(before.torsoSwing, 1)} -> ${num(after.torsoSwing, 1)} deg`);
      check(after.torsoSwing <= 20 && after.bodySwing <= 2.0,
        'and the lower body does not start swinging in exchange (the yaw symbols are not fed)',
        `torso ${num(after.torsoSwing, 1)} deg, body ${num(after.bodySwing, 1)} deg`);
      check(all.bodySwing > 20,
        'feeding the yaw symbols DOES swing the lower body (AllBody is the legs\' parent too), which is'
        + ' why the shipped default is pitch-only',
        `${num(all.bodySwing, 1)} deg`);
    }
    const codeOff = results.get(key(scenario, CONFIGS[1]));
    check(Math.abs(autoOff.gain - codeOff.gain) < 1e-9
        && Math.abs(autoOff.torsoSwing - codeOff.torsoSwing) < 1e-9,
      'poseSource = auto with the variables off is identical to code: the two keys are independent',
      `gain ${num(autoOff.gain, 3)}/${num(codeOff.gain, 3)}, torso swing `
      + `${num(autoOff.torsoSwing, 1)}/${num(codeOff.torsoSwing, 1)} deg`);
    // And with the PITCH feed (where nothing is look-driven either) auto must still equal code, which
    // is what makes the default a no-op for the arbitration and a pure "the author's pitch poses live"
    // change.
    check(Math.abs(autoPitch.gain - codePitch.gain) < 1e-9
        && Math.abs(autoPitch.torsoSwing - codePitch.torsoSwing) < 1e-9,
      'auto + pitch is identical to code + pitch (a yaw expression with the yaw symbols at 0 is a'
      + ' constant, so nothing is look-driven)',
      `gain ${num(autoPitch.gain, 3)}/${num(codePitch.gain, 3)}`);
  }
  console.log(`    before/after: torso swing ${num(before.torsoSwing, 1)} -> ${num(after.torsoSwing, 1)} deg`
    + `, body swing ${num(before.bodySwing, 1)} -> ${num(after.bodySwing, 1)} deg`
    + `, chain gain ${num(before.gain, 3)} -> ${num(after.gain, 3)}`
    + `, symmetry error ${num(Math.abs(before.gainPositive - before.gainNegative), 3)}`
    + ` -> ${num(Math.abs(after.gainPositive - after.gainNegative), 3)}`
    + `, differenced correlation ${num(before.differenceCorrelation, 3)}`
    + ` -> ${num(after.differenceCorrelation, 3)}`
    + `, max yaw step ${num(before.maxStep, 2)} -> ${num(after.maxStep, 2)} deg`
    + `, look-conflicts ${before.lookConflicts} -> ${after.lookConflicts}`);
  if (scenario.restPitch) {
    const headOwned = simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...AFTER(scenario) })
      .series.filter((entry) => entry.headOwns).length;
    console.log(`    rest pitch +${scenario.restPitch}: the code owns Head in ${headOwned}/${FRAMES}`
      + ` frame(s) under the shipped default, so the correction is applied in those frames`);
  }
}

// ==================================================================== the torso-share sweep
// The knob the report is about: whatever it is set to, the head carries the rest, so the total gain is
// 1.0 and only the split of the twist between chest and head changes.
console.log('\ntorso share sweep (armed walking, gun up; the shipped poseSource/molangVariables):');
console.log('    torsoYawShare   torso swing   chain gain   gain+   gain-');
for (const share of [0.0, 0.25, 0.5, 0.7, 1.0]) {
  const scenario = { frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...SCENARIOS[0],
    feed: 'pitch', poseSource: 'auto', torso: share };
  const stat = metrics(simulate(scenario), baselineOf(SCENARIOS[0], { feed: 'pitch', poseSource: 'auto', torso: share }));
  console.log(`    ${num(share, 2).padStart(12)}   ${num(stat.torsoSwing, 1).padStart(10)}   `
    + `${num(stat.gain, 3).padStart(10)}   ${num(stat.gainPositive, 3).padStart(6)}  `
    + `${num(stat.gainNegative, 3).padStart(6)}`);
  check(Math.abs(stat.gain - 1.0) <= 0.05,
    `torso share ${share}: the head takes the rest, so the look still lands in the same place`,
    `gain ${num(stat.gain, 3)}`);
  check(stat.torsoSwing <= share * 66.0 + 1.0,
    `torso share ${share}: the chest swing is at most the share of the look swing`,
    `${num(stat.torsoSwing, 1)} deg`);
}
check(SHIPPED_TORSO_SHARE >= 0.0 && SHIPPED_TORSO_SHARE <= 1.0,
  'the shipped torsoYawShare is a fraction', String(SHIPPED_TORSO_SHARE));

// ==================================================================== the one combination to avoid
// poseSource = code keeps the code on Head, and with the yaw symbols live the author's UpBody -head_yaw
// cancels the code's +head_yaw: the look stops being tracked AND the bone has two writers. That is why
// `code` is documented as a fallback to pair with molangVariables = off, not as an equal A/B.
const codeAll = metrics(
  simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...SCENARIOS[0], feed: 'all',
    poseSource: 'code', torso: SHIPPED_TORSO_SHARE }),
  baselineOf(SCENARIOS[0], { feed: 'all', poseSource: 'code', torso: SHIPPED_TORSO_SHARE }));
console.log(`\ncode + molang all (the combination the config warns about): look-conflicts `
  + `${codeAll.lookConflicts}/${FRAMES}, chain gain ${num(codeAll.gain, 3)}, differenced correlation `
  + `${num(codeAll.differenceCorrelation, 3)}`);
check(codeAll.lookConflicts > 0 && Math.abs(codeAll.gain) < 0.05
    && codeAll.differenceCorrelation < 0.5,
  'code + molang all is a two-writer frame AND loses the tracking: the author\'s live UpBody -head_yaw'
  + ' cancels the code\'s +head_yaw on the same chain, so `code` must be paired with molangVariables off',
  `conflicts ${codeAll.lookConflicts}, gain ${num(codeAll.gain, 3)}, corr `
  + `${num(codeAll.differenceCorrelation, 3)}`);

// ==================================================================== headline numbers
const armedNeedle = SCENARIOS[0].name;
const armedBefore = metrics(
  simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...BEFORE(SCENARIOS[0]) }),
  baselineOf(SCENARIOS[0], BEFORE(SCENARIOS[0])));
const armedAfter = metrics(
  simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...AFTER(SCENARIOS[0]) }),
  baselineOf(SCENARIOS[0], AFTER(SCENARIOS[0])));
const idleAfter = metrics(
  simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...AFTER(SCENARIOS[4]) }),
  baselineOf(SCENARIOS[4], AFTER(SCENARIOS[4])));
console.log(`\nheadline numbers (${armedNeedle}):`);
console.log(`  before (code + molang off + torso 0.7): torso swing ${num(armedBefore.torsoSwing, 1)} deg,`
  + ` chain gain ${num(armedBefore.gain, 3)}, max yaw step ${num(armedBefore.maxStep, 2)} deg,`
  + ` differenced correlation ${num(armedBefore.differenceCorrelation, 3)}, look-conflicts`
  + ` ${armedBefore.lookConflicts}, fixed-track overrides ${armedBefore.fixedOverrides}`);
console.log(`  after  (auto + pitch + torso ${SHIPPED_TORSO_SHARE}): torso swing`
  + ` ${num(armedAfter.torsoSwing, 1)} deg, chain gain ${num(armedAfter.gain, 3)}, max yaw step`
  + ` ${num(armedAfter.maxStep, 2)} deg, differenced correlation`
  + ` ${num(armedAfter.differenceCorrelation, 3)}, look-conflicts ${armedAfter.lookConflicts},`
  + ` fixed-track overrides ${armedAfter.fixedOverrides}`);
check(armedBefore.torsoSwing > 40,
  'the report is reproduced by the model: the chest pivoted 0.7 x netHeadYaw while the legs did not'
  + ' move at all',
  `${num(armedBefore.torsoSwing, 1)} deg peak-to-peak`);
check(armedAfter.torsoSwing <= 20 && Math.abs(armedAfter.gain - 1.0) <= 0.05,
  'the shipped default cuts that swing to the stylistic lean and keeps the aim exactly once',
  `${num(armedAfter.torsoSwing, 1)} deg, gain ${num(armedAfter.gain, 3)}`);
check(armedAfter.lookConflicts === 0 && idleAfter.lookConflicts === 0,
  'the shipped default has no look-conflict, in the aim states or while standing idle',
  `${armedAfter.lookConflicts} / ${idleAfter.lookConflicts}`);
console.log('  the rest pitch: under the shipped default the code still owns Head in every state of the'
  + ' idle scenario, so the +20 correction is unchanged.');
console.log('  the yaw symbols stay 0 by default because feeding them swings the lower body (measured'
  + ` ${num(metrics(simulate({ frames: FRAMES, yawOf: walkYaw, pitchOf: walkPitch, ...SCENARIOS[0], feed: 'all', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE }), baselineOf(SCENARIOS[0], { feed: 'all', poseSource: 'auto', torso: SHIPPED_TORSO_SHARE })).bodySwing, 1)}`
  + ' deg peak-to-peak, against 0.0 with pitch). /tarkovscav client pose molang all shows it live.');

// ==================================================================== the source guards
console.log('\nthe rule in the source (RigSupport / ClipPose / PoseWriters / Config):');
const rig = read('client/RigSupport.java');
const config = read('Config.java');
const poseSourceSource = read('client/PoseSource.java');
const molangAccess = read('client/MolangAccess.java');

check(/case CODE -> false;/.test(rig) && /case CLIPS -> true;/.test(rig)
    && /case AUTO -> Config\.feedsMolangYaw\(\) && clip\.lookDriven\(bone\);/.test(rig),
  'clipOwns has exactly the three documented cases, and auto also requires the YAW symbols to be fed');
check(/case AUTO -> !clip\.anyTrack\(bone\);/.test(rig),
  'the arm fallback yields to any track (it exists for rigs with no clips at all)');
check((rig.match(/PoseWriters\.note\(writers,/g) || []).length === 4,
  'every code write goes through PoseWriters.note (UpperBody, Head and the two arms)',
  `${(rig.match(/PoseWriters\.note\(writers,/g) || []).length} call(s)`);
check(/PoseWriters\.frame\(clip\)/.test(rig) && /PoseWriters\.flush\(entity, source, clip/.test(rig)
    && /PoseWriters\.beginMob\(\)/.test(read('client/ScavGeoModel.java'))
    && /PoseWriters\.beginMob\(\)/.test(read('client/GunnerPillagerGeoModel.java')),
  'the writer log is opened per mob (beginMob in handleAnimations), filled and closed around the code'
  + ' writes');
check(/boolean torsoOwns = upperBody != null && aiming && !clipOwns\(clip, "UpperBody", source\);/.test(rig)
    && /boolean headOwns = head != null && !clipOwns\(clip, "Head", source\);/.test(rig),
  'the two ownership predicates are the only gate on the two aim writes');
check(/public static void supplyMolangVariables\(Entity entity, float netHeadYaw, float headPitch\)/.test(rig)
    && ['MOLANG_HEAD_YAW', 'MOLANG_HEAD_PITCH', 'MOLANG_HEAD_Y_ROTATION', 'MOLANG_HEAD_X_ROTATION',
      'MOLANG_IS_SNEAKING'].every((name) => new RegExp(`MolangAccess\\.setValue\\(${name}`).test(rig)),
  'all five suppliable symbols are fed from the entity');
check(/"software\.bernie\.geckolib\.core\.molang\.MolangParser"/.test(molangAccess)
    && /isConstant/.test(molangAccess),
  'the Molang parser is reached by name (mclib is a shaded nested jar, not on the compile path) and a'
  + ' track counts as look-driven exactly when its keyframe value is not a constant');
check(/if \(existing\.contains\(CLIPS_MOLANG\)\) \{\s*\n\s*lookConflicts\+\+;/.test(read('client/PoseWriters.java'))
    && /fixedOverrides\+\+;/.test(read('client/PoseWriters.java'))
    && /boolean look = Config\.feedsMolangYaw\(\) && clip\.lookDriven\(bone\);/.test(read('client/PoseWriters.java')),
  'PoseWriters separates a look-driven conflict from a fixed-track override, and only calls a track'
  + ' look-driven while the yaw symbols are fed');
check(/public static final String DEFAULT = "auto";/.test(poseSourceSource)
    && /\.define\("poseSource", PoseSource\.DEFAULT\)/.test(config),
  'client.poseSource defaults to auto');
check(/DEFAULT_MOLANG_VARIABLES = "pitch"/.test(config)
    && /\.define\("molangVariables", DEFAULT_MOLANG_VARIABLES\)/.test(config),
  'client.molangVariables defaults to pitch (the yaw symbols are the author\'s body-yaw terms)');
check(/public static boolean feedsMolangYaw\(\) \{\s*\n\s*return molangFeed\(\) == MolangFeed\.ALL;/.test(config),
  'feedsMolangYaw is the single place "the yaw symbols are live" is decided');
check(/DEFAULT_TORSO_YAW_SHARE = [0-9.]+D/.test(config)
    && /\.defineInRange\("torsoYawShare", DEFAULT_TORSO_YAW_SHARE, 0\.0D, 1\.0D\)/.test(config)
    && /float torsoShare = aiming \? Config\.torsoYawShare\(\) : 0\.0F;/.test(rig),
  'client.torsoYawShare is the torso share the rule reads, bounded to 0..1');
check(/\.define\("logPoseWriters", false\)/.test(config),
  'client.logPoseWriters exists and defaults to false');
check(/MOLANG_HEAD_YAW = "ysm\.head_yaw"/.test(rig)
    && /MOLANG_HEAD_PITCH = "ysm\.head_pitch"/.test(rig)
    && /MOLANG_HEAD_Y_ROTATION = "query\.head_y_rotation"/.test(rig)
    && /MOLANG_HEAD_X_ROTATION = "query\.head_x_rotation"/.test(rig)
    && /MOLANG_IS_SNEAKING = "query\.is_sneaking"/.test(rig),
  'the five symbol names are the ones the rig actually uses');

// The symbol census, run as a subprocess, is the inventory this tool's assumptions come from.
const { spawnSync } = require('child_process');
const census = spawnSync(process.execPath, [path.join(__dirname, 'scan_molang_variables.js')],
  { encoding: 'utf8' });
if (census.status === 0) {
  const text = census.stdout;
  const missing = ['ysm.head_yaw', 'ysm.head_pitch', 'query.head_y_rotation', 'query.head_x_rotation',
    'query.is_sneaking'].filter((symbol) => !text.includes(symbol));
  check(missing.length === 0, 'the census still lists every symbol this mod feeds',
    missing.join(', ') || 'all five');
} else {
  console.log('  (the Molang census could not be run; skipped)');
}

// ==================================================================== the GeckoLib bytecode evidence
// "Are math.min / math.abs supported?" is answered from GeckoLib's own class file, not from an opinion:
// MolangParser#doCoreRemaps moves every mclib function registration to its Bedrock name, and the game's
// copy of that class is right there in the Gradle cache. Read it, list its strings, and check all four
// claims this mod depends on.
console.log('\nGeckoLib Molang evidence (read out of the geckolib jar on this machine):');
const GECKOLIB_CANDIDATES = [
  path.join(process.env.USERPROFILE || '', '.gradle', 'caches', 'modules-2', 'files-2.1',
    'software.bernie.geckolib'),
  'D:\\deepseek\\GirlsFrontline\\.gradle-home\\caches\\modules-2\\files-2.1\\software.bernie.geckolib',
  path.join(ROOT, 'build', 'tmp'),
];
function findGeckolibJar() {
  for (const candidate of GECKOLIB_CANDIDATES) {
    if (!fs.existsSync(candidate)) continue;
    const stack = [candidate];
    while (stack.length) {
      const current = stack.pop();
      let entries;
      try {
        entries = fs.readdirSync(current, { withFileTypes: true });
      } catch (unreadable) {
        continue;
      }
      for (const entry of entries) {
        const full = path.join(current, entry.name);
        if (entry.isDirectory()) stack.push(full);
        else if (entry.name.startsWith('geckolib-forge-') && entry.name.endsWith('.jar')
            && !entry.name.endsWith('-sources.jar')) return full;
      }
    }
  }
  return null;
}
const geckolibJar = findGeckolibJar();
if (!geckolibJar) {
  console.log('  (no geckolib jar found in the Gradle cache; the bytecode check is skipped)');
} else {
  console.log(`  jar: ${geckolibJar}`);
  const jarBytes = fs.readFileSync(geckolibJar);
  // A jar is a zip. Read the central directory (always carries real sizes and offsets), then inflate the
  // one entry this needs. Node-only: no unzip, no PowerShell, so the gate is reproducible anywhere.
  const zlib = require('zlib');
  function readEntry(buffer, wanted) {
    // End Of Central Directory: signature 0x06054b50, searched from the end (the comment is <= 64 KiB).
    let eocd = -1;
    for (let offset = buffer.length - 22; offset >= 0 && offset > buffer.length - 22 - 0x10000; offset--) {
      if (buffer.readUInt32LE(offset) === 0x06054b50) { eocd = offset; break; }
    }
    if (eocd < 0) return null;
    const count = buffer.readUInt16LE(eocd + 10);
    let cursor = buffer.readUInt32LE(eocd + 16);
    for (let entry = 0; entry < count && cursor + 46 <= buffer.length; entry++) {
      if (buffer.readUInt32LE(cursor) !== 0x02014b50) return null;
      const method = buffer.readUInt16LE(cursor + 10);
      const compressedSize = buffer.readUInt32LE(cursor + 20);
      const nameLength = buffer.readUInt16LE(cursor + 28);
      const extraLength = buffer.readUInt16LE(cursor + 30);
      const commentLength = buffer.readUInt16LE(cursor + 32);
      const localOffset = buffer.readUInt32LE(cursor + 42);
      const name = buffer.slice(cursor + 46, cursor + 46 + nameLength).toString('utf8');
      if (name === wanted) {
        const localNameLength = buffer.readUInt16LE(localOffset + 26);
        const localExtraLength = buffer.readUInt16LE(localOffset + 28);
        const start = localOffset + 30 + localNameLength + localExtraLength;
        const data = buffer.slice(start, start + compressedSize);
        if (method === 0) return data;
        if (method === 8) {
          try {
            return zlib.inflateRawSync(data);
          } catch (broken) {
            return null;
          }
        }
        return null;
      }
      cursor += 46 + nameLength + extraLength + commentLength;
    }
    return null;
  }
  const parserClass = readEntry(jarBytes, 'software/bernie/geckolib/core/molang/MolangParser.class');
  check(jarBytes.includes(Buffer.from('META-INF/jarjar/mclib-')),
    'mclib really is a nested jar inside the geckolib jar (META-INF/jarjar/mclib-*.jar), i.e. it is not'
    + ' on this mod\'s compile classpath');
  if (!parserClass) {
    console.log('  (MolangParser.class could not be read out of the jar; its bytecode check is skipped)');
  } else {
    // Byte search, not "extract the printable runs": a Utf8 constant is prefixed by its length byte, and
    // when that byte happens to be printable (36 = '$') a run-based scan glues it to the name.
    const has = (text) => parserClass.includes(Buffer.from(text));
    check(has('math.min') && has('math.max') && has('math.abs') && has('math.clamp'),
      'MolangParser registers the mclib functions under their math.* names'
      + ' (doCoreRemaps: functions.put("math.min", functions.remove("min")) ...)',
      ['math.min', 'math.max', 'math.abs', 'math.clamp'].filter(has).join(' '));
    check(has('min') && has('abs'),
      'and the bare names are the source of those remaps (the MathBuilder registrations)',
      ['min', 'abs'].filter(has).join(' '));
    check(has('setValue') && has('setMemoizedValue'),
      'MolangParser declares setValue/setMemoizedValue - what this mod binds the rig variables through');
    check(has('com/eliotlash/mclib/math/MathBuilder'),
      'and its superclass is the shaded mclib MathBuilder, which is why the call has to go through'
      + ' reflection (MolangAccess) instead of a direct one');
  }
}

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
