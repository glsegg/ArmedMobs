// README 5ab: lethal fire against an EXPOSED target, and the hurt -> retreat -> hold -> peek path.
//
//   node tools/selftest_ai_fire.js
//
// The user's report: "when they face an enemy who has stepped out of cover, the troop and elite AI
// only fire short bursts - they never empty a magazine, never actually kill the target, and when
// they themselves are being hit and beaten down they don't duck into cover."
//
// What is asserted, and why each check is the kind it is:
//
//   1. THE TRUTH TABLE of the exposure test, executed. "Exposed" is defined as eyes AND feet visible
//      from this mob's own eyes AND the target inside its effective range. The gate mirrors
//      AiProfile#isExposed, compares the mirror with the Java text CHARACTER BY CHARACTER (so the
//      simulation cannot drift), and then runs the real cases: fully visible in range = exposed,
//      head behind a wall = not, legs behind a wall = not, visible but out of range = not.
//   2. THE PER-TIER NUMBERS as shipped, parsed straight out of Config.AiSettings, plus the
//      ORDERINGS the design implies (troop/elite get the mag dump, sniper gets one shot, scav gets
//      nothing) and the explicit statement that SCAV ships every sentinel.
//   3. THE BURST DECODER, executed: -1 really means "until the magazine is empty or the target
//      dies" (decoded against the magazine that is loaded now, not against a hard-coded 30), a
//      positive value is exactly that, and suppression still scales and still cannot promise more
//      rounds than the magazine holds.
//   4. THE COOLDOWN IS SKIPPED ONLY WHILE EXPOSED, simulated on the mirrors: the same troop mob
//      fires 6 and pauses 20 plus a reposition when the target is behind cover, and keeps firing
//      with no pause at all when the target is exposed - and the double opt-in means SCAV's and
//      SNIPER's sentinels never take that branch.
//   5. THE HURT -> RETREAT -> HOLD -> PEEK PATH exists in the source AND is enforced: the hold clock
//      is armed on every entry into RETREAT, being hit while retreating re-arms it instead of
//      rolling for a new state, the hold cannot be left before the clock runs out, re-engaging goes
//      through REPOSITION (CombatTactics#peekSpot) rather than decide(), and the no-progress
//      watchdog stands down while the mob is deliberately holding IN cover.
//   6. THE "REVERT TO DUMB" RECIPE still yields the OLD numbers, executed over the six new keys:
//      copying [ai.scav] everywhere gives exposedRule = false, the gun tier's burst, the gun tier's
//      pause, the global warm-up 8, the global retreat health 0.35, the global hurt chance 0.5 and
//      no hold.
//   7. NO NEW STALL. Both behaviours the user asked for are loops, so both are bounded and the two
//      bounds are asserted: the mag dump ends in RELOAD (bounded by reloadStallTicks) or in the
//      existing FIRE watchdog (fireStallTicks), and the hold is shorter than giveUpTicks so the
//      RETREAT state still ends by itself. The existing gates (selftest_antistall.js, the FIRE
//      watchdog) are not weakened: this gate reads them and asserts they are still in place.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** The `{...}` body of the method whose signature starts at `signature`. */
function methodBody(code, signature) {
  const start = code.indexOf(signature);
  if (start < 0) return null;
  const open = code.indexOf('{', start);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '{') depth++;
    else if (code[i] === '}') {
      depth--;
      if (depth === 0) return code.slice(open + 1, i);
    }
  }
  return null;
}

/** The same normalised Java-vs-JS comparison tools/selftest_ai_profiles.js uses. */
const normJava = (text) => (text || '')
  .replace(/\(int\)/g, '')
  .replace(/\b(int|long|double|float|boolean|final)\b/g, '')
  .replace(/([0-9])\.0D/g, '$1')
  .replace(/\s+/g, '')
  .replace(/;/g, '');
const normJs = (text) => (text || '')
  .replace(/\bof\b/g, ':')
  .replace(/\b(const|let|var)\b/g, '')
  .replace(/\s+/g, '')
  .replace(/;/g, '');
const sameDecision = (java, js) => normJava(java) !== '' && normJava(java) === normJs(js);

