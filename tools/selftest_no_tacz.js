// TaCZ is optional (README 5ac). The mod has to LOAD and FIGHT in a pack that does not have TaCZ installed,
// and the whole reason that is delicate is Java's lazy class resolution:
//
//   * a method that names a TaCZ type may never be EXECUTED without TaCZ, and
//   * a class with a TaCZ-typed field initialiser / static initialiser may never be LOADED without TaCZ
//     (GunAttachments is exactly that case - `private static final AttachmentType[] SLOTS`).
//
// A compiler cannot see any of this, which is why the checks below are structural and source-derived: the
// pinned list of files that touch TaCZ, the absence of TaCZ-typed static fields outside the one documented
// class, the guards that keep the unexecutable methods unexecuted, and the one place that answers "is TaCZ
// here?". The checks that CANNOT be made here are named out loud at the end of this file rather than faked.
//
//   node tools/selftest_no_tacz.js
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const readRoot = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const count = (text, needle) => text.split(needle).length - 1;
/** Comments out of the way - a javadoc that says "static" and later names IGun is not a field. */
const stripComments = (source) => source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').map((line) => line.replace(/\/\/.*$/, '')).join('\n');

// ---------------------------------------------------------------------------------------------
console.log('1. mods.toml declares tacz as an OPTIONAL dependency');
const toml = readRoot('src/main/resources/META-INF/mods.toml');
const depBlocks = [...toml.matchAll(/\[\[dependencies\.[^\]]+\]\]([\s\S]*?)(?=\[\[|$)/g)].map((m) => m[1]);
const taczBlock = depBlocks.find((block) => /modId\s*=\s*"tacz"/.test(block));
check(taczBlock !== undefined, 'mods.toml has a dependency block for modId "tacz"');
check(taczBlock !== undefined && /mandatory\s*=\s*false/.test(taczBlock),
  'that block says mandatory = false (a pack without TaCZ is not refused at launch)',
  taczBlock === undefined ? '' : (taczBlock.match(/mandatory\s*=\s*\w+/) || ['no mandatory line'])[0]);
check(!/modId\s*=\s*"tacz"[\s\S]{0,400}?mandatory\s*=\s*true/.test(toml),
  'no block declares tacz with mandatory = true');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. the files that touch TaCZ are a pinned, reviewable list');
/** Every .java file under src/main/java that names com.tacz.guns. */
function javaFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return javaFiles(full);
    }
    return entry.name.endsWith('.java') ? [full] : [];
  });
}
const TACZ_FILES = [
  'block/WeaponRackArmament.java',
  'block/WeaponRackTaker.java',
  'client/RenderStateGuard.java',
  'gun/GunAttachments.java',
  'gun/GunBrain.java',
  'gun/GunPool.java',
  'gun/ScriptedGuns.java',
  'gun/TaczPresence.java',
  'loot/CityChestExtras.java',
];
const mentionTacz = javaFiles(JAVA)
  .filter((file) => fs.readFileSync(file, 'utf8').includes('com.tacz.guns'))
  .map((file) => path.relative(JAVA, file).split(path.sep).join('/'))
  .sort();
const pinned = [...TACZ_FILES].sort();
check(mentionTacz.join(', ') === pinned.join(', '),
  `exactly ${pinned.length} source file(s) name com.tacz.guns`,
  mentionTacz.join(', ') === pinned.join(', ')
    ? pinned.join(', ')
    : `new: [${mentionTacz.filter((f) => !pinned.includes(f)).join(', ')}] gone: [${pinned.filter((f) => !mentionTacz.includes(f)).join(', ')}]`);

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. no TaCZ-typed static field outside the one documented class');
const TACZ_TYPES = ['IGun', 'IAttachment', 'AttachmentType', 'IGunOperator', 'ReloadState', 'ShootResult',
  'AbstractGunItem', 'TimelessAPI', 'CommonGunIndex', 'CommonAttachmentIndex', 'AttachmentItemBuilder',
  'AmmoItemBuilder', 'GunItemBuilder', 'FireMode', 'Bolt', 'GunData'];
/** A `static` FIELD whose declared type is one of the TaCZ simple names - the class-loading landmine.
 *  A static METHOD that merely returns/accepts such a type is not one (its body is resolved on a call). */
