# Independent audit of `guns.respectDeclaredFireModes` against the USER'S REAL instance.
#
# Answers, from the gun packs themselves (never from a hard-coded list):
#   * how many gun data files declare a fire mode, and how many declare which modes;
#   * how many guns the mobs are today forced into FULL-AUTO even though their own data file
#     never asked for it (that is the number the README quotes);
#   * what the two guns named in the crash chain actually declare
#     (hamster:win1894 vs smle_mk3 / win1873 - rapid_bolt_time and fire_mode).
#
# Read-only: it only expands gun packs into %TEMP%.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\firemode_scan.ps1
param(
    # The `tacz` folder of the instance to audit (the one holding the gun packs).
    [string]$TaczRoot = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\tacz',
    # 7-Zip, only needed for packs that are still .zip files.
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe',
    # Where the raw text of every gun data file goes, so the numbers can be re-checked by hand.
    [string]$Work = (Join-Path $env:TEMP 'firemodescan')
)
$ErrorActionPreference = 'Continue'
Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $Work -Force | Out-Null

$rows = New-Object System.Collections.Generic.List[object]

function Add-Gun([string]$pack, [string]$file) {
    $txt = ''
    try { $txt = [System.IO.File]::ReadAllText($file) } catch { return }
    # The declared fire modes: the "fire_mode" array of the gun's own data file.
    $modes = @()
    $m = [regex]::Match($txt, '"fire_mode"\s*:\s*\[([^\]]*)\]')
    if ($m.Success) {
        # @() matters: a one-element result would otherwise be a String, and String[0] is a Char.
        $modes = @([regex]::Matches($m.Groups[1].Value, '"([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value })
    }
    # What the resolver picks: AUTO when declared, else the first declared mode, else AUTO.
    $resolved = 'AUTO'
    if ($modes.Count -gt 0 -and -not ($modes -contains 'auto')) { $resolved = $modes[0].ToUpperInvariant() }
    $rows.Add([pscustomobject]@{
        Pack        = $pack
        Gun         = [IO.Path]::GetFileNameWithoutExtension($file)
        Modes       = ($modes -join '+')
        DeclaresAuto = ($modes -contains 'auto')
        DeclaresNone = ($modes.Count -eq 0)
        Resolved    = $resolved
        RapidBolt   = $txt.Contains('rapid_bolt_time')
    })
}

foreach ($dir in Get-ChildItem $TaczRoot -Directory -ErrorAction SilentlyContinue) {
    if (-not (Test-Path (Join-Path $dir.FullName 'gunpack.meta.json'))) { continue }
    Write-Host "pack(folder): $($dir.Name)"
    Get-ChildItem $dir.FullName -Recurse -File -Filter *.json -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\data\\guns\\' } |
        ForEach-Object { Add-Gun $dir.Name $_.FullName }
}

foreach ($z in Get-ChildItem $TaczRoot -Filter *.zip -ErrorAction SilentlyContinue) {
    $out = Join-Path $Work ([IO.Path]::GetFileNameWithoutExtension($z.Name))
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    Write-Host "pack(zip): $($z.Name)"
    & $SevenZip x $z.FullName "-o$out" "-ir!*/data/guns/*.json" -y | Out-Null
    Get-ChildItem $out -Recurse -File -Filter *.json -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\data\\guns\\' } |
        ForEach-Object { Add-Gun $z.Name $_.FullName }
}

Write-Host ''
Write-Host '=== per pack: declared fire modes ==='
$rows | Group-Object Pack | ForEach-Object {
    [pscustomobject]@{
        Pack          = $_.Name
        Guns          = $_.Count
        DeclaresAuto  = ($_.Group | Where-Object { $_.DeclaresAuto }).Count
        NoDeclaration = ($_.Group | Where-Object { $_.DeclaresNone }).Count
    }
} | Sort-Object Guns -Descending | Format-Table -AutoSize

Write-Host '=== every declared fire-mode set (whole instance) ==='
$rows | Group-Object Modes | Select-Object Name, Count | Sort-Object Count -Descending |
    Format-Table -AutoSize

Write-Host '=== what the resolver would pick (respectDeclaredFireModes = true) ==='
$rows | Group-Object Resolved | Select-Object Name, Count | Sort-Object Count -Descending |
    Format-Table -AutoSize

$total = $rows.Count
$auto = ($rows | Where-Object { $_.DeclaresAuto }).Count
$none = ($rows | Where-Object { $_.DeclaresNone }).Count
$forced = $total - $auto
Write-Host ''
Write-Host "TOTAL gun data files: $total"
Write-Host "declaring 'auto' (today's forced mode == their own choice): $auto"
Write-Host "NOT declaring 'auto' (today forced into FULL-AUTO against their data file): $forced"
Write-Host "declaring no fire mode at all (resolver keeps AUTO, unchanged): $none"

Write-Host ''
Write-Host '=== the crash chain, gun by gun ==='
foreach ($needle in @('win1894', 'smle_mk3', 'win1873')) {
    $rows | Where-Object { $_.Gun -like "$needle*" } | ForEach-Object {
        Write-Host ("{0,-10} pack={1,-22} fire_mode=[{2,-16}] resolved={3,-6} rapid_bolt_time={4}" -f `
            $_.Gun, $_.Pack, $_.Modes, $_.Resolved, $_.RapidBolt)
    }
}
Write-Host ''
Write-Host "csv: $(Join-Path $Work 'fire_modes.csv')"
$rows | Export-Csv (Join-Path $Work 'fire_modes.csv') -NoTypeInformation -Encoding UTF8
