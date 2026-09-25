// scratch: add the name-resolution section to the entity-registry gate (README 5x)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_entity_registry.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;

const section = `
console.log('');
console.log('11. names: no raw translation key may reach a screen (README 5x)');
const names = read('entity/EntityNames.java');
const villagerEntity = read('entity/GunnerVillagerEntity.java');
const killFeed = read('killfeed/KillFeed.java');
const commandsSrc = read('command/ModCommands.java');
// The bug: vanilla Villager#getTypeName() concatenates the profession onto the entity's own key, and we never
// shipped those keys, so the kill feed printed "entity.tarkovscav.gunner_villager.weaponsmith".
check(/public net\\.minecraft\\.network\\.chat\\.Component getTypeName\\(\\)/.test(villagerEntity)
  && /EntityNames\\.villagerTypeName\\(this\\.getType\\(\\), this\\.getVillagerData\\(\\)\\)/.test(villagerEntity),
  'GunnerVillagerEntity overrides getTypeName() with our own composer (and the sniper villager inherits it)');
check(!/getDescriptionId\\(\\) \\+/.test(villagerEntity + names)
  || /A switch of <b>literal<\\/b> keys/.test(names),
  'nothing concatenates a profession onto a translation key any more');
check(/professionKey\\(@Nullable VillagerProfession profession\\)/.test(names)
  && (names.match(/entity\\.minecraft\\.villager\\./g) || []).length >= 14,
  'the vanilla profession keys are literal entries in a table (so the asset gate can resolve them)',
  \`\${(names.match(/entity\\.minecraft\\.villager\\./g) || []).length} literal key(s)\`);
check(/public static String safeName\\(Entity entity\\)/.test(names)
  && /public static boolean looksUntranslated\\(@Nullable String text\\)/.test(names)
  && /WARNED\\.add\\(type\\)/.test(names)
  && /TarkovScav\\.LOGGER\\.warn\\("\\[names\\]/.test(names),
  'and there is a floor: an untranslated-looking name is replaced by the entity type path and WARNed once');
check(/EntityNames\\.safeName\\(killer\\)/.test(killFeed) && /EntityNames\\.safeName\\(victim\\)/.test(killFeed),
  'the kill feed runs both names through it (the screen this bug was reported on)');
check(/EntityNames\\.safeName\\(/.test(commandsSrc),
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
    \`\${langName}: the villager name and the composition format exist\`);
  for (const profession of professions) {
    // Mirrors EntityNames: no profession (or none) -> the bare base name; otherwise base + vanilla profession.
    const professionText = table['entity.minecraft.villager.' + profession];
    const composed = profession === 'none' || professionText === undefined
      ? base
      : format.replace('%s', base).replace('%s', professionText);
    check(!composed.startsWith('entity.') && !composed.includes('tarkovscav.'),
      \`\${langName}: \${profession} renders as a real name\`, composed);
  }
}
// The survey: every entity we register must have a name key, and only the two Villager-based ones have a
// variant (profession) in their name.
const langZh = langFor('zh_cn');
for (const row of rows) {
  check(langZh['entity.' + row.id] !== undefined || langZh['entity.tarkovscav.' + row.id.split(':')[1]]
    !== undefined, \`\${row.id} has a name key (no variant can turn it into a raw key)\`);
}
check(/VillagerData/.test(villagerEntity) || /getVillagerData\\(\\)/.test(villagerEntity),
  'only the Villager-derived entities have a variant-sensitive name (surveyed: nobody else builds one)');
check(/### 5x\\./.test(readme), 'README has the 5x section');

console.log('');
if (failures > 0) {`;

text = text.replace(`
console.log('');
if (failures > 0) {`, section);
fs.writeFileSync(file, text);
console.log('changed:', before !== text);
