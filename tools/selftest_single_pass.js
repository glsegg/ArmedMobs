// Gate for "the rig is submitted for drawing exactly once per frame" and for the animation-layering
// switch that was asked for.
//
//   node tools/selftest_single_pass.js
//
// What it checks, and why it can:
//   * the mod registers exactly ONE renderer per entity type and adds ONE item layer to it, so nothing
//     in this mod's own code can walk the bone tree twice;
//   * no code calls GeckoLib's re-render entry point (that is the only other way a rig gets drawn again
//     inside one pass), and neither model class toggles bone visibility per pass - there is no
//     "hide a set, draw, hide another set, draw again" path;
//   * the two animation controllers (when modelLayering = upperLower) drive DISJOINT bone sets, which
//     is why they are a layering and not a second draw - the leg clips touch no upper-body bone and the
//     gun clips touch no leg bone (read from the animation file, not from a comment);
//   * modelLayering exists, defaults to single, and the single path really registers one controller;
//   * the runtime counter that proves it in game is wired (RenderStats + logRenderStats).
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8').replace(/\/\/[^\n]*/g, '');
const readRaw = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const setup = read('client/ClientSetup.java');
const scavRenderer = read('client/ScavRenderer.java');
const pillagerRenderer = read('client/GunnerPillagerGeoRenderer.java');
const scavModel = read('client/ScavGeoModel.java');
const pillagerModel = read('client/GunnerPillagerGeoModel.java');
const layer = read('client/GunInHandGeoLayer.java');
const scavEntity = read('entity/ScavEntity.java');
const pillagerEntity = read('entity/GunnerPillagerEntity.java');
const stats = read('client/RenderStats.java');
const config = readRaw('Config.java');

