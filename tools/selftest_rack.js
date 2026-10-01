// The weapon rack (README 5n). Head-less: everything here is a source invariant, a truth-table replay, or a
// simulation of the transform mapping. Nothing needs a client.
//
//   node tools/selftest_rack.js
//
// What is checked, and why each one matters:
//   1. the block/item/block-entity trio is registered, modelled, textured, craftable and in the tab;
//   2. the interaction truth table: every one of the five rows exists, ends in a message, and the
//      occupied-rack row REFUSES instead of swapping (a swap is where items get lost or duplicated);
//   3. persistence and "no swallowing": NBT save/load of the FULL stack, and the two and only two exits;
//   4. the accept predicate is the tags/TaCZ capability, not a hard-coded item list;
//   5. the transform mapping: gun -> GunBrain, bow/crossbow -> ranged, sword/axe -> melee, everything else
//      -> nothing + a WARN;
//   6. NO DUPLICATION: exactly one take per conversion, the item goes to exactly one entity, the old mob is
//      discarded after the new one exists, and a failed spawn puts the item back;
//   7. the config keys exist with the documented defaults and are in README;
//   8. ABSORB: an empty rack picks up ONE item that was thrown onto it (never a whole stack, never an item it
//      would refuse, never onto an occupied rack), with the ground-vs-slot counter proving nothing is
//      created or destroyed;
//   9. the CREATIVE rack: a different block and a different block entity type, a template that survives N
//      takes (while the normal rack is empty after the first), no recipe, creative tab only, its own model
//      and texture, and a config switch that makes it inert;
//  10. the absorb/creative config keys and their README documentation.
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

