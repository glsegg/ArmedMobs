// The kill feed (README 5u).
//
//   node tools/selftest_killfeed.js
//
// The feature is a small pipeline with three decision points, and each one is checked here as a source
// invariant AND as a simulation, because "who killed whom, with what, and who may see it" is exactly the kind
// of logic that silently drifts:
//   1. naming: the fallback chain, the table-driven weapon resolver (with the extension point the grenade batch
//      needs), fists, environment deaths and the WARN-once for an unnameable weapon;
//   2. visibility: the three modes, the radius boundary, the per-second throttle and the dedup window;
//   3. the HUD: enabled=false, the line cap (oldest dropped), F1, position/scale, and the truncation rule that
//      keeps a long name from wrapping or running off the screen.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const RES = path.join(ROOT, 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const langText = (name) => fs.readFileSync(path.join(ASSETS, 'lang', `${name}.json`), 'utf8');

const feed = read('killfeed/KillFeed.java');
const feedCode = strip(feed);
const weapons = read('killfeed/KillFeedWeapons.java');
const weaponsCode = strip(weapons);
const source = read('killfeed/KillFeedSource.java');
const network = read('killfeed/KillFeedNetwork.java');
const hud = read('client/KillFeedHud.java');
const hudCode = strip(hud);
const setup = read('client/ClientSetup.java');
const config = read('Config.java');
const main = read('TarkovScav.java');
const clientCommands = read('command/ClientCommands.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

console.log('1. naming the killer: the fallback chain');
// The order in the method body is the chain: assert the three answers appear in this order.
const chainBody = feedCode.slice(feedCode.indexOf('public static LivingEntity killerOf'));
const order = ['source.getEntity()', 'victim.getLastHurtByMob()', 'victim.getLastDamageSource()']
  .map((needle) => chainBody.indexOf(needle));
check(order.every((index) => index >= 0) && order[0] < order[1] && order[1] < order[2],
  'the killer chain asks the damage source first, then lastHurtByMob, then lastDamageSource');
check(/return null;\s*\n\s*\}/.test(chainBody.slice(0, chainBody.indexOf('// ---'))) || /return null;/.test(chainBody),
  'and gives up cleanly (null = an environment death)');
// The request named getKiller()/getLastHurtByPlayer(); they do not exist on LivingEntity in 1.20.1, so the
// code must not call them (it would not compile) and must say so.
check(!/victim\.getKiller\(\)|victim\.getLastHurtByPlayer\(\)/.test(feed),
  'it does not call getKiller()/getLastHurtByPlayer() (no such accessors in 1.20.1)');
check(/protected field with no public read/.test(feed),
  'and the source explains why the chain looks different from the request');
// Simulation of the chain, with each answer present or absent.
function killerOf(hasSource, hasLastHurt, hasLastDamage) {
  if (hasSource) return 'source';
  if (hasLastHurt) return 'lastHurtByMob';
  if (hasLastDamage) return 'lastDamageSource';
  return null;
}
check(killerOf(true, true, true) === 'source' && killerOf(false, true, true) === 'lastHurtByMob'
  && killerOf(false, false, true) === 'lastDamageSource' && killerOf(false, false, false) === null,
  'simulated: every step of the chain is reachable and the empty case is null (environment)');
check(/boolean environment = killer == null && source\.getEntity\(\) == null && source\.getDirectEntity\(\) == null/
  .test(feedCode), 'an environment death is "no killer entity at all", not a guess');

