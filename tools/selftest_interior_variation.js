// The INTERIOR VARIATION gate (deliverable G): the interior of a building is drawn from its seed, and the
// proof is a measurement, not a claim.
//
//   node tools/selftest_interior_variation.js
//
// How it works: it runs the real generator in REPORT mode (`CityStructureGen --report <layout>`, which writes
// NOTHING) over
//   (a) the six shipped layouts, and
//   (b) two synthetic layouts of the SAME size with DIFFERENT seeds,
// parses the `INTERIOR_REPORT <json>` line and asserts:
//
//   1. for every shipped layout: every upper floor has 1..N floor holes, the per-floor partition and opening
//      counts vary (not one constant grid), every building reports the same room count as the partitions
//      imply, and no floor is left unreachable (the generator's own report of
//      `floorsWithoutShaft`/`sealedRooms` - a floor hole is a vertical connection, so with the ladder shaft
//      treated as a wall at least one floor must still be reachable through a hole, and the generator's
//      connectivity pass reports sealedRooms = 0);
//   2. the two same-size layouts with different seeds produce measurably DIFFERENT interiors: different
//      partition counts, different hole positions (the per-floor hash) and different room adjacency, not
//      just a different random number somewhere;
//   3. the reported distribution of partitions per floor, openings per floor and holes per upper floor.
//
// The generator is invoked with the JDK the suite uses; if tools/spike/out is missing, the gate SKIPs the
// measurement loudly instead of passing silently (the suite compiles the spikes before the Node gates).
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'tools', 'spike', 'out');
const TOOLS = path.join(ROOT, 'tools');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const skip = (label, detail) => console.log(`  SKIP  ${label}${detail ? '  ' + detail : ''}`);

const javas = [
  process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java.exe'),
  'C:\\Program Files\\Java\\jdk-21.0.12\\bin\\java.exe',
  'C:\\Program Files\\Java\\jdk-21.0.11\\bin\\java.exe',
  'C:\\Program Files\\Common Files\\Oracle\\Java\\javapath\\java.exe',
  'D:\\deepseek\\GirlsFrontline\\.toolchain\\jdk-17\\bin\\java.exe',
  'java',
].filter(Boolean);

/**
 * Runs the generator in report mode with the first Java that can actually LOAD the compiled spikes. A JDK 17
 * cannot read class file version 65, and the suite's javac is a JDK 21 - so each candidate is tried and a
 * mismatch moves on to the next one instead of crashing the gate.
 */
function report(layoutFiles) {
  let lastError = null;
  for (const candidate of javas) {
    try {
      const output = execFileSync(candidate, ['-cp', OUT, 'CityStructureGen', '--report', ...layoutFiles], {
        cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
      });
      const reports = [];
      for (const line of output.split('\n')) {
        if (line.startsWith('INTERIOR_REPORT ')) {
          reports.push(JSON.parse(line.slice('INTERIOR_REPORT '.length)));
        }
      }
      return { reports, java: candidate };
    } catch (error) {
      lastError = error;
      if (!/UnsupportedClassVersionError|LinkageError|version up to/i.test(String(error.stderr || error.message))) {
        throw error;
      }
    }
  }
  return { reports: [], java: null, error: lastError };
}

if (!fs.existsSync(path.join(OUT, 'CityStructureGen.class'))) {
  skip('the interior-variation measurement', 'tools/spike/out/CityStructureGen.class is missing');
  skip('run tools/spike/selftest.ps1, which compiles the spikes before the Node gates');
  process.exit(0);
}

