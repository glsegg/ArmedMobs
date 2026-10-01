// The gunner villager: a friendly gun unit built out of vanilla villager parts plus the shared gun AI.
//
//   node tools/selftest_gunner_villager.js
//
// The whole design of this mob is a set of "reuse" claims, and every one of them is a claim about code
// that is *not* here - no new model, no new texture, no new sound, no forked AI. That is exactly the
// kind of statement that rots silently (somebody adds an override, or a new asset, and the mob quietly
// stops being a villager), so it is checked structurally:
//
//   1. it is a Villager and a GunUser, and it does not carry its own model/animation assets;
//   2. the client side reuses the vanilla model class, the vanilla texture path and the vanilla
//      profession layer, and it can only pose the arms through ArmedModel (VillagerModel has no arm
//      parts and does not implement it - both checked against the real class files);
//   3. the sounds are inherited, not re-declared;
//   4. the Brain is parked while fighting (villagers are a Brain mob and the Brain writes navigation
//      without consulting goal flags);
//   5. the hostility table is exactly: AbstractIllager + whoever hits it + the practice dummy, and
//      never players/villagers/iron golems;
//   6. spawn gate + config + lang + spawn egg + biome modifier are all wired.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawnSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const skip = (label, why) => console.log(`  SKIP  ${label}  (${why})`);

const entity = strip(read('entity/GunnerVillagerEntity.java'));
const model = strip(read('client/GunnerVillagerModel.java'));
const renderer = strip(read('client/GunnerVillagerRenderer.java'));
const handLayer = strip(read('client/TaczItemInHandLayer.java'));
const entities = strip(read('registry/ModEntities.java'));
const items = strip(read('registry/ModItems.java'));
const tabs = strip(read('registry/ModCreativeTabs.java'));
const gate = strip(read('world/CitySpawnEvents.java'));
const config = strip(read('Config.java'));
const configRaw = read('Config.java');
const setup = strip(read('client/ClientSetup.java'));
const commands = strip(read('command/ModCommands.java'));
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. a vanilla villager with the shared gun AI');
check(/class GunnerVillagerEntity extends Villager implements GunUser/.test(entity),
  'GunnerVillagerEntity extends Villager and implements GunUser',
  'the vanilla model data + sounds, and the shared brain, both come from those two');
check(entity.includes('public GunBrain gunBrain()'), 'it hands out a GunBrain');
check(!/extends Villager implements.*GeoEntity/.test(entity) && !entity.includes('AnimatableInstanceCache'),
  'it is not a GeckoLib entity', 'no rig, no controllers - the render is the vanilla model');
check(!/getAmbientSound|getHurtSound/.test(entity),
  'it does not override the ambient or hurt sounds', 'Villager already answers with SoundEvents.VILLAGER_*');
