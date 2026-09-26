// The random attachment pool, "改枪王" (README 5p).
//
//   node tools/selftest_mods.js
//
// The six things the request asked to be checked, each as a source invariant or a simulation:
//   1. every install is gated by TaCZ (allowAttachmentType -> allowAttachment -> installAttachment), so an
//      illegal combination cannot be written - "0 illegal combinations" by construction;
//   2. one slot cannot hold two attachments (installAttachment is the only writer, and it replaces);
//   3. the total never exceeds mods.maxPerGun, for any roll;
//   4. each slot's fill frequency and the full-mod roll land in the confidence interval of the config;
//   5. an extended magazine's capacity is RE-READ from the item, never assumed, and the reload path uses the
//      same re-read capacity;
//   6. the gun that drops on death is the same ItemStack, so attachments survive being picked up.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

const mods = read('gun/GunAttachments.java');
const modsCode = strip(mods);
const pool = read('gun/GunPool.java');
const brain = strip(read('gun/GunBrain.java'));
const loot = read('gun/GunLoot.java');
const config = read('Config.java');
const commands = read('command/ModCommands.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. legality is TaCZ\'s answer, not a table of ours');
check(/igun\.allowAttachmentType\(gun, type\)/.test(modsCode),
  'the slot itself is asked first (a pistol is skipped, not "refused")');
check(/igun\.allowAttachment\(gun, candidate\)/.test(modsCode),
  'the fit question is asked of TaCZ');
check(/igun\.installAttachment\(gun, candidate\)/.test(modsCode),
  'and only then is anything written');
// Order matters: a single reordering would install first and ask later. The fit question lives in pick(),
// so what has to hold is "we asked for a legal candidate BEFORE we wrote anything".
const pickCallAt = modsCode.indexOf('ItemStack candidate = pick(igun, gun, type, random);');
const installAt = modsCode.indexOf('igun.installAttachment(gun, candidate)');
check(pickCallAt >= 0 && installAt > pickCallAt,
  'a candidate is obtained (which asks TaCZ) before anything is installed',
  `pick@${pickCallAt} install@${installAt}`);
check(modsCode.indexOf('igun.allowAttachment(gun, candidate)') >= 0,
  'and that candidate is the one TaCZ approved');