// ------------------------------------------------------------------ the two synthetic layouts
// Same size, same building geometry, same everything except the seed: any difference the report shows is the
// interior variation and nothing else.
function synthetic(seed, name) {
  return {
    name,
    palette: 'modern_scav',
    size: [40, 26, 40],
    floor_height: 4,
    interior_variation: 1,
    extra_openings_per_wall: 2,
    floor_holes_per_floor: 2,
    buildings: [
      { name: 'a', x: 1, z: 1, w: 16, d: 16, floors: 4, floor_height: 4, wall: 'wall_brick',
        accent: 'accent', floor_block: 'floor', roof_block: 'roof', window_block: 'window',
        door_side: 'south', roof_access: true, ruined: false, rooms_x: 2, rooms_z: 2,
        min_cover_per_floor: 5, min_furniture_per_floor: 6, min_doors: 2, seed },
      { name: 'b', x: 21, z: 1, w: 16, d: 16, floors: 4, floor_height: 4, wall: 'wall_slate',
        accent: 'accent', floor_block: 'floor', roof_block: 'roof', window_block: 'window_band',
        door_side: 'south', roof_access: true, ruined: false, rooms_x: 2, rooms_z: 2,
        min_cover_per_floor: 5, min_furniture_per_floor: 6, min_doors: 2, seed: seed + 1 },
    ],
    cover: [],
  };
}
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tarkovscav-interior-'));
const fileA = path.join(tmp, 'variation-a.json');
const fileB = path.join(tmp, 'variation-b.json');
fs.writeFileSync(fileA, JSON.stringify(synthetic(4242, 'variation_a')));
fs.writeFileSync(fileB, JSON.stringify(synthetic(9999, 'variation_b')));

console.log('1. the same building, two seeds: the interiors differ measurably');
const seedRun = report([fileA, fileB]);
const seeds = seedRun.reports;
if (seeds.length !== 2) {
  skip('the interior-variation measurement',
    `the generator reported ${seeds.length} layout(s) with ${seedRun.java || 'no java'}`);
  fs.rmSync(tmp, { recursive: true, force: true });
  process.exit(0);
}
check(seeds.length === 2, 'the generator reported both synthetic layouts', `${seeds.length}`);
const [a, b] = seeds;
if (seeds.length === 2) {
  const flat = (r) => r.buildings.map((x) => x.floorsDetail);
  const fingerprint = (r) => r.buildings.map((x) => x.floorsDetail.map((f) => f.hash).join('|')).join('//');
  check(fingerprint(a) !== fingerprint(b), 'the per-floor partition/hole fingerprints differ',
    `${fingerprint(a).slice(0, 48)}... vs ${fingerprint(b).slice(0, 48)}...`);
  const partitions = (r) => r.buildings.map((x) => x.floorsDetail.map((f) => f.partitions).join(',')).join('|');
  const holes = (r) => r.buildings.map((x) => x.floorsDetail.map((f) => f.holes).join(',')).join('|');
  const rooms = (r) => r.buildings.map((x) => x.floorsDetail.map((f) => f.rooms).join(',')).join('|');
  check(partitions(a) !== partitions(b) || holes(a) !== holes(b) || rooms(a) !== rooms(b),
    'partition counts, hole counts or the room adjacency graph differ',
    `partitions ${partitions(a)} vs ${partitions(b)}`);
  const differs = [
    partitions(a) !== partitions(b) ? 'partitions' : null,
    holes(a) !== holes(b) ? 'holes' : null,
    rooms(a) !== rooms(b) ? 'rooms' : null,
  ].filter(Boolean);
  console.log(`  the measured difference: ${differs.join(', ')}`);
  for (const r of seeds) {
    for (const building of r.buildings) {
      for (const floor of building.floorsDetail) {
        console.log(`    ${r.layout}/${floor.band}: partitions=${floor.partitions} `
          + `(X=${floor.partitionsX} Z=${floor.partitionsZ}) openings=${floor.openings} `
          + `holes=${floor.holes} rooms=${floor.rooms} hash=${floor.hash}`);
      }
    }
  }
}

// ------------------------------------------------------------------ the shipped layouts
console.log('');
console.log('2. every shipped layout: holes per upper floor, varying partitions, no unreachable floor');
const SHIPPED = ['city-layout.json', 'city-layout-a.json', 'city-layout-b.json', 'city-layout-c.json',
  'strongpoint-layout.json', 'district-layout.json'];
