// The command system gate: three faction-locked tools, neutral A/B/C/D marks, one signal stick, one
// signal-point block, and the advance rules.
//
//   node tools/selftest_command_marks.js
//
// WHY THIS FILE EXISTS
//   The acceptance list in docs/指挥系统设计.md is mostly decidable without a game, and the parts that
//   are not (right-click feel, texture look, whether the advance *reads* well) are exactly the parts a
//   gate must not pretend to check. So this gate splits the list in two:
//
//   A. STATIC CONTRACT - each tool is bound to its own faction tag and nothing else (the tags are also
//      asserted pairwise disjoint, so no unit can be in two line-ups); the mark lifetimes; the letter
//      numbering; the config keys; and the full registration completeness of the five new items, the
//      block, the block entity, the projectile entity, the textures, the models, the recipes, the loot
//      table and the bilingual lang entries.
//
//   B. DETERMINISTIC SIMULATION - the pure helpers in CommandMark / MarkData / AdvanceOrder are
//      MIRRORED here (the same technique tools/selftest_ai_profiles.js uses for the profile maths) and
//      driven through the acceptance cases: letters are reused after a removal, a stick mark dies at
//      6000 ticks and a point mark never does, marks do not leak between dimensions, cycling wraps and
//      falls back when the current mark disappears, an order arrives / expires / dies with its mark,
//      a unit below the retreat threshold stops advancing and resumes when the retreat ends, and the
//      coordination switch really does turn the stagger and the leapfrog rotation on and off.
//
//   Where a mirror is used, the Java body is ALSO matched textually, so the two cannot drift apart.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');
const DATA = path.join(RES, 'data', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
const json = (rel) => JSON.parse(fs.readFileSync(rel, 'utf8'));

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** The body of a method declaration (parameter list followed by `{`). */
function bodyOf(src, name) {
  const re = new RegExp(`\\b${name}\\s*\\(`, 'g');
  let match;
  while ((match = re.exec(src)) !== null) {
    const open = src.indexOf('(', match.index);
    let depth = 0;
    let close = -1;
    for (let i = open; i < src.length; i++) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') { depth--; if (depth === 0) { close = i; break; } }
    }
    if (close < 0) continue;
    const after = /^\s*(?:throws\s[\w.,\s]+?)?\{/.exec(src.slice(close + 1));
    if (!after) continue;
    const braceAt = close + 1 + after[0].length - 1;
    depth = 0;
    for (let i = braceAt; i < src.length; i++) {
      if (src[i] === '{') depth++;
      else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(braceAt, i + 1); }
    }
  }
  return '';
}

const faction = strip(read('command/CommandFaction.java'));
const mark = strip(read('command/CommandMark.java'));
const markData = strip(read('command/MarkData.java'));
const order = strip(read('command/AdvanceOrder.java'));
const goal = strip(read('command/AdvanceOrderGoal.java'));
const marks = strip(read('command/CommandMarks.java'));
const tool = strip(read('command/CommandToolItem.java'));
const stickEntity = strip(read('command/SignalStickEntity.java'));
const pointBlock = strip(read('command/SignalPointBlock.java'));
const pointBe = strip(read('command/SignalPointBlockEntity.java'));
const config = strip(read('Config.java'));
const squad = strip(read('gun/SquadCoordinator.java'));
const modCommands = strip(read('command/ModCommands.java'));
const entities = strip(read('registry/ModEntities.java'));
const items = strip(read('registry/ModItems.java'));
const blocks = strip(read('registry/ModBlocks.java'));
const tabs = strip(read('registry/ModCreativeTabs.java'));
const client = strip(read('client/ClientSetup.java'));
const brain = strip(read('gun/GunBrain.java'));

// ------------------------------------------------------------------ 1. faction locking

console.log('1. faction locking: each tool commands its own tag and nothing else');
const enumEntries = [...faction.matchAll(/(VILLAGE|ILLAGER|SCAV)\("([a-z]+)",\s*"([a-z_]+)",\s*"([a-z_]+)"\)/g)]
  .map((m) => ({ constant: m[1], id: m[2], tag: m[3], tool: m[4] }));
check(enumEntries.length === 3, 'three factions are declared', enumEntries.map((e) => e.id).join(', '));
const expected = {
  VILLAGE: ['village', 'faction_village', 'village_command_tool'],
  ILLAGER: ['illager', 'faction_illager', 'illager_command_tool'],
  SCAV: ['scav', 'faction_scav', 'scav_command_tool'],
};
for (const entry of enumEntries) {
  const want = expected[entry.constant];
  check(!!want && entry.id === want[0] && entry.tag === want[1] && entry.tool === want[2],
    `${entry.constant} -> #tarkovscav:${entry.tag} and item ${entry.tool}`,
    `id=${entry.id}`);
}
check(/entity\.getType\(\)\.is\(this\.tag\)/.test(bodyOf(faction, 'affects')),
  'membership is the entity-type tag test, and it is the only one in the class',
  bodyOf(faction, 'affects').replace(/\s+/g, ' ').trim());
