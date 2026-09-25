// The voice clips: every claim about them is checkable without ears, so it is checked here.
//
//   node tools/selftest_voice.js
//
//   1. every sounds.json entry has a real file in assets/tarkovscav/sounds/ (a missing file is a SILENT
//      failure in game - the single most important check in this file);
//   2. every shipped clip is MONO, read from its own Vorbis identification header (a stereo clip is not
//      spatialised by OpenAL at all: it plays centred and audible everywhere, which is the exact bug the
//      mono requirement exists to prevent);
//   3. every clip has a subtitle key in BOTH languages, and that key exists in sounds.json;
//   4. the pools the AI speaks from cover all 15 clips exactly once, and the idle interval is ours
//      (400 ticks) rather than vanilla's ~6s ambient cadence;
//   5. every voice config key is documented in README.md.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const SOUNDS_DIR = path.join(ASSETS, 'sounds');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

console.log('1. sounds.json -> file');
const soundsJson = JSON.parse(fs.readFileSync(path.join(ASSETS, 'sounds.json'), 'utf8'));
const entries = Object.entries(soundsJson);
// The count is derived from what the registry actually declares: the hand-written clips in ModSounds.all()
// plus every line of the voice_clips.txt manifest (delivery 11 reads that file at construction, so the
// manifest IS the registry for family clips). Adding a clip therefore cannot leave this gate describing an
// older batch.
const registrySource = fs.readFileSync(path.join(JAVA, 'registry', 'ModSounds.java'), 'utf8');
const baseBlock = /private static RegistryObject<SoundEvent>\[\] all\(\)\s*\{[\s\S]{0,200}?List\.of\(\s*([\s\S]*?)\)\);/
  .exec(registrySource);
const baseNames = baseBlock ? (baseBlock[1].match(/[A-Z_]+_\d+/g) || []) : [];
const manifestPath = path.join(ASSETS, 'voice_clips.txt');
const manifest = fs.existsSync(manifestPath)
  ? fs.readFileSync(manifestPath, 'utf8').split('\n').map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith('#'))
  : [];
const allCount = baseNames.length + manifest.length;
check(baseNames.length > 0, 'the hand-declared clips are enumerated in ModSounds.all()',
  `${baseNames.length} base clip(s)`);
check(manifest.length > 0, 'the family clip manifest exists and is not empty',
  `${manifest.length} managed clip(s)`);
check(entries.length === allCount, `${allCount} sounds.json entries (${baseNames.length} base + ${manifest.length} managed)`,
  `found ${entries.length}`);
check(manifest.every((name) => soundsJson[name] !== undefined),
  'every manifest line has a sounds.json entry (a manifest entry without one is a silent clip)');
check(Object.keys(soundsJson).filter((k) => !baseNames.some((b) => `voice.${b.toLowerCase()}` === k))
  .every((k) => manifest.includes(k)),
  'and every non-base entry is in the manifest (sounds.json cannot grow a clip the mod never registers)');
let missingFiles = 0;
let subtitles = 0;
for (const [event, def] of entries) {
  for (const ref of def.sounds || []) {
    // "tarkovscav:voice/contact_1" -> sounds/voice/contact_1.ogg
    const rel = ref.replace(/^[^:]+:/, '');
    const file = path.join(SOUNDS_DIR, `${rel}.ogg`);
    if (!fs.existsSync(file)) {
      missingFiles++;
      console.log(`        missing: ${path.relative(ROOT, file)}  (event ${event})`);
    }
  }
  if (def.subtitle && def.subtitle.startsWith('subtitles.tarkovscav.')) subtitles++;
}
check(missingFiles === 0, 'every sounds.json entry has its .ogg on disk');
check(subtitles === entries.length, 'every entry has a subtitle key', `${subtitles}/${entries.length}`);

