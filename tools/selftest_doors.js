// Doors (README 5a): every armed unit opens and shuts wooden doors, and never touches an iron one.
//
//   node tools/selftest_doors.js
//
// What is asserted, and why each one is a source-level check rather than a runtime one:
//
//   1. every armed unit type reaches the shared door behaviour. The nine types are one Monster, one
//      Pillager and one Villager plus six subclasses, and "which of them has a door goal" is not
//      something the compiler can answer - so the table is checked here against ModEntities and
//      against each class's own `extends` clause;
//   2. the behaviour is driven every tick. GunBrain#tick is called from GunAttackGoal#tick, which is
//      only active while the mob has a live target and a gun, so the brain hook alone would leave
//      doors open whenever a unit stops fighting - the LivingTickEvent hook and the once-per-tick
//      guard are the answer;
//   3. the three [ai] keys exist with the shipped defaults and are documented in the README (AssetTest
//      also requires the documentation, but the defaults are only asserted here);
//   4. the two safety branches really exist in the source: "somebody is standing in the doorway" and
//      "this is not a wooden door" (which is what leaves iron doors alone);
//   5. the close decision itself. It is factored out as a pure method (`shouldClose`), and this gate
//      (a) compares the Java body with the JS mirror used for the simulation, textually, so the two
//      cannot drift, and (b) runs that mirror over the rule's cases: the doorway rule, the ally rule,
//      the delay backstop and the "unit is clear" leg.
//
// The vanilla facts the design leans on are read from the mapped 1.20.1 jar when it is present (the
// same technique selftest_antistall.js uses): Pillager/Raider reference no door goal at all, only
// Vindicator does, and DoorBlock/BlockSetType expose exactly the calls this mod uses.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
/** Comments are stripped for the structural checks; prose is never allowed to satisfy a check. */
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const squash = (text) => text.replace(/\s+/g, ' ').trim();

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const skip = (label, why) => console.log(`  SKIP  ${label}  (${why})`);

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

const doorRaw = read('gun/DoorBehavior.java');
const door = strip(doorRaw);
const brain = strip(read('gun/GunBrain.java'));
const config = strip(read('Config.java'));
const gunUser = strip(read('gun/GunUser.java'));
const attackGoal = strip(read('gun/GunAttackGoal.java'));
const main = strip(read('TarkovScav.java'));
const entities = strip(read('registry/ModEntities.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

// ---------------------------------------------------------------------------------------------
console.log('1. every armed unit type reaches the shared door behaviour');
// The nine registered gun-mob types. The roots call GunUser#allowDoors themselves; the other six are
// subclasses of a root and inherit the constructor, so the table is what proves the coverage.
const UNITS = [
  { name: 'ScavEntity', base: null, id: 'scav' },
  { name: 'GunnerPillagerEntity', base: null, id: 'gunner_pillager' },
  { name: 'GunnerVillagerEntity', base: null, id: 'gunner_villager' },
  { name: 'SniperPillagerEntity', base: 'GunnerPillagerEntity', id: 'sniper_pillager' },
  { name: 'SniperVillagerEntity', base: 'GunnerVillagerEntity', id: 'sniper_villager' },
  { name: 'BearPillagerEntity', base: 'GunnerPillagerEntity', id: 'bear_pillager' },
  { name: 'ElitePillagerEntity', base: 'GunnerPillagerEntity', id: 'elite_pillager' },
  { name: 'UsecVillagerEntity', base: 'GunnerVillagerEntity', id: 'usec_villager' },
  { name: 'EliteVillagerEntity', base: 'GunnerVillagerEntity', id: 'elite_villager' },
];
for (const unit of UNITS) {
  const source = strip(read(`entity/${unit.name}.java`));
  check(entities.includes(`"${unit.id}"`), `${unit.name} is a registered entity (${unit.id})`,
    'the table has to describe the units the game actually spawns');
  if (unit.base === null) {
    check(/GunUser\.allowDoors\(this\);/.test(source),
      `${unit.name} calls GunUser.allowDoors in its constructor`,
      'the navigation flags are what let the path go through a door in the first place');
  } else {
    check(new RegExp(`class ${unit.name} extends ${unit.base}\\b`).test(source),
      `${unit.name} extends ${unit.base}`,
      'so it inherits the allowDoors call and the door behaviour');
  }
}
check(/setCanOpenDoors\(true\)/.test(gunUser) && /setCanPassDoors\(true\)/.test(gunUser),
  'allowDoors enables both navigation door flags');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. the behaviour is driven from the brain tick AND every tick');
const brainTick = methodBody(brain, 'public void tick()');
check(/this\.doors\.tick\(level\);/.test(brainTick || ''),
  'GunBrain#tick calls this.doors.tick(level)');
check(brain.includes('private final DoorBehavior doors;') && /public DoorBehavior doors\(\)/.test(brain),
  'GunBrain owns one DoorBehavior per unit and exposes it');
check(/this\.doors = new DoorBehavior\(mob\);/.test(brain), 'the constructor creates it');
// The brain hook is NOT enough on its own, and that is the point of the event hook: GunBrain#tick is
// only called from GunAttackGoal#tick, which only runs while the mob has a live target and a gun.
const attackTick = methodBody(attackGoal, 'public void tick()');
check(/this\.brain\.tick\(\);/.test(attackTick || ''),
  'GunAttackGoal#tick is the only caller of GunBrain#tick',
  'and it only runs with a live target - so the brain alone cannot cover an idle unit');
check(attackGoal.includes('this.brain.ensureEquipped()') && /public boolean canUse\(\)/.test(attackGoal),
  'the goal refuses to run without a gun/target');
check(/@SubscribeEvent[\s\S]{0,400}LivingEvent\.LivingTickEvent/.test(door),
  'DoorBehavior has a LivingTickEvent handler');
check(/instanceof GunUser user[\s\S]{0,200}instanceof ServerLevel level[\s\S]{0,200}user\.gunBrain\(\)\.doors\(\)\.tick\(level\)/
    .test(door),
  'the handler ticks exactly the GunUser mobs, server side only');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.gun\.DoorBehavior\.class\);/.test(main),
  'TarkovScav registers DoorBehavior on the Forge event bus',
  'an unregistered handler would silently leave the every-tick half of the behaviour dead');
