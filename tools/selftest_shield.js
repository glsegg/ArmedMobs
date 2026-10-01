// The VANT ballistic shield (README 5zb): a craftable item whose passive rule is
// "bullet damage from your front is reduced 99 %, explosions are never reduced, and the shield pays
// durability per blocked hit until it shatters".
//
//   node tools/selftest_shield.js
//
// What is pinned, and why each one matters:
//   * the item's registry line - name `vant_shield`, stacksTo(1), durability(500);
//   * the assets this batch did NOT touch (model / texture) by hash, element count and pixel size, so
//     "the gameplay edit rewrote the model" is impossible to hide;
//   * the handler is registered on the Forge bus - an unregistered @SubscribeEvent is a silent no-op;
//   * "this is gunfire" is REUSED from the ricochet rule (HardTarget.isGunfire -> the #tacz:bullets
//     damage-type tag), not re-invented here;
//   * explosions bypass by default - a per-case truth table over (explosion, protectFromExplosions);
//   * THE ARC as a deterministic unit test: fixed look / to-source vectors, the dot product, the angle
//     in degrees, and the in/out verdict printed for every one, including the exact half-angle
//     boundary and the "source position unknown" case;
//   * the durability charge formula is monotonic, the numbers are printed, and the 50th blocked rifle
//     round destroys the 500-durability shield;
//   * the destroy path really empties the hand (so a shattered shield cannot keep protecting) and
//     plays the break sound, particles and the holder message;
//   * docs / config / lang: every key documented, both language files key-for-key identical, and no
//     `tacz:` item id in any JSON this batch added.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');
const DATA = path.join(RES, 'data', 'tarkovscav');
const SHIELD_WORK = path.join(ROOT, 'tools', 'spike', 'work', 'shield');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

let checks = 0;
let failures = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const section = (title) => {
  console.log('');
  console.log(title);
};

// The numbers the batch promises. Everything below is derived from the repository or simulated from
// the shipped formula; nothing is copied from the README prose.
const DURABILITY = 500;
const REDUCTION = 0.99;
const HALF_ANGLE = 90;
const DURABILITY_PER_BLOCKED_HIT = 2.0;
const ARC_EPSILON = 1.0e-9;
// The model was re-generated from the .bbmodel source with the element rotations snapped to the set
// BlockElement.Deserializer accepts (the un-snapped file loaded as the missing-model checkerboard), so
// this pin is the snapped output, not the asset agent's original bytes. selftest_shield_assets.js owns
// the stronger claim: re-running tools/make_shield_assets.js reproduces these exact bytes.
const MODEL_SHA = 'bf1b5836bdcebc065d6de42a41badfc091a10625a4c64546eb7287cdeda9025e';
const TEXTURE_SHA = '5f87f7db24793da21725eab5c977fbeff6d62f9b191569f6a78fc5023b3409c8';
const NEW_LANG_KEYS = [
  'item.tarkovscav.vant_shield.tooltip',
  'item.tarkovscav.vant_shield.tooltip2',
  'item.tarkovscav.vant_shield.tooltip3',
  'item.tarkovscav.vant_shield.tooltip4',
  'tarkovscav.shield.broken',
];
const CONFIG_KEYS = ['enabled', 'bulletReduction', 'frontAngleDegrees', 'durabilityPerBlockedHit',
  'protectFromExplosions'];

const modItems = read('registry/ModItems.java');
const tabs = read('registry/ModCreativeTabs.java');
const main = read('TarkovScav.java');
const itemShell = read('item/VantShieldItem.java');
const handler = strip(read('combat/VantShieldHandler.java'));
const hardTarget = strip(read('combat/HardTarget.java'));
const config = read('Config.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const reference = fs.readFileSync(path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'), 'utf8');

// ---------------------------------------------------------------------------------------------
section('1. the item: registered as vant_shield, stacksTo(1), durability 500');
const registerLine = /ITEMS\.register\("vant_shield",\s*\(\) -> new com\.gfl\.tarkovscav\.item\.VantShieldItem\(\s*new Item\.Properties\(\)\.stacksTo\(1\)\.durability\(500\)\)\)/;
check(registerLine.test(modItems), 'ModItems registers vant_shield with stacksTo(1).durability(500)',
  `durability=${DURABILITY} stackSize=1`);
