# 城市编辑沙盘 — 使用指南

这个文档对应存档 `塔科夫城市-可编辑`（英文目录名占位 `TarkovCityEdit` 只在构建期使用）。
目的：把模组里的城市预设摊开成若干份、放在平整地面上，方便你挑一栋改，
再把改完的建筑交回来变成新的结构。

> **交付状态（2026-09-23 更新）：存档里第 1–7 份是你原有的，本轮一个新变体都还没有落到存档里。**
> 原因：本轮开工时**你正在这个世界里**（`session.lock` 被客户端占用、`region` 7:46 还在写），
> 往活着的世界写区块有并发风险，所以**新坐标与新命令都已备好、但没有落盘**。
> 你退出世界后，按第 2.1 节跑一条命令即可（约 1 分钟）。你原有的 7 份与他自己的改动**一个方块都没动**。
>
> ⚠️ 唯一需要你**手动做**的一件事在第 6.1 节：把城市区域粘进实例配置。
> 那一步**必须先把游戏关掉**（Forge 在游戏启动/退出时会重写那个文件，游戏开着改会被覆盖）。

---

## 2.1 本轮新增的 3 个城市变体（**尚未落盘**，坐标已实测预留）

新变体和老的不一样，不是旋转/缩放：占地、层数、内部布局都不同（见 README 第 7 节）。

| # | 变体 id | 模板尺寸 | 最小角坐标 | 台子（60+ 格见方，台面 y=63） | 状态 |
| --- | --- | --- | --- | --- | --- |
| N1 | `tarkovscav:city_a` | 56 × 30 × 56 | `40 63 520` | x 32–103, z 512–583 | 未落盘 |
| N2 | `tarkovscav:city_b` | 64 × 34 × 40 | `200 63 520` | x 192–271, z 512–583 | 未落盘 |
| N3 | `tarkovscav:city_c` | 40 × 22 × 64 | `360 63 520` | x 352–407, z 512–599 | 未落盘 |
| N4 | `tarkovscav:city_small`（第二版，对照用） | 48 × 26 × 48 | `520 63 520` | x 512–583, z 512–583 | 可选 |

- 全部在第 1 排（z≈120）与第 2 排（z≈320）**以南 200 格**，即 z=520 起的一整排新地皮；
  与第 7 份（`384 65 336`）、自然生成的两处（`16 ~ 0`、`448 ~ 400`）以及**你自己那份**
  （`-383 34 -132` 到 `-254 163 -3`）都不相交。
- `city_small` 的模板本轮也重做过（真门 + 每层家具），所以 N4 是"新版对照份"；你原有的 7 份是旧版，
  方块不会再变（世界生成只影响新生成的区块）。
