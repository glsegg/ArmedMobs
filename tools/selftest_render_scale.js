// Two model sizes, two keys (README 5b). This gate exists because they were ONE key, and that made every
// armed villager 10 % too big the moment the Bedrock rig was enlarged - the rig's baseline is 0.7, the vanilla
// villager's is 1.0, so a single shared number cannot mean the same thing to both.
//
//   node tools/selftest_render_scale.js
//
// What it pins:
//   * client.renderScale        (default 0.7) -> the RIG renderers only;
//   * client.villagerRenderScale (default 1.0) -> the villager family only, used absolutely;
//   * the two are independent (changing one must not move the other's renderers);
//   * shadow radius and culling padding follow the entity's OWN key;
//   * the command reaches both, and client state prints both with which family each governs.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const configRaw = read('Config.java');
const config = strip(configRaw);
const villagerRenderer = strip(read('client/GunnerVillagerRenderer.java'));
const scavRenderer = strip(read('client/ScavRenderer.java'));
const pillagerGeo = strip(read('client/GunnerPillagerGeoRenderer.java'));
const pillagerVanilla = strip(read('client/GunnerPillagerRenderer.java'));
const villagerEntity = strip(read('entity/GunnerVillagerEntity.java'));
const scavEntity = strip(read('entity/ScavEntity.java'));
const commands = strip(read('command/ClientCommands.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. the two keys and their defaults');
check(/DEFAULT_RENDER_SCALE = 0\.77D/.test(config),
  'client.renderScale defaults to 0.77 = the release baseline the user calibrated (0.7 rig baseline x 1.1)');
check(/DEFAULT_VILLAGER_RENDER_SCALE = 1\.0D/.test(config),
  'client.villagerRenderScale defaults to 1.0 = the vanilla villager size (the mesh is authored at 1.0)');
check(/defineInRange\("renderScale", DEFAULT_RENDER_SCALE/.test(config)
  && /defineInRange\("villagerRenderScale", DEFAULT_VILLAGER_RENDER_SCALE/.test(config),
  'both are declared as their own config keys');
check(/public static float renderScale\(\)/.test(config)
  && /public static float villagerRenderScale\(\)/.test(config),
  'both have a NaN-guarded, clamped accessor read per render');
check(/cullingBoxPaddingForScale\(\)/.test(config) && /cullingBoxPaddingForVillagerScale\(\)/.test(config),
  'and both have their own culling-padding helper');

console.log('');
console.log('2. each renderer reads its own key');
check(/Config\.villagerRenderScale\(\)/.test(villagerRenderer),
  'the villager renderer reads villagerRenderScale');
check(!/Config\.renderScale\(\)/.test(villagerRenderer),
  'and NOT renderScale - that is the bug this gate was written for (the villager used renderScale/0.7)');
check(!/DEFAULT_RENDER_SCALE/.test(villagerRenderer),
  'nor the rig baseline constant, so it cannot silently become relative again');
check(/this\.shadowRadius = 0\.5F \* villagerScale/.test(villagerRenderer)
  && /poseStack\.scale\(villagerScale, villagerScale, villagerScale\)/.test(villagerRenderer),
  'its shadow radius and its pose scale both come from that one value');
check(/Config\.renderScale\(\)/.test(scavRenderer) && !/villagerRenderScale/.test(scavRenderer),
  'the scav renderer still reads renderScale (the scav keeps its 1.1x size)');
check(/Config\.renderScale\(\)/.test(pillagerGeo) && !/villagerRenderScale/.test(pillagerGeo),
  'so does the gecko pillager renderer (the rig path)');

console.log('');
console.log('3. the vanilla illager path (the pillager family without gecko)');
// Reported, not changed: the parent asked which path the pillagers take. With the shipped default
// useGeckoModel = false the pillagers render with the VANILLA illager model, and that renderer has no
// scale() override at all - so they are NOT affected by renderScale unless gecko mode is switched on.
check(!/protected void scale\(/.test(pillagerVanilla) && !/renderScale|villagerRenderScale/.test(pillagerVanilla),
  'GunnerPillagerRenderer (vanilla illager model) does not scale at all',
  'so with useGeckoModel = false the pillagers are vanilla-sized; the RIG path (gecko) is the one that follows renderScale');
check(/\.define\("useGeckoModel", false\)/.test(config),
  'and useGeckoModel still ships false, so that is the default path');

console.log('');
console.log('4. the linked geometry follows the entity\'s own key');
check(/cullingBoxPaddingForVillagerScale\(\)/.test(villagerEntity),
  'the villager culling box follows the villager scale');
check(!/cullingBoxPaddingForScale\(\)/.test(villagerEntity),
  'and not the rig one (a villager scaled with its own key would be culled too early otherwise)');
check(/cullingBoxPaddingForScale\(\)/.test(scavEntity),
  'the scav culling box still follows the rig scale');

console.log('');
console.log('5. the command reaches both, and says which is which');
check(/parts\[0\]\.equalsIgnoreCase\("villager"\)/.test(commands),
  '/tarkovscav client scale villager <value> selects the villager key');
check(/Config\.VILLAGER_RENDER_SCALE\.set\(/.test(commands) && /Config\.RENDER_SCALE\.set\(/.test(commands),
  'both keys are writable from it');
check(/client\.renderScale = " \+ trim\(Config\.renderScale\(\)\)/.test(commands)
  && /client\.villagerRenderScale = " \+ trim\(Config\.villagerRenderScale\(\)\)/.test(commands),
  'client state prints both values');
check(/RIG: scav \+ gecko pillager/.test(commands) && /VILLAGER family: gunner\/sniper\/usec\/elite villager/
  .test(commands),
  'and labels which family each key governs');
check(/client scale \[villager\] <value>/.test(commands) || /client scale villager/.test(commands),
  'the usage string documents both forms');

console.log('');
console.log('6. the README records the division of labour');
check(readme.includes('villagerRenderScale') && readme.includes('renderScale'),
  'README documents both keys');
check(/\| `villagerRenderScale` \| `1\.0` \|/.test(readme),
  'with the villager default 1.0 = vanilla size');
check(/两个 scale 键/.test(readme) && /client scale villager/.test(readme),
  'and a section stating which key governs which family, plus the live-tuning form');

console.log('');
if (failures > 0) {
  console.log(`${failures} render-scale check(s) FAILED`);
  process.exit(1);
}
console.log('the two model sizes are separate and each renderer reads its own key');
