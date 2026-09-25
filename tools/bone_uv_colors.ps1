# Companion step of tools/bone_uv_colors.js: samples the average colour of every face-UV rect that
# the Node half dumped, using System.Drawing as the PNG decoder.
#
#   powershell -File tools/bone_uv_colors.ps1 -Texture tex.png -Rects rects.json -Out colors.json
param(
    [Parameter(Mandatory = $true)][string]$Texture,
    [Parameter(Mandatory = $true)][string]$Rects,
    [Parameter(Mandatory = $true)][string]$Out
)

Add-Type -AssemblyName System.Drawing

$img = [System.Drawing.Bitmap]::FromFile($Texture)
$data = Get-Content -Raw $Rects | ConvertFrom-Json
$results = New-Object System.Collections.ArrayList

function Get-ColorName([double]$r, [double]$g, [double]$b) {
    if ($r -lt 55 -and $g -lt 55 -and $b -lt 70) { return 'dark' }
    if ($r -gt 150 -and $g -gt 150 -and $b -gt 150) { return 'white' }
    if ($r -gt 100 -and $g -lt 90 -and $b -lt 90) { return 'red' }
    if ($b -gt 100 -and $r -lt 95 -and $g -lt 110) { return 'blue' }
    if ($g -gt 100 -and $r -lt 100 -and $b -lt 100) { return 'green' }
    if ($r -gt 100 -and $g -gt 80 -and $b -lt 80) { return 'brown-orange' }
    if ($b -gt 100 -and $r -gt 95) { return 'violet' }
    return 'grey'
}

foreach ($rect in $data.rects) {
    if ($rect.missing) {
        [void]$results.Add([pscustomobject]@{ bone = $rect.bone; index = $rect.index; face = $rect.face; color = 'MISSING' })
        continue
    }
    # Sample the middle of the rect, capped at the texture size: a UV rect that runs off the atlas
    # wraps in game, so only the on-atlas part is meaningful here.
    $x0 = [Math]::Max(0, [Math]::Min($img.Width - 1, [int]$rect.u))
    $y0 = [Math]::Max(0, [Math]::Min($img.Height - 1, [int]$rect.v))
    $w = [Math]::Max(1, [Math]::Min($img.Width - $x0, [int][Math]::Ceiling($rect.w)))
    $h = [Math]::Max(1, [Math]::Min($img.Height - $y0, [int][Math]::Ceiling($rect.h)))
    $rs = 0.0; $gs = 0.0; $bs = 0.0; $n = 0
    for ($y = $y0; $y -lt ($y0 + $h); $y++) {
        for ($x = $x0; $x -lt ($x0 + $w); $x++) {
            $c = $img.GetPixel($x, $y)
            $rs += $c.R; $gs += $c.G; $bs += $c.B; $n++
        }
    }
    $r = $rs / $n; $g = $gs / $n; $b = $bs / $n
    [void]$results.Add([pscustomobject]@{
            bone  = $rect.bone; index = $rect.index; face = $rect.face
            uv    = "$([int]$rect.u),$([int]$rect.v) $([int]$rect.w)x$([int]$rect.h)"
            rgb   = "$([int]$r),$([int]$g),$([int]$b)"
            color = (Get-ColorName $r $g $b)
        })
}

$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $Out
$img.Dispose()
Write-Host "sampled $($results.Count) face rects -> $Out"
