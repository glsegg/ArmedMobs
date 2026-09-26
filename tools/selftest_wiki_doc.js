// The wiki reference document (docs/COMMAND_AND_CONFIG_REFERENCE.md) is a published promise: a
// third-party author reads it instead of the source. So every claim in it that CAN be derived from the
// repository IS derived from the repository here, and a command, a config key or a data-pack tag that
// is added without being documented fails this gate.
//
//   node tools/selftest_wiki_doc.js
//
// What is asserted, and why each one is a different kind of check:
//
//   1. THE COMMAND NODES. Every `Commands.literal("X")` node registered in ModCommands.java and
//      ClientCommands.java has to appear somewhere in the document, so a command cannot ship
//      undocumented. Each missing name is printed.
//   2. THE CONFIG KEYS. Every `.define*("key", ...)` registration in Config.java has to appear in the
//      document - both the 295 keys registered directly in configure() and the per-tier keys defined
//      once each in AiSettings/TierSettings and instantiated per tier (26 and 7 registrations that
//      expand to 132 toml keys). Each missing key is printed.
//   3. THE FOUR TIER SECTIONS AND THE NINE ENTITY IDS. [ai.scav]/[ai.sniper]/[ai.troop]/[ai.elite]
//      must be named, and the nine mob ids from ModEntities.java must all be in the document - with the
//      registration table cross-checked so "nine" is the registry's number, not this file's.
//   4. THE TIER TABLE, ITS ENTITY MAPPING AND ITS NUMBERS. The nine entity ids must be inside the
//      marked entity->tier region, the four tier names inside the marked behaviour region, and the
//      SHIPPED NUMBERS parsed out of Config.java's per-tier switches (reaction, accuracy, cover,
//      suppression, advance, survival, the 5ab six) must all be present in that behaviour region.
//   5. THE THIRD-PARTY SECTION. It has to name the real entity tags, the real structure tag, the real
//      `structures/buildings` directory, the voice manifest/sounds file and the runtime
//      `tarkovscav/city` directory - and each of those has to actually exist in the repository/read by
//      the worldgen code, so the document cannot point at a file that was deleted.
//   6. THE COMMAND ROOTS. The document must mention BOTH `/armedmobs` (the primary, display-name root)
//      and `/tarkovscav` (the compatibility alias). The gate ALSO reports how many root literals it
//      finds in ModCommands.java and demands both are registered, so the rename cannot land in the
//      document and be forgotten in the code (or the other way round). The count is printed either
//      way; this assertion is deliberately not weakened when the rename has not landed.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const DATA = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const DOC_PATH = path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const doc = fs.readFileSync(DOC_PATH, 'utf8');
/** The text between two HTML comment markers, or null when a marker is missing. */
function region(text, startTag, endTag) {
  const start = text.indexOf(startTag);
  const end = text.indexOf(endTag);
  if (start < 0 || end < 0 || end <= start) {
    return null;
  }
  return text.slice(start + startTag.length, end);
}
/** True when `name` appears as a standalone token (not as part of a longer identifier). */
function mentions(text, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^A-Za-z0-9_])${escaped}([^A-Za-z0-9_]|$)`).test(text);
}
/** Every number in a block of text, as Numbers (so 0.80 and 0.8 compare equal). */
function numbersIn(text) {
  return new Set([...text.matchAll(/-?\d+(?:\.\d+)?/g)].map((m) => Number(m[0])));
}

// ---------------------------------------------------------------------------------------------
console.log('1. every command literal node is documented');
const COMMAND_SOURCES = ['command/ModCommands.java', 'command/ClientCommands.java'];
const literalNames = [];
for (const rel of COMMAND_SOURCES) {
  const src = read(rel);
  const found = [...new Set([...src.matchAll(/Commands\.literal\("([^"]+)"\)/g)].map((m) => m[1]))];
  console.log(`        ${path.basename(rel)}: ${found.join(', ')}`);
  for (const name of found) {
    if (!literalNames.includes(name)) {
      literalNames.push(name);
    }
  }
}
const missingLiterals = literalNames.filter((name) => !mentions(doc, name));
check(literalNames.length > 0, 'the command trees register literal nodes',
  `${literalNames.length} distinct literal(s)`);
check(missingLiterals.length === 0,
  `every Commands.literal(...) node name appears in the document (${literalNames.length} name(s))`,
  missingLiterals.length ? `missing: ${missingLiterals.join(', ')}` : '');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. every config key is documented');
const config = read('Config.java');
const keyPattern = /\.define(?:InRange|ListAllowEmpty|List|Enum)?\(\s*(?:List\.of\()?"([A-Za-z0-9_]+)"/g;
const keyMatches = [...config.matchAll(keyPattern)].map((m) => m[1]);
const keys = [...new Set(keyMatches)];
const missingKeys = keys.filter((key) => !doc.includes(key));
check(keyMatches.length === 358,
  `Config.java registers 358 define() call sites including the one-time gun mount revision`,
  `found ${keyMatches.length}`);
check(keys.length === 328,
  `those call sites carry 328 distinct key names including gunMountRevision`,
  `found ${keys.length}`);
check(missingKeys.length === 0, `every config key appears in the document`,
  missingKeys.length ? `missing: ${missingKeys.join(', ')}` : `${keys.length} key(s)`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. the four tier sections and the nine entity ids');
for (const section of ['[ai.scav]', '[ai.sniper]', '[ai.troop]', '[ai.elite]']) {
  check(doc.includes(section), `the document names the config section ${section}`);
}
const entitiesSource = read('registry/ModEntities.java');
const entityRe = /ENTITY_TYPES\.register\("([a-z0-9_]+)",\s*\(\) -> EntityType\.Builder\.<[^>]+>of\([^,]+,\s*MobCategory\.(\w+)\)/g;
const registered = [...entitiesSource.matchAll(entityRe)].map((m) => ({ id: m[1], category: m[2] }));
const mobIds = registered.filter((row) => row.category !== 'MISC').map((row) => row.id);
check(mobIds.length === 9, 'ModEntities registers exactly nine mob types', mobIds.join(', '));
const missingMobIds = mobIds.filter((id) => !doc.includes(`tarkovscav:${id}`));
check(missingMobIds.length === 0, 'every registered mob id is in the document',
  missingMobIds.length ? `missing: ${missingMobIds.join(', ')}` : '');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. the tier table: the nine entities, the four tiers and the shipped numbers');
const ENTITY_TIER = region(doc, '<!-- ENTITY_TIER_TABLE_START -->', '<!-- ENTITY_TIER_TABLE_END -->');
const TIER_BEHAVIOUR = region(doc, '<!-- TIER_BEHAVIOUR_TABLE_START -->', '<!-- TIER_BEHAVIOUR_TABLE_END -->');
check(ENTITY_TIER !== null, 'the entity -> tier table is marked');
check(TIER_BEHAVIOUR !== null, 'the tier behaviour table is marked');
const entityRegion = ENTITY_TIER || '';
const behaviourRegion = TIER_BEHAVIOUR || '';
const missingInMapping = mobIds.filter((id) => !entityRegion.includes(`tarkovscav:${id}`));
check(missingInMapping.length === 0, 'the entity -> tier table lists all nine entity types',
  missingInMapping.length ? `missing: ${missingInMapping.join(', ')}` : mobIds.length + ' ids');
for (const tier of ['SCAV', 'SNIPER', 'TROOP', 'ELITE']) {
  check(mentions(behaviourRegion, tier), `the tier behaviour table has a ${tier} column`);
}
// The shipped numbers, parsed straight out of the per-tier `switch (tier) { ... }` defaults, so the
// table cannot drift from what actually ships.
function switchDefaults(key) {
  const body = new RegExp(`defineInRange\\("${key}", switch \\(tier\\) \\{([\\s\\S]*?)\\},`).exec(config);
  if (!body) {
    return null;
  }
  const values = {};
  for (const match of body[1].matchAll(/case\s+(\w+)\s*->\s*(-?[0-9.]+)D?;/g)) {
    values[match[1]] = Number(match[2]);
  }
  return ['SCAV', 'SNIPER', 'TROOP', 'ELITE'].every((tier) => values[tier] !== undefined) ? values : null;
}
const NUMERIC_KEYS = [
  'reactionMinTicks', 'reactionMaxTicks', 'accuracyScale', 'coverChance', 'coverRadiusScale',
  'suppressChanceScale', 'suppressTicksScale', 'advanceCoverScale', 'repositionScale',
  'retreatHealthScale', 'engageRangeScale', 'exposedBurstShots',
  'exposedBurstCooldownTicks', 'warmupShotsWhenExposed', 'retreatHealthFraction', 'hurtRetreatChance',
  'retreatHoldTicks',
];
const tierNumbers = numbersIn(behaviourRegion);
let numericChecks = 0;
for (const key of NUMERIC_KEYS) {
  const defaults = switchDefaults(key);
  check(defaults !== null, `Config.java defines ${key} as a per-tier switch`);
  if (defaults === null) {
    continue;
  }
  check(behaviourRegion.includes(key), `the tier behaviour table has a ${key} row`);
  for (const tier of ['SCAV', 'SNIPER', 'TROOP', 'ELITE']) {
    const value = defaults[tier];
    const present = tierNumbers.has(value);
    numericChecks++;
    check(present, `${key}.${tier.toLowerCase()} = ${value} (from Config.java) is in the tier table`,
      present ? '' : 'number not found in the marked region');
  }
}
// partialCoverBonus is the one per-tier key with NO switch: it is a single constant registered once
// per tier inside the same loop, so it ships the same value in all four columns. Asserted separately
// so it is still a documented, source-checked row rather than a key silently dropped from this list.
{
  const constant = /defineInRange\("partialCoverBonus",\s*(-?[0-9.]+)D?,/.exec(config);
  check(constant !== null, 'Config.java defines partialCoverBonus as a per-tier constant');
  if (constant) {
    check(behaviourRegion.includes('partialCoverBonus'),
      'the tier behaviour table has a partialCoverBonus row');
    check(numbersIn(behaviourRegion).has(Number(constant[1])),
      `partialCoverBonus = ${Number(constant[1])} (from Config.java) is in the tier table`,
      'the same value ships for all four tiers');
  }
}
console.log(`        ${numericChecks} shipped per-tier number(s) cross-checked against Config.java`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. the third-party section names the real extension points');
const EXTENSION = region(doc, '<!-- EXTENSION_SECTION_START -->', '<!-- EXTENSION_SECTION_END -->');
check(EXTENSION !== null, 'the third-party / data-pack section is marked');
const extension = EXTENSION || '';
const TAG_FILES = ['faction_scav', 'faction_illager', 'faction_village', 'faction_village_hostile',
  'hard_target'];
for (const tag of TAG_FILES) {
  const file = path.join(DATA, 'tags', 'entity_types', `${tag}.json`);
  check(fs.existsSync(file), `the entity tag file data/tarkovscav/tags/entity_types/${tag}.json exists`);
  check(extension.includes(`tarkovscav:${tag}`), `the section names the tag tarkovscav:${tag}`);
}
const cityTag = path.join(DATA, 'tags', 'worldgen', 'structure', 'city.json');
check(fs.existsSync(cityTag), 'the structure tag data/tarkovscav/tags/worldgen/structure/city.json exists');
check(extension.includes('tarkovscav:city'), 'the section names the structure tag tarkovscav:city');
const buildingsDir = path.join(DATA, 'structures', 'buildings');
const buildingNbt = fs.existsSync(buildingsDir)
  ? fs.readdirSync(buildingsDir).filter((name) => name.endsWith('.nbt')) : [];
check(buildingNbt.length > 0, 'data/tarkovscav/structures/buildings/ ships .nbt pieces',
  `${buildingNbt.length} piece(s)`);
check(extension.includes('structures/buildings'), 'the section names the structures/buildings directory');
const districtPools = path.join(DATA, 'worldgen', 'template_pool', 'city_district');
check(fs.existsSync(districtPools), 'the city_district template pools exist');
check(extension.includes('city_district'), 'the section names the city_district pools');
check(extension.includes('voice_clips.txt'), 'the section names the voice manifest voice_clips.txt');
check(fs.existsSync(path.join(ASSETS, 'voice_clips.txt')), 'assets/tarkovscav/voice_clips.txt exists');
check(extension.includes('sounds.json'), 'the section names the sounds.json manifest');
check(fs.existsSync(path.join(ASSETS, 'sounds.json')), 'assets/tarkovscav/sounds.json exists');
const structuresCode = read('world/CityStructures.java');
check(structuresCode.includes('"tarkovscav/city"'),
  'CityStructures reads the runtime directory tarkovscav/city');
check(extension.includes('tarkovscav/city'), 'the section names the runtime tarkovscav/city directory');
// The voice pools the code actually accepts: three owning families and six categories.
for (const family of ['usec', 'bear', 'elite']) {
  check(extension.includes(family), `the voice section names the ${family} family`);
}
for (const category of ['contact', 'chatter', 'idle', 'grenade', 'mark', 'death']) {
  check(extension.includes(category), `the voice section names the ${category} category`);
}
// And the config keys that hold gun ids / attachment control.
for (const key of ['gunBlacklist', 'gunWhitelist', 'excludedGunTypes', 'mods.enabled']) {
  check(extension.includes(key) || doc.includes(key),
    `the gun-pool extension point names the config key ${key}`);
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('6. the command roots');
const modCommands = read('command/ModCommands.java');
const clientCommands = read('command/ClientCommands.java');
const rootRe = /(?:tree|Commands\.literal)\(\s*"([^"]+)"\s*\)/g;
const modRoots = [...new Set([...modCommands.matchAll(rootRe)].map((m) => m[1]))]
  .filter((name) => name === 'armedmobs' || name === 'tarkovscav');
const clientRoots = [...new Set([...clientCommands.matchAll(rootRe)].map((m) => m[1]))]
  .filter((name) => name === 'armedmobs' || name === 'tarkovscav');
check(doc.includes('/armedmobs'), 'the document names the primary root /armedmobs');
check(doc.includes('/tarkovscav'), 'the document names the compatibility alias /tarkovscav');
check(modRoots.length >= 1, 'ModCommands.java registers at least one of the two root literals',
  modRoots.join(', ') || 'none');
check(modRoots.includes('armedmobs') && modRoots.includes('tarkovscav'),
  'ModCommands.java registers BOTH /armedmobs (primary) and /tarkovscav (alias)',
  `roots found in ModCommands.java: ${modRoots.length} [${modRoots.join(', ')}]`);
check(clientRoots.includes('armedmobs') && clientRoots.includes('tarkovscav'),
  'ClientCommands.java registers both roots too',
  `roots found in ClientCommands.java: ${clientRoots.length} [${clientRoots.join(', ')}]`);
console.log(`        ModCommands.java roots: ${modRoots.length} [${modRoots.join(', ')}]`);
console.log(`        ClientCommands.java roots: ${clientRoots.length} [${clientRoots.join(', ')}]`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('7. the document has the required shape');
for (const heading of ['## 1.', '## 2.', '## 3.', '## 4.', '## 5.', '## 6.', '## 7.', '## 8.']) {
  check(doc.includes(heading), `the document has the section heading ${heading}`);
}
check(mentions(doc, 'tarkovscav'), 'the document states the modId tarkovscav');
check(/Armed Mobs/.test(doc) && /武装暴徒/.test(doc), 'the document states the display name');
check(/权限等级\s*\*\*2\*\*|权限等级 2|hasPermission\(2\)/.test(doc),
  'the document states the server permission level 2');
check(/单人/.test(doc) && /客户端命令/.test(doc),
  'the document states that client commands work in single player / on any server');
check(doc.length > 20000, 'the document is a full reference, not a stub', `${doc.length} bytes`);
check(/\d+ 个 `define\*`|define\*/.test(doc) || doc.includes('328'),
  'the document records the number of config registrations');

console.log('');
if (failures > 0) {
  console.log(`${failures} wiki-document check(s) FAILED`);
  process.exit(1);
}
console.log('the wiki reference documents every command, config key and extension point it claims');
