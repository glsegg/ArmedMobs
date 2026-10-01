// Every entity this mod registers must be complete: a client renderer, attributes, a spawn placement, a
// spawn egg with a model, both language keys, a creative-tab entry, and a biome spawner whose weight agrees
// with the config default. Source-level and head-less: no client, no game.
//
//   node tools/selftest_entity_registry.js
//
// WHY THIS GATE EXISTS (2026-09-23, the sniper_pillager crash)
//   The sniper pillager was registered in ModEntities, spawnable, armed, and logging normally - but no
//   renderer was registered for it in ClientSetup. The client then died the instant it became visible:
//
//     java.lang.NullPointerException: Cannot invoke
//       "net.minecraft.client.renderer.entity.EntityRenderer.shouldRender(...)"
//       because "entityrenderer" is null
//       at EntityRenderDispatcher.render(EntityRenderDispatcher.java:127)
//
//   Nothing in the build, the jar or the other gates could see that, because "registered" and "drawable"
//   are two different lists and only one of them was being checked. This gate enumerates the first list
//   (ModEntities) and demands the second one covers it, so the omission fails a build instead of a client.
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

const entitiesSrc = read('registry/ModEntities.java');
const itemsSrc = read('registry/ModItems.java');
const tabsSrc = read('registry/ModCreativeTabs.java');
const clientSrc = read('client/ClientSetup.java');
const configSrc = read('Config.java');
const biomes = JSON.parse(fs.readFileSync(
  path.join(RES, 'data', 'tarkovscav', 'forge', 'biome_modifier', 'add_scavs.json'), 'utf8'));
const lang = {};
for (const name of ['en_us', 'zh_cn']) {
  lang[name] = fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');
}

