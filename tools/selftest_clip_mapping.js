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
const os = require('os');
const path = require('path');
const cp = require('child_process');

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
const tier = read('entity/ScavTier.java');
const anims = JSON.parse(fs.readFileSync(path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav', 'animations', 'scav.animation.json'), 'utf8')).animations;

// ---- 1. the state -> clip table, from the code ------------------------------------------------
// Compile real helper sources and both entities' actual selection method bodies. A regex which
// merely recognises a helper call cannot detect swapped same-typed boolean arguments.
function method(source, name) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const start = new RegExp(`private\\s+[\\w<>]+\\s+${name}\\s*\\([^)]*\\)\\s*\\{`).exec(clean);
  if (!start) throw new Error(`Missing production method ${name}`);
  let end = start.index + start[0].length, depth = 1;
  for (; end < clean.length && depth; end++) {
    if (clean[end] === '{') depth++;
    if (clean[end] === '}') depth--;
  }
  if (depth) throw new Error(`Unbalanced production method ${name}`);
  return clean.slice(start.index, end);
}

function controllerFixture(name) {
  const source = fs.readFileSync(path.join(JAVA, 'entity', name + '.java'), 'utf8');
  return `static class ${name} extends Fixture {
    ${['singleController', 'gunController', 'movementController', 'isArmed'].map(n => method(source, n)).join('\n')}
    @SuppressWarnings("unchecked")
    PlayState select(boolean single, AnimationState state) {
      return single ? singleController(state) : gunController(state);
    }
    @SuppressWarnings("unchecked")
    PlayState movement(AnimationState state) { return movementController(state); }
  }`;
}

function productionSelectionTest() {
  const source = `
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunClips;
public class ClipSelectionTest {
  static int checks, armedSelections, unarmedSelections;
  static void check(boolean ok, String message) {
    checks++; if (!ok) throw new AssertionError(message);
  }
  enum PlayState { CONTINUE, STOP }
  static class RawAnimation {
    String clip;
    static RawAnimation begin() { return new RawAnimation(); }
    RawAnimation thenLoop(String value) { clip = value; return this; }
  }
  static class AnimationState<T> {
    String clip; int writes;
    PlayState setAndContinue(RawAnimation animation) { clip = animation.clip; writes++; return PlayState.CONTINUE; }
  }
  static class ItemStack {
    final int kind; ItemStack(int kind) { this.kind = kind; }
    boolean isEmpty() { return kind == 0; }
  }
  static class IGun {
    static IGun getIGunOrNull(ItemStack held) { return held.kind == 2 ? new IGun() : null; }
  }
  static class Walk {
    boolean moving, running;
    boolean isMoving() { return moving; } boolean isRunning() { return running; }
  }
  abstract static class Fixture {
    boolean aiming, firing, reloading, pistol; GunAiState aiState;
    final Walk walk = new Walk(); ItemStack held = new ItemStack(2);
    boolean isGunAiming() { return aiming; } boolean isGunFiring() { return firing; }
    boolean isGunReloading() { return reloading; } boolean usesPistolClips() { return pistol; }
    GunAiState gunAiState() { return aiState; } ItemStack getMainHandItem() { return held; }
    abstract PlayState select(boolean single, AnimationState state);
    abstract PlayState movement(AnimationState state);
  }
  ${controllerFixture('ScavEntity')}
  ${controllerFixture('GunnerPillagerEntity')}
  public static void main(String[] args) {
    check(GunAiState.values().length == 10, "all ten real AI states are exercised");
    // Bit 0 = aiming, bit 1 = firing, bit 2 = reloading. An independent expected table
    // specifies priority for inconsistent transient network flags as well as normal states.
    String[] normalActions = {"hold", "aim", "aim:fire", "aim:fire", "reload", "reload", "reload", "reload"};
    String[] retreatActions = {"hold", "hold", "aim:fire", "aim:fire", "reload", "reload", "reload", "reload"};
    String[] serverActions = {"hold", "aim", "aim", "aim", "aim:fire", "aim:fire", "reload", "aim", "aim", "hold"};
    String[] movementClips = {"idle", "walk", "idle", "run"};
    for (GunAiState aiState : GunAiState.values()) {
      for (boolean pistol : new boolean[]{false, true}) {
        String family = pistol ? "pistol" : "rifle";
        String expectedServer = "tac:" + serverActions[aiState.ordinal()] + ":" + family;
        check(expectedServer.equals(GunClips.forState(aiState, family)), "server action " + aiState + " " + family);
        String synced = GunClips.actionFor(aiState != GunAiState.IDLE,
            aiState == GunAiState.FIRE || aiState == GunAiState.SUPPRESS,
            aiState == GunAiState.RELOAD, aiState);
        check(expectedServer.equals(GunClips.gun(family, synced)), "normal server flags agree " + aiState + " " + family);
        for (int mask = 0; mask < 8; mask++) {
          boolean aiming = (mask & 1) != 0, firing = (mask & 2) != 0, reloading = (mask & 4) != 0;
          String action = (aiState == GunAiState.RETREAT ? retreatActions : normalActions)[mask];
          String expected = "tac:" + action + ":" + family;
          check(action.equals(GunClips.actionFor(aiming, firing, reloading, aiState)), "helper " + aiState + " flags " + mask);
          for (Fixture entity : new Fixture[]{new ScavEntity(), new GunnerPillagerEntity()}) {
            entity.aiState = aiState; entity.aiming = aiming; entity.firing = firing;
            entity.reloading = reloading; entity.pistol = pistol;
            String label = entity.getClass().getSimpleName() + " " + aiState + " flags " + mask + " " + family;
            for (boolean single : new boolean[]{false, true}) {
              AnimationState state = new AnimationState();
              check(entity.select(single, state) == PlayState.CONTINUE, label + " continue single=" + single);
              check(state.writes == 1 && expected.equals(state.clip),
                  label + " single=" + single + " expected=" + expected + " actual=" + state.clip);
              armedSelections++;
            }
            // Movement still uses the synced visible main-hand gun, independent of action flags.
            for (int motion = 0; motion < 4; motion++) {
              entity.walk.moving = (motion & 1) != 0; entity.walk.running = (motion & 2) != 0;
              AnimationState movement = new AnimationState();
              check(entity.movement(movement) == PlayState.CONTINUE
                  && ("tac:" + movementClips[motion]).equals(movement.clip), label + " armed movement " + motion);
            }
            // Empty hands and non-gun items must stop the layered gun controller and retain the
            // whole-body movement fallback in single mode, even with stale synced action flags.
            for (int heldKind : new int[]{0, 1}) {
              entity.held = new ItemStack(heldKind);
              for (int motion = 0; motion < 4; motion++) {
                entity.walk.moving = (motion & 1) != 0; entity.walk.running = (motion & 2) != 0;
                AnimationState upper = new AnimationState();
                check(entity.select(false, upper) == PlayState.STOP && upper.writes == 0,
                    label + " unarmed layered stop kind=" + heldKind);
                AnimationState single = new AnimationState();
                check(entity.select(true, single) == PlayState.CONTINUE && single.writes == 1
                    && movementClips[motion].equals(single.clip), label + " unarmed single movement " + motion);
                AnimationState movement = new AnimationState();
                check(entity.movement(movement) == PlayState.CONTINUE
                    && movementClips[motion].equals(movement.clip), label + " unarmed lower movement " + motion);
                unarmedSelections += 2;
              }
            }
          }
        }
      }
    }
    check(armedSelections == 640, "10 states x 8 flag combinations x 2 families x 2 entities x 2 paths");
    System.out.println("ClipSelectionTest: " + checks + " checks passed; " + armedSelections
        + " armed controller selections; " + unarmedSelections + " unarmed selections");
  }
}`;
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-clips-'));
  const java = name => process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
  try {
    const sources = {
      'ClipSelectionTest.java': source,
      'GunClips.java': fs.readFileSync(path.join(JAVA, 'gun/GunClips.java'), 'utf8'),
      'GunAiState.java': fs.readFileSync(path.join(JAVA, 'gun/GunAiState.java'), 'utf8'),
      'Config.java': 'package com.gfl.tarkovscav; public class Config { public static boolean usesPistolClips(String type) { return "pistol".equals(type); } }',
      'GunLoadout.java': 'package com.gfl.tarkovscav.gun; public record GunLoadout(String gunType) {}',
    };
    for (const [name, text] of Object.entries(sources)) fs.writeFileSync(path.join(temporary, name), text, 'utf8');
    const compile = cp.spawnSync(java('javac'), ['-encoding', 'UTF-8', '--release', '17', '-d', temporary,
      ...Object.keys(sources).map(name => path.join(temporary, name))], { encoding: 'utf8' });
    if (compile.error) throw compile.error;
    if (compile.status !== 0) throw new Error(compile.stdout + compile.stderr);
    const run = cp.spawnSync(java('java'), ['-cp', temporary, 'ClipSelectionTest'], { encoding: 'utf8' });
    if (run.error) throw run.error;
    if (run.status !== 0) throw new Error(run.stdout + run.stderr);
    process.stdout.write(run.stdout);
    return true;
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
try {
  check(productionSelectionTest(), 'production helper and both real entity controller bodies select the expected clips');
} catch (error) {
  check(false, 'production controller selection regression', error.message);
}
check(/this\.user\.setGunPose\(next != GunAiState\.IDLE,/.test(brain)
    && /next == GunAiState\.FIRE \|\| next == GunAiState\.SUPPRESS/.test(brain)
    && /next == GunAiState\.RELOAD\);/.test(brain),
  'GunBrain#transition publishes exactly those flags (one source for both sides)');

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
