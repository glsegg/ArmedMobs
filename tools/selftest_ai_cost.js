// The per-tick cost invariants of the armed-unit AI.
//
//   node tools/selftest_ai_cost.js
//
// Why this gate exists: the user reported "my own framerate is fine, but the villagers/AI stutter when
// they move, and a dead unit takes a long time to disappear". Measured with tools/spike/work/aiperf
// (a real city street, real gunner_villagers, /forge tps), the AI cost was about 1.25 ms of server tick
// PER ARMED UNIT PER TICK, so fifty units alone exceed the 50 ms budget - and a 20-tick death animation
// then takes 20 x 64 ms = 1.28 s of wall clock. This file pins the three things that were added to fix
// it, so they cannot be quietly removed:
//
//   1. THE DEAD GUARD, FIRST IN EVERY TICK ENTRY POINT. Not "somewhere in the method": the measurement
//      showed a dying unit already costs almost nothing, so a guard that sits after the work it is
//      meant to skip is worse than useless - it looks like a fix and pays the cost anyway. For each
//      entry point the guard is asserted to appear before every call that does real work.
//   2. THE CADENCES EXIST AND ARE HONOURED. One line-of-sight verdict per mob per SIGHT_CACHE_TICKS
//      ticks (staggered by entity id), the accuracy decay read every ACCURACY_DECAY_CHECK_TICKS, the
//      faction layer every TICK_INTERVAL_TICKS, focus fire retried on FOCUS_FIRE_RETRY_TICKS, and a
//      path re-issue no more often than PATH_RETRY_COOLDOWN_TICKS. Each constant is asserted to be
//      read somewhere, so a constant cannot be renamed out of use and still pass.
//   3. THE CACHES ARE PER ENTITY. The sight verdict is keyed on the target's entity id and stored in
//      instance fields; a static cache would make one mob's target hide every other mob's.
//
// Section 5 prints the recorded measurements when a run is present. It is a report, not an assertion:
// a gate that fails because somebody deleted an evidence file is a gate nobody keeps.
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

// ---------------------------------------------------------------------------------------------
console.log('1. the dead guard is present in every per-tick AI entry point, and ordered first');
console.log('   (a guard that sits after the work it skips is not a fix: measured, the corpse already');
console.log('    costs nothing, so the only thing a late guard buys is the appearance of one)');

const OWN = /if\s*\(\s*this\.isRemoved\(\)\s*\|\|\s*this\.isDeadOrDying\(\)\s*\)/;
const MOB = /if\s*\(\s*this\.mob\.isRemoved\(\)\s*\|\|\s*this\.mob\.isDeadOrDying\(\)\s*\)/;
const ARG = /if\s*\(\s*mob\.isRemoved\(\)\s*\|\|\s*mob\.isDeadOrDying\(\)\s*\)/;

const SOURCES = {
  brain: read('gun/GunBrain.java'),
  tactics: read('gun/CombatTactics.java'),
  doors: read('gun/DoorBehavior.java'),
  factionAi: read('faction/FactionAi.java'),
  alert: read('faction/AlertNetwork.java'),
  renegade: read('faction/Renegade.java'),
  advance: read('command/AdvanceOrderGoal.java'),
  scav: read('entity/ScavEntity.java'),
  gunnerVillager: read('entity/GunnerVillagerEntity.java'),
  gunnerPillager: read('entity/GunnerPillagerEntity.java'),
  sniperVillager: read('entity/SniperVillagerEntity.java'),
  sniperPillager: read('entity/SniperPillagerEntity.java'),
};

/**
 * The guard must exist in `body` and every `marker` must come after it. This is the whole point of the
 * section, so it is one helper rather than a hand-written regex per class.
 */
function guardFirst(source, signature, guard, markers, label) {
  const body = methodBody(strip(source), signature);
  if (body === null) {
    check(false, `${label}: method not found`, signature);
    return;
  }
  const guardAt = body.search(guard);
  if (guardAt < 0) {
    check(false, `${label}: dead guard missing`);
    return;
  }
  const early = markers.filter((marker) => {
    const at = body.search(marker);
    return at >= 0 && at < guardAt;
  });
  check(early.length === 0, `${label}: guard first`,
    early.length ? `work before the guard: ${early.join(' | ')}` : 'before every tick-side call');
}

