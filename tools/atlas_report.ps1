# Coarse colour/alpha map of an entity atlas, plus a per-bone count of faces that sample transparent
# texture pixels (those faces disappear under the alpha-test render types the entity models use).
#
#   powershell -File tools/atlas_report.ps1 -Texture <scav.png> -Rects <rects.json> [-Tile 8]
#
# Why: "from one angle the model shows another outfit's blocks" has three data-level causes - an
# inverted winding (needs back-face culling, which the entity render types do NOT use), z-fighting
# between coincident geometry, and *cut-out* faces that let the camera see the inside of the model.
# Only the last two show up in the atlas, and this script is what names them.
param(
    [Parameter(Mandatory = $true)][string]$Texture,
    [Parameter(Mandatory = $true)][string]$Rects,
    [int]$Tile = 8
)

Add-Type -AssemblyName System.Drawing

$img = [System.Drawing.Bitmap]::FromFile($Texture)
$data = Get-Content -Raw $Rects | ConvertFrom-Json
$rects = if ($data.rects) { $data.rects } else { $data }

function Get-Class([double]$r, [double]$g, [double]$b) {
    # Olive/camo: green dominant but dark - the classifier in bone_uv_colors.ps1 calls this 'grey',
    # which is exactly why a green screenshot could not be traced before.
    if ($g -gt $r + 6 -and $g -gt $b + 6) { return 'G' }
    if ($r -gt 200 -and $g -gt 200 -and $b -gt 200) { return 'W' }
    if ($r -lt 45 -and $g -lt 45 -and $b -lt 55) { return 'k' }
    if ($r -gt 120 -and $g -lt 95 -and $b -lt 95) { return 'R' }
    if ($b -gt 110 -and $r -lt 105) { return 'B' }
    if ($r -gt 120 -and $g -gt 95 -and $b -lt 95) { return 'y' }
    if ($r -gt 110 -and $g -gt 110 -and $b -gt 110) { return 'w' }
    return '.'
}

Write-Host "atlas $($img.Width)x$($img.Height), tile=$Tile px  legend: G=green/olive W=white w=pale-grey .=grey k=dark R=red B=blue y=tan"
Write-Host '     ' + (0..([int]($img.Width / $Tile) - 1) | ForEach-Object { if ($_ % 4 -eq 0) { [string]([int]($_ * $Tile / 100)) } else { ' ' } }) -join ''
for ($ty = 0; $ty -lt [int]($img.Height / $Tile); $ty++) {
    $line = ''
    for ($tx = 0; $tx -lt [int]($img.Width / $Tile); $tx++) {
        $rs = 0.0; $gs = 0.0; $bs = 0.0; $as = 0.0; $n = 0
        for ($y = $ty * $Tile; $y -lt ($ty * $Tile + $Tile); $y++) {
            for ($x = $tx * $Tile; $x -lt ($tx * $Tile + $Tile); $x++) {
                $c = $img.GetPixel($x, $y)
                $rs += $c.R; $gs += $c.G; $bs += $c.B; $as += $c.A; $n++
            }
        }
        if (($as / $n) -lt 8) { $line += ' ' } else { $line += (Get-Class ($rs / $n) ($gs / $n) ($bs / $n)) }
    }
    Write-Host ("y={0,3} {1}" -f ($ty * $Tile), $line)
}

# ---------------------------------------------------------------- transparent faces per bone
$byBone = @{}
foreach ($rect in $rects) {
    if ($rect.missing) { continue }
    $x0 = [Math]::Max(0, [Math]::Min($img.Width - 1, [int]$rect.u))
    $y0 = [Math]::Max(0, [Math]::Min($img.Height - 1, [int]$rect.v))
    $w = [Math]::Max(1, [Math]::Min($img.Width - $x0, [int][Math]::Ceiling($rect.w)))
    $h = [Math]::Max(1, [Math]::Min($img.Height - $y0, [int][Math]::Ceiling($rect.h)))
    $clear = 0; $n = 0
    for ($y = $y0; $y -lt ($y0 + $h); $y++) {
        for ($x = $x0; $x -lt ($x0 + $w); $x++) {
            $c = $img.GetPixel($x, $y)
            if ($c.A -lt 26) { $clear++ }
            $n++
        }
    }
    if (-not $byBone.ContainsKey($rect.bone)) { $byBone[$rect.bone] = @{ total = 0; clear = 0; sample = @() } }
    $byBone[$rect.bone].total++
    if ($clear -eq $n) {
        $byBone[$rect.bone].clear++
        if ($byBone[$rect.bone].sample.Count -lt 3) {
            $byBone[$rect.bone].sample += "$($rect.face)@$([int]$rect.u),$([int]$rect.v) $([int]$rect.w)x$([int]$rect.h)"
        }
    }
}
Write-Host ''
Write-Host 'faces that sample FULLY transparent atlas pixels (alpha-tested away = a hole in the model):'
foreach ($bone in ($byBone.Keys | Sort-Object)) {
    $e = $byBone[$bone]
    if ($e.clear -gt 0) {
        Write-Host ("  {0,-16} {1,3}/{2,3} faces  e.g. {3}" -f $bone, $e.clear, $e.total, ($e.sample -join ' ; '))
    }
}
$img.Dispose()
