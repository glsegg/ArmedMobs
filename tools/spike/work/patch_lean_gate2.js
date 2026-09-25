// scratch: lean gate part 2 - suppression semantics, the AT instead of reflection, invert switches
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_lean.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 80));
    return;
  }
  text = text.replace(from, to);
};

// --- 2. the old screen-cancel check becomes the "short tap does nothing" checks -------------------
sub(`check(/public static void onScreenOpening\\(ScreenEvent\\.Opening event\\)/.test(client)
  && /event\\.getNewScreen\\(\\) instanceof InventoryScreen/.test(client)
  && /event\\.setCanceled\\(true\\)/.test(client),
  'the inventory screen is cancelled outright (order-independent, and it covers every route in)');`,
  `check(!/ScreenEvent\\.Opening|setCanceled\\(true\\)/.test(client),
  'the inventory is NOT done by cancelling the screen any more: only the key click is consumed, so nothing'
    + ' else that opens the backpack (a command, another mod) is affected');
check(/if \\(minecraft\\.player == null \\|\\| !suppressing\\(\\)\\)/.test(client)
  && !/lean == 0\\.0F\\) \\{\\s*return;\\s*\\}\\s*KeyMapping/m.test(client),
  'and the consumption does NOT depend on a lean being active: a SHORT TAP of Q/E is swallowed too');`);

sub(`check(/if \\(minecraft\\.player == null \\|\\| !suppressing\\(\\)\\)/.test(client)
  && /if \\(!suppressing\\(\\) \\|\\| lean == 0\\.0F\\)/.test(client),
  'and neither fires unless the feature is on and a lean key is actually held');`,
  `check(/for \\(KeyMapping vanilla : occupiedVanillaKeys\\(minecraft\\)\\)/.test(client)
  && /minecraft\\.options\\.keyDrop/.test(client) && /minecraft\\.options\\.keyInventory/.test(client),
  'only the two VANILLA keys we occupy are touched (nothing else, and no blanket screen cancel)');`);

// --- 3. camera: access transformer, direct call, no reflection -----------------------------------
sub(`check(/findCameraSetPosition\\(\\)[\\s\\S]{0,400}?method\\.getReturnType\\(\\) == void\\.class && method\\.getParameterCount\\(\\) == 1\\s*&& method\\.getParameterTypes\\(\\)\\[0\\] == Vec3\\.class/
  .test(client),
  'Camera#setPosition is found BY SIGNATURE (its name is SRG at runtime)');`,
  `check(!/java\\.lang\\.reflect|getDeclaredMethods|Method\\.invoke|setAccessible/.test(client),
  'there is NO reflection left in the client: the old by-signature lookup is gone');
check(/camera\\.setPosition\\(position\\);/.test(client),
  'Camera#setPosition is called DIRECTLY (possible only because the access transformer made it public)');
check(fs.existsSync(path.join(ROOT, 'src', 'main', 'resources', 'META-INF', 'accesstransformer.cfg')),
  'the access transformer file exists');
check(/public net\\.minecraft\\.client\\.Camera m_90581_\\(Lnet\\/minecraft\\/world\\/phys\\/Vec3;\\)V/
  .test(fs.readFileSync(path.join(ROOT, 'src', 'main', 'resources', 'META-INF', 'accesstransformer.cfg'),
    'utf8')),
  'and it publishes Camera#setPosition(Vec3) under its SRG name (m_90581_, checked against the tsrg)');
check(/accessTransformer = file\\('src\\/main\\/resources\\/META-INF\\/accesstransformer\\.cfg'\\)/
  .test(fs.readFileSync(path.join(ROOT, 'build.gradle'), 'utf8')),
  'and build.gradle declares it (so the dev classes are transformed and the jar ships it)');
check(/cameraSlideMode\\(\\)/.test(client) && /return cameraMoveUnavailable \\? "unavailable" : "at";/.test(client),
  'client state reports how the slide is implemented (at | unavailable)');`);

// --- 3b. the roll check follows the new signed/rollLean split ------------------------------------
sub(`  && /event\\.setRoll\\(event\\.getRoll\\(\\) - \\(float\\) \\(Config\\.LEAN_ROLL_DEGREES\\.get\\(\\) \\* lean\\)\\)/
    .test(client),
  'the roll is applied through the public camera-angle event');`,
  `  && /event\\.setRoll\\(event\\.getRoll\\(\\) - \\(float\\) \\(Config\\.LEAN_ROLL_DEGREES\\.get\\(\\) \\* rollLean\\)\\)/
    .test(client),
  'the roll is applied through the public camera-angle event');
check(/float signed = lean \\* \\(Config\\.LEAN_INVERT_OFFSET\\.get\\(\\) \\? -1\\.0F : 1\\.0F\\);/.test(client)
  && /float rollLean = lean \\* \\(Config\\.LEAN_INVERT_ROLL\\.get\\(\\) \\? -1\\.0F : 1\\.0F\\);/.test(client)
  && /LeanMath\\.offsetFor\\(event\\.getYaw\\(\\), signed, Config\\.LEAN_MAX_OFFSET\\.get\\(\\)\\)/.test(client),
  'the offset and the roll each apply the SAME lean value with their own invert switch (one axis each)');`);

// --- 7. config: the new default and the two invert switches --------------------------------------
sub("['LEAN_MAX_OFFSET', 'defineInRange(\"leanMaxOffset\", 0.5D'],",
  "['LEAN_MAX_OFFSET', 'defineInRange(\"leanMaxOffset\", 0.6D'],\n  ['LEAN_INVERT_OFFSET', 'define(\"leanInvertOffset\", false)'],\n  ['LEAN_INVERT_ROLL', 'define(\"leanInvertRoll\", false)'],");
sub("for (const key of ['leanEnabled', 'leanMaxOffset', 'leanRollDegrees', 'leanSpeedTicks',\n  'leanSuppressVanillaKeys']) {",
  "for (const key of ['leanEnabled', 'leanMaxOffset', 'leanRollDegrees', 'leanSpeedTicks',\n  'leanSuppressVanillaKeys', 'leanInvertOffset', 'leanInvertRoll']) {");

fs.writeFileSync(file, text);
console.log('changed:', before !== text);