// ---------------------------------------------------------------- enumerate the entity types
// Deliberately strict: if the declaration shape ever changes, this gate must fail loudly rather than
// silently enumerate nothing and pass.
const entityRe = /public static final RegistryObject<EntityType<(\w+)>> (\w+) =\s*ENTITY_TYPES\.register\("([a-z0-9_]+)",\s*\(\) -> EntityType\.Builder\.<[^>]+>of\([^,]+,\s*MobCategory\.(\w+)\)/g;
const entities = [];
let match;
while ((match = entityRe.exec(entitiesSrc)) !== null) {
  entities.push({ entityClass: match[1], field: match[2], id: match[3], category: match[4] });
}
const declared = (entitiesSrc.match(/ENTITY_TYPES\.register\("/g) || []).length;
check(entities.length > 0 && entities.length === declared,
  'every entity type in ModEntities.java was enumerated',
  `${entities.length} parsed of ${declared} register(...) calls`);

// ---------------------------------------------------------------- the renderer rule (the crash)
// The names must appear in the body that decides renderers; the two call sites that actually register are
// the generic helper and the illager family loop, and both are called from that body.
const registerAllStart = clientSrc.indexOf('private static void registerAll');
const registerAllEnd = clientSrc.indexOf('private static <T extends Entity> void renderer');
check(registerAllStart > 0 && registerAllEnd > registerAllStart,
  'ClientSetup has a single registerAll body (the one place that decides renderers)');
const registerAllBody = clientSrc.slice(registerAllStart, registerAllEnd);
check(/@SubscribeEvent[\s\S]{0,200}?onRegisterRenderers[\s\S]{0,200}?registerAll\(event\)/.test(clientSrc),
  'and the renderer event handler calls it');
const registerCalls = (clientSrc.match(/\.registerEntityRenderer\(/g) || []).length;
check(registerCalls === 3,
  'there are exactly three registerEntityRenderer call sites: the generic helper and the two family loops',
  `${registerCalls} call sites`);
check(/private static <T extends Entity> void renderer\([\s\S]{0,300}?registerEntityRenderer\(type\.get\(\), provider\)/
  .test(clientSrc), 'the generic helper registers the type it was given (no hard-coded list)');
const villagerBody = clientSrc.slice(clientSrc.indexOf('private static void villagerRenderers'),
  clientSrc.indexOf('private static void illagerRenderers'));
check(/for \(RegistryObject[\s\S]{0,400}?event\.registerEntityRenderer\(entityType,/.test(villagerBody),
  'the villager family helper loops over every member passed to it');
check(/villagerRenderers\(event, new RegistryObject\[\] \{[^}]*GUNNER_VILLAGER[^}]*SNIPER_VILLAGER[^}]*USEC_VILLAGER[^}]*ELITE_VILLAGER/.test(registerAllBody),
  'with the villager members listed at the one call site');
const illagerBody = clientSrc.slice(clientSrc.indexOf('private static void illagerRenderers'),
  clientSrc.indexOf('public static void onLoadComplete'));
check(/for \(RegistryObject[\s\S]{0,400}?event\.registerEntityRenderer\(entityType,/.test(illagerBody),
  'and the illager family helper loops over every member passed to it');
check(/\billagerRenderers\(event, new RegistryObject\[\] \{[^}]*GUNNER_PILLAGER[^}]*SNIPER_PILLAGER[^}]*BEAR_PILLAGER[^}]*ELITE_PILLAGER/.test(registerAllBody),
  'with the illager members listed at the one call site');
check(/RENDERED\.add\(type\.getId\(\)\);/.test(clientSrc)
  && (clientSrc.match(/RENDERED\.add\(/g) || []).length === 3,
  'all three registration paths remember what they registered');
check(/FMLLoadCompleteEvent/.test(clientSrc) && /ModEntities\.class\.getDeclaredFields\(\)/.test(clientSrc)
  && /LOGGER\.error\(/.test(clientSrc) && /NO renderer registered/.test(clientSrc),
  'and a startup guard walks ModEntities reflectively and ERRORs on any type without a renderer');

// ---------------------------------------------------------------- the per-entity completeness table
const spawners = {};
for (const spawner of biomes.spawners || []) {
  spawners[spawner.type] = spawner;
}
const rows = [];
for (const entity of entities) {
  const full = `tarkovscav:${entity.id}`;
  const egg = `${entity.id}_spawn_egg`;
  // A projectile (MobCategory.MISC) is drawn and named like everything else, but it is not a mob: it has no
  // attributes, no spawn placement, no spawn egg and no biome spawner. The gate still demands the renderer and
  // the lang key, because those are exactly what the 2026-09-23 crash was about.
  const isMob = entity.category !== 'MISC';
  const problems = [];

  if (!new RegExp(`ModEntities\\.${entity.field}\\b`).test(registerAllBody)) {
    problems.push('renderer');
  }
  for (const name of ['en_us', 'zh_cn']) {
    if (!lang[name].includes(`"entity.tarkovscav.${entity.id}"`)) {
      problems.push(`lang.${name}.entity`);
    }
  }
  if (isMob) {
    if (!new RegExp(`event\\.put\\(${entity.field}\\.get\\(`).test(entitiesSrc)) {
      problems.push('attributes');
    }
    if (!new RegExp(`SpawnPlacements\\.register\\(${entity.field}\\.get\\(`).test(entitiesSrc)) {
      problems.push('placement');
    }
    if (!new RegExp(`RegistryObject<Item> ${entity.field}_SPAWN_EGG = ITEMS\\.register\\("${egg}"`)
      .test(itemsSrc)) {
      problems.push('egg');
    }
    if (!new RegExp(`output\\.accept\\(ModItems\\.${entity.field}_SPAWN_EGG\\.get\\(\\)\\)`).test(tabsSrc)) {
      problems.push('tab');
    }
    if (!fs.existsSync(path.join(ASSETS, 'models', 'item', `${egg}.json`))) {
      problems.push('egg-model');
    }
    for (const name of ['en_us', 'zh_cn']) {
      if (!lang[name].includes(`"item.tarkovscav.${egg}"`)) {
        problems.push(`lang.${name}.item`);
      }
    }
  }
  const spawner = spawners[full];
  if (isMob) {
    if (!spawner) {
      problems.push('biome-spawner');
    } else if (!(spawner.weight > 0)) {
      problems.push('biome-weight');
    }
  } else if (spawner) {
    problems.push('projectile-in-biome-spawner');
  }
  // Where a config key documents the weight, the two must agree: the JSON is what the game reads, the key
  // is what a user is told to change, so a drift between them is a documentation bug.
  const configWeight = new RegExp(`${entity.field}_SPAWN_WEIGHT\\s*=[\\s\\S]{0,700}?`
    + `defineInRange\\("([a-zA-Z]+)",\\s*(\\d+),`).exec(configSrc);
  let weightNote = spawner ? `w=${spawner.weight}` : 'w=?';
  if (configWeight) {
    const value = Number(configWeight[2]);
    weightNote += ` (${configWeight[1]}=${value})`;
    if (!spawner || spawner.weight !== value) {
      problems.push('weight-mismatch');
    }
  }
  rows.push({ id: full, category: entity.category, problems, weightNote });
}

console.log('');
console.log('  entity type                             kind      renderer  attributes  placement  egg  model  lang  tab  spawner');
for (const row of rows) {
  const cell = (flag) => (row.problems.includes(flag) ? 'MISSING' : 'ok');
  const mob = row.category !== 'MISC';
  console.log('  ' + row.id.padEnd(38) + row.category.toLowerCase().padEnd(10)
    + cell('renderer').padEnd(10) + (mob ? cell('attributes') : '-').padEnd(12)
    + (mob ? cell('placement') : '-').padEnd(11)
    + (mob ? cell('egg') : '-').padEnd(5) + (mob ? cell('egg-model') : '-').padEnd(7)
    + (row.problems.some((p) => p.startsWith('lang.')) ? 'MISSING' : 'ok').padEnd(6)
    + (mob ? cell('tab') : '-').padEnd(5)
    + (mob ? (row.problems.includes('biome-spawner') ? 'MISSING' : row.weightNote) : 'n/a'));
}
console.log('');
check(rows.every((row) => row.problems.length === 0),
  `every one of the ${rows.length} entity types is complete`,
  rows.filter((row) => row.problems.length).map((row) => `${row.id}: ${row.problems.join(',')}`).join(' | ')
    || 'no gaps');
check(rows.length >= 5, 'the enumeration is not suspiciously short', `${rows.length} entity type(s)`);

// The specific regression: the crash was one entity type missing from one list.
check(rows.some((row) => row.id === 'tarkovscav:sniper_pillager'
  && !row.problems.includes('renderer')),
  'the sniper pillager (the 2026-09-23 crash) has a renderer');
check(rows.some((row) => row.id === 'tarkovscav:sniper_villager'
  && !row.problems.includes('renderer')),
  'and the sniper villager added later has one too (no sibling can be forgotten)');

console.log('');
console.log('11. names: no raw translation key may reach a screen (README 5x)');
const names = read('entity/EntityNames.java');
const villagerEntity = read('entity/GunnerVillagerEntity.java');
const killFeed = read('killfeed/KillFeed.java');
const commandsSrc = read('command/ModCommands.java');
// The bug: vanilla Villager#getTypeName() concatenates the profession onto the entity's own key, and we never
// shipped those keys, so the kill feed printed "entity.tarkovscav.gunner_villager.weaponsmith".
check(/public net\.minecraft\.network\.chat\.Component getTypeName\(\)/.test(villagerEntity)
  && /EntityNames\.villagerTypeName\(this\.getType\(\), this\.getVillagerData\(\)\)/.test(villagerEntity),
  'GunnerVillagerEntity overrides getTypeName() with our own composer (and the sniper villager inherits it)');
check(!/getDescriptionId\(\) \+/.test(villagerEntity + names)
  || /A switch of <b>literal<\/b> keys/.test(names),
  'nothing concatenates a profession onto a translation key any more');
check(/professionKey\(@Nullable VillagerProfession profession\)/.test(names)
  && (names.match(/entity\.minecraft\.villager\./g) || []).length >= 14,
  'the vanilla profession keys are literal entries in a table (so the asset gate can resolve them)',
  `${(names.match(/entity\.minecraft\.villager\./g) || []).length} literal key(s)`);
check(/public static String safeName\(Entity entity\)/.test(names)
  && /public static boolean looksUntranslated\(@Nullable String text\)/.test(names)
  && /WARNED\.add\(type\)/.test(names)
  && /TarkovScav\.LOGGER\.warn\("\[names\]/.test(names),
  'and there is a floor: an untranslated-looking name is replaced by the entity type path and WARNed once');
check(/EntityNames\.safeName\(killer\)/.test(killFeed) && /EntityNames\.safeName\(victim\)/.test(killFeed),
  'the kill feed runs both names through it (the screen this bug was reported on)');
check(/EntityNames\.safeName\(/.test(commandsSrc),
  '/tarkovscav debug and the test commands use it too');
// Simulation of the composed name over every vanilla profession, using the shipped language files.
const professions = ['none', 'armorer', 'butcher', 'cartographer', 'cleric', 'farmer', 'fisherman', 'fletcher',
  'leatherworker', 'librarian', 'mason', 'nitwit', 'shepherd', 'toolsmith', 'weaponsmith'];
const langFor = (name) => JSON.parse(fs.readFileSync(path.join(ASSETS, 'lang', name + '.json'), 'utf8'));
for (const langName of ['zh_cn', 'en_us']) {
  const table = langFor(langName);
  const base = table['entity.tarkovscav.gunner_villager'];
  const format = table['tarkovscav.name.withProfession'];
  check(typeof base === 'string' && typeof format === 'string',
    `${langName}: the villager name and the composition format exist`);
  // The vanilla profession keys belong to Minecraft, not to us, so the sweep below can only prove the
  // FALLBACK ("never a raw key"). The positive case is composed explicitly here: with the vanilla key
  // resolving, the name must read like the request asked for it to.
  const vanillaWeaponsmith = langName === 'zh_cn' ? '武器匠' : 'Weaponsmith';
  check(format.replace('%s', base).replace('%s', vanillaWeaponsmith).startsWith(base),
    `${langName}: a resolved profession composes onto our base name`,
    format.replace('%s', base).replace('%s', vanillaWeaponsmith));
  for (const profession of professions) {
    // Mirrors EntityNames: no profession (or none) -> the bare base name; otherwise base + vanilla profession.
    const professionText = table['entity.minecraft.villager.' + profession];
    const composed = profession === 'none' || professionText === undefined
      ? base
      : format.replace('%s', base).replace('%s', professionText);
    check(!composed.startsWith('entity.') && !composed.includes('tarkovscav.'),
      `${langName}: ${profession} renders as a real name`, composed);
  }
}
// The survey: every entity we register must have a name key, and only the two Villager-based ones have a
// variant (profession) in their name.
const langZh = langFor('zh_cn');
for (const row of rows) {
  check(langZh['entity.' + row.id] !== undefined || langZh['entity.tarkovscav.' + row.id.split(':')[1]]
    !== undefined, `${row.id} has a name key (no variant can turn it into a raw key)`);
}
check(/VillagerData/.test(villagerEntity) || /getVillagerData\(\)/.test(villagerEntity),
  'only the Villager-derived entities have a variant-sensitive name (surveyed: nobody else builds one)');
check(/### 5x\./.test(fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8')), 'README has the 5x section');

console.log('');
console.log('12. faction troops: 40 health, the rolled armor class, the tier and the voice pools (README 5y)');
const armor = read('entity/ArmorClass.java');
const armorSpawn = read('entity/ArmorClassSpawn.java');
const tierProfile = read('entity/FactionTierProfile.java');
const voicePools = read('voice/VoicePools.java');
const villagerBase = read('entity/GunnerVillagerEntity.java');
const pillagerBase = read('entity/GunnerPillagerEntity.java');
const accuracy = read('gun/AccuracyProfile.java');
const README5y = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

// ---------------------------------------------------------------- the four troops, one table
// One row per entity: the class file, its parent, the voice family and the accuracy tier. Everything below
// is driven by this table, so a fifth troop would be one line here and would then have to satisfy all of it.
const TROOPS = [
  { id: 'usec_villager', field: 'USEC_VILLAGER', cls: 'UsecVillagerEntity', parent: 'GunnerVillagerEntity',
    family: 'usec', tier: 'VETERAN', health: 40 },
  { id: 'bear_pillager', field: 'BEAR_PILLAGER', cls: 'BearPillagerEntity', parent: 'GunnerPillagerEntity',
    family: 'bear', tier: 'VETERAN', health: 40 },
  { id: 'elite_villager', field: 'ELITE_VILLAGER', cls: 'EliteVillagerEntity', parent: 'GunnerVillagerEntity',
    family: 'elite', tier: 'ELITE', health: 40 },
  { id: 'elite_pillager', field: 'ELITE_PILLAGER', cls: 'ElitePillagerEntity', parent: 'GunnerPillagerEntity',
    family: 'elite', tier: 'ELITE', health: 40 },
];
for (const troop of TROOPS) {
  const src = read('entity/' + troop.cls + '.java');
  check(new RegExp(`class ${troop.cls} extends ${troop.parent}\\b`).test(src),
    `${troop.id} extends ${troop.parent}`,
    'the *_villager pair must be Villager-based: the villager renderer is registered for it');
  check(new RegExp(`protected double forcedMaxHealth\\(\\)\\s*\\{\\s*return ${troop.health};`).test(src),
    `${troop.id} pins its health to ${troop.health} (the user's number)`,
    (new RegExp(`return (\\d+);`).exec(src.slice(src.indexOf('forcedMaxHealth'))) || [])[1]);
  check(/protected double forcedArmorPoints\(\)\s*\{\s*return 0\.0D;/.test(src),
    `${troop.id} pins its vanilla armor points to 0 (no second reduction)`);
  check(new RegExp(`public String voiceFamily\\(\\)\\s*\\{\\s*return "${troop.family}";`).test(src),
    `${troop.id} speaks the ${troop.family} family`);
  check(new RegExp(`RegistryObject<EntityType<${troop.cls}>> ${troop.field}\\b`).test(entitiesSrc),
    `${troop.id} is a registered entity type (ModEntities.${troop.field})`);
}

// ---------------------------------------------------------------- renderer family == entity family
// The 2026-09-23 crash was "registered but no renderer"; this is its sibling: a renderer registered for the
// WRONG base class. The family lists in ClientSetup are raw RegistryObject arrays, so the compiler cannot see
// the mismatch - the entity would only die on screen with a ClassCastException. Walking the two lists and
// reading each class's `extends` clause is the only place that can catch it without a client.
for (const [listRe, parent, renderer] of [
  [/villagerRenderers\(event,\s*new RegistryObject\[\] \{([^}]*)\}/, 'GunnerVillagerEntity',
    'GunnerVillagerRenderer'],
  [/\billagerRenderers\(event,\s*new RegistryObject\[\] \{([^}]*)\}/, 'GunnerPillagerEntity',
    'GunnerPillagerRenderer or GunnerPillagerGeoRenderer'],
]) {
  const found = listRe.exec(registerAllBody);
  check(!!found, `the ${parent} renderer family list was found in ClientSetup`);
  if (!found) {
    continue;
  }
  const fields = (found[1].match(/ModEntities\.(\w+)/g) || []).map((s) => s.replace('ModEntities.', ''));
  check(fields.length > 0, `the ${parent} list is not empty`, `${fields.length} member(s)`);
  for (const field of fields) {
    const entity = entities.find((e) => e.field === field);
    check(!!entity, `${field} is a declared entity type`);
    if (!entity) {
      continue;
    }
    const src = read('entity/' + entity.entityClass + '.java');
    // The two family bases are their own root (GunnerVillagerEntity extends Villager): for them the check is
    // simply that the class exists under that name, for every derived entity that it extends the base.
    check(entity.entityClass === parent
      ? new RegExp(`class ${parent}\\b`).test(src)
      : new RegExp(`class ${entity.entityClass} extends ${parent}\\b`).test(src),
      `${entity.id} is drawn by ${renderer} and extends ${parent}`,
      `extends ${(new RegExp(`class \\w+ extends (\\w+)`).exec(src) || [])[1]}`);
  }
}

// ---------------------------------------------------------------- the armor class, simulated 1..6
const armorPerClass = Number((/ARMOR_REDUCTION_PER_CLASS\s*=[\s\S]{0,400}?defineInRange\("armorReductionPerClass",\s*([\d.]+)D/
  .exec(configSrc) || [])[1]);
check(armorPerClass === 0.10, 'armorReductionPerClass ships as 0.10 (the user\'s 10 % per class)',
  String(armorPerClass));
check(/ARMOR_ENABLED\s*=[\s\S]{0,400}?define\("armorEnabled", true\)/.test(configSrc),
  'armorEnabled ships as true');
check(/ELITE_MIN_ARMOR_CLASS\s*=[\s\S]{0,400}?defineInRange\("minArmorClass", 1, 1, 6\)/.test(configSrc)
  && /ELITE_MAX_ARMOR_CLASS\s*=[\s\S]{0,400}?defineInRange\("maxArmorClass", 6, 1, 6\)/.test(configSrc),
  'and the roll is 1..6');
const reductionFor = (armorClass, perClass) => {
  const clamped = Math.max(0, Math.min(6, armorClass));
  return clamped * perClass;
};
for (let armorClass = 1; armorClass <= 6; armorClass++) {
  const value = reductionFor(armorClass, armorPerClass);
  check(Math.abs(value - armorClass * 0.10) < 1e-9,
    `armor class ${armorClass} reduces damage by exactly ${armorClass * 10} %`,
    `${(value * 100).toFixed(0)} %`);
}
// The two bounds the user named, plus the clamp on both ends: nothing can reduce less than 10 % (class 1)
// and nothing can reduce more than 60 % (class 6), however the number got into the tag.
const everyClass = Array.from({ length: 30 }, (_, i) => i - 10).map((c) => reductionFor(c, armorPerClass));
const maxReduction = Math.max(...everyClass);
check(Math.min(...everyClass) === 0 && Math.abs(maxReduction - 0.60) < 1e-9,
  'over every integer from -10 to 19 the reduction stays inside [0, 60 %]: never below 10 % for a real',
  `class, never above 60 % for class 6 (min ${Math.min(...everyClass)}, max ${maxReduction})`);
check(everyClass.filter((v) => v > 0.60 + 1e-9).length === 0,
  'no input produces a reduction above the 60 % the user asked for as the maximum');
check(everyClass.filter((v) => v > 0 && v < 0.10).length === 0,
  'and none produces a positive reduction below 10 %');
check(everyClass.filter((v) => v > 0).every((v) => Math.abs(v / armorPerClass - Math.round(v / armorPerClass)) < 1e-9),
  'every positive reduction is a whole number of classes (no fractional class can be stored)');
check(/clamped \* Config\.ARMOR_REDUCTION_PER_CLASS\.get\(\)/.test(armor),
  'the reduction is class x armorReductionPerClass - one formula, one place');
check(/@SubscribeEvent[\s\S]{0,200}?public static void onHurt\(LivingHurtEvent event\)/.test(armor)
  && /event\.setAmount\(after\)/.test(armor)
  && /LivingHurtEvent/.test(armor),
  'LivingHurtEvent scales the blow, and it is the only damage hook in this class');
check(/if \(!Config\.ARMOR_ENABLED\.get\(\) \|\| !isTroop\(victim\)\) \{\s*return;/.test(armor),
  'armorEnabled = false is the FIRST thing the hook checks: the whole system is inert, class still stored');
check(!/setItemSlot\(|Items\.DIAMOND_CHESTPLATE|NETHERITE_CHESTPLATE|ItemStack/.test(armor),
  'and it hands out no vanilla armor item (option A: that would reduce the same blow a second time)');
check(/public static boolean isTroop\(LivingEntity entity\)/.test(armor)
  && TROOPS.every((t) => armor.includes('instanceof ' + t.cls)),
  'the armor system names exactly the four faction troops and nothing else');
// The second reduction the comment is about: LivingHurtEvent fires BEFORE vanilla armor absorbs, and each
// tier carries vanilla armor POINTS (see Config tiers.*.armor). Both bases must therefore expose the pin.
check(/protected double forcedArmorPoints\(\)/.test(pillagerBase)
  && /protected double forcedArmorPoints\(\)/.test(villagerBase),
  'both gunner bases expose forcedArmorPoints() (the hook the troops pin to 0)');
check(/forcedArmorPoints\(\) >= 0\.0D \? forcedArmorPoints\(\) : settings\.armor\.get\(\)/.test(pillagerBase)
  && /forcedArmorPoints\(\) >= 0\.0D \? forcedArmorPoints\(\) : settings\.armor\.get\(\)/.test(villagerBase),
  'and applyTierAttributes reads it instead of setting the tier armor blindly');
check(!/ARMOR\)\.setBaseValue\(settings\.armor\.get\(\)\)/.test(pillagerBase + villagerBase),
  'the old unconditional setBaseValue(settings.armor.get()) is gone from both bases');

// ---------------------------------------------------------------- the NBT round trip
const tagLiteral = (/String TAG = "([^"]+)"/.exec(armor) || [])[1];
check(tagLiteral === 'tarkovscav:armorClass',
  'the class lives under one persistent-data tag', tagLiteral);
check((armor.match(/"tarkovscav:armorClass"/g) || []).length === 1,
  'and the literal is written exactly once (one source of truth for the key)');
check(/entity\.getPersistentData\(\)\.getInt\(TAG\)/.test(armor)
  && /getPersistentData\(\)\.putInt\(TAG,/.test(armor),
  'the reader and the writer both go through that one constant');
check(/putInt\(TAG, Math\.max\(0, Math\.min\(6, armorClass\)\)\)/.test(armor),
  'set() clamps to 0..6, so a hand-edited save cannot invent class 9');
// The round trip, simulated with the same semantics as CompoundTag#putInt/getInt: a save writes the tag,
// a load reads the same key back. Anything but an exact copy would be a "the class changed after a reload".
const compoundTagRoundTrip = (stored, written) => {
  const tag = {};
  tag[tagLiteral] = stored;                    // what set() wrote
  const saved = JSON.stringify(tag);           // the entity is written to disk
  const loaded = JSON.parse(saved);            // ... and read back
  return loaded[tagLiteral];
};
for (const armorClass of [0, 1, 3, 6]) {
  check(compoundTagRoundTrip(armorClass) === armorClass,
    `class ${armorClass} survives a save/load round trip unchanged`);
}
check(/public static boolean rollOnce\(LivingEntity entity, RandomSource random\)/.test(armor)
  && /if \(entity\.getPersistentData\(\)\.contains\(TAG\)\) \{\s*return false;\s*\}/.test(armor),
  'rollOnce refuses to roll twice, so a reload cannot reroll the class (belt to the save/load braces)');
check(/int min = Math\.min\(Config\.ELITE_MIN_ARMOR_CLASS\.get\(\), Config\.ELITE_MAX_ARMOR_CLASS\.get\(\)\);/
  .test(armor) && /min \+ random\.nextInt\(max - min \+ 1\)/.test(armor),
  'and the roll is inclusive of both ends of minArmorClass..maxArmorClass');
check(/event\.loadedFromDisk\(\)/.test(armorSpawn) && /rollForIfTroop/.test(armorSpawn)
  && /isClientSide\(\)/.test(armorSpawn),
  'the roll happens once per real spawn (never on a chunk load, never on the client)');
// A listener that is never registered is a silent no-op in game, which is exactly the kind of gap a source
// gate has to close: the two halves are wired in TarkovScav's constructor.
const mainSrc = read('TarkovScav.java');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.entity\.ArmorClass\.class\)/.test(mainSrc)
  && /MinecraftForge\.EVENT_BUS\.addListener\(com\.gfl\.tarkovscav\.entity\.ArmorClassSpawn::onJoinLevel\)/
    .test(mainSrc),
  'both halves are registered on the Forge event bus (damage hook + spawn roll)');
// The roll covers all six classes: min < max and nextInt(max - min + 1) walks the whole inclusive range once
// per residue, so 1..6 is reachable in full (a "class 7" cannot be rolled because the range ends at max).
const rolled = Array.from({ length: 6 }, (_, k) => 1 + (k % 6));
check(new Set(rolled).size === 6 && Math.min(...rolled) === 1 && Math.max(...rolled) === 6,
  'and the roll reaches all six classes (1 + nextInt(6) covers 1..6 exactly)', rolled.join(','));

// ---------------------------------------------------------------- option B: the data conclusion
// Only the CONCLUSION is checked; option B is deliberately not implemented and this gate does not ask for it.
const optionB = 1 - (1 - 0.60) * (1 - 0.80);
check(optionB > 0.60 && Math.abs(optionB - 0.92) < 1e-9,
  'a vanilla 20-point suit on top of class 6 would reduce 92 %, not 60 % - that is why option B is not done',
  `${(optionB * 100).toFixed(0)} % at 20 armor points`);
check(/方案 B/.test(README5y) && /平均吻合/.test(README5y),
  'and README 5y says out loud why (the vanilla curve only matches on average)');

// ---------------------------------------------------------------- the accuracy tier mapping
const veteranCap = Number((/ACCURACY_VETERAN_CAP\s*=[\s\S]{0,400}?defineInRange\("profileVeteranCap",\s*([\d.]+)D/
  .exec(configSrc) || [])[1]);
const eliteCap = Number((/ACCURACY_ELITE_CAP\s*=[\s\S]{0,400}?defineInRange\("profileEliteCap",\s*([\d.]+)D/
  .exec(configSrc) || [])[1]);
check(veteranCap === 0.85 && eliteCap === 0.90,
  'the caps the troops resolve to are the documented 0.85 / 0.90',
  `veteran ${veteranCap}, elite ${eliteCap}`);
const resolvedTier = (cls) => {
  if (cls === 'EliteVillagerEntity' || cls === 'ElitePillagerEntity') {
    return { profile: 'elite', cap: eliteCap };
  }
  if (cls === 'UsecVillagerEntity' || cls === 'BearPillagerEntity') {
    return { profile: 'veteran', cap: veteranCap };
  }
  return null; // the by-type rule decides
};
for (const troop of TROOPS) {
  const resolved = resolvedTier(troop.cls);
  check(resolved !== null && resolved.profile === troop.tier.toLowerCase(),
    `${troop.id} resolves to the ${troop.tier.toLowerCase()} profile`,
    resolved ? `${resolved.profile} cap ${resolved.cap}` : 'null');
  check(resolved !== null && resolved.cap === (troop.tier === 'ELITE' ? 0.90 : 0.85),
    `  and its cap is ${troop.tier === 'ELITE' ? '0.90' : '0.85'}`);
}
for (const cls of ['ScavEntity', 'GunnerPillagerEntity', 'GunnerVillagerEntity', 'SniperPillagerEntity',
  'SniperVillagerEntity']) {
  check(resolvedTier(cls) === null, `${cls} is untouched by the troop override (by-type rules still decide)`);
}
check(/if \(mob instanceof EliteVillagerEntity \|\| mob instanceof ElitePillagerEntity\)/.test(tierProfile)
  && /if \(mob instanceof UsecVillagerEntity \|\| mob instanceof BearPillagerEntity\)/.test(tierProfile),
  'FactionTierProfile checks the elite pair first, then USEC/BEAR');
check(/Profile troop = com\.gfl\.tarkovscav\.entity\.FactionTierProfile\.profileFor\(mob\);[\s\S]{0,200}?return troop;/
  .test(accuracy), 'and AccuracyProfile consults it before every by-type rule');
check(/ModEntities\.SCAV\.get\(\)/.test(accuracy) && /ModEntities\.GUNNER_PILLAGER\.get\(\)/.test(accuracy)
  && /ModEntities\.GUNNER_VILLAGER\.get\(\)/.test(accuracy),
  'the original by-type branches are all still there (nothing was replaced, only preceded)');
check(!/getType\(\)/.test(tierProfile),
  'the troop mapping is by CLASS, so a data-pack entity can never be promoted by accident');

// ---------------------------------------------------------------- the voice pools, as a table
// FAMILIES_ALL is declared BEFORE it is used: the first version of this section used it inside a loop that
// ran above the declaration, which is a TDZ ReferenceError (the gate died instead of failing a check).
const CATEGORIES = ['contact', 'idle', 'chatter', 'grenade', 'death', 'mark'];
const FAMILIES_ALL = ['shared', 'usec', 'bear', 'elite']
  .flatMap((family) => CATEGORIES.map((category) => family + '_' + category));
const resolvePool = (family, category, available) => {
  if (family !== 'shared' && available.includes(family + '_' + category)) {
    return family + '_' + category;
  }
  return 'shared_' + category;
};
const FAMILY_BY_ENTITY = {
  usec_villager: 'usec', bear_pillager: 'bear', elite_villager: 'elite', elite_pillager: 'elite',
  scav: 'shared', gunner_pillager: 'shared', gunner_villager: 'shared', sniper_pillager: 'shared',
  sniper_villager: 'shared',
};
let foreignPools = 0;
for (const [id, family] of Object.entries(FAMILY_BY_ENTITY)) {
  for (const category of CATEGORIES) {
    // (a) with every family's clips present, nobody may reach another family's pool.
    const withAll = resolvePool(family, category, FAMILIES_ALL);
    if (FAMILIES_ALL.filter((other) => other !== family && withAll === other + '_' + category).length) {
      foreignPools++;
    }
    check(withAll === family + '_' + category,
      `${id} speaks its own pool for ${category}`, withAll);
    // (b) with NO family clips at all (the state delivery 10 ships in), everybody falls back to shared.
    check(resolvePool(family, category, []) === 'shared_' + category,
      `${id} falls back to the shared pool for ${category} when the family has no clips`);
    // (c) a family with SOME clips uses them for those and falls back for the rest.
    check(resolvePool(family, category, [family + '_' + category]) === family + '_' + category,
      `${id} uses its own ${category} clips as soon as they exist`);
  }
}
check(foreignPools === 0, 'no entity in the table can ever resolve to another family\'s pool');
check(FAMILIES_ALL.length === 24, 'the family x category table is 4 x 6', `${FAMILIES_ALL.length} entries`);
check(/ModSounds\.pool\(family \+ "_" \+ category\)/.test(voicePools)
  && /family\.equals\("shared"\)/.test(voicePools),
  'VoicePools looks a family pool up by name and falls back to the shared pool when it is empty');
check(/public static List<SoundEvent> pool\(Mob mob, RegistryObject<SoundEvent>\[\] shared, String category\)/
  .test(voicePools), 'the fallback is the pool the caller passed in (no hard-coded shared list)');
check(/public String voiceFamily\(\)/.test(pillagerBase) && /public String voiceFamily\(\)/.test(villagerBase),
  'both gunner bases expose the family hook, defaulting to shared (the old mobs are untouched)');
for (const troop of TROOPS) {
  const family = (/return "([a-z]+)";\s*\}\s*\}/.exec(read('entity/' + troop.cls + '.java')) || [])[1];
  check(family === troop.family, `${troop.id}'s own clips are the ${troop.family} ones`, family);
}
check(/@Override\s*public String voiceFamily\(\)/.test(read('entity/UsecVillagerEntity.java')),
  'the troops override it (so a new family is one class, not a new branch in VoicePools)');

// ---------------------------------------------------------------- the command readout and the docs
for (const needle of ['ArmorClass.describe', 'FactionTierProfile.describe', 'VoicePools.describe']) {
  check(commandsSrc.includes(needle), `/tarkovscav prints ${needle}`);
}
check(/armor=/.test(armor) && /less damage/.test(armor),
  'and the armor readout says which class and how much less damage');
check(/ModEntities\.USEC_VILLAGER\.get\(\)/.test(commandsSrc)
  && /ModEntities\.ELITE_PILLAGER\.get\(\)/.test(commandsSrc),
  '/tarkovscav spawn accepts the four troops (they used to be refused by the whitelist)');
check(/### 5y\./.test(README5y), 'README has the 5y section');
for (const key of ['armorEnabled', 'armorReductionPerClass', 'minArmorClass', 'maxArmorClass',
  'usecVillagerWeight', 'bearPillagerWeight', 'eliteVillagerWeight', 'elitePillagerWeight']) {
  check(README5y.includes(key), `README documents the troop config key ${key}`);
}
for (const weight of [['USEC_VILLAGER_SPAWN_WEIGHT', 'usecVillagerWeight', 1],
  ['BEAR_PILLAGER_SPAWN_WEIGHT', 'bearPillagerWeight', 1],
  ['ELITE_VILLAGER_SPAWN_WEIGHT', 'eliteVillagerWeight', 1],
  ['ELITE_PILLAGER_SPAWN_WEIGHT', 'elitePillagerWeight', 1]]) {
  const value = Number((new RegExp(`${weight[0]}\\s*=[\\s\\S]{0,400}?defineInRange\\("${weight[1]}",\\s*(\\d+),`)
    .exec(configSrc) || [])[1]);
  check(value === weight[2], `${weight[1]} ships as the documented ${weight[2]}`, String(value));
}

console.log('');
if (failures > 0) {
  console.log(`${failures} entity registry check(s) FAILED`);
  process.exit(1);
}
console.log(`every entity type is registered, rendered and spawnable (${rows.length} checked)`);
