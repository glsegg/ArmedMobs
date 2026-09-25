// The datapack codec gate: every JSON this mod ships is parsed and held against the REAL field names and
// numeric ranges of the vanilla (and mod) codecs it is written for.
//
//   node tools/selftest_datapack.js
//
// Why this file exists (2026-09-23): `worldgen/structure/city_strongpoint.json` shipped with
// `"max_distance_from_center": 224`, but vanilla's JigsawStructure codec is `Codec.intRange(1, 128)`.
// The result was not a wrong-looking structure - it was
//   "Failed to parse tarkovscav:worldgen/structure/city_strongpoint.json from pack armedmobs-...jar"
// and EVERY world load and new-world creation refused on the client. A build that had never been
// started on the dedicated server shipped anyway. This gate is the static half of the fix: a field that
// is out of range, misspelled, or pointing at an id that does not exist fails here, in a second, with
// the file name and the field name.
//
// What it asserts, for every JSON under src/main/resources/data/:
//   1. it parses;
//   2. structure JSON: the key set is EXACTLY the allowed set for its `type`, and max_distance_from_center
//      / size / step / terrain_adaptation / start_height / project_start_to_heightmap are in range;
//   3. structure_set JSON: spacing >= separation > 0, spacing covers the structure's real footprint
//      (spacing is in CHUNKS, so the comparison is spacing * 16 >= the block footprint, which is read
//      out of the structure's own NBT), and every `structure` id exists;
//   4. every referenced id exists: start_pool, structure, biomes tag, biome, settings, dimension type,
//      placed/configured features, template_pool element locations (.nbt) and their `fallback`;
//   5. biome JSON: temperature / downfall / spawner weights / spawn_costs are in range and the nine mob
//      ids are real;
//   6. noise_settings JSON: min_y / height / size_horizontal / size_vertical / sea_level are inside the
//      ranges NoiseSettings validates, and the router references exist;
//   7. dimension + dimension_type JSON: the fields DimensionType validates;
//   8. recipes: the result item is a real registered item.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const DATA = path.join(RES, 'data');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  if (!ok) {
    failures++;
    console.log(`  FAIL  ${label}${detail ? '  ' + detail : ''}`);
  } else {
    console.log(`  pass  ${label}${detail ? '  ' + detail : ''}`);
  }
};
/** A hard failure: prints and records, so the run never dies on the first bad file. */
const fail = (file, field, problem) => {
  check(false, `${file}: ${field}`, problem);
};

// ------------------------------------------------------------------ the vanilla codec facts
// Each entry is the FACT this gate enforces. Sources are named so a future reader can re-check them.
const JIGSAW_KEYS = ['type', 'biomes', 'step', 'terrain_adaptation', 'spawn_overrides', 'start_pool',
  'size', 'start_height', 'project_start_to_heightmap', 'max_distance_from_center', 'use_expansion_hack'];
const OPTIONAL_JIGSAW_KEYS = ['start_jigsaw_name', 'pool_aliases'];
const MAX_DISTANCE_RANGE = [1, 128];        // JigsawStructure: Codec.intRange(1, 128) (javap: sipush 128)
const SIZE_RANGE = [0, 7];                  // JigsawStructure max depth (javap: Codec.intRange(0, 7))
// JigsawStructure's codec validates `max_distance_from_center + terrain padding <= 128` and errors with
// "Structure size including terrain adaptation must not exceed 128". The padding comes from the switch
// map in JigsawStructure$1 (javap of the shipped server jar): NONE -> 1 -> padding 0, BURY/BEARD_THIN/
// BEARD_BOX -> 2/3/4 -> padding 12. So a beard_thin structure may use at most 116 - and 224 (or even
// 128) is not a wrong-looking structure, it is a registry failure that stops every world from loading.
const TERRAIN_PADDING = { none: 0, bury: 12, beard_thin: 12, beard_box: 12 };
const ADAPTED_MAX = 128;
const SPACING_RANGE = [1, 4096];            // RandomSpreadStructurePlacement: intRange(1, 4096)
const DECORATION_STEPS = ['raw_generation', 'lakes', 'local_modifications', 'underground_structures',
  'surface_structures', 'strongholds', 'underground_ores', 'underground_decoration', 'fluid_springs',
  'vegetal_decoration', 'top_layer_modification'];
const TERRAIN_ADAPTATION = ['none', 'bury', 'beard_thin', 'beard_box', 'encapsulate'];
const HEIGHTMAPS = ['WORLD_SURFACE_WG', 'WORLD_SURFACE', 'OCEAN_FLOOR_WG', 'OCEAN_FLOOR',
  'MOTION_BLOCKING', 'MOTION_BLOCKING_NO_LEAVES'];
const HEIGHT_PROVIDERS = ['absolute', 'above_bottom', 'below_top', 'trapezoid', 'uniform',
  'biased_to_bottom', 'very_biased_to_bottom', 'weighted_list', 'constant'];
const POOL_ELEMENT_TYPES = ['minecraft:single_pool_element', 'minecraft:list_pool_element',
  'minecraft:feature_pool_element', 'minecraft:empty_pool_element', 'minecraft:legacy_single_pool_element'];
const PLACEMENT_TYPES = ['minecraft:random_spread', 'minecraft:concentric_rings',
  'tarkovscav:wasteland_spread'];