const aiRaw = read('gun/AiProfile.java');
const ai = strip(aiRaw);
const brainRaw = read('gun/GunBrain.java');
const brain = strip(brainRaw);
const tactics = strip(read('gun/CombatTactics.java'));
const accuracyRaw = read('gun/AccuracyProfile.java');
const accuracy = strip(accuracyRaw);
const gunUser = strip(read('gun/GunUser.java'));
const config = read('Config.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

// ---------------------------------------------------------------------------------------------
// The shipped per-tier switches, parsed out of Config.AiSettings.
const TIERS = ['SCAV', 'SNIPER', 'TROOP', 'ELITE'];
/** Parses `defineInRange("key", switch (tier) { case SCAV -> -1; ... }, lo, hi)`, signs included. */
function switchDefaults(key) {
  const re = new RegExp(`defineInRange\\("${key}", switch \\(tier\\) \\{([\\s\\S]*?)\\},`);
  const body = re.exec(config);
  if (!body) return null;
  const values = {};
  for (const match of body[1].matchAll(/case\s+(\w+)\s*->\s*(-?[0-9.]+)D?;/g)) {
    values[match[1]] = Number(match[2]);
  }
  return TIERS.every((tier) => values[tier] !== undefined) ? values : null;
}

console.log('1. the exposure truth table: eyes AND feet visible from here AND in range');
const JS_EXPOSED = 'return eyesVisible && feetVisible && inRange;';
const isExposed = new Function('eyesVisible', 'feetVisible', 'inRange', JS_EXPOSED);
check(sameDecision(methodBody(ai, 'public static boolean isExposed('), JS_EXPOSED),
  'AiProfile#isExposed and the JS mirror are the same expression',
  'the mirror cannot drift from the shipped rule');
// The cases, in the shapes a fight produces them.
const CASES = [
  { eyes: true, feet: true, inRange: true, exposed: true, why: 'standing in the open, in range' },
  { eyes: false, feet: true, inRange: true, exposed: false, why: 'head (eyes) behind a wall = partial cover' },
  { eyes: true, feet: false, inRange: true, exposed: false, why: 'legs behind a wall = partial cover' },
  { eyes: false, feet: false, inRange: true, exposed: false, why: 'fully in cover' },
  { eyes: true, feet: true, inRange: false, exposed: false, why: 'fully visible but past engageRange: not a shot' },
  { eyes: false, feet: false, inRange: false, exposed: false, why: 'in cover and far away' },
];
console.log('  eyes  feet  inRange  exposed   case');
for (const c of CASES) {
  const got = isExposed(c.eyes, c.feet, c.inRange);
  check(got === c.exposed, `eyes=${c.eyes} feet=${c.feet} inRange=${c.inRange} -> ${c.exposed}`, c.why);
  console.log('  ' + String(c.eyes).padEnd(6) + String(c.feet).padEnd(6) + String(c.inRange).padEnd(9)
    + String(got).padEnd(10) + c.why);
}
// The two halves are real ray casts, not a flag: they mirror the ones isInCoverFrom already does.
check(/public boolean canSeeEyes\(ServerLevel level, LivingEntity other\)/.test(tactics)
  && /public boolean canSeeFeet\(ServerLevel level, LivingEntity other\)/.test(tactics),
  'CombatTactics computes both halves with ray casts (canSeeEyes / canSeeFeet)');
check(/hasLineOfSight\(level, this\.mob\.getEyePosition\(\), other\.getEyePosition\(\)\)/.test(tactics),
  'canSeeEyes is an eye-to-eye line of sight');
check(/hasLineOfSight\(level, this\.mob\.getEyePosition\(\), other\.position\(\)\.add\(0\.0D, 0\.2D, 0\.0D\)\)/
  .test(tactics),
  'canSeeFeet is an eye-to-feet line of sight (the same 0.2 sample isInCoverFrom uses)');
check(/private boolean targetExposed\(ServerLevel level, LivingEntity target\)/.test(brain)
  && /AiProfile\.isExposed\(eyes, feet, this\.mob\.distanceTo\(target\) <= engageRange\(\)\)/.test(brain),
  'GunBrain feeds the truth table the two halves plus the engageRange test');
check(/this\.exposedNow = targetExposed\(level, target\);/.test(brain)
  && /this\.exposedNow = false;/.test(brain),
  'the verdict is taken once per tick and cleared at the start of every tick (no stale exposure)');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. the per-tier numbers, as shipped');
const EXPECTED = {
  exposedBurstShots: { SCAV: 0, SNIPER: 1, TROOP: -1, ELITE: -1 },
  exposedBurstCooldownTicks: { SCAV: -1, SNIPER: -1, TROOP: 0, ELITE: 0 },
  warmupShotsWhenExposed: { SCAV: -1, SNIPER: -1, TROOP: 0, ELITE: 0 },
  retreatHealthFraction: { SCAV: -1, SNIPER: -1, TROOP: 0.5, ELITE: 0.55 },
  hurtRetreatChance: { SCAV: -1, SNIPER: -1, TROOP: 0.8, ELITE: 0.85 },
  retreatHoldTicks: { SCAV: 0, SNIPER: 60, TROOP: 80, ELITE: 60 },
};
const shipped = {};
for (const [key, expected] of Object.entries(EXPECTED)) {
  const parsed = switchDefaults(key);
  check(parsed !== null, `Config defines ${key} as a per-tier switch`, parsed ? '' : 'not parsed');
  shipped[key] = parsed === null ? expected : parsed;
  for (const tier of TIERS) {
    check(shipped[key][tier] === expected[tier], `${key}.${tier.toLowerCase()} ships as ${expected[tier]}`,
      `got ${shipped[key][tier]}`);
  }
}
console.log('  tier    exposedBurst  exposedCooldown  warmup  retreatHP  hurtChance  holdTicks');
for (const tier of TIERS) {
  console.log('  ' + tier.toLowerCase().padEnd(8)
    + String(shipped.exposedBurstShots[tier]).padEnd(15)
    + String(shipped.exposedBurstCooldownTicks[tier]).padEnd(17)
    + String(shipped.warmupShotsWhenExposed[tier]).padEnd(8)
    + String(shipped.retreatHealthFraction[tier]).padEnd(11)
    + String(shipped.hurtRetreatChance[tier]).padEnd(12)
    + shipped.retreatHoldTicks[tier]);
}
// The character of each tier, as inequalities rather than as a screenshot.
check(shipped.exposedBurstShots.TROOP === -1 && shipped.exposedBurstShots.ELITE === -1,
  'TROOP and ELITE empty the magazine against an exposed target (-1)');
check(shipped.exposedBurstCooldownTicks.TROOP === 0 && shipped.exposedBurstCooldownTicks.ELITE === 0,
  'and do not pause between those bursts (0)');
check(shipped.warmupShotsWhenExposed.TROOP === 0 && shipped.warmupShotsWhenExposed.ELITE === 0,
  'nor waste the first burst of the engagement on the warm-up penalty (0)');
check(shipped.exposedBurstShots.SNIPER === 1,
  'SNIPER fires one round at an exposed target (it does not spray)');
check(shipped.exposedBurstShots.SCAV === 0 && shipped.exposedBurstCooldownTicks.SCAV === -1
  && shipped.warmupShotsWhenExposed.SCAV === -1,
  'SCAV ships every sentinel: the gun tier decides its burst and its pause, and it stays wild');
check(shipped.hurtRetreatChance.TROOP > 0.5 && shipped.hurtRetreatChance.ELITE > shipped.hurtRetreatChance.TROOP,
  'the smart tiers break contact on a hit far more often than the global 0.5',
  `troop ${shipped.hurtRetreatChance.TROOP} > elite ${shipped.hurtRetreatChance.ELITE} > 0.5`);
check(shipped.hurtRetreatChance.SCAV === -1 && shipped.hurtRetreatChance.SNIPER === -1,
  'SCAV and SNIPER keep the global combat.hurtRetreatChance');
check(shipped.retreatHealthFraction.TROOP > 0.35 && shipped.retreatHealthFraction.ELITE > 0.35,
  'and retreat at HALF health instead of pressing on to the old 0.35 / 0.245',
  `troop ${shipped.retreatHealthFraction.TROOP}, elite ${shipped.retreatHealthFraction.ELITE} vs global 0.35`);
check(shipped.retreatHealthFraction.SCAV === -1 && shipped.retreatHealthFraction.SNIPER === -1,
  'SCAV keeps the low global value (it runs early) and SNIPER keeps its post (scaled global)');
check(shipped.retreatHoldTicks.TROOP > 0 && shipped.retreatHoldTicks.ELITE > 0
  && shipped.retreatHoldTicks.SCAV === 0,
  'the smart tiers hold cover, the dumb tier keeps the old re-evaluate-after-40-ticks path');
check(shipped.exposedBurstShots.ELITE === shipped.exposedBurstShots.TROOP
  && shipped.retreatHoldTicks.ELITE < shipped.retreatHoldTicks.TROOP,
  'ELITE is the aggressive one: same mag dump, shorter hold than the methodical troop');

// The two sentinel resolvers, mirrored and compared with the Java text.
const JS_RULE_APPLIES = 'return exposedBurstShots != 0 && exposedBurstCooldownTicks >= 0;';
const exposedRuleApplies = new Function('exposedBurstShots', 'exposedBurstCooldownTicks', JS_RULE_APPLIES);
check(sameDecision(methodBody(ai, 'public static boolean exposedRuleApplies('), JS_RULE_APPLIES),
  'AiProfile#exposedRuleApplies and the JS mirror are the same expression');
for (const tier of TIERS) {
  const applies = exposedRuleApplies(shipped.exposedBurstShots[tier], shipped.exposedBurstCooldownTicks[tier]);
  const expected = tier === 'TROOP' || tier === 'ELITE';
  check(applies === expected, `the exposed rule ${expected ? 'applies' : 'does NOT apply'} to ${tier}`,
    'the double opt-in (burst length AND an explicit pause) is what protects the other two tiers');
}
const JS_SCALED_HEALTH =
  'return absolute >= 0 ? absolute : scaleValue(global, scale);';
const scaleValue = (value, scale) => Math.max(0, value * scale);
const scaledRetreatHealthFraction =
  new Function('global', 'absolute', 'scale', 'scaleValue', JS_SCALED_HEALTH);
check(sameDecision(methodBody(ai, 'public static double scaledRetreatHealthFraction('), JS_SCALED_HEALTH),
  'AiProfile#scaledRetreatHealthFraction and the JS mirror are the same expression');
check(scaledRetreatHealthFraction(0.35, -1, 1.0, scaleValue) === 0.35
  && scaledRetreatHealthFraction(0.35, -1, 0.6, scaleValue) === 0.35 * 0.6,
  '-1 keeps the scaled global value (SCAV 0.35, SNIPER 0.21)');
check(scaledRetreatHealthFraction(0.35, 0.5, 1.0, scaleValue) === 0.5,
  'an absolute value ignores the scale entirely (TROOP 0.5)');
check(scaledRetreatHealthFraction(0.35, 0.0, 0.7, scaleValue) === 0.0,
  'and 0.0 is a real value: never retreat on health alone');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. the burst decoder: -1 is "until the magazine is empty or the target dies"');
const JS_DECODE = 'return configured < 0 ? Math.max(1, magazine) : configured;';
const decodeBurst = new Function('configured', 'magazine', JS_DECODE);
check(sameDecision(methodBody(brain, 'public static int decodeBurst('), JS_DECODE),
  'GunBrain#decodeBurst and the JS mirror are the same expression');
const JS_RULE = 'return exposed && tierExposedShots != 0 ? tierExposedShots : gunConfigured;';
const burstRuleFor = new Function('tierExposedShots', 'gunConfigured', 'exposed', JS_RULE);
check(sameDecision(methodBody(brain, 'public static int burstRuleFor('), JS_RULE),
  'GunBrain#burstRuleFor and the JS mirror are the same expression');
const JS_SUPPRESSED = 'return Math.min(Math.max(1, magazine), Math.round(shots * scale));';
const suppressedBurst = new Function('shots', 'scale', 'magazine', JS_SUPPRESSED);
check(sameDecision(methodBody(brain, 'public static int suppressedBurst('), JS_SUPPRESSED),
  'GunBrain#suppressedBurst and the JS mirror are the same expression');
const JS_COOLDOWN = 'return tierExposedCooldown < 0 ? gunCooldown : tierExposedCooldown;';
const burstCooldown = new Function('tierExposedCooldown', 'gunCooldown', JS_COOLDOWN);
check(sameDecision(methodBody(brain, 'public static int burstCooldown('), JS_COOLDOWN),
  'GunBrain#burstCooldown and the JS mirror are the same expression');

// A rifle: 6 shots per burst, 20 ticks between bursts, 30-round magazine. The shipped numbers.
const RIFLE = { burstShots: 6, cooldownTicks: 20, magazine: 30 };
check(decodeBurst(-1, RIFLE.magazine) === 30,
  '-1 against a 30-round magazine is 30 shots, i.e. the magazine', 'not the old hard-coded 30');
check(decodeBurst(-1, 60) === 60, 'and against an extended 60-round magazine it is 60',
  'the old code rewrote -1 to 30 and paused instead of emptying the magazine');
check(decodeBurst(-1, 1) === 1 && decodeBurst(-1, 0) === 1,
  'a one-round (or unknown, magazine() = -1) gun still gets one shot, never zero or negative');
check(decodeBurst(6, RIFLE.magazine) === 6 && decodeBurst(1, RIFLE.magazine) === 1,
  'a positive value is exactly that many shots (the sniper single shot included)');
check(burstRuleFor(shipped.exposedBurstShots.SCAV, RIFLE.burstShots, true) === 6,
  'SCAV: the 0 sentinel hands the decision back to the gun tier (6, short bursts)');
check(decodeBurst(burstRuleFor(shipped.exposedBurstShots.SCAV, RIFLE.burstShots, true), RIFLE.magazine) === 6,
  'so even an exposed target only ever gets the gun tier short burst from a scav');
check(decodeBurst(burstRuleFor(shipped.exposedBurstShots.TROOP, RIFLE.burstShots, true), RIFLE.magazine) === 30,
  'TROOP: the magazine goes down range');
check(burstRuleFor(shipped.exposedBurstShots.TROOP, RIFLE.burstShots, false) === RIFLE.burstShots,
  'and the same TROOP fires the normal 6-shot burst the moment the target is no longer exposed',
  'the exposed flag is half of the rule, not decoration');
check(decodeBurst(burstRuleFor(shipped.exposedBurstShots.SNIPER, 6, true), RIFLE.magazine) === 1,
  'SNIPER: one shot even if its gun tier were set to spray');
check(suppressedBurst(6, 1.2, RIFLE.magazine) === 7 && suppressedBurst(30, 1.2, RIFLE.magazine) === 30,
  'suppression still scales the burst but can never promise more rounds than the magazine holds',
  '6 x troop 1.2 = 7; a mag dump x 1.2 is still 30');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. the pause is skipped ONLY while the target is exposed');
/**
 * The whole post-burst decision, mirrored from GunBrain#finishBurst on the mirrors above.
 * Returns what the mob does when its burst ends.
 */
function finishBurst(tier, exposed, magazine) {
  const gunCooldown = RIFLE.cooldownTicks;
  const keepFiring = exposed && magazine > 0
    && exposedRuleApplies(shipped.exposedBurstShots[tier], shipped.exposedBurstCooldownTicks[tier]);
  const shots = decodeBurst(burstRuleFor(shipped.exposedBurstShots[tier], RIFLE.burstShots, exposed), magazine);
  if (keepFiring) {
    return {
      next: 'AIM',
      pause: burstCooldown(shipped.exposedBurstCooldownTicks[tier], gunCooldown),
      shots: shots,
    };
  }
  if (magazine <= 0) {
    return { next: 'RELOAD', pause: -1, shots: 0 };
  }
  return { next: 'REPOSITION', pause: gunCooldown, shots: shots };
}
const exposedTroop = finishBurst('TROOP', true, RIFLE.magazine);
check(exposedTroop.next === 'AIM' && exposedTroop.pause === 0 && exposedTroop.shots === RIFLE.magazine,
  'TROOP vs an exposed target: the magazine, then straight back into AIM with NO pause',
  `${exposedTroop.shots} shots, pause ${exposedTroop.pause}, next ${exposedTroop.next}`);
const coveredTroop = finishBurst('TROOP', false, RIFLE.magazine);
check(coveredTroop.next === 'REPOSITION' && coveredTroop.pause === RIFLE.cooldownTicks
  && coveredTroop.shots === RIFLE.burstShots,
  'the same TROOP vs a target in cover: the old 6-shot burst, 20-tick pause and a reposition',
  'the pre-5ab behaviour is intact wherever the user did not complain');
check(finishBurst('SCAV', true, RIFLE.magazine).next === 'REPOSITION'
  && finishBurst('SCAV', true, RIFLE.magazine).pause === RIFLE.cooldownTicks,
  'SCAV never takes the keep-firing branch, even against a fully exposed target',
  'both of its sentinels are in place, so it stays the dumb short-burst tier');
check(finishBurst('SNIPER', true, RIFLE.magazine).next === 'REPOSITION',
  'SNIPER never takes it either: its single shot keeps the gun tier pause and the reposition',
  'the hold-post rhythm, not a spray');
check(finishBurst('ELITE', true, 0).next === 'RELOAD',
  'an empty magazine goes to RELOAD, not to an AIM that would immediately ask for another shot');
// The source side of the same rule, so the mirror above cannot be true of a ship that differs.
const finishBody = methodBody(brain, 'private void finishBurst(');
check(finishBody !== null, 'GunBrain has the post-burst decision in one method');
check(/boolean keepFiring = this\.exposedNow\s*&& this\.magazine\(\) > 0\s*&& AiProfile\.exposedRuleApplies\(/
  .test(finishBody || ''),
  'keepFiring needs the exposed verdict AND rounds left AND the tier double opt-in');
check(/this\.burstPause = burstCooldown\(AiProfile\.exposedBurstCooldownTicks\(this\.mob\), gunCooldown\);/
  .test(finishBody || ''),
  'the keep-firing pause is the tier exposedBurstCooldownTicks, decoded against the gun tier');
check(/this\.burstPause = gunCooldown;/.test(finishBody || '')
  && /transition\(GunAiState\.REPOSITION\);/.test(finishBody || ''),
  'every other ending still gets the gun tier pause plus a REPOSITION');
check(/this\.burstTarget = burstSize\(this\.exposedNow, false\);/.test(brain),
  'the burst length is fixed on the first tick of the burst, from that tick exposure verdict');
check(/int rule = burstRuleFor\(AiProfile\.exposedBurstShots\(this\.mob\), gunConfigured, exposed\);/.test(brain)
  && /int gunConfigured = Config\.tier\(this\.loadout\.tier\(\)\)\.burstShots\.get\(\);/.test(brain),
  'and the rule order is: tier exposedBurstShots first (while exposed), gun tier burstShots otherwise');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. hurt -> retreat -> hold -> peek');
const onHurt = methodBody(brain, 'public void onHurt(');
check(onHurt !== null, 'GunBrain has the hurt hook');
check(/if \(this\.state == GunAiState\.RETREAT\) \{[\s\S]{0,220}?this\.retreatHoldUntil = this\.mob\.tickCount \+ AiProfile\.retreatHoldTicks\(this\.mob\);[\s\S]{0,40}?return;/
  .test(onHurt || ''),
  'being hit WHILE retreating re-arms the hold and returns - it cannot roll its way back into ADVANCE/AIM');
check(!/transition\(/.test((onHurt || '').slice((onHurt || '').indexOf('if (this.state == GunAiState.RETREAT)'),
  (onHurt || '').indexOf('if (this.mob.getRandom()'))),
  'and the RETREAT branch contains no transition at all (a structural guarantee, not a dice roll)');
check(/AiProfile\.hurtRetreatChance\(this\.mob\)/.test(onHurt || '')
  && /transition\(GunAiState\.RETREAT\);/.test(onHurt || ''),
  'the hit chance is the tier profile -1 = global, and the state change is the existing RETREAT');
const transition = methodBody(brain, 'private void transition(');
check(/this\.retreatHoldUntil = next == GunAiState\.RETREAT\s*\? this\.mob\.tickCount \+ AiProfile\.retreatHoldTicks\(this\.mob\)\s*: 0;/
  .test(transition || ''),
  'every entry into RETREAT arms the hold clock, from the one place all of them go through');
const holding = methodBody(brain, 'private boolean retreatHolding(');
check(holding !== null && /this\.state == GunAiState\.RETREAT/.test(holding)
  && /this\.mob\.tickCount < this\.retreatHoldUntil/.test(holding)
  && /this\.tactics\.isInCoverFrom\(level, target\.getEyePosition\(\)\)/.test(holding),
  'retreatHolding = in RETREAT, hold clock not expired, and actually IN cover');
const tickRetreat = methodBody(brain, 'private void tickRetreat(');
check(/if \(retreatHolding\(level, target\)\) \{\s*return;/.test(tickRetreat || ''),
  'tickRetreat refuses to do anything else while the hold is running');
check(/this\.stateTicks > 40 && safe && this\.mob\.tickCount >= this\.retreatHoldUntil/.test(tickRetreat || ''),
  'and the re-engage gate needs the hold clock to have run out as well as "safe"');
check(/boolean contact = this\.tactics\.isUnderFire\(\);/.test(tickRetreat || '')
  && /boolean safe = this\.tactics\.isInCoverFrom\(level, threatEye\) && !contact;/.test(tickRetreat || ''),
  'safe means in cover AND not under fire, so incoming fire keeps it down');
check(/if \(hold > 0\) \{[\s\S]{0,300}?transition\(GunAiState\.REPOSITION\);/.test(tickRetreat || '')
  && /int hold = AiProfile\.retreatHoldTicks\(this\.mob\);/.test(tickRetreat || ''),
  'a holding tier re-engages through REPOSITION instead of decide()');
check(/CombatTactics\.Spot spot = inCover\s*\?\s*this\.tactics\.peekSpot\(level, threatEye, 4\.0D, allowed\)/
  .test(brain),
  'and REPOSITION is the peek: it uses CombatTactics#peekSpot from the cover it is behind',
  'so re-engaging is a peek out, not a walk back into the open');
check(/this\.state == GunAiState\.RETREAT && !retreatHolding\(level, target\)/.test(
  methodBody(brain, 'private void checkMovementProgress(') || ''),
  'the no-progress watchdog stands down while the mob is deliberately holding IN cover',
  'standing still on purpose is not the stall that watchdog exists for');

// The hold, simulated tick by tick. A troop breaks contact at t=0 and sits in cover.
{
  const HOLD = shipped.retreatHoldTicks.TROOP;
  let tick = 0;
  let holdUntil = 0;
  let state = 'RETREAT';
  const log = [];
  // t=0: the hit that started it (transition arms the clock).
  holdUntil = tick + HOLD;
  // Being hit again at t=30, still retreating: the clock is re-armed, the state never changes.
  tick = 30;
  holdUntil = tick + HOLD;
  log.push(`hit while retreating at t=${tick}: state still ${state}, hold now ${holdUntil - tick}`);
  // t=40: in cover, out of contact, but the hold has 70 ticks left.
  for (const probe of [40, 79, 109, 110]) {
    tick = probe;
    const inCover = true;
    const contact = false;
    const holding = state === 'RETREAT' && tick < holdUntil && inCover;
    const safe = inCover && !contact;
    const reEngage = !holding && tick > 40 && safe && tick >= holdUntil;
    if (reEngage) state = 'REPOSITION';
    log.push(`t=${tick}: holding=${holding} re-engage=${reEngage} state=${state}`);
  }
  console.log('  ' + log.join('\n  '));
  check(log[0].includes('state still RETREAT'), 'being hit while retreating does not change the state');
  check(log[1].includes('holding=true') && log[1].includes('re-engage=false'),
    'at t=40 (in cover, out of contact) it is still holding - the old code would have re-evaluated');
  check(log[2].includes('holding=true') && log[2].includes('re-engage=false'),
    'and still holding most of the way through the re-armed clock');
  check(log[3].includes('holding=true') && log[3].includes('re-engage=false'),
    'and one tick before the clock expires');
  check(log[4].includes('re-engage=true') && log[4].includes('state=REPOSITION'),
    'the tick the clock expires it re-engages - through REPOSITION, the peek',
    'instead of walking back into the open');
  check(HOLD + 40 <= 160,
    'the hold plus the 40-tick minimum is inside combat.giveUpTicks 160, so RETREAT still ends by itself',
    `${HOLD} + 40 <= 160`);
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('6. the "revert to dumb" recipe, executed over the six new keys');
// The mirror of AiProfile's resolvers, on the shipped global baseline.
const BASELINE = {
  warmupShots: 8, retreatHealthFraction: 0.35, hurtRetreatChance: 0.5,
  gunBurstShots: RIFLE.burstShots, gunBurstCooldown: RIFLE.cooldownTicks,
};
const resolve5ab = (tier) => ({
  exposedRule: exposedRuleApplies(shipped.exposedBurstShots[tier], shipped.exposedBurstCooldownTicks[tier]),
  burstRule: burstRuleFor(shipped.exposedBurstShots[tier], BASELINE.gunBurstShots, true),
  cooldownRule: burstCooldown(shipped.exposedBurstCooldownTicks[tier], BASELINE.gunBurstCooldown),
  warmupShots: shipped.warmupShotsWhenExposed[tier] < 0
    ? BASELINE.warmupShots : shipped.warmupShotsWhenExposed[tier],
  retreatHealth: scaledRetreatHealthFraction(BASELINE.retreatHealthFraction,
    shipped.retreatHealthFraction[tier], 1.0, scaleValue),
  hurtRetreatChance: shipped.hurtRetreatChance[tier] < 0
    ? BASELINE.hurtRetreatChance : shipped.hurtRetreatChance[tier],
  holdTicks: shipped.retreatHoldTicks[tier],
});
const dumb = resolve5ab('SCAV');
const dumbJson = JSON.stringify(dumb);
for (const tier of TIERS) {
  // The recipe: copy the [ai.scav] block over this tier.
  const other = resolve5ab('SCAV');
  check(JSON.stringify(other) === dumbJson,
    `copying [ai.scav] over [ai.${tier.toLowerCase()}] yields the pre-5ab numbers`,
    JSON.stringify(other));
}
check(dumb.exposedRule === false && dumb.burstRule === BASELINE.gunBurstShots
  && dumb.cooldownRule === BASELINE.gunBurstCooldown,
  'the reverted tiers fire the gun tier burst and take the gun tier pause again',
  JSON.stringify(dumb));
check(dumb.warmupShots === 8 && dumb.retreatHealth === 0.35 && dumb.hurtRetreatChance === 0.5
  && dumb.holdTicks === 0,
  'and keep the global warm-up 8, the global retreat health 0.35, the global 0.5 hurt chance and no hold');
const live = resolve5ab('TROOP');
check(live.exposedRule === true && live.burstRule === -1 && live.warmupShots === 0
  && live.retreatHealth === 0.5 && live.hurtRetreatChance === 0.8 && live.holdTicks === 80,
  'while the shipped TROOP block really does change all six',
  JSON.stringify(live));

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('7. no new stall: both new loops are bounded');
const fireStall = Number((/defineInRange\("fireStallTicks", (\d+),/.exec(config) || [])[1]);
const reloadStall = Number((/defineInRange\("reloadStallTicks", (\d+),/.exec(config) || [])[1]);
const giveUp = Number((/defineInRange\("giveUpTicks", (\d+),/.exec(config) || [])[1]);
check(fireStall === 100 && reloadStall === 200 && giveUp === 160,
  'the three bounds the new rules lean on are still shipped as 100 / 200 / 160',
  `fireStall ${fireStall}, reloadStall ${reloadStall}, giveUp ${giveUp}`);
check(/if \(this\.watchdogTicks <= 0\) \{/.test(methodBody(brain, 'private void tickFire(') || ''),
  'the FIRE watchdog is still read in tickFire (a mag dump that TaCZ keeps refusing still ends)');
check(/if \(this\.shotsInBurst >= this\.burstTarget\) \{\s*finishBurst\(\);/.test(brain),
  'the burst still ends on its shot count, so "empty the magazine" is a finite loop');
check(/transition\(GunAiState\.RELOAD\);/.test(finishBody || ''),
  'and an emptied magazine ends in RELOAD, which is itself bounded by reloadStallTicks');
check(/int timeout = Config\.AI_COORD_OVERWATCH_TIMEOUT_TICKS\.get\(\);/.test(strip(read('gun/SquadCoordinator.java'))),
  'the squad overwatch timeout that bounds the other new-ish loop is untouched');
check(Math.max(...TIERS.map((tier) => shipped.retreatHoldTicks[tier])) < giveUp,
  'every shipped hold is shorter than giveUpTicks, so a hiding mob still ends its RETREAT',
  `max hold ${Math.max(...TIERS.map((tier) => shipped.retreatHoldTicks[tier]))} < ${giveUp}`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('8. the README documents the six keys and the priority rule');
const README_KEYS = ['exposedBurstShots', 'exposedBurstCooldownTicks', 'warmupShotsWhenExposed',
  'retreatHealthFraction', 'hurtRetreatChance', 'retreatHoldTicks'];
const missing = README_KEYS.filter((key) => !readme.includes(key));
check(missing.length === 0, 'every README 5ab key appears in README',
  missing.length ? missing.join(', ') : `${README_KEYS.length} keys`);
check(/### 5ab\./.test(readme), 'README has the 5ab section');
check(/\[tiers/.test(readme) && /burstShots/.test(readme),
  'and states the interaction with the per-gun [tiers] burstShots rule');
check(/exposedBurstShots/.test(readme) && /-1/.test(readme) && /空弹匣|empty the magazine|打空/.test(readme),
  'including what -1 means');
check(/revert to dumb|REVERT TO DUMB|一键变笨/.test(readme),
  'and the revert-to-dumb recipe is still documented');
check(/gates\/selftest_ai_fire|selftest_ai_fire/.test(readme),
  'and names this gate');

console.log('');
if (failures > 0) {
  console.log(`${failures} exposed-target / hurt-reaction check(s) FAILED`);
  process.exit(1);
}
console.log('exposed-target lethality + hurt-reaction invariants all hold');
