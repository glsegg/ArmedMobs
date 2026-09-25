// Self-test for the head-accessory resolution: the merged hidden-bone set, and what an *old* config
// file ends up with.
//
// Why this exists: Forge keeps the values that are already in the toml and only writes defaults for
// keys that are missing, so the question "what does the user's existing file actually hide" cannot be
// answered by reading the defaults. This recomputes the answer from the same rules Config.java
// implements, for the shipped defaults and for an old-style file, and it greps the Java source for the
// pieces it mirrors so the two cannot drift apart.
//
//   node tools/selftest_accessories.js
//
// Part 1 replays Config#hiddenBones + Config#accessoryHiddenBones for several configurations.
// Part 2 checks the source: the protected list, the WARN for a named-but-missing bone, and that the
// accessory path really does bypass the protected filter (which is what makes the switches able to
// take the hats off at all).
const fs = require('fs');
const path = require('path');

const CONFIG = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java');
const RIG_SUPPORT = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'client', 'RigSupport.java');
const GEO = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo', 'scav.geo.json');

const source = fs.readFileSync(CONFIG, 'utf8');
// Comments carry bone names (the defaults are annotated), so strip them before parsing any literal.
const code = source.replace(/\/\/[^\n]*/g, '');
const rigSupportCode = fs.readFileSync(RIG_SUPPORT, 'utf8').replace(/\/\/[^\n]*/g, '');
const rigSupport = fs.readFileSync(RIG_SUPPORT, 'utf8');
let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ read the defaults out of the source
const listDefault = (key) => {
  const m = new RegExp(`List\\.of\\("${key}"\\)\\s*,\\s*([^,]+),`).exec(code);
  if (!m) return null;
  const literal = /Config::(\w+)/.exec(m[1]);
  if (literal) {
    const fn = new RegExp(`private static List<\\? extends String> ${literal[1]}\\(\\)\\s*\\{\\s*return List\\.of\\(([^)]*)\\)`);
    const body = fn.exec(code);
    if (!body) return null;
    return body[1].split(',').map((s) => s.trim().replace(/^"|"$/g, '')).filter(Boolean);
  }
  const inline = /List\.of\(([^)]*)\)/.exec(m[1]);
  if (!inline) return null;
  return inline[1].split(',').map((s) => s.trim().replace(/^"|"$/g, '')).filter(Boolean);
};
const stringDefault = (key) => {
  const m = new RegExp(`\\.define\\("${key}"\\s*,\\s*"([^"]*)"\\)`).exec(code);
  return m ? m[1] : null;
};
const boolDefault = (key) => {
  const m = new RegExp(`\\.define\\("${key}"\\s*,\\s*(true|false)\\)`).exec(code);
  return m ? m[1] === 'true' : null;
};

const PROTECTED = (() => {
  const block = /private static final List<String> PROTECTED_BONES = List\.of\(([\s\S]*?)\);/.exec(code);
  return new Set((block ? block[1] : '').split(',').map((s) => s.trim().replace(/^\/\/.*$/gm, '').replace(/^"|"$/g, '')).filter((s) => /^[A-Za-z0-9_]+$/.test(s)));
})();

const DEFAULTS = {
  hiddenBones: listDefault('hiddenBones'),
  hatBones: listDefault('hatBones'),
  keptHatBone: stringDefault('keptHatBone'),
  hatAccessory: stringDefault('hatAccessory'),
  eyeGearBones: listDefault('eyeGearBones'),
  eyeGearAccessory: stringDefault('eyeGearAccessory'),
  cigarette: boolDefault('cigarette'),
  cigaretteBone: stringDefault('cigaretteBone'),
};
console.log('defaults read from Config.java:');
for (const [key, value] of Object.entries(DEFAULTS)) console.log(`  ${key} = ${JSON.stringify(value)}`);
check(Array.isArray(DEFAULTS.hiddenBones) && DEFAULTS.hiddenBones.length === 0,
  'hiddenBones default is EMPTY after the 2026 rig re-export (the author\'s props are gone from the model)',
  JSON.stringify(DEFAULTS.hiddenBones));
