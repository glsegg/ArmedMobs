# Armed Mobs (武装暴徒)

Minecraft **1.20.1 / Forge 47** mod: **Scav** mobs and a **gun-armed pillager** that fight with
**TaCZ** (*Timeless and Classics Zero*) firearms, use cover, suppress, advance and break contact, and
only spawn **inside city areas**. The Scav rig is the user's own YSM model, imported and layered.

> **Name vs. id (deliberate split).** The mod's display name is **Armed Mobs** and the built artifact is
> `armedmobs-<version>`, but the internal mod id deliberately stays **`tarkovscav`** - as do the Java
> package, the resource namespace, the structure ids, the NBT/tag keys and `config/tarkovscav-common.toml`.
> That split is what keeps already-generated city structures, block/NBT tags and the tuned config working in
> existing worlds: a tester updating the jar does not have to touch their save. `/armedmobs ...` is a full
> alias of `/tarkovscav ...` (the same tree is registered under both literals), so either spelling works
> everywhere this document shows a command.

> **Just want the tables?** `docs/COMMAND_AND_CONFIG_REFERENCE.md` is the standalone, wiki-ready reference:
> every command with its arguments, every config key with its shipped default, the four AI tiers, and the
> third-party hooks (entity tags, custom `tarkovscav/city/*.nbt` structures, voice packs, gun pools). It is
> checked against the source by `tools/selftest_wiki_doc.js`, which fails if a command or a key is missing
> from it. This README remains the long-form design record.

---

## 1. What is in here

| Piece | Where | Notes |
| --- | --- | --- |
| `ScavEntity` | `entity/ScavEntity.java` | Monster + GeckoLib `GeoEntity`, rolls a `ScavTier`, two-layer animation |
| `GunnerPillagerEntity` | `entity/GunnerPillagerEntity.java` | `extends Pillager`; keeps every vanilla pillager behaviour, drops the crossbow for a TaCZ gun |
| Gun AI / tactics | `gun/GunBrain.java` | the state machine: `IDLE → ALERT → AIM/FIRE, ADVANCE, SUPPRESS, RELOAD, BOLT, REPOSITION, RETREAT` |
| Cover system | `gun/CombatTactics.java` | ray-cast cover search, target memory, under-fire timer |
| Clip table | `gun/GunClips.java` | which animation clip each TaCZ state plays, per gun family |
| Gun pools | `gun/GunPool.java` | built from **TaCZ's own index**, classified by TaCZ's gun `type` |
| Ammo capability | `gun/MobAmmoInventory.java`, `gun/GunCapabilities.java` | the mob's `IItemHandler`, which is what TaCZ reloads from |
| City gate | `world/CityGate.java`, `world/CitySpawnEvents.java` | structure ids/tags + explicit boxes |
| City preset | `data/tarkovscav/structures/city_small.nbt` | 48 × 26 × 48 ruined city block, 4 buildings, street, alley, roof access |
| Commands | `command/ModCommands.java` | `/tarkovscav …`, including the head-less fight harness |
| Tools | `tools/` | model importer, analysis, city generator, self-tests, RCON workflow |

**TaCZ is a hard dependency and is never bundled.** `mods.toml` declares `tacz` mandatory; the jar
lives in `libs/` (gitignored) and is pulled in as `compileOnly` + `runtimeOnly`. GeckoLib *is*
bundled through `jarJar`.

**No third-party art or audio is shipped.** The YSM model's geometry, animation and texture are the
user's; the author's `avatar/*.png` and `sounds/*.ogg` (some of which is audio from other games) stay
in `assets_source/scav/` and are deliberately not imported - see §6.

---

## 2. Build

```
Set-Location D:\deepseek\ArmedMobs
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'
& '.\gradlew.bat' -g 'D:\deepseek\GirlsFrontline\.gradle-home' build --console=plain
```

The shippable artifact is `build/libs/armedmobs-0.1.0-all.jar`. The file name comes from `mod_jar_name`
in `gradle.properties`; the internal `mod_id` deliberately stays `tarkovscav` (see the note at the top).
Before building, drop the user's
TaCZ jar in as `libs/tacz-1.20.1-1.1.8-hotfix.jar` (or edit `tacz_module` / `tacz_version` in
`gradle.properties`); see `libs/README.md`.

Self-tests (no Minecraft needed, a couple of seconds):

```
.\tools\spike\selftest.ps1
```

Model pipeline (re-runnable; see §6):

```
node tools/analyze_scav_model.js            >  tools/spike/work/scav-analysis.txt
node tools/import_scav_assets.js
```

---

## 3. Damage comes from TaCZ, not from us

`GunBrain` **never calls `hurt`**. Every point of damage a scav deals comes out of

```java
ShootResult r = IGunOperator.fromLivingEntity(mob).shoot(() -> pitch, () -> yaw, System.currentTimeMillis());
```

which makes TaCZ consume a real round, spawn a real `EntityKineticBullet` carrying the gun's own
bullet data (damage, armour penetration, headshot multiplier, per-distance falloff) and resolve the
hit itself. Ammunition, magazines, reload time and rate of fire are all TaCZ's as well; this mod only
decides *when* to pull the trigger and *where* to point, and supplies the ammunition through the
mob's `IItemHandler`.

The verification in §8 proves it the only way that really counts: the practice dummy loses exactly as
much health as the rounds that were fired at it.

---

## 4. Configuration

The file is `config/tarkovscav-common.toml`.

### `[spawn]` — where these mobs may appear

| Key | Default | Meaning |
| --- | --- | --- |
| `scavCityOnly` | `true` | scavs only spawn inside a city area |
| `gunnerPillagerCityOnly` | `true` | gun-armed pillagers only spawn inside a city area |
| `gunnerVillagerCityOnly` | `true` | gun-armed villagers only spawn inside a city area (same gate, same log lines) |
| `gunnerVillagerNaturalSpawn` | `true` | when false, gunner villagers never come from the biome spawner - only an egg, `/summon` or `/tarkovscav spawn`. The natural weight (2, against the scav's 5) lives in `data/tarkovscav/forge/biome_modifier/add_scavs.json` |
| `gateCommandSpawns` | `false` | when false, `/summon` and spawn eggs ignore the gate (so you can always place one by hand for testing) |
| `cityStructureIds` | `["tarkovscav:city_small", "tarkovscav:city_a", "tarkovscav:city_b", "tarkovscav:city_c", "tarkovscav:city_strongpoint"]` | structure ids that make a position a city - the default is **all four shipped presets plus the 18-building strongpoint**. **Point this at your own city mod**, e.g. `"somecitymod:downtown"`, `"minecraft:village_plains"` |
| `cityStructureTags` | `["tarkovscav:city"]` | structure **tags** that make a position a city; the easiest hook for somebody else's mod |
| `cityRegions` | `[]` | explicit boxes for cities that are **not** worldgen structures. Format: `[<name>\|<dimension>\|<x1> <y1> <z1>\|<x2> <y2> <z2>]` |
| `cityRegionPadding` | `4` | how many blocks around a city structure still count as city. **This is the linear lever on the city spawn rate** - it was `24`, and 4 cuts the gateable area around one district by about 53%. See §7i. **An install with an existing `config/tarkovscav-common.toml` keeps its old `24`**: Forge never rewrites a config file that is already there |
| `logSpawnGate` | `true` | log every accept/reject decision to the server log |
| `foundationDepth` | `5` | how many blocks of footing `/tarkovscav city district` guarantees **under every piece it places**. The shipped pieces are baked with 5 (buildings) / 3 (streets) / 0 (rubble), so 5 is a no-op for them; raise it to 7-9 on a mountain, lower it to 3 for less skirt in flat land. The generator's `--foundation N` and this key should be kept equal (§7). **This key lives in `[spawn]`, not `[city]`** - see §7 |

### `[guns]` — which TaCZ guns a mob may be issued

| Key | Default | Meaning |
| --- | --- | --- |
| `gunBlacklist` | `["tacz:rpg7", "tacz:m320", "tacz:minigun"]` | gun ids never handed to a mob |
| `gunWhitelist` | `[]` | when non-empty, **only** these ids may be issued |
| `excludedGunTypes` | `["rpg"]` | whole TaCZ gun types that are never issued |
| `pistolClipTypes` | `["pistol"]` | TaCZ gun types that use the rig's one-handed (`*_pistol`) clips instead of the two-handed (`*_rifle`) ones. Add `"smg"` if you want SMGs to use the one-handed pose too |
| `gunDropChance` | `0.35` | chance a dead mob drops its gun |
| `ammoDropChance` | `0.5` | chance a dead mob drops its spare ammo |
| `ammoItemStacks` | `3` | ammo stacks a mob carries; when they run out it breaks contact |
| `manualReloadFallback` | `true` | top the magazine up ourselves when TaCZ's own `reload()` does nothing. **Required in practice** - see §7 |
| `manualReloadTicks` | `45` | how long that fallback reload takes (the mob stays in cover and plays the reload pose) |
| `reloadStallTicks` | `200` | how long TaCZ may keep reporting a reload "in progress" before the mob stops believing it and breaks contact. Bounds the one state that is otherwise driven entirely by TaCZ - see §5i |
| `excludeScriptedGuns` | `true` | scripted-gun protection master switch: a gun whose script namespace is not trusted never enters the mob pool (a mob already holding one is re-issued). Turning it off restores the pre-fix behaviour and **can crash the server again** - see §5p |
| `trustedScriptNamespaces` | `["tacz"]` | script namespaces that are still allowed. `[]` = exclude **every** scripted gun (safest, much smaller pool); add a namespace = trust that pack's scripts; case-insensitive |
| `scriptedGunRescanTicks` | `40` | interval of the runtime fallback rescan (ticks, staggered per entity id); `0` = only filter at spawn/load |
| `respectDeclaredFireModes` | `false` | let the **gun's own data file** decide the fire mode of the gun in a mob's hands; `false` = the old behaviour (everything `AUTO`). See §5p |

Tiers are filled from TaCZ's gun `type` field, so a custom gun pack feeds them automatically:
`pistol` tier ← `pistol`, `smg` · `shotgun` tier ← `shotgun` · `rifle` tier ← `rifle`, `mg` ·
`sniper` tier ← `sniper`.

### `[combat]` — shared behaviour

| Key | Default | Meaning |
| --- | --- | --- |
| `reactionTicks` | `12` | delay between spotting a target and raising the gun (fairness window) |
| `giveUpTicks` | `160` | how long a mob keeps trying before disengaging |
| `retreatHealthFraction` | `0.35` | below this fraction of max health it falls back |
| `repositionTicks` | `40` | time spent moving to a new firing position |
| `targetMemoryTicks` | `100` | how long a mob remembers where its target was after losing sight of it (this is what makes suppression possible) |
| `fireStallTicks` | `100` | how long `FIRE` may go without a single accepted shot before the mob gives up on the burst position and moves. This is the anti-stall watchdog - see §5i |
| `logGunAi` | `true` | log every state change, every TaCZ `ShootResult`, every cover decision, and a distance/cover/position/shots/`ticks`/`stateDist` telemetry line every second |

### `[ai]` — 门：所有武装单位都开门，也关门（整套行为见 §5a）

| Key | Default | Meaning |
| --- | --- | --- |
| `closeDoorsBehind` | `true` | 单位穿过**自己打开的木门**后把它关回去。与开门无关：门照开，这个键只决定关不关。`false` = 旧行为（走过的门一直开着）。**原版没有任何东西会在怪物走过去以后关门**：唯一带门 Goal 的原版怪 `Vindicator` 用的 `RaiderOpenDoorGoal` 是 `closeDoor=false` 构造的（只开不关），而村民大脑里那个会关门的 `InteractWithDoor` 在武装村民一有目标时就被停掉（`GunnerVillagerEntity#customServerAiStep`）——所以这一条是我们自己加的 |
| `doorCloseDelayTicks` | `40` | 开门后最多再等多少 tick（20 = 1s）、在"没人挡路"时关门。这是给"开了门却停在门口的单位"兜底用的：正常情况下单位一离开 `doorCloseAllyRadius` 就立刻关（规则里那个"或"）。`40` = 原版门冷却常量 `InteractWithDoor.COOLDOWN_BEFORE_RERUNNING_IN_SAME_NODE`（20）的两倍，单位开了门又马上折返时还有整整 1 秒能从门里再穿回来。`0` = 门口一空就关 |
| `doorCloseAllyRadius` | `2.0` | **安全半径**（格）：除关门者本人外，只要有**任何**活着的实体在这个半径内（对门的上下两半各算一次）就不关门。`1.5` 是原版自己的开门交互距离（`DoorInteractGoal#canUse` 用 `distanceToSqr <= 2.25`），`2.0` 刻意更大——"近到足以让它开门"的家伙一定在保护圈里；而且 `2.0` 就是**原版村民大脑用的同一个数**（`InteractWithDoor.MAX_DISTANCE_TO_HOLD_DOOR_OPEN_FOR_OTHER_MOBS = 2.0d`，测试方法也照抄：`doorPos.closerToCenterThan(entity.position(), 2.0)`）。调大 = 队伍过门时更少被打断，调小 = 门关得更早 |
| `enabled` | `true` | **智力分级总开关**（README 5aa）。`false` = 完全不读下面四个分档，所有单位回到"只有全局键"的旧行为（`[combat] reactionTicks` + 原始 `[tactics]`）；`true` = 四个分档**按倍数缩放**那些全局键（没有全局键的 5 个才是绝对值）。"新 AI 我不喜欢"是一把键的事 |

### `[ai.<scav\|sniper\|troop\|elite>]` — 四档智力（整套行为与推导见 §5aa）

档位：`scav`（最笨：慢、几乎不用掩体、短点射、无压制、直线推进、不协同）· `sniper`（耐心：只蹲隐蔽射击位、不推进、命中率不够不开枪）· `troop`（稳健战术：反应快、掩体到掩体、强压制、会协同）· `elite`（猛冲：反应最快、短促冲刺、交战距离更近、会协同）。

**缩放规则（对已经调过 `[tactics]` 的人是承诺，不是口号）**：下面每个 `*Scale` 都**乘以**同名全局键，所以你的数值仍然是基线。例：`suppressChance` 的实际值 = `[tactics] suppressChance` × `suppressChanceScale`（再夹到 0..1）。只有 `reactionMinTicks` / `reactionMaxTicks` / `holdPost` / `minHitChance` / `patienceTicks` / `coordination` 是绝对值——它们**没有**对应的全局键。`[tactics] retreatSprint` 例外：它**永远不被缩放**，全局关了就是关了，没有任何档位能偷偷把疾跑打开。

| Key | scav | sniper | troop | elite | Meaning |
| --- | --- | --- | --- | --- | --- |
| `reactionMinTicks` | `12` | `16` | `6` | `4` | 发现目标后**不开枪**的最短 tick 数（20 = 1s）。绝对值：`enabled=true` 时取代 `[combat] reactionTicks` |
| `reactionMaxTicks` | `24` | `30` | `10` | `8` | 上限。**每次"新获得目标"都重掷一次**（`0.6–1.2s` 就是 scav 的 `12..24`）；min=max 即去掉抖动 |
| `accuracyScale` | `0.80` | `1.0` | `1.0` | `1.05` | 乘在枪械档位 `accuracy` 上（随后仍受精度档位 75%/85%/90% 上限约束）。scav 0.80 让它低于自己的 rookie 上限；elite 1.05 只在 0.90 上限处生效 |
| `coverChance` | `0.15` | `1.0` | `0.90` | `0.75` | 0..1：一次"要不要找掩体"的判定里真的去找的概率。scav 0.15 = **很少用掩体**。换弹/撤退找掩体**不**受它影响（那是保命） |
| `coverRadiusScale` | `0.45` | `1.0` | `1.0` | `0.85` | 乘 `coverSearchRadius`：scav 只看身边一小圈 |
| `coverCacheScale` | `1.5` | `1.0` | `1.0` | `0.75` | 乘 `coverCacheTicks`：scav 更久才重算，elite 更早重算 |
| `suppressChanceScale` | `0.0` | `0.0` | `1.6` | `1.0` | 乘 `suppressChance` 后夹 0..1：troop 0.6×1.6=0.96（**强压制**），elite 仍是 0.6（中），scav/sniper 完全无压制 |
| `suppressTicksScale` | `0.0` | `0.0` | `1.5` | `1.0` | 乘 `suppressTicks`：troop 60→90 tick |
| `suppressAccuracyScale` | `1.0` | `1.0` | `0.80` | `1.0` | 乘 `suppressAccuracyMultiplier`：troop 的压制更准（0.45→0.36） |
| `suppressBurstScale` | `0.5` | `1.0` | `1.2` | `1.0` | 乘 `suppressBurstMultiplier`：scav 只有短点射 |
| `advanceCoverScale` | `0.0` | `1.0` | `0.6` | `1.5` | 乘 `advanceCoverStep`。**`0.0` = 这个档位推进时根本不找掩体（scav 直线走）**；troop 3→1.8 = 小步；elite 3→4.5 = 长步/冲刺 |
| `coverSeekSpeedScale` | `1.0` | `1.0` | `1.0` | `1.15` | 乘 `coverSeekSpeedModifier`：elite 的冲刺略快于走 |
| `repositionScale` | `1.0` | `1.0` | `0.6` | `0.5` | 乘 `[combat] repositionTicks`：troop/elite 不在空地上磨蹭 |
| `retreatHealthScale` | `1.0` | `0.6` | `1.0` | `0.7` | 乘 `[combat] retreatHealthFraction`：sniper/elite 更晚才脱离 |
| `engageRangeScale` | `1.0` | `1.0` | `1.0` | `0.6` | 乘枪械档位 `engageRange`：elite 拉到约 60% 距离才开火（近战倾向） |
| `holdPost` | `false` | `true` | `false` | `false` | `true` = 超出射程时**不推进**，改为换射击位（复用 `SniperBehavior`/`SniperPost`）。只有狙击档 |
| `minHitChance` | `0.0` | `0.5` | `0.0` | `0.0` | 0 = 关。开火前先算**预估命中率**，低于它**不起射**。估计用的是**稳态误差锥**（故意**不**含 warm-up 惩罚——那个惩罚只能靠开枪消除，把它算进去会让"不开枪→永远不热→永远不开枪"自锁）。实测（veteran 0.85 锥、0.3 半径目标）：20 格 85%、30 格 72.5%、40 格 58.7%、52 格 47.1%，所以 0.50 只在约 48 格以外拒射 |
| `patienceTicks` | `0` | `100` | `0` | `0` | 为了 `minHitChance` 在 AIM 里最多等多少 tick，超过就重新决策（防"瞄着永远不开枪"）。0 = 不设上限 |
| `coordination` | `false` | `false` | `true` | `true` | 这个档位是否参加小队协同（下面那一节）。sniper 独来独往、scav 不会配合 |
| `partialCoverBonus` | `250` | `250` | `250` | `250` | 狙击手选点时，对"只遮住一半（眼/脚其一被挡）或比目标至少高 1 格"的位置额外加分。完全看不见的位置仍然是硬编码的 1000 分优先；0 = 只认完全隐蔽（旧行为） |

### `[ai.coordination]` — 小队协同（troop / elite；整套机制见 §5aa）

小队 = **同阵营 + 在 `squadRadius` 内 + 警报共享**（对方有活目标或手上有接触报告）。成员表**缓存** `squadCacheTicks`，所以是"每个单位每窗口一次实体查询"，不是每 tick 全图扫描。

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | 协同总开关。`false` = 不查小队、不排角色、不占点：每个单位按自己的档位单打独斗 |
| `focusFire` | `true` | **①集火**：全队指定**一个**目标（票数最多者，平票取最小实体 id），刚获得目标的成员会转向它——但**必须自己看得见**（阵营情报本身永远不能开战，同 §5m） |
| `overwatch` | `true` | **②交替掩护**：同一时刻只有一名成员是压制手（失去视线时用现有 SUPPRESS 状态压住目标），其余人推进，角色每 `overwatchWindowTicks` 轮换 |
| `flanking` | `true` | **③包抄**：队伍里固定一部分人（`flankFraction`，至少 1 个、绝不超过 size−1）只在"己方一侧"找掩体，于是全队从两个方向接近 |
| `coverClaims` | `true` | **④掩体独占**：选中的掩体方块被登记（`coverClaimTicks`），其他人不会再选同一格——不会两个单位叠在同一块墙后面 |
| `squadRadius` | `24.0` | 找队友的半径（格）。24 略小于狙击视野，约等于城市一个街区的宽度 |
| `squadCacheTicks` | `40` | 成员表复用多少 tick；这就是全部开销控制 |
| `overwatchWindowTicks` | `60` | 一个掩护角色持续多少 tick 后轮换（3 s：够过一次街，又不会太久） |
| `overwatchTimeoutTicks` | `120` | **防死锁上限**：压制角色持有超过这么多 tick 就退回"自己行动"。正常轮换先到；窗口比它长时这一条兜底 |
| `flankFraction` | `0.5` | 包抄比例；四舍五入后夹到 `[1, size-1]`（2 人以上时总有人守正面，也绝不全员绕同一侧） |
| `flankOffset` | `6.0` | 包抄侧向判定的离轴距离（格），让包抄者不跟火力组走同一条街 |
| `coverClaimTicks` | `100` | 掩体占用保留多少 tick。够覆盖一次掩体换弹（`manualReloadTicks` 45），也不至于让死掉的人长期占位 |


### `[tactics]` — cover, suppression, advancing, breaking contact

| Key | Default | Meaning |
| --- | --- | --- |
| `coverSearchRadius` | `14` | how far (blocks) a mob looks for cover around itself |
| `coverCacheTicks` | `20` | how long a cover search result is reused (the search itself is two ray casts per candidate and does **not** run every tick) |
| `coverSamples` | `28` | candidate positions tested per search; half on a near ring, half at the full radius |
| `suppressChance` | `0.6` | chance that a mob whose target just broke line of sight lays down suppressing fire instead of moving |
| `suppressTicks` | `60` | how long it keeps suppressing before re-evaluating |
| `suppressAccuracyMultiplier` | `0.45` | accuracy multiplier while blind-firing at a remembered position |
| `suppressBurstMultiplier` | `2.0` | burst length multiplier while suppressing (suppression is meant to be long and inaccurate) |
| `advanceCoverStep` | `3.0` | when advancing, a cover spot must be at least this many blocks closer to the target to be worth moving to |
| `coverSeekSpeedModifier` | `1.0` | speed multiplier for the three **cover moves only** (dive behind cover to reload, reposition, retreat dash), relative to the mob's normal walking pace. **User-tuned twice**: it shipped at `1.2/1.2/1.25` + an unconditional retreat sprint, was set to `1.5` on request ("1.5× normal"), and after seeing 1.5 in game the user asked for "their running-away speed changed to normal 1×" - so the default is now `1.0`. Every other `moveTo` speed site is listed in §5i |
| `retreatSprint` | `false` | whether a retreating mob also **sprints**. This is the hidden multiplier the user was actually seeing: sprinting adds its own speed *on top of* the navigation modifier, so the configured number was never the real speed. Off = "running away is a normal walk"; `true` restores the old frantic flee |
| `escapeWithoutCoverSpeedModifier` | `1.0` | speed for the retreat fallback where **no** cover was found and the mob just runs from the threat. It used to be a hard-coded `1.25` with sprinting on top; it is its own key now. The cover-seeking retreat (a spot was found) uses `coverSeekSpeedModifier` |
| `moveProgressSampleTicks` | `20` | the no-progress watchdog: how often a mob in a movement state (`ADVANCE`/`REPOSITION`/`RETREAT`) has its position compared with the previous sample (§5i) |
| `moveProgressMinBlocks` | `0.5` | how many blocks it has to cover per window to count as making progress; below this it re-paths and tries to hop the obstacle |
| `moveProgressRetries` | `3` | re-path attempts before it logs a WARN and changes state instead of standing there |
| `underFireTicks` | `100` | after being hit, the mob counts as "under fire" for this long and prefers cover further from the threat |
| `hurtRetreatChance` | `0.5` | chance that taking a hit immediately triggers a retreat, on top of the health threshold |
| `accuracyNear` / `accuracyMid` / `accuracyFar` | `1.0` / `0.85` / `0.7` | accuracy multipliers by distance band (near = inside 35% of the tier's range, far = beyond 70%) |

### `[tiers.<pistol\|shotgun\|rifle\|sniper>]`

| Key | Meaning |
| --- | --- |
| `spawnWeight` | relative chance of this tier (0 disables it) |
| `health` | max health |
| `armor` | armour points |
| `accuracy` | `0..1`; how much of the aim error is removed (1.0 never misses) |
| `aimTicks` | extra aiming ticks before the first shot of a burst, on top of TaCZ's own aim time |
| `burstShots` | shots per burst (`-1` = fire until the magazine is empty) |
| `burstCooldownTicks` | pause between bursts |

### `[voice]` — the voice lines (README §5l)

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | master switch for every voice line |
| `idle` | `true` | mutter to itself while it has no target |
| `idleIntervalTicks` | `400` | ticks between idle lines (20 s, as requested). **Replaces vanilla's ~6 s ambient cadence**: the idle timer is ours, `getAmbientSoundInterval` is not used for voice |
| `idleJitterTicks` | `80` | random extra ticks per line, so a group does not speak in unison |
| `contact` | `true` | say one line when it first engages a target |
| `contactCooldownTicks` | `200` | minimum ticks between two contact lines (anti-spam); also reused for the "lost them" line |
| `chatter` | `true` | keep talking during the firefight (`FIRE`/`SUPPRESS`) |
| `chatterMinIntervalTicks` | `300` | lower bound of the random chatter interval (15 s) |
| `chatterMaxIntervalTicks` | `600` | upper bound (30 s) |
| `grenade` | `true` | shout when something explosive lands nearby |
| `grenadeRadius` | `12.0` | how close that has to be, in blocks |
| `grenadeCooldownTicks` | `200` | minimum ticks between two **reaction** shouts ("something explosive landed near me") |
| `grenadeShoutCooldownTicks` | `40` | minimum ticks between the **thrower's own** shouts. Separate from the reaction above: one 200-tick timer for both meant a nearby blast ate the shout for the grenade the mob had just thrown itself (2026 report "连续扔雷时听不到喊话") |
| `mark` | `true` | say the "where did you run off to" line when the target is lost |
| `death` | `true` | replace the vanilla death sound with one of the four death clips |
| `volume` | `1.0` | volume of every line |
| `familyVolume` | `["shared=1.0","usec=1.0","bear=1.0","elite=1.0"]` | **per-family** volume multiplier on top of `volume` (`family=value` entries; valid families: `shared`, `usec`, `bear`, `elite`). 逃生开关：音频本身已按实测响度对齐（见 §5l 的 `voice_levels.json`），所以 `1.0` 就是校准值；某个家族仍偏轻/偏响时改这里，不用重剪音频。未知家族名/坏数字只报一次并忽略 |
| `effectVolume` | `1.0` | 非语音音效（手雷落地/弹跳，§5v）的音量倍数。单独一个键是因为它是**瞬态音**：均值天然低，唯一有意义的旋钮是电平，而且不该跟着语音一起动 |
| `pitchMin` | `0.9` | lower end of the voice pitch band |
| `pitchMax` | `1.1` | upper end of the band (if `pitchMin > pitchMax` both fall back to `0.9`/`1.1`) |
| `pitchJitter` | `0.03` | per-line wobble (±3 %) added on top of the mob's own pitch, then clamped back into the band |

### `[client]` — presentation and the Bedrock rig

| Key | Default | Meaning |
| --- | --- | --- |
| `useGeckoModel` | `false` | draw the gunner pillager with the GeckoLib Bedrock rig instead of the vanilla illager model |
| `renderScale` | `0.7` | **RIG 模型**的渲染倍率：**scav**，以及 `useGeckoModel=true` 时的**武装掠夺者**（Bedrock rig 的作者基线就是 0.7）。**不再影响村民系**。现场调：`/tarkovscav client scale <值\|up\|down> [step]` |
| `villagerRenderScale` | `1.0` | **村民系**（`gunner_villager` / `sniper_villager` / `usec_villager` / `elite_villager`，四者共用同一个渲染器）的渲染倍率，**按绝对值使用**：**`1.0` = 原版村民大小**（原版模型按 1.0 建模）。现场调：`/tarkovscav client scale villager <值\|up\|down> [step]`。2026 的「武装村民都大一号」就是这两把键原先共用一个数造成的（村民曾被按 `renderScale/0.7` 放大） |
| `hiddenBones` | see below | bones hidden once per baked model, and re-hidden after a resource reload |
| `logHiddenBones` | `true` | log once per baked model which bones were hidden (with a reason per bone) and which entries matched nothing. Also gates the head-pitch INFO line |
| `gunAnchorBone` | `RightHandLocator` | the **right-hand** anchor: the bone the held TaCZ gun is mounted on; falls back to `RightHandLocator` → `Gun3` → `RightHand`. The explicit value is always the first candidate, so a fallback can never steal it. **Not `Gun3`** unless you want the palm without the authored pose |
| `gunAnchorMode` | `normalisedHand` | `normalisedHand` \| `locatorAnimated`. How the anchor frame becomes a held-item frame - **this is the "treat the locator as a real hand" switch**, and the fix for "the gun lies across the chest like a slab" (§5f). `locatorAnimated` is the previous behaviour, kept for A/B |
| `gunOffhandAnchorBone` | `LeftHandLocator` | the **left-hand** anchor, used for the offhand item (and for the optional two-handed support copy). Falls back `LeftHandLocator` → `LeftHand`, with a WARN if the rig has neither |
| `renderOffhandItem` | `true` | draw the mob's offhand item there, the way vanilla's `ItemInHandLayer` does. Nothing is drawn when the offhand is empty |
| `gunTwoHandedSupport` | `false` | EXPERIMENTAL: also draw the **main-hand gun** on the offhand anchor so a two-handed grip can be lined up. Off by default because it draws the gun twice; the rig's own clips already pose the left hand across the weapon |
| `gunMountOffhandRotation` | `["0","0","0"]` | extra rotation for the offhand anchor, in degrees |
| `gunMountOffhandOffset` | `["0","0","0"]` | extra offset for the offhand anchor, in blocks |
| `gunMountOffhandScale` | `1.0` | extra scale for the offhand anchor |
| `gunMountDisplayContext` | `THIRD_PERSON_RIGHT_HAND` | the `ItemDisplayContext` the gun item is rendered with. **This is the real fix for "the gun looks badly skewed"** - TaCZ's own gun renderer branches on it (see §5b). Do not set it to `FIXED` or `THIRD_PERSON_LEFT_HAND`: those produce an item-frame layout or nothing at all |
| `gunMountRifleRotation` | `["0","0","0"]` | extra rifle-class rotation in degrees, `[x, y, z]` applied in that order. Neutral: TaCZ already places the gun for a hand |
| `gunMountRifleOffset` | `["0","0","-0.7"]` | **the user's in-game measurement**, in blocks: 0.7 towards the muzzle along the hand frame's -Z. **This settles the axis question: in `normalisedHand` -Z is forward.** A translation cannot rotate anything, so the accepted orientation is untouched; in model space it moves the anchor 0.54 blocks forward and 0.44 to the character's left (§5f) |
| `gunMountRifleScale` | `1.0` | extra rifle-class scale (multiplied on top of TaCZ's own `thirdperson` 0.6) |
| `gunMountPistolRotation` | `["0","0","0"]` | extra pistol-class rotation in degrees |
| `gunMountPistolOffset` | `["0","0","-0.7"]` | the same measured forward slide: a pistol is drawn in the same `normalisedHand` frame on the same anchor, so the frame axes and their meaning do not change. It **replaces** YSM's old `y=-0.125` (the triple is the whole offset) - write `[0,-0.125,-0.7]` if a pistol looks too high |
| `gunMountPistolScale` | `1.0` | extra pistol-class scale |
| `gunnerVillagerAimArmPitch` | `0.0` | gunner villager pose (§5j): **offset** in degrees from the vanilla crossed arms while the weapon is raised. **All four arm pitches are the same kind of number since 2026-09-24: `0` = exactly the vanilla arms, negative = lift above that, positive = press down.** (The user's old absolute `-100` is now the offset `-57`.) |
| `gunnerVillagerHoldArmPitch` | `0.0` | 闲置（`LOWERED`：拿着枪但没在用）那档手臂的**偏移量**，不是绝对角度：`0` = **完完全全的原版村民抱臂**。原版网格把抱臂烤在 `xRot = -0.75 rad ≈ -43°`（1.20.1 客户端 jar 字节码实测：`m_171052_` 里两个 `arms` 方块后面就是 `ldc -0.75f` 进 `PartPose.m_171423_`），模型在构造时把这个烘焙值抓下来，闲置档写的是"**烘焙值 + 本键**"。负 = 在原版基础上再往上抬（`-20` = 比原版高 20°），正 = 往下压进身体。**这就是 2026"闲置时他的手平放在身侧、和身体穿模"那个报告的修复**：以前这里写绝对角度，`0` 会把抱臂压平、`-20` 只是绕路；现在"原版位置"就是 `0` |
| `gunnerVillagerReloadArmPitch` | `0.0` | same, for `RELOAD` - an offset now too. **A user who sets `0` gets the vanilla arms while reloading**, which is exactly what the "his arms lie flat while reloading" report asked for |
| `gunnerVillagerHunkerArmPitch` | `0.0` | same, for `RETREAT`, as an offset. `0` = vanilla arms, `-20` = lifted 20° |
| `gunnerVillagerGunOffset` | `["0","0.06","-0.09"]` | where the held gun sits on the villager, in blocks, `[x,y,z]` in the arm frame, applied just before the vanilla `ItemInHandLayer` adds the standard hand frame. **发布基线 = 用户在自己实例里校准的值**：这个坐标系里 `-Z` 是前方（与 `gunMountRifleOffset` 同一约定），他要求过"再往后一点"，并把 z 定在 `-0.09`。现场调 `x/y/z` 或 `forward/back/left/right/up/down` |
| `gunnerVillagerGunRotation` | `["5","0","0"]` | 枪在那个手臂坐标系里的旋转（度，`[pitch,yaw,roll]`，X→Y→Z）。**`["5","0","0"]` 是用户在游戏里校准的值，2026-09-25 起就是出厂默认（发布基线）**；历史：先出厂 `-90`（枪口朝天，用户原话「现在这个朝天上看了」，要求回 50°）→ `-40` → 他在自己实例里定到 `5`。手臂角度与它是同一根栈上的 X 旋转、**相加**，所以枪的倾角是 `ARMS_REST + 手臂偏移 + 这个值 + 本档 delta - 90`（`ARMS_REST ≈ -42.97`，见 §5j 的表） |
| `gunnerVillagerGunScale` | `1.0` | extra uniform scale for the villager's gun, on top of TaCZ's own `0.6` for the third-person-hand context: `1.0` is TaCZ's normal size, `>1.0` is bigger. Final visible size ≈ `renderScale × this × 0.6` |
| `hideGunWhenIdle` | `false` | 闲置（`LOWERED`）时是否**隐藏手里的枪**。默认 `false` = 照常画在手上。`true` 只是口味开关：一瞄准/换弹/撤退枪立刻回来（每帧按状态判断） |
| `gunnerVillagerIdleGunRotation` | `["-2","0","0"]` | **只在闲置时**叠加到 `gunnerVillagerGunRotation` 上的枪旋转（度，`[pitch,yaw,roll]`，X→Y→Z）。枪锚在 `arms` 上，所以手臂角度一变枪就跟着变：闲置手臂是**原版抱臂的 -43°**，于是闲置 tilt = `-43 + 5 + (-2) - 90 = -130`，正是用户在游戏里校准、并认可为发布基线的那条枪口方向。**只影响 `LOWERED`**；瞄准/换弹/撤退偏高时改的是 `gunnerVillagerGunRotation` |
| `gunnerVillagerReloadGunRotation` | `["0","0","0"]` | **只在换弹时**叠加到 `gunnerVillagerGunRotation` 上的枪旋转（三轴，同构）。默认全 0 = **与加这个键之前逐帧完全一致**（换弹档过去与瞄准档共用基准）。现场调 `reloadPitch/reloadYaw/reloadRoll` |
| `gunnerVillagerHunkerGunRotation` | `["0","0","0"]` | **只在撤退（`HUNKERED`）时**叠加的枪旋转（三轴）。默认全 0 = 撤退档观感不变。现场调 `hunkerPitch/hunkerYaw/hunkerRoll` |
| `gunnerVillagerIdleGunOffset` | `["0","0","0"]` | **只在闲置时**叠加到 `gunnerVillagerGunOffset` 上的枪**位置**偏移（blocks，`[x,y,z]`，同一手臂坐标系：`-Z` 前、`+Y` 上、`+X` 右）。默认全 0 = 与加这个键之前逐帧一致。存在理由：基准位置是**四档共用**的，所以"把闲置那把枪从身体里挪出来"以前做不到（一动就动到他已校准的瞄准档）。现场调 `idleX/idleY/idleZ` |
| `gunnerVillagerReloadGunOffset` | `["0","0","0"]` | **只在换弹时**叠加的位置偏移（三轴）。现场调 `reloadX/reloadY/reloadZ` |
| `gunnerVillagerHunkerGunOffset` | `["0","0","0"]` | **只在撤退时**叠加的位置偏移（三轴）。现场调 `hunkerX/hunkerY/hunkerZ` |
| `gunnerVillagerGunAnchor` | `arms` | `arms` \| `body`: whether the gun hangs off the animated crossed-arms block - so it rises and falls with the aiming/reload/retreat pose - or off the torso, which is steadier but ignores the arm animation. Any other value warns and falls back to `arms` |
| `logGlState` | `false` | GL-state debug for the "part of the mob disappeared and I see what is behind it" report (§5k): logs the stencil / depth / blend / cull / texture state around every entity render and every held-item draw, and WARNs when the stencil func changed underneath us. Off by default (it logs per frame) |
| `logGunMount` | `true` | log which anchor bone was chosen (and whether a fallback was used) once per baked model, plus the applied transform, mode and display context once per mob |
| `modelLayering` | `upperLower` | `upperLower` \| `single`. **Both are ONE geometry submission per frame** - this mod never draws the rig twice. It only decides how many animation *controllers* drive the one model: `upperLower` (default) = the layered pair - legs from `tac:idle`/`tac:walk`/`tac:run`, upper body from `tac:hold/aim/aim:fire/reload:*` - so an armed mob walks and shoots at once; `single` = one controller, one clip, and because the rig's gun clips contain **0 leg tracks** an armed mob's legs hold their rest pose while walking. Switch live with `/tarkovscav client reload` |
| `modelRenderType` | `cutout` | `cutout` \| `zOffset` \| `translucent` \| `solid` - A/B for the "at one angle I see the world through the mob" family. `cutout` = `entityCutoutNoCull` (the look so far); `zOffset` = `entityCutoutNoCullZOffset`, same look **plus a depth bias so the rig wins depth ties against world geometry that intersects it** (grass, flowers, item frames, dropped items); `translucent`/`solid` are diagnostics with known new artifacts (no depth write / alpha-ignored black patches). Each one is a stock vanilla type - no custom render state |
| `cullingBoxPadding` | `1.0` | extra blocks on `Entity#getBoundingBoxForCulling`, the box vanilla frustum-culls with. A raider hitbox is 0.6 x 1.95 while the rig renders ~2.8 x 3.85 blocks (4 x 5.5 units at `renderScale` 0.7) and the held gun reaches further, so a part-visible mob at the screen edge could be dropped. `0` = vanilla behaviour |
| `logRenderStats` | `false` | log every 5 s how many geometry passes were submitted for how many mobs, and WARN if any mob got more than one. The runtime proof of "one submission per mob per frame" |
| `poseSource` | `auto` | `auto` \| `code` \| `clips` - **who writes the aim pose bones.** `auto`: per bone, a clip that drives that bone from the entity's look (its keyframes are Molang rather than numbers) owns it and the code writes nothing there; every other bone keeps the code's absolute fallback write, with the code's total yaw/pitch gain always 1.0. `code`: the code owns `UpperBody` and `Head` as it did before the author's Molang was revived. `clips`: the code writes no pose bone at all. Switch live with `/tarkovscav client pose auto` (§5n) |
| `molangVariables` | `pitch` | `pitch` \| `all` \| `off` - which of the rig's own aim variables are fed from the live entity: `ysm.head_yaw` ← `EntityModelData#netHeadYaw`, `ysm.head_pitch` ← `#headPitch`, `query.head_y_rotation`, `query.head_x_rotation`, `query.is_sneaking`. `pitch` feeds the two pitch quantities only - the author's chest-lean and arm keyframes come alive. `all` feeds the yaw too; **it is not the default because the author's yaw keyframes sit on `UpBody`/`AllBody` and `AllBody` is the legs' parent as well, so feeding them applies the body yaw a second time** (`tools/selftest_pose_writers.js`: 31.7° peak-to-peak lower-body yaw with `all`, 0.0° with `pitch`). `off` pins every symbol to 0 - the behaviour before this key existed. Switch live with `/tarkovscav client pose molang` (§5n) |
| `torsoYawShare` | `0.25` | `0`…`1`: how much of the look's yaw the code puts on the **chest** (`UpperBody`); the head takes the rest, so the total gain is always 1.0 and the aim always lands in the same place. **This number is the size of the reported twist**: at the old value `0.7` the chest pivoted ±17° (45.3° peak-to-peak over a ±25° look swing) while the legs did not move at all. `0.25` gives 16.2°, `0` removes it. Switch live with `/tarkovscav client pose torso 0.2` (§5n) |
| `logPoseWriters` | `false` | log one `[pose]` line per rendered frame: the `netHeadYaw`/`headPitch` the pose was driven from, the writer of every pose bone (`clips(molang)`, `clips(fixed)` or `code`) and the resulting yaw of `Root`/`AllBody`/`UpBody`/`UpperBody`/`Body`/`Head` plus the head-chain total. **The evidence tool for the "the upper body twists while it walks" report** (§5n); it also WARNs if one bone is ever written by both a look-driven clip track and the code. Off by default (one line per frame) |

Tune the four `gunMount*` families live with `/tarkovscav client gunpose` instead of editing and
restarting - see §5b. It writes the config for you and applies the change on the next frame.

> **Forge keeps values that are already in the file.** The neutral defaults above only reach a config
> that does not have those keys yet; an existing `config/tarkovscav-common.toml` keeps whatever the
> previous jar wrote there (`gunMountRifleRotation = ["0","-180","0"]`, `scale = 0.65`, ...). Check the
> `[gunmount]` line in `latest.log`, then run `/tarkovscav client gunpose reset` or delete those lines
> by hand. **New** keys (like `gunAnchorMode`, `gunMountOffhand*`) do get their defaults.

### `[client.headAccessories]` — the hats, the eye gear and the cigarette

The imported rig wears **three hats at once, two pairs of eye gear and a cigarette**, and they are
stacked in the same few blocks of space, so they z-fight with each other and with the head mesh.
These keys are **incremental to `hiddenBones`**: both apply, and a bone is hidden when either asks
for it.

| Key | Default | Meaning |
| --- | --- | --- |
| `hatAccessory` | `keepOne` | `keepOne` \| `hideAll` \| `showAll`. `keepOne` hides every hat except `keptHatBone` |
| `hatBones` | `["Hat1","Hat2","hat3"]` | which bones count as hats (only `keepOne` and `hideAll` use it) |
| `keptHatBone` | `Hat2` | the hat that survives `keepOne`. See §5c for why `Hat2` |
| `eyeGearAccessory` | `Glass` | a bone name from `eyeGearBones`, or `none`. Default `Glass`: `YanJing`'s side frames occupy the same box as `Hat2`'s ear flaps |
| `eyeGearBones` | `["Glass","YanJing"]` | which bones count as eye gear. **Not `Eyes`** - that bone owns the eyebrows and the eyeballs, i.e. the face |
| `cigarette` | `false` | draw the cigarette in the mouth (`Yan`) |
| `cigaretteBone` | `Yan` | the bone that carries it |
| `extraHiddenBones` | `[]` | extra bones to hide, on top of everything above. This is the **bisect tool** (`/tarkovscav client hide <bone>`), not a fix - empty by default |
| `headRestPitchDegrees` | `20` | rest pitch added to the `Head` bone, in degrees. **Positive = the head looks up.** Applied only to the states in `headRestPitchStates`, never while aiming (§5d) |
| `headRestPitchStates` | `["idle"]` | gun-AI states the rest pitch applies to: `idle`, `alert`, `advance`, `aim`, `fire`, `suppress`, `reload`, `bolt`, `reposition`, `retreat`, or `any`. Keep it at `idle` |

A bone named by any of these keys that does **not** exist in the rig being baked produces a `WARN`
naming the bone - it is never ignored quietly. The gunner pillager's placeholder rig has none of
these bones, so `useGeckoModel = true` will warn about all of them; that is correct and expected (and
the accessory system cannot touch a rig it does not own).

`hiddenBones` defaults to `["Gun3", "Ban", "Lianru", "Bao", "Spwt", "Jiu", "Parrot", "money"]` — the
author's reference props in the right hand plus the placeholder rifle. Body and clothing bones are
protected in code (`Config.PROTECTED_BONES`) and cannot be hidden from the config; an entry that names
one is refused with a `WARN` that points at the `headAccessories` keys instead.

---

## 5. The two-layer animation scheme

The rig splits at the top into `UpBody` and `DownBody`, and the author's clip library follows that
split. Measured per clip (upper/lower bone counts, `tools/analyze_scav_model.js`):

| Clip | Bones in lower body | Bones in upper body | Used for |
| --- | --- | --- | --- |
| `idle`, `walk`, `run` | 7 / 5 / 5 | 9 / 9 / 9 | movement when **unarmed** (whole body) |
| `tac:idle`, `tac:walk`, `tac:run` | 5 / 5 / 5 | **0** | movement when **armed** - legs only |
| `tac:hold:rifle`, `tac:hold:pistol` | 0 | 9 / 9 | armed idle |
| `tac:aim:rifle`, `tac:aim:pistol` | 0 | 9 / 9 | aiming |
| `tac:aim:fire:rifle`, `tac:aim:fire:pistol` | 0 | 9 / 9 | firing |
| `tac:reload:rifle`, `tac:reload:pistol` | 0 | 9 / 9 | reloading |
| `tac:melee:rifle`, `tac:melee:pistol` | 0 | 11 / 11 | imported, not driven yet |
| `death` | 4 | 8 | death (triggered animation) |

So the mob runs **two GeckoLib controllers**: `movement` (legs, registered first) and `gun` (arms,
head, torso, registered second). GeckoLib applies controllers in registration order and the later one
wins per bone, so the legs keep walking while the upper body aims - instead of one whole-body clip
overriding everything, which is what makes a walking shooter look like it is sliding.

Clips that were **not** imported, and why: the whole `parcool` (parkour), `swem` (horse riding),
`arrow`, `carryon` and `arm` (first-person arms) files, the `extra` file's dances/emotes/voice clips,
and every `rpg`/`minigun` gun clip (those gun types are blacklisted). The full analysis is in
`tools/spike/work/scav-analysis.txt`; the import manifest is
`tools/spike/work/scav-import-manifest.json`.

### The gun anchor: `RightHandLocator` (not `Gun3`)

* Bone chain: `Root → MAllBody → AllBody → UpBody → UpperBody → Arm → RightArm → RightForeArm →
  RightHand → RightHandLocator`
* `RightHandLocator` is an empty marker (no cubes, pivot `[-5.080, 16.597, 0.133]`) and - the part that
  matters - **it is the bone the rig's `tac:hold:*`, `tac:aim:*` and `tac:reload:*` clips animate**,
  which is how the author expresses the gun pose.
* `Gun3` is a **sibling** of it (both are children of `RightHand`), not a child: it holds a 42-cube
  *modelled placeholder rifle* (pivot `[-4.573, 16.893, -0.149]`, rest rotation `[90, 0, 90]`, local +X
  toward the muzzle - barrel bone `QiangguanA` at +X, stock bones `qiangtuo2`/`bone6` at -X). A gun
  mounted on `Gun3` would sit in the palm but ignore every authored gun pose, so the default anchor is
  `RightHandLocator` and `Gun3` is in `hiddenBones` so its placeholder mesh never shows.
* YSM 2.6.5 - which this model was authored for - renders a held TaCZ gun on its own
  `ILocationModel#tacPistolBones()/tacRifleBones()` locator groups, after applying fixed transforms.
  This mod used to copy YSM's constants verbatim (rifle: `scale 0.65`, `Axis.Y -180°`, no translation;
  pistol: `translate(0, -0.125, 0)`, `scale 0.65`, `Axis.Y -90°`, `Axis.Z +90°`) and render with
  `ItemDisplayContext.FIXED`. **Those numbers cannot be transplanted, and they are gone from the
  defaults** - see §5b for the bytecode evidence and for the live tuner that replaces them.
* GeckoLib skips a hidden bone's **cubes** but still calls the render layers for that bone, which is why
  hiding `Gun3` does not stop anything from being drawn on `RightHandLocator`.
* **The anchor is resolved against the rig that is actually loaded, and never silently.** The chain is
  `client.gunAnchorBone` → `RightHandLocator` → `Gun3` → `RightHand`, and the first one the baked model
  really has wins (compared case-insensitively, mounted under the rig's own spelling). The choice is
  logged once per baked model, a fallback says so, and a rig with none of the four gets a **WARN**
  listing the bones it does have instead of quietly rendering the mob empty-handed - which is exactly
  what the gunner pillager's placeholder rig used to do. The resolution is keyed on the `BakedGeoModel`
  object, so a resource reload re-resolves it. `node tools/resolve_gun_anchor.js [bone]` mirrors the
  chain against both shipped rigs and greps the layer for the chain and the warning.

The rig also carries `RifleLocator` (back mount, child of `UpperBody`, pivot `[5, 26.1, 3]`) and
`PistolLocator` (right thigh, child of `RightLeg`, pivot `[-4.88, 18.16, 0]`) - YSM's own
"背部枪械 / 腿部枪械" anchors, kept for holstered weapons and not used yet.

### 5b. The gun angle: what was actually wrong, and the 30-second tuning loop

> **两个 scale 键（2026 修正）**：`client.renderScale` 只管 **RIG 模型**（scav、`useGeckoModel=true` 的武装掠夺者，
> 作者基线 0.7），`client.villagerRenderScale` 只管**村民系四兄弟**（原版村民模型，基线 1.0 = 原版大小）。
> 现场调分别用 `/tarkovscav client scale <值|up|down>`（保持旧语义 = 改 rig）与
> `/tarkovscav client scale villager <值|up|down>`；`/tarkovscav client state` 会同时打印两把键并标明谁管谁。
> **历史**：以前两者共用一个数，且村民是按 `renderScale / 0.7` 的**相对倍率**缩放，所以用户把 rig 放大到 0.77（1.1×）
> 之后，**每只武装村民也跟着大了 10%**（就是"武装村民都要大一号"那条反馈）。拆键后村民默认回到 `1.0`。
> 阴影半径（`0.5 × 该族的 scale`）与各自的剔除盒 padding 都跟着各自的键走。

The report was "the gun's rotation is badly off". Three things were measured, and only one of them is
the kind of thing a new set of magic numbers can fix.

**1. The mount space is not rotation-free, but it is nearly so.** The rest rotations down the anchor
chain (`tools/pose_chain.js`):

```
Root 0,0,0 → MAllBody 0,0,0 → AllBody 0,0,0 → UpBody 0,0,0 → UpperBody 0,0,0 → Arm 0,0,0
  → RightArm [0,0,8]  ← the only non-zero rest rotation in the whole chain
  → RightForeArm 0,0,0 → RightHand 0,0,0 → RightHandLocator 0,0,0  (pivot [-5.07955, 16.59652, 0.1325])
```

So the mount is composed inside a coordinate system carrying exactly **8° of Z roll** (the arm's
splay). Real, but far too small to explain "badly skewed" on its own - it is worth ~8°, not ~90°.

**2. The anchor is heavily animated by the clips, which is by design.** `RightHandLocator` is the bone
the author's gun clips rotate, so the gun's world orientation is a composition of four rotations:
`RightArm` (clip, e.g. `[-55.8, -36.9, 45.7]` in `tac:aim:rifle`), `RightForeArm` (`[-96.1, 0.7, -9.3]`),
`RightHand` (identity) and `RightHandLocator` (`[21.3, 17.7, 21.1]` in aim, `[-16.0, 24.6, 21.0]` in
hold, `[-17.3, -8.5, 23.2]` mid-reload; `[9.5, 4.2, 3.7]` in `tac:aim:pistol`). GeckoLib applies a
bone's rotation in the order Z→Y→X (`RenderUtils.rotateMatrixAroundBone`), while the config triple is
applied X→Y→Z - irrelevant for the old single-axis `[0,-180,0]`, and *not* irrelevant as soon as the
tuner puts non-zero values on more than one axis. That is why the tuner prints the order.

**3. The main cause: `ItemDisplayContext.FIXED` is the item-frame context, not a hand context.** The
held item is TaCZ's `modern_kinetic_gun` (visible in the log line: `stack=modern_kinetic_gun`), and
TaCZ renders gun items through its own `BlockEntityWithoutLevelRenderer`
(`com.tacz.guns.client.renderer.item.GunItemRendererWrapper#renderByItem`). `javap -p -c` on the 1.1.8
jar shows it branching on the context:

| Context | What TaCZ does |
| --- | --- |
| `FIRST_PERSON_LEFT_HAND`, `FIRST_PERSON_RIGHT_HAND` | `return` - **draws nothing** |
| `THIRD_PERSON_LEFT_HAND` | `return` - **draws nothing** |
| `GUI` | the flat slot icon only |
| `FIXED` | `translate(0.5, 2, 0.5)`, `scale(-1, -1, 1)` (**a mirror flip**), the model's `fixed` positioning group, and `TransformScale#getFixed()` |
| `THIRD_PERSON_RIGHT_HAND` | the model's third-person-hand positioning group and `TransformScale#getThirdPerson()` |

and TaCZ's own gun display JSONs say which scale that is (and, in their comments, that rotation and
translation come from the **positioning groups inside the model**, not from the display file):

```json
"transform": { "scale": { "thirdperson": [0.6,0.6,0.6], "ground": [0.6,0.6,0.6], "fixed": [1.2,1.2,1.2] } }
```

`entityCutoutNoCull`-style rendering aside, that means the old defaults were compensating an
**item-frame layout with a mirror flip and twice the scale** (`1.2 × 0.65 = 0.78` instead of
`0.6`). YSM's own `0.65` is recognisably TaCZ's `thirdperson` `0.6`, i.e. YSM is a third-person-hand
renderer - which is exactly what vanilla's `ItemInHandLayer` asks for, and it is now the default here
too, so the Bedrock path and the vanilla illager path finally request the same thing.

**The mount path's own fallback applies the same table.** `GunInHandGeoLayer.displayContext` mirrors any
context that TaCZ draws nothing for to `THIRD_PERSON_RIGHT_HAND`, because the previous fallback for the
`FIRST_PERSON_*` contexts was `FIRST_PERSON_RIGHT_HAND` - itself a `return` inside TaCZ, so the gun would
have stayed invisible, which is the opposite of what that fallback exists for. The left-hand contexts
already mirrored to `THIRD_PERSON_RIGHT_HAND`; the two are now one hardcoded target (the older
`EdDYON/tarkovscav` snapshot's form). Only a non-default `client.gunMountDisplayContext` reaches the
branch, and `tools/mount_matrix.js` asserts the `mirrored to {}` WARN that goes with it.

With the context fixed, the old compensation numbers are meaningless, so the defaults are **neutral**
(`rot [0,0,0]`, `offset [0,0,0]`, `scale 1.0`) and the remaining few degrees are for the user to dial
in. That is what this loop is for:

```
/tarkovscav client gunpose                             # print the current transform, both families
/tarkovscav client gunpose pitch=0 yaw=0 roll=0        # reset to neutral
/tarkovscav client gunpose yaw=-90                     # rotate 90° about Y
/tarkovscav client gunpose family=pistol roll=90
/tarkovscav client gunpose scale=0.9 z=-0.05           # nudge the offset
/tarkovscav client gunpose context=THIRD_PERSON_RIGHT_HAND
```

Every one of those applies to **every mob using the rig on the next frame** (the render layer reads
the config per frame), prints the exact toml line, and saves it, so it survives a restart. Sample
output:

```
Gun mount (rifle) updated: pitch=0 yaw=-90 roll=0 offset=(0, 0, 0) scale=0.9 context=THIRD_PERSON_RIGHT_HAND (applies next frame)
Written to config/tarkovscav-common.toml:
  [client]
  gunMountRifleRotation = ["0", "-90", "0"]
  gunMountRifleOffset = ["0", "0", "-0.7"]     <- the shipped baseline: 0.7 blocks towards the muzzle
  gunMountRifleScale = 0.9
  gunMountDisplayContext = "THIRD_PERSON_RIGHT_HAND"
```

### The axis names, and what `reset` means

The user measured the axes for us: in the default `normalisedHand` mode they ran
`/tarkovscav client gunpose z=-0.7` and the rifle moved **forward**, so in this hand frame **-Z is the
muzzle direction**. The semantic arguments map onto that measurement, and the mapping is the same for
both weapon families because the frame and the anchor kind are the same:

| argument | axis effect | why |
| --- | --- | --- |
| `forward=n` / `back=n` | `z -= n` / `z += n` | user-measured: a negative z moves the gun forward |
| `right=n` / `left=n` | `x += n` / `x -= n` | the frame basis maps +x to model -X, the character's right |
| `up=n` / `down=n` | `y -= n` / `y += n` | the frame basis maps +y to model -Y, i.e. down |

`x=`/`y=`/`z=` **set** the offset, the named ones **add** to it, so `forward=0.05` can be tapped
repeatedly while watching a mob. In `locatorAnimated` the anchor frame itself is rotated, so the same
names point elsewhere; the table holds for `normalisedHand` only.

`/tarkovscav client gunpose reset` restores the **shipped baseline**, which is not zero: rifle and pistol
both go back to `rot [0,0,0] offset [0,0,-0.7] scale 1.0` (the measured forward slide), the offhand
anchor to neutral, plus `gunMountDisplayContext=THIRD_PERSON_RIGHT_HAND` and
`gunAnchorMode=normalisedHand`. The spec and `reset` read the same constants in `Config`, so a default
and its reset cannot drift apart.

**Forge only writes a default for a key that is missing.** A toml that already carries values written by
`gunpose` keeps them, so a new mod default does not show up on its own - run `gunpose reset` (or delete
those lines) to see the shipped baseline.

and `[gunmount] tarkovscav:scav: bone 'RightHandLocator' (parent RightHand) pistolFamily=false
rot=[0,-90,0] offset=[0,0,0] scale=0.9 context=THIRD_PERSON_RIGHT_HAND stack=modern_kinetic_gun` in
`latest.log` for verification.

### 5f. The hand anchor: why the gun lay across the chest, and what `normalisedHand` does

The report: at close range the gun is "a flat dark slab across the chest", the arms look stubby, the
head is hard to make out. Three candidates, all measurable with `node tools/mount_matrix.js` (it
reproduces GeckoLib's own call order and prints the model-space matrix the item is drawn with):

| Candidate | Verdict | Numbers for `tac:hold:rifle` (idle with a gun) |
| --- | --- | --- |
| the arm pose is wrong | **no** - the arm angles are the author's clip values and no code of ours touches them | `RightArm` rest `[0,0,8]` + clip `[-16,-40,33]` → `[-16,-40,41]`; `RightForeArm` `[-91,-19,-9]`; `RightHand` untouched. A forearm folded 91° with the upper arm swung 40° *is* "the arm looks short" from a high camera |
| the mount squashes a scale axis | **no** - no bone in the chain has a scale, and both modes produce a unit-length barrel axis | `|barrel| = 1.0000` in all three clips |
| the gun is not in the hand | **no** - the anchor point is in the palm | item origin is **0.035 blocks (0.6 model units)** from the centre of the `RightHand` cube |
| **the anchor frame is wrong** | **yes, twice over** | see below |

**(1) GeckoLib applies the anchor bone's own rotation twice.** `renderRecursively` leaves the bone's
transform on the pose stack, and then `BlockAndItemGeoLayer.renderForBone` calls
`RenderUtils.translateAndRotateMatrixForBone` - pivot and rotation *again* - so the item's frame carries
`R·R` for that bone. Harmless for a locator no clip animates; `RightHandLocator` is animated by every
`tac:*` clip. Measured: the barrel direction with `R` applied twice vs once differs by **31.9°**
(`tac:hold:rifle`), **27.3°** (`tac:aim:rifle`) and **27.3°** (`tac:aim:fire:rifle`). The current
in-game values make it worse (`rot=[0,-180,0] scale=0.65` are still in the user's toml from an older
jar - Forge keeps them), so the gun ended up at `0.65 × 0.6 = 0.39` scale and rotated 180° on top of an
already-over-rotated frame.

**(2) The item was drawn in the rig's locator frame, not in a hand frame.** TaCZ's gun models carry
their own positioning groups ("定位组") authored for the frame vanilla's `ItemInHandLayer` produces:
`mulPose(X, -90)`, `mulPose(Y, 180)`, `translate(arm == LEFT ? -1/16 : +1/16, 0.125, -0.625)`. Feeding
TaCZ's renderer the raw bone frame is why its own placement maths came out wrong.

`gunAnchorMode = normalisedHand` (the new default) does exactly the two corrections, and nothing else:

```
cancel duplicate R:  mulPose(X, -rotX) → mulPose(Y, -rotY) → mulPose(Z, -rotZ)     // R^-1 on the right
vanilla hand frame:  mulPose(X, -90)   → mulPose(Y, 180)   → translate(±1/16, 0.125, -0.625)
```

Measured effect on the item frame's own axes (model space; `-X` is the character's right). These are the
frame's basis vectors, **not** the gun's barrel - TaCZ's positioning groups rotate the gun mesh inside
the frame, which is exactly why the forward axis had to be measured in game rather than predicted here:

| clip | `locatorAnimated` (old frame) | `normalisedHand` (default frame) |
| --- | --- | --- |
| `tac:hold:rifle` | x `( 0.818, -0.482,  0.315)` | x `(-0.769,  0.041, -0.638)` |
| `tac:aim:rifle` | x `( 0.888, -0.247,  0.389)` | x `(-0.741, -0.108, -0.663)` |

The user then tuned `z=-0.7` in game and the rifle moved forward, which is the authoritative statement
that in `normalisedHand` the frame's **-Z is the muzzle direction**. A translation cannot rotate
anything, so the orientation they accepted stays put; in model space the shipped `-0.7` moves the anchor
0.54 blocks forward and 0.44 to the character's left (printed by `node tools/mount_matrix.js`). The extra
`gunMount*` rotation/offset/scale apply inside that frame, so `/tarkovscav client gunpose` keeps working
- and it now takes `mode=`, `hand=` and the named directions:

```
/tarkovscav client gunpose mode=locatorAnimated      # A/B the old frame
/tarkovscav client gunpose mode=normalisedHand       # back to the default
/tarkovscav client gunpose hand=offhand roll=90      # the offhand (left-hand) anchor
```

**The left hand is a real anchor now.** `LeftHandLocator` exists in the rig (child of `LeftHand`, no
cubes, pivot `[5.07955, 16.59652, 0.1325]` - the exact mirror of the right one, so it is inside the left
palm), and **no clip animates it**, so it is a clean hand anchor rather than an authored gun pose. It is
used for the mob's offhand item (`renderOffhandItem`) and, if `gunTwoHandedSupport` is switched on, for a
second copy of the main-hand gun so a two-handed grip can be lined up. When the item on a left anchor is
a TaCZ gun, the left-hand contexts are mirrored to the right-hand one with a WARN, because TaCZ's
renderer draws *nothing* for `THIRD_PERSON_LEFT_HAND` / `FIRST_PERSON_*_HAND` - an invisible weapon is
not an acceptable failure mode here.

### 5h. Parallel animation, and why `upperLower` is the default

The rig's clip library is written for a layered rig, and the measured track ownership is what makes it
work without drawing anything twice:

| controller | clip when armed | clip when unarmed | bones it animates |
| --- | --- | --- | --- |
| movement (registered first) | `tac:idle` / `tac:walk` / `tac:run` | `idle` / `walk` / `run` | **legs only** (`DownBody`, `LeftLeg`, `LeftLowerLeg`, `leftfoot`, `RightLeg`, `RightLowerLeg`, `rightfoot`) - 0 upper-body tracks, except `tac:idle` which also has `Head` |
| gun (registered second) | `tac:hold/aim/aim:fire/reload:{rifle,pistol}` | - (stopped) | **upper body only** (`UpBody`, `Arm`, both arms/forearms, `Head`, `AllHead`, `Body`/`AllBody` where authored) - **0 leg tracks** |

GeckoLib applies the controllers in registration order and the later one wins per bone, so a walking,
shooting mob is one animation result from one geometry pass - and the same is true of `single` mode.
`node tools/selftest_single_pass.js` asserts the disjoint track sets and the single-submission
invariants; `client.logRenderStats = true` counts the passes at runtime (one per mob per frame).

The state -> clip table (`node tools/selftest_clip_mapping.js` proves the server table
`GunClips#forState` and the client-side `GunClips#actionFor` agree for all ten states):

| gun AI state | aiming / firing / reloading | action | clip (per family) |
| --- | --- | --- | --- |
| `IDLE` | false / false / false | `hold` | `tac:hold:{rifle,pistol}` |
| `ALERT`, `AIM`, `ADVANCE`, `BOLT`, `REPOSITION` | true / false / false | `aim` | `tac:aim:{...}` |
| `FIRE`, `SUPPRESS` | true / true / false | `aim:fire` | `tac:aim:fire:{...}` |
| `RELOAD` | true / false / true | `reload` | `tac:reload:{...}` |
| `RETREAT` | true / false / false | `hold` | `tac:hold:{...}` (the brain lowers the weapon here, so the client resolves it via the synced state) |

**Which weapon gets which family**: the family comes from the gun's TaCZ **type**, not from the tier name
- `client.guns.pistolClipTypes` defaults to `["pistol"]`, so a pistol uses the `:pistol` clips while an
SMG (also in the PISTOL *tier*) uses the `:rifle` clips, exactly like YSM. The server syncs the answer
with the rest of the gun pose, so the client never guesses. A clip the rig does not have is reported by
`ScavGeoModel#warnAboutMissingClips` at bake time instead of leaving a silent bind pose.
### 5a. 开门与关门：所有武装单位穿过木门后把它关上（`[ai]`）

用户原话：「需要**所有单位**会**开门关门**」。开门那半边以前只做了一半，关门那半边**一行都没有**。

#### 先纠正一个前提：`allowDoors()` 只让**寻路**穿过门，并没有让怪**开**门

`GunUser#allowDoors`（三个根类的构造函数都调用，另外六个单位继承）做的是
`GroundPathNavigation#setCanOpenDoors(true)` + `setCanPassDoors(true)`。前者只改**寻路器**：它允许路径**穿过**关着的木门，代价是怪走到门口时门还是关的。真正把门推开的动作在**另一个 Goal** 里，而我们对它的依赖是零（下面这张表是 javap 查 1.20.1 mapped jar 得到的，`tools/selftest_doors.js` 把它变成了断言）：

| 原版类 | 有没有门 Goal | 对九个单位意味着什么 |
| --- | --- | --- |
| `Vindicator` | 有：`AbstractIllager$RaiderOpenDoorGoal`，优先级 2 | 是**唯一**注册门 Goal 的原版怪，而且它 `closeDoor=false`（字节码 `iconst_0`），只开不关 |
| `Pillager` / `Raider` | **没有**（两个类的常量池里都没有 `OpenDoorGoal`） | `GunnerPillagerEntity` + 掠夺者系 3 个子类：门**根本不会开** |
| `Monster`（`ScavEntity`） | 没有 | 同上 |
| `Villager` 系 | 有，但走**大脑行为** `InteractWithDoor` | 武装村民有；可它一有目标就把村民大脑停掉（`customServerAiStep` 只在 `getTarget() == null` 时跑 `super`）——**交战中正好是它要穿楼的时候**，于是门也不会开 |

**结论：交战中九个单位里能自己开门的是 0 个，能关门的是 0 个**（村民系闲逛时那半条路由原版大脑提供，但它覆盖不了"一边打一边穿楼"这个我们要修的场景，也不覆盖另外六个单位）。所以这一节把**开和关放在同一个类里**一起做，而不是只补"关"。

#### 实现：一个类、两条驱动、三步

`gun/DoorBehavior.java`，一个单位一个实例，挂在 `GunBrain` 上（和 `CombatTactics` 并列）。放 `gun/` 而不是 `entity/`：它由 `GunBrain` 驱动、又要用 `GunUser` 做每 tick 挂勾，放 `entity/` 会绕出一个 gun ↔ entity 的包循环。

1. **开**——条件就是原版 `DoorInteractGoal#canUse` 的原文：地面寻路允许开门 + `horizontalCollision`（正顶在东西上）+ `Path` 没走完 + 路径**下两个节点内**有一个**关着的木门**且距离 ≤ 1.5 格（`distanceToSqr <= 2.25`；`doorPos` 取的是 `(node.x, node.y+1, node.z)`，也就是门的**上**半）。所以只开"它本来就要穿过去的那扇门"。`DoorBlock#isWoodenDoor` 就是 `type().canOpenByHand()`：**铁门**（`BlockSetType.IRON`）、石头门、金门一概不碰，源码里连一个 `BlockSetType.IRON` 字样都没有（闸门断言）。
2. **记**——只记**我们自己开的**门（下半个方块坐标 + 开门时的 `gameTime`），最多 8 扇。玩家早就敞着的门不在名单里，**永远不动**；这一条同时也是"开-关-开-关"死循环不可能出现的原因（加上下面的防抖，共两道）。
3. **关**——写 `DoorBlock.OPEN = false`（`level.setBlock(pos, state.setValue(OPEN, false), 10)`，与原版 `DoorBlock#setOpen` 逐字相同）、播 `BlockSetType#doorClose()` 的**门声**（`SoundSource.BLOCKS`、音量 1.0、音高 `rand*0.1+0.9`，同原版）、再发一个 `GameEvent.BLOCK_CLOSE`（潜声传感器也听得见），然后把这扇门从名单里划掉。**一扇门对一个单位来说最多开一次、关一次。**

**两条驱动**（这一步值得单独写，因为核查推翻了原计划）：`GunBrain#tick` 里加了 `this.doors.tick(level)`，但 `GunBrain#tick` **只有 `GunAttackGoal#tick` 一个调用者**，而那个 Goal 只在"有活目标 + 有枪"时才跑——**它不是每 tick 都跑**，没有目标的单位（比如一个闲着溜达的武装村民）永远到不了那里。所以真正兜底的是 `DoorBehavior` 自己注册的 `LivingEvent.LivingTickEvent`（`TarkovScav` 里 `MinecraftForge.EVENT_BUS.register(DoorBehavior.class)`）：它对**每一个** `GunUser`、每一 tick 调一次，包括当前没有目标的单位和以后新加的单位，不需要改任何实体类。Forge 是在 `LivingEntity#tick` 的**第一条指令**上发这个事件的（`ForgeHooks.onLivingTick`，`javap` 查过字节码），也就是说门在**这一 tick 的移动阶段之前**就被推开了；两条路进的是同一个方法，里面用 `this.lastTick == now`（用的是 `gameTime`，不是 `tickCount`——事件发生在 `Entity#baseTick` 自增 `tickCount` 之前，用 `tickCount` 会去重失败）保证一个 tick 只做一次。

#### 两条安全规则（永远优先于时间）

1. **门里站着人就不关**——**自己的**碰撞箱与门的**上/下任意一半**方块相交（`doorwayOccupied`）→ 不关。**关门者自己被硬判在这一条里**：时间那半可能在它身体还压着门框的时候到点，所以这条不能靠半径兜。别人站在门里则由下面第 2 条拦住（站在门洞里必然在 2.0 半径内）。
2. **半径内有人就不关**——除关门者本人外，任何活体距门的任一"半"在 `doorCloseAllyRadius`（默认 2.0）以内 → 不关（`otherLivingWithin`，用的就是原版 `InteractWithDoor` 的 `doorPos.closerToCenterThan(entity.position(), 2.0)`）。

**时间规则**（纯函数 `DoorBehavior#shouldClose`，闸门直接拿它跑仿真）：`elapsedTicks >= doorCloseDelayTicks`（默认 40）**或** 单位已离门 `> doorCloseAllyRadius`（默认 2.0），**谁先到算谁**——40 tick 是给"开了门却停在门口的单位"兜底的，不是最短持有时间。另有一条**防抖**：单位还在顶这扇门（`horizontalCollision` 且距离 ≤ 半径）时只**重置计时器**、不关；否则"门一关它还在撞 → 下一 tick 又开"就是循环。

#### 闸门：`tools/selftest_doors.js`（已进 `selftest.ps1`）
* 九个单位**逐个**过表：三个根类构造函数里有 `GunUser.allowDoors(this)`，六个子类的 `extends` 指向根类，九个 `id` 都在 `ModEntities` 里；
* `GunBrain#tick` 里有 `this.doors.tick(level)`、`GunBrain` 持有并暴露它、`DoorBehavior` 有 `LivingTickEvent` 处理、`TarkovScav` 注册了它、两个驱动一 tick 只跑一次；
* 三个 `[ai]` 键存在、默认值与 README 一致（AssetTest 另有一道"每个键都要有文档"的闸）；
* 源码里确实有"门里有人→不关"和"铁门→不碰"两条分支，且**唯一的关门调用**就在 `if (shouldClose(...))` 后面（多一个关门点就绕过安全规则）；
* `shouldClose` 的 **Java 方法体**与闸门里那份 JS 镜像**逐字比对**（改一边不改另一边直接红），然后拿镜像跑规则表与**逐 tick 仿真**：
  | 场景（0.25 格/tick，半径 2.0，延迟 40） | 关门 tick |
  | --- | --- |
  | 一直往前走 | **8**（第 8 tick 距离 2.25 > 2.0，走"已离开半径"这条腿，远早于 40） |
  | 停在门口 0.5 格处 | **40**（走"延迟到点"这条腿） |
  | 停在 1.0 格处 | **40** |
  | 有同伴在门口站到第 60 tick | **61**（同伴一走就关） |
  | 有同伴一直堵着门 | **永不**（直到没人） |
* 以及从 class 文件读到的原版事实：`Pillager`/`Raider` 不含 `OpenDoorGoal`、`Vindicator` 含、`DoorBlock#isWoodenDoor` 用的是 `canOpenByHand`、`BlockSetType` 有 `doorClose`、村民的 `InteractWithDoor` 里有 `MAX_DISTANCE_TO_HOLD_DOOR_OPEN_FOR_OTHER_MOBS`。

#### 日志
开门/关门各一行 INFO（`[doors] …`，跟 `logGunAi` 同一个开关）；卡住的单位那条 WARN 现在把门的全部状态写在**一行**里：
`doors: open=true pass=true closes=true pending=0`（`closes` = `[ai].closeDoorsBehind`，`pending` = 这个单位还欠着几扇没关的门）。

#### 还没被眼睛验证的部分
* 「单位穿门 → 门在背后关上」这条**只能在一个真客户端里看到**，本环境没有开客户端；上面那张仿真表是算术证明，不是目视证明。
* 单位**死掉/卸载**时手里还挂着一扇没关的门，那扇门就保持原样（记忆随实体消失）。原版村民大脑也一样，没做"死亡补关"。
* 单位**永久卡在门框正中**时（碰撞箱一直压着门方块）那扇门会一直开着，直到它挪开——这是刻意的取舍：**宁可不关，也不夹人**。
* 关门的对象是**木门**；铁门不碰（也不能开）。活板门、栅栏门、其他模组的自定义"门"不在范围内。

### 5i. "Sometimes it just stands there and lets me kill it" — the stall watchdog, and the cover speed

Two gameplay fixes live here: the reachable state in which a gun mob **neither moved nor fired**, and
the speed at which a mob **dives for cover**.

#### The stall: a watchdog that was written but never read

`GunBrain` armed a watchdog in two places and compared it to nothing, so neither state could ever end:

| Where | What it did | Why it could not end |
| --- | --- | --- |
| `GunBrain#tickReload` (`GunBrain.java:736`) | `this.watchdogTicks = 200; return;` on the "TaCZ says a reload is running" path | the only timeout (`if (this.stateTicks > 200)`, `GunBrain.java:785`) sat **after** that early return, so it could only fire once TaCZ had already stopped reporting a reload |
| `GunBrain#handleShootResult`, `default ->` branch (`GunBrain.java:1022`) | `this.watchdogTicks = 20` for `UNKNOWN_FAIL` / `NETWORK_FAIL` / `FORGE_EVENT_CANCEL` | nothing read it; `FIRE` only ends on an accepted shot (`shotsInBurst` only grows on `SUCCESS`) or on losing line of sight |
| `GunBrain#tick` (`GunBrain.java:363`) | decremented it every tick | a counter that is written and decremented but never read is a comment, not a bound |

The reachable combination is `FIRE` while TaCZ refuses every shot: `tickFire` calls
`getNavigation().stop()` and `setSprinting(false)` every tick (`GunBrain.java:601`, `603`), so the mob
is pinned in place, aiming, producing nothing - the "stands there waiting to be hit" report. It is not
hypothetical: `NETWORK_FAIL` is a real, documented answer from TaCZ (the timestamp bug in
`GunBrain#shootAt` used to make *every* shot answer it), and any cancelled shot event is
`FORGE_EVENT_CANCEL`.

The fix makes the field mean something, in the state that arms it:

* `tickFire` arms `watchdogTicks` from `combat.fireStallTicks` (default 100) **exactly once**, on the
  first tick of a burst (`GunBrain.java:617` - `stateTicks == 1`), and every `SUCCESS` re-arms it
  (`GunBrain.java:994`), so it measures *ticks since the last shot actually happened*. The failure
  branch deliberately does **not** re-arm it: refilling the counter is what made the old one a no-op,
  and it would also hide the expiry from the check. When it reaches zero the mob logs a `WARN`
  (`GunBrain.java:648`) and leaves - `REPOSITION` first, and after three consecutive stalled windows
  (or once `giveUpTicks / 2` is exceeded) `RETREAT`, which always issues a move
  (`GunBrain.java:914`/`919`) so a stalled mob is guaranteed to move.
* `tickReload` bounds the "still reloading" wait with `guns.reloadStallTicks` (default 200) **inside**
  that branch (`GunBrain.java:739`), and `WARN`s before breaking contact.
* The escape is never silent: both paths log `[gunai] … - giving up on this position` /
  `- breaking contact` at `WARN` with the TaCZ result that caused it.

`node tools/selftest_antistall.js` asserts all of the above from the source (including "the bound sits
inside the reloading branch, before the early return" and "tickFire assigns the watchdog exactly once",
which are the exact shapes of the old bug) and the bytecode-level fact that **vanilla `Pillager` /
`AbstractIllager` / `Raider` reference no `MeleeAttackGoal` at all** - which is why
`GunnerPillagerEntity` now registers the same `MeleeAttackGoal(this, 1.1D, false)` at priority 2 that
`ScavEntity` already had: `GunAttackGoal#canUse` is false whenever TaCZ's gun index yields nothing for
the tier, and before this line the pillager then had *no* attacking goal and stood next to its target
until it was killed.

#### The cover speed: one multiplier, three call sites

Every `PathNavigation#moveTo` in `GunBrain` is in this table; `selftest_antistall.js` fails on any call
site that is not, so a new movement speed cannot be added silently.

| Call site | Was | Now | State / purpose | Why it is (not) scaled |
| --- | --- | --- | --- | --- |
| `GunBrain.java` (reload behind cover) | `1.2` → `1.5` | `coverSeekSpeed()` = **1.0** | `RELOAD`: dive behind cover to reload | cover move; 1.0 = normal walk after the user's second tuning pass |
| `GunBrain.java` (reposition) | `1.2` → `1.5` | `coverSeekSpeed()` = **1.0** | `REPOSITION`: move to a new firing position | cover move |
| `GunBrain.java` (retreat to cover) | `1.25` → `1.5` | `coverSeekSpeed()` = **1.0** | `RETREAT`: dash to cover further from the threat | cover move |
| `GunBrain.java` (retreat, no cover) | `1.25` + `setSprinting(true)` | `escapeWithoutCoverSpeed()` = **1.0**, sprint **off** | `RETREAT` with no cover found: run away | this is the leg the user complained about; both the extra 0.25 and the sprint are gone (both are config keys now) |
| `GunBrain.java` (advance, cover) | `1.15` | `1.15` unchanged | `ADVANCE`: cover *towards* the target | closing a gap, not seeking cover - the user did not ask for this |
| `GunBrain.java` (advance, straight) | `1.15` | `1.15` unchanged | `ADVANCE`: no cover, walk at the target | same |

`setSprinting` is now config-driven: the retreat sets `setSprinting(tactics.retreatSprint)` and that key
defaults to **false**, because unconditional sprinting was the hidden multiplier behind "they escape
absurdly fast" - it adds speed on top of the navigation modifier. `setSprinting(false)` still happens in
`ALERT`, `AIM`, `FIRE` and on the `IS_SPRINTING` shoot result.

#### The runtime gate (no client needed)

| Command | What it does |
| --- | --- |
| `/tarkovscav test watch [seconds] [distance] [pos]` | the `test fight` setup (with a 1000 HP dummy, so the fight lasts the whole window), then after the window it asserts **moved ≥ 1 block and ≥ 3 accepted shots** and logs `[test] WATCH PASS/FAIL … moved=… shots=… dummyDamage=… stalls=…` plus a context line (`target`, `los`, `distance`) |
| `/tarkovscav test stall [seconds] [distance] [pos]` | the same fight with every shot answered `FORGE_EVENT_CANCEL` without calling TaCZ (the condition that used to pin the mob in `FIRE` for ever), then asserts the watchdog broke the stand-off: **escapes ≥ 1**. `moved` is reported but not asserted - a mob can legitimately cover a couple of blocks on its way *into* `FIRE` |

Both run on the dedicated test server over RCON, with an explicit `pos` on open ground: at the world
spawn the two mobs can end up on opposite sides of a city wall, the target selector drops the target for
lack of line of sight, and the fight never starts.

**Measured, on the isolated Forge 1.47.4.3 server** (TaCZ 1.1.7, GeckoLib 4.8.2, flat 51x51 arena,
`logGunAi = true`):

| Case | Result | Numbers |
| --- | --- | --- |
| final shipped jar, `/tarkovscav test watch 20 16` | **PASS** | `moved=1.65 blocks, shots=18, dummyDamage=106.09, stalls=0` |
| final shipped jar, `/tarkovscav test stall 20 16` | **PASS** | `escapes=2, moved=2.09 blocks` (two WARNs 100 ticks apart, a third at +1 s) |
| `/tarkovscav test watch 20 16` | **PASS** | `moved=6.09 blocks, shots=37, dummyDamage=261.38, stalls=0` (RPK, rifle tier) |
| `/tarkovscav test watch 20 16` | **PASS** | `moved=1.32, shots=41, dummyDamage=361.08, stalls=0` (RPK; the mob never needed to advance) |
| `/tarkovscav test stall 20 16` | **PASS** | `escapes=2, moved=2.30 blocks`, with two WARN lines exactly 100 ticks apart: `FIRE produced no accepted shot for 100 ticks (last TaCZ result FORGE_EVENT_CANCEL, 1 stall(s)) - giving up on this position` |
| `/tarkovscav test watch 30 16` at `coverSeekSpeedModifier = 1.0` | **PASS** | `moved=3.11 blocks, shots=19` |
| `/tarkovscav test watch 30 16` at `coverSeekSpeedModifier = 1.5` | **PASS** | `moved=5.54 blocks, shots=4` - **1.78x the distance** in the same window and scenario, i.e. the multiplier does reach the path |

`stalls=0` in every healthy run is the point: the watchdog does not fire during normal combat, so the
verified cover/reload/suppression/retreat behaviour is untouched. The 1.0-vs-1.5 numbers are an
*indicative* measurement (each run rolls a different gun, so the number of reposition legs differs); the
telemetry line now also carries `ticks=` and `stateDist=`, so any single logged line yields an exact
blocks/second figure for the state the mob is in - read `stateDist / (ticks / 20)` for a
`REPOSITION`/`RELOAD`/`RETREAT` line to audit the multiplier directly.

### 5k. "Part of the mob is missing and I can see the entity behind it" — the stencil leak

Three reports turned out to be one bug: a mob's upper body showing **another entity's pixels** (a scav
wearing an iron golem's texture; a "red iron golem"), the same thing at 45° showing grass/flowers/picture
frames, and "only one of several scavs does it". The second reading of all three is the right one: **that
part of the mob is not being drawn at all, so you are looking through it at whatever stands behind.**

**Cause, read out of TaCZ's bytecode** (`javap -c -p com.tacz.guns.client.model.BedrockGunModel`, decoded
with `node tools/gl_state_audit.js <dump>`, TaCZ 1.1.8):

| Where | Call | Constant | Restored? |
| --- | --- | --- | --- |
| `render` | `RenderSystem.stencilOp` | `GL_KEEP, GL_KEEP, GL_KEEP` (7680 x3) | - |
| `render` | `RenderHelper.disableItemEntityStencilTest()` | **raw `GL11.glDisable(2960)`** | bypasses Minecraft's state cache |
| `render` | `RenderSystem.clearStencil`, `RenderSystem.clear` | `0`, then `GL_STENCIL_BUFFER_BIT` (1024) | **mid-frame clear of the shared stencil buffer** |
| `lambda$render$28`, `lambda$renderAccelerated$29` (twice each) | `RenderHelper.enableItemEntityStencilTest()` | **raw `GL11.glEnable(2960)`** | bypasses the cache |
| same lambdas | `RenderSystem.stencilFunc` | `GL_LEQUAL,127,255` (516) then **`GL_EQUAL,0,255`** (514) | **never restored to `GL_ALWAYS` (519)** |

After a gun has been drawn the *cached* stencil func is `GL_EQUAL, ref 0`. Anything that enables the
stencil test afterwards inherits it, and only fragments whose stencil value is 0 survive - every other
fragment of the next mob drawn is discarded. That is the "half the mob is missing" picture, it depends on
draw order (hence "only one of several"), and it has nothing to do with texture binding - which is why
every `RenderType`/atlas investigation came up empty.

**What we do about it** (TaCZ cannot be patched, so our own draws are made immune):

* `client/RenderStateGuard` snapshots the stencil test on/off, `stencilFunc(func,ref,mask)`,
  `stencilMask`, `stencilOp`, depth test + depth mask, blend and cull. Reads go through
  `GlStateManager._getInteger` (no cache side effect), and every write that has a `GlStateManager` entry
  goes through it so the cache stays coherent.
* **Four more restores**, ported from an older, independently published snapshot of this mod
  (`https://github.com/EdDYON/tarkovscav`, vendored read-only under `_friendfix/` and analysed in
  `docs/朋友修复分析.md` §3.2): the **back face's** stencil state
  (`GL20.glStencilFuncSeparate/glStencilMaskSeparate/glStencilOpSeparate`, for `GL_FRONT` and
  `GL_BACK`), `GL_STENCIL_CLEAR_VALUE`, the **active texture unit plus the bindings of units 0..2**
  (base texture, overlay, lightmap), and **all 12 `RenderSystem` shader samplers**. These writes are raw
  GL on purpose: the cached call is skipped whenever Minecraft's cache already holds the value it was
  asked for, and that is exactly the state a foreign renderer leaves behind when it changes the driver
  with a raw call (`RenderHelper`, and TaCZ's texture binds, do). The whole list is asserted in
  `tools/selftest_texture_state.js` section 8, so it cannot be deleted again.
* **The live-GL gate**: `tools/spike/gl/RenderStateGuardLiveTest.java` (registered in
  `tools/spike/selftest.ps1`) opens a hidden GLFW window, makes a real context current, and round-trips
  the shipped guard against the real driver - the active unit, units 0..2, the shader-owned unit, the 12
  samplers, both stencil faces, the clear value, depth/blend/cull, `GL_NO_ERROR` and enter/leave
  idempotency, 16 scenarios. It is the only check here that measures the driver instead of the source or
  the bytecode; on a machine with no usable GL context it prints one `SKIP` line and exits 2, which the
  suite does not count as a failure.
* All three renderers (`ScavRenderer`, `GunnerPillagerGeoRenderer`, `GunnerVillagerRenderer`) wrap their
  whole `render(...)` in `snapshot` -> `forceAlwaysPassStencil()` -> `finally restore()`.
* `GunInHandGeoLayer` forces always-pass **again immediately after** the TaCZ item draw, so neither the
  next bone nor the next entity can inherit what TaCZ left.

**The instrument (delivered so it can be confirmed in game).** Set `client.logGlState = true` (then
`/tarkovscav client reload`, or restart), stand next to the affected mob with an iron golem nearby, and
read `latest.log`:

```
[gldebug] before item draw (RightHandLocator): stencilTest=true func=519 ref=0 valueMask=255 writeMask=255 op=(7680,7680,7680) backFunc=519 clear=0 depthTest=true depthWrite=true blend=true cull=true activeUnit=0 tex=… shaderTex0=…
[gldebug] after item draw  (RightHandLocator): stencilTest=true func=514 ref=0 valueMask=255 writeMask=255 op=(7680,7680,7680) backFunc=514 clear=0 depthTest=true depthWrite=true blend=true cull=true activeUnit=2 tex=… shaderTex0=…
[gldebug] scav 123 end: stencilTest=… func=519 … backFunc=519 clear=0 activeUnit=… tex=…
[gldebug] WARN scav 123 changed the stencil func while it ran: entry=(519,0,255) exit=(514,0,255) …
```

* `func=519` is `GL_ALWAYS` (passes everywhere), `func=514` is `GL_EQUAL`. **If you see 514 anywhere
  outside the `after item draw` line, or you get the WARN line, the leak is confirmed on your machine.**
* With the guard in place the same scenario should end at `519` and print no WARN.
* The same lines carry the four values the ported restores put back: `backFunc` (the back-face func,
  `519` on entry), `clear` (`GL_STENCIL_CLEAR_VALUE`), `activeUnit` (the texture unit TaCZ left active)
  and `shaderTex0` (`RenderSystem` sampler 0). Any of them different on an `end:` line than on the
  `before item draw` line is a restore that did not happen - and now a `tools/selftest_texture_state.js`
  failure too.

**Bisect that is still worth doing** (decisive for the "see through" reading): kill everything except that
one mob - if it becomes normal, the missing geometry really was state left behind by another draw; if it
stays broken, it is that mob's own state. Also worth one try: take its gun away
(`/item replace entity <it> weapon.mainhand with air`) - with no TaCZ gun drawn, the stencil path never
runs at all.

### 5l. The voice lines (暴徒语音)

Twenty-seven mono clips supplied by the user, played from the mob's own position so they are spatialised
(you can tell which building a line came from) and on our own timers, not vanilla's.

**The clips.** Converted with `ffmpeg -ac 1 -ar 44100` plus a leading/trailing silence trim and EBU R128
loudness normalisation (`loudnorm=I=-16:TP=-1.5:LRA=11`); the trailing trim uses the reverse-trim-reverse
trick, because `silenceremove=stop_periods=1` also eats internal pauses (it cut a 3.2 s line down to
0.06 s before that). `node tools/voice_report.js <dir>` re-reads every file's Vorbis header and prints
channels / sample rate / duration; `tools/selftest_voice.js` fails if any shipped clip is not mono.

| Clip | Source line | Channels | Rate | Length |
| --- | --- | --- | --- | --- |
| `contact_1` | 发现敌人-狗日的在这儿 | 1 | 44.1 kHz | 0.64 s |
| `contact_2` | 发现敌人-军人！ | 1 | 44.1 kHz | 0.94 s |
| `contact_3` | 发现敌人-在在这 | 1 | 44.1 kHz | 0.80 s |
| `contact_4` | 发现敌人-人在这 | 1 | 44.1 kHz | 1.74 s |
| `contact_5` | 发现敌人-他TM在这儿！ | 1 | 44.1 kHz | 2.76 s |
| `taunt_1` | 嘲讽-TMD啥？ | 1 | 44.1 kHz | 0.99 s |
| `taunt_2` | 嘲讽-来吧搞定他们 | 1 | 44.1 kHz | 1.36 s |
| `taunt_3` | 嘲讽-SB,你死定了！ | 1 | 44.1 kHz | 3.10 s |
| `grenade_1` | 手雷-躲起来MD | 1 | 44.1 kHz | 2.80 s |
| `grenade_2` | 手雷-该死手雷！ | 1 | 44.1 kHz | 1.54 s |
| `mark_1` | 标记-喂你跑哪儿去了 | 1 | 44.1 kHz | 1.13 s |
| `death_1` | 死亡-SCAV鬼叫 | 1 | 44.1 kHz | 7.60 s |
| `death_2` | 死亡-SCAV鬼叫 (2) | 1 | 44.1 kHz | 6.00 s |
| `death_3` | 死亡-SCAV鬼叫 (3) | 1 | 44.1 kHz | 6.00 s |
| `death_4` | 死亡-SCAV鬼叫 (4) | 1 | 44.1 kHz | 4.60 s |
| `contact_6` | Bilibili `scav1_enemy_contact_02_1` - Right here he is! / 他就在这儿！ | 1 | 44.1 kHz | 0.95 s |
| `contact_7` | Bilibili `scav1_enemy_contact_06_1` - Get the cap! / 搞掉那个大兵！ | 1 | 44.1 kHz | 4.24 s |
| `contact_8` | Bilibili `scav1_enemy_contact_08_1` - Hey soldier, this is our point! / 喂兵哥哥，这是我们的地盘！ | 1 | 44.1 kHz | 2.09 s |
| `contact_9` | Bilibili `scav1_enemy_contact_11_1` - You are dead, soldier! / 你死定了，大兵！ | 1 | 44.1 kHz | 2.35 s |
| `contact_10` | Bilibili `scav1_enemy_contact_12_1` - Fellas, surround him! / 伙计们，包围他！ | 1 | 44.1 kHz | 1.71 s |
| `contact_11` | Bilibili `scav1_enemy_contact_13_1` - We don't give a damn who you are! / 我们才不管你是谁！ | 1 | 44.1 kHz | 3.17 s |
| `idle_1` | Bilibili `scav1_mutter_01` - It's fine, it's fine! / 一切正常，正常！ | 1 | 44.1 kHz | 2.07 s |
| `idle_2` | Bilibili `scav1_mutter_03` - Safe and sound, not bad already. / 安然无恙！目前还不错 | 1 | 44.1 kHz | 3.36 s |
| `idle_3` | Bilibili `scav1_mutter_04` - Now to dealers, and then to ladies! / 先找商人，后找女人！ | 1 | 44.1 kHz | 1.30 s |
| `idle_4` | Bilibili `scav1_mutter_06` - Cheeki breeki, CHEEKI BREEKI!! / CHEEKI BREEKI！ | 1 | 44.1 kHz | 5.70 s |
| `idle_5` | Bilibili `scav1_mutter_08_n` - It's ok, sell stuff to dealer and we'll be fine. / 好了，东西卖给商人，然后我们就没事儿了 | 1 | 44.1 kHz | 1.58 s |
| `idle_6` | Bilibili `scav1_mutter_08` - Sell stuff to dealer and we'll be fine! / 东西卖给商人，我们就没事儿了！ | 1 | 44.1 kHz | 2.51 s |

**The Bilibili batch** (the last twelve) comes from one video (`BV18J411s73M`, 【逃离塔科夫】Scav语音翻译):
six contact shouts from `2:26-3:02` and six idle mutterings from `7:31-8:46`, which is what the user asked
for. The video carries its own bilingual caption AND the original YSM clip name on screen, so the
subtitles above are read off the video rather than guessed. They are cut by
`tools/make_voice_clips.ps1`, which is the reproducible version of that pass:

```
.\tools\make_voice_clips.ps1 -Source <audio> -StartSeconds 146 -EndSeconds 182 -Prefix contact -IndexStart 6 -MaxClips 6
.\tools\make_voice_clips.ps1 -Source <audio> -StartSeconds 451 -EndSeconds 526 -Prefix idle -MinSeconds 0.8 -MaxClips 6 -SilenceSeconds 0.45
```

It detects sentence boundaries (`silencedetect=n=-34dB:d=0.20`, and `0.45` for the muttering, where the
internal pauses are longer), trims both ends, runs `loudnorm=I=-16.5:TP=-1.0:LRA=11`, then applies one
final gain that lands the clip on **-16.8 dB mean without pushing the peak past -1.0 dB** - the band the
first fifteen already sit in (-16.4..-17.1 dB mean, -1.0..-6.0 dB peak). Measured on the shipped files:
contact_6..11 -16.8..-18.1 dB, idle_1..6 -16.8..-20.5 dB. The quietest two are short mutterings that
`loudnorm` leaves alone; they are 3-4 dB below the rest, which is audible only in a side-by-side A/B.
The un-cut source copies live in `assets_source/voice/raw/bilibili-*.ogg`.

**The pools** (`ModSounds`): `CHATTER` = the five contact lines + the three taunts + the six Bilibili
contact shouts - **one merged shout pool**, which is what the user asked for, used both for "I just found
someone" and for talking during the fight. `IDLE` is now **its own pool** of the six self-talk clips (it
used to borrow `CHATTER` while that batch did not exist). `GRENADE` (2), `MARK` (1) and `DEATH` (4) are
their own pools.

<!-- voice-inventory:start -->

#### 阵营语音池（⑪：USEC / BEAR / 优质PMC，每池 5 条）

每个家族 6 个池、每池 5 条，共 90 条；全部**单声道 44.1 kHz**、首尾裁静音、`loudnorm=I=-16.5:TP=-1.0:LRA=11` 后再统一增益到**均值 ≈ −16.8 dB、峰值 ≤ −1.0 dB**（峰值被限住的句子均值会更低，这与最初 27 条的取舍一致）。**池表**在 `tools/voice_pools.json`，**剪裁**在 `tools/make_family_voice_clips.ps1`，**落盘**在 `tools/voice_emit.js`——`<家族>_<类别>` 就是一个池，`ModSounds` 读 `voice_clips.txt` 清单注册，所以加语音是**改数据**不是改代码。

| 池 | 条数 | 来源（前三） | 触发 |
| --- | --- | --- | --- |
| `usec_contact` | 5 | `usec1_enemy_contact_01_l`、`usec1_attention_01_l`、`usec1_ambush_01_l` | 发现敌人 |
| `usec_chatter` | 5 | `usec1_fight_01_l`、`usec1_gogogo_01_l`、`usec1_inthefront_01_l` | 交火喊话 |
| `usec_idle` | 5 | `usec1_mutter_01_q`、`usec1_tired_01_q`、`usec1_dontknow_01_l` | 自言自语 |
| `usec_grenade` | 5 | `usec1_grenade_01_l`、`usec1_enemy_grenade_01_l`、`usec1_grenadeflash_01_l` | 手雷 |
| `usec_mark` | 5 | `usec1_lostvisual_01_n`、`usec1_onyourown_01_n`、`usec1_regroup_01_l` | 报点 |
| `usec_death` | 5 | `usec1_death_01`、`usec1_agony_01`、`usec1_hurt_neardeath_01` | 阵亡 |
| `bear_contact` | 5 | `bear2_enemy_contact1_l`、`bear2_attention_01_n`、`bear2_ambush1_l` | 发现敌人 |
| `bear_chatter` | 5 | `bear2_fight10_l`、`bear2_gogogo_01_n`、`bear2_inthefront_01_l` | 交火喊话 |
| `bear_idle` | 5 | `bear2_mutter10_q`、`bear2_tired10_bl_n`、`bear2_dontknow_01_n` | 自言自语 |
| `bear_grenade` | 5 | `bear2_grenade1_l`、`bear2_enemy_grenade1_bl_l`、`bear2_grenadeflash_01_l` | 手雷 |
| `bear_mark` | 5 | `bear2_lostvisual_01_l`、`bear2_onyourown_01_n`、`bear2_regroup_01_n` | 报点 |
| `bear_death` | 5 | `bear2_death1`、`bear2_hurt_neardeath1_bl`、`bear2_hurt_medium_bl_l` | 阵亡 |
| `elite_contact` | 5 | `标记敌人-located_10`、`标记敌人-located_11`、`标记敌人-located_6` | 发现敌人 |
| `elite_chatter` | 5 | `被压制-suppressed_1`、`被击中-fire_1`、`队友击杀敌人-praise_1` | 交火喊话 |
| `elite_idle` | 5 | `嘲讽-insult_1`、`嘲讽-insult_10`、`嘲讽-insult_11` | 自言自语 |
| `elite_grenade` | 5 | `有手雷-grenade_1`、`有手雷-grenade_2`、`有手雷-grenade_3` | 手雷 |
| `elite_mark` | 5 | `located_1`、`located_2`、`located_3` | 报点 |
| `elite_death` | 5 | `受伤-hurt_1`、`受伤-hurt_2`、`受伤-hurt_3` | 阵亡 |

**效果音**：`grenade_land`（BV1j1cZeHEAt 0:00-0:02）——手雷**落地/弹跳**，见 §5v。

**共享池**（`shared`，**武装暴徒 / 武装村民 / 武装暴徒掠夺者三者共用**——这三者在代码里不覆盖 `voiceFamily()`，`VoicePools.familyOf` 就把它们落到 `shared`）：`idle` 6、`contact` 14、`chatter` 14、`mark` 4、`death` 4、`grenade` **5**。

**2026-09-25 手雷喊话补充（用户原话：「丢雷明显少了戛纳大那些」）**：共享手雷池原来只有 `grenade_1/2` 两条，而 usec/bear/优质 三族各有 5 条，所以连续扔雷时听来听去就那两句。新加 `grenade_3/4/5`，素材是用户指定的 B 站视频《【俄中英三语字幕】逃离塔科夫SCAV语音翻译》`BV1eyZeBYEnn`：**4:36–4:43**（用户点名的"scav 扔手雷"那句）、**8:20–8:24**、**8:28.5–8:30.5**；用 `tools/wav_envelope.js` 找人声段，再按现有 `grenade_1/2` 的**实测**均值（−17.1 / −17.2 dB）对齐响度后导出——成品实测 −17.1 / −17.1 / −17.6 dB、峰值 ≤ −0.6 dB（不削波），落在池表的 0.25–4.0 s 规则内。**仅供本地私用**，不要随公开整合包分发。

**手雷的两个冷却（同日拆分）**：`voice.grenadeCooldownTicks`（默认 200）现在只管**反应**（"有雷落在我旁边"）；新键 `voice.grenadeShoutCooldownTicks`（默认 40）管**扔雷者自己的喊话**。原来两者共用一个 200 tick 计时器，所以它先对别人的雷喊过一句，自己扔雷时的喊话就被自己的冷却吃掉——混战里几乎听不到；拆开以后连续扔雷每次都会喊。

合计 91 条（清单池）+ 30 条（共享池）= **121 条语音**，另 1 条效果音，1.4 MB（打包目标 ≤ 20 MB）。**第三方素材声明**：USEC/BEAR 语音来自《逃离塔科夫》原版音频（用户提供的 `塔科夫音效.rar`），优质PMC 语音来自用户提供的 `优质PMC.zip`，手雷落地音效来自 B 站 `BV1j1cZeHEAt`（0:00–0:02），共享手雷喊话 `grenade_3/4/5` 来自 B 站 `BV1eyZeBYEnn`（4:36–4:43 / 8:20–8:24 / 8:28.5–8:30.5）。**仅供本地私用，请勿随整合包公开发布。**

<!-- voice-inventory:end -->

#### 响度实测表（可选工具，**未对已有的音频文件做任何改动**）
**背景**：曾有一次"优质/部队语音声音有点小"的反馈，当时按"重渲染 BEAR+优质"做过一版；**随后用户回复是他自己
把「敌对生物」音量滑块调低了**（掠夺者系走 `HOSTILE`、村民系走 `NEUTRAL`，见 §4 的说明），所以**那一版音频被完整
回滚**：现在 `sounds/` 下原有的 **118 条 ogg 与已交付 jar（`a3788955…`）逐字节相同**（sha256 全量比对 118/118，
工具：把旧 jar 里的 `assets/tarkovscav/sounds/*.ogg` 解出来对比即可）——之后只在 2026-09-25 **新增**了 3 条共享手雷喊话
（`grenade_3/4/5`，来源与实测值见上），所以现在是 **121 条**。**语音播放仍然各按自己的
`mob.getSoundSource()`**（掠夺者 HOSTILE / 村民 NEUTRAL），这是我们刻意保留的原行为。

**但"同一家族内是否有几条特别轻"仍是一件可测的事**，所以保留了一套**只测量、不改文件**的工具：
`tools/measure_voice_levels.ps1` 用 ffmpeg `volumedetect` 逐条量出 `voice_levels.json`（**出厂随包**，121 条全量；
均值单位 dB，峰值是**样本峰**而不是过采样真峰），`tools/make_family_voice_clips.ps1` 则保留了一次"提高均值 + 限幅"
的可选渲染路径（`-TargetMeanDb -16.8 -PeakCeilingDb -0.5 -MaxLimiterDb 6`），**默认不跑**。

**当前实测分布**（来自随包的 `voice_levels.json`）：

| 家族 | 条数 | 均值中位 | 均值平均 | 标准差 | 峰值最大 | 均值最小..最大 |
| --- | --- | --- | --- | --- | --- | --- |
| `shared`（scav/村民/掠夺者，2026-09-25 加 3 条手雷喊话） | 30 | -17.10 | -17.38 | 1.06 | 0.00 | -20.50 .. -14.80 |
| `usec` | 30 | -16.90 | -17.30 | 1.04 | -1.00 | -20.60 .. -16.80 |
| `bear` | 30 | -16.90 | -17.48 | 1.22 | -1.00 | -21.50 .. -16.80 |
| `elite` | 30 | -16.80 | -16.86 | 0.11 | -1.30 | -17.30 .. -16.70 |
| `effect`（落地音，瞬态） | 1 | -32.20 | -32.20 | 0.00 | -1.00 | -32.20 .. -32.20 |

**四族的中位数只差 0.3 dB（-16.80 .. -17.10），所以"整族偏轻"这件事在文件层面并不存在**；存在的是**族内参差**
（`shared` 与 `bear` 的标准差 ≈1.1–1.2 dB，`elite` 只有 0.11）。

**如果以后要逐条校准，优先看这些（比本族中位数偏离 > 2 dB 的条目，不藏）**：

| 家族 | 偏轻的条目（均值 dB） | 偏响的条目 |
| --- | --- | --- |
| `shared` | `idle_5` -20.5、`death_3` -19.3、`death_2` -19.2 | `death_4` -14.8 |
| `usec` | `usec_death_3` -20.6、`usec_idle_5` -20.6、`usec_death_5` -19.3 | — |
| `bear` | `bear_death_5` -21.5、`bear_idle_5` -20.4、`bear_death_1` -20.0、`bear_death_2` -19.3 | — |
| `elite` | — | — |

**校准一条池的命令**（会按同一套"提高均值 + 限幅"策略重渲染该池，其余池**合并保留不变**；跑完记得
`node tools/voice_emit.js` 与 `tools/measure_voice_levels.ps1`）：

```
node tools/voice_plan.js
powershell -ExecutionPolicy Bypass -File tools/make_family_voice_clips.ps1 -OnlyFamily bear -OnlyPool bear_death
powershell -ExecutionPolicy Bypass -File tools/measure_voice_levels.ps1
```

**逃生开关（不用重渲染，默认不影响听感）**：`voice.familyVolume`（`shared/usec/bear/elite`，默认全 1.0）与
`voice.effectVolume`（默认 1.0）。`/tarkovscav client state` 会打印当前生效值，`/tarkovscav debug` 每个怪后面带
`voice=usec (own clips, x1.00)`。

**手雷落地音**：瞬态音，均值低（-32.2）不代表听不见，判据是**峰值 −1.0 dB**（与语音峰值同档）。

**逃生开关**：`voice.familyVolume`（`shared/usec/bear/elite`，默认全 1.0）+ `voice.effectVolume`（默认 1.0），
`/tarkovscav client state` 会打印 `voiceVolume / familyVolume shared=… usec=… / effectVolume`，
`/tarkovscav debug` 每个怪后面带 `voice=usec (own clips, x1.00)`。**默认全是 1.0，所以默认听感与加这两个键之前完全一致。**
（**为什么掠夺者的语音可能比村民轻**：语音走 `mob.getSoundSource()`，掠夺者系是 `HOSTILE`、村民系是 `NEUTRAL`，
所以「敌对生物」和「中立生物」两个滑块会分别缩放它们——这是原版行为，我们**刻意保留**；想让两者一致就把两个滑块调成
一样，或把某个家族的 `familyVolume` 调高。）

**工具链**（全部可复跑，**默认不动音频**）：`tools/voice_plan.js`（选片）→ `tools/make_family_voice_clips.ps1`
（可选渲染：`-OnlyFamily`、`-TargetMeanDb`、`-PeakCeilingDb`、`-MaxLimiterDb`，报告合并写入）→
`tools/make_impact_clip.ps1`（效果音，按峰值瞄准）→ `tools/voice_emit.js`（清单/字幕/README）→
`tools/measure_voice_levels.ps1`（**逐条实测，产出出厂 `voice_levels.json`**；`selftest_voice` 用它做"离群条目必须在
README 里点名"的可观测性检查，而不是拿它当响度合格线）。

**The triggers** (all switchable, see the `[voice]` table in §4):

| Trigger | When | Anti-spam |
| --- | --- | --- |
| idle | no target, every `idleIntervalTicks` (400 = 20 s) + jitter | its own timer, deliberately not vanilla's ~6 s |
| contact | first line after the brain engages a target (`state != IDLE`) | one line per target entity id + `contactCooldownTicks` |
| chatter | during `FIRE`/`SUPPRESS` | random 15-30 s interval |
| grenade | a primed TNT - or any entity whose registry name contains `grenade` - is within `grenadeRadius` | `grenadeCooldownTicks` |
| mark | the target is lost | `contactCooldownTicks` |
| death | `die()` | one line, always |

**Death**: the four clips **replace** the vanilla death sound - `getDeathSound()` returns `null` while
`voice.death` is on, so the clip and `SoundEvents.PILLAGER_DEATH`/`VILLAGER_DEATH` never overlap. Set
`voice.death = false` to get the vanilla sound back.

**Pitch — one voice per mob, for life.** Vanilla already does this: `Mob#getVoicePitch()` draws a pitch
once per mob (0.8-1.2) and every sound it plays uses it, which is why a crowd of pillagers does not sound
like one recording on repeat. The scav clips do the same thing, but with the band exposed as a config key:

| | vanilla | here |
| --- | --- | --- |
| band | hard-coded 0.8-1.2 | `voice.pitchMin`/`pitchMax`, default **0.9-1.1** (narrower on purpose: these are real speech clips from one speaker, and a wide shift turns the same person into a chipmunk) |
| drawn | once per mob, in the constructor | once per mob, **the first time it speaks**, then written to its persistent data (`tarkovscav:voicePitch`) |
| kept | for the mob's life (memory only) | for the mob's life, **across a save, a chunk unload and a server restart** - meet the same scav again and it has the same voice |
| per line | identical | mob's own pitch plus `voice.pitchJitter` (±3 % by default, 0 disables), clamped back into the band so no single line escapes it |

Narrowing the band later (e.g. `pitchMax = 1.0`) re-draws a pitch for any mob whose stored voice is now
outside it, so the toml always wins; widening it leaves existing mobs alone. Every line logs
`pitch=… voice=…` when `logGunAi` is on, so you can check from the server log that two scavs really do
speak at different pitches.

**Command**: `/tarkovscav test sound <all|idle|chatter|contact|taunt|grenade|mark|death|contact_1|...>
[pitch]`
plays the pool at the command position, one line every 1.5 s, and logs each one - so you can tell which
line is which before you ever meet a mob in the field. Give a `pitch` (0.1-2.0) and every clip plays at it;
leave it out and the clips are **swept across `pitchMin..pitchMax`**, so a single command auditions the
whole range your mobs can speak in.

> **Licence note.** These voice clips come from third-party material (a game and a video). They are here
> for **local, private use only** - do not redistribute them in a modpack.

### 5m. Factions, friendly fire, the renegade brand, and shared intel (阵营 / 叛变 / 警戒情报)

Three things were missing from the fight: the mobs did not know **who their friends were**, a stray bullet
had no consequence, and a mob that found you could not tell anybody. This section is all three.

#### Who is on whose side: a data-pack tag, not a class list

| Faction | Tag | Shipped contents |
| --- | --- | --- |
| scav | `#tarkovscav:faction_scav` | `tarkovscav:scav` |
| illager | `#tarkovscav:faction_illager` | `pillager`, `vindicator`, `evoker`, `illusioner`, `ravager` + `tarkovscav:gunner_pillager` |
| village | `#tarkovscav:faction_village` | `minecraft:villager`, `wandering_trader`, `iron_golem`, `snow_golem` + `tarkovscav:gunner_villager` |

The tags live in `data/tarkovscav/tags/entity_types/faction_*.json`, so **a modpack moves a mob between
sides by editing a JSON file**, with no rebuild. The illager side lists its five vanilla types one by one
rather than using a vanilla tag: **1.20.1 has no `#minecraft:illagers`** (the vanilla tag is
`#minecraft:raiders`, which also contains the witch), and an unknown tag reference resolves to nothing
without any error - so the explicit list is the only honest way to say "the illagers". `faction/Faction.java` also carries a hard-coded fallback
that mirrors those files exactly and only runs when no tag matched at all (a bare dev run, or a data pack
that deleted them), so behaviour is never worse than "the tags are missing". Membership asks the entity
*type* - it never instantiates an entity to find out what it is.

#### Village defence: the armed villagers also fight the monsters (2026)

用户原话：「顺带这些**武装村民会把那些敌人生物也顺带打了，比如僵尸**」——即把村民系变成**村防守卫**。

**改前 / 改后目标表**（村民系 = `gunner_villager`、`sniper_villager`、`usec_villager`、`elite_villager`；
四者共用 `GunnerVillagerEntity`，所以改一处全覆盖）：

| 目标 | 改前 | 改后 |
| --- | --- | --- |
| 原版灾厄（pillager/vindicator/evoker/illusioner/ravager） | ✅ `NearestAttackableTargetGoal<AbstractIllager>` | ✅ **不变** |
| 本模组掠夺者系（gunner/sniper/bear/elite pillager） | ✅（同上，它们是 `AbstractIllager`） | ✅ **不变** |
| 僵尸/骷髅/尸壳/溺尸/僵尸猪灵/幻翼/凋灵等 | ❌ 无视 | ✅ `#minecraft:undead` |
| 蜘蛛/洞穴蜘蛛/蠹虫/末影螨 | ❌ 无视 | ✅ **逐项列出**（见下） |
| 爬行者 / 史莱姆 / 岩浆怪 / 烈焰人 / 恶魂 / 守卫者 / 潜影贝 / 恼鬼 / 疣猪兽 / 僵尸疣猪兽 | ❌ 无视 | ✅ 逐项列出 |
| 谁打过它（含玩家） | ✅ `HurtByTargetGoal` | ✅ **不变** |
| 被烙上"叛徒"的同阵营单位 | ✅ | ✅ **不变** |
| 同阵营（村民之间、铁傀儡、雪傀儡） | ❌ 不打 | ❌ **仍不打**（`Faction.allies` 在目标判定里挡住） |
| 玩家 | ❌ 不打 | ❌ **仍不打**（除非玩家先动手 → `HurtByTargetGoal`） |

**名单是数据包标签，不是代码**：`data/tarkovscav/tags/entity_types/faction_village_hostile.json`，默认
`#minecraft:raiders`、`#minecraft:undead`，加上**逐个列出的四种节肢动物**（`spider`、`cave_spider`、
`silverfish`、`endermite`）、`creeper`、`slime`、`magma_cube`、`blaze`、`ghast`、`guardian`、
`elder_guardian`、`shulker`、`vex`、`hoglin`、`zoglin`。
**为什么不用 `#minecraft:arthropods`**：那个标签里**包含蜜蜂**——一个"会去打蜜蜂的村民"本身就会变成 bug
报告，所以宁可手写 4 行。**想排除爬行者/加某个 mod 的怪，直接改这个 JSON**（删一行/加一行，无需重编译）。

**总开关**：`faction.villagersAttackMonsters`（默认 `true`）。设 `false` 就回到**改前目标表**（只打灾厄、
打它的人、叛徒）。目标选择器**一直注册**，判定在谓词里**每次扫描都读配置与标签**，所以改成 `false` 不需要重生成
怪物；`Faction.villageHostile(defender, candidate)` 是唯一的判定点（先看开关 → 再看标签 → 最后用 `allies()`
排除同阵营）。

**与枪械 AI 的关系**：这次只动**索敌目标表**。开火仍然由 `GunBrain` 决定（要有视线、要走 warming-up/精度/
掩体那套），警戒情报共享、放置台转化、精度档位、友军误伤与叛变规则**都不变**——村民打错自己人照样触发那套。

**可观测**：`/tarkovscav debug` 现在每只怪都带 `target=<名字> (entity_type)`，若是村防目标还会标
`VILLAGE-HOSTILE`；新锁定一个怪兽目标时会打一行 INFO（受现有 `logGunAi` 开关）
`[faction] <村民> locked onto <僵尸> (minecraft:zombie)`。

> **建议（未改默认）**：**暴徒（scav）系与掠夺者系的目标表这次一个字都没动**。如果你也想让它们打僵尸，
> 照抄一份即可——给 `faction_scav` / `faction_illager` 各加一个同样的目标选择器（或把
> `faction_village_hostile` 的清单复制成 `faction_scav_hostile` 并复用 `Faction.villageHostile` 的判定）。
> 按你之前的规格，暴徒的敌人是"其他人形实体"，所以**保持现状**是默认行为。

#### Friendly fire: three hits, then the traitor is branded

| Rule | Detail |
| --- | --- |
| who counts | only damage between two members of the **same** faction. A player is never friendly fire |
| how much counts | **one hit = one hit**, however big the damage was |
| window | `faction.friendlyFireWindowTicks` (200 = 10 s, sliding); a hit after the window restarts the count |
| one free answer | a victim answering the attacker that already hit it does **not** count - you may shoot back once without becoming a traitor yourself |
| shooting a renegade | never counts. Once somebody is an outlaw, shooting them is public service |
| anger | at `faction.friendlyFireHitsToAnger` (3) hits from the same attacker, the victim turns on **that attacker by UUID** - nobody else, only that pair |
| betrayal | at `faction.betrayalThreshold` (3) hits, the **attacker** is branded a RENEGADE |

A renegade gets: the `tarkovscav.faction.renegade` name prefix ("Renegade"/"叛徒"), an optional glowing
outline (`faction.renegadeGlow`), an entry in its own saved data, and every mob within
`faction.renegadeBroadcastRadius` (48) treats it as an enemy - through the same predicate the target
selectors use, so the announcement cannot drift from the behaviour. `faction.renegadeDecayTicks = 0`
(default) means **the brand is permanent**: the user asked for "the traitor stays a traitor". A positive
value forgives after that many ticks.

Everything (flag, hit count, window start, victim id) lives in the entity's **persistent data**, which Forge
saves and loads with the entity. That is why no entity class needed a new field, the rule works for any
future faction member, and a renegade is still a renegade after a restart.

The hook is a single `LivingHurtEvent` handler (`faction/FactionEvents.java`) rather than an override in each
of the three entity classes: one rule in one place cannot drift between three copies. It never cancels or
modifies the damage - the hit lands exactly as vanilla resolved it; the handler only *counts* it.

#### Shared intel: a direction and a distance band, never a position

A mob with a live target broadcasts a **`SharedContact`**: one of eight 45-degree sectors, one of three
distance bands (near `alert.nearDistance` 8, mid `alert.midDistance` 24, far `alert.radius` 40), an expiry,
and a rolled bearing error of up to `alert.bearingNoiseDegrees` (22). That is the entire message. **It
contains no coordinates**, and the receiver therefore cannot know where you are - only roughly which way.

The two rules that keep this honest, both enforced by `tools/selftest_faction.js` rather than by a comment:

1. **Intel never becomes a target.** `AlertNetwork` and `Faction` contain no `setTarget` call at all. A
   report is held only in the network, and the only things a receiver may do with it are
   `getNavigation().moveTo(...)` (converge) and `getLookControl().setLookAt(...)` (hold). Firing still
   requires line of sight, which is still `GunBrain`'s job - so a mob that follows a report around a wall
   arrives, looks, and only then shoots. The **only** `setTarget` in the whole faction package is the
   victim turning on the friendly-fire attacker, which is the rule above and is deliberate.
2. **Holding never touches the gun.** The held-alert stance uses only the vanilla `LookControl`, which is a
   completely separate path from `applyAimTracking`/the pose source, so a mob that is only *listening* can
   never look "aimed".

| Behaviour | When | What it does |
| --- | --- | --- |
| broadcast | has a target, at most once per `alert.broadcastCooldownTicks` (40) | tells up to `alert.maxRecipients` (6) armed allies within `alert.radius`, chosen by entity id so the choice is stable rather than random |
| converge | no target, the reported area is within `alert.convergeRadius` (20), and it is among the first `alert.convergeMaxAllies` (3) convergers | walks to a point on a ring of `alert.surroundStandoff` (6) around the reported spot, re-pathing at most every `alert.minRepathIntervalTicks` (60) so a report cannot become a pathfinding storm |
| hold | no target, and either too far or too many already converging | turns its head towards the reported direction and stays put |
| forget | `alert.memoryTicks` (300 = 15 s) elapse, the report is from another faction, the mob acquires its own target, or it becomes a renegade | no contact again - this is what takes it back out of the stance |
| ignore | the mob is not an armed member | a plain villager has no business marching to a contact report |

#### Config keys

`[faction]`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | master switch: `false` restores the plain behaviour (everybody hostile to everybody, nothing shared) |
| `friendlyFireHitsToAnger` | `3` | hits from the same attacker before the victim turns on it |
| `friendlyFireWindowTicks` | `200` | sliding window for those hits (10 s) |
| `betrayalThreshold` | `3` | hits after which the **attacker** is branded a renegade |
| `renegadeBroadcastRadius` | `48.0` | how far the news travels, in blocks |
| `renegadeDecayTicks` | `0` | `0` = permanent (default); a positive value forgives after that many ticks |
| `renegadeGlow` | `true` | give a renegade the glowing outline |

`[alert]`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | master switch for intel sharing |
| `radius` | `40.0` | how far a report carries; also the "far" band edge |
| `nearDistance` | `8.0` | upper edge of the NEAR band |
| `midDistance` | `24.0` | upper edge of the MID band |
| `memoryTicks` | `300` | how long a report stays news (15 s) |
| `maxRecipients` | `6` | at most this many allies told per broadcast |
| `broadcastCooldownTicks` | `40` | minimum ticks between two broadcasts from one mob |
| `bearingNoiseDegrees` | `22` | extra bearing error on top of the 45-degree sector, in degrees |
| `convergeRadius` | `20.0` | the reported area must be this close for a receiver to walk to it |
| `convergeMaxAllies` | `3` | at most this many allies converge on one report |
| `surroundStandoff` | `6.0` | radius of the ring convergers stand on |
| `minRepathIntervalTicks` | `60` | at most one re-path per this many ticks while converging |

#### Checking it in game

`/tarkovscav debug` now prints the faction layer next to the gun state for every mob within 32 blocks:

```
tarkovscav:scav: state=FIRE ... | scav renegade=true hits=3 alert=NE/near/-7deg
```

`faction=` is the tag it matched (`scav`/`illager`/`village`/`none`, plus `/RENEGADE`),
`renegade=` is the NBT flag and the hit count, and `alert=` is the report it is currently holding
(compass sector / band / the bearing error that was rolled) or `alert=none`.

To watch the whole thing without a second player: hit a scav three times in creative (in the same 10 s
window) and read the server log - `[faction] … hit friendly … (1/3)`, then `(3/3)`, then
`[faction] … is now a RENEGADE (friendly fire x3), broadcast to N ally/ies within 48.0`, and the next
`/tarkovscav debug` shows `renegade=true`.

### 5q. 狙击手（掠夺者 + 村民）— the snipers (蹲点、被发现就换位、打带跑)

**两只狙击手共用一个实现**。用户先要了掠夺者狙击手，然后说「给村民也加个狙击手的」，所以这节有两半：
`tarkovscav:sniper_pillager`（中文名「狙击手掠夺者」）与 `tarkovscav:sniper_villager`（「狙击手村民」），
**行为代码只有一份**：`gun/SniperBehavior.java`。两只都 `implements SniperMob`，各自只持有
`private final SniperBehavior sniper = new SniperBehavior(this)` 并在自己的 `tick()` 里转发一次，
**没有任何规则被复制第二遍**（闸门会断言两份实体源码里都找不到「took damage / shotsFromHere / retreatPoint」这些规则字符串）。

| | 狙击手掠夺者 | 狙击手村民 |
| --- | --- | --- |
| 继承 | `GunnerPillagerEntity`（原版 `Pillager`） | `GunnerVillagerEntity`（原版 `Villager`） |
| 模型/贴图/音效 | 掠夺者模型 / GeckoLib 或原版 | **原版村民模型 + 职业层贴图 + 原版村民音效**（和武装村民完全同一套） |
| 枪的挂载与姿态 | 掠夺者的举枪姿态 | `GunnerVillagerModel`（`ArmedModel` 手部坐标系） |
| 狙击 AI | `SniperBehavior` | **同一个** `SniperBehavior` |
| 阵营 | `#tarkovscav:faction_illager`（**本次补上：之前它根本没进任何阵营表**） | `#tarkovscav:faction_village`（和武装村民同表；对掠夺者/灾厄敌对，被打会反击） |
| 自然生成权重 | `sniper.spawnWeight = 1` | `sniper.villagerWeight = 1`（**不高于掠夺者**：友好方远程单位对村子的影响更大；两个数都与 `add_scavs.json` 里的权重逐项相等，见 §7i） |

> 为什么要抽组件而不是继承：两只狙击手**必须继承不同的原版类**（`Pillager` / `Villager`），Java 单继承下「都是狙击手」只能靠组合表达。`SniperBehavior` 拿到的只有它真正需要的东西（导航、移动控制、目标、`GunUser#isGunFiring`），全部状态仍然放在生物自己的持久数据里（`SniperPost`），所以和实体类型完全解耦，将来的第三只狙击手不用改任何调用方（`SniperMob` 就是那个"调用方接口"）。

下面是两只共用的规则（实现在 `SniperBehavior`）：

| 项 | 做法 |
| --- | --- |
| **索敌远** | `sniper.followRange = 64`（步枪兵 35）。它在**你还够不着它**的时候就已经进入 AIM/FIRE——这正是蹲点有价值的原因。实际抽到的枪由 `GunPool` 决定（固定 **sniper tier**），命令会打印出来 |
| **蹲点不 strafe 不推进** | 有目标且守住位时，每 tick 做两件事：`getNavigation().stop()` 取消任何路径 + `getMoveControl().setWantedPosition(自己)` 钉死移动控制。**这两句在实体自己里，`GunBrain` 一行未改**，所以普通枪手字节级不受影响 |
| **换位触发 ①** | **受伤**（`getLastHurtByMob() == 目标` 且 100 tick 内） |
| **换位触发 ②** | **被发现**：目标对它有视线**且**距离 < `sniper.discoveredRange`(24) |
| **换位触发 ③** | **同一位置开火 `sniper.shotsBeforeMove`(2) 发**（打带跑；设 1 就是"打一枪换一个地方"） |
| **换位触发 ④** | **太近**：距离 < `sniper.closeRange`(8)——与"近身优先后撤"是同一条 |
| **新位要求** | ≥ `sniper.minPostDistance`(16) 格；16 个候选里按「目标当前**看不到**」优先（评分 +1000）、距离次之，然后取该处 heightmap 最高点。抵达后 `setTarget(原目标)`——**这就是"重新 AIM"**，因为大脑本来就要先瞄准才开枪 |
| **找不到新位** | **可执行退路**：沿「远离目标」方向后撤 16 格（归一化后取 heightmap 最高点），并打印 `no post found, backing off`。**绝不静默站着** |
| **走路卡住** | `sniper.relocateTimeoutTicks`(200) 内没到就地蹲下并重新锁定目标，避免坏路径让它一直游荡 |
| **近身** | <8 格**先换位后撤**；近战只是最后手段（没有给它加任何近战 goal；`NoGunMeleeGoal` 仅在没枪时生效，且对枪手而言手上是枪） |
| **生成** | 走 `CityGate`（与另外两只同一套）**+ 两条前置规则**：与**任何玩家距离 < `sniper.minSpawnDistanceFromPlayer`(32) 直接拒绝**（不会在你脸上刷新）；`sniper.preferHighGround=true` 时要求**周围 8 个探针列中有过半**比自己低 ≥ `sniper.minElevation`(4) 格——**这就是"偏好高处"的实现**：屋顶、山脊、土坡合格，街道不合格。拒绝原因写进 `[spawngate] REJECT …` 日志 |

**精度**：因为固定 sniper tier，它自动落进 **veteran(0.85)** 档（`accuracy.profileSniperTier`），**这两个类里没有一行关于精度的代码**——"更强的人形生物打得更准"由档位规则实现。逐距离命中率见 §5o 表（veteran 列 6/10/20/30/40/52 格 = 85.0/85.0/85.0/72.5/58.7/47.1 %），且**仍会失手**。

**这次顺手修掉的一个真 bug**：以前狙击手掠夺者只重写了 `scavTier()`（返回 SNIPER），**没有改保存用的 `tier` 字段**——于是它会按随机档位拿血量/护甲，被存档记成那个档位，**重载后甚至可能变回步枪手**（读取时用随机档位去枪池里找那把狙击枪，找不到就重新随机）。现在基类提供 `forcedSpawnTier()` 钩子，两只狙击手都返回 `ScavTier.SNIPER`，**生成与重载都以它为准**，老存档里的狙击手也会被自动纠正。

**狙击手村民的两处差异（都在 `SniperVillagerEntity` 里，各一行）**：
- **Brain 停车**：村民是 `Brain` 生物，父类本来只在"有目标"时停掉它（否则 `MoveToTargetSink` 会覆盖枪械 goal 发出的路径）。换位期间狙击手**会清掉目标**（这是它能走完这段路的办法），而"没有目标"正好会把 Brain 叫醒并和刚设定的寻路打架——所以重写的 `customServerAiStep()` 把「正在换位」也算进停车条件。
- **不新增近战 goal**：它不贴脸。近战只是继承来的最后手段（手上没枪时才会用），这条与掠夺者狙击手一致。

**`sniper.enabled`**：以前这个键只写在文档里、代码里没人读。现在 `false` 会（a）让 `SniperBehavior` 整个停摆（不再蹲点/换位，但仍会用共享枪脑正常射击），（b）**拒绝两只狙击手的自然生成**（`[spawngate] REJECT … sniper.enabled = false`），手动召唤仍然可用。

**`/tarkovscav spawn sniper [pos]`**（`sniper` 是短别名，完整名 `sniper_pillager`；村民版是 `sniper_villager`）会额外打印
`sniper=holding post=… shotsHere=0/2 lastReason=none state=… entity=tarkovscav:sniper_pillager tier=sniper profile=veteran cap=0.85 gun=… capacity=… followRange=64`。
**`/tarkovscav test sniper`**：64 格内**每一只狙击手**（掠夺者与村民都算，通过 `SniperMob` 而不是类列表）的同一份报告 + 与你的距离。

| Key | Default | Meaning |
| --- | --- | --- |
| `sniper.enabled` | `true` | 总开关：`false` 停掉蹲点/换位逻辑**并**拒绝自然生成（手动召唤仍可用） |
| `sniper.followRange` | `64.0` | 索敌距离（步枪兵 35） |
| `sniper.discoveredRange` | `24.0` | 触发②的"被发现"距离 |
| `sniper.shotsBeforeMove` | `2` | 触发③：同位置开几发就走 |
| `sniper.minPostDistance` | `16.0` | 新位最小距离 |
| `sniper.closeRange` | `8.0` | 触发④ / 近身后撤距离 |
| `sniper.moveSpeed` | `1.1` | 换位时的移动速度 |
| `sniper.relocateTimeoutTicks` | `200` | 走路超时后放弃并就地蹲下 |
| `sniper.minSpawnDistanceFromPlayer` | `32.0` | 自然生成与玩家的最小距离 |
| `sniper.preferHighGround` | `true` | 是否要求高地生成 |
| `sniper.minElevation` | `4` | 高出周围多少格算"高地" |
| `sniper.spawnWeight` | `1` | 掠夺者狙击手的自然生成权重（生物群系修饰器里同为 1）；0 = 不自然生成 |
| `sniper.villagerWeight` | `1` | 狙击手村民的自然生成权重（生物群系修饰器里同为 1，不高于掠夺者）；0 = 不自然生成 |

### 5s. 实体注册完整性 — 每个实体都必须有客户端渲染器（2026-09-23 崩溃的教训）

**发生了什么**：狙击手掠夺者上线后，用户刚放出它就**客户端崩溃**：

```
java.lang.NullPointerException: Cannot invoke
  "net.minecraft.client.renderer.entity.EntityRenderer.shouldRender(net.minecraft.world.entity.Entity, ...)"
  because "entityrenderer" is null
	at net.minecraft.client.renderer.entity.EntityRenderDispatcher.render(EntityRenderDispatcher.java:127)
	at net.minecraft.client.renderer.LevelRenderer.renderLevel(LevelRenderer.java:1199)
```

**根因**：`tarkovscav:sniper_pillager` 在 `ModEntities` 里注册了、能生成、能抽枪、能喊话（`[gunai] 狙击手掠夺者 equipped sniper/jak:jak_tyrant`），但 `ClientSetup` 里**没有给它注册渲染器**——`EntityRenderDispatcher.getRenderer()` 返回 `null`，于是它第一次进入视锥的那一帧就 NPE。**"注册了实体"和"能画出来"是两份名单，之前只检查了前一份**。这不是模型缺失、也不是紫黑方块，而是直接崩客户端，所以任何"缺资源只是不好看"的直觉在这里都是错的。

**修法**（`ClientSetup`）：

| 手段 | 内容 |
| --- | --- |
| 一个入口 | 所有实体渲染器注册都走 `ClientSetup.registerAll(...)`，两种 `useGeckoModel` 分支都在里面决定 |
| 家族循环 | 同类身体的实体（掠夺者 + 狙击手掠夺者）**一次调用、一个循环**传给 `illagerRenderers(...)`：`illagerRenderers(event, useGecko, ModEntities.GUNNER_PILLAGER, ModEntities.SNIPER_PILLAGER)`——新增一个"同族"实体只需在**这一处**加名字，不可能只加进一个分支 |
| 记账 | 每条注册路径都记下 type id 到 `RENDERED` 集合 |
| 启动自检 | `FMLLoadCompleteEvent` 里**反射遍历 `ModEntities` 的所有 `RegistryObject<EntityType>` 字段**，任何一个不在 `RENDERED` 里就 `LOGGER.error`（写清楚"会在 `EntityRenderDispatcher.render()` NPE"以及怎么修），最后打印一行 `[client] entity renderers registered: N/N` |

也就是说：**以后漏注册不再是"崩客户端"，而是启动时一行红色日志 + 一个 `N/N` 数字**（N 与总数不相等就是有问题）。这条自检在客户端启动时执行，服务端不碰。

**闸门**：`tools/selftest_entity_registry.js`（已进 `selftest.ps1`）。它枚举 `ModEntities` 里的**每一个**实体类型，并逐项断言：客户端渲染器、属性注册（`EntityAttributeCreationEvent`）、`SpawnPlacements`、刷怪蛋物品、蛋模型 `models/item/<id>_spawn_egg.json`、中英 `entity.` / `item.` 语言键、创造栏条目、生物群系修饰器里的生成项（权重 > 0，且若 `Config` 里有对应的 `*_SPAWN_WEIGHT` 键，**两者数值必须一致**——JSON 是游戏真正读的，配置键是给人改的，漂移就是文档 bug）。闸门会把**每个实体的逐项状态打表**，所以"清单里第几个缺什么"一眼可见。

**反向验证（证明这条闸门真的能抓这次的 bug）**：把 `illagerRenderers(...)` 调用里的 `ModEntities.SNIPER_PILLAGER` 临时去掉后运行闸门 → 3 条 FAIL，表格里 `tarkovscav:sniper_pillager` 的 renderer 列显示 `MISSING`：

```
FAIL  with the family members listed at the one call site
tarkovscav:sniper_pillager            MISSING   ok  ok  ok  ok  ok  ok  w=3
FAIL  every one of the 4 entity types is complete  tarkovscav:sniper_pillager: renderer
FAIL  the sniper pillager (the 2026-09-23 crash) has a renderer
```

### 5t. 玩家歪头 / peek-lean（长按 Q/E 从掩体侧身，射击也从歪出去的那一侧出）

用户原话：「给玩家做点小功能，**长按 e 键或者 q 键像 FPS 一样朝着一边歪头**。**视角会倾斜一些**，**TaCZ 武器和箭 攻击会从你歪头一点的方向射出来**。」

**怎么用**：按住 **Q = 向右歪**、**E = 向左歪**（都能在 选项→控制→武装暴徒 里改），松开自动回正。`client.leanEnabled = false` 时整个功能完全惰性。

> **2026-09-23 修正（用户回报）**：Q/E 一开始是**反的**（Q 曾经是向左歪），现在 **Q=右、E=左**；改的是**键位映射**（`LeanClient` 里两个 `KeyMapping` 的 GLFW 键码对调），**lean 数学（偏移沿右向量、roll 与头部同向）一个字没改**。若你觉得"歪头方向/倾斜方向还是反的"，用下面两个独立开关微调，不用改代码：
>
> | 键 | 默认 | 作用 |
> | --- | --- | --- |
> | `client.leanInvertOffset` | `false` | **只翻横移方向**（相机与枪口一起翻） |
> | `client.leanInvertRoll` | `false` | **只翻视角 roll**（横移感觉对、只有地平线倾斜反了时用这个） |
>
> **按键 → 偏移方向 → roll 符号对照表**（`lean` 为内部值，正 = 玩家右侧）：
>
> | 按键 | `lean` | 偏移量（世界方向） | `roll` |
> | --- | --- | --- | --- |
> | Q（右歪） | `+1` | 沿**右向量** `(-cos yaw, 0, -sin yaw)` × 0.6 | `-`（12° × −1，地平线随头部倾） |
> | E（左歪） | `-1` | 沿**左向量** × 0.6 | `+`（12°） |
> | 两个键同时按 | `0` | 无 | 无 |
> | `leanInvertOffset=true` | — | 整列取反 | 不变 |
> | `leanInvertRoll=true` | — | 不变 | 整列取反 |

#### 按键：Q/E 是原版键，所以必须"按住时不触发原版动作"
Q 是原版的**丢弃物品**、E 是**打开背包**。长按必然误触发，所以：

| 原版动作 | 怎么被压住 | 为什么这样做 |
| --- | --- | --- |
| **按下** | `InputEvent.Key` 的 PRESS 里立刻**吃掉 `options.keyDrop` / `options.keyInventory` 的点击**并**记下按下时刻** | 原版是在**按下那一瞬间**执行动作的，而"这是轻点还是长按"要到松开才知道——所以必须先把点击拿走，再由我们决定要不要重放 |
| **轻点松开（< `tapThresholdTicks`，默认 5 tick = 250ms）** | **我们代为执行原版动作**：E → 打开 `InventoryScreen`（与 `Minecraft#handleKeybinds` 那条一致）；Q → `player.drop(false)` 丢**一个**（旁观者不丢），**只丢一次** | "短按才会开"是用户要的语义；重放在**松开时**发生，所以不会和原版抢 |
| **长按（≥ 阈值）松开** | **什么都不做**（纯 peek） | "长按不开"；按住期间原版动作也早就被吃掉了 |
| **歪头起始时机** | `client.leanStartMode = immediate`（默认）按下即开始歪——手感跟手，代价是"轻点一下"会有极短的一次歪头、松手立刻回正；`afterThreshold` 则要按住到阈值才歪，轻点完全不歪 | 两种都可用，配置切换 |


`client.leanSuppressVanillaKeys = false` 可以整体关掉这两条压制（完全恢复原版）。**"只禁用被我们占用的那两个键"是按当前键位算的**：只要你把歪头键改绑到别的键，Q/E 立刻恢复原版（这条被闸门断言）。另外服务端还有一道兜底：`LeanServerEvents#onItemToss` 在收到"歪头中"的玩家丢东西时会取消这次投掷——**并且把物品放回背包**（见下）。

> **一个坑，写在这里免得以后有人踩**：Forge 1.20.1 **没有** `PlayerEvent.ItemTossEvent`；真实事件是 `net.minecraftforge.event.entity.item.ItemTossEvent`，它在 `Player#drop(ItemStack, boolean, boolean)` 里触发，此时 **`ItemEntity` 已经建好、物品已经从背包里扣掉了**。所以只 `setCanceled(true)` 会把物品**销毁**——必须自己把 stack 加回背包（背包满则走 `Containers.dropItemStack` 掉在脚下，**绝不能再走 `Player#drop`**，否则事件会再次触发，无限递归）。

#### 视角：只动客户端相机，**绝不动玩家实体**
- 横向偏移 + roll（默认 **0.6 格 + 12°**，`client.leanMaxOffset` / `leanRollDegrees`），进/出都平滑（`leanSpeedTicks = 5` tick 到位），左右同时按则**互相抵消**（视为 0）。
- **靠墙不穿视**：从**相机当前位置**沿偏移方向做方块碰撞检测，命中就把偏移**缩短到离墙 0.1 格**；完全没空间时偏移为 0（视角只剩 roll）。
- **服务端玩家不动**：玩家的坐标、eye height、hitbox 一个字节都不改（闸门里有专门断言：歪头代码里不允许出现 `player.setPos / setDeltaMovement / setBoundingBox / refreshDimensions`）。所以歪头**不能**用来把 hitbox 挪出子弹、也不能挤过缺口或看穿不该看到的墙——它只是**眼睛**移动。
- 相机位置的横移需要 `Camera#setPosition(Vec3)`，它在原版里是 `protected`。**2026-09-23 改成 access transformer（AT）**（`src/main/resources/META-INF/accesstransformer.cfg`，在 `build.gradle` 里用 `accessTransformer = file(...)` 声明），**彻底删掉了原来那条"按签名反射、失败静默降级"的路**——那次静默降级正是"歪头看起来只是转了一下、没有位移"的嫌疑来源。
  - AT 那一行是 `public net.minecraft.client.Camera m_90581_(Lnet/minecraft/world/phys/Vec3;)V`：**SRG 成员名**（对着 `srg_to_official_1.20.1.tsrg` 查的：`m_90581_` → `setPosition`），因为运行时用的是 SRG 名；而 **`.cfg` 里不能有 `#` 注释**——Forge 的解析器会直接报 `Invalid access transformer line`（我们第一次就这么被构建拦下来了），所以解释写在 `build.gradle` 的注释里。
  - **验证顺序（都可复跑）**：① 构建期 ForgeGradle 会把我们的 AT 应用到 dev 类上（日志 `JAR transformation complete`），**编译通过本身就是证明**——没有 AT 的话 `protected` 方法根本编译不过；② 产物里必须有 `META-INF/accesstransformer.cfg` 且内容仍是 SRG 那一行；③ `javap -c` 反查 `LeanClient`：必须是**直接** `invokevirtual net/minecraft/client/Camera.m_90581_(Lnet/minecraft/world/phys/Vec3;)V`，且**不能出现** `java/lang/reflect`/`Method.invoke`。这三条都写进了闸门（第 3 节）并在本次交付里逐条核过。
  - 万一 AT 在运行时没生效，`camera.setPosition` 会抛 `IllegalAccessError` → **catch 住 + ERROR 一行**，`/tarkovscav client state` 显示 `cameraSlide=unavailable`（**响亮地降级**，不再静默）；正常时应显示 `cameraSlide=at`。

#### 射击：弹道起点跟着歪，方向**故意不修**
- 钩子：`EntityJoinLevelEvent`，**只在服务端**，只处理 **`owner` 是服务端玩家**的 `Projectile`。TaCZ 的子弹类 `EntityKineticBullet` **就是** `Projectile`（对 `libs/tacz-1.20.1-1.1.8-hotfix.jar` javap 确认：`extends net.minecraft.world.entity.projectile.Projectile`——顺带说明**没打包也没改 TaCZ**），所以同一个钩子覆盖 **TaCZ 枪 + 弓 + 弩**，一行 TaCZ 代码都不用碰。
- 位移量 = **和相机完全同一个函数** `LeanMath.offsetFor(yaw, lean, leanMaxOffset)`（闸门断言两处调用同源，所以"相机偏多少、弹道起点就偏多少"是**按构造成立**的，不是两个常量碰巧相等）。
- **方向不动**：相机横移 0.5 格后，准星仍然画在屏幕正中，也就是"从偏移后的眼睛沿原朝向前看"。把弹道起点平移 `o`、速度不变，得到的正是这条射线的**平行副本**，所以**子弹去的正是歪头后准星指的地方**；同时"两束平行射线相距 `o`，打到墙上也相距 `o`"——这就是验收要测的"弹着点横移 ≈ 0.4–0.6 格"（闸门里对 1/2/4/6/8/20/50 格都算出 **0.50**）。**反过来**去"修正速度以命中未偏移视线的落点"会让子弹偏离准星，所以**刻意不做**。
- 起点位移同样经过**靠墙裁剪**（从子弹出生点算），所以贴墙时不会把子弹生成到墙里。
- **只在服务端做的代价（必须说清）**：射手自己在客户端还会生成一份本地子弹做曳光（TaCZ 的客户端预测），服务端的权威副本带的是**偏移后**的位置。如果两边都偏移，携带服务端 spawn 数据的那一份会被**偏移两次**；只偏移客户端又会让曳光去的地方和真正结算伤害的地方不一致。所以选择**只偏移服务端**：其他人从第一帧看到的就是歪出去的弹道、**伤害结算也是歪的**，代价是**射手自己**的曳光可能在下一个包到达前的极短时间内还从原来的枪口出发。**真机观感必须由用户确认**（歪头打墙，弹着点应比站直时横移约 0.5 格）。

#### 配置键（都在 `[client]`）
| Key | Default | Meaning |
| --- | --- | --- |
| `leanEnabled` | `true` | 总开关；false = 键位无作用、不压制原版键、不偏移弹道 |
| `leanMaxOffset` | `0.6` | 满歪时相机（与弹道起点）横移的格数（0–1.5）；**同一个值同时决定两者** |
| `leanInvertOffset` | `false` | 只翻横移方向（觉得左右反了用它） |
| `leanInvertRoll` | `false` | 只翻视角 roll（只有倾斜方向反了用它） |
| `leanRollDegrees` | `12.0` | 满歪时视角 roll（0–45；0 = 只平移不倾斜） |
| `leanSpeedTicks` | `5` | 从正到满（以及回正）用多少 tick |
| `leanSuppressVanillaKeys` | `true` | 拦截被歪头键**占用的**原版键（短按由我们重放原版动作、长按不触发）；改绑歪头键后这些键自动恢复；`false` 则完全不干预 |
| `tapThresholdTicks` | `5` | 按住多少 tick 才算"长按/peek"；**短于它 = 轻点 = 原版动作** |
| `startMode` | `immediate` | `immediate`（按下即歪）/ `afterThreshold`（到阈值才歪） |
| `replayVanillaOnTap` | `true` | 轻点是否由我们重放原版动作（false = 轻点也什么都不做，即上一版行为） |

**就地验证**：`/tarkovscav client state` 会多打一行
`lean=0.00 (left=false right=false) maxOffset=0.60 roll=12.0 speed=5t invertOffset=false invertRoll=false tapThreshold=5t startMode=immediate replayOnTap=true holding=false suppressVanillaKeys=true cameraSlide=at`。
**肉眼判据（2026-09-23 新增）**：**贴墙歪头**时，枪口/准星应当**真的绕过了墙角**（能看到墙角另一侧的东西），并且 `client state` 的 `cameraSlide=` 必须是 **`at`**（若显示 `unavailable` 就是 AT 没生效，把那一行发我）。**轻点 E 应打开背包、按住 E 应歪头且不开背包、轻点 Q 应丢出一个、按住 Q 应不丢**（2026-09-23 用户改定的语义）。

**闸门**：`tools/selftest_lean.js` —— 键位与默认值、两条压制路径与开关、`leanEnabled=false` 惰性、**服务端不许动玩家**、相机与弹道同源、平行射线在 7 个距离上都相距 0.5、靠墙裁剪的算术（0.3 格墙 → 0.2、0.05 格墙 → 0）、以及配置键 + README（AssetTest 要求）。

### 5y. 部队与优质单位（USEC 村民 / BEAR 掠夺者 / 优质村民 / 优质掠夺者）

用户原话：「村民和掠夺者都新增一个**部队村民 / 部队掠夺者**，分别用 **USEC 和 BEAR** 的语音，然后……再都加个变体，**优质村民 / 优质掠夺者**，用**优质系列**语音，同时把他们的**血量增加到 40**，并且**随机生成身上穿着 1-6 甲**，1 甲可以**减免 10% 伤害**，6 甲可以**减免 60% 伤害**。」

| 实体 | id | 父类 | 阵营 | 语音家族 | 血量 | 精度档位 |
| --- | --- | --- | --- | --- | --- | --- |
| 部队村民 | `tarkovscav:usec_villager` | `GunnerVillagerEntity`（村民系） | 村庄（友好） | `usec` | **40** | veteran 0.85 |
| 部队掠夺者 | `tarkovscav:bear_pillager` | `GunnerPillagerEntity`（灾厄系） | 灾厄（敌对） | `bear` | **40** | veteran 0.85 |
| 优质村民 | `tarkovscav:elite_villager` | `GunnerVillagerEntity`（村民系） | 村庄（友好） | `elite` | **40** | elite 0.90 |
| 优质掠夺者 | `tarkovscav:elite_pillager` | `GunnerPillagerEntity`（灾厄系） | 灾厄（敌对） | `elite` | **40** | elite 0.90 |

**父类是承重的，不是风格问题**：`ClientSetup` 把村民家族（`gunner_villager` / `sniper_villager` / `usec_villager` / `elite_villager`）统一注册成 `GunnerVillagerRenderer`，所以一个叫 `*_villager` 却继承掠夺者的实体，会在**被渲染的那一刻**以 `ClassCastException` 崩客户端；而且"村民"这半边的东西（职业后缀名字、村庄阵营、原版村民音效、`Villager` 行为）全都来自 `Villager` 这个父类。闸门按 **实体名 ↔ 父类 ↔ 渲染器家族** 三者一致来断言（`tools/selftest_entity_registry.js` 第 12 节）。

**复用**：模型/渲染（走"一次调用+循环"那套，不会再漏注册）、举枪姿态、`GunBrain`/掩体/换弹/压制/游走、精度机制、阵营与警戒共享、放置台转化、击杀播报、配件池——全部继承，没有分叉。名字走 `EntityNames` 组合（村民系带职业后缀，不会有原始键上屏）。

#### 1–6 级甲（**方案 A：不穿原版甲，也不吃原版护甲点**）
生成时**随机**滚一个 1..6 的等级，**写进实体持久数据**（`tarkovscav:armorClass`，随存档持久化，重载不丢也不重滚），伤害在 `LivingHurtEvent` 里按 **`10% × 等级`** 减免：**1 级 10% … 6 级 60%**。等级可在 `/tarkovscav spawn`、`/tarkovscav test sniper`、`/tarkovscav debug` 的输出里看到（`ArmorClass.describe()`）；`armorEnabled=false` 时等级仍然滚、仍然显示，但**不减免**。

**"不双重减免"要成立，只把甲拿在手里是不够的**：`LivingHurtEvent` 触发在**原版护甲吸收之前**，而每个等级档（`[tiers]`）本身带**原版护甲点**（霰弹 2 / 步枪 4 / 狙击 1，见 `tiers.*.armor`）。如果不处理，这 4 个单位会先吃一次原版护甲减免、再吃一次我们的百分比，**6 级甲就会超过 60%**。所以四个单位通过 `forcedArmorPoints()=0` 把自己钉在**0 护甲点**（和 `forcedMaxHealth()=40` 同一个钩子思路），我们的百分比是**唯一**的减免来源——这也正是闸门里"逐级仿真 = 10%..60%、绝不超过 60%"能成立的前提。

**为什么不用"看得见的甲"（方案 B）**：如果给他穿上对应等级的原版护甲（或者保留原版护甲点），原版曲线会**先减一次**，我们的百分比再减一次，**总减免会超过 60%**（把 60% 叠到 20 点护甲上，理论上限是 `1-(1-0.6)(1-0.8)=92%`，闸门会把这个数打印出来）。要做到"总减免恒等于 10%×等级"就得精确建模原版护甲曲线与每种伤害类型，只能**平均吻合**，所以默认不做（用户没有要求）。若要开启，前置是：① 一个 `troops.cosmeticArmor` 开关；② 一套按伤害类型反解原版护甲的仿真与逐级断言；③ 装饰甲的掉落/生成清理规则。

#### 配置键（`[troops]`）
| Key | Default | Meaning |
| --- | --- | --- |
| `armorEnabled` | `true` | 是否启用等级减免；false 时等级照滚照显示但不减伤 |
| `armorReductionPerClass` | `0.10` | 每级减免比例（0.10 → 1 级 10%、6 级 60%） |
| `minArmorClass` | `1` | 生成时能滚到的最低等级 |
| `maxArmorClass` | `6` | 生成时能滚到的最高等级 |
| `usecVillagerWeight` | `1` | 部队村民的自然生成权重（普通武装村民是 2；与 `add_scavs.json` 相等，见 §7i） |
| `bearPillagerWeight` | `1` | 部队掠夺者的权重（普通武装掠夺者是 2；与 `add_scavs.json` 相等） |
| `eliteVillagerWeight` | `1` | 优质村民的权重（**四者中最低**，刻意稀有） |
| `elitePillagerWeight` | `1` | 优质掠夺者的权重（同上） |

#### 语音池（结构在 ⑩ 接好，音频在 ⑪ 落盘）
每个实体回答自己的 `voiceFamily()`（`usec` / `bear` / `elite`，老单位是 `shared`），`VoicePools` 按 `<family>_<category>` 取池（例如 `usec_contact`），**该家族还没有片时回退到共享池**——所以 ⑩ 交付时四个单位仍说老句子，⑪ 把 ogg 放进去后**同一份代码立刻开始说自己的话**，不用改一行。**隔离规则**：类别永不跨家族（USEC 只会问 `usec_*`），闸门按实体类型断言这张表。优质系列按 **(A)** 落地（用自己的 `hurt` 当 death、`suppressed/fire` 当 chatter、reload 不配），用户若改选 (B) 只改这张表一行。

#### 判据（用户肉眼确认）
1. 四种名字正确：部队村民 / 部队掠夺者 / 优质村民 / 优质掠夺者（村民系带职业后缀）；
2. 血量 **40**（打它要打更久）；
3. `/tarkovscav spawn usec_villager`（或任意一个）的输出里能看到 **armor=class N (N0% less damage)**；
4. ⑪ 之后：**彼此只说各自的语音**（USEC 说 USEC、BEAR 说 BEAR、优质说优质）。

### 5x. 「村民的名字显示成 `entity.tarkovscav.gunner_villager.weaponsmith`」—— 名字解析与原始键兜底

**现象**（用户截图）：击杀播报里出现 `entity.tarkovscav.gunner_villager.weaponsmith [BAS-P 微型冲锋枪] 叛徒 entity.tarkovscav.gunner_villager.none`。

**根因**：原版 `Villager#getTypeName()` 是**按职业拼键**的——`Component.translatable(实体描述键 + "." + 职业)`。我们的武装村民/狙击手村民继承它，于是生成 `entity.tarkovscav.gunner_villager.weaponsmith` / `…none` 这种**我们从没写过的键**，界面就把原始键名打了出来（击杀播报、名字牌、任何取名字的地方都会）。

**修法（两半）**：
1. **`GunnerVillagerEntity#getTypeName()` 换成我们自己的组合**（`EntityNames.villagerTypeName`）：**基础名用我们自己的实体键**（`entity.tarkovscav.gunner_villager` = 武装村民 / `sniper_villager` = 狙击手村民），**职业后缀复用原版职业键**（`entity.minecraft.villager.weaponsmith` = 武器匠），拼成 **`武装村民（武器匠）`**；`none`/没有职业/其它模组加的职业 → **只显示基础名**，所以 30+ 职业组合不需要任何新键。狙击手村民**继承**这个实现，名字同样自动正确。
   - 职业键是一张**字面量表**（14 个 `entity.minecraft.villager.*`），**不是字符串拼接**——AssetTest 会解析代码里出现的每个键，拼接出来的键它看不见（这个坑我们踩过两次，第一次就被它当场拦下）。
2. **原始键兜底（`EntityNames.safeName`）**：任何要上屏的名字，如果**看起来是没翻译的键**（以 `entity.`/`item.`/`block.`/`effect.`/`subtitles.`/`translation{` 开头，或含有 `tarkovscav.`），就换成**实体类型路径的可读形式**（`gunner_villager`）并**按实体类型 WARN 一次**；**绝不让原始键出现在屏幕上**。击杀播报的两个名字、`/tarkovscav debug`、`/tarkovscav test grenade|sniper|mods|rack` 的输出都走它。

**一个必须说清的边界**：击杀播报的名字是**服务端**渲染成字符串再下发的，而**专用服务端没有语言文件**（`Component#getString()` 拿不到翻译；单机因为同 JVM 共享客户端语言所以没问题）。所以专用服上名字会走兜底路径（可读的实体类型名 + WARN）而不是原始键——不难看，但也不是翻译名。要变成真翻译名，得把"实体类型 + 职业"作为**数据**下发、由客户端翻译（下一步可做的改进）；本次先把"原始键上屏"这条硬 bug 修掉。

**顺手确认过的清单**（谁取名字、用什么）：
| 位置 | 用的名字 | 现在的结果 |
| --- | --- | --- |
| 击杀播报 | `getDisplayName()`（服务端渲染） | ✅ 走 `safeName`，村民显示「武装村民（武器匠）」 |
| 名字牌 | 客户端 `getTypeName()` | ✅ 客户端有语言，直接是翻译名 |
| 原版死亡信息 | `getDisplayName()` | ✅ 同上 |
| `/tarkovscav debug`、`test sniper/mods/grenade/rack` | 输出里的实体名 | ✅ 全部改为 `safeName` |
| 日志（`[killfeed]`/`[grenade]` 等） | 同上 | ✅ 同样安全（不是屏幕，但保持一致） |
| 其余实体（暴徒/掠夺者/两只狙击手） | `getTypeName()` 默认实现 = 实体描述键 | ✅ 这些键**我们都写过**（实体注册闸门逐项断言），且它们**没有职业/变体**，不可能拼出不存在的键 |

**闸门**（`tools/selftest_entity_registry.js` 第 11 节）：`getTypeName()` 覆写存在且调用组合器；**职业键是字面量表**（≥14 条）；`safeName` + `looksUntranslated` + WARN 一次存在；击杀播报与 debug/test 都用它；并且**用真实语言文件仿真全部 15 种职业（含 `none`）**，逐条断言结果**不以 `entity.` 开头、不含 `tarkovscav.`**；最后给出实体清单对照：每个我们自己注册的实体都有名字键，且只有 `Villager` 系有变体名字。

### 5w. 「scav 的头/背包是一大块纯黑」—— 物品绘制留下的贴图状态（2026-09-23 第二次渲染缺陷）

**现象**：一只暴徒站在室内，**头部 + 背部上方是一大块纯黑的多面体**（棱角分明、像背包/头被拉伸或翻转后的体块），躯干、腿、枪都正常带贴图。

**根因（假设 1 成立，证据如下）**：**贴图绑定**被外来渲染器（TaCZ 的枪）改坏后没有修回来，而这正好命中我们自己的两处缺口：

| 证据 | 内容 |
| --- | --- |
| **物品层是在骨骼遍历途中被调用的** | GeckoLib `GeoRenderer.renderRecursively`（源码 `_recon/geckolib-4.8.4-sources.jar` 的 `GeoRenderer.java:245`）在**递归到某一根骨时**调用 `applyRenderLayersForBone(...)`（同文件 `:253`）→ 我们的 `GunInHandGeoLayer` 是在**锚点骨**上、**遍历还没走完**时画枪的，所以锚点之后画的骨全部继承 TaCZ 留下的状态 |
| **锚点骨 = `RightHandLocator`** | `client.gunAnchorBone` 的默认值（`Config.java`：`define("gunAnchorBone", "RightHandLocator")`）。按 `scav.geo.json` 的文件顺序（GeckoLib 就是按它遍历、父在子前）**排在其后的骨**是：`RightHandDMZ, LeftArm, LeftForeArm, bone999, LeftHand, LeftHandDMZ, LeftHandLocator, **Bag(39)**, **AllHead(40)→Head(41)→…→hat3(59)**, RifleLocator, DownBody, Leg(62)…rightfoot, PistolLocator` —— **背包与头正好在锚点之后**，这就是"为什么只有头/背包（和左臂那一路）" |
| **guard 的贴图只被"读"过、从没被"写"回** | `RenderStateGuard` 早在第 89 行就把 `GL_TEXTURE_BINDING_2D` 存进 `this.texture`，但 `restore()` 里只用它打日志、**没有恢复**（原来只恢复 stencil/depth/blend/cull）。TaCZ 用**裸 GL** 绑贴图，于是"原始 GL 绑定"与"Minecraft 的贴图缓存"不一致；下一次 `RenderType#setupRenderState` 发现**缓存说"我的贴图已绑好"就跳过绑定**，后面的骨于是采样 texture 0 = **纯黑**（cutout 边缘锐利），而不是"看到别的实体的贴图"——这正是它与 §5k 那次 stencil 泄漏**表现不同**的原因，也解释了为什么 §5k 的修法没能修好这次 |

**修法（三处，全在客户端）**：
1. `RenderStateGuard.restore()` 现在**真的恢复贴图绑定**：先 `activateTexture(this.activeTexture)` 把进入时的**纹理单元选回来**（TaCZ 是裸 GL 调用，可能把活动单元留在别处），再 `GlStateManager._bindTexture(this.texture)` 写缓存、**无条件** `GL11.glBindTexture` 写驱动（`_bindTexture` 在缓存已经等于目标时会跳过 GL 调用，而"缓存对、驱动错"正是这次黑块的状态）；并新增 `resyncTextureBinding()`：把**原始 GL 绑定同步成 Minecraft 缓存里的那个 id**（`RenderSystem.getShaderTexture(0)`），"缓存说已绑定、GPU 却在采样别的"这种状态无法再出现。同一轮从朋友的旧快照移植了另外四条恢复（双面模板、stencil clear 值、活动单元 + 单元 0..2、12 个 sampler，见 §5k）。
2. `GunInHandGeoLayer` 的**两次外来绘制**（主手 + 双手托枪副本）都改成 **`snapshot → try → finally { restore() ; rebindTexture(模型贴图) ; forceAlwaysPassStencil() }`**：画完枪**显式重绑模型自己的贴图**（`RenderStateGuard.rebindTexture(this.renderer.getTextureLocation(animatable))`）。
3. 所有 GL/贴图写入仍然**只在 `RenderStateGuard` 一个文件**里（层也走 guard 的静态方法），"客户端只有一处碰 GL 状态"这条不变量继续成立。

**闸门**（`tools/selftest_texture_state.js` 第 7 节）：guard 恢复贴图 + `resyncTextureBinding` 存在；层对**两次**绘制都做了 `snapshot/try/finally` 且**重绑模型贴图**；没有任何地方故意绑 texture 0；并且**从 `scav.geo.json` 重算骨骼顺序**，断言 `Bag/Head/LeftArm/Leg` 确实排在 `RightHandLocator` 之后——"为什么只有这几块"变成一条可复跑的断言。同一文件的第 8 节把 §5k 移植来的四条恢复（双面模板、stencil clear 值、活动单元 + 单元 0..2、12 个 sampler）与恢复顺序也钉在源码上，并由 `tools/spike/gl/RenderStateGuardLiveTest.java`（隐藏窗口真 GL 往返，见 §5k）在驱动上实测。

#### 假设 2/3（NaN/无穷值）：已加检测，但**尚未证明**是本次原因
`PoseWriters` 在 `logPoseWriters=true` 时**遍历全部已注册骨骼**，任何 `rot/pos/scale` 出现 NaN/Inf 就 **ERROR 一次**（按 生物类型+骨+字段 去重）并打印**是哪个 writer 写的**：
`[pose] NON-FINITE Head.rot = (…,…) on tarkovscav:scav - … Writers: …`。**日志里出现 NON-FINITE = 假设 2/3；没有 = 排除**。
作者全部 Molang 表达式的数值域穷举**尚未做**（需要接上 `selftest_pose_writers` 的表达式解释器）；本次先修证据最硬的贴图路径，用户回报后再决定是否补穷举。

#### 给用户的即时二分（一条条试，能立刻缩小范围）
| 动作 | 排掉什么 |
| --- | --- |
| `client.poseSource=code` + `client.molangVariables=off` | **假设 2/3**（Molang 喂值/表达式算出 NaN） |
| `client.modelRenderType=zOffset` | 渲染类型/alpha 测试那一类（cutout 之外的对照） |
| `client.torsoYawShare=0` | 躯干份额造成的姿态问题 |
| `client.logPoseWriters=true` | 采 `[pose]` 日志（含上面的 NON-FINITE 检测），把日志发我 |
| 上面都不变且 `[pose]` 无 NON-FINITE | 只剩贴图路径 → 本节修复应当直接解决 |

### 5v. 手雷与闪光弹（3 手雷 + 2 闪光弹，暴徒也会用、也会来领）

用户原话：「做 **3 个手雷 2 个闪光弹**，同时**暴徒也可以用这些手雷**。（**默认不会破坏地形** 但是**手雷有破片**。）」+ 后续三条：闪光**要影响所有生物**（含我们自己的单位）、被闪后**乱开枪**、手雷**可以放到军械台/放置台上**、**没手雷的暴徒会来领取**。

#### 五个投掷物
| 物品 | 中文名 | 引信 | 定位 | 配方 |
| --- | --- | --- | --- | --- |
| `frag_grenade` | 破片手雷 | 60t (3s) | **伤害主要来自破片**（24 条射线、单条 **14** 点、10 格衰减；爆炸本身只有 power 1.0） | 4 铁锭 + 1 火药 |
| `he_grenade` | 高爆手雷 | 80t (4s) | **爆炸伤害高、破片少**（power 4.0 + 8 条破片、单条 **8** 点） | 6 铁锭 + 2 火药 + 1 TNT |
| `smoke_grenade` | 烟雾手雷 | 40t (2s) | 半径 4 格、**15 秒**烟幕 | 2 白羊毛 + 2 火药 + 1 沙子 |
| `flash_grenade` | 闪光弹 | 40t (2s) | 半径 12 格；正对白屏约 5s，背对减半 | 6 铁锭 + 2 火药 + 1 荧石粉 |
| `flash_grenade_short` | 短引信闪光弹 | **20t (1.0s)** | 大约落地就炸、致盲时间是标准闪光的 **75%**（正对约 3.75s） | 2 铁锭 + 2 火药 + 1 荧石粉 |

**投掷**：按住右键**蓄力**（`throwChargeTicks=20` 满），投出速度 `0.6 → 1.6`（近丢/远投）；**蓄力期间引信照烧**（`cookWhileHolding=true`）——所以"炖雷"不是额外功能，而是算术的自然结果：拿久了它就在你手里炸。贴图是程序化生成的 16×16（`tools/make_grenade_textures.js`），模型/配方同理由 `tools/make_grenade_assets.js` 生成，两个脚本就是"做了哪些东西"的单一事实来源。

#### 默认不破坏地形，而且是"按构造"不破坏
`grenades.terrainDamage=false`（默认）时**根本不创建爆炸对象**：伤害由本模组自己结算，只有粒子和音效。这是刻意的选择（比用 `ExplosionInteraction.NONE` 更强）：
- 阵营规则必须**同时**作用于爆炸与破片，而原版爆炸不认识阵营；
- "方块零变化"因此是**按构造成立**的，不需要信任一个 flag。闸门断言：`false` 分支里**没有任何 `level.explode`**，`true` 分支才走 `ExplosionInteraction.BLOCK`。

#### 破片模型（伤害表由仿真算出）
以爆点为中心撒 **N 条均匀射线**（黄金角球面分布 + 少量抖动，同一次爆炸可复现）；每条**按 `fragmentStep`(0.5) 步进，遇到第一个方块就停**（所以墙是真实掩体）；途中**每个实体每颗雷最多被打中 `MAX_HITS_PER_ENTITY=3` 次**（身体会挡掉大部分）。单条伤害 `fragmentDamage × (1 − 距离/半径)`；护甲只挡掉 `(1 − armorPierce)` 那一部分：`final = damage × (1 − (1−pierce) × min(20,护甲)/25)`。

**伤害翻倍（2026 追加：「手雷伤害低了，增加一倍才合适」）**：一次性把**所有伤害源**翻倍——破片单条 `7 → 14`、高爆破片单条 `4 → 8`、**爆炸的整体系数 `6 → 12`**（新键 `grenades.blastDamagePerPower`，本来写死在代码里的 6.0）。`he.blastPower` **故意没动**：power 同时决定爆炸**半径**（`power × 2` 格），4.0 → 8.0 会把范围也翻倍（半径 8 → 16 格，8 格处从 0 变成 48 点），那不是"伤害翻倍"而是"换了一颗雷"。所以"翻倍"由 `blastDamagePerPower` 承担：**射程不变、每个距离上的伤害都是改前的 2.00 倍**。

**`MAX_HITS_PER_ENTITY=3` 保持不动**：它只在**贴脸 0.85 格以内**才会咬到（闭式解 `24·0.36/(4d²) > 3` ⇔ `d < 0.85`），而改为只翻倍单条伤害后，1 格处约 33 点、2 格处约 6 点——2 格已经能活，贴脸本来就该致命。调大上限会让"贴脸秒杀"更严重、调小会让远距离也跟着变弱，两头都不划算。

**期望伤害表**（`GrenadeBlast.expectedFragmentDamage` + `expectedBlastDamage`，闭式解 `命中数 ≈ N·r²/(4d²)`，`r=0.6`，与 24 条射线的蒙特卡洛仿真一致——闸门比对；**`/tarkovscav test grenade` 打印的就是这张表**，含 1/2/4/6/8 格五档）：

| 距离 | 破片手雷（24 条 × 14 点 + power 1 爆炸） | 高爆手雷（8 条 × 8 点 + power 4 爆炸） |
| --- | --- | --- |
| 1 格 | 33.22 | 47.18 |
| 2 格 | 6.05 | 37.15 |
| 4 格 | 1.13 | 24.22 |
| 6 格 | 0.34 | 12.06 |
| 8 格 | 0.09 | 0.02 |

**改前 / 改后对照**（同一套闭式解，改前 = 旧默认值 7 / 4 / 6；比值全部 **2.00**）：

| 距离 | 破片 改前 → 改后 | 高爆 改前 → 改后 |
| --- | --- | --- |
| 1 格 | 16.61 → **33.22** | 23.59 → **47.18** |
| 2 格 | 3.02 → **6.05** | 18.58 → **37.15** |
| 4 格 | 0.57 → **1.13** | 12.11 → **24.22** |
| 6 格 | 0.17 → **0.34** | 6.03 → **12.06** |
| 8 格 | 0.05 → **0.09** | 0.01 → **0.02** |

（**未计护甲**：表里是爆点结算的原始伤害，穿甲后还要按 `fragmentArmorPierce` 与 `min(20,护甲)/25` 再打折；四个部队单位另有 1–6 级甲减免，见 5y。上一版 README 这张表与闭式解**对不上**——因为它是在系数还被写死、且表里只算破片没算爆炸时抄下来的；本次由 `tools/selftest_grenades.js` 的闸门逐档重算后重写。想让破片更狠，把 `frag.fragmentCount` 调大即可。）

#### 闪光：**对所有生物**生效，被闪后**乱开枪**
- **生效对象**：玩家（白屏 overlay，`FlashOverlay`，按强度线性恢复）+ **所有生物**（原版 `Blindness`）——**包括我们自己的村民/暴徒/掠夺者/两只狙击手**，也**不分阵营**：闪光不看你站哪边（`flash.blindsMobs=true`；爆炸与破片仍然分阵营）。
- **行为 = 恐慌射击（PANIC 语义，复用 SUPPRESS 状态）**：致盲期间**不需要目标也能开火**——朝**最后一次真正看到目标的位置**（`GunBrain.lastKnownTargetPos`）打，没看到过就朝**随机方向**（±60°）打；**散布 × `panicSpreadMultiplier`（默认 8）**，射击间隔 `panicBurstTicks`（默认 4，TaCZ 自己的射速仍然生效）；致盲一结束就恢复正常索敌（有目标继续交战，没有回 IDLE）。
- **致盲期间不瞄准不使用目标**：`getTarget()` 不参与弹道计算（伤的是"记忆里的那个位置"），所以被闪的单位**不能靠锁定看穿闪光**。
- **允许打中自己人**：子弹是真实弹道，**这是有意为之**——被闪的代价就该是真的。README 与配置注释都写明了。
- `flash.panicFire=false` 可以退回"安静停火"的旧行为。
- **狙击手**：被闪时 `SniperBehavior` 的蹲点/换位逻辑让位给恐慌射击（`getTarget()` 为 null 时它会解除蹲点，这正好是恐慌射击需要的）——取舍：**被闪的狙击手会边打边"忘掉"自己的射击位**，这是可以接受的（它本来就看不见了）。

#### 手雷落地音效（⑪，2026 追加）
**来源**：B 站 `BV1j1cZeHEAt`（《塔科夫玩家最喜欢听的声音💀》，21s）的 **0:00–0:02**——用户指定"2 秒之前的就是
手雷音效"。抓取用 `tools/bili_audio.js`（公开接口 + 浏览器 UA + `Referer: https://www.bilibili.com/`，无需登录），
剪裁用 `tools/make_impact_clip.ps1`（与语音同一套规范：单声道 44.1 kHz、首尾裁静音、
`loudnorm=I=-16.5:TP=-1.0:LRA=11`、统一增益到均值 ≈ −16.8 dB 且**出厂文件**峰值 ≤ −1.0 dB）。
落盘后由 `tools/voice_emit.js` 写进 `sounds.json`、`voice_clips.txt` 清单、中英字幕与上面的清单表。

**触发点**：接在手雷实体的**落地/弹跳**上（`GrenadeEntity#onHit` 的 BLOCK 分支，速度大于静止阈值时），
不是引信也不是投掷——因为"手雷在哪儿"是靠**撞地声**判断的。防刷屏两道闸：**每颗雷最多 3 次** +
**两次之间 ≥ 8 tick**（`impactSoundMaxPerGrenade` / `impactSoundCooldownTicks`），且计数器**不写 NBT**
（区块重载后可以再响一次，这符合"一个东西掉下来"的直觉）。

**关于这段音本身（我没法"听"，所以给客观包络）**：`tools/wav_envelope.js` 报 0–2s 内有**两个**瞬态
——t≈0.07s（峰值 −2.3 dBFS，最响，带 0.25/0.39s 的余响）与 t≈1.85s（峰值 −3.9 dBFS）。我**按用户给的区间
整段 0:00–0:02 落地**；如果你觉得每次落地响两下太多，把区间改成 `0:00–0:01` 重跑一条命令即可：
`powershell -ExecutionPolicy Bypass -File tools/make_impact_clip.ps1 -Source tools/spike/work/voice_src/bili/BV1j1cZeHEAt.m4s -StartSeconds 0 -EndSeconds 1 -Name grenade_land -Event grenade_land -Sound effect/grenade_land -Note 'BV1j1cZeHEAt 0:00-0:01' -SubtitleZh '手雷：落地' -SubtitleEn 'Grenade: impact'`，然后 `node tools/voice_emit.js`。

**另一个视频（`BV19jrNB3EDo`）**：用户后来给了更精确的来源，所以那个视频**没有下载、没有剪任何片段**，
不占用任何预算，也没有接进游戏。

**版权**：这段音效属于第三方素材（B 站视频），**仅供本地私用，请勿随整合包公开发布**。

#### 手雷可以放在放置台/军械台上，没雷的暴徒会来领
- **接受判定**：`WeaponRackArmament` 新增 **`THROWABLE`** 分类（`item instanceof GrenadeItem`）。注意 `usable()` 对它返回 **false**：`usable` 的含义是"能拿它武装一个单位"，而手雷不武装任何人；`accepts()` 因此单独放行 `resupply()` 那一类。**创造军械台**照旧"模板不消耗"，所以它就是一个**无限手雷补给站**。
  > **曾经的假日志（已修）**：`WeaponRackTaker.tick` 过去用 `!armament.usable()` 当"不支持"的判据，于是台子上放着手雷时**每 tick** 都刷
  > `[rack] unsupported item tarkovscav:he_grenade … Accepted: … or a throwable (a grenade of this mod)`——同一句话里既说"不支持"又说"手雷是接受的"。
  > 现在只有 `UNSUPPORTED` 才走 `warnUnsupported`，`THROWABLE` **静默返回**（这条路本来就不该转换任何人，领雷走的是 `GrenadeResupplyGoal`）。
- **谁能取、来取什么**（新真值表；原有规则**不动**）：

| 来的单位 | 台上放的 | 结果 |
| --- | --- | --- |
| 未武装村民/掠夺者 | 武器 | 取走并转化（**原规则，未变**） |
| 未武装村民/掠夺者 | 投掷物 | **什么都不发生**（手雷不武装人） |
| 已武装持枪单位，背包满 | 任意 | 什么都不发生 |
| 已武装持枪单位，还有空位 | **投掷物** | **走过去领取一枚**（新 `GrenadeResupplyGoal`），**绝不碰台上的枪** |
| 已武装持枪单位，还有空位 | 武器 | 什么都不发生（它已经武装过了） |

- **投掷物库存**：照 `MobAmmoInventory` 的做法新建 `MobGrenades`——**按种类计数**存在生物的持久数据里（`tarkovscav:grenades`），投掷时**从库存扣除**（`MobGrenades.take`），所以"捡到两颗破片一颗闪光"是真实状态，扔的就是它捡到的那种。**不复制**：领取时先 `MobGrenades.add` 再 `rack.claim()`，`claim()` 返回空（被别的怪抢先）就把刚加的那枚**退回**（普通台取走即清空，创造台保留模板）。
- **人工投放路径（手测步骤）**：① 空手右键把一台放置台放在地上 → ② 对着它按 Q **扔一枚手雷上去**（吸收逻辑会收 1 枚）→ ③ 把一只**已武装**的暴徒（`/tarkovscav spawn scav`）放到 24 格内、并确保它当前没有目标 → ④ 它会走向台子、领取一枚，日志出现 `[grenade] <名字> resupplied <种类> from the rack at ... pouch now frag x1 (1/2)`。
- `grenades.rackPriority`：**台子只有一个槽位**，所以"同时有枪又有雷"在物理上不存在；这个键是用来在**多个台子**之间取舍的（`grenade` 默认=只去有投掷物的台；`nearest`=最近；`weapon`=只去放枪的台，用于测试"到了却拿不到"）。

#### 配置键
`[grenades]`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | 总开关；false = 不能投掷、没有怪物投掷、空中的雷会被拆除 |
| `terrainDamage` | `false` | 是否破坏地形（false = 自结算伤害、零方块变化；true = 原版 `BLOCK` 爆炸） |
| `throwChargeTicks` | `20` | 蓄满力需要的 tick |
| `minThrowSpeed` | `0.6` | 不蓄力的投掷速度 |
| `maxThrowSpeed` | `1.6` | 满蓄力速度 |
| `cookWhileHolding` | `true` | 蓄力时引信是否照烧（"炖雷"） |
| `friendlyFire` | `false` | 爆炸/破片是否伤害同阵营（true = 全打） |
| `playerSelfDamage` | `true` | **玩家自己**是否会被自己的雷伤害/致盲（true=会，false=免疫；两种行为见下） |
| `blastDamagePerPower` | `12.0` | 爆炸**本身**在贴脸处每 1 点 `blastPower` 的伤害（旧值写死在代码里 = 6.0，本次翻倍）。半径仍是 `blastPower × 2` 格，所以这个键**只改硬度、不改范围**；想让爆炸更大请改 `he.blastPower`（但那会连半径一起翻倍） |
| `impactSound` | `true` | 手雷**落地/弹跳**时是否播放撞击音效（`sounds/effect/grenade_land.ogg`，来源见下） |
| `impactSoundMaxPerGrenade` | `3` | **每颗雷**最多播几次撞击音（音长约 2 秒，不封顶会在斜坡上自我叠加） |
| `impactSoundCooldownTicks` | `8` | 同一颗雷两次撞击音之间的最小间隔（tick），防止滚动时连响 |

`[grenades.frag]` / `[grenades.he]` / `[grenades.smoke]` / `[grenades.flash]` / `[grenades.flashShort]`

| Key | Default | Meaning |
| --- | --- | --- |
| `frag.fuseTicks` | `60` | 破片手雷引信 |
| `frag.fragmentCount` | `24` | 破片射线数 |
| `frag.fragmentDamage` | `14.0` | 单条破片贴脸伤害（旧值 7.0，本次翻倍） |
| `frag.fragmentRadius` | `10.0` | 破片飞行距离（伤害线性衰减到 0） |
| `frag.fragmentArmorPierce` | `0.35` | 无视护甲的比例 |
| `frag.fragmentStep` | `0.5` | 射线步长（越小越准、越贵） |
| `he.fuseTicks` | `80` | 高爆引信 |
| `he.blastPower` | `4.0` | 原版爆炸力（爬行者 3、TNT 4）。**同时决定半径 = power × 2 格**，所以本次翻伤害没动它（见上面的说明） |
| `he.fragmentCount` | `8` | 高爆破片数 |
| `he.fragmentDamage` | `8.0` | 高爆单条破片伤害（旧值 4.0，本次翻倍） |
| `smoke.fuseTicks` | `40` | 烟雾引信 |
| `smoke.radius` | `4.0` | 烟幕半径 |
| `smoke.durationTicks` | `300` | 烟幕持续时间（15 秒）；**这是简化实现**：没有真实的视线遮挡判定，烟里的怪物只是被致盲（因此丢目标、不开枪），玩家只是视野被粒子挡住 |
| `flash.fuseTicks` | `40` | 闪光弹引信 |
| `flash.flashRadius` | `12.0` | 闪光有效半径 |
| `flash.flashIntensity` | `1.0` | 贴脸白屏强度 |
| `flash.playerBlindTicks` | `100` | 玩家贴脸致盲时长 |
| `flash.mobBlindTicks` | `120` | 生物贴脸致盲时长（=恐慌射击时长） |
| `flash.lookAwayFactor` | `0.5` | 背对爆点的时长/强度系数 |
| `flash.blindsMobs` | `true` | 是否致盲生物（含我们自己的单位） |
| `flash.panicFire` | `true` | 被闪后是否恐慌射击（false = 安静停火） |
| `flash.panicSpreadMultiplier` | `8.0` | 恐慌射击的散布倍数 |
| `flash.panicBurstTicks` | `4` | 恐慌射击的射击间隔 |
| `flashShort.fuseTicks` | `20` | 短引信闪光引信（旧值 8 tick = 0.4s，投出去还没落地就炸了；「引信太短」→ 20t = 1.0s） |
| `flashShort.blindFactor` | `0.75` | 短引信闪光致盲时长 = **标准闪光 × 这个系数**（旧值是写死的 50 tick = 50%，本次改成 75%：玩家 `100 × 0.75 = 75t (3.75s)`、生物 `120 × 0.75 = 90t`）。**如果你说的"太短"其实是指白屏时长，改的是这一键，不是引信**；反过来改 `flash.playerBlindTicks` 会同时抬高两颗闪光的时长 |

`[grenades.mob]`

| Key | Default | Meaning |
| --- | --- | --- |
| `mob.enabled` | `true` | 持枪单位是否会投掷手雷 |
| `mob.carryChance` | `0.35` | 生成时带雷的概率 |
| `mob.maxPerMob` | `2` | 每只最多携带（也是**领取上限**） |
| `mob.cooldownTicks` | `200` | 两次投掷之间的最小间隔 |
| `mob.minRange` | `6.0` | 近了不扔（会炸到自己） |
| `mob.maxRange` | `20.0` | 远了不扔（扔不到） |
| `mob.allySafetyRadius` | `4.5` | 投掷前检查落点周围这个半径内有没有自己人 |
| `mob.allySafetyMax` | `0` | 允许几个自己人在落点半径内（0 = 有一个就不扔） |
| `mob.arcSamples` | `13` | 弹道求解器试投的**发射仰角个数**（在 `0..maxLaunchPitchDegrees` 上均匀取样，13 个 = 每 3.75°）。粗扫**故意粗**——落点对角度的敏感度是每度好几格——之后再局部细化。调大 = 每次求解多几百次方块查询，调到 5 以下会漏掉"刚好越过矮墙"的那条弧 |
| `mob.requireClearArc` | `true` | 只在**求解出的弧真的落在目标身上**时才扔（默认，这是"AI 扔雷很容易被掩体影响"的修法）；`false` = 就算弧被掩体吃掉也照扔（旧行为，扔的是求解器找到的、最接近目标的那条弧） |
| `mob.maxLaunchPitchDegrees` | `45.0` | 求解器允许的**最大仰角**（扫描区间 `0..这个值`）。45° 是经典吊射；闸门用例显示 18 格处越过 1 格墙只需约 5° 的吊射，而 2 格以上的墙在**任何**角度都无法"越过并落在目标身上"。调大 = 能吊过更高的掩体；调小 = 更多投掷被判为"被掩体挡住"而放弃 |
| `mob.retryCooldownTicks` | `40` | 一次**收回的投掷**（弧被挡住 / 落点有自己人）之后，隔多少 tick 再求解。求解要模拟十几条弹道，而目标选择器**每 tick** 都问一次：没有这个键，怪会每 tick 重算十几条弧、并把同一行日志刷满（2026 年日志里就是这个症状） |
| `resupplyEnabled` | `true` | 没雷的已武装单位是否会去台上领 |
| `resupplyRadius` | `24.0` | 领雷的**搜索**半径与行走半径。搜索现在走**区块的方块实体表**（每轴最多 4 次区块查表，默认半径下**最多 16 次**，radius 4 时 4 次，外加这些区块里每个方块实体一次遍历），不再是"逐格 `getBlockEntity`"：24 格的旧写法是每次搜索 49×17×49 = **40817 次查询**，而且 `Level#getBlockEntity` 会**同步加载区块**；新写法每次搜索最多 16 次区块查表，而且每 40 tick 才搜一次。所以调大这个值不再按体积收费。调小 = 只在身边这栋楼里找，怪会**错过**它以前会去用的台子 |
| `resupplyCooldownTicks` | `200` | 领一枚之后的冷却（同一只不要来回跑） |
| `resupplySearchCooldownTicks` | `40` | **没找到台子**时的搜索冷却。目标选择器每 tick 都问一次 `canUse()`，而这个搜索曾经是已武装单位最贵的每 tick 开销（默认半径下每只空闲怪每 tick 40817 次方块实体查询，实测约 **1.25 ms 服务端 tick**——50 只就能吃光 50 ms 预算）。40 玩家完全看不出来（台子又不会跑）；填 `1` 恢复"每 tick 都找"的旧行为 |
| `rackPriority` | `grenade` | 多个台子时的取舍：`grenade` / `nearest` / `weapon` |

#### 触发（暴徒什么时候扔）+ 弹道求解器（2026 修「AI 扔雷很容易被掩体影响」）
目标距离在 **6–20 格**（`minRange`/`maxRange`）**且失去视线**（目标在掩体后）时才扔。**先解弹道，再决定扔不扔**：

- `GrenadeBallistics.solve` 是**全工程唯一的弹道实现**（旧的"水平投影 + 固定 −9 格"的 `GrenadeEntity.predictedLanding` 已删除）：按原版投掷物的物理**逐 tick 步进**（`v *= 0.99`、`v.y -= 0.03`、`pos += v`），在 `0..maxLaunchPitchDegrees` 上扫 `arcSamples` 个发射仰角，**每个采样点取手雷 0.25 箱体的"箱底中心"判定是否撞到方块**（所以"只擦着墙顶过去"会被判成撞上，和真实箱体一致），返回落点离目标最近的那条弧。粗扫之后再局部细化：粗扫每 3.75° 一跳，而落点每度会移动好几格，不细化落点会差好几格。
- **"通"（clear）的定义**：落点（进入方块的那个采样点）与瞄准点在**水平方向**上相差 ≤ 2.5 格（`GrenadeBallistics.ARRIVE_TOLERANCE`）。一条通了的弧，路径上必然什么都没撞到——因为落点就是它撞到的**第一个**方块。
- `requireClearArc=true`（默认）时**没有通的弧就不扔**：不扣雷、**不设投掷冷却**，只设 `retryCooldownTicks`，并记一行 `[grenade] <怪> held the throw: arc blocked by cover at (x, y, z)`；真正扔出去的那行日志会带上**仰角**与 `arc clear`。
- **为什么必须重写**：旧代码沿"眼睛 → 眼睛"的直线扔，而在怪的投掷速度（`maxThrowSpeed × 0.85 = 1.36 格/tick`）下**平扔只能飞约 12 格就落地**（实测箱底在 11.64 格触地）——18 格外的目标本来就扔不到，中间再有一格墙/栅栏/半砖/台阶就必被吃掉。
- **投掷散布改成 0**（`shoot(..., 0.0F)`）：求解器给出的弧就是**实际飞的那条弧**，否则"安全检查看到的落点"与"真实落点"又会分叉（这正是本次要消灭的东西）。旧代码的 3.0 散布是给直线扔法"加手感"用的。
- 投掷前的**安全检查**用的就是这个求解落点：`allySafetyRadius` 内的同阵营数量 > `allySafetyMax` 就**放弃**（默认"有一个自己人就不扔"）。
- **代价（已知、可接受）**：固定投掷速度下**任何**弧都落不到约 10 格以内（实测 9 格时最近的一条弧也差 2.64 格 > 2.5），所以 6–9 格的近距离目标会被 `requireClearArc` 判为"扔不到"而收回（旧代码照扔，然后飞过头落远——那正是用户抱怨的浪费）。想让近距离也扔，把 `requireClearArc` 关掉即可。
- 扔的同时喊 `grenade_1/grenade_2`（`MobVoice.sayGrenade`），附近有雷落下时的反应语音**沿用原有逻辑**（识别 `*grenade*` 实体路径）。
- `/tarkovscav test grenade` 的落点/仰角报告也走**同一个求解器**（不再自己算），所以"命令打印的弧"和"真的扔出去的弧"是同一套数字。

#### 玩家自伤：两种行为的对照（`playerSelfDamage`）
| | `true`（默认） | `false` |
| --- | --- | --- |
| 自己扔的雷炸到自己 | **会受伤、会被致盲** | 免疫 |
| 意义 | "炖雷"多炖一 tick 就会死，和所有人一样 | 可以往脚下扔来清房间，零风险 |
| 同阵营 mob | 不受影响（`friendlyFire=false` 仍然生效） | 不受影响 |

**就地验证**：`/tarkovscav test grenade [frag|he|smoke|flash|flash_short]` —— 在你前方**真的扔一枚**，并打印：落点、半径、引信、是否开地形破坏、**每 2/4/6/8 格的期望伤害**、半径内每个生物的**预计伤害/是否被墙挡住/闪光强度**。

**闸门**：`tools/selftest_grenades.js`（见下一节的清单）。

### 5u. 击杀播报 / 击杀列表 — the kill feed (屏幕正上方：谁 用什么 杀了谁)

用户原话：「**正上方可以做一个击杀列表吗：xxx 用什么武器 击杀了 xxx**」

**长这样**（一行一条，新条在最上面、旧的往下挤，整块从屏幕顶边往下长）：

```
武装暴徒   [AK-47]        武装村民
Ge_SiLa    [破片手雷]      狙击手掠夺者
武装村民    [石剑]         掠夺者
             [摔落]        环境
```

#### 名字与武器是怎么定出来的
| 项 | 规则 |
| --- | --- |
| **击杀者** | `DamageSource#getEntity()`（**射手**，不是箭） → 受害者 `getLastHurtByMob()` → 受害者 `getLastDamageSource()` → 都没有 = **环境**。**注意**：请求里写的 `getKiller()` / `getLastHurtByPlayer()` 在 1.20.1 **不存在**（`LivingEntity` 只公开 `getLastHurtByMob()` 与 `getLastDamageSource()`，`lastHurtByPlayer` 是 protected 字段且没有公开读法；对映射类 javap 核对过），所以"是不是玩家杀的"由**类型判断**（`instanceof Player`）回答，而不是再调一个访问器 |
| **被击杀者** | 实体显示名（所以我们的单位会带上「叛徒」之类前缀，原样显示） |
| **武器** | 击杀者**当时主手**的物品。**发的是 ItemStack 而不是字符串**，由**客户端**用 `getHoverName()` 取名——这是唯一正确的做法：TaCZ 枪的显示名来自 **TaCZ 客户端**的枪械索引（**专用服务端根本没有它**），而剑/弓的名字要按玩家语言翻译。所以枪会显示 **AK-47** 而不是 `tacz:ak47` 或翻译键 |
| **赤手空拳** | 主手为空且伤害不是投掷物 → 「赤手空拳」（不是"未知"） |
| **爆炸/破片** | `DamageSource` 类型表 → 「爆炸」（**B 批的手雷就走这里**，见下面的扩展点） |
| **环境** | `fall` / `drown` / `fire` / `magic` 等由**一张 damage-message 表**映射成「摔落/溺水/火焰/魔法/环境」 |
| **解析不出来** | **不静默**：显示「未知武器」，并在服务端按 id **WARN 一次**（`KillFeedWeapons#register` 或表里加一行就能修） |

> **可扩展点（B 批要用）**：武器解析是**表驱动**的——`KillFeedWeapons` 里有一个有序的 `List<Resolver>`，新增武器只要 `KillFeedWeapons.register(...)`（插到最前，优先级最高）或往 `fromMessageId` 表里加一行，**不需要改任何 if-else 控制流**。闸门专门断言了这一点。

#### 谁能看到（服务端决定，客户端只画收到的内容）
| Key | Default | Meaning |
| --- | --- | --- |
| `killFeed.enabled` | `true` | HUD 总开关（false 时服务端也不发，不浪费带宽） |
| `killFeed.mode` | `involved` | `involved` = 击杀者/被击杀者/半径内；`all` = 同维度所有人；`global` = 全服（忽略半径） |
| `killFeed.radius` | `64.0` | `involved` 模式下的"附近"半径（格） |
| `killFeed.showMobKills` | `true` | 双方都不是玩家的击杀（我们的单位互杀、村庄遭遇战） |
| `killFeed.showPlayerKills` | `true` | 有玩家参与 |
| `killFeed.showEnvironmentDeaths` | `true` | 无击杀者的死亡；**还要求受害者是玩家或本模组单位**（"一头牛摔死了"不算新闻） |
| `killFeed.lineDurationTicks` | `100` | 一条在屏幕上活多久（含淡入淡出），100 = 5 秒 |
| `killFeed.maxLines` | `5` | 同时最多几条，超出**丢最旧的** |
| `killFeed.position` | `top_center` | `top_center` / `top_left` / `top_right`（永远在屏幕**顶部**，只换水平锚点） |
| `killFeed.scale` | `1.0` | 文字缩放（0.5–2.0） |
| `killFeed.maxPerSecond` | `4` | 每个玩家每秒最多几条（一颗手雷炸死六个不会同时刷六行） |
| `killFeed.dedupTicks` | `40` | 同一对实体 + 同一武器在这个窗口内只播一次 |

**网络**：一条自定义包（`tarkovscav:killfeed`），**只发给该看到的玩家**（`PacketDistributor.PLAYER`），字段是**纯数据**：击杀者名、被击杀者名、武器 ItemStack（仅物品类）、来源类别枚举、时间戳。**不下发任何可点击/可格式化的文本**，客户端只用**自己的字体和自己的语言**渲染；太长的名字**截断加省略号、绝不换行**（不会撑爆屏幕或压到准星上）。

**颜色**：击杀者/被击杀者按**阵营**着色（暴徒/掠夺者=红、村民=绿、玩家=黄、环境/认不出来=灰），武器名统一暗灰并加方括号。颜色在**收到那一条时解析一次**（不是每帧扫实体表）。尊重 **F1**（`options.hideGui`）。

**就地预览**：`/tarkovscav test killfeed [killer] [victim] [weapon]`——**纯客户端**，不杀人就能看排版。`weapon` 给物品 id（如 `minecraft:stone_sword`）、给类别（`fists|explosion|fall|environment|unknown`）或留空（=赤手空拳）；连打几次可以顺便验证 `maxLines` 溢出与淡出。

**闸门**：`tools/selftest_killfeed.js` —— 回退链（含"不许调不存在的 `getKiller()`"）与逐级仿真、**表驱动解析器 + 扩展点**、赤手空拳/环境/未知+WARN一次、ItemStack 下发与客户端 `getHoverName()`、三种可见性模式 + 半径边界（64 含、64.1 不含）+ 每秒节流（4/s → 两秒正好 8 条）+ 去重窗口边界、HUD 的 `enabled=false` 惰性 / `maxLines` 丢最旧（仿真）/ F1 / 位置与缩放 / **300 字名字截断到宽度且带省略号**（仿真），以及 12 个配置键与本节 README。

### 5p. 随机配件池「改枪王」 — mobs that spawn with a kitted-out gun

怪物生成时**有概率自带配件**（默认每槽 35%，另有 2% 的「改枪王」满配 roll）。

**合法性由 TaCZ 回答，我们只问**——这是「0 非法组合」能**按构造成立**的原因：

| 步骤 | 调用 | 说明 |
| --- | --- | --- |
| 1 | `IGun.allowAttachmentType(gun, type)` | 这把枪**有没有**这个槽（手枪没有枪托）→ 没有就跳过，不算失败 |
| 2 | `IGun.allowAttachment(gun, attachment)` | 这个配件**配不配**这把枪 —— TaCZ 自己判定，我方没有任何白名单表 |
| 3 | `IGun.installAttachment(gun, attachment)` | 装进**正确槽位**（同槽自动替换，所以一个槽不可能有两个） |

**候选配件来自 TaCZ 自己的配件索引**（`TimelessAPI.getAllCommonAttachmentIndex()`：id + `getType()`，再用 TaCZ 的 `AttachmentItemBuilder.setId(id).build()` 造 ItemStack），所以新枪包自动被纳入；物品注册表扫描**只作为兜底与诊断数字**保留。

#### 2026 修掉的"从没改过枪"（真实实例：`[mods] 0 attachment(s) of type SCOPE available`）
**现象**：用户日志里六个槽全是 `0 attachment(s) of type X available`，随后每把枪都是
`no legal attachment of the rolled type(s) for gun …; the gun is left unchanged` → **每把枪都是裸枪**。
**根因（时序 + 缓存）**：旧实现从**物品注册表**扫配件、`computeIfAbsent` **把结果缓存一次**，包括**空结果**。
TaCZ 的 `GunPackFinder: Start scanning… Found 8 possible gunpack(s)` 发生在我们的首次扫描**前几秒**，所以我们那一枪打在了
**枪包索引重载的中途**：那一刻扫不到配件 → **空列表被缓存整个会话** → 之后再也不重试。
**修法（两条，就是这次的全部改动）**：
1. **候选源换成 TaCZ 索引**（见上）；物品注册表扫描降级为兜底/诊断；
2. **空结果绝不入缓存**：池子只在**非空**时才记住，且只在该池构建时的 **TaCZ 索引大小与当前一致**时才信任
   （`indexSize()` 就是重载探测器——TaCZ 公开 API 里**没有**可订阅的"枪包重载"事件：`api/event` 只有开火/配件等
   运行时事件，`network/message/ServerMessageSyncGunPack` 是**网络包（IMessage）**、`client/event/ReloadResourceEvent`
   是**贴图图集回调**，两者都不是 Forge 事件；以上用 `javap` 对实例的 **1.1.7** 与开发用的 **1.1.8** 都核实过），
   `/tarkovscav client reload` 也会**显式清空**池子（连同 `GunPool` 的枪池）。
   另外单槽候选尝试数 8 → **16**，窄接口的枪也有公平命中机会。

**诊断（下次进游戏一眼可判）**：`/tarkovscav test mods` 现在先打印
`scope: index=N registry-scan=M candidates=K`（六个槽各一行），再打印每只怪的枪；若索引为 0 会额外写
**`TaCZ's attachment index is EMPTY: your gun packs define no attachments`**。日志里那六条
`N attachment(s) of type X available (TaCZ index: …)` **只在数字变化时**打印（不再刷屏）；某把枪"没配上"的 WARN
仍是**每个枪 id 一次**，且索引为空时那句话会直接说"你的枪包没有配件定义"。

**扩容弹匣与弹药容量**：容量**不是本 mod 知道的数字**。`GunPool.buildGun` 先用 TaCZ 索引里的 `magazineSize` 建枪，**装完配件后再从物品上重读**（`GunAttachments.capacityOf`：`useDummyAmmo` 时读 `getMaxDummyAmmoAmount`，否则读 `getCurrentAmmoCount`），随后 `refillToCapacity` 按**改后**容量补满。换弹路径（`GunBrain.finishManualReload`）也改成读**同一处**容量，否则扩容弹匣只会被按原厂容量装填。**没有任何硬编码的 +10**。

**死亡掉落保留配件**：枪是**同一个 ItemStack**（配件在它的 NBT 里）被 `GunLoot.dropGunAndAmmo` 丢出的，不是重建的，所以玩家捡起来配件仍在。

#### 2026 崩溃防护：带 Lua 脚本的枪（`hamster:win1894` 崩服）
**崩溃原文**（用户的 crash report，`crash-2026-09-23_22.29.05-server.txt`）：
```
Description: Ticking entity
org.luaj.vm2.LuaError: hamster_win1894_gun_logic:32 attempt to perform arithmetic __mul on nil and number
  at com.tacz.guns.item.ModernKineticGunItem.lambda$tickBolt$4(ModernKineticGunItem.java:95)
  at com.tacz.guns.entity.shooter.LivingEntityBolt.tickBolt(LivingEntityBolt.java:97)
Entity Type: tarkovscav:usec_villager
```
即：**枪包给枪挂的 Lua 在 TaCZ 自己的 tick 里跑**，脚本第 32 行对 nil 做乘法 → `LuaError` 冒泡到服务器 tick → **崩服**。我们**无法 catch**（异常抛在别的模组的 tick 里），TaCZ 也没有"全局关脚本"的配置项（`tacz-common.toml` / `tacz-client.toml` 里搜 `script|lua` 无结果），所以**唯一的结构性防护 = 不让怪拿到这种枪**。

**为什么按"命名空间"判定，而不是"有脚本就排除"**：实测（`tools/gunpack_script_scan.ps1`，只读地展开用户实例的 7 个枪包）——
**230 把枪里 105 把声明了脚本**，其中 TaCZ 自己的默认包是 **47 把里的 20 把**（16 × `xmag_reload_logic` + 4 × `*_gun_logic`，全部 `tacz:*`）；这些枪**我们的怪已经用了很久没出过事**（用户日志里就有 `tacz:hk_mp5a5`）。崩掉的那把 `hamster:win1894_gun_logic` 来自 **GunpowderRevolution v1.3.5**（该包 48 把 / 34 把带脚本 / 33 把被挡）。
**只统计默认包会严重低估影响面**——实例级分布如下：

| 枪包 / 命名空间 | 枪数 | 带脚本 | 默认策略挡掉 |
| --- | --- | --- | --- |
| tacz（TaCZ 默认包） | 47 | 20 | 0（命名空间受信任） |
| hamster（GunpowderRevolution v1.3.5） | 48 | 34 | **33** |
| echoes_of_ruin（残响，28 把共用 `allow_running_when_reloading_logic`） | 31 | 31 | **31** |
| bf1（Apocalypse） | — | 7 | **7** |
| suffuse（Suffuse-GunSmoke-Pack） | — | 6 | **6** |
| gsl_server（`1`） | — | 1 | **1** |
| m18（m18_gun_pack） | — | 1 | **1** |
| **合计** | **230** | **105** | **79（≈34%）** |

命名空间计数：hamster 33、echoes_of_ruin 31、tacz 26、bf1 7、suffuse 6、gsl_server 1、m18 1。
所以**默认策略已经挡掉全部第三方脚本**（79/230 ≈ 34%），`trustedScriptNamespaces=[]`（最保守）只会**再**挡掉那 26 把 `tacz:*`（合计 105 把）。想放行某个你检查过的包：把它的命名空间加进去即可。

**判据（来自 TaCZ 自己的索引；`javap` 对实例的 1.1.7 与开发库 1.1.8 都核实过）**：`GunData#getScript()` → `ResourceLocation`，即这把枪**声明**的脚本 id（例如 `tacz:xmag_reload_logic`、或崩掉的 `hamster:win1894_gun_logic`）。
> TaCZ 还提供 `CommonGunIndex#getScript()` → `org.luaj.vm2.LuaTable`（**编译后**的脚本，是"TaCZ 是否真会为这把枪跑 Lua"的权威信号）。**我们没有调用它**：luaj 不在本模组的编译期依赖里（TaCZ 是 `compileOnly`），引用 `LuaTable` 返回类型无法编译。声明的 id 已经带命名空间，够这条策略用；那一层只是冗余信号（真要用就得给 `build.gradle` 加 luaj 依赖）。

**为什么按"命名空间"判定，而不是"有脚本就排除"**（结论）：默认策略是 **声明了脚本、且脚本命名空间不在信任列表里 → 不进怪物枪池**（`guns.trustedScriptNamespaces` 默认 `["tacz"]`）。上面的实例数据说明这个默认**已经挡掉全部第三方脚本**；设成 `[]` 是字面意义的"排除所有带脚本的枪"（实例上会**再**挡掉 26 把 `tacz:*`，合计 105 把，**约 46% 的枪会消失**）；把某个命名空间加进去 = 放行你检查过的那个包。每条被挡的枪 id **只 INFO 一次**（含脚本 id 与命名空间），列表可在 `test mods` / `gunpool` 查看。

**已有单位也会被净化（防"读档即崩"）**：两条互相独立的路径——
1. **恢复存档时**：`GunBrain.equipLoadout`（从盘里读回枪 id 的那条路）先过规则，被挡的枪**直接换成从池子重滚的一把**；
2. **运行时兜底**：`GunBrain.tick` 每 `guns.scriptedGunRescanTicks`（默认 40 tick，**按实体 id 错峰**）检查手里的枪；命中就**先清空手部并清掉 brain 的字段**（`setItemInHand(EMPTY)`，此后没有任何东西再 tick 那把枪），**再**用 `GunPool.buildGun` 从池子建一把新的——**换枪动作不经过被挡枪的 Lua**。这条覆盖"从军械台拿的枪 / `/give` / 规则出现前存的档"。
**不静默**：换枪时有一条 WARN，写明哪把枪、哪个脚本、为什么。

| Key | Default | Meaning |
| --- | --- | --- |
| `guns.excludeScriptedGuns` | `true` | 脚本枪防护总开关：`true` = 脚本命名空间不受信任的枪**不进怪物枪池**（已有单位也会被换掉）；`false` = 旧行为（TaCZ 认得的枪都可能发给怪），**可能再次崩服** |
| `guns.trustedScriptNamespaces` | `["tacz"]` | 仍放行的脚本命名空间。`[]` = 排除**所有**带脚本的枪（最安全，池子明显变小）；加一个命名空间 = 信任你检查过的那套脚本；大小写不敏感 |
| `guns.scriptedGunRescanTicks` | `40` | 运行时兜底检查间隔（tick，按实体 id 错峰）；`0` = 只做生成/读档时的过滤 |
| `guns.respectDeclaredFireModes` | `false` | 是否让**枪自己的数据文件**决定怪物手上这把枪的开火模式（见下面的「开火模式」小节）；`false` = 一直是旧行为（全部 `AUTO`） |

#### 开火模式：为什么我们一直把每把枪都写成全自动（`guns.respectDeclaredFireModes`）

**这是上一节崩溃链条里我们自己加进去的那一环。**`GunPool.buildGun` 过去无条件 `setFireMode(FireMode.AUTO)`，也就是**不管枪的数据文件怎么写，怪物手上那把枪永远是全自动**。枪包自带的 Lua 会读这个模式：

- `hamster_win1894_gun_logic.lua` 第 31/32 行就是 `if api:getFireMode() == AUTO then … 用 rapid_bolt_time …`——
  **这个分支只有因为我们写了 AUTO 才会被执行**；
- 而 `hamster:win1894_data` 声明的是 `fire_mode: ["semi"]`，**它的数据文件里根本没有 `rapid_bolt_time`**
  （同包的 `smle_mk3_data`（`["semi"]`）与 `win1873_data`（`["semi","auto"]`）**都定义了这个字段**）——
  于是跳进 AUTO 分支后对 `nil` 做乘法 → `LuaError` → 崩服。

**实例实测**（`tools/firemode_scan.ps1`，只读地展开用户实例的 7 个枪包，逐把读 `fire_mode` 数组）：

| 数字 | 值 | 说明 |
| --- | --- | --- |
| 枪数据文件总数 | **230** | 与脚本扫描同一口径 |
| 声明了 `auto` | **108** | 我们强制 AUTO 恰好等于它们自己的选择，行为不变 |
| **没声明 `auto`** | **122** | **今天被我们强行拉成全自动**（半自动 110 + `semi+burst` 4 + `burst+semi` 4 + `burst` 4）；`win1894` 就在其中 |
| 打开本开关后的模式分布 | `SEMI` **114** / `AUTO` **108** / `BURST` **8** | 判定规则见下表 |

**判定规则**（`GunPool#fireModeFor`，只在 `true` 时生效）：数据文件声明了 `auto` → `AUTO`；否则取**第一个**声明的模式；
**声明为空 / TaCZ 索引里没有这把枪 → `AUTO`**（所以这个开关**永远不会让怪物打不出枪**）。每条枪的判定**只 INFO 一次**：
`respectDeclaredFireModes=true: <gun> declares [semi] -> mob stack uses SEMI`。

> **它是缓解，不是根治**：脚本仍可能因为别的原因在怪物身上出错，所以 `excludeScriptedGuns` 仍然是结构性防线（上一节）。
> **默认 `false`**：这是所有已发布 jar 的行为，改默认会静默改变每个存档的手感（拉栓枪突然变成单发点射节奏）。
> 想试试看：`guns.respectDeclaredFireModes = true` + `/tarkovscav client reload`。

**怎么调/怎么看**：`/tarkovscav test mods` 会打印 `scripted guns: excludeScriptedGuns=… trustedScriptNamespaces=… rescanTicks=…` 与**当前被挡的 id 列表**；`/tarkovscav gunpool [tier]` 会打印"被 config 挡 N 把 / 被脚本规则挡 M 把"及被挡清单。改了 `trustedScriptNamespaces` 之后 `/tarkovscav client reload` 会**重算**（脚本判定按枪 id 缓存，reload 会清缓存并重新记一次日志）。

| Key | Default | Meaning |
| --- | --- | --- |
| `mods.enabled` | `true` | 总开关；`false` = 全部裸枪（回到本功能之前的行为） |
| `mods.perSlotChance` | `0.35` | 每个槽独立填充概率；枪没有的槽被跳过而不是计入 |
| `mods.fullModChance` | `0.02` | 「改枪王」roll：以该概率填满这把枪允许的**所有**槽 |
| `mods.maxPerGun` | `5` | 每把枪配件数硬上限 |
| `mods.allowExtendedMag` | `true` | 是否允许 `EXTENDED_MAG` 槽（唯一会改变容量的配件；改后容量总是重读） |

`[guns]` 的脚本枪防护键见上面的「2026 崩溃防护」小节（`excludeScriptedGuns` / `trustedScriptNamespaces` / `scriptedGunRescanTicks`）。

`/tarkovscav test mods`：先打印**配件池诊断**（六行 `scope: index=N registry-scan=M candidates=K`，索引为 0 时额外提示"你的枪包没有配件定义"），再打印当前配置 + 32 格内每只枪怪手上那把枪的 `capacity=… attachments=scope=…,muzzle=…`，日志同时写 `[mods] …`。想验证分布就直接刷一堆怪然后 `test mods`。

### 5o. 精度档位 — wild first shots, and a 75 % ceiling (前 8 发偏、稳态上限 75%)

这两条规则只作用在**一个**数字上：`GunBrain#computeAim` 里「要消除多少瞄准误差」的那个比例（`accuracy`）。本项目的精度从来不是命中判定，而是**误差锥角的缩放**：`spread = (1 - accuracy) * 7 度 * 距离系数 * 收枪系数 * (移动时 1.75)`，所以「上限 75%」在这里等于「误差锥角有一个下限」——不是给怪物加一个作弊的命中骰子。

| 规则 | 说明 |
| --- | --- |
| **前 N 发偏** | 一次交火的前 `accuracy.warmupShots`（默认 **8**）发，精度乘以 `accuracy.warmupMultiplier`（默认 **0.45**）。狙击手 0.85 × 0.45 = **0.38**，所以刚接火时它明显打不准——这就是玩家的先手窗口 |
| **稳态上限（按档位分开）** | 上限不是全局一个数，而是**档位（profile）**：`rookie`（小卡拉米：暴徒/掠夺者/武装村民，默认 **0.75**）、`veteran`（狙击手档，以及以后更强的单位，默认 **0.85**）、`elite`（再往后，默认 **0.90**）。**归属由实体类型决定，再由 tier 提升**：`profileScav/profileGunnerPillager/profileGunnerVillager` 指定类型的档位，任何 **tier = sniper** 的单位会被提升到 `profileSniperTier`（**只升不降**，所以这条规则永远不会让谁变弱）。另有 `hardCeiling`（默认 **0.95**）作为**绝对天花板**：1.0 = 从不失手，本项目不允许，手改配置也会被夹回 |
| **重新变冷** | 连续 `accuracy.resetTicks`（默认 600 = 30 s）没开火，计数清零：**跟丢了再遇到，是又一次「刚接火」**，而不是立刻被爆头 |
| **只有真开火才算** | 计数器只在 `ShootResult.SUCCESS` 时 +1。被拒绝的射击（`NOT_DRAW`、`FORGE_EVENT_CANCEL`……）不算「经验」，否则躲在墙后空扣扳机也能把精度养起来 |

计数存在怪物自己的持久数据里（`tarkovscav:shotsFired` / `tarkovscav:lastShotTick`），所以**存档/重启后仍然记得自己打过几发**。

| Key | Default | Meaning |
| --- | --- | --- |
| `accuracy.enabled` | `true` | 总开关；`false` = 完全恢复旧行为（第一发就用 tier 精度、不封顶） |
| `accuracy.hardCeiling` | `0.95` | 所有档位共同的**绝对**上限（任何档位都夹不过它） |
| `accuracy.profileRookieCap` | `0.75` | rookie 档命中率上限（小卡拉米） |
| `accuracy.profileVeteranCap` | `0.85` | veteran 档上限（狙击手档） |
| `accuracy.profileEliteCap` | `0.90` | elite 档上限（更后面的单位） |
| `accuracy.profileScav` | `rookie` | 暴徒用哪一档 |
| `accuracy.profileGunnerPillager` | `rookie` | 武装掠夺者用哪一档 |
| `accuracy.profileGunnerVillager` | `rookie` | 武装村民用哪一档 |
| `accuracy.profileSniperTier` | `veteran` | tier=sniper 的单位提升到哪一档（只升不降） |
| `accuracy.warmupShots` | `8` | 前多少发算「冷枪」；0 关闭预热 |
| `accuracy.warmupMultiplier` | `0.45` | 预热期间的精度倍率 |
| `accuracy.resetTicks` | `600` | 多久没开火就重新变冷；0 = 终生累计 |

档位名写错（例如 `profileScav = "pro"`）会 **WARN 一次并退回 rookie**，不会静默失效。**逐距离命中率（已证明，不是估计）**：

| 距离 | rookie（0.75）| veteran（0.85）| elite（0.90）| 若不封顶（0.85 原样） |
| --- | --- | --- | --- | --- |
| 6 格 | 75.0% | 85.0% | 90.0% | 100.0% |
| 10 格 | 75.0% | 85.0% | 90.0% | 99.9% |
| 20 格 | 67.4% | 85.0% | 90.0% | 89.8% |
| 30 格 | 48.7% | 72.5% | 85.0% | 72.5% |
| 40 格 | 37.7% | 58.7% | 70.4% | 58.7% |
| 52 格 | 29.4% | 47.1% | 58.7% | 47.1% |

每一档在近距离都被硬性压到自己的上限，远距离本来就打不准；`elite` 即使 0.90 **仍然会失手**。

`/tarkovscav debug` 现在会多打印一段：`shots=3/warmup acc=0.383 (tier 0.85, profile veteran cap 0.85)`——一眼能看出「冷枪还是稳态、哪一档、实际用多少」。

### 5n. 武器放置台 — the weapon rack (把枪放在台上，让没武装的人自己来拿)

一个方块，**只存一件物品**。放上去的东西会浮在台上慢慢转（方块实体渲染，3D），**未武装的原版村民/掠夺者**会走过来把它取走，然后**变成武装单位**——而且**拿什么就怎么打**。

#### 朝向（`facing`，普通台子和军械台都有）

台子有 `BlockStateProperties.HORIZONTAL_FACING` 四个朝向，放置时和箱子/工作台一样：**你面朝哪、台子就朝哪**
（`getStateForPlacement` 用 `context.getHorizontalDirection().getOpposite()`）；结构方块旋转/镜像也会带着它转
（`rotate` / `mirror` 与 `HorizontalDirectionalBlock` 一致）。资源侧是两个 blockstate 各四个 `y = 0/90/180/270` 变体（同一个模型），
渲染台子上那把枪时**跟着朝向转**（`WeaponRackRenderer.facingDegrees`，就在原有的"浮空+缩放+倾斜+自转"之外套一层 Y 轴旋转）。

> **默认值 = `NORTH` = 原来的唯一变体。** 旧存档里已经放好的台子会拿到这个默认值（不会崩、**台上的枪也不会丢**：物品存在方块实体 NBT 里，
> 与 blockstate 无关），而 `NORTH` 在 blockstate 里是 `y = 0`、在渲染器里是 **+0 度**，所以**旧存档的观感逐像素不变**。
> `Direction.toYRot()` 的原点在 SOUTH（`NORTH.toYRot() == 180`），这就是 `facingDegrees` 里那个 `- 180` 的由来——两边必须对齐同一个约定，
> 否则台子转了、枪没转。
> **没有做「贴墙安装」**（那需要第二套模型与贴图，用户尚未确认是否需要）。

#### 合成与获取

```
P P          P = 橡木木板 (minecraft:oak_planks)
PPP          I = 铁锭 (minecraft:iron_ingot)
I I          -> 1x tarkovscav:weapon_rack
```
创造模式物品栏里也有（与三个刷怪蛋同一个页签）。破坏方块时**台上物品会回到世界里**——**掉且只掉一次**：生存模式由 `playerWillDestroy` 掉（它跑完后 `onRemove` 发现槽位已空，不会再掉第二次），创造模式、爆炸、活塞、`/setblock` 由 `onRemove` 掉（创造模式的 `instabuild` 只是抑制 `playerWillDestroy` 这一路，`onRemove` 照样掉一件——这与原版箱子一致，拿了就拿了，不会复制也不会吞）。除了这个可合成的台子，模组里还有**创造模式专用的「军械台」**`tarkovscav:creative_weapon_rack`（无限模板，只在本页签、**无配方**，见下面 5n 的对应小节）。

#### 交互真值表（从上往下判定，第一条命中即生效；**每一条都有提示，绝不静默**）

| # | 手 | 台上 | 结果 |
| --- | --- | --- | --- |
| 1 | 潜行（任意手） | 任意 | **取回**：「Took X off the weapon rack.」/ 台空则「The weapon rack is empty.」 |
| 2 | 空手 | 任意 | **取回**（同上） |
| 3 | 拿着物品 | 空 | **放上**：「Put X on the weapon rack (tacz_gun/bow/crossbow/melee).」 |
| 4 | 拿着物品 | 已有物品 | **拒绝**：「…already holds X - take it first (sneak or empty hand). **Nothing was moved, and the item on the rack is still there.**」——**不换物**，所以既不会丢也不会复制 |
| 5 | 拿着物品 | 空，但不是武器 | **拒绝**：报出物品名 + 列出接受范围 + 提示 `rack.acceptsAnyItem = true` 可放开 |

**只收武器还是任意物品？** 默认只收（判定依据，不是猜的）：TaCZ 枪械（`IGun.getIGunOrNull(stack) != null`，TaCZ 自己的能力判定，覆盖所有枪包）、弓/弩（`Items.BOW`/`Items.CROSSBOW`；1.20.1 没有 `minecraft:bows` 这个物品标签，这两个物品就是全集）、剑/斧（原版 **`#minecraft:swords`** / **`#minecraft:axes`** 标签，所以整合包的剑不用改代码就被接受）。`rack.acceptsAnyItem = true` 可以放开成任意物品。

#### 取用与转化（武器类型决定战斗方式）

| 取走的东西 | 变成 | 怎么打 |
| --- | --- | --- |
| TaCZ 枪械 | `gunner_villager` / `gunner_pillager` | 复用 **`GunBrain`**（枪池能配齐弹药时发**那一把**枪；配不齐则**不给枪**、只近战，并 WARN——不许凭空变出一把打不了的枪） |
| 弓 | 同上 | **远程**，本项目自己的 `ArmedRangedGoal`（拉弓 20 tick 后放箭） |
| 弩 | 同上 | **远程**，同一个 goal，**拉 25 tick**（更重，所以比弓慢） |
| 剑 / 斧 | 同上 | **近战**，复用已有的 `NoGunMeleeGoal` |
| 其它（若 `acceptsAnyItem` 放开） | **什么都不发生** | 留在台上，并按物品 id **WARN 一次**（不消耗、不浪费） |

> 为什么弓/弩不是复用原版 `RangedBowAttackGoal`：那个类声明为 `RangedBowAttackGoal<T extends Monster & RangedAttackMob>`，而 `GunnerVillagerEntity extends Villager` **不是 Monster**，原版 goal 根本不能用在村民身上。与其给掠夺者用原版、给村民另写一套，不如两边共用一个 goal；`GunnerVillagerEntity` 因此实现了 `RangedAttackMob`（`performRangedAttack` 按 `AbstractSkeleton` 的射法放箭）。

**谁来取**：只接受**成年、站在地上、主手为空**的原版 `Villager`/`Pillager`，且**不是** `GunUser`（武装村民/掠夺者本身就继承自这两个类，不排除的话「已武装」会被判成可再武装）。半径 `rack.takeRadius`（默认 8 格，含高度差）。**优先级** `rack.priority` = `nearest`（默认）/`villager`/`pillager`——是**偏好不是过滤**：附近没有偏好类型时另一种照样会被服务，否则村里的台子会永远不工作。取走后 `rack.takeCooldownTicks`（默认 200 tick）内不再服务下一个，避免一座村子一秒内全副武装。

**物品消耗与「不刷物品」**：物品从台上**只被取走一次**（`take()` 取值与清空同一次调用完成），交给**恰好一个新实体**；新实体创建失败就把物品**放回台上**。旧实体在新实体出现之后才 `discard()`。**转化前后不继承任何东西**（没有装备、没有名字、没有 NBT）——除了位置与朝向；这是刻意的，写在这里以免有人期待别的。转换时拿到的弓/剑会写进实体的持久数据（`tarkovscav:rackWeapon`），并在 `readAdditionalSaveData` 里**在随机补枪之后**重新装上，所以**重启服务器后弓箭手不会偷偷变回枪手**。

#### 扔武器也能吸收（`absorbDroppedItems`）

**为什么需要**：手里拿着枪时右键是「放上/拒绝」那条分支，而 TaCZ 枪械的右键要用来开枪——**根本没机会「持枪互动」**。所以台子会自己捡：**空置**的台子每隔 `absorbCheckIntervalTicks`（默认 8 tick）扫描一次自己的身体范围，把**扔到台上**的那件武器收进槽位。

| 规则 | 行为 | 为什么 |
| --- | --- | --- |
| 台上有东西 | **完全不吸收**（不覆盖、不排队） | 只有一个槽位，收进来就必须扔掉原有的；排队意味着玩家看不见的库存，出了 bug 也说不清 |
| 物品不被接受 | 留在原地 | 否则一块土就能占住槽位，把真武器挡在外面（判定与手动放置同一个 `WeaponRackArmament.accepts`） |
| 地上一叠 5 个 | **只收 1 个**：`copyWithCount(1)` 上台、实体 `shrink(1)`，地上留 4 个 | 「吸收」不该等于「清空整叠」；只剩 0 个时由**实体自己的 tick** 移除，不由我们 `discard()`，避免重复移除 |
| 实体已经被别的代码移除 | 查询时用 `drop.isRemoved()` 过滤掉 | 否则可能把「已经消失的东西」复活进槽位 |
| `absorbDroppedItems = false` | 一次也不扫描 | 总开关，出问题时可以立刻关掉（`rack.enabled = false` 也会一起关掉） |

范围：`absorbRadius`（默认 0.75 格，水平，以方块中心为心）×`absorbHeight`（默认 1.25 格，从方块底面往上），所以需要**扔到台子上**，而不是掉在旁边；`0` 表示不覆盖上方（只扫水平）。每次吸收都会在日志里写 INFO 一行：`[rack] absorbed <物品名> x1 (<分类>) dropped at <坐标> onto the rack at <坐标>`。

**不刷不丢**：进去的是 1 个，地上少的是 1 个，总数不变（这条被下面第 9 节的仿真计数断言：`1 → 1`、`5 → 1+4`、`3 → 3`、`4 → 4`）。

#### 军械台（创造）— `tarkovscav:creative_weapon_rack`

**这是另一个方块，不是同一个方块的一个开关**。理由：无限刷的开关如果放在 NBT 或 blockstate 里，玩家就能靠改存档/NBT 把一个普通台子变成无限台子；挂在**方块实体类型**上，则**只有创造模式物品栏（和 `/give`）能拿到这个方块**，生存模式下无从获得。

- **没有合成配方**（`data/tarkovscav/recipes/` 里没有任何文件产出它），只列在本模组的创造模式页签里。
- **槽位是「模板」，不是容器**：`claim()` 在 `infinite` 台子上返回 `held().copy()`，**从不**清空槽位——所以**同一个武器可以武装无限个村民/掠夺者**（第 10 节仿真：连续 5 次取用 → 5 个武装单位 + 模板仍在台上；对照组普通台子 5 次尝试只出 1 个，第 1 次后台子就空了）。
- **玩家右键也无限拿**：空手/潜行右键给**一份副本**，模板仍在台上（提示语会明说「the rack still holds it, as many copies as you like」）。台上有模板时，再右键一个**被接受**的物品会**替换模板**（普通台子是拒绝换物——因为普通台子能空，创造台子永远空不了，拒绝就没法改了），**旧模板还回玩家背包**。
- **破坏它不掉出模板**（`dropContents` 对 `infinite` 直接返回）：槽位是模板，取用本来就无限，再掉一个实物就是白送。
- 它同样会**吸收**扔上来的武器（规则同上），同样会把取走它东西的村民/掠夺者变成武装单位，日志里会写 `[rack] creative: item not consumed (<物品> stays on the rack at <坐标>)` 和 `[creative: template kept]`，让「这是刻意的」在日志里也能看见。
- 外观：模型复用放置台并**多两个顶角饰块**（`endless_finial_left/right`），贴图是把放置台贴图调色后的**独立贴图** `textures/block/creative_weapon_rack.png`（紫/绿），所以在世界里一眼能区分。
- `creativeRackEnabled = false` 时它**完全惰性**：不武装任何人、不吸收、右键会直接说明「switched off」；方块还在（不是靠删方块来关，避免存档里出现「未知方块」）。
- 物品 tooltip 会明说：**「仅创造模式：槽位是无限模板，永远拿不完」**、右键拿副本、怪物可无限刷、没有配方。

#### 配置键 `[rack]`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | 总开关；`false` 时怪物不会来取、**也不会再吸收掉落物**（方块本身仍可手动存取，等于一个普通单格架子） |
| `acceptsAnyItem` | `false` | 是否接受任意物品；放开的无战斗路径物品会被 WARN 并留在台上 |
| `takeRadius` | `8.0` | 多远内的未武装村民/掠夺者会被服务（格，含高度差） |
| `takeCooldownTicks` | `200` | 两次取用之间的间隔 |
| `takeCheckIntervalTicks` | `20` | 检查频率（每台每 interval 一次实体查询，不是每 tick） |
| `priority` | `nearest` | `nearest` / `villager` / `pillager` |
| `absorbDroppedItems` | `true` | 是否吸收**扔到台上的**物品（只收 1 件、只收空置的、只收被接受的） |
| `absorbRadius` | `0.75` | 吸收扫描的水平半径（格，0–4） |
| `absorbHeight` | `1.25` | 吸收扫描从方块底面往上的高度（格，0–4；`0` = 只扫水平） |
| `absorbCheckIntervalTicks` | `8` | 吸收扫描频率（每台每 interval 一次实体查询，不是每 tick） |
| `creativeRackEnabled` | `true` | 创造模式军械台是否生效；`false` = 方块保留但完全惰性 |

#### 就地验证

`/tarkovscav test rack`：报告 16 格内最近的台子——台上是什么、分类成什么 `armament`、冷却剩余、接受范围，以及**当前会被谁取走**（名字 + 会变成哪个实体 + 以什么方式作战）；没有合格对象时会说明 `takeRadius` 和 `priority` 当前值。现在还会打印：

- `kind=normal|creative` 与 `infinite=true|false`（这台子是普通台还是创造台），以及 `creativeRackEnabled` 当前值；创造台被关掉时会写 `INERT: creative rack switched off`；
- 取用时武器会怎样：`(and the template STAYS on the rack)` 或 `(and the rack empties)`；
- 吸收状态：`absorb=true radius=0.75 height=1.25 every=8t -> nothing dropped in range` 或 `-> would absorb <物品名> x1 of x<总数> [<坐标>]`。

日志同时写 `[rack] test at …`。

### 5j. The gunner villager (武装村民) — a villager that armed itself


A new friendly gun unit, `tarkovscav:gunner_villager`, built entirely out of vanilla villager parts plus
the shared gun AI. Nothing about the fight is forked: it implements the same `GunUser` interface as the
scav and the gunner pillager, so `GunBrain`, `GunAttackGoal`, the cover/reload/suppression/retreat
tactics and the clip selection are literally the same code.

**What is reused, and how**

| Piece | Where it comes from | Evidence |
| --- | --- | --- |
| model geometry | `VillagerModel` baked from `ModelLayers.VILLAGER` | `client/GunnerVillagerModel.java` extends it; no new `.geo.json` |
| textures | `textures/entity/villager/villager.png` + the vanilla type/profession overlays | `client/GunnerVillagerRenderer.java` copies the vanilla `VillagerRenderer` setup, including the `VillagerProfessionLayer` constructed with the same `"villager"` path prefix vanilla passes |
| sounds | `SoundEvents.VILLAGER_AMBIENT/_HURT/_DEATH` | inherited from `Villager`, which already overrides all three - this class deliberately does not touch them |
| skins | `VillagerData` (biome `VillagerType` + `VillagerProfession`) | set in `finalizeSpawn`; the profession is `WEAPONSMITH` and the biome type is the spawn biome, so the same mob looks right in every biome and "keep the profession/biome skins" costs no assets |
| combat | `GunBrain` / `GunAttackGoal` / `ArmPose` / `GunClips` | identical to `ScavEntity` and `GunnerPillagerEntity` |

**The arm pose: villagers are not illagers.** Vanilla illagers have `AbstractIllager#getArmPose()` and an
`IllagerModel` whose arms are separate parts - that is how the gunner pillager gets its look. A villager
has neither: `VillagerModel`'s mesh has one static `arms` block (`head`, `hat`, `hat_rim`, `nose`,
`body`, `jacket`, `legs`, `arms`) and its `setupAnim` animates only `head` and the two legs, so the
crossed arms are baked in. It also does not implement `ArmedModel`, and vanilla's `ItemInHandLayer` is
declared `<T, M extends EntityModel<T> & ArmedModel>` - so the held item cannot even be attached. The
subclass therefore adds exactly two things:

1. an arm rotation driven by the synced `GunAiState` through `ArmPose` (the same single source of truth
   the rig uses, so the villager cannot be "aiming" while the brain is idle) - angle from
   `client.gunnerVillagerAimArmPitch` / `_HoldArmPitch`;
2. `ArmedModel#translateToHand`, whose body is the same single line `HumanoidModel` uses
   (`getArm(arm).translateAndRotate(poseStack)`). With it in place the vanilla `ItemInHandLayer` applies
   its own standard hand frame (`mulPose XP -90`, `mulPose YP 180`, `translate ±1/16, 0.125, -0.625`) -
   exactly the frame TaCZ's third-person gun positioning groups are authored for, and the same frame
   `GunInHandGeoLayer#applyVanillaHandFrame` reproduces for the rig.

The two angles and `gunnerVillagerGunOffset` are config keys because which number *looks* right on a
vanilla villager body is an eye question; `/tarkovscav client reload` applies a change on the next frame.

**Tuning the gun position** - the whole point of the four `gunnerVillagerGun*` keys:

```
/tarkovscav client villagerpose                  # print what is in effect now
/tarkovscav client villagerpose back=0.05        # semantic axis: -Z is forward, so this moves it back
/tarkovscav client villagerpose yaw=15           # degrees, applied X then Y then Z
/tarkovscav client villagerpose anchor=body      # pin it to the torso instead of the arms
/tarkovscav client villagerpose aim=-90 reload=-60 hunker=-20   # the other three ARM pitches, live
/tarkovscav client villagerpose hold=0           # ... and the idle one (0 = the vanilla villager's arms; -20 = 20° above that)
/tarkovscav client villagerpose idlePitch=-47    # gun rotation: LOWERED only
/tarkovscav client villagerpose reloadPitch=0    # gun rotation: RELOADING only
/tarkovscav client villagerpose hunkerPitch=0    # gun rotation: RETREAT only
/tarkovscav client villagerpose reset            # back to the shipped baseline
```

**Every pose is tunable from this one command.** Four arm pitches (`aim` / `hold` / `reload` / `hunker`) and
four gun rotations - the base `pitch/yaw/roll` plus one three-axis **delta per pose** (`idlePitch/idleYaw/
idleRoll` for `LOWERED`, `reloadPitch/...`, `hunkerPitch/...`). `RAISED` (aiming and firing) is the base
rotation itself and has no delta, which is why the base stays the user-confirmed `["-40","0","0"]`. `reset`
restores all of them from the `DEFAULT_*` constants - the single source of truth, so it can never resurrect an
older number. No argument (and `client state`) prints all of them back.

**Do the arm angle first.** The gun hangs off the villager's `arms` block (that is what
`gunnerVillagerGunAnchor = arms` means), so changing `gunnerVillagerAimArmPitch` moves the gun with it -
settle the pose, *then* tune the offset, or the work is thrown away. That is also why the offset default is
marked as a user-confirmed value: it was measured with the arm angle already at `-90`.

**The arm angle and the gun rotation ADD.** Both are X rotations on the same pose stack - the arm's own
`translateAndRotate`, then `gunnerVillagerGunRotation`, then the layer's constant `-90` - so the gun's tilt
is `armPitch + gunPitch - 90`, and changing the arm pose moves the gun by exactly as much, in the same
direction. With the shipped defaults:

| Pose (state) | arm pitch | gun rotation in force | resulting tilt |
| --- | --- | --- | --- |
| RAISED (ALERT/AIM/FIRE/SUPPRESS/BOLT/REPOSITION) | 偏移 `-57`（= 原版 -43 之上的绝对 **-100**） | `gunnerVillagerGunRotation` = `["5","0","0"]`（**基准，没有 delta**） | **-185** ← the pose the user tuned, muzzle level |
| LOWERED, weapon in hand (IDLE) | **原版抱臂 (-43°)** + 偏移 `0` | base **+ `gunnerVillagerIdleGunRotation` = `["-2","0","0"]`** | **-130**（**水平**，手臂就是原版村民的位置） |
| RELOADING (RELOAD) | **原版抱臂 (-43°)** + 偏移 `0` | base **+ `gunnerVillagerReloadGunRotation` = `["0","0","0"]`** | -128（和闲置同高，用户要求"换弹也这样"） |
| HUNKERED (RETREAT) | **原版抱臂 (-43°)** + 偏移 `0` | base **+ `gunnerVillagerHunkerGunRotation` = `["0","0","0"]`** | -128（同上，用户把 hunker 也设成原版位置） |

（表里的"resulting tilt"= `ARMS_REST + armOffset + gunRot.x + 本档 delta.x - 90`，`ARMS_REST ≈ -42.97`；四档各有一个自己的三轴 delta，**RAISED 的 delta 恒为 0**，所以瞄准/开火档就是基准值本身。**上面四个数是 2026-09-25 起的发布基线 = 用户在游戏里亲自校准的那一组**。）

**枪的旋转按状态分档（用户原话：「不同状态下枪的旋转角度」）**：`gunnerVillagerGunRotation` 是**所有状态的基准**（`RAISED` 就是它本身，没有 delta），另外三档各有一个**独立的三轴 delta**，`translateToHand` 只做加法：
`gunnerVillagerIdleGunRotation`（`LOWERED`）、`gunnerVillagerReloadGunRotation`（`RELOADING`，默认 `["0","0","0"]`）、`gunnerVillagerHunkerGunRotation`（`HUNKERED`，默认 `["0","0","0"]`）。这样"四个状态各自一个角度"表达得出来，而瞄准/开火那份用户已校准的数值一个数都不用动。在线调这三个 delta 用 `idlePitch/idleYaw/idleRoll`、`reloadPitch/reloadYaw/reloadRoll`、`hunkerPitch/hunkerYaw/hunkerRoll`（三轴同构，通常只需要 pitch）。

**2026 闲置姿态修复（用户原话：「村民的闲置状态可以让他的手正常归位吗」+ 截图：站着不动双臂是抬起的）**：
`LOWERED`（拿了枪、但处于 `IDLE`）**不再套用战斗抬臂角度**，改用这一档自己的 `gunnerVillagerHoldArmPitch`。
瞄准 / 换弹 / 撤退三档**一个都没动**（仍是 `-90` / `-60` / `-20`，用户已认可的战斗姿态）。
写回是**必须**的、不是可选优化：`ModelPart` 的旋转会跨帧保留，所以瞄准之后若不显式写回，手臂就会一直举着。
`client.hideGunWhenIdle = true`（默认 `false`）可以让闲置时**不画枪**——纯口味开关，不改默认观感。

**2026 第三次闲置修复（用户原话：「能不能让他闲置的时候和正常村民手臂的位置一样」+ 截图：闲置时双臂平放在身前、插进身体）**：
这一次找到了**真正的根因**。原版网格把抱臂的姿势**烤在 `PartPose` 里**（`xRot = -0.75 rad ≈ -43°`），而 `VillagerModel.setupAnim` **从不重写它**——所以"原版位置"**不是 0**。
而这一档（以及"手里没枪"那条路）过去写的是**绝对角度**：`0` 就把抱臂**压平进肚子**（用户 2026 年先后看到的"手插在身体里"和"平放在下面"都是这个），`-20` 也只是个绕路。
现在 `GunnerVillagerModel` 在构造时把**烘焙值抓下来**（`armsRestXRot`），闲置档写 **`armsRestXRot + gunnerVillagerHoldArmPitch`**，"手里没枪"那条路也恢复成烘焙值：
**`gunnerVillagerHoldArmPitch = 0` 出厂默认 = 完完全全的原版村民手臂**；负值才是在原版基础上往上抬。
瞄准 / 换弹 / 撤退三档**一个都没动**（仍是绝对角度 `-90` / `-60` / `-20`，用户已认可的战斗姿态）。
写回是**必须**的、不是可选优化：`ModelPart` 的旋转会跨帧保留，所以瞄准之后若不显式写回，手臂就会一直举着。
`client.hideGunWhenIdle = true`（默认 `false`）可以让闲置时**不画枪**——纯口味开关，不改默认观感。


**2026 第四次姿势修复（用户原话：「然后需要换弹的时候也这样 现在换弹还是会放平胳膊，以及需要闲置的时候手里也有枪」）**：
前三次把 `LOWERED` 改成了"原版抱臂 + 偏移量"，**另外三档却还是绝对角度**——用户把 `hold` 的
"0 = 原版"心智模型套到 `reload`/`hunker` 上（他设 `reload=-30`），结果换弹时手臂**比原版还低**，
看起来就是"胳膊放平"。这次把**四档统一成同一种数**：
`RAISED = rest + aim`、`RELOADING = rest + reload`、`HUNKERED = rest + hunker`、`LOWERED = rest + hold`
（`rest` = 原版抱臂的烘焙值 `armsRestXRot ≈ -43°`）。**四档出厂默认全部 `0.0` = 原版村民手臂**。
用户 toml 的换算：`aim -100 → -57`（偏移 = 绝对 + 43）、`reload -30 → 0`、`hunker 0 → 0`、`hold 0 → 0`。

**同一次修复还给了枪的"位置"每档一个 delta**：`gunnerVillagerGunOffset` 是四档共用的基准，
所以"闲置时把枪从身体里拿出来看手里是什么武器"做不到；现在
`gunnerVillagerIdleGunOffset` / `ReloadGunOffset` / `HunkerGunOffset` 各是一个三轴位置增量，
**默认 `["0","0","0"]`**，`translateToHand` 里按当前档**加到**基准上（`RAISED` 没有 delta）。
在线调：`idleX/idleY/idleZ`、`reloadX/reloadY/reloadZ`、`hunkerX/hunkerY/hunkerZ`。

**2026 闲置枪口朝上修复（用户原话：「村民举枪，需要向下调整 90°」+ 截图：站着不动、枪竖着朝上）**：
手臂归位后，**枪是挂在 `arms` 上的**，于是它继承了原版静止朝向 → **枪口朝上**。修法与手臂角度同款：
**给枪也分档**——`client.gunnerVillagerIdleGunRotation`，**只在 `LOWERED` 叠加**在
`gunnerVillagerGunRotation` 之上，其余三档完全不受影响（三轴而不是单 pitch，是为了与 `gunnerVillagerGunRotation`
同构、并且万一闲置还需要一点 yaw/roll 时不必再加键；通常只需要 pitch）。
默认值 **`["-47","0","0"]`**（第一版是 `-90`，第二版是 `-70`）：枪锚在 `arms` 上、走 `translateToHand` 的 `anchor.translateAndRotate`，
所以**手臂角度变了多少，枪就跟着转多少**。闲置手臂从"被压平的 0"恢复成**原版抱臂的 -43°**，枪就多转 43°，
于是 idle 修正从 `-90` 改成 **`-47`** 正好抵消——**枪口朝向回到用户已经校准过的样子**，
手臂则待在原版村民的位置上（闲置 tilt 仍是 `-43 - 40 - 47 - 90 = -220`）。
现场调：`/tarkovscav client villagerpose idlePitch=-47 idleYaw=0 idleRoll=0`（下一帧生效、写盘、`reset` 复位、
`client state` 会打印 `idleRot=[...] (idle only)`）；换弹/撤退档现在各有一个同样形态的 delta
（`reloadPitch/...`、`hunkerPitch/...`，默认 `["0","0","0"]`）。
⚠️ **两个键的分工（别改错）**：**闲置**姿态偏高 → 改 **`gunnerVillagerIdleGunRotation`**（只影响 `LOWERED`）；
**瞄准/换弹/撤退**整体偏高 → 改 **`gunnerVillagerGunRotation`**（影响所有档，现在是发布基线 `["5","0","0"]`）。

`gunnerVillagerGunRotation` 的历史是三次校准：出厂 `-90`，用户看到枪口朝天（「现在这个朝天上看了」）要求回 **50°**，于是 `-90 + 50 = -40`
—— 那次是 **USER-CONFIRMED in game**，而且是就**瞄准**姿态判定的（村民只要有目标就是这个姿态）；后来他在自己实例里
又把它定到 **`["5","0","0"]`**，并认可为**发布基线**（2026-09-25）。**闲置**那档恢复的是**原版抱臂位置**
（烘焙的 `-0.75 rad ≈ -43°`），`gunnerVillagerHoldArmPitch = 0` 就是从它出发的**偏移量**，闲置枪修正
（`gunnerVillagerIdleGunRotation = ["-2","0","0"]`）把枪口拉回他确认的方向（闲置 tilt = `-43 + 5 - 2 - 90 = -130`）。
这几个数是一个姿势的不同分量，不是各自独立的口味：手臂在原版基础上抬多少（负的 `hold`），闲置枪修正就要反向补多少。

The axis names follow the same convention as `/tarkovscav client gunpose` and the rig's
`gunMountRifleOffset`: the character faces `-Z`, so `forward=n` is `z -= n`, `back=n` is `z += n`,
`right=n` is `x += n`, `left=n` is `x -= n`, `up=n` is `y -= n`, `down=n` is `y += n`.
**Both angle defaults are user-measured**: the aim angle shipped as `-70`, the user looked at that in game
and asked for another 20° up, so it is now `-90` (more negative = higher). The **hold** angle is an offset
from the vanilla crossed-arms rest and ships as **`0` = 原版村民的位置**（2026 第三次闲置报告：「让他闲置的时候
和正常村民手臂的位置一样」）；negative values lift the arms above that rest. Both `client villagerpose pitch=...` /
`hold=...` and hand-editing the toml reach the same
value: the command writes the config (`Config.SPEC.save()`), so it survives a restart, and `client state`
prints the value in force.

**Villagers are a Brain mob.** Their work/stroll/panic behaviour writes `WalkTarget` memories, and
`MoveToTargetSink` turns those into navigation **without consulting goal flags** - left running during a
fight it would keep overwriting the path `GunAttackGoal` just issued. `GunnerVillagerEntity` therefore
parks the brain (`customServerAiStep`) while it has a target and runs the vanilla brain untouched when it
does not.

**Who it fights**

| | Target | Why |
| --- | --- | --- |
| hostile to | `AbstractIllager` - vanilla pillagers, vindicators, illusioners **and** this mod's `gunner_pillager` (a `Pillager`) | one class covers all of them; `NearestAttackableTargetGoal(this, AbstractIllager.class, true)` |
| hostile to | whoever hurt it, players included | `HurtByTargetGoal` - a friendly mob that ignores being shot would be worse than a hostile one |
| friendly to | players, villagers, iron golems | simply never targeted. Iron golems leave it alone because they look for `Enemy` and `Villager` is not one (verified in `IronGolem`'s goal list) |
| test target | anything tagged `tarkovscav_dummy` | so `/tarkovscav test fight|watch|stall` works on this mob too |

**Spawning.** `spawn.gunnerVillagerCityOnly` (default `true`) puts it behind the same `CityGate` as the
other two, with the same `[spawngate] ACCEPT|REJECT …` log lines; `spawn.gunnerVillagerNaturalSpawn`
(default `true`) can take it out of the biome spawner entirely, in which case the refusal is logged in the
same shape with its own reason. Its natural weight is **2** against the scav's 5 and the gunner
pillager's 2 - a friendly gun unit should be a rare, memorable encounter, not the default city
population.

**Verifying it**: `/tarkovscav spawn gunner_villager` (goes through the gate; refuses outside a city with
the reason), then `/tarkovscav debug` shows its state machine like any other gun mob, and
`/tarkovscav test watch 20 16 [pos]` makes it shoot a practice dummy. `node tools/selftest_gunner_villager.js`
checks the reuse claims above from the source.

### 5z. 硬目标：铁傀儡减免 80% 枪械伤害 + 概率跳弹（`[ricochet]`）

用户原话：「能不能写个**铁傀儡减免 80% 枪械伤害**，而且**打他身上有概率跳弹**。」

#### "这是不是枪械伤害"——用 TaCZ 自己的伤害类型标签判定
不看开枪者的物品，不看弹丸的类，也不猜伤害类型名：TaCZ 在 jar 里自带一张**伤害类型标签**
`#tacz:bullets`（`data/tacz/tags/damage_type/bullets.json`，实测内容：`tacz:bullet`、
`tacz:bullet_ignore_armor`、`tacz:bullet_void`、`tacz:bullet_void_ignore_armor`），所以判定就是一句
`damageSource.is(TACZ_BULLETS)`。**近战 / 爆炸（含我们自己的手雷）/ 摔落 / 火焰 / 魔法 / 溺水**全都不满足它，
**完全不受影响**（闸门逐类型仿真断言）。箭矢默认也不算（`ricochet.includeArrows=false`；打开后箭矢也算"枪械伤害"）。

#### 顺序：先掷跳弹，再谈减免
1. 受害者在 `#tarkovscav:hard_target` 标签里（默认**只有 `minecraft:iron_golem`**）；
2. 伤害是 TaCZ 子弹（或打开开关后的箭矢）；
3. 掷 `ricochet.chance`（默认 **0.3**）：**命中跳弹 → 这一下伤害归零**（不是"减免后再掉血"），弹丸被**反射**，伴随金属声与火花；
4. **没跳弹** → 伤害乘以 `ricochet.gunDamageMultiplier`（默认 **0.2** = 用户要的**减 80%**）。
> 这个顺序就是功能本身的意义：跳弹必须是**零伤害**。

#### 反射怎么算
法线取**受害者碰撞箱上离弹丸最近的点 → 弹丸**的方向（所以打胸口往前弹、打肩膀往侧弹），速度按
`v' = v − 2(v·n)n` 反射；退化情况（弹丸已在箱内 / 法线为零）退化为"反向 + 小随机抖动"。只对
`Projectile` 动手（`setDeltaMovement` + `hasImpulse`），**并把弹丸推出碰撞箱**，否则下一 tick 它还在傀儡肚子里。
每颗弹最多弹 `ricochet.maxBouncesPerBullet`（默认 1）次——**两个傀儡之间不会无限对弹**。
**诚实说明**：反射之后**能不能继续飞**是 TaCZ 的事（它自己的命中处理可能在同 tick 就把弹丸 `discard`；穿甲弹才会继续）。
**方向反射、零伤害、音效、火花一定发生**；"它又打到了别人"取决于那颗弹——需实测（见下）。
> 音效是原版 `SoundEvents.TRIDENT_HIT_GROUND`（`minecraft:item.trident.ground_impact`）：1.20.1 的
> 资源索引里根本没有 `item.trident.ricochet` 这个文件（那是更高版本才加的），所以用最接近"金属撞击"的原版音，
> **不新增任何音频资源、也不需要注册**。

#### 配置
| Key | Default | Meaning |
| --- | --- | --- |
| `ricochet.enabled` | `true` | 总开关；false = 完全惰性（枪械伤害全额、永不跳弹） |
| `ricochet.gunDamageMultiplier` | `0.2` | 未跳弹时的伤害倍率（0.2 = 减 80%）；`0` = 未跳弹也零伤害，`1` = 不做减免 |
| `ricochet.chance` | `0.3` | 跳弹概率；`0` = 从不，`1` = 必定（跳弹即零伤害） |
| `ricochet.maxBouncesPerBullet` | `1` | 每颗弹最多弹几次；`0` = 只做减免、不反射任何弹丸 |
| `ricochet.includeArrows` | `false` | 箭矢是否也算"枪械伤害"（默认不算：箭矢照旧全额、不跳弹） |
| `ricochet.soundVolume` | `0.8` | 跳弹音（原版 `SoundEvents.TRIDENT_HIT_GROUND` = 三叉戟砸地金属声）音量；0 = 静音但仍有火花 |
| `ricochet.sparks` | `true` | 是否生成火花（`ParticleTypes.CRIT` + `ELECTRIC_SPARK`） |
| `ricochet.log` | `false` | 每次跳弹打一行日志（默认关：和傀儡对射会刷屏） |

#### 怎么把别的生物也加进来
编辑 `data/tarkovscav/tags/entity_types/hard_target.json` 的 `values`（`replace: false`，所以是**追加**语义）：
```json
{ "replace": false, "values": ["minecraft:iron_golem", "minecraft:ravager", "somepack:mech"] }
```
数据包/整合包改这个 JSON 即可，**不用重编译**；想只对某一个生物测试，就把它单独留在里面。

#### 闸门
`tools/selftest_ricochet.js`：判定来自 `#tacz:bullets`（源码断言 + **从 TaCZ jar 里解出那张标签并核对 4 个 id**）；
标签文件与默认成员；**顺序**（先掷、再置零；未跳弹才乘）；倍率线性与 `0/1` 边界；**逐类型仿真**：近战/爆炸/摔落/火焰/魔法/溺水**都不受影响**；不在标签里的实体（僵尸）照旧全额；`enabled=false` 惰性；
反射算术（垂直命中正面沿切向原速返回、45° 肩部往前上方弹）、每弹上限、非 `Projectile` 直系实体只做零伤害+特效；
音效/粒子/音量开关被调用；配置键与 README 齐；事件监听器已注册。

### 5zb. 「VANT 防弹盾牌」（`tarkovscav:vant_shield`，`[shield]`：正面挡 99% 枪弹，手雷照打）

用户原话：**「耐久 500；被打空耐久才会碎掉消失；持有者的正面朝向会抵挡 99% 的子弹伤害；但是无法防御手雷伤害。」**
本批次按已定的方案落地：**拿在主手或副手即被动生效**，没有右键「举盾」状态（要加的话是在同一个锥形数学前面加一道"是否举着"的门，见文末）。

#### 判定链（顺序就是语义）
1. `shield.enabled`（默认 `true`）总开关；
2. **`source.is(#minecraft:is_explosion)` 且 `shield.protectFromExplosions = false`（默认）→ 直接返回，什么都不做**：手雷、HE、闪光、TNT、苦力怕爆炸一律**不减免**——这就是用户要的「无法防御手雷伤害」。
   > 1.20.1 的 `DamageSource` **没有** `isExplosion()` 这个方法，所以这里问的是原版伤害类型标签 `#minecraft:is_explosion`——和「是不是枪弹」同一个形状的判定，而且改过爆炸伤害类型的模组只要加进那张标签也会被算进来；
3. 「这是不是枪械伤害」**直接复用铁傀儡跳弹那套判定**：`combat/HardTarget.isGunfire(source)`，即 TaCZ 自己的伤害类型标签 `#tacz:bullets`（`tacz:bullet`、`tacz:bullet_ignore_armor`、`tacz:bullet_void`、`tacz:bullet_void_ignore_armor`）。近战 / 摔落 / 火焰 / 魔法 / 我们自己的手雷破片全都不满足，**完全不受影响**；箭矢仍与 `ricochet.includeArrows` 共用同一个开关（默认不算）；
4. 持有者**主手或副手**真的有盾牌（主手优先）；
5. 伤害来自**正面锥形**之内。

#### 正面锥形怎么算（一个点积）
`look = holder.getViewVector(1.0F)`；`toSource = 来源位置 − 持有者眼睛位置`。
来源位置的回退链：`DamageSource.getSourcePosition()`（有弹丸时就是弹丸）→ 攻击者 `getEntity()` 的位置 → **null**。
`dot(look.normalize(), toSource.normalize()) >= cos(shield.frontAngleDegrees) − 1.0E-9` 才算在正面锥形内，**角度恰好等于半角的边缘也算挡下**。
- `shield.frontAngleDegrees` 默认 **90** = 正面 **180 度**锥形；45 = 只有 90 度窄扇面；180 = 前后全包。
- **来源位置未知时按「在正面」处理**（函数返回 true）：这是对持有者更安全的一侧；真实的 TaCZ 子弹一定带位置，所以这一条实际到不了。
- 每个测试向量的角度都在 `tools/selftest_shield.js` 里算出来并打印。

#### 减免、耐久与碎裂
命中在锥形内时：`event.setAmount(before × (1 − shield.bulletReduction))`，默认 **0.99 → 只剩 1%**（20 伤害的子弹落地 0.2）。
每次**真正挡下**的一击扣耐久 `max(1, ceil(被挡下的伤害 / shield.durabilityPerBlockedHit))`，默认 `2.0`：
20 伤害的子弹被挡下 19.8，扣 `ceil(19.8 / 2) = 10` 点耐久 → **500 耐久刚好扛住 50 发**，第 51 发让它碎。
- 扣到 0 就**碎掉消失**：那一击的伤害照减，随后 `ItemStack` 从手上移除（`setItemInHand(hand, ItemStack.EMPTY)`）、播放碎裂音与粒子、并给持有者一条红字消息（`tarkovscav.shield.broken`）。**碎掉之后什么都不再挡**（判定链第 4 步直接返回，破损的盾牌不存在于任何手上）。
- 每次挡下都有反馈：原版 `SoundEvents.SHIELD_BLOCK`（金属挡格）+ `ParticleTypes.CRIT` 与 `ELECTRIC_SPARK`；碎掉那一下额外加 `SoundEvents.SHIELD_BREAK` 与更多粒子。都是原版音效常量，**不新增资源、不需要注册**。

#### 配置
| Key | Default | Meaning |
| --- | --- | --- |
| `shield.enabled` | `true` | 总开关；false = 完全惰性（不减免、不扣耐久、不会碎） |
| `shield.bulletReduction` | `0.99` | 正面命中减免比例（0.99 = 只剩 1%）；`1.0` = 全免，`0.0` = 不挡也不扣耐久 |
| `shield.frontAngleDegrees` | `90` | 正面锥形**半角**（度），从持有者视线量起；90 = 180 度正面，45 = 90 度，180 = 全向 |
| `shield.durabilityPerBlockedHit` | `2.0` | 每点耐久抵多少被挡下的伤害；扣耐久 = `max(1, ceil(被挡伤害 / 这个值))` |
| `shield.protectFromExplosions` | `false` | **默认 false = 爆炸（手雷/TNT/苦力怕）永远不减免**；只有显式改 true 才会把爆炸也送进锥形判定 |

#### 合成（生存可做）
`data/tarkovscav/recipes/vant_shield.json`：中间一块玻璃，上下左右与四角共 1 铁块 + 5 铁锭。
```
I N I
I G I
  I
```
`I = minecraft:iron_ingot`、`N = minecraft:iron_block`、`G = minecraft:glass`。

#### 还没有的东西（下一批可以加）
**右键举盾**：本批没有 use 状态，拿在手上就一直生效。要加就是在锥形判定之前插一个"是否举着"的门（例如 `use` 通道 + `getUseItem`），`VantShieldHandler.insideCone` 这个纯函数不用动。

#### 闸门
`tools/selftest_shield.js`：注册名 / 耐久 500 / `stacksTo(1)`；事件监听器已注册；爆炸旁路 + `protectFromExplosions` 默认 false（逐情况真值表）；锥形点积**逐向量单测并打印每个角度**（正前 0°、正后 180°、恰好等于半角的边界、来源未知，以及 60/0/180 三档半角）；减免线性；耐久公式单调且第 50 发碎；碎裂路径（移除物品 + 音效 + 粒子 + 消息）；模型 / 贴图 / 配方 / 中英双语键齐、两种语言键集合逐一相同；新增 JSON 里不出现任何 `tacz:` 物品 id；README 与 `docs/COMMAND_AND_CONFIG_REFERENCE.md` 逐键对齐。

### 5g. The "upper body shows another outfit at 45 degrees" report

The report: from one particular ~45° angle a scav's upper body is replaced by a white plate/bowl, picture
frames, a sign with writing, a yellow flower and grass - i.e. by **item/block textures**. Both of the
mechanisms that were suspected were tested and are **wrong**:

* **"the upper body is drawn twice" - no.** GeckoLib walks the bone tree once per render
  (`GeoRenderer#actuallyRender` calls `renderRecursively` per top-level bone, and the item layer is
  invoked once per bone from inside that same walk), this mod registers exactly one renderer per entity
  type, adds exactly one item layer to it, never calls `GeoRenderer#reRender`, and never toggles bone
  visibility per pass - the only `setHidden(true)` calls are in the once-per-*bake* pass in
  `RigSupport`. `node tools/selftest_single_pass.js` asserts all of that, and
  `client.logRenderStats = true` counts it at runtime: one geometry pass per mob per frame, warned about
  if it is ever more. The two-controller scheme (`modelLayering = upperLower`) is a *layering*: the gun
  clips animate **0 of the 8 leg bones** and the armed movement clips touch no upper-body bone except
  `Head` in `tac:idle`, which the controller order resolves.
* **"the layer polluted the model's texture state" - no.** The model's render type is chosen once per
  pass (`GeoRenderer#getRenderType` -> `GeoModel#getRenderType`, the default
  `RenderType.entityCutoutNoCull(texture)`) and that texture is
  `tarkovscav:textures/entity/scav.png` - a **direct file, not an atlas**, because entity render types
  carry a `TextureStateShard` for the file. Vanilla's `MultiBufferSource.BufferSource` keeps one builder
  per `RenderType` and flushes each with its own shader and texture, so the item the layer draws (a TaCZ
  gun, its own texture) cannot rebind anything the model's vertices will be flushed with;
  `BlockAndItemGeoLayer#renderForBone` even re-fetches its buffer afterwards. The pixels in the
  screenshot (bowl `textures/item/bowl.png`, `painting`, `oak_sign`, `block/dandelion`,
  `block/grass_block_top`, `block/item_frame`, `block/flower_pot`) all live under
  `assets/minecraft/textures/{item,block}` and are stitched into the **block atlas at runtime**, which
  this mod's model never binds.

So the foreign pixels come from **world geometry drawn in front of the mob** - dropped items, item
frames, signs, flowers, grass - which is why it is angle dependent: flat item/painting sprites are only
visible when they face the camera. The mobile is drawn normally behind them; the lower body is clear
because the pile is at torso height.

Two things this mod *does* contribute, both measured, neither of them a texture mix-up:

1. **A few genuinely see-through faces.** The author hides spare geometry by pointing a cube's face at
   empty (alpha 0) atlas space, which `entityCutoutNoCull` discards. A runtime scan that transforms every
   cube per pose and ray-casts both ways (`node tools/scan_transparent_faces.js`) finds **12 such faces
   in the rest pose** (4 on the backpack `Bag[27]/[28]`, 4 on the head `Head3[11]`, 2 on `Glass[2]`, 2 on
   `bone4`), and 2-12 depending on the clip, because the clothing layers sit on different bones and move
   relative to each other. `node tools/patch_transparent_faces.js --dry` prints a per-face fix table
   (12 of 12 faces have an opaque donor on the same cube or a neighbour 1.3 units away) and
   `--dry`-free applies it - it is **not applied by default**: the faces are small (a few tenths of a
   block), they cannot account for a whole upper body's worth of foreign pixels, and rewriting the
   author's UVs without seeing the result is exactly the kind of blind change this project avoids. It is
   a documented, one-command opt-in.
2. **The item layer draws the held gun where the model is** - a TaCZ gun is textured from its own files,
   so at worst it shows *gun* pixels, never a bowl or a painting.

### The engine-level angle: what MC itself does, and the two A/B switches

The report was re-framed as "like looking through a translucent block at one spot, the entity is not
drawn - an engine-level thing". What the engine actually does, checked in the sources:

* **Frustum culling** uses `Entity#getBoundingBoxForCulling()` (inflated by 0.5 in
  `EntityRenderer#shouldRender`). A raider hitbox is **0.6 x 1.95** blocks, while this rig renders
  **~2.8 x 3.85** blocks (4 x 5.5 model units at `renderScale = 0.7`, from the geo's `visible_bounds`)
  and the held gun reaches further still. So the visible geometry is *larger than the box being tested*:
  a mob that is partly on screen can be culled because its box is not. That is a real defect class, and
  `client.cullingBoxPadding` (default 1.0 block) fixes it; the cost is only that a few more mobs pass
  the cull test - no extra draw call, and `0` restores vanilla.
* **Per-bone culling**: GeckoLib has none. `renderRecursively` walks every top-level bone and every child
  unconditionally; the only per-bone skip is `GeoCube` visibility (`bone.isHidden()`), which this mod sets
  once per bake, never per frame. So no part of the rig is dropped by GeckoLib at an angle.
* **Depth ties with world geometry** are the plausible reading of "I see the scenery through the mob":
  grass, flowers, item frames and dropped items that *intersect* the body win or lose the depth test
  depending on the angle, because the two surfaces are coplanar-ish. `client.modelRenderType = zOffset`
  draws the rig with the stock `entityCutoutNoCullZOffset` - the same look plus a -1/-10 depth bias
  towards the camera, the same bias MC uses for its crumbling overlay - so on a tie the mob wins. Cost:
  nothing measurable; the side effect is that blocks the mob is embedded in are hidden slightly less
  often.
* **No `RenderSystem` state is touched by this mod** (asserted by `tools/selftest_single_pass.js`):
  `RenderType#setupRenderState`/`clearRenderState` own the shader, blend, cull and colour-mask state, and
  vanilla's `BufferSource` flushes every batch through that pair, so the item layer cannot leave the
  model's draw with a changed state.

Three steps for the user, standing in tall grass or next to glass with the mob at the bad angle:

1. Leave `modelRenderType` on `cutout` and set `cullingBoxPadding = 0` -> if the mob *disappears
   entirely* at some camera positions, that was the culling box; put it back to 1.0 and keep it.
2. Set `modelRenderType = zOffset` and `/tarkovscav client reload` -> if the scenery stops showing
   through the body, it was a depth tie with intersecting world geometry; keep it.
3. `modelRenderType = translucent` then `solid` -> these are diagnostics: `translucent` loses depth
   writes (the mob can end up behind water/glass) and `solid` draws the author-emptied faces as black
   patches. If `solid` removes the artifact but adds black spots, the see-through faces from §5g are the
   remaining cause and `tools/patch_transparent_faces.js` is the fix.
The 30-second test for the user, in order:

1. `/kill @e[type=item]` next to the mob (and step away from any item frames / signs / flowers), then look
   again from that 45° angle. **If it is gone, it was world sprites in front of the mob** and this mod was
   never involved - that is the expected outcome.
2. `/tarkovscav client hide Bag` (the biggest cluster of see-through faces is the backpack).
   **If the artifact shrinks or changes, those holes are part of it**; then run
   `node tools/patch_transparent_faces.js` and re-import to close all 12.
3. `/tarkovscav client gunpose scale=0` removes the gun from the picture; if the artifact goes with it,
   it is the weapon draw, not the body.
### 5c. The head accessories: what was measured, and what the defaults keep

Every bone of the rig was dumped (`node tools/classify_head_parts.js` and
`node tools/scan_accessories.js`: parent chain, rest pivot, rest rotation, own cubes, cube AABB, atlas
patch). The head branch is `Head` → (`Glass`, `Head3`, `Ear`, `Hat1`, `Hat2`, `hat3`) with
`Head3` → (`Eyes`, `YanJing`, `Yan`), and that is what makes the classification unambiguous:

| Bone | Parent | Cubes | Rest pivot | Evidence | Class |
| --- | --- | --- | --- | --- | --- |
| `Head3` | Head | 12 | `[-2.31, 34.01, -0.09]` | the head mesh itself (y 31.0…39.6), and the parent of the face | **head** - never hide |
| `Eyes` | Head3 | 0 | `[0, 33.80, 0]` | owns `Eyebrows`/`EyeBalls` → `LeftIris`/`RightIris` at the eye plane (y 34.8…35.4, z ≈ -3.5) | **the face** - never hide |
| `Glass` | Head | 4 | `[0, 35.02, -3.00]` | two 1.76×0.89×4.29 temple arms tilted ±7.5° that stop at the head silhouette (`|x| ≤ 4.06`, z ≥ -4.36) plus a 5.5-wide bridge across the eyes | **eye gear** |
| `YanJing` | Head3 | 17 | `[0, 34.97, -3.84]` | lens/frame cubes at the face plus two 4.6-wide side frames rotated ~131° that reach `|x| 8.18` - they wrap around the head | **eye gear** |
| `Hat1` | Head | 7 | `[-0.44, 37.14, 0.13]` | an 8.11×2.83×2.34 visor tilted +17.5°, two 1.1×2.7×7.81 side flaps, three crown slabs | **hat** |
| `Hat2` | Head | 12 | `[0.50, 37.00, 0]` | an 8×5×8 crown centred on the head (y 36…41) plus six ~22.5°/67.5°/135° segments that come down to y 32.5 over the ears, and a front brim | **hat** |
| `hat3` | Head | 6 | `[0, 40.71, 0.83]` | four stacked 8.5-wide slabs from y 35.5 to 40.2 and a 3×3 knob rotated to y 45.4 - a tall stack on top of everything | **hat** |
| `Yan` | Head3 | 2 | `[1.25, 32.63, -3.30]` | a 0.5×0.5×2.5 stick leaving the mouth at y 32.4 with an ember cube at its tip; atlas patch is dark | **cigarette** |
| `Ear` | Head | 2 | `[3.81, 45.66, -1.06]` | two cubes at ear height (y 33.7…36.3) on either side at `|x| 3.9…6.2`, i.e. the character's ears | **not headgear** - left alone |

Defaults: **keep `Hat2`**, hide `Hat1` and `hat3`; wear **`Glass`**, hide `YanJing`; cigarette **off**.

> **2026 重导出之后**：本 rig **只剩 `Hat2`**，`Glass`/`YanJing`/`Hat1`/`hat3`/`Yan` 这 5 根已经从模型里删掉，
> 所以默认改成 **`eyeGearAccessory = "none"`**、`hiddenBones` 清空，`keptHatBone` 仍是 `Hat2`。上表是**旧 rig**
> 的测量结果，保留下来是因为它正是 `hatBones`/`eyeGearBones`/`cigaretteBone` 这几个键的**词表来源**；这 5 个名字
> 现在会在运行时被**报一次**"not in this rig"（详见 §7 的"2026 模型重导出"）。

* `Hat2` because it is the only hat that covers the whole skull *and* comes down over the ears, so
  hiding the other two leaves no bare patch - and because its faces sample one compact 45×40 block of
  the atlas (a dedicated garment), while `Hat1`'s sample the whole atlas and `hat3` stacks four slabs
  plus a knob 6 units above the head. Switch with `headAccessories.keptHatBone = "Hat1"` or `"hat3"`.
* `Glass` because it stays inside the head silhouette, whereas `YanJing`'s side frames sit in exactly
  the same box as `Hat2`'s ear flaps (`x 3.42…7.63`, `y 32.5…36`) - wearing both means two pieces
  fighting for the same space. `eyeGearAccessory = "YanJing"` or `"none"` switches it.
* Nothing here is a "hide the bone that looks wrong" workaround: these are the author's accessories and
  the switch is the feature.

`node tools/selftest_accessories.js` recomputes the merged hidden set from the shipped defaults *and*
from an old-style toml (where only `hiddenBones` exists), so "the new keys are incremental and the old
file still behaves" is checkable without a game.

### 5d. The head rest pitch (idle only)

The report: the head sits about 20° too low **while idle**, and is correct as soon as the mob is
alerted or shot at. That matches the mechanism exactly, and here is the data
(`node tools/scan_head_keyframes.js src/main/resources/assets/tarkovscav/animations/scav.animation.json`):
the author's head keyframes are Molang that references YSM-only variables, and GeckoLib resolves an
unknown variable to 0, so each one **collapses to a constant** - e.g. `idle`'s `Head` rotation is
`["-0.7*ysm.head_pitch", ...]` → `0`, `1-0.7*... ` → `0`, while `tac:hold:rifle`'s arm channels collapse
to `-7.8865`, `0.8611` and `-2.2145`. A collapsed `0` on the head's X channel is exactly "level instead
of raised".

So `client.headRestPitchDegrees` (default `20`, **positive = up**) is added to the head inside
`RigSupport.applyAimTracking`, and only when:

* the mob is **not** aiming (`aiming == false`), and
* the synced gun-AI state is one of `headRestPitchStates` (default `["idle"]`).

Both paths are absolute writes, so neither can accumulate:

```java
head.setRotY(netHeadYaw * (1 - torsoShare) * DEG);
if (restPitch != 0) head.setRotX((headPitch * (1 - pitchShare) + restPitch) * DEG);
else                head.setRotX( headPitch * (1 - pitchShare)              * DEG);   // unchanged
```

The `else` branch is the pre-existing expression character for character: when the mob is alert or
aiming, `restPitch` is 0 by construction and the code that runs is the code that ran before.
`node tools/selftest_rig_pose.js` proves that (plus that no additive write exists on the head) and
`logHiddenBones = true` prints `[model] head rest pitch: +20.0 degree(s) added on bone 'Head' while the
gun AI state is IDLE …` once, so the effect can be confirmed from `latest.log`.

Why `Head` and not `Head3`: `Head3` is a **child** of `Head` and only carries the face
(`Eyes`/`YanJing`/`Yan`). The hats (`Hat1`, `Hat2`, `hat3`) and `Glass`/`Ear` hang directly off `Head`,
so rotating `Head` is the only choice that lifts the whole head assembly - hat included - as one.
`AllHead` (Head's parent) would also work, but the aim tracking already owns `Head`, and one bone
owning the head pose is one place to look.

### 5r. 「上半身扭来扭去」— who writes which bone, and the size of the twist (poseSource / molangVariables / torsoYawShare)

The report: **while the scav walks, its upper body twists about.**

**First, the mechanism.** GeckoLib writes a bone from the animation controllers first
(`AnimationProcessor#tickAnimation`) and this mod's code writes it afterwards
(`GeoModel#setCustomAnimations`), so the **later writer wins** - the visible angle is not a sum, it is
whichever writer ran last. That is why the same defect can look like a steady wrong angle *or* like a
twist that comes and goes: the winner changes with the AI state and with which clip is playing.
`client.poseSource` therefore exists to make **at most one writer** own each bone:

| `poseSource` | `UpperBody` | `Head` | arms |
| --- | --- | --- | --- |
| `code` | the code, `netHeadYaw × torsoYawShare` | the code, the remaining share + the rest pitch | the code, if the rig has no clip for them |
| `clips` | the clip, if it animates it | the clip | the clip |
| `auto` (default) | the clip when the clip drives that bone **from the entity's look** (Molang keyframes, not numbers) *and* the yaw symbols are fed; otherwise the code | same | the clip if it animates them, else the code |

The code's **total** yaw gain stays 1.0 over whatever set of bones is left to it: when the head becomes
the clip's, the share the head would have taken is folded into the torso (and the other way round), so
reviving the author's tracking cannot double - or halve - the angle.
`tools/selftest_rig_pose.js` checks the fold for all eight combinations, and
`tools/selftest_pose_writers.js` checks the result against the real clip data.

**The one combination to avoid** is `poseSource = code` **with** `molangVariables = all`: the code keeps
`Head` while the author's `UpBody` `-head_yaw` is live, and the two cancel on the same chain - the
measured gain is **0.000** and every one of the 600 frames has two writers on `Head`. `code` is a
fallback for `molangVariables = off` (or the default `pitch`), where it is bit-for-bit the old behaviour.

**Second, the size of the twist - and its root cause.** `EntityModelData#netHeadYaw` is by definition the
head's yaw **relative to the body**, so the head is where all of it belongs; a share on the torso makes
the chest over-rotate by exactly that fraction while the legs do not move at all. That *was* the twist:

| configuration | chest swing (peak-to-peak, ±25° look swing) | chain gain | lower-body swing |
| --- | --- | --- | --- |
| before: `torsoYawShare = 0.7` (shipped) | **45.3°** | 1.000 | 0.0° |
| now: `torsoYawShare = 0.25` (shipped) | **16.2°** | 1.000 | 0.0° |
| `torsoYawShare = 0.0` | 0.0° | 1.000 | 0.0° |

The gain is 1.000 in every row, so **the aim still lands in exactly the same place** - only the split of
the twist between chest and head changes. `tools/selftest_pose_writers.js` prints that table and asserts
the gain for each value; `/tarkovscav client pose torso 0.0` tries it live.

**Third, the author's own Molang.** `client.molangVariables` feeds the rig's own variables from the live
entity, so the keyframes that used to collapse to constants evaluate on a real value:

* `ysm.head_yaw` ← `EntityModelData#netHeadYaw`, `query.head_y_rotation` ← the same quantity;
* `ysm.head_pitch` ← `EntityModelData#headPitch`, `query.head_x_rotation` ← the same quantity;
* `query.is_sneaking` ← `pose == CROUCHING`.

`math.min` and `math.abs` are **functions, not missing variables**: GeckoLib's own
`MolangParser#doCoreRemaps` moves every mclib function registration to its Bedrock name
(`functions.put("math.min", functions.remove("min"))`, and the same for `abs`, `clamp`, `floor`, `round`,
`sqrt`, `pow`, `lerp`, … - read out of `geckolib-forge-1.20.1-4.8.4` with `javap`). So
`math.min(-7.8865-0.75*ysm.head_pitch,20)` is evaluated rather than defaulted to 0, and **nothing warns
about it**. `tools/scan_molang_variables.js` reports them as `FUNCTION`, and
`tools/selftest_pose_writers.js` evaluates them for real.

**Why the default is `pitch` and not `all`.** The author's *yaw* keyframes sit on `UpBody` and
`AllBody`, and `AllBody` is the parent of the **legs** as well as of the torso. They express the
*body's* yaw ("the chest un-blades as the head turns") - but GeckoLib already rotates the whole model by
the entity's body yaw (`GeoEntityRenderer#applyRotations`), so feeding them applies the body yaw a
second time. Measured on the armed walking scenario: **31.7° peak-to-peak lower-body yaw with `all`,
0.0° with `pitch`.** The yaw keyframes are also a cancelling pair (`UpBody` −`head_yaw`, `Head`
+`head_yaw`), so they cannot supply the look tracking this mod's code provides - they only redistribute
the twist. `all` is one keystroke away (`/tarkovscav client pose molang all`) for the A/B.
The rest pitch (`headRestPitchDegrees`) is untouched by any of this: the code still owns `Head` in every
state of the idle scenario.

**The evidence, in game.** `client.logPoseWriters = true` prints one line per rendered frame:

```
[pose] frame=812 mob=tarkovscav:scav#42 src=auto clips=[tac:walk, tac:aim:rifle] yaw=18.402 pitch=-4.113
       writers=[AllBody=clips(fixed),UpBody=clips(fixed),UpperBody=code,AllHead=clips(fixed),Head=code]
       yawDeg=[AllBody.Y=0.000,UpBody.Y=35.161,UpperBody.Y=4.601,Head.Y=13.802] headX=-2.468 chainYaw=53.564
```

Differentiate the `chainYaw` column and correlate it with `yaw` to tell the two mechanisms apart: a
two-writer frame makes the chain stop being a scaled copy of the input (`corr` collapses, the
zero-crossing rate of the difference jumps), while a wrong gain shows up as a slope that is not 1.
`/tarkovscav client state` prints the counters (`two-writer-look-conflicts`, `fixed-track-overrides`).
The look-conflict counter is 0 by construction under `auto`/`clips`; the fixed-track counter is normal -
it counts the frames where the code replaces a *numeric* clip track, which is the documented fallback for
the unarmed `idle`/`walk`/`run` clips.

**One detail about `bodyYaw` and the head.** `AllHead` is animated with a fixed authored offset (yaw
−33.09° on the rifle clips, −27.5° on the pistol ones - the rest of the `UpBody` blading, and it cancels
most of that +35.16°). It is a constant, it sits inside the chain `tools/selftest_pose_writers.js` sums,
and no keyframe of it is Molang, so it is a baseline and never a look response.

### 5e. The gunner pillager's arm: raised when it is about to shoot, hanging when idle

The report: the gun-armed pillager carried the rifle hanging at its side **in every state**, including
while firing. The requirement was that the pose must not be a second, independent guess at "is this mob
about to shoot", or a mob ends up holding a gun up while not firing (and the other way round).

**The predicate.** `GunBrain#transition` - the one place the state machine decides anything - now pushes
its own `GunAiState` to the client through the same synced data that already carried the
aiming/firing/reloading flags (`GunPose.Keys#setState`, an extra `BYTE` accessor). The renderer then
asks one function:

```java
ArmPose.forState(GunAiState)   // client/ArmPose.java - the only mapping in the mod
  IDLE                                    -> LOWERED    (write nothing: the pose the user accepted)
  RELOAD                                  -> RELOADING  (gun in towards the chest, both hands on the mag)
  RETREAT                                 -> HUNKERED   (gun down, shoulders in)
  ALERT, ADVANCE, AIM, FIRE, SUPPRESS,    -> RAISED     (weapon up, following the head)
  BOLT, REPOSITION
```

**Is that the same condition as "TaCZ is asked to shoot"?** `IGunOperator#shoot` is called from exactly
two places in `GunBrain`: `tickFire` (state `FIRE`) and `tickSuppress` (state `SUPPRESS`). Both map to
`RAISED`, so *a shot can always leave the barrel only while the weapon is up*. The converse is
deliberately false - `AIM`/`ALERT`/`ADVANCE`/`BOLT`/`REPOSITION` also hold the weapon up without
shooting, which is what "raise it when he is about to shoot" means, and it matches what the mod already
did for the clips (`isGunAiming() = state != IDLE`) and for TaCZ's own `aim(true)` calls. The two states
where the brain explicitly calls `op.aim(false)` - `RELOAD` and `RETREAT` - get their own poses instead
of the raised one. `node tools/pose_chain.js` and the source guards in `tools/selftest_rig_pose.js` keep
this honest; the invariants are:

* `FIRE` and `SUPPRESS` (the shooting states) always map to a raised pose;
* both render paths ask the same function for the same entity (`GunnerPillagerArmModel` for the vanilla
  illager model, `RigSupport.applyArmPose` for the Bedrock rig), so they cannot disagree;
* `LOWERED` writes **nothing at all** - the idle pose is byte-for-byte the pose that was already there.

**The vanilla path** (`useGeckoModel = false`, what the user sees) is
`client/GunnerPillagerArmModel`, an `IllagerModel` subclass: `super.setupAnim` runs first and is left
untouched (walk cycle, head look, crossed arms, death), then only `rightArm`/`leftArm` `xRot`/`yRot` are
overwritten for the non-idle poses. Those two fields are assigned absolutely by vanilla on every frame
in both of its branches, so nothing this class writes can leak into a later frame; `zRot`, which one
vanilla branch does not assign, is deliberately left alone. The renderer keeps the **vanilla pillager
texture**: `textures/entity/gunner_pillager.png` belongs to the placeholder Bedrock rig's UV layout and
would smear across the vanilla illager model.

**The Bedrock path** (`useGeckoModel = true`): `RigSupport.applyArmPose` writes the same poses as
absolute `rest + angle` values on `RightArm`/`LeftArm` (never `+=`, for the reason in §5). It has to,
because the shipped placeholder rig cannot animate them from clips: `gunner_pillager.animation.json`
contains `idle`, `walk`, `aiming` and `firing`, while the entity asks GeckoLib for `GunClips`'
`tac:idle`/`tac:walk`/`tac:run` and `tac:hold:*`/`tac:aim:*` names - **none of which exist in that
file**, so that rig plays no clip at all and would otherwise stand in its bind pose. A real rig dropped
in later should bring those clip names; the code pose then only decides the arms while the clips drive
the rest.

The gunner pillager's *placeholder* rig (`gunner_pillager.geo.json`) has a `RightHandLocator` of its
own, so the anchor chain finds a real bone with `useGeckoModel = true` instead of falling through to
`RightHand`. Its pivot is **derived, not authored**: Bedrock pivots are model-space, so the bone was
placed at the placeholder's `RightHand` pivot plus the scav rig's local offset from `RightHand` to
`RightHandLocator` - the placeholder's `[-5.5, 12, 0]` plus `[0.053, -1.8815, 0.106]` (the scav's
locator `[-5.07955, 16.59652, 0.1325]` minus its `RightHand` `[-5.13255, 18.47802, 0.0265]`) gives
`[-5.447, 10.1185, 0.106]`. `node tools/derive_anchor_pivot.js <reference.geo.json> <target.geo.json>
<childBone> <parentBone>` re-runs that arithmetic. **Nobody has looked at this in game** - it is the
first thing to check if the pillager rig is ever enabled.

### Aim tracking: why the torso write is absolute (and must stay that way)

`RigSupport.applyAimTracking` writes `UpperBody` and `Head` **absolutely**, as
`bone.getInitialSnapshot() + share * yaw/pitch`. That is not a style choice, it is the fix for the
"upper body looks wrong, sometimes it shows and sometimes it does not" report, and it is guarded by
`node tools/selftest_rig_pose.js`.

GeckoLib only resets a bone to its rest pose while that bone still reports
`hasRotationChanged() == false` (`AnimationProcessor#tickAnimation` in 4.8.4 - the reset branch runs
for `!bone.hasRotationChanged()`, and `resetBoneTransformationMarkers()` clears the flag at the end of
the tick), and `GeoBone#setRotX/setRotY/setRotZ` are what set that flag. So a `+=` on a bone that the
playing clip does not animate is self-sustaining: the write sets the flag, the flag suppresses the
reset, and the next frame adds to the previous frame's value again. None of the clips that play while
the mob is **armed** animates `UpperBody` - they animate its parent `UpBody`; only the unarmed
`idle`/`walk`/`run`/`death` clips and the unused `tac:melee:*` ones touch `UpperBody`, which
`node tools/list_animated_bones.js <anim.json> UpperBody` prints per clip - so nothing else wrote that
bone while aiming, and the old `upperBody.setRotY(upperBody.getRotY() + yaw * 0.7)` grew by 0.7 * yaw
**per rendered frame** for as long as the mob was aiming. At 60 fps a 10 degree head/body offset is
already more than a full turn per second. The torso, and with it the arms, the head and the gun in the
hand (every one of them a descendant of `UpperBody`), pivoted around the shoulder at a rate that
depended only on how long the mob had been aiming, while the legs - under `DownBody`, a different
branch of the tree - stayed normal. Writing `rest + share` is idempotent, so the frame count cannot
change the pose. The torso write stays **behind the `aiming` guard** (so the clips that do animate
`UpperBody` keep their keyframes) and GeckoLib's own reset brings it back to rest when the mob stops
aiming - it just has the small aim share to travel now, not an angle that had been growing for as long
as the mob had been aiming.

---

### 5aa. AI 智力分级（四档 profile + 小队协同）

用户原话：「接下来改 AI 的智力。现在暴徒/小弟系列是**最笨**的单位——慢、血少。然后是**狙击手**，会**埋伏、狙击、尽量保持自己的位置隐蔽**。再然后是 **elite 系列和 troop 系列**算一种：他们需要**更高的智能**，会**配合、压制、沿着掩体爬、探头**。**elite 系列喜欢冲，troop 系列打得更稳、更战术**。」

映射经用户确认（3+2+2+2，九个实体全覆盖）：

| 档位 | 实体 id |
| --- | --- |
| **SCAV** | `tarkovscav:scav` · `tarkovscav:gunner_pillager` · `tarkovscav:gunner_villager` |
| **SNIPER** | `tarkovscav:sniper_pillager` · `tarkovscav:sniper_villager` |
| **TROOP** | `tarkovscav:usec_villager` · `tarkovscav:bear_pillager` |
| **ELITE** | `tarkovscav:elite_villager` · `tarkovscav:elite_pillager` |

#### 目标行为表（出厂默认）

| tier | 反应 | 精度 | 掩体 | 压制 | 推进 | 协同 |
| --- | --- | --- | --- | --- | --- | --- |
| SCAV | 慢：**0.6–1.2 s**（12–24 tick，每次获得目标重掷） | 最低（`accuracyScale 0.80`） | **很少用**（`coverChance 0.15`、半径 ×0.45） | 无（`0.0`，只有短点射 `suppressBurstScale 0.5`） | 直线（`advanceCoverScale 0.0`） | 无 |
| SNIPER | 慢而稳（16–30） | 高（×1.0，veteran 锥 + `minHitChance 0.5` 只打有把握的枪） | **只选隐蔽射击位**（`partialCoverBonus 250` + 原有的完全隐蔽 1000 分） | 无 | **守点、不推进**（`holdPost true`） | 无 |
| TROOP | 快（6–10） | 高（×1.0） | **掩体到掩体**、暴露时间短（`coverChance 0.90`、`advanceCoverScale 0.6`、`repositionScale 0.6`） | **强**（`suppressChanceScale 1.6` → 0.96；`suppressTicksScale 1.5` → 90 tick） | 小步 | 是 |
| ELITE | 最快（4–8） | 最高（×1.05，0.90 上限） | **短促冲刺**、交战距离更近（`engageRangeScale 0.6`、`advanceCoverScale 1.5`、`coverSeekSpeedScale 1.15`） | 中（×1.0） | 长步 / 冲刺 | 是 |

#### 实现：profile 层 + 小队层

* **`gun/AiProfile.java`** —— 档位枚举（`SCAV`/`SNIPER`/`TROOP`/`ELITE`）、`tierFor(Mob)` 映射，以及**所有把决定因素化出来的纯方法**（掷反应时间、掩体概率、命中率门槛、包抄人数/侧向判定、集火投票、压制轮换、掩体占用过期）。纯方法存在的理由就是**可被闸门镜像执行**：`tools/selftest_ai_profiles.js` 把 Java 方法体和 JS 镜像**逐字符（去声明/去空白）对比**，所以仿真不会和出货代码悄悄分叉。
* **`gun/SquadCoordinator.java`** —— 每个协同档位的 `GunBrain` 各持一个；成员表缓存 `squadCacheTicks`，只在换窗口时重排角色，其余全是 map/list 查表。
* **`GunBrain`** —— 反应延迟走**现有 ALERT 状态**：目标实体 id 一变就重掷 `reactionTicks`（`resetReaction()`），并把状态拉回 ALERT，所以"刚发现你就被吓住"不需要任何并行状态机。狙击档的 `holdPost` 让 `decide()` 在超距时**选 REPOSITION 而不是 ADVANCE**；`minHitChance`/`patienceTicks` 在 AIM 里做**开火前**的命中率判定。
  * 命中率估计（`estimatedHitChance`）用的是**稳态锥**，**故意不含 warm-up**：warm-up 惩罚只能靠"真的开枪"消除，若把它算进门槛就会自锁（不开枪 → 永远不热 → 永远不开枪）。闸门把这个数算了出来：sniper 锥 20/30/40/52 格稳态为 **85.0% / 72.5% / 58.7% / 47.1%**，而同一批距离的 warm-up 锥只有 **30.9% / 20.9% / 15.8% / 12.2%**——后者在 40 格就低于 0.50，正是自锁的形状。
  * **防死锁是两层的**：等满 `patienceTicks`（100）就换一次位置（REPOSITION），连续 **3** 个窗口（`MAX_PATIENT_ESCAPES`，写死在 `GunBrain`）都找不到满意的枪就**直接开这一枪**——所以狙击手不会因为门槛太高而永远不开枪；换目标会重置这个预算。
* **`CombatTactics`** —— `coverSearchRadius`/`coverCacheTicks` 从 profile 取（= 全局键 × 档位倍数），`bestCover`/`peekSpot` 新增 `Predicate<BlockPos>` 重载，专门给"这格被占了 / 这不是我这一侧"用；旧的 5 参签名原样保留（命令与其它调用点不受影响）。
* **`SniperBehavior`** —— 完全复用；只在 `findPost` 的评分上**追加** `partialCoverBonus`（部分遮挡或比目标高 ≥1 格），原有的"目标看不见的位置 +1000"一行未动，`hold` 里依旧没有任何 `moveTo`。

#### 协同的四件事（TROOP + ELITE）

1. **集火**：`chooseFocusTarget` 在成员现有目标里投票（票多者胜，平票取最小实体 id）。刚获得目标的成员在 ALERT 里转向队选目标——**前提是自己 `canAttack` 且 `hasLineOfSight`**，阵营情报本身永远不能开战（§5m 的同一条规则，闸门断言了 `hasLineOfSight` 守卫）。
2. **交替掩护**：`isSuppressor(index, window, count)` = `window % count == index`，所以任意时刻**恰好一名**压制手，且每个窗口轮换（3 人队形是 0,1,2,0,1,2）。压制手在失去视线时进入现有 SUPPRESS 状态；其余人 `isMover()`，推进时跳过 `coverChance` 判定（因为队里已经决定"该我动"）。
3. **包抄**：`flankerCount` = `round(size × flankFraction)` 夹到 `[1, size−1]`（2 人以上**总有人守正面，也绝不全员绕同一侧**），`onFlankSide` 用叉积符号判定候选掩体在轴线的哪一侧，只有"自己那一侧"的掩体可选；万一那一侧没有可用点，会用**去掉侧向限制但仍排除占用**的第二次搜索退回（不会因此走到空地上）。
4. **掩体独占**：`claim(BlockPos)` 登记 `(ownerId, expiresAt)`，`isCoverFree` 让第二个人跳过该格，`coverClaimTicks` 到期自动释放（死掉/脱离的单位不会长期占位）。**闸门仿真："两个单位不能占同一格"、"到期即释放"、"第一名占 0 号、第二名只能拿 1 号"。**

#### 不会卡死

`shouldSuppress` 除了角色轮换还有 `overwatchTimeoutTicks` 上限（`timeout <= 0 || now - suppressorSince <= timeout`），所以即使窗口被调得比上限还长，"被叫去压制却一直没机会动"的单位也会**退回自己的行为**；再加上 SUPPRESS 状态本身由 `suppressTicks` 收尾、推进只发生在 ADVANCE，协同一层**只能偏置一个本单位本来就能做的决定**。新的"等命中率"则是**两层**边界：`patienceTicks` 到期先换位置（REPOSITION），连续 3 个窗口仍不满足就直接开这一枪；`transition()` 会把耐心清零——防的就是 §5i 那类"瞄着永远不开枪"。

#### 关掉 / 回退

* **一键关**：`[ai] enabled = false`。四个分档完全不读，所有单位回到"只有全局键"的旧行为。
* **revert to dumb（一键变笨）**：保持 `enabled = true`，然后 **copy every number of `[ai.scav]` 到 `[ai.sniper]`、`[ai.troop]`、`[ai.elite]`**，并把这三块的 `holdPost=false`、`minHitChance=0.0`、`patienceTicks=0`、`coordination=false`（这就是 SCAV 的五个绝对值）。这样**每一档都表现得像暴徒**。若还想要"加这个功能之前"的**完全一致**手感，再补一句：`reactionMinTicks = reactionMaxTicks` = `[combat] reactionTicks`（因为 0.6–1.2 s 的随机反应是这次新加的）。闸门把这条配方**真的执行了一遍**（用 SCAV 的旋钮解析四个档位，断言结果逐个相等）。**§5ab 的六个键不需要追加任何步骤**：它们就写在 `[ai.scav]` 里，而且全部是哨兵值（`0 / -1 / -1 / -1 / -1 / 0` = 退回枪械档与全局键），所以"抄 `[ai.scav]`"这一步顺带就把暴露火力与受伤反应也退回去了。
* **例外**：`[tactics] retreatSprint` **不被任何档位缩放**——你把它关掉就是关掉（"逃跑 = 正常走路速度"），没有哪一档能偷偷把疾跑打开。

#### 闸门

`tools/selftest_ai_profiles.js`（已接进 `tools/spike/selftest.ps1`）：
① 四档真值表与出厂默认（逐键比对）＋ 表格隐含的大小关系（scav 最慢/最不准/最少用掩体/无压制、troop 压制最强、elite 推进最长、elite 交战距离最近）；② 九个 id ↔ 档位映射，**与 `ModEntities` 的注册表交叉核对**，并核对每个实体的 `extends`（三个是另外两个基类的子类，所以"先判子类"是承重的）；③ 九条行为分支在源码里真的存在（反应延迟、掩体偏好、压制、守点、命中率门槛、集火、掩护轮换、包抄、掩体占用/过期）；④ 协同决策**仿真**（两个单位不能占同一格、压制手轮换且每人不偏、平票取最小 id、包抄不是全员、压制角色会超时回落）＋ Java/JS 逐字符对比；⑤ README 键全在；⑥ revert-to-dumb 配方执行后可复现 SCAV 数值；⑦ 新增的"等命中率"有上限且 `transition()` 会清零（不是新的卡死点）。

#### 判据（用户肉眼确认）

1. **SCAV**：发现你之后**明显愣一下**（0.6–1.2 s）才开枪；很少主动绕掩体，基本直线走来；不会压制。
2. **SNIPER**：**站着不动**打你；你一靠近/一还击就换位；远处的枪**不是每发都打**（命中率不够时它不开枪），换位后会在新的隐蔽点继续。
3. **TROOP**：两人以上时**打同一个目标**；你会看到"一个在打、另一个在动"的交替；躲在墙后它会**持续压制**你藏身的方向（压制更久、更密）。
4. **ELITE**：反应最快，**贴得更近**才开火，推进入更长的步/短冲刺；也会集火和包抄（从两个方向来）。

---

### 5ab. 暴露目标的致命火力 + 被打就退（`[ai.<tier>]` 的六个新键）

用户原话：「现在有个问题：面对**已经走出掩体**的敌人，troop 和 elite 只打**短点射**——一个弹匣都打不完，根本打不死人；而**他们自己被打、被打残的时候又不会缩进掩体**。TTK 本来可以非常短。」

问题的**根**不在 AI 档，而在两处叠加：

1. 点射长度来自**枪械档**，不是 AI 档：`GunBrain#burstSize` 读的是 `[tiers.<gun>] burstShots`（步枪出厂 **6**，`-1` = 打空弹匣或目标死亡），只有**压制**时才再乘上 AI 档的 `suppressBurstScale`。所以面对站着不动的目标，四档打的是一样长的短点射。
2. 一梭子打完还有 `[tiers.<gun>] burstCooldownTicks`（步枪出厂 **20** tick = 1 s，用户 toml 里是 16）+ 一次 `REPOSITION`（`combat.repositionTicks 40 × repositionScale`，troop 0.6 → 24 tick、elite 0.5 → 20 tick）才能再开火。**每 3.2 秒里只有约 1.2 秒在开枪**。
3. 再叠上 `AccuracyProfile` 的 warm-up：一场交火的**前 `accuracy.warmupShots`（出厂 8）发**精度乘 `accuracy.warmupMultiplier`（0.45），所以**第一梭子基本全空**——连 troop/elite 也是。

#### "暴露"的精确定义（纯函数，闸门逐字执行）

> **exposed = 本怪的眼睛到目标的【眼睛】和到目标的【脚】都能拉出无遮挡直线（即目标没躲进掩体），且目标在本怪的有效射程 `engageRange()` 之内。**

写成一行就是 `AiProfile#isExposed(eyesVisible, feetVisible, inRange)`；两半可见性分别是 `CombatTactics#canSeeEyes` / `#canSeeFeet`（各一次 `level.clip`，和 `isInCoverFrom` 用的那两次同源），射程那半就是 AIM/FIRE 自己在用的 `engageRange`。**只露头不露脚（头在墙后）不算暴露，只露脚不露头也不算，看得见但超出射程也不算**——真值表由闸门打印并逐行断言。判定**每 tick 只做一次**（`GunBrain#tick` 开头清零、`tickFire` 里重算），同一 tick 里弹匣长度、梭间停顿、warm-up 豁免（`AccuracyProfile#warmingUp` 通过 `GunUser#targetExposedNow` 取同一个判定）看到的**必须是同一个答案**。

#### 出厂数值（`[ai.<tier>]`，六个键）

| 键 | 含义 | SCAV | SNIPER | TROOP | ELITE |
| --- | --- | --- | --- | --- | --- |
| `exposedBurstShots` | 目标暴露时一梭打几发。`-1` = **打空弹匣或打死为止**，`0` = **不覆盖**（交给 `[tiers.<gun>] burstShots`），`>0` = 正好这么多发 | `0`（保持笨：短点射） | `1`（单发，绝不扫射） | **`-1`（打空）** | **`-1`（打空）** |
| `exposedBurstCooldownTicks` | 目标暴露时**梭间停顿** tick。`-1` = 不覆盖（用枪械档的 `burstCooldownTicks`），`0` = **完全不停** | `-1` | `-1`（保持守点节奏） | **`0`（不停）** | **`0`（不停）** |
| `warmupShotsWhenExposed` | 目标暴露时用哪个 warm-up 阈值。`-1` = 用全局 `accuracy.warmupShots`(8)，`0` = **首发即稳态** | `-1` | `-1` | **`0`** | **`0`** |
| `retreatHealthFraction` | **绝对**血量比例，低于它就脱离接触。`-1` = 不覆盖（用 `combat.retreatHealthFraction × retreatHealthScale`） | `-1`（→0.35，跑得早） | `-1`（→0.6×0.35=0.21，守点） | **`0.5`** | **`0.55`** |
| `hurtRetreatChance` | **单次中弹**直接转入 RETREAT 的概率。`-1` = 用全局 `combat.hurtRetreatChance` | `-1`（→0.5） | `-1`（→0.5） | **`0.8`** | **`0.85`** |
| `retreatHoldTicks` | 脱离接触并进了掩体后，**至少**在掩体里待多少 tick；`0` = 关闭（旧行为：40 tick 后重新判断） | `0` | **`60`** | **`80`** | **`60`** |

#### 谁说了算：`exposedBurstShots` vs `[tiers.<gun>] burstShots`

顺序写死在 `GunBrain#burstSize` 里，闸门把它当纯函数镜像执行：

1. **目标暴露**且 `exposedBurstShots != 0` → **本键说了算**；
2. 其它一切时刻 → **`[tiers.<gun>] burstShots` 说了算**（`0` 这个哨兵值就是"退回枪械档"）；
3. 结果是负数 → `decodeBurst` 按**现在枪里的弹匣**解码成"打空或打死"（旧代码把 `-1` 悄悄改写成 30 发，所以 60 发扩容弹匣打到 30 就停；现在真的打空）；
4. **压制**时再乘 `tactics.suppressBurstMultiplier × suppressBurstScale`，并且**夹到弹匣容量**（不会承诺比弹匣里更多的子弹）。

"暴露规则接管扳机"还需要**双开关**：`exposedBurstShots != 0` **且** `exposedBurstCooldownTicks >= 0`（`AiProfile#exposedRuleApplies`）。这条双保险是给另外两档留的：**SCAV 两个哨兵都在**（枪械档的短点射 + 枪械档的停顿），**SNIPER 只有单发长度、没有停顿覆盖**（所以那一发之后照旧是枪械档停顿 + 换位，守点节奏不变）。

#### 被打就退，而且**待在**掩体里

* **退的血线**：troop `0.5`、elite `0.55`（**绝对**值，绕开 `retreatHealthScale`，不会被顺手再缩放）；scav 保持全局 `0.35`，sniper 保持 `0.6×0.35=0.21`（守点）。旧值是反的——elite 要到 `0.7×0.35=0.245` 才退。
* **中弹即退**：troop `0.8`、elite `0.85`（全局是 `0.5`）。26 血的步枪兵半血就是 13 血，正是"这笔交易已经亏了"的点。
* **`retreatHoldTicks`**：进入 RETREAT 时由 `GunBrain#transition` **统一**给 `retreatHoldUntil` 上钟（十几个 `transition(RETREAT)` 调用点一个都不会漏）；`retreatHolding()` = 「在 RETREAT **且** 钟没走完 **且** 真的在掩体里」。
* **被打时不会弹回去**（用户点名要的规则）：`GunBrain#onHurt` 里**第一件事就是** `if (state == RETREAT) { 重新上钟; return; }`——这是个**结构性保证**，不是掷骰子：RETREAT 分支里**没有任何 `transition`**，中弹只会刷新 under-fire 计时并**延长**hold（越打越缩）。其余状态下中弹才按 `hurtRetreatChance` 掷骰转 RETREAT。
* **重新交战必须是"探头"**：hold 走完、且**在掩体里**、且**不在被打**（`safe = isInCoverFrom(...) && !isUnderFire()`）之后，`retreatHoldTicks > 0` 的档**转入 REPOSITION**——那正是 `CombatTactics#peekSpot` 的"从掩体后探头"动作，然后再 AIM；**不再走旧的 `decide()`**（旧路径可能选 ADVANCE，等于又走回空地）。`retreatHoldTicks = 0` 的档（SCAV，或整层关掉）走的就是旧路径。
* **不让 hold 变成新的卡死点**：`GunBrain#checkMovementProgress` 的"原地不动"看门狗在 `retreatHolding()` 为真时**主动停用**（"故意蹲在掩体后不动"不是它要抓的"腿在动、人没动"）；真的没到掩体就照旧被抓。而且 hold 加上最小的 40 tick 仍小于 `combat.giveUpTicks`（160），所以 RETREAT 一定会自己结束。

#### 关掉 / 回退

* 单个键回退：把它们设回哨兵值即可——`exposedBurstShots = 0`、`exposedBurstCooldownTicks = -1`、`warmupShotsWhenExposed = -1`、`retreatHealthFraction = -1`、`hurtRetreatChance = -1`、`retreatHoldTicks = 0`，行为与 5ab 之前**逐位相同**（枪械档点射、枪械档停顿 + REPOSITION、全局 warm-up 8、全局 0.35 / 0.5）。
* **revert to dumb（一键变笨）配方不变、也不用追加步骤**：`[ai.scav]` 里这六个键**本身就是全部哨兵**，所以"把 `[ai.scav]` 的每个数抄进另外三块"这一步**顺带**就把 5ab 也退回去了。闸门把这条配方在新六键上**真的执行了一遍**（用 SCAV 的旋钮解析四档，断言结果逐个相等，并断言解析出来的是旧数值：`exposedRule=false`、枪械档弹长/停顿、warm-up 8、0.35、0.5、hold 0），同时断言 shipped 的 TROOP 块**确实**六个都不一样。

#### 闸门

`tools/selftest_ai_fire.js`（已接进 `tools/spike/selftest.ps1`，紧跟 `selftest_ai_profiles`）：

① 暴露真值表**执行**（眼+脚+射程 → exposed；只露头/只露脚/超距 → 不算）＋ `AiProfile#isExposed` 与 JS 镜像**逐字符对比**；② 六个新键的出厂值与大小关系（troop/elite 打空、sniper 单发、**scav 全是哨兵**）；③ 弹匣解码器**执行**（`-1` = 打空/打死、60 发扩容真的 60、正数就是正数、压制夹到弹匣）；④ **停顿只在暴露时被跳过**（同一支步枪、同一个 troop：目标在掩体后 = 6 发 + 20 tick + REPOSITION；目标暴露 = 30 发 + 停顿 0 + 直接回 AIM），并断言 SCAV/SNIPER 永远进不了这条分支；⑤ 中弹→RETREAT→hold→peek 路径在源码里存在**且被执行**（中弹时 RETREAT 分支无 transition、上钟、`retreatHolding` 三条件、hold 走完才 REPOSITION、看门狗停用），并逐 tick 仿真"中弹延长 hold、钟没走完不重新交战、钟一过走 REPOSITION"；⑥ revert-to-dumb 配方在新六键上执行并复现旧数值；⑦ **没有新的卡死点**：弹匣打空 → RELOAD（`reloadStallTicks` 200 有界）、FIRE 看门狗（`fireStallTicks` 100）仍在读、hold < `giveUpTicks`。README 键与优先级说明也在闸门里断言。

#### 判据（用户肉眼确认）

1. **暴露敌人死得快**：找个 troop 或 elite，从掩体后走出来站住不动——它会**一个弹匣打到底**（步枪 30 发连成一片），中间**没有**那段 1 秒停顿和换位；打空才换弹。而同样的情况对 **scav** 应该**没有变化**（还是 6 发一顿一停）。
2. **被打就缩、而且待着**：把 troop/elite 打到半血以下或连着打中它几枪——它会**脱离接触、进掩体并待住**（约 3–4 秒），期间你再打它**不会**把它勾出来；之后它是**从掩体后探头**重新开火，而不是走出来。
3. **sniper 还是单发守点**，**scav 还是又笨又短**。

---

## 6. First time in game - what to look at, and what proves it

Everything below is client-side, so none of it can be checked from a dedicated server. Each row says
what to look at, which config key fixes it, and which log line tells you the binding actually ran
(these lines are client-log only, printed once per entity type / once per mob).

| Look at | Expected | Config key | Proof in `latest.log` |
| --- | --- | --- | --- |
| `/summon tarkovscav:scav` | the Scav appears, roughly player-sized (YSM authors it at 0.7) | `client.renderScale` (0.7) | - |
| Its head | **one** hat (`Hat2`), **one** pair of eye gear (`Glass`), no cigarette, ears and face untouched | `client.headAccessories.*` (§5c) | `[model] tarkovscav:scav: hid 12 bone(s) [Gun3 … money Hat1 hat3 YanJing Yan]` followed by one line per bone with its reason, e.g. `Hat1  <- hat: keepOne (keeping Hat2)` |
| Its head while idle | raised 20° compared with the author's rig | `client.headRestPitchDegrees` (20) + `headRestPitchStates` (`["idle"]`) | `[model] head rest pitch: +20.0 degree(s) added on bone 'Head' while the gun AI state is IDLE …`; there is **no** such line while aiming, by design |
| Its right hand | the author's **reference props are gone**: no board, stretcher, bag, vest, bottle, parrot, bankroll, and no placeholder rifle | `client.hiddenBones` | `[model] tarkovscav:scav: hid N bone(s) [...] - 8 from client.hiddenBones, 4 from the head-accessory switches; 0 hiddenBones entr(ies) and 0 accessory name(s) matched no bone` |
| The hand | the **TaCZ gun** is in the hand and follows the arm through the aim/fire/reload poses | `client.gunAnchorBone` (default `RightHandLocator`) | `[gunmount] … bone 'RightHandLocator' (parent RightHand) pistolFamily=false rot=[0,0,0] offset=[0,0,0] scale=1.0 context=THIRD_PERSON_RIGHT_HAND stack=modern_kinetic_gun` |
| Gun orientation | muzzle forward, grip in the palm, sensible size | `client.gunAnchorMode` (`normalisedHand`) + `client.gunMountDisplayContext` (`THIRD_PERSON_RIGHT_HAND`), then `gunMountRifle*` / `gunMountPistol*` (§5b, §5f) | `[gunmount] … bone 'RightHandLocator' (parent RightHand) mode=normalisedHand rot=[0,0,0] offset=[0,0,0] scale=1.0 context=THIRD_PERSON_RIGHT_HAND stack=modern_kinetic_gun`; tune it live with `/tarkovscav client gunpose` |
| The left hand | the offhand item (if any) sits in the left palm, and the left-hand anchor is resolved | `client.gunOffhandAnchorBone` (`LeftHandLocator`) + `renderOffhandItem` | `[gunmount] … offhand bone 'LeftHandLocator' (parent LeftHand) mode=normalisedHand …`, or a WARN naming the bones the rig does have |
| The gunner pillager, arm | **gun up while alert/aiming/firing**, hanging in the idle pose | nothing to configure; the pose is derived from the gun AI state (see below) | client log: `Gunner pillager: using the vanilla illager renderer` (or `… GeckoLib Bedrock renderer`) |
| One-handed vs two-handed pose | pistols fire one-handed, everything else two-handed | `guns.pistolClipTypes` (default `["pistol"]`, matching YSM) | server log: `[gunai] … equipped … [clip family pistol|rifle]` |
| Walking while shooting | **legs walk, upper body aims** - this is the whole point of the two controllers | - (see §5) | - |
| Reload | the mob gets behind cover, plays a reload pose, and the magazine refills | `guns.manualReloadFallback`, `guns.manualReloadTicks` | server log: `[gunai] … TaCZ did not start a reload; reloading from its ammo items (45 ticks)` then `reloaded from its ammo items: took 30 round(s), magazine 30/30, reserve items left 150` |
| The gunner pillager | vanilla illager holding a gun, or the Bedrock rig | `client.useGeckoModel` (`false` = vanilla illager) | client log: `Gunner pillager: using the GeckoLib Bedrock renderer` |
| Spawning | scavs/pillagers only inside a city | `spawn.*` (see §4) | server log: `[spawngate] ACCEPT|REJECT …` |
| Gunner villager | a villager with a gun: it walks, aims, fires, reloads and takes cover like a scav, but fights *for* the village | `spawn.gunnerVillager*`, `client.gunnerVillagerArm*` (§5j) | `/tarkovscav spawn gunner_villager` inside a city, then `/tarkovscav test watch 20 16 [pos]` → `[test] WATCH PASS`; the log shows `[gunai] Armed Villager …` with the same state machine |

If a row's log line is missing entirely, the client never got that far - start there rather than with the
config. If a `[model]` line reports `(N config entries matched no bone)`, a name in
`client.hiddenBones` is misspelled; the mod logs the ones that matched so the two can be compared.

---

## 7. Importing the model (re-runnable)

```
node tools/analyze_scav_model.js           # bone tree, box sizes, per-clip region counts
node tools/import_scav_assets.js           # writes the mod's assets
```

Sources live in `assets_source/scav/` (extracted from the user's zip, never modified) and the
importer writes:

* `src/main/resources/assets/tarkovscav/geo/scav.geo.json` (identifier set to
  `geometry.tarkovscav.scav`; **47 bones / 145 cubes** since the 2026 re-export, see below)
* `src/main/resources/assets/tarkovscav/animations/scav.animation.json` - only the 17 clips the mob
  plays, with a manifest of what was dropped
* `src/main/resources/assets/tarkovscav/textures/entity/scav.png`
* `tools/spike/work/scav-import-manifest.json` - what was taken, renamed, dropped and skipped

The importer also reconciles a real mismatch in the author's files: every clip animates `LeftFoot` /
`RightFoot` while the geometry calls them `leftfoot` / `rightfoot`. GeckoLib matches bone names
exactly, so without that step the feet would silently never animate. Clip tracks for bones that do not
exist at all (`long_h1`, a leftover from another rig) are dropped and reported.

### 2026 模型重导出：只删不加（70→47 骨 / 322→145 立方体）

用户重新导出了模型，**只删掉不必要的东西、没有新增任何骨骼**，并要求"动画依旧可以套用"。导入前先用
`node tools/compare_scav_geo.js`（新源 ↔ 旧 geo，默认参数已经指向这两个文件）核对：

| 项 | 旧 rig | **新 rig** |
| --- | --- | --- |
| 骨骼 | 70 | **47** |
| 立方体 | 322 | **145** |
| 贴图 | 256×256 | 256×256（未变） |
| 动画引用的骨骼 | 22 | 22（**0 缺失**） |

**删掉的 23 根**：`Ban, Bao, Chibang, Glass, Gun3, Hat1, Jiu, Lianru, Lianru2, Lianru3, Parrot,
QiangguanA, Spwt, Yan, YanJing, bone2, bone3, bone5, bone6, hat3, mag2, money, qiangtuo2`（作者的道具/眼部装备/
帽子/烟那些配件骨）。动画库**没有重导出**，所以"clips 引用的骨骼是否还在"是这次替换唯一会**静默**坏掉的东西
（GeckoLib 对不存在的骨骼轨道一声不吭地跳过）——`tools/selftest_rig_bones.js` 因此把它做成硬门：**新 geo 的
骨骼集合 ⊇ 动画引用集合（0 缺失）**，另外断言 `RightHandLocator`/`LeftHandLocator`/`Head`/`Head3`/`Hat2`/
`Ear`/`RifleLocator`/`PistolLocator` 等关键锚点仍在、identifier 与 256×256 贴图未变。

**配置默认值配套改了**（避免"配置指向不存在的骨"）：
- `client.hiddenBones` 默认**清空**——作者那 8 个道具（含占位步枪 `Gun3`）已经不在几何里，没有东西可藏；
- `eyeGearAccessory` 默认 `none`（`Glass`/`YanJing` 都被删了）；`cigarette` 仍是 `false`；`keptHatBone` 仍是
  **`Hat2`**（本 rig 只剩这一顶帽子），`hatAccessory` 保持 `keepOne`；
- `hatBones`/`eyeGearBones`/`cigaretteBone` 里的 `Hat1, hat3, Glass, YanJing, Yan` 保留为这两个开关的**词表**：
  它们是旧 rig 的名字，本 rig 没有 → 运行时会**明确报一次**（不许静默），但**不是**每帧刷屏。

**消掉日志刷屏（`RigSupport.hideReferenceProps`）**：用户旧 toml 不会被覆盖，里面可能同时留下 13 个不存在的名字。
现在的规则是——**按 (rig, 骨) 去重**，每个名字只 WARN 一次；每次 bake 再给**一条 INFO 汇总**
（`[model] scav: 5 configured bone(s) not in this rig (Hat1, hat3, Glass, YanJing, Yan) - the rig no longer has
them; ...`）。改配置（`/tarkovscav client reload`）会清掉去重表，所以换回旧 rig 时这些名字会重新被报出来。

**这次替换"预期不修"纹理错乱**：上一轮已经定位为 **TaCZ 裸 GL 造成的贴图绑定泄漏**，并由 `RenderStateGuard`
在每帧重绑修掉；**删几何不改变状态泄漏这一类问题**（少 23 根骨骼、少 177 个立方体只会让绘制量更小）。
所以换上新模型后若仍有错乱，下一步是**假设 2/3**（Molang 表达式数值域穷举 + 非有限值钳制），并请提供
`logPoseWriters` / `logGlState` 日志。

**Not imported, on purpose:** `models/arm.json` (YSM first-person arms - a mob never renders in first
person), `avatar/*.png` (the authors' avatars), `sounds/*.ogg` (the authors' audio, including
`A Cup Of Liber-Tea`, which is not theirs to redistribute), `ysm.json` and
`controller/controller_scav.json` (YSM metadata and its own state machine, which is not portable to
GeckoLib).

### Rig self-tests (no game needed, a second each)

```
node tools/compare_scav_geo.js
node tools/selftest_rig_bones.js
node tools/scan_geo_structure.js src/main/resources/assets/tarkovscav/geo/scav.geo.json
node tools/scan_molang.js        src/main/resources/assets/tarkovscav/animations/scav.animation.json
node tools/scan_head_keyframes.js src/main/resources/assets/tarkovscav/animations/scav.animation.json
node tools/selftest_rig_pose.js
node tools/selftest_accessories.js
node tools/pose_chain.js
node tools/selftest_city_import.js
node tools/resolve_gun_anchor.js
node tools/classify_head_parts.js src/main/resources/assets/tarkovscav/geo/scav.geo.json src/main/resources/assets/tarkovscav/animations/scav.animation.json
node tools/scan_overlapping_geometry.js src/main/resources/assets/tarkovscav/geo/scav.geo.json
powershell -ExecutionPolicy Bypass -File tools/atlas_report.ps1 -Texture src/main/resources/assets/tarkovscav/textures/entity/scav.png -Rects <rects.json>
```

* `scan_geo_structure.js` is the structural pass over the geo file: duplicate bone names, dangling
  parents, parent cycles, `poly_mesh` (which GeckoLib 4.8 does not render at all), per-face UVs that
  name a direction the cube does not define (GeckoLib drops that face - an invisible side, seen as a
  body part that comes and goes with the camera angle), per-field limits on
  `inflate`/`size`/`origin`/`pivot`/`rotation`, zero-size and plane-like cubes, and whether any
  `hiddenBones` entry is an **ancestor** of a body bone (hiding a bone hides its subtree). Exit code 1
  on a hard problem, so it can gate a build.
* `scan_molang.js` lists every keyframe whose value is a **string** instead of a number, i.e. a Molang
  expression, and flags the ones GeckoLib cannot resolve (`ysm.*`, unregistered `query.*`). The numeric
  keyframe scans (`scan_ranges.js`, `scan_ranges2.js`) cannot see these at all.
* `selftest_rig_pose.js` simulates GeckoLib's per-frame bone handling to show why the aim write has to
  be absolute (§5) and greps `RigSupport` so the accumulation cannot come back. It also checks the share
  fold - the code's total yaw/pitch gain stays 1.0 for all eight (aiming, torso owned, head owned)
  combinations.
* `selftest_pose_writers.js` **replays the real pose pipeline over the real clip data** (§5r): the clips
  in controller order, then the code with the same arbitration `RigSupport` implements, for every
  `poseSource` × `molangVariables` × `torsoYawShare` combination, and prints per scenario the writers per
  bone per frame, the least-squares gain of the head-chain yaw against `netHeadYaw`, its symmetry for
  positive and negative yaw, the correlation and zero-crossing rate of the differenced series, the chest
  and lower-body swing, and the largest frame-to-frame step. It asserts: gain 1.0 under the shipped
  default, no frame with both a look-driven clip track and the code on one bone, no alternation, no jump
  larger than the input step, the before/after torso swing (45.3° → 16.2°), and that feeding the yaw
  symbols swings the lower body (31.7° vs 0.0°). Its Molang evaluator implements the `math.*` function
  table, which is where "are `math.min`/`math.abs` supported?" is answered by evaluation rather than by
  argument.
* `list_animated_bones.js` prints which bones each clip animates - the evidence for "the armed clips
  never touch `UpperBody`" in §5.
* `resolve_gun_anchor.js` mirrors the `gunAnchorBone` fallback chain against both shipped rigs (and
  greps the layer for the chain, the WARN and the rebake guard), so "which bone does the gun really
  mount on" is answerable without a game. `derive_anchor_pivot.js` re-runs the pivot arithmetic behind
  the placeholder rig's anchor (§5).
* `bone_uv_colors.js` + `bone_uv_colors.ps1` sample the texture inside every cube's face UV, so a
  screenshot's "a red cube on the head" can be traced back to a bone name.
* `classify_head_parts.js` dumps every bone of the head branch (parent chain, rest pivot/rotation, own
  cubes, cube AABB, atlas patch, whether a clip animates it) - the evidence table in §5c.
* `scan_head_keyframes.js` prints, per clip, the head-chain keyframes *and the constant each Molang
  expression collapses to* once GeckoLib resolves the YSM-only variables to 0 - the data behind
  "only the idle head looks wrong" (§5d).
* `selftest_accessories.js` recomputes the merged hidden set from the same rules `Config` implements,
  for the shipped defaults and for an **old toml that only has `hiddenBones`**, and checks that a
  named bone missing from the rig is reported rather than ignored (§5c).
* `pose_chain.js` folds the rest rotations of the gun anchor's ancestors into one matrix, prints what
  each gun clip does to the same bones, and greps the layer for the config-driven transform (§5b).
* `mount_matrix.js` reproduces GeckoLib's item path (`renderRecursively` + `BlockAndItemGeoLayer`) in
  4×4 maths and prints, per clip, the final bone rotations, the matrix the item is really drawn with, its
  distance to the palm, and the two `gunAnchorMode` results - the evidence table in §5f. It also gates
  the hand-anchor source invariants.
* `selftest_city_import.js` runs the `city import` validation against the shipped `city_small.nbt` and
  against synthetic files for every rejection branch (missing, empty, not NBT, no size, zero size,
  oversized, empty palette, no blocks, too many blocks, out-of-range palette index), plus source guards
  for the command and gate wiring (§7).
* `scan_overlapping_geometry.js` finds cubes another bone's cube contains, and faces two bones keep in
  the same plane - the z-fighting pairs. `atlas_report.ps1` prints a coarse colour/alpha map of the
  texture and lists the faces that sample fully transparent pixels (i.e. get alpha-tested away). Both
  exist for the "the upper body renders as another outfit from certain angles" class of report - see
  §10.


---

## 7. The city preset (place it, edit it, hand it back)

The preset is a real structure template: **48 × 26 × 48**, four 3–5 floor buildings, a street with
cover (low walls, iron-bar barriers, burnt-out cars, rubble), an alley, and a ladder shaft to every
roof.

### 7b. `tarkovscav:city_district` — the jigsaw district (built out of the user's own buildings)

`city_small/a/b/c` are **one template each**: a whole city in a single `.nbt`. The district is the other
shape: **every building is its own piece**, and the vanilla jigsaw engine assembles several of them
**near each other** into a district.

**No terrain is replaced**: `terrain_adaptation` is **`none`**, and each piece carries its own
**foundation that grows downwards** (5 blocks, the extractor's `--foundation 5`; it is baked into the
pieces, so nothing has to be configured at generation time), so a building dropped on a slope is buried
rather than floating. That is the whole reason the pieces are extracted with a footing instead of being
pasted flat.

| Piece | Size | What it is |
| --- | --- | --- |
| `buildings/building_a1` | 16×23×16 | 5 floors, light-grey concrete, deepslate-tile roof, furnished ground floor |
| `buildings/building_a2` | 16×19×16 | 4 floors, terracotta walls |
| `buildings/building_b1` | 16×27×16 | 6 floors, brown concrete, **beds and a bell on floor 2** |
| `buildings/building_b2` | 16×27×16 | 6 floors, light-grey concrete |
| `buildings/street_tile_a/b` | 16×10×16 | the pavement: smooth stone + grey concrete + one small rubble pile |
| `buildings/decor_rubble_west/east` | 16×7×11 / 11×7×9 | the two big rubble piles (orange terracotta + red sand) |
| `buildings/wall_ring` | 66×27×80 | the district's own enclosure wall (kept as an asset; not in a pool yet) |

The pieces were **cut out of the user's save** (see `docs/CITY_EDIT_GUIDE.md`), and what is inside them is
auditable: **no spawner, no chest/barrel, no sign, no fire, no platform slab, no `tarkovscav:` block** -
"only the building body" is enforced by an argument (`stripBlockEntities`, `keepFire`) and asserted by
`tools/selftest_city_district.js`. Decoration that IS wanted (beds, doors, ladders, torches, the terracotta
skirt) is kept.

**The connectors.** Each piece carries real `minecraft:jigsaw` blocks with their block entity NBT
(`name` / `target` / `pool` / `final_state: minecraft:air` / `joint`), placed by
`tools/spike/citysave/StructureConnectors.java`. A street tile has four street connectors, eight
building sites and two decor sites; a building has exactly one entrance connector; a rubble pile has one
terminal connector. `final_state` is air, so a connector is never visible in game.

**Why `keepFire=false` by default:** `minecraft:fire` is part of the user's hand work, but a generated
district with `doFireTick=true` would set *itself* on fire on the first lightning storm. A generated city
that burns down is not a city, so fire is stripped unless you ask for it.

### Seeing it in an existing save: `/tarkovscav city district [seed] [grid]`

World generation only ever touches **chunks that have not been generated yet**, so a district will **not**
appear in a save whose chunks are already there - no worldgen change can do that. （**诚实说明：新结构只会在
"还没生成"的区块里出现；已有存档里"已生成的区块"不会凭空长出城区**，这不是模组的限制而是引擎规则。）
For that reason there is also a command that places one **right now**, out of **the same structure NBT files
and the same pools**:

```
/tarkovscav city district                 # 3x3 street tiles (48x48) around you, random seed
/tarkovscav city district 123456789       # same layout again - the seed is printed every time
/tarkovscav city district 123456789 5     # 5x5 street tiles (80x80)
```

It builds a road grid, hangs buildings off the outside of the grid (70 % of the slots), drops the rubble
piles on free corners (50 %), and puts every piece on the terrain height of its own column minus its own
foundation. **Honest difference from worldgen:** the *pieces* and the *pools* are the same, but the
arrangement is a grid, not the jigsaw engine's random walk - a command-placed district and a naturally
generated one are the same city in different layouts. `/locate structure tarkovscav:city_district` finds a
naturally generated one.

**Which ids count as a city:** the district is in the `tarkovscav:city` structure **tag**, which is what
`spawn.cityStructureTags` already points at, so the spawn gate treats it as a city with no config change.
`cityStructureIds` (the id list) was deliberately **not** changed: the tag is the documented extension
point, and editing the user's id list would be a silent config change.
**The generator builds in the same standard (M4).** `tools/district-layout.json` +
`java -cp tools/spike/out CityStructureGen --pieces-only --foundation 5 tools/district-layout.json`
writes one structure NBT per building into `structures/buildings/gen_district_b*.nbt` **and touches nothing
else** - the four shipped city templates stay byte-identical, which `selftest_city_district.js` asserts. The
generated buildings follow the M1 survey of the user's own district: light-grey / brown concrete walls, a
**terracotta wainscot** on the ground floor, a torch, a lantern, a bed when the corner is free, more cover
per floor (7, up from 5), a real door, and **the same 5-block footing**. They go into the **same
`city_district/building` pool** as the extracted ones, so a district mixes both. Containers, signs,
spawners and fire are stripped out of generated pieces exactly like they are out of extracted ones ("only
the building body").

**`[spawn] foundationDepth` (default 5).** The footing is **baked into each piece**: the extracted pieces
carry a **frozen 5** blocks (the extractor's `--foundation 5`; the street tiles carry 3, the rubble piles
0), and generated pieces carry whatever `--foundation N` the generator was run with. This key is the number
those pieces are *supposed* to carry: the assembler reads it, logs it, and **warns** when it disagrees with
what the pieces were baked with ("re-run the extractor / `CityStructureGen --pieces-only --foundation N`").
It cannot retro-fit a piece, which is said here instead of implied.

**Why `buildings/wall_ring.nbt` is not in a pool:** it is the district's **whole 66x27x80 enclosure**. A
jigsaw pool element of that size pins the district to one fixed shape (every placement would be the same
ring), so it is kept as an asset for a hand-placed or command-placed wall. If a "ring" mode is ever wanted,
build it from **four straight wall segments plus corners**, not from this single piece.

### The full workflow, in order

**1. Put the preset in your world**

```
/place template tarkovscav:city_small 100 -60 100
```

`/place template` pastes the blocks - it is the command that works reliably. `/place structure
tarkovscav:city_small` also runs and prints "Generated structure", but see "why `city add`" below.

**2. Tell the mobs this is a city**

```
/tarkovscav city add <name> <radius> [pos]

# examples
/tarkovscav city add downtown 48 100 -60 100     # box of radius 48 around 100,-60,100
/tarkovscav city add downtown 48                 # box of radius 48 around YOU
/tarkovscav city add downtown                    # radius 64 around you
```

* `<name>` is just a label you can later remove with `/tarkovscav city remove <name>`.
* `<radius>` is in blocks, half-width of the box (4…512). For this preset, `48` with the origin at the
  centre covers the whole 48 × 26 × 48 footprint plus the surrounding street.
* `[pos]` is optional. Without it the box is centred on the command source: **a player standing in the
  city**, a command block, or the console at 0,0,0. With it the whole thing is scriptable over RCON,
  which is how the verification in §8 drives it.
* The list is written to `spawn.cityRegions` in `config/tarkovscav-common.toml` immediately, so it
  survives a restart, and `/tarkovscav city list` prints everything the gate currently knows.

**3. Check the gate**

```
/tarkovscav city test 124 -60 124     # expect ACCEPT
/tarkovscav city test 300 -60 300     # expect REJECT
/tarkovscav spawn scav 124 -60 124    # spawns only if the gate accepts
```

**4. Edit it, save it, hand it back — 现在这一整套在游戏里就能闭环**

改建筑 → 结构方块存档 → `/tarkovscav city import` → `/tarkovscav city place` → `/tarkovscav city add` → 生成。

1. Walk in and edit the buildings: add your own, knock holes in things, re-stack the cover.
2. Put a structure block over the whole block, mode **SAVE**, size `48 26 48`, name it e.g. `mycity`,
   and save. (Expand the size if you built outside the original footprint.) That writes
   `<world>/generated/minecraft/structures/mycity.nbt`.
3. Hand it back to the mod, in game:

```
/tarkovscav city import mycity
```

   The mod reads that file, validates it (non-empty, compressed NBT, a palette, a size of at most 512
   blocks per axis, every block entry pointing at a palette entry that exists), prints the size,
   palette size, block count and entity count, copies it to `<gameDir>/tarkovscav/city/mycity.nbt`
   and registers it under the runtime id `tarkovscav:city/mycity`. **Nothing is registered, copied or
   half-loaded if validation fails** - the command answers with the reason in chat and the log.

```
/tarkovscav city place mycity            # where you are standing
/tarkovscav city place mycity 100 -60 100 90 left_right
```

   `place` takes `[pos] [rotation(0|90|180|270)] [mirror(none|left_right|front_back)]`, prints the box
   it occupies, and immediately reports the **real** spawn-gate verdict at the placement position.

```
/tarkovscav city add mycity 48           # optional: also open the streets around it
/tarkovscav city reload                  # re-scan the directory after dropping files in by hand
/tarkovscav city structures              # what the gate can see right now
```

If you only ever drop `.nbt` files into `<gameDir>/tarkovscav/city/` yourself, they are loaded at
server start and with `city reload`, so the copy step is optional.

**5. What a runtime structure can and cannot do - read this, it is not a bug**

* It **can** be placed with `/tarkovscav city place`, and every placed instance is remembered as a
  box that the spawn gate accepts (`[spawngate] ACCEPT … inside placed city structure 'mycity' at …`).
  That is the supported "generate mobs in the building I built" workflow.
* It **cannot** be seen by natural worldgen. Vanilla `worldgen/structure/*.json`,
  `structure_set/*.json` and `template_pool/*.json` are resolved by the resource manager at server
  start, and the gate's structure lookups read what worldgen registered. A file that appears in
  `tarkovscav/city/` at runtime has no structure json, no structure set and no biome modifier, so it
  is never placed naturally, `/place structure tarkovscav:city/mycity` does not exist, and
  `/locate structure` will not find it. Making one generate naturally means shipping it as a
  datapack and restarting the server. `/tarkovscav city structures` prints this warning every time
  for exactly that reason.
* `spawn.cityStructureIds` / `cityStructureTags` keep their old meaning and keep working for the
  shipped `tarkovscav:city_small` and for any other mod's structures. The runtime pool is additive.

### Why `city add`, and not the structure id

`cityStructureIds` / `cityStructureTags` are for **worldgen** structures: the gate asks
`StructureManager`, which reads the chunk's structure *references* - the data worldgen writes while a
chunk passes `ChunkStatus.STRUCTURE_REFERENCES`.

`/place` does not write them. Measured on the dev server at 18 positions across a freshly placed city,
in three separate sessions:

```
[spawngate] test at BlockPos{x=124,y=-60,z=124} -> REJECT (…); chunk 7,7 starts=0 references=[]
```

`starts=0 references=[]` on every chunk of the placed city, while `/place template` and
`/place structure` both reported success and the blocks were verifiably present (`execute if block 124
-60 124 minecraft:gray_concrete` → Test passed). The mechanism itself is fine - a vanilla village shows
up as `references=[minecraft:village_plains:1]` right next to it - it is command placement that leaves
no trace in that data.

So: **paste the preset, then mark it once with `city add`.** That is the path that works today, and it
is also the only path that can work for a city you built by hand from scratch. The gate additionally
reads `LevelChunk#getAllStarts()` as a second, cheaper source (which is how any mod that *does* register
starts would be picked up), and prints what the chunk holds in every `city test` line so this is never
guesswork again.

### Regenerate / reshape it

```
java -cp tools/spike/out CityStructureGen                  # every variant, from tools/city-layout*.json
java -cp tools/spike/out CityStructureGen tools/city-layout-a.json   # just one
java -cp tools/spike/out CityStructureGen tools/strongpoint-layout.json   # the 18-building strongpoint
java -cp tools/spike/out CityStructureGen tools/district-layout.json      # the district template
java -cp tools/spike/out CityStructureGen --pieces-only --foundation 5 tools/district-layout.json
java -cp tools/spike/out CityStructureGen --report tools/city-layout.json  # print the metrics, write NOTHING
```

`--report` builds the layout, prints the per-floor floor report and the interior-variation JSON and writes no
file at all - that is what `tools/selftest_interior_variation.js` measures with. `tools/spike/selftest.ps1`
regenerates the four presets, the strongpoint and `gen_district` on every run and then asserts they are
byte-identical, which is also the determinism check (see §7l for the indoor fixtures and the variation).

**Four variants ship**, and each one is generated from its own layout file, so they are not rotations or
resizes of each other:

| preset | layout | size (x y z) | what it is |
| --- | --- | --- | --- |
| `tarkovscav:city_small` | `tools/city-layout.json` | 48 × 26 × 48 | the cross-street block: four 16×16 buildings (3–5 floors, two of them ruined) around a road crossing |
| `tarkovscav:city_a` | `tools/city-layout-a.json` | 56 × 30 × 56 | two office blocks (5 and 4 floors) plus a low ruined warehouse and a small annex around a paved courtyard |
| `tarkovscav:city_b` | `tools/city-layout-b.json` | 64 × 34 × 40 | one long **six-storey office slab** over an open plaza, with a three-storey annex and a two-storey ruined block |
| `tarkovscav:city_c` | `tools/city-layout-c.json` | 40 × 22 × 64 | a narrow **street canyon**: two rows of three small buildings (2–4 floors) facing each other across a 12-block alley, the heaviest street cover of the four |

Every variant is built from the same two palettes, held as constants in `CityStructureGen`
(`EXTERIOR_MODERN` for the outside: stone brick, deepslate, smooth stone, grey concrete, a dark frame
grid, glass panes, a dark oak double door in a chiselled frame with steps; `INTERIOR_SCAV` for the
inside: crate and barrel stacks, sandbag piles, plank barricades, iron bar gates, overturned tables,
rubble, shelves and lockers, lanterns and end rods, carpets and potted plants). Furniture is built out
of slabs, signs, fences and trapdoors, and **only vanilla blocks are used** - a structure is placed by a
server with just this mod, TaCZ and GeckoLib loaded, so a block from any other mod would place as air.
A layout picks a palette by name (`"palette": "modern_scav"`), which is the one place to relook all four.

Every building is procedurally furnished per floor: rooms split by one-block walls with doorways, and
each room gets cover, furniture and a light. `StructureNbtTest` counts them **per building per floor**
from the generated NBT and fails if any floor is below the `min_cover_per_floor` /
`min_furniture_per_floor` the layout declares, if a door's sill is not a full block, or if a door has no
wall on both sides at both halves ("a floating door"). Files per variant: `data/tarkovscav/structures/
<name>.nbt`, `worldgen/structure/<name>.json`, `worldgen/template_pool/<name>/start.json`; `city_small`
keeps its own structure set, the three new ones share `worldgen/structure_set/city_variants.json` so they
never generate on top of each other; all four are in `tags/worldgen/structure/city.json` and in
`spawn.cityStructureIds` (the default value - an existing toml keeps whatever it has).

### The entrance porch (2026-10 fix)

The doors used to get exactly **one** stair block outside, whose `facing` came from the palette default
(`north`) - so every door on the north, east or west wall had a **crooked** step, and there was no porch
at all. The rule now, in `CityStructureGen.entrance`:

* the run is always **axis-aligned with the door and centred on the doorway**: the door's own cell plus
  one either side for a lone door (3 wide), or exactly the two door cells of the pair with the mullion
  between them (also 3 wide);
* every stair **ascends toward the door** (`facing` = the direction from the step back to the door) and is
  written `half=bottom, shape=straight`, so the cells join cleanly and no stair can be rotated or
  misaligned;
* the run steps **down one block per row** away from the sill and stops the moment a row would have to cut
  into the ground or float. In this preset the exterior street is one block below the ground-floor slab, so
  the doorstep is the single row at sill level that bridges street (1.0) -> stair (1.5) -> sill (2.0); a
  building on lower ground gets more rows, up to three;
* a step is only placed on **air with something solid below**, so it never blocks the doorway's two
  passage cells and never floats, and it is placed with `place` (not `set`), so street cover is never
  overwritten.

`StructureNbtTest` verifies it per structure: for every door whose outside cell is outside the footprint
(a street entrance) it asserts the three-cell straight run, the correct stair `facing` and `half`/`shape`,
solid ground under every step, a clear passage cell above it, the contiguous descending run landing on
ground, and it prints `porches: N street entrance(s) with a step run`. The generator prints
`porches: N stair(s) across M entrance(s)` per structure (28 stairs / 37 buildings' worth across the six
layouts when this was written: 12/4 `city_small`, 12/4 `city_a`, 9/3 `city_b`, 18/6 `city_c`,
54/18 `city_strongpoint`, 6/2 `gen_district`). The four preset NBTs' pinned sha256 values in
`tools/selftest_strongpoint.js` and the five maps in `docs/maps/` were regenerated in the same change.

### Laying a variant out in a sandbox world

```
.\tools\build_city.ps1 -Variant city_a -X 100 -Y -60 -Z 100
```

That sends `<variant>_platform.txt` first (the flat stone plot and the air clear above it) and then
`<variant>_commands.txt`, both shifted to the given origin, over RCON to a running dev server -
`tools/spike/CityStructureGen.java` writes both from the same block grid as the `.nbt`, so the world and
the shipped structure cannot drift apart. `-SkipPlatform` stacks a variant on a plot that is already
there. To place one **in game** with no server at all, the same `.nbt` files are in
`<gameDir>/tarkovscav/city/`, so `/tarkovscav city reload` then `/tarkovscav city place city_a` works and
registers the box with the spawn gate.

### 7i. The 18-building strongpoint and the city spawn rate (2026-10-01)

**What the survey found** (`docs/城市战城区调研.md` §6-7): the generator already placed plenty of low
cover per district (218-240 usable cells in the shipped presets, against 15-21 in the user's hand-built
one), but it placed it as ISOLATED cells - only 0-5% of those cells sat inside a straight run of three or
more, against 58-76% in the hand-built district. The six building pieces measured 0/0/0/0/0/1 usable
cover lines, i.e. a generated building contained no usable cover line at all. So the fix was never "add
more cover"; it was "put the cover that is already there into lines, on the streets and on every floor",
plus one big 18-building strongpoint.

**What shipped**

* `tools/strongpoint-layout.json` -> `tarkovscav:city_strongpoint`: 18 buildings on a 6x3 grid, footprint
  138 x 34 x 66, 8-wide streets, 3-5 floors, some ruined, roof access everywhere, >= 3 usable cover lines
  per floor AND on the roof, and 87 street cover entries including a sandbag-plus-low-wall pair at each of
  the ten street junctions. Regenerate with
  `java -cp tools/spike/out CityStructureGen tools/strongpoint-layout.json`; the picture is
  `docs/maps/strongpoint.png` (`CityMap structure --nbt <nbt> --gen src\main\resources\data\tarkovscav\structures`).
* `CityStructureGen` learned opt-in layout keys: `min_cover_lines_per_floor` (**0 when absent**, so any
  layout that does not name it is generated byte for byte as before - that is why the four presets are
  untouched), `cover_line_min_length` / `cover_line_max_length` / `cover_line_blocks`, `cover[].level`,
  and `street.bands_x` / `bands_z` for a street GRID. The builder computes, for every candidate line, the
  longest span whose cells are free AND have a walkable neighbour, writes it, then re-runs the V4 metric
  and reverts unless the line count really went up - so a line cannot end up inside a wall, under a window
  pane, or merged into another run. A floor that comes up short aborts the generation.
* The wiring mirrors `city_small` exactly: `worldgen/structure/city_strongpoint.json` (single-piece
  jigsaw, `terrain_adaptation: beard_thin`, `max_distance_from_center: 116`),
  `worldgen/template_pool/city_strongpoint/start.json`,
  `worldgen/structure_set/city_strongpoint.json` with **spacing 192 / separation 48** (`structure_set/city_small.json`
  still uses spacing 24 against a 48-wide preset, so overlap was already possible there; 192 against a
  138-wide footprint is the point of this one), the id in `tags/worldgen/structure/city.json` and in
  `spawn.cityStructureIds`, and NO lang key - the four presets have none, so an invented one would be a
  raw-key leak the entity-registry gate does not ask for.
* `node tools/selftest_strongpoint.js` is the gate. It parses the NBT itself (gzip + NBT) and pins the
  four presets' sha256, so a generator change that moved them is caught here as well as in
  `selftest.ps1`.

**The city spawn rate: two different levers, told honestly.** Minecraft's biome-spawner `weight` is
relative WITHIN the `monster` category, so taking `add_scavs.json` from `12,6,4,3,2,2,2,1,1` (total 33) to
`5,2,2,1,1,1,1,1,1` (total 15) lowers **this mod's share** of monster spawns; it is not a linear rate dial
and it does not by itself reduce the number of vanilla zombies. The linear lever is
`spawn.cityRegionPadding`: with `scavCityOnly` (and the two gunner city-only switches) on, only the
padding ring around a city can spawn these mobs, so `24 -> 4` cuts the gateable area around one 80-wide
district from `(80+48)^2 = 16,384` cells to `(80+8)^2 = 7,744`, about **-52.7%**. The four weight values
that are ALSO declared in `Config.java` (`sniper.spawnWeight`, `sniper.villagerWeight`,
`troops.usecVillagerWeight`, `troops.bearPillagerWeight`) were moved with the JSON so the two can never
disagree; `selftest_entity_registry.js` fails on a mismatch.

**An existing install keeps the old numbers.** Forge never rewrites a config file that already exists, so
a pack with a `config/tarkovscav-common.toml` still has `cityRegionPadding = 24` (and any other old value)
until you edit that file or delete it and let the mod write a fresh one. The new defaults only apply to a
fresh config.

**2026-09-23 crash: `max_distance_from_center` 224 is not a legal value.** The strongpoint shipped with
`"max_distance_from_center": 224`. Vanilla's codec is `Codec.intRange(1, 128)`, and on top of that the
codec VALIDATES `max_distance_from_center + terrain-adaptation padding <= 128`, erroring with
`Structure size including terrain adaptation must not exceed 128`. The client refused to load ANY world
with `Failed to parse tarkovscav:worldgen/structure/city_strongpoint.json from pack
armedmobs-0.1.0-all.jar`, i.e. a datapack typo became "the game is broken". The exact padding comes from
the shipped server jar (`javap` of `JigsawStructure$1`): `none` -> 0, `bury` / `beard_thin` / `beard_box`
-> 12. The field is now **116** (the largest value `beard_thin` permits: 116 + 12 = 128). A size-1 jigsaw
performs no expansion steps, so the field does not have to cover the 138-wide footprint at all - the old
`>= 138` gate assertion could never be satisfied by a value that loads, and it has been corrected to the
real rule. `tools/selftest_datapack.js` now checks that rule for every shipped structure JSON, and the
dedicated server is the acceptance test.

### 7j. The urban wasteland dimension (2026-10-01)

**What it is.** A second dimension, `tarkovscav:urban_wasteland`, whose single biome is
`tarkovscav:urban_wasteland` and whose terrain is a low-relief, waterless, dead-city palette. It exists to
answer "make a terrain that generates buildings" - the city jigsaw structures that were already in the mod
now have a world that is *full* of them, because the dimension overrides their structure sets with much
tighter spacing.

**How to get in**

| Route | Who | What happens |
| --- | --- | --- |
| `tarkovscav:deployment_beacon` (部署信标) | survival, the primary route | right-click in the overworld: a 2 s cast, then you arrive in the wasteland. Right-click inside the wasteland: you go back to where you deployed from |
| `/armedmobs dimension [name]` (alias `/tarkovscav dimension`) | operators (permission 2), debug/admin | same shared landing code; no argument means the wasteland, a name means that dimension |

Both routes call `world/WastelandTravel.java` - one teleport, one safe-landing rule, one arrival message,
so the command path exercises exactly the code the item uses.

**The beacon's cast rule, exactly.** The item rides the vanilla use channel (`getUseDuration` = 40 ticks),
so holding right-click cannot restart the cast. During the cast you hear `minecraft:block.beacon.activate`
and see campfire-smoke and cloud particles; the cast is aborted, with its own message, if you take **any**
damage, if you move more than **4 blocks** from where the cast started (checked every tick, centre to
centre), if you let go early, or if you change dimension or disconnect. It only works in the overworld
(deploy) and in the wasteland (return). The cooldown is **10 s** between arrivals and the beacon is **never
consumed** - it is a tool, not a charge.

**Safe landing.** The target column is your own X/Z (you deploy "straight down your own grid square"), the
Y comes from the dimension's `MOTION_BLOCKING` heightmap, and the search walks up while either the landing
cell or the one above it is blocked, so you cannot arrive inside a wall or suffocate. If the cell below the
landing point has no collision (a hole, a cave mouth, open air), a **5x5 `stone_bricks` pad** is built one
block under you with a `torch` on a corner, and the message says so. On the return trip the anchor is a
per-player `SavedData` (`data/tarkovscav_retreat.dat`); if you have no anchor (first visit, or a fresh
world) you are sent to the overworld's shared spawn and told that this is what happened.

**Recipe** (survival-obtainable, shaped):

```
 I
ITI
 E
```
`I` = `minecraft:iron_ingot` (x3), `T` = `minecraft:redstone_torch`, `E` = `minecraft:ender_pearl` - a
signal canister that carries an ender pearl. The texture is generated procedurally by
`node tools/make_beacon_texture.js` (16x16, no third-party art).

**What generates.** The dimension type is a full overworld-like type (`min_y -64`, `height 384`,
`logical_height 384`, `natural`, skylight, no ceiling, `coordinate_scale 1.0`, `bed_works`, no respawn
anchor, raids on) with an urban-haze `effects` palette: `sky_color` `0x9A8259`, `fog_color` `0xC9B48E`,
`water_color` `0x4A6B5A`, `water_fog_color` `0x2E3A2C`. The noise settings are vanilla's `overworld.json`
extracted from the mapped client jar and adapted by `tools/make_wasteland_dimension.js`:

* **relief**: the vanilla `depth` function is `y_gradient + overworld/offset`; the wasteland keeps the
  gradient (so the average surface height does not move) and scales the offset spline to **0.35**, i.e.
  gentle terrain with hills at about a third of overworld amplitude, which is what a city needs to sit on.
  `minecraft:overworld/offset` is NOT overridden - two new density functions
  (`tarkovscav:urban_wasteland/depth` and `/sloped_cheese`) exist instead, so the overworld is untouched.
* **sea level -63** (the bedrock floor is -64): aquifers and the surface flood downward from the sea level,
  so below the floor means **no oceans, no lakes and no water in caves** - a dry wasteland, and the arrival
  platform never lands in water.
* **surface rule**: `coarse_dirt` / `gravel` / `andesite` on the surface over `tuff` / `stone`, driven by
  the vanilla `minecraft:surface` noise. No grass anywhere.
* **ore veins off** (`ore_veins_enabled: false`, the three vein fields constant 0), **aquifers on**,
  `disable_mob_generation: false`, `default_block: minecraft:stone` - all as vanilla.
* **surface dressing**: four configured/placed feature pairs - `tarkovscav:gravel_rubble` (a
  `random_patch` scattering gravel), `coarse_dirt_patch`, `debris_field` (cobblestone) - plus vanilla's
  `minecraft:patch_dead_bush`, all in the `vegetal_decoration` step. No roads: a road network is not
  something a placed feature can express, and the brief says so.
* the biome carries the mod's nine mobs in the `monster` category with the same weights as
  `data/tarkovscav/forge/biome_modifier/add_scavs.json` (`5,2,2,1,1,1,1,1,1`, total 15) plus `spawn_costs`.

**Density: one custom structure placement, two grids.** The obvious knob - a `structures` block in the
dimension JSON - **does not exist in 1.20.1**. `javap` of `net.minecraft.world.level.dimension.LevelStem`
shows the codec the datapack loader uses reads exactly two fields, `type` and `generator`;
`DimensionStructuresSettings` is only used on the level.dat path for the three built-in dimensions, so a
`structures` block in a datapack dimension is silently dropped and changes nothing. That was proven by
measurement: with the block in place, a forceloaded 512x512 sample of the wasteland held **2** city
structures - exactly the shipped sparse spacing.

The real mechanism is `tarkovscav:wasteland_spread`, a mod-registered `StructurePlacement`
(`worldgen/WastelandSpreadPlacement.java`, registered from `worldgen/ModWorldgen.java`):

* `isStructureChunk(ChunkGeneratorStructureState, x, z)` uses the **dense** pair when that chunk
  generator's biome source is a `FixedBiomeSource` holding `tarkovscav:urban_wasteland`, and the
  **shipped** pair otherwise. Both branches run vanilla's own random-spread arithmetic, including the
  salt-seeded `setLargeFeatureWithSalt`, so the overworld branch is not "similar to" vanilla - it is the
  same formula with the same numbers.
* The biome source is read reflectively and once, through a cached `Field` (vanilla keeps
  `ChunkGeneratorStructureState#biomeSource` private). An access-transformer line was tried first and did
  not publish the field in the ForgeGradle dev workspace, so the reflective read is the working route; the
  server log prints `[wasteland] structure placement reads ChunkGeneratorStructureState#f_254681_` at
  startup.
* `spacing()`/`separation()` report the **dense** pair, because `/locate structure` walks candidates in
  steps of `spacing()` and never hands the placement a generator. The cost is on the overworld side:
  `/locate` there now probes the dense grid, so it does not report the overworld's sparse-grid cities -
  which was already the documented behaviour (see the TODO below), now for a different reason.

**`frequency: 0.25` on all four city sets - the user's "cut the building rate by 75 %".** `javap` of
`StructurePlacement#isPlacementChunk` shows the order of decisions: the set's biome test, then
`if (this.frequency < 1.0F) { if (!this.frequencyReductionMethod.shouldGenerate(seed, salt, x, z, this.frequency)) return false; }`,
then the exclusion zone, then `isStructureChunk`. The frequency check therefore runs **before** the
placement decides, so one value cuts both dimensions by the same factor. 0.25 keeps the grid geometry, the
salt and the separation behaviour and multiplies the per-candidate acceptance chance - exactly 25 % of the
previous placements in expectation. `spacing` would also have given 25 % (density ~ 1/spacing^2, so
spacing x 2) but it moves the grid, so `frequency` is the knob that was chosen.

`spacing` and `separation` are in **chunks** (this is easy to get wrong), so a block distance is
`spacing * 16`:

| structure set | footprint (blocks, from its own NBT) | spacing/separation (chunks, both dimensions) | wasteland dense pair (chunks) | = blocks | verdict |
| --- | --- | --- | --- | --- | --- |
| `tarkovscav:city_small` | 48 x 48 | 24 / 8 | 4 / 1 | 64 | no overlap, dense |
| `tarkovscav:city_variants` (a/b/c) | 56 / 64 / 64 | 32 / 12 | 5 / 2 | 80 | no overlap, dense |
| `tarkovscav:city_district` | assembles its own, up to 2 x `max_distance_from_center` = 256 | 48 / 20 | 16 / 5 | 256 | room for a full district |
| `tarkovscav:city_strongpoint` | 138 x 66 | 192 / 48 | 192 / 48 | 3072 | deliberately left SPARSE: a world packed with 138-wide strongpoints would be unusable |

The wasteland's dense grid is 4x-41x the candidate density of the overworld grid, and the shared
`frequency: 0.25` then removes three quarters of those candidates: the measured result is a wasteland that
is many times denser than the overworld while the overworld itself is 25 % of what shipped before.

**Overworld generation is unchanged apart from that frequency cut.** All six city structures declare
`"biomes": "#tarkovscav:city_biomes"`, and that tag is
`["#minecraft:is_overworld", "tarkovscav:urban_wasteland"]` - a strict **superset** of the old value, so
every overworld biome that could host a city still can, and the wasteland is added. The placement's
overworld branch was verified against a control build whose four sets are plain
`minecraft:random_spread`: with the same world seed and the same 1024x1024 forceloaded sample, both builds
produce the identical set of `tarkovscav:` structure starts (chunk for chunk), which is the proof that the
custom type inherits vanilla's overworld placement exactly.

**`#minecraft:undead` in `tarkovscav:faction_village_hostile` was a dead reference (fixed 2026-09-25).**
That tag does not exist in 1.20.1 (the client jar ships exactly 13 entity_type tags and `undead` is not one
of them), so `TagLoader` logged
`Couldn't load tag tarkovscav:faction_village_hostile as it is missing following references: #minecraft:undead`
and silently created it EMPTY - the armed villagers were not targeting a single zombie or skeleton.
It is now the explicit 1.20.1 undead mobs (`#minecraft:skeletons` plus zombie, husk, drowned,
zombie_villager, zombified_piglin, zombie_horse, phantom, wither). **This is a behaviour change:** the
village-defence tag now actually contains undead, so armed villagers fight them. `selftest_datapack.js`
holds every `#minecraft:` entity_type tag reference against the list of tags that really exist in 1.20.1,
so the next dead reference fails a gate instead of a player's game.

**Measured evidence** (dedicated server, RCON + region-file scan; the full numbers, the surface histogram
and the rendered map are in `docs/城市废土维度.md`):

* the server starts with zero registry errors and creates the dimension;
* `/execute in tarkovscav:urban_wasteland run locate structure tarkovscav:city_strongpoint` and
  `... city_small` both answer with a real position, which proves the structure's biome tag includes the new
  biome AND that the terrain can host it;
* a forceloaded 512x512-block sample was scanned from the region files: **46** generated city structures
  with the dense grid, against **2** with the shipped spacing (the same sample, same seed);
* the overworld sample: 2 `tarkovscav` starts before the frequency cut, 0 after it (a 512x512 overworld
  sample only holds a couple of cities, so the arithmetic is what carries the 75 % - 2 x 0.25 = 0.5);
* `docs/maps/urban_wasteland.png` is the rendered picture (terrain + buildings).

**Gates.** `node tools/selftest_wasteland.js` checks the dimension chain, the tag superset, the density
table above (including `frequency === 0.25` on all four sets), the placement class and its registration,
the beacon's registration/assets/recipe/tab and that every message key exists in both languages.
`node tools/selftest_datapack.js` checks every shipped datapack JSON against its codec ranges.

### 7k. Interior connection states + doorways: two generator post-passes (2026-09-25)

The user reported two defects against the four shipped presets:

1. **"这个墙 以及玻璃没有互相连在一起"** - interior walls and window glass rendered as disconnected posts.
   Measured on the shipped NBTs: **8,250 of 8,510** connector blocks in `city_strongpoint` and **1,742 of
   1,742** in `city_small` were written with their connection properties saying "attached to nothing".
   The cause is structural: a structure NBT stores EXPLICIT block states and Minecraft does not re-run
   `updateShape` when a structure places them, so the generator has to bake them itself - and it wrote the
   palette entry's default properties.
2. **"建筑内的墙偶尔会挡住门"** - an interior partition wall occasionally blocked a doorway.

Both are fixed by two post-passes in `tools/spike/CityStructureGen.java` that run after the volume is
final and before the NBT is written:

* `bakeConnectorStates()` recomputes every `*_pane` / `iron_bars` / `*_fence` / `*_wall` from the final
  neighbour grid with vanilla's own rules: a pane or fence attaches to its own family or to a block that
  presents a full support face; a wall's side is `tall` for a neighbour wall, `low` for a full solid face,
  `none` otherwise, and its `up` post follows the straight-run rule (no post on a straight run, a post at
  ends, corners and crosses, propagated upward). Out-of-structure neighbours count as not connectable,
  exactly like vanilla structures. A chest / crafting table / cauldron IS a full support face in vanilla
  even though its outline is not, which is why they are excluded from the project's `NOT_FULL_SUPPORT`
  list while staying in `NOT_FULL_CUBE` (the "can this hold a door up" list).
* `repairDoorways()` restores both halves of every recorded door, then clears the two cells on each side
  along the passage axis at both head heights. It also protects the interior openings that have no door
  block: they are now recorded when they are punched, because the cover-line pass runs last and used to be
  able to seal a room by dropping one cover piece into its only doorway.
* `ensureConnectivity()` then floods from each building's entrance and, for any recorded room it cannot
  reach, opens one 2-high breach in that room's wall next to something already reachable - "leave the room
  sealed" is the one outcome the invariant forbids. It reports `sealedRooms` / `breached` in the
  generator's output (all six shipped structures end at `sealedRooms=0`).

**The structures were regenerated on purpose.** All six generated NBTs (the four presets, the strongpoint,
`gen_district`) come from the same layouts and seeds, so their bytes change; the sha256 pins in
`tools/selftest_strongpoint.js` were updated and the reason is recorded next to them. The extracted
buildings in `structures/buildings/` are the USER's own geometry and were not rewritten - the gate prints
their numbers instead of asserting them, and it found exactly one counterexample worth reporting:
`building_b1.nbt` has 2 blocked door sides (his build, left alone).

**Already-placed cities do not change.** Worldgen only affects chunks that have not been generated yet, so
a city already in a save keeps its old geometry.

**The gate is `node tools/selftest_blockstates.js`** (registered in `tools/spike/selftest.ps1`), a
zero-dependency NBT reader that asserts, per shipped structure: **zero** connector blocks that are
unconnected while they have something to attach to, **zero** `*_wall` blocks without explicit
north/south/east/west/up, and **zero** doorways without 2-high clearance on both sides. It also prints an
independent sealed-room metric; that number is REPORTED rather than asserted, because its definition and
the generator's per-room flood disagree on the shaft geometry and a gate that lies in one direction is
worse than a printed disagreement. After the fix the connector totals are: city_small 1,698 checked / 0
unconnected, city_a 1,705 / 0, city_b 2,064 / 0, city_c 1,366 / 0, city_strongpoint 8,254 / 0,
gen_district 976 / 0, and 0 walls missing their sides in all six.

### 7l. 室内刷怪笼、战利品箱与室内随机化（2026-09-26，B / C / G）

三件事一起做的，因为都挂在同一个东西上：**结构 NBT 现在能写方块实体**（`blocks: [{pos, state, nbt}]`）。
在此之前生成器只会给 jigsaw 连接器写 `nbt`，`minecraft:spawner` 和 `minecraft:chest` 写进去也只是
一个空壳方块——**加载不报错，但什么也不刷、什么都没有**。

#### B. 室内刷怪笼 → TROOP 档

每个建筑 1–2 个 `minecraft:spawner`（强点 18 栋只给 **4 个**，`spawner_budget` 卡住，不糊满地图）。
位置由布局里的建筑参数决定，并且**每个候选位置都是"先放上去再测量"**：先静态筛（地板上的空气格、头顶
2 格净空、贴内墙、不在门洞/楼梯井/竖井里、不长在掩体线上、旁边也不是掩体线的格子），再真正放下去，

* 该层（和下一层）的**可用低掩体线**数量**不允许下降**（箱子本身算低掩体，所以允许变多），
* 从各入口的**洪泛仍然能到达每一间房**（连通性不变量），
* 开了随机化的建筑还要**竖井当墙时每层仍然可达**（见 G）。

任何一条不过就撤掉换下一个候选；10 个候选都不行会打 WARNING 而不是硬塞。

刷的是 **TROOP 档**：`tarkovscav:usec_villager` / `tarkovscav:bear_pillager`
（`gun/AiProfile.java` 里 `Tier.TROOP` 就是这两个类），**只有这两个 id**。写的是 `SpawnData`
（`{entity:{id}}`）+ `SpawnPotentials`（`[{weight, data}]`，两条各 1 权重），外加显式数值：

| 键 | 值 | 含义 |
| --- | --- | --- |
| `SpawnCount` | 2 | 每次生成 2 个 |
| `SpawnRange` | 4 | 4 格半径内生成 |
| `Delay` | 60 | 首次 3 秒 |
| `MinSpawnDelay` / `MaxSpawnDelay` | 300 / 900 | 之后 15–45 秒一轮 |
| `RequiredPlayerRange` | 16 | 16 格内有玩家才工作（原版默认） |
| `MaxNearbyEntities` | 6 | 一个笼子周围最多 6 个 |

这些键名和形状不是猜的：`javap` 过 1.20.1 的 jar——`SpawnData.CODEC` 是 `{entity: CompoundTag}`
(+可选 `custom_spawn_rules`)、`SimpleWeightedRandomList.wrappedCodecAllowingEmpty` 是 `{data, weight}`、
`Weight.validateWeight` 拒绝负数、`BaseSpawner#load` 读的正是上面这些键名。**暗处/自然刷怪完全没动**
（还是 thug 为主）——刷怪笼只负责 TROOP 形态。

#### C. 战利品箱 + TaCZ 注入（不在 JSON 里写任何 `tacz:` id）

每个建筑 1–3 个 `minecraft:chest`（角落/贴内墙，同样的"放下去再测量"规则），方块实体里只有
`{id: "minecraft:chest", LootTable: "minecraft:chests/abandoned_mineshaft"}`——**用原版 id，不抄表**：
所以废矿那套战利品照旧，其它 mod 往这张表里的注入也自动生效。

TaCZ 的"弹药 + 改好的枪"是一个 Forge **global loot modifier**（Java，不是 JSON 物品 id）：

* `loot/CityChestLootModifier.java`：注册进 `ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS`
  （`tarkovscav:city_chest_extras`），声明在 `data/forge/loot_modifiers/global_loot_modifiers.json`，
  自己的条件是 `tarkovscav:mod_loaded`（`modid: tacz`）+ `forge:loot_table_id`
  （`loot_table_id: minecraft:chests/abandoned_mineshaft`）；
* **按位置限定**：只有箱子的 `LootContextParams.ORIGIN` 落在**我们自己的 `CityGate`** 认定的城区里才注入，
  真废矿保持原样。`CityGate` 本身不写死主世界（结构 start / 标签 / 显式区域 / 运行时放置的城区，任何维度都算），
  所以荒原维度里的箱子同样生效；
* 双保险：JSON 条件之外，`doApply` 里还有 `ModList.get().isLoaded("tacz")`，没有 TaCZ 时**什么都不做**，
  不报错、不产生空池；
* 数值：**1–3 个小口径弹药堆**（每种口径 6–24 发，口径来自 TaCZ 弹药索引里"本 mod 允许的枪所用的口径"，
  `GunPool.buildAmmo` 生成，按物品自身最大堆叠截断），**5 %** 概率给一把枪（档位权重 步枪 50 / 霰弹 30 /
  手枪 20），枪走的是**现有那条路**：`GunPool.rollLoadout` + `GunPool.buildGun(loadout, random, owner)`
  ——也就是说随机配件（`GunAttachments.apply`）和满弹匣都是现成代码，没有第二条造枪路径；
* 触发时**打一行日志**：`[cityloot] city chest at <x,y,z> (<dimension>) received ...`。

#### C 的一个必须记录的发现：`forge:mod_loaded` **不能**用在 loot modifier 里

需求原文要求"a `forge:mod_loaded` condition for `tacz`"。实测（`javap` 1.20.1 forge jar）：

* `forge:mod_loaded` 是 **crafting** 条件，注册在 `forge:conditions` 注册表里
  （`ForgeMod#registerCraftingConditions`）；
* global loot modifier 的 `conditions` 走的是 `IGlobalLootModifier.LOOT_CONDITIONS_CODEC`
  = `LootModifierManager.GSON_INSTANCE.fromJson(json, LootItemCondition[].class)`，也就是**原版战利品
  Gson**：它按 `"condition"` 字段在 `BuiltInRegistries.LOOT_CONDITION_TYPE` 里查
  （`LootItemConditions#createGsonAdapter`）；
* 那个注册表里 forge 只加了两个：`forge:loot_table_id` 和 `forge:can_tool_perform_action`
  （`ForgeMod#registerLootData`）。

所以写 `{"condition": "forge:mod_loaded"}` 的后果是 **GLM 反序列化失败、Forge 打
"Could not decode GlobalLootModifier with json id ..." 把它整条丢掉**——静默失效。
本 mod 的做法是把这个条件**自己实现一遍**：`loot/ModLoadedLootCondition.java` 注册成
`tarkovscav:mod_loaded`（`modid` 字段同名），语义完全一致（`ModList.get().isLoaded(modid)`），
闸门里同时断言"我们的 JSON 用了它"和"整个 datapack 里没有一处 `forge:mod_loaded`"。

#### G. 室内随机化（同一栋楼、不同种子，量得出来不一样）

布局键（可写在布局顶层做默认，建筑条目可覆盖）：

| 键 | 含义 |
| --- | --- |
| `interior_variation` | 0/1：房间网格**由种子生成**，不再总是 `rooms_x × rooms_z` |
| `extra_openings_per_wall` | 每道隔墙额外 0..N 个 1x2 门洞大小的开口（房间互通） |
| `floor_holes_per_floor` | 每个**上层楼板** 1..N 个 1x1/1x2 的**楼层洞**（上下打通） |

* 隔墙数量在 `rooms_x/z` 附近 ±1，位置再随机抖动；房间永远 ≥3 格宽，两道隔墙至少隔 4 格（保证 3 格长的
  掩体线放得下）；
* **楼层洞是"真的通道"**：洞本身、洞上面 2 格、洞下面 1 格必须全是**空气**（不能是挂着的锁链——否则灯会
  变成挂在空中的孤儿）；洞要替换真实楼板、严格在建筑内部、避开竖井、避开所有门洞/开口（三个高度都算）、
  避开掩体线格；**洞下面那一格必须是已被入口洪泛到的可达地板**；
* 洞里"下面可达 → 上面从不可达房间变可达"是**优先**选法（能连上新房间的洞优先），连不出新房间的洞在
  废墟（塌掉的那一块已经上下通了）里也允许放，但**每个上层楼板至少 1 个洞**；
* 竖井（梯子）在测量时被当成**墙**：报告里的 `floorsWithoutShaft` 就是"只靠楼层洞能到达几层"，六个出厂
  布局**每一栋楼都是 满/满**（例如 `north_west:4/4`），这就是"楼层洞确实被当成竖直连接"的量化证明；
* 掩体线规划时把洞口列（洞 + 上 2 + 下 1）和**门洞通道格**一起列为"占用"：所以洞不会吃掉一条线，门洞
  也不会事后被 `repairDoorways` 清掉一条已经数过的线（这是这一版修掉的一个真实缺陷）。

量出来的分布（六个出厂布局，`CityStructureGen --report` 直接给）：

| 指标 | min | max | mean | n |
| --- | --- | --- | --- | --- |
| 每层隔墙数 | 1 | 7 | 3.27 | 142 层 |
| 每层开口数（含主门洞） | 1 | 14 | 6.72 | 142 层 |
| 每个上层楼板洞数 | 1 | 2 | 1.46 | 105 个上层 |

#### 生成顺序（这一版改了，值得记）

```
街区/建筑/街道掩体 → bakeConnectorStates → repairDoorways → ensureConnectivity
                   → markPassages（门洞通道格） → 楼层洞 → 掩体线（规划在这里落地）
                   → 刷怪笼/箱子（放下去再测） → bakeConnectorStates/repairDoorways/ensureConnectivity
                   → 竖井无关可达性报告 → 补齐每层下限（不抢线、不封房） → 清掉"挂空"的装饰
```

理由：门洞修复**必须**先跑，"洞下面必须是可达地板"和"连通性"才是有意义的测量；掩体线放在洞**之后**，
线的规划器就能把洞口列当占用格，洞也就不可能出现在一条线的中间。最后一步 `sweepDetachedDecor()`
清掉"接不到任何东西"的家具/灯串（废墟的碎石砸掉了锁链的楼板、小房间把桌腿挤到别处时会漏出来这种），
用的是 `selftest_strongpoint.js` 数连通分量时用的同一条规则（空气和低掩体家族不算"连接"）。

#### 闸门（都注册进 `tools/spike/selftest.ps1`）

* `node tools/selftest_blockentities.js`：每个刷怪笼的 `SpawnData`/`SpawnPotentials` 只认那两个 TROOP
  id、权重是非负整数、七个数值键与生成器常量一致；每个带 `nbt` 的箱子必须指向**存在或已知**的战利品表；
  除刷怪笼/箱子外任何方块都不许带 `nbt`；数量符合布局声明的每建筑上限与 `spawner_budget`。
* `node tools/selftest_interior_variation.js`：用 `CityStructureGen --report`（**只打表、不写文件**）跑
  六个出厂布局 + 两个"同尺寸、不同种子"的合成布局，断言每个上层都有洞、每层都能只靠洞到达、隔墙/开口
  不是恒定值，并且两个种子的**每层指纹/隔墙数/房间邻接图**确实不同，最后打印上面的分布表。
* `node tools/selftest_loot_modifier.js`：注册与声明、双保险条件、`CityGate` 位置限定、**任何 datapack
  JSON 里不许出现 `tacz:` 物品 id**、loot 包里只有一个文件碰 TaCZ API（那个被守卫的 helper）、
  `forge:mod_loaded` 不许出现（含 javap 事实的注释），以及 5 % / 1–3 堆 / 6–24 发这些权重。
* `tools/selftest_blockstates.js` 加了一条便宜的结构性检查：每个刷怪笼/箱子必须带 `nbt`。

**又一次故意的重新生成**：六个结构 NBT 全部重新生成（`--report` 与两份布局文件之外没有别的输入），
四个 preset 的 sha256 pin 已更新并注明原因；`docs/maps/` 里受影响的图（4 张 preset、strongpoint、
pieces_buildings）已用 `CityMap` 重画。生成器是**确定性的**：连跑两遍逐字节相同，
`selftest.ps1` 每次都会重生成这六个文件并用 "workspace-unchanged" 把"字节不变"钉成断言。

---

### 7m. 指挥系统：三件阵营道具 + 信号棒 + 信号点方块（2026-10）

**设计定稿见 `docs/指挥系统设计.md`；这一节是实现说明 + 生效配置，`docs/COMMAND_AND_CONFIG_REFERENCE.md` 的 5.24 / 5.25 是逐键表。**

**三件道具严格阵营锁定。** `village_command_tool` 只影响 `#tarkovscav:faction_village`，
`illager_command_tool` 只影响 `#tarkovscav:faction_illager`，`scav_command_tool` 只影响
`#tarkovscav:faction_scav`；判定**只有**一条 `entity.getType().is(tag)`，没有任何分支能绕过它，
三个标签本身也两两不相交（`tools/selftest_command_marks.js` 逐个断言）。

**标记中立、可编号。** 标记不属于任何阵营，任何道具都能把任何标记当目标。同维度可同时存在多个，
按创建顺序自动取**第一个空闲字母** A、B、C、D…（删掉 B，下一个新标记就是 B）；上限 `command.maxMarks`
（默认 12），超出时**最旧的失效，新标记一定进得去**。玩家身上有「当前选中标记」，按 **玩家 UUID** 记在
服务端 `SavedData` 里，重登保留；当前标记被删掉或过期时自动回落到第一个存活标记。标记按**维度**隔离。

| 来源 | 生命 |
| --- | --- |
| 指挥道具右键方块 | `command.stickDurationTicks`（默认 6000 tick = 5 分钟） |
| 信号棒（投掷物） | 同上；落地生成标记并**把自己掉在原地**，可以捡回来（是工具不是消耗品） |
| 信号点方块 | **永久**；方块带 `BlockEntity` 记住自己的字母，破坏方块即精确注销**自己那一个**标记 |

**右键语义**（`CommandToolItem`）：右键方块 = 新建标记 → 设为当前标记 → 立刻下令；潜行+右键方块 =
只新建标记；右键空气 = 向当前标记下令；潜行+右键空气 = 循环切换当前标记并在聊天栏显示
「当前标记：B (x, y, z)」。本维度没有标记时两种右键都给出明确提示，从不静默失败。

**推进逻辑**：命令写在单位**自己的持久化 NBT**（`tarkovscav:advanceOrder`），重登 / 区块卸载 /
服务器重启都不丢。速度 = 自身步行 × `command.speedScale`（默认 0.65），**永不冲刺**；每一步都走现有
`CombatTactics` 掩体搜索并认领掩体（不是直线冲锋）；`command.coordination = true` 时按实体 id **错峰出发**，
并按现有 `SquadCoordinator` 的 `isSuppressor` 交替掩护轮换**跃进**（一个成员停、其余前进），不新写第二套协同；
进入 `command.arrivalRadius`（默认 4 格）命令自动解除。**战斗优先**：任务由优先级 5、只占用 `MOVE` 的
`AdvanceOrderGoal` 执行，而 `GunAttackGoal` 是优先级 1 且占用 `MOVE+LOOK`——有目标时它自然抢不到移动权，
撤退（`GunAiState.RETREAT`）同理，且撤退期间连**新命令都不接**；撤退结束、命令未过期则继续推进。

`/armedmobs marks` 列出当前维度全部标记（字母 / 坐标 / 来源 / 剩余时间），
`/armedmobs marks remove <字母>` 删除单个，`/armedmobs marks clear` 清空本维度。

**生效配置（`[command]`，默认值）**

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `command.enabled` | `true` | 总开关；false 时道具惰性并给出提示，已有命令也不再推进 |
| `command.radius` | `32.0` | 影响半径（格）；`4.0..256.0` |
| `command.speedScale` | `0.65` | 推进速度倍率；`0.05..1.0`，永不冲刺 |
| `command.arrivalRadius` | `4.0` | 到达半径（格）；`0.5..32.0` |
| `command.stickDurationTicks` | `6000` | 信号棒/道具临时标记存活 tick（5 分钟）；信号点标记永久，不受影响 |
| `command.coordination` | `true` | 错峰出发 + 交替掩护跃进；false = 全体同 tick 起步、各自直接走 |
| `command.maxMarks` | `12` | 单维度标记上限；超出时最旧的失效 |

### 7n. 一次性城市驻军：一个城市固定刷几队，永不重刷（2026-10）

用户原话：「一个城市固定刷几队，不会再刷，一队通常 1-5 人」。实现是 `world/CityGarrison.java` +
`world/GarrisonData.java`（`SavedData`）。

#### 7n.1 阵营按「楼」分配：一个城市可能被两方割据（2026-10 追加）

用户原话：「生成可以改一下生成的几栋楼里面 有可能一个区块一个楼会生成不同阵容」——所以阵营的
**单位是楼，不是城**；一座城会整体偏向一方，但也可以是**割据（contested）**的。

* **城的倾向**：`garrison.friendlyCityChance`（默认 **0.5**）决定这座城的**主导阵营**是村民（village）还是
  掠夺者（illager）。**是否全城统一**由 `garrison.cityDominantFactionChance`（默认 **0.5**）决定：命中则全城
  每栋楼都是主导阵营；否则城市**割据**，每栋楼各自独立 50/50 掷一次。默认值下约 **56% 的城市是统一的**
  （4 栋楼时：0.5 直接统一 + 0.5×(1/16) 割据但恰好掷成同一方），其余是真正的混编城；把
  `cityDominantFactionChance` 调成 `1.0` 就精确恢复「一城一阵营」的旧行为，调成 `0.0` 则几乎每城都混编。
* **掷骰是确定性的**：城的骰子 = `hash(世界种子, 维度|城市标识)`；楼的骰子 = `hash(世界种子, 维度|城市标识#building:序号)`。
  同一个世界种子里，同一座城的同一栋楼永远得到同一个阵营。`CityFactions` 全部是纯函数，
  `tools/selftest_city_faction.js` 在 JS 里逐位镜像它（含 murmur3 收尾的 32 位乘法），并用 4000 个城市键
  实测分布均匀。
* **一旦记录，永不改变**：城的「主导阵营 + 每栋楼的阵营」在第一次被问到时（玩家靠近、或第一次自然刷怪
  检查）**写入账本**，之后编辑配置概率也不会改变已有城市。账本键仍然是维度感知的：
  楼行 = `维度|城市标识#楼id`，楼 id 是生成器写出的 `序号:楼名`（如 `0:north_west`）。
* **楼是怎么认出来的**：（a）structure 由多个 jigsaw 件拼成（城区）时，**每个件的世界包围盒就是一栋楼**；
  （b）出厂的单件城市（`city_small` / `city_a|b|c` / `city_strongpoint` / `gen_district`，整座城就是一个 NBT）
  由生成器 `tools/spike/CityStructureGen.java` 从**同一份 layout** 顺手写出
  `data/tarkovscav/city_buildings/<结构名>.json`（每栋楼一条 `x/z/w/d` 矩形 + 结构尺寸），运行时读回并把
  矩形按**该 piece 自己的随机旋转**（`StructureTemplate.transform` + `JigsawPlacement` 掷出的 `Rotation`）
  映射到世界坐标；（c）两者都没有（别的模组的结构、手画 `cityRegions`、运行时 `city place`）时，整座城算
  一栋楼——这是安全的回退，等价于「一城一阵营」。
* **每栋楼一个阵营的落点**：`CityBuildings.indexAt` 先做**包含判定**，不在任何楼里（街道上）则取**最近的楼**。
  所以刷怪笼（生成器永远放在楼内地板上）总能归到它所在的那栋楼，而街道上的驻军/自然刷怪归到离它最近的楼。
* **刷怪笼按楼改写**：城市阵营第一次确定时，把城里每个 `minecraft:spawner` 的 `SpawnData`/`SpawnPotentials`
  改成**它所在那栋楼**的 TROOP id（村民楼 = `usec_villager`，掠夺者楼 = `bear_pillager`），其余键
  （数量/半径/延迟等）原样保留；**只碰已加载区块**、**绝不碰盒子外面的刷怪笼**，整盒区块都覆盖过才记账
  （`rewriteCitySpawners`，默认 true）。出厂的 NBT 保持**混编的默认对**（模板是共享的，烘焙阵营会让全
  世界同阵营），所以改写的意义就是把默认值纠正成本城的分布。
* **驻军按楼出兵**：每个驻军成员的阵营取自**它自己站立点所在的那栋楼**，所以割据城里一个建筑内不会混编、
  楼与楼之间才会不同；统一城里全城纯色。队长同样是该楼阵营的 ELITE（`elite_villager` / `elite_pillager`）。
* **自然刷怪按楼过滤**：`garrison.factionSpawnFilter`（默认 true）。用 `faction/Faction.java` 的实体类型
  标签判定，所以本模组的 gunner/sniper/TROOP/ELITE **以及原版村民/掠夺者/铁傀儡**都算数：村民楼里不会刷
  出掠夺者阵营单位，掠夺者楼里不会刷出村民阵营单位。**SCAV 阵营是第三方，两种城里都放行**（我拍板的决定，
  写在这里以便用户纠正）；若这使「友好城市」显得过于敌意，请见报告而不是静默改掉。没有任何账本条目的城市
  会在刷怪检查的当下**即时掷骰并记录**，所以这个过滤器不可能"误封一切"。`/summon` 与刷怪蛋默认豁免
  （除非 `spawn.gateCommandSpawns` 打开）。
* **可观测与覆写**：`/armedmobs garrison` 逐城列出主导阵营 + **每栋楼的阵营** + 刷怪笼是否已改写；
  `/armedmobs city faction` 单列同一份账本；`/armedmobs city faction <城市键> village|illager|auto` 按键覆写
  整座城（键后加 `#<楼id>` 只改一栋楼），覆写持久化并在下一次触发生效；
  `/armedmobs city faction <village|illager|auto> [pos]` 在调用者（或 `pos`）所在的城市上**立即生效**
  （立刻改写已加载区块的刷怪笼，并补刷尚未放置的驻军）——控制台可以配合 `/execute positioned` 使用。
* **后续批次（本次不做）**：阵营**兵力池**（20-100，按城市大小缩放）、按阵营显示的 HUD 血条、击杀扣池、
  池归零后该区域该阵营不再刷武装单位。账本已经为它留好位置（见 `GarrisonData` 的 TODO）：需要的是
  **按城、按阵营**的一行，键形如 `维度|城市标识|阵营名`，字段 `strength`（int）/`max`（int）/最后变化 tick；
  若要做成按楼，则挂在 `维度|城市标识#楼id` 上。这两者都只是同一个 `SavedData` 文件里再加一张表。

* **只刷一次**：账本键 = **维度 + 城市标识**（结构 id / 区域名 / 运行时实例名 + 包围盒中心按 16 格取整），
  写在 `data/tarkovscav_garrison.dat`。玩家第一次进入 `garrison.triggerRadius`（默认 64 格，算到盒子最近点）
  时放置，此后无论驻军是否全灭都**不再触发**——因为账本里根本没有"存活"这个概念，只有"这个城市已经刷过"，
  而代码里没有任何一处会删除账本条目（闸门静态断言这一点 + 确定性模拟"全灭后再问 50 次"）。
* **触发粗粒度**：`TickEvent.ServerTickEvent`（END 阶段）每 `garrison.checkIntervalTicks`（默认 100 tick = 5 秒）
  检查一次；只遍历**已加载**区块（`getChunk(..., FULL, false)`），玩家在野外乱走不会因此加载地形。
  主世界与 `tarkovscav:urban_wasteland` 都走同一条路径（维度是账本键的一部分）。
* **组成**：普通成员取**该楼阵营**的 TROOP 档（村民楼 `tarkovscav:usec_villager`，掠夺者楼
  `tarkovscav:bear_pillager`），小队 1-5 人；每队另有 `garrison.eliteLeaderChance`（默认 **0.2**，即约五分之一）
  的概率**额外**多一名该楼阵营的 ELITE 队长（`elite_villager` / `elite_pillager`）。“一城固定刷几队、永不重刷”
  的规则不变，变的只是每名成员的阵营来自它所在的楼。
* **每城几队**：`garrison.squadsPerCity = -1`（默认）走**尺寸公式** `1 + max(宽, 深) / 48`，夹在 `1..6`；
  填 `1..6` 则所有城市固定用这个数。
* **真正的小队**：同一支小队共享一个 `tarkovscav:squadId`（写进持久化数据，跨重启有效），
  并通过 `gun/SquadCoordinator` 各认领一个掩体方块。`SquadCoordinator.refresh` 只在本单位**带** id 时
  才要求对方 id 相同，所以普通怪物的小队行为与以前完全一致（无 id = 旧逻辑）。
* **放置点**：脚下实心、自身与头顶两格无碰撞、不在门里；第一遍只收看不到天空的点（**优先建筑内部**），
  第二遍才放开；点与点之间至少隔 2 格，所以整座城的驻军是散开的。
* **可观测**：每座城市刷出时写一行 `[garrison] <城市> -> N squads / M units [dominant <阵营>: {各阵营计数}]`；
  `/armedmobs garrison` 打印生效配置 + 账本（维度 / 城市 / 小队数 / 单位数 / 放置 tick）+ **每城每栋楼的阵营**；
  `/armedmobs city faction` 单列阵营账本。一个站立点都找不到时**不写账本**，下一次检查会重试
  （避免"这一次区块半加载 → 城市永久空着"）。

**生效配置（`[garrison]`，默认值）**

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `garrison.enabled` | `true` | 总开关；false 只停新放置，已放置的与账本都保留 |
| `garrison.squadsPerCity` | `-1` | -1 = 尺寸公式 `1 + max(宽,深)/48`（夹 1..6）；1..6 = 固定值 |
| `garrison.squadSizeMin` | `1` | 小队人数下限 |
| `garrison.squadSizeMax` | `5` | 小队人数上限；手改 toml 把 min 写大时读取时自动交换 |
| `garrison.eliteLeaderChance` | `0.2` | 每队额外一名精英队长的概率 |
| `garrison.triggerRadius` | `64.0` | 触发半径（格，到城市盒子的最近点） |
| `garrison.checkIntervalTicks` | `100` | 检查间隔（tick）；只查已加载区块 |
| `garrison.friendlyCityChance` | `0.5` | 城市**主导阵营**是村民的概率；0 = 全图偏掠夺者，1 = 全图偏村民 |
| `garrison.cityDominantFactionChance` | `0.5` | 整座城**统一**的概率；否则每栋楼独立 50/50（割据城）。1.0 = 恢复「一城一阵营」 |
| `garrison.rewriteCitySpawners` | `true` | 首次确定阵营时把城内刷怪笼改写为**所在楼**的 TROOP id；只碰已加载区块 |
| `garrison.factionSpawnFilter` | `true` | 自然刷怪按楼过滤阵营；**SCAV 两种城都放行** |

> 限制（诚实记录）：命令只能下给**本模组自己的三个武装基类**。`faction_village` 标签里的原版
> `minecraft:villager` 等也在阵营内，但 Forge 没有给"给别的类加 Goal"的入口，所以它们**不会**被命令
> 驱动——`CommandMarks.isCommandable` 直接跳过，不会收到一条永远执行不了的命令。阵营判定本身仍然只有标签那一条。

### TODO (recorded, not done)

**The city does not generate naturally yet.** `/locate structure tarkovscav:city_small` → "Could not
find a structure of type …", while every datapack file loads without error and both `/place` commands
work. The structure_set / `start_height` / `project_start_to_heightmap` combination needs tuning before
"scavs spawn in cities with no setup at all" is true. Until then, the workflow above (`place template` +
`city add`) is the supported one. This also means the structure-id/tag gate path has so far only been
verified in the *reject* direction.

---

#- **别用 `client.modelRenderType = "zOffset"` 治"某些角度变材质/发黑"**：`entityCutoutNoCullZOffset` 用的是
  `POLYGON_OFFSET(-1.0, -10.0)`，是**斜率相关**的深度偏移——偏移量随视角变化，某些角度下层间深度会整个反过来，
  内层方块赢过外壳，模型看起来就是"一堆乱块"（用户截图实测）。`cutout` 与 `zOffset` **都是 NoCull**，
  所以这从来不是剔除问题。真正的病因是**重合面**（两个骨骼的面在同一平面），修法是
  `tools/scan_overlapping_geometry.js` 查、`tools/patch_coplanar_faces.js` 把其中**较小的那个 cube 沿法线挪
  0.03 单位**（=0.002 格，肉眼不可见；扫描器自己的容差是 0.02，所以挪 0.01 不够、会被继续判为重合）；
  **只改 `origin`**，不动 size/UV/inflate/mirror/rotation/骨骼。2026-09-24：20 对 → **0 对**（分两趟，第二趟
  1 个 cube），`selftest_rig_bones.js` 现在把"重合面 = 0"钉成永久性质。
## 8.0 踩过的坑（工具与资产，都是真踩出来的）

- Java 注释里出现斜杠加星号：闸门的注释剥离器会把它后面几千行当成注释吃掉，症状是"Config 少了一堆键"的假故障
  （实测害 selftest_antistall 报 13 条、selftest_render_scale 报 2 条）。写路径时用 buildings/<name>.nbt 这种形式。
- 新的 layout 别用 city-layout 前缀命名：既有闸门按这个 glob 认"整城 layout"，会要求它必须有对应的 structure/池子/标签。
  城区那套叫 tools/district-layout.json。
- 这个 rig 的"隐藏面"是靠 UV 指到 alpha=0 实现的：entityCutoutNoCull 会把落在透明像素上的面直接丢掉，
  所以 UV 一旦落到外壳上，那一面就变成看穿面，而且每个面只在看进去的那个角度可见——姿势一变可见集合就变，
  症状就是"某些角度头/背包不见了/发黑"。tools/scan_transparent_faces.js 是检查手段（逐姿势统计 holes），
  tools/patch_transparent_faces.js 是修法（只重写那几个面的 UV，不动 cube/骨骼）。
  2026-09-24：rest/aim/walk 共 10/6/10 个看穿面，打补丁后四个姿势 holes = 0。
- 手雷弹道求解器把雷当成 **0.25 箱体、只取箱底中心采样**，且按 `v*=0.99 → v.y-=0.03 → pos+=v` 的顺序积分；
  原版投掷物是**先用初速度移动、再上阻力与重力**，碰撞盒还带 ±0.125 的横向范围。所以"刚好越过 1 格矮墙"
  的解在闸门里只剩约 0.17 格余量——**别把投掷散布调回去**（`GrenadeEntity.shoot` 的 inaccuracy 现在是 0），
  否则那点余量会被随机散布吃光，怪又开始撞墙。另一个坑：固定投掷速度下**任何**弧都落不到约 10 格以内，
  改 `GRENADES_MAX_THROW_SPEED` 或怪那 0.85 的系数会同时改变"多近还能扔"（6–9 格会被 `requireClearArc` 收回）。
- `GunBrain#tick` **不是每 tick 都跑**：它唯一调用者是 `GunAttackGoal#tick`，而那个 Goal 只在"有活目标 + 有枪"时
  才活跃。任何"挂到 `GunBrain#tick` 就会对所有单位每 tick 生效"的想法都是错的（会漏掉没目标、没枪、以及
  以后新加的单位）。门那套（§5a）因此是 **两条驱动**：`GunBrain#tick` + `DoorBehavior` 自己的
  `LivingEvent.LivingTickEvent`，并用 `lastTick == gameTime` 去重。
- `setCanOpenDoors(true)` **不等于"会开门"**：它只放宽寻路器（路径允许穿过关着的木门），把门推开的动作在
  `DoorInteractGoal`/`OpenDoorGoal` 里，而原版只有 `Vindicator` 注册了它（`Raider`/`Pillager` 的常量池里
  根本没有 `OpenDoorGoal`，是 javap 看出来的）。所以"给九个怪都调了 `allowDoors`，开门就算修好了"是个假结论，
  §5a 里那张表就是这次的教训。
- 原版 `DoorInteractGoal#canUse` 的细节容易抄错：它的 `doorPos` 是 `(node.x, node.y+1, node.z)`，取的是门的
  **上**半；交互半径是 `distanceToSqr <= 2.25`（1.5 格），而 `InteractWithDoor` 给同伴留门的半径是另一个数
  （`MAX_DISTANCE_TO_HOLD_DOOR_OPEN_FOR_OTHER_MOBS = 2.0d`，还有 `SKIP_CLOSING_DOOR_IF_FURTHER_AWAY_THAN = 3.0d`）。
  这两个 2.0/1.5 不一样，`[ai].doorCloseAllyRadius` 的默认值就是照前者定的。
- **"只打短点射、打不死人"的根不在 AI 档**（§5ab）：点射长度读的是 `[tiers.<gun>] burstShots`，梭间停顿和
  `REPOSITION` 也是别的键。只改 AI 档的旋钮（精度、压制、掩体）根本碰不到 TTK；必须先确认"这条规则到底归谁"。
  同理，`-1` 在旧代码里被 `burstSize` 悄悄改写成 30 发，配置注释承诺的"打空弹匣"从来没成立过——**注释和代码
  对不上时，闸门要自己执行一遍解码器**，别信注释。
- **同一个 tick 里的两处判断必须用同一个输入**（§5ab）：弹匣长度、梭间停顿、warm-up 豁免都依赖"目标是否暴露"。
  如果 `AccuracyProfile` 和 `GunBrain` 各自去 ray cast 一次，两者会在目标刚好进出掩体的那一 tick 上打架
  （一个按"暴露"决定打 30 发，另一个按"没暴露"给首发乘 0.45）。所以判定**每 tick 只算一次**，由 `GunBrain`
  清零并重算，`AccuracyProfile` 通过 `GunUser#targetExposedNow` 读同一个值。
- **"故意蹲着不动"会被"原地不动"看门狗误判**（§5ab）：`checkMovementProgress` 把 RETREAT 当移动状态，所以
  一个在掩体后执行 `retreatHoldTicks` 的单位会被记 WARN、还会白烧 `unreachableTicks`（等于缩短 hold、提前放弃
  整场战斗）。凡是"新增一种合法的静止"，都要同时告诉那个看门狗。

## 8. Dev server + RCON verification

Ports: game `25566`, RCON `25577`, password `dsh123`, flat world, `generate-structures=true`.

> `generate-structures` **must** stay `true`. `StructureManager#getStructureAt` resolves through
> `level.getChunk(x, z, ChunkStatus.STRUCTURE_REFERENCES)`; with structures disabled that status is
> never reached, so the structure-id gate could not see anything even in principle. The dev
> `server.properties` carries a comment about this.

```
.\tools\spike\dev-server.ps1 -Restart
node tools\rcon.js 127.0.0.1 25577 dsh123 "list"
```

### Running TaCZ in the dev workspace (this one cost real time)

TaCZ **cannot** simply be dropped into `run/mods` here, and the failure is nasty: the game dies
during mixin application with

```
Critical injection failure: @Inject annotation on onTickServerSide could not find any targets
matching 'Lnet/minecraft/world/entity/LivingEntity;m_8119_()V' in net.minecraft.world.entity.LivingEntity
```

because a released mod ships a mixin refmap full of SRG names (`m_8119_` is `tick()`) while the
ForgeGradle dev workspace uses official names. Two things fix it, and both are in the project:

1. `build.gradle` pulls TaCZ in with `fg.deobf` as `compileOnly` + `runtimeOnly`, so the *classes* are
   dev-mapped;
2. the run configs set `-Dmixin.env.remapRefMap=true` and
   `-Dmixin.env.refMapRemappingFile=build/createSrgToMcp/output.srg`, so the *refmap* is translated to
   the dev names as well.

With both in place TaCZ loads normally in `runServer`.

### Commands added by the mod (permission level 2)

Every command below is registered under **both** root literals: `/tarkovscav` (the internal id) and
`/armedmobs` (the display name). They are one tree - a `tree(rootLiteral)` helper is registered twice - so
`/armedmobs city add ...` and `/tarkovscav city add ...` are interchangeable.

| Command | What it does |
| --- | --- |
| `/tarkovscav city add <name> [radius] [pos]` | marks a box as a city area and saves it to `cityRegions` |
| `/tarkovscav city remove <name>` / `list` | manage and inspect the configured areas |
| `/tarkovscav city import <name>` | reads `<world>/generated/minecraft/structures/<name>.nbt`, validates it, copies it to `<gameDir>/tarkovscav/city/` and registers it in the runtime pool |
| `/tarkovscav city reload` | re-scans that directory and re-registers everything in it |
| `/tarkovscav city place <name> [pos] [rotation] [mirror]` | places one instance and registers its box for the spawn gate |
| `/tarkovscav city structures` | lists the jar's structures, the runtime pool and the placed instances, and states the worldgen limitation |
| `/tarkovscav city test [pos]` | runs the **real** gate and reports ACCEPT/REJECT with the reason |
| `/tarkovscav spawn <scav\|gunner_pillager\|gunner_villager> [pos]` | spawns **through the gate**; refuses outside a city |
| `/tarkovscav test fight [distance]` | builds a complete firefight: one scav vs one no-AI practice dummy (a zombie tagged `tarkovscav_dummy`) |
| `/tarkovscav test watch [seconds] [distance] [pos]` | the same fight plus the anti-stall regression gate: after the window it asserts the mob **moved ≥ 1 block and fired ≥ 3 shots**, and logs `[test] WATCH PASS/FAIL … moved=… shots=… dummyDamage=… stalls=…` |
| `/tarkovscav test stall [seconds] [distance] [pos]` | the same fight with every shot refused on purpose (`FORGE_EVENT_CANCEL`, without calling TaCZ): asserts the watchdog broke the stand-off (`escapes ≥ 1`) and logs `[test] STALL PASS/FAIL …` |
| `/tarkovscav test sound <pool\|clip> [pitch]` | plays the voice clips at the command position, one every 1.5 s: `all`, `idle`, `chatter`, `contact`, `taunt`, `grenade`, `mark`, `death`, or a single clip name like `contact_1`. Every line is logged. With `pitch` (0.1-2.0) every clip plays at that pitch; without it the clips sweep `voice.pitchMin..pitchMax` so you hear the whole range |
| `/tarkovscav cover` | prints every cover candidate the nearest gun mob can see, with its hidden/open verdict |
| `/tarkovscav gunpool [tier]` | prints the TaCZ guns that tier may be issued |
| `/tarkovscav debug` | prints the state machine of every gun mob within 32 blocks |
| `/tarkovscav marks` | lists every live command mark in your dimension: letter, position, source (`tool` / `stick` / `point`) and remaining time |
| `/tarkovscav marks remove <letter>` | deletes one mark (any source, including a signal point); orders pointing at it are dropped |
| `/tarkovscav marks clear` | empties your dimension's marks (the debugging path) |
| `/tarkovscav garrison` | prints the one-time city garrison's effective config and its ledger: which dimension + city has already placed, how many squads/units, at which tick, and the faction of EVERY building of every decided city |
| `/tarkovscav city faction` | prints the faction ledger only: every decided city with its dominant faction and one line per building (`<序号:楼名> -> 阵营`), plus whether its spawners were rewritten |
| `/tarkovscav city faction <cityKey> <village\|illager\|auto>` | overwrites the faction of a city by its ledger key; append `#<buildingId>` to overwrite one building. Persists in the ledger and is applied on the next garrison trigger |
| `/tarkovscav city faction <village\|illager\|auto> [pos]` | resolves the city at the caller (or at `pos`, `/execute positioned ...` from the console) and applies the overwrite IMMEDIATELY: rewrites the loaded chunks' spawners per building and places the garrison if it was not placed yet |

Client-side (they run on your own client, no server permission):

| Command | What it does |
| --- | --- |
| `/tarkovscav client reload` | re-reads `config/tarkovscav-common.toml` from disk and re-applies it (hidden bones, head accessories, head pitch, mount transform) without restarting. Forge re-reads **all** COMMON config files here - that is the only public entry point - so other mods' common configs are re-read from disk too |
| `/tarkovscav client state` | prints what is actually in effect: the merged hidden set with a reason per bone, the head pitch and its states, the anchor, the context and both mount transforms |
| `/tarkovscav client gunpose [family=] [pitch=] [yaw=] [roll=] [x= y= z=] [scale=] [context=] [reset]` | the live gun-transform tuner (§5b): applies next frame, prints the toml and saves it |
| `/tarkovscav client scale <value\|up\|down> [step]` | the live **model size** tuner (§5b): writes `client.renderScale`, saves the toml, applies next frame. Clamped to `0.3 .. 2.0`, non-numbers refused. `up`/`down` step by `0.05` unless a step is given. Shadow radius and culling padding follow the scale |
| `/tarkovscav client villagerpose [pitch= yaw= roll= x= y= z= scale= anchor=arms\|body] [aim= hold= reload= hunker=] [forward= back= left= right= up= down=] [idlePitch= idleYaw= idleRoll= reloadPitch= reloadYaw= reloadRoll= hunkerPitch= hunkerYaw= hunkerRoll=] [hideGun=on\|off] [reset]` | the live tuner for the **gunner villager's gun and pose** (§5j): writes the `gunnerVillagerGun*` keys, the four arm pitches and the three per-pose gun-rotation deltas, saves the toml, applies next frame. No argument prints the current values; `reset` restores every one of them from the shipped `DEFAULT_*` constants. **Tune the arm angle first** - the gun hangs off the arms block, so the arm angle moves it |
| `/tarkovscav client hide <bone>` / `show <bone>` | adds/removes a bone in `client.extraHiddenBones` - the bisect tool for "something on the model looks wrong" (§10) |

Build the city into the dev world block by block over RCON:

```
.\tools\build_city.ps1 -X 100 -Y -60 -Z 100
```

---

## 9. TaCZ version tolerance (1.1.7 and 1.1.8)

`mods.toml` declares `tacz` as `mandatory=true` with `versionRange="[1.1.7,)"`, so **both the 1.1.7
release and the 1.1.8 hotfix satisfy it** (and so does anything newer). The build compiles against
1.1.8 because that is the jar in `libs/`, but every API member this mod touches was checked with
`javap` against the user's `tacz-1.20.1-1.1.7-release.jar` and found **byte-identical**:

| API used | 1.1.7 | 1.1.8 |
| --- | --- | --- |
| `IGunOperator.fromLivingEntity/initialData/draw/aim/reload/bolt/getDataHolder` | OK | OK |
| `IGunOperator.shoot(Supplier<Float>, Supplier<Float>, long)` | OK | OK |
| `IGunOperator.getSynShootCoolDown/getSynIsBolting/getSynReloadState/getSynAimingProgress` | OK | OK |
| `ShootResult` (all 16 constants incl. `NETWORK_FAIL`, `OVERHEATED`, `IS_BOLTING`) | OK | OK |
| `ReloadState$StateType.isReloading()` | OK | OK |
| `IGun.getIGunOrNull/getCurrentAmmoCount/setCurrentAmmoCount/setBulletInBarrel/hasInventoryAmmo/getGunId` | OK | OK |
| `AbstractGunItem.findAndExtractInventoryAmmo(IItemHandler, ItemStack, int)` | OK | OK |
| `GunItemBuilder.create/setId/setAmmoCount/setAmmoInBarrel/setFireMode/build/forceBuild` | OK | OK |
| `AmmoItemBuilder.create/setId/setCount/build` | OK | OK |
| `TimelessAPI.getAllCommonGunIndex()/getCommonGunIndex(ResourceLocation)` | OK | OK |
| `CommonGunIndex.getType/getGunData`, `GunData.getAmmoId/getAmmoAmount/getRoundsPerMinute/getBolt` | OK | OK |
| `ShooterDataHolder.baseTimestamp` (public field) | OK | OK |

The one behaviour the whole gun AI depends on was verified in **both** jars: `ShooterDataHolder`'s
constructor sets `baseTimestamp = System.currentTimeMillis()` in 1.1.7 and 1.1.8 alike (same bytecode:
`invokestatic System.currentTimeMillis` → `putfield baseTimestamp`). Because the timestamp handed to
`shoot()` is computed as `now - baseTimestamp` (§3), the value of that field cancels out - the check
passes on either version, and would keep passing if a future version changed what it stores there.

No reflection or soft-dependency fallback is needed: there is no member in the table that 1.1.7 lacks.

---

## 10. Known limitations

* **Client rendering is unverified here** - no game client was launched in this environment. The
  renderers, layers, clip names, bone names and assets are all checked mechanically (see
  `tools/spike/selftest.ps1` and the rig self-tests in `tools/`), but "does the gun sit in the hand
  correctly" still needs one look in game; §6 is the checklist for that look, and the `gunMount*` keys
  plus `/tarkovscav client gunpose` exist for exactly that.
* **The gun mount defaults are neutral because the context changed, not because they were measured.**
  §5b proves `ItemDisplayContext.FIXED` was the item-frame layout (mirror flip, 1.2 scale) and switches
  to `THIRD_PERSON_RIGHT_HAND` (0.6 scale, the positioning group a hand uses, and the context vanilla's
  `ItemInHandLayer` passes), and §5f proves the anchor frame was over-rotated (31.9° from the duplicate
  bone rotation) and drawn outside a hand frame. Both are structural fixes; the last few degrees of roll
  and the exact scale are a matter of taste and have **not** been seen in game. `gunAnchorMode` picks
  between the corrected frame and the old one, and `/tarkovscav client gunpose` tunes the rest.
* **An existing `config/tarkovscav-common.toml` still holds the old compensation values.** Forge only
  writes a default for a key that is missing, so the file that shipped with an earlier jar keeps
  `gunMountRifleRotation = ["0","-180","0"]` and `gunMountRifleScale = 0.65`. The live log from the
  report proves it: `[gunmount] scav: … rot=[0.0,-180.0,0.0] … scale=0.65 context=THIRD_PERSON_RIGHT_HAND`.
  Run `/tarkovscav client gunpose reset` once (or delete those lines) to get the neutral baseline.
* **`modelLayering` ships as `upperLower`, and `single` freezes an armed mob's legs.** The rig's gun clips
  contain **0 leg tracks** (measured: `tac:hold/aim/aim:fire/reload` animate 10 upper-body bones and none
  of the 8 leg bones; only `tac:idle/walk/run` animate legs), so one controller playing one clip cannot
  walk and shoot at the same time. `upperLower` restores armed walking and is still one draw;
  `client.modelLayering` plus `/tarkovscav client reload` switches live. An earlier build defaulted to
  `single` (the originally requested presentation); set `client.modelLayering = "single"` if frozen legs
  matter more than the layered one. A merged-clip
  variant (one controller whose clip carries both the leg and the upper-body tracks, generated from the
  two sources, with the loop lengths reconciled) would give both - it is the documented follow-up, not
  something that is in this build.
* **The rig has 12 see-through faces in its rest pose** (4 backpack, 4 head, 2 eye gear, 2 vest) which the
  author created by pointing faces at empty atlas space; the set changes per clip (2-12) because the
  clothing layers sit on different bones. Quantified by `tools/scan_transparent_faces.js` and fixable with
  `tools/patch_transparent_faces.js`, which is **not applied** (§5g): the faces are small, they do not
  explain a whole upper body's worth of foreign pixels, and re-authoring the author's UVs blind is not how
  this project works.
* **The 20° head rest pitch and the accessory defaults have not been seen in game either.** They are
  derived from measurements (which hat covers what, which eye piece collides with which hat, which
  keyframes collapse to constants - §5c/§5d) and they apply to the idle path only, but "20° is exactly
  right" is a human judgement. Change one number, `/tarkovscav client reload`, look again.
* **The "upper body renders as another outfit from a certain angle" report has been narrowed, not
  reproduced.** Every angle-independent cause was ruled out with data:
  * **inverted winding from negative/zero-size cubes**: the rig has exactly **one** cube with a negative
    size component - `Lianru2[0]`, `size=[3.194,-0.0056,3.194]`, `origin=[-6.597,15.813,-1.597]` - and
    it is inside the `Lianru` subtree, which is in the default `hiddenBones`, so it is never drawn. The
    only zero-thickness cubes are the two eyeball planes in the head, which the report says is fine.
  * **back-face culling**: the render type is GeckoLib's default `RenderType.entityCutoutNoCull`
    (`GeoRenderer#getRenderType` → `GeoModel#getRenderType`; neither `ScavRenderer`, `ScavGeoModel`,
    `GunnerPillagerGeoRenderer` nor `GunnerPillagerGeoModel` overrides it), i.e. culling is **already
    off**, so a wrong winding cannot hide a face and "switch to NoCull" would change nothing.
  * **the texture**: the atlas has exactly one olive-green patch (`x 176…200, y 0…32`) and one pale
    white/blue cluster (`x 130…215, y 128…200`). `node tools/bone_uv_colors.js` +
    `tools/atlas_report.ps1` attribute the green patch to `Parrot`/`Chibang` and the pale cluster to
    `Lianru`/`Lianru2`/`Lianru3` - both in the default `hiddenBones`, and the user's own `latest.log`
    confirms they were hidden (`[model] scav: hid 8 bone(s) [Gun3 Ban Lianru Bao Spwt Jiu Parrot money]`).
  * what is left is (a) **z-fighting between the layered upper-body clothing**: the vest
    (`fangdanyi_2`, 30 cubes), the backpack (`Bag`, 32 cubes, 78 of its 192 faces sample fully
    transparent pixels), the sleeve layers (`bone52`/`bone999`) and the body share planes and swallow
    each other - `node tools/scan_overlapping_geometry.js` lists every pair - and (b) the **gun drawn
    with the item-frame context**, a mirrored 1.2-scaled blocky model sitting at the right hand across
    the chest. (b) is fixed by §5b. (a) is depth-precision flicker from the author's layering and can
    only be fixed by moving geometry, which is not something to do blind.
  * the user's 30-second confirmation: walk a full circle around the mob looking up at it with
    `/tarkovscav client hide <bone>` toggled on the candidates one at a time (`Bag`, `fangdanyi_2`,
    `Parrot`, `Lianru`), and `/tarkovscav client gunpose scale=0` to remove the gun from the equation.
    Whichever toggle makes it vanish names the culprit.
* **`useGeckoModel = true` still shows the placeholder rig in its bind pose for the legs.** Its
  animation file has `idle`/`walk`/`aiming`/`firing` while the entity asks for the `tac:*` names, so
  GeckoLib finds no clip (`Unable to find animation: tac:hold:rifle` in the log) and only the code arm
  pose (§5e) moves. The real rig is expected to ship those clip names.
* **`/tarkovscav client reload` re-reads every COMMON config file**, not just this mod's. That is the
  only public Forge entry point for "read the config files again" (`ConfigTracker#loadConfigs`), so a
  mod whose common config is edited on disk mid-session is re-read at the same time. If that is
  unwanted, `F3+T` is the alternative for resource-side changes and a restart for config changes.
* **The author's own aim keyframes are revived only in part, on purpose.** The clips drive `Head`, `Arm`,
  `UpBody`, `AllBody` (and even the feet in `tac:run`) through Molang expressions that reference
  YSM-only variables - `ysm.head_yaw`, `ysm.head_pitch` - plus `query.head_x_rotation`,
  `query.head_y_rotation` and `query.is_sneaking`, none of which GeckoLib registers on its own:
  `node tools/scan_molang.js` counts **106 keyframes carrying such expressions in 12 of the 17 clips**
  (61 distinct expressions in total). GeckoLib resolves an unknown variable to **0** rather than failing
  (`MolangParser#getVariable` -> `computeIfAbsent(key, key -> new LazyVariable(key, 0))`), so without
  `client.molangVariables` every one of those keyframes collapses to a constant -
  `(ysm.head_yaw > 0) ? (-ysm.head_yaw + 35.1606) : (35.1606)` is simply `35.1606`,
  `query.head_y_rotation` is simply `0`. The variables are now fed (see §5r), but the **yaw** symbols are
  opt-in: the author's yaw keyframes sit on `UpBody`/`AllBody`, whose parent chain already carries the
  entity's body yaw in GeckoLib, so feeding them measures a 31.7° lower-body swing. The default
  (`client.molangVariables = pitch`) revives the pitch keyframes only, and `/tarkovscav client pose molang
  all` is the one-keystroke A/B for the rest. `math.min`/`math.abs` are **not** part of this: GeckoLib
  registers them under their `math.*` names, so they evaluate and never warn.
* **A wide translucent "beam" from the mob's upper body is TaCZ's bullet tracer, not this rig's
  geometry.** TaCZ draws a stretched tracer for tracer ammo
  (`com/tacz/guns/client/renderer/entity/EntityBulletRenderer#renderTracerAmmo`), and the second report
  ("a giant cone, white/orange/purple gradient with horizontal bands") was checked against the live
  session rather than assumed:
  * **it is coloured per ammunition.** Every `display/ammo/*_display.json` in the installed 1.1.7 pack
    carries a `tracer_color`, and the renderer picks it with `GunDisplayInstance#getTracerColor()`:
    `12g` `#FF3030` (red), `308` `#FF9999` / `338` `#FFAAAA` / `50ae` `#FF9999` (pink),
    `357mag` `#ffe09e` / `45acp` `#ffb373` / `50bmg` `#FF4732` (orange-red). The beam *texture* itself is
    **pure white** (`assets/tacz/textures/entity/beam.png` is 64 pixels of `255,255,255,255`;
    `beam_s.png` is pure blue `0,0,143,144`), so all of the colour is a tint and any rainbow has to come
    from several tracers overlapping.
  * the tracer is drawn with `RenderType.energySwirl` (additive transparency, UV scroll), i.e. the quads
    **add** where they overlap: a shotgun fan of differently-tinted beams sums towards white/pink/purple,
    and the individual quads' edges are the "horizontal bands". A beam passing between the camera and the
    ground adds a constant tint to everything behind it, which is why the lower half of a screenshot can
    look flat.
  * **the live log confirms our mobs were firing**: 49 `[gunai] … shoot -> …` lines in that session
    (`SUCCESS`/`COOL_DOWN`), with scavs and pillagers equipped with `shotgun/hamster:one_barrel`,
    `shotgun/bo6:marine_sp`, `pistol/bf1:kolibri` - a shotgun burst is several tracers at once.
  * TaCZ 1.1.7 exposes **no third-person tracer switch**. `config/tacz-client.toml` only has
    `FirstPersonBulletTracerEnable = true`; the tint/size can only be overridden per bullet through the
    `tacz:tracer_override` / `tacz:tracer_size` NBT keys, which this mod does not create. The practical
    mitigations are a resource pack that replaces `tacz:textures/entity/beam.png` + `beam_s.png`, or
    living with it.
  * **the rainbow is not a shader artefact in that session**, and that was checked rather than assumed:
    `config/oculus.properties` has `enableShaders=false` (with `shaderPack=ComplementaryUnbound_r5.4 +
    EuphoriaPatches_1.5.1` selected but off), and the log says `Oculus: Shaders are disabled because
    enableShaders is set to false`. Iris 1.7.6 *and* Oculus 1.8.0 are both installed - an odd pair - and
    the pack is one keypress (`K` in this instance's `options.txt`) away, so it is still the first thing
    to rule out if the colour looks wrong.
  * the HUD text in that screenshot ("草: 踩踏") is **another mod's**: `PresenceFootsteps` ships that
    string in `assets/presencefootsteps/lang/zh_cn.json`. It is a footstep debug display, nothing to do
    with this mod.
  * `node tools/scan_overlapping_geometry.js` finds no large or stretched geometry in the rig (no
    duplicate/dangling/cyclic bones, no `poly_mesh`, no missing face UVs, max cube size ~8 units, max
    pivot ~27) and no clip has a `scale` channel at all, so nothing in the model can reach the sky. Our
    textures are 256×256 opaque entity atlases with no rainbow gradient either.
  * 30-second bisect, in order: **1.** walk somewhere flat and look again (the cone follows the beam, not
    the terrain) → if it appears only when a mob fires, it is the tracer; **2.** press `K` (Iris toggle)
    → if the *colour* changes, a shader is involved; **3.** hide the shooter with
    `/tarkovscav client hide <bone>` on the upper-body props, and `/tarkovscav client gunpose scale=0`
    → if the cone disappears with `scale=0` the gun (and its muzzle) is the source; **4.** drop a resource
    pack that replaces `tacz:textures/entity/beam.png` with a transparent 8×8 PNG → if that kills the beam,
    it was the tracer and nothing else. TaCZ's own client render config is the place to turn tracers off.
* **The city does not generate naturally yet** - see the TODO at the end of §7. Until the structure_set
  is tuned, a city has to be placed with `/place template` and marked with `/tarkovscav city add`.
  Because of that, the structure-id/structure-tag gate path has only been verified in the *reject*
  direction so far.
* **`SUPPRESS` has never been observed in game** - it is implemented (`tactics.suppressChance` etc.) and
  the target-memory it depends on is logged (`memory=` in `/tarkovscav debug`), but every test
  engagement kept line of sight, so it has not fired yet.
* **Door closing has been proven by source + simulation, not by eye.** §5a's rule (open a wooden door the
  unit's own path goes through, shut it once the unit is clear and nobody is within 2.0 blocks of either
  half) is asserted structurally and simulated tick by tick in `tools/selftest_doors.js`, but "walk a mob
  through a door and watch it swing shut" needs one look in game. Two deliberate holes: a unit that dies
  or unloads with a door still pending leaves that one door as it is (the memory dies with it, as with the
  vanilla villager brain), and a unit that is permanently stuck with its hitbox inside a door block will
  keep that door open rather than be crushed by it. Iron doors are never touched at all
  (`DoorBlock#isWoodenDoor` is `canOpenByHand()`); trapdoors, fence gates and other mods' custom doors are
  out of scope.
* **The gunner-pillager placeholder rig's gun anchor pivot is derived, not authored, and has never been
  looked at in game.** `gunner_pillager.geo.json` now carries a `RightHandLocator` (parent `RightHand`,
  pivot `[-5.447, 10.1185, 0.106]`, see §5 for the arithmetic), which is what makes
  `useGeckoModel = true` mount the gun instead of rendering the mob empty-handed - but the pivot is a
  *calculation* from the scav rig, so whether the gun sits exactly in the palm there is unverified.
  The placeholder is meant to be replaced by the real rig anyway, and the real rig must keep the scav's
  bone names. Its `hiddenBones` entries still match nothing (it has no `Gun3` and none of the author's
  props) - `tools/scan_geo_structure.js` prints that, and the anchor chain no longer depends on it.
* The `tac:melee:*` and `death` clips are imported; `death` is wired as a triggered animation (also not
  yet seen in game), melee is not driven (the AI never chooses melee while it has ammunition).
* `RifleLocator` / `PistolLocator` (holstered weapons) are identified and reserved, not used.
* The gate's padding around a structure is radial-sampled (8 points), not an exact distance to the
  nearest piece.
* A mob's gun is persisted by id; if the gun pack that provided it is removed, the mob rolls a new one
  of its tier.
* **The AI intelligence tiers (§5aa) have been proven by source + simulation, never by eye.** The four
  profiles, the nine-way entity mapping, the reaction window, the sniper hold-fire threshold and all
  four coordination rules are asserted structurally and simulated in `tools/selftest_ai_profiles.js`,
  but no client was launched. What to watch for, tier by tier: **SCAV** - it should visibly hesitate
  (0.6-1.2 s) after spotting you, rarely break for cover, and walk at you in a straight line;
  **SNIPER** - it should stand still, hold fire when the shot is bad (a distant sniper that *misses* is
  expected; one that never fires at all would mean `minHitChance` is too high, though the 3-window
  fallback means even a mis-set floor ends in a shot), and relocate when you close in or hit it; **TROOP** - two or more of them should shoot the *same* target, one should keep
  firing while the other moves, and a wall should be answered with a noticeably longer and denser
  suppression; **ELITE** - fastest to react, closes to about 60 % of its weapon's range before firing,
  advances in longer bounds / short rushes, and joins the same focus fire and flanking.
* **The coordination layer is advisory, not a formation controller.** A squad member only ever biases a
  decision it could already make, and the overwatch role expires (`overwatchTimeoutTicks`) exactly so a
  bad squad lookup can never park a mob. Two consequences worth knowing: the "one suppresses while the
  other moves" effect only appears when one of them has *lost* line of sight (SUPPRESS is the existing
  blind-fire state, there is no new "fire while moving" mode), and a mob that dies with a cover spot
  claimed leaves that one block reserved until `coverClaimTicks` expires.
* **The sniper hold-fire estimate is a hit *chance*, not a hit *prediction*.** It uses the shooter's own
  error cone and the target's half-width (the same model §5o/§5aa describe), so a moving player can
  still be missed and a stationary one at long range can still be refused; the floor is a difficulty
  knob, not a guarantee.
* **`[ai] enabled = false` gives uniform behaviour, not "the old per-tier behaviour".** There was no
  per-tier behaviour before this feature: every armed unit used the same `[combat]` + `[tactics]`
  numbers, and that is exactly what the switch restores.
* **The exposed-target lethality and the hurt reaction (§5ab) are proven by source + simulation, never by
  eye.** The exposure truth table, the burst decoder, the "pause only while exposed" branch, the hold
  clock, the "being hit while retreating cannot change the state" guarantee and the peek re-engage are all
  asserted and executed in `tools/selftest_ai_fire.js`, but no client was launched, so the *feel* is
  unverified. Two things to watch, in order: (1) a troop/elite with an enemy standing in the open should
  put a whole magazine down range with no pause and no reposition in the middle - if it still stops every
  six rounds, check that the target really is exposed (`eyes` AND `feet` line of sight; a low wall, a
  fence post or a single stair block between the two mobs makes it *not* exposed, by definition) and read
  `exposed=` in `/tarkovscav debug`; (2) a troop/elite that has taken damage should break contact, sit in
  cover for about 3-4 s, and then peek back out - if it walks back into the open it is the `holdTicks = 0`
  path, so check `[ai.<tier>] retreatHoldTicks` is not 0. Because the exposure test is two ray casts per
  FIRE tick, a *very* large number of simultaneously firing mobs is the one place the cost could show up;
  nothing in this environment measured that.
* **The mag dump is limited only by TaCZ's own rate of fire and the magazine.** With
  `exposedBurstShots = -1` and `exposedBurstCooldownTicks = 0` a troop/elite fires until the magazine is
  empty, the target dies, the target stops being exposed, or the FIRE watchdog gives up. There is
  deliberately no artificial cap between those bursts, so a tier tuned with a large magazine and a high
  rate of fire will feel harsher than the shipped rifle numbers - that is the knob working, not a bug.

