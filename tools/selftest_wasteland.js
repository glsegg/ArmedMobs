// The urban-wasteland dimension and the deployment beacon that is its way in.
//
//   node tools/selftest_wasteland.js
//
// Why each check exists:
//   1. THE DATAPACK LOADS. A dimension is five JSON files that reference each other by id
//      (dimension -> dimension_type + noise_settings + biome -> placed features -> configured features),
//      and every one of those is a reload-time failure if it is misspelled or malformed. This gate parses
//      all of them and walks the references, because "the server refuses to start" is a much worse way to
//      find a typo.
//   2. THE BIOME TAG IS A SUPERSET. All six city structures now point at #tarkovscav:city_biomes instead
//      of #minecraft:is_overworld. Overworld generation is unchanged only if that tag still CONTAINS
//      #minecraft:is_overworld - so that containment is asserted here, not assumed.
//   3. THE DENSITY. The dimension's `structures` block overrides the four city structure sets. spacing
//      and separation are in CHUNKS, so the check is spacing * 16 >= the structure's block footprint;
//      the strongpoint is additionally asserted against the brief's literal spacing >= 138.
//   4. THE BEACON IS REAL. Registered item, model, 16x16 texture, recipe, creative-tab entry, and the one
//      shared teleport helper used by BOTH the item and /armedmobs dimension.
//   5. NO RAW KEY LEAKS. Every translatable key the new code prints must exist in en_us.json AND
//      zh_cn.json, otherwise a player sees "tarkovscav.beacon.arrived" in chat.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const DATA = path.join(RES, 'data', 'tarkovscav');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const DIMENSION = 'tarkovscav:urban_wasteland';

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');

// ------------------------------------------------------------------ 1. the datapack parses and links
console.log('1. the dimension datapack: files, JSON, and the id references between them');
const FILES = {
  dimension: path.join(DATA, 'dimension', 'urban_wasteland.json'),
  dimensionType: path.join(DATA, 'dimension_type', 'urban_wasteland.json'),
  noiseSettings: path.join(DATA, 'worldgen', 'noise_settings', 'urban_wasteland.json'),
  biome: path.join(DATA, 'worldgen', 'biome', 'urban_wasteland.json'),
  biomeTag: path.join(DATA, 'tags', 'worldgen', 'biome', 'city_biomes.json'),
};
const loaded = {};
for (const [name, file] of Object.entries(FILES)) {
  const exists = fs.existsSync(file);
  check(exists, `${path.relative(ROOT, file)} exists`, exists ? `${fs.statSync(file).size} bytes` : 'missing');
  if (exists) {
    try {
      loaded[name] = readJson(file);
    } catch (error) {
      check(false, `  ${name} parses as JSON`, error.message);
    }
  }
}
if (failures > 0 && Object.keys(loaded).length < Object.keys(FILES).length) {
  console.log('\nthe datapack is incomplete - nothing else can be checked');
  process.exit(1);
}

const dimension = loaded.dimension;
const dimensionType = loaded.dimensionType;
const settings = loaded.noiseSettings;
const biome = loaded.biome;

check(dimension.type === DIMENSION, 'the dimension uses the tarkovscav dimension type', dimension.type);
check(dimension.generator && dimension.generator.type === 'minecraft:noise',
  'the generator is minecraft:noise', dimension.generator && dimension.generator.type);
check(dimension.generator.biome_source.type === 'minecraft:fixed'
  && dimension.generator.biome_source.biome === DIMENSION,
  'the biome source is minecraft:fixed on the mod biome',
  `${dimension.generator.biome_source.type} -> ${dimension.generator.biome_source.biome}`);
check(dimension.generator.settings === DIMENSION, 'the noise settings id is the mod one',
  dimension.generator.settings);
check(FILES.dimensionType && dimensionType.min_y === -64 && dimensionType.height === 384
  && dimensionType.logical_height === 384,
  'the dimension type is 384 tall starting at min_y -64',
  `min_y=${dimensionType.min_y} height=${dimensionType.height} logical=${dimensionType.logical_height}`);
check(dimensionType.natural === true && dimensionType.has_skylight === true
  && dimensionType.has_ceiling === false && dimensionType.coordinate_scale === 1.0,
  'natural + skylight + no ceiling + coordinate_scale 1.0',
  `natural=${dimensionType.natural} skylight=${dimensionType.has_skylight} ceiling=${dimensionType.has_ceiling}`);
