// Generates the DERIVED worldgen JSON of the urban-wasteland dimension, so the terrain file is provably
// vanilla-derived instead of hand-typed:
//
//   data/tarkovscav/worldgen/noise_settings/urban_wasteland.json
//   data/tarkovscav/worldgen/density_function/urban_wasteland/depth.json
//   data/tarkovscav/worldgen/density_function/urban_wasteland/sloped_cheese.json
//
//   node tools/make_wasteland_dimension.js
//
// Why a generator: the vanilla overworld noise_settings is ~110 KB of nested density expressions. Copying
// it by hand would be a transcription exercise with no upside; this script reads the real file out of the
// mapped client jar, deep-clones the two expressions that decide the terrain, and applies exactly the
// changes the wasteland needs - so "what changed versus vanilla" is this list and nothing else.
//
// The changes (and nothing else):
//   1. RELIEF. vanilla depth = y_clamped_gradient(1.5 @ -64 -> -1.5 @ 320) + overworld/offset, and the
//      offset spline is the entire hill/valley signal. The wasteland keeps the y gradient (so the average
//      surface height is unchanged) and scales the offset spline by 0.35 - gentle terrain with hills at
//      about a third of overworld amplitude, which is what a city needs to sit on.
//   2. The router's depth entry, initial_density_without_jaggedness and final_density are re-pointed at
//      the tarkovscav:urban_wasteland/* clones (only the ids change; the expressions are vanilla's).
//   3. SEA LEVEL -63: aquifers and the surface flood from the sea level down, so a sea level below the
//      bedrock floor (-64) means no oceans, no lakes and no water in caves - a dry wasteland. The default
//      fluid stays water (unused in practice).
//   4. SURFACE RULE replaced by a dead-urban palette: coarse_dirt / gravel / andesite at the surface,
//      tuff / stone underneath, no grass anywhere.
//   5. ORE VEINS off (ore_veins_enabled=false, the three vein fields are constant 0), because the ore
//      veinifier only paints copper/iron veins that a dead city does not want.
//   6. Everything else - aquifers, the cave router (noodle), disable_mob_generation=false, default_block
//      stone, noise size, spawn_target - is byte-identical to vanilla's overworld.json.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const DATA = path.join(ROOT, 'src', 'main', 'resources', 'data', 'tarkovscav', 'worldgen');
const ENTRY = 'data/minecraft/worldgen/noise_settings/overworld.json';

const RELIEF_SCALE = 0.35;
const SEA_LEVEL = -63;

// ------------------------------------------------------------------ find a jar that has the vanilla file
function candidateJars() {
  const list = [];
  if (process.env.MC_CLIENT_JAR) {
    list.push(process.env.MC_CLIENT_JAR);
  }
  list.push(path.join('D:', 'deepseek', 'GirlsFrontline', '.gradle-home', 'caches', 'forge_gradle',
    'minecraft_repo', 'versions', '1.20.1', 'client-extra.jar'));
  const libs = path.join(ROOT, 'tools', 'spike', 'citysave', 'server', 'libraries');
  if (fs.existsSync(libs)) {
    for (const dir of fs.readdirSync(libs, { withFileTypes: true })) {
      if (!dir.isDirectory()) continue;
      const walk = (base) => {
        for (const item of fs.readdirSync(base, { withFileTypes: true })) {
          const full = path.join(base, item.name);
          if (item.isDirectory()) walk(full);
          else if (item.name.endsWith('.jar')) list.push(full);
        }
      };
      walk(path.join(libs, dir.name));
    }
  }
  return list;
}

// ------------------------------------------------------------------ a minimal zip reader (stored + deflate)
function zipEntries(buf) {
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) return null;
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    entries.push({ name: buf.toString('utf8', off + 46, off + 46 + nameLen), method, compSize, localOff });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}
function entryData(buf, entry) {
  const lo = entry.localOff;
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const start = lo + 30 + nameLen + extraLen;
  const raw = buf.subarray(start, start + entry.compSize);
  return entry.method === 0 ? raw : zlib.inflateRawSync(raw);
}

function readVanilla() {
  const tried = [];
  for (const jar of candidateJars()) {
    if (!fs.existsSync(jar)) { tried.push(`${jar} (missing)`); continue; }
    let buf;
    try { buf = fs.readFileSync(jar); } catch (error) { tried.push(`${jar} (${error.message})`); continue; }
    const entries = zipEntries(buf);
    if (!entries) { tried.push(`${jar} (not a zip)`); continue; }
    const entry = entries.find((e) => e.name === ENTRY);
    if (!entry) { tried.push(`${jar} (no ${ENTRY})`); continue; }
    console.log(`reading ${ENTRY} from ${jar}`);
    return JSON.parse(entryData(buf, entry).toString('utf8'));
  }
  console.error('could not find the vanilla overworld noise settings. Tried:');
  for (const line of tried) console.error('  ' + line);
  console.error('set MC_CLIENT_JAR to a mapped client jar (or a server jar with data/minecraft) and retry.');
  process.exit(1);
}

/** Deep-clone `value` and replace every occurrence of the exact string `from` with `to`. */
function reId(value, from, to) {
  if (typeof value === 'string') return value === from ? to : value;
  if (Array.isArray(value)) return value.map((item) => reId(item, from, to));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [key, item] of Object.entries(value)) out[key] = reId(item, from, to);
    return out;
  }
  return value;
}

const MY_DEPTH = 'tarkovscav:urban_wasteland/depth';
const MY_CHEESE = 'tarkovscav:urban_wasteland/sloped_cheese';
const VANILLA_DEPTH = 'minecraft:overworld/depth';
const VANILLA_CHEESE = 'minecraft:overworld/sloped_cheese';