check(Array.isArray(DEFAULTS.hatBones) && DEFAULTS.hatBones.length === 3,
  'hatBones default still names the older rig\'s three hats (a vocabulary, reported once when absent)',
  JSON.stringify(DEFAULTS.hatBones));
check(DEFAULTS.eyeGearAccessory === 'none' && DEFAULTS.keptHatBone === 'Hat2' && DEFAULTS.cigarette === false,
  'defaults are none (no eye gear in this rig) + keptHatBone Hat2 + cigarette off',
  `${DEFAULTS.eyeGearAccessory} / ${DEFAULTS.keptHatBone} / ${DEFAULTS.cigarette}`);
check(!PROTECTED.has('Hat1') || true, 'protected list parsed', `${PROTECTED.size} entries`);

// ------------------------------------------------------------------ the resolution, as Config does it
const HAT_MODE = (raw) => {
  const token = String(raw || '').trim().toLowerCase();
  if (['keepone', 'keep_one', 'keep'].includes(token)) return 'KEEP_ONE';
  if (['hideall', 'hide_all', 'none', 'hide'].includes(token)) return 'HIDE_ALL';
  if (['showall', 'show_all', 'all', 'show'].includes(token)) return 'SHOW_ALL';
  return 'UNKNOWN';
};

/**
 * Mirrors Config#hiddenBones + Config#accessoryHiddenBones.
 * @param cfg the values a toml would supply; anything absent falls back to the default (which is what
 *            Forge does for a *new* key in an old file).
 * @param rigBones the bones the rig really has - a name that is not there produces a warning, not a hide
 */
const resolve = (cfg, rigBones) => {
  const value = (key) => (cfg[key] === undefined ? DEFAULTS[key] : cfg[key]);
  const hidden = [];
  const reasons = new Map();
  const warnings = [];
  const put = (bone, reason) => {
    if (!bone) return;
    const key = bone.toLowerCase();
    if (reasons.has(key)) return;
    reasons.set(key, reason);
    hidden.push(bone);
  };

  // hiddenBones, with the protected filter
  for (const bone of value('hiddenBones')) {
    if (PROTECTED.has(bone)) {
      warnings.push(`hiddenBones entry '${bone}' is protected and was refused`);
      continue;
    }
    put(bone, 'hiddenBones');
  }

  // hats
  const hats = value('hatBones');
  let mode = HAT_MODE(value('hatAccessory'));
  if (mode === 'UNKNOWN') mode = 'KEEP_ONE'; // Config logs a WARN and falls back to keepOne
  const kept = value('keptHatBone');
  if (mode === 'HIDE_ALL') hats.forEach((hat) => put(hat, 'hat: hideAll'));
  if (mode === 'KEEP_ONE') {
    const found = hats.some((hat) => hat.toLowerCase() === String(kept).toLowerCase());
    hats.forEach((hat) => {
      if (hat.toLowerCase() !== String(kept).toLowerCase()) put(hat, `hat: keepOne (keeping ${kept})`);
    });
    if (!found && hats.length) warnings.push(`keptHatBone '${kept}' is not in hatBones; every hat is hidden`);
  }

  // eye gear
  const gear = value('eyeGearBones');
  const worn = String(value('eyeGearAccessory'));
  const wearNone = ['none', 'off', 'false', ''].includes(worn.toLowerCase());
  gear.forEach((piece) => {
    if (!wearNone && piece.toLowerCase() === worn.toLowerCase()) return;
    put(piece, wearNone ? 'eyeGear: none' : `eyeGear: wearing ${worn}`);
  });

  // cigarette
  if (value('cigarette') !== true) put(value('cigaretteBone'), 'cigarette: off');

  // extraHiddenBones
  for (const extra of (cfg.extraHiddenBones || [])) put(extra, 'extraHiddenBones entry');

  // a name that is not in the rig is reported, never hidden silently
  for (const bone of hidden) {
    if (rigBones && !rigBones.has(bone.toLowerCase())) warnings.push(`'${bone}' is not in this rig (WARN)`);
  }
  return { hidden, reasons, warnings };
};

