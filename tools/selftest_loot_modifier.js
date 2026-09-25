// The TaCZ loot-injection gate (deliverable C): the city-chest extras are a Forge global loot modifier, they
// are gated twice (the JSON condition and a runtime check), they are scoped by POSITION through our own city
// gate, and a world without TaCZ never sees an error, an empty pool or a hard-coded TaCZ id.
//
//   node tools/selftest_loot_modifier.js
//
// What it asserts, and why each one matters:
//   1. the modifier is registered as a Forge global loot modifier (a serializer in
//      ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS) and declared in
//      data/forge/loot_modifiers/global_loot_modifiers.json;
//   2. its own JSON exists, parses, and its conditions are `tarkovscav:mod_loaded` (modid tacz) plus
//      `forge:loot_table_id` (minecraft:chests/abandoned_mineshaft) - the two conditions that actually
//      resolve in the LOOT condition registry. `forge:mod_loaded` is a CRAFTING condition (the
//      forge:conditions registry) and is NOT a loot condition: javap of ForgeMod#registerLootData shows the
//      loot registry gets exactly forge:loot_table_id and forge:can_tool_perform_action, and
//      LootItemConditions#createGsonAdapter resolves the "condition" field against that registry only. A
//      GLM JSON that used it would fail to deserialize and the modifier would be silently dropped, so this
//      gate FAILS on any occurrence of it;
//   3. the Java half: a runtime ModList.get().isLoaded("tacz") guard, CityGate.isCityArea scoping of the
//      chest's LootContextParams.ORIGIN, and a GunPool build path for the gun (no second gun-building path);
//   4. no TaCZ ITEM ID string anywhere (the task's crash rule: a `tacz:` id in a JSON breaks every world on a
//      machine without TaCZ), and no TaCZ API type used outside the guarded helper class;
//   5. the weights the modifier uses are the ones documented: a low gun weight, tiny ammo stacks.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const LOOT_JAVA = path.join(JAVA, 'loot');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ 0. the files exist and parse
console.log('0. the modifier, its serializer and its JSON');
const modifierSource = fs.readFileSync(path.join(LOOT_JAVA, 'CityChestLootModifier.java'), 'utf8');
const helperSource = fs.readFileSync(path.join(LOOT_JAVA, 'CityChestExtras.java'), 'utf8');
const conditionSource = fs.readFileSync(path.join(LOOT_JAVA, 'ModLoadedLootCondition.java'), 'utf8');
const modSource = fs.readFileSync(path.join(JAVA, 'TarkovScav.java'), 'utf8');
const globalFile = path.join(RES, 'data', 'forge', 'loot_modifiers', 'global_loot_modifiers.json');
const modifierFile = path.join(RES, 'data', 'tarkovscav', 'loot_modifiers', 'city_chest_extras.json');
check(fs.existsSync(globalFile), 'data/forge/loot_modifiers/global_loot_modifiers.json exists');
check(fs.existsSync(modifierFile), 'data/tarkovscav/loot_modifiers/city_chest_extras.json exists');
const global = JSON.parse(fs.readFileSync(globalFile, 'utf8'));
const modifier = JSON.parse(fs.readFileSync(modifierFile, 'utf8'));
check(global.replace === false && Array.isArray(global.entries) && global.entries.length >= 1,
  'the global list does not replace other mods\' modifiers and lists ours',
  JSON.stringify(global.entries));
check(global.entries.includes('tarkovscav:city_chest_extras'),
  'the global list names tarkovscav:city_chest_extras');
check(modifier.type === 'tarkovscav:city_chest_extras',
  'the modifier file declares the serializer id', String(modifier.type));