check(dimensionType.bed_works === true && dimensionType.respawn_anchor_works === false
  && dimensionType.has_raids === true && dimensionType.piglin_safe === false
  && dimensionType.ultrawarm === false,
  'bed_works, no respawn anchor, raids on, piglins safe off, not ultrawarm');
check(dimensionType.ambient_light === 0.0 && dimensionType.monster_spawn_light_level
  && dimensionType.monster_spawn_light_level.type === 'minecraft:uniform'
  && dimensionType.monster_spawn_light_level.value.min_inclusive === 0
  && dimensionType.monster_spawn_light_level.value.max_inclusive === 7
  && dimensionType.monster_spawn_block_light_limit === 0,
  'ambient light 0.0 and the overworld monster-spawn light rule', JSON.stringify(
    dimensionType.monster_spawn_light_level));

const effects = dimensionType.effects || {};
for (const key of ['sky_color', 'fog_color', 'water_color', 'water_fog_color']) {
  const value = effects[key];
  const hex = typeof value === 'number' ? '0x' + value.toString(16).toUpperCase().padStart(6, '0') : '?';
  const rgb = typeof value === 'number'
    ? `rgb(${(value >> 16) & 255},${(value >> 8) & 255},${value & 255})` : '-';
  check(typeof value === 'number', `  effects.${key} = ${value} (${hex} ${rgb})`);
}
check(effects.sky_color === biome.effects.sky_color && effects.fog_color === biome.effects.fog_color
  && effects.water_color === biome.effects.water_color
  && effects.water_fog_color === biome.effects.water_fog_color,
  'the dimension type and the biome use the same dusty palette');

check(settings.aquifers_enabled === false,
  'noise settings: aquifers OFF - standing water in the seams between city pieces was a hole to fall into');
check(settings.disable_mob_generation === false, 'noise settings: mob generation on');
check(settings.default_block && settings.default_block.Name === 'minecraft:stone',
  'noise settings: default block is stone', settings.default_block && settings.default_block.Name);
check(settings.ore_veins_enabled === false, 'noise settings: ore veins off');
check(settings.noise_router.vein_toggle === 0 && settings.noise_router.vein_ridged === 0
  && settings.noise_router.vein_gap === 0, 'noise settings: the three vein fields are constant 0');
check(typeof settings.sea_level === 'number' && settings.sea_level <= 0,
  'noise settings: sea level is at or below 0, so there is no ocean or lake water',
  `sea_level=${settings.sea_level} (min_y=${settings.noise.min_y})`);

// The low-relief chain: the router must reference the mod's own depth / sloped_cheese, which are the
// vanilla expressions with the offset spline scaled down.
console.log(`  noise_router.depth = ${JSON.stringify(settings.noise_router.depth)}`);
check(settings.noise_router.depth === `${DIMENSION}/depth`,
  'noise settings: the router depth is the mod low-relief depth', settings.noise_router.depth);
const depthFile = path.join(DATA, 'worldgen', 'density_function', 'urban_wasteland', 'depth.json');
const cheeseFile = path.join(DATA, 'worldgen', 'density_function', 'urban_wasteland', 'sloped_cheese.json');
for (const file of [depthFile, cheeseFile]) {
  check(fs.existsSync(file), `${path.relative(ROOT, file)} exists`);
}
if (fs.existsSync(depthFile) && fs.existsSync(cheeseFile)) {
  const depth = readJson(depthFile);
  const cheese = readJson(cheeseFile);
  const scale = depth.argument2 && depth.argument2.argument1;
  check(depth.argument2 && depth.argument2.argument2 === 'minecraft:overworld/offset'
    && typeof scale === 'number' && scale > 0 && scale < 1,
    'the low-relief depth scales the vanilla offset spline below 1',
    `scale=${scale} (so hills keep ${Math.round(scale * 100)} % of overworld amplitude)`);
  check(JSON.stringify(cheese).includes(`${DIMENSION}/depth`),
    'sloped_cheese is the vanilla expression re-pointed at the mod depth');
  check(JSON.stringify(settings.noise_router.final_density).includes(`${DIMENSION}/sloped_cheese`),
    'final_density uses that sloped_cheese');
  check(JSON.stringify(settings.noise_router.initial_density_without_jaggedness)
    .includes(`${DIMENSION}/depth`),
    'initial_density_without_jaggedness uses that depth (the heightmap follows the terrain)');
}
const paletteBlocks = JSON.stringify(settings.surface_rule);
for (const wanted of ['coarse_dirt', 'gravel', 'andesite', 'tuff', 'stone']) {
  check(paletteBlocks.includes(`minecraft:${wanted}`), `  the surface rule paints ${wanted}`);
}
check(!paletteBlocks.includes('grass_block'), 'the surface rule never paints grass');