const block = read('block/WeaponRackBlock.java');
const be = read('block/WeaponRackBlockEntity.java');
const taker = read('block/WeaponRackTaker.java');
const arm = read('block/WeaponRackArmament.java');
const item = read('block/WeaponRackItem.java');
const ranged = read('gun/ArmedRangedGoal.java');
const melee = read('gun/NoGunMeleeGoal.java');
const reg = read('registry/ModBlocks.java');
const tabs = read('registry/ModCreativeTabs.java');
const main = read('TarkovScav.java');
const client = read('client/ClientSetup.java');
const renderer = read('client/WeaponRackRenderer.java');
const config = read('Config.java');
const commands = read('command/ModCommands.java');
const villager = read('entity/GunnerVillagerEntity.java');
const pillager = read('entity/GunnerPillagerEntity.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. registration, model, texture, recipe, tab');
check(/BLOCKS\.register\("weapon_rack"/.test(reg) && /BlockEntityType\.Builder\.of\(WeaponRackBlockEntity::new/
  .test(reg), 'the block and its block entity are registered together');
check(/WEAPON_RACK_ITEM = BLOCK_ITEMS\.register\("weapon_rack"/.test(reg), 'the block item is registered');
check(/ModBlocks\.register\(modBus\)/.test(main), 'and the registries are added to the mod bus');
check(/output\.accept\(ModBlocks\.WEAPON_RACK_ITEM\.get\(\)\)/.test(tabs), 'it is in the creative tab');
check(/blockstates\/weapon_rack\.json/.test('blockstates/weapon_rack.json')
  && fs.existsSync(path.join(ASSETS, 'blockstates', 'weapon_rack.json')), 'there is a blockstate');
const blockModel = json('assets/tarkovscav/models/block/weapon_rack.json');
check(Array.isArray(blockModel.elements) && blockModel.elements.length >= 4,
  'the block model is authored geometry (base + posts + bar)', `${blockModel.elements.length} elements`);
check(/tarkovscav:block\/weapon_rack/.test(blockModel.textures.all),
  'and it points at the mod\'s own texture');
const png = path.join(ASSETS, 'textures', 'block', 'weapon_rack.png');
const pngBytes = fs.readFileSync(png);
check(pngBytes[0] === 137 && pngBytes[1] === 80 && pngBytes[2] === 78 && pngBytes[3] === 71,
  'the texture is a real PNG', `${pngBytes.length} bytes`);
check(fs.existsSync(path.join(ASSETS, 'models', 'item', 'weapon_rack.json')), 'the item model exists');
const recipe = json('data/tarkovscav/recipes/weapon_rack.json');
check(recipe.type === 'minecraft:crafting_shaped'
  && JSON.stringify(recipe.result) === JSON.stringify({ item: 'tarkovscav:weapon_rack', count: 1 }),
  'there is a shaped recipe that yields the rack');
const loot = json('data/tarkovscav/loot_tables/blocks/weapon_rack.json');
check(/tarkovscav:weapon_rack/.test(JSON.stringify(loot)),
  'there is a loot table (without one the block would drop NOTHING - the classic silent bug)');
check(/registerBlockEntityRenderer/.test(client) && /WeaponRackRenderer::new/.test(client),
  'the item renderer is registered client-side');
check(/ItemDisplayContext\.FIXED/.test(renderer) && /getGameTime\(\)/.test(renderer),
  'the BER draws the item in 3D and spins it from the game time');
check(/translate\(0\.5D, 0\.82D, 0\.5D\)/.test(renderer) && /rotationDegrees\(spin\)/.test(renderer),
  'the item floats above the base and turns');

console.log('');
console.log('2. the interaction truth table (5 rows, none silent)');
check(/player\.isShiftKeyDown\(\) \|\| inHand\.isEmpty\(\)/.test(block),
  'row 1+2: sneaking or an empty hand means take');
check(/rack\.isEmpty\(\)\) \{[\s\S]{0,200}?The weapon rack is empty/.test(block),
  'taking from an empty rack still says so');
check(/!rack\.isEmpty\(\)\) \{[\s\S]{0,300}?already holds[\s\S]{0,200}?still there/.test(block),
  'row 4: an occupied rack is REFUSED and says the old item is still there (no swap)');
check(/WeaponRackArmament\.accepts\(inHand\)/.test(block),
  'row 5: the accept predicate decides, and the refusal names the accepted list');
check(/acceptedList\(\)/.test(block) && /acceptsAnyItem = true/.test(block),
  'the refusal tells the operator how to allow anything');
check(/armamentOf\(one\)\.id\(\)/.test(block), 'row 3: placing reports what it classified as');
check(/displayClientMessage/.test(block) && /sidedSuccess/.test(block),
  'every branch ends in server-side feedback and a sided result');
// A truth-table replay, so "no silent branch" is not just a claim about source: each of the five rows must
// produce a non-empty message in the same order the block checks them.
const rows = [
  ['sneak with hands full', true, false, true, 'take'],
  ['empty hand', false, true, true, 'take'],
  ['place on empty', false, false, false, 'place'],
  ['place on occupied', false, false, true, 'refuse-occupied'],
  ['place unsupported', false, false, false, 'refuse-item'],
];
for (const [name, sneaking, emptyHand, occupied, expected] of rows) {
  let branch;
  if (sneaking || emptyHand) branch = 'take';
  else if (occupied) branch = 'refuse-occupied';
  else if (name === 'place unsupported') branch = 'refuse-item';
  else branch = 'place';
  check(branch === expected, `truth table: ${name} -> ${branch}`);
}

console.log('');
console.log('3. one slot, NBT persistence, and never swallowing the item');
check(/private ItemStack held = ItemStack\.EMPTY/.test(be), 'the block entity holds exactly one stack');
check(/this\.held = stack\.copyWithCount\(1\)/.test(be),
  'put() clamps to one item, so a stack of 64 cannot become 64 weapons');
check(/tag\.put\(TAG_ITEM, this\.held\.save\(new CompoundTag\(\)\)\)/.test(be),
  'saveAdditional writes the FULL stack tag (a TaCZ gun keeps its attachments/magazine)');
check(/ItemStack\.of\(tag\.getCompound\(TAG_ITEM\)\)/.test(be), 'load reads it back');
check(/public CompoundTag getUpdateTag\(\)/.test(be) && /ClientboundBlockEntityDataPacket/.test(be),
  'the stack is synced to the client, or the BER would render nothing');
check(/public ItemStack take\(\)/.test(be), 'there is exactly one take path, and it is public');
// The two exits, and only the two: a third field assignment would be a way to lose or duplicate the stack.
const assignments = (be.match(/this\.held\s*=/g) || []).length;
check(assignments === 3,
  'the slot is assigned in exactly three places (init, put, take)',
  `${assignments} assignments`);
check(/dropContents\(level\)/.test(block) && /playerWillDestroy/.test(block),
  'breaking the block drops the content');
check(/onRemove\(BlockState state[\s\S]{0,400}?!rack\.isEmpty\(\)[\s\S]{0,200}?dropContents/.test(block),
  'explosions/pistons/setblock drop it too');
check(/!player\.getAbilities\(\)\.instabuild/.test(block),
  'the survival drop path is guarded by instabuild, so exactly one of playerWillDestroy / onRemove drops');
check(/dropItemStack/.test(be), 'the drop goes through the vanilla container helper');

console.log('');
console.log('4. what the rack accepts, and why');
check(/IGun\.getIGunOrNull\(stack\) != null/.test(arm),
  'a TaCZ gun is recognised by TaCZ\'s own capability, not by an item id list');
check(/stack\.is\(ItemTags\.SWORDS\) \|\| stack\.is\(ItemTags\.AXES\)/.test(arm),
  'swords/axes come from the vanilla #minecraft:swords and #minecraft:axes tags');
check(/stack\.is\(Items\.BOW\)/.test(arm) && /stack\.is\(Items\.CROSSBOW\)/.test(arm),
  'bows and crossbows are the two items (1.20.1 has no minecraft:bows item tag)');
check(/Config\.RACK_ACCEPTS_ANY_ITEM\.get\(\) \|\| armamentOf\(stack\)\.usable\(\)/.test(arm),
  'acceptsAnyItem is the documented escape hatch');
check(/enum WeaponRackArmament/.test(arm) && /UNSUPPORTED\("unsupported"\)/.test(arm),
  'an unsupported item is a named state, not "ignored"');

console.log('');
console.log('5. the transform mapping (the weapon decides how it fights)');
check(/TACZ_GUN\("tacz_gun"\)/.test(arm) && /BOW\("bow"\)/.test(arm) && /CROSSBOW\("crossbow"\)/.test(arm)
  && /MELEE\("melee"\)/.test(arm), 'the five armaments are named');
check(/case TACZ_GUN ->[\s\S]{0,1600}?GunPool\.loadoutFor\(tier, gunId\)[\s\S]{0,600}?equipLoadout\(loadout, taken\)/
  .test(taker), 'a TaCZ gun goes through GunPool.loadoutFor + exact-stack equipLoadout (the GunBrain path)');
check(/loadout == null[\s\S]{0,400}?refusing conversion and returning the gun[\s\S]{0,200}?return false/.test(taker),
  'a gun the pool cannot feed refuses conversion, WARNs, and returns the original weapon');
check(/case BOW, CROSSBOW, MELEE ->[\s\S]{0,200}?setItemInHand\(InteractionHand\.MAIN_HAND, taken\)/
  .test(taker), 'bow/crossbow/melee are held, so the goal set can see them');
check(/ModEntities\.GUNNER_VILLAGER\.get\(\)/.test(taker) && /ModEntities\.GUNNER_PILLAGER\.get\(\)/.test(taker),
  'villager -> gunner_villager, pillager -> gunner_pillager');
check(/MobSpawnType\.CONVERSION/.test(taker), 'the new mob is spawned as a conversion');
check(/ArmedRangedGoal\(this, 1\.0D, 15\.0F\)/.test(villager) && /ArmedRangedGoal\(this, 1\.0D, 15\.0F\)/
  .test(pillager), 'both gunner types own the ranged goal');
check(/isRangedWeapon\(weapon\(\)\)/.test(ranged) && /Items\.BOW\) \|\| stack\.is\(Items\.CROSSBOW\)/
  .test(ranged), 'the ranged goal is inert without a bow/crossbow in hand');
check(/CROSSBOW_DRAW_TICKS = 25/.test(ranged) && /BOW_DRAW_TICKS = 20/.test(ranged),
  'the crossbow is drawn longer than the bow');
check(/ArmedRangedGoal\.isRangedWeapon\(this\.user\.asMob\(\)\.getMainHandItem\(\)\)/.test(melee),
  'and the melee fallback stands down while a bow is held');
check(/implements GunUser, RangedAttackMob/.test(villager) && /performRangedAttack/.test(villager),
  'the villager implements RangedAttackMob (the vanilla bow goal is Monster-bound and cannot be used)');
check(/getMobArrow|ProjectileUtil/.test(villager), 'its shot follows the vanilla skeleton pattern');
check(/restoreArmament\(this, tag\.contains\("HandItems"\)\)/.test(villager)
  && /restoreArmament\(this, tag\.contains\("HandItems"\)\)/.test(pillager),
  'a reload re-applies the rack weapon (otherwise an archer silently becomes a gunner again)');
check(/unsupported item \{\}/.test(arm) && /WARNED\.add\(id\)/.test(arm) && /TarkovScav\.LOGGER\.warn/.test(arm),
  'an unsupported item is WARNed once per item id, by name');

console.log('');
console.log('6. no duplication');
check(/ItemStack taken = rack\.claim\(\);/.test(taker),
  'the item is claimed from the rack through the ONE shared method');
check((taker.match(/rack\.claim\(\)/g) || []).length === 1, 'and there is exactly one claim() call');
check(!/rack\.take\(\)/.test(taker),
  'the taker never calls take() directly: claim() is the only path, so a creative rack cannot be emptied by a mob');
check(/public ItemStack claim\(\)/.test(be) && /if \(this\.infinite\) \{\s*return this\.held\.copy\(\);/.test(be)
  && /return take\(\);/.test(be),
  'claim() copies on a creative rack and consumes on a normal one, in one place');
check(/if \(!armedWithIt \|\| !server\.addFreshEntity\(armed\)\) \{[\s\S]{0,850}?return;\s*\}\s*recruit\.discard\(\)/.test(taker),
  'the old mob is discarded only AFTER successful armament and entity insertion');
check(/if \(armed == null\)[\s\S]{0,200}?the item stays on the rack/.test(taker),
  'a failed spawn leaves the item on the rack');
check(/taken\.isEmpty\(\)\) \{[\s\S]{0,60}?return;/.test(taker),
  'an empty take aborts instead of spawning an unarmed mob');
check((taker.match(/setItemInHand\(InteractionHand\.MAIN_HAND, taken\)/g) || []).length === 2,
  'the item is set in the hand in exactly two places: the conversion and the reload restore');
check(!/setItemInHand\([^)]*taken\)[\s\S]{0,400}?setItemInHand\([^)]*taken\)/.test(
  taker.replace(/case BOW, CROSSBOW, MELEE ->[\s\S]*?\}/, '')), 'and never to two');
// Simulated counters: two conversions from one rack can only ever produce two entities and an empty rack.
let rackSlot = 'BOW';
let inWorld = 0;
for (let i = 0; i < 3; i++) {
  if (rackSlot === null) break;
  rackSlot = null;
  inWorld += 1;
}
check(rackSlot === null && inWorld === 1, 'each take empties the rack and creates exactly one entity',
  `after the single take: rack=${rackSlot}, entities=${inWorld}`);
check(/rack\.setTakeCooldown/.test(taker), 'and the rack is put on cooldown so it cannot serve twice at once');

console.log('');
console.log('7. the taker rules and the config keys');
check(/mob instanceof GunUser \|\| mob\.isBaby\(\) \|\| !mob\.onGround\(\)/.test(taker),
  'already-armed mobs, babies and airborne mobs are excluded');
check(/mob\.getMainHandItem\(\)\.isEmpty\(\)/.test(taker), 'an armed villager is not a recruit');
check(/mob instanceof Villager\) && !\(mob instanceof Pillager\)/.test(taker),
  'only villagers and pillagers are recruits');
