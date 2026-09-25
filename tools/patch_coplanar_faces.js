// Moves ONE cube per COPLANAR pair by a hair, so the depth buffer stops having to choose between two faces
// in the same plane. This is the geometric fix for the "at some angles his head / bag / forearm changes
// material, goes black or seems to vanish" family of reports (README 5k + the pitfalls list).
//
//   node tools/patch_coplanar_faces.js [file.geo.json] [--dry] [--shift 0.03]
//
// What it does, and what it deliberately does NOT do:
//   * it reads the same cube boxes scanner used by tools/scan_overlapping_geometry.js (origin + size);
//   * for every COPLANAR pair it moves the origin of ONE of the two cubes along that axis, away from the
//     other face - and only `origin`: no uv, no uv_size, no inflate, no mirror, no rotation, no bone, no
//     pivot, and no size (changing a size would re-map that cube's UVs);
//   * it picks the SMALLER cube (a small cube is usually the plate/armour layer), and on a tie the cube
//     whose bone owns fewer cubes - the reason is printed for every pair;
//   * the shift defaults to 0.03 Bedrock units. WHY NOT 0.01: the scanner's own tolerance is EPS = 0.02, so
//     a 0.01 shift would still be reported as coplanar and the gate could never go green. 0.03 units is
//     0.03/16 = 0.0019 blocks - about 2 mm on a 1-block model - and is invisible in game.
//   * --dry prints the plan (which cube moves which way) and writes nothing.
'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULT_FILE = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'tarkovscav', 'geo',
  'scav.geo.json');
const args = process.argv.slice(2);
const dry = args.includes('--dry');
const shiftArg = args.indexOf('--shift');
const SHIFT = shiftArg >= 0 ? Number(args[shiftArg + 1]) : 0.03;
const file = args.find((a) => !a.startsWith('--') && a !== String(SHIFT)) || DEFAULT_FILE;

const EPS = 0.02;          // the scanner's tolerance: a pair closer than this counts as coplanar
const AREA_MIN = 0.25;     // ... and the shared plane must overlap by more than this

const json = JSON.parse(fs.readFileSync(file, 'utf8'));
const geo = json['minecraft:geometry'][0];
const bones = geo.bones || [];

const cubes = [];
for (const bone of bones) {
  (bone.cubes || []).forEach((cube, index) => {
    const lo = cube.origin.map((n, i) => Math.min(n, n + cube.size[i]));
    const hi = cube.origin.map((n, i) => Math.max(n, n + cube.size[i]));
    cubes.push({ bone: bone.name, index, cube, lo, hi,
      volume: (hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2]) });
  });
}
const cubesPerBone = new Map();
for (const cube of cubes) {
  cubesPerBone.set(cube.bone, (cubesPerBone.get(cube.bone) || 0) + 1);
}
const fmt = (v) => '[' + v.map((n) => Math.round(n * 1000) / 1000).join(', ') + ']';