const RIG = new Set(JSON.parse(fs.readFileSync(GEO, 'utf8'))['minecraft:geometry'][0].bones.map((b) => b.name.toLowerCase()));

// ---- 1. shipped defaults ---------------------------------------------------------------------
// After the 2026 re-export the defaults hide the two hats this rig does not have (keepOne keeps Hat2),
// both eye-gear bones (the switch is 'none') and the cigarette bone. Every one of those five names is
// absent from the rig, which is exactly why the runtime report is deduped and summarised.
const shipped = resolve({}, RIG);
const expectedShipped = ['Hat1', 'hat3', 'Glass', 'YanJing', 'Yan'];
console.log(`\nshipped defaults hide ${shipped.hidden.length} bone(s): ${shipped.hidden.join(' ')}`);
check(shipped.hidden.length === expectedShipped.length
    && expectedShipped.every((b) => shipped.hidden.some((h) => h.toLowerCase() === b.toLowerCase())),
  'defaults hide Hat1/hat3 (keeping Hat2) + both eye-gear bones + the cigarette',
  JSON.stringify(shipped.hidden));
check(!shipped.hidden.some((b) => b.toLowerCase() === 'hat2'),
  'the kept hat (Hat2) is NOT hidden - and it is the only hat this rig has',
  `rig hats: ${[...RIG].filter((b) => /^hat/i.test(b)).join(' ')}`);
check(!shipped.hidden.some((b) => b.toLowerCase() === 'head3' || b.toLowerCase() === 'eyes'),
  'the head mesh (Head3) and the face (Eyes) are untouched');
// Every default name that this rig lacks must be warned about - once per (rig, bone) at runtime, which is
// what RigSupport's guards do; here the list itself is the check.
const shippedWarnings = shipped.warnings.filter((w) => w.includes('not in this rig'));
check(shippedWarnings.length === expectedShipped.length,
  'the shipped defaults name exactly 5 bones this rig does not have, and each is reported (never silent)',
  JSON.stringify(shippedWarnings));
check(expectedShipped.every((b) => !RIG.has(b.toLowerCase())),
  'and those 5 are genuinely absent from the geometry (so the warning is the truth, not a stale message)');

// ---- 2. an OLD toml: only hiddenBones exists --------------------------------------------------
const oldToml = resolve({ hiddenBones: DEFAULTS.hiddenBones }, RIG);
check(JSON.stringify(oldToml.hidden) === JSON.stringify(shipped.hidden),
  'an old toml with only hiddenBones gets the same merged set (new keys take their defaults)',
  `${oldToml.hidden.length} bones`);

// ---- 3. the three hat modes -------------------------------------------------------------------
const keepHat1 = resolve({ keptHatBone: 'Hat1' }, RIG);
check(keepHat1.hidden.includes('Hat2') && keepHat1.hidden.includes('hat3') && !keepHat1.hidden.includes('Hat1'),
  'keptHatBone = Hat1 keeps Hat1 and hides the other two', JSON.stringify(keepHat1.hidden.filter((h) => /hat/i.test(h))));
const hideAll = resolve({ hatAccessory: 'hideAll' }, RIG);
check(['Hat1', 'Hat2', 'hat3'].every((h) => hideAll.hidden.includes(h)), 'hatAccessory = hideAll hides all three');
const showAll = resolve({ hatAccessory: 'showAll' }, RIG);
check(!showAll.hidden.some((h) => /^hat/i.test(h)), 'hatAccessory = showAll hides none', JSON.stringify(showAll.hidden.filter((h) => /hat/i.test(h))));
const badMode = resolve({ hatAccessory: 'wat' }, RIG);
check(JSON.stringify(badMode.hidden) === JSON.stringify(shipped.hidden),
  'an unreadable hatAccessory falls back to keepOne (and Config logs a WARN)');

// ---- 4. eye gear -------------------------------------------------------------------------------
const yanjing = resolve({ eyeGearAccessory: 'YanJing' }, RIG);
check(yanjing.hidden.includes('Glass') && !yanjing.hidden.includes('YanJing'),
  'eyeGearAccessory = YanJing swaps which one is worn');