// ------------------------------------------------------------------ 2. the biome
console.log('');
console.log('2. the biome: one custom biome, populated with the mod nine');
check(biome.has_precipitation === false, 'has_precipitation is false');
check(typeof biome.temperature === 'number' && typeof biome.downfall === 'number',
  `temperature/downfall are set (${biome.temperature} / ${biome.downfall})`);
check(Array.isArray(biome.features) && biome.features.length === 11,
  'features has the 11 vanilla decoration steps', `${biome.features && biome.features.length}`);
check(biome.carvers && Array.isArray(biome.carvers.air) && Array.isArray(biome.carvers.liquid)
  && biome.carvers.air.length === 0 && biome.carvers.liquid.length === 0,
  'carvers is an empty air+liquid map (1.20.1 wants an object, not a list)',
  JSON.stringify(biome.carvers));
const monster = (biome.spawners && biome.spawners.monster) || [];
const weights = monster.map((entry) => entry.weight);
console.log(`  monster spawners: ${monster.map((e) => `${e.type}(${e.weight})`).join(', ')}`);
check(monster.length === 9, 'the monster category carries the nine mod mobs', `${monster.length}`);
check(weights.join(',') === '5,2,2,1,1,1,1,1,1',
  'their weights mirror data/tarkovscav/forge/biome_modifier/add_scavs.json', weights.join(','));
check(weights.reduce((a, b) => a + b, 0) === 15, 'and their total is 15');
check(monster.every((e) => e.maxCount >= e.minCount && e.minCount >= 1),
  'every spawner has a sane minCount/maxCount');
const costs = Object.keys(biome.spawn_costs || {});
check(costs.length === 9, 'spawn_costs covers the same nine mobs', `${costs.length}`);
check(costs.every((id) => id.startsWith('tarkovscav:')),
  'spawn_costs only names the mod mobs', costs.join(', '));

const placedNames = ['gravel_rubble', 'coarse_dirt_patch', 'debris_field'];
const vegetal = biome.features[9];
for (const name of placedNames) {
  check(vegetal.includes(`tarkovscav:${name}`),
    `  the vegetal_decoration step places tarkovscav:${name}`);
  for (const kind of ['configured_feature', 'placed_feature']) {
    const file = path.join(DATA, 'worldgen', kind, `${name}.json`);
    check(fs.existsSync(file), `  ${kind}/${name}.json exists`);
  }
}
if (vegetal.includes('tarkovscav:gravel_rubble')) {
  const patch = readJson(path.join(DATA, 'worldgen', 'configured_feature', 'gravel_rubble.json'));
  check(patch.type === 'minecraft:random_patch'
    && patch.config.feature === 'tarkovscav:gravel_rubble_block',
    'the gravel rubble is a random_patch over the single-block placed feature',
    patch.config.feature);
  check(fs.existsSync(path.join(DATA, 'worldgen', 'placed_feature', 'gravel_rubble_block.json')),
    '  and that inner placed feature exists');
}

// ------------------------------------------------------------------ 3. the city tag and the density
console.log('');
console.log('3. the city biome tag, the six structures, and the per-dimension structure density');
const tag = loaded.biomeTag;
check(Array.isArray(tag.values) && tag.values.includes('#minecraft:is_overworld'),
  'the tag still CONTAINS #minecraft:is_overworld (so overworld generation is unchanged)',
  JSON.stringify(tag.values));
check(tag.values.includes(DIMENSION), 'and it adds the wasteland biome', DIMENSION);
const STRUCTURES = ['city_small', 'city_a', 'city_b', 'city_c', 'city_district', 'city_strongpoint'];
for (const name of STRUCTURES) {
  const file = path.join(DATA, 'worldgen', 'structure', `${name}.json`);
  const structure = readJson(file);
  check(structure.biomes === '#tarkovscav:city_biomes',
    `  ${name} can appear in the tag (and therefore in the overworld)`, structure.biomes);
}
// The density mechanism: a custom StructurePlacement, because a datapack dimension CANNOT override
// structure sets in 1.20.1 (LevelStem's codec reads only type + generator).
check(dimension.structures === undefined,
  'the dimension JSON has NO structures block (in 1.20.1 LevelStem ignores it - a silent trap)',
  dimension.structures === undefined ? 'absent, as it should be' : JSON.stringify(dimension.structures));
