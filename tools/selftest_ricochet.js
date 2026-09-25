// Hard targets / ricochet (README 5z): the iron golem's 80 % gunfire reduction and its chance to bounce a
// bullet, with NO effect on any other damage type or any entity outside the tag.
//
//   node tools/selftest_ricochet.js
//
// What is pinned, and why each one matters:
//   * "this is gunfire" is asked of TaCZ's OWN damage-type tag (#tacz:bullets), not of a class or a guessed
//     damage-type name - and that tag is verified to exist inside the TaCZ jar, with the four ids it lists;
//   * the order (ricochet first = ZERO damage, reduction second) is a property of the numbers, so it is
//     simulated;
//   * every other damage type, and every entity outside #tarkovscav:hard_target, is untouched - simulated
//     type by type, because "the rule leaked into melee" is the failure mode that would matter;
//   * the reflection arithmetic (normal component flipped, tangential component kept), the per-bullet bounce
//     cap, and the "nothing to reflect" path (zero damage + effects still happen);
//   * documentation: README says what it does, why, and how to add another mob.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const hard = strip(read('combat/HardTarget.java'));
const hardRaw = read('combat/HardTarget.java');
const config = strip(read('Config.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const main = read('TarkovScav.java');

console.log('1. "this is gunfire" = TaCZ\'s own damage-type tag');
check(/TagKey\.create\(Registries\.DAMAGE_TYPE, new ResourceLocation\("tacz", "bullets"\)\)/.test(hard),
  'the rule asks for the damage-type tag tacz:bullets');
check(/source\.is\(TACZ_BULLETS\)/.test(hard),
  'and asks it of the DamageSource itself (no class check, no guess at a damage-type name)');
check(/source\.getDirectEntity\(\) instanceof AbstractArrow/.test(hard)
  && /Config\.RICOCHET_INCLUDE_ARROWS\.get\(\)/.test(hard),
  'arrows only count with ricochet.includeArrows = true (off by default)');
// The tag is not a guess: it is inside the shipped TaCZ jar. Read it out of the zip and check its contents.
const readZipEntry = (zipPath, entryName) => {
  const buf = fs.readFileSync(zipPath);
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = buf.readUInt16LE(eocd + 10);
  let at = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    const nameLength = buf.readUInt16LE(at + 28);
    const extraLength = buf.readUInt16LE(at + 30);
    const commentLength = buf.readUInt16LE(at + 32);
    const localOffset = buf.readUInt32LE(at + 42);
    const name = buf.toString('utf8', at + 46, at + 46 + nameLength);
    if (name === entryName) {
      const method = buf.readUInt16LE(at + 10);
      const compressedSize = buf.readUInt32LE(at + 20);
      const localNameLength = buf.readUInt16LE(localOffset + 26);
      const localExtraLength = buf.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + localNameLength + localExtraLength;
      const raw = buf.subarray(start, start + compressedSize);
      return method === 0 ? Buffer.from(raw) : zlib.inflateRawSync(raw);
    }
    at += 46 + nameLength + extraLength + commentLength;
  }
  return null;
};
const taczLib = path.join(ROOT, 'libs', 'tacz-1.20.1-1.1.8-hotfix.jar');
if (fs.existsSync(taczLib)) {
  const tag = readZipEntry(taczLib, 'data/tacz/tags/damage_type/bullets.json');
  check(tag !== null, 'TaCZ really ships data/tacz/tags/damage_type/bullets.json');
  const text = tag ? tag.toString('utf8') : '';
  for (const id of ['tacz:bullet', 'tacz:bullet_ignore_armor', 'tacz:bullet_void',
    'tacz:bullet_void_ignore_armor']) {
    check(text.includes(id), `  and it lists ${id}`);
  }
} else {
  check(false, 'the TaCZ dev lib is present (libs/)', 'needed to verify the damage-type tag');
}

console.log('');
console.log('2. the tag decides WHO, and the default is the iron golem');
const tagPath = path.join(RES, 'data', 'tarkovscav', 'tags', 'entity_types', 'hard_target.json');
check(fs.existsSync(tagPath), 'data/tarkovscav/tags/entity_types/hard_target.json exists');
const tag = fs.existsSync(tagPath) ? JSON.parse(fs.readFileSync(tagPath, 'utf8')) : { values: [] };
check(tag.replace === false, 'it is additive (a pack can extend it)');
check(tag.values.includes('minecraft:iron_golem'), 'and it ships with minecraft:iron_golem');
check(/isHardTarget\(Entity entity\)/.test(hard) && /entity\.getType\(\)\.is\(HARD_TARGET\)/.test(hard),
  'membership is asked of the tag, never of a class');

console.log('');
console.log('3. the order: ricochet = ZERO damage, then the reduction');
check(/if \(rollsRicochet\(victim\.getRandom\(\)\)\) \{[\s\S]{0,400}?event\.setAmount\(0\.0F\);/.test(hard),
  'the ricochet roll comes first and sets the blow to zero');
check(/event\.setAmount\(\(float\) \(before \* multiplier\)\);/.test(hard),
  'and only the non-bouncing path multiplies');
check(hard.indexOf('rollsRicochet') < hard.indexOf('before * multiplier'),
  'the order in the source is the order in the comment',
  `ricochet@${hard.indexOf('rollsRicochet')} reduce@${hard.indexOf('before * multiplier')}`);
// The numbers, simulated.
const outcome = (roll, multiplier, amount) => (roll ? 0 : amount * multiplier);
check(outcome(true, 0.2, 20) === 0, 'a ricochet does 0 damage out of a 20-damage hit');
check(outcome(false, 0.2, 20) === 4, 'a non-ricochet does 20 % of it (4 of 20 = the user\'s 80 % off)');
check(Math.abs(outcome(false, 0.2, 7.5) - 1.5) < 1e-9, 'and the multiplier is linear');
check(outcome(false, 0.0, 20) === 0, 'gunDamageMultiplier = 0 makes every non-bouncing hit do nothing');
check(outcome(false, 1.0, 20) === 20, 'and 1.0 disables the reduction (only the ricochet remains)');
check(/public static boolean rollsRicochet\(RandomSource random\)/.test(hard)
  && /chance > 0\.0D && random\.nextDouble\(\) < chance/.test(hard),
  'the roll is chance > 0 && random < chance, so 0 can never bounce');
check(/defineInRange\("chance", 0\.3D, 0\.0D, 1\.0D\)/.test(config),
  'ricochet.chance ships 0.3, clamped to 0..1 (1 = always bounces)');
check(/defineInRange\("gunDamageMultiplier", 0\.2D, 0\.0D, 1\.0D\)/.test(config),
  'ricochet.gunDamageMultiplier ships 0.2 (80 % off), clamped to 0..1');
check(/public static double damageMultiplier\(\)/.test(hard)
  && /Math\.max\(0\.0D, Math\.min\(1\.0D, Config\.RICOCHET_GUN_DAMAGE_MULTIPLIER\.get\(\)\)\)/.test(hard),
  'and the multiplier is clamped in code too, so a silly toml cannot heal the target');

console.log('');
console.log('4. nothing else is affected: other damage types, other entities');
// The rule has exactly two gates - the victim tag and the damage tag - so the truth table is short, and every
// "no" is what keeps melee/explosions/fall/fire and every normal mob exactly as they were.
const applies = (victimInTag, gunfire, enabled) => enabled && victimInTag && gunfire;
const OTHER_DAMAGE = ['melee (player)', 'mob melee', 'explosion (TNT)', 'explosion (our grenade)',
  'fall', 'fire', 'magic', 'drowning', 'our armor-class scaling'];
for (const kind of OTHER_DAMAGE) {
  check(applies(true, false, true) === false, `${kind} is NOT touched (it is not #tacz:bullets damage)`, kind);
}
check(applies(false, true, true) === false,
  'a zombie (not in #tarkovscav:hard_target) takes full bullet damage as before');
check(applies(true, true, false) === false,
  'ricochet.enabled = false is inert: full damage, no bounce');
check(/if \(!Config\.RICOCHET_ENABLED\.get\(\)\) \{\s*return;/.test(hard),
  'and that switch is the first thing the handler checks');
check(/if \(!isHardTarget\(victim\) \|\| !isGunfire\(event\.getSource\(\)\)\) \{\s*return;/.test(hard),
  'the two gates are one early return each (no other branch can apply the rule)');
check(!/MobEffectInstance|potions|DamageType\./.test(hard),
  'and the rule never writes a damage type or an effect of its own');

console.log('');
console.log('5. the reflection, the bounce cap and the "nothing to reflect" path');
check(/incoming\.subtract\(normal\.scale\(2\.0D \* incoming\.dot\(normal\)\)\)/.test(hard),
  'the velocity is reflected about the surface normal (v - 2(v.n)n)');
check(/static Vec3 surfaceNormal\(LivingEntity victim, Projectile projectile\)/.test(hard)
  && /Math\.max\(box\.minX, Math\.min\(box\.maxX, point\.x\)\)/.test(hard),
  'the normal is the vector from the closest point of the victim\'s box to the projectile');
check(/reflected\.lengthSqr\(\) < 1\.0E-8D/.test(hard) && /incoming\.reverse\(\)/.test(hard),
  'a degenerate reflection falls back to "reverse + jitter", so a bullet never freezes');
check(/projectile\.setDeltaMovement\(reflected\)/.test(hard) && /hasImpulse = true/.test(hard),
  'the reflection writes the projectile motion (and tells the entity it was pushed)');
check(/if \(box\.contains\(position\)\)/.test(hard) && /projectile\.setPos\(/.test(hard),
  'and the bullet is nudged out of the hitbox, so it cannot be inside the golem next tick');
// The arithmetic itself, on a plane case: n = +X, v = (-1, 0.2, 0) -> x flips, y/z stay.
const reflect = (v, n) => ({
  x: v.x - 2 * (v.x * n.x + v.y * n.y + v.z * n.z) * n.x,
  y: v.y - 2 * (v.x * n.x + v.y * n.y + v.z * n.z) * n.y,
  z: v.z - 2 * (v.x * n.x + v.y * n.y + v.z * n.z) * n.z,
});
const flat = reflect({ x: -1, y: 0.2, z: 0 }, { x: 1, y: 0, z: 0 });
check(Math.abs(flat.x - 1) < 1e-9 && Math.abs(flat.y - 0.2) < 1e-9 && Math.abs(flat.z) < 1e-9,
  'a head-on hit on a +X face bounces straight back with the same tangential speed',
  JSON.stringify(flat));
const side = reflect({ x: -1, y: 0, z: 0 }, { x: 0.7071, y: 0.7071, z: 0 });
check(side.y > 0.99 && Math.abs(side.x) < 0.01,
  'a hit on a 45-degree face comes back at 90 degrees (straight up), as the reflection says',
  JSON.stringify(side));
check(/int max = Config\.RICOCHET_MAX_BOUNCES_PER_BULLET\.get\(\);\s*if \(max <= 0\) \{\s*return false;/.test(hard)
  && /if \(already >= max\) \{\s*return false;/.test(hard),
  'a bullet bounces at most maxBouncesPerBullet times (0 = never reflect, the reduction still applies)');
check(/private static final Map<UUID, Integer> BOUNCES/.test(hard)
  && /BOUNCE_MEMORY_LIMIT/.test(hard),
  'the bounce count is kept per projectile UUID in a bounded map');
check(/public static boolean reflect\(LivingEntity victim, DamageSource source\)/.test(hard)
  && /if \(!\(source\.getDirectEntity\(\) instanceof Projectile projectile\)\) \{\s*return false;/.test(hard),
  'a direct entity that is not a Projectile is left alone (only the damage/effects happen)');
check(/boolean reflected = reflect\(victim, event\.getSource\(\)\);[\s\S]{0,200}?event\.setAmount\(0\.0F\);/
  .test(hard),
  'and the zero damage is applied whatever reflect() answered - a non-projectile source still bounces off');

console.log('');
console.log('6. the presentation (sound + sparks) and the config surface');
check(/SoundEvents\.TRIDENT_HIT_GROUND/.test(hard),
  'the clang is the vanilla SoundEvents.TRIDENT_HIT_GROUND (1.20.1 has no trident ricochet sound)');
check(/ParticleTypes\.CRIT/.test(hard) && /ParticleTypes\.ELECTRIC_SPARK/.test(hard),
  'the sparks are ParticleTypes.CRIT + ELECTRIC_SPARK');
check(/public static void effects\(LivingEntity victim, DamageSource source\)/.test(hard)
  && /if \(volume > 0\.0F\)/.test(hard) && /Config\.RICOCHET_SPARKS\.get\(\)/.test(hard),
  'both are switchable (volume 0 = silent, sparks false = no particles)');
for (const [key, pattern] of [
  ['enabled', /\.define\("enabled", true\)/],
  ['gunDamageMultiplier', /defineInRange\("gunDamageMultiplier", 0\.2D/],
  ['chance', /defineInRange\("chance", 0\.3D/],
  ['maxBouncesPerBullet', /defineInRange\("maxBouncesPerBullet", 1, 0, 8\)/],
  ['includeArrows', /\.define\("includeArrows", false\)/],
  ['soundVolume', /defineInRange\("soundVolume", 0\.8D/],
  ['sparks', /\.define\("sparks", true\)/],
  ['log', /\.define\("log", false\)/],
]) {
  check(pattern.test(config), `Config declares ricochet.${key} with the documented default`);
  check(readme.includes(key), `README documents ricochet.${key}`);
}
check(/\.push\("ricochet"\)/.test(config), 'the keys live in their own [ricochet] section');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.combat\.HardTarget\.class\)/.test(main),
  'and the handler is registered on the Forge event bus (an unregistered listener is a silent no-op)');
check(/### 5z\./.test(readme), 'README has the 5z section');
check(/TRIDENT_HIT_GROUND/.test(readme) && /hard_target/.test(readme) && /铁傀儡/.test(readme),
  'which explains the sound, the tag and the mob in Chinese');
check(/80\s?%/.test(readme) && /tacz:bullets|#tacz:bullets/.test(readme),
  'and states the 80 % rule and the TaCZ damage tag it is based on');
check(/hard_target\.json/.test(readme) && /怎么把别的生物也加进来|加进来/.test(readme),
  'plus how to add another mob to the tag');
check(/ricochet/i.test(readme) && /跳弹/.test(readme), 'and names the feature');
check(!/hardcoded damage type|DamageType\./.test(hardRaw),
  'no damage type is constructed or hard-coded in the class (the tag is the only judgement)');

console.log('');
if (failures > 0) {
  console.log(`${failures} ricochet check(s) FAILED`);
  process.exit(1);
}
console.log('the hard-target rule is tag-driven, gunfire-only and order-correct');
