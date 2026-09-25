// Turns the cutter's report into every data file the mod needs for the new clips. No sound is cut here.
//
//   node tools/voice_emit.js
//
// It reads  tools/spike/work/voice_clips_report.json  (tools/make_family_voice_clips.ps1)
// and, if present, tools/spike/work/effect_clips_report.json (an impact/effect clip, see README 5v/5l),
// then writes - idempotently, so it can be re-run after adding a clip:
//   1. src/main/resources/assets/tarkovscav/sounds.json   (event -> file + subtitle key)
//   2. src/main/resources/assets/tarkovscav/voice_clips.txt (the manifest ModSounds reads at construction,
//      so shipping a new clip is a data change and never a code change)
//   3. the subtitle keys in BOTH language files (inserted as text, so the rest of the file is untouched)
//   4. the inventory table in README 5l, between the voice-inventory markers
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'tarkovscav');
const SOUNDS_JSON = path.join(ASSETS, 'sounds.json');
const MANIFEST = path.join(ASSETS, 'voice_clips.txt');
const README = path.join(ROOT, 'README.md');
const VOICE_REPORT = path.join(__dirname, 'spike', 'work', 'voice_clips_report.json');
const EFFECT_REPORT = path.join(__dirname, 'spike', 'work', 'effect_clips_report.json');

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const writeUtf8 = (file, text) => fs.writeFileSync(file, text, 'utf8');

// ---------------------------------------------------------------- what to say about each clip
const FAMILY_LABEL = { usec: 'USEC', bear: 'BEAR', elite: '优质PMC' };
const FAMILY_LABEL_EN = { usec: 'USEC', bear: 'BEAR', elite: 'Elite PMC' };
const CATEGORY_ZH = {
  contact: '发现敌人', chatter: '交火喊话', idle: '自言自语', grenade: '手雷', mark: '报点', death: '阵亡',
};
const CATEGORY_EN = {
  contact: 'Enemy spotted', chatter: 'In the fight', idle: 'Muttering', grenade: 'Grenade',
  mark: 'Lost them', death: 'Down',
};
// The source event names carry the meaning; the gloss is what the subtitle shows next to it.
const EVENT_ZH = {
  enemy_contact: '发现敌人', attention: '警戒', ambush: '遭遇埋伏', enemyonpoint: '敌人占点',
  fight: '交火', gogogo: '冲', suppressingfire: '压制射击', inthefront: '前方接敌',
  enemy_down: '击倒敌人', enemy_hit: '命中敌人',
  mutter: '自语', tired: '疲惫', dontknow: '不确定', ready: '就绪', backpack: '整理背包',
  grenade: '手雷', enemy_grenade: '敌方手雷', grenadeflash: '闪光弹',
  lostvisual: '跟丢了', onyourown: '各自为战', regroup: '集合', followme: '跟我来',
  death: '阵亡', agony: '重伤', hurt_neardeath: '濒死', hurt_medium: '中伤',
  '标记敌人-located': '标记敌人', located: '报点', '被压制-suppressed': '被压制',
  '被击中-fire': '被击中', '队友击杀敌人-praise': '队友击杀', '嘲讽-insult': '嘲讽',
  '有手雷-grenade': '手雷', '受伤-hurt': '受伤',
};
const EVENT_EN = {
  enemy_contact: 'Enemy contact', attention: 'Watch out', ambush: 'Ambush', enemyonpoint: 'Enemy on point',
  fight: 'In the fight', gogogo: 'Go go go', suppressingfire: 'Suppressing fire',
  inthefront: 'Enemy in front', enemy_down: 'Enemy down', enemy_hit: 'Hit him',
  mutter: 'Muttering', tired: 'Exhausted', dontknow: 'Not sure', ready: 'Ready', backpack: 'Checking gear',
  grenade: 'Grenade', enemy_grenade: 'Enemy grenade', grenadeflash: 'Flashbang',
  lostvisual: 'Lost visual', onyourown: 'On your own', regroup: 'Regroup', followme: 'Follow me',
  death: 'Down', agony: 'Agony', hurt_neardeath: 'Near death', hurt_medium: 'Hurt',
  '标记敌人-located': 'Enemy marked', located: 'Location', '被压制-suppressed': 'Suppressed',
  '被击中-fire': 'Taking fire', '队友击杀敌人-praise': 'Teammate got one', '嘲讽-insult': 'Taunt',
  '有手雷-grenade': 'Grenade', '受伤-hurt': 'Hurt',
};

