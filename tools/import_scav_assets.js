// Imports the user's YSM Scav model into the mod's GeckoLib asset layout.
//
//   node tools/import_scav_assets.js [--force]
//
// Source (never modified):  assets_source/scav/
//   models/main.json                     the third-person rig (70 bones)
//   models/arm.json                      the YSM first-person arm rig - NOT imported (a mob never
//                                        renders in first person)
//   animations/{main,tac,...}.json       the clip library
//   textures/texture.png                 256x256 skin
//   avatar/*.png                         the authors' avatars - NOT imported
//   sounds/*.ogg                         the authors' audio, some of it from other games - NOT
//                                        imported: this mod ships nobody else's audio
//   ysm.json, controller/*.json          YSM metadata / YSM state machine - NOT portable to
//                                        GeckoLib, kept for reference only
//
// Output (overwritten every run):
//   src/main/resources/assets/tarkovscav/geo/scav.geo.json
//   src/main/resources/assets/tarkovscav/animations/scav.animation.json
//   src/main/resources/assets/tarkovscav/textures/entity/scav.png
//   tools/spike/work/scav-import-manifest.json   (what was taken, what was dropped and why)
//
// Only the clips the mob actually plays are exported: the source library is ~24 MB of dances, voices,
// Parkour animations and horse riding, and none of that belongs in a mob's jar.
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'assets_source/scav');
const ASSETS = path.join(ROOT, 'src/main/resources/assets/tarkovscav');
const MANIFEST = path.join(ROOT, 'tools/spike/work/scav-import-manifest.json');
const GEO_IDENTIFIER = 'geometry.tarkovscav.scav';

/** Clips the mob plays. Names are kept exactly as the author wrote them, so any of them can be
 *  traced back to the source file. */
const CLIPS = {
  // --- lower body / whole body while no gun is held -------------------------------
  movement: {
    file: 'main.animation.json',
    names: ['idle', 'walk', 'run', 'death'],
  },
  // --- lower body while a gun IS held (the author's own layered set) --------------
  movementArmed: {
    file: 'tac.animation.json',
    names: ['tac:idle', 'tac:walk', 'tac:run'],
  },
  // --- upper body: the gun handling ----------------------------------------------
  gun: {
    file: 'tac.animation.json',
    names: [
      'tac:hold:rifle', 'tac:aim:rifle', 'tac:aim:fire:rifle', 'tac:reload:rifle', 'tac:melee:rifle',
      'tac:hold:pistol', 'tac:aim:pistol', 'tac:aim:fire:pistol', 'tac:reload:pistol', 'tac:melee:pistol',
    ],
  },
};

/** Reference props in the right hand: never shown in normal play (see Config#hiddenBones). */
const REFERENCE_PROPS = ['Ban', 'Lianru', 'Bao', 'Spwt', 'Jiu', 'Parrot', 'money', 'Gun3'];

/** The gun anchor bone. Its pivot sits inside the palm and its +X axis points at the muzzle. */
const GUN_ANCHOR = 'Gun3';

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, '\t') + '\n');
  console.log('wrote ' + path.relative(ROOT, file));
}