- **进游戏后就能用**（不需要落盘、不需要重启）：4 个 `.nbt` 已经放进
  `C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\tarkovscav\city\`，所以
  `/tarkovscav city reload` → `/tarkovscav city place city_a`（站在你想要的位置）即可，
  它会把占用的箱子登记给刷怪门（`/tarkovscav city test` 立刻能看到 `Inside a city area`）。
  注意 `/city place` 是**直接放置**（不建台子、不清地形），建议先 `/tp` 到一片平地上。

### 退出世界后怎么落盘（约 1 分钟，一条命令）

1. 完全退出 `塔科夫城市-可编辑`（确认 `saves\塔科夫城市-可编辑\session.lock` 不再被占用）。
2. 用本仓库既有的沙盘服务端流程（第 3 节那套：把存档副本挂到 `tools/spike/citysave/server/TarkovCityEdit`、
   起服务端、RCON）。落盘命令是：

   ```powershell
   .\tools\build_city.ps1 -Variant city_a -X 40  -Y 63 -Z 520
   .\tools\build_city.ps1 -Variant city_b -X 200 -Y 63 -Z 520
   .\tools\build_city.ps1 -Variant city_c -X 360 -Y 63 -Z 520
   # 可选对照份：
   .\tools\build_city.ps1 -Variant city_small -X 520 -Y 63 -Z 520
   ```

   它会先把 `<variant>_platform.txt`（64+ 格见方的平滑石台 + 清空到 y=150）发过去，再发城市本体；
   两份命令都由 `tools/spike/CityStructureGen.java` 从同一份方块网格生成，所以**放出来的和 jar 里的 `.nbt` 是同一座城**。
   `-SkipPlatform` 用于在已有台子上叠放。
3. 落盘后再把第 6.1 节的 `cityRegions` 追加 3 条（游戏必须关着改），或者进游戏里
   `/tarkovscav city add city_a 40` 逐块加。

> 如果你更想自己看得见再决定：直接在游戏里 `/tarkovscav city place city_a` 放到任意空地即可，
> 效果与落盘一致（区别只是没有那块石台、也不会写进存档的地形）。

---

## 1. 世界里有什么

| 项目 | 值 |
| --- | --- |
| 存档目录 | `C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\saves\塔科夫城市-可编辑` |
| 显示名 `LevelName` | `塔科夫城市-可编辑` |
| `DataVersion` | 3465（Minecraft 1.20.1） |
| 世界类型 | 普通（overworld / `minecraft:normal`），**种子 `20240913`** |
| 游戏模式 | 创造（`GameType=1`），**允许作弊 `allowCommands=1`** |
| 难度 | 和平（Peaceful） |
| 出生点 | `36 64 144`，朝东（+X） |
| 模组 | tarkovscav 0.1.0 + TaCZ 1.1.7 + GeckoLib 4.8.2（服务端只加载这三个） |

出生点旁边放了三块告示牌（坐标表摘要）和一个箱子；箱子里有一本成书《城市编辑与交回指南》
（9 页，含完整坐标表和交回步骤）以及一个结构方块。

### 世界规则（为编辑服务）

| gamerule / 设置 | 值 | 为什么 |
| --- | --- | --- |
| `difficulty` | `peaceful` | 和平模式，编辑时没有敌对生物 |
| `doMobSpawning` | `false` | 完全不刷怪（含城市里的暴徒） |
| `doDaylightCycle` | `false` + `time set day`（daytime=1000） | 光照恒定，截图/对比不受日落影响 |
| `doWeatherCycle` | `false` + `weather clear` | 不会下雨下雪 |
| `keepInventory` | `true` | 万一死了不掉东西 |
| `mobGriefing` | `false` | 苦力怕/末影人不会破坏你的改动 |
| `randomTickSpeed` | `0` | 草不会长、作物不会变、雪不会积 |
| `doFireTick` / `fireDamage` / `fallDamage` / `drowningDamage` | `false` | 编辑时少些意外 |
| `doInsomnia` / `doPatrolSpawning` / `doTraderSpawning` / `disableRaids` | `false`/`true` | 不刷巡逻队、商队、袭击 |
| `spawnRadius` | `2` | 出生点稳定 |

> 想看武装暴徒在城市里活动：`/difficulty easy` + `/gamerule doMobSpawning true`。
> 7 座城市的范围已经写进 `config/tarkovscav-common.toml` 的 `spawn.cityRegions`（见第 6 节）。

---

## 2. 坐标表

每一份城市占 **48 × 48 × 26**（模板 `data/tarkovscav/structures/city_small.nbt` 的尺寸，其中
第 0 层是整块 48×48 的粗泥地面）。表里的坐标是**最小角**，即该点延伸到
`(x+47, y+25, z+47)`。

| # | 朝向 / 镜像 | 最小角坐标 | 台子（64×64 平滑石） | 放置方式 |
| --- | --- | --- | --- | --- |
| 1 | 原版朝向 | `40 63 120` | x 32–95, z 112–175, 台面 y=63 | `/place template` |
| 2 | 顺时针 90° | `200 63 120` | x 192–255, z 112–175, 台面 y=63 | `/place template` |
| 3 | 旋转 180° | `360 63 120` | x 352–415, z 112–175, 台面 y=63 | `/place template` |
| 4 | 逆时针 90° | `520 63 120` | x 512–575, z 112–175, 台面 y=63 | `/place template` |
| 5 | 左右镜像 | `40 89 320` | x 32–95, z 312–375, 台面 y=89 | `/place template` |
| 6 | 前后镜像 + 顺时针 90° | `200 63 320` | x 192–255, z 312–375, 台面 y=63 | `/place template` |
| 7 | 原版朝向（世界生成路线） | `384 65 336` | x 367–446, z 319–398, 台面 y=63 | `/place structure` |
| N | 原版朝向（**世界自然生成**） | `-31 62 -47` | 无台子（下面就是天然地形） | 世界生成 |

> 第 1–7 份与第 N 份都是**你原有的**，本轮一个方块都没改；新变体见第 2.1 节。

- 第 1–6 份彼此间隔 160 格，沿 +X 一字排开（第一排在 z≈120，第二排在 z≈320）。
- 第 7 份是用 `/place structure tarkovscav:city_small` 放的，**带地形适应（beard_thin）**，所以它底下会被自动填土；它的落点是该区块里由世界生成算法选定的，实测最小角是 `384 65 336`（比台面高 2 格）。
- 第 N 份是这个世界自己生成的（`/locate structure tarkovscav:city_small` 能找到两处：`16 ~ 0` 和 `448 ~ 400`）。它埋在天然地形里 2–22 格，只作为「自然生成确实工作」的证据，不建议拿来改。

### 怎么快速过去

```
/tp @s 40 70 140        # 城市 1（出生点旁边）
/tp @s 200 70 140       # 城市 2
/tp @s 360 70 140       # 城市 3
/tp @s 520 70 140       # 城市 4
/tp @s 40 96 340        # 城市 5（台面更高）
/tp @s 200 70 340       # 城市 6
/tp @s 384 72 356       # 城市 7（世界生成路线）
```

---

## 3. 台子说明

- 每个城市坐在一块 **64×64 的平滑石台**上：台面一层 `smooth_stone`，下面 8 层 `stone`。
- 台子上方已经**清空到 y=150**，所以台子范围内不会再有天然山体穿过建筑。
- 台子**外面**仍是原始地形，所以台边可能有高低差（像切出来的施工平台），这是正常的。
- 清台时削掉了天然生成在那些位置上的零星城市一角（世界生成的城市很密，间隔约 384 格）。
  例如第 7 份台子东北侧还留着一段自然城市的路面（大约 `x 401–448, z 399–410`）。
- 另外还留有 5 块 48×48 的平滑石空地（原先旋转错位的那几份被清掉后留下的），可以当额外的操作场地：
  `153 63 120`、`313 63 73`、`520 63 73`、`40 89 273`、`153 63 273`。

---

## 4. 怎么改

1. 进游戏确认是**创造模式**（`/gamemode creative`）——存档已经打开作弊。
2. 先用 `/tp` 到想改的那一份，从台子边缘整体看一遍，挑一栋楼。
3. 大改之前先复制一份存档（把 `saves\塔科夫城市-可编辑` 整个目录拷走一份）。
4. 只改一栋楼的时候，**建议先用结构方块把那栋楼单独抠出来**存成一个小结构，再复制到空台子上改；
   直接在整座城市里改容易把街道、地基一起带坏。
5. 台面 y 就是该城市第 0 层（粗泥地面）的高度，记坐标时以这个平面为基准。

> 快捷取方块：`/give @s structure_block`（箱子里也放了一个）。
> 想清掉一片区域：`/fill <x1> <y1> <z1> <x2> <y2> <z2> minecraft:air`（一次最多 32768 格）。

---

## 5. 怎么把改完的建筑存出来（结构方块）

1. `/give @s structure_block`，放在建筑旁边。
2. 打开界面，模式选 **SAVE（保存）**。
3. **名字**填一个好记的英文名，例如 `mycity`（只能用字母数字和 `_-.+`）。
4. **相对坐标**：结构方块自身的坐标当作 `0 0 0`。把方块放在建筑的最小角（西北下角）外侧一格，
   然后结束坐标填 `47 25 47`（整座城市）或只包住你改的那栋楼的范围（边缘多留 1 格）。
5. 点 **DETECT（检测）** 可以让它自动算范围，再手工微调。
6. **重要**：一定要用 **结构空位（structure void）** 方块标记「这里应该是空的」，
   并且保存时包含空气；否则导出再放回来时门窗、天井这些空洞会被填成实心。
   - 需要「保留原样、不要覆盖」的位置放 `structure_void`；
   - 需要「一定清成空气」的位置放空气。
7. 想让箱子、盔甲架里的东西一起存下来，勾选 **包含实体（Include entities）**。
8. 点 **SAVE**。

导出文件会落在：

```
<存档目录>/generated/minecraft/structures/mycity.nbt
```

也就是：

```
C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\saves\塔科夫城市-可编辑\generated\minecraft\structures\mycity.nbt
```

---

## 6. 怎么把改完的建筑交回来

两条路，优先走第一条：

**A. 用模组指令（当前 jar 里已经有这两个子命令）**

```
/tarkovscav city import mycity          # 从 generated/minecraft/structures/mycity.nbt 读进来
/tarkovscav city place mycity           # 放一份核对（放在你脚下）
```

`/tarkovscav city` 的补全里现在有 `add / remove / list / import / reload / place / structures / test`。
（早先那一版 jar 里确实没有 `import`/`place`，本轮已经装上的构建里有；如果补全里看不到，
说明你客户端装的是旧包，走 B 路线。）

**B. 手工交回**

把 `mycity.nbt` 这个文件直接发给我们，我们把它装进模组里（放进
`data/tarkovscav/structures/`，再挂到 `worldgen/template_pool/city_small/start.json` 或新增一个 pool），
下个版本就能在游戏里用。

**顺带：城市范围也要跟着改（6.1 需要你手动做）**

### 6.1 关掉游戏后，把这一行粘进实例配置

文件：`C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\config\tarkovscav-common.toml`
（这是实例级 COMMON 配置，单人存档也读它。）

**步骤：先完全退出 Minecraft，再改这个文件，存盘后再启动游戏。**
原因：Forge 在游戏启动和退出时会重写 `tarkovscav-common.toml`；游戏开着改，退出时会被覆盖掉。

把该文件里 `[spawn]` 段的 `cityRegions = [...]` 整行替换成下面这一行：

```toml
cityRegions = ["1|minecraft:overworld|-383 34 -132|-254 163 -3", "city1|minecraft:overworld|24 59 104|103 98 183", "city2|minecraft:overworld|184 59 104|263 98 183", "city3|minecraft:overworld|344 59 104|423 98 183", "city4|minecraft:overworld|504 59 104|583 98 183", "city5|minecraft:overworld|24 85 304|103 124 383", "city6|minecraft:overworld|184 59 304|263 98 383", "city7|minecraft:overworld|368 61 320|447 100 399"]
```

- 第一条 `1|minecraft:overworld|-383 34 -132|-254 163 -3` 是**你原有的**，原样保留，不要删。
- 格式是 `[<名字>|]<维度>|<x1> <y1> <z1>|<x2> <y2> <z2>`（只写 3 段也行，名字会变成 `region`）。
- 这几行我们曾经直接写进过那份实时配置一次；如果你看到它已经在了，就不用再粘。
  但如果游戏已经启动/退出过一次而这几行不见了，按上面重粘即可（Forge 重写配置时可能按它自己的格式回写）。
- **不想改配置文件也行**：进游戏后用 `/tarkovscav city add <名字> [半径]` 逐块加，它自己会写回配置。
- 只想让暴徒认城市、又懒得分块，也可以只加一个大框把整排城市罩住：
  `/tarkovscav city add sandbox 600`（以你站的位置为中心，半径 600 的立方体，够罩住全部 7 座）。

---

## 7. 交回之后怎么验证

1. `/tarkovscav city import mycity`（若 jar 里有；没有就直接用结构方块 LOAD）。
2. `/tarkovscav city place mycity`（同样看 jar 里有没有），和导出前那一栋逐块对比。
3. 用 `/tarkovscav city list` 看范围有没有登记上。
4. **不管有没有 import，都能用的核对方法**：`/tarkovscav city test` ——
   站在城市里应该返回 `Inside a city area: ...`，站在外面应该返回 `NOT inside any city area`。
5. 结构方块界面直接 **LOAD（加载）** 一次，和原建筑叠着看。
6. 想逐块核对数量：把改动前后都用结构方块存成两个名字，再用 NBT 工具比对方块直方图
   （我们本轮就是用这种方式确认 7 份副本和模板完全一致的）。

---

## 8. 已知的坑 / 风险（不粉饰）

1. **服务端 mod 集 ≠ 你的客户端 mod 集。** 建这个存档时服务端只装了
   `armedmobs-0.1.0-all.jar` + `tacz-1.20.1-1.1.7-release.jar` + `geckolib-forge-1.20.1-4.8.2.jar`。
   城市本身只用原版方块，所以在你那一大堆模组的客户端里大概率没问题；
   但如果你客户端里的 `tarkovscav` jar 已经是另一个 worker 新重建的版本，方块 id 若有变化需要留意。
2. **`/place template` 的旋转锚点和直觉不一致。** 我们实测：旋转/镜像后的模板并不是把最小角放在
   你给的坐标上，而是按 `clockwise_90 → (-47,0)`、`180 → (-47,-47)`、`counterclockwise_90 → (0,-47)`、
   `left_right → (0,-47)`、`front_back+clockwise_90 → (-47,-47)` 偏移。本存档里的坐标表是**实测过的**
   （逐个解码 region 文件核对），不是推算的。
3. **自然生成的实例基本埋在土里。** 世界生成把第 0 层放在 `y=62`，而那一带地表在 `y=64–84`，
   所以自然那份只有局部露出来（里面还长了树和草）。要能编辑请用第 1–7 份。
4. **清台削掉了天然城市的一角。** 见第 3 节。这是世界生成城市太密的副作用，已经在上面写明。
5. **`/tarkovscav city import` 可能还不在你的 jar 里**（另一个 worker 本轮在加）。那就走第 6 节 B 路线。
6. **结构方块导出务必包含空气 / 用结构空位。** 这是「改完放回去变形」最常见的原因。
7. **沙盘世界会一直保持和平 + 不刷怪**，直到你自己改 gamerule。

---

## 9. 你的城市 → 结构件 → jigsaw 城区（2026-09-24，M1→M3）

这一节记录**怎么把你的手改城市拆成可复用的楼**，以及怎么在游戏里立刻看到结果。全部只读你的存档
（先把 `region\*.mca` 复制到 `tools/spike/work/citycopy` 再解析，**活存档一个字节都没写**）。

### 9.1 先量（M1）：哪块地方真有内容

- 工具：`tools/spike/citysave/PaletteScan.java`（扫全部 region 的 section palette，找"只有建造者才会放的方块"）、
  `CitySurvey.java`（逐楼包围盒 / 层数 / 墙·顶·楼板材质 / 逐层内装 / 方块实体 / 掩体团）、
  `StructureDump.java`（读一个结构 NBT 的调色板与方块实体）。
- 实测结论（`tools/spike/work/city_survey.json` + `city_survey_summary.txt`）：
  - 配置里 `cityRegions[0]`（`-383 34 -132` → `-254 163 -3`）**是空的**：81 个 chunk 全解出来，
    Y 从 -64 探到 320，只有自然地形、**0 栋建筑**；
  - 你真正改的那片是**第 2 节表里的「城市 1」**（`40 63 120` 那一份）：x 32–95 / z 112–175，
    铁证是那 4 台创造军械台（`84,65,132` / `87,64,133` / `46,65,156` / `42,65,165`，其中 `46,65,156`
    就是日志里 `[rack] unsupported item ...` 的那一台）；
  - 台面就是第 3 节那块 **y=63 的平滑石台**；街面在 **y=64**；楼是 16×16 足迹、**每层 4 格**
    （楼板 y64/68/72/76/80/84）；
  - 4 栋楼 + 1 圈外壳；**掩体在 y65–69**（36 团，最大两堆 326 / 242 方块的瓦砾）；
  - 需要保留 NBT 的方块：箱子×1、木桶×2（都带 Items）、牌子×3、刷怪笼×4、床×14、铃×1、军械台×4。

### 9.2 再切（M2）：一栋楼 = 一个结构件

```
java -cp tools/spike/citysave/out SaveBuildingExtract ^
  --world tools\spike\work\citycopy --out src\main\resources\data\tarkovscav\structures\buildings ^
  --name building_a1 --box 41 64 121 56 81 136 ^
  --base-y 63 --foundation 5 --strip-block-entities true --keep-fire false