const PLACEMENT_TYPE = 'tarkovscav:wasteland_spread';
const FOOTPRINTS = {
  // The wasteland pairs for city_small/city_variants were 4/1 and 5/2 (one city every 64 and 80 blocks,
  // i.e. the presets touched each other) until the no-water/density pass; they are now the shipped grid's
  // 24/8 = one city per 384 blocks. city_district and city_strongpoint are deliberately untouched.
  city_small: { ids: ['tarkovscav:city_small'], width: 48, normalSpacing: 24, normalSeparation: 8,
    denseSpacing: 24, denseSeparation: 8 },
  city_variants: { ids: ['tarkovscav:city_a', 'tarkovscav:city_b', 'tarkovscav:city_c'], width: 64,
    normalSpacing: 32, normalSeparation: 12, denseSpacing: 24, denseSeparation: 8 },
  city_district: { ids: ['tarkovscav:city_district'], width: 256, normalSpacing: 48, normalSeparation: 20,
    denseSpacing: 16, denseSeparation: 5 },
  city_strongpoint: { ids: ['tarkovscav:city_strongpoint'], width: 138, normalSpacing: 192,
    normalSeparation: 48, denseSpacing: 192, denseSeparation: 48 },
};
for (const [name, want] of Object.entries(FOOTPRINTS)) {
  const file = path.join(DATA, 'worldgen', 'structure_set', `${name}.json`);
  const set = readJson(file);
  const placement = set.placement;
  const blocks = placement.dense_spacing * 16;
  check(placement.type === PLACEMENT_TYPE,
    `  ${name}: placement.type = ${placement.type}`, PLACEMENT_TYPE);
  check(placement.spacing === want.normalSpacing && placement.separation === want.normalSeparation,
    `  ${name}: the overworld pair is the shipped ${want.normalSpacing}/${want.normalSeparation}`
    + ' (so the overworld grid is unchanged)',
    `${placement.spacing}/${placement.separation}`);
  check(placement.dense_spacing === want.denseSpacing
    && placement.dense_separation === want.denseSeparation,
    `  ${name}: the wasteland pair is ${want.denseSpacing}/${want.denseSeparation}`,
    `${placement.dense_spacing}/${placement.dense_separation}`);
  check(placement.frequency === 0.25,
    `  ${name}: frequency = ${placement.frequency} - the user's -75 % placement cut, applied to both`
    + ' dimensions because vanilla checks frequency before the placement decides',
    String(placement.frequency));
  check(blocks >= want.width && placement.dense_separation < placement.dense_spacing,
    `  ${name}: dense_spacing ${placement.dense_spacing} chunks = ${blocks} blocks`
    + ` >= footprint ${want.width} blocks`,
    `dense_separation=${placement.dense_separation}`);
  check(want.ids.every((id) => set.structures.some((s) => s.structure === id)),
    `  ${name}: still places ${want.ids.join(', ')}`);
}
const strongpointSet = readJson(path.join(DATA, 'worldgen', 'structure_set', 'city_strongpoint.json'));
check(strongpointSet.placement.dense_spacing >= 138
  && strongpointSet.placement.dense_spacing === strongpointSet.placement.spacing,
  'the strongpoint is deliberately left SPARSE (dense == normal == 192 >= its 138-wide footprint):'
  + ' a world packed with 138-wide strongpoints would be unusable',
  `dense=${strongpointSet.placement.dense_spacing} normal=${strongpointSet.placement.spacing}`);

// The Java half of the mechanism.
const placementSource = read('worldgen/WastelandSpreadPlacement.java');
const worldgenSource = read('worldgen/ModWorldgen.java');
check(/extends RandomSpreadStructurePlacement/.test(placementSource),
  'the placement extends the vanilla random-spread placement');
check(/instanceof FixedBiomeSource/.test(placementSource)
  && /urban_wasteland/.test(placementSource),
  'its discriminator is a fixed biome source holding the wasteland biome');
