// The accuracy profile (README 5o): wild first shots, a 75 % steady-state ceiling, and a counter that only
// a real shot advances.
//
//   node tools/selftest_accuracy.js
//
// The claim "上限 75%" is a claim about a NUMBER, so it is checked as one: the shipped numbers are plugged
// into the same cone model GunBrain uses, and the resulting hit chance at a realistic range is compared with
// the cap. Two things are deliberately simulated rather than asserted from source text:
//   * the warm-up counter (first 8 shots, then steady, then cold again after the reset window);
//   * the hit chance implied by the aim cone, so "75 % cap" cannot be satisfied by a no-op.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
/** A profile cap is clamped by the hard ceiling, exactly as AccuracyProfile.capFor does it. */
const capOf = (raw) => Math.max(0, Math.min(raw, HARD));
// erf, the same approximation AccuracyProfile uses, defined before any simulation runs.
function erf(x) {
  const sign = Math.sign(x);
  const z = Math.abs(x);
  const t = 1 / (1 + 0.3275911 * z);
  const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
    + 0.254829592) * t * Math.exp(-z * z);
  return sign * y;
}
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

const profile = read('gun/AccuracyProfile.java');
const brain = strip(read('gun/GunBrain.java'));
const config = read('Config.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const debug = read('command/ModCommands.java');

console.log('1. where the number comes from, and that it is the ONLY place');
check(/AccuracyProfile\.accuracyFor\(this\.mob, tierAccuracy, eye\.distanceTo\(aimPoint\), targetRadius\)/
  .test(brain),
  'GunBrain asks the profile for the accuracy of the shot it is about to take, with the real distance');
check(/aimTarget\.getBbWidth\(\) \* 0\.5D/.test(brain),
  'and the target\'s own half-width, which is what the cone is compared against');
check(/Config\.ACCURACY_ENABLED\.get\(\)[\s\S]{0,200}?AccuracyProfile\.accuracyFor/.test(brain),
  'and the master switch falls back to the tier value (the old behaviour)');
const tierReads = (brain.match(/Config\.tier\(this\.loadout\.tier\(\)\)\.accuracy\.get\(\)/g) || []).length;
check(tierReads === 1, 'the tier accuracy is read exactly once in the aim path', `${tierReads} read(s)`);
check(/1\.0D - Mth\.clamp\(accuracy \* accuracyMultiplier, 0\.0D, 1\.0D\)/.test(brain),
  'the value still feeds the same error-cone arithmetic (no new hit-roll was introduced)');
check(/result == ShootResult\.SUCCESS/.test(brain) && /AccuracyProfile\.noteShot/.test(brain),
  'only a SUCCESS shot counts as experience');
check(/AccuracyProfile\.tickDecay\(this\.mob, level\.getGameTime\(\)\)/.test(brain),
  'the counter decays on the brain\'s own tick');

console.log('');
console.log('2. the warm-up and the cap, as shipped');
const parsed = (name) => {
  const m = new RegExp(`defineInRange\\("${name}", ([0-9.]+)D?`).exec(config);
  return m ? Number(m[1]) : NaN;
};
const ROOKIE = parsed('profileRookieCap');
const VETERAN = parsed('profileVeteranCap');
const ELITE = parsed('profileEliteCap');
const HARD = parsed('hardCeiling');
const WARMUP_SHOTS = parsed('warmupShots');
const WARMUP_MULT = parsed('warmupMultiplier');
const RESET = parsed('resetTicks');
check(ROOKIE === 0.75, 'accuracy.profileRookieCap ships as 0.75 (the user\'s number for the small fry)',
  String(ROOKIE));
check(VETERAN === 0.85, 'accuracy.profileVeteranCap ships as 0.85 (the sniper profile)', String(VETERAN));
check(ELITE === 0.9, 'accuracy.profileEliteCap ships as 0.90', String(ELITE));
check(HARD === 0.95 && HARD < 1.0, 'accuracy.hardCeiling ships below 1.0, so NO unit ever never-misses',
  String(HARD));
check(VETERAN > ROOKIE && ELITE > VETERAN,
  'the profiles are ordered rookie < veteran < elite (a stronger unit really is more accurate)');
check(WARMUP_SHOTS === 8, 'accuracy.warmupShots ships as 8 (the "first 8 shots" rule)', String(WARMUP_SHOTS));
check(WARMUP_MULT === 0.45, 'accuracy.warmupMultiplier ships as 0.45', String(WARMUP_MULT));
check(RESET === 600, 'accuracy.resetTicks ships as 600 (30 s)', String(RESET));
check(/define\("enabled", true\)/.test(config.slice(config.indexOf('push("accuracy")'),
  config.indexOf('push("accuracy")') + 2000)), 'and the profile is on by default');
// The sniper's own tier value is 0.85, which is exactly what the cap has to bite on.
const sniper = /case SNIPER -> 0\.85D;/.exec(config);
check(!!sniper, 'the sniper tier still claims 0.85 accuracy (so the cap is doing real work)');
// Role assignment, i.e. "the cap must be per role, not global".
check(/define\("profileScav", "rookie"\)/.test(config), 'scavs are assigned a profile by name');
check(/define\("profileGunnerPillager", "rookie"\)/.test(config), 'the gunner pillager too');
check(/define\("profileGunnerVillager", "rookie"\)/.test(config), 'and the gunner villager');
check(/define\("profileSniperTier", "veteran"\)/.test(config),
  'and the sniper TIER is promoted to a better profile');
check(/enum Profile/.test(profile) && /ROOKIE\("rookie"\)/.test(profile) && /ELITE\("elite"\)/.test(profile),
  'the three profiles are a real enum');
check(/public static Profile byName/.test(profile) && /warnOnce/.test(profile),
  'an unknown profile name is WARNed and falls back to rookie');
check(/capFor\(sniper\) > capFor\(byType\)/.test(profile),
  'the tier promotion only ever RAISES the cap (a rule that cannot make a unit worse)');
check(/mob\.getType\(\) == com\.gfl\.tarkovscav\.registry\.ModEntities\.SCAV\.get\(\)/.test(profile),
  'the assignment is by entity type, so a data-pack unit falls back to rookie');

// Mirror of AccuracyProfile: warm-up, the value cap, and then the hit-chance clamp (same cone model).
function hitChanceOf(acc, distance, targetRadius) {
  const halfAngle = (1 - Math.max(0, Math.min(1, acc))) * 7;
  const sigma = Math.max(1e-6, halfAngle * 0.5);
  const angularRadius = Math.atan2(targetRadius, distance) * 180 / Math.PI;
  return Math.max(0, Math.min(1, erf(angularRadius / (sigma * Math.SQRT2))));
}
function clampToHitChance(acc, distance, targetRadius, cap) {
  if (cap >= 1 || distance <= 0 || targetRadius <= 0) return acc;
  if (hitChanceOf(acc, distance, targetRadius) <= cap) return acc;
  let low = 0;
  let high = acc;
  for (let i = 0; i < 40; i++) {
    const mid = (low + high) / 2;
    if (hitChanceOf(mid, distance, targetRadius) > cap) high = mid; else low = mid;
  }
  return low;
}
function accuracy(profileCap, shots, firedAt, now, tier, distance = 20, radius = 0.3,
                  warmup = WARMUP_SHOTS, mult = WARMUP_MULT) {
  let count = shots;
  if (RESET > 0 && firedAt !== 0 && now - firedAt > RESET) count = 0;
  const capped = Math.max(0, Math.min(capOf(profileCap), Math.min(1, profileCap)));
  const value = count < warmup ? tier * mult : tier;
  const value2 = Math.max(0, Math.min(value, Math.min(1, capped)));
  return clampToHitChance(value2, distance, radius, capped);
}
check(accuracy(ROOKIE, 0, 0, 0, 0.85) === 0.3825, 'shot 0 of a sniper uses the warm-up value',
  String(accuracy(ROOKIE, 0, 0, 0, 0.85)));
check(accuracy(ROOKIE, 7, 0, 0, 0.85) === 0.3825, 'shot 7 is still warming up');
check(accuracy(ROOKIE, 100, 1, RESET + 2, 0.85) === 0.3825, 'after the reset window the mob is cold again');
check(accuracy(ROOKIE, 8, 0, 0, 0.6) === 0.6, 'a rifleman below the cap is left alone (the cap is a ceiling)');
check(capOf(2.0) === HARD, 'a hand-edited cap of 2.0 is clamped to the hard ceiling',
  `${capOf(2.0)} (hardCeiling ${HARD})`);

console.log('');
console.log('3. every profile is a hit-chance ceiling (cone model, not a promise)');
const PLAYER_RADIUS = 0.3; // a player is 0.6 wide
check(/clampToHitChance/.test(profile) && /hitChanceFor/.test(profile),
  'the profile clamps on the hit chance, not only on the number');
check(/for \(int i = 0; i < 40; i\+\+\)/.test(profile),
  'and it does so by bisection on the same cone model the aim path uses');
for (const [name, cap] of [['rookie', ROOKIE], ['veteran', VETERAN], ['elite', ELITE]]) {
  for (const distance of [6, 10, 20, 30, 40, 52]) {
    const shipped = accuracy(cap, 100, 0, 0, 0.85, distance, PLAYER_RADIUS);
    const p = hitChanceOf(shipped, distance, PLAYER_RADIUS);
    check(p <= cap + 1e-9, `${name} at ${distance} blocks hits at most ${(100 * cap).toFixed(0)} %`,
      `${(100 * p).toFixed(1)}% (uncapped 0.85 would be `
      + `${(100 * hitChanceOf(0.85, distance, PLAYER_RADIUS)).toFixed(1)}%)`);
  }
}
// The whole point of per-role caps: the same shot is BETTER for the better profile.
const rookie20 = hitChanceOf(accuracy(ROOKIE, 100, 0, 0, 0.85, 20, PLAYER_RADIUS), 20, PLAYER_RADIUS);
const veteran20 = hitChanceOf(accuracy(VETERAN, 100, 0, 0, 0.85, 20, PLAYER_RADIUS), 20, PLAYER_RADIUS);
const elite20 = hitChanceOf(accuracy(ELITE, 100, 0, 0, 0.85, 20, PLAYER_RADIUS), 20, PLAYER_RADIUS);
check(rookie20 < veteran20 && veteran20 < elite20,
  'a better profile really is more accurate at the same range and tier',
  `rookie ${(100 * rookie20).toFixed(1)}% < veteran ${(100 * veteran20).toFixed(1)}% < elite `
  + `${(100 * elite20).toFixed(1)}% at 20 blocks`);
const warm = hitChanceOf(accuracy(ROOKIE, 0, 0, 0, 0.85, 20, PLAYER_RADIUS), 20, PLAYER_RADIUS);
check(warm < rookie20 * 0.8, 'the warm-up shots are decisively worse than the steady state',
  `${(100 * warm).toFixed(1)}% vs ${(100 * rookie20).toFixed(1)}% at 20 blocks`);
check(hitChanceOf(1.0, 20, PLAYER_RADIUS) === 1, 'accuracy 1.0 is a guaranteed hit - which the ceiling forbids',
  'so the ceiling is what keeps a straight fight winnable');

console.log('');
console.log('4. persistence, and the debug readout');
check(/TAG_SHOTS = "tarkovscav:shotsFired"/.test(profile)
  && /TAG_LAST_SHOT = "tarkovscav:lastShotTick"/.test(profile),
  'the counter lives in the mob\'s persistent data (survives a reload)');
check(/getPersistentData\(\)\.getInt\(TAG_SHOTS\)/.test(profile) && /putInt\(TAG_SHOTS/.test(profile),
  'and is read and written through the same key');
check(/ACCURACY_ROOKIE_CAP/.test(config) && /ACCURACY_HARD_CEILING/.test(config)
  && /\.push\("accuracy"\)/.test(config), 'the keys live in their own [accuracy] section');
check(/AccuracyProfile\.describe\(mob\)/.test(debug), '/tarkovscav debug reports the profile');
for (const key of ['hardCeiling', 'profileRookieCap', 'profileVeteranCap', 'profileEliteCap',
  'profileScav', 'profileGunnerPillager', 'profileGunnerVillager', 'profileSniperTier',
  'warmupShots', 'warmupMultiplier', 'resetTicks', 'enabled']) {
  check(readme.includes(key), `README documents accuracy.${key}`);
}
check(!/steadyStateCap/.test(config) && !/steadyStateCap/.test(readme),
  'the old global steadyStateCap key is gone from both code and docs (replaced by the profiles)');
check(/### 5o\./.test(readme), 'README has the 5o section');
check(/误差锥角|error cone|cone/.test(readme),
  'README explains that accuracy scales the error cone rather than rolling a hit dice');
check(/shots=/.test(profile) && /debug/i.test(readme.slice(readme.indexOf('### 5o.'), readme.indexOf('### 5o.') + 6000)),
  'README mentions the debug readout');

console.log('');
if (failures > 0) {
  console.log(`${failures} accuracy check(s) FAILED`);
  process.exit(1);
}
console.log('accuracy profile invariants all hold');