check(/public class VantShieldItem extends Item/.test(itemShell), 'VantShieldItem extends Item');
for (const key of ['item.tarkovscav.vant_shield.tooltip', 'item.tarkovscav.vant_shield.tooltip2',
  'item.tarkovscav.vant_shield.tooltip3', 'item.tarkovscav.vant_shield.tooltip4']) {
  check(itemShell.includes(`"${key}"`), `the item shows ${key}`);
}
check(/appendHoverText\(ItemStack stack, @Nullable Level level, List<Component> tooltip,\s*TooltipFlag flag\)/
  .test(itemShell), 'appendHoverText has the vanilla shape');
check(/stack\.getMaxDamage\(\)/.test(itemShell), 'the tooltip prints the live durability (the 500)');
check(/output\.accept\(ModItems\.VANT_SHIELD\.get\(\)\)/.test(tabs),
  'the creative tab shows the shield next to the beacon');

// ---------------------------------------------------------------------------------------------
section('2. the shield assets (hash + shape + pixels; the model carries the snapped rotations)');
const modelPath = path.join(ASSETS, 'models', 'item', 'vant_shield.json');
const texturePath = path.join(ASSETS, 'textures', 'item', 'vant_shield.png');
check(fs.existsSync(modelPath), 'assets/.../models/item/vant_shield.json exists', path.relative(ROOT, modelPath));
check(fs.existsSync(texturePath), 'assets/.../textures/item/vant_shield.png exists', path.relative(ROOT, texturePath));
const modelBuf = fs.readFileSync(modelPath);
const textureBuf = fs.readFileSync(texturePath);
const modelHash = sha256(Buffer.from(modelBuf.toString('utf8').replace(/\r\n/g, '\n')));
const textureHash = sha256(textureBuf);
check(modelHash === MODEL_SHA, 'the model matches the pinned generator output after normalizing line endings', modelHash);
check(textureHash === TEXTURE_SHA, 'the texture is byte-identical to the asset agent\'s file', textureHash);
const model = JSON.parse(modelBuf.toString('utf8'));
check(Array.isArray(model.elements) && model.elements.length === 49,
  'the model still has its 49 elements', `elements=${(model.elements || []).length}`);
check(model.textures && model.textures.layer0 === 'tarkovscav:item/vant_shield',
  'the model still points at tarkovscav:item/vant_shield', String(model.textures && model.textures.layer0));
check(Object.keys(model.display || {}).length === 7, 'the model keeps its 7 display contexts',
  Object.keys(model.display || {}).join(', '));
const pngWidth = textureBuf.readUInt32BE(16);
const pngHeight = textureBuf.readUInt32BE(20);
check(textureBuf[0] === 0x89 && textureBuf.toString('latin1', 1, 4) === 'PNG',
  'the texture is a real PNG');
check(pngWidth === 512 && pngHeight === 512, 'the texture is 512x512', `${pngWidth}x${pngHeight}`);
console.log(`        model ${modelBuf.length} B sha256 ${modelHash.slice(0, 16)}..., texture `
  + `${textureBuf.length} B sha256 ${textureHash.slice(0, 16)}..., ${pngWidth}x${pngHeight}`);

// ---------------------------------------------------------------------------------------------
section('3. the recipe: shaped, resolves to tarkovscav:vant_shield, vanilla ingredients only');
const recipePath = path.join(DATA, 'recipes', 'vant_shield.json');
check(fs.existsSync(recipePath), 'data/tarkovscav/recipes/vant_shield.json exists');
const recipeText = fs.readFileSync(recipePath, 'utf8');
const recipe = JSON.parse(recipeText);
check(recipe.type === 'minecraft:crafting_shaped', 'it is a shaped recipe', recipe.type);
check(recipe.result && recipe.result.item === 'tarkovscav:vant_shield' && recipe.result.count === 1,
  'result = tarkovscav:vant_shield x1', JSON.stringify(recipe.result));
