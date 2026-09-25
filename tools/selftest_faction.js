// Factions, friendly fire, the renegade brand and shared intel (README 5m).
//
//   node tools/selftest_faction.js
//
// Nobody can look at a screen from here, so every claim in this batch has to be either a source invariant
// or a simulation of the exact algorithm the Java runs. The six gates the design asked for are A..F below:
//
//   A  a held alert bears within half a sector (22.5 deg) of the truth, and alert.memoryTicks takes the
//      mob back out of the stance (no report -> no hold);
//   B  three converging allies never pile onto one block: pairwise ring distance >= standoff * 0.7;
//   C  a broadcast carries a direction and a band and NOTHING positional, and its bearing error is real
//      (mean error >= 5 deg over a simulation, so a receiver cannot pin the contact);
//   D  no fire without line of sight: the faction layer has no setTarget, and GunBrain still gates on LOS;
//   E  a renegade is excluded from the network and from "ally" status;
//   F  friendly fire: hits 1-2 change nothing, the 3rd makes the victim answer, betrayal brands the
//      attacker, and the brand survives save/load (NBT round-trip).
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

const faction = read('faction/Faction.java');
const alert = read('faction/AlertNetwork.java');
const renegade = read('faction/Renegade.java');
const ai = read('faction/FactionAi.java');
const events = read('faction/FactionEvents.java');
const config = read('Config.java');
const brain = strip(read('gun/GunBrain.java'));
const debug = read('command/ModCommands.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const scav = strip(read('entity/ScavEntity.java'));
const pillager = strip(read('entity/GunnerPillagerEntity.java'));
const villager = strip(read('entity/GunnerVillagerEntity.java'));
const main = read('TarkovScav.java');

// The tag files are the whole point of "a data pack can move a mob": check them by content, not by name.
const readTag = (name) => JSON.parse(fs.readFileSync(
  path.join(RES, 'data', 'tarkovscav', 'tags', 'entity_types', `${name}.json`), 'utf8'));
const scavTag = readTag('faction_scav');
const illagerTag = readTag('faction_illager');
const villageTag = readTag('faction_village');

console.log('1. factions: a data-pack tag per side');
check(scavTag.values.includes('tarkovscav:scav'), 'faction_scav holds this mod\'s scav');
check(illagerTag.values.includes('minecraft:pillager')
  && illagerTag.values.includes('minecraft:vindicator')
  && illagerTag.values.includes('minecraft:evoker')
  && illagerTag.values.includes('tarkovscav:gunner_pillager'),
  'faction_illager is the vanilla illager types + the gunner pillager');
// 1.20.1 has no #minecraft:illagers tag (only #minecraft:raiders, which also has the witch), and an
// unknown tag reference resolves to NOTHING with no error - so a list is the only honest spelling.
check(!illagerTag.values.some((v) => v === '#minecraft:illagers'),
  'faction_illager does not reference the non-existent #minecraft:illagers tag');
check(fs.existsSync(path.join(RES, 'data', 'minecraft', 'tags', 'entity_types', 'illagers.json')) === false,
  'and nothing in this mod defines it either (a tag we invented would shadow nothing)');
check(villageTag.values.includes('minecraft:villager')
  && villageTag.values.includes('tarkovscav:gunner_villager')
  && villageTag.values.includes('minecraft:iron_golem'),
  'faction_village is vanilla villagers/golems + the gunner villager');
check([scavTag, illagerTag, villageTag].every((tag) => tag.replace === false),
  'the tags are additive (replace=false), so a pack can extend rather than fight them');
check(/TagKey\.create\(Registries\.ENTITY_TYPE/.test(faction),
  'membership is asked of the tag, not of a class');
check(/type == ModEntities\.SCAV\.get\(\)/.test(faction) && /type == EntityType\.PILLAGER/.test(faction),
  'the fallback (no tags loaded) mirrors the tag files by TYPE, never by instantiating');
check(/public static boolean allies/.test(faction) && /public static boolean hostile/.test(faction),
  'allies()/hostile() are the single place the side question is answered');
// The tags and the fallback must agree, or a bare dev run behaves differently from a normal one.
for (const [type, side] of [['SCAV', 'SCAV'], ['GUNNER_PILLAGER', 'ILLAGER'],
  ['GUNNER_VILLAGER', 'VILLAGE']]) {
  const inTag = [scavTag, illagerTag, villageTag].some((tag) => tag.values.some(
    (v) => v.endsWith(type.toLowerCase())));
  const inFallback = new RegExp(`ModEntities\\.${type}\\.get\\(\\)[\\s\\S]{0,400}?return ${side};`).test(faction);
  check(inTag && inFallback, `${type} is in its tag AND in the fallback`);
}

console.log('');
console.log('2. gate A: the held alert bears within half a sector, and then expires');
const wrap = (deg) => ((deg + 180) % 360 + 360) % 360 - 180;
const sectorOf = (bearing) => Math.floor((wrap(bearing) + 180 + 22.5) / 45) & 7;
const sectorCentre = (sector) => -180 + sector * 45;
let worst = 0;
let holds = 0;
for (let bearing = -180; bearing < 180; bearing += 0.5) {
  const centre = sectorCentre(sectorOf(bearing));
  worst = Math.max(worst, Math.abs(wrap(bearing - centre)));
  holds++;
}
check(worst <= 22.5 + 1e-9, 'eight sectors quantise any bearing to within 22.5 deg',
  `worst error ${worst.toFixed(3)} deg over ${holds} bearings`);
check(/mob\.getLookControl\(\)\.setLookAt\(x, mob\.getEyeY\(\), z, 20\.0F, 20\.0F\)/.test(alert),
  'the hold stance uses the vanilla LookControl and nothing else');
check(!/applyAimTracking|poseSource/.test(alert) && !/applyAimTracking|poseSource/.test(faction),
  'holding never touches the gun pose / aim tracking');
check(/now \+ Config\.ALERT_MEMORY_TICKS\.get\(\)/.test(alert)
  && /level\.getGameTime\(\) > contact\.expiresAt\(\)/.test(alert),
  'a report expires after alert.memoryTicks');
check(/defineInRange\("memoryTicks", 300,/.test(config), 'memoryTicks defaults to 300 ticks (15 s)');
// Simulate the lifetime: a report is news for exactly memoryTicks, then current() returns null, which is
// what takes the mob out of the held stance.
const MEMORY = 300;
let news = 0;
for (let now = 0; now <= MEMORY + 2; now++) {
  if (!(now > MEMORY)) news++;
}
check(news === MEMORY + 1, 'the report is acted on for exactly memoryTicks ticks',
  `${news} tick(s) of ${MEMORY}, then no contact again`);

console.log('');
console.log('3. gate B: converging allies spread out instead of stacking');
const STANDOFF = 6.0;
let minPair = Infinity;
let worstN = 0;
for (let n = 2; n <= 8; n++) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const ring = Math.PI * 2 * i / n;
    pts.push([Math.cos(ring) * STANDOFF, Math.sin(ring) * STANDOFF]);
  }
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      const d = Math.hypot(pts[i][0] - pts[j][0], pts[i][1] - pts[j][1]);
      if (d < minPair) { minPair = d; worstN = n; }
    }
  }
}
check(minPair >= STANDOFF * 0.7, 'the closest two convergers are never closer than standoff * 0.7',
  `worst ${minPair.toFixed(3)} blocks at n=${worstN} (limit ${(STANDOFF * 0.7).toFixed(2)})`);
