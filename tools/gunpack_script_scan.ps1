# Independent audit of the scripted-gun rule against the USER'S REAL instance.
# Answers: how many guns exist, how many declare a Lua script, in which namespaces, and which
# ones the shipped default policy (trustedScriptNamespaces=["tacz"]) keeps out of the mob pool.
# Read-only: it only expands gun packs into %TEMP%.
param(
    # The `tacz` folder of the instance to audit (the one holding the gun packs).
    [string]$TaczRoot = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\tacz',
    # 7-Zip, only needed for packs that are still .zip files.
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe'
)
$ErrorActionPreference = 'Continue'
$root = $TaczRoot
$sevenZip = $SevenZip
$tmp = Join-Path $env:TEMP 'gunscan_all'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

$results = New-Object System.Collections.Generic.List[object]

function Scan-Pack([string]$packName, [string]$packPath) {
    # LiteralPath: a pack folder called "[TACZ1.1.5-]Call of Duty ..." is a wildcard pattern to -Path, and the
    # first version of this script silently scanned 0 files of that pack because of it.
    $files = Get-ChildItem -LiteralPath $packPath -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -eq '.json' -and $_.FullName -match '\\data\\guns\\' }
    foreach ($f in $files) {
        $txt = ''
        # LiteralPath here too: the same bracket-name pack made Get-Content throw on every one of its files,
        # which the catch below turned into "this pack has no guns" - the second half of the same bug.
        try { $txt = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 } catch { continue }
        $m = [regex]::Match($txt, '"script"\s*:\s*"((?:[^"\\]|\\.)*)"')
        $script = if ($m.Success) { $m.Groups[1].Value } else { '' }
        $ns = if ($script) { ($script -split ':')[0].ToLowerInvariant() } else { '' }
        $results.Add([pscustomobject]@{
            Pack     = $packName
            Gun      = $f.BaseName
            Script   = $script
            Namespace = $ns
            BlockedByDefault = ($script -ne '' -and $ns -ne 'tacz')
        })
    }
}

# 1. gun packs that are plain folders
foreach ($dir in Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue) {
    if (-not (Test-Path -LiteralPath (Join-Path $dir.FullName 'gunpack.meta.json'))) { continue }
    Write-Host "pack(folder): $($dir.Name)"
    Scan-Pack $dir.Name $dir.FullName
}

# 2. gun packs that are zip archives
foreach ($z in Get-ChildItem -LiteralPath $root -Filter *.zip -ErrorAction SilentlyContinue) {
    $out = Join-Path $tmp ([IO.Path]::GetFileNameWithoutExtension($z.Name))
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
    [IO.Directory]::CreateDirectory($out) | Out-Null
    Write-Host "pack(zip): $($z.Name)"
    & $sevenZip x $z.FullName "-o$out" "-ir!*/data/guns/*.json" -y | Out-Null
    Scan-Pack $z.Name $out
}

Write-Host ''
Write-Host '=== per pack ==='
$results | Group-Object Pack | ForEach-Object {
    $scripted = ($_.Group | Where-Object { $_.Script -ne '' }).Count
    $blocked  = ($_.Group | Where-Object { $_.BlockedByDefault }).Count
    [pscustomobject]@{
        Pack = $_.Name; Guns = $_.Count; Scripted = $scripted; BlockedByDefault = $blocked
    }
} | Sort-Object -Property Guns -Descending | Format-Table -AutoSize

Write-Host '=== namespaces of declared scripts (whole instance) ==='
$results | Where-Object { $_.Script -ne '' } | Group-Object Namespace |
    Select-Object Name, Count | Sort-Object Count -Descending | Format-Table -AutoSize

Write-Host '=== guns blocked by the shipped default policy ==='
$blockedRows = $results | Where-Object { $_.BlockedByDefault } | Sort-Object Pack, Gun
if ($blockedRows) { $blockedRows | Format-Table Pack, Gun, Script -AutoSize }
else { Write-Host '(none)' }

$total = $results.Count
$scriptedTotal = ($results | Where-Object { $_.Script -ne '' }).Count
Write-Host ''
Write-Host ("TOTAL gun data files: $total   declaring a script: $scriptedTotal   blocked by default: " +
            (($results | Where-Object { $_.BlockedByDefault }).Count))
$results | Export-Csv (Join-Path $tmp 'gun_scripts.csv') -NoTypeInformation -Encoding UTF8
Write-Host "csv: $(Join-Path $tmp 'gun_scripts.csv')"
