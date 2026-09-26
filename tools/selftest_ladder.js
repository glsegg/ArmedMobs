// The LADDER-CLIMBING gate (README 7o / docs/爬梯设计.md, plan A "vertical link").
//
//   node tools/selftest_ladder.js
//
// It is honest about its two kinds of evidence:
//
//   * STRUCTURAL (source text, labelled as such): the goal is registered in the three classes that call
//     addGoal and in none of the six subclasses, the shaft logic exists once (LadderSearch) and is delegated
//     to, the fall rule is a registered LivingFallEvent subscriber, the seven config keys are read and not
//     redefined, and the priority slot is above every combat goal and above the advance order.
//
//   * EXECUTABLE (real bytecode, real data):
//       1. tools/spike/LadderTest runs the SHIPPED com.gfl.tarkovscav.gun.LadderSearch over a synthetic
//          world that reproduces the generator's own shaft geometry, and over the obstructed variant the
//          shipped presets actually contain. Its PASS lines are counted and its exit code is asserted.
//       2. CityStructureGen --report is run over the six shipped layouts (writes NOTHING) and the
//          `LADDER_REPORT <json>` line per layout is parsed: one line per shaft, with the rung count, the
//          column's continuity, the per-floor opening/headroom count and how many ordered floor pairs the
//          shipped finder accepts. Those numbers are pinned below.
//
// FINDING, carried in the output on purpose: with the current presets 0 of the 37 shipped shafts is
// climbable on every floor, because the generator's interior furnishing/cover passes place blocks on the
// two cells beside the ladder. Making them climbable changes the six .nbt presets, so it is a decision for
// the orchestrator, not something this gate may do. The gate therefore PINS the measured baseline (so the
// number can never drift silently) and prints the finding instead of failing the suite for a defect whose
// fix is out of scope.
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'tools', 'spike', 'out');
const TOOLS = path.join(ROOT, 'tools');
const SRC = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const GUN = path.join(SRC, 'gun');
const ENTITY = path.join(SRC, 'entity');