```

规则（见 `tools/spike/work/buildings_extract_summary.txt`）：

| 规则 | 为什么 |
| --- | --- |
| `--base-y 63` 排除平台与街面 | 楼底那块平滑石台不是楼体，**导出件里不许出现** |
| `--foundation 5` **向下长 5 格同材质地基** | 台面顶 y63、街面 y64，这一带地表在 y56–70 之间起伏：5 格能把 ±2 格落差都埋住，平地上只露 0–1 格；4 格在落差 >2 时露底，6 格以上平地上会拖出一圈难看的裙边 |
| 裁掉纯空气边（内部空气保留） | 内部空气就是"把地形挖出楼内空间"的那部分 |
| `--strip-block-entities true` | 刷怪笼/箱子/木桶/牌子/我们的军械台**整块换成空气**（只清 NBT 会留下空笼子，更糟） |
| `--keep-fire false` | `doFireTick=true` 时生成出来的城区会自己烧起来 |
| 保留床/门/梯/火把/terracotta 墙裙 | 这些就是你要的"一楼内装"观感 |
| `--no-air true`（墙 / 瓦砾件） | 墙和瓦砾只应"加方块"，带空气的 66×27×80 会把整块地形挖空 |

产出 9 个件：4 栋楼、2 个街面件、2 块大瓦砾、1 圈围墙（`wall_ring` 暂时不入池）。

### 9.3 装连接点（M3）：jigsaw

```
java -cp tools/spike/citysave/out StructureConnectors --in piece.nbt --out piece.nbt ^
  --connector 8,4,0,north_up,tarkovscav:street_side,tarkovscav:street_side,tarkovscav:city_district/street,aligned,minecraft:air