const PLACEMENT_KEYS = ['type', 'spacing', 'separation', 'salt', 'spread_type', 'frequency',
  'frequency_reduction_method', 'locate_offset', 'dense_spacing', 'dense_separation'];
// The vanilla entity_type tags that actually exist in 1.20.1 (read out of the client jar's
// data/minecraft/tags/entity_types/). `#minecraft:undead` is NOT one of them - referencing it made
// TagLoader log "Couldn't load tag tarkovscav:faction_village_hostile as it is missing following
// references: #minecraft:undead" and silently emptied that part of the tag. This list is the gate that
// stops the next missing tag reference.
const VANILLA_ENTITY_TYPE_TAGS = ['arrows', 'axolotl_always_hostiles', 'axolotl_hunt_targets',
  'beehive_inhabitors', 'dismounts_underwater', 'fall_damage_immune', 'freeze_hurts_extra_types',
  'freeze_immune_entity_types', 'frog_food', 'impact_projectiles', 'powder_snow_walkable_mobs',
  'raiders', 'skeletons'];
const MOB_CATEGORIES = ['monster', 'creature', 'ambient', 'axolotls', 'underground_water_creature',
  'water_creature', 'water_ambient', 'misc'];
const SETTINGS_KEYS = ['aquifers_enabled', 'default_block', 'default_fluid', 'disable_mob_generation',
  'legacy_random_source', 'noise', 'noise_router', 'ore_veins_enabled', 'sea_level', 'spawn_target',
  'surface_rule'];
const ROUTER_KEYS = ['barrier', 'fluid_level_floodedness', 'fluid_level_spread', 'lava', 'temperature',
  'vegetation', 'continents', 'erosion', 'depth', 'ridges', 'final_density', 'vein_toggle', 'vein_ridged',
  'vein_gap', 'initial_density_without_jaggedness'];
const DIMENSION_TYPE_KEYS = ['ultrawarm', 'natural', 'coordinate_scale', 'has_skylight', 'has_ceiling',
  'ambient_light', 'monster_spawn_light_level', 'monster_spawn_block_light_limit', 'piglin_safe',
  'bed_works', 'respawn_anchor_works', 'has_raids', 'logical_height', 'min_y', 'height', 'infiniburn',
  'effects', 'fixed_time'];
const NOISE_SETTINGS_KEYS = ['min_y', 'height', 'size_horizontal', 'size_vertical'];

// ------------------------------------------------------------------ helpers
/**
 * Every json under data/, as { file, rel, namespace, registry, id, json }.
 *
 * The registry directory is derived from the path layout the game uses, NOT from the file name: a
 * template pool may nest ({@code worldgen/template_pool/city_a/start.json} is the id
 * {@code tarkovscav:city_a/start}), and a tag lives three levels deep
 * ({@code tags/worldgen/biome/city_biomes.json} is {@code tarkovscav:city_biomes}).
 */
function allJson() {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.name.endsWith('.json')) {
        const rel = path.relative(DATA, full).split(path.sep).join('/');
        const parts = rel.split('/');
        const namespace = parts[0];
        const rest = parts.slice(1);
        let registry;
        let idPath;
        if (rest[0] === 'worldgen') {
          registry = `${rest[0]}/${rest[1]}`;
          idPath = rest.slice(2).join('/');
        } else if (rest[0] === 'tags') {
          registry = `${rest[0]}/${rest[1]}/${rest[2]}`;
          idPath = rest.slice(3).join('/');
        } else {
          registry = rest[0];
          idPath = rest.slice(1).join('/');
        }
        out.push({
          file: full,
          rel: `data/${rel}`,
          namespace,
          registry,
          id: `${namespace}:${idPath.replace(/\.json$/, '')}`,
          json: null,
        });
      }
    }
  };
  walk(DATA);
  return out.sort((a, b) => a.rel.localeCompare(b.rel));
}

/** The ids this pack defines, as "<namespace>:<path>", grouped by registry directory. */
const DEFINED = new Map();
for (const entry of allJson()) {
  if (!DEFINED.has(entry.registry)) {
    DEFINED.set(entry.registry, new Set());
  }
  DEFINED.get(entry.registry).add(entry.id);
}
const definedIn = (registry) => DEFINED.get(registry) || new Set();