const installs = (modsCode.match(/installAttachment\(/g) || []).length;
check(installs === 1, 'installAttachment is called in exactly one place (no second write path)',
  `${installs} call(s)`);
check(!/new ItemStack\(Items\./.test(modsCode) && !/hardcoded|whitelist/i.test(modsCode),
  'there is no hard-coded attachment table');
check(/IAttachment\.getIAttachmentOrNull\(stack\)/.test(modsCode)
  && /attachment\.getType\(stack\) == type/.test(modsCode),
  'candidates are every attachment item in the registry, classified by IAttachment#getType');
check(/BuiltInRegistries\.ITEM/.test(modsCode) && /ConcurrentHashMap/.test(modsCode),
  'the registry scan is done once and cached');
check(/catch \(RuntimeException exception\)/.test(modsCode) && /LOGGER\.warn/.test(modsCode),
  'a broken third-party attachment cannot take the spawn down (and is WARNed)');

console.log('');
console.log('2. one slot, one attachment');
check(/installAttachment/.test(modsCode) && !/setAttachmentTag|\.put\(.*AttachmentTag/.test(modsCode),
  'the only writer is TaCZ\'s install, which replaces the slot contents');
check(/AttachmentType\.SCOPE, AttachmentType\.MUZZLE, AttachmentType\.STOCK,/.test(modsCode)
  && /AttachmentType\.GRIP, AttachmentType\.LASER, AttachmentType\.EXTENDED_MAG,/.test(modsCode),
  'the six slots are enumerated once, so a slot cannot be rolled twice');
const slotArray = /SLOTS = \{([\s\S]*?)\};/.exec(modsCode)[1];
const slots = (slotArray.match(/AttachmentType\.\w+/g) || []);
check(new Set(slots).size === slots.length, 'and each appears exactly once', slots.length + ' slots');
check(/for \(AttachmentType type : SLOTS\)/.test(modsCode), 'the loop walks that array');

console.log('');
console.log('3. the cap, and the documented defaults');
const parsed = (name) => {
  const m = new RegExp(`defineInRange\\("${name}", ([0-9.]+)`).exec(config);
  return m ? Number(m[1]) : NaN;
};
const PER_SLOT = parsed('perSlotChance');
const FULL = parsed('fullModChance');
const MAX = parsed('maxPerGun');
check(PER_SLOT === 0.35, 'mods.perSlotChance ships as 0.35', String(PER_SLOT));
check(FULL === 0.02, 'mods.fullModChance ships as 0.02', String(FULL));
check(MAX === 5, 'mods.maxPerGun ships as 5', String(MAX));
check(/define\("enabled", true\)/.test(config), 'mods.enabled ships true');
check(/define\("allowExtendedMag", true\)/.test(config), 'and the extended magazine is allowed');
check(/if \(installed\.size\(\) >= max\)/.test(modsCode),
  'the cap is checked before every slot, so no roll can exceed it');
check(/int max = Math\.max\(1, Config\.MODS_MAX_PER_GUN\.get\(\)\)/.test(modsCode),
  'a silly maxPerGun of 0 or negative is floored to 1');

// Simulation of the roll: mirrors apply(), including "the gun has no such slot".
function simulate(runs, hasSlot, perSlot, full, max, rnd) {
  const filledPerSlot = {};
  let capped = 0;
  let fullRolls = 0;
  for (let i = 0; i < runs; i++) {
    const isFull = rnd() < full;
    if (isFull) fullRolls++;
    const chance = isFull ? 1 : perSlot;
    let installed = 0;
    const used = {};
    for (const slot of ['SCOPE', 'MUZZLE', 'STOCK', 'GRIP', 'LASER', 'EXTENDED_MAG']) {
      if (installed >= max) { capped++; break; }
      if (!hasSlot[slot]) continue;
      if (rnd() >= chance) continue;
      installed++;
      used[slot] = true;
      filledPerSlot[slot] = (filledPerSlot[slot] || 0) + 1;
    }
    if (installed > max) capped++;
  }
  return { filledPerSlot, capped, fullRolls, runs };
}
let seed = 0xC0FFEE;
const rnd = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const ALL = { SCOPE: true, MUZZLE: true, STOCK: true, GRIP: true, LASER: true, EXTENDED_MAG: true };
const RIFLE_ONLY = { SCOPE: true, MUZZLE: true, STOCK: true, GRIP: true, LASER: true, EXTENDED_MAG: false };
const PISTOL = { SCOPE: false, MUZZLE: true, STOCK: false, GRIP: true, LASER: true, EXTENDED_MAG: true };
for (const [name, hasSlot] of [['a full-size rifle', ALL], ['a rifle with no extended mag', RIFLE_ONLY],
  ['a pistol-shaped slot set', PISTOL]]) {
  const runs = 20000;
  const result = simulate(runs, hasSlot, PER_SLOT, FULL, MAX, rnd);
  const slotsAvailable = Object.values(hasSlot).filter(Boolean).length;
  // Per-slot frequency: the full-mod roll makes every slot certain in 2 % of cases, so the expected
  // frequency is perSlotChance * (1 - full) + 1 * full.
  const expected = PER_SLOT * (1 - FULL) + FULL;
  check(Object.keys(result.filledPerSlot).every((slot) => hasSlot[slot]),
    `${name}: no attachment ever lands in a slot the gun does not have`,
    Object.keys(result.filledPerSlot).join(','));
  const worst = Object.entries(result.filledPerSlot)
    .map(([slot, n]) => Math.abs(n / runs - expected)).reduce((a, b) => Math.max(a, b), 0);
  // 4 sigma of a binomial proportion at n = 20000 is about 0.0129 for p = 0.363.
  check(worst < 0.02, `${name}: every slot's fill frequency matches the config`,
    `max deviation ${worst.toFixed(4)} (expected ${expected.toFixed(4)}, 4-sigma ~0.014)`);
  const fullRate = result.fullRolls / runs;
  check(Math.abs(fullRate - FULL) < 3 * Math.sqrt(FULL * (1 - FULL) / runs),
    `${name}: the full-mod ("改枪王") rate matches fullModChance`,
    `${(100 * fullRate).toFixed(3)}% vs ${(100 * FULL).toFixed(2)}%`);
  check(slotsAvailable <= MAX || result.capped > 0,
    `${name}: the ${MAX}-attachment cap binds when more slots are available`);
}

console.log('');
console.log('4. the magazine: re-read, never assumed');
check(/public static int capacityOf\(ItemStack gun\)/.test(modsCode),
  'capacity comes from one method');
check(/igun\.useDummyAmmo\(gun\) && igun\.hasMaxDummyAmmo\(gun\)/.test(modsCode)
  && /igun\.getMaxDummyAmmoAmount\(gun\)/.test(modsCode),
  'which reads TaCZ\'s own dummy-ammo capacity');
check(/igun\.getCurrentAmmoCount\(gun\)/.test(modsCode),
  'and falls back to the gun\'s own ammo count');
check(!/\b(10|15|20|30)\b\s*;?\s*\/\/\s*(mag|extended)/i.test(modsCode)
  && !/\+\s*10\b/.test(modsCode), 'there is no hard-coded "+10 rounds"');
check(/int before = capacityOf\(gun\);/.test(modsCode)
  && /igun\.installAttachment\(gun, candidate\);\s*\n\s*int after = capacityOf\(gun\);/.test(modsCode),
  'the capacity is read BEFORE and AFTER the install, so the change is measured');
check(/after != before/.test(modsCode), 'and a capacity change is logged');
check(/public static void refillToCapacity/.test(modsCode)
  && /igun\.setDummyAmmoAmount\(gun, capacity\)/.test(modsCode)
  && /igun\.setCurrentAmmoCount\(gun, capacity\)/.test(modsCode),
  'the gun is topped up to the re-read capacity');
check(/GunAttachments\.apply\(stack, random, owner\)[\s\S]{0,200}?GunAttachments\.refillToCapacity\(stack\)/
  .test(pool),
  'GunPool builds the magazine first, then mods, then tops up');
check(/GunPool\.buildGun\(loadout, this\.mob\.getRandom\(\), name\(\)\)/.test(brain),
  'the brain passes its own random source, so each gun differs');
const generatedLoadout = /private void applyLoadout\(GunLoadout loadout, String verb\)\s*\{([^}]*)\}/.exec(brain);
check(!!generatedLoadout
  && /GunPool\.buildGun[\s\S]*GunAttachments\.refillToCapacity\(stack\)[\s\S]*applyLoadout\(loadout, stack, verb\)/
    .test(generatedLoadout[1]),
  'newly generated guns are filled before the exact stack is equipped');
const existingLoadout = /private void applyLoadout\(GunLoadout loadout, ItemStack stack, String verb\)\s*\{([^}]*)\}/.exec(brain);
check(!!existingLoadout && /this\.gunStack = stack;/.test(existingLoadout[1])
  && !/buildGun|refillToCapacity|setCurrentAmmoCount/.test(existingLoadout[1])
  && /equipLoadout\(loadout, this\.mob\.getMainHandItem\(\)\)/.test(brain),
  'existing saved guns keep their exact stack and magazine contents during restoration');
check(/int capacity = capacityOf\(held\);/.test(brain)
  && /Math\.max\(0, capacity - gun\.getCurrentAmmoCount\(held\)\)/.test(brain),
  'the reload path uses the re-read capacity, not the loadout magazine size');
const reloadCapacity = /needed = Math\.max\(0, this\.loadout\.magazineSize\(\)/.test(brain);
check(!reloadCapacity, 'the old loadout-only capacity is gone from the reload path');
check(/private int capacityOf\(ItemStack held\)/.test(brain)
  && /fromItem > 0 \? fromItem : this\.loadout\.magazineSize\(\)/.test(brain),
  'with a documented fallback when TaCZ cannot answer');

console.log('');
console.log('5. the drop keeps the attachments');
check(/dropGunAndAmmo/.test(loot), 'the death path is GunLoot.dropGunAndAmmo');
check(/getMainHandItem\(\)\.copy\(\)/.test(loot),
  'which copies the gun the mob is actually holding (the same ItemStack, attachments and all)');
check(/mob\.spawnAtLocation\(gun\)/.test(loot),
  'and spawns it as an item entity, so picking it up keeps its NBT');
check(!/GunPool\.buildGun|GunItemBuilder/.test(loot),
  'it never rebuilds the gun (a rebuilt gun would lose the attachments)');

console.log('');
console.log('6. config, command and docs');
for (const key of ['MODS_ENABLED', 'MODS_PER_SLOT_CHANCE', 'MODS_FULL_MOD_CHANCE', 'MODS_MAX_PER_GUN',
  'MODS_ALLOW_EXTENDED_MAG']) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
}
check(/\.push\("mods"\)/.test(config), 'the keys live in their own [mods] section');
for (const key of ['perSlotChance', 'fullModChance', 'maxPerGun', 'allowExtendedMag', 'enabled']) {
  check(readme.includes(key), `README documents mods.${key}`);
}
check(/### 5p\./.test(readme), 'README has the 5p section');
check(/allowAttachment/.test(readme) && /installAttachment/.test(readme),
  'and documents that TaCZ answers the legality question');
check(/capacityOf|重读/.test(readme), 'and the re-read capacity rule');
check(/Commands\.literal\("mods"\)/.test(commands) && /testMods/.test(commands),
  '/tarkovscav test mods exists');
check(/GunAttachments\.describe\(held\)/.test(commands), 'and it reports the attachments it finds');
check(/MODS_FULL_MOD_CHANCE/.test(commands), 'and the configured chances');

console.log('');
console.log('7. the candidate pool: TaCZ\'s index, and a cache that never remembers emptiness');
// THE 2026 BUG (a real instance): the first request ran while TaCZ was still scanning its gun packs
// ("GunPackFinder: Start scanning... Found 8 possible gunpack(s)" a few seconds earlier), the item-registry
// scan found nothing, and computeIfAbsent cached the EMPTY list for the whole session - so every gun stayed
// bare and every gun logged "no legal attachment". These checks pin the fix.
check(/TimelessAPI\.getAllCommonAttachmentIndex\(\)/.test(modsCode),
  'the candidates come from TaCZ\'s own attachment index (getAllCommonAttachmentIndex)');
check(/CommonAttachmentIndex/.test(modsCode) && /index\.getType\(\) != type/.test(modsCode),
  'and are filtered by the index\'s own getType() - the id+type pair, not a capability probe');
check(/AttachmentItemBuilder\.create\(\)\.setId\(entry\.getKey\(\)\)\.build\(\)/.test(modsCode),
  'each id is turned into an ItemStack by TaCZ\'s own AttachmentItemBuilder');
// The index must be asked FIRST; the registry sweep is the fallback and the second diagnostic number.
const indexAt = modsCode.indexOf('List<ItemStack> fromIndex = scanIndex(type);');
const registryAt = modsCode.indexOf('return scanRegistry(type);');
check(indexAt >= 0 && registryAt > indexAt,
  'the index is the primary source and the registry sweep only the fallback',
  `index@${indexAt} registry@${registryAt}`);
check(/if \(!fromIndex\.isEmpty\(\)\) \{\s*return fromIndex;/.test(modsCode),
  'the registry is not even swept when the index answered');
// The cache guards.
check(!/computeIfAbsent/.test(modsCode),
  'the old computeIfAbsent cache (which stored the empty result) is gone');
check(/if \(fresh\.isEmpty\(\)\) \{\s*BY_TYPE\.remove\(type\);/.test(modsCode),
  'an EMPTY pool is removed, never stored - the next request asks TaCZ again');
check(/cached\.indexSize\(\) == indexSize/.test(modsCode) && /int indexSize = indexSize\(\);/.test(modsCode),
  'a cached pool is trusted only while the TaCZ index size it was built from still matches (reload detector)');
check(/public static int indexSize\(\)/.test(modsCode)
  && /catch \(RuntimeException \| LinkageError unavailable\)/.test(modsCode),
  'indexSize() is guarded, so a TaCZ build without the API degrades to the registry instead of throwing');
check(/public static void invalidate\(\)/.test(modsCode) && /BY_TYPE\.clear\(\);/.test(modsCode),
  'invalidate() drops the pools');
check(/GunAttachments\.invalidate\(\)/.test(read('command/ClientCommands.java')),
  'and /tarkovscav client reload calls it (the explicit "re-read everything" hook)');
// No gun-pack-reload event exists in TaCZ's public API - the delivery verified that with javap - so the
// reload is detected by the index size. That claim is checkable without a game: the class files inside the
// jar are DEFLATED, so the check inflates the one class it needs and looks for the method name in its
// constant pool. (A plain byte search of the jar only finds entry NAMES - they are stored uncompressed in
// the central directory - which is exactly the difference this helper exists for.)
const readZipEntry = (zipPath, entryName) => {
  const buf = fs.readFileSync(zipPath);
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) return null;
  const count = buf.readUInt16LE(eocd + 10);
  let at = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(at) !== 0x02014b50) return null;
    const nameLength = buf.readUInt16LE(at + 28);
    const extraLength = buf.readUInt16LE(at + 30);
    const commentLength = buf.readUInt16LE(at + 32);
    const localOffset = buf.readUInt32LE(at + 42);
    const name = buf.toString('utf8', at + 46, at + 46 + nameLength);
    if (name === entryName) {
      // The method and the sizes come from the CENTRAL DIRECTORY record (the local header may hold zeros and
      // point at a data descriptor instead), while the data offset needs the LOCAL name/extra lengths.
      const method = buf.readUInt16LE(at + 10);
      const compressedSize = buf.readUInt32LE(at + 20);
      const localNameLength = buf.readUInt16LE(localOffset + 26);
      const localExtraLength = buf.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + localNameLength + localExtraLength;
      const raw = buf.subarray(start, start + compressedSize);
      return method === 0 ? Buffer.from(raw) : zlib.inflateRawSync(raw);
    }
    at += 46 + nameLength + extraLength + commentLength;
  }
  return null;
};
const taczLib = path.join(ROOT, 'libs', 'tacz-1.20.1-1.1.8-hotfix.jar');
check(fs.existsSync(taczLib), 'the TaCZ dev lib is present (libs/)', path.basename(taczLib));
if (fs.existsSync(taczLib)) {
  const timeless = readZipEntry(taczLib, 'com/tacz/guns/api/TimelessAPI.class');
  check(timeless !== null, 'and TimelessAPI.class can be inflated out of it',
    timeless ? `${timeless.length} bytes` : 'not found');
  check(timeless !== null && timeless.includes('getAllCommonAttachmentIndex'),
    'with the getAllCommonAttachmentIndex method in its constant pool');
  check(timeless !== null && timeless.includes('getCommonAttachmentIndex'),
    'and the single-id lookup');
  const builder = readZipEntry(taczLib, 'com/tacz/guns/api/item/builder/AttachmentItemBuilder.class');
  check(builder !== null && builder.includes('setId') && builder.includes('build'),
    'AttachmentItemBuilder has setId/build, the pair we construct candidates with');
  const index = readZipEntry(taczLib, 'com/tacz/guns/resource/index/CommonAttachmentIndex.class');
  check(index !== null && index.includes('getType'),
    'and CommonAttachmentIndex has getType(), which classifies a candidate');
}
check(!/GunPackReload|GunPackLoadEvent|ServerMessageSyncGunPack/.test(modsCode),
  'and we do not pretend to listen to a pack-reload event that the API does not have');
// The per-slot attempt count, and the diagnostic surface.
check(/PICK_ATTEMPTS = 16/.test(modsCode) && /Math\.min\(candidates\.size\(\), PICK_ATTEMPTS\)/.test(modsCode),
  'a slot tries up to 16 candidates before refusing (a narrow mount still gets a fair chance)');
check(/public static List<String> diagnostics\(\)/.test(modsCode)
  && /": index=" \+ indexed/.test(modsCode) && /" registry-scan=" \+ registry/.test(modsCode)
  && /" candidates=" \+ \(indexed > 0 \? indexed : registry\)/.test(modsCode),
  'diagnostics() reports index / registry-scan / candidates per slot');
check(/GunAttachments\.diagnostics\(\)/.test(commands),
  'and /tarkovscav test mods prints them');
check(/public static boolean indexEmpty\(\)/.test(modsCode)
  && /TaCZ's attachment index is EMPTY/.test(commands) && /indexEmpty\(\)/.test(modsCode),
  'with an explicit "your gun packs define no attachments" line when the index is empty');
check(/TaCZ's attachment index is EMPTY/.test(modsCode) && /Run \/tarkovscav test mods/.test(modsCode),
  'and the per-gun WARN says so too (once per gun id, never per spawn)');
check(/private static void logAvailability\(AttachmentType type, int count\)/.test(modsCode)
  && /Integer previous = LAST_LOGGED\.put\(type, count\);/.test(modsCode),
  'the six availability INFO lines are printed only when the number CHANGES (no log spam)');
check(/if \(!Config\.MODS_ENABLED\.get\(\) \|\| gun\.isEmpty\(\)\) \{\s*return 0;/.test(modsCode),
  'mods.enabled = false stays inert: nothing is scanned and nothing is installed');
check(/attachment index/.test(readme) && /test mods/.test(readme)
  && /registry-scan/.test(readme),
  'and README explains the pool, the index, the diagnostic command and its two numbers');

console.log('');
console.log('8. scripted-gun protection (README 5p, the hamster:win1894 crash)');
// The crash: a gun pack's Lua runs inside TaCZ's own tick (ModernKineticGunItem.tickBolt), so a script that
// throws takes the server down and we cannot catch it. The only structural defence is to keep such a gun out
// of a mob's hands. This section pins the judgement, the pool filter, the runtime guard and the defaults.
const safety = strip(read('gun/ScriptedGuns.java'));
// Comments are stripped above, but this one is part of the check ("say WHY the cache is skipped"), so the
// raw source is read too.
const safetyRaw = read('gun/ScriptedGuns.java');
const gunPoolCode = strip(read('gun/GunPool.java'));
const brainCode2 = strip(read('gun/GunBrain.java'));
const clientCommands = strip(read('command/ClientCommands.java'));
check(/GunData data = index\.get\(\)\.getGunData\(\);/.test(safety) && /data\.getScript\(\)/.test(safety),
  'the judgement comes from TaCZ\'s index: GunData#getScript() (the DECLARED script id)');
check(/TimelessAPI\.getCommonGunIndex\(gunId\)/.test(safety),
  'looked up by gun id through TimelessAPI');
// CommonGunIndex#getScript() returns org.luaj.vm2.LuaTable, and luaj is not a compile-time dependency here
// (TaCZ is compileOnly) - so that call must NOT appear, or the build breaks. README says so out loud.
check(!/LuaTable/.test(safety),
  'the LuaTable-typed CommonGunIndex#getScript() is deliberately not called (luaj is not on our classpath)');
check(/public static boolean isBlocked\(@Nullable ResourceLocation gunId\)/.test(safety)
  && /Config\.trustedScriptNamespaces\(\)\.contains\(namespace\)/.test(safety),
  'a scripted gun is blocked unless its script NAMESPACE is trusted');
check(/if \(gunId == null \|\| !Config\.EXCLUDE_SCRIPTED_GUNS\.get\(\)\) \{\s*return false;/.test(safety),
  'and the whole rule is inert while guns.excludeScriptedGuns is false (the old behaviour)');
check(/LOGGED\.add\(gunId\.toString\(\)\)/.test(safety) && /\[gunsafety\]/.test(safety),
  'the first time a gun is blocked it is logged once (id + script + namespace), never silently');
check(/static String namespaceOf\(String script\)/.test(safety) && /indexOf\(':'\)/.test(safety),
  'the namespace is the part before the colon');
// The EMPTY-CACHE lesson, second time: during a gun-pack reload TaCZ's index is briefly empty, and caching
// that "this gun has no script" answer would silently let every scripted gun through for the session (the
// runtime guard reads the same cache). So an unknown gun must answer without being remembered.
const scriptOfBody = safety.slice(safety.indexOf('public static String scriptOf'),
  safety.indexOf('public static boolean isBlocked'));
check(/if \(index\.isEmpty\(\)\) \{\s*\/\/[^\n]*\n\s*\/\/[^\n]*\n\s*return "";/.test(scriptOfBody)
  || /if \(index\.isEmpty\(\)\) \{[^}]*return "";/.test(scriptOfBody),
  'scriptOf() returns early when TaCZ does not know the gun (the reload window)');
const emptyBranch = /if \(index\.isEmpty\(\)\) \{([\s\S]{0,400}?)\}/.exec(scriptOfBody);
check(emptyBranch !== null && !/SCRIPT_OF\.put/.test(emptyBranch[1]),
  'and the unknown-gun branch does NOT cache (only a real index answer is remembered)',
  emptyBranch ? emptyBranch[1].replace(/\s+/g, ' ').slice(0, 60) : 'branch not found');
check(/SCRIPT_OF\.put\(gunId, script\);\s*return script;/.test(scriptOfBody),
  'while a genuine "no script" (and a genuine script id) IS cached, so the hot path is unchanged');
check(/do NOT cache/i.test(safetyRaw) || /Not known \(yet\)/.test(safetyRaw),
  'and the code says why, so the next reader does not "optimise" the cache back');
// The truth table, simulated exactly as the Java decides it.
const blockedByRule = (script, trusted, enabled) => {
  if (!enabled || script === '') return false;
  const namespace = script.includes(':') ? script.split(':')[0].toLowerCase() : script.toLowerCase();
  return !trusted.includes(namespace);
};
check(blockedByRule('hamster:win1894_gun_logic', ['tacz'], true) === true,
  'the crashing gun (hamster:win1894_gun_logic) is blocked by default');
check(blockedByRule('tacz:xmag_reload_logic', ['tacz'], true) === false,
  'a TaCZ-namespace script (19 of the default pack\'s 47 guns, all running fine today) is NOT blocked');
check(blockedByRule('hamster:win1894_gun_logic', [], true) === true
  && blockedByRule('tacz:xmag_reload_logic', [], true) === true,
  'an empty trusted list is the literal "exclude every scripted gun" mode');
check(blockedByRule('mypack:logic', ['tacz', 'mypack'], true) === false,
  'adding a namespace trusts that pack');
check(blockedByRule('hamster:x', ['tacz'], false) === false,
  'excludeScriptedGuns = false restores the old behaviour for every gun');
check(blockedByRule('', ['tacz'], true) === false,
  'a gun that declares no script is never blocked (that is most of the pool)');
check(/if \(ScriptedGuns\.isBlocked\(id\)\) \{\s*rejectedByScript\+\+;\s*continue;/.test(gunPoolCode),
  'GunPool.build() skips a blocked gun and counts it separately (visible in gunpool/logSummary)');
check(/rejectedByScript/.test(gunPoolCode) && /ScriptedGuns\.describe\(8\)/.test(gunPoolCode),
  'and the pool summary reports how many the scripted-gun rule kept out');
check(/private boolean sanitizeScriptedGun\(ServerLevel level\)/.test(brainCode2),
  'GunBrain has the runtime guard (for guns that arrived from a rack, /give or an old save)');
check(/if \(\(level\.getGameTime\(\) \+ this\.mob\.getId\(\)\) % period != 0L\)/.test(brainCode2),
  'it is staggered per mob and gated by guns.scriptedGunRescanTicks');
check(/this\.mob\.setItemInHand\(InteractionHand\.MAIN_HAND, ItemStack\.EMPTY\);\s*this\.gunStack = ItemStack\.EMPTY;\s*this\.loadout = null;/
  .test(brainCode2),
  'the swap clears the hand AND the brain fields FIRST, so nothing ticks the blocked gun any more');
check(/if \(ScriptedGuns\.isBlocked\(loadout\.gunId\(\)\)\)/.test(brainCode2),
  'equipLoadout screens a gun restored from disk too (a world saved before this rule)');
check(/ScriptedGuns\.invalidate\(\);/.test(clientCommands),
  'and /tarkovscav client reload drops the per-id script cache so a new trusted list takes effect');
check(/\.define\("excludeScriptedGuns", true\)/.test(config),
  'guns.excludeScriptedGuns ships true');
check(/defineListAllowEmpty\(List\.of\("trustedScriptNamespaces"\), \(\) -> List\.of\("tacz"\)/.test(config),
  'guns.trustedScriptNamespaces ships ["tacz"]');
check(/defineInRange\("scriptedGunRescanTicks", 40, 0, 2400\)/.test(config),
  'guns.scriptedGunRescanTicks ships 40');
check(/public static List<String> trustedScriptNamespaces\(\)/.test(config)
  && /public static int scriptedGunRescanTicks\(\)/.test(config),
  'both have accessors');
check(/ScriptedGuns\.describe\(10\)/.test(commands) && /kept out of the pool/.test(commands),
  '/tarkovscav test mods prints the blocked list');
check(/GunPool\.rejectedByScript\(\)/.test(commands) && /scripted-gun rule kept out/.test(commands),
  'and /tarkovscav gunpool says how many the rule removed and which');
check(/excludeScriptedGuns/.test(readme) && /trustedScriptNamespaces/.test(readme)
  && /scriptedGunRescanTicks/.test(readme),
  'README documents all three keys');
check(/LuaError/.test(readme) && /hamster/.test(readme) && /tickBolt/.test(readme),
  'and records the crash it exists for (the LuaError, the script id, TaCZ\'s tick)');
check(/230/.test(readme) && /105/.test(readme) && /79/.test(readme) && /20 /.test(readme),
  'with the instance-level numbers (230 guns / 105 scripted / 79 blocked by default, 20 of the default 47)');
check(/GunpowderRevolution|hamster/.test(readme) && /echoes_of_ruin/.test(readme),
  'and the per-pack breakdown that came out of tools/gunpack_script_scan.ps1');
check(/luaj/.test(readme) && /LuaTable/.test(readme),
  'and the honest note about the LuaTable accessor we cannot call');

console.log('');
console.log('9. guns.respectDeclaredFireModes: the gun\'s own data file decides the fire mode');
// The truth table, as a simulation of the resolver's documented rule: AUTO when declared, else the FIRST
// declared mode, else AUTO. `declared` is what GunData#getFireModeSet() answers.
const resolveMode = (declared) => {
  if (declared.length === 0 || declared.includes('auto')) return 'AUTO';
  return declared[0].toUpperCase();
};
for (const [declared, want] of [
  [['auto'], 'AUTO'],
  [['semi'], 'SEMI'],
  [['burst', 'semi'], 'BURST'],
  [[], 'AUTO'],
  [['semi', 'auto'], 'AUTO'],
  [['auto', 'burst', 'semi'], 'AUTO'],
  [['semi', 'burst'], 'SEMI'],
]) {
  const got = resolveMode(declared);
  check(got === want, `truth table: declares [${declared}] -> ${got}`, want === got ? '' : `want ${want}`);
}
// The same rule, as source: the ternary the gate describes must be the one that ships.
check(/FireMode chosen = declared\.isEmpty\(\) \|\| declared\.contains\(FireMode\.AUTO\) \? FireMode\.AUTO\s*\n?\s*: declared\.get\(0\);/
  .test(gunPoolCode),
  'and that is exactly the expression in GunPool#fireModeFor (empty or AUTO -> AUTO, else the first declared)');
check(/public static synchronized FireMode fireModeFor\(ResourceLocation gunId\)/.test(gunPoolCode),
  'the resolver is one public entry point');
check(/if \(!Config\.respectDeclaredFireModes\(\)\) \{\s*return FireMode\.AUTO;/.test(gunPoolCode),
  'with the default answer AUTO, so respectDeclaredFireModes = false (the shipped default) is the old behaviour');
check(/TimelessAPI\.getCommonGunIndex\(gunId\)\s*\n?\s*\.map\(index -> index\.getGunData\(\)\.getFireModeSet\(\)\)/
  .test(gunPoolCode),
  'the declared modes come from TaCZ\'s index (GunData#getFireModeSet), not from a table of ours');
check(/\.filter\(list -> list != null && !list\.isEmpty\(\)\)\s*\n?\s*\.orElse\(List\.of\(\)\)/.test(gunPoolCode),
  'a gun TaCZ cannot answer for (or that declares nothing) resolves to "no declaration", i.e. AUTO');
// Both builders must use the ONE resolver, or a forced AUTO would silently come back in the fallback path.
const setFireModeCalls = (pool.match(/\.setFireMode\(/g) || []).length;
check(setFireModeCalls === 2 && (pool.match(/\.setFireMode\(fireMode\)/g) || []).length === 2,
  'both builder branches (build + forceBuild) use the resolver\'s value, never a literal',
  `${setFireModeCalls} setFireMode call(s)`);
check(!/\.setFireMode\(FireMode\.AUTO\)/.test(pool),
  'and no code path writes a hard-coded AUTO into a mob\'s stack any more');
check((pool.match(/fireModeFor\(loadout\.gunId\(\)\)/g) || []).length === 1,
  'the mode is read once per stack, so build and forceBuild cannot disagree');
check(/FIRE_MODE_LOGGED\.add\(gunId\)/.test(gunPoolCode)
  && /respectDeclaredFireModes=true: \{\} declares \{\} -> mob stack uses \{\}/.test(pool),
  'ONE INFO line per gun says what was declared and what was chosen');
check(/FIRE_MODE_LOGGED\.clear\(\)/.test(gunPoolCode),
  'and /tarkovscav client reload re-arms those log lines (the cache is cleared with the pool)');
check(/\.define\("respectDeclaredFireModes", false\)/.test(config),
  'guns.respectDeclaredFireModes ships false (flipping it would silently re-balance every world)');
check(/public static boolean respectDeclaredFireModes\(\)/.test(config),
  'it has an accessor, like every other key');
check(/respectDeclaredFireModes/.test(readme), 'README documents guns.respectDeclaredFireModes');
check(/firemode_scan\.ps1/.test(readme), 'README names the instance audit that produced the numbers');
check(/api:getFireMode\(\) == AUTO/.test(readme) && /rapid_bolt_time/.test(readme),
  'and records the crash chain: the script\'s AUTO branch reading a field win1894 does not define');
check(/win1894/.test(readme) && /fire_mode/.test(readme) && /\[semi\]/.test(readme),
  'including what win1894 actually declares');
check(/122/.test(readme) && /108/.test(readme) && /114/.test(readme) && /230/.test(readme),
  'and the verified instance numbers (230 guns, 108 declare auto, 122 forced today, 114 -> SEMI)');

console.log('');
if (failures > 0) {
  console.log(`${failures} mods check(s) FAILED`);
  process.exit(1);
}
console.log('attachment pool invariants all hold');