function taczTypedStaticField(source) {
  const pattern = new RegExp(`^\\s*(?:(?:public|private|protected|static|final|transient|volatile)\\s+)+[^()=;]*\\b(${TACZ_TYPES.join('|')})\\b[^()]*[=;{]\\s*$`);
  return stripComments(source)
    .split('\n')
    .some((line) => /\bstatic\b/.test(line) && pattern.test(line));
}
const dangerous = TACZ_FILES.filter((rel) => taczTypedStaticField(read(rel)));
check(dangerous.length === 1 && dangerous[0] === 'gun/GunAttachments.java',
  'GunAttachments is the ONLY file with a TaCZ-typed static field (so ONLY it may never be class-loaded'
  + ' without TaCZ)', dangerous.join(', ') || 'none found');
const attachments = read('gun/GunAttachments.java');
check(/private static final AttachmentType\[\] SLOTS/.test(attachments),
  'the pinned landmine is still the one the javadoc names (AttachmentType[] SLOTS)');
const presence = read('gun/TaczPresence.java');
check(/must never be <b>loaded<\/b>/.test(presence) && /GunAttachments\} is exactly that case/.test(presence),
  'TaczPresence documents the load-time rule, not only the execute-time one');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. one presence check, and the guards that keep TaCZ code unexecuted');
check(count(stripComments(read('gun/TaczPresence.java')), 'isLoaded("tacz")') === 1,
  'the "is TaCZ here?" answer lives in exactly one method (TaczPresence.loaded)');
const lootModifier = read('loot/CityChestLootModifier.java');
check(lootModifier.includes('isLoaded(TarkovScav.TACZ_MOD_ID)'),
  'the loot modifier keeps its own pre-existing runtime guard (the other allowed check)');

const brain = read('gun/GunBrain.java');
check(count(brain, '!TaczPresence.loaded()') >= 4,
  'GunBrain guards every entry point that runs without TaCZ (tick, onHurt, magazine, debugSummary)',
  `${count(brain, '!TaczPresence.loaded()')} guard(s)`);
const tickStart = brain.indexOf('public void tick()');
const tickGuard = brain.indexOf('if (!TaczPresence.loaded())', tickStart);
const firstEquipInTick = brain.indexOf('equip(this.mob.getRandom());', tickStart);
check(tickGuard > tickStart && tickGuard < firstEquipInTick,
  'GunBrain.tick returns before the loadout/equip path (the ladder goal calls tick without TaCZ)');
check(brain.includes('this.doors.tick(level);')
  && brain.indexOf('this.doors.tick(level);', tickStart) < tickGuard,
  'the door driver stays OUTSIDE the guard: a TaCZ-free unit still opens and closes doors');

const commands = read('command/ModCommands.java');
check(commands.includes('private static boolean requireTacz(CommandSourceStack source, String what)'),
  'ModCommands has one refusal helper for TaCZ-only reports');
