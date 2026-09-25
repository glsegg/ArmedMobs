# Phase A: forceload one chunk patch per showroom site and probe the world surface with markers,
# so each city pad can be built at a known, flat height instead of guessing.
param(
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave'
)

$ErrorActionPreference = 'Stop'

# Showroom sites: the city template's min corner (the lot is 48x48, the pad is 64x64 centred on it).
$sites = @(
    @{ name = 'S1'; x = 40;  z = 120 },
    @{ name = 'S2'; x = 200; z = 120 },
    @{ name = 'S3'; x = 360; z = 120 },
    @{ name = 'S4'; x = 520; z = 120 },
    @{ name = 'S5'; x = 40;  z = 320 },
    @{ name = 'S6'; x = 200; z = 320 },
    @{ name = 'S7'; x = 360; z = 320 }
)

$lines = New-Object System.Collections.Generic.List[string]
foreach ($s in $sites) {
    $ox = $s.x; $oz = $s.z
    $lines.Add("forceload add $($ox - 16) $($oz - 16) $($ox + 64) $($oz + 64)")
    $points = @(
        @(($ox + 24), ($oz + 24)),   # pad centre: this is the height the pad is built at
        @(($ox - 6),  ($oz - 6)),
        @(($ox + 54), ($oz - 6)),
        @(($ox - 6),  ($oz + 54)),
        @(($ox + 54), ($oz + 54))
    )
    foreach ($p in $points) {
        $px = $p[0]; $pz = $p[1]
        $lines.Add("execute positioned $px 320 $pz positioned over motion_blocking run summon minecraft:marker ~ ~ ~ {Tags:[""probe_$($s.name)""]}")
        $lines.Add("data get entity @e[tag=probe_$($s.name),limit=1] Pos")
        $lines.Add("kill @e[tag=probe_$($s.name)]")
    }
}

# Also probe the naturally generated city that worldgen already put at the spawn area.
$lines.Add('forceload add -64 -80 48 32')
foreach ($p in @(@(-20, -35), @(16, 0), @(-43, -59), @(28, 12))) {
    $lines.Add("execute positioned $($p[0]) 320 $($p[1]) positioned over motion_blocking run summon minecraft:marker ~ ~ ~ {Tags:[""probe_nat""]}")
    $lines.Add('data get entity @e[tag=probe_nat,limit=1] Pos')
    $lines.Add('kill @e[tag=probe_nat]')
}

$batch = Join-Path $WorkDir 'batches\02-probe.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $batch -Parent) | Out-Null
[System.IO.File]::WriteAllLines($batch, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "wrote $($lines.Count) commands to $batch"
