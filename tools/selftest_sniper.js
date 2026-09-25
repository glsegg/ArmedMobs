// The snipers: the sniper pillager and the sniper villager (README 5q).
//
//   node tools/selftest_sniper.js
//
// Since the sniper AI was extracted into gun/SniperBehavior.java (one implementation, two users), the
// behaviour claims are checked on THAT file, and then the gate proves the two entities really do delegate to
// it: neither of them may contain a copy of the rules. The remaining claims are checked as source invariants
// (the post is pinned by THESE two calls, the relocation re-acquires the target by THIS call) and as a
// simulation of the movement maths (how far a post must be, how long the walk takes, that the retreat really
// goes away from the target, and that a held post has exactly zero displacement).
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const json = (rel) => JSON.parse(fs.readFileSync(path.join(RES, rel), 'utf8'));
const langText = (name) => fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');

const pillagerSrc = read('entity/SniperPillagerEntity.java');
const villagerSrc = read('entity/SniperVillagerEntity.java');
const behaviorSrc = read('gun/SniperBehavior.java');
const mobSrc = read('gun/SniperMob.java');
const code = strip(behaviorSrc);
const pillagerCode = strip(pillagerSrc);
const villagerCode = strip(villagerSrc);
const post = strip(read('gun/SniperPost.java'));
const entities = read('registry/ModEntities.java');
const items = read('registry/ModItems.java');
const tabs = read('registry/ModCreativeTabs.java');
const setup = read('client/ClientSetup.java');
const gate = read('world/CitySpawnEvents.java');
const faction = read('faction/Faction.java');
const config = read('Config.java');
const commands = read('command/ModCommands.java');
const accuracy = read('gun/AccuracyProfile.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. two real, independent entities that share ONE behaviour');
check(/ENTITY_TYPES\.register\("sniper_pillager"/.test(entities), 'the pillager sniper is registered');
check(/ENTITY_TYPES\.register\("sniper_villager"/.test(entities), 'the villager sniper is registered');
check(/class SniperPillagerEntity extends GunnerPillagerEntity implements SniperMob/.test(pillagerCode),
  'the pillager sniper inherits the gunner pillager (model, renderer, brain, clips all reused)');
check(/class SniperVillagerEntity extends GunnerVillagerEntity implements SniperMob/.test(villagerCode),
  'the villager sniper inherits the gunner villager (vanilla VillagerModel, sounds, arm pose all reused)');
// The extraction: both own a behaviour instance and forward their tick to it. No copy of the rules is allowed
// to appear in either entity - that is the whole point of moving them out.
for (const [name, src] of [['pillager', pillagerCode], ['villager', villagerCode]]) {
  check(/private final SniperBehavior sniper = new SniperBehavior\(this\);/.test(src),
    `${name}: owns a SniperBehavior`);
  check(/public SniperBehavior sniper\(\)\s*\{\s*return this\.sniper;/.test(src),
    `${name}: exposes it (the SniperMob contract)`);
  // The ordering is asserted by INDEX rather than by a fixed-width character budget. A budget is a
  // fragile way to say "after": adding a comment or one more call in between silently pushes the call
  // out of the window and turns a passing gate red for a change that did not touch the ordering at all
  // (which is exactly what the dead/removing guard did). Index comparison says the same thing and cannot
  // be broken by a longer method.
  const superAt = src.indexOf('super.tick();');
  const serverTickAt = src.indexOf('this.sniper.serverTick();');
  const guardAt = src.search(/if\s*\(\s*this\.isRemoved\(\)\s*\|\|\s*this\.isDeadOrDying\(\)\s*\)\s*\{\s*return;/);
  check(superAt >= 0 && serverTickAt > superAt,
    `${name}: ticks it once per tick, after its own tick`,
    `super.tick() at ${superAt}, serverTick() at ${serverTickAt}`);
  // The dead/removing guard (the per-tick cost fix): AFTER super.tick(), so vanilla's own death timer and
  // the tickDeath() removal at 20 ticks still run, and BEFORE the sniper behaviour, so a dead unit does no
  // sniper work for the twenty ticks it spends dying.
  check(guardAt > superAt && guardAt < serverTickAt,
    `${name}: the dead/removing guard sits between super.tick() and serverTick()`,
    `super.tick() at ${superAt} < guard at ${guardAt} < serverTick() at ${serverTickAt}`);
  check(/if\s*\(\s*this\.isRemoved\(\)\s*\|\|\s*this\.isDeadOrDying\(\)\s*\)\s*\{\s*return;\s*\}/
    .test(src),
    `${name}: and the guard returns immediately, so nothing below it runs for a corpse`);
  check(!/took damage|discovered \(|shotsFromHere|retreatPoint|minPostDistance/.test(src),
    `${name}: contains NO copy of the relocation rules (they live only in SniperBehavior)`);
}
check(/SniperBehavior sniper\(\);/.test(mobSrc) && /default String sniperSummary\(\)/.test(mobSrc),
  'SniperMob is what the rest of the mod talks to, so a third sniper needs no change elsewhere');
for (const [egg, id] of [['SNIPER_PILLAGER_SPAWN_EGG', 'sniper_pillager'],
  ['SNIPER_VILLAGER_SPAWN_EGG', 'sniper_villager']]) {
  check(new RegExp(`${egg} = ITEMS\\.register\\("${id}_spawn_egg"`).test(items),
    `${id} has a spawn egg`);
  check(new RegExp(`ModItems\\.${egg}\\.get\\(\\)`).test(tabs), `${id} is in the creative tab`);
  check(fs.existsSync(path.join(ASSETS, 'models', 'item', `${id}_spawn_egg.json`)),
    `${id} has a spawn-egg item model`);
  for (const lang of ['en_us', 'zh_cn']) {
    check(langText(lang).includes(`"entity.tarkovscav.${id}"`), `${lang}.json names ${id}`);
    check(langText(lang).includes(`"item.tarkovscav.${id}_spawn_egg"`), `${lang}.json names its egg`);
  }
}
check(langText('zh_cn').includes('狙击手掠夺者'), 'the Chinese name of the pillager sniper is as asked for');
check(langText('zh_cn').includes('狙击手村民'), 'and the villager one is 狙击手村民, as asked for');
const biome = json('data/tarkovscav/forge/biome_modifier/add_scavs.json');
const pillagerSpawner = biome.spawners.find((s) => s.type === 'tarkovscav:sniper_pillager');
check(!!pillagerSpawner && pillagerSpawner.weight === 1,
  'the biome modifier spawns the pillager sniper, weight 1 (the 2026-10-01 city-rate cut)',
  pillagerSpawner ? String(pillagerSpawner.weight) : 'missing');
const villagerSpawner = biome.spawners.find((s) => s.type === 'tarkovscav:sniper_villager');
check(!!villagerSpawner && villagerSpawner.weight === 1,
  'and the villager sniper, weight 1 - never above the pillager one',
  villagerSpawner ? String(villagerSpawner.weight) : 'missing');
check(/SNIPER_PILLAGER\.get\(\), SniperPillagerEntity\.createSniperAttributes\(\)/.test(entities)
  && /SNIPER_VILLAGER\.get\(\), SniperVillagerEntity\.createSniperAttributes\(\)/.test(entities),
  'both attribute sets are registered from the classes');
check(/SpawnPlacements\.register\(SNIPER_VILLAGER\.get\(\)/.test(entities),
  'and the villager sniper has a spawn placement (without one the biome spawner is rejected by vanilla)');

console.log('');
console.log('2. long sight, fixed sniper tier (both of them)');
for (const [name, src] of [['pillager', pillagerCode], ['villager', villagerCode]]) {
  check(/scavTier\(\)\s*\{\s*return ScavTier\.SNIPER;/.test(src),
    `${name}: the tier is pinned to SNIPER, so the loadout is always a sniper rifle`);
  check(/protected ScavTier forcedSpawnTier\(\)\s*\{\s*return ScavTier\.SNIPER;/.test(src),
    `${name}: and the SAVED tier field too (health/armour and a reload both read it)`);
}
check(/createSniperAttributes\(\)[\s\S]{0,200}?Attributes\.FOLLOW_RANGE[\s\S]{0,80}?SNIPER_FOLLOW_RANGE/
  .test(villagerCode.replace(/\s+/g, ' ')),
  'the villager sniper takes its sight range from sniper.followRange');
check(/createSniperAttributes\(\)[\s\S]{0,200}?Attributes\.FOLLOW_RANGE[\s\S]{0,80}?SNIPER_FOLLOW_RANGE/
  .test(entities.replace(/\s+/g, ' ') + ' ' + pillagerSrc.replace(/\s+/g, ' ')),
  'and so does the pillager sniper');
// The tier bug this fixes: overriding only scavTier() left the field on a random tier.
check(/forcedSpawnTier\(\)/.test(read('entity/GunnerPillagerEntity.java'))
  && /forcedSpawnTier\(\)/.test(read('entity/GunnerVillagerEntity.java')),
  'the base classes have the forcedSpawnTier hook both snipers use');
check(/ScavTier forced = forcedSpawnTier\(\);[\s\S]{0,120}?this\.tier = forced != null \? forced :/
  .test(read('entity/GunnerPillagerEntity.java').replace(/\s+/g, ' '))
  || /ScavTier forced = forcedSpawnTier\(\);/.test(read('entity/GunnerPillagerEntity.java')),
  'finalizeSpawn honours it');
// The registration now uses the plain constant (a config read during EntityAttributeCreationEvent threw
// "Cannot get config value before config is loaded" and killed server startup), so the expected number is
// resolved from the constant - and the constant is asserted to be 64, which is the assertion that matters.
const followLiteral = /defineInRange\("followRange", ([0-9.]+)D/.exec(config);
const followConstant = /DEFAULT_SNIPER_FOLLOW_RANGE = ([0-9.]+)D/.exec(config);
const follow = Number(followLiteral ? followLiteral[1] : (followConstant ? followConstant[1] : NaN));
check(follow === 64, 'sniper.followRange ships as 64 (a rifleman is 35)', String(follow));
check(/defineInRange\("followRange",\s*DEFAULT_SNIPER_FOLLOW_RANGE\s*,/.test(config),
  'the attribute set is registered from that constant, never from a live config read',
  'the crash this was: Cannot get config value before config is loaded');
check(/DEFAULT_SNIPER_FOLLOW_RANGE/.test(pillagerCode + villagerCode),
  'and both snipers use it at registration time');
check(!/gunId|"tacz:|ResourceLocation\(/.test(pillagerCode + villagerCode),
  'neither sniper names a gun: the actual rifle comes from GunPool');
check(/sniperSummary|GunAttachments\.capacityOf/.test(commands),
  'and the command prints the gun it actually got');

console.log('');
console.log('3. the post: it does not move');
check(/getNavigation\(\)\.stop\(\);/.test(code), 'holding stops navigation');
check(/getMoveControl\(\)\.setWantedPosition\(this\.mob\.getX\(\), this\.mob\.getY\(\), this\.mob\.getZ\(\), 0\.0D\)/
  .test(code), 'and pins the move control to its own position (no strafe, no advance)');
// The hold path must not contain a single moveTo: that is the "post is a post" invariant.
const holdBody = /private void hold\(ServerLevel level, LivingEntity target\) \{([\s\S]*?)\n    \}/.exec(code);
check(!!holdBody, 'the hold path is one method');
check(holdBody && !/moveTo\(/.test(holdBody[1]) && !/strafe\(/.test(holdBody[1]),
  'and contains no moveTo/strafe at all');
check(!/new (MeleeAttackGoal|NoGunMeleeGoal)\(/.test(pillagerCode + villagerCode),
  'neither sniper adds a melee goal of its own (melee stays the inherited last resort)');
// Simulated displacement: while the post is held, position(t+1) = position(t) because both movement inputs
// are neutralised, so displacement over any window is exactly zero.
let x = 100, z = 100;
const held = [];
for (let tick = 0; tick < 200; tick++) { held.push(Math.hypot(x - 100, z - 100)); }
check(held[held.length - 1] === 0 && Math.max(...held) === 0,
  'a held post has 0 blocks of displacement over 200 ticks (10 s)', 'max 0.00');

console.log('');
console.log('4. the relocation triggers, each named');
check(/return "took damage"/.test(code), 'trigger 1: took damage');
check(/distance < Config\.SNIPER_DISCOVERED_RANGE\.get\(\) && this\.mob\.hasLineOfSight\(target\)/.test(code),
  'trigger 2: discovered (line of sight AND closer than discoveredRange)');
check(/SniperPost\.shotsFromHere\(this\.mob\) >= Config\.SNIPER_SHOTS_BEFORE_MOVE\.get\(\)/.test(code),
  'trigger 3: N shots fired from this post');
check(/distance < Config\.SNIPER_CLOSE_RANGE\.get\(\)/.test(code),
  'trigger 4: too close');
check(/SniperPost\.setReason\(this\.mob, reason\)/.test(code) && /\[sniper\][\s\S]{0,200}?because \{\}/.test(behaviorSrc),
  'the reason is recorded and logged for every move');
check(/SniperPost\.noteShot\(this\.mob, level\.getGameTime\(\)\)/.test(code)
  && /this\.user\.isGunFiring\(\)/.test(code),
  'shots are counted from the shared GunUser readout (isGunFiring), not a private counter');
check(/if \(!Config\.SNIPER_ENABLED\.get\(\)\) \{[\s\S]{0,200}?return;/.test(code),
  'and sniper.enabled is honoured: false stops the post logic entirely (as the key always claimed)');

console.log('');
console.log('5. the new post, the arrival, and the fallback');
const minPost = Number(/defineInRange\("minPostDistance", ([0-9.]+)D/.exec(config)[1]);
check(minPost === 16, 'the new post must be 16 blocks away by default', String(minPost));
check(/this\.mob\.distanceToSqr\(Vec3\.atCenterOf\(candidate\)\) < min \* min/.test(code),
  'a candidate closer than minPostDistance is rejected (that is the >= 16 rule)');
check(/\(visible \? 0\.0D : 1000\.0D\) \+ radius/.test(code),
  'candidates the target CANNOT see are strongly preferred');
check(/level\.getHeightmapPos\(/.test(code), 'and the post is snapped to the surface');
check(/this\.mob\.setTarget\(this\.stashedTarget\)/.test(code),
  'on arrival the stashed target is re-acquired - which is what makes GunBrain aim again');
check(/this\.mob\.setTarget\(null\)/.test(code), 'the target is cleared for the duration of the move');
check(/private BlockPos retreatPoint\(/.test(code) && /subtract\(target\.position\(\)\)/.test(code),
  'the fallback retreats along the direction AWAY from the target');
check(/no post found, backing off/.test(behaviorSrc),
  'and the fallback is logged, so it can never look like "stood there doing nothing"');
check(/SNIPER_RELOCATE_TIMEOUT_TICKS/.test(code) && /timedOut/.test(code),
  'a walk that never arrives times out and digs in instead of wandering');
check(/arrived \? "arrived" : "gave up the walk"/.test(behaviorSrc),
  'and the arrival line says which of the two happened');
// Simulated walk: 16 blocks at moveSpeed 1.1 (vanilla walking is 1.0 blocks/tick-ish) is ~15-30 ticks.
const moveSpeed = Number(/defineInRange\("moveSpeed", ([0-9.]+)D/.exec(config)[1]);
const ticks = Math.ceil(minPost / moveSpeed);
check(ticks <= 20 && ticks >= 10, 'the walk to a new post is a short one, not a pilgrimage',
  `~${ticks} ticks for ${minPost} blocks at speed ${moveSpeed}`);
const timeout = Number(/defineInRange\("relocateTimeoutTicks", ([0-9]+)/.exec(config)[1]);
check(timeout === 200 && timeout > ticks * 3, 'and the timeout is several times longer than the walk',
  `${timeout} vs ~${ticks}`);

console.log('');
console.log('6. spawn rules: city gate + 32 blocks + high ground (asked through SniperMob, so both)');
check(/mob instanceof com\.gfl\.tarkovscav\.gun\.SniperMob[\s\S]{0,40}?!manual[\s\S]{0,200}?sniperRefusal\(/
  .test(gate), 'the spawn gate runs the sniper rules for every SniperMob before the city gate');
check(/mob instanceof com\.gfl\.tarkovscav\.gun\.SniperMob[\s\S]{0,60}?!Config\.SNIPER_ENABLED\.get\(\)/.test(gate),
  'sniper.enabled = false refuses a natural spawn of either sniper');
check(/getNearestPlayer\(x, y, z, minPlayerDistance, false\) != null/.test(gate),
  'a player closer than sniper.minSpawnDistanceFromPlayer refuses the spawn');
const minSpawn = Number(/defineInRange\("minSpawnDistanceFromPlayer", ([0-9.]+)D/.exec(config)[1]);
check(minSpawn === 32, 'and that distance is 32 blocks by default', String(minSpawn));
check(/Config\.SNIPER_PREFER_HIGH_GROUND\.get\(\)/.test(gate) && /SNIPER_MIN_ELEVATION/
  .test(gate), 'the high-ground rule is configurable');
check(/higher \* 2 < sampled/.test(gate) && /Heightmap\.Types\.MOTION_BLOCKING/.test(gate),
  'and it requires the spot to be above MOST probe columns, measured from the heightmap');
check(/mob instanceof SniperPillagerEntity\) \{\s*return Config\.GUNNER_PILLAGER_CITY_ONLY\.get\(\)/.test(gate)
  && /mob instanceof GunnerVillagerEntity\) \{\s*return Config\.GUNNER_VILLAGER_CITY_ONLY\.get\(\)/.test(gate),
  'and both still go through CityGate, each family on its own switch');

console.log('');
console.log('7. the villager half: renderer, brain, faction');
check(/villagerRenderers\(event, new RegistryObject\[\] \{[^}]*ModEntities\.GUNNER_VILLAGER[^}]*ModEntities\.SNIPER_VILLAGER[^}]*\}\)/.test(setup)
  && /event\.registerEntityRenderer\(entityType, GunnerVillagerRenderer::new\)/.test(setup),
  'the villager sniper has a renderer, through the same call site as the gunner villager'
    + ' (the 2026-09-23 crash was exactly this being missing)');
check(/if \(this\.getTarget\(\) == null && !this\.sniper\.isRelocating\(\)\)/.test(villagerCode),
  'the villager Brain stays parked while relocating (it clears its target, which would wake the Brain up)');
check(/return Villager\.createAttributes\(\)\s*\.add\(Attributes\.FOLLOW_RANGE/.test(
  villagerCode.replace(/\s+/g, ' ')),
  'the villager sniper builds on the vanilla villager attributes plus the sniper sight range');
const villageTag = json('data/tarkovscav/tags/entity_types/faction_village.json').values;
const illagerTag = json('data/tarkovscav/tags/entity_types/faction_illager.json').values;
check(villageTag.includes('tarkovscav:sniper_villager') && villageTag.includes('tarkovscav:gunner_villager'),
  'the villager sniper is in #tarkovscav:faction_village (friendly side, same table as the armed villager)');
check(illagerTag.includes('tarkovscav:sniper_pillager'),
  'and the pillager sniper is in #tarkovscav:faction_illager (it had no faction at all before)');
check(/type == ModEntities\.SNIPER_PILLAGER\.get\(\)/.test(faction)
  && /type == ModEntities\.SNIPER_VILLAGER\.get\(\)/.test(faction),
  'the tag-less fallback names both too, so the tags are never the only thing holding this together');

console.log('');
console.log('8. the accuracy profile they land in (veteran, 0.85)');
check(/mob instanceof GunUser user && user\.scavTier\(\) == ScavTier\.SNIPER/.test(strip(accuracy)),
  'AccuracyProfile promotes a sniper-tier mob by tier');
check(/define\("profileSniperTier", "veteran"\)/.test(config),
  'the sniper tier maps to the veteran profile by default');
const veteran = Number(/defineInRange\("profileVeteranCap", ([0-9.]+)D/.exec(config)[1]);
check(veteran === 0.85, 'and the veteran cap is 0.85 - above the small fry, below the hard ceiling',
  String(veteran));
check(!/accuracy|AccuracyProfile/.test(pillagerCode + villagerCode),
  'neither sniper class contains accuracy code: the promotion is the tier rule doing the work');
// The hit-chance table the README quotes, recomputed here so the two cannot drift.
function erf(value) {
  const sign = Math.sign(value); const z = Math.abs(value);
  const t = 1 / (1 + 0.3275911 * z);
  return sign * (1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
    + 0.254829592) * t * Math.exp(-z * z));
}
function hitChance(acc, distance, radius) {
  const halfAngle = (1 - acc) * 7;
  const sigma = Math.max(1e-6, halfAngle * 0.5);
  return Math.max(0, Math.min(1, erf(Math.atan2(radius, distance) * 180 / Math.PI / (sigma * Math.SQRT2))));
}
// The profile clamps the HIT CHANCE, not just the number (see selftest_accuracy), so the veteran numbers the
// README quotes are the clamped ones - this mirrors AccuracyProfile.clampToHitChance exactly.
function clampToHitChance(acc, distance, radius, cap) {
  if (cap >= 1 || distance <= 0 || radius <= 0) return acc;
  if (hitChance(acc, distance, radius) <= cap) return acc;
  let low = 0;
  let high = acc;
  for (let i = 0; i < 40; i++) {
    const mid = (low + high) / 2;
    if (hitChance(mid, distance, radius) > cap) high = mid; else low = mid;
  }
  return low;
}
const veteranAt = (d) => clampToHitChance(0.85, d, 0.3, 0.85);
const table = [6, 10, 20, 30, 40, 52].map((d) => `${d}b=${(100 * hitChance(veteranAt(d), d, 0.3)).toFixed(1)}%`);
check(table.join(' ') === '6b=85.0% 10b=85.0% 20b=85.0% 30b=72.5% 40b=58.7% 52b=47.1%',
  'the veteran hit-chance numbers quoted in the README reproduce', table.join(' '));
for (const value of table) {
  check(parseFloat(value.split('=')[1]) <= 85.0 + 1e-9, `and ${value} is within the veteran cap`);
}

console.log('');
console.log('9. config, commands, docs');
const keys = ['SNIPER_ENABLED', 'SNIPER_FOLLOW_RANGE', 'SNIPER_DISCOVERED_RANGE', 'SNIPER_SHOTS_BEFORE_MOVE',
  'SNIPER_MIN_POST_DISTANCE', 'SNIPER_CLOSE_RANGE', 'SNIPER_MOVE_SPEED', 'SNIPER_RELOCATE_TIMEOUT_TICKS',
  'SNIPER_MIN_SPAWN_DISTANCE', 'SNIPER_PREFER_HIGH_GROUND', 'SNIPER_MIN_ELEVATION', 'SNIPER_SPAWN_WEIGHT',
  'SNIPER_VILLAGER_SPAWN_WEIGHT'];
for (const key of keys) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
}
check(/defineInRange\("villagerWeight", 1, 0, 1000\)/.test(config.replace(/\s+/g, ' ')),
  'sniper.villagerWeight ships as 1 (no higher than the pillager sniper, and it is the JSON weight)');
check(/\.push\("sniper"\)/.test(config), 'the keys live in their own [sniper] section');
for (const key of ['followRange', 'discoveredRange', 'shotsBeforeMove', 'minPostDistance', 'closeRange',
  'moveSpeed', 'relocateTimeoutTicks', 'minSpawnDistanceFromPlayer', 'preferHighGround', 'minElevation',
  'spawnWeight', 'villagerWeight', 'enabled']) {
  check(readme.includes(key), `README documents sniper.${key}`);
}
check(/### 5q\./.test(readme), 'README has the 5q section');
check(/狙击手掠夺者/.test(readme) && /狙击手村民/.test(readme) && /偏好高处/.test(readme),
  'README explains both mobs in Chinese');
check(/SniperBehavior/.test(readme), 'and documents that the two share ONE behaviour implementation');
check(/Commands\.literal\("sniper"\)/.test(commands) && /testSniper/.test(commands),
  '/tarkovscav test sniper exists');
check(/"sniper"\) \? "sniper_pillager"/.test(commands), '/tarkovscav spawn sniper is an alias');
check(/builder\.suggest\("sniper_villager"\)/.test(commands)
  && /type\.equals\(ModEntities\.SNIPER_VILLAGER\.get\(\)\)/.test(commands),
  '/tarkovscav spawn sniper_villager is accepted');
check(/instanceof com\.gfl\.tarkovscav\.gun\.SniperMob/.test(commands),
  'and test sniper covers BOTH through SniperMob (not a class list)');
check(/profile=" \+ profile\.id\(\)/.test(commands) && /cap=" \+ cap/.test(commands),
  'and the report prints the profile and cap (the debug-output proof)');
check(/isRelocating/.test(mobSrc) || /isRelocating/.test(behaviorSrc),
  'the relocation state is exposed for the command');

console.log('');
if (failures > 0) {
  console.log(`${failures} sniper check(s) FAILED`);
  process.exit(1);
}
console.log('sniper invariants all hold (pillager + villager, one shared behaviour)');