const refusals = [...commands.matchAll(/requireTacz\(source, "([^"]+)"\)/g)].map((m) => m[1]);
check(refusals.length >= 4, 'every TaCZ-only report refuses through it', refusals.join(' | '));
for (const needed of ['The sniper report', 'The attachment report', 'The fight harness', 'The gun-pool report']) {
  check(refusals.includes(needed), `the refusal list covers "${needed}"`);
}
const clientCommands = read('command/ClientCommands.java');
const clientGuard = clientCommands.indexOf('TaczPresence.loaded()');
const clientUse = clientCommands.indexOf('GunAttachments.invalidate()');
check(clientGuard > 0 && clientUse > clientGuard,
  'ClientCommands guards GunAttachments.invalidate BEFORE the first call (the class must not be loaded)');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. the fallback: equipment, the config keys and the ranged goal');
const fallback = read('gun/FallbackEquipment.java');
check(fallback.includes('!"bow".equalsIgnoreCase(Config.GUNS_FALLBACK_WEAPON.get().trim())'),
  'wantsCrossbow() accepts only "bow" (case-insensitive); every other value is the crossbow');
check(fallback.includes('EquipmentSlot.MAINHAND, weapon'),
  'the fallback weapon goes to the MAIN hand (where ArmedRangedGoal looks for it)');
check(fallback.includes('EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, arrows)'),
  'the arrows go to the offhand as a carried stack');
check(fallback.includes('Items.CROSSBOW : Items.BOW'), 'both weapons are reachable from the config value');

const config = read('Config.java');
check(config.includes('.define("fallbackWeapon", "crossbow")'),
  'Config registers guns.fallbackWeapon with the shipped default "crossbow"');
check(config.includes('.defineInRange("fallbackArrows", 32, 0, 256)'),
  'Config registers guns.fallbackArrows as 32 in 0..256');

for (const entity of ['ScavEntity', 'GunnerPillagerEntity', 'GunnerVillagerEntity']) {
  const source = read(`entity/${entity}.java`);
  check(count(source, 'TaczPresence.loaded()') >= 3,
    `${entity} branches on TaczPresence.loaded() for equipment, goals and persistence`,
    `${count(source, 'TaczPresence.loaded()')} branch(es)`);
  check(count(source, 'FallbackEquipment.equip(this, this.getRandom())') >= 3,
    `${entity} issues the fallback weapon on all three equipment paths`,
    `${count(source, 'FallbackEquipment.equip(this, this.getRandom())')} call(s)`);
  check(source.includes('new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(')
    || source.includes('MeleeAttackGoal(this'),
    `${entity} keeps a plain melee goal for the no-TaCZ branch`);
  check(source.includes('new com.gfl.tarkovscav.gun.ArmedRangedGoal('),
    `${entity} registers ArmedRangedGoal unconditionally (it is the bow AI)`);
}
const scav = read('entity/ScavEntity.java');
check(/class ScavEntity[^{]*implements[^{]*RangedAttackMob/.test(scav),
  'ScavEntity implements RangedAttackMob (a Monster has no ranged attack of its own)');
check(/public void performRangedAttack\(LivingEntity target, float distanceFactor\)/.test(scav),
  'ScavEntity implements performRangedAttack');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('6. the arrows are cosmetic, and every document says so');
const shot = scav.slice(scav.indexOf('public void performRangedAttack'));
const body = shot.slice(0, shot.indexOf('\n    }'));
check(body.includes('getProjectile(weapon)') && body.includes('ProjectileUtil.getMobArrow('),
  'the fallback shot uses the vanilla projectile path (the same one a skeleton uses)');
check(!/\.shrink\(|consume\(/.test(body),
  'and it does NOT consume the quiver - the documented "arrows are cosmetic" behaviour is the real one');
check(config.includes('vanilla skeletons never run out of arrows'),
  'Config.GUNS_FALLBACK_ARROWS says the shot does not consume the stack');
check(fallback.includes('a vanilla skeleton never runs out of arrows either'),
  'FallbackEquipment says the same thing in the same words');
const readme = readRoot('README.md');
const heading = '### 5ac. ';
const sectionStart = readme.indexOf(heading);
check(sectionStart > 0, `README has the section "${heading.trim()}"`);
const section = sectionStart < 0 ? '' : readme.slice(sectionStart, readme.indexOf('\n### ', sectionStart + 4));
check(section.includes('fallbackWeapon') && section.includes('fallbackArrows'),
  'that section documents both config keys');
check(section.includes('getMobArrow'),
  'that section states the cosmetic-arrow fact with the vanilla method name');
check(section.includes('requireTacz') || section.includes('\u62d2\u7edd'),
  'that section states that TaCZ-only commands refuse cleanly (Chinese for "refuse")');
const intro = readRoot('docs/\u6a21\u7ec4\u4ecb\u7ecd.md');
check(intro.includes('TaCZ') && intro.includes('\u53ef\u9009'),
  'docs intro says TaCZ is optional (Chinese for "optional")');
const wiki = readRoot('docs/COMMAND_AND_CONFIG_REFERENCE.md');
check(wiki.includes('guns.fallbackWeapon') && wiki.includes('guns.fallbackArrows'),
  'the wiki reference documents both keys');

// A stale section number is how a re-pointed reference rots, so the old one is pinned as gone.
const staleRefs = [];
for (const file of javaFiles(JAVA).concat([path.join(ROOT, 'src/main/resources/META-INF/mods.toml')])) {
  const text = fs.readFileSync(file, 'utf8');
  for (const old of ['No TaCZ (README 5x)', 'WHEN TaCZ IS NOT INSTALLED (README 5x)',
    'bow/crossbow fallback (README 5x)', 'TaCZ is OPTIONAL since 2026-10 (README 5x)']) {
    if (text.includes(old)) {
      staleRefs.push(`${path.basename(file)}: ${old}`);
    }
  }
}
check(staleRefs.length === 0, 'no no-TaCZ reference still points at the old README section 5x',
  staleRefs.join(' | '));

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('WHAT THIS FILE CANNOT CHECK (by design, so nothing here pretends otherwise):');
console.log('  * that the game actually launches and plays without TaCZ. The dev classpath always has TaCZ,');
console.log('    so TaczPresence.loaded() is true in every automated run; the only real test is a pack with');
console.log('    the mod and no TaCZ on a client. That is user-verified, and README 5ac says so.');
console.log('  * that a bow-armed unit aims well or that the fallback feels balanced. Not measurable here.');

console.log('');
if (failures > 0) {
  console.log(`${failures} no-TaCZ check(s) FAILED`);
  process.exit(1);
}
console.log('the mod is structurally fit to load and fight without TaCZ');