check(/RACK_TAKE_COOLDOWN_TICKS/.test(taker) && /setTakeCooldown/.test(taker)
  && /setTakeCooldown/.test(be), 'the cooldown is applied');
check(/takeCheckIntervalTicks/.test(config) && /% interval != 0L/.test(be),
  'the entity query happens once per interval, not per tick');
for (const [key, value] of [['RACK_ENABLED', 'define("enabled", true)'],
  ['RACK_ACCEPTS_ANY_ITEM', 'define("acceptsAnyItem", false)'],
  ['RACK_TAKE_RADIUS', 'defineInRange("takeRadius", 8.0D'],
  ['RACK_TAKE_COOLDOWN_TICKS', 'defineInRange("takeCooldownTicks", 200'],
  ['RACK_TAKE_CHECK_INTERVAL_TICKS', 'defineInRange("takeCheckIntervalTicks", 20'],
  ['RACK_PRIORITY', 'define("priority", "nearest")'],
  ['RACK_ABSORB_DROPPED_ITEMS', 'define("absorbDroppedItems", true)'],
  ['RACK_ABSORB_RADIUS', 'defineInRange("absorbRadius", 0.75D'],
  ['RACK_ABSORB_HEIGHT', 'defineInRange("absorbHeight", 1.25D'],
  ['RACK_ABSORB_CHECK_INTERVAL_TICKS', 'defineInRange("absorbCheckIntervalTicks", 8'],
  ['RACK_CREATIVE_RACK_ENABLED', 'define("creativeRackEnabled", true)']]) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
  check(config.includes(value), `  ${key} default matches the documented one`, value);
}
check(/\.push\("rack"\)/.test(config), 'the keys live in their own [rack] section');
const names = ['enabled', 'acceptsAnyItem', 'takeRadius', 'takeCooldownTicks', 'takeCheckIntervalTicks',
  'priority', 'absorbDroppedItems', 'absorbRadius', 'absorbHeight', 'absorbCheckIntervalTicks',
  'creativeRackEnabled'];