const plan = [];
const seenPair = new Set();
for (let i = 0; i < cubes.length; i++) {
  for (let j = i + 1; j < cubes.length; j++) {
    const a = cubes[i];
    const b = cubes[j];
    if (a.bone === b.bone) { continue; }
    const overlapLo = [0, 1, 2].map((k) => Math.max(a.lo[k], b.lo[k]));
    const overlapHi = [0, 1, 2].map((k) => Math.min(a.hi[k], b.hi[k]));
    const volume = overlapHi.reduce((acc, n, k) => acc * Math.max(0, n - overlapLo[k]), 1);
    if (volume <= 0) { continue; }
    const smallerBox = Math.min(a.volume, b.volume);
    if (smallerBox > 0 && volume / smallerBox > 0.85) { continue; }   // CONTAINS: a shell, not a coplanar
    for (let axis = 0; axis < 3; axis++) {
      const others = [0, 1, 2].filter((k) => k !== axis);
      const shareLo = Math.abs(a.lo[axis] - b.lo[axis]) < EPS;
      const shareHi = Math.abs(a.hi[axis] - b.hi[axis]) < EPS;
      if (!shareLo && !shareHi) { continue; }
      const areaOk = others.every((k) => Math.min(a.hi[k], b.hi[k]) - Math.max(a.lo[k], b.lo[k]) > AREA_MIN);
      if (!areaOk) { continue; }
      const key = [a.bone, a.index, b.bone, b.index, axis].join('|');
      if (seenPair.has(key)) { continue; }
      seenPair.add(key);
      // Whom to move: the smaller cube; on a tie, the one from the bone with fewer cubes.
      let move = a;
      let stay = b;
      let reason = 'smaller cube (' + a.volume.toFixed(0) + ' < ' + b.volume.toFixed(0) + ' units^3)';
      if (b.volume < a.volume) {
        move = b; stay = a;
        reason = 'smaller cube (' + b.volume.toFixed(0) + ' < ' + a.volume.toFixed(0) + ' units^3)';
      } else if (Math.abs(a.volume - b.volume) < 1e-9) {
        const ca = cubesPerBone.get(a.bone) || 0;
        const cb = cubesPerBone.get(b.bone) || 0;
        if (cb < ca) {
          move = b; stay = a;
          reason = 'equal volume, ' + b.bone + ' owns fewer cubes (' + cb + ' < ' + ca + ')';
        } else {
          reason = 'equal volume, kept ' + a.bone + ' (owns ' + ca + ' cubes, no more than ' + cb + ')';
        }
      }
      // Away from the other cube: if they share the low face, push down (-axis); if the high face, push up.
      // The move is then TESTED: nudging a cube can line its other face up with a third cube (which is how
      // one pass took 20 pairs down to 7), so the opposite direction is tried too and the one that leaves
      // fewer coplanar pairs involving this cube wins. Ties keep the "away" direction.
      const preferred = shareLo ? -1 : 1;
      const conflicts = (dir) => {
        const probe = move.cube.origin[axis];
        move.cube.origin[axis] = probe + dir * SHIFT;
        let count = 0;
        for (const other of cubes) {
          if (other === move) { continue; }
          for (let k = 0; k < 3; k++) {
            const sameLo = Math.abs(Math.min(move.cube.origin[k], move.cube.origin[k] + move.cube.size[k])
              - other.lo[k]) < EPS;
            const sameHi = Math.abs(Math.max(move.cube.origin[k], move.cube.origin[k] + move.cube.size[k])
              - other.hi[k]) < EPS;
            if (!sameLo && !sameHi) { continue; }
            const axes = [0, 1, 2].filter((n) => n !== k);
            if (axes.every((n) => Math.min(Math.max(move.cube.origin[n], move.cube.origin[n] + move.cube.size[n]),
              other.hi[n]) - Math.max(Math.min(move.cube.origin[n], move.cube.origin[n] + move.cube.size[n]),
              other.lo[n]) > AREA_MIN)) { count++; }
          }
        }
        move.cube.origin[axis] = probe;
        return count;
      };
      const preferredConflicts = conflicts(preferred);
      const oppositeConflicts = conflicts(-preferred);
      const direction = oppositeConflicts < preferredConflicts ? -preferred : preferred;
      plan.push({ axis, axisName: 'xyz'[axis], direction, move, stay, reason, share: shareLo ? 'lo' : 'hi',
        conflicts: direction === preferred ? preferredConflicts : oppositeConflicts,
        tried: preferredConflicts + '/' + oppositeConflicts });
    }
  }
}

console.log('file : ' + file);
console.log('cubes: ' + cubes.length + ' in ' + bones.length + ' bones');
console.log('plan : ' + plan.length + ' cube move(s), shift ' + SHIFT + ' units ('
  + (SHIFT / 16).toFixed(4) + ' blocks; scanner EPS is ' + EPS + ', so the shift must exceed it)');
console.log('');
for (const step of plan) {
  const cube = step.move.cube;
  console.log('  ' + step.move.bone + '[' + step.move.index + '] axis ' + step.axisName
    + (step.share === 'lo' ? '-lo' : '-hi') + ' move ' + (step.direction < 0 ? '-' : '+') + SHIFT
    + '  origin ' + fmt(cube.origin) + ' -> '
    + fmt(cube.origin.map((n, k) => (k === step.axis ? n + step.direction * SHIFT : n)))
    + '   [' + step.reason + ']');
  console.log('        away from ' + step.stay.bone + '[' + step.stay.index + '] ' + fmt(step.stay.lo)
    + '..' + fmt(step.stay.hi));
}

if (dry) {
  console.log('');
  console.log('--dry: nothing written.');
  process.exit(0);
}
if (plan.length === 0) {
  console.log('');
  console.log('nothing to do: no coplanar pair found.');
  process.exit(0);
}

const backup = path.join(__dirname, 'spike', 'work', path.basename(file) + '.before-coplanar-patch.json');
fs.mkdirSync(path.dirname(backup), { recursive: true });
fs.copyFileSync(file, backup);
console.log('');
console.log('backup: ' + backup);

// Apply: ONE move per cube per axis (a cube can be the "smaller" one in several pairs).
const applied = new Map();
for (const step of plan) {
  const key = step.move.bone + '[' + step.move.index + ']' + step.axisName;
  const already = applied.get(key) || 0;
  if (already !== 0) { continue; }   // already nudged on this axis
  step.move.cube.origin[step.axis] += step.direction * SHIFT;
  applied.set(key, step.direction);
}
fs.writeFileSync(file, JSON.stringify(json, null, 2) + '\n');
console.log('applied ' + applied.size + ' cube move(s) to ' + path.basename(file));
