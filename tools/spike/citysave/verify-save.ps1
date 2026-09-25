# Verifies the delivered save by decoding its region files: every /place template copy must contain
# the city_small template's exact block histogram (rotation and mirroring do not change counts).
#
# The save folder name is reconstructed from code points here on purpose: passing Chinese through a
# Windows command line mangles it via the ANSI code page.
param(
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave',
    [string]$Saves = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\saves'
)

$ErrorActionPreference = 'Stop'
$java = 'C:\Program Files\Java\jdk-21.0.12\bin\java.exe'
$out = Join-Path $WorkDir 'out'
$name = ([char]0x5854).ToString() + [char]0x79D1 + [char]0x592B + [char]0x57CE + [char]0x5E02 + '-' + [char]0x53EF + [char]0x7F16 + [char]0x8F91
$world = Join-Path $Saves $name
Write-Output "world = $world"
Write-Output "exists = $(Test-Path -LiteralPath $world)"
Write-Output ''

# exact histogram of data/tarkovscav/structures/city_small.nbt (measured with NbtHeader)
$expected = @{
    'minecraft:air'                = 49381
    'minecraft:gray_concrete'      = 784
    'minecraft:light_gray_concrete'= 1262
    'minecraft:smooth_stone'       = 420
    'minecraft:coarse_dirt'        = 1024
    'minecraft:brown_concrete'     = 627
    'minecraft:terracotta'         = 419
    'minecraft:stripped_dark_oak_log' = 192
    'minecraft:polished_andesite'  = 4053
    'minecraft:deepslate_tiles'    = 1103
    'minecraft:glass_pane'         = 452
    'minecraft:ladder'             = 49
    'minecraft:cobblestone_wall'   = 13
    'minecraft:iron_bars'          = 8
    'minecraft:cobblestone'        = 83
    'minecraft:barrel'             = 2
    'minecraft:black_concrete'     = 24
    'minecraft:tinted_glass'       = 8
}

$cities = @(
    @{ n = 'city1 none';              x = 40;  y = 63; z = 120 },
    @{ n = 'city2 clockwise_90';      x = 200; y = 63; z = 120 },
    @{ n = 'city3 180';               x = 360; y = 63; z = 120 },
    @{ n = 'city4 counterclockwise';  x = 520; y = 63; z = 120 },
    @{ n = 'city5 mirror left_right'; x = 40;  y = 89; z = 320 },
    @{ n = 'city6 mirror+90';         x = 200; y = 63; z = 320 },
    @{ n = 'city7 /place structure';  x = 384; y = 65; z = 336 }
)

$allPass = $true
foreach ($c in $cities) {
    $x2 = $c.x + 47; $y2 = $c.y + 25; $z2 = $c.z + 47
    $raw = & $java -cp $out RegionStat count $world $c.x $c.y $c.z $x2 $y2 $z2 2>&1
    $actual = @{}
    $total = 0
    foreach ($line in $raw) {
        if ($line -match '^\s+(minecraft:[a-z_]+) x(\d+)$') {
            $actual[$Matches[1]] = [int]$Matches[2]
            $total += [int]$Matches[2]
        }
    }
    $missing = @(); $extra = @()
    foreach ($k in $expected.Keys) {
        if (-not $actual.ContainsKey($k)) { $missing += $k }
        elseif ($actual[$k] -ne $expected[$k]) { $missing += "$k($($actual[$k])!=$($expected[$k]))" }
    }
    foreach ($k in $actual.Keys) { if (-not $expected.ContainsKey($k)) { $extra += "$k=$($actual[$k])" } }
    $ok = ($missing.Count -eq 0 -and $extra.Count -eq 0 -and $total -eq 59904)
    if (-not $ok) { $allPass = $false }
    $verdict = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Output ("{0,-32} x={1,-4} y={2,-3} z={3,-4} total={4} {5}" -f $c.n, $c.x, $c.y, $c.z, $total, $verdict)
    if ($missing.Count) { Write-Output ("    differing/absent: " + ($missing -join ', ')) }
    if ($extra.Count)   { Write-Output ("    unexpected extra: " + ($extra -join ', ')) }
}

Write-Output ''
Write-Output "=== naturally generated city_small (origin -31,62,-47) ==="
$raw = & $java -cp $out RegionStat count $world -31 62 -47 16 87 0 2>&1
$raw | Where-Object { $_ -match 'distinctBlocks|^\s+minecraft:' } | ForEach-Object { Write-Output $_ }

Write-Output ''
Write-Output "ALL SEVEN COPIES EXACT MATCH: $allPass"