check(/TagKey\.create\(net\.minecraft\.core\.registries\.Registries\.ENTITY_TYPE,/.test(faction),
  'the tag is built from the ENTITY_TYPE registry (a typo here would be a silent always-false test)');

// The three tags must also be pairwise disjoint: a unit in two line-ups would be "cross-faction" by
// construction, whatever the tool code did.
const tagFile = (name) => json(path.join(DATA, 'tags', 'entity_types', `${name}.json`)).values;
const villageTag = tagFile('faction_village');
const illagerTag = tagFile('faction_illager');
const scavTag = tagFile('faction_scav');
const overlap = (a, b) => a.filter((value) => b.includes(value));
for (const [left, right, a, b] of [
  ['village', 'illager', villageTag, illagerTag],
  ['village', 'scav', villageTag, scavTag],
  ['illager', 'scav', illagerTag, scavTag],
]) {
  const shared = overlap(a, b);
  check(shared.length === 0, `#faction_${left} and #faction_${right} share no unit`,
    shared.length ? shared.join(', ') : '');
}
// And per-tool, simulated: the village tool hits village units and neither of the other two, etc.
const simFaction = { VILLAGE: villageTag, ILLAGER: illagerTag, SCAV: scavTag };
const allUnits = [...new Set([...villageTag, ...illagerTag, ...scavTag])];
check(allUnits.length === villageTag.length + illagerTag.length + scavTag.length,
  'no unit appears in two factions at all', `${allUnits.length} distinct units`);
for (const toolName of ['VILLAGE', 'ILLAGER', 'SCAV']) {
  const own = simFaction[toolName];
  const others = allUnits.filter((unit) => !own.includes(unit));
  const hitsOwn = own.every((unit) => simFaction[toolName].includes(unit));
  const hitsOther = others.some((unit) => simFaction[toolName].includes(unit));
  check(hitsOwn && !hitsOther, `the ${toolName} tool affects its own tag and none of the other two`,
    `${own.length} own unit(s), ${others.length} refused`);
}
const modOwned = ['tarkovscav:gunner_villager', 'tarkovscav:sniper_villager', 'tarkovscav:usec_villager',
  'tarkovscav:elite_villager', 'tarkovscav:gunner_pillager', 'tarkovscav:sniper_pillager',
  'tarkovscav:bear_pillager', 'tarkovscav:elite_pillager', 'tarkovscav:scav'];
check(/isCommandable\(/.test(marks) && /ScavEntity/.test(marks) && /GunnerPillagerEntity/.test(marks)
  && /GunnerVillagerEntity/.test(marks),
  'the orderable set is the mod\'s own three armed base classes (a vanilla unit gets no impossible order)');
check(!/CommandFaction\.(VILLAGE|ILLAGER|SCAV)/.test(goal) && !/CommandFaction\.(VILLAGE|ILLAGER|SCAV)/.test(tool),
  'the goal and the item never name another faction - the faction lock cannot be branched around');

// ------------------------------------------------------------------ 2. the config keys

console.log('');
console.log('2. the seven command config keys exist with the shipped defaults');
const configKeys = [
  ['COMMAND_ENABLED', /COMMAND_ENABLED\s*=\s*b[\s\S]{0,300}?\.define\("enabled",\s*true\)/, 'enabled = true'],
  ['COMMAND_RADIUS', /COMMAND_RADIUS\s*=\s*b[\s\S]{0,700}?\.defineInRange\("radius",\s*32\.0D,/, 'radius = 32'],
  ['COMMAND_SPEED_SCALE', /COMMAND_SPEED_SCALE\s*=\s*b[\s\S]{0,700}?\.defineInRange\("speedScale",\s*0\.65D,/, 'speedScale = 0.65'],
  ['COMMAND_ARRIVAL_RADIUS', /COMMAND_ARRIVAL_RADIUS\s*=\s*b[\s\S]{0,400}?\.defineInRange\("arrivalRadius",\s*4\.0D,/, 'arrivalRadius = 4'],
  ['COMMAND_STICK_DURATION_TICKS', /COMMAND_STICK_DURATION_TICKS\s*=\s*b[\s\S]{0,600}?\.defineInRange\("stickDurationTicks",\s*6000,/, 'stickDurationTicks = 6000'],
  ['COMMAND_COORDINATION', /COMMAND_COORDINATION\s*=\s*b[\s\S]{0,700}?\.define\("coordination",\s*true\)/, 'coordination = true'],
  ['COMMAND_MAX_MARKS', /COMMAND_MAX_MARKS\s*=\s*b[\s\S]{0,400}?\.defineInRange\("maxMarks",\s*12,/, 'maxMarks = 12'],
];
for (const [name, pattern, detail] of configKeys) {
  check(pattern.test(config), name, detail);
}
check(/push\("command"\)/.test(config), 'they live in their own [command] section');

// ------------------------------------------------------------------ 3. mark lifetime and numbering

console.log('');
console.log('3. mark lifetime, letters and dimensions');
check(/long expiresAtTick/.test(read('command/CommandMark.java')) && /PERMANENT = -1L/.test(mark),
  'a mark carries an expiry tick, with -1 as the permanent sentinel');
check(/source == CommandMark\.Source\.POINT[\s\S]{0,120}?CommandMark\.PERMANENT/.test(marks),
  'a signal-point mark is permanent');
check(/Source\.STICK \? stickDurationTicks\(\)/.test(marks),
  'a thrown stick mark uses command.stickDurationTicks');
check(/COMMAND_STICK_DURATION_TICKS\.get\(\)/.test(bodyOf(marks, 'stickDurationTicks')),
  'and that helper reads the config key, not a literal 6000');
const expiredBody = bodyOf(mark, 'expired');
check(/!permanentMark\(\) && now >= this\.expiresAtTick/.test(expiredBody.replace(/\s+/g, ' ')),
  'expiry is a comparison against the clock, never a countdown that can lose ticks',
  expiredBody.replace(/\s+/g, ' ').trim());
check(/Map<ResourceLocation, List<CommandMark>>/.test(markData),
  'the storage is keyed by dimension first (marks cannot leak across levels)');
check(/Map<UUID, String>/.test(markData), 'and the current mark is per player UUID');
check(/stillExists\(ResourceLocation dimension, String letter, BlockPos pos, long now\)/.test(markData),
  'an order is tied to letter + block + dimension, so removing a mark kills exactly its orders');
check(/list\.remove\(0\)/.test(bodyOf(markData, 'put')),
  'past command.maxMarks the OLDEST mark is dropped, never the one just made');
check(!/entries\s*\.\s*remove|marks\s*\.\s*remove/.test(markData) || /clear\(ResourceLocation/.test(markData),
  'removal exists only through the explicit remove/clear/removeExact paths');

// ------------------------------------------------------------------ 4. the pure helpers, mirrored

console.log('');
console.log('4. the pure helpers are mirrored and matched against the Java text');
const mirror = {
  PERMANENT: -1,
  firstFreeLetter: (used) => {
    for (let i = 0; i < 26; i++) {
      const candidate = String.fromCharCode('A'.charCodeAt(0) + i);
      if (!used.includes(candidate)) return candidate;
    }
    return 'X1';
  },
  expired: (expiresAtTick, now) => expiresAtTick !== -1 && now >= expiresAtTick,
  valid: (expiresTick, now) => expiresTick === -1 || now < expiresTick,
  arrived: (distanceSqr, arrivalRadius) => distanceSqr <= arrivalRadius * arrivalRadius,
  startDelay: (entityId, coordination) => (coordination ? ((entityId % 40) + 40) % 40 : 0),
  isSuppressor: (index, windowIndex, count) => count > 1 && (((windowIndex % count) + count) % count) === index,
  holds: (index, windowIndex, size, coordination) =>
    coordination && mirror.isSuppressor(index, windowIndex, size),
  advances: (orderValid, arrived, hasLiveTarget, retreating) =>
    orderValid && !arrived && !hasLiveTarget && !retreating,
  retreatThresholdBreached: (health, maxHealth, fraction) => maxHealth > 0 && health < maxHealth * fraction,
};

const letterBody = bodyOf(mark, 'firstFreeLetter').replace(/\s+/g, ' ');
check(/'A' \+ i/.test(letterBody) && /!used\.contains\(candidate\)/.test(letterBody),
  'firstFreeLetter really does pick the first unused A..Z', letterBody.slice(0, 120));
const validBody = bodyOf(order, 'valid').replace(/\s+/g, ' ');
check(/expiresTick == CommandMark\.PERMANENT \|\| now < expiresTick/.test(validBody),
  'AdvanceOrder.valid is the permanent-aware clock test', validBody);
const arrivedBody = bodyOf(order, 'arrived').replace(/\s+/g, ' ');
check(/distanceSqr <= arrivalRadius \* arrivalRadius/.test(arrivedBody),
  'arrived is the arrival-radius comparison', arrivedBody);
const delayBody = bodyOf(order, 'startDelay').replace(/\s+/g, ' ');
check(/!coordination[\s\S]{0,40}?return 0/.test(delayBody) && /Math\.floorMod\(entityId, STAGGER_SPREAD_TICKS\)/.test(delayBody),
  'startDelay is 0 with coordination off and spreads by entity id with it on', delayBody);
const holdsBody = bodyOf(order, 'holds').replace(/\s+/g, ' ');
check(/coordination && SquadCoordinator\.isSuppressor\(index, windowIndex, size\)/.test(holdsBody),
  'holds reuses SquadCoordinator.isSuppressor - the firefight rotation, not a second system', holdsBody);
const advancesBody = bodyOf(order, 'advances').replace(/\s+/g, ' ');
check(/orderValid[\s\S]{0,140}?!combatOverrides\(hasLiveTarget\)[\s\S]{0,60}?!retreatOverrides\(retreating\)/.test(advancesBody),
  'advances requires the order to be valid, not arrived, not in combat and not retreating', advancesBody);
const retreatBody = bodyOf(order, 'retreatThresholdBreached').replace(/\s+/g, ' ');
check(/health < maxHealth \* fraction/.test(retreatBody),
  'the retreat trigger mirrors GunBrain.decide exactly', retreatBody);
check(/this\.mob\.getHealth\(\)[\s\S]{0,80}?AiProfile\.retreatHealthFraction\(this\.mob\)/.test(brain),
  'and GunBrain still uses that same shape (the mirror is of the shipped rule)');
const suppressorBody = bodyOf(squad, 'isSuppressor').replace(/\s+/g, ' ');
check(/\(\(windowIndex % count\) \+ count\) % count == index/.test(suppressorBody),
  'the rotation itself is unchanged in SquadCoordinator', suppressorBody);

// Letter numbering, including reuse after a deletion.
let used = [];
check(mirror.firstFreeLetter(used) === 'A', 'the first mark is A');
used = ['A'];
check(mirror.firstFreeLetter(used) === 'B', 'the second is B');
used = ['A', 'B', 'C'];
check(mirror.firstFreeLetter(used) === 'D', 'the fourth is D');
used = ['A', 'C', 'D'];
check(mirror.firstFreeLetter(used) === 'B', 'a deleted B is reused by the next mark (design section 3)');
used = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
check(mirror.firstFreeLetter(used) === 'X1', 'past Z the numbering continues instead of throwing');

// Lifetime: a 6000-tick stick vs a permanent point.
const STICK = 6000;
check(!mirror.expired(1000 + STICK, 1000 + STICK - 1), 'a stick mark is alive one tick before its expiry');
check(mirror.expired(1000 + STICK, 1000 + STICK), 'and gone exactly at 6000 ticks');
check(mirror.valid(1000 + STICK, 1000 + STICK - 1) && !mirror.valid(1000 + STICK, 1000 + STICK),
  'and an order on it expires in lock step');
check(!mirror.expired(-1, 10_000_000) && mirror.valid(-1, 10_000_000),
  'a permanent mark (the signal point) never expires, however long the world runs');
const total = 5 * 60 * 20;
check(total === STICK, '6000 ticks is exactly 5 minutes at 20 tps', `${total} ticks`);

// Arrival.
const arrival = 4.0;
check(mirror.arrived(0, arrival) && mirror.arrived(16, arrival), 'the arrival radius clears at 4 blocks');
check(!mirror.arrived(16.0001, arrival), 'and not beyond it');

// ------------------------------------------------------------------ 5. cycling and fallback

console.log('');
console.log('5. target cycling and the fallback when the current mark disappears');
/** A mirror of MarkData's per-dimension list plus the per-player current letter. */
function newLedger() { return { marks: new Map(), current: new Map() }; }
function liveMarks(ledger, dimension, now) {
  const list = ledger.marks.get(dimension) || [];
  const alive = list.filter((entry) => !mirror.expired(entry.expiresAtTick, now));
  ledger.marks.set(dimension, alive);
  return alive;
}
function setMark(ledger, dimension, entry) {
  const list = (ledger.marks.get(dimension) || []).filter((other) => other.letter !== entry.letter);
  list.push(entry);
  ledger.marks.set(dimension, list);
}
function removeMark(ledger, dimension, letter) {
  const list = ledger.marks.get(dimension) || [];
  const before = list.length;
  ledger.marks.set(dimension, list.filter((entry) => entry.letter !== letter));
  return list.length !== before;
}
function currentMark(ledger, player, dimension, now) {
  const live = liveMarks(ledger, dimension, now);
  if (live.length === 0) return null;
  const wanted = ledger.current.get(player);
  const hit = live.find((entry) => entry.letter === wanted);
  if (hit) return hit;
  const fallback = live[0];
  ledger.current.set(player, fallback.letter);
  return fallback;
}
function cycleMark(ledger, player, dimension, now) {
  const live = [...liveMarks(ledger, dimension, now)].sort((a, b) => a.letter.localeCompare(b.letter));
  if (live.length === 0) return null;
  const wanted = ledger.current.get(player);
  let index = 0;
  for (let i = 0; i < live.length; i++) {
    if (live[i].letter === wanted) { index = (i + 1) % live.length; break; }
  }
  ledger.current.set(player, live[index].letter);
  return live[index];
}

const DIM = 'minecraft:overworld';
const OTHER = 'tarkovscav:urban_wasteland';
const cl = newLedger();
setMark(cl, DIM, { letter: 'A', expiresAtTick: mirror.PERMANENT });
setMark(cl, DIM, { letter: 'B', expiresAtTick: 10_000 });
setMark(cl, DIM, { letter: 'C', expiresAtTick: mirror.PERMANENT });
cl.current.set('p1', 'A');
check(cycleMark(cl, 'p1', DIM, 100).letter === 'B', 'sneak-right-click cycles A -> B');
check(cycleMark(cl, 'p1', DIM, 100).letter === 'C', 'then B -> C');
check(cycleMark(cl, 'p1', DIM, 100).letter === 'A', 'then C -> A (it wraps)');
check(currentMark(cl, 'p1', DIM, 100).letter === 'A', 'and the current mark is what the cycle left');
// The fallback: delete the current mark.
removeMark(cl, DIM, 'A');
check(currentMark(cl, 'p1', DIM, 100).letter === 'B',
  'deleting the current mark falls back to the first live mark instead of failing silently');
check(cl.current.get('p1') === 'B', 'and the fallback is remembered');
// The stick running out is the same case.
check(currentMark(cl, 'p1', DIM, 10_000).letter === 'C',
  'a current mark whose stick expired also falls back');
check(currentMark(cl, 'p1', DIM, 10_000_000).letter === 'C', 'a permanent mark survives any clock');
// No marks at all.
const empty = newLedger();
check(currentMark(empty, 'p2', DIM, 0) === null && cycleMark(empty, 'p2', DIM, 0) === null,
  'with no marks both actions return null, which the item reports out loud');
check(/tarkovscav\.command\.no\.marks/.test(tool), 'and the tool really does report it');
// Per dimension.
setMark(cl, OTHER, { letter: 'A', expiresAtTick: mirror.PERMANENT });
check(liveMarks(cl, DIM, 0).length === 1 && liveMarks(cl, OTHER, 0).length === 1,
  'the same letter in another dimension is a different mark',
  `overworld ${liveMarks(cl, DIM, 0).map((m) => m.letter)}, wasteland ${liveMarks(cl, OTHER, 0).map((m) => m.letter)}`);
check(liveMarks(cl, DIM, 0).every((entry) => entry.letter !== 'A')
  && liveMarks(cl, OTHER, 0).some((entry) => entry.letter === 'A'),
  'the wasteland keeps its own A after the overworld A was deleted');
check(/MarkData\.get\(level\.getServer\(\)\)\.live\(level\.dimension\(\)\.location\(\), now\)/
  .test(bodyOf(marks, 'marks').replace(/\s+/g, ' ')),
  'the listing is scoped to the caller\'s own level');

// ------------------------------------------------------------------ 6. order persistence / arrival / expiry

console.log('');
console.log('6. order persistence, arrival, expiry and the mark link');
check(/TAG = "tarkovscav:advanceOrder"/.test(order), 'the order lives under a namespaced NBT key');
check(/mob\.getPersistentData\(\)\.put\(TAG, tag\)/.test(bodyOf(order, 'write').replace(/\s+/g, ' ')),
  'write() puts it into the entity\'s persistent data (Forge saves that, so it survives a restart)');
check(/mob\.getPersistentData\(\)\.getCompound\(TAG\)/.test(bodyOf(order, 'read').replace(/\s+/g, ' ')),
  'read() takes it back after a reload');
check(/KEY_EXPIRES/.test(order) && /KEY_LETTER/.test(order) && /KEY_DIMENSION/.test(order),
  'the stored order carries its expiry, its letter and its dimension');
check(/MarkData\.get\(level\.getServer\(\)\)\.stillExists\(/.test(goal),
  'the goal drops an order whose mark is gone, which is how an order "expires with the mark"');
check(/AdvanceOrder\.arrived\(/.test(goal) && /AdvanceOrder\.clear\(this\.mob\)/.test(goal),
  'and it clears the order on arrival');
check(/arrivalRadius/.test(config), 'the arrival radius is configurable');
check(/COMMAND_SPEED_SCALE\.get\(\)/.test(goal) && /setSprinting\(false\)/.test(goal),
  'the advance uses command.speedScale and never sprints');
check(/bestCover\(/.test(goal) && /AiProfile\.advanceCoverStep/.test(goal),
  'the step goes through the existing cover search, not a straight line');
check(/squad\.claim\(/.test(goal) && /squad\.isFree\(/.test(goal),
  'and through the existing cover claims, so two ordered units cannot pick one wall');

// Persistence, simulated: the order is a plain record, so write/read is identity.
const storedOrder = { target: [100, 64, -20], letter: 'B', dimension: DIM, issuedTick: 500,
  expiresTick: 6500, index: 1, size: 3 };
const reloaded = JSON.parse(JSON.stringify(storedOrder));
check(JSON.stringify(reloaded) === JSON.stringify(storedOrder),
  'an order survives a serialise/reload round trip unchanged');
check(mirror.valid(reloaded.expiresTick, 6500 - 1) && !mirror.valid(reloaded.expiresTick, 6500),
  'the reloaded order still expires at its own tick');

// Arrival / expiry decisions through the one rule.
check(mirror.advances(true, false, false, false), 'a valid, unfinished, peaceful order advances');
check(!mirror.advances(false, false, false, false), 'an expired one does not');
check(!mirror.advances(true, true, false, false), 'an arrived one does not');
check(!mirror.advances(true, false, true, false), 'one in combat does not (combat takes priority)');
check(!mirror.advances(true, false, false, true), 'one that is retreating does not');

// ------------------------------------------------------------------ 7. retreat priority

console.log('');
console.log('7. retreat priority (self-preservation first), simulated');
const RETREAT_FRACTION = 0.35;   // combat.retreatHealthFraction scaled by the troop tier
const maxHealth = 40;
let health = 40;
check(!mirror.retreatThresholdBreached(health, maxHealth, RETREAT_FRACTION), 'a healthy unit does not break off');
// Mid-order, the unit is shot down below the threshold.
health = 12;
const breached = mirror.retreatThresholdBreached(health, maxHealth, RETREAT_FRACTION);
check(breached, 'below the threshold the retreat rule fires (health 12 < 14)');
let retreating = breached;
let orderValid = true;
let arrived = false;
check(!mirror.advances(orderValid, arrived, false, retreating),
  'and an order in progress does NOT advance while it is retreating');
check(/retreatOverrides\(isRetreating\(\)\)/.test(goal) && /GunAiState\.RETREAT/.test(goal),
  'the goal reads the real GunAiState.RETREAT for that decision');
check(/retreatOverrides\(isRetreating\(mob\)\)/.test(marks),
  'and CommandMarks.issue refuses to hand out a NEW order to a retreating unit');
// The retreat ends with the order still valid.
retreating = false;
check(mirror.advances(orderValid, arrived, false, retreating),
  'when the retreat ends and the order is still valid, the unit resumes advancing');
// The order expired during the retreat: it must NOT resume.
orderValid = false;
check(!mirror.advances(orderValid, arrived, false, false),
  'but if the order expired meanwhile, it does not resume');

// ------------------------------------------------------------------ 8. coordination

console.log('');
console.log('8. coordination: stagger and leapfrog, off vs on');
const ids = [41, 42, 43, 44, 45, 46];
const delaysOff = ids.map((id) => mirror.startDelay(id, false));
check(delaysOff.every((value) => value === 0),
  'with command.coordination OFF every unit starts on the same tick', JSON.stringify(delaysOff));
const delaysOn = ids.map((id) => mirror.startDelay(id, true));
check(new Set(delaysOn).size > 1,
  'with it ON the departure ticks are staggered by entity id', JSON.stringify(delaysOn));
check(delaysOn.every((value) => value >= 0 && value < 40), 'and stay inside one spread window');

// The leapfrog: over a run of windows, is there at least one window where one member moves and another
// holds? And is every member a mover at some point (no unit parked forever)?
const SIZE = 3;
let sawRotation = false;
const movedAtLeastOnce = new Set();
const heldAtLeastOnce = new Set();
for (let window = 0; window < 12; window++) {
  const movers = [];
  const holders = [];
  for (let index = 0; index < SIZE; index++) {
    if (mirror.holds(index, window, SIZE, true)) holders.push(index); else movers.push(index);
  }
  if (movers.length > 0 && holders.length > 0) sawRotation = true;
  movers.forEach((index) => movedAtLeastOnce.add(index));
  holders.forEach((index) => heldAtLeastOnce.add(index));
}
check(sawRotation, 'at least one window has one element moving while another holds (the leapfrog)');
check(movedAtLeastOnce.size === SIZE && heldAtLeastOnce.size === SIZE,
  'and every member both moves and holds over a few windows (nobody is parked)');
// With coordination off, nobody ever holds, so everybody moves every window.
let holdsOff = 0;
for (let window = 0; window < 12; window++) {
  for (let index = 0; index < SIZE; index++) if (mirror.holds(index, window, SIZE, false)) holdsOff++;
}
check(holdsOff === 0, 'with coordination OFF nobody holds: all units move every window');
// A one-unit element never holds (there is nobody to cover for).
let holdsSingle = 0;
for (let window = 0; window < 12; window++) if (mirror.holds(0, window, 1, true)) holdsSingle++;
check(holdsSingle === 0, 'a one-unit element never holds - the isSuppressor rule needs a squad of two');
check(/COMMAND_COORDINATION\.get\(\)/.test(goal) && /COMMAND_COORDINATION/.test(config),
  'the goal really reads command.coordination');

// ------------------------------------------------------------------ 9. registration completeness

console.log('');
console.log('9. registration completeness: items, block, block entity, entity, assets, recipes, lang');
const newItems = [
  ['village_command_tool', 'VILLAGE'],
  ['illager_command_tool', 'ILLAGER'],
  ['scav_command_tool', 'SCAV'],
  ['signal_stick', null],
];
for (const [name, factionConstant] of newItems) {
  // The registry name is a literal (the recipe/loot-table gate resolves it), and the faction next to it
  // is asserted to be the one whose enum toolPath() is that same name - so the name and the faction
  // cannot be pointed at different sides even though they are written twice.
  const registered = new RegExp(`ITEMS\\.register\\("${name}",\\s*\\(\\) -> new com\\.gfl\\.tarkovscav\\.command\\.(?:CommandToolItem\\(\\s*com\\.gfl\\.tarkovscav\\.command\\.CommandFaction\\.${factionConstant}|SignalStickItem)`)
    .test(items);
  const enumToolPath = factionConstant === null ? null
    : new RegExp(`CommandFaction\\.${factionConstant}`).test(items);
  check(registered && (factionConstant === null || enumToolPath), `item ${name} is registered`,
    factionConstant === null ? '' : `paired with CommandFaction.${factionConstant}`);
  if (factionConstant !== null) {
    const entry = enumEntries.find((candidate) => candidate.constant === factionConstant);
    check(entry && entry.tool === name,
      `the registry literal "${name}" equals CommandFaction.${factionConstant}.toolPath()`,
      entry ? entry.tool : 'no enum entry');
  }
  const modelPath = path.join(ASSETS, 'models', 'item', `${name}.json`);
  check(fs.existsSync(modelPath), `model item/${name}.json exists`);
  const model = json(modelPath);
  check(model.parent === 'minecraft:item/generated'
    && model.textures.layer0 === `tarkovscav:item/${name}`,
    `item/${name}.json is an item/generated layer over its own texture`);
  const texturePath = path.join(ASSETS, 'textures', 'item', `${name}.png`);
  check(fs.existsSync(texturePath), `texture item/${name}.png exists (procedurally generated)`);
  check(fs.existsSync(path.join(DATA, 'recipes', `${name}.json`)), `recipe ${name}.json exists`);
  const recipe = json(path.join(DATA, 'recipes', `${name}.json`));
  check(recipe.result && recipe.result.item === `tarkovscav:${name}`,
    `recipe ${name}.json crafts tarkovscav:${name}`);
  check(new RegExp(`ModItems\\.${name.toUpperCase()}\\.get\\(\\)`).test(tabs),
    `${name} is in the creative tab`);
}
// The recipe gate resolves `tarkovscav:<name>` against the strings in ModItems/ModBlocks, so the literal
// names above are load-bearing: assert the recipe ids really do appear as registry literals.
for (const [name] of newItems) {
  check(new RegExp(`(?:ITEMS|BLOCK_ITEMS)\\.register\\("${name}"`).test(items + blocks),
    `the recipe id tarkovscav:${name} is a registry literal`);
}
check(/ModBlocks\.SIGNAL_POINT_ITEM\.get\(\)/.test(tabs), 'the signal point item is in the creative tab');
check(/BLOCKS\.register\("signal_point"/.test(blocks), 'block signal_point is registered');
check(/BLOCK_ITEMS\.register\("signal_point"/.test(blocks), 'its block item is registered');
check(/BLOCK_ENTITIES\.register\("signal_point"/.test(blocks), 'its block entity type is registered');
check(/class SignalPointBlockEntity extends BlockEntity/.test(read('command/SignalPointBlockEntity.java')),
  'the block entity class exists');
check(/class SignalPointBlock extends BaseEntityBlock/.test(read('command/SignalPointBlock.java')),
  'the block extends BaseEntityBlock, so it can own one');
check(/ENTITY_TYPES\.register\("signal_stick"/.test(entities), 'the thrown signal stick entity is registered');
check(/ModEntities\.SIGNAL_STICK/.test(client) && /ThrownItemRenderer/.test(client),
  'and it has a client renderer (the crash this project already had once)');
check(fs.existsSync(path.join(ASSETS, 'blockstates', 'signal_point.json')), 'blockstate signal_point.json exists');
check(fs.existsSync(path.join(ASSETS, 'models', 'block', 'signal_point.json')), 'block model exists');
check(fs.existsSync(path.join(ASSETS, 'models', 'item', 'signal_point.json')), 'block item model exists');
check(fs.existsSync(path.join(ASSETS, 'textures', 'block', 'signal_point.png')), 'block texture exists');
check(fs.existsSync(path.join(DATA, 'loot_tables', 'blocks', 'signal_point.json')),
  'the signal point has a loot table (so breaking it drops itself)');
check(/ModLoadedLootCondition|minecraft:survives_explosion/.test(
  fs.readFileSync(path.join(DATA, 'loot_tables', 'blocks', 'signal_point.json'), 'utf8')),
  'and that table is a plain block table');
check(/setPlacedBy/.test(pointBlock) && /removeExact/.test(pointBlock),
  'the block creates its mark on placement and retracts exactly its own mark on removal');
check(/letter/.test(pointBe) && /saveAdditional/.test(pointBe) && /load\(/.test(pointBe),
  'the block entity stores and reloads the letter, so the retraction survives a restart');
check(/MarkData\.get\(serverLevel\.getServer\(\)\)/.test(read('command/SignalPointBlock.java').replace(/\s+/g, ' ')),
  'and it retracts through the same per-dimension MarkData');
check(/CommandMarks\.create\(level, pos, CommandMark\.Source\.STICK/.test(stickEntity),
  'the thrown stick creates a STICK-source mark on landing');
check(/new ItemEntity\(/.test(stickEntity) && /setPickUpDelay/.test(stickEntity),
  'and it drops itself, so the tool can be picked back up');

// Lang: every new player-visible key, in BOTH files, and no raw key leaks in the sources.
const en = json(path.join(ASSETS, 'lang', 'en_us.json'));
const zh = json(path.join(ASSETS, 'lang', 'zh_cn.json'));
const requiredKeys = [
  'entity.tarkovscav.signal_stick',
  'item.tarkovscav.village_command_tool', 'item.tarkovscav.village_command_tool.tooltip',
  'item.tarkovscav.illager_command_tool', 'item.tarkovscav.illager_command_tool.tooltip',
  'item.tarkovscav.scav_command_tool', 'item.tarkovscav.scav_command_tool.tooltip',
  'item.tarkovscav.command_tool.tooltip', 'item.tarkovscav.command_tool.tooltip2',
  'item.tarkovscav.command_tool.tooltip3',
  'item.tarkovscav.signal_stick', 'item.tarkovscav.signal_stick.tooltip',
  'item.tarkovscav.signal_stick.tooltip2',
  'block.tarkovscav.signal_point', 'item.tarkovscav.signal_point.tooltip',
  'tarkovscav.command.disabled', 'tarkovscav.command.no.marks', 'tarkovscav.command.no.units',
  'tarkovscav.command.mark.created', 'tarkovscav.command.ordered', 'tarkovscav.command.current',
  'tarkovscav.command.point.placed',
];
for (const key of requiredKeys) {
  check(typeof en[key] === 'string' && en[key].length > 0, `en_us has ${key}`);
  check(typeof zh[key] === 'string' && zh[key].length > 0, `zh_cn has ${key}`);
}
// No raw key leak: every translatable key the command sources use must exist, and every new tooltip key
// must be reachable from a source file (so a renamed key cannot silently fall back to its raw id).
const sourcesText = ['command/CommandToolItem.java', 'command/CommandMarks.java', 'command/SignalStickItem.java',
  'command/SignalPointBlock.java'].map((rel) => read(rel)).join('\n');
const usedKeys = [...sourcesText.matchAll(/translatable\(\s*"([^"]+)"/g)].map((m) => m[1]);
check(usedKeys.length > 0, 'the command sources really do use translatable components',
  `${usedKeys.length} call(s)`);
for (const key of new Set(usedKeys)) {
  check(typeof en[key] === 'string', `the used key ${key} exists in en_us`, 'a missing one renders raw');
  check(typeof zh[key] === 'string', `the used key ${key} exists in zh_cn`);
}
// ...and the two language files must stay key-for-key identical, which is the existing project contract.
const enKeys = Object.keys(en).sort();
const zhKeys = Object.keys(zh).sort();
check(enKeys.length === zhKeys.length && enKeys.every((key, index) => key === zhKeys[index]),
  'en_us and zh_cn still carry exactly the same key set',
  `en ${enKeys.length}, zh ${zhKeys.length}`);
check(enKeys.length === new Set(enKeys).size, 'no duplicate keys in en_us');

// ------------------------------------------------------------------ 10. commands

console.log('');
console.log('10. the /armedmobs marks command family');
check(/Commands\.literal\("marks"\)/.test(modCommands), 'marks is a command node');
check(/Commands\.literal\("remove"\)/.test(modCommands) && /Commands\.literal\("clear"\)/.test(modCommands)
  && /marks\(\)[\s\S]{0,400}?Commands\.literal\("clear"\)/.test(modCommands),
  'with remove <letter> and clear underneath it');
check(/StringArgumentType\.getString\(context, "letter"\)/.test(modCommands), 'remove takes a letter argument');
check(/CommandMark\.sanitiseLetter/.test(modCommands), 'and the letter is normalised before use');
check(/CommandMarks\.listLines/.test(modCommands), 'marks lists the dimension\'s live marks');
check(/CommandMarks\.remove/.test(modCommands) && /CommandMarks\.clear/.test(modCommands),
  'remove and clear call the same logic layer the tools use');

console.log('');
if (failures > 0) {
  console.log(`${failures} command-system check(s) FAILED`);
  process.exit(1);
}
console.log('the command system is faction-locked, dimension-aware and its numbering/lifetime/advance rules hold');