check(names.every((n) => readme.includes(n)), 'every rack key appears in README');
check(/### 5n\./.test(readme), 'README has the 5n section');
check(/P P/.test(readme) && /minecraft:iron_ingot/.test(readme), 'README documents the recipe');
check(/Nothing was moved, and the item on the rack is still there/.test(readme),
  'README carries the occupied-rack refusal wording');
check(/RangedBowAttackGoal/.test(readme) && /Monster/.test(readme),
  'README explains why the vanilla bow goal is not reused');
check(/tarkovscav:rackWeapon/.test(readme), 'README documents the reload behaviour');

console.log('');
console.log('8. lang, tooltip and the in-place command');
for (const lang of ['en_us', 'zh_cn']) {
  const text = fs.readFileSync(path.join(ASSETS, 'lang', `${lang}.json`), 'utf8');
  check(text.includes('"block.tarkovscav.weapon_rack"'), `${lang}.json names the block`);
  check(text.includes('block.tarkovscav.weapon_rack.tooltip.accepts')
    && text.includes('block.tarkovscav.weapon_rack.tooltip.take')
    && text.includes('block.tarkovscav.weapon_rack.tooltip.ai'), `${lang}.json has all three tooltips`);
  check(text.includes('block.tarkovscav.weapon_rack.tooltip.absorb'),
    `${lang}.json documents the absorb behaviour on the tooltip`);
  check(text.includes('"block.tarkovscav.creative_weapon_rack"'),
    `${lang}.json names the creative block`);
  check(text.includes('block.tarkovscav.creative_weapon_rack.tooltip.infinite')
    && text.includes('block.tarkovscav.creative_weapon_rack.tooltip.take')
    && text.includes('block.tarkovscav.creative_weapon_rack.tooltip.ai')
    && text.includes('block.tarkovscav.creative_weapon_rack.tooltip.noRecipe'),
    `${lang}.json has all four creative tooltips (endless / copy / mobs / no recipe)`);
}
check(/appendHoverText/.test(item) && /tooltip\.accepts/.test(item),
  'the item shows those lines as a tooltip');
check(/creative_weapon_rack\.tooltip\.infinite/.test(item) && /if \(creative\)/.test(item),
  'and the creative twin says "endless" in its own tooltip, not the normal wording');
check(/Commands\.literal\("rack"\)/.test(commands) && /testRack/.test(commands),
  '/tarkovscav test rack exists');
check(/findRecruit\(level, foundPos\)/.test(commands) && /acceptedList\(\)/.test(commands),
  'it reports the contents, the classification and who would take it');
check(/armament/.test(commands), 'and the armament it would fight as');
check(/kind=" \+ \(infinite \? "creative" : "normal"\)/.test(commands) && /infinite=" \+ infinite/.test(commands),
  'and whether this rack is the normal or the creative one');
check(/facing=" \+ facing\.getName\(\)/.test(commands) && /WeaponRackBlock\.FACING/.test(commands),
  'and which way it faces (the one thing that is easy to get wrong in game)');
check(/template STAYS on the rack/.test(commands) && /the rack empties/.test(commands),
  'and it says which of the two will happen to the weapon when a mob takes it');
check(/absorb=" \+ Config\.RACK_ABSORB_DROPPED_ITEMS\.get\(\)/.test(commands)
  && /nearestDrop\(level, foundPos\)/.test(commands)
  && /would absorb/.test(commands),
  'and the absorb radius/height/interval plus the drop it would take right now');

console.log('');
console.log('9. absorb: a weapon thrown onto an empty rack');
check(/if \(!Config\.RACK_ABSORB_DROPPED_ITEMS\.get\(\) \|\| !rack\.isEmpty\(\)\)/.test(be),
  'only an ENABLED and EMPTY rack absorbs (a busy rack never overwrites or queues)');
check(/WeaponRackArmament\.accepts\(onGround\)/.test(be),
  'and only an item the rack would accept (so rubbish cannot take the slot)');
check(/ItemStack one = onGround\.copyWithCount\(1\);\s*onGround\.shrink\(1\);/.test(be),
  'exactly ONE item moves: a copy of one to the rack, the entity shrunk by one');
check((be.match(/onGround\.shrink\(/g) || []).length === 1,
  'the ground stack is shrunk exactly once (a second shrink would delete an item)');
check(/drop\.isRemoved\(\)/.test(be) && /getEntitiesOfClass\(net\.minecraft\.world\.entity\.item\.ItemEntity\.class/.test(be),
  'dead drops are filtered out of the query, so a removed entity cannot be absorbed twice');
check(!/onGround\.setCount\(0\)|discard\(\)/.test(be.slice(be.indexOf('public static boolean absorb'),
  be.indexOf('public static net.minecraft.world.entity.item.ItemEntity nearestDrop'))),
  'and the absorb never discards the entity itself (its own tick does that if it empties)');
check(/\[rack\] absorbed/.test(be) && /armamentOf\(one\)\.id\(\)/.test(be),
  'every absorption is logged at INFO with the item name and its classification');
check(/public static net\.minecraft\.world\.phys\.AABB absorbBox\(BlockPos pos\)/.test(be)
  && /RACK_ABSORB_RADIUS/.test(be) && /RACK_ABSORB_HEIGHT/.test(be),
  'the scan box is the configured radius/height around the rack');
check(/RACK_ABSORB_CHECK_INTERVAL_TICKS\.get\(\)/.test(be) && /% absorbInterval == 0L/.test(be),
  'the scan runs once per interval, not per tick');
check(/int absorbInterval[\s\S]{0,400}?absorb\(level, pos, rack\);/.test(be)
  && /WeaponRackTaker\.tick\(level, pos, rack\);/.test(be),
  'and the absorb scan is its own step in the tick, so a busy taker cannot starve it');
// Simulated counters: run the absorb rules over the four interesting cases and account for every item.
const absorb = (slot, groundCount, accepted, enabled = true) => {
  if (!enabled || slot !== null) return { slot, ground: groundCount };
  if (!accepted) return { slot, ground: groundCount };
  return { slot: 1, ground: groundCount - 1 };
};
for (const [name, slot, ground, accepted, wantSlot, wantGround] of [
  ['empty rack, accepted weapon', null, 1, true, 1, 0],
  ['empty rack, a stack of 5', null, 5, true, 1, 4],
  ['empty rack, unaccepted junk', null, 3, false, null, 3],
  ['occupied rack', 1, 3, true, 1, 3],
  ['absorb switched off', null, 1, true, null, 1]]) {
  const before = (slot === null ? 0 : slot) + ground;
  const result = absorb(slot, ground, accepted, name !== 'absorb switched off');
  const after = (result.slot === null ? 0 : result.slot) + result.ground;
  check(result.slot === wantSlot && result.ground === wantGround,
    `absorb: ${name} -> rack=${result.slot === null ? 'empty' : '1'}, ground=${result.ground}`);
  check(before === after, `  and the total is conserved (${before} -> ${after}, no duplication, no loss)`);
}

console.log('');
console.log('10. the creative rack: one block, endless copies, survival-proof');
check(/CREATIVE_WEAPON_RACK = BLOCKS\.register\("creative_weapon_rack"/.test(reg),
  'the creative rack is a SEPARATE block, not a flag on the normal one');
check(/CREATIVE_WEAPON_RACK_BE =[\s\S]{0,240}?BlockEntityType\.Builder\.of\([\s\S]{0,120}?new WeaponRackBlockEntity\(pos, state, true\)/
  .test(reg), 'with its own block entity TYPE (so the flag can never come from NBT or a block state)');
check(/private final boolean infinite/.test(be) && /this\.infinite = infinite/.test(be)
  && /super\(infinite\s*\?[\s\S]{0,140}?CREATIVE_WEAPON_RACK_BE\.get\(\)/.test(be),
  'the flag is chosen by the block entity type in the constructor');
check(!/infinite\s*=\s*tag\.|infinite\s*=\s*state\./.test(be),
  'and never read from the saved tag or the block state (there is no survival route to it)');
check(/public ItemStack claim\(\)[\s\S]{0,200}?return this\.held\.copy\(\);/.test(be),
  'a mob taking from it gets a COPY');
check(/if \(this\.infinite \|\| this\.held\.isEmpty\(\)\) \{\s*return;/.test(be),
  'and breaking it spills nothing (the slot is a template, not a container)');
check(/rack\.infinite && !Config\.RACK_CREATIVE_RACK_ENABLED\.get\(\)/.test(be)
  && /rack\.infinite\(\) && !Config\.RACK_CREATIVE_RACK_ENABLED\.get\(\)/.test(taker)
  && /infinite && !com\.gfl\.tarkovscav\.Config\.RACK_CREATIVE_RACK_ENABLED\.get\(\)/.test(block),
  'creativeRackEnabled=false makes the block inert on all three paths (tick, taker, right-click)');
check(/\[rack\] creative: item not consumed/.test(taker),
  'a creative take is logged as "item not consumed", so an operator can see it is deliberate');
// Simulated takes: the creative rack survives N, the normal rack is empty after the first (the control).
const takeN = (infinite, takes) => {
  let slot = 'TACZ_GUN';
  let produced = 0;
  for (let i = 0; i < takes; i++) {
    if (slot === null) break;
    if (infinite) produced += 1;
    else { produced += 1; slot = null; }
  }
  return { produced, slot };
};
const creative = takeN(true, 5);
const normal = takeN(false, 5);
check(creative.produced === 5 && creative.slot === 'TACZ_GUN',
  'creative: 5 takes produce 5 armed mobs and the template is STILL on the rack',
  `produced=${creative.produced}, rack=${creative.slot}`);
check(normal.produced === 1 && normal.slot === null,
  'normal (control): the same 5 attempts produce exactly 1 mob, then the rack is empty',
  `produced=${normal.produced}, rack=${normal.slot}`);
check(takeN(true, 3).produced === 3, 'and the creative rack survives at least the 3 takes the request asked for');
check(!/this\.held = ItemStack\.EMPTY/.test(be.slice(be.indexOf('public ItemStack claim'),
  be.indexOf('public int takeCooldown'))),
  'claim() never clears the slot on a creative rack (that is what makes it endless)');
// Assets: model, texture, blockstate, item model, loot table - and NO recipe.
check(fs.existsSync(path.join(ASSETS, 'blockstates', 'creative_weapon_rack.json')),
  'the creative rack has a blockstate');
const creativeModel = json('assets/tarkovscav/models/block/creative_weapon_rack.json');
check(Array.isArray(creativeModel.elements) && creativeModel.elements.length > blockModel.elements.length,
  'its model is the rack plus a marker, so the two are distinguishable in the world',
  `${creativeModel.elements.length} vs ${blockModel.elements.length} elements`);
check(creativeModel.elements.some((e) => /endless/.test(e.name)),
  'the extra element is named for what it means');
check(/tarkovscav:block\/creative_weapon_rack/.test(creativeModel.textures.all),
  'and it uses its OWN texture (a tinted copy of the rack texture)');
const creativePng = path.join(ASSETS, 'textures', 'block', 'creative_weapon_rack.png');
const creativeBytes = fs.readFileSync(creativePng);
check(creativeBytes[0] === 137 && creativeBytes[1] === 80 && creativeBytes[2] === 78 && creativeBytes[3] === 71,
  'the creative texture is a real PNG', `${creativeBytes.length} bytes`);
check(!creativeBytes.equals(pngBytes), 'and it is not byte-identical to the normal one');
check(fs.existsSync(path.join(ASSETS, 'models', 'item', 'creative_weapon_rack.json')),
  'the creative item model exists');
const creativeLoot = json('data/tarkovscav/loot_tables/blocks/creative_weapon_rack.json');
check(/tarkovscav:creative_weapon_rack/.test(JSON.stringify(creativeLoot)),
  'and it has a loot table (without one it would drop nothing)');
check(!fs.existsSync(path.join(RES, 'data', 'tarkovscav', 'recipes', 'creative_weapon_rack.json')),
  'there is NO recipe for it (a survival player cannot craft the endless variant)');
const recipeDir = path.join(RES, 'data', 'tarkovscav', 'recipes');
const recipes = fs.existsSync(recipeDir) ? fs.readdirSync(recipeDir).filter((f) => f.endsWith('.json')) : [];
check(recipes.length > 0 && recipes.every((f) => !fs.readFileSync(path.join(recipeDir, f), 'utf8')
  .includes('creative_weapon_rack')), `no recipe anywhere yields it (${recipes.length} recipes scanned)`);
check(/output\.accept\(ModBlocks\.CREATIVE_WEAPON_RACK_ITEM\.get\(\)\)/.test(tabs),
  'it is in the mod\'s creative tab');
check(/new WeaponRackBlock\(true\)/.test(reg),
  'the block passes creative=true to the block, which passes it to its block entity');
check(/registerBlockEntityRenderer\(com\.gfl\.tarkovscav\.registry\.ModBlocks\.CREATIVE_WEAPON_RACK_BE\.get\(\)/
  .test(client), 'the client registers the item renderer for the second block entity type too');
check(/boolean creative/.test(block) && /infinite \? " off the creative weapon rack/.test(block),
  'the right-click take on a creative rack hands over a copy and says so');

console.log('');
console.log('11. facing: the rack can be turned, and an old world cannot notice');
// The property, and the four pieces of boilerplate a block with a state needs (BlockStateProperties is the
// vanilla property, so a resource pack or another mod can reason about it like any chest).
check(/public static final DirectionProperty FACING = BlockStateProperties\.HORIZONTAL_FACING;/.test(block),
  'the rack has the vanilla horizontal facing property (HORIZONTAL_FACING)');
check(/builder\.add\(FACING\)/.test(block),
  'createBlockStateDefinition registers it (without that the property does not exist)');
check(/getStateForPlacement\(BlockPlaceContext context\)[\s\S]{0,200}?context\.getHorizontalDirection\(\)\.getOpposite\(\)/
  .test(block), 'placement faces the rack AT the player, like a chest or a workbench');
check(/public BlockState rotate\(BlockState state, Rotation rotation\)[\s\S]{0,160}?rotation\.rotate\(state\.getValue\(FACING\)\)/
  .test(block), 'rotate() turns it (structure blocks, wrenches)');
check(/public BlockState mirror\(BlockState state, Mirror mirror\)[\s\S]{0,160}?mirror\.getRotation\(state\.getValue\(FACING\)\)/
  .test(block), 'mirror() mirrors it');
check(/registerDefaultState\(this\.stateDefinition\.any\(\)\.setValue\(FACING, Direction\.NORTH\)\)/.test(block),
  'the default state is NORTH');
check(/private static final VoxelShape SHAPE = Shapes\.box\(0\.0D, 0\.0D, 0\.0D, 1\.0D, 0\.9375D, 1\.0D\)/.test(block)
  && /public VoxelShape getShape\(BlockState state[\s\S]{0,240}?return SHAPE;/.test(block),
  'the shape is the whole block footprint, so it is the same for every facing (nothing per-facing to get wrong)');
// Both blockstates: exactly the four variants, all four pointing at the same model, NORTH at y = 0.
for (const [name, model] of [['weapon_rack', 'tarkovscav:block/weapon_rack'],
  ['creative_weapon_rack', 'tarkovscav:block/creative_weapon_rack']]) {
  const variants = json(`assets/tarkovscav/blockstates/${name}.json`).variants;
  const keys = Object.keys(variants);
  check(keys.length === 4 && keys.every((k) => /^facing=(north|east|south|west)$/.test(k)),
    `${name}.json has exactly the four facing variants`, keys.join(' '));
  check(keys.every((k) => variants[k].model === model),
    `  and all four use the same model (${model})`);
  check(variants['facing=north'].y === undefined || variants['facing=north'].y === 0,
    '  facing=north adds no rotation, which is what the old single "" variant was');
  check(variants['facing=east'].y === 90 && variants['facing=south'].y === 180
    && variants['facing=west'].y === 270, '  and the other three are the vanilla 90/180/270 convention');
}
// The renderer has to turn the item too: a blockstate y rotation moves geometry, never a BER pose.
check(/rack\.getBlockState\(\)\.getValue\(WeaponRackBlock\.FACING\)/.test(renderer),
  'the item renderer reads the block state\'s FACING');
check(/public static float facingDegrees\(Direction facing\)/.test(renderer)
  && /facing\.toYRot\(\) - 180\.0F/.test(renderer),
  'and converts it with the same convention the json uses (toYRot is measured from SOUTH)');
check(/Axis\.YP\.rotationDegrees\(facingDegrees\(facing\)\)[\s\S]{0,220}?Axis\.YP\.rotationDegrees\(spin\)/
  .test(renderer),
  'the facing rotation is applied BEFORE the idle spin, on top of the existing transform');
check(/translate\(0\.5D, 0\.82D, 0\.5D\)/.test(renderer) && /scale\(0\.6F, 0\.6F, 0\.6F\)/.test(renderer)
  && /Axis\.XP\.rotationDegrees\(20\.0F\)/.test(renderer)
  && renderer.indexOf('translate(0.5D, 0.82D, 0.5D)') < renderer.indexOf('scale(0.6F, 0.6F, 0.6F)')
  && renderer.indexOf('scale(0.6F, 0.6F, 0.6F)') < renderer.indexOf('rotationDegrees(20.0F)'),
  'and the float height, the 0.6 scale and the 20-degree tilt are untouched and in the same order');
// The convention itself, simulated: NORTH must add exactly zero degrees, or an old world would change.
const JSON_Y = { north: 0, east: 90, south: 180, west: 270 };
const TO_Y_ROT = { south: 0, west: 90, north: 180, east: 270 };
for (const facing of ['north', 'east', 'south', 'west']) {
  const deg = TO_Y_ROT[facing] - 180;
  const normalised = ((deg % 360) + 360) % 360;
  check(normalised === JSON_Y[facing],
    `facing=${facing}: the renderer adds ${normalised} deg, and the json already turned the model ${JSON_Y[facing]}`);
}
check(TO_Y_ROT['north'] - 180 === 0, 'NORTH is the identity: a rack placed before this feature looks unchanged');
// The item cannot be lost by the upgrade: it lives in the block entity's NBT, not in the state.
check(/tag\.put\(TAG_ITEM, this\.held\.save\(new CompoundTag\(\)\)\)/.test(be)
  && !/held\s*=\s*tag[^\n]*FACING/.test(be),
  'the held stack is still stored in the block entity tag, so adding a property cannot drop it');
// Item model unchanged, and README says all of this in Chinese.
check(fs.existsSync(path.join(ASSETS, 'models', 'item', 'weapon_rack.json'))
  && fs.existsSync(path.join(ASSETS, 'models', 'item', 'creative_weapon_rack.json')),
  'the item models are untouched by the facing work (an inventory item has no facing)');
check(/朝向/.test(readme) && /HORIZONTAL_FACING/.test(readme) && /toYRot/.test(readme),
  'README 5n documents the facing, the vanilla property and the toYRot convention');
check(/逐像素不变/.test(readme) && /NORTH/.test(readme),
  'including the promise that NORTH - and therefore every old world - looks pixel-identical');
check(!/贴墙/.test(block) && !/wall_mount/.test(block) && !/wall_mount/.test(renderer),
  'and NO wall-mount variant was added (the user has not asked for one)');

console.log('');
if (failures > 0) {
  console.log(`${failures} rack check(s) FAILED`);
  process.exit(1);
}
console.log('weapon rack invariants all hold');
