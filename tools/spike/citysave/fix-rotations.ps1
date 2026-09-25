# Fix-up: /place template anchors a rotated/mirrored template at a DIFFERENT corner than the plain
# one. Measured from the wrongly placed copies (region-file scan):
#   clockwise_90                -> origin = pos + (-47,  0)
#   clockwise_180               -> origin = pos + (-47,  0, -47)
#   counterclockwise_90         -> origin = pos + (  0,  0, -47)
#   mirror left_right, none     -> origin = pos + (  0,  0, -47)
#   mirror front_back + cw90    -> origin = pos + (-47,  0, -47)
# So the placement position has to be moved by the negative of that offset for the lot to land on
# the prepared pad. This script (1) turns each wrongly placed copy's footprint into a plain stone
# apron and (2) re-places it so the lot lands exactly on the intended grid coordinate.
param(
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave',
    [int]$ClearTop = 150
)

$ErrorActionPreference = 'Stop'
$lines = New-Object System.Collections.Generic.List[string]
function Add([string]$s) { $lines.Add($s) }
function AddFill([int]$x1, [int]$z1, [int]$x2, [int]$z2, [int]$yFrom, [int]$yTo, [string]$block) {
    $layers = $yTo - $yFrom + 1
    $span = [Math]::Floor(32768 / (($x2 - $x1 + 1) * ($z2 - $z1 + 1)))
    if ($span -lt 1) { $span = 1 }
    $y = $yFrom
    while ($y -le $yTo) {
        $end = [Math]::Min($y + $span - 1, $yTo)
        $lines.Add("fill $x1 $y $z1 $x2 $end $z2 $block")
        $y = $end + 1
    }
}

# wrongly placed copies: actual origin measured from the saved region files
$misplaced = @(
    @{ n = 'city2 cw90';          x = 153; y = 63; z = 120 },
    @{ n = 'city3 180';           x = 313; y = 63; z = 73 },
    @{ n = 'city4 ccw90';         x = 520; y = 63; z = 73 },
    @{ n = 'city5 mirror_lr';     x = 40;  y = 89; z = 273 },
    @{ n = 'city6 mirror_fb_cw90';x = 153; y = 63; z = 273 }
)

Add '# ---- forceload everything the fix touches (vanilla caps one /forceload at 256 chunks, and a'
Add '#      55x23-chunk area is 805, so it has to be split or the whole command is rejected) ----'
foreach ($band in @(@(32, 159), @(160, 287), @(288, 415), @(416, 543), @(544, 591))) {
    Add "forceload add $($band[0]) 64 $($band[1]) 431"
}

foreach ($m in $misplaced) {
    $mx = $m.x; $my = $m.y; $mz = $m.z
    Add "# ---- erase misplaced copy $($m.n) at ($mx,$my,$mz) ----"
    AddFill $mx $mz ($mx + 47) ($mz + 47) ($my - 8) ($my - 1) 'minecraft:stone'
    AddFill $mx $mz ($mx + 47) ($mz + 47) $my $my 'minecraft:smooth_stone'
    AddFill $mx $mz ($mx + 47) ($mz + 47) ($my + 1) $ClearTop 'minecraft:air'
}

Add '# ---- re-place with the anchor corrected so each lot lands on its pad ----'
Add 'place template tarkovscav:city_small 247 63 120 clockwise_90'
Add 'place template tarkovscav:city_small 407 63 167 180'
Add 'place template tarkovscav:city_small 520 63 167 counterclockwise_90'
Add 'place template tarkovscav:city_small 40 89 367 none left_right'
Add 'place template tarkovscav:city_small 247 63 367 clockwise_90 front_back'
Add '# ---- leave no forced chunks behind in the delivered save ----'
Add 'forceload remove all'

$batch = Join-Path $WorkDir 'batches\18-fix-rotations.txt'
[System.IO.File]::WriteAllLines($batch, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "wrote $($lines.Count) commands to $batch"