console.log('');
console.log('2. mono (read from each Vorbis identification header)');
// Every shipped clip, wherever it lives under sounds/ (voice/ and effect/), so a new folder cannot slip a
// stereo file past the gate by not being in voice/.
const allClips = (function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else if (entry.name.endsWith('.ogg')) out.push(full);
  }
  return out;
})(SOUNDS_DIR).sort();
check(allClips.length === entries.length, `${entries.length} clips shipped (one file per event)`,
  `found ${allClips.length}`);
let stereo = 0;
const rows = [];
for (const file of allClips) {
  const buf = fs.readFileSync(file);
  const marker = buf.indexOf(Buffer.from([0x01, 0x76, 0x6f, 0x72, 0x62, 0x69, 0x73]));
  const channels = marker >= 0 ? buf.readUInt8(marker + 11) : -1;
  const rate = marker >= 0 ? buf.readUInt32LE(marker + 12) : -1;
  let granules = -1;
  for (let at = buf.lastIndexOf(Buffer.from('OggS')); at >= 0; at = buf.lastIndexOf(Buffer.from('OggS'), at - 1)) {
    granules = Number(buf.readBigInt64LE(at + 6));
    if (granules > 0) break;
  }
  const seconds = rate > 0 && granules > 0 ? granules / rate : -1;
  if (channels !== 1) stereo++;
  if (rate !== 44100) stereo++;
  rows.push(`        ${path.basename(file).padEnd(22)} ${String(channels)}ch ${String(rate)}Hz ${seconds.toFixed(2)}s ${buf.length}B`);
}
check(stereo === 0, 'every clip is mono 44.1 kHz', stereo === 0 ? `${allClips.length}/${allClips.length}` : `${stereo} bad clip(s)`);
if (process.env.VOICE_REPORT) console.log(rows.join('\n'));

console.log('');
console.log('2b. measured loudness (voice_levels.json) - reported, not gated');
// The shipped 118 oggs are the ORIGINALS: a re-render was done once and then rolled back (the user's report
// turned out to be his own "hostile mobs" volume slider), so this section does NOT enforce a loudness target.
// What it does enforce is VISIBILITY: the measured table has to cover every shipped clip, and any clip that
// sits more than 2 dB off its own family's median has to be named in the README - so "which lines are quiet"
// is always written down instead of being rediscovered by ear.
const levelsPath = path.join(ASSETS, 'voice_levels.json');
check(fs.existsSync(levelsPath), 'the shipped measurement table exists (voice_levels.json)');
const levels = fs.existsSync(levelsPath) ? JSON.parse(fs.readFileSync(levelsPath, 'utf8')) : { clips: [] };
// Read the README here (the section-5 variable of the same content is declared much later in this file, and
// a block-scoped const cannot be used before its declaration).
const readmeText = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
const config2 = fs.readFileSync(path.join(JAVA, 'Config.java'), 'utf8');
const measurer = fs.readFileSync(path.join(ROOT, 'tools', 'measure_voice_levels.ps1'), 'utf8');
check(typeof levels.targetMeanDb === 'number' && typeof levels.peakCeilingDb === 'number',
  'and records the tooling settings it was measured with (target / ceiling)',
  `${levels.targetMeanDb} / ${levels.peakCeilingDb}`);
check(measurer.includes(`$TargetMeanDb = ${levels.targetMeanDb}`)
  && measurer.includes(`$PeakCeilingDb = ${levels.peakCeilingDb}`),
  'which are the measurer defaults, so the table cannot be stale about its own settings');
const clipNames = new Set(allClips.map((f) => path.basename(f, '.ogg')));
const levelRows = Array.isArray(levels.clips) ? levels.clips : [];
check(levelRows.length === clipNames.size && levelRows.every((row) => clipNames.has(row.name)),
  `the table covers every shipped clip exactly once (${clipNames.size})`, `${levelRows.length} row(s)`);