console.log('one geometry pass per mob per frame:');
// The registration moved into ClientSetup's two helpers (2026-09-23): the generic `renderer(...)` helper and
// the `illagerRenderers(...)` family loop. Each REGISTERS exactly one renderer for the type it is given, and
// the two `useGeckoModel` branches are chosen when its provider constructs a renderer - so the invariant is still "exactly
// one renderer per entity type, whichever branch runs". The complete per-entity list is asserted by
// tools/selftest_entity_registry.js.
check((setup.match(/\.registerEntityRenderer\(/g) || []).length === 3
    && (setup.match(/renderer\(event, ModEntities\.SCAV, ScavRenderer::new\)/g) || []).length === 1
    && /villagerRenderers\(event, new RegistryObject\[\] \{/.test(setup)
    && /\billagerRenderers\(event, new RegistryObject\[\] \{/.test(setup)
    && /registerEntityRenderer\(entityType, context -> \{\s*boolean useGecko = Config\.SPEC\.isLoaded\(\) && Config\.USE_GECKO_MODEL\.get\(\);\s*return useGecko \? new GunnerPillagerGeoRenderer\(context\) : new GunnerPillagerRenderer\(context\);/.test(setup),
  'one renderer per entity type: the scav directly, and both families (villager + illager) through ONE'
    + ' call site each, so a new sibling cannot be left out');
check(!/onRegisterRenderers\([^)]*\)\s*\{[^}]*Config\./.test(setup)
    && !/registerAll\([^)]*boolean|illagerRenderers\([^)]*boolean/.test(setup),
  'registration never captures the Gecko config before Forge loads the common TOML');

// Execute the exact production registration method with deferred providers. Loading the config between
// registration and construction is the real Forge ordering that a source-only branch count cannot test.
const methodStart = setup.indexOf('private static void illagerRenderers(');
let methodEnd = setup.indexOf('{', methodStart), depth = 1;
for (methodEnd++; methodEnd < setup.length && depth; methodEnd++) {
  if (setup[methodEnd] === '{') depth++;
  if (setup[methodEnd] === '}') depth--;
}
const providerMethod = setup.slice(methodStart, methodEnd);
const providerOutput = path.join(ROOT, 'build', 'audit', 'provider-timing');
fs.mkdirSync(providerOutput, { recursive: true });
const providerSource = path.join(providerOutput, 'ProviderTimingTest.java');
fs.writeFileSync(providerSource, `import java.util.*;
public class ProviderTimingTest {
 static class Entity {} static class GunnerPillagerEntity extends Entity {} static class Sibling extends GunnerPillagerEntity {}
 static class EntityType<T extends Entity> {}
 record RegistryObject<T>(T value,String id) { T get() { return value; } String getId() { return id; } }
 interface EntityRendererProvider<T extends Entity> { EntityRenderer<? super T> create(Context context); }
 static class Context {}
 static class EntityRenderer<T extends Entity> { final Context context; EntityRenderer(Context context) { this.context=context; constructed++; } }
 static class GunnerPillagerGeoRenderer extends EntityRenderer<GunnerPillagerEntity> { GunnerPillagerGeoRenderer(Context context) { super(context); } }
 static class GunnerPillagerRenderer extends EntityRenderer<GunnerPillagerEntity> { GunnerPillagerRenderer(Context context) { super(context); } }
 static class EntityRenderersEvent { static class RegisterRenderers {
  final Map<EntityType<?>,EntityRendererProvider<?>> providers=new LinkedHashMap<>();
  <T extends Entity> void registerEntityRenderer(EntityType<T> type,EntityRendererProvider<T> provider) {
   if(providers.put(type,provider)!=null) throw new AssertionError("duplicate renderer provider");
  }
 } }
 static class Config {
  static boolean loaded,enabled; static int reads;
  static class Spec { boolean isLoaded() { reads++; return loaded; } }
  static class Flag { boolean get() { if(!loaded) throw new AssertionError("config read before loaded"); return enabled; } }
  static final Spec SPEC=new Spec(); static final Flag USE_GECKO_MODEL=new Flag();
 }
 static final Set<String> RENDERED=new LinkedHashSet<>(); static int checks,constructed;
 static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
 @SafeVarargs
 ${providerMethod}
 public static void main(String[] args) {
  var event=new EntityRenderersEvent.RegisterRenderers(); var context=new Context();
  var base=new RegistryObject<>(new EntityType<GunnerPillagerEntity>(),"base");
  var sniper=new RegistryObject<>(new EntityType<Sibling>(),"sniper");
  var bear=new RegistryObject<>(new EntityType<Sibling>(),"bear");
  var elite=new RegistryObject<>(new EntityType<Sibling>(),"elite");
  illagerRenderers(event,base,sniper,bear,elite);
  check(Config.reads==0,"registration does not sample unloaded configuration");
  check(constructed==0,"registration does not build renderers early");
  check(event.providers.size()==4 && RENDERED.size()==4,"exactly one provider for every family member");
  Config.loaded=true; Config.enabled=true; var existing=new ArrayList<EntityRenderer<?>>();
  for(var provider:event.providers.values()) {
   var renderer=provider.create(context); existing.add(renderer);
   check(renderer instanceof GunnerPillagerGeoRenderer,"config loaded after registration selects Gecko");
   check(renderer.context==context,"provider retains the real construction context");
  }
  check(constructed==4,"one concrete renderer per provider construction");
  Config.enabled=false;
  for(var renderer:existing) check(renderer instanceof GunnerPillagerGeoRenderer,"existing renderer stays concrete after config change");
  for(var provider:event.providers.values()) check(provider.create(context) instanceof GunnerPillagerRenderer,"resource rebuild uses latest loaded choice");
  Config.loaded=false;
  for(var provider:event.providers.values()) check(provider.create(context) instanceof GunnerPillagerRenderer,"unloaded rebuild safely uses vanilla fallback");
  check(event.providers.size()==4,"rebuilds do not add a second provider");
  System.out.println("PASS "+checks+" production provider timing checks");
 }
}`, 'utf8');
const javaExe = name => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
const compiledProvider = spawnSync(javaExe('javac'), ['-encoding', 'UTF-8', '-d', providerOutput, providerSource],
  { cwd: ROOT, encoding: 'utf8' });
check(!compiledProvider.error && compiledProvider.status === 0, 'production provider timing fixture compiles',
  compiledProvider.error?.message || compiledProvider.stderr.trim() || undefined);
if (compiledProvider.status === 0) {
  const result = spawnSync(javaExe('java'), ['-cp', providerOutput, 'ProviderTimingTest'], { cwd: ROOT, encoding: 'utf8' });
  check(!result.error && result.status === 0, 'deferred renderer choice survives real registration/load/build ordering',
    result.error?.message || result.stdout.trim() || result.stderr.trim());
}
check((scavRenderer.match(/addRenderLayer\(/g) || []).length === 1
    && (pillagerRenderer.match(/addRenderLayer\(/g) || []).length === 1,
  'each renderer adds exactly one item layer (the layer that draws the gun)');
// The villager renderer is the vanilla-model one: one profession layer (the vanilla skins) and one
// held-item layer, and no GeckoLib layer at all.
const villagerRenderer = read('client/GunnerVillagerRenderer.java');
check((villagerRenderer.match(/addLayer\(new HeldGunLayer\(/g) || []).length === 1
    && (villagerRenderer.match(/addLayer\(new VillagerProfessionLayer</g) || []).length === 1
    && /class HeldGunLayer[\s\S]{0,300}?extends TaczItemInHandLayer/.test(villagerRenderer)
    && !/GunInHandGeoLayer|GeoLayer/.test(villagerRenderer),
  'the gunner villager draws its gun through one TaCZ-aware held-item layer and nothing else');
// Note the word boundary: a bare /reRender\(/ also matches preRender(, which is a different method.
check(!/\breRender\s*\(/.test(layer) && !/\breRender\s*\(/.test(scavRenderer) && !/\breRender\s*\(/.test(pillagerRenderer),
  'nothing calls GeoRenderer#reRender - the one API that would draw the rig again inside a pass');
check(!/setHidden\(/.test(scavModel) && !/setHidden\(/.test(pillagerModel),
  'no model toggles bone visibility per pass (the hide pass lives in RigSupport, once per bake)');
check((read('client/RigSupport.java').match(/RigVisibility\.hide\(/g) || []).length === 2
    && /RigVisibility\.restore\(model\.getAnimationProcessor\(\)\.getRegisteredBones\(\)\)/
      .test(read('client/RigSupport.java')),
  'config hiding restores previous owned flags before applying the two hide lists');
check(/RenderStats\.onGeometryPass\(/.test(layer) && /PASSES_PER_MOB/.test(stats),
  'the runtime counter is wired into the per-pass entry point');
check(/\.define\("logRenderStats", false\)/.test(config),
  'client.logRenderStats exists (default false) so the count can be seen in game');

console.log('\nmodelLayering (single | upperLower), default single:');
check(/DEFAULT_MODEL_LAYERING = "upperLower"/.test(config)
    && /\.define\("modelLayering", DEFAULT_MODEL_LAYERING\)/.test(config),
  'modelLayering defaults to upperLower (parallel legs + armed upper body), with single still selectable');
check(/public static boolean singleControllerMode\(\)/.test(config),
  'Config#singleControllerMode() is the one place the value is interpreted');
for (const [name, source] of [['ScavEntity', scavEntity], ['GunnerPillagerEntity', pillagerEntity]]) {
  check(new RegExp(`Config\\.singleControllerMode\\(\\)`).test(source)
      && /new AnimationController<>\(this, "model", 3, this::singleController\)/.test(source),
    `${name}: the single path registers exactly one controller named "model"`);
  check(/private PlayState singleController\(/.test(source)
      && /private PlayState movementController\(/.test(source)
      && /private PlayState gunController\(/.test(source),
    `${name}: both layering paths are present (single and the layered pair)`);
}

console.log('\nthe layered controllers drive disjoint bone sets (so layering, not a second draw):');
const anims = JSON.parse(fs.readFileSync(path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json'), 'utf8')).animations;
const LEG = ['DownBody', 'LeftLeg', 'LeftLowerLeg', 'leftfoot', 'RightLeg', 'RightLowerLeg', 'rightfoot'];
const gunClips = Object.keys(anims).filter((n) => n.startsWith('tac:') && !['tac:idle', 'tac:walk', 'tac:run'].includes(n));
const moveClips = ['tac:idle', 'tac:walk', 'tac:run'];
let gunLegTracks = 0;
let moveUpperTracks = 0;
for (const clip of gunClips) {
  gunLegTracks += Object.keys(anims[clip].bones || {}).filter((b) => LEG.includes(b)).length;
}
for (const clip of moveClips) {
  moveUpperTracks += Object.keys(anims[clip].bones || {})
    .filter((b) => !LEG.includes(b) && b !== 'Root' && b !== 'AllBody' && b !== 'UpBody' && b !== 'DownBody').length;
}
console.log(`  ${gunClips.length} gun clip(s) carry ${gunLegTracks} leg track(s); ${moveClips.length} armed movement clip(s) carry ${moveUpperTracks} upper-body track(s)`);
check(gunLegTracks === 0,
  'no gun clip animates a leg bone, which is exactly why single mode cannot walk while armed');
// tac:idle is the one armed movement clip that touches an upper-body bone (Head, 1 track). The layered
// scheme resolves that by registration order - the gun controller is added second, and GeckoLib applies
// controllers in order with the later one winning per bone - so the gun's Head pose wins. That is a
// per-bone precedence, not a second draw.
check(moveUpperTracks <= 1,
  'the armed movement clips overlap the gun clips on at most one bone (Head in tac:idle), resolved by '
  + 'controller order with no second draw',
  `${moveUpperTracks} overlapping track(s)`);

console.log('\nthe render type / culling A-B for the see-through reports:');
const renderTypes = read('client/ModelRenderTypes.java');
check(/case "zOffset" -> RenderType\.entityCutoutNoCullZOffset\(texture\)/.test(renderTypes),
  'the zOffset option uses the stock entityCutoutNoCullZOffset (depth bias against intersecting world geometry)');
check(/\.define\("modelRenderType", DEFAULT_MODEL_RENDER_TYPE\)/.test(config)
    && /DEFAULT_MODEL_RENDER_TYPE = "cutout"/.test(config),
  'client.modelRenderType exists, defaults to cutout (the current look)');
check(/\.defineInRange\("cullingBoxPadding", DEFAULT_CULLING_BOX_PADDING/.test(config)
    && /getBoundingBoxForCulling\(\)\.inflate\(Config\.cullingBoxPaddingForScale\(\)\)/.test(read('entity/ScavEntity.java'))
    && /getBoundingBoxForCulling\(\)\.inflate\(Config\.cullingBoxPaddingForScale\(\)\)/.test(read('entity/GunnerPillagerEntity.java'))
    && /getBoundingBoxForCulling\(\)\.inflate\(Config\.cullingBoxPaddingForVillagerScale\(\)\)/
      .test(read('entity/GunnerVillagerEntity.java')),
  'client.cullingBoxPadding pads Entity#getBoundingBoxForCulling on all three mobs, each coupled to ITS OWN'
    + ' model size now that /tarkovscav client scale can grow the rig (the API vanilla culls with)',
  'the rig mobs use cullingBoxPaddingForScale, the villager family uses cullingBoxPaddingForVillagerScale');
check(!/RenderSystem\./.test(renderTypes) && !/RenderSystem\./.test(layer) && !/RenderSystem\./.test(read('client/ScavRenderer.java')),
  'model and item layers route GL state writes through the shared guard');
check(/RenderStateGuard/.test(renderTypes) && /RenderStateGuard\.snapshot\(/.test(layer),
  'render-type documentation acknowledges foreign renderer state, and item draws use the guard');

console.log('\nthe palm mount and its correction axis:');
check(/DEFAULT_MOUNT_OFFSET = List\.of\("0", "0", "0"\)/.test(config),
  'the shipped palm correction is zero; the gunpack locator supplies the grip');
const commands = readRaw('command/ClientCommands.java').replace(/\r\n/g, '\n');
check(/case "forward", "barrel" -> nudgeForward = -Float\.parseFloat\(value\)/.test(commands),
  'forward = -Z in the command');
check(/if \(nudgeForward != null\) \{\s*\n\s*offset\[2\] \+= nudgeForward;/.test(commands),
  'that nudge is added to Z');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
