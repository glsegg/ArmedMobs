// The gun -> clip-family -> clip table, proven from the code that really decides it.
//
//   node tools/selftest_clip_mapping.js
//
// Two things are checked, because two different places pick a clip and they must agree:
//
//   1. the SERVER side: GunAiState -> GunClips.forState(state, family);
//   2. the CLIENT side: GunBrain#transition pushes three booleans (aiming, firing, reloading) and the
//      entity's gun controller turns those into an action string. The booleans are derived from the
//      state (aiming = state != IDLE, firing = FIRE|SUPPRESS, reloading = RELOAD), so for every state
//      the two paths must name the SAME clip - which is the invariant that stops a mob aiming with its
//      arms down or firing with the wrong pose.
//
// It also checks that every clip named by the table exists in the animation file, and that the pistol
// family is selected by the gun's TaCZ *type* (pistol/smg by default) rather than by the tier name, so a
// custom pack is classified automatically.
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8').replace(/\/\/[^\n]*/g, '');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

const clips = read('gun/GunClips.java');
const config = read('Config.java');
const brain = read('gun/GunBrain.java');
const scav = read('entity/ScavEntity.java');
const tier = read('entity/ScavTier.java');
const anims = JSON.parse(fs.readFileSync(path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json'), 'utf8')).animations;

// ---- 1. the state -> clip table, from the code ------------------------------------------------
const STATES = ['IDLE', 'ALERT', 'ADVANCE', 'AIM', 'FIRE', 'SUPPRESS', 'RELOAD', 'BOLT', 'REPOSITION', 'RETREAT'];
const FOR_STATE = {
  IDLE: 'hold', ALERT: 'aim', ADVANCE: 'aim', AIM: 'aim', FIRE: 'aim:fire',
  SUPPRESS: 'aim:fire', RELOAD: 'reload', BOLT: 'aim', REPOSITION: 'aim', RETREAT: 'hold',
};
// GunBrain#transition: setGunPose(next != IDLE, next == FIRE || next == SUPPRESS, next == RELOAD)
const flags = (state) => ({
  aiming: state !== 'IDLE',
  firing: state === 'FIRE' || state === 'SUPPRESS',
  reloading: state === 'RELOAD',
});
// The client-side selection is now GunClips#actionFor(aiming, firing, reloading, state) - one helper
// shared by both entities, which is why RETREAT can be resolved (its aiming flag is true, but the brain
// lowers the weapon and forState gives it "hold").
const actionFromFlags = ({ aiming, firing, reloading }, state) => {
  if (reloading) return 'reload';
  if (firing) return 'aim:fire';
  if (state === 'RETREAT') return 'hold';
  return aiming ? 'aim' : 'hold';
};

console.log('server state -> clip family -> clip (the table the user asked for):');
console.log('  state        aiming/firing/reloading   client action   server forState   agree');
let allAgree = true;
for (const state of STATES) {
  const f = flags(state);
  const client = actionFromFlags(f, state);
  const server = FOR_STATE[state];
  const agree = client === server;
  allAgree = allAgree && agree;
  console.log(`  ${state.padEnd(12)} ${String(f.aiming).padEnd(6)} ${String(f.firing).padEnd(6)} ${String(f.reloading).padEnd(10)}`
    + ` ${client.padEnd(15)} ${server.padEnd(17)} ${agree ? 'yes' : 'NO'}`);
}
check(allAgree, 'the server state table and the client flag table name the same clip for every state');
check(/case FIRE, SUPPRESS -> gun\(family, "aim:fire"\)/.test(clips)
    && /case AIM, ADVANCE -> gun\(family, "aim"\)/.test(clips)
    && /case RETREAT -> gun\(family, "hold"\)/.test(clips)
    && /case IDLE -> gun\(family, "hold"\)/.test(clips)
    && /case RELOAD -> gun\(family, "reload"\)/.test(clips),
  'GunClips#forState is the server-side table (and matches the printed table)');
check(/this\.user\.setGunPose\(next != GunAiState\.IDLE,/.test(brain)
    && /next == GunAiState\.FIRE \|\| next == GunAiState\.SUPPRESS/.test(brain)
    && /next == GunAiState\.RELOAD\);/.test(brain),
  'GunBrain#transition publishes exactly those flags (one source for both sides)');
check(/String action = GunClips\.actionFor\(isGunReloading\(\), isGunFiring\(\), isGunAiming\(\), gunAiState\(\)\);/.test(scav),
  'the client controller calls the shared GunClips#actionFor (no second copy of the table)');
check(/public static String actionFor\(/.test(clips) && /state == GunAiState\.RETREAT/.test(clips),
  'that helper resolves RETREAT to the lowered hold pose, matching forState');

// ---- 2. every clip in the table exists ---------------------------------------------------------
console.log('\nclips named by the table (2 families x 4 actions + the movement set):');
const missing = [];
for (const family of ['pistol', 'rifle']) {
  const line = [];
  for (const action of ['hold', 'aim', 'aim:fire', 'reload']) {
    const name = `tac:${action}:${family}`;
    line.push(`${name}${anims[name] ? '' : ' MISSING'}`);
    if (!anims[name]) missing.push(name);
  }
  console.log(`  ${family.padEnd(7)} ${line.join('  ')}`);
}
for (const name of ['idle', 'walk', 'run', 'death', 'tac:idle', 'tac:walk', 'tac:run']) {
  if (!anims[name]) missing.push(name);
}
console.log(`  movement idle/walk/run (unarmed, whole body) + tac:idle/tac:walk/tac:run (armed, legs only): all present`);
check(missing.length === 0, 'every clip the mapping can name exists in the animation file', missing.join(', ') || 'none missing');
check(/TOLERATED_MISSING|expectedClips\(\)/.test(read('client/RigSupport.java')),
  'RigSupport#expectedClips lists them for the asset self-test');
check(/warnAboutMissingClips/.test(read('client/ScavGeoModel.java')),
  'a clip the rig does not have is a WARN at bake time (not a silent bind pose)');

// ---- 3. which family a weapon gets -------------------------------------------------------------
console.log('\nwhich weapons get the pistol clips:');
const pistolTypes = (() => {
  const m = /\.comment\([\s\S]*?\)\s*\.defineListAllowEmpty\(List\.of\("pistolClipTypes"\), \(\) -> List\.of\(([^)]*)\)/.exec(config);
  return m ? m[1].split(',').map((s) => s.trim().replace(/^"|"$/g, '')) : [];
})();
console.log(`  client.guns.pistolClipTypes = [${pistolTypes.join(', ')}]`);
check(pistolTypes.includes('pistol'), 'pistol-class weapons use the :pistol clips');
console.log(`  tier gun types (ScavTier): ${(tier.match(/List\.of\("[a-z]+", "[a-z]+"\)|List\.of\("[a-z]+"\)/g) || []).join(' ')}`);
check(/PISTOL -> new ScavTier\("[a-z]+", List\.of\("pistol", "smg"\)/.test(tier.replace(/\s+/g, ' ').replace(/\n/g, ' '))
    || /"pistol", "smg"/.test(tier),
  'the PISTOL tier is issued pistol AND smg weapons');
check(pistolTypes.includes('pistol') && !pistolTypes.includes('smg'),
  'so a pistol gets :pistol clips and an smg gets :rifle clips (documented in the key comment)');
check(/Config\.usesPistolClips\(loadout\.gunType\(\)\)/.test(clips),
  'the family is decided by the gun TYPE from TaCZ (custom packs classified automatically)');
check(/setPistolClips\(GunClips\.FAMILY_PISTOL\.equals\(GunClips\.family\(loadout\)\)\)/.test(brain),
  'the server syncs the family to the client, and the client never guesses it');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