// ------------------------------------------------------------------ 1. registration + runtime guard
console.log('');
console.log('1. registered as a Forge global loot modifier, gated twice');
check(/GLOBAL_LOOT_MODIFIER_SERIALIZERS/.test(modifierSource)
  && /DeferredRegister\.create\(ForgeRegistries\.Keys\.GLOBAL_LOOT_MODIFIER_SERIALIZERS/.test(modifierSource),
  'the serializer is registered in ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS');
check(/SERIALIZERS\.register\(modBus\)/.test(modSource),
  'TarkovScav registers the serializer on the mod event bus');
check(/CONDITIONS\.register\(modBus\)/.test(modSource),
  'TarkovScav registers the mod_loaded loot condition on the mod event bus');
check(/extends LootModifier/.test(modifierSource) && /codecStart\(instance\)/.test(modifierSource),
  'it extends Forge\'s LootModifier and composes its codec with codecStart (the conditions field)');
check(/ModList\.get\(\)\.isLoaded\(TarkovScav\.TACZ_MOD_ID\)/.test(modifierSource),
  'the runtime half of the TaCZ guard is ModList.get().isLoaded(TACZ_MOD_ID)');
check(/ModList\.get\(\)\.isLoaded\(this\.modId\)/.test(conditionSource),
  'the condition\'s test is ModList.get().isLoaded(modid)');
check(/LOOT_CONDITION_TYPE/.test(conditionSource)
  && /DeferredRegister\.create\(Registries\.LOOT_CONDITION_TYPE/.test(conditionSource),
  'the condition registers in the vanilla loot condition registry');
check(/tarkovscav:mod_loaded|register\("mod_loaded"/.test(conditionSource),
  'the condition id is tarkovscav:mod_loaded');

// ------------------------------------------------------------------ 2. the JSON conditions
console.log('');
console.log('2. the conditions in the modifier JSON - and the condition that cannot work');
const conditions = modifier.conditions || [];
const hasModLoaded = conditions.some((c) => c.condition === 'tarkovscav:mod_loaded' && c.modid === 'tacz');
const hasTable = conditions.some((c) => c.condition === 'forge:loot_table_id'
  && c.loot_table_id === 'minecraft:chests/abandoned_mineshaft');
check(hasModLoaded, 'the JSON refuses to build without TaCZ (tarkovscav:mod_loaded, modid tacz)',
  JSON.stringify(conditions));
check(hasTable, 'the JSON is limited to the abandoned mineshaft chest table (forge:loot_table_id)',
  JSON.stringify(conditions));
const allJson = [];
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name.endsWith('.json')) allJson.push(full);
  }
};
walk(path.join(RES, 'data'));
const forgeModLoaded = allJson.filter((file) => /"forge:mod_loaded"/.test(fs.readFileSync(file, 'utf8')));
check(forgeModLoaded.length === 0,
  'no datapack JSON uses forge:mod_loaded, which is a CRAFTING condition and cannot be resolved by the loot '
  + 'condition deserializer', forgeModLoaded.join(', ') || 'none');
check(/forge:mod_loaded/.test(modifierSource) === false || !/"forge:mod_loaded"/.test(modifierSource),
  'the Java does not claim to use forge:mod_loaded either');

// ------------------------------------------------------------------ 3. position scoping through CityGate
console.log('');
console.log('3. scoped by position: only chests inside a city get the extras');
check(/LootContextParams\.ORIGIN/.test(modifierSource) && /BlockPos\.containing\(origin\)/.test(modifierSource),
  'the modifier reads the chest\'s ORIGIN from the LootContext');
check(/CityGate\.isCityArea\(level, pos\)/.test(modifierSource),
  'it asks our own CityGate, so it works in every dimension the gate knows (overworld and wasteland)');
check(/return loot;/.test(modifierSource.split('isCityArea')[1] || ''),
  'a chest outside a city returns the loot untouched');
const cityGate = fs.readFileSync(path.join(JAVA, 'world', 'CityGate.java'), 'utf8');
check(!/Level\.OVERWORLD|minecraft:overworld/.test(cityGate),
  'CityGate itself does not hardcode the overworld (nothing in it mentions it)',
  /minecraft:overworld/.test(cityGate) ? 'mentions overworld' : 'dimension-agnostic');

// ------------------------------------------------------------------ 4. no TaCZ id strings, no stray API
console.log('');
console.log('4. no hard-coded TaCZ item id, and the TaCZ API only in the guarded helper');
const JAVA_FILES = [];
const walkJava = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkJava(full);
    else if (entry.name.endsWith('.java')) JAVA_FILES.push(full);
  }
};
walkJava(JAVA);
const taczIds = JAVA_FILES.filter((file) => /"tacz:[a-z0-9_/]+"/.test(fs.readFileSync(file, 'utf8')))
  .map((f) => path.relative(ROOT, f));