let failures = 0;
let checks = 0;
const check = (ok, label, detail) => {
  checks++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const skip = (label, detail) => console.log(`  SKIP  ${label}${detail ? '  ' + detail : ''}`);
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8');
const count = (text, pattern) => (text.match(pattern) || []).length;
/** Comments removed, so a check that says "the code never does X" is not tripped by the javadoc that says so. */
const strip = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
/** The {priority, registration text} pairs of every addGoal call in a file (multi-line call included). */
const addGoals = (text) =>
  [...text.matchAll(/addGoal\((\d+),\s*([\s\S]*?)\);/g)].map((m) => [Number(m[1]), m[2]]);

// ------------------------------------------------------------------ the nine armed units
// Three classes call addGoal (verified: only these three declare/override registerGoals), six subclasses
// inherit from them. This table IS the chain the report has to give.
const BASE = ['entity/ScavEntity.java', 'entity/GunnerPillagerEntity.java', 'entity/GunnerVillagerEntity.java'];
const SUBCLASSES = [
  ['entity/ElitePillagerEntity.java', 'GunnerPillagerEntity'],
  ['entity/BearPillagerEntity.java', 'GunnerPillagerEntity'],
  ['entity/SniperPillagerEntity.java', 'GunnerPillagerEntity'],
  ['entity/EliteVillagerEntity.java', 'GunnerVillagerEntity'],
  ['entity/SniperVillagerEntity.java', 'GunnerVillagerEntity'],
  ['entity/UsecVillagerEntity.java', 'GunnerVillagerEntity'],
];

console.log('1. registration: all nine armed units, one goal implementation');
{
  const goal = read('gun/LadderClimbGoal.java');
  for (const file of BASE) {
    const text = read(file);
    const name = path.basename(file, '.java');
    check(/addGoal\(5, new com\.gfl\.tarkovscav\.gun\.LadderClimbGoal\(this\)\)/.test(text),
      `${name}: registers LadderClimbGoal at priority 5`);
    check(/addGoal\(6, new com\.gfl\.tarkovscav\.command\.AdvanceOrderGoal\(this\)\)/.test(text),
      `${name}: the advance order moved to priority 6, so the climb wins the vertical leg`);
    // The order goals this file registers, as {priority, registration text} pairs.
    const goals = addGoals(text);
    const combat = goals.filter(([, what]) =>
      /GunAttackGoal|NoGunMeleeGoal|GrenadeThrowGoal|GrenadeResupplyGoal|ArmedRangedGoal/.test(what));
    check(combat.length > 0 && combat.every(([p]) => p < 5),
      `${name}: every combat goal outranks the climb`,
      `climb=5, combat=${combat.map(([p, what]) => p + ':' + (what.match(/[A-Za-z]+Goal/) || ['?'])[0]).join(', ')}`);
  }
  for (const [file, parent] of SUBCLASSES) {
    const text = read(file);
    const name = path.basename(file, '.java');
    check(new RegExp(`class ${name} extends ${parent}\\b`).test(text),
      `${name} extends ${parent} (so it inherits the goal)`);
    check(!/registerGoals\s*\(/.test(text),
      `${name} does not override registerGoals (no second copy of the goal list)`);
  }
  const allEntity = fs.readdirSync(ENTITY).filter((f) => f.endsWith('.java'))
    .map((f) => fs.readFileSync(path.join(ENTITY, f), 'utf8')).join('\n');
  check(count(allEntity, /new com\.gfl\.tarkovscav\.gun\.LadderClimbGoal\(/g) === 3,
    'exactly the three registering classes name the goal', '3 occurrences');
  check(count(goal, /LadderSearch\.(find|opening|nearestLanding|shouldStart|differentFloor|reached|exceededHeight|interrupted|geometryLost|column|standable)\(/g) >= 12,
    'the goal delegates every geometric decision to the one LadderSearch core',
    `${count(goal, /LadderSearch\.\w+\(/g)} LadderSearch calls`);

  // The shaft geometry exists once: only LadderSearch may define the ladder/opening/landing predicates.
  const GEOMETRY = /(static\s+)?(boolean|int|Shaft|Opening|Exit)\s+(standable|opening|column|nearestLanding|reached|exceededHeight|interrupted|geometryLost)\s*\(/;
  const dupes = [];
  for (const dir of [ENTITY, GUN]) {
    for (const file of fs.readdirSync(dir)) {
      if (!file.endsWith('.java')) continue;
      const rel = path.relative(SRC, path.join(dir, file)).replace(/\\/g, '/');
      if (rel === 'gun/LadderSearch.java') continue;
      if (GEOMETRY.test(fs.readFileSync(path.join(dir, file), 'utf8')) || /findShaft/.test(read(rel))) {
        dupes.push(rel);
      }
    }
  }
  check(dupes.length === 0, 'the shaft geometry is defined once (LadderSearch), never per entity',
    dupes.length ? dupes.join(', ') : 'only LadderSearch.java defines it');
  const everySource = [];
  const collect = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) collect(full);
      else if (entry.name.endsWith('.java')) everySource.push(full);
    }
  };
  collect(SRC);
  const climbSources = everySource
    .filter((f) => /\.is\(BlockTags\.CLIMBABLE\)/.test(fs.readFileSync(f, 'utf8')))
    .map((f) => path.relative(SRC, f).replace(/\\/g, '/'));
  check(climbSources.length === 1 && climbSources[0] === 'gun/LadderClimb.java',
    'the ladder block test is used in exactly one place (code, not javadoc)', climbSources.join(', ') || 'none');
}

console.log('');
console.log('2. the AI yield: climbing suppresses shooting/grenades with the mechanism already in the code');
{
  const goal = read('gun/LadderClimbGoal.java');
  check(/setFlags\(EnumSet\.of\(Flag\.MOVE, Flag\.LOOK\)\)/.test(goal),
    'LadderClimbGoal holds MOVE+LOOK, the same two flags GunAttackGoal holds');
  check(/isInterruptable\(\)/.test(goal) && /return this\.phase != Phase\.CLIMB && this\.phase != Phase\.LAND/.test(goal),
    'isInterruptable() is false while on the rungs (Forge WrappedGoal#canBeReplacedBy reads it), so a fight cannot yank the unit off mid-climb');
  check(/AdvanceOrder\.combatOverrides\(/.test(goal) && /AdvanceOrder\.retreatOverrides\(/.test(goal),
    'the combat and retreat vetoes are the command layer\'s own methods, called not re-stated');
  check(/LadderSearch\.shouldStart\(/.test(goal), 'the intent rule is the tested pure function, not an inline condition');
  check(/gunBrain\(\)\.tick\(\)/.test(goal) && /LADDER_COMBAT_WHILE_CLIMBING/.test(goal),
    'combatWhileClimbing=true is implemented by ticking the existing GunBrain itself (no second fire flag)');
  check(!/Grenade|grenade/.test(strip(goal)), 'the goal\'s code never touches grenade code');
  // "Stick to the ladder": the vertical delta is paired with a clamped pull towards the column centre, which
  // is what cancels a villager Brain walk target the goal cannot park itself (see LadderClimb#climbTick).
  const climbSource = read('gun/LadderClimb.java');
  check(/Mth\.clamp\(\(columnX \+ 0\.5D - mob\.getX\(\)\) \* 0\.25D, -0\.15D, 0\.15D\)/.test(climbSource)
    && /Mth\.clamp\(\(columnZ \+ 0\.5D - mob\.getZ\(\)\) \* 0\.25D, -0\.15D, 0\.15D\)/.test(climbSource),
    'climbTick pulls the unit back to the column centre, clamped to vanilla\'s 0.15 on-ladder limit');
  check(count(goal, /climbTick\(this\.mob, step, this\.climbYaw, this\.shaft\.x\(\), this\.shaft\.z\(\)\)/g) === 2,
    'both climb phases anchor that pull on the ladder column');
  const flags = [['gun/GunAttackGoal.java', 'GunAttackGoal'], ['gun/NoGunMeleeGoal.java', 'NoGunMeleeGoal'],
    ['grenade/GrenadeResupplyGoal.java', 'GrenadeResupplyGoal'], ['gun/ArmedRangedGoal.java', 'ArmedRangedGoal']];
  for (const [rel, name] of flags) {
    check(/Flag\.MOVE/.test(read(rel)),
      `${name} needs MOVE, so the climb's flags keep it from running on the rungs`);
  }
  // GrenadeThrowGoal is the one goal flags cannot stop: it registers NO flags, so it may start next to any
  // running goal. Measured from its source, and the reason the wrapper exists at all.
  const throwGoal = strip(read('grenade/GrenadeThrowGoal.java'));
  check(!/setFlags\(/.test(throwGoal),
    'GrenadeThrowGoal carries no goal flags (measured), so flag arbitration cannot suppress it');
  const gated = read('gun/LadderGatedGoal.java');
  check(/this\.delegate\.canUse\(\) && !LadderClimb\.onRungs\(this\.mob\)/.test(gated),
    'LadderGatedGoal refuses its delegate while the unit is on the rungs');
  check(/setFlags\(delegate\.getFlags\(\)\.isEmpty\(\)/.test(gated),
    'and it forwards the delegate\'s own flags, so it conflicts with exactly what the delegate conflicts with');
  for (const file of BASE) {
    const name = path.basename(file, '.java');
    check(/LadderGatedGoal\(this,\s*new com\.gfl\.tarkovscav\.grenade\.GrenadeThrowGoal\(/.test(read(file)),
      `${name}: the grenade throw goal is registered inside the ladder gate`);
  }
}

console.log('');
console.log('3. the fall rule and the config keys');
{
  const climb = read('gun/LadderClimb.java');
  check(/@Mod\.EventBusSubscriber\(modid = TarkovScav\.MOD_ID\)/.test(climb),
    'LadderClimb is its own Forge event subscriber (no shared setup file was edited)');
  check(/@SubscribeEvent\s+public static void onFall\(LivingFallEvent event\)/.test(climb.replace(/\s+/g, ' ')),
    'it subscribes LivingFallEvent');
  check(/event\.setCanceled\(true\)/.test(climb), 'and cancels the fall inside a shaft');
  check(/Config\.LADDER_FALL_DAMAGE_IN_SHAFT\.get\(\)/.test(climb),
    'gated by ladder.fallDamageInShaft (false = cancel, the default)');
  const config = fs.readFileSync(path.join(SRC, 'Config.java'), 'utf8');
  const keys = ['LADDER_ENABLED', 'LADDER_CLIMB_SPEED', 'LADDER_DOWN_SPEED', 'LADDER_SEARCH_RADIUS',
    'LADDER_MAX_HEIGHT', 'LADDER_COMBAT_WHILE_CLIMBING', 'LADDER_FALL_DAMAGE_IN_SHAFT'];
  for (const key of keys) {
    check(new RegExp(key + ' = b').test(config), `Config declares ${key} (the code only reads it)`);
  }
  const mine = climb + read('gun/LadderClimbGoal.java') + read('gun/LadderSearch.java');
  check(!/LADDER_[A-Z_]+ = b/.test(mine), 'the ladder code redefines no config key');
  check(!/LADDER_[A-Z_]+ = /.test(mine.replace(/Config\.LADDER_[A-Z_]+/g, '')),
    'and assigns to none of them');
}

console.log('');
console.log('4. EXECUTABLE: the shipped shaft finder (tools/spike/LadderTest)');
const javas = [
  process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java.exe'),
  'C:\\Program Files\\Java\\jdk-21.0.12\\bin\\java.exe',
  'C:\\Program Files\\Java\\jdk-21.0.11\\bin\\java.exe',
  'D:\\deepseek\\GirlsFrontline\\.toolchain\\jdk-17\\bin\\java.exe',
  'java',
].filter(Boolean);
const javacs = [
  process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'javac.exe'),
  'C:\\Program Files\\Java\\jdk-21.0.12\\bin\\javac.exe',
  'C:\\Program Files\\Java\\jdk-21.0.11\\bin\\javac.exe',
  'D:\\deepseek\\GirlsFrontline\\.toolchain\\jdk-17\\bin\\javac.exe',
  'javac',
].filter((c) => c && fs.existsSync(c));
const versionTrouble = (error) =>
  /UnsupportedClassVersionError|LinkageError|version up to|class file version/i.test(String(error.stderr || error.message));

function walkJava(dir, into) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkJava(full, into);
    else if (entry.name.endsWith('.java')) into.push(full);
  }
}

function usable(candidate) {
  return !(candidate.includes(path.sep) || candidate.endsWith('.exe')) || fs.existsSync(candidate);
}

/**
 * Runs a spike class with arguments and returns its stdout. The suite compiles tools/spike before the Node
 * gates; when this gate is run on its own, the class is compiled into a temp dir first - only the spike
 * source, the one shipped source it drives, and the Minecraft stubs in tools/spike/stubs (the same shape
 * tools/spike/selftest.ps1 uses for GrenadeBallisticsTest). It never runs a spike class with no arguments,
 * because CityStructureGen without --report would rewrite the shipped presets and a gate must not do that.
 */
function spikeOutput(main, extraSources, args) {
  const inOut = fs.existsSync(path.join(OUT, main + '.class'));
  if (!inOut && !javacs.length) return null;
  let dir = OUT;
  if (!inOut) {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tarkovscav-ladder-'));
    const stubs = [];
    walkJava(path.join(TOOLS, 'spike', 'stubs'), stubs);
    execFileSync(javacs[0], ['-encoding', 'UTF-8', '-d', dir,
      path.join(TOOLS, 'spike', main + '.java'), ...extraSources, ...stubs],
      { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  }
  let lastError = null;
  for (const candidate of javas) {
    if (!usable(candidate)) continue;
    try {
      return execFileSync(candidate, ['-cp', dir, main, ...args], {
        cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (error) {
      lastError = error;
      // A real FAIL exit (the spike test's own exit code), not a JVM mismatch: report its output.
      if (error.status === 1 || error.status === 2) return String(error.stdout || '');
      if (!versionTrouble(error)) throw error;
    }
  }
  throw lastError || new Error('no java could run ' + main);
}

const ladderSearchSource = path.join(GUN, 'LadderSearch.java');
try {
  const output = spikeOutput('LadderTest', [ladderSearchSource], []);
  const pass = count(output, /^ {2}PASS {2}/gm);
  const fail = count(output, /^ {2}FAIL {2}/gm);
  check(fail === 0, 'the shipped LadderSearch was executed and reported no failure',
    `${pass} PASS, ${fail} FAIL`);
  check(pass >= 74, 'LadderTest ran every decision and state-machine case', `${pass} checks`);
  check(/4\. the four state-machine exits/.test(output),
    'LadderTest covers the four state-machine exits');
  for (const line of output.split('\n')) {
    if (/^ {2}FAIL/.test(line)) console.log('    ' + line.trim());
  }
} catch (error) {
  check(false, 'LadderTest ran', String(error.message || error).split('\n')[0]);
}

console.log('');
console.log('5. EXECUTABLE: the shaft report over the six shipped layouts (writes nothing)');
// Pinned from the measured run AFTER the shaft-cell guard landed in the generator and the six presets were
// regenerated (README 7o): every shaft of every structure is now climbable on every floor. The per-structure
// numbers are pinned so a later generator change cannot move them silently, and `climbable: shafts` /
// `badTotal === 0` is the acceptance assertion itself, not a baseline.
const SHIPPED = [
  { file: 'city-layout.json', layout: 'city_small', shafts: 4, climbable: 4, continuous: 4, openFloors: 17, expectedOpen: 17, pairs: 58 },
  { file: 'city-layout-a.json', layout: 'city_a', shafts: 4, climbable: 4, continuous: 4, openFloors: 14, expectedOpen: 14, pairs: 40 },
  { file: 'city-layout-b.json', layout: 'city_b', shafts: 3, climbable: 3, continuous: 3, openFloors: 11, expectedOpen: 11, pairs: 38 },
  { file: 'city-layout-c.json', layout: 'city_c', shafts: 6, climbable: 6, continuous: 6, openFloors: 18, expectedOpen: 18, pairs: 40 },
  { file: 'strongpoint-layout.json', layout: 'city_strongpoint', shafts: 18, climbable: 18, continuous: 18, openFloors: 73, expectedOpen: 73, pairs: 234 },
  { file: 'district-layout.json', layout: 'gen_district', shafts: 2, climbable: 2, continuous: 2, openFloors: 9, expectedOpen: 9, pairs: 32 },
];
try {
  const reports = [];
  const output = spikeOutput('CityStructureGen',
    [ladderSearchSource, path.join(TOOLS, 'spike', 'Json.java'), path.join(TOOLS, 'spike', 'NbtReader.java')],
    ['--report', ...SHIPPED.map((s) => path.join(TOOLS, s.file))]);
  if (output === null) {
    skip('the shaft report', 'tools/spike/out/CityStructureGen.class is missing and no javac was found');
  } else {
    for (const line of output.split('\n')) {
      if (line.startsWith('LADDER_REPORT ')) reports.push(JSON.parse(line.slice('LADDER_REPORT '.length)));
    }
    check(reports.length === SHIPPED.length, 'every shipped layout reported its shafts (report mode writes'
      + ' nothing)', `${reports.length}/${SHIPPED.length}`);
    let shaftTotal = 0;
    let badTotal = 0;
    let pairTotal = 0;
    for (const expected of SHIPPED) {
      const report = reports.find((r) => r.layout === expected.layout);
      check(!!report, `${expected.layout}: LADDER_REPORT present`);
      if (!report) continue;
      check(report.shafts === expected.shafts, `${expected.layout}: shafts pinned`,
        `${report.shafts} (expected ${expected.shafts})`);
      check(report.detail.length === report.shafts, `${expected.layout}: one line per shaft`,
        `${report.detail.length} shaft record(s)`);
      check(report.bad === report.shafts - report.climbable, `${expected.layout}: the bad count is consistent`);
      // The acceptance numbers, pinned per structure. These moved once already, when the shaft-cell guard
      // landed: city_small 0 -> 4 climbable, city_a 0 -> 4, city_b 0 -> 3, city_c 0 -> 6,
      // city_strongpoint 0 -> 18, gen_district 0 -> 2 (and the continuous/opening/pair columns with them).
      check(report.climbable === expected.climbable && report.bad === report.shafts - expected.climbable,
        `${expected.layout}: every shaft climbable on every floor`, `${report.climbable}/${report.shafts}`);
      check(report.continuous === expected.continuous, `${expected.layout}: continuous columns pinned`,
        `${report.continuous}/${report.shafts}`);
      check(report.open_floors === expected.openFloors && report.expected_open_floors === expected.expectedOpen,
        `${expected.layout}: floors with a standable opening pinned`,
        `${report.open_floors}/${report.expected_open_floors}`);
      check(report.climbable_pairs === expected.pairs, `${expected.layout}: climbable floor pairs pinned`,
        `${report.climbable_pairs}`);
      // The report must be self-consistent with its own per-shaft records.
      check(report.detail.every((s) => s.gap_cells >= 0 && s.rungs > 0 && s.floors === s.expected_openings),
        `${expected.layout}: every shaft record is well formed`);
      shaftTotal += report.shafts;
      badTotal += report.bad;
      pairTotal += report.climbable_pairs;
    }
    check(shaftTotal === 37, 'the six shipped structures carry 37 ladder shafts', `${shaftTotal}`);
    // Acceptance item 1 of docs/爬梯设计.md §4, as a hard assertion: no shaft may be unclimbable.
    check(badTotal === 0, 'acceptance item 1: EVERY one of the 37 shipped shafts is climbable on every floor',
      `${badTotal} blocked`);
    check(pairTotal === 442, 'the finder accepts every ordered floor pair of every shaft', `${pairTotal} pairs`);
    if (badTotal > 0) {
      // Never silent: if this ever regresses, name what is in the way.
      const blockers = new Set();
      for (const report of reports) for (const key of report.blockers || []) blockers.add(key.split('|')[0]);
      console.log('  WARNING  a pass that writes into a shaft cell is not filtered again. Blocking keys'
        + ' measured: ' + [...blockers].sort().join(', '));
      for (const report of reports) {
        for (const shaft of report.detail) {
          if (!shaft.climbable) {
            console.log(`  WARNING  ${report.layout}/${shaft.building} (${shaft.x},${shaft.z}):`
              + ` rungs=${shaft.rungs} gaps=${shaft.gap_cells} openings=${shaft.openings}/${shaft.floors}`);
          }
        }
      }
    }
  }
} catch (error) {
  check(false, 'the shaft report ran', String(error.message || error).split('\n')[0]);
}

console.log('');
if (failures > 0) {
  console.log(`${failures} ladder check(s) FAILED (${checks} run)`);
  process.exit(1);
}
console.log(`${checks} ladder check(s) passed: the goal is registered in all nine armed units, the shipped`
  + ' shaft finder and its four exits were executed against the generator\'s own geometry, and the six shipped'
  + ' layouts were measured - 37 shafts, all 37 climbable on every floor, all 442 ordered floor pairs accepted'
  + ' by the shipped finder (acceptance item 1).');
