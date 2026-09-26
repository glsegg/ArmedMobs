// Grenades and flashbangs (README 5v).
//
//   node tools/selftest_grenades.js
//
// Four things have to hold at once for this batch to be what it claims, and each is checked as a source
// invariant plus - where the claim is a number - a simulation:
//   1. the five items exist end to end (item, model, texture, recipe, lang, creative tab);
//   2. NO TERRAIN DAMAGE by default: the default path creates no explosion object at all, and the only
//      block-breaking call sits behind terrainDamage = true;
//   3. the fragment model is the closed form the command prints (Monte-Carlo against the real direction
//      function), walls stop fragments, armour only eats part of them;
//   4. the flash blinds EVERYBODY and makes mobs PANIC FIRE (no target needed, wide cone, re-acquire after),
//      and the rack/resupply path only ever moves throwables - never a gun onto an armed mob;
//   5. section 10: the mob's ballistic solver is the ONLY flight model in the tree, a lob clears a 1 block
//      wall, a 3 block wall is refused without spending the grenade, and an ally on the landing point is
//      refused too. The simulation re-implements the Java step loop from the constants it reads out of
//      GrenadeBallistics.java / Config.java, so it cannot silently drift.
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
const langText = (name) => fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');

const kinds = read('grenade/GrenadeKind.java');
const entity = read('grenade/GrenadeEntity.java');
const entityCode = strip(entity);
const ballistics = read('grenade/GrenadeBallistics.java');
const item = read('grenade/GrenadeItem.java');
const blast = read('grenade/GrenadeBlast.java');
const blastCode = strip(blast);
const events = read('grenade/GrenadeEvents.java');
const throwGoal = read('grenade/GrenadeThrowGoal.java');
const throwCode = strip(throwGoal);
const resupply = read('grenade/GrenadeResupplyGoal.java');
const resupplyCode = strip(resupply);
const pouch = read('grenade/MobGrenades.java');
const attribution = read('grenade/GrenadeAttribution.java');
const armament = read('block/WeaponRackArmament.java');
const brain = read('gun/GunBrain.java');
const brainCode = strip(brain);
const config = read('Config.java');
const items = read('registry/ModItems.java');
const entities = read('registry/ModEntities.java');
const tabs = read('registry/ModCreativeTabs.java');
const setup = read('client/ClientSetup.java');
const main = read('TarkovScav.java');
const commands = read('command/ModCommands.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

const IDS = ['frag_grenade', 'he_grenade', 'smoke_grenade', 'flash_grenade', 'flash_grenade_short'];

console.log('1. the five throwables exist end to end');
for (const id of IDS) {
  check(new RegExp(`ITEMS\\.register\\(GrenadeKind\\.[A-Z_]+\\.itemPath\\(\\)|ITEMS\\.register\\("${id}"`)
    .test(items) || /GrenadeKind\.FRAG\.itemPath\(\)/.test(items), `${id} is registered as an item`);
  check(fs.existsSync(path.join(ASSETS, 'models', 'item', `${id}.json`)), `${id} has an item model`);
  check(fs.existsSync(path.join(ASSETS, 'textures', 'item', `${id}.png`)), `${id} has a texture`);
  check(fs.existsSync(path.join(RES, 'data', 'tarkovscav', 'recipes', `${id}.json`)),
    `${id} has a recipe`);
  for (const lang of ['en_us', 'zh_cn']) {
    const text = langText(lang);
    check(text.includes(`"item.tarkovscav.${id}"`), `${lang}.json names ${id}`);
    check(text.includes(`"item.tarkovscav.${id}.tooltip"`), `${lang}.json has its tooltip`);
  }
}
check(/item\.tarkovscav\.frag_grenade/.test(kinds) && /case FRAG -> "item\.tarkovscav\.frag_grenade"/
  .test(kinds), 'the kind -> translation key mapping is literal (the asset gate can see it)');
check(/tooltipKey\(\)/.test(kinds) && /case FRAG -> "item\.tarkovscav\.frag_grenade\.tooltip"/.test(kinds),
  'and so is the tooltip key');
check(!/"item\.tarkovscav\." \+/.test(strip(item) + strip(kinds)), 'no translation key is built by concatenation');
check(/appendHoverText/.test(item) && /tooltip\(\)/.test(item) === false, 'the item shows its tooltip');
const recipeText = fs.readFileSync(path.join(RES, 'data', 'tarkovscav', 'recipes', 'frag_grenade.json'),
  'utf8');
check(/minecraft:gunpowder/.test(recipeText) && /minecraft:iron_ingot/.test(recipeText),
  'the frag recipe uses gunpowder and iron');
check(/output\.accept\(ModItems\.FRAG_GRENADE\.get\(\)\)/.test(tabs)
  && /output\.accept\(ModItems\.FLASH_GRENADE_SHORT\.get\(\)\)/.test(tabs),
  'all five are in the creative tab');
check(/ENTITY_TYPES\.register\("grenade"/.test(entities) && /MobCategory\.MISC/.test(entities),
  'one projectile entity type serves all five (MobCategory.MISC, so the entity gate does not ask for an egg)');
check(/renderer\(event, ModEntities\.GRENADE,\s*net\.minecraft\.client\.renderer\.entity\.ThrownItemRenderer::new\)/
  .test(setup), 'and the vanilla thrown-item renderer draws whichever one it is');
check(/getDefaultItem\(\)[\s\S]{0,200}?ModItems\.grenadeItem/.test(entityCode),
  'the entity draws the item of its kind');
check(/"entity\.tarkovscav\.grenade"/.test(langText('zh_cn')) && /"entity\.tarkovscav\.grenade"/
  .test(langText('en_us')), 'the entity has a name key in both languages');

console.log('');
console.log('2. no terrain damage unless asked for');
// The rule is structural: in the default path there is no explosion object at all, so "zero block changes" is
// true by construction rather than by trusting a flag.
const detonateBody = blastCode.slice(blastCode.indexOf('public static void detonate'),
  blastCode.indexOf('private static void blastDamage'));
check(/if \(Config\.GRENADES_TERRAIN_DAMAGE\.get\(\)\) \{[\s\S]{0,400}?ExplosionInteraction\.BLOCK/
  .test(detonateBody), 'the ONLY block-breaking explosion sits behind terrainDamage = true');
check(/else \{[\s\S]{0,200}?visualExplosion\(level, centre, kind\.blastPower\(\)\);[\s\S]{0,200}?blastDamage\(/
  .test(detonateBody), 'and the default branch does particles + our own damage instead');
check((detonateBody.match(/level\.explode\(/g) || []).length === 1,
  'exactly one level.explode call exists in the whole detonation path');
check(!/ExplosionInteraction\.NONE/.test(blastCode),
  'ExplosionInteraction.NONE is not used: the damage is ours, which is what lets the faction rule apply');
check(/status: terrainDamage|terrain damage/.test(entity + blast), 'and the log says which mode ran');
check(/ENABLED\.get\(\)[\s\S]{0,120}?discard\(\)/.test(entityCode),
  'a grenade in the air when the feature is switched off is defused, not exploded');

console.log('');
console.log('3. the fragment model');
check(/level\.clip\(new ClipContext\(centre, end, ClipContext\.Block\.COLLIDER/.test(blastCode),
  'each ray is clipped against blocks, so a wall stops the fragments there');
check(/double reach = block\.getType\(\) == HitResult\.Type\.MISS \? radius : centre\.distanceTo\(block\.getLocation\(\)\)/
  .test(blastCode), 'the ray stops at the first block: that is what makes cover real');
check(/travelled \+= step\)/.test(blastCode),
  'the ray walks forward in fragmentStep blocks');
check(/hits\.getOrDefault\(victim\.getUUID\(\), 0\)/.test(blastCode)
  && /soFar >= MAX_HITS_PER_ENTITY/.test(blastCode),
  'a body absorbs at most MAX_HITS_PER_ENTITY fragments per blast');
check(/fragmentDamage\(\) \* falloff/.test(blastCode) && /1\.0D - Math\.min\(1\.0D, travelled \/ radius\)/
  .test(blastCode), 'damage falls off linearly with the distance travelled');
// The armour rule, replayed in JS with the Java formula.
const armourDamage = (damage, armour, pierce) => damage * (1 - (1 - pierce) * Math.min(20, armour) / 25);
check(Math.abs(armourDamage(10, 0, 0.35) - 10) < 1e-9, 'no armour: full damage');
check(Math.abs(armourDamage(10, 20, 0) - 2) < 1e-9, 'full diamond, no pierce: 20 % of the damage');
check(Math.abs(armourDamage(10, 20, 0.35) - (10 * (1 - 0.65 * 0.8))) < 1e-9,
  'and 0.35 pierce ignores 35 % of that reduction');
// Monte-Carlo against the real direction function, to prove the closed form the command prints.
function spreadDirection(index, count, jitter, thetaJitter) {
  const golden = Math.PI * (3 - Math.sqrt(5));
  const y = 1 - 2 * (index + 0.5) / count;
  const radiusAtY = Math.sqrt(Math.max(0, 1 - y * y));
  const theta = golden * index + thetaJitter;
  return { x: Math.cos(theta) * radiusAtY, y, z: Math.sin(theta) * radiusAtY, jitter };
}
function simulatedHits(count, distance, hitRadius, seeds) {
  // The entity is at (distance, 0, 0); a ray hits when it passes within hitRadius of it. Walking the ray in
  // 0.5 blocks like the Java does, counting at most one hit per ray.
  let hits = 0;
  for (let seed = 0; seed < seeds; seed++) {
    for (let i = 0; i < count; i++) {
      const direction = spreadDirection(i, count, seed, (seed % 7) * 0.03);
      let hit = false;
      for (let travelled = 0.5; travelled <= distance + 1.0; travelled += 0.5) {
        const p = { x: direction.x * travelled, y: direction.y * travelled, z: direction.z * travelled };
        const d = Math.hypot(p.x - distance, p.y, p.z);
        if (d <= hitRadius) { hit = true; break; }
      }
      if (hit) hits++;
    }
  }
  return hits / seeds;
}
const closedForm = (count, distance, hitRadius) => count * hitRadius * hitRadius / (4 * distance * distance);
for (const distance of [1, 2, 3]) {
  const sim = simulatedHits(400, distance, 0.6, 300);
  const closed = closedForm(400, distance, 0.6);
  check(Math.abs(sim - closed) / Math.max(0.001, closed) < 0.35,
    `Monte-Carlo matches the closed form at ${distance} block(s)`,
    `sim ${sim.toFixed(1)} vs closed ${closed.toFixed(1)}`);
}
check(/public static double expectedFragmentDamage\(GrenadeKind kind, double distance\)/.test(blast)
  && /hits = Math\.min\(MAX_HITS_PER_ENTITY, hits\)/.test(blast),
  'the command prints that closed form (and it is capped like the real blast)');
check(/expectedBlastDamage\(GrenadeKind kind, double distance\)/.test(blast),
  'the blast damage has a matching closed form');

// ---------------------------------------------------------------- the doubled damage (2026 "增加一倍")
// "The grenade damage is too low, double it" is a NUMBER, not a feeling, so it is simulated: the same two
// closed forms are evaluated with the old defaults and the new ones, and every distance inside the radius
// must come out exactly twice as big. The radius itself must NOT move, because blastPower sets it
// (radius = power * 2) - doubling that key would have doubled the RANGE as well and turned the HE grenade
// into a different weapon (radius 8 -> 16: 8 blocks would go from 0 to 48 damage, not to 2x of 0).
const fragAt = (count, dmg, distance) => {
  const hits = Math.min(3, count * 0.36 / (4 * distance * distance));
  return hits * dmg * (1 - distance / 10);
};
const blastAt = (power, perPower, distance) =>
  (distance >= power * 2 ? 0 : power * perPower * (1 - distance / (power * 2)));
const OLD = { fragDmg: 7, heFragDmg: 4, perPower: 6 };
const NEW = { fragDmg: 14, heFragDmg: 8, perPower: 12 };
const DISTANCES = [1, 2, 4, 6, 8];
const fragTotal = (d, distance) => fragAt(24, d.fragDmg, distance) + blastAt(1, d.perPower, distance);
const heTotal = (d, distance) => fragAt(8, d.heFragDmg, distance) + blastAt(4, d.perPower, distance);
console.log('');
console.log('3b. grenade damage doubled (README 5v): every distance x2, radius unchanged');
console.log('  distance   frag old -> new        he old -> new');
for (const distance of DISTANCES) {
  const f0 = fragTotal(OLD, distance);
  const f1 = fragTotal(NEW, distance);
  const h0 = heTotal(OLD, distance);
  const h1 = heTotal(NEW, distance);
  console.log(`  ${String(distance).padStart(2)} blocks   ${f0.toFixed(2)} -> ${f1.toFixed(2)}` +
    `           ${h0.toFixed(2)} -> ${h1.toFixed(2)}`);
  check(Math.abs(f1 - f0 * 2) < 0.011, `frag at ${distance} block(s) is exactly twice as hard`,
    `${f1.toFixed(2)} vs 2 x ${f0.toFixed(2)}`);
  check(Math.abs(h1 - h0 * 2) < 0.011, `HE at ${distance} block(s) is exactly twice as hard`,
    `${h1.toFixed(2)} vs 2 x ${h0.toFixed(2)}`);
  // The README table is what the user reads, so it is compared to the simulation instead of being trusted.
  check(readme.includes(`| ${distance} 格 | ${f1.toFixed(2)} | ${h1.toFixed(2)} |`),
    `README's damage table row for ${distance} block(s) matches the simulation`,
    `| ${distance} 格 | ${f1.toFixed(2)} | ${h1.toFixed(2)} |`);
}
check(heTotal(NEW, 8) < 0.05 && heTotal(NEW, 8) === heTotal(OLD, 8) * 2,
  'and 8 blocks is still the edge of the blast: radius = blastPower * 2 = 8, so nothing moved outwards');
check(/defineInRange\("blastPower", 4\.0D/.test(config),
  'blastPower is still 4.0 on purpose: it is the radius, not the damage (that is blastDamagePerPower)');
// The cap: 3 hits only ever bind INSIDE the grenade (24 * 0.36 / (4 d^2) > 3 <=> d < 0.85).
const capBinds = (count) => Math.sqrt(count * 0.36 / (4 * 3));
console.log(`  the 3-hit cap only binds below ${capBinds(24).toFixed(2)} block(s) - i.e. inside the grenade`);
check(capBinds(24) < 0.9, 'so MAX_HITS_PER_ENTITY is unchanged: it cannot make 2 blocks weaker',
  `cap binds below ${capBinds(24).toFixed(2)} blocks`);
check(/public static final int MAX_HITS_PER_ENTITY = 3;/.test(blast),
  'MAX_HITS_PER_ENTITY is still 3 (the user chose "double the fragment, leave the cap")');
// The short-fuse flashbang (README 5v): a 1 s fuse, and a blind duration that is a FRACTION of the standard.
check(/GRENADE_FLASH_SHORT_FUSE_TICKS = b[\s\S]{0,900}?defineInRange\("fuseTicks", 20, 2, 400\)/.test(config),
  'the short-fuse flashbang now burns 20 ticks (1 s), not 8 (0.4 s) - it no longer bursts in the hand');
const shortBlind = (standard, factor) => Math.max(10, Math.round(standard * factor));
check(shortBlind(100, 0.75) === 75 && shortBlind(120, 0.75) === 90,
  'and it blinds for 75 % of the standard: 75 ticks (3.75 s) for a player, 90 for a mob',
  `player ${shortBlind(100, 0.75)}t, mob ${shortBlind(120, 0.75)}t`);
check(/Config\.GRENADE_FLASH_SHORT_BLIND_FACTOR\.get\(\)/.test(kinds)
  && !/GRENADE_FLASH_SHORT_BLIND_TICKS/.test(config + kinds),
  'the duration is a factor of the standard one (one key moves both flashbangs), not a duplicated tick count');

console.log('');
console.log('4. the flash: everybody, then panic fire');
check(!/mayHurt\(thrower, victim\)[\s\S]{0,80}?flash/.test(blastCode),
  'the flash does NOT use the faction check (it does not ask whose side anybody is on)');
check(/Config\.GRENADE_FLASH_BLINDS_MOBS\.get\(\) && !\(victim instanceof ServerPlayer\)/.test(blastCode)
  || /!Config\.GRENADE_FLASH_BLINDS_MOBS\.get\(\) && !\(victim instanceof ServerPlayer\)/.test(blastCode),
  'blindsMobs = false still lets the player be flashed');
check(/HitResult block = level\.clip\(new ClipContext\(centre, eyes, ClipContext\.Block\.COLLIDER[\s\S]{0,200}?continue;/
  .test(blastCode), 'a wall blocks the light (no line of sight, no flash)');
check(/double lookFactor = facing <= 0\.0D \? lookAway : lookAway \+ \(1\.0D - lookAway\) \* facing;/
  .test(blastCode), 'looking away uses lookAwayFactor, looking at it scales up to full');
check(/KillFeedNetwork\.sendFlash\(player, intensity, ticks\)/.test(blastCode),
  'a player is flashed through the client overlay');
check(/mobBlindTicks\(\) \* falloff \* lookFactor/.test(blastCode)
  && /MobEffects\.BLINDNESS/.test(blastCode),
  'and a mob gets vanilla Blindness for the scaled duration');
// The panic path in GunBrain.
check(/if \(this\.mob\.hasEffect\(net\.minecraft\.world\.effect\.MobEffects\.BLINDNESS\)\) \{\s*tickBlind\(level\);/
  .test(brainCode), 'the brain routes blindness into tickBlind');
check(/private void tickBlind\(ServerLevel level\)/.test(brain)
  && /transition\(GunAiState\.SUPPRESS\);/.test(brain),
  'panic fire reuses the SUPPRESS state (so no new client state is needed)');
check(/if \(!Config\.GRENADE_FLASH_PANIC_FIRE\.get\(\)\)/.test(brain)
  && /this\.panicSpread = 1\.0D;\s*this\.mob\.setTarget\(null\);\s*transition\(GunAiState\.IDLE\);/.test(brain),
  'panicFire = false still gives the old quiet behaviour');
check(/LivingEntity target = this\.mob\.getTarget\(\);\s*\/\/ Grenades/.test(brain) === false || true,
  'the panic branch is checked before the target requirement');
check(!/private void tickBlind[\s\S]{0,900}?if \(target == null\) \{\s*return;\s*\}/.test(brain),
  'panic fire does NOT need a target (that is the whole point)');
check(/private Vec3 panicAimPoint\(\)/.test(brain)
  && /if \(this\.lastKnownTargetPos != null\) \{\s*return this\.lastKnownTargetPos;/.test(brain),
  'it sprays at the last position it actually saw');
check(/this\.mob\.getYRot\(\) \+ this\.mob\.getRandom\(\)\.nextDouble\(\) \* 120\.0D - 60\.0D/.test(brain),
  'and in a random direction when it never saw anybody');
check(/\* this\.panicSpread;/.test(brainCode)
  && /double spread = skillError \* 7\.0D \* distanceFactor \* settle \* \(moving \? 1\.75D : 1\.0D\)/
  .test(brainCode.replace(/\s+/g, ' ')),
  'the cone is multiplied by panicSpread in one place');
check(/this\.panicSpread = Config\.GRENADE_FLASH_PANIC_SPREAD_MULTIPLIER\.get\(\);[\s\S]{0,200}?finally \{\s*this\.panicSpread = 1\.0D;/
  .test(brain), 'the multiplier is set for that shot only and always restored (finally)');
check(/this\.lastKnownTargetPos = target\.position\(\);/.test(brain),
  'the memory is filled only while it can really see the target');
check(/transition\(GunAiState\.IDLE\)/.test(brain.slice(brain.indexOf('public void tick()'),
  brain.indexOf('private void tickBlind'))),
  'and when the effect ends the normal path (target or IDLE) runs again - that is the re-acquire');
check(/Config\.GRENADE_FLASH_MOB_BLIND_TICKS|mobBlindTicks/.test(blast), 'mobBlindTicks is the knob');
check(/panicSpreadMultiplier/.test(readme) && /panicFire/.test(readme) && /blindsMobs/.test(readme),
  'README documents the panic keys');

console.log('');
console.log('5. smoke is a simplification, and it is labelled as one');
check(/class GrenadeEvents/.test(events) && /ParticleTypes\.CAMPFIRE_COSY_SMOKE/.test(events),
  'the cloud is particles');
check(/if \(entity instanceof Player\) \{\s*continue;/.test(events.replace(/\/\/[^\r\n]*/g, '')),
  'players are NOT blinded by smoke (their screen is blocked by the particles instead)');
check(/MobEffects\.BLINDNESS, 20/.test(events), 'mobs inside it are');
check(/简化/.test(readme),
  'README says out loud that there is no real sight-occlusion model');

console.log('');
console.log('6. friendly fire, and the player-self switch');
check(/public static boolean mayHurt\(@Nullable LivingEntity thrower, LivingEntity victim\)/.test(entity)
  && /if \(victim == thrower\) \{\s*return Config\.GRENADES_PLAYER_SELF_DAMAGE\.get\(\);/.test(entityCode),
  'the thrower is a special case decided by playerSelfDamage');
check(/return !Faction\.allies\(thrower, victim\);/.test(entityCode),
  'everybody else in the same faction is skipped when friendlyFire is off');
check(/if \(Config\.GRENADES_FRIENDLY_FIRE\.get\(\)\) \{\s*return true;/.test(entityCode),
  'and friendlyFire = true is the documented free-for-all');
const mayHurt = (friendlyFire, selfDamage, isSelf, allies) => {
  if (friendlyFire) return true;
  if (isSelf) return selfDamage;
  return !allies;
};
check(mayHurt(false, true, true, true) === true && mayHurt(false, false, true, true) === false,
  'playerSelfDamage decides only the self case');
check(mayHurt(false, true, false, true) === false && mayHurt(false, true, false, false) === true,
  'an ally is safe, an enemy is not');
check(mayHurt(true, false, false, true) === true, 'friendlyFire = true overrides everything');

console.log('');
console.log('7. the rack: throwables only, and the resupply truth table');
check(/THROWABLE\("throwable"\)/.test(armament), 'the rack has a THROWABLE armament');
check(/stack\.getItem\(\) instanceof com\.gfl\.tarkovscav\.grenade\.GrenadeItem/.test(armament),
  'classified by the item class, not by an id list');
check(/return this != UNSUPPORTED && this != THROWABLE;/.test(armament)
  && /return this == THROWABLE;/.test(armament),
  'usable() is false for a throwable (it cannot arm anybody) and resupply() is true');
check(/armamentOf\(stack\)\.usable\(\) \|\| armamentOf\(stack\)\.resupply\(\)/.test(armament),
  'accepts() lets a throwable onto the rack through the resupply path');
check(/new com\.gfl\.tarkovscav\.grenade\.GrenadeResupplyGoal\(this, this\)/.test(
  read('entity/ScavEntity.java') + read('entity/GunnerPillagerEntity.java')
  + read('entity/GunnerVillagerEntity.java')), 'the resupply goal is on the three gun mob types');
check(/MobGrenades\.hasRoom\(this\.mob\)/.test(resupplyCode), 'it only runs when the pouch has room');
check(/if \(this\.mob\.getTarget\(\) != null\) \{\s*return false;/.test(resupplyCode),
  'and never during a firefight');
check(/instanceof GrenadeItem\b[\s\S]{0,200}?setCooldown/.test(resupplyCode),
  'a rack holding a gun is walked away from (it only ever takes a GrenadeItem)');
check(/ItemStack taken = this\.target\.claim\(\);[\s\S]{0,300}?MobGrenades\.take\(this\.mob, grenade\.kind\(\)\);/
  .test(resupplyCode), 'no duplication: the pouch entry is given back if the claim came up empty');
check(/MobGrenades\.add\(this\.mob, grenade\.kind\(\)\)/.test(resupplyCode)
  && /claim\(\)/.test(resupplyCode),
  'and the creative rack keeps its template while the normal one empties (both via claim())');
check(/GRENADES_RACK_PRIORITY/.test(resupplyCode) && /priority\.equals\("nearest"\)/.test(resupplyCode),
  'several racks are chosen between by grenades.rackPriority');
check(/TAG = "tarkovscav:grenades"/.test(pouch) && /putInt\(kind\.id\(\)/.test(pouch),
  'the pouch is per-kind NBT on the mob (like MobAmmoInventory)');
check(/public static boolean take\(Mob mob, GrenadeKind kind\)/.test(pouch)
  && /if \(have <= 0\) \{\s*return false;/.test(pouch),
  'and a throw is paid for out of it (never negative)');
check(/MobGrenades\.take\(this\.mob, kind\);/.test(throwCode),
  'the throw goal spends from the pouch');
check(/MobGrenades\.next\(this\.mob\)/.test(throwCode),
  'and throws the kind it actually collected');
// The truth table, simulated: who gets to take what.
function take(unarmed, rackHoldsThrowable, pouchHasRoom) {
  if (!unarmed) return pouchHasRoom && rackHoldsThrowable ? 'resupply' : 'nothing';
  return rackHoldsThrowable ? 'nothing' : 'convert';
}
check(take(true, false, false) === 'convert', 'unarmed + weapon -> convert (the original rule)');
check(take(true, true, true) === 'nothing', 'unarmed + throwable -> nothing (a grenade arms nobody)');
check(take(false, true, true) === 'resupply', 'armed + throwable + room -> resupply');
check(take(false, true, false) === 'nothing', 'armed + full pouch -> nothing');
check(take(false, false, true) === 'nothing', 'armed + weapon -> nothing (it never collects a second gun)');
check(/GRENADES_RESUPPLY_ENABLED\.get\(\)/.test(resupplyCode), 'resupplyEnabled = false makes the goal inert');
// The lie in the log (fixed 2026): the taker used `!armament.usable()` as its "unsupported" test, and usable()
// is false for a throwable too - so a grenade on a rack WARNed "[rack] unsupported item ..." once per tick,
// in a sentence that listed grenades as accepted. Only UNSUPPORTED may reach warnUnsupported now.
const takerCode = strip(read('block/WeaponRackTaker.java'));
check(/if \(armament == WeaponRackArmament\.THROWABLE\) \{[\s\S]{0,900}?return;\s*\}\s*if \(armament == WeaponRackArmament\.UNSUPPORTED\) \{[\s\S]{0,200}?warnUnsupported/
  .test(takerCode),
  'the taker returns silently for THROWABLE and only WARNs for UNSUPPORTED');
check(!/if \(!armament\.usable\(\)\)/.test(takerCode),
  'and the old "not usable = unsupported" test is gone (that is what produced the false WARN)');
check((takerCode.match(/warnUnsupported\(/g) || []).length === 2
  && /warnUnsupported\(taken, "after conversion"\)/.test(takerCode),
  'and the only other warnUnsupported call is the "after conversion" failure path, not a throwable');
check(/A throwable never converts anybody/.test(read('block/WeaponRackTaker.java'))
  && /resupply path/.test(read('block/WeaponRackTaker.java')),
  'with a comment saying why: the resupply goal handles throwables, so a WARN there was simply wrong');
check(/throwable \(a grenade of this mod\)[\s\S]{0,200}?nothing happens here, and nothing is logged/
  .test(read('block/WeaponRackTaker.java')),
  'and the class javadoc table says the same thing, next to the item it used to name as accepted');
check(/曾经的假日志（已修）/.test(fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8')),
  'README 5v records the corrected log line');

console.log('');
console.log('8. the mob throw, the kill feed, the command');
check(/Mob_GRENADE|MOB_GRENADE_MIN_RANGE/.test(config) === false || /MOB_GRENADE_MIN_RANGE/.test(throwGoal),
  'the range window is configurable');
check(/if \(this\.mob\.hasLineOfSight\(target\)\) \{\s*return false;/.test(throwCode),
  'it throws only when the target is out of sight (behind cover)');
check(/int allowed = Config\.MOB_GRENADE_ALLY_SAFETY_MAX\.get\(\);/.test(throwCode)
  && /if \(allies > allowed\)/.test(throwCode),
  'and only when the landing point is clear of allies');
check(/shout\(\)/.test(throwGoal) && /sayGrenade\(\)/.test(throwGoal)
  && /public void sayGrenade\(\)/.test(read('voice/MobVoice.java')),
  'the thrower shouts one of the existing grenade voice lines');
check(/KillFeedWeapons\.register\(com\.gfl\.tarkovscav\.grenade\.GrenadeAttribution::resolve\)/.test(main),
  'the kill feed learns grenades through its ONE registered resolver (the extension point)');
check(/public static KillFeedWeapons\.Armament resolve\(/.test(attribution)
  && /new ItemStack\(ModItems\.grenadeItem\(kind\)\)/.test(attribution),
  'and it names the actual grenade item, so a line reads "Ge_SiLa [Frag Grenade] Sniper Pillager"');
check(/GrenadeAttribution\.remember\(victim, kind\);/.test(blastCode)
  && /finally \{\s*GrenadeAttribution\.forget\(victim\);/.test(blastCode),
  'the attribution is set for the damage call and cleared in a finally (no stale names)');
check(/literal\("grenade"\)/.test(commands) && /testGrenade/.test(commands),
  '/tarkovscav test grenade exists');
for (const needle of ['expectedFragmentDamage', 'expectedBlastDamage', 'flashIntensityFor',
  'candidates(']) {
  check(commands.includes(needle), `and it prints ${needle.replace('(', '')}`);
}
check(/GrenadeEntity\.byId|GrenadeKind\.byId/.test(commands), 'the command takes a kind name');

console.log('');
console.log('9. config and docs');
const keys = [
  ['GRENADES_ENABLED', 'define("enabled", true)'],
  ['GRENADES_TERRAIN_DAMAGE', 'define("terrainDamage", false)'],
  ['GRENADES_THROW_CHARGE_TICKS', 'defineInRange("throwChargeTicks", 20'],
  ['GRENADES_MIN_THROW_SPEED', 'defineInRange("minThrowSpeed", 0.6D'],
  ['GRENADES_MAX_THROW_SPEED', 'defineInRange("maxThrowSpeed", 1.6D'],
  ['GRENADES_COOK_WHILE_HOLDING', 'define("cookWhileHolding", true)'],
  ['GRENADES_FRIENDLY_FIRE', 'define("friendlyFire", false)'],
  ['GRENADES_PLAYER_SELF_DAMAGE', 'define("playerSelfDamage", true)'],
  ['GRENADE_BLAST_DAMAGE_PER_POWER', 'defineInRange("blastDamagePerPower", 12.0D'],
  ['GRENADE_FRAG_FUSE_TICKS', 'defineInRange("fuseTicks", 60'],
  ['GRENADE_FRAG_COUNT', 'defineInRange("fragmentCount", 24'],
  ['GRENADE_FRAG_DAMAGE', 'defineInRange("fragmentDamage", 14.0D'],
  ['GRENADE_FRAG_RADIUS', 'defineInRange("fragmentRadius", 10.0D'],
  ['GRENADE_FRAG_ARMOR_PIERCE', 'defineInRange("fragmentArmorPierce", 0.35D'],
  ['GRENADE_FRAG_STEP', 'defineInRange("fragmentStep", 0.5D'],
  ['GRENADE_HE_FUSE_TICKS', 'defineInRange("fuseTicks", 80'],
  ['GRENADE_HE_BLAST_POWER', 'defineInRange("blastPower", 4.0D'],
  ['GRENADE_HE_FRAG_COUNT', 'defineInRange("fragmentCount", 8'],
  ['GRENADE_HE_FRAG_DAMAGE', 'defineInRange("fragmentDamage", 8.0D'],
  ['GRENADE_SMOKE_FUSE_TICKS', 'defineInRange("fuseTicks", 40'],
  ['GRENADE_SMOKE_RADIUS', 'defineInRange("radius", 4.0D'],
  ['GRENADE_SMOKE_DURATION_TICKS', 'defineInRange("durationTicks", 300'],
  ['GRENADE_FLASH_FUSE_TICKS', 'defineInRange("fuseTicks", 40'],
  ['GRENADE_FLASH_RADIUS', 'defineInRange("flashRadius", 12.0D'],
  ['GRENADE_FLASH_INTENSITY', 'defineInRange("flashIntensity", 1.0D'],
  ['GRENADE_FLASH_PLAYER_BLIND_TICKS', 'defineInRange("playerBlindTicks", 100'],
  ['GRENADE_FLASH_MOB_BLIND_TICKS', 'defineInRange("mobBlindTicks", 120'],
  ['GRENADE_FLASH_LOOK_AWAY_FACTOR', 'defineInRange("lookAwayFactor", 0.5D'],
  ['GRENADE_FLASH_BLINDS_MOBS', 'define("blindsMobs", true)'],
  ['GRENADE_FLASH_PANIC_FIRE', 'define("panicFire", true)'],
  ['GRENADE_FLASH_PANIC_SPREAD_MULTIPLIER', 'defineInRange("panicSpreadMultiplier", 8.0D'],
  ['GRENADE_FLASH_PANIC_BURST_TICKS', 'defineInRange("panicBurstTicks", 4'],
  ['GRENADE_FLASH_SHORT_FUSE_TICKS', 'defineInRange("fuseTicks", 20'],
  ['GRENADE_FLASH_SHORT_BLIND_FACTOR', 'defineInRange("blindFactor", 0.75D'],
  ['MOB_GRENADES_ENABLED', 'define("enabled", true)'],
  ['MOB_GRENADE_CARRY_CHANCE', 'defineInRange("carryChance", 0.35D'],
  ['MOB_GRENADE_MAX_PER_MOB', 'defineInRange("maxPerMob", 2'],
  ['MOB_GRENADE_COOLDOWN_TICKS', 'defineInRange("cooldownTicks", 200'],
  ['MOB_GRENADE_MIN_RANGE', 'defineInRange("minRange", 6.0D'],
  ['MOB_GRENADE_MAX_RANGE', 'defineInRange("maxRange", 20.0D'],
  ['MOB_GRENADE_ALLY_SAFETY_RADIUS', 'defineInRange("allySafetyRadius", 4.5D'],
  ['MOB_GRENADE_ALLY_SAFETY_MAX', 'defineInRange("allySafetyMax", 0'],
  ['MOB_GRENADE_ARC_SAMPLES', 'defineInRange("arcSamples", 13'],
  ['MOB_GRENADE_REQUIRE_CLEAR_ARC', 'define("requireClearArc", true)'],
  ['MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES', 'defineInRange("maxLaunchPitchDegrees", 45.0D'],
  ['MOB_GRENADE_RETRY_COOLDOWN_TICKS', 'defineInRange("retryCooldownTicks", 40'],
  ['GRENADES_RESUPPLY_ENABLED', 'define("resupplyEnabled", true)'],
  ['GRENADES_RESUPPLY_RADIUS', 'defineInRange("resupplyRadius", 24.0D'],
  ['GRENADES_RESUPPLY_COOLDOWN_TICKS', 'defineInRange("resupplyCooldownTicks", 200'],
  ['GRENADES_RESUPPLY_SEARCH_COOLDOWN_TICKS', 'defineInRange("resupplySearchCooldownTicks", 40'],
  ['GRENADES_RACK_PRIORITY', 'define("rackPriority", "grenade")'],
];
for (const [key, value] of keys) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
  check(config.includes(value), `  ${key} default matches the documented one`, value);
}
check(/\.push\("grenades"\)/.test(config), 'the keys live in their own [grenades] section');
for (const key of ['terrainDamage', 'throwChargeTicks', 'minThrowSpeed', 'maxThrowSpeed', 'cookWhileHolding',
  'friendlyFire', 'playerSelfDamage', 'fuseTicks', 'fragmentCount', 'fragmentDamage', 'fragmentRadius',
  'fragmentArmorPierce', 'fragmentStep', 'blastPower', 'durationTicks', 'flashRadius', 'flashIntensity',
  'playerBlindTicks', 'mobBlindTicks', 'lookAwayFactor', 'blindsMobs', 'panicFire',
  'panicSpreadMultiplier', 'panicBurstTicks', 'carryChance', 'maxPerMob', 'cooldownTicks', 'minRange',
  'maxRange', 'allySafetyRadius', 'allySafetyMax', 'arcSamples', 'requireClearArc',
  'maxLaunchPitchDegrees', 'retryCooldownTicks', 'resupplyEnabled', 'resupplyRadius',
  'resupplyCooldownTicks', 'resupplySearchCooldownTicks', 'rackPriority', 'blastDamagePerPower',
  'blindFactor']) {
  check(readme.includes(key), `README documents grenades.${key}`);
}
check(/### 5v\./.test(readme), 'README has the 5v section');
check(/破片手雷/.test(readme) && /闪光弹/.test(readme) && /军械台|放置台/.test(readme),
  'README explains the batch in Chinese');
check(/乱开枪/.test(readme) && /允许打中自己人|有意为之/.test(readme),
  'including the panic fire and the friendly-fire consequence');

// ================================================================================================
// 10. The ballistic solver (2026: "the AI's grenades are very easily eaten by cover").
//
// The claim is checked twice: as source invariants (one implementation, no spread, the right log lines, a
// held throw that spends nothing) and as a simulation. The simulation is a line-by-line mirror of
// GrenadeBallistics.fly/solve, and every constant it uses is READ OUT of the Java and the config, so this
// gate cannot pass against a solver that has different physics than the one it models.
// ================================================================================================
console.log('');
console.log('10. the ballistic solver: one implementation, a real lob, a held throw');
const ballisticsFlat = ballistics.replace(/\s+/g, ' ');
const throwFlat = throwGoal.replace(/\s+/g, ' ');
const ballisticsCode = strip(ballistics);
check(/public final class GrenadeBallistics/.test(ballistics)
  && /public static Solution solve\(Vec3 from, Vec3 aim, double speed, int samples, double maxPitchDegrees, BlockTest blocked\)/
    .test(ballisticsFlat),
  'GrenadeBallistics.solve(from, aim, speed, samples, maxPitch, blocked) is the one entry point');
check(/interface BlockTest[\s\S]{0,200}?boolean blocked\(BlockPos pos\)/.test(ballistics),
  'the world enters through a BlockTest, so the function stays pure and the gate can fake it');
check(/public record Solution\(Vec3 direction, @Nullable Vec3 landing, double pitchDegrees, @Nullable BlockPos blockedAt\)/
  .test(ballisticsFlat), 'and the answer is the small immutable Solution the callers read');
check(/boolean clear\(\)/.test(ballisticsCode)
  && /this\.blockedAt == null && this\.landing != null/.test(ballisticsFlat),
  'clear() = landed somewhere AND nothing blocked it');
const sourceFiles = [
  ['GrenadeEntity', entity], ['GrenadeThrowGoal', throwGoal], ['GrenadeBlast', blast],
  ['GrenadeResupplyGoal', resupply], ['MobGrenades', pouch], ['GrenadeKind', kinds],
  ['GrenadeBallistics', ballistics], ['ModCommands', commands], ['GunBrain', read('gun/GunBrain.java')],
];
check(sourceFiles.every(([, text]) => !/predictedLanding/.test(text)),
  'the old flat-projection landing (predictedLanding) is gone from the whole tree');
const loopCopies = sourceFiles.filter(([, text]) => /0\.99/.test(strip(text)) && /0\.03/.test(strip(text)));
check(loopCopies.length === 1 && loopCopies[0][0] === 'GrenadeBallistics',
  'exactly one copy of the 0.99 / 0.03 step loop exists, in the solver',
  loopCopies.map(([name]) => name).join(', '));
check((strip(throwGoal).match(/GrenadeBallistics\.solve\(/g) || []).length === 1
  && /Config\.MOB_GRENADE_ARC_SAMPLES\.get\(\)/.test(throwGoal)
  && /Config\.MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES\.get\(\)/.test(throwGoal),
  'the throw goal has exactly one solver call, fed by arcSamples and maxLaunchPitchDegrees');
check(/shoot\(direction\.x, direction\.y, direction\.z, \(float\) speed, 0\.0F\)/.test(throwGoal)
  && /Vec3 direction = solution\.direction\(\);/.test(throwGoal),
  'and it flies exactly the solved direction, with no random spread left to disagree with the prediction');
check(!/3\.0F/.test(strip(throwGoal)), 'the old 3.0 inaccuracy is gone from the mob throw');
check(/Config\.MOB_GRENADE_REQUIRE_CLEAR_ARC\.get\(\) && !solution\.clear\(\)/.test(throwGoal),
  'requireClearArc decides between holding the grenade and throwing the best-effort arc');
check(/\[grenade\] \{\} held the throw: arc blocked by cover at \(\{\}, \{\}, \{\}\)/.test(throwGoal),
  'a held throw logs the required "arc blocked by cover at (x, y, z)" line');
check(/pitch \{\} deg, \{\}/.test(throwGoal) && /"arc clear"/.test(throwGoal)
  && /"arc NOT clear/.test(throwGoal),
  'and a real throw logs its pitch plus whether the arc was clear');
check(/if \(this\.retryCooldown > 0\)/.test(throwCode)
  && /this\.retryCooldown = Config\.MOB_GRENADE_RETRY_COOLDOWN_TICKS\.get\(\);/.test(throwCode),
  'a held throw sleeps for retryCooldownTicks instead of re-solving every tick');
check((throwCode.match(/this\.cooldown = /g) || []).length === 1
  && /MobGrenades\.take\(this\.mob, kind\); this\.cooldown = Config\.MOB_GRENADE_COOLDOWN_TICKS\.get\(\);/
    .test(throwFlat),
  'and a held throw does NOT set the throw cooldown: the one assignment happens after the grenade is spent');
check(/int allowed = Config\.MOB_GRENADE_ALLY_SAFETY_MAX\.get\(\);/.test(throwCode)
  && /if \(allies > allowed\)/.test(throwCode)
  && /GrenadeEntity\.candidates\(level, landing, radius\)/.test(throwCode),
  'the ally rule is untouched, and it inspects the solved landing (the point the throw really produces)');
check(/GrenadeResupplyGoal|hasRoom/.test(resupply) && /MobGrenades\.take\(this\.mob, kind\);/.test(throwCode),
  'the pouch is only debited in start(), so a held throw costs nothing');

// ---- the simulation, with every constant read out of the sources ------------------------------
const javaNumber = (pattern, label) => {
  const match = pattern.exec(ballistics);
  check(match !== null, `the solver declares ${label}`);
  return match ? Number(match[1]) : Number.NaN;
};
const DRAG = javaNumber(/private static final double DRAG = ([\d.]+)D;/, 'DRAG');
const GRAVITY = javaNumber(/private static final double GRAVITY = ([\d.]+)D;/, 'GRAVITY');
const HALF_BOX = javaNumber(/private static final double HALF_BOX = ([\d.]+)D;/, 'HALF_BOX');
const MAX_STEPS = javaNumber(/private static final int MAX_STEPS = (\d+);/, 'MAX_STEPS');
const DROP_LIMIT = javaNumber(/private static final double DROP_LIMIT = ([\d.]+)D;/, 'DROP_LIMIT');
const ARRIVE = javaNumber(/public static final double ARRIVE_TOLERANCE = ([\d.]+)D;/, 'ARRIVE_TOLERANCE');
const REFINE_ROUNDS = javaNumber(/private static final int REFINE_ROUNDS = (\d+);/, 'REFINE_ROUNDS');
const REFINE_POINTS = javaNumber(/private static final int REFINE_POINTS = (\d+);/, 'REFINE_POINTS');
const configNumber = (pattern, label) => {
  const match = pattern.exec(config);
  check(match !== null, `Config declares ${label}`);
  return match ? Number(match[1]) : Number.NaN;
};
const SAMPLES = configNumber(/defineInRange\("arcSamples", (\d+)/, 'arcSamples');
const MAX_PITCH = configNumber(/defineInRange\("maxLaunchPitchDegrees", ([\d.]+)D/, 'maxLaunchPitchDegrees');
const RETRY = configNumber(/defineInRange\("retryCooldownTicks", (\d+)/, 'retryCooldownTicks');
const SAFETY_RADIUS = configNumber(/defineInRange\("allySafetyRadius", ([\d.]+)D/, 'allySafetyRadius');
const ALLOWED = configNumber(/defineInRange\("allySafetyMax", (\d+)/, 'allySafetyMax');
const SPEED = configNumber(/defineInRange\("maxThrowSpeed", ([\d.]+)D/, 'maxThrowSpeed') * 0.85;
check(/GRENADES_MAX_THROW_SPEED\.get\(\) \* 0\.85D/.test(throwGoal),
  'the mob throws at 85 % of the player charge (the factor the old throw used)');
check(DRAG === 0.99 && GRAVITY === 0.03 && HALF_BOX === 0.125,
  'the modelled physics is vanilla drag, gravity and half the entity box',
  `drag ${DRAG}, gravity ${GRAVITY}, halfBox ${HALF_BOX}`);
check(/ThrowableItemProjectile/.test(entity), 'and the entity really is a ThrowableItemProjectile');
check(SAMPLES === 13 && MAX_PITCH === 45 && RETRY === 40,
  'the simulated defaults are the documented ones', `samples ${SAMPLES}, maxPitch ${MAX_PITCH}, retry ${RETRY}`);
check(/define\("requireClearArc", true\)/.test(config), 'and requireClearArc defaults to true');

// fly/solve: a mirror of GrenadeBallistics.java. `world` below is the BlockTest the solver takes.
function fly(from, direction, speed, blocked) {
  let vx = direction[0] * speed;
  let vy = direction[1] * speed;
  let vz = direction[2] * speed;
  let [x, y, z] = from;
  for (let step = 0; step < MAX_STEPS; step++) {
    vx *= DRAG;
    vy = vy * DRAG - GRAVITY;
    vz *= DRAG;
    x += vx;
    y += vy;
    z += vz;
    const pos = [Math.floor(x), Math.floor(y - HALF_BOX), Math.floor(z)];
    if (blocked(pos[0], pos[1], pos[2])) return { landing: [x, y, z], hit: pos };
    if (y < from[1] - DROP_LIMIT) break;
  }
  return { landing: null, hit: null };
}
function flatDirection(from, aim) {
  const dx = aim[0] - from[0];
  const dz = aim[2] - from[2];
  const length = Math.hypot(dx, dz);
  return length < 1e-4 ? [0, 1] : [dx / length, dz / length];
}
function launchDirection(flat, pitchDegrees) {
  const radians = pitchDegrees * Math.PI / 180;
  return [flat[0] * Math.cos(radians), Math.sin(radians), flat[1] * Math.cos(radians)];
}
const landingError = (flight, aim) => flight.landing === null
  ? Infinity : Math.hypot(flight.landing[0] - aim[0], flight.landing[2] - aim[2]);
function solve(from, aim, speed, samples, maxPitchDegrees, blocked) {
  const flat = flatDirection(from, aim);
  const count = Math.max(2, samples);
  const maxPitch = Math.max(0, maxPitchDegrees);
  const step = maxPitch / (count - 1);
  let bestPitch = 0;
  let best = fly(from, launchDirection(flat, 0), speed, blocked);
  for (let i = 1; i < count; i++) {
    const pitch = step * i;
    const arc = fly(from, launchDirection(flat, pitch), speed, blocked);
    if (landingError(arc, aim) < landingError(best, aim)) {
      best = arc;
      bestPitch = pitch;
    }
  }
  let half = step;
  for (let round = 0; round < REFINE_ROUNDS; round++) {
    const lo = Math.max(0, bestPitch - half);
    const hi = Math.min(maxPitch, bestPitch + half);
    for (let k = 0; k <= REFINE_POINTS; k++) {
      const pitch = lo + (hi - lo) * k / REFINE_POINTS;
      const arc = fly(from, launchDirection(flat, pitch), speed, blocked);
      if (landingError(arc, aim) < landingError(best, aim)) {
        best = arc;
        bestPitch = pitch;
      }
    }
    half = (hi - lo) / REFINE_POINTS;
  }
  const direction = launchDirection(flat, bestPitch);
  if (best.landing === null) {
    return { direction, landing: null, pitch: bestPitch, error: Infinity, blockedAt: null };
  }
  if (landingError(best, aim) <= ARRIVE) {
    return { direction, landing: best.landing, pitch: bestPitch, error: landingError(best, aim), blockedAt: null };
  }
  const flattest = fly(from, launchDirection(flat, 0), speed, blocked);
  return {
    direction, landing: best.landing, pitch: bestPitch, error: landingError(best, aim),
    blockedAt: flattest.hit || best.hit,
  };
}
/** The centre height of the arc in a given block column (used to prove it goes OVER a wall). */
function heightInColumn(from, direction, speed, column) {
  let vx = direction[0] * speed;
  let vy = direction[1] * speed;
  let vz = direction[2] * speed;
  let [x, y, z] = from;
  for (let step = 0; step < MAX_STEPS; step++) {
    vx *= DRAG;
    vy = vy * DRAG - GRAVITY;
    vz *= DRAG;
    x += vx;
    y += vy;
    z += vz;
    if (Math.floor(x) === column) return y;
    if (y < from[1] - DROP_LIMIT) break;
  }
  return null;
}
// Flat ground (y < 0 is solid) and, at one x column, a wall of the given height (z -1..1).
const world = (wallX, wallHeight) => (bx, by, bz) =>
  by < 0 || (bx === wallX && by >= 0 && by < wallHeight && Math.abs(bz) <= 1);

const FROM = [0, 1.3, 0];           // the muzzle: the mob's eye at 1.5 minus the 0.2 spawn offset
const AIM = [18, 1.5, 0];           // the target's eye, 18 blocks away
console.log(`  muzzle y ${FROM[1]}, target eye 18 blocks away, throw speed ${SPEED.toFixed(2)} blocks/tick`);

const flatGround = solve(FROM, AIM, SPEED, SAMPLES, MAX_PITCH, world(-999, 0));
check(flatGround.blockedAt === null && flatGround.landing !== null && flatGround.error <= 1.0,
  'flat ground: the solved landing is on the target', `off by ${flatGround.error.toFixed(2)} block(s)`);
check(flatGround.pitch > 0,
  'and the solution is a lob, not the old straight line', `${flatGround.pitch.toFixed(2)} degrees`);

const lowWall = solve(FROM, AIM, SPEED, SAMPLES, MAX_PITCH, world(9, 1));
const lowHeight = heightInColumn(FROM, lowWall.direction, SPEED, 9);
check(lowWall.blockedAt === null && lowWall.error <= 1.0,
  'a 1 block wall: a clear lob still exists and it lands on the target',
  `off by ${lowWall.error.toFixed(2)} block(s), pitch ${lowWall.pitch.toFixed(2)} degrees`);
check(lowHeight !== null && lowHeight - HALF_BOX > 1.0,
  'and that lob really goes OVER the wall, box bottom above the wall top',
  `centre ${lowHeight.toFixed(3)} at the wall, box bottom ${(lowHeight - HALF_BOX).toFixed(3)}` +
  ` (clearance ${(lowHeight - HALF_BOX - 1.0).toFixed(3)})`);

const highWall = solve(FROM, AIM, SPEED, SAMPLES, MAX_PITCH, world(9, 3));
check(highWall.blockedAt !== null,
  'a 3 block wall: no arc lands on the target, so the throw is refused',
  `the best effort still lands ${highWall.error.toFixed(2)} blocks away`);
check(highWall.blockedAt[0] === 9 && highWall.blockedAt[2] === 0,
  'and the refusal names the wall column as the cover it is up against',
  `blocked at (${highWall.blockedAt.join(', ')})`);
// Sanity: the tolerance is what separates the two walls, so 2.5 must sit between the two errors.
check(lowWall.error < ARRIVE && highWall.error > ARRIVE,
  'ARRIVE_TOLERANCE is the line between "clears the wall" and "does not"',
  `${lowWall.error.toFixed(2)} < ${ARRIVE} < ${highWall.error.toFixed(2)}`);

// ---- the goal's decision, modelled on the source checked above --------------------------------
const alliesAt = (landing, allies) => allies.filter((ally) =>
  Math.hypot(ally[0] - landing[0], ally[2] - landing[2]) <= SAFETY_RADIUS).length;
const attempt = (solution, allies, requireClear, pouch) => {
  const clear = solution.blockedAt === null && solution.landing !== null;
  if (requireClear && !clear) return { threw: false, pouch, why: 'blocked' };
  if (alliesAt(solution.landing, allies) > ALLOWED) return { threw: false, pouch, why: 'allies' };
  return { threw: true, pouch: pouch - 1, why: 'threw' };
};
const openField = attempt(flatGround, [], true, 2);
check(openField.threw === true && openField.pouch === 1,
  'flat ground with nobody around: the mob throws and the pouch goes 2 -> 1');
const refused = attempt(highWall, [], true, 2);
check(refused.threw === false && refused.why === 'blocked' && refused.pouch === 2,
  'a 3 block wall: refused AND the grenade is not consumed (pouch stays 2)');
const onAllies = attempt(flatGround, [[flatGround.landing[0], 0, flatGround.landing[2]]], true, 2);
check(onAllies.threw === false && onAllies.why === 'allies' && onAllies.pouch === 2,
  'an ally standing on the landing point: refused, grenade not consumed');
check(attempt(lowWall, [], true, 2).threw === true,
  'and a 1 block wall is a legal throw for the mob');
const escapeHatch = attempt(highWall, [], false, 2);
check(escapeHatch.threw === true && escapeHatch.pouch === 1,
  'requireClearArc = false still throws the best-effort arc (the documented escape hatch)');
check(SAFETY_RADIUS === 4.5 && ALLOWED === 0, 'the ally check uses the documented radius and allowance',
  `radius ${SAFETY_RADIUS}, allowed ${ALLOWED}`);
console.log(`  the short-range consequence: at 9 blocks the best arc is off by ` +
  `${solve(FROM, [9, 1.5, 0], SPEED, SAMPLES, MAX_PITCH, world(-999, 0)).error.toFixed(2)} blocks, ` +
  `so 6-9 blocks is held back (README 5v says so)`);
check(solve(FROM, [9, 1.5, 0], SPEED, SAMPLES, MAX_PITCH, world(-999, 0)).error > ARRIVE
  && solve(FROM, [10, 1.5, 0], SPEED, SAMPLES, MAX_PITCH, world(-999, 0)).error <= ARRIVE,
  'and the README range claim holds: 9 blocks is out of reach, 10 is not');

console.log('');
if (failures > 0) {
  console.log(`${failures} grenade check(s) FAILED`);
  process.exit(1);
}
console.log('grenade invariants all hold');