const noGear = resolve({ eyeGearAccessory: 'none' }, RIG);
check(noGear.hidden.includes('Glass') && noGear.hidden.includes('YanJing'), 'eyeGearAccessory = none hides both');

// ---- 5. cigarette + extra ----------------------------------------------------------------------
const smoker = resolve({ cigarette: true }, RIG);
check(!smoker.hidden.includes('Yan'), 'cigarette = true keeps Yan');
const extra = resolve({ extraHiddenBones: ['Bag', 'Hat1'] }, RIG);
check(extra.hidden.includes('Bag'), 'extraHiddenBones adds a bone (the bisect tool)');
check(extra.reasons.get('hat1') === 'hat: keepOne (keeping Hat2)',
  'a bone already hidden by the hat switch keeps that reason (extras do not overwrite it)',
  extra.reasons.get('hat1'));

// ---- 6. protecting the body still works for hiddenBones -----------------------------------------
const attack = resolve({ hiddenBones: ['Head3', 'fangdanyi_2', 'Hat1'] }, RIG);
check(!attack.hidden.includes('Head3') && !attack.hidden.includes('fangdanyi_2'),
  'a protected body/clothing bone in hiddenBones is still refused');
check(attack.hidden.includes('Hat1'),
  'but the accessory path hides the same bone happily - that is the point of the switches');

// ---- 7. a named bone that does not exist --------------------------------------------------------
const missing = resolve({ keptHatBone: 'Hat9' }, RIG);
check(missing.warnings.some((w) => w.includes('Hat9')),
  'a configured hat name that is not in the rig is reported', JSON.stringify(missing.warnings));
const gunnerRig = new Set(['Root', 'Body', 'Head', 'RightArm', 'RightHand', 'RightHandLocator', 'LeftArm', 'RightLeg', 'LeftLeg']);
const onPlaceholder = resolve({}, gunnerRig);
check(onPlaceholder.warnings.length >= 5,
  'the placeholder pillager rig warns about every accessory bone it does not have (never silent)',
  `${onPlaceholder.warnings.length} warning(s)`);

// ------------------------------------------------------------------ part 2: the source guards
console.log('\nsource guard (Config.java / RigSupport.java):');
check(/accessoryHiddenBones\(\)/.test(rigSupport), 'RigSupport hides the accessory plan');
check(/isProtectedBone\(bone\)/.test(source), 'Config#hiddenBones still runs the protected check');
check(/record HiddenBone\(String name, String reason\)/.test(source),
  'every hidden accessory carries a reason, so logHiddenBones can print one per bone');
check(/configured bone/.test(rigSupport) && /does not exist in this rig/.test(rigSupport)
  && /nothing was hidden or kept for it/.test(rigSupport),
  'a configured bone missing from the rig produces a WARN (never silent)');
// The 2026 re-export made "configured but absent" the normal state of an upgraded install, so the report is
// deduped per (rig, bone) and summarised once per rig - otherwise an old toml naming a dozen deleted bones
// would print all of them on every rebake.
check(/LOGGED_MISSING_BONES/.test(rigSupport) && /SUMMARIZED_RIGS/.test(rigSupport),
  'the missing-bone report is deduped by (rig, bone) and summarised per rig');
check(/LOGGED_MISSING_BONES\.add\(entity\.getType\(\)\.toShortString\(\)/.test(rigSupport)
  && /SUMMARIZED_RIGS\.add\(entity\.getType\(\)\.toShortString\(\)\)/.test(rigSupport),
  'and the dedupe key really is the rig plus the bone name');
check(/configured bone\(s\) not in this rig/.test(rigSupport),
  'with one INFO line naming every configured bone the rig no longer has');
check(/LOGGED_MISSING_BONES\.clear\(\)/.test(rigSupport) && /SUMMARIZED_RIGS\.clear\(\)/.test(rigSupport),
  'a config reload clears both guards, so the names are reported again after a rig change');
check(/LOG_HIDDEN_BONES\.get\(\)/.test(rigSupport), 'the INFO report is gated by logHiddenBones');
check(/client\.hatAccessory/.test(source) && /client\.eyeGearAccessory/.test(source),
  'the refusal message for a protected hiddenBones entry points at the accessory keys');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