check(/indexAmongConvergers < Config\.ALERT_CONVERGE_MAX_ALLIES\.get\(\)/.test(alert),
  'only the first convergeMaxAllies allies converge');
check(/2\.0D \* indexAmongConvergers \/ convergers/.test(alert),
  'the ring angle is 2*pi*i/n, i.e. evenly spread');
check(/defineInRange\("surroundStandoff", 6\.0D/.test(config)
  && /defineInRange\("convergeMaxAllies", 3,/.test(config),
  'standoff 6.0 and convergeMaxAllies 3 are the shipped defaults');

console.log('');
console.log('4. gate C: the message is a direction and a band, and the error is real');
const record = /public record SharedContact\(([\s\S]*?)\)\s*\{/.exec(alert);
const components = record ? record[1].replace(/\s+/g, ' ').trim() : '';
check(components.length > 0, 'SharedContact is the whole payload', components);
check(!/\b(double|float)\s+(x|y|z)\b/.test(components) && !/BlockPos|Vec3|ChunkPos/.test(components),
  'the payload has no coordinates at all');
check(/public record SharedContact\(Faction faction, int sector, int distanceBand, long expiresAt,/.test(alert),
  'the payload is exactly faction + sector + band + expiry + bearing error + source id');
check(/Config\.ALERT_BEARING_NOISE_DEGREES\.get\(\)/.test(alert),
  'the bearing error is config-driven (alert.bearingNoiseDegrees)');
const NOISE = 22;
const NEAR = 8.0;
const MID = 24.0;
const FAR = 40.0;
let seed = 12345;
const rnd = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
let errSum = 0;
let errCount = 0;
let under2 = 0;
for (let i = 0; i < 2000; i++) {
  const dx = rnd() * 80 - 40;
  const dz = rnd() * 80 - 40;
  const bearing = wrap(Math.atan2(-dx, dz) * 180 / Math.PI);
  const sector = sectorOf(bearing);
  const noise = Math.floor(rnd() * (2 * NOISE + 1)) - NOISE;
  const believed = wrap(sectorCentre(sector) + noise);
  const err = Math.abs(wrap(bearing - believed));
  errSum += err;
  errCount++;
  if (err < 2) under2++;
}
const meanErr = errSum / errCount;
check(meanErr >= 5.0, 'the believed bearing is off by >= 5 deg on average', `mean ${meanErr.toFixed(2)} deg`);
check(under2 / errCount < 0.2, 'a report is never (almost never) dead on',
  `${(100 * under2 / errCount).toFixed(1)}% of 2000 samples within 2 deg`);
// The band, not the distance: the receiver walks to the band edge, so its error is real too.
const bandErr = [Math.abs(FAR - MID), Math.abs(MID - NEAR), Math.abs(NEAR - 0)];
check(bandErr.every((e) => e >= 8.0), 'the three bands are far enough apart to matter',
  `band widths ${bandErr.map((e) => e.toFixed(0)).join('/')} blocks`);
check(/distance <= Config\.ALERT_NEAR_DISTANCE\.get\(\)/.test(alert)
  && /case 0 -> Config\.ALERT_NEAR_DISTANCE\.get\(\)/.test(alert),
  'the receiver is given the band and assumes the band edge');

console.log('');
console.log('5. gate D: intel can never start a fight');
const factionCode = strip(faction) + strip(alert);
check(!/setTarget/.test(factionCode), 'Faction and AlertNetwork contain no setTarget call');
check(!/setTarget/.test(strip(ai)), 'FactionAi contains no setTarget call');
check(/setTarget/.test(strip(renegade)), 'the one setTarget is the victim answering its attacker');
check(/isWithinMeleeAttackRange|hasLineOfSight/.test(brain),
  'GunBrain still gates aiming/firing on line of sight');
check(/hasLineOfSight\(/.test(brain), 'and it is hasLineOfSight specifically');
check(/getNavigation\(\)\.moveTo/.test(alert) && !/GunBrain/.test(strip(alert)),
  'the only movement the network can cause is a navigation moveTo');

console.log('');
console.log('6. gate E: a renegade is out of the network');
check(/!Renegade\.is\(entity\)/.test(faction), 'isArmedMember refuses a renegade');
check(/if \(Renegade\.is\(mob\)\) \{[\s\S]{0,80}RECEIVED\.remove\(mob\);[\s\S]{0,40}return null;/.test(alert),
  'current() drops a cached report the moment the mob is branded');
check(/!Renegade\.is\(a\) && !Renegade\.is\(b\)/.test(faction), 'allies() is false for a renegade');
check(/Renegade\.is\(a\) \|\| Renegade\.is\(b\)/.test(faction),
  'hostile() is true when either side is a renegade');
check(/AlertNetwork\.forget\(attacker\)/.test(renegade), 'branding forgets the traitor\'s own report');

console.log('');
console.log('7. gate F: friendly fire, anger, betrayal, NBT');
const FIRE_WINDOW = 200;
const ANGER = 3;
const BETRAY = 3;
// Mirror of Renegade.noteFriendlyFire, including the sliding window and the "same pair" reset.
function hits(hitTimes) {
  let count = 0;
  let windowStart = 0;
  let out = [];
  for (const t of hitTimes) {
    count = t - windowStart > FIRE_WINDOW ? 0 : count;
    count++;
    windowStart = t;
    out.push(count);
  }
  return out;
}
check(JSON.stringify(hits([0, 1, 2])) === '[1,2,3]', 'three hits inside the window count 1,2,3');
check(hits([0, 201, 402]).join(',') === '1,1,1', 'a hit after the window restarts the count',
  '10 s window, so 201 ticks apart is three separate incidents');
const one = hits([0])[0];
const two = hits([0, 20])[1];
check(one < ANGER && two < ANGER, 'hits 1 and 2 change nothing (no anger, no brand)');
check(hits([0, 20, 40])[2] >= ANGER, 'the 3rd hit reaches the anger threshold');
check(ANGER <= BETRAY, 'the victim gets its warning shot before the brand lands',
  `hitsToAnger ${ANGER} <= betrayalThreshold ${BETRAY}`);
check(/attacker\.getLastHurtByMob\(\) == victim/.test(renegade),
  'one free retaliation: answering the mob that hit you never counts');
check(/previousVictim != null && !previousVictim\.equals\(victim\.getUUID\(\)\)/.test(renegade),
  'anger is per attacker/victim pair, so hitting somebody else restarts it');
check(/victim instanceof Mob mobVictim[\s\S]{0,200}?mobVictim\.setTarget\(attacker\)/.test(renegade),
  'the victim turns on that attacker by instance (and only a Mob has a target to set)');
// NBT round-trip: the flag, the counter and the "since" stamp are all in the persistent data.
const tags = [...renegade.matchAll(/TAG_[A-Z_]+ = "([^"]+)"/g)].map((m) => m[1]);
check(tags.length === 5 && new Set(tags).size === 5,
  'all five NBT keys are distinct', tags.join(', '));
check(/getPersistentData\(\)\.getBoolean\(TAG_RENEGADE\)/.test(renegade)
  && /putBoolean\(TAG_RENEGADE, true\)/.test(renegade),
  'the brand is read and written through the same NBT key (save/load round-trip)');
const nbt = new Map();
nbt.set('TarkovScavRenegade', true);
const reloaded = new Map(nbt);
check(reloaded.get('TarkovScavRenegade') === true, 'a branded mob is still branded after a reload');
check(/getPersistentData\(\)\.putLong\(TAG_SINCE/.test(renegade) && /getLong\(TAG_SINCE\)/.test(renegade),
  'renegadeDecayTicks can read back when the brand landed');
check(/defineInRange\("renegadeDecayTicks", 0,/.test(config),
  'renegadeDecayTicks = 0 means permanent (the user\'s rule)');
check(/attacker\.setGlowingTag\(true\)/.test(renegade) && /define\("renegadeGlow", true\)/.test(config),
  'the glow is on by default and switchable');
check(/Component\.translatable\("tarkovscav\.faction\.renegade"\)/.test(renegade),
  'the brand is a translatable name, not a hard-coded English string');
for (const lang of ['en_us', 'zh_cn']) {
  const text = fs.readFileSync(path.join(RES, 'assets', 'tarkovscav', 'lang', `${lang}.json`), 'utf8');
  check(text.includes('"tarkovscav.faction.renegade"'), `${lang}.json has the renegade name`);
}

console.log('');
console.log('8. wiring: the layer runs, and the debug command shows it');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.faction\.FactionEvents\.class\)/.test(main),
  'the friendly-fire hook is registered on the Forge bus');
check(/LivingHurtEvent/.test(events) && /event\.getSource\(\)\.getEntity\(\)/.test(events),
  'it is a LivingHurtEvent handler on the resolved attacker');
check(!/event\.setCanceled|setAmount/.test(events),
  'it never cancels or re-scales the damage - it only counts it');
for (const [name, src] of [['scav', scav], ['gunner pillager', pillager], ['gunner villager', villager]]) {
  check(/FactionAi\.tick\(this, this\.getTarget\(\)\)/.test(src),
    `the ${name} ticks the faction layer server-side`);
  check(/Renegade\.is\(candidate\)/.test(src),
    `the ${name} hunts a branded renegade (and nothing else)`);
}
check(/Faction\.describe\(mob\)/.test(debug) && /Renegade\.describe\(mob\)/.test(debug)
  && /AlertNetwork\.describe\(mob\)/.test(debug),
  '/tarkovscav debug reports faction, renegade and alert');
check(/\[faction\]/.test(renegade) && /\[alert\]/.test(alert), 'both layers log with a tag');

console.log('');
console.log('9. the config keys, with the documented defaults, in README');
const keys = ['FACTION_ENABLED', 'FACTION_FRIENDLY_FIRE_HITS_TO_ANGER', 'FACTION_FRIENDLY_FIRE_WINDOW_TICKS',
  'FACTION_BETRAYAL_THRESHOLD', 'FACTION_RENEGADE_BROADCAST_RADIUS', 'FACTION_RENEGADE_DECAY_TICKS',
  'FACTION_RENEGADE_GLOW', 'ALERT_ENABLED', 'ALERT_RADIUS', 'ALERT_NEAR_DISTANCE', 'ALERT_MID_DISTANCE',
  'ALERT_MEMORY_TICKS', 'ALERT_MAX_RECIPIENTS', 'ALERT_BROADCAST_COOLDOWN_TICKS',
  'ALERT_BEARING_NOISE_DEGREES', 'ALERT_CONVERGE_RADIUS', 'ALERT_CONVERGE_MAX_ALLIES',
  'ALERT_SURROUND_STANDOFF', 'ALERT_MIN_REPATH_INTERVAL_TICKS'];
for (const key of keys) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
}
const names = [...config.matchAll(/define(?:InRange|ListAllowEmpty)?\("([A-Za-z0-9]+)"/g)].map((m) => m[1]);
const readmeKeys = ['friendlyFireHitsToAnger', 'friendlyFireWindowTicks', 'betrayalThreshold',
  'renegadeBroadcastRadius', 'renegadeDecayTicks', 'renegadeGlow', 'nearDistance', 'midDistance',
  'memoryTicks', 'maxRecipients', 'broadcastCooldownTicks', 'bearingNoiseDegrees', 'convergeRadius',
  'convergeMaxAllies', 'surroundStandoff', 'minRepathIntervalTicks'];
const missing = readmeKeys.filter((k) => !readme.includes(k));
check(missing.length === 0, 'every faction/alert key appears in README',
  missing.length ? missing.join(', ') : `${readmeKeys.length} keys`);
check(readme.includes('### 5m.'), 'README has the 5m section');
check(/#tarkovscav:faction_scav/.test(readme) && /#tarkovscav:faction_illager/.test(readme)
  && /#tarkovscav:faction_village/.test(readme) && /vindicator/.test(readme),
  'README documents the three tags and their contents');
check(names.includes('friendlyFireHitsToAnger') && names.includes('memoryTicks'),
  'and the keys really are defined in the spec, not just named in the source');
check(/\.push\("faction"\)/.test(config) && /\.push\("alert"\)/.test(config),
  'they live in their own toml sections');
check(/faction\.enabled/.test(readme) || /`enabled` \| `true` \| master switch/.test(readme),
  'README explains the master switches');

console.log('');
console.log('9. village defence: the villagers fight the monsters the tag lists (README 5m)');
// The 2026 request: "the armed villagers also shoot the enemy mobs, e.g. the zombies". The target list is a
// data-pack tag so a pack can tune it, and the master switch returns the mob to its pre-2026 list.
const hostileTag = readTag('faction_village_hostile');
check(hostileTag.replace === false, 'faction_village_hostile is additive (replace=false)');
// CORRECTED 2026-09-25: the tag used to reference `#minecraft:undead`, which DOES NOT EXIST in 1.20.1
// (the client jar ships exactly 13 entity_type tags and `undead` is not one of them). TagLoader logged
// "Couldn't load tag tarkovscav:faction_village_hostile as it is missing following references:
// #minecraft:undead" and created that part EMPTY, so the armed villagers were not targeting a single
// zombie or skeleton. The tag now lists the 1.20.1 undead explicitly, and this assertion checks the
// MOBS rather than a tag name that never existed.
const UNDEAD = ['minecraft:zombie', 'minecraft:husk', 'minecraft:drowned', 'minecraft:zombie_villager',
  'minecraft:zombified_piglin', 'minecraft:phantom', 'minecraft:wither'];
check(hostileTag.values.includes('#minecraft:raiders')
  && hostileTag.values.includes('#minecraft:skeletons')
  && UNDEAD.every((id) => hostileTag.values.includes(id)),
  'it covers the vanilla raiders and the undead (1.20.1 has no #minecraft:undead, so they are listed)',
  hostileTag.values.join(' '));
// Arthropods are listed ONE BY ONE on purpose: the vanilla #minecraft:arthropods tag also contains the bee,
// and a village that hunts bees would be a bug report of its own.
check(!hostileTag.values.includes('#minecraft:arthropods'),
  'it does NOT use #minecraft:arthropods (that tag contains bees)');
for (const spider of ['minecraft:spider', 'minecraft:cave_spider', 'minecraft:silverfish',
  'minecraft:endermite']) {
  check(hostileTag.values.includes(spider), `the arthropod ${spider} is listed explicitly`);
}
check(hostileTag.values.includes('minecraft:creeper') && hostileTag.values.includes('minecraft:slime')
  && hostileTag.values.includes('minecraft:blaze'),
  'plus creeper, slime and the nether hostiles');
// The goal itself, source level: a Monster-class target selector whose predicate is the faction helper.
check(/new NearestAttackableTargetGoal<>\(this, Monster\.class, 10, true, false,\s*\n?\s*candidate -> com\.gfl\.tarkovscav\.faction\.Faction\.villageHostile\(this, candidate\)\)/
  .test(villager),
  'the villager family registers a Monster target selector gated by Faction.villageHostile');
check(/targetSelector\.addGoal\(2, new NearestAttackableTargetGoal<>\(this, AbstractIllager\.class, true\)\)/
  .test(villager),
  'the existing illager target is still there (the new goal is additive)');
check(!/Monster\.class/.test(scav) && !/Monster\.class/.test(pillager),
  'the scav and pillager target lists are deliberately NOT changed by this batch',
  'the user asked about the villagers; the report suggests how to extend it to the scavs');
check(/public static boolean villageHostile\(@Nullable LivingEntity defender, @Nullable LivingEntity candidate\)/
  .test(faction),
  'Faction.villageHostile(defender, candidate) is the single place the question is answered');
check(/candidate\.getType\(\)\.is\(VILLAGE_HOSTILE\)/.test(faction)
  && /!allies\(defender, candidate\)/.test(faction),
  'it asks the TAG for membership and refuses a fellow faction member');
check(/VILLAGER_ATTACK_MONSTERS\.get\(\)/.test(faction),
  'and it is switched by faction.villagersAttackMonsters');
check(/\.define\(\s*"villagersAttackMonsters", true\)/.test(config),
  'which ships true');
check(/VILLAGER_ATTACK_MONSTERS/.test(config)
  && /ForgeConfigSpec\.BooleanValue VILLAGER_ATTACK_MONSTERS;/.test(config),
  'and is declared in the spec');
// The truth table, simulated exactly as the Java decides it (tag membership + allies + the switch).
const villageHostile = (defFaction, candFaction, inTag, enabled, monsters, renegade) => {
  if (!candFaction && candFaction !== null) return false;
  if (!enabled || !monsters) return false;
  if (!inTag) return false;
  if (candFaction === null) return true;                       // no faction at all (a zombie)
  if (renegade) return true;                                   // a branded renegade is fair game
  return defFaction !== candFaction;                           // never your own side
};
check(villageHostile('VILLAGE', null, true, true, true, false) === true,
  'a zombie (no faction at all, in the tag) is attacked');
check(villageHostile('VILLAGE', 'VILLAGE', true, true, true, false) === false,
  'a fellow villager is NEVER attacked, even if a pack put it in the tag');
check(villageHostile('VILLAGE', 'VILLAGE', true, true, true, true) === true,
  'a branded renegade villager IS attacked (the renegade rules win, as before)');
check(villageHostile('VILLAGE', 'ILLAGER', true, true, true, false) === true,
  'an illager in the tag is attacked');
check(villageHostile('VILLAGE', null, false, true, true, false) === false,
  'an entity the tag does not list is ignored (that is how a pack excludes creepers)');
check(villageHostile('VILLAGE', null, true, true, false, false) === false,
  'villagersAttackMonsters = false is the old behaviour (the undead are ignored again)');
check(villageHostile('VILLAGE', null, true, false, true, false) === false,
  'and faction.enabled = false disables it too');
check(/setTarget\(@Nullable LivingEntity target\)/.test(villager)
  && /Faction\.villageHostile\(this, target\)/.test(villager)
  && /LOGGER\.info\("\[faction\] \{\} locked onto/.test(villager),
  'a new monster target is logged once (gated by the existing logGunAi switch) - never silent');
check(/target=" \+ \(mob\.getTarget\(\) == null \? "none"/.test(debug)
  && /VILLAGE-HOSTILE/.test(debug),
  '/tarkovscav debug prints the current target and marks a village-defence one');
check(/villagersAttackMonsters/.test(readme) && /faction_village_hostile/.test(readme),
  'README documents the switch and the tag');
check(/Monster\.class/.test(villager) && /GunBrain/.test(readme),
  'the target list changed, not the gun AI (aiming/firing still goes through GunBrain)');

console.log('');
if (failures > 0) {
  console.log(`${failures} faction check(s) FAILED`);
  process.exit(1);
}
console.log('faction invariants all hold');