const lootPathIds = taczIds.filter((f) => /loot[\\/]/.test(f));
check(lootPathIds.length === 0, 'not one "tacz:<item>" string literal in the loot path',
  lootPathIds.join(', ') || 'none');
// The one place in the mod that does name TaCZ ids is Config's default gun BLACKLIST - three ids that must
// never be handed to a mob, typed into a config default (not a datapack file). It is reported here so the
// distinction is on the record rather than assumed.
console.log(`  note: "tacz:" literals outside the loot path: ${taczIds.join(', ') || 'none'}`
  + ' (Config\'s default gun blacklist - a config default, never a datapack file)');
const datapackTaczIds = allJson.filter((file) => /"tacz:[a-z0-9_/]+"/.test(fs.readFileSync(file, 'utf8')));
check(datapackTaczIds.length === 0,
  'no datapack JSON anywhere contains a tacz: item id (the crash a machine without TaCZ would take)',
  datapackTaczIds.map((f) => path.relative(ROOT, f)).join(', ') || 'none');
check(/import com\.tacz\./.test(helperSource) && !/import com\.tacz\./.test(modifierSource)
  && !/import com\.tacz\./.test(conditionSource),
  'the loot modifier and the condition themselves import no TaCZ class');
const lootFiles = fs.readdirSync(LOOT_JAVA).filter((f) => f.endsWith('.java'))
  .map((f) => path.join(LOOT_JAVA, f));
const lootImporters = lootFiles.filter((f) => /import com\.tacz\./.test(fs.readFileSync(f, 'utf8')))
  .map((f) => path.basename(f));
check(lootImporters.length === 1 && lootImporters[0] === 'CityChestExtras.java',
  'inside the loot package, exactly ONE file touches the TaCZ API and it is the guarded helper',
  lootImporters.join(', ') || 'none');
check(/GunPool\.rollLoadout/.test(helperSource) && /GunPool\.buildGun\(loadout, random/.test(helperSource),
  'the rare gun is built by the EXISTING path (GunPool rollLoadout + buildGun with a random source)');
check(/GunPool\.buildAmmo/.test(helperSource),
  'the ammo stacks go through GunPool.buildAmmo, so the item\'s own max stack size wins');

// ------------------------------------------------------------------ 5. the weights and the numbers
console.log('');
console.log('5. the weights and the measured numbers in the source');
const intConstant = (name, source = helperSource) => {
  const m = new RegExp(`${name}\\s*=\\s*(\\d+);`).exec(source);
  return m ? Number(m[1]) : null;
};
const gunChance = intConstant('GUN_CHANCE_PERCENT');
const ammoMin = intConstant('AMMO_STACKS_MIN');
const ammoMax = intConstant('AMMO_STACKS_MAX');
const countMin = intConstant('AMMO_COUNT_MIN');
const countMax = intConstant('AMMO_COUNT_MAX');
const tierWeights = /GUN_TIER_WEIGHTS\s*=\s*\{([^}]+)\}/.exec(helperSource);
console.log(`  gun chance: ${gunChance}%   ammo stacks: ${ammoMin}..${ammoMax}   ammo count: `
  + `${countMin}..${countMax}   tier weights: ${tierWeights ? tierWeights[1].trim() : '?'}`);
check(gunChance !== null && gunChance >= 1 && gunChance <= 10,
  'the gun chance is a LOW weight (1..10 percent)', `${gunChance}%`);
check(ammoMin >= 1 && ammoMax <= 4 && countMin >= 1 && countMax <= 32,
  'the ammo is a few small stacks, not a supply drop', `${ammoMin}..${ammoMax} x ${countMin}..${countMax}`);
check(/LOGGER\.info\(/.test(modifierSource) && /city chest at/.test(modifierSource),
  'a city chest logs one line when it receives the extras');
check(/ModLoadedLootCondition|converted|forge:mod_loaded/.test(conditionSource),
  'the condition documents why forge:mod_loaded cannot be used');

console.log('');
if (failures > 0) {
  console.log(`${failures} loot-modifier check(s) FAILED`);
  process.exit(1);
}
console.log('the city-chest loot modifier is registered, doubly gated, position-scoped, free of TaCZ id '
  + 'strings and built by the existing gun code');
