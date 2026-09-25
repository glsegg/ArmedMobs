// M1 of the city work: merge the raw surveys and the preset dumps into ONE report,
// tools/spike/work/city_survey.json, and print the human-readable summary.
//
//   node tools/spike/work/build_city_survey.js
//
// Inputs (all produced by read-only tools over a COPY of the save):
//   city_survey_city1_raw.json  CitySurvey on the district that actually contains the user's edits
//   city_survey_box1.json       CitySurvey on the box named in the user's config (spawn.cityRegions[0])
//   preset_city_*.txt           StructureDump of the mod's own four city presets
'use strict';
const fs = require('fs');
const path = require('path');

const work = __dirname;
const read = (name) => fs.readFileSync(path.join(work, name), 'utf8');
const json = (name) => JSON.parse(read(name));

const district = json('city_survey_city1_raw.json');
const configBox = json('city_survey_box1.json');

// The preset palettes: the first column of every "  minecraft:..." line, before the x-count.
function presetPalette(name) {
  const text = read(`preset_${name}.txt`);
  const header = /size=\[(\d+), (\d+), (\d+)\]/.exec(text);
  const blocks = /paletteEntries=(\d+) blocksPlaced=(\d+) blocksWithNbt=(\d+)/.exec(text);
  const palette = {};
  const section = text.split('=== palette')[1] || '';
  for (const line of section.split(/\r?\n/)) {
    const m = /^\s{2}(\S+?)\s+x(\d+)\s*$/.exec(line);
    if (m) palette[m[1]] = Number(m[2]);
  }
  return {
    file: `data/tarkovscav/structures/${name}.nbt`,
    size: header ? [Number(header[1]), Number(header[2]), Number(header[3])] : null,
    paletteEntries: blocks ? Number(blocks[1]) : null,
    blocksPlaced: blocks ? Number(blocks[2]) : null,
    blocksWithNbt: blocks ? Number(blocks[3]) : null,
    palette,
  };
}
const presets = ['city_small', 'city_a', 'city_b', 'city_c'].map(presetPalette);

// Which of the district's blocks the presets NEVER place: that set is the user's own hand work. Terrain
// blocks are filtered out - the box contains a slice of the world, and "the world is not in the preset"
// is not a finding.
const NATURAL = /^(minecraft:(air|cave_air|void_air|stone|deepslate|granite|diorite|andesite|tuff|calcite|dripstone_block|bedrock|dirt|coarse_dirt|rooted_dirt|grass_block|podzol|mycelium|moss_block|mud|sand|red_sand|sandstone|gravel|clay|snow|snow_block|ice|packed_ice|blue_ice|water|lava|magma_block|obsidian|crying_obsidian|.*_ore|.*_leaves|.*_log|.*_wood|moss_carpet|hanging_roots|big_dripleaf|big_dripleaf_stem|small_dripleaf|lily_pad|seagrass|tall_seagrass|kelp|kelp_plant|.*_coral.*|grass|short_grass|tall_grass|fern|large_fern|dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|sunflower|lilac|rose_bush|peony|sugar_cane|bamboo|cactus|dead_bush|pumpkin|melon|sweet_berry_bush|cocoa|wheat|carrots|potatoes|beetroots|.*_mushroom.*|infested_.*|sculk|sculk_vein|sculk_catalyst|sculk_shrieker|sculk_sensor|amethyst_block|budding_amethyst|amethyst_cluster|.*_amethyst_bud|pointed_dripstone|soul_sand|soul_soil|basalt|smooth_basalt|blackstone|netherrack|glowstone|end_stone|chorus_plant|chorus_flower|spore_blossom|cave_vines|cave_vines_plant|weeping_vines.*|twisting_vines.*|warped_roots|crimson_roots|nether_sprouts|sea_pickle|prismarine.*|sea_lantern|sponge|wet_sponge|powder_snow|bone_block|suspicious_.*|.*_froglight|decorated_pot|flower_pot|torchflower|pitcher_plant|pink_petals|bubble_column|glow_lichen|vine|azalea|flowering_azalea|mossy_cobblestone|oak_leaves|oak_log|oak_wood))$/;
const presetBlocks = new Set();
for (const p of presets) Object.keys(p.palette).forEach((b) => presetBlocks.add(b.split('[')[0]));
const districtBlocks = Object.keys(district.paletteOccurrences || {}).map((b) => b.split('[')[0]);
const userOnlyBlocks = [...new Set(districtBlocks)].filter((b) => !presetBlocks.has(b)
  && !NATURAL.test(b)).sort();