```

- 格式：`x,y,z,朝向,name,target,pool,joint,final_state`；`final_state=ORIGINAL` 表示"保持原来那块方块"
  （铺在路面上的连接点就不会在生成后留一个洞）。
- 一个街面件有 **4 个街道口 + 8 个楼房位 + 2 个装饰位**；一栋楼只有 **1 个入口**；瓦砾有 1 个终端口。
- 池子：`worldgen/template_pool/city_district/{start,street,building,decor}.json`；结构：
  `worldgen/structure/city_district.json`（**`terrain_adaptation: "none"`**，`start_pool` 指向 start，
  `size` 6，`project_start_to_heightmap: WORLD_SURFACE_WG`，`max_distance_from_center` 128）；
  分布：`worldgen/structure_set/city_district.json`（spacing 48 / separation 20）；
  id 已加入 `tags/worldgen/structure/city.json`，所以 `cityStructureTags` 直接把它算作城区。
- **`cityStructureIds` 没有改**：标签才是文档里写的扩展点，改用户配置里的 id 列表属于静默变更。

### 9.4 现在就看（不用开新世界）

```
/tarkovscav city district                 # 你脚下 3x3 街面（48x48），随机种子
/tarkovscav city district 123456789       # 同一个布局再摆一次（种子每次都会打印）
/tarkovscav city district 123456789 5     # 5x5 街面（80x80）
```

它用**同一批 NBT 件**摆一个路网 + 沿路外侧挂楼 + 自由角落放瓦砾，每件都按自己那一列的地形高度落位
（再减掉它自己的地基）。**诚实说明**：件和池子与世界生成完全相同，但摆放算法是这个命令自己的网格，
不是 jigsaw 的随机生长——所以两者是"同一座城的不同布局"。自然生成的那份用
`/locate structure tarkovscav:city_district` 找。

### 9.5 已知的坑（这一节新增）

1. **世界生成只影响"还没生成的区块"**：已有存档的已生成区域**不会**凭空长出城区，这是引擎规则，
   不是本模组的限制；要立刻看到就用 9.4 的命令。
2. **jigsaw 的摆放我无法在无客户端环境里肉眼验证**：件的尺寸、连接点、池子、`terrain_adaptation` 都有
   闸门（`tools/selftest_city_district.js`）逐条断言，但"几栋楼挨在一起好不好看"必须你进游戏看。
3. **`wall_ring` 暂时没有入池**：它是一整圈 66×80 的外壳，塞进 jigsaw 会把一个城区撑成固定形状；
   先作为素材留着，等你看过街面+楼的效果再决定怎么用。
4. **楼是"密封盒子"**：连接点放在楼体外侧（生成后变空气），所以楼不会因此破洞；但楼真正的门在原位置，
   旋转后不一定朝向马路。要"门对着街"需要按门的位置给每栋楼单独定连接点（下一步可做）。

