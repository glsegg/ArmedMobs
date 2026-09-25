// Self-test for the aim-tracking rule in RigSupport.applyAimTracking.
//
// Why this exists: the bug it guards against is invisible in the assets - no NaN, no out-of-range
// keyframe, nothing in the geo file. It was a rule in Java code that only misbehaves because of how
// GeckoLib drives bones every frame, so it has to be tested as a *rule*, not as data.
//
// Part 1 simulates GeckoLib 4.8's per-frame bone handling for a bone that no clip animates:
//
//   AnimationProcessor#tickAnimation (4.8.4, lines 133-174):
//       if (!bone.hasRotationChanged()) { bone.setRot*(lerp(snapshot, initialSnapshot, ...)); }
//   GeoBone#setRotY / setRotX:  this.rotY = value; markRotationAsChanged();
//   resetBoneTransformationMarkers(): every bone's changed-marker is cleared at the end of the tick.
//
//   => a "+=" write marks the bone changed, the reset branch is skipped, and the next frame adds to
//      its own previous value again. Unbounded growth, one increment per rendered frame.
//   => a "rest + share" write is idempotent, so the frame count cannot change the pose.
//
// Part 2 is the regression guard: it greps the real source for the additive pattern on the aim bones
// and for the rest-rotation base, so a future edit cannot quietly reintroduce the accumulation.
//
//   node tools/selftest_rig_pose.js
const fs = require('fs');
const path = require('path');

const RIG_SUPPORT = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'client', 'RigSupport.java');
const DEG = Math.PI / 180;

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