// A cluster whose footprint is far larger than a building is the district enclosure plus whatever touches
// it (the detector flood-fills 4-connected wall columns, so touching walls merge): labelled, not hidden.
const classify = (b) => {
  const bb = b.bounds;
  const area = (bb.x2 - bb.x1 + 1) * (bb.z2 - bb.z1 + 1);
  const floors = b.floorLevels || [];
  const spacing = floors.length > 2 ? floors[1] - floors[0] : 0;
  if (area > 1000) { return 'enclosure-or-merged (touching walls merged into one cluster)'; }
  return spacing === 4 ? 'building (4-block storeys)' : 'building';
};
const buildings = district.buildings.map((b) => ({ ...b, kind: classify(b) }));

const report = {
  generatedBy: 'tools/spike/citysave/CitySurvey.java (+ StructureDump.java), read-only over a COPY of the save',
  save: 'C:\\TESTv3\\.minecraft\\versions\\1.20.1-Forge_47.4.3\\saves\\塔科夫城市-可编辑',
  copyUsed: 'tools/spike/work/citycopy (region/*.mca copied out of the live save; the save was never written)',
  configBox: {
    source: 'spawn.cityRegions[0] = "1|minecraft:overworld|-383 34 -132|-254 163 -3"',
    box: configBox.box,
    probedY: [-64, 320],
    buildingsFound: configBox.buildings.length,
    chunksRead: configBox.chunksRead,
    chunksAbsent: configBox.chunksAbsent,
    verdict: 'NOT the built area: every chunk decodes, and the box is pure terrain (stone/dirt/water/ore) '
      + 'at every height from -64 to 320 - zero builder blocks, zero buildings, zero block entities.',
    paletteOccurrences: configBox.paletteOccurrences,
  },
  realDistrict: {
    howFound: 'palette sweep of every region file (PaletteScan.java) for builder-only blocks '
      + '(tarkovscav weapons racks, deepslate_tiles, light_gray_concrete, gray_concrete, glass_pane, '
      + 'stripped_dark_oak_log), then 4-connected clustering of the chunks that contain them.',
    evidence: 'the four tarkovscav:creative_weapon_rack block entities are at 84,65,132 / 87,64,133 / '
      + '46,65,156 / 42,65,165 - the last one is the rack in the user\'s own log line '
      + '"[rack] unsupported item tarkovscav:he_grenade (on the rack at 46, 65, 156)".',
    allDistricts: [
      { name: 'city1 (THE user-edited one, surveyed below)', worldX: [32, 95], worldZ: [112, 175], chunks: 16, racks: 3 },
      { name: 'city2', worldX: [192, 255], worldZ: [112, 175], chunks: 16, racks: 0 },
      { name: 'city3', worldX: [352, 415], worldZ: [112, 175], chunks: 16, racks: 0 },
      { name: 'city4 (+ annex)', worldX: [512, 623], worldZ: [112, 207], chunks: 25, racks: 0 },
      { name: 'city5', worldX: [32, 95], worldZ: [320, 367], chunks: 12, racks: 0 },
      { name: 'city6', worldX: [192, 255], worldZ: [320, 367], chunks: 12, racks: 0 },
      { name: 'city7 area', worldX: [368, 463], worldZ: [320, 447], chunks: 19, racks: 0 },
      { name: 'north-east district', worldX: [192, 239], worldZ: [576, 623], chunks: 9, racks: 0 },
    ],
    surveyBox: district.box,
    platformTopY: district.platformTopY,
    surfaceLayers: district.surfaceLayers,
    streetFurniture: district.streetFurniture,
    streetFurnitureByY: district.streetFurnitureByY,
    coverClusters: district.coverClusters,
    buildings,
    blockEntities: district.blockEntities,
  },
  presets: {
    note: 'The mod\'s own four presets are single monolithic structures (one .nbt per city) and place NO '
      + 'block entity NBT at all (blocksWithNbt = 0). They use polished_andesite / gray_concrete / '
      + 'deepslate_tiles / stone_bricks; they contain NO terracotta and NO beds.',
    files: presets.map((p) => ({
      file: p.file, size: p.size, paletteEntries: p.paletteEntries,
      blocksPlaced: p.blocksPlaced, blocksWithNbt: p.blocksWithNbt,
    })),
  },
  userOnlyBlocksInDistrict: userOnlyBlocks,
  blockEntitiesToCarryInStructureNbt: district.blockEntities,
};

fs.writeFileSync(path.join(work, 'city_survey.json'), JSON.stringify(report, null, 2) + '\n');