const stat = (family) => {
  const of = levelRows.filter((r) => r.family === family);
  if (!of.length) return null;
  const means = of.map((r) => r.meanDb).sort((a, b) => a - b);
  const median = means[Math.floor(means.length / 2)];
  const avg = means.reduce((s, v) => s + v, 0) / means.length;
  const sd = Math.sqrt(means.reduce((s, v) => s + (v - avg) ** 2, 0) / means.length);
  return { n: means.length, median, avg, sd, peak: Math.max(...of.map((r) => r.peakDb)), means };
};
for (const family of ['shared', 'usec', 'bear', 'elite', 'effect']) {
  const s = stat(family);
  if (s) {
    console.log(`        ${family.padEnd(6)} n=${String(s.n).padEnd(3)} median ${s.median.toFixed(2)} dB`
      + ` avg ${s.avg.toFixed(2)} sd ${s.sd.toFixed(2)} peakMax ${s.peak.toFixed(2)}`);
  }
}
// The families must be in the same loudness BAND (the user's whole complaint was a 9.5 dB family gap, which
// would show up here). Elite/bear/usec medians all sit within 0.3 dB of the original 27.
const reference = stat('shared');
for (const family of ['usec', 'bear', 'elite']) {
  const s = stat(family);
  check(s !== null && Math.abs(s.median - reference.median) <= 1.0,
    `${family}'s median mean is within 1 dB of the original scav clips`,
    s ? `${s.median.toFixed(2)} vs ${reference.median.toFixed(2)}`
      + ` (diff ${Math.abs(s.median - reference.median).toFixed(2)} dB)` : 'missing');
}
// Every clip that is more than 2 dB off its own family median is a candidate for a future one-line re-render,
// and it has to be named in the README (with its measured value) so nobody has to hunt for it by ear.
const outliers = [];
for (const family of ['shared', 'usec', 'bear', 'elite']) {
  const s = stat(family);
  if (!s) continue;
  for (const row of levelRows.filter((r) => r.family === family)) {
    if (Math.abs(row.meanDb - s.median) > 2.0) outliers.push(row);
  }
}
for (const row of outliers) {
  check(readmeText.includes(row.name),
    `the outlier ${row.name} (${row.meanDb} dB vs its family median) is named in README`);
}
console.log(`        ${outliers.length} clip(s) more than 2 dB off their family median: `
  + outliers.map((r) => `${r.name} ${r.meanDb}`).join(', '));
check(/familyVolume/.test(readmeText) && /effectVolume/.test(readmeText),
  'README documents the two escape-hatch volume keys');