// ------------------------------------------------------------------ the three derived files
const vanilla = readVanilla();
const router = vanilla.noise_router;

const myDepth = {
  type: 'minecraft:add',
  argument1: {
    type: 'minecraft:y_clamped_gradient',
    from_value: 1.5,
    from_y: -64,
    to_value: -1.5,
    to_y: 320,
  },
  argument2: {
    type: 'minecraft:mul',
    argument1: RELIEF_SCALE,
    argument2: 'minecraft:overworld/offset',
  },
};

// sloped_cheese: vanilla's file, with the depth id swapped. (Read from the same jar.)
function readDensityFunction(jarEntry) {
  for (const jar of candidateJars()) {
    if (!fs.existsSync(jar)) continue;
    const buf = fs.readFileSync(jar);
    const entries = zipEntries(buf);
    if (!entries) continue;
    const entry = entries.find((e) => e.name === jarEntry);
    if (!entry) continue;
    return JSON.parse(entryData(buf, entry).toString('utf8'));
  }
  console.error(`could not read ${jarEntry} from any candidate jar`);
  process.exit(1);
}

const slopedCheese = reId(
  readDensityFunction('data/minecraft/worldgen/density_function/overworld/sloped_cheese.json'),
  VANILLA_DEPTH, MY_DEPTH);

// final_density / initial_density_without_jaggedness keep vanilla's expression shape; only the two ids
// they reference move to the low-relief clones.
const finalDensity = reId(router.final_density, VANILLA_CHEESE, MY_CHEESE);
const initialDensity = reId(router.initial_density_without_jaggedness, VANILLA_DEPTH, MY_DEPTH);

const surfaceRule = {
  type: 'minecraft:sequence',
  sequence: [
    {
      type: 'minecraft:condition',
      if_true: {
        type: 'minecraft:vertical_gradient',
        random_name: 'minecraft:bedrock_floor',
        true_at_and_below: { above_bottom: 0 },
        false_at_and_above: { above_bottom: 5 },
      },
      then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:bedrock' } },
    },
    {
      type: 'minecraft:condition',
      if_true: { type: 'minecraft:above_preliminary_surface' },
      then_run: {
        type: 'minecraft:sequence',
        sequence: [
          {
            type: 'minecraft:condition',
            if_true: {
              type: 'minecraft:stone_depth',
              offset: 0,
              surface_type: 'floor',
              add_surface_depth: false,
              secondary_depth_range: 0,
            },
            then_run: {
              type: 'minecraft:sequence',
              sequence: [
                {
                  type: 'minecraft:condition',
                  if_true: {
                    type: 'minecraft:noise_threshold',
                    noise: 'minecraft:surface',
                    min_threshold: -0.35,
                    max_threshold: 0.35,
                  },
                  then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:coarse_dirt' } },
                },
                {
                  type: 'minecraft:condition',
                  if_true: {
                    type: 'minecraft:noise_threshold',
                    noise: 'minecraft:surface',
                    min_threshold: -0.75,
                    max_threshold: -0.35,
                  },
                  then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:gravel' } },
                },
                { type: 'minecraft:block', result_state: { Name: 'minecraft:andesite' } },
              ],
            },
          },
          {
            type: 'minecraft:condition',
            if_true: {
              type: 'minecraft:stone_depth',
              offset: 0,
              surface_type: 'floor',
              add_surface_depth: true,
              secondary_depth_range: 0,
            },
            then_run: {
              type: 'minecraft:sequence',
              sequence: [
                {
                  type: 'minecraft:condition',
                  if_true: {
                    type: 'minecraft:noise_threshold',
                    noise: 'minecraft:surface',
                    min_threshold: -0.15,
                    max_threshold: 0.25,
                  },
                  then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:tuff' } },
                },
                { type: 'minecraft:block', result_state: { Name: 'minecraft:stone' } },
              ],
            },
          },
          {
            type: 'minecraft:condition',
            if_true: {
              type: 'minecraft:stone_depth',
              offset: 0,
              surface_type: 'ceiling',
              add_surface_depth: false,
              secondary_depth_range: 0,
            },
            then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:stone' } },
          },
        ],
      },
    },
  ],
};

const settings = JSON.parse(JSON.stringify(vanilla));
settings.noise_router.depth = MY_DEPTH;
settings.noise_router.initial_density_without_jaggedness = initialDensity;
settings.noise_router.final_density = finalDensity;
settings.noise_router.vein_toggle = 0.0;
settings.noise_router.vein_ridged = 0.0;
settings.noise_router.vein_gap = 0.0;
settings.ore_veins_enabled = false;
settings.sea_level = SEA_LEVEL;
settings.surface_rule = surfaceRule;

function writeJson(rel, value) {
  const file = path.join(DATA, rel);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
  console.log(`wrote ${path.relative(ROOT, file)}  ${fs.statSync(file).size} bytes`);
}

writeJson(path.join('density_function', 'urban_wasteland', 'depth.json'), myDepth);
writeJson(path.join('density_function', 'urban_wasteland', 'sloped_cheese.json'), slopedCheese);
writeJson(path.join('noise_settings', 'urban_wasteland.json'), settings);

console.log('');
console.log(`relief scale on minecraft:overworld/offset = ${RELIEF_SCALE}`);
console.log(`sea_level = ${SEA_LEVEL} (bedrock floor is min_y = ${settings.noise.min_y})`);
console.log(`aquifers_enabled = ${settings.aquifers_enabled}, ore_veins_enabled = ${settings.ore_veins_enabled}`);
console.log(`disable_mob_generation = ${settings.disable_mob_generation}, default_block = ${settings.default_block.Name}`);
console.log(`surface palette = coarse_dirt / gravel / andesite over tuff / stone`);