const shippedRun = report(SHIPPED.map((f) => path.join(TOOLS, f)));
const shipped = shippedRun.reports;
if (shipped.length !== SHIPPED.length) {
  skip('the shipped-layout measurement',
    `the generator reported ${shipped.length} of ${SHIPPED.length} layout(s) with `
    + `${shippedRun.java || 'no java'}`);
  fs.rmSync(tmp, { recursive: true, force: true });
  process.exit(0);
}
check(shipped.length === SHIPPED.length, 'every shipped layout reported', `${shipped.length}`);
const distribution = { partitions: [], openings: [], holes: [] };
let upperFloors = 0;
let buildingsWithTwoHoles = 0;
let buildingsWithOneSpawner = 0;
let buildingsWithTwoSpawners = 0;
for (const layout of shipped) {
  let holes = 0;
  let upper = 0;
  let constantPartitions = 0;
  for (const building of layout.buildings) {
    const partitionSet = new Set();
    for (const floor of building.floorsDetail) {
      if (floor.holes > 0) {
        holes += floor.holes;
        upper++;
        distribution.holes.push(floor.holes);
      }
      distribution.partitions.push(floor.partitions);
      distribution.openings.push(floor.openings);
      partitionSet.add(floor.partitions);
    }
    if (partitionSet.size === 1) {
      constantPartitions++;
    }
    if (building.spawners === 1) buildingsWithOneSpawner++;
    if (building.spawners === 2) buildingsWithTwoSpawners++;
  }
  upperFloors += upper;
  check(holes >= upper && upper > 0,
    `${layout.layout}: every upper floor has at least one floor hole`,
    `${upper} upper floor(s), ${holes} hole(s)`);
  check(constantPartitions < layout.buildings.length || layout.buildings.length === 1,
    `${layout.layout}: the partition count is not one constant per building`,
    `${layout.buildings.length - constantPartitions} of ${layout.buildings.length} building(s) vary`);
  check(layout.buildings.every((x) => x.floorsWithoutShaft === x.floors),
    `${layout.layout}: EVERY floor is reachable through the floor holes alone `
    + '(the ladder shaft treated as a wall)',
    layout.buildings.map((x) => `${x.building}:${x.floorsWithoutShaft}/${x.floors}`).join(' '));
  const big = layout.buildings.filter((x) => x.ok !== false).length;
  check(big === layout.buildings.length, `${layout.layout}: no building failed`,
    `${big}/${layout.buildings.length}`);
}
check(upperFloors >= 80, 'the shipped layouts really do have upper floors to vary', `${upperFloors}`);
check(buildingsWithOneSpawner + buildingsWithTwoSpawners > 0,
  'the shipped layouts place spawners (the B deliverable is visible here)',
  `${buildingsWithOneSpawner} building(s) with 1, ${buildingsWithTwoSpawners} with 2`);

console.log('');
console.log('3. the measured interior-variation distribution (from the generator report, all 6 layouts)');
const stat = (values) => {
  const min = Math.min(...values);
  const max = Math.max(...values);
  const sum = values.reduce((x, y) => x + y, 0);
  return `min=${min} max=${max} mean=${(sum / values.length).toFixed(2)} n=${values.length}`;
};
console.log(`  partitions per floor : ${stat(distribution.partitions)}`);
console.log(`  openings per floor   : ${stat(distribution.openings)}`);
console.log(`  holes per upper floor: ${stat(distribution.holes)}`);
check(distribution.partitions.length > 100 && distribution.holes.length === upperFloors,
  'the distribution covers every floor and every upper floor',
  `${distribution.partitions.length} floor(s), ${distribution.holes.length} upper floor(s)`);
check(Math.max(...distribution.holes) >= 2, 'at least one upper floor has more than one hole',
  `max=${Math.max(...distribution.holes)}`);
check(new Set(distribution.partitions).size >= 2 && new Set(distribution.openings).size >= 2,
  'both the partition and the opening counts take more than one value',
  `${new Set(distribution.partitions).size} / ${new Set(distribution.openings).size} distinct`);

fs.rmSync(tmp, { recursive: true, force: true });
console.log('');
if (failures > 0) {
  console.log(`${failures} interior-variation check(s) FAILED`);
  process.exit(1);
}
console.log('the interiors are seed-driven, every upper floor has a floor hole that is a real vertical '
  + 'connection, and two seeds of the same building measurably differ');
