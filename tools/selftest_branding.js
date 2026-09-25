// Branding gate: the mod is called "Armed Mobs" everywhere a player can see it, while the internal
// identity stays tarkovscav.
//
//   node tools/selftest_branding.js
//
// Why each check exists:
//
//   1. the display name is the one string a tester reads in the mod list - mods.toml displayName has to
//      be exactly "Armed Mobs", with no "Tarkov"/"Scav"/"Thug" left in it;
//   2. gradle.properties carries the human-readable metadata that ends up in the jar manifest and in
//      mods.toml (name, authors, description). None of those may reference Tarkov;
//   3. the creative tab and the key-binding category are player-visible, so they read "Armed Mobs" in
//      en_us while zh_cn keeps its own name (武装暴徒) - a translation is allowed to differ;
//   4. the artifact name is display-only too: build.gradle must take archivesName from mod_jar_name
//      (armedmobs), NOT from mod_id;
//   5. /armedmobs is registered right next to /tarkovscav, in BOTH command trees (the server-side
//      operator tree and the client-side one). A gate that only looked for the string "/armedmobs"
//      somewhere would pass on a comment; this one requires the two dispatcher.register calls side by
//      side, so the alias cannot silently drift away from the tree it aliases;
//   6. the four Java sites that used to carry the old wording (two javadoc comments and two display
//      strings) now say "Armed Mobs", and no Java source anywhere still says "Tarkov Scav" or
//      "Armed Thug";
//   7. mod_id is STILL tarkovscav. This is the check that stops a well-meaning rename of the id, which
//      would break already-generated structures, block/NBT tags and the user's tuned config.
//
// A few extra checks guard the rebrand's real hazards rather than its strings: the resource-pack
// description, the installer (new jar name + the pre-rebrand jar moved aside) and the README's alias note.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(ROOT, rel));

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** Parses a flat key=value properties file (comments and blank lines skipped). */
function properties(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.trim().startsWith('#')) continue;
    const equals = line.indexOf('=');
    if (equals > 0) out[line.slice(0, equals).trim()] = line.slice(equals + 1).trim();
  }
  return out;
}

/** True when a line containing `a` and a line containing `b` are at most maxGap lines apart. */
function adjacent(lines, a, b, maxGap) {
  const at = [];
  const bt = [];
  lines.forEach((line, index) => {
    if (line.includes(a)) at.push(index);
    if (line.includes(b)) bt.push(index);
  });
  return at.some((i) => bt.some((j) => Math.abs(i - j) <= maxGap));
}

const BANNED = /Tarkov|Scav/i;

// -------------------------------------------------------------------------------------------------
console.log('1. mods.toml displayName');
const modsToml = read('src/main/resources/META-INF/mods.toml');
const displayLine = (modsToml.split(/\r?\n/).find((line) => /^\s*displayName\s*=/.test(line)) || '').trim();
check(/^displayName="Armed Mobs"$/.test(displayLine), 'displayName is exactly "Armed Mobs"', displayLine || '(line not found)');
check(!BANNED.test(displayLine), 'the displayName line has no Tarkov/Scav wording');
check(modsToml.includes('modId="${mod_id}"'), 'mods.toml still takes its id from ${mod_id} (no literal rename)');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('2. gradle.properties metadata');
const props = properties(read('gradle.properties'));
check(props.mod_name === 'Armed Mobs', 'mod_name is "Armed Mobs"', props.mod_name || '(missing)');
for (const key of ['mod_name', 'mod_authors', 'mod_description']) {
  check(!!props[key] && !BANNED.test(props[key]), `${key} contains no Tarkov/Scav wording`,
    props[key] ? `"${props[key].slice(0, 70)}${props[key].length > 70 ? '...' : ''}"` : '(missing)');
}

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('3. player-visible lang keys');
const en = JSON.parse(read('src/main/resources/assets/tarkovscav/lang/en_us.json'));
const zh = JSON.parse(read('src/main/resources/assets/tarkovscav/lang/zh_cn.json'));
check(en['itemGroup.tarkovscav'] === 'Armed Mobs', 'en_us creative tab is "Armed Mobs"', String(en['itemGroup.tarkovscav']));
check(en['key.categories.tarkovscav'] === 'Armed Mobs', 'en_us key category is "Armed Mobs"', String(en['key.categories.tarkovscav']));
check(zh['itemGroup.tarkovscav'] === '武装暴徒', 'zh_cn keeps its own creative-tab name', String(zh['itemGroup.tarkovscav']));
check(zh['key.categories.tarkovscav'] === '武装暴徒', 'zh_cn keeps its own key-category name', String(zh['key.categories.tarkovscav']));
// The command hints in the lang files tell the player what to type; since /armedmobs now exists, a hint
// that still spells /tarkovscav would be sending testers to the internal name.
const staleHints = [];
for (const [lang, table] of [['en_us', en], ['zh_cn', zh]]) {
  for (const [key, value] of Object.entries(table)) {
    if (typeof value === 'string' && value.includes('/tarkovscav ')) staleHints.push(`${lang}:${key}`);
  }
}
check(staleHints.length === 0, 'no command hint tells the player to type /tarkovscav',
  staleHints.length ? staleHints.join(', ') : 'every hint uses /armedmobs');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('4. the artifact name is display-only');