// ------------------------------------------------------------------ part 1: the rule
// A mob standing still and aiming at a player one block away: the body already faces the target, so
// the head/body yaw offset is small. 10 degrees is a deliberately conservative choice - the old rule
// needs a much smaller offset than this to run away.
const NET_HEAD_YAW = 10.0;
const HEAD_PITCH = 12.0;
// The shipped client.torsoYawShare. The rule is a share of the look yaw on the torso with the head
// taking the rest, so the value only moves the twist between the two bones, never the total.
const TORSO_SHARE = (() => {
  const m = /DEFAULT_TORSO_YAW_SHARE\s*=\s*([0-9.]+)D/.exec(
    fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java'), 'utf8'));
  return m ? Number(m[1]) : 0.25;
})();
const PITCH_SHARE = 0.4;
const FRAMES = 60;

// GeckoLib keeps this per bone; the rig's UpperBody has no rest rotation at all (no "rotation" key in
// the geo file, and no clip animates it), so the rest value is 0 rad.
const REST_Y = 0.0;
const REST_X = 0.0;

const simulate = (additive) => {
  let rotY = REST_Y;
  let rotX = REST_X;
  for (let frame = 0; frame < FRAMES; frame++) {
    // tickAnimation's reset branch: skipped for a bone whose changed-marker is still set.
    if (additive && frame > 0) {
      // nothing resets it - the previous frame's write is what the next frame builds on
    } else if (!additive) {
      rotY = REST_Y;
      rotX = REST_X;
    }
    // applyAimTracking
    if (additive) {
      rotY = rotY + NET_HEAD_YAW * TORSO_SHARE * DEG;
      rotX = rotX + HEAD_PITCH * PITCH_SHARE * DEG;
    } else {
      rotY = REST_Y + NET_HEAD_YAW * TORSO_SHARE * DEG;
      rotX = REST_X + HEAD_PITCH * PITCH_SHARE * DEG;
    }
  }
  return { rotY, rotX };
};

console.log(`aim tracking over ${FRAMES} rendered frames (netHeadYaw=${NET_HEAD_YAW}, headPitch=${HEAD_PITCH}):`);

const oldRule = simulate(true);
const newRule = simulate(false);
const expectedY = NET_HEAD_YAW * TORSO_SHARE * DEG;

console.log(`  additive "+=" rule : torso rotY=${oldRule.rotY.toFixed(3)} rad = ${(oldRule.rotY / DEG).toFixed(1)} deg`
  + `, rotX=${(oldRule.rotX / DEG).toFixed(1)} deg`);
console.log(`  absolute rule     : torso rotY=${newRule.rotY.toFixed(3)} rad = ${(newRule.rotY / DEG).toFixed(1)} deg`
  + `, rotX=${(newRule.rotX / DEG).toFixed(1)} deg`);

check(oldRule.rotY > 10 * NET_HEAD_YAW * TORSO_SHARE * DEG,
  'the old additive rule grows linearly with the frame count (10x the correct single-frame share within one second)',
  `(${(oldRule.rotY / DEG).toFixed(0)} deg after ${FRAMES} frames, the correct value is ${(NET_HEAD_YAW * TORSO_SHARE).toFixed(1)} deg)`);
check(Math.abs(newRule.rotY - expectedY) < 1e-6, 'the absolute rule equals rest + share on every frame',
  `(${(newRule.rotY / DEG).toFixed(2)} deg, expected ${(expectedY / DEG).toFixed(2)} deg)`);

// At 60 fps the torso, the arms, the head and the gun pivot around the shoulder at a rate that
// depends only on how long the mob has been aiming.
const degreesPerSecondOld = oldRule.rotY / DEG * 60 / FRAMES;
console.log(`  -> the old rule turns the upper body ${degreesPerSecondOld.toFixed(0)} deg/second while aiming`);

// ------------------------------------------------------------------ part 2: the source guard
const source = fs.readFileSync(RIG_SUPPORT, 'utf8');
const body = source.slice(source.indexOf('applyAimTracking'));

// A write that reads the *bone's own* rotation is the bug; reading a snapshot is the fix.
const readsOwnRotation = /(upperBody|head)\s*\.\s*getRot[XYZ]\s*\(/.test(body);
const usesRestSnapshot = /getInitialSnapshot\s*\(\s*\)/.test(body);
const additiveOnAimBones = /(upperBody|head)\s*\.\s*setRot[XYZ]\s*\([^;]*\b(upperBody|head)\s*\.\s*getRot/.test(body);

console.log('source guard (RigSupport.applyAimTracking):');
check(!readsOwnRotation, 'no write builds on the bone\'s current rotation (getRotX/getRotY/getRotZ)');
check(!additiveOnAimBones, 'no additive write on UpperBody/Head');
check(usesRestSnapshot, 'the torso write is based on the bone\'s initial (rest) snapshot');

// The two other ways this bug hides. The torso write has to stay behind an "aiming" guard, because
// idle/walk/run/death *do* animate UpperBody - an unconditional write would freeze those clips - and
// what makes it safe behind that guard is that it is absolute, not that it is frequent.
//
// The guard is now `torsoOwns` rather than `aiming && upperBody != null`, because the write also has to
// yield to a clip that owns the bone (client.poseSource). The two halves are checked separately so
// neither can be dropped: the ownership predicate must still require `aiming`, and the write must be
// gated on the predicate.
check(/boolean torsoOwns = [^;]*upperBody != null\s*&&\s*aiming\s*&&/.test(body),
  'the torso write is still guarded by "aiming", so the unarmed/death clips keep their UpperBody keyframes');
check(/if \(torsoOwns\) \{\s*\n\s*PoseWriters\.note\(writers, "UpperBody", PoseWriters\.CODE\);/.test(body),
  'the torso write is gated on that single ownership predicate and nothing else');
check(/torsoShare\s*=\s*aiming\s*\?\s*Config\.torsoYawShare\(\)\s*:\s*0\.0F/.test(body),
  'the torso share is the configured 0..1 lean, so the aim share itself cannot run away');

// The share fold, which is what keeps the aim gain at 1.0 once a clip can own one of the two bones.
check(/float headYawShare = 1\.0F - torsoShare;/.test(body)
    && /float headPitchShare = 1\.0F - pitchShare;/.test(body),
  'the head share is the complement of the torso share (the code gain is 1.0 by definition)');
check(/if \(torsoOwns && !headOwns\) \{\s*\n\s*torsoShare = 1\.0F;\s*\n\s*pitchShare = 1\.0F;/.test(body)
    && /\} else if \(!torsoOwns && headOwns\) \{\s*\n\s*headYawShare = 1\.0F;\s*\n\s*headPitchShare = 1\.0F;/.test(body),
  'when one bone is the clip\'s the other takes the whole share, so the total gain stays 1.0');

// And the arithmetic itself, for every combination of (aiming, torso owned, head owned). A gain of 1.0
// means "the total yaw the code contributes still follows netHeadYaw once", which is the property that
// stops a revived author track from doubling or halving the aim.
const codeGain = (aiming, torsoOwns, headOwns) => {
  const share = TORSO_SHARE;
  let torsoShare = aiming ? share : 0.0;
  let pitchShare = aiming ? 0.4 : 0.0;
  let headYawShare = 1.0 - torsoShare;
  let headPitchShare = 1.0 - pitchShare;
  if (torsoOwns && !headOwns) {
    torsoShare = 1.0;
    pitchShare = 1.0;
  } else if (!torsoOwns && headOwns) {
    headYawShare = 1.0;
    headPitchShare = 1.0;
  }
  return {
    yaw: (torsoOwns ? torsoShare : 0) + (headOwns ? headYawShare : 0),
    pitch: (torsoOwns ? pitchShare : 0) + (headOwns ? headPitchShare : 0),
  };
};
console.log('code-side aim gain over every ownership combination (Y = yaw, P = pitch):');
for (const aiming of [true, false]) {
  for (const torsoOwns of [true, false]) {
    for (const headOwns of [true, false]) {
      const gain = codeGain(aiming, torsoOwns, headOwns);
      // Nothing owned means the clips own the whole aim and the code must contribute exactly nothing.
      const expected = torsoOwns || headOwns ? 1.0 : 0.0;
      check(Math.abs(gain.yaw - expected) < 1e-9 && Math.abs(gain.pitch - expected) < 1e-9,
        `aiming=${aiming} torso=${torsoOwns} head=${headOwns} -> yaw gain ${gain.yaw}`,
        `pitch gain ${gain.pitch}`);
    }
  }
}

// ------------------------------------------------------------------ part 3: the head rest pitch
//
// The report: the head sits ~20 degrees low while the mob is IDLE, and is correct as soon as it is
// alerted or shot at. The fix is a constant added to the head INSIDE the non-aiming branch only, so the
// aiming path is the expression it always was. These checks pin both halves down.
const CONFIG = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav', 'Config.java');
const config = fs.readFileSync(CONFIG, 'utf8');

const REST_PITCH = (() => {
  const m = /defineInRange\("headRestPitchDegrees",\s*([0-9.]+)D/.exec(config);
  return m ? Number(m[1]) : null;
})();
const REST_STATES = (() => {
  const m = /defineListAllowEmpty\(List\.of\("headRestPitchStates"\),\s*\(\)\s*->\s*List\.of\(([^)]*)\)/.exec(config);
  return m ? m[1].split(',').map((s) => s.trim().replace(/^"|"$/g, '')) : null;
})();
console.log('\nhead rest pitch (RigSupport.applyAimTracking):');
check(REST_PITCH === 20, 'client.headRestPitchDegrees defaults to 20', `(${REST_PITCH})`);
check(JSON.stringify(REST_STATES) === '["idle"]',
  'it applies to the idle state only by default', JSON.stringify(REST_STATES));
check(/float restPitch = 0\.0F;\s*\n\s*if \(!aiming && Config\.headRestPitchAppliesTo\(state\)\)/.test(body),
  'restPitch is non-zero only when NOT aiming and the synced state is in scope');
// The aiming path must be the same expression it was before this feature existed - only the name of the
// share changed, from "(1 - pitchShare)" to headPitchShare, which is that same value when both bones
// are the code's (see the share fold above).
check(/else\s*\{\s*\n\s*head\.setRotX\(headPitch \* headPitchShare \* Mth\.DEG_TO_RAD\);/.test(body),
  'the aiming / out-of-scope branch is still exactly headPitch * the head pitch share');
check(/head\.setRotX\(\(headPitch \* headPitchShare \+ restPitch\) \* Mth\.DEG_TO_RAD\)/.test(body),
  'the in-scope branch is rest + share + correction (absolute, so idempotent)');
check(/if \(headOwns\) \{\s*\n\s*PoseWriters\.note\(writers, "Head", PoseWriters\.CODE\);/.test(body),
  'the head write is gated on the head ownership predicate, so a head-owning clip cannot be overwritten');

const headWrite = body.slice(body.indexOf('CoreGeoBone head'), body.indexOf('gunAiState'));
check(!/head\s*\.\s*getRot[XYZ]/.test(headWrite),
  'the head write never reads the bone\'s own rotation (that is the accumulation bug)');
check(/head\.setRotY\(netHeadYaw \* headYawShare \* Mth\.DEG_TO_RAD\)/.test(headWrite),
  'the head yaw write is the head share of netHeadYaw and nothing else');

// Simulate one frame of the real expression both ways, for every (aiming, state) combination.
const simulateHead = (aiming, state, netHeadYaw, headPitch) => {
  const torsoShare = aiming ? 0.7 : 0.0;
  const pitchShare = aiming ? 0.4 : 0.0;
  const inScope = REST_STATES.some((s) => s === 'any' || s.toUpperCase() === state);
  const restPitch = !aiming && inScope ? REST_PITCH : 0.0;
  return {
    rotY: netHeadYaw * (1.0 - torsoShare) * DEG,
    rotX: (headPitch * (1.0 - pitchShare) + restPitch) * DEG,
    before: headPitch * (1.0 - pitchShare) * DEG,
  };
};
const idle = simulateHead(false, 'IDLE', 10, 12);
const alert = simulateHead(false, 'ALERT', 10, 12);
const aimingNow = simulateHead(true, 'AIM', 10, 12);

check(Math.abs(idle.rotX - (idle.before + REST_PITCH * DEG)) < 1e-6,
  'idle: the head is raised by exactly the configured angle');
check(Math.abs(alert.rotX - alert.before) < 1e-9,
  'alert (out of scope): the head is unchanged');
check(Math.abs(aimingNow.rotX - aimingNow.before) < 1e-9,
  'aiming: the head is bit-for-bit what it was before this feature');
check(Math.abs(aimingNow.rotY - 10 * 0.3 * DEG) < 1e-9,
  'aiming: the yaw share (30 % to the head) is unchanged');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);