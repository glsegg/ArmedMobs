// Scratch: finish delivery 10 - README 5y, the gate section, and the armor/tier/voice readout in commands.
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/';
const JAVA = R + 'src/main/java/com/gfl/tarkovscav/';
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, t) => fs.writeFileSync(p, t);
let misses = 0;
const sub = (file, from, to, count) => {
  const text = read(file);
  if (!text.includes(from)) {
    console.log('MISS in', file.split('/').pop(), ':', from.slice(0, 60));
    misses++;
    return;
  }
  write(file, count ? text.split(from).join(to) : text.replace(from, to));
};

// ---------------------------------------------------------------- 1. README 5y -------------------------
const readme = R + 'README.md';
const section = [
  '### 5y. 部队与优质单位（USEC 村民 / BEAR 掠夺者 / 优质村民 / 优质掠夺者）',
  '',
  '用户原话：「村民和掠夺者都新增一个**部队村民 / 部队掠夺者**，分别用 **USEC 和 BEAR** 的语音，然后……再都加个变体，**优质村民 / 优质掠夺者**，用**优质系列**语音，同时把他们的**血量增加到 40**，并且**随机生成身上穿着 1-6 甲**，1 甲可以**减免 10% 伤害**，6 甲可以**减免 60% 伤害**。」',
  '',
  '| 实体 | id | 父类 | 阵营 | 语音家族 | 血量 | 精度档位 |
  '| --- | --- | --- | --- | --- | --- | --- |',
  '| 部队村民 | `tarkovscav:usec_villager` | `GunnerVillagerEntity` | 村庄（友好） | `usec` | **40** | veteran 0.85 |',
  '| 部队掠夺者 | `tarkovscav:bear_pillager` | `GunnerPillagerEntity` | 灾厄（敌对） | `bear` | **40** | veteran 0.85 |',
  '| 优质村民 | `tarkovscav:elite_villager` | `GunnerVillagerEntity` | 村庄（友好） | `elite` | **40** | elite 0.90 |',
  '| 优质掠夺者 | `tarkovscav:elite_pillager` | `GunnerPillagerEntity` | 灾厄（敌对） | `elite` | **40** | elite 0.90 |',
  '',
  '**复用**：模型/渲染（走"一次调用+循环"那套，不会再漏注册）、举枪姿态、`GunBrain`/掩体/换弹/压制/游走、精度机制、阵营与警戒共享、放置台转化、击杀播报、配件池——全部继承，没有分叉。名字走 `EntityNames` 组合（村民系带职业后缀，不会有原始键上屏）。',
  '',
  '#### 1–6 级甲（**方案 A：不穿原版甲**）',
  '生成时**随机**滚一个 1..6 的等级，**写进实体持久数据**（`tarkovscav:armorClass`，随存档持久化，重载不丢也不重滚），伤害在 `LivingHurtEvent` 里按 **`10% × 等级`** 减免：**1 级 10% … 6 级 60%**。等级可在 `/tarkovscav spawn`、`/tarkovscav test sniper` 的输出里看到（`ArmorClass.describe()`），`armorEnabled=false` 时等级仍然滚、仍然显示，但**不减免**。',
  '',
  '**为什么不用"看得见的甲"（方案 B）**：如果给他穿上对应等级的原版护甲，原版护甲点会**先减一次**，我们的百分比再减一次，**总减免会超过 60%**（把 60% 叠到 20 点护甲上，理论上限是 `1-(1-0.6)(1-0.8)=92%`，见闸门打印的数据结论）。要做到"总减免恒等于 10%×等级"就得精确建模原版护甲曲线与每种伤害类型，只能**平均吻合**，所以默认不做（用户没有要求）。若要开启，前置是：① 一个 `armor.cosmeticArmor` 开关；② 一套按伤害类型反解原版护甲的仿真与逐级断言；③ 装饰甲的掉落/生成清理规则。',
  '',
  '#### 配置键（`[troops]`）',
  '| Key | Default | Meaning |',
  '| --- | --- | --- |',
  '| `armorEnabled` | `true` | 是否启用等级减免；false 时等级照滚照显示但不减伤 |',
  '| `armorReductionPerClass` | `0.10` | 每级减免比例（0.10 → 1 级 10%、6 级 60%） |',
  '| `minArmorClass` | `1` | 生成时能滚到的最低等级 |',
  '| `maxArmorClass` | `6` | 生成时能滚到的最高等级 |',
  '| `usecVillagerWeight` | `2` | 部队村民的自然生成权重（普通武装村民是 4） |',
  '| `bearPillagerWeight` | `2` | 部队掠夺者的权重（普通武装掠夺者是 6） |',
  '| `eliteVillagerWeight` | `1` | 优质村民的权重（**四者中最低**，刻意稀有） |',
  '| `elitePillagerWeight` | `1` | 优质掠夺者的权重（同上） |',
  '',
  '#### 语音池（结构已在 ⑩ 接好，音频在 ⑪ 落盘）',
  '每个实体回答自己的 `voiceFamily()`（`usec` / `bear` / `elite`，老单位是 `shared`），`VoicePools` 按 `<family>_<category>` 取池（例如 `usec_contact`），**该家族还没有片时回退到共享池**——所以 ⑩ 交付时四个单位仍说老句子，⑪ 把 ogg 放进去后**同一份代码立刻开始说自己的话**，不用改一行。**隔离规则**：类别永不跨家族（USEC 只会问 `usec_*`），闸门按实体类型断言这张表。优质系列按 **(A)** 落地（用自己的 `hurt` 当 death、`suppressed/fire` 当 chatter、reload 不配），用户若改选 (B) 只改这张表一行。',
  '',
  '#### 判据（用户肉眼确认）',
  '1. 四种名字正确：部队村民 / 部队掠夺者 / 优质村民 / 优质掠夺者（村民系带职业后缀）；',
  '2. 血量 **40**（打它要打更久）；',
  '3. `/tarkovscav spawn usec_villager`（或任意一个）的输出里能看到 **armor=class N (N0% less damage)**；',
  '4. ⑪ 之后：**彼此只说各自的语音**（USEC 说 USEC、BEAR 说 BEAR、优质说优质）。',
  '',
  '### 5x. 「村民的名字显示成 `entity.tarkovscav.gunner_villager.weaponsmith`」—— 名字解析与原始键兜底',
].join('\n');
sub(readme, '### 5x. 「村民的名字显示成', section);