// ------------------------------------------------------------------ human summary
const lines = [];
lines.push('=== M1: city survey (read-only, over a copy of the save) ===');
lines.push('');
lines.push('1) The box named in the config is EMPTY of builds');
lines.push(`   ${report.configBox.source}`);
lines.push(`   box ${JSON.stringify(configBox.box)} probed down to y=${-64} and up to y=320`);
lines.push(`   chunks ${configBox.chunksRead} read / ${configBox.chunksAbsent} absent, buildings found: ${configBox.buildings.length}`);
lines.push('   -> pure terrain. The district the user edited is NOT here.');
lines.push('');
lines.push('2) The real district (found by a palette sweep of all 25 region files)');
lines.push(`   city1: world x 32..95, z 112..175  (chunks 2..5 x 7..10)`);
lines.push('   proof: our creative weapon racks live there, including the very rack from the user\'s log');
lines.push('   line "[rack] unsupported item tarkovscav:he_grenade (on the rack at 46, 65, 156)".');
lines.push('');
lines.push('   street/platform: the district sits on a flat platform whose top is y=63');
const layers = district.surfaceLayers || {};
for (const [y, hist] of Object.entries(layers)) {
  lines.push(`     ${y} surface: ${top(hist, 6)}`);
}
lines.push('');
lines.push(`   buildings found: ${buildings.length}`);
for (const b of buildings) {
  const bb = b.bounds;
  const floors = b.floorLevels;
  lines.push(`   - ${b.id} [${b.kind}]: x[${bb.x1}..${bb.x2}] y[${bb.y1}..${bb.y2}] z[${bb.z1}..${bb.z2}]`
    + `  (${bb.x2 - bb.x1 + 1}x${bb.y2 - bb.y1 + 1}x${bb.z2 - bb.z1 + 1}, ${b.columns} wall columns)`);
  lines.push(`       floor Y levels: ${floors.join(', ')}`
    + (floors.length > 2 ? `  -> ${floors[1] - floors[0]} blocks per storey` : ''));
  lines.push(`       walls: ${top(b.walls, 4)}`);
  lines.push(`       roof : ${top(b.roof, 3)}`);
  lines.push(`       slab : ${top(b.floorSlabs, 3)}`);
  lines.push(`       ground floor interior: ${top(b.groundFloorInterior, 6)}`);
  const bes = (b.blockEntities || []);
  lines.push(`       block entities: ${bes.length ? bes.join(' ') : '(none)'}`);
}
lines.push('');
lines.push('2b) Cover / street furniture (the user: "增加了许多掩体")');
lines.push(`   band: y${district.platformTopY + 2}..${district.platformTopY + 6} (the street is y${district.platformTopY + 1}),`);
lines.push('   counted only OUTSIDE every wall column, so building material is not miscounted as cover.');
lines.push(`   blocks: ${top(district.streetFurniture, 8)}`);
lines.push(`   per Y : ${Object.entries(district.streetFurnitureByY).map(([y, n]) => `y${y}=${n}`).join(' ')}`);
lines.push(`   clusters (>= 4 blocks): ${(district.coverClusters || []).length}`);
for (const c of (district.coverClusters || [])) {
  lines.push(`   - ${c.id}: x[${c.x1}..${c.x2}] y[${c.y1}..${c.y2}] z[${c.z1}..${c.z2}]  n=${c.blocks}  ${top(c.palette, 4)}`);
}
lines.push('');
lines.push('3) Block entities that a structure NBT must carry');
const beCount = {};
for (const be of district.blockEntities) beCount[be.id] = (beCount[be.id] || 0) + 1;
for (const [id, n] of Object.entries(beCount).sort((a, b) => b[1] - a[1])) {
  const withItems = district.blockEntities.filter((b) => b.id === id && b.hasItems).length;
  lines.push(`   ${id} x${n}${withItems ? ` (${withItems} with Items)` : ''}`);
}
lines.push('');
lines.push('4) What the mod\'s own presets contain (for comparison)');
for (const p of presets) {
  lines.push(`   ${p.file}: size ${JSON.stringify(p.size)}, palette ${p.paletteEntries}, placed ${p.blocksPlaced}, with NBT ${p.blocksWithNbt}`);
}
lines.push('   -> no terracotta, no beds, no block entity NBT: everything terracotta/bed/rack/chest-item in the');
lines.push('      district is the user\'s own hand work.');
lines.push('');
lines.push(`5) Blocks the district uses that NO preset uses (${userOnlyBlocks.length}) - the user's own style:`);
lines.push('   ' + userOnlyBlocks.join(', '));
lines.push('');
fs.writeFileSync(path.join(work, 'city_survey_summary.txt'), lines.join('\n') + '\n');
console.log(lines.join('\n'));

function top(map, n) {
  return Object.entries(map || {}).sort((a, b) => b[1] - a[1]).slice(0, n)
    .map(([k, v]) => `${k} x${v}`).join(', ');
}