/** True when `id` is defined in one of the given registry directories. */
function idExists(id, ...registries) {
  return registries.some((registry) => definedIn(registry).has(id));
}
/** A "#ns:tag" reference resolves against tag files of the given registry ("worldgen/biome", ...). */
function tagExists(ref, registry) {
  const bare = ref.replace(/^#/, '');
  return definedIn(`tags/${registry}`).has(bare);
}
/** Vanilla (or any third-party namespace) references are out of this pack's control - accepted. */
const isForeign = (id) => typeof id === 'string' && id.includes(':') && !id.startsWith('tarkovscav:');

const mobIds = new Set([...fs.readFileSync(path.join(JAVA, 'registry/ModEntities.java'), 'utf8')
  .matchAll(/ENTITY_TYPES\.register\("([a-z0-9_]+)"/g)].map((m) => `tarkovscav:${m[1]}`));
const itemIds = new Set([
  ...[...fs.readFileSync(path.join(JAVA, 'registry/ModItems.java'), 'utf8')
    .matchAll(/ITEMS\.register\("([a-z0-9_]+)"/g)].map((m) => `tarkovscav:${m[1]}`),
  // Blocks with an item form are registered in ModBlocks; accept those names too.
  ...[...fs.readFileSync(path.join(JAVA, 'registry/ModBlocks.java'), 'utf8')
    .matchAll(/(?:BLOCK_ITEM|ITEMS)\.register\("([a-z0-9_]+)"/g)].map((m) => `tarkovscav:${m[1]}`),
  // The five throwables register through GrenadeKind.itemPath(): FRAG("frag_grenade", "frag") - a
  // non-literal register call, so the literal scan above cannot see them.
  ...[...fs.readFileSync(path.join(JAVA, 'grenade/GrenadeKind.java'), 'utf8')
    .matchAll(/\(\s*"([a-z0-9_]+)"\s*,\s*"[a-z0-9_]+"\s*\)/g)].map((m) => `tarkovscav:${m[1]}`),
]);

// ------------------------------------------------------------------ a minimal NBT size reader
// The footprint of a single-piece jigsaw is the size of its .nbt, so the density check reads it from the
// shipped structure instead of trusting a number typed into this file. Only the root "size" tag is
// decoded; everything else is skipped by length, so a 300k-block structure costs nothing.
function nbtSize(file) {
  let buffer = fs.readFileSync(file);
  if (buffer[0] === 0x1f && buffer[1] === 0x8b) {
    buffer = zlib.gunzipSync(buffer);
  }
  let offset = 0;
  const u8 = () => buffer[offset++];
  const i16 = () => { const v = buffer.readInt16BE(offset); offset += 2; return v; };
  const i32 = () => { const v = buffer.readInt32BE(offset); offset += 4; return v; };
  const skipString = () => { const n = buffer.readUInt16BE(offset); offset += 2 + n; return null; };
  const readString = () => {
    const n = buffer.readUInt16BE(offset); offset += 2;
    const s = buffer.toString('utf8', offset, offset + n); offset += n; return s;
  };
  const skipPayload = (type) => {
    switch (type) {
      case 1: offset += 1; break;
      case 2: offset += 2; break;
      case 3: case 5: offset += 4; break;
      case 4: case 6: offset += 8; break;
      case 7: { const n = i32(); offset += n; break; }
      case 8: skipString(); break;
      case 9: {
        const elementType = u8();
        const n = i32();
        for (let i = 0; i < n; i++) skipPayload(elementType);
        break;
      }
      case 10: {
        for (;;) {
          const childType = u8();
          if (childType === 0) break;
          skipString();
          skipPayload(childType);
        }
        break;
      }
      case 11: { const n = i32(); offset += n * 4; break; }
      case 12: { const n = i32(); offset += n * 8; break; }
      default: throw new Error(`bad NBT tag type ${type}`);
    }
  };
  const rootType = u8();
  skipString();
  if (rootType !== 10) throw new Error('the structure NBT root is not a compound');
  for (;;) {
    const childType = u8();
    if (childType === 0) break;
    const name = readString();
    if (name === 'size' && childType === 9) {
      const elementType = u8();
      const n = i32();
      if (elementType !== 3 || n !== 3) throw new Error('the size tag is not three ints');
      return [i32(), i32(), i32()];
    }
    skipPayload(childType);
  }
  throw new Error('the structure NBT has no size tag');
}

// ------------------------------------------------------------------ 1. everything parses
console.log('1. every JSON under data/ parses');
const files = allJson();
for (const entry of files) {
  try {
    entry.json = JSON.parse(fs.readFileSync(entry.file, 'utf8'));
  } catch (error) {
    fail(entry.rel, 'JSON', error.message);
  }
}
console.log(`  ${files.length} file(s) scanned, ${failures} parse failure(s)`);

const byRegistry = (registry) => files.filter((f) => f.json && f.registry === registry
  && f.namespace === 'tarkovscav');

// The structure id -> its own NBT footprint, used by the density check in part 3.
const structureFootprint = new Map();
function footprintOf(structureId, json) {
  if (structureFootprint.has(structureId)) {
    return structureFootprint.get(structureId);
  }
  let footprint = null;
  if (json.size === 1) {
    const poolId = json.start_pool;
    const pool = poolId && definedIn('worldgen/template_pool').has(poolId) ? poolId : null;
    const poolEntry = pool ? byRegistry('worldgen/template_pool').find((f) => f.id === pool) : null;
    const location = poolEntry && poolEntry.json.elements && poolEntry.json.elements[0]
      && poolEntry.json.elements[0].element && poolEntry.json.elements[0].element.location;
    if (location) {
      const nbt = path.join(DATA, location.split(':')[0], 'structures',
        `${location.split(':')[1]}.nbt`);
      if (fs.existsSync(nbt)) {
        const size = nbtSize(nbt);
        footprint = Math.max(size[0], size[2]);
      }
    }
  } else if (typeof json.max_distance_from_center === 'number') {
    // An expanding jigsaw can reach max_distance_from_center in every direction.
    footprint = json.max_distance_from_center * 2;
  }
  structureFootprint.set(structureId, footprint);
  return footprint;
}

// ------------------------------------------------------------------ 2. structures
console.log('');
console.log('2. worldgen/structure: exact key set per type, and every numeric field in codec range');
const structureEntries = byRegistry('worldgen/structure');
for (const entry of structureEntries) {
  const json = entry.json;
  const id = entry.id;
  if (json.type !== 'minecraft:jigsaw') {
    fail(entry.rel, 'type', `unknown structure type ${json.type}; this gate knows minecraft:jigsaw`);
    continue;
  }
  const allowed = new Set([...JIGSAW_KEYS, ...OPTIONAL_JIGSAW_KEYS]);
  const unknown = Object.keys(json).filter((key) => !allowed.has(key));
  const missing = JIGSAW_KEYS.filter((key) => !(key in json));
  check(unknown.length === 0, `${entry.rel}: no unknown field`,
    unknown.length ? `unknown: ${unknown.join(', ')}` : `${Object.keys(json).length} keys, all allowed`);
  check(missing.length === 0, `${entry.rel}: every required jigsaw field is present`,
    missing.length ? `missing: ${missing.join(', ')}` : JIGSAW_KEYS.join(', '));

  const distance = json.max_distance_from_center;
  check(Number.isInteger(distance) && distance >= MAX_DISTANCE_RANGE[0] && distance <= MAX_DISTANCE_RANGE[1],
    `${entry.rel}: max_distance_from_center = ${distance} (allowed ${MAX_DISTANCE_RANGE[0]}..${MAX_DISTANCE_RANGE[1]})`);
  const padding = TERRAIN_PADDING[json.terrain_adaptation];
  check(padding !== undefined && distance + padding <= ADAPTED_MAX,
    `${entry.rel}: max_distance_from_center ${distance} + terrain adaptation ${json.terrain_adaptation}`
    + ` (padding ${padding}) = ${distance + padding} <= ${ADAPTED_MAX}`,
    padding === undefined ? `unknown terrain_adaptation ${json.terrain_adaptation}` : '');
  const size = json.size;
  check(Number.isInteger(size) && size >= SIZE_RANGE[0] && size <= SIZE_RANGE[1],
    `${entry.rel}: size = ${size} (allowed ${SIZE_RANGE[0]}..${SIZE_RANGE[1]})`);
  check(DECORATION_STEPS.includes(json.step), `${entry.rel}: step = ${json.step} (a decoration step)`);
  check(TERRAIN_ADAPTATION.includes(json.terrain_adaptation),
    `${entry.rel}: terrain_adaptation = ${json.terrain_adaptation}`, TERRAIN_ADAPTATION.join('|'));
  check(json.project_start_to_heightmap === undefined || HEIGHTMAPS.includes(json.project_start_to_heightmap),
    `${entry.rel}: project_start_to_heightmap = ${json.project_start_to_heightmap}`);
  check(typeof json.use_expansion_hack === 'boolean',
    `${entry.rel}: use_expansion_hack = ${json.use_expansion_hack} (boolean)`);
  check(json.spawn_overrides !== null && typeof json.spawn_overrides === 'object'
    && !Array.isArray(json.spawn_overrides), `${entry.rel}: spawn_overrides is a map`);
  const heightKeys = json.start_height ? Object.keys(json.start_height) : [];
  check(heightKeys.length === 1 && HEIGHT_PROVIDERS.includes(heightKeys[0]),
    `${entry.rel}: start_height = ${JSON.stringify(json.start_height)}`,
    `provider must be one of ${HEIGHT_PROVIDERS.join('|')}`);
  if (heightKeys.length === 1 && heightKeys[0] === 'absolute') {
    check(Number.isInteger(json.start_height.absolute),
      `${entry.rel}: start_height.absolute = ${json.start_height.absolute} (an integer Y)`);
  }

  // references
  const biomes = json.biomes;
  const biomeOk = isForeign(biomes) || tagExists(biomes, 'worldgen/biome')
    || idExists(biomes, 'worldgen/biome');
  check(biomeOk, `${entry.rel}: biomes = ${biomes} resolves (tag file or biome file)`);
  const poolOk = isForeign(json.start_pool) || idExists(json.start_pool, 'worldgen/template_pool');
  check(poolOk, `${entry.rel}: start_pool = ${json.start_pool} exists`);

  const footprint = footprintOf(id, json);
  console.log(`        ${id}: size=${size} max_distance_from_center=${distance}`
    + ` footprint=${footprint === null ? '?' : footprint} blocks`);
}

// ------------------------------------------------------------------ 3. structure sets + density
console.log('');
console.log('3. worldgen/structure_set: spacing >= separation > 0, and spacing covers the footprint');
for (const entry of byRegistry('worldgen/structure_set')) {
  const json = entry.json;
  const allowed = new Set(['structures', 'placement']);
  const unknown = Object.keys(json).filter((key) => !allowed.has(key));
  check(unknown.length === 0, `${entry.rel}: no unknown field`,
    unknown.length ? `unknown: ${unknown.join(', ')}` : 'structures + placement');
  const placement = json.placement || {};
  const unknownPlacement = Object.keys(placement).filter((key) => !PLACEMENT_KEYS.includes(key));
  check(unknownPlacement.length === 0, `${entry.rel}: placement has no unknown field`,
    unknownPlacement.length ? `unknown: ${unknownPlacement.join(', ')}` : Object.keys(placement).join(', '));
  check(PLACEMENT_TYPES.includes(placement.type),
    `${entry.rel}: placement.type = ${placement.type}`);
  const spacing = placement.spacing;
  const separation = placement.separation;
  check(Number.isInteger(spacing) && spacing >= SPACING_RANGE[0] && spacing <= SPACING_RANGE[1],
    `${entry.rel}: spacing = ${spacing} (allowed ${SPACING_RANGE[0]}..${SPACING_RANGE[1]} chunks)`);
  check(Number.isInteger(separation) && separation > 0 && separation < spacing,
    `${entry.rel}: separation = ${separation} (0 < separation < spacing)`);
  check(Number.isInteger(placement.salt) && placement.salt >= 0 && placement.salt <= 2147483647,
    `${entry.rel}: salt = ${placement.salt} (a non-negative int)`);
  // tarkovscav:wasteland_spread carries the dense pair as well: the same two rules, and the dense pair
  // is what the wasteland uses, so the footprint check below uses it.
  let denseSpacing = null;
  if (placement.type === 'tarkovscav:wasteland_spread') {
    denseSpacing = placement.dense_spacing;
    check(Number.isInteger(denseSpacing) && denseSpacing >= SPACING_RANGE[0]
      && denseSpacing <= SPACING_RANGE[1],
      `${entry.rel}: dense_spacing = ${denseSpacing} (allowed ${SPACING_RANGE[0]}..${SPACING_RANGE[1]})`);
    check(Number.isInteger(placement.dense_separation) && placement.dense_separation > 0
      && placement.dense_separation < denseSpacing,
      `${entry.rel}: dense_separation = ${placement.dense_separation} (0 < dense_separation < dense_spacing)`);
  }
  const effectiveSpacing = denseSpacing === null ? spacing : denseSpacing;
  for (const selection of json.structures || []) {
    const structureOk = isForeign(selection.structure) || idExists(selection.structure, 'worldgen/structure');
    check(structureOk, `${entry.rel}: structure ${selection.structure} exists`);
    check(Number.isInteger(selection.weight) && selection.weight > 0,
      `${entry.rel}: ${selection.structure} weight = ${selection.weight} (> 0)`);
    const structureEntry = byRegistry('worldgen/structure')
      .find((f) => f.id === selection.structure);
    const footprint = structureEntry ? footprintOf(selection.structure, structureEntry.json) : null;
    if (footprint !== null && Number.isInteger(effectiveSpacing)) {
      // spacing and separation are in CHUNKS, so the block comparison is spacing * 16.
      check(effectiveSpacing * 16 >= footprint,
        `${entry.rel}: ${denseSpacing === null ? 'spacing' : 'dense_spacing'} ${effectiveSpacing} chunks =`
        + ` ${effectiveSpacing * 16} blocks >= ${selection.structure} footprint ${footprint} blocks`);
      if (denseSpacing !== null && structureEntry && structureEntry.json.size === 1
        && footprint >= 128) {
        // A 138-wide strongpoint at 138 chunks would be wall-to-wall; the shipped 192 is kept on purpose.
        check(effectiveSpacing >= footprint,
          `${entry.rel}: ${selection.structure} keeps the literal dense_spacing >= footprint`
          + ` (${effectiveSpacing} >= ${footprint}) - a 138-wide landmark is deliberately rare`);
      }
    } else if (footprint === null) {
      console.log(`        ${entry.rel}: footprint of ${selection.structure} unknown - density not checked`);
    }
  }
}

// ------------------------------------------------------------------ 4. the dimension chain
console.log('');
console.log('4. dimension + dimension_type + noise_settings + biome');
const dimensionTypeIds = new Set([...byRegistry('dimension_type')].map((f) => f.id));
for (const entry of byRegistry('dimension_type')) {
  const json = entry.json;
  const unknown = Object.keys(json).filter((key) => !DIMENSION_TYPE_KEYS.includes(key));
  check(unknown.length === 0, `${entry.rel}: no unknown field`,
    unknown.length ? `unknown: ${unknown.join(', ')}` : `${Object.keys(json).length} keys`);
  check(Number.isInteger(json.min_y) && json.min_y % 16 === 0,
    `${entry.rel}: min_y = ${json.min_y} (multiple of 16)`);
  check(Number.isInteger(json.height) && json.height % 16 === 0 && json.height > 0 && json.height <= 4064,
    `${entry.rel}: height = ${json.height} (positive multiple of 16, <= 4064)`);
  check(Number.isInteger(json.logical_height) && json.logical_height > 0
    && json.logical_height <= json.height,
    `${entry.rel}: logical_height = ${json.logical_height} (0 < logical_height <= height)`);
  check(typeof json.coordinate_scale === 'number' && json.coordinate_scale > 0,
    `${entry.rel}: coordinate_scale = ${json.coordinate_scale} (> 0)`);
  check(typeof json.ambient_light === 'number' && json.ambient_light >= 0 && json.ambient_light <= 1,
    `${entry.rel}: ambient_light = ${json.ambient_light} (0..1)`);
  check(Number.isInteger(json.monster_spawn_block_light_limit)
    && json.monster_spawn_block_light_limit >= 0 && json.monster_spawn_block_light_limit <= 15,
    `${entry.rel}: monster_spawn_block_light_limit = ${json.monster_spawn_block_light_limit} (0..15)`);
  for (const key of ['ultrawarm', 'natural', 'has_skylight', 'has_ceiling', 'piglin_safe', 'bed_works',
    'respawn_anchor_works', 'has_raids']) {
    check(typeof json[key] === 'boolean', `${entry.rel}: ${key} = ${json[key]} (boolean)`);
  }
  for (const key of ['sky_color', 'fog_color', 'water_color', 'water_fog_color']) {
    const value = json.effects ? json.effects[key] : undefined;
    check(Number.isInteger(value) && value >= 0 && value <= 0xFFFFFF,
      `${entry.rel}: effects.${key} = ${value} (0..16777215)`);
  }
}

for (const entry of byRegistry('dimension')) {
  const json = entry.json;
  check(json.type === undefined || isForeign(json.type) || dimensionTypeIds.has(json.type),
    `${entry.rel}: type = ${json.type} exists`);
  const generator = json.generator || {};
  check(generator.type === 'minecraft:noise', `${entry.rel}: generator.type = ${generator.type}`);
  const source = generator.biome_source || {};
  if (source.type === 'minecraft:fixed') {
    check(isForeign(source.biome) || idExists(source.biome, 'worldgen/biome'),
      `${entry.rel}: biome_source.biome = ${source.biome} exists`);
  }
  check(isForeign(generator.settings) || idExists(generator.settings, 'worldgen/noise_settings'),
    `${entry.rel}: generator.settings = ${generator.settings} exists`);
  const overrides = (json.structures && json.structures.structures) || {};
  for (const [setId, placement] of Object.entries(overrides)) {
    check(isForeign(setId) || idExists(setId, 'worldgen/structure_set'),
      `${entry.rel}: structure override ${setId} exists`);
    check(PLACEMENT_TYPES.includes(placement.type),
      `${entry.rel}: ${setId} override type = ${placement.type}`);
    check(Number.isInteger(placement.spacing) && placement.spacing >= SPACING_RANGE[0]
      && placement.spacing <= SPACING_RANGE[1],
      `${entry.rel}: ${setId} override spacing = ${placement.spacing}`);
    check(Number.isInteger(placement.separation) && placement.separation > 0
      && placement.separation < placement.spacing,
      `${entry.rel}: ${setId} override separation = ${placement.separation}`);
    check(Number.isInteger(placement.salt), `${entry.rel}: ${setId} override salt = ${placement.salt}`);
    const setEntry = byRegistry('worldgen/structure_set').find((f) => f.id === setId);
    if (setEntry) {
      for (const selection of setEntry.json.structures || []) {
        const structureEntry = byRegistry('worldgen/structure')
          .find((f) => f.id === selection.structure);
        const footprint = structureEntry
          ? footprintOf(selection.structure, structureEntry.json) : null;
        if (footprint !== null) {
          check(placement.spacing * 16 >= footprint,
            `${entry.rel}: ${setId} override spacing ${placement.spacing} chunks =`
            + ` ${placement.spacing * 16} blocks >= ${selection.structure} footprint ${footprint} blocks`);
        }
      }
    }
  }
}

for (const entry of byRegistry('worldgen/noise_settings')) {
  const json = entry.json;
  const unknown = Object.keys(json).filter((key) => !SETTINGS_KEYS.includes(key));
  check(unknown.length === 0, `${entry.rel}: no unknown field`,
    unknown.length ? `unknown: ${unknown.join(', ')}` : `${Object.keys(json).length} keys`);
  const noise = json.noise || {};
  for (const key of NOISE_SETTINGS_KEYS) {
    check(Number.isInteger(noise[key]), `${entry.rel}: noise.${key} = ${noise[key]} (integer)`);
  }
  check(Number.isInteger(noise.min_y) && noise.min_y % 16 === 0 && noise.min_y >= -2032,
    `${entry.rel}: noise.min_y = ${noise.min_y} (multiple of 16, >= -2032)`);
  check(Number.isInteger(noise.height) && noise.height % 16 === 0 && noise.height > 0
    && noise.min_y + noise.height <= 2032,
    `${entry.rel}: noise.height = ${noise.height} (multiple of 16, min_y + height <= 2032)`);
  check(noise.size_horizontal >= 1 && noise.size_horizontal <= 4,
    `${entry.rel}: noise.size_horizontal = ${noise.size_horizontal} (1..4)`);
  check(noise.size_vertical >= 1 && noise.size_vertical <= 4,
    `${entry.rel}: noise.size_vertical = ${noise.size_vertical} (1..4)`);
  check(Number.isInteger(json.sea_level), `${entry.rel}: sea_level = ${json.sea_level} (integer)`);
  check(typeof json.disable_mob_generation === 'boolean',
    `${entry.rel}: disable_mob_generation = ${json.disable_mob_generation} (boolean)`);
  check(typeof json.aquifers_enabled === 'boolean',
    `${entry.rel}: aquifers_enabled = ${json.aquifers_enabled} (boolean)`);
  check(json.default_block && typeof json.default_block.Name === 'string',
    `${entry.rel}: default_block.Name = ${json.default_block && json.default_block.Name}`);
  check(json.default_fluid && typeof json.default_fluid.Name === 'string',
    `${entry.rel}: default_fluid.Name = ${json.default_fluid && json.default_fluid.Name}`);
  const router = json.noise_router || {};
  const missingRouter = ROUTER_KEYS.filter((key) => !(key in router));
  check(missingRouter.length === 0, `${entry.rel}: noise_router has all ${ROUTER_KEYS.length} fields`,
    missingRouter.length ? `missing: ${missingRouter.join(', ')}` : '');
  const reference = (value) => {
    if (typeof value === 'string') return value;
    if (value && typeof value === 'object') {
      if (typeof value.type === 'string' && value.type.startsWith('minecraft:')) return null;
      for (const item of Object.values(value)) {
        const found = reference(item);
        if (found) return found;
      }
    }
    return null;
  };
  const routerId = reference(router);
  if (routerId && !isForeign(routerId)) {
    check(idExists(routerId, 'worldgen/density_function', 'worldgen/noise'),
      `${entry.rel}: router reference ${routerId} exists`);
  }
  // every tarkovscav density function the router names must exist as a file
  const text = JSON.stringify(json);
  for (const match of new Set([...text.matchAll(/"tarkovscav:([a-z0-9_/]+)"/g)].map((m) => `tarkovscav:${m[1]}`))) {
    check(idExists(match, 'worldgen/density_function', 'worldgen/noise', 'worldgen/biome'),
      `${entry.rel}: referenced id ${match} exists`);
  }
}

for (const entry of byRegistry('worldgen/biome')) {
  const json = entry.json;
  check(typeof json.has_precipitation === 'boolean',
    `${entry.rel}: has_precipitation = ${json.has_precipitation} (boolean)`);
  check(typeof json.temperature === 'number' && json.temperature >= -2 && json.temperature <= 2,
    `${entry.rel}: temperature = ${json.temperature} (-2..2)`);
  check(typeof json.downfall === 'number' && json.downfall >= 0 && json.downfall <= 1,
    `${entry.rel}: downfall = ${json.downfall} (0..1)`);
  check(Array.isArray(json.features) && json.features.length === 11,
    `${entry.rel}: features has 11 decoration steps`, `${json.features && json.features.length}`);
  const carvers = json.carvers;
  check(carvers && typeof carvers === 'object' && !Array.isArray(carvers),
    `${entry.rel}: carvers is an object (1.20.1 wants {"air": [...], "liquid": [...]})`,
    JSON.stringify(carvers));
  const unknownCategories = Object.keys(json.spawners || {}).filter((k) => !MOB_CATEGORIES.includes(k));
  check(unknownCategories.length === 0, `${entry.rel}: spawner categories are real`,
    unknownCategories.length ? unknownCategories.join(', ') : MOB_CATEGORIES.length + ' categories');
  for (const [category, list] of Object.entries(json.spawners || {})) {
    for (const spawner of list) {
      check(isForeign(spawner.type) || mobIds.has(spawner.type),
        `${entry.rel}: spawner ${spawner.type} in ${category} is a registered mob`);
      check(Number.isInteger(spawner.weight) && spawner.weight > 0,
        `${entry.rel}: ${spawner.type} weight = ${spawner.weight} (> 0)`);
      check(Number.isInteger(spawner.minCount) && Number.isInteger(spawner.maxCount)
        && spawner.minCount >= 1 && spawner.maxCount >= spawner.minCount,
        `${entry.rel}: ${spawner.type} count = ${spawner.minCount}..${spawner.maxCount}`);
    }
  }
  for (const [id, cost] of Object.entries(json.spawn_costs || {})) {
    check(isForeign(id) || mobIds.has(id), `${entry.rel}: spawn_costs ${id} is a registered mob`);
    check(typeof cost.energy_budget === 'number' && cost.energy_budget >= 0
      && typeof cost.charge === 'number' && cost.charge >= 0,
      `${entry.rel}: spawn_costs ${id} = energy_budget ${cost.energy_budget}, charge ${cost.charge}`);
  }
  for (const step of json.features || []) {
    for (const id of step) {
      check(isForeign(id) || idExists(id, 'worldgen/placed_feature'),
        `${entry.rel}: placed feature ${id} exists`);
    }
  }
}

// ------------------------------------------------------------------ 5. features + pools
console.log('');
console.log('5. configured/placed features and template pools');
for (const entry of byRegistry('worldgen/placed_feature')) {
  const json = entry.json;
  check(typeof json.feature === 'string', `${entry.rel}: feature = ${json.feature}`);
  if (typeof json.feature === 'string' && !isForeign(json.feature)) {
    check(idExists(json.feature, 'worldgen/configured_feature', 'worldgen/placed_feature'),
      `${entry.rel}: ${json.feature} exists as a configured or placed feature`);
  }
  check(Array.isArray(json.placement) && json.placement.length > 0,
    `${entry.rel}: placement has ${json.placement && json.placement.length} modifier(s)`);
  for (const modifier of json.placement || []) {
    check(typeof modifier.type === 'string' && modifier.type.startsWith('minecraft:'),
      `${entry.rel}: placement modifier type = ${modifier.type}`);
  }
}
for (const entry of byRegistry('worldgen/configured_feature')) {
  const json = entry.json;
  check(typeof json.type === 'string', `${entry.rel}: type = ${json.type}`);
  if (json.type === 'minecraft:random_patch') {
    const inner = json.config && json.config.feature;
    check(typeof inner === 'string' && (isForeign(inner) || idExists(inner, 'worldgen/placed_feature')),
      `${entry.rel}: random_patch inner feature ${inner} is a placed feature`);
    check(Number.isInteger(json.config.tries) && json.config.tries > 0,
      `${entry.rel}: tries = ${json.config.tries} (> 0)`);
  }
  if (json.type === 'minecraft:simple_block') {
    check(json.config && json.config.to_place && json.config.to_place.state
      && typeof json.config.to_place.state.Name === 'string',
      `${entry.rel}: simple_block places ${json.config && json.config.to_place
        && json.config.to_place.state && json.config.to_place.state.Name}`);
  }
}
for (const entry of byRegistry('worldgen/template_pool')) {
  const json = entry.json;
  check(json.name === entry.id,
    `${entry.rel}: name = ${json.name} matches its path`);
  check(Array.isArray(json.elements) && json.elements.length > 0,
    `${entry.rel}: ${json.elements && json.elements.length} element(s)`);
  for (const element of json.elements || []) {
    const inner = element.element || {};
    check(POOL_ELEMENT_TYPES.includes(inner.element_type),
      `${entry.rel}: element_type = ${inner.element_type}`);
    if (typeof inner.location === 'string' && !isForeign(inner.location)) {
      const [namespace, locationPath] = inner.location.split(':');
      const nbt = path.join(DATA, namespace, 'structures', `${locationPath}.nbt`);
      check(fs.existsSync(nbt), `${entry.rel}: ${inner.location} has a structure .nbt`,
        path.relative(ROOT, nbt));
    }
    if (element.fallback !== undefined) {
      check(isForeign(element.fallback) || idExists(element.fallback, 'worldgen/template_pool'),
        `${entry.rel}: fallback ${element.fallback} exists`);
    }
    if (typeof inner.projection === 'string') {
      check(['rigid', 'terrain_matching'].includes(inner.projection),
        `${entry.rel}: projection = ${inner.projection}`);
    }
  }
}

// ------------------------------------------------------------------ 6. recipes and the other registries
console.log('');
console.log('6. recipes, loot tables, tags and biome modifiers');
for (const entry of byRegistry('recipes')) {
  const json = entry.json;
  check(typeof json.type === 'string' && json.type.startsWith('minecraft:'),
    `${entry.rel}: type = ${json.type}`);
  check(json.result && typeof json.result.item === 'string',
    `${entry.rel}: result.item = ${json.result && json.result.item}`);
  const result = json.result && json.result.item;
  if (result && !isForeign(result)) {
    check(itemIds.has(result), `${entry.rel}: result item ${result} is registered`);
  }
  const keyItems = [];
  for (const value of Object.values(json.key || {})) {
    if (value && typeof value.item === 'string') keyItems.push(value.item);
    if (value && typeof value.tag === 'string') keyItems.push(value.tag);
  }
  for (const id of keyItems) {
    if (!id.startsWith('tarkovscav:')) continue;
    check(itemIds.has(id), `${entry.rel}: ingredient ${id} is registered`);
  }
  check(json.type !== 'minecraft:crafting_shaped'
    || (Array.isArray(json.pattern) && json.pattern.length >= 1),
  `${entry.rel}: shaped recipe has a pattern`);
}
for (const entry of byRegistry('forge/biome_modifier')) {
  const json = entry.json;
  check(typeof json.type === 'string', `${entry.rel}: type = ${json.type}`);
  for (const spawner of json.spawners || []) {
    check(isForeign(spawner.type) || mobIds.has(spawner.type),
      `${entry.rel}: spawner ${spawner.type} is a registered mob`);
  }
}
for (const entry of files.filter((f) => f.registry.startsWith('tags/') && f.json)) {
  check(Array.isArray(entry.json.values), `${entry.rel}: values is a list`,
    `${entry.json.values && entry.json.values.length} entr(ies)`);
  // A "#minecraft:<tag>" reference must be a tag that exists in 1.20.1. This is the check that would
  // have caught `#minecraft:undead` in tarkovscav:faction_village_hostile before a player saw it.
  if (entry.registry === 'tags/entity_types') {
    for (const value of entry.json.values || []) {
      if (typeof value !== 'string' || !value.startsWith('#minecraft:')) continue;
      const name = value.slice('#minecraft:'.length);
      check(VANILLA_ENTITY_TYPE_TAGS.includes(name),
        `${entry.rel}: ${value} exists in 1.20.1`,
        VANILLA_ENTITY_TYPE_TAGS.includes(name) ? '' : `not one of: ${VANILLA_ENTITY_TYPE_TAGS.join(', ')}`);
    }
    const seen = new Set();
    const duplicates = (entry.json.values || []).filter((v) => (seen.has(v) ? true : (seen.add(v), false)));
    check(duplicates.length === 0, `${entry.rel}: no duplicate entry`,
      duplicates.length ? duplicates.join(', ') : `${seen.size} distinct`);
  }
}

// ------------------------------------------------------------------ summary
console.log('');
console.log(`=== ${checks} fact(s) checked, ${failures} failure(s) ===`);
if (failures > 0) {
  console.log('the datapack would not load cleanly: fix the fields named above');
  process.exit(1);
}
console.log('every shipped datapack JSON is inside its codec ranges and every reference resolves');