console.log('');
console.log('2. naming the weapon: a TABLE, not a chain of ifs');
check(/public interface Resolver/.test(weapons) && /List<Resolver> RESOLVERS = new ArrayList<>\(List\.of\(/
  .test(weapons), 'the resolvers live in one ordered list');
check(/public static void register\(Resolver resolver\) \{\s*RESOLVERS\.add\(0, resolver\);/.test(weapons),
  'and there is a public extension point (the grenade batch registers one line, no control flow edit)');
check(/RESOLVERS\.add\(0, resolver\)/.test(weapons) && /for \(Resolver resolver : RESOLVERS\)/.test(weapons),
  'the chain is walked in order and the newest registration wins');
check(/public static KillFeedSource fromMessageId\(String messageId\)/.test(weapons)
  && /id\.contains\("fall"\)/.test(weapons) && /id\.contains\("drown"\)/.test(weapons)
  && /id\.contains\("explosion"\)/.test(weapons),
  'the damage-message table names explosions, falls, drowning and the rest in one place');
check(/KillFeedWeapons::fromExplosion/.test(weapons) && /KillFeedWeapons::fromKillerHand/.test(weapons)
  && /KillFeedWeapons::fromDamageMessage/.test(weapons),
  'and the three resolvers are listed as method references (adding one is a one-line edit)');
check(/ItemStack held = killer\.getMainHandItem\(\);/.test(weaponsCode),
  'the weapon is the item in the killer\'s hand at the moment of the kill');
check(/return Armament\.of\(KillFeedSource\.FISTS\);/.test(weaponsCode),
  'an empty hand is "fists", not "unknown"');
check(/private static Armament unknown\(DamageSource source, @Nullable LivingEntity killer\)/.test(weapons)
  && /WARNED\.add\(id\)/.test(weapons) && /TarkovScav\.LOGGER\.warn\("\[killfeed\] could not name the weapon/
    .test(weapons),
  'an unnameable weapon says "unknown" AND WARNs once per id (never silent)');
check(/Set<String> WARNED = ConcurrentHashMap\.newKeySet\(\)/.test(weapons),
  'the WARN dedup is a set, so it is once per id and not once per death');
// The gun-name rule: the stack travels and the CLIENT names it, so a TaCZ gun shows its translated name.
check(/buffer\.writeItem\(message\.weapon\(\)\)/.test(network),
  'the weapon travels as an ItemStack (so the client can translate it)');
check(/public Component weaponText\(\)/.test(hud) && /this\.weapon\.getHoverName\(\)/.test(hud),
  'and the client draws getHoverName() - the real, translated name of a TaCZ gun, not its id');
check(!/"tacz:"|tacz:/.test(hud) && !/getHoverName\(\)\.getString\(\)/.test(hud),
  'no raw gun id and no pre-rendered string is used for the weapon');
check(/source == KillFeedSource\.ITEM && !this\.weapon\.isEmpty\(\)/.test(hud),
  'a category with no item falls back to the client\'s own translation of that category');
for (const lang of ['en_us', 'zh_cn']) {
  const text = langText(lang);
  check(text.includes('"tarkovscav.killfeed.weapon.fists"')
    && text.includes('"tarkovscav.killfeed.weapon.unknown"')
    && text.includes('"tarkovscav.killfeed.weapon.explosion"')
    && text.includes('"tarkovscav.killfeed.environment"'),
    `${lang}.json has the fists / unknown / explosion / environment texts`);
}
check(langText('zh_cn').includes('赤手空拳') && langText('zh_cn').includes('未知武器')
  && langText('zh_cn').includes('摔落'),
  'the Chinese texts are the ones the request used (赤手空拳 / 未知武器 / 摔落)');

console.log('');
console.log('3. who may see a line');
check(/String mode = Config\.KILLFEED_MODE\.get\(\)\.toLowerCase\(Locale\.ROOT\)/.test(feedCode)
  && /if \(mode\.equals\("global"\)\)/.test(feedCode) && /if \(mode\.equals\("all"\)\)/.test(feedCode),
  'the three modes are read from the config, global and all handled explicitly');
check(/level\.getServer\(\)\.getAllLevels\(\)/.test(feedCode),
  'global crosses dimensions on purpose');
check(/player\.distanceToSqr\(victim\) <= radius \* radius/.test(feedCode)
  && /player == killer \|\| player == victim/.test(feedCode),
  'involved = the killer, the victim, or within the radius (squared distance, no square root per player)');
// Simulation of the radius boundary.
const within = (distance, radius) => distance * distance <= radius * radius;
check(within(63.9, 64) && within(64, 64) && !within(64.1, 64),
  'the radius boundary is inclusive at exactly killFeed.radius');
check(/KILLFEED_SHOW_ENVIRONMENT_DEATHS\.get\(\)/.test(feedCode)
  && /victim instanceof Player \|\| Faction\.of\(victim\) != null/.test(feedCode),
  'an environment death also needs a player or one of our units as the victim');
check(/return playerInvolved \? Config\.KILLFEED_SHOW_PLAYER_KILLS\.get\(\) : Config\.KILLFEED_SHOW_MOB_KILLS\.get\(\);/
  .test(feedCode), 'player kills and mob kills have their own switches');
check(/int max = Config\.KILLFEED_MAX_PER_SECOND\.get\(\);/.test(feedCode)
  && /long second = now \/ 20L;/.test(feedCode) && /if \(window\[0\] >= max\)/.test(feedCode),
  'the throttle is a per-player, per-second window');
check(/private static boolean duplicate\(String killer, String victim, String weapon, long now\)/.test(feed)
  && /now - last <= window/.test(feed),
  'the dedup window is killer+victim+weapon inside dedupTicks');
// Simulation of the throttle and the dedup.
function throttle(max, ticks) {
  const out = [];
  let window = -1;
  let used = 0;
  for (const tick of ticks) {
    const second = Math.floor(tick / 20);
    if (second !== window) { window = second; used = 0; }
    out.push(used < max);
    if (used < max) used++;
  }
  return out;
}
const throttled = throttle(4, Array.from({ length: 40 }, (_, i) => i));
check(throttled.filter(Boolean).length === 8,
  'simulated: maxPerSecond = 4 allows exactly 8 lines over two seconds', `${throttled.filter(Boolean).length}`);
check(throttled.slice(0, 4).every(Boolean) && !throttled[4] && throttled[20],
  'and the window refills on the next second');
const dedup = (window, last, now) => last !== null && now - last <= window;
check(!dedup(40, null, 100) && dedup(40, 100, 120) && dedup(40, 100, 140) && !dedup(40, 100, 141),
  'simulated: the dedup window is inclusive at its edge and expires after it');
check(/PacketDistributor\.PLAYER\.with\(\(\) -> player\)/.test(network),
  'a line is sent to specific players, not broadcast');
check(/public record KillFeedMessage\(String killer, String victim, ItemStack weapon, KillFeedSource source,\s*long gameTime\)/
  .test(network), 'the payload is data only: two names, a stack, a category and a tick');

console.log('');
console.log('4. the HUD');
check(/public final class KillFeedHud implements IGuiOverlay/.test(hud)
  && /event\.registerAboveAll\("killfeed", KillFeedHud\.INSTANCE\)/.test(setup),
  'the HUD is a Forge GUI overlay registered above everything');
check(/!Config\.KILLFEED_ENABLED\.get\(\) \|\| LINES\.isEmpty\(\)/.test(hudCode),
  'enabled = false draws nothing at all');
check(/minecraft\.options\.hideGui/.test(hudCode), 'F1 hides it (the vanilla flag, not a copy of it)');
check(/LINES\.add\(0, new Line\(/.test(hudCode) && /LINES\.remove\(LINES\.size\(\) - 1\)/.test(hudCode),
  'new lines go in at the top and the OLDEST is dropped at the cap');
// Simulation of the cap.
function push(list, line, max) {
  list.unshift(line);
  while (list.length > max) list.pop();
  return list;
}
let lines = [];
for (let i = 1; i <= 8; i++) push(lines, `line${i}`, 5);
check(lines.length === 5 && lines[0] === 'line8' && lines[4] === 'line4',
  'simulated: maxLines = 5 keeps the newest five, newest first', lines.join(','));
check(/switch \(position\(\)\)/.test(hudCode) && /case "top_left"/.test(hudCode)
  && /case "top_right"/.test(hudCode),
  'the horizontal anchor is configurable (top_center / top_left / top_right)');
check(/graphics\.pose\(\)\.scale\(\(float\) scale/.test(hudCode),
  'and the scale is applied to the pose stack');
check(/int fadeOut = Math\.min\(FADE_OUT_TICKS, Math\.max\(1, duration \/ 4\)\);/.test(hudCode)
  && /age < FADE_IN_TICKS/.test(hudCode),
  'fade in at the start and fade out at the end of lineDurationTicks');
check(/LINES\.removeIf\(line -> \+\+line\.age > duration\)/.test(hudCode),
  'a line is dropped when it has lived longer than lineDurationTicks');
// The truncation rule, simulated with the same arithmetic as trimToWidth.
function trimToWidth(widthOf, text, maxWidth) {
  if (maxWidth <= 0) return '';
  if (widthOf(text) <= maxWidth) return text;
  const ellipsis = '...';
  const room = maxWidth - widthOf(ellipsis);
  if (room <= 0) return text.slice(0, Math.max(0, maxWidth));
  let out = '';
  for (const ch of text) {
    if (widthOf(out + ch) > room) break;
    out += ch;
  }
  return out + ellipsis;
}
const widthOf = (s) => s.length * 6; // a stand-in for Font#width
const longName = 'A'.repeat(300);
const trimmed = trimToWidth(widthOf, longName, 240);
check(widthOf(trimmed) <= 240 && trimmed.endsWith('...'),
  'a 300-character name is truncated to the width with an ellipsis, never wrapped',
  `${longName.length} chars -> ${trimmed.length}`);
check(trimToWidth(widthOf, '短名', 240) === '短名', 'a short name is left exactly as it is');
check(/int allowed = \(int\) \(width \* MAX_WIDTH_SHARE \/ scale\)/.test(hudCode),
  'and the width budget is the screen width (scaled), so a bigger scale truncates earlier');
check(/font\.plainSubstrByWidth\(text, room\) \+ ellipsis/.test(hudCode),
  'the cut is done by the font (so it never splits a glyph in half)');

console.log('');
console.log('5. config, command and docs');
for (const [key, value] of [['KILLFEED_ENABLED', 'define("enabled", true)'],
  ['KILLFEED_MODE', 'define("mode", "involved")'],
  ['KILLFEED_RADIUS', 'defineInRange("radius", 64.0D'],
  ['KILLFEED_SHOW_MOB_KILLS', 'define("showMobKills", true)'],
  ['KILLFEED_SHOW_PLAYER_KILLS', 'define("showPlayerKills", true)'],
  ['KILLFEED_SHOW_ENVIRONMENT_DEATHS', 'define("showEnvironmentDeaths", true)'],
  ['KILLFEED_LINE_DURATION_TICKS', 'defineInRange("lineDurationTicks", 100'],
  ['KILLFEED_MAX_LINES', 'defineInRange("maxLines", 5'],
  ['KILLFEED_POSITION', 'define("position", "top_center")'],
  ['KILLFEED_SCALE', 'defineInRange("scale", 1.0D'],
  ['KILLFEED_MAX_PER_SECOND', 'defineInRange("maxPerSecond", 4'],
  ['KILLFEED_DEDUP_TICKS', 'defineInRange("dedupTicks", 40']]) {
  check(new RegExp(`ForgeConfigSpec[^;]*\\b${key}\\b`).test(config), `Config declares ${key}`);
  check(config.includes(value), `  ${key} default matches the documented one`, value);
}
check(/\.push\("killFeed"\)/.test(config), 'the keys live in their own [killFeed] section');
for (const key of ['mode', 'radius', 'showMobKills', 'showPlayerKills', 'showEnvironmentDeaths',
  'lineDurationTicks', 'maxLines', 'position', 'scale', 'maxPerSecond', 'dedupTicks', 'enabled']) {
  check(readme.includes(key), `README documents killFeed.${key}`);
}
check(/### 5u\./.test(readme), 'README has the 5u section');
check(/击杀列表|击杀播报/.test(readme), 'README explains the feature in Chinese');
check(/MinecraftForge\.EVENT_BUS\.register\(com\.gfl\.tarkovscav\.killfeed\.KillFeed\.class\)/.test(main),
  'the death listener is registered');
check(/KillFeedNetwork\.register\(\)/.test(main), 'and the packet is registered');
check(/literal\("killfeed"\)/.test(clientCommands) && /testKillFeed/.test(clientCommands),
  '/tarkovscav test killfeed exists');
check(/KillFeedHud\.add\(killerName, victimName, stack, category\)/.test(clientCommands),
  'and it previews a line without killing anything');
check(/KillFeedSource\.values\(\)/.test(clientCommands) && /BuiltInRegistries\.ITEM\.get\(id\)/
  .test(clientCommands),
  'the preview takes an item id OR a category, so both paths are visible by eye');

console.log('');
if (failures > 0) {
  console.log(`${failures} kill feed check(s) FAILED`);
  process.exit(1);
}
console.log('kill feed invariants all hold');