check(!/setCanceled/.test(door), 'the handler never cancels the tick event (it only observes it)');
const tickBody = methodBody(door, 'public void tick(ServerLevel level)');
check(/if \(this\.lastTick == now\) \{\s*return;/.test(tickBody || '') && /this\.lastTick = now;/.test(tickBody || ''),
  'the two drivers cannot tick one unit twice in a tick');
check(/Config\.SPEC\.isLoaded\(\)/.test(tickBody || ''), 'the config is checked before it is read');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. the [ai] config keys, with the documented defaults, in the README');
const keyDefaults = [
  ['closeDoorsBehind', /\.define\("closeDoorsBehind", true\)/],
  ['doorCloseDelayTicks', /\.defineInRange\("doorCloseDelayTicks", 40,/],
  ['doorCloseAllyRadius', /\.defineInRange\("doorCloseAllyRadius", 2\.0D,/],
];
for (const [key, re] of keyDefaults) {
  check(re.test(config), `Config defines ${key} with the documented default`);
  check(config.includes(`"${key}"`), `Config declares the ${key} spec value`);
  check(readme.includes(key), `README documents ${key}`, 'AssetTest requires every config key to be documented');
}
check(new RegExp('push\\("ai"\\)').test(config), 'the keys live in the new [ai] section');
for (const field of ['CLOSE_DOORS_BEHIND', 'DOOR_CLOSE_DELAY_TICKS', 'DOOR_CLOSE_ALLY_RADIUS']) {
  check(new RegExp(`ForgeConfigSpec\\.\\w+Value ${field};`).test(config), `Config declares the ${field} field`);
}
// The master switch has to be asked about before the close loop runs, and a disabled switch must not
// leave stale entries behind that would close a door later, when the user has switched it back on.
check(/if \(!closeBehind\) \{\s*this\.opened\.clear\(\);/.test(tickBody || ''),
  'closeDoorsBehind = false forgets the pending doors',
  'otherwise a door would be shut later, after the key was switched back on');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. the two safety branches are in the source');
const close = methodBody(door, 'private void closeDoorsLeftOpen(');
const open = methodBody(door, 'private void openDoorInTheWay(');
check(/if \(!DoorBlock\.isWoodenDoor\(state\) \|\| state\.getValue\(DoorBlock\.OPEN\)\) \{\s*return;/.test(open || ''),
  'the open path refuses anything that is not a closed wooden door',
  'isWoodenDoor is type().canOpenByHand(), i.e. false for iron, stone, gold');
check(/if \(!DoorBlock\.isWoodenDoor\(state\) \|\| !state\.getValue\(DoorBlock\.OPEN\)\) \{\s*this\.opened\.remove\(i\);/.test(close || ''),
  'the close path re-checks the wooden door (and forgets a door somebody else already shut)');
check(!/BlockSetType\.IRON/.test(door), 'no iron-specific code path exists at all');
check(/private boolean doorwayOccupied\(BlockPos lower, BlockPos upper\)/.test(door)
  && /box\.intersects\(new AABB\(lower\)\) \|\| box\.intersects\(new AABB\(upper\)\)/.test(door),
  'the "somebody is standing in the doorway" test intersects BOTH halves of the door');
check(/doorwayOccupied \|\| otherLivingNearby/.test(door),
  'and it wins over the timing in the decision');
check(/private boolean otherLivingWithin\(/.test(door)
  && /lower\.closerToCenterThan\(other\.position\(\), radius\)/.test(door),
  'the ally test is vanilla InteractWithDoor\'s own (door centre vs entity position)');
check(/if \(other == this\.mob\) \{\s*continue;/.test(door),
  'the closing unit is excluded from the ally test',
  'it is handled by doorwayOccupied, so a unit 1 block away can still shut the door');
check(/level\.playSound\(this\.mob, pos, sound, SoundSource\.BLOCKS/.test(door)
  && /door\.type\(\)\.doorClose\(\)/.test(door),
  'closing plays the matching BlockSetType door-close sound');
check(/level\.setBlock\(pos, state\.setValue\(DoorBlock\.OPEN, open\), 10\);/.test(door),
  'the block is written with DoorBlock.OPEN and flag 10, like vanilla DoorBlock#setOpen');
check(/this\.mob\.horizontalCollision && distance <= radius/.test(close || '')
  && /this\.opened\.set\(i, new OpenedDoor\(lower, now\)\)/.test(close || ''),
  'a unit still pushing at the door restarts the delay instead of re-opening/re-closing in a loop');
check(/MAX_REMEMBERED_DOORS/.test(door) && /this\.opened\.remove\(0\)/.test(door),
  'the memory of opened doors is bounded');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. the close decision (pure method + simulation)');
// The mirror. The Java body is compared with this text below, so the simulation cannot drift from
// the shipped rule: change the Java and this file fails until the mirror is changed with it.
const DECISION_SOURCE = `
if (doorwayOccupied || otherLivingNearby) { return false; }
return elapsedTicks >= delayTicks || distanceToUnit > clearRadius;
`;
const shouldClose = new Function('elapsedTicks', 'delayTicks', 'distanceToUnit', 'clearRadius',
  'otherLivingNearby', 'doorwayOccupied', DECISION_SOURCE);
const javaDecision = methodBody(door, 'static boolean shouldClose(');
check(javaDecision !== null, 'DoorBehavior has the pure shouldClose method');
check(squash(javaDecision || '') === squash(DECISION_SOURCE),
  'the Java decision and the JS mirror are the same expression',
  squash(javaDecision || '') === squash(DECISION_SOURCE) ? '' : `java: ${squash(javaDecision || '')}`);
check(/if \(shouldClose\(elapsed, delayTicks, distance, radius, allyNearby, occupied\)\)[\s\S]{0,160}setOpen\(level, lower, state, false\)/
    .test(close || ''),
  'the ONLY close call is behind shouldClose(...)',
  'a second close site would bypass the safety rules');
check(/Config\.DOOR_CLOSE_DELAY_TICKS\.get\(\)/.test(close || '')
  && /Config\.DOOR_CLOSE_ALLY_RADIUS\.get\(\)/.test(close || ''),
  'the decision is fed from the two config keys');

const DELAY = 40;
const RADIUS = 2.0;
const cases = [
  // [label, elapsed, distance, ally, occupied, expected]
  ['somebody standing in the doorway never closes (10000 ticks, 0.0 away)', 10000, 0.0, false, true, false],
  ['an ally within the radius never closes (10000 ticks, 0.0 away)', 10000, 0.0, true, false, false],
  ['inside the radius before the delay: keep waiting', 39, 0.5, false, false, false],
  ['the delay is the backstop: shut it at tick 40 even 0.5 blocks away', 40, 0.5, false, false, true],
  ['the clear leg beats the delay: shut it at once beyond 2.0 blocks', 1, 2.25, false, false, true],
  ['exactly at the radius is NOT clear (the test is distance > radius)', 1, 2.0, false, false, false],
  ['exactly at the delay is enough (the test is elapsed >= delay)', 40, 2.0, false, false, true],
];
for (const [label, elapsed, distance, ally, occupied, expected] of cases) {
  const got = shouldClose(elapsed, DELAY, distance, RADIUS, ally, occupied);
  check(got === expected, label, `elapsed=${elapsed} dist=${distance} ally=${ally} occupied=${occupied} -> ${got}`);
}

// A tick-by-tick walk, from the tick the unit opens the door (t = 0, standing in the doorway) until
// the close fires. Only the decision function is simulated; the loop guard and the block writes are
// asserted structurally above.
console.log('  (simulated: 0.25 blocks/tick walk, 2.0-block radius, 40-tick delay)');
function simulateClose({ speed, stopAt = null, allyUntil = 0 }) {
  for (let t = 0; t <= 400; t++) {
    const distance = t === 0 ? 0.5 : Math.min(0.5 + speed * (t - 1), stopAt === null ? Infinity : stopAt);
    const occupied = t === 0;
    const ally = t > 0 && t <= allyUntil;
    if (shouldClose(t, DELAY, distance, RADIUS, ally, occupied)) return t;
  }
  return -1;
}
const walkCases = [
  ['walks straight on: clear of the 2.0-block radius on tick 8', { speed: 0.25 }, 8],
  ['stops in the doorway (0.5 blocks away): the 40-tick delay shuts it', { speed: 0.0 }, 40],
  ['stops 1.0 block short of the radius: still the 40-tick delay', { speed: 0.25, stopAt: 1.0 }, 40],
  ['an ally is at the door until tick 60: it waits, then shuts on tick 61', { speed: 0.0, allyUntil: 60 }, 61],
  ['never closes while an ally camps at the door for the whole run', { speed: 0.0, allyUntil: 400 }, -1],
];
for (const [label, options, expected] of walkCases) {
  const got = simulateClose(options);
  check(got === expected, label, `close tick = ${got}`);
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('6. the vanilla facts the design is based on (mapped jar)');
function zipEntry(jar, name) {
  const eocd = jar.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = jar.readUInt16LE(eocd + 10);
  let at = jar.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (jar.readUInt32LE(at) !== 0x02014b50) return null;
    const method = jar.readUInt16LE(at + 10);
    const compressed = jar.readUInt32LE(at + 20);
    const nameLen = jar.readUInt16LE(at + 28);
    const extraLen = jar.readUInt16LE(at + 30);
    const commentLen = jar.readUInt16LE(at + 32);
    const localAt = jar.readUInt32LE(at + 42);
    const entryName = jar.toString('utf8', at + 46, at + 46 + nameLen);
    if (entryName === name) {
      const localNameLen = jar.readUInt16LE(localAt + 26);
      const localExtraLen = jar.readUInt16LE(localAt + 28);
      const dataAt = localAt + 30 + localNameLen + localExtraLen;
      const raw = jar.slice(dataAt, dataAt + compressed);
      return method === 0 ? raw : zlib.inflateRawSync(raw);
    }
    at += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}
const mappedJar = path.join(ROOT, '..', 'GirlsFrontline', '.gradle-home', 'caches', 'forge_gradle',
  'minecraft_user_repo', 'net', 'minecraftforge', 'forge', '1.20.1-47.3.0_mapped_official_1.20.1',
  'forge-1.20.1-47.3.0_mapped_official_1.20.1.jar');
if (fs.existsSync(mappedJar)) {
  const jar = fs.readFileSync(mappedJar);
  const has = (entry, needle) => {
    const body = zipEntry(jar, entry);
    if (!body) return null;
    return body.includes(Buffer.from(needle));
  };
  // "only Vindicator registers a door goal" is the whole reason this behaviour had to be written:
  // a class file only names a type it references, so a class that registers OpenDoorGoal (or its
  // inner RaiderOpenDoorGoal, which contains the same substring) must contain the name.
  for (const [entry, expected] of [
    ['net/minecraft/world/entity/monster/Pillager.class', false],
    ['net/minecraft/world/entity/raid/Raider.class', false],
    ['net/minecraft/world/entity/monster/Vindicator.class', true],
  ]) {
    const references = has(entry, 'OpenDoorGoal');
    if (references === null) { skip(`${entry} and OpenDoorGoal`, 'entry not found in the mapped jar'); continue; }
    check(references === expected,
      `${entry.split('/').pop()} ${expected ? 'does' : 'does not'} reference OpenDoorGoal`,
      expected ? 'the one vanilla mob that opens doors' : 'so the mob cannot open a door on its own');
  }
  const doorBlock = zipEntry(jar, 'net/minecraft/world/level/block/DoorBlock.class');
  if (doorBlock) {
    check(doorBlock.includes(Buffer.from('isWoodenDoor')) && doorBlock.includes(Buffer.from('canOpenByHand')),
      'DoorBlock#isWoodenDoor asks BlockSetType#canOpenByHand',
      'which is what makes "iron is never touched" structural');
    check(doorBlock.includes(Buffer.from('OPEN')) && doorBlock.includes(Buffer.from('HALF')),
      'DoorBlock exposes OPEN and HALF');
  } else {
    skip('DoorBlock API', 'entry not found in the mapped jar');
  }
  const blockSetType = zipEntry(jar, 'net/minecraft/world/level/block/state/properties/BlockSetType.class');
  check(blockSetType !== null && blockSetType.includes(Buffer.from('doorClose')),
    'BlockSetType exposes doorClose (the sound the close uses)');
  const villagerDoors = zipEntry(jar, 'net/minecraft/world/entity/ai/behavior/InteractWithDoor.class');
  check(villagerDoors !== null && villagerDoors.includes(Buffer.from('closeDoorsThatIHaveOpenedOrPassedThrough'))
    && villagerDoors.includes(Buffer.from('MAX_DISTANCE_TO_HOLD_DOOR_OPEN_FOR_OTHER_MOBS')),
    'the villager brain does close doors - and holds them for allies within a 2.0-block constant',
    'the same number as doorCloseAllyRadius, and the precedent for this behaviour');
  // The every-tick driver is only real if Forge fires the event: LivingEntity#tick calls
  // ForgeHooks.onLivingTick as its FIRST instruction, and that is what posts LivingTickEvent.
  check(has('net/minecraft/world/entity/LivingEntity.class', 'onLivingTick') === true
    && has('net/minecraftforge/common/ForgeHooks.class', 'LivingTickEvent') === true,
    'Forge fires LivingTickEvent for every living entity, from the top of LivingEntity#tick',
    'so the every-tick driver is a fact about the loader, not a hope');
} else {
  skip('vanilla door class-file checks', 'mapped jar not present');
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('7. the stuck-mob log line reports the door state in one line');
check(/doors: open=\{\} pass=\{\} closes=\{\} pending=\{\}/.test(brain),
  'the no-progress WARN carries open= pass= closes= pending=');
check(/private boolean doorsClose\(\)\s*\{\s*return Config\.CLOSE_DOORS_BEHIND\.get\(\);\s*\}/.test(brain)
  && /private int doorsPending\(\)\s*\{\s*return this\.doors\.pending\(\);\s*\}/.test(brain),
  'and both values come from the live config / the unit\'s own memory');

console.log('');
if (failures > 0) {
  console.log(`${failures} door check(s) FAILED`);
  process.exit(1);
}
console.log('door behaviour invariants all hold');