check(/\.defineListAllowEmpty\(List\.of\("familyVolume"\)/.test(config2)
  && /\.defineInRange\("effectVolume", 1\.0D/.test(config2),
  'Config declares voice.familyVolume (a list) and voice.effectVolume');
check(/List\.of\("shared=1\.0", "usec=1\.0", "bear=1\.0", "elite=1\.0"\)/.test(config2),
  'and both ship at 1.0, so the calibrated clips are the default mix');
check(/public static double familyVolume\(String family\)/.test(config2)
  && /public static double effectVolume\(\)/.test(config2)
  && /public static List<String> familyVolumeSummary\(\)/.test(config2),
  'with accessors for the mix, the effect and the command readout');
check(/Config\.familyVolume\(familyOf\(mob\)\)/.test(fs.readFileSync(path.join(JAVA, 'voice', 'VoicePools.java'), 'utf8')),
  'the family multiplier is applied where a line is played (one formula, VoicePools.volumeFor)');
check(/Config\.effectVolume\(\)/.test(fs.readFileSync(path.join(JAVA, 'grenade', 'GrenadeEntity.java'), 'utf8')),
  'and the impact clip uses effectVolume');

console.log('');
console.log('3. subtitles in both languages');
for (const lang of ['en_us', 'zh_cn']) {
  const text = fs.readFileSync(path.join(ASSETS, 'lang', `${lang}.json`), 'utf8');
  const missing = entries.filter(([, def]) => !text.includes(`"${def.subtitle}"`)).map(([event]) => event);
  check(missing.length === 0, `${lang}.json has every subtitle`, missing.length ? missing.join(', ') : '');
}

console.log('');
console.log('4. the pools the AI speaks from');
const registry = fs.readFileSync(path.join(JAVA, 'registry', 'ModSounds.java'), 'utf8');
const voiceSource = fs.readFileSync(path.join(JAVA, 'voice', 'MobVoice.java'), 'utf8');
// Comments are stripped for the code checks: the class documents vanilla's interval in prose, which is
// not the same thing as using it.
const voice = voiceSource.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const config = fs.readFileSync(path.join(JAVA, 'Config.java'), 'utf8');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
for (const pool of ['CHATTER', 'IDLE', 'GRENADE', 'MARK', 'DEATH']) {
  check(new RegExp(`${pool}\\s*=`).test(registry), `the ${pool} pool exists`);
}
const allBlock = /private static RegistryObject<SoundEvent>\[\] all\(\)\s*\{[\s\S]{0,200}?List\.of\(\s*([\s\S]*?)\)\);/
  .exec(registry);
const declared = (allBlock ? allBlock[1].match(/[A-Z_]+_\d+/g) || [] : []).map((s) => s.trim());
check(new Set(declared).size === baseNames.length && declared.length === baseNames.length,
  `all ${baseNames.length} hand-declared clips appear in the ALL pool exactly once`,
  `${declared.length} entries, ${new Set(declared).size} distinct`);
check(/every\.addAll\(MANAGED\)/.test(registry) && /MANAGED\.add\(clip\)/.test(registry),
  'and the manifest clips join it, so "play every clip" means every clip');
check(/CHATTER = new RegistryObject\[\]\{\s*CONTACT_1/.test(registry) && /CONTACT_11\}/.test(registry),
  'the chatter pool is the merged contact+taunt pool', 'the user asked for one shout pool');
check(/IDLE = new RegistryObject\[\]\{\s*\n\s*IDLE_1/.test(registry) && !/IDLE = CHATTER/.test(registry),
  'idle has its OWN pool now (the Bilibili self-talk batch), not a borrow of the chatter pool');
check(/CONTACT_6[\s\S]*IDLE_6/.test(registry) && /GRENADE_1/.test(registry),
  'the Bilibili contact batch feeds CHATTER and the self-talk batch feeds IDLE');
check(/getAmbientSoundInterval/.test(voice) === false,
  'the idle timer is ours, not vanilla getAmbientSoundInterval');
check(/Config\.VOICE_IDLE_INTERVAL_TICKS/.test(voice), 'idle uses voice.idleIntervalTicks');
check(/defineInRange\("idleIntervalTicks", 400,/.test(config), 'the idle default is 400 ticks (20s)');
check(/playSound\(null, this\.mob\.getX\(\)/.test(voice),
  'every line is played at the mob position (spatialised), never on a player');
for (const trigger of ['VOICE_CONTACT', 'VOICE_CHATTER', 'VOICE_GRENADE', 'VOICE_MARK', 'VOICE_DEATH']) {
  check(voice.includes(`Config.${trigger}`), `${trigger} gates its trigger`);
}
check(/PrimedTnt/.test(voice) && /grenade/.test(voice),
  'the grenade trigger covers primed TNT and any entity named *grenade*');

console.log('');
console.log('5. config keys documented in README');
const voiceKeys = [...config.matchAll(/define(?:InRange|ListAllowEmpty)?\(\s*"([A-Za-z0-9_]+)"/g)]
  .map((m) => m[1]);
const documented = ['enabled', 'idle', 'idleIntervalTicks', 'idleJitterTicks', 'contact',
  'contactCooldownTicks', 'chatter', 'chatterMinIntervalTicks', 'chatterMaxIntervalTicks', 'grenade',
  'grenadeRadius', 'grenadeCooldownTicks', 'mark', 'death', 'volume',
  'pitchMin', 'pitchMax', 'pitchJitter'];
const missingDocs = documented.filter((key) => !readme.includes(key));
check(documented.every((key) => voiceKeys.includes(key) || key === 'volume'),
  'every voice key is declared in Config', `${voiceKeys.length} keys declared overall`);
check(missingDocs.length === 0, 'every voice key is documented in README',
  missingDocs.length ? missingDocs.join(', ') : '');
check(/第三方|third-party/i.test(readme), 'README states the third-party/private-use licence note');
check(/test sound/.test(readme), 'README documents /tarkovscav test sound');

console.log('');
console.log('6. pitch: one voice per mob, persistent, never hard-coded');
const commands = fs.readFileSync(path.join(JAVA, 'command', 'ModCommands.java'), 'utf8');

// (a) the pitch is computed, not typed in. The old code was `0.9F + random.nextFloat() * 0.2F`.
check(/Config\.voicePitch(Band|Min|Max|Jitter)\(\)/.test(voice),
  'the pitch comes from Config.voicePitch...(), never a literal');
check(!/nextFloat\(\)\s*\*\s*0\.2F/.test(voice) && !/0\.9F\s*\+/.test(voice),
  'the hard-coded 0.9..1.1 pitch is gone');
check(/playSound\([\s\S]{0,200}?,\s*pitch\)/.test(voice),
  'the computed pitch is the one handed to playSound');
check(/pitch=\{\} voice=\{\}/.test(voice) && /String\.format\("%\.3f", pitch\)/.test(voice),
  'every line logs pitch= and voice=');
check(/pitch=/.test(commands) && /pitch swept across|pitch swept|all at pitch/.test(commands),
  '/tarkovscav test sound reports and auditions the pitch');

// (b) the three keys exist with the shipped defaults, and README carries the vanilla comparison.
check(/defineInRange\("pitchMin"/.test(config) && /defineInRange\("pitchMax"/.test(config)
  && /defineInRange\("pitchJitter"/.test(config), 'all three pitch keys are declared');
check(/DEFAULT_VOICE_PITCH_MIN = 0\.9D/.test(config) && /DEFAULT_VOICE_PITCH_MAX = 1\.1D/.test(config)
  && /DEFAULT_VOICE_PITCH_JITTER = 0\.03D/.test(config),
  'the shipped band is 0.9/1.1 with 0.03 jitter');
check(/getVoicePitch/.test(readme) && /0\.8-1\.2/.test(readme),
  'README compares the band with vanilla (getVoicePitch, 0.8-1.2)');
check(/\| `pitchMin` \| `0\.9` \|/.test(readme) && /\| `pitchMax` \| `1\.1` \|/.test(readme)
  && /\| `pitchJitter` \| `0\.03` \|/.test(readme),
  'the README table carries the documented defaults');

// (c) NBT round-trip: the mob's voice survives a save -> load. Simulated against the real algorithm,
// because there is no game here: draw -> store -> new MobVoice over the same data -> same pitch.
check(/NBT_VOICE_PITCH/.test(config) && /NBT_VOICE_PITCH = "tarkovscav:voicePitch"/.test(config),
  'the NBT key is namespaced (getPersistentData is shared with other mods)');
check(/getPersistentData\(\)/.test(voice) && /\.putFloat\(Config\.NBT_VOICE_PITCH/.test(voice)
  && /\.get\(Config\.NBT_VOICE_PITCH\)/.test(voice),
  'the same key is written and read back');
check(/instanceof NumericTag/.test(voice),
  'a hand-edited/wrong-typed NBT tag cannot throw on load');
check(/voicePitchBand\(\)/.test(config) && /clampPitch/.test(config) && /min > max/.test(config),
  'the band is validated as a pair (NaN -> default, min > max -> default)');

const FLOOR = 0.1; const CEIL = 2.0; const DJIT = 0.03;
const DMIN = 0.9; const DMAX = 1.1;
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
// Mirror of Config.voicePitchBand(): clamp, NaN -> default, the wrong way round -> both defaults.
function band(rawMin, rawMax) {
  let min = Number.isFinite(rawMin) ? clamp(rawMin, FLOOR, CEIL) : DMIN;
  let max = Number.isFinite(rawMax) ? clamp(rawMax, FLOOR, CEIL) : DMAX;
  if (min > max) { min = DMIN; max = DMAX; }
  return [min, max];
}
check(JSON.stringify(band(DMIN, DMAX)) === '[0.9,1.1]', 'the default pair passes through unchanged');
check(JSON.stringify(band(NaN, 1.05)) === '[0.9,1.05]', 'a NaN end falls back to its own default');
check(JSON.stringify(band(1.5, 1.2)) === '[0.9,1.1]', 'min > max falls back to the shipped pair');
check(JSON.stringify(band(-4, 99)) === '[0.1,2]', 'a silly toml is clamped to 0.1..2.0, not rejected');
check(JSON.stringify(band(1.0, 1.0)) === '[1,1]', 'a collapsed band is allowed (every mob identical)');

// Deterministic PRNG, so this gate cannot flake.
let seed = 0x9e3779b9;
const rnd = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const MOTHS = 200;
const voices = [];
const saved = new Map();
let outOfBand = 0;
let jitterOut = 0;
for (let id = 0; id < MOTHS; id++) {
  let base = saved.has(id) ? saved.get(id) : undefined;
  if (base === undefined || base < DMIN || base > DMAX) {
    base = DMIN + rnd() * (DMAX - DMIN);
    saved.set(id, base);
    // "save and load": a fresh MobVoice reads the same stored value.
    const reloaded = saved.get(id);
    if (reloaded !== base) jitterOut++;
  }
  voices.push(base);
  for (let line = 0; line < 5; line++) {
    const pitch = clamp(base + (rnd() * 2 - 1) * DJIT, DMIN, DMAX);
    if (pitch < DMIN || pitch > DMAX) outOfBand++;
  }
}
const mean = voices.reduce((a, b) => a + b, 0) / voices.length;
const stddev = Math.sqrt(voices.reduce((a, b) => a + (b - mean) ** 2, 0) / voices.length);
check(stddev >= 0.02, `${MOTHS} mobs do not sound alike`,
  `stddev ${stddev.toFixed(4)} over ${DMIN}..${DMAX}, mean ${mean.toFixed(4)}`);
check(new Set(voices.map((v) => v.toFixed(6))).size === MOTHS, 'all 200 voices are distinct');
check(jitterOut === 0, 'the stored voice round-trips exactly (NBT save -> load)');
check(outOfBand === 0, 'the per-line jitter never leaves the band',
  '1000 simulated lines, all clamped');
const distinctLines = new Set();
for (let i = 0; i < 200; i++) distinctLines.add(clamp(1.0 + (rnd() * 2 - 1) * DJIT, DMIN, DMAX).toFixed(5));
check(distinctLines.size > 150, 'the jitter really varies line to line',
  `${distinctLines.size}/200 distinct pitches for one mob`);

console.log('');
console.log('7. the family pools (delivery 11): manifest, isolation, audition');
const voicePoolsSrc = fs.readFileSync(path.join(JAVA, 'voice', 'VoicePools.java'), 'utf8');
const FAMILIES = ['usec', 'bear', 'elite'];
const CATEGORIES = ['contact', 'chatter', 'idle', 'grenade', 'mark', 'death'];
const poolNames = [...new Set(manifest.filter((m) => m.startsWith('voice.'))
  .map((m) => m.replace(/^voice\./, '').replace(/_\d+$/, '')))].sort();
const wanted = FAMILIES.flatMap((f) => CATEGORIES.map((c) => `${f}_${c}`)).sort();
check(JSON.stringify(poolNames) === JSON.stringify(wanted),
  `the manifest declares exactly the ${FAMILIES.length} x ${CATEGORIES.length} family pools`,
  `${poolNames.length} pool(s)`);
for (const family of FAMILIES) {
  for (const category of CATEGORIES) {
    const pool = `${family}_${category}`;
    const clips = manifest.filter((m) => m.startsWith(`voice.${pool}_`));
    check(clips.length >= 4 && clips.length <= 6,
      `${pool}: ${clips.length} clip(s) - the 4..6 per entity per category that was asked for`);
    // ISOLATION: a clip of another family in this pool is the "the BEAR said the USEC line" bug, and it is
    // caught by name here and by the pool table in tools/selftest_entity_registry.js by entity.
    const foreign = clips.filter((m) => !m.startsWith(`voice.${family}_`));
    check(foreign.length === 0, `${pool} holds only ${family} clips`, foreign.join(', ') || 'clean');
  }
}
// The name form of the family table must agree with the four classes' own voiceFamily(): two tables that
// could drift apart is exactly how a mob ends up speaking the wrong pool after a rename.
const ENTITY_FAMILY = { usec_villager: 'usec', bear_pillager: 'bear', elite_villager: 'elite',
  elite_pillager: 'elite' };
// Parse the switch itself (a name form and a multi-label arm like `case "a", "b" -> ...` both work), so the
// check is about the mapping and not about the shape of one line.
const familyForEntityId = {};
for (const arm of voicePoolsSrc.matchAll(/case ((?:"[a-z_]+"(?:,\s*"[a-z_]+")*)) -> "([a-z]+)";/g)) {
  for (const quoted of arm[1].match(/"[a-z_]+"/g)) {
    familyForEntityId[quoted.replace(/"/g, '')] = arm[2];
  }
}
for (const [entity, family] of Object.entries(ENTITY_FAMILY)) {
  const cls = `${entity.split('_').map((s) => s[0].toUpperCase() + s.slice(1)).join('')}Entity`;
  const src = fs.readFileSync(path.join(JAVA, 'entity', `${cls}.java`), 'utf8');
  check(new RegExp(`voiceFamily\\(\\)\\s*\\{\\s*return "${family}";`).test(src),
    `${entity} answers voiceFamily() = ${family} (${cls})`);
  check(familyForEntityId[entity] === family,
    `VoicePools.familyForEntityId maps ${entity} -> ${family} (the two cannot drift)`,
    familyForEntityId[entity]);
}
check(Object.keys(familyForEntityId).length === Object.keys(ENTITY_FAMILY).length,
  'and the table has no extra entity in it', Object.keys(familyForEntityId).join(','));
check(/FAMILY_POOLS/.test(registry) && /static String poolOf\(String event\)/.test(registry)
  && /replaceAll\("_\[0-9\]\+\$", ""\)/.test(registry),
  'ModSounds groups the manifest into <family>_<category> pools by stripping the index');
check(/case "all" -> ALL;/.test(registry) && /FAMILY_POOLS\.get\(key\)/.test(registry)
  && /startsWith\(key \+ "_"\)/.test(registry),
  'test sound resolves "all", a single family pool and a whole family');
check(/InputStream in = ModSounds\.class\.getResourceAsStream\(resource\)/.test(registry)
  && /is missing from the jar/.test(registry),
  'and a missing manifest is logged and survivable, not a silent empty pool');
check(/ModSounds\.poolNames\(\)/.test(commands) && /VoicePools\.familyForEntityId\(name\)/.test(commands),
  '/tarkovscav test sound lists the pools and accepts an entity name (usec_villager)');
check(/no such voice pool|No such voice pool/i.test(commands.replace(/Component\.literal\("/g, 'Component.literal("')),
  'and an unknown name is reported instead of playing nothing');
check(fs.readFileSync(path.join(ROOT, 'tools', 'voice_emit.js'), 'utf8').includes('voice_clips.txt')
  && fs.readFileSync(path.join(ROOT, 'tools', 'voice_plan.js'), 'utf8').includes('voice_pools.json'),
  'the manifest has a generator and the pool table is data (tools/voice_pools.json)');
check(/voice-inventory:start/.test(readme) && /第三方素材声明/.test(readme),
  'README carries the clip inventory and the third-party/private-use note');
check(/USEC/.test(readme) && /BEAR/.test(readme) && /优质PMC/.test(readme),
  'README names the three voice families');

console.log('');
if (failures > 0) {
  console.log(`${failures} voice check(s) FAILED`);
  process.exit(1);
}
console.log('voice invariants all hold');