const buildGradle = read('build.gradle');
check(/archivesName\s*=\s*mod_jar_name\b/.test(buildGradle), 'build.gradle sets archivesName = mod_jar_name',
  (/archivesName[^\n]*/.exec(buildGradle) || ['(no archivesName line)'])[0].trim());
check(props.mod_jar_name === 'armedmobs', 'gradle.properties mod_jar_name is armedmobs', props.mod_jar_name || '(missing)');
check(!/archivesName\s*=\s*mod_id\b/.test(buildGradle), 'archivesName is NOT taken from mod_id');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('5. the /armedmobs alias is registered next to /tarkovscav');
const modCommands = read('src/main/java/com/gfl/tarkovscav/command/ModCommands.java');
const clientCommands = read('src/main/java/com/gfl/tarkovscav/command/ClientCommands.java');
const mainJava = read('src/main/java/com/gfl/tarkovscav/TarkovScav.java');
check(adjacent(modCommands.split(/\r?\n/), 'tree("tarkovscav")', 'tree("armedmobs")', 2),
  'ModCommands registers both root literals side by side',
  'dispatcher.register(tree("tarkovscav")) then dispatcher.register(tree("armedmobs"))');
check(adjacent(clientCommands.split(/\r?\n/), 'tree("tarkovscav")', 'tree("armedmobs")', 2),
  'ClientCommands registers both root literals side by side');
// One tree body, two registrations: the named helper is what keeps the alias from being a copy.
check(/LiteralArgumentBuilder<CommandSourceStack>\s+tree\(String rootLiteral\)/.test(modCommands),
  'ModCommands builds the tree once in a tree(rootLiteral) helper');
check(/LiteralArgumentBuilder<CommandSourceStack>\s+tree\(String rootLiteral\)/.test(clientCommands),
  'ClientCommands builds the tree once in a tree(rootLiteral) helper');
check(mainJava.includes('ModCommands.register(event.getDispatcher())'),
  'TarkovScav.onRegisterCommands still hands the dispatcher to ModCommands.register');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('6. the four Java sites, and no old wording anywhere in the sources');
const javaDir = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
function javaFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) javaFiles(full, out);
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}
const oldWording = [];
for (const file of javaFiles(javaDir)) {
  const text = fs.readFileSync(file, 'utf8');
  if (text.includes('Tarkov Scav') || text.includes('Armed Thug')) {
    oldWording.push(path.relative(ROOT, file));
  }
}
check(oldWording.length === 0, 'no Java source says "Tarkov Scav" or "Armed Thug"',
  oldWording.length ? oldWording.join(', ') : 'four sites reworded');
check(mainJava.includes('Armed Mobs'), 'TarkovScav.java javadoc says "Armed Mobs"');
check(clientCommands.includes('"Armed Mobs"'), 'ClientCommands kill-feed fallback name is "Armed Mobs"');
check(modCommands.includes('"Armed Mobs Practice Dummy"'), 'ModCommands practice dummy is "Armed Mobs Practice Dummy"');
check(read('src/main/java/com/gfl/tarkovscav/gun/GunAiState.java').includes('"Armed Mobs"'),
  'GunAiState.java comment says "Armed Mobs"');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('7. mod_id is STILL tarkovscav (this is the check that protects the save)');
check(props.mod_id === 'tarkovscav', 'gradle.properties mod_id is tarkovscav', props.mod_id || '(missing)');
check(mainJava.includes('MOD_ID = "tarkovscav"'), 'TarkovScav.MOD_ID is still "tarkovscav"');

// -------------------------------------------------------------------------------------------------
console.log('');
console.log('8. the rebrand hazards: resource pack, installer, README');
const packMeta = read('src/main/resources/pack.mcmeta');
check(!BANNED.test(packMeta), 'the resource-pack description carries no Tarkov/Scav wording',
  (/\"description\"[^\n]*/.exec(packMeta) || ['(none)'])[0].trim());
const installer = read('tools/spike/install_jar.ps1');
check(installer.includes("armedmobs-0.1.0-all.jar"), 'install_jar.ps1 installs armedmobs-0.1.0-all.jar');
check(installer.includes("tarkovscav-0.1.0-all.jar") && /Move-Item[^\n]*\$aside/.test(installer),
  'install_jar.ps1 moves a pre-rebrand jar aside (never leaves it live)',
  'two files with modId tarkovscav would abort the launch');
check(installer.includes('com\\gfl\\tarkovscav\\command\\ModCommands.class'),
  'install_jar.ps1 still verifies ModCommands.class in the archive');
check(read('tools/spike/JarVerify.java').includes('build/libs/armedmobs-0.1.0-all.jar'),
  'JarVerify.java javadoc names the new jar');
const readme = read('README.md');
check(readme.includes('armedmobs-0.1.0-all.jar'), 'README names the new artifact');
check(readme.includes('/armedmobs') && readme.includes('/tarkovscav'),
  'README explains the /armedmobs alias of /tarkovscav');
check(read('docs/CITY_EDIT_GUIDE.md').includes('armedmobs-0.1.0-all.jar'),
  'docs/CITY_EDIT_GUIDE.md names the new artifact');

console.log('');
if (failures > 0) {
  console.log(`${failures} branding check(s) FAILED`);
  process.exit(1);
}
console.log('branding invariants all hold: display "Armed Mobs", internal id still tarkovscav');