check(/isPlacementChunk\(ChunkGeneratorStructureState/.test(placementSource)
    && !/public boolean isStructureChunk\(/.test(placementSource),
  'it overrides the placement predicate and retains vanilla frequency/exclusion checks');
check(/normalSpacing, this\.normalSeparation/.test(placementSource)
  && /denseSpacing, this\.denseSeparation/.test(placementSource),
  'both branches run the same vanilla formula, one with the shipped pair and one with the dense pair');
check(/public int spacing\(\)\s*\{\s*return this\.denseSpacing/.test(placementSource)
  && /public int separation\(\)\s*\{\s*return this\.denseSeparation/.test(placementSource),
  'spacing()/separation() report the dense pair so /locate walks the dense grid');
check(/Registries\.STRUCTURE_PLACEMENT\b/.test(worldgenSource)
  && /"wasteland_spread"/.test(worldgenSource),
  'ModWorldgen registers tarkovscav:wasteland_spread on the structure-placement registry');
check(/ModWorldgen\.register\(modBus\)/.test(read('TarkovScav.java')),
  'TarkovScav registers it');
// The dimension discriminator reads the private field through a cached reflective lookup.
// It does not depend on the camera access transformer removed with player lean.
check(/"biomeSource"/.test(placementSource) && /"f_254681_"/.test(placementSource)
  && /getDeclaredField/.test(placementSource),
  'the placement resolves ChunkGeneratorStructureState#biomeSource once, under both its official and SRG'
  + ' names (vanilla keeps it private, and it is the only reachable dimension discriminator)');

// ------------------------------------------------------------------ 4. the beacon item
console.log('');
console.log('4. the deployment beacon: registration, assets, recipe, tab, and the shared helper');
const itemsSource = read('registry/ModItems.java');
const tabsSource = read('registry/ModCreativeTabs.java');
const itemSource = read('item/DeploymentBeaconItem.java');
const travelSource = read('world/WastelandTravel.java');
const commandsSource = read('command/ModCommands.java');
const savedDataSource = read('world/RetreatData.java');

check(/ITEMS\.register\("deployment_beacon"/.test(itemsSource),
  'ModItems registers deployment_beacon');
check(/output\.accept\(ModItems\.DEPLOYMENT_BEACON\.get\(\)\)/.test(tabsSource),
  'the creative tab lists it');
check(/register\(com\.gfl\.tarkovscav\.world\.WastelandTravel\.class\)/.test(read('TarkovScav.java')),
  'TarkovScav registers the travel event handler');
const model = path.join(ASSETS, 'models', 'item', 'deployment_beacon.json');
check(fs.existsSync(model), 'assets/.../models/item/deployment_beacon.json exists');
if (fs.existsSync(model)) {
  const parsed = readJson(model);
  check(parsed.parent === 'minecraft:item/generated'
    && parsed.textures.layer0 === 'tarkovscav:item/deployment_beacon',
    'the model is item/generated on tarkovscav:item/deployment_beacon', parsed.textures.layer0);
}
const texture = path.join(ASSETS, 'textures', 'item', 'deployment_beacon.png');
check(fs.existsSync(texture), 'assets/.../textures/item/deployment_beacon.png exists');
if (fs.existsSync(texture)) {
  const png = fs.readFileSync(texture);
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  check(width === 16 && height === 16 && png.length > 100,
    'the texture is a real 16x16 PNG', `${width}x${height} ${png.length} bytes`);
}
const recipeFile = path.join(DATA, 'recipes', 'deployment_beacon.json');
check(fs.existsSync(recipeFile), 'data/tarkovscav/recipes/deployment_beacon.json exists');
if (fs.existsSync(recipeFile)) {
  const recipe = readJson(recipeFile);
  check(recipe.type === 'minecraft:crafting_shaped'
    && recipe.result && recipe.result.item === 'deployment_beacon'.replace(/^/, 'tarkovscav:'),
    'the recipe is a shaped crafting recipe yielding the beacon', recipe.result && recipe.result.item);
  const ingredients = Object.values(recipe.key || {}).map((entry) => entry.item);
  check(ingredients.length >= 3 && ingredients.includes('minecraft:iron_ingot')
    && ingredients.includes('minecraft:ender_pearl'),
    'and it is survival-obtainable (iron + ender pearl + a redstone torch)',
    ingredients.join(', '));
}

// The shared helper: both call sites must name the same class, and that class must own the landing code.
check(/WastelandTravel/.test(itemSource), 'the beacon item calls the shared helper');
check(/WastelandTravel\.teleportInto|WastelandTravel\.teleportTo/.test(commandsSource),
  '/armedmobs dimension calls the same shared helper');
check(/Arrival teleportInto\(ServerPlayer player\)/.test(travelSource)
  && /Arrival teleportBack\(ServerPlayer player\)/.test(travelSource)
  && /teleportTo\(player, WASTELAND, player\.getBlockX\(\), player\.getBlockZ\(\), true\)/.test(travelSource),
  'teleportInto and teleportBack are thin wrappers over the one teleportTo implementation');
check(/Heightmap\.Types\.MOTION_BLOCKING/.test(travelSource),
  'the landing Y comes from the motion-blocking heightmap');
// The user was teleported UNDER the wasteland (y=-56) because a heightmap query on a chunk that does not
// exist yet answers with the minimum build height. The fix has three parts, all asserted here.
check(/getChunkSource\(\)\.getChunk\([^)]*ChunkStatus\.FULL[^)]*true\)/.test(travelSource),
  'the landing forces the target chunk to FULL before reading the heightmap');
check(/surface <= minY \+ 1/.test(travelSource) && /scanDownForGround/.test(travelSource),
  'and falls back to a downward ground scan when the heightmap only reports the world floor');
check(/return minY \+ 1;/.test(travelSource),
  'the scan never returns a y below the world floor (minY + 1)');
check(/private static int scanDownForGround\(ServerLevel level, int x, int z, int top, int minY\)/
  .test(travelSource),
  'the fallback scan is a named method with the world bounds passed in');
check(!/check\(\/recompute/.test(fs.readFileSync(__filename, 'utf8')),
  'no self-referential placeholder check is left in this gate');
check(/STONE_BRICKS/.test(travelSource) && /Blocks\.TORCH/.test(travelSource),
  'the arrival platform is stone bricks with a torch');
check(/SavedData/.test(savedDataSource) && /class RetreatData extends SavedData/.test(savedDataSource),
  'the return anchor is a SavedData keyed by player');
check(/retreat|RetreatData/.test(travelSource), 'and the helper reads it for the return trip');
const castKeys = ['CAST_TICKS', 'COOLDOWN_TICKS', 'MAX_CAST_DRIFT'];
for (const key of castKeys) {
  check(new RegExp(`public static final (int|double) ${key}`).test(travelSource),
    `  the cast rule names ${key}`);
}
check(/LivingHurtEvent/.test(travelSource), 'taking damage aborts the cast');
check(/literal\("dimension"\)/.test(commandsSource), '/armedmobs dimension is registered');

// ------------------------------------------------------------------ 5. no raw lang key leaks
console.log('');
console.log('5. every message key the new code prints exists in both languages');
const lang = {};
for (const name of ['en_us', 'zh_cn']) {
  const text = fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');
  lang[name] = JSON.parse(text);
}
const used = new Set();
for (const source of [travelSource, itemSource, commandsSource]) {
  for (const match of source.matchAll(/translatable\("([^"]+)"/g)) {
    if (match[1].startsWith('tarkovscav.')) used.add(match[1]);
  }
}
console.log(`  the new code prints ${used.size} translatable key(s)`);
for (const key of [...used].sort()) {
  const inEn = Object.prototype.hasOwnProperty.call(lang.en_us, key);
  const inZh = Object.prototype.hasOwnProperty.call(lang.zh_cn, key);
  check(inEn && inZh, `  ${key}`, inEn ? (inZh ? 'en + zh' : 'MISSING in zh_cn') : 'MISSING in en_us');
}
for (const key of ['item.tarkovscav.deployment_beacon', 'item.tarkovscav.deployment_beacon.tooltip',
  'item.tarkovscav.deployment_beacon.tooltip2']) {
  check(Boolean(lang.en_us[key]) && Boolean(lang.zh_cn[key]), `  ${key} is present in both languages`);
}
check(lang.zh_cn['item.tarkovscav.deployment_beacon'] === '部署信标',
  'zh_cn names the item 部署信标', String(lang.zh_cn['item.tarkovscav.deployment_beacon']));

// ------------------------------------------------------------------ 6. the rendered map and the doc
console.log('');
console.log('6. the published evidence');
const map = path.join(ROOT, 'docs', 'maps', 'urban_wasteland.png');
if (fs.existsSync(map)) {
  const png = fs.readFileSync(map);
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  console.log(`  docs/maps/urban_wasteland.png ${width}x${height} ${png.length} bytes`);
  check(width > 400 && height > 400, 'the rendered map exists and is not a thumbnail',
    `${width}x${height}`);
} else {
  console.log('  docs/maps/urban_wasteland.png is not rendered yet (CityMap box mode writes it)');
}
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
check(/urban_wasteland/.test(readme), 'README names the dimension');
check(/deployment_beacon/.test(readme), 'README documents the beacon item');
const reference = fs.readFileSync(path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'), 'utf8');
check(/urban_wasteland/.test(reference), 'the command/config reference names the dimension');
check(/deployment_beacon/.test(reference), 'the command/config reference documents the beacon');

// ------------------------------------------------------------------ 7. the no-water / density hygiene
console.log('');
console.log('7. no standing water, one city per 384 blocks, and the fillwater retro-fit');
const noise = readJson(path.join(DATA, 'worldgen', 'noise_settings', 'urban_wasteland.json'));
check(noise.aquifers_enabled === false,
  'the wasteland preset generates no aquifers', `aquifers_enabled=${noise.aquifers_enabled}`);
check(noise.default_fluid && noise.default_fluid.Name === 'minecraft:air',
  'and its default fluid is air, so the seams between city pieces cannot fill with water',
  JSON.stringify(noise.default_fluid));

// spacing is in CHUNKS. The four city sets on purpose do NOT all share one grid (the exact numbers are
// asserted against FOOTPRINTS in section 3, which is the single place they live):
//   city_variants / city_small -> one city per 24 chunks (384 blocks); they were 5 and 4 chunks, so the
//     48-64 block presets were landing 64-80 blocks apart and could not help overlapping
//   city_district -> 16 chunks, UNCHANGED: the street tiles are meant to chain into the big city
//   city_strongpoint -> 192 chunks, UNCHANGED: the rare 18-building piece
const margins = ['city-layout', 'city-layout-a', 'city-layout-b', 'city-layout-c', 'strongpoint-layout']
  .map((name) => ({ name, margin: readJson(path.join(ROOT, 'tools', `${name}.json`)).platform.margin }));
for (const { name, margin } of margins) {
  check(margin === 16, `${name}: the test-build platform keeps a 16 block margin`, `margin=${margin}`);
}

const cleanup = read(path.join('world', 'WaterCleanup.java'));
const commands = read(path.join('command', 'ModCommands.java'));
check(fs.existsSync(path.join(JAVA, 'world', 'WaterCleanup.java')),
  'the fillwater body exists as its own class');
check(/DEFAULT_BLOCK = "minecraft:stone"/.test(cleanup),
  'fillwater defaults to a block you can stand on, not to air');
check(/here == Blocks\.WATER/.test(cleanup),
  'it replaces the water block and nothing else');
check(!/Blocks\.FLOWING_WATER/.test(cleanup),
  'and does not pretend a flowing_water BLOCK exists (1.20.1 has one water block with a level property)');
const waterloggedAt = cleanup.indexOf('waterlogged++');
const setBlockAt = cleanup.indexOf('setBlock(');
check(waterloggedAt > 0 && setBlockAt > waterloggedAt,
  'a waterlogged block is counted and left alone - the count comes before any replacement',
  `waterlogged@${waterloggedAt} setBlock@${setBlockAt}`);
check(/Block\.UPDATE_CLIENTS/.test(cleanup) && !/Block\.UPDATE_ALL/.test(cleanup),
  'the mass fill sends to clients only, so it cannot stall the tick on neighbour updates');
check(/fillWater\(context, 48,/.test(commands) && /IntegerArgumentType\.integer\(1, 128\)/.test(commands),
  'the command defaults to 48 and clamps the radius to 1..128');
check(/root\.then\(fillWater\(\)\)/.test(commands), 'fillwater is registered on the operator tree');
check(/Unknown block/.test(commands) && /return 0;/.test(commands),
  'an unknown block is refused with a message instead of defaulting silently');

console.log('');
if (failures > 0) {
  console.log(`${failures} wasteland check(s) FAILED`);
  process.exit(1);
}
console.log('the urban wasteland dimension, its dense city placement and the deployment beacon all hold');
