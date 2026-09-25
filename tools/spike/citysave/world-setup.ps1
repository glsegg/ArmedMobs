# Emits the "make this world good for hand-editing" batch: gamerules, fixed daylight, cleared
# weather, the spawn point, and the in-world documentation (signs + a chest holding a written book
# with the coordinate table and the export/hand-back steps).
param(
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave'
)

$ErrorActionPreference = 'Stop'
$lines = New-Object System.Collections.Generic.List[string]
function Add([string]$s) { $lines.Add($s) }

Add '# ---- world rules for editing ----'
Add 'difficulty peaceful'
Add 'gamerule doMobSpawning false'
Add 'gamerule doDaylightCycle false'
Add 'gamerule doWeatherCycle false'
Add 'gamerule keepInventory true'
Add 'gamerule mobGriefing false'
Add 'gamerule randomTickSpeed 0'
Add 'gamerule doFireTick false'
Add 'gamerule doInsomnia false'
Add 'gamerule doPatrolSpawning false'
Add 'gamerule doTraderSpawning false'
Add 'gamerule disableRaids true'
Add 'gamerule fallDamage false'
Add 'gamerule fireDamage false'
Add 'gamerule drowningDamage false'
Add 'gamerule announceAdvancements false'
Add 'gamerule commandBlockOutput false'
Add 'gamerule spawnRadius 2'
Add 'gamerule sendCommandFeedback true'
Add 'time set day'
Add 'weather clear'
Add 'setworldspawn 36 64 144 -90'

Add '# ---- spawn hub: three signs with the coordinate table ----'
Add 'setblock 34 64 139 minecraft:oak_sign[rotation=0]'
Add 'data merge block 34 64 139 {front_text:{messages:[''{"text":"=== 塔科夫城市 编辑沙盘 ==="}'',''{"text":"7 份 tarkovscav:city_small"}'',''{"text":"沿 +X 一字排开 间隔 160"}'',''{"text":"详细坐标见旁边的箱子"}'']}}'
Add 'setblock 36 64 139 minecraft:oak_sign[rotation=0]'
Add 'data merge block 36 64 139 {front_text:{messages:[''{"text":"城市1 原版 x40 y63 z120"}'',''{"text":"城市2 顺90 x200 y63 z120"}'',''{"text":"城市3 转180 x360 y63 z120"}'',''{"text":"城市4 逆90 x520 y63 z120"}'']}}'
Add 'setblock 38 64 139 minecraft:oak_sign[rotation=0]'
Add 'data merge block 38 64 139 {front_text:{messages:[''{"text":"城市5 左右镜像 x40 y89 z320"}'',''{"text":"城市6 前后+90 x200 y63 z320"}'',''{"text":"城市7 世界生成 x384 y65 z336"}'',''{"text":"自然生成 x-31 y62 z-47"}'']}}'

Add '# ---- spawn hub: chest with the full guide as a written book ----'
Add 'setblock 34 64 141 minecraft:chest[facing=south]'
Add 'data merge block 34 64 141 {Items:[{Slot:0b,id:"minecraft:written_book",Count:1b,tag:{title:"城市编辑与交回指南",author:"TarkovScav",pages:[]}},{Slot:1b,id:"minecraft:structure_block",Count:1b}]}'

$pages = @(
    '{"text":"塔科夫城市 - 可编辑沙盘\n\n世界：1.20.1 Forge，普通(overworld)地形，种子 20240913\n\n8 份 tarkovscav:city_small：\n7 份是我放的(6 份放在平整石台上，1 份走世界生成)，1 份是世界自己生成的。\n\n世界规则：和平 / 锁定白天 / 无刷怪 / keepInventory / 不掉落保护。"}',
    '{"text":"坐标表 (1/2)\n每一份占地 48x48x26，坐标 = 最小角(x, y, z)，即从该点延伸到 (x+47, y+25, z+47)。\n\n城市1 原版朝向\n  x40 y63 z120\n城市2 顺时针90度\n  x200 y63 z120\n城市3 旋转180度\n  x360 y63 z120\n城市4 逆时针90度\n  x520 y63 z120"}',
    '{"text":"坐标表 (2/2)\n\n城市5 左右镜像\n  x40 y89 z320\n城市6 前后镜像+90度\n  x200 y63 z320\n城市7 世界生成放置(带地形适应)\n  x384 y65 z336\n\n自然生成的那一份\n  x-31 y62 z-47\n(它埋在地形里 2~22 格，只作证据)"}',
    '{"text":"怎么过去\n/tp @s 40 70 140\n把坐标换成表里的即可。\n\n台子：每个城市坐在一块 64x64 的平滑石台上，台子上方已经清空到 y=150，所以不会挖到山。台子外面还是原始地形，台边可能有高低差。\n\n别忘了备份存档再大改。"}',
    '{"text":"怎么改\n1) /gamemode creative 创造模式，直接拆改。\n2) 只改一栋楼的话，先用结构方块把它单独存出来，再复制到别处改，不要动整座城市。\n3) 城市7 是 /place structure 放的，带地形适应(底下会被填土)，其它 6 份是 /place template 放的，落在石台上。"}',
    '{"text":"怎么导出(结构方块)\n1) /give @s structure_block\n2) 放下结构方块，模式选 SAVE(保存)。\n3) 名字填：mycity\n4) 相对坐标：从城市最小角(0,0,0)到(47,25,47)。\n5) 一定要把 included 里的空气/结构空位打开(否则门窗空洞会被填成实心)。\n6) 点保存。\n\n文件落在：<存档>/generated/minecraft/structures/mycity.nbt"}',
    '{"text":"怎么交回来\n两条路：\n\nA) 如果这个 jar 里有 /tarkovscav city import <名字>：\n   /tarkovscav city import mycity\n   然后 /tarkovscav city place mycity 核对。\n\nB) 如果没有 import 指令：把 mycity.nbt 文件发给我们，我们装进模组里。\n\n导出后核对：/tarkovscav city place <名字>"}',
    '{"text":"关于武装暴徒\n这个存档是和平 + 关刷怪的，编辑时不会被打。\n\n想看武装暴徒在城市里活动：\n/difficulty easy\n/gamerule doMobSpawning true\n\n7 座城市的范围已经写进 config/tarkovscav-common.toml 的 spawn.cityRegions，所以打开刷怪后它们会认这些地方。"}',
    '{"text":"结构方块小贴士\n- 保存范围要包住整栋楼，边缘多留 1 格。\n- 一定要勾“包含空气/结构空位”，空洞才不会被填实。\n- 想让某个方块留空，在结构方块界面用“结构空位”方块标记。\n- 想连箱子里的东西一起存，勾“包含实体”。\n- .nbt 文件可以直接发给我们。"}'
)

foreach ($p in $pages) {
    Add ("data modify block 34 64 141 Items[{Slot:0b}].tag.pages append value '" + $p + "'")
}

$batch = Join-Path $WorkDir 'batches\13-world.txt'
[System.IO.File]::WriteAllLines($batch, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "wrote $($lines.Count) commands to $batch"