// getDeathSound IS overridden - but only to get out of the way of the voice module's death cry
// (README 5l): it must return the vanilla sound whenever voice.death is off.
check(/getDeathSound\(\)\s*\{\s*return Config\.VOICE_ENABLED\.get\(\) && Config\.VOICE_DEATH\.get\(\)\s*\?\s*null :/.test(entity),
  'getDeathSound is silenced only while the voice module replaces it',
  'so with voice.death=false the vanilla villager death sound is back');

console.log('');
console.log('2. the client side reuses the vanilla model, texture and profession layer');
check(/class GunnerVillagerModel extends VillagerModel<GunnerVillagerEntity> implements ArmedModel/.test(model),
  'the model subclasses VillagerModel and implements ArmedModel');
check(model.includes('root.getChild("arms")'), 'it poses the vanilla mesh\'s "arms" part', 'the one the vanilla setupAnim never touches');
check(/super\.setupAnim\(/.test(model), 'the vanilla setupAnim still runs first', 'head and legs stay vanilla');
check(/ArmPose\.forState\(entity\.gunAiState\(\)\)/.test(model),
  'the arm angle comes from the synced gun state through ArmPose',
  'the same single source of truth the rig uses');
check(model.includes('implements ArmedModel, GunGripModel') && model.includes('translateToGunGrip(HumanoidArm'),
  'the villager exposes a separate physical grip socket for TaCZ guns');
check(handLayer.includes('grip.translateToGunGrip(arm, poseStack)')
  && handLayer.includes('grip.applyGunGripTransform(poseStack)'),
  'the held-gun layer uses the socket and applies scale after the hand orientation');
check(/Config\.gunnerVillagerGunRotation\(\)/.test(model) && /Config\.gunnerVillagerGunOffset\(\)/.test(model)
    && /Config\.gunnerVillagerGunScale\(\)/.test(model),
  'rotation, offset and scale remain live config controls');
const defaultList = name => JSON.parse('[' + new RegExp(name + ' = List\\.of\\(([^)]*)\\)').exec(config)[1] + ']');
check(defaultList('DEFAULT_GUNNER_VILLAGER_GUN_OFFSET').every(value => Number(value) === 0),
  'default correction offset is zero: the grip starts on the actual hand socket');
check(/extends MobRenderer<GunnerVillagerEntity, GunnerVillagerModel>/.test(renderer), 'the renderer is a vanilla MobRenderer');
check(renderer.includes('ModelLayers.VILLAGER'), 'it bakes ModelLayers.VILLAGER', 'the vanilla mesh, not a copy');
check(renderer.includes('"textures/entity/villager/villager.png"'), 'it uses the vanilla villager texture');
check(/new VillagerProfessionLayer<>\(this, context\.getResourceManager\(\), "villager"\)/.test(renderer),
  'it adds the vanilla profession layer with vanilla\'s own path prefix',
  'biome type + profession + level skins, no assets shipped');
check(renderer.includes('new HeldGunLayer(this, context.getItemInHandRenderer())')
  && /class HeldGunLayer[\s\S]{0,300}?extends TaczItemInHandLayer/.test(renderer),
  'it adds the held-item layer so the gun is visible',
  'our HeldGunLayer subclass - the vanilla layer plus the hideGunWhenIdle switch');
check(!/TarkovScav\.id\("/.test(model + renderer), 'no new asset path is referenced from the client code',
  'a TarkovScav.id("...png"/"...json") here would mean a shipped asset');
// The registration now goes through ClientSetup's family helper (one call site for every villager-bodied
// type), so the assertion is about the invariant - this entity type is registered, with this renderer class -
// and not about a literal call shape. The full "every entity has a renderer" rule lives in
// tools/selftest_entity_registry.js.
check(/villagerRenderers\(event, new RegistryObject\[\] \{[^}]*ModEntities\.GUNNER_VILLAGER[^}]*ModEntities\.SNIPER_VILLAGER[^}]*\}\)/.test(setup)
  && /event\.registerEntityRenderer\(entityType, GunnerVillagerRenderer::new\)/.test(setup),
  'ClientSetup registers that renderer');