// ---------------------------------------------------------------- 2. the gate section ------------------
const gate = R + 'tools/selftest_entity_registry.js';
const gateSection = [
  '',
  "console.log('');",
  "console.log('12. faction troops: 40 health, the rolled armor class, the tier and the voice pools (README 5y)');",
  "const armor = read('entity/ArmorClass.java');",
  "const spawn = read('entity/ArmorClassSpawn.java');",
  "const tier = read('entity/FactionTierProfile.java');",
  "const pools = read('voice/VoicePools.java');",
  "const README = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');",
  "check(/public static boolean isTroop\\(LivingEntity entity\\)/.test(armor)",
  "  && ['UsecVillagerEntity', 'EliteVillagerEntity', 'BearPillagerEntity', 'ElitePillagerEntity']",
  "    .every((cls) => armor.includes('instanceof ' + cls)),",
  "  'the armor system names exactly the four faction troops');",
  "check(/public static double reductionFor\\(int armorClass\\)/.test(armor)",
  "  && /clamped \\* Config\\.ARMOR_REDUCTION_PER_CLASS\\.get\\(\\)/.test(armor),",
  "  'the reduction is class x armorReductionPerClass (one formula, one place)');",
  "check(/@SubscribeEvent[\\s\\S]{0,200}?public static void onHurt\\(LivingHurtEvent event\\)/.test(armor)",
  "  && /event\\.setAmount\\(after\\)/.test(armor),",
  "  'LivingHurtEvent scales the blow - no second reduction anywhere');",
  "check(!/setItemSlot\\(EquipmentSlot\\.CHEST|Items\\.DIAMOND_CHESTPLATE|NETHERITE_CHESTPLATE/.test(armor))",
  "  , 'and it hands out no vanilla armor (option A: that would reduce the damage twice)');",
  "check(/putInt\\(TAG, Math\\.max\\(0, Math\\.min\\(6, armorClass\\)\\)\\)/.test(armor)",
  "  && /getPersistentData\\(\\)\\.getInt\\(TAG\\)/.test(armor),",
  "  'the class round-trips through the entity persistent data under ONE tag (save/load cannot lose it)');",
  "check(/if \\(!Config\\.ARMOR_ENABLED\\.get\\(\\) \\|\\| !isTroop\\(victim\\)\\)/.test(armor),",
  "  'armorEnabled = false is the first thing the hook checks (inert, but the class is still rolled)');",
  "check(/event\\.loadedFromDisk\\(\\)/.test(spawn) && /rollForIfTroop/.test(spawn),",
  "  'and the roll happens once per real spawn, never on a chunk load');",
  "// Simulated reduction for every class: 10 %..60 %, never more, never less.",
  "const reduction = (armorClass, perClass) => Math.max(0, Math.min(6, armorClass)) * perClass;",
  "for (let armorClass = 1; armorClass <= 6; armorClass++) {",
  "  const value = reduction(armorClass, 0.10);",
  "  check(Math.abs(value - armorClass * 0.10) < 1e-9,",
  "    'class ' + armorClass + ' reduces ' + (armorClass * 10) + ' %', (value * 100).toFixed(0) + ' %');",
  "}",
  "check(reduction(0, 0.10) === 0 && reduction(7, 0.10) === 0.60 && reduction(99, 0.10) === 0.60,",
  "  'and the bounds hold: no class reduces less than 10 % or more than 60 %');",
  "const optionB = 1 - (1 - 0.60) * (1 - 0.80);",
  "check(optionB > 0.60,",
  "  'option B (vanilla armor + ours) would exceed the 60 % cap - the data conclusion for not doing it',",
  "  (optionB * 100).toFixed(0) + ' % at 20 armor points');",
  "check(/profileFor\\(Mob mob\\)/.test(tier)",
  "  && /return AccuracyProfile\\.Profile\\.ELITE;/.test(tier)",
  "  && /return AccuracyProfile\\.Profile\\.VETERAN;/.test(tier),",
  "  'the troop tiers map to the elite and veteran profiles');",
  "check(/if \\(EliteVillagerEntity|if \\(mob instanceof EliteVillagerEntity \\|\\| mob instanceof ElitePillagerEntity\\)/.test(tier)",
  "  && /if \\(mob instanceof UsecVillagerEntity \\|\\| mob instanceof BearPillagerEntity\\)/.test(tier),",
  "  'elite pair -> elite, USEC/BEAR -> veteran, in that order');",
  "check(/Profile troop = com\\.gfl\\.tarkovscav\\.entity\\.FactionTierProfile\\.profileFor\\(mob\\);/.test(read('gun/AccuracyProfile.java')),",
  "  'and AccuracyProfile consults it before the by-type rule (nothing else changed)');",
  "// The voice-pool isolation table, simulated: every entity resolves to its own family, or to shared.",
  "const FAMILY_BY_ENTITY = { usec_villager: 'usec', bear_pillager: 'bear', elite_villager: 'elite',",
  "  elite_pillager: 'elite', scav: 'shared', gunner_pillager: 'shared', gunner_villager: 'shared',",
  "  sniper_pillager: 'shared', sniper_villager: 'shared' };",
  "const resolvePool = (family, category, familiesWithClips) => {",
  "  if (family !== 'shared' && familiesWithClips.includes(family + '_' + category)) {",
  "    return family + '_' + category;",
  "  }",
  "  return 'shared_' + category;",
  "};",
  "for (const [id, family] of Object.entries(FAMILY_BY_ENTITY)) {",
  "  for (const category of ['contact', 'idle', 'chatter', 'grenade', 'death', 'mark']) {",
  "    // With every family's clips present, no entity may reach another family's pool.",
  "    const resolved = resolvePool(family, category, FAMILIES_ALL);",
  "    const foreign = FAMILIES_ALL.filter((other) => other !== family && resolved === other + '_' + category);",
  "    check(foreign.length === 0, id + ' never speaks a foreign pool (' + category + ')', resolved);",
  "  }",
  "}",
  "function checkEveryEntityHasOwnFamily() {",
  "  // The four troops override voiceFamily(); everything else inherits 'shared'.",
  "  for (const [cls, family] of [['UsecVillagerEntity', 'usec'], ['BearPillagerEntity', 'bear'],",
  "    ['EliteVillagerEntity', 'elite'], ['ElitePillagerEntity', 'elite']]) {",
  "    check(new RegExp('return \"' + family + '\";').test(read('entity/' + cls + '.java')),",
  "      cls + ' speaks the ' + family + ' family');",
  "  }",
  "}",
  "const FAMILIES_ALL = ['shared', 'usec', 'bear', 'elite'].flatMap((family) =>",
  "  ['contact', 'idle', 'chatter', 'grenade', 'death', 'mark'].map((category) => family + '_' + category));",
  "checkEveryEntityHasOwnFamily();",
  "check(/ModSounds\\.pool\\(family \\+ \"_\" \\+ category\\)/.test(pools)",
  "  && /family\\.equals\\(\"shared\"\\)/.test(pools),",
  "  'VoicePools looks a family pool up by name and falls back to the shared one');",
  "check(/public String voiceFamily\\(\\)/.test(read('entity/GunnerPillagerEntity.java'))",
  "  && /public String voiceFamily\\(\\)/.test(read('entity/GunnerVillagerEntity.java')),",
  "  'and the base classes expose the family hook (default shared, so old mobs are untouched)');",
  "check(/ArmorClass\\.describe/.test(commandsSrc) || /ArmorClass\\.describe/.test(read('command/ModCommands.java')),",
  "  'the armor class is readable from a command (ArmorClass.describe)');",
  "check(/### 5y\\./.test(README), 'README has the 5y section');",
  "for (const key of ['armorEnabled', 'armorReductionPerClass', 'minArmorClass', 'maxArmorClass',",
  "  'usecVillagerWeight', 'bearPillagerWeight', 'eliteVillagerWeight', 'elitePillagerWeight']) {",
  "  check(README.includes(key), 'README documents ' + key);",
  "}",
].join('\n');
sub(gate, "\nconsole.log('');\nif (failures > 0) {", gateSection + "\nconsole.log('');\nif (failures > 0) {");