guardFirst(SOURCES.brain, 'public void tick()', MOB,
  [/this\.doors\.tick\(/, /this\.tactics\.tick\(/, /AccuracyProfile\.tickDecay\(/, /transition\(/],
  'GunBrain#tick');
guardFirst(SOURCES.doors, 'public void tick(ServerLevel level)', MOB,
  [/this\.lastTick = now/, /openDoorInTheWay\(/, /closeDoorsLeftOpen\(/], 'DoorBehavior#tick');
guardFirst(SOURCES.doors, 'public static void onLivingTick(', /!event\.getEntity\(\)\.isRemoved\(\)/,
  [/user\.gunBrain\(\)/], 'DoorBehavior#onLivingTick (the Forge LivingTickEvent driver)');
guardFirst(SOURCES.factionAi, 'public static void tick(Mob mob', ARG,
  [/Renegade\.tickDecay\(/, /AlertNetwork\./], 'FactionAi#tick');
guardFirst(SOURCES.advance, 'public boolean canUse()', MOB,
  [/AdvanceOrder\.read\(/, /MarkData\.get\(/], 'AdvanceOrderGoal#canUse');
guardFirst(SOURCES.advance, 'public void tick()', MOB,
  [/AdvanceOrder\.read\(/, /getNavigation\(\)/], 'AdvanceOrderGoal#tick');
guardFirst(SOURCES.renegade, 'public static void tickDecay(', ARG,
  [/Config\.FACTION_RENEGADE_DECAY_TICKS/], 'Renegade#tickDecay');
guardFirst(SOURCES.alert, 'public static SharedContact current(', ARG,
  [/Config\.ALERT_ENABLED/, /Faction\.isArmedMember/], 'AlertNetwork#current');
guardFirst(SOURCES.alert, 'public static void broadcastIfDue(', /if\s*\(\s*source\.isRemoved\(\)/,
  [/Faction\.isArmedMember/], 'AlertNetwork#broadcastIfDue');
for (const [key, label] of [['scav', 'ScavEntity#tick'], ['gunnerVillager', 'GunnerVillagerEntity#tick'],
  ['gunnerPillager', 'GunnerPillagerEntity#tick']]) {
  guardFirst(SOURCES[key], 'public void tick()', OWN,
    [/this\.walk\.tick\(/, /this\.voice\.tick\(/, /FactionAi\.tick\(/], label);
}
for (const [key, label] of [['sniperVillager', 'SniperVillagerEntity#tick'],
  ['sniperPillager', 'SniperPillagerEntity#tick']]) {
  guardFirst(SOURCES[key], 'public void tick()', OWN, [/this\.sniper\.serverTick\(/], label);
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. the cadences exist and are honoured');

const brain = SOURCES.brain;
const factionAi = SOURCES.factionAi;
const CADENCES = [
  { name: 'SIGHT_CACHE_TICKS', where: brain, value: 2, who: 'GunBrain' },
  { name: 'ACCURACY_DECAY_CHECK_TICKS', where: brain, value: 20, who: 'GunBrain' },
  { name: 'FOCUS_FIRE_RETRY_TICKS', where: brain, value: 5, who: 'GunBrain' },
  { name: 'PATH_RETRY_COOLDOWN_TICKS', where: brain, value: 10, who: 'GunBrain' },
  { name: 'TICK_INTERVAL_TICKS', where: factionAi, value: 4, who: 'FactionAi' },
];
for (const cadence of CADENCES) {
  const declared = new RegExp(`${cadence.name}\\s*=\\s*${cadence.value}\\b`).test(cadence.where);
  const uses = (cadence.where.match(new RegExp(cadence.name, 'g')) || []).length;
  check(declared && uses >= 2, `${cadence.who}.${cadence.name} is ${cadence.value} and is actually read`,
    `${uses} occurrence(s) in the file`);
}

// One line-of-sight verdict per mob: the brain must ask the raw question in exactly two places - once
// inside seesTarget (the cache) and once in focus fire, whose expression tools/selftest_ai_profiles.js
// pins verbatim - and never ask it directly by target again.
const rawSites = (brain.match(/\.hasLineOfSight\(/g) || []).length;
const targetSites = (brain.match(/\.hasLineOfSight\(target\)/g) || []).length;
const seesTargetBody = methodBody(strip(brain), 'private boolean seesTarget(') || '';
check(rawSites === 2 && targetSites === 1 && /this\.mob\.hasLineOfSight\(target\)/.test(seesTargetBody),
  'GunBrain asks the raw line-of-sight question in exactly two places (inside seesTarget + focus fire)',
  `${rawSites} raw site(s), ${targetSites} by target, the by-target one inside seesTarget`);
const seesTargetCalls = (brain.match(/seesTarget\(/g) || []).length;
check(seesTargetCalls >= 8, 'every decision that used to ask by target now goes through seesTarget',
  `${seesTargetCalls} call sites`);
check(/private static final int SIGHT_CACHE_TICKS/.test(strip(brain))
  && /mySampleTick|Math\.floorMod\(now \+ this\.mob\.getId\(\), SIGHT_CACHE_TICKS\)/.test(strip(brain)),
  'and the sample tick is staggered by entity id (a crowd does not cast on one tick)');
check(/this\.tactics\.tick\(target, seesTarget\(target\)\)/.test(brain)
  && /public void tick\(@Nullable LivingEntity target, boolean canSeeTarget\)/.test(
    strip(SOURCES.tactics)),
  'CombatTactics takes the verdict instead of casting a second identical ray',
  'the tactics memory and the state machine agree on one answer');
check(/target != null && this\.mob\.hasLineOfSight\(target\)/.test(strip(SOURCES.tactics)),
  'and the one-argument overload still exists for any caller that has no verdict to hand');

const tickBody = methodBody(strip(brain), 'public void tick()') || '';
check(/% ACCURACY_DECAY_CHECK_TICKS == 0L/.test(tickBody)
  && /AccuracyProfile\.tickDecay\(this\.mob, level\.getGameTime\(\)\)/.test(tickBody),
  'the accuracy decay clock is sampled on its cadence, not every tick');
check(/this\.pathRetryCooldown > 0/.test(tickBody) && /this\.pathRetryCooldown--/.test(tickBody),
  'and the path retry cooldown actually counts down once per tick');
const advanceBody = methodBody(strip(brain), 'private void tickAdvance(') || '';
check(/getNavigation\(\)\.isDone\(\)\) && this\.pathRetryCooldown <= 0/.test(advanceBody)
  && /this\.pathRetryCooldown = PATH_RETRY_COOLDOWN_TICKS/.test(advanceBody),
  'the re-path is gated by the cooldown (a destination the mob cannot reach makes isDone() true at once)',
  'this is the "pathfinding storm in dense geometry" guard');
const factionBody = methodBody(strip(factionAi), 'public static void tick(Mob mob') || '';
check(/% TICK_INTERVAL_TICKS != 0/.test(factionBody),
  'the faction layer runs on its interval, so the per-tick NBT read and the converger query are not per tick');
check(/this\.focusFireRetryTicks = FOCUS_FIRE_RETRY_TICKS/.test(brain)
  && /focusFireRetryTicks > 0/.test(brain),
  'a squad focus target this mob cannot see is retried on a cooldown, not every tick');
check(/mob\.getNavigation\(\)\.moveTo/.test(SOURCES.alert),
  'the alert network still moves a mob only through navigation.moveTo (no new movement path)');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. the arithmetic, simulated (deterministic - the numbers this gate prints are the file\'s)');

/** Java: `(gameTime + entityId) % period == 0` with Java's floor semantics for a non-negative sum. */
const samplesOn = (gameTime, entityId, period) => ((gameTime + entityId) % period === 0);
const countSamples = (entityId, period, ticks) => {
  let n = 0;
  for (let t = 0; t < ticks; t++) if (samplesOn(t, entityId, period)) n++;
  return n;
};
const TICKS = 200;
const SIGHT = 2;
const sightCounts = [];
for (let id = 0; id < 8; id++) sightCounts.push(countSamples(id, SIGHT, TICKS));
check(sightCounts.every((n) => n === TICKS / SIGHT),
  `each mob samples line of sight ${TICKS / SIGHT} times per ${TICKS} ticks (was ${TICKS})`,
  `ids 0..7 -> ${sightCounts.join(',')}`);
// The stagger: how many of eight mobs share a sample tick. Half of them, at worst - not all eight.
let worstTogether = 0;
for (let t = 0; t < SIGHT * 4; t++) {
  const together = [0, 1, 2, 3, 4, 5, 6, 7].filter((id) => samplesOn(t, id, SIGHT)).length;
  worstTogether = Math.max(worstTogether, together);
}
check(worstTogether === 4,
  'eight mobs spread over the two phases (4 cast on any one tick, never 8)',
  `worst tick: ${worstTogether} of 8`);
const decaySamples = countSamples(0, 20, TICKS);
const factionSamples = countSamples(0, 4, TICKS);
console.log(`  cadence          samples per ${TICKS} ticks per mob     before`);
console.log(`  sight (x${SIGHT})        ${String(TICKS / SIGHT).padStart(4)}                          ${TICKS}`);
console.log(`  accuracy (x20)   ${String(decaySamples).padStart(4)}                          ${TICKS}`);
console.log(`  faction (x4)     ${String(factionSamples).padStart(4)}                          ${TICKS}`);
check(TICKS / SIGHT === 100 && decaySamples === 10 && factionSamples === 50,
  'the three cadences are the numbers the report quotes (100 / 10 / 50 per 200 ticks)');

// The path cooldown, as arithmetic: a navigation that reports Done on every tick (the unreachable
// destination case) may still only re-plan once per PATH_RETRY_COOLDOWN_TICKS.
function plansInTicks(ticks, cooldown) {
  let cooldownLeft = 0;
  let plans = 0;
  for (let t = 0; t < ticks; t++) {
    if (cooldownLeft <= 0) {
      plans++;
      cooldownLeft = cooldown;
    }
    if (cooldownLeft > 0) cooldownLeft--;
  }
  return plans;
}
const plansUnthrottled = plansInTicks(TICKS, 1);
const plansThrottled = plansInTicks(TICKS, 10);
check(plansUnthrottled === TICKS && plansThrottled === TICKS / 10,
  `a mob pressed against a wall re-plans ${TICKS / 10} times per ${TICKS} ticks instead of ${TICKS}`,
  `${plansThrottled} vs ${plansUnthrottled}`);
// The focus-fire retry, the same shape.
let focusCasts = 0;
for (let t = 0, left = 0; t < TICKS; t++) {
  if (left <= 0) { focusCasts++; left = 5; }
  left--;
}
check(focusCasts === TICKS / 5,
  `a squad target this mob cannot see costs ${TICKS / 5} ray casts per ${TICKS} ticks, not ${TICKS}`,
  `${focusCasts} vs ${TICKS}`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. the caches are per entity, never static');
for (const field of ['sightValid', 'sightValue', 'sightTargetId', 'focusFireAdoptedId',
  'focusFireRetryTicks', 'pathRetryCooldown']) {
  const declaration = new RegExp(`(^|\\n)\\s*(private|protected|public)?\\s*(static\\s+)?[A-Za-z]+\\s+${field}\\s*(=|;)`);
  const match = declaration.exec(brain);
  check(match !== null && !match[3],
    `GunBrain.${field} is an instance field`,
    match === null ? 'declaration not found' : (match[3] ? 'it is static' : 'per brain, per mob'));
}
check(/public void tick\(@Nullable LivingEntity target, boolean canSeeTarget\)/.test(strip(SOURCES.tactics))
  && /private List<Spot> cached = List\.of\(\);/.test(strip(SOURCES.tactics))
  && /private int cacheTicks;/.test(strip(SOURCES.tactics)),
  'CombatTactics keeps its cover cache on the instance too (one per mob, not one for the server)');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. the recorded measurement (tools/spike/work/aiperf), as evidence rather than as a gate');
const workDir = path.join(ROOT, 'tools', 'spike', 'work', 'aiperf');
if (!fs.existsSync(workDir)) {
  console.log('  (no aiperf run recorded - run tools/spike/work/aiperf/aiperf.ps1 to produce one)');
} else {
  const files = fs.readdirSync(workDir).filter((f) => /^aiperf-.*\.json$/.test(f)).sort();
  for (const file of files) {
    let data;
    try {
      data = JSON.parse(fs.readFileSync(path.join(workDir, file), 'utf8'));
    } catch (error) {
      console.log(`  ${file}: unreadable (${error.message})`);
      continue;
    }
    const rows = data.Counts || [];
    const useful = rows.filter((r) => r.Live > 0 && r.MsptMean !== undefined);
    if (data.Baseline) {
      console.log(`  ${data.Label || file}: baseline ${data.Baseline.Mean} ms, worst ${data.Baseline.Max} ms`);
    }
    for (const row of rows) {
      console.log(`      ${String(row.Live).padStart(3)} mob(s): MSPT ${String(row.MsptMean).padStart(7)} ms`);
    }
    if (useful.length >= 2) {
      const a = useful[0];
      const b = useful[useful.length - 1];
      const slope = (b.MsptMean - a.MsptMean) / (b.Live - a.Live);
      console.log(`      slope between ${a.Live} and ${b.Live} mobs: ${slope.toFixed(3)} ms per mob per tick`);
    }
    for (const run of Array.isArray(data.KillWindow) ? data.KillWindow : []) {
      if (!run || typeof run !== 'object' || !Array.isArray(run.Curve)) continue;
      const curve = run.Curve.map((p) => `+${p.AfterMs}ms=${p.Mspt}`).join(' ');
      console.log(`      kill window run ${run.Run} (${run.Victims} mobs at ${run.AliveMspt} ms): ${curve}`);
    }
  }
}

console.log('');
if (failures > 0) {
  console.log(`${failures} AI cost invariant(s) FAILED`);
  process.exit(1);
}
console.log('AI per-tick cost invariants hold (dead guard first everywhere, cadences live, caches per entity)');