function main() {
  const force = process.argv.includes('--force');
  const geoSource = path.join(SRC, 'models/main.json');
  if (!fs.existsSync(geoSource)) {
    throw new Error('missing ' + geoSource + ' - extract the YSM zip into assets_source/scav first');
  }

  const geo = readJson(geoSource);
  const geometry = geo['minecraft:geometry'][0];
  geometry.description.identifier = GEO_IDENTIFIER;
  const boneNames = new Set(geometry.bones.map((b) => b.name));
  // The author's animation files disagree with their own geometry about capitalisation:
  // the geo has `leftfoot`/`rightfoot`, every clip says `LeftFoot`/`RightFoot`. GeckoLib matches bone
  // names exactly, so without this the feet would silently never animate. The geometry wins (it is the
  // file a future model update would replace), and the track is renamed to match.
  const boneNamesLower = new Map(geometry.bones.map((b) => [b.name.toLowerCase(), b.name]));

  // ---------------------------------------------------------------- animations
  const animations = {};
  const manifest = {
    source: path.relative(ROOT, SRC).replace(/\\/g, '/'),
    geoIdentifier: GEO_IDENTIFIER,
    bones: geometry.bones.length,
    gunAnchor: GUN_ANCHOR,
    referenceProps: [],
    clips: [],
    droppedTracks: [],
    renamedTracks: [],
    notImported: [],
  };

  for (const [group, spec] of Object.entries(CLIPS)) {
    const file = path.join(SRC, 'animations', spec.file);
    const json = readJson(file);
    for (const name of spec.names) {
      const anim = json.animations && json.animations[name];
      if (!anim) {
        throw new Error(`animation "${name}" is not in ${spec.file}`);
      }
      const clean = JSON.parse(JSON.stringify(anim));
      let tracks = 0;
      let dropped = 0;
      let renamed = 0;
      for (const bone of Object.keys(clean.bones || {})) {
        tracks++;
        if (boneNames.has(bone)) {
          continue;
        }
        const canonical = boneNamesLower.get(bone.toLowerCase());
        if (canonical) {
          clean.bones[canonical] = clean.bones[bone];
          delete clean.bones[bone];
          renamed++;
          manifest.renamedTracks.push({ clip: name, from: bone, to: canonical });
          continue;
        }
        delete clean.bones[bone];
        dropped++;
        manifest.droppedTracks.push({ clip: name, bone });
      }
      animations[name] = clean;
      manifest.clips.push({
        group,
        name,
        sourceFile: spec.file,
        length: clean.animation_length,
        loop: clean.loop === undefined ? null : clean.loop,
        bones: Object.keys(clean.bones || {}).length,
        bonesDropped: dropped,
        bonesRenamed: renamed,
        tracks,
      });
    }
  }

  // ---------------------------------------------------------------- reference props
  const byName = new Map(geometry.bones.map((b) => [b.name, b]));
  for (const prop of REFERENCE_PROPS) {
    const bone = byName.get(prop);
    if (!bone) continue;
    let cubes = 0;
    const stack = [prop];
    while (stack.length) {
      const name = stack.pop();
      const current = byName.get(name);
      cubes += (current.cubes || []).length;
      for (const child of geometry.bones) {
        if (child.parent === name) stack.push(child.name);
      }
    }
    manifest.referenceProps.push({ bone: prop, ownCubes: (bone.cubes || []).length, subtreeCubes: cubes });
  }

  for (const [label, files] of Object.entries({
    'first-person arm rig': ['models/arm.json'],
    'author avatars': fs.readdirSync(path.join(SRC, 'avatar')).map((f) => 'avatar/' + f),
    'author audio': fs.readdirSync(path.join(SRC, 'sounds')).map((f) => 'sounds/' + f),
    'YSM metadata': ['ysm.json', 'controller/controller_scav.json'],
    'unused clip file': fs.readdirSync(path.join(SRC, 'animations')).filter((f) => f.endsWith('.json')).map((f) => 'animations/' + f),
  })) {
    manifest.notImported.push({ what: label, files });
  }
  manifest.notImported = manifest.notImported.map((entry) => ({
    what: entry.what,
    files: entry.files.filter((f) => !Object.values(CLIPS).some((s) => ('animations/' + s.file) === f)),
  }));

  // ---------------------------------------------------------------- write
  const skipExisting = !force;
  const geoOut = path.join(ASSETS, 'geo/scav.geo.json');
  if (skipExisting && fs.existsSync(geoOut) && process.env.SCAV_IMPORT_FORCE !== '1') {
    // Always rewrite: this is the import step, overwriting is the point. The flag only exists to
    // make the intent explicit if somebody adds --force handling later.
  }
  writeJson(geoOut, geo);

  // The animation file also carries the placeholder filler clips the placeholder generator wrote for
  // the gunner pillager - no: those live in their own file. This file is the scav's, exclusively.
  writeJson(path.join(ASSETS, 'animations/scav.animation.json'), {
    format_version: '1.8.0',
    animations,
  });

  const textureOut = path.join(ASSETS, 'textures/entity/scav.png');
  fs.mkdirSync(path.dirname(textureOut), { recursive: true });
  fs.copyFileSync(path.join(SRC, 'textures/texture.png'), textureOut);
  console.log('wrote ' + path.relative(ROOT, textureOut));

  writeJson(MANIFEST, manifest);

  console.log('\nimported clips:');
  for (const clip of manifest.clips) {
    const notes = [];
    if (clip.bonesRenamed) notes.push(`renamed ${clip.bonesRenamed}`);
    if (clip.bonesDropped) notes.push(`dropped ${clip.bonesDropped}`);
    console.log(
      `  ${clip.group.padEnd(14)} ${clip.name.padEnd(22)} len=${String(clip.length).padEnd(8)} loop=${String(
        clip.loop
      ).padEnd(20)} bones=${clip.bones}${notes.length ? ` (${notes.join(', ')})` : ''}`
    );
  }
  if (manifest.renamedTracks.length) {
    console.log('\nrenamed to match the geometry (author used a different capitalisation):');
    const seen = new Set();
    for (const track of manifest.renamedTracks) {
      const key = track.from + '->' + track.to;
      if (seen.has(key)) continue;
      seen.add(key);
      console.log(`  ${track.from} -> ${track.to}`);
    }
  }
  if (manifest.droppedTracks.length) {
    console.log('\ndropped (bone is not in the geometry - leftovers from the author\'s other rigs):');
    const seen = new Set();
    for (const track of manifest.droppedTracks) {
      if (seen.has(track.bone)) continue;
      seen.add(track.bone);
      console.log(`  ${track.bone}`);
    }
  }
  console.log('\nreference props (hidden by default):');
  for (const prop of manifest.referenceProps) {
    console.log(`  ${prop.bone.padEnd(10)} own=${String(prop.ownCubes).padEnd(4)} subtree=${prop.subtreeCubes}`);
  }
  console.log(`\ngun anchor: ${GUN_ANCHOR} (pivot inside the palm, +X points at the muzzle)`);
  console.log('not imported:');
  for (const entry of manifest.notImported) {
    if (entry.files.length) console.log(`  ${entry.what}: ${entry.files.join(', ')}`);
  }
}

main();