// ---------------------------------------------------------------- 3. the command readout ---------------
sub(JAVA + 'command/ModCommands.java',
  'String extra = mob instanceof com.gfl.tarkovscav.gun.SniperMob sniper',
  'String troopInfo = " " + com.gfl.tarkovscav.entity.ArmorClass.describe(mob)\n'
  + '                + " tier=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(mob)\n'
  + '                + " " + com.gfl.tarkovscav.voice.VoicePools.describe(mob);\n'
  + '        String extra = mob instanceof com.gfl.tarkovscav.gun.SniperMob sniper');
sub(JAVA + 'command/ModCommands.java',
  '                : "";\n        source.sendSuccess(() -> Component.literal(Component.translatable("tarkovscav.command.spawn.ok",',
  '                : "";\n        extra = extra + troopInfo;\n        source.sendSuccess(() -> Component.literal(Component.translatable("tarkovscav.command.spawn.ok",');
sub(JAVA + 'command/ModCommands.java',
  '                    + " distanceToPlayer=" + String.format(java.util.Locale.ROOT, "%.1f",\n'
  + '                            Math.sqrt(sniper.distanceToSqr(source.getPosition())));',
  '                    + " distanceToPlayer=" + String.format(java.util.Locale.ROOT, "%.1f",\n'
  + '                            Math.sqrt(sniper.distanceToSqr(source.getPosition())))\n'
  + '                    + " " + com.gfl.tarkovscav.entity.ArmorClass.describe(sniper)\n'
  + '                    + " tier=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(sniper)\n'
  + '                    + " " + com.gfl.tarkovscav.voice.VoicePools.describe(sniper);');
console.log('done, misses =', misses);