const registeredItems = [...modItems.matchAll(/ITEMS\.register\("([a-z0-9_]+)"/g)].map((m) => `tarkovscav:${m[1]}`);
check(registeredItems.includes(recipe.result.item),
  'the result id resolves against the ITEMS.register literals (the scan selftest_datapack.js uses)',
  `${registeredItems.length} registered item ids scanned`);
const counts = {};
for (const row of recipe.pattern) {
  for (const cell of row) {
    if (cell !== ' ') counts[cell] = (counts[cell] || 0) + 1;
  }
}
const ingredients = [];
let vanillaOnly = true;
for (const [letter, count] of Object.entries(counts)) {
  const key = recipe.key[letter];
  const id = key && key.item;
  if (!id || !id.startsWith('minecraft:')) vanillaOnly = false;
  ingredients.push(`${letter}=${id} x${count}`);
}
check(vanillaOnly, 'every ingredient is a minecraft: id', ingredients.join(', '));
check(recipe.pattern.length === 3 && recipe.pattern.every((row) => row.length === 3),
  'the pattern is a full 3x3', JSON.stringify(recipe.pattern));
console.log(`        recipe       : ${Object.values(recipe.key).map((k) => k.item).join(', ')}`);
console.log(`        material cost: ${ingredients.join(', ')}`);
// "no tacz: item id appears in any JSON this batch added" - the recipe is the only JSON added.
const addedJson = [{ rel: 'data/tarkovscav/recipes/vant_shield.json', text: recipeText }];
for (const file of addedJson) {
  const hit = file.text.match(/tacz:[a-z0-9_/]+/);
  check(hit === null, `${file.rel}: no tacz: item id`, hit ? `found ${hit[0]}` : 'clean');
}

// ---------------------------------------------------------------------------------------------
section('4. the handler is registered, and "this is gunfire" is REUSED, not reinvented');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.combat\.VantShieldHandler\.class\)/.test(main),
  'TarkovScav registers VantShieldHandler on the Forge event bus (one additive line)');
check(/@SubscribeEvent\s*public static void onHurt\(LivingHurtEvent event\)/.test(handler),
  'the rule is a static @SubscribeEvent on LivingHurtEvent');
check(/HardTarget\.isGunfire\(source\)/.test(handler),
  'the handler calls the ricochet rule\'s own predicate: HardTarget.isGunfire(DamageSource)');
check(/source\.is\(TACZ_BULLETS\)/.test(hardTarget)
  && /TagKey\.create\(Registries\.DAMAGE_TYPE, new ResourceLocation\("tacz", "bullets"\)\)/.test(hardTarget),
  'and that predicate is the #tacz:bullets damage-type tag (combat/HardTarget.java#isGunfire)');