console.log('');
console.log('3. the Brain is parked while fighting');
// The parked-guard started as "no target"; the command system added a second reason to park (the unit is
// walking to a mark), so the assertion now pins BOTH halves: the target condition must still be there and
// must still be first, and the order condition may only be an AND on the same guard - never a replacement.
check(/customServerAiStep\(\)\s*\{\s*if \(this\.getTarget\(\) == null/.test(entity),
  'customServerAiStep only runs the villager brain when there is no target',
  'the Brain writes walk targets without consulting goal flags');
check(/customServerAiStep\(\)\s*\{\s*if \(this\.getTarget\(\) == null && !com\.gfl\.tarkovscav\.command\.AdvanceOrder\.has\(this\)\) \{\s*super\.customServerAiStep\(\);/
  .test(entity),
  'and the same guard covers an active advance order (the Brain would overwrite the ordered path)',
  'arrival/expiry/the mark disappearing clears the order and the brain comes back');
check(!/customServerAiStep\(\)\s*\{\s*if \(!com\.gfl\.tarkovscav\.command\.AdvanceOrder\.has\(this\)\)/.test(entity),
  'the order condition did not REPLACE the target condition', 'a fighting villager must still park its brain');
check(!/makeBrain|brainProvider/.test(entity), 'it does not replace the villager brain', 'the vanilla brain stays for idle life');

console.log('');
console.log('4. who it fights');
check(/targetSelector\.addGoal\(1, new HurtByTargetGoal\(this\)\)/.test(entity),
  'it retaliates against whoever hurts it (players included)');
check(/NearestAttackableTargetGoal<>\(this, AbstractIllager\.class, true\)/.test(entity),
  'it targets AbstractIllager', 'covers vanilla pillager/vindicator/illusioner AND our gunner pillager');
check(/candidate\.getTags\(\)\.contains\(ScavEntity\.DUMMY_TAG\)/.test(entity),
  'it targets the practice dummy', 'so /tarkovscav test fight|watch|stall work on it');
check(!/NearestAttackableTargetGoal<>\(this, (Player|Villager|IronGolem)/.test(entity),
  'it never targets players, villagers or iron golems', 'friendly means not on the target list');
check(/addGoal\(1, new com\.gfl\.tarkovscav\.gun\.GunAttackGoal\(this\)\)/.test(entity)
    && /addGoal\(2, new com\.gfl\.tarkovscav\.gun\.NoGunMeleeGoal\(this, 1\.1D, false\)\)/.test(entity),
  'gun goal at 1, the mutually-exclusive melee fallback at 2', 'same ordering as the other two gun mobs');
check(/public InteractionResult mobInteract\(Player player, InteractionHand hand\)\s*\{\s*return InteractionResult\.PASS;/.test(entity),
  'right-click does not open a trade screen', 'it is a combat unit, not a merchant');

console.log('');
console.log('4b. the live tuner command (/tarkovscav client villagerpose)');
const clientCommands = strip(read('command/ClientCommands.java'));
check(/Commands\.literal\("villagerpose"\)/.test(clientCommands), 'the command is registered');
for (const axis of ['pitch', 'yaw', 'roll', 'x', 'y', 'z', 'scale']) {
  check(new RegExp('case "' + axis + '"').test(clientCommands), 'the raw key ' + axis + '= is accepted');
}
for (const axis of ['forward', 'back', 'left', 'right', 'up', 'down']) {
  check(new RegExp('case "' + axis + '"').test(clientCommands), 'the semantic axis ' + axis + '= is accepted');
}
check(/case "forward" -> offset\[2\] -= /.test(clientCommands) && /case "back" -> offset\[2\] \+= /.test(clientCommands),
  'forward/back map to -Z/+Z', 'the same convention as gunpose and gunMountRifleOffset');
check(/case "right" -> offset\[0\] \+= /.test(clientCommands) && /case "up" -> offset\[1\] -= /.test(clientCommands),
  'right= is +X and up= is -Y', 'the character faces -Z, so right is +X and up is -Y');
check(/anchor=arms/.test(clientCommands) && /anchor=body/.test(clientCommands), 'both anchors are selectable');
check(/part\.equalsIgnoreCase\("reset"\)/.test(clientCommands), 'reset restores the shipped baseline');
// The reset path must not carry its own copy of the old number: a second hard-coded -90 there would have
// quietly restored the sky-pointing gun on /tarkovscav client villagerpose reset.
check(/reset"\)\)\s*\{[\s\S]{0,300}?rotation = Config\.triple\(Config\.DEFAULT_GUNNER_VILLAGER_GUN_ROTATION/
    .test(clientCommands)
    && !/rotation = new float\[\]\{-90\.0F/.test(clientCommands)
    && !/GUNNER_VILLAGER_GUN_ROTATION\.set\(java\.util\.List\.of\("-90"/.test(clientCommands),
  'reset reads the shipped default from Config.DEFAULT_GUNNER_VILLAGER_GUN_ROTATION, not a second -90');
check(/Config\.SPEC\.save\(\)/.test(clientCommands), 'the command persists to config/tarkovscav-common.toml');
check(/gunnerVillagerGunRotation = /.test(clientCommands), 'it prints a paste-ready toml snippet');
check(/villager gun rot=/.test(clientCommands), 'the log line reports the villager gun transform');
// /tarkovscav client state must show what villagerpose wrote - otherwise "did my pitch take?" is a
// question only the toml can answer.
check(/villagerGun rot=\[/.test(clientCommands)
  && /gun tilt = armPitch \+ rot\.x \+ thisPoseDeltaX - 90/.test(clientCommands)
  && /idleRot=\[/.test(clientCommands),
  'client state prints the villager gun rot/offset/scale/anchor AND the arm pitches it adds to');
check(readme.includes('client villagerpose'), 'README documents the command');
check(/Do the arm angle first/i.test(readme), 'README carries the arm-angle-first timing warning',
  'the gun hangs off the arms block, so the arm angle moves it');
check(/forward=n.*z -= n/s.test(readme), 'README documents the axis meaning');
// All FOUR arm pitches are live-tunable (the user named this: only hold= used to be), so the four-pose
// silhouette no longer needs a toml edit plus a reload.
for (const arm of ['hold', 'aim', 'reload', 'hunker']) {
  check(new RegExp('case "' + arm + '" -> ' + arm + ' = \\(float\\) value;').test(clientCommands),
    `villagerpose accepts ${arm}= (the ${arm} arm pitch)`);
}
check(/float aim = Config\.gunnerVillagerAimArmPitch\(\)/.test(clientCommands)
  && /float reload = Config\.gunnerVillagerReloadArmPitch\(\)/.test(clientCommands)
  && /float hunker = Config\.gunnerVillagerHunkerArmPitch\(\)/.test(clientCommands),
  'and the other three arm pitches are read from the config at the start, like hold');
check(/Config\.GUNNER_VILLAGER_AIM_ARM_PITCH\.set\(\(double\) aim\)/.test(clientCommands)
  && /Config\.GUNNER_VILLAGER_RELOAD_ARM_PITCH\.set\(\(double\) reload\)/.test(clientCommands)
  && /Config\.GUNNER_VILLAGER_HUNKER_ARM_PITCH\.set\(\(double\) hunker\)/.test(clientCommands),
  'and all three are written back to the toml with the rest');
check(/aim = \(float\) Config\.DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH/.test(clientCommands)
  && /reload = \(float\) Config\.DEFAULT_GUNNER_VILLAGER_RELOAD_ARM_PITCH/.test(clientCommands)
  && /hunker = \(float\) Config\.DEFAULT_GUNNER_VILLAGER_HUNKER_ARM_PITCH/.test(clientCommands),
  'reset restores all four arm pitches from the DEFAULT_* constants (no second copy of any number)');
check(/armPitch aim\/hold\/reload\/hunker=/.test(clientCommands)
  && /gunnerVillagerReloadArmPitch = /.test(clientCommands)
  && /gunnerVillagerHunkerArmPitch = /.test(clientCommands),
  'the echo and the "Written to config" list print all four arm pitches, in the same order as client state');

// Test the current arm-space calibration against the baked vanilla crossed-arms angle.
const ARMS_REST = -0.75 * 180 / Math.PI;
const aimOffset = Number(/DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH = (-?[\d.]+)D/.exec(config)[1]);
const baseRotation = defaultList('DEFAULT_GUNNER_VILLAGER_GUN_ROTATION').map(Number);
const neutralAim = ARMS_REST + aimOffset + baseRotation[0] - 90;
check(baseRotation.every(Number.isFinite) && Math.abs(neutralAim + 180) < 0.1,
  'default aim frame points forward within 0.1 degree', neutralAim.toFixed(6) + ' degrees');
check(/DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH = 0\.0D/.test(config),
  'idle arms retain the vanilla baked rest');
const productionPoseCheck = spawnSync(process.execPath, [path.join(__dirname, 'selftest_vanilla_poses.js')],
  { cwd: ROOT, encoding: 'utf8' });
check(productionPoseCheck.status === 0 && /production vanilla pose\/frame\/grip checks/.test(productionPoseCheck.stdout || ''),
  'production model frame, aim direction and invariant grip regression runs',
  (productionPoseCheck.stdout || productionPoseCheck.stderr || productionPoseCheck.error || '').toString().trim());
// The idle fix itself, source level: LOWERED must be handled by its own early return that only RESETS the
// crossed-arms block, and it must no longer be one arm of the pose switch (which is what held the arms up).
const setupAnim = model.slice(model.indexOf('public void setupAnim'), model.indexOf('translateToHand'));
check(/if \(pose == ArmPose\.LOWERED\) \{[\s\S]{0,1600}?this\.arms\.xRot = this\.armsRestXRot \+ hold \* Mth\.DEG_TO_RAD;[\s\S]{0,200}?return;/
  .test(setupAnim),
  'the LOWERED (idle) branch has its own early return and writes the vanilla REST plus the configured offset');
check(/float hold = Config\.gunnerVillagerHoldArmPitch\(\);/.test(setupAnim),
  'and it still reads gunnerVillagerHoldArmPitch, now as an offset: 0 = the vanilla arms, -20 = 20° above them');
check(/private final float armsRestXRot;/.test(model) && /this\.armsRestXRot = this\.arms\.xRot;/.test(model),
  'the model captures the arms block\'s BAKED rotation when it is constructed (-0.75 rad) - that captured '
  + 'value is what "the normal villager arms" means, and it is measured rather than assumed');
check(!/this\.arms\.xRot = 0\.0F;/.test(model),
  'nothing writes a bare 0 into arms.xRot any more: that was the bug that laid the crossed arms flat into the body');
check(/if \(!holdsGun\(entity\)\) \{[\s\S]{0,400}?this\.arms\.xRot = this\.armsRestXRot;/.test(setupAnim),
  'the no-weapon path restores the same baked rest, so an unarmed gunner villager is exactly vanilla too');
check(!/case LOWERED -> Config\.gunnerVillagerHoldArmPitch\(\)/.test(setupAnim),
  'the pose switch no longer drives LOWERED (that is what kept the arms raised)');
check(/case RAISED -> Config\.gunnerVillagerAimArmPitch\(\)/.test(setupAnim)
    && /case RELOADING -> Config\.gunnerVillagerReloadArmPitch\(\)/.test(setupAnim)
    && /case HUNKERED -> Config\.gunnerVillagerHunkerArmPitch\(\)/.test(setupAnim),
  'while aim, reload and hunker still come from their own keys');
// The optional "hide the gun while idle" switch (README 5j).
check(/HIDE_GUN_WHEN_IDLE\s*=[\s\S]{0,1200}?define\("hideGunWhenIdle", false\)/.test(config),
  'client.hideGunWhenIdle exists and ships false (the gun keeps being drawn on the crossed arms)');
check(readme.includes('hideGunWhenIdle'), 'and README documents it');
check(/Config\.hideGunWhenIdle\(\) && RigSupport\.armPose\(entity\) == ArmPose\.LOWERED/.test(renderer),
  'the renderer skips the held-gun layer only while the state is LOWERED (it comes back when aiming)');
check(/class HeldGunLayer[\s\S]{0,400}?extends TaczItemInHandLayer/.test(renderer),
  'through the compatibility layer, which keeps ordinary items on the vanilla path');
// The idle-ONLY gun offset (README 5j, the 2026 "the gun points up while idle" report). It exists because
// the idle gun rides the arms block, so it needs its own correction there - and nowhere else.
function fallbackMatchesDefault(source, key) {
  const match = new RegExp('triple\\((?:Config\\.)?' + key
    + ',\\s*(-?[\\d.]+)F,\\s*(-?[\\d.]+)F,\\s*(-?[\\d.]+)F\\)').exec(source);
  const expected = defaultList(key).map(Number);
  return match && expected.every((value, index) => value === Number(match[index + 1]));
}
check(fallbackMatchesDefault(config, 'DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION')
    && fallbackMatchesDefault(clientCommands, 'DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION'),
  'both idle parse fallbacks agree with the shipped calibration');
check(/DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH = 0\.0D/.test(config),
  'idle retains the villager crossed-arm rest; the weapon correction controls its lowered muzzle');
check(/case LOWERED -> Config\.gunnerVillagerIdleGunRotation\(\);/.test(model),
  'the idle delta is still the LOWERED pose\'s own correction');
check(/rotation = new float\[\]\{rotation\[0\] \+ delta\[0\], rotation\[1\] \+ delta\[1\], rotation\[2\] \+ delta\[2\]\}/
  .test(model),
  'and it is ADDED to the base rotation, so aim/reload/hunker keep their calibrated numbers untouched');
check(/private ArmPose currentPose/.test(model) && /this\.currentPose = pose;/.test(model),
  'the pose is remembered in setupAnim, because translateToHand is handed no entity');
check(/idlepitch/.test(clientCommands) && /idleyaw/.test(clientCommands) && /idleroll/.test(clientCommands),
  'villagerpose exposes idlePitch/idleYaw/idleRoll for live tuning');
check(/GUNNER_VILLAGER_IDLE_GUN_ROTATION\.set\(/.test(clientCommands)
  && /DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION/.test(clientCommands),
  'and writes it to the toml, with reset reading the shipped default');
check(/gunnerVillagerIdleGunRotation/.test(readme)
  && /闲置/.test(readme) && /gunnerVillagerGunRotation/.test(readme),
  'README documents the key AND which of the two gun-rotation keys is for which pose');

// 4c. One gun-rotation DELTA per pose (README 5j, the user's "不同状态下枪的旋转角度"). The base rotation is
// the RAISED pose itself; the other three states each add their own calibrated triple.
console.log('');
console.log('4c. one gun-rotation delta per pose (RAISED = the base itself)');
for (const pose of ['IDLE', 'RELOAD', 'HUNKER']) {
  const delta = defaultList('DEFAULT_GUNNER_VILLAGER_' + pose + '_GUN_ROTATION').map(Number);
  check(delta.length === 3 && delta.every(Number.isFinite), `${pose} has a finite calibrated rotation triple`);
}
check(/defineListAllowEmpty\(List\.of\("gunnerVillagerReloadGunRotation"\)/.test(config)
  && /defineListAllowEmpty\(List\.of\("gunnerVillagerHunkerGunRotation"\)/.test(config),
  'both are declared as list keys');
check(/public static float\[\] gunnerVillagerReloadGunRotation\(\)/.test(config)
  && /public static float\[\] gunnerVillagerHunkerGunRotation\(\)/.test(config)
  && fallbackMatchesDefault(config, 'DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION')
  && fallbackMatchesDefault(config, 'DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION'),
  'each has its accessor, with the shipped default as the parse fallback');
check(/float\[\] delta = switch \(this\.currentPose\) \{[\s\S]{0,600}?case LOWERED -> Config\.gunnerVillagerIdleGunRotation\(\);/
  .test(model)
  && /case RELOADING -> Config\.gunnerVillagerReloadGunRotation\(\);/.test(model)
  && /case HUNKERED -> Config\.gunnerVillagerHunkerGunRotation\(\);/.test(model)
  && /case RAISED -> null;/.test(model),
  'translateToHand picks the delta BY POSE, and RAISED has none (the base rotation IS that pose)');
check(!/if \(this\.currentPose == ArmPose\.LOWERED\)/.test(model),
  'the old idle-only if is gone, so RELOADING/HUNKERED are no longer glued to the base');
check(/if \(delta != null && \(delta\[0\] != 0\.0F \|\| delta\[1\] != 0\.0F \|\| delta\[2\] != 0\.0F\)\)/
  .test(model)
  && /rotation = new float\[\]\{rotation\[0\] \+ delta\[0\], rotation\[1\] \+ delta\[1\], rotation\[2\] \+ delta\[2\]\}/
    .test(model),
  'only ADDITION happens (a zero delta is skipped entirely), so nothing else about the transform moves');
// Pose composition and repeated frames are executed by the production model regression above.
const commandAxes = ['reloadpitch', 'reloadyaw', 'reloadroll', 'hunkerpitch', 'hunkeryaw', 'hunkerroll'];
for (const axis of commandAxes) {
  check(new RegExp('case "' + axis + '" ->').test(clientCommands),
    `villagerpose accepts ${axis}= for live tuning`);
}
check(/reloadRotation = Config\.triple\(Config\.DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION/.test(clientCommands)
  && /hunkerRotation = Config\.triple\(Config\.DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION/.test(clientCommands)
  && fallbackMatchesDefault(clientCommands, 'DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION')
  && fallbackMatchesDefault(clientCommands, 'DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION'),
  'reset restores both new deltas from the DEFAULT_* constants');
check(/gunnerVillagerReloadGunRotation = /.test(clientCommands)
  && /gunnerVillagerHunkerGunRotation = /.test(clientCommands)
  && /reloadRot=\[/.test(clientCommands) && /hunkerRot=\[/.test(clientCommands),
  'the echo and the "Written to config" list print all four rotation groups');
check(/reloadRot=\[/.test(clientCommands) && /hunkerRot=\[/.test(clientCommands)
  && /rotation deltas/.test(clientCommands) && /offsetDelta idle=\[/.test(clientCommands),
  'and client state prints them too, next to the arm pitches');
// B: the weapon POSITION deltas, one triple per pose (README 5j). Same arrangement as the rotation deltas.
for (const pose of ['Idle', 'Reload', 'Hunker']) {
  check(new RegExp('DEFAULT_GUNNER_VILLAGER_' + pose.toUpperCase() + '_GUN_OFFSET = List\\.of\\("0", "0", "0"\\)')
    .test(config), pose + ' gun offset ships [0,0,0] (frame-for-frame what shipped before)');
  check(config.includes('"gunnerVillager' + pose + 'GunOffset"'),
    'and the ' + pose + ' gun offset key is declared');
  check(new RegExp('public static float\\[\\] gunnerVillager' + pose + 'GunOffset\\(\\)').test(config),
    '  with an accessor');
}
check(/case LOWERED -> Config\.gunnerVillagerIdleGunOffset\(\);/.test(model)
  && /case RELOADING -> Config\.gunnerVillagerReloadGunOffset\(\);/.test(model)
  && /case HUNKERED -> Config\.gunnerVillagerHunkerGunOffset\(\);/.test(model)
  && /case RAISED -> null;/.test(model),
  'translateToHand picks the POSITION delta by pose too (RAISED carries the base position)');
check(/offset\[0\] \+ offsetDelta\[0\]/.test(model) && /offset\[1\] \+ offsetDelta\[1\]/.test(model)
  && /offset\[2\] \+ offsetDelta\[2\]/.test(model),
  'and the deltas are ADDED to the base offset, so the aiming position is never moved by them');
for (const axis of ['idlex', 'idley', 'idlez', 'reloadx', 'reloady', 'reloadz', 'hunkerx', 'hunkery',
  'hunkerz']) {
  check(new RegExp('case "' + axis + '" ->').test(clientCommands), 'villagerpose accepts ' + axis + '=');
}
check(/GUNNER_VILLAGER_IDLE_GUN_OFFSET\.set\(/.test(clientCommands)
  && /DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_OFFSET/.test(clientCommands),
  'reset restores all three position deltas from the DEFAULT_* constants');
check(/gunnerVillagerReloadGunRotation/.test(readme) && /gunnerVillagerHunkerGunRotation/.test(readme)
  && /不同状态下枪的旋转角度/.test(readme),
  'README documents both new keys and the request they come from');
console.log('');
console.log('5. spawning, gate and wiring');
check(/GUNNER_VILLAGER\s*=\s*ENTITY_TYPES\.register\("gunner_villager"/.test(entities), 'the entity type is registered');
check(/event\.put\(GUNNER_VILLAGER\.get\(\), Villager\.createAttributes\(\)\.build\(\)\)/.test(entities),
  'it uses the vanilla villager attribute set', 'the tier only overwrites health and armour');
check(/SpawnPlacements\.register\(GUNNER_VILLAGER\.get\(\)/.test(entities), 'its spawn placement is registered');
check(/mob instanceof GunnerVillagerEntity\)\s*\{\s*return Config\.GUNNER_VILLAGER_CITY_ONLY\.get\(\);/.test(gate),
  'the city gate knows the new mob');
check(/GUNNER_VILLAGER_NATURAL_SPAWN\.get\(\)/.test(gate) && gate.includes('event.setResult(Event.Result.DENY)'),
  'the natural-spawn switch is enforced in the gate and logged', 'spawn.gunnerVillagerNaturalSpawn');
check(/gunner_villager_spawn_egg/.test(items) && /ModItems\.GUNNER_VILLAGER_SPAWN_EGG\.get\(\)/.test(tabs),
  'spawn egg registered and put in the creative tab');
check(fs.existsSync(path.join(ASSETS, 'models', 'item', 'gunner_villager_spawn_egg.json')),
  'the spawn egg has an item model');
for (const lang of ['en_us', 'zh_cn']) {
  const text = fs.readFileSync(path.join(ASSETS, 'lang', `${lang}.json`), 'utf8');
  check(text.includes('"entity.tarkovscav.gunner_villager"') && text.includes('"item.tarkovscav.gunner_villager_spawn_egg"'),
    `${lang}.json has the entity and egg names`);
}
const biomeModifier = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav', 'forge', 'biome_modifier', 'add_scavs.json'), 'utf8'));
const spawner = biomeModifier.spawners.find((entry) => entry.type === 'tarkovscav:gunner_villager');
const scavSpawner = biomeModifier.spawners.find((entry) => entry.type === 'tarkovscav:scav');
check(!!spawner, 'the biome modifier spawns it naturally');
check(spawner && scavSpawner && spawner.weight < scavSpawner.weight,
  'its natural weight is lower than the scav\'s', spawner ? `weight ${spawner.weight} vs ${scavSpawner.weight}` : '');
check(/gunner_villager/.test(commands) && commands.includes('gunner_villager'), '/tarkovscav spawn accepts it');

console.log('');
console.log('6. the config keys, with the documented defaults, in README');
const keys = [
  ['gunnerVillagerCityOnly', /\.define\("gunnerVillagerCityOnly", true\)/],
  ['gunnerVillagerNaturalSpawn', /\.define\("gunnerVillagerNaturalSpawn", true\)/],
  ['gunnerVillagerAimArmPitch', /defineInRange\("gunnerVillagerAimArmPitch", DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH/],
  ['gunnerVillagerHoldArmPitch', /defineInRange\("gunnerVillagerHoldArmPitch", DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH/],
  ['gunnerVillagerGunOffset', /defineListAllowEmpty\(List\.of\("gunnerVillagerGunOffset"\)/],
  ['hideGunWhenIdle', /\.define\("hideGunWhenIdle", false\)/],
];
for (const [key, re] of keys) {
  check(re.test(config), `Config defines ${key}`);
  check(readme.includes(key), `README documents ${key}`);
}
check(/DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH = 0\.0D/.test(config),
  'the hold default means the baked vanilla rest, not an absolute zero arm angle');
check(/this\.armsRestXRot = this\.arms\.xRot/.test(model),
  'the model reads the real baked arm rotation rather than replacing it with a guessed constant');
check(readme.includes('5j.'), 'README has the section that explains the mob');

// The three vanilla facts the design rests on, read out of the real class files when the mapped jar is
// available (a class only names a type/field it actually uses, so absence is meaningful here).
function zipEntry(jar, name) {
  const eocd = jar.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = jar.readUInt16LE(eocd + 10);
  let at = jar.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (jar.readUInt32LE(at) !== 0x02014b50) return null;
    const method = jar.readUInt16LE(at + 10);
    const compressed = jar.readUInt32LE(at + 20);
    const nameLen = jar.readUInt16LE(at + 28);
    const extraLen = jar.readUInt16LE(at + 30);
    const commentLen = jar.readUInt16LE(at + 32);
    const localAt = jar.readUInt32LE(at + 42);
    const entryName = jar.toString('utf8', at + 46, at + 46 + nameLen);
    if (entryName === name) {
      const localNameLen = jar.readUInt16LE(localAt + 26);
      const localExtraLen = jar.readUInt16LE(localAt + 28);
      const dataAt = localAt + 30 + localNameLen + localExtraLen;
      const raw = jar.slice(dataAt, dataAt + compressed);
      return method === 0 ? raw : zlib.inflateRawSync(raw);
    }
    at += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}
const mappedJar = require('./resolve_minecraft_jar')(ROOT);
if (fs.existsSync(mappedJar)) {
  const jar = fs.readFileSync(mappedJar);
  const has = (entry, needle) => {
    const body = zipEntry(jar, entry);
    return body ? body.includes(Buffer.from(needle)) : null;
  };
  const villagerModel = 'net/minecraft/client/model/VillagerModel.class';
  check(has(villagerModel, 'arms') === true, 'VillagerModel really has an "arms" part', 'the part the pose rotates');
  check(has(villagerModel, 'rightArm') === false && has(villagerModel, 'leftArm') === false,
    'VillagerModel really has no separate arm parts', 'so there is nothing illager-style to pose');
  check(has(villagerModel, 'translateToHand') === false,
    'VillagerModel really does not implement ArmedModel', 'hence the subclass, and hence translateToHand');
  check(has('net/minecraft/client/renderer/entity/layers/ItemInHandLayer.class', 'ArmedModel') === true,
    'ItemInHandLayer really requires ArmedModel', 'the interface we add');
} else {
  skip('the vanilla class-file facts', 'mapped jar not present');
}

console.log('');
if (failures > 0) {
  console.log(`${failures} gunner-villager check(s) FAILED`);
  process.exit(1);
}
console.log('gunner villager invariants all hold');