const voice = fs.existsSync(VOICE_REPORT) ? readJson(VOICE_REPORT).generated : [];
const effects = fs.existsSync(EFFECT_REPORT) ? readJson(EFFECT_REPORT).generated : [];
if (voice.length === 0 && effects.length === 0) {
  console.error('no clip report found - run tools/make_family_voice_clips.ps1 first');
  process.exit(1);
}

const entries = [];
for (const clip of voice) {
  const gloss = EVENT_ZH[clip.event] || clip.event;
  const glossEn = EVENT_EN[clip.event] || clip.event;
  entries.push({
    event: `voice.${clip.name}`,
    sound: `voice/${clip.name}`,
    group: clip.pool,
    family: clip.family,
    category: clip.category,
    source: clip.source,
    seconds: clip.seconds,
    meanDb: clip.meanDb,
    peakDb: clip.peakDb,
    bytes: clip.bytes,
    subtitleZh: `${FAMILY_LABEL[clip.family] || clip.family}：${gloss}（${clip.event}）`,
    subtitleEn: `${FAMILY_LABEL_EN[clip.family] || clip.family}: ${glossEn} (${clip.event})`,
  });
}
for (const clip of effects) {
  entries.push({
    event: clip.event,
    sound: clip.sound,
    group: 'effect',
    family: 'effect',
    category: clip.category || 'effect',
    source: clip.source,
    seconds: clip.seconds,
    meanDb: clip.meanDb,
    peakDb: clip.peakDb,
    bytes: clip.bytes,
    subtitleZh: clip.subtitleZh,
    subtitleEn: clip.subtitleEn,
  });
}

// ---------------------------------------------------------------- 1. sounds.json
// The family entries are dropped and re-added, so re-running after adding one clip cannot leave a stale
// entry behind (and the hand-written base entries are never touched).
const FAMILY_PREFIX = /^voice\.(usec|bear|elite)_/;
const sounds = readJson(SOUNDS_JSON);
let dropped = 0;
for (const key of Object.keys(sounds)) {
  if (FAMILY_PREFIX.test(key) || /^grenade_(land|bounce)/.test(key)) {
    delete sounds[key];
    dropped++;
  }
}
for (const entry of entries) {
  sounds[entry.event] = {
    subtitle: `subtitles.tarkovscav.${entry.event}`,
    sounds: [`tarkovscav:${entry.sound}`],
  };
}
writeUtf8(SOUNDS_JSON, `${JSON.stringify(sounds, null, 2)}\n`);
console.log(`sounds.json: ${Object.keys(sounds).length} entries (${entries.length} new, ${dropped} stale dropped)`);

// ---------------------------------------------------------------- 2. the manifest ModSounds reads
const lines = ['# Generated by tools/voice_emit.js - do not edit by hand.', '# One sound EVENT per line.'];
let currentGroup = null;
for (const entry of entries) {
  if (entry.group !== currentGroup) {
    currentGroup = entry.group;
    lines.push(`# pool ${currentGroup}`);
  }
  lines.push(entry.event);
}
writeUtf8(MANIFEST, `${lines.join('\n')}\n`);
console.log(`voice_clips.txt: ${entries.length} event(s) in ${new Set(entries.map((e) => e.group)).size} pool(s)`);