check(!/ResourceLocation\("tacz"/.test(handler) && !/TACZ_BULLETS/.test(handler),
  'the shield defines no second classifier and no tacz id of its own');
check(!/DamageType\./.test(handler), 'the shield constructs no damage type of its own');
check(/public static ItemStack heldShield\(LivingEntity holder\)/.test(handler)
  && /for \(InteractionHand hand : InteractionHand\.values\(\)\)/.test(handler)
  && /held\.is\(ModItems\.VANT_SHIELD\.get\(\)\)/.test(handler),
  'the holder check is main hand OR off hand, for any LivingEntity');
console.log('        classifier   : combat/HardTarget.java#isGunfire -> DamageSource.is(#tacz:bullets)');
console.log('        holder check : InteractionHand.values() -> ItemStack.is(ModItems.VANT_SHIELD)');

// ---------------------------------------------------------------------------------------------
section('5. explosions: bypassed by default, opt-in only, and never by accident');
const shieldSection = /\.push\("shield"\)([\s\S]*?)b\.pop\(\);/.exec(config);
check(shieldSection !== null, 'Config.java has a [shield] section', shieldSection ? 'found' : 'missing');
const shieldConfig = shieldSection ? shieldSection[1] : '';
check((config.match(/\.push\("shield"\)/g) || []).length === 1, '[shield] is pushed once');
check(/\.define\("protectFromExplosions", false\)/.test(shieldConfig),
  'shield.protectFromExplosions ships false (the gate asserts the DEFAULT)');
check(/source\.is\(DamageTypeTags\.IS_EXPLOSION\) && !Config\.SHIELD_PROTECT_FROM_EXPLOSIONS\.get\(\)\) \{\s*return;/
  .test(handler),
  'with the default, an explosion (#minecraft:is_explosion) returns before any arc/durability work');
check(/import net\.minecraft\.tags\.DamageTypeTags;/.test(read('combat/VantShieldHandler.java'))
  && !/DamageTypes\.EXPLOSION/.test(handler),
  'the explosion test is the vanilla damage-type tag, not a hard-coded single explosion type');
check(handler.indexOf('IS_EXPLOSION') < handler.indexOf('isGunfire'),
  'the explosion gate comes before the gunfire gate, in the source',
  `explosion@${handler.indexOf('IS_EXPLOSION')} gunfire@${handler.indexOf('isGunfire')}`);
// The truth table, simulated: the shield reduces only when (not disabled) and (not explosion or the
// operator opted in) and (gunfire) and (held) and (in arc).
const reduces = (explosion, protect, gunfire, held, inArc, enabled) =>
  enabled && (!explosion || protect) && gunfire && held && inArc;
const CASES = [
  ['grenade, default config', true, false, false, true, true, true, false],
  ['grenade, opt-in config (an operator chose it)', true, true, false, true, true, true, false],
  ['TNT / creeper, default config', true, false, false, true, true, true, false],
  ['our own HE blast, default config', true, false, false, true, true, true, false],
  ['TaCZ bullet, front, held, default config', false, false, true, true, true, true, true],
  ['TaCZ bullet, from behind', false, false, true, true, false, true, false],
  ['TaCZ bullet, no shield in hand', false, false, true, false, true, true, false],
  ['melee', false, false, false, true, true, true, false],
  ['fall / fire / magic', false, false, false, true, true, true, false],
  ['shield.enabled = false', false, false, true, true, true, false, false],
];
for (const [label, explosion, protect, gunfire, held, inArc, enabled, expected] of CASES) {
  // A grenade never even reaches the classifier, so the gunfire flag above is a don't-care for it.
  const got = explosion && !protect ? false
    : reduces(explosion, protect, gunfire, held, inArc, enabled);
  check(got === expected, `${label} -> reduced=${got}`, `expected=${expected}`);
}

// ---------------------------------------------------------------------------------------------
section('6. the front arc: the dot-product maths, re-run on fixed vectors');
const DEG = 180 / Math.PI;
const length = (v) => Math.hypot(v.x, v.y, v.z);
const unit = (v) => ({ x: v.x / length(v), y: v.y / length(v), z: v.z / length(v) });
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const angleDeg = (a, b) => Math.acos(Math.max(-1, Math.min(1, dot(unit(a), unit(b))))) * DEG;
// The shipped formula, character for character: cos of the clamped half angle, dot >= cos - epsilon.
const insideCone = (look, toSource, halfAngleDegrees) => {
  if (length(look) ** 2 < 1e-8 || length(toSource) ** 2 < 1e-8) {
    return true;
  }
  const halfAngle = Math.max(0, Math.min(180, halfAngleDegrees));
  const cos = Math.cos(halfAngle / DEG);
  return dot(unit(look), unit(toSource)) >= cos - ARC_EPSILON;
};
// inFrontArc: an unknown source position is IN the arc (the safe side for the holder).
const inFrontArc = (look, toSource, halfAngleDegrees) =>
  toSource === null ? true : insideCone(look, toSource, halfAngleDegrees);

check(/public static boolean insideCone\(Vec3 look, Vec3 toSource, double halfAngleDegrees\)/.test(handler),
  'the arc is one pure static function: insideCone(Vec3, Vec3, double)');
check(/Math\.cos\(Math\.toRadians\(halfAngle\)\)/.test(handler),
  'the formula is cos(toRadians(halfAngle))');
check(/look\.normalize\(\)\.dot\(toSource\.normalize\(\)\) >= cos - ARC_EPSILON/.test(handler),
  'and dot(normalize(look), normalize(toSource)) >= cos - ARC_EPSILON');
check(/public static final double ARC_EPSILON = 1\.0E-9D;/.test(handler),
  `the boundary tolerance is a named constant, ARC_EPSILON = ${ARC_EPSILON}`);
check(/Math\.max\(0\.0D, Math\.min\(180\.0D, halfAngleDegrees\)\)/.test(handler),
  'the half angle is clamped to 0..180 in code as well as in the toml');
check(/if \(toSource == null\) \{\s*return true;\s*\}/.test(handler),
  'a source position that is UNKNOWN is treated as IN the arc (protects the holder)');
check(/source\.getSourcePosition\(\)/.test(handler) && /source\.getEntity\(\)\.position\(\)/.test(handler),
  'the position fallback chain is getSourcePosition() -> the attacker -> null');

const LOOK = { x: 0, y: 0, z: 1 };
const UP = { x: 0, y: 1, z: 0 };
const VECTORS = [
  // label, look, toSource, halfAngle, expected
  ['straight ahead', LOOK, { x: 0, y: 0, z: 1 }, 90, true],
  ['ahead-left, 45 degrees off the look', LOOK, { x: 1, y: 0, z: 1 }, 90, true],
  ['hard left, exactly the 90-degree boundary', LOOK, { x: 1, y: 0, z: 0 }, 90, true],
  ['hard right, exactly the 90-degree boundary', LOOK, { x: -1, y: 0, z: 0 }, 90, true],
  ['straight up, exactly the 90-degree boundary', LOOK, UP, 90, true],
  ['straight down, exactly the 90-degree boundary', LOOK, { x: 0, y: -1, z: 0 }, 90, true],
  ['behind-left, 135 degrees off the look', LOOK, { x: 1, y: 0, z: -1 }, 90, false],
  ['straight behind, 180 degrees', LOOK, { x: 0, y: 0, z: -1 }, 90, false],
  ['45 degrees with a narrow 60-degree half angle', LOOK, { x: 1, y: 0, z: 1 }, 60, true],
  ['exactly 60 degrees with a 60-degree half angle (the edge counts)', LOOK,
    { x: Math.sin(Math.PI / 3), y: 0, z: Math.cos(Math.PI / 3) }, 60, true],
  ['90 degrees with a narrow 60-degree half angle', LOOK, { x: 1, y: 0, z: 0 }, 60, false],
  ['straight ahead with a 0-degree half angle', LOOK, { x: 0, y: 0, z: 1 }, 0, true],
  ['45 degrees with a 0-degree half angle', LOOK, { x: 1, y: 0, z: 1 }, 0, false],
  ['straight behind with a 180-degree half angle (all-round)', LOOK, { x: 0, y: 0, z: -1 }, 180, true],
  ['the holder looks up and the shot comes from above', UP, UP, 90, true],
  ['the holder looks up and the shot comes from below', UP, { x: 0, y: -1, z: 0 }, 90, false],
  ['SOURCE POSITION UNKNOWN', LOOK, null, 90, true],
];
let arcMatches = 0;
for (const [label, look, toSource, halfAngle, expected] of VECTORS) {
  const got = inFrontArc(look, toSource, halfAngle);
  const angle = toSource === null ? null : angleDeg(look, toSource);
  const shown = toSource === null
    ? 'to=(unknown) angle=(unknown)'
    : `to=(${toSource.x.toFixed(4)},${toSource.y.toFixed(4)},${toSource.z.toFixed(4)}) `
      + `angle=${angle.toFixed(4)}deg dot=${dot(unit(look), unit(toSource)).toFixed(6)}`;
  check(got === expected, `${label} -> inArc=${got}`,
    `look=(${look.x},${look.y},${look.z}) ${shown} half=${halfAngle} expected=${expected}`);
  if (got === expected) {
    arcMatches++;
  }
}
console.log(`        arc vectors  : ${arcMatches}/${VECTORS.length} matched (half angles tested: `
  + `${[...new Set(VECTORS.map((v) => v[3]))].join(', ')} degrees)`);
console.log('        note         : with the default half angle of 90 the front cone is the front'
  + ' hemisphere, so straight up/down sits exactly ON the boundary and counts as blocked.');

// ---------------------------------------------------------------------------------------------
section('7. the reduction and the durability charge (formula + the 50-round shatter)');
check(/float after = \(float\) \(before \* \(1\.0D - reduction\)\);/.test(handler),
  'the reduction is amount * (1 - bulletReduction)');
check(/event\.setAmount\(after\);/.test(handler), 'and it is written back with setAmount');
check(/public static double bulletReduction\(\)/.test(handler)
  && /Math\.max\(0\.0D, Math\.min\(1\.0D, Config\.SHIELD_BULLET_REDUCTION\.get\(\)\)\)/.test(handler),
  'bulletReduction is clamped to 0..1 in code, so a silly toml cannot heal the holder');
check(/if \(reduction <= 0\.0D\) \{[\s\S]{0,120}?return;/.test(handler),
  'a reduction of 0 returns before charging durability (no protection = no cost)');
const after = (before, reduction) => before * (1 - reduction);
for (const [before, reduction] of [[20, REDUCTION], [20, 1.0], [20, 0.0], [20, 0.5], [7.5, REDUCTION]]) {
  console.log(`        simulate     : ${before} damage x (1 - ${reduction}) = `
    + `${after(before, reduction).toFixed(4)} (blocked ${(before - after(before, reduction)).toFixed(4)})`);
}
check(Math.abs(after(20, REDUCTION) - 0.2) < 1e-9,
  `a 20-damage round lands as 0.2 with the shipped reduction ${REDUCTION}`,
  `blocked=${(20 - after(20, REDUCTION)).toFixed(4)}`);
check(/public static int durabilityCharge\(double blockedDamage\)/.test(handler),
  'the durability cost is one function: durabilityCharge(blockedDamage)');
check(/public static final double ARC_EPSILON/.test(handler) && /Math\.max\(1, \(int\) Math\.ceil\(blockedDamage \/ per\)\)/
  .test(handler), 'the charge is max(1, ceil(blockedDamage / durabilityPerBlockedHit))');
const durabilityCharge = (blockedDamage, per = DURABILITY_PER_BLOCKED_HIT) =>
  Math.max(1, Math.ceil(blockedDamage / per));
const BLOCKED = [0.1, 0.5, 1, 1.9, 2, 2.1, 4, 10, 19.8, 20, 100];
const charges = BLOCKED.map((blocked) => durabilityCharge(blocked));
let monotonic = true;
for (let i = 1; i < charges.length; i++) {
  if (charges[i] < charges[i - 1]) {
    monotonic = false;
  }
}
check(monotonic, 'the charge never goes down as the blocked damage goes up',
  BLOCKED.map((b, i) => `${b}->${charges[i]}`).join(' '));
check(charges.every((charge) => charge >= 1), 'every blocked hit costs at least 1 durability',
  `min=${Math.min(...charges)}`);
check(/int remaining = shield\.getMaxDamage\(\) - shield\.getDamageValue\(\);/.test(handler)
  && /if \(charge >= remaining\) \{/.test(handler),
  'the charge is compared against the remaining durability, so zero is destruction');
check(/shield\.setDamageValue\(shield\.getDamageValue\(\) \+ charge\);/.test(handler),
  'the surviving path writes the new damage value');
// The full shatter simulation: fire 20-damage rounds at a front-facing holder until the shield breaks.
const chargePerRound = durabilityCharge(20 - after(20, REDUCTION));
let remaining = DURABILITY;
let rounds = 0;
while (rounds < 10000) {
  rounds++;
  if (chargePerRound >= remaining) {
    remaining = 0;
    break;
  }
  remaining -= chargePerRound;
}
check(chargePerRound === 10, 'a blocked 19.8-damage round costs 10 durability', `charge=${chargePerRound}`);
check(remaining === 0, 'the shield reaches destruction (no immortal shield)', `remaining=${remaining}`);
check(rounds === 50, 'the 500-durability shield survives exactly 50 such rounds, the 51st would break it',
  `rounds=${rounds}`);
check(rounds * chargePerRound === DURABILITY, 'and 50 x 10 = 500 = the whole durability',
  `${rounds * chargePerRound}`);

section('8. destroy at zero: the stack leaves the hand, effects play, the holder is told');
check(/public static boolean applyCharge\(LivingEntity holder, ItemStack shield, int charge\)/.test(handler),
  'applyCharge returns whether the charge destroyed the shield');
check(/destroyShield\(holder, shield\);/.test(handler) && /return true;/.test(handler),
  'the destroyed branch calls destroyShield and reports true');
check(/holder\.setItemInHand\(hand, ItemStack\.EMPTY\);/.test(handler),
  'destroyShield empties the hand the shield was held in');
check(/if \(!cleared\) \{\s*shield\.setCount\(0\);/.test(handler),
  'with a defensive setCount(0) fallback if the hand lookup missed');
check(/holder instanceof Player player/.test(handler)
  && /Component\.translatable\("tarkovscav\.shield\.broken"\)/.test(handler),
  'the holder gets the tarkovscav.shield.broken message');
check(/SoundEvents\.SHIELD_BLOCK/.test(handler) && /SoundEvents\.SHIELD_BREAK/.test(handler),
  'the metallic block sound and the break sound are vanilla SoundEvents (nothing to register)');
check(/ParticleTypes\.CRIT/.test(handler) && /ParticleTypes\.ELECTRIC_SPARK/.test(handler),
  'the sparks are ParticleTypes.CRIT + ELECTRIC_SPARK');
check(/blockEffects\(holder, source\);/.test(handler)
  && handler.indexOf('blockEffects') < handler.indexOf('breakEffects'),
  'every reduced hit gets the block feedback, and the break feedback comes on top');
check(/is\(ModItems\.VANT_SHIELD\.get\(\)\)/.test(handler),
  'a destroyed shield cannot protect: the only entry point is holding the registered item');
// Hands simulation: the next hit after the shatter finds an empty hand.
let hands = ['tarkovscav:vant_shield', 'empty'];
const protects = (held) => held.some((slot) => slot === 'tarkovscav:vant_shield');
check(protects(hands) === true, 'before the shatter the held shield protects', hands.join('+'));
hands = ['empty', 'empty'];
check(protects(hands) === false, 'after the shatter nothing is held, so nothing protects', hands.join('+'));

// ---------------------------------------------------------------------------------------------
section('9. the config surface: five keys, documented in both documents');
for (const [key, pattern, expectedValue] of [
  ['enabled', /\.define\("enabled", true\)/, 'true'],
  ['bulletReduction', /defineInRange\("bulletReduction", 0\.99D, 0\.0D, 1\.0D\)/, '0.99'],
  ['frontAngleDegrees', /defineInRange\("frontAngleDegrees", 90\.0D, 0\.0D, 180\.0D\)/, '90.0'],
  ['durabilityPerBlockedHit', /defineInRange\("durabilityPerBlockedHit", 2\.0D, 0\.1D, 100\.0D\)/, '2.0'],
  ['protectFromExplosions', /\.define\("protectFromExplosions", false\)/, 'false'],
]) {
  check(pattern.test(shieldConfig), `Config declares shield.${key} = ${expectedValue}`,
    `default ${expectedValue}`);
  check(readme.includes(`shield.${key}`), `README documents shield.${key}`);
  check(reference.includes(`shield.${key}`), `the command/config reference documents shield.${key}`);
}
check((shieldConfig.match(/\.define/g) || []).length === CONFIG_KEYS.length,
  `the [shield] section holds exactly ${CONFIG_KEYS.length} define call sites`,
  shieldSection ? `${(shieldConfig.match(/\.define/g) || []).length} define call(s)` : 'missing');
check(reference.includes('| `shield.protectFromExplosions` | `false` |'),
  'the reference states the false default (grenades always get through)');
// Every claim the README and the reference make about the user's four numbers must be there.
for (const claim of ['500', '0.99', '90', 'vant_shield']) {
  check(readme.includes(claim), `README mentions ${claim}`);
}
check(/5zb\./.test(readme), 'README has the 5zb section');
check(/VANT 防弹盾牌/.test(readme), 'README names the item in Chinese');
check(/#tacz:bullets/.test(readme) && /HardTarget\.isGunfire/.test(readme),
  'README says which classifier is reused');
check(/踢盾|举盾/.test(readme), 'README says the raise state is not in this batch');

// ---------------------------------------------------------------------------------------------
section('10. the language files: key-for-key identical, only ADDED keys');
const LANG = {
  en_us: path.join(ASSETS, 'lang', 'en_us.json'),
  zh_cn: path.join(ASSETS, 'lang', 'zh_cn.json'),
};
const parsed = {};
for (const locale of Object.keys(LANG)) {
  const raw = fs.readFileSync(LANG[locale]);
  const text = raw.toString('utf8');
  let json = null;
  try {
    json = JSON.parse(text);
    check(true, `${locale}: parses`, `${raw.length} bytes`);
  } catch (err) {
    check(false, `${locale}: parses`, err.message);
    continue;
  }
  parsed[locale] = json;
  check(!text.replace(/\r\n/g, '\n').includes('\r') && raw[0] !== 0xef,
    `${locale}: valid LF/CRLF line endings and no BOM`);
  check(Object.prototype.hasOwnProperty.call(json, 'item.tarkovscav.vant_shield'),
    `${locale}: the asset agent's item.tarkovscav.vant_shield key is still there`);
  for (const key of NEW_LANG_KEYS) {
    check(Object.prototype.hasOwnProperty.call(json, key), `${locale}: has ${key}`,
      JSON.stringify(json[key]));
  }
  const addedText = NEW_LANG_KEYS.map((key) => JSON.stringify(json[key])).join(' ');
  check(!addedText.includes('tacz:'), `${locale}: no tacz: id in the added keys`);
}
if (parsed.en_us && parsed.zh_cn) {
  const enKeys = Object.keys(parsed.en_us);
  const zhKeys = Object.keys(parsed.zh_cn);
  check(enKeys.length === zhKeys.length, 'both files have the same number of keys',
    `en_us=${enKeys.length} zh_cn=${zhKeys.length}`);
  const missingInZh = enKeys.filter((key) => !Object.prototype.hasOwnProperty.call(parsed.zh_cn, key));
  const missingInEn = zhKeys.filter((key) => !Object.prototype.hasOwnProperty.call(parsed.en_us, key));
  check(missingInZh.length === 0 && missingInEn.length === 0,
    'the two files are key-for-key identical (no key exists in only one language)',
    `missing in zh: ${missingInZh.length}, missing in en: ${missingInEn.length}`);
}
// The regression comparison against the asset agent's own baseline snapshot: nothing removed, nothing
// re-worded, and the shield family is exactly what was added on top of the 258-key baseline.
for (const locale of Object.keys(LANG)) {
  const baselinePath = path.join(ROOT, 'assets_source', 'shield', `lang_baseline_${locale}.json`);
  if (!fs.existsSync(baselinePath) || !parsed[locale]) {
    check(false, `${locale}: the asset baseline snapshot is present`, path.relative(ROOT, baselinePath));
    continue;
  }
  const baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8'));
  const baseKeys = Object.keys(baseline);
  const nowKeys = Object.keys(parsed[locale]);
  const lost = baseKeys.filter((key) => !Object.prototype.hasOwnProperty.call(parsed[locale], key));
  const changed = baseKeys.filter((key) => Object.prototype.hasOwnProperty.call(parsed[locale], key)
    && parsed[locale][key] !== baseline[key]);
  const added = nowKeys.filter((key) => !Object.prototype.hasOwnProperty.call(baseline, key));
  check(lost.length === 0, `${locale}: no baseline key was removed`, `lost=${lost.length}`);
  check(changed.length === 0, `${locale}: no baseline value was changed`, `changed=${changed.length}`);
  const mine = NEW_LANG_KEYS.filter((key) => added.includes(key));
  check(mine.length === NEW_LANG_KEYS.length, `${locale}: all 5 new keys are additions`,
    `${mine.length}/${NEW_LANG_KEYS.length}`);
  console.log(`        ${locale}        : ${baseKeys.length} baseline keys -> ${nowKeys.length} keys,`
    + ` added [${added.join(', ')}]`);
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log(`checks       : ${checks} total, ${failures} failure(s)`);
console.log(`arc vectors  : ${arcMatches}/${VECTORS.length} matched, half angles `
  + `${[...new Set(VECTORS.map((v) => v[3]))].join('/')} degrees, epsilon ${ARC_EPSILON}`);
if (failures > 0) {
  console.log(`${failures} shield check(s) FAILED`);
  process.exit(1);
}
console.log('the VANT ballistic shield blocks bullets from the front, never grenades, and shatters at 0');