// ---------------------------------------------------------------- 3. subtitles, inserted as text
for (const [lang, field] of [['zh_cn', 'subtitleZh'], ['en_us', 'subtitleEn']]) {
  const file = path.join(ASSETS, 'lang', `${lang}.json`);
  const before = fs.readFileSync(file, 'utf8');
  const kept = before.split('\n').filter((line) => !/^\s*"subtitles\.tarkovscav\.(voice\.(usec|bear|elite)_|grenade_(land|bounce))/.test(line));
  let anchor = -1;
  for (let i = 0; i < kept.length; i++) {
    if (/^\s*"subtitles\.tarkovscav\./.test(kept[i])) anchor = i;
  }
  if (anchor < 0) {
    console.error(`${lang}.json has no subtitle block to anchor to`);
    process.exit(1);
  }
  if (!/,\s*$/.test(kept[anchor])) {
    kept[anchor] = `${kept[anchor]},`;
  }
  const added = entries.map((entry) => `  "subtitles.tarkovscav.${entry.event}": ${JSON.stringify(entry[field])},`);
  kept.splice(anchor + 1, 0, ...added);
  // The subtitle block is the LAST block of both language files, so the line we insert right before the
  // closing brace must not carry a trailing comma (JSON has no trailing commas).
  const lastAdded = anchor + added.length;
  const nextLine = kept.slice(lastAdded + 1).find((line) => line.trim().length > 0);
  if (nextLine !== undefined && /^\s*\}/.test(nextLine)) {
    kept[lastAdded] = kept[lastAdded].replace(/,\s*$/, '');
  }
  const after = kept.join('\n');
  JSON.parse(after); // never write a language file that stopped being valid JSON
  writeUtf8(file, after);
  console.log(`${lang}.json: ${added.length} subtitle key(s) inserted after the existing block`);
}

// ---------------------------------------------------------------- 4. the README inventory
const START = '<!-- voice-inventory:start -->';
const END = '<!-- voice-inventory:end -->';
const readme = fs.readFileSync(README, 'utf8');
const totalBytes = entries.reduce((sum, e) => sum + (e.bytes || 0), 0);
const rows = [];
rows.push(START);
rows.push('');
rows.push('#### 阵营语音池（⑪：USEC / BEAR / 优质PMC，每池 5 条）');
rows.push('');
rows.push('每个家族 6 个池、每池 5 条，共 90 条；全部**单声道 44.1 kHz**、'
  + '首尾裁静音、`loudnorm=I=-16.5:TP=-1.0:LRA=11` 后再统一增益到**均值 ≈ −16.8 dB、峰值 ≤ −1.0 dB**'
  + '（峰值被限住的句子均值会更低，这与最初 27 条的取舍一致）。**池表**在 `tools/voice_pools.json`，'
  + '**剪裁**在 `tools/make_family_voice_clips.ps1`，**落盘**在 `tools/voice_emit.js`——'
  + '`<家族>_<类别>` 就是一个池，`ModSounds` 读 `voice_clips.txt` 清单注册，所以加语音是**改数据**不是改代码。');
rows.push('');
rows.push('| 池 | 条数 | 来源（前三） | 触发 |');
rows.push('| --- | --- | --- | --- |');
for (const group of [...new Set(entries.filter((e) => e.family !== 'effect').map((e) => e.group))]) {
  const of = entries.filter((e) => e.group === group);
  const [, category] = group.split(/_(.*)/s);
  rows.push(`| \`${group}\` | ${of.length} | ${of.slice(0, 3).map((e) => '`' + e.source.replace(/\.ogg$/, '') + '`').join('、')} | ${CATEGORY_ZH[category] || category} |`);
}
const effectEntries = entries.filter((e) => e.family === 'effect');
if (effectEntries.length) {
  rows.push('');
  rows.push(`**效果音**：${effectEntries.map((e) => '`' + e.event + '`（' + e.source + '）').join('、')}`
    + '——手雷**落地/弹跳**，见 §5v。');
}
rows.push('');
rows.push(`合计 ${entries.length} 条、${(totalBytes / 1024 / 1024).toFixed(2)} MB（`+
  '打包目标 ≤ 20 MB）。**第三方素材声明**：USEC/BEAR 语音来自《逃离塔科夫》原版音频（用户提供的 `塔科夫音效.rar`），'
  + '优质PMC 语音来自用户提供的 `优质PMC.zip`，手雷落地音效来自 B 站 `BV1j1cZeHEAt`（0:00–0:02）。'
  + '**仅供本地私用，请勿随整合包公开发布。**');
rows.push('');
rows.push(END);
const block = rows.join('\n');
const existing = new RegExp(`${START}[\\s\\S]*?${END}`);
let next;
if (existing.test(readme)) {
  next = readme.replace(existing, block);
} else {
  const anchor = '**The triggers** (all switchable';
  if (!readme.includes(anchor)) {
    console.error('README has no anchor for the inventory block');
    process.exit(1);
  }
  next = readme.replace(anchor, `${block}\n\n${anchor}`);
}
writeUtf8(README, next);
console.log(`README: inventory block written (${entries.length} clip(s), ${(totalBytes / 1024 / 1024).toFixed(2)} MB)`);
console.log('Next: edit ModSounds to read voice_clips.txt, then rebuild.');
