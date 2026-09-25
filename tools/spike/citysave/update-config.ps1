# Appends the seven showroom city boxes to spawn.cityRegions in the user's tarkovscav-common.toml.
#
# Safety rules honoured here:
#   - ONLY the spawn.cityRegions line is touched; every other key is left byte-identical.
#   - the user's existing entry is preserved verbatim, and is asserted to be present exactly once
#     before anything is written;
#   - the original file is copied to backup/ first;
#   - the file is written back as UTF-8 without a BOM, with the original line endings (CRLF).
param(
    [string]$Config = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\config\tarkovscav-common.toml',
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave'
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$existing = 'cityRegions = ["1|minecraft:overworld|-383 34 -132|-254 163 -3"]'
$replacement = 'cityRegions = ["1|minecraft:overworld|-383 34 -132|-254 163 -3"' + `
    ', "city1|minecraft:overworld|24 59 104|103 98 183"' + `
    ', "city2|minecraft:overworld|184 59 104|263 98 183"' + `
    ', "city3|minecraft:overworld|344 59 104|423 98 183"' + `
    ', "city4|minecraft:overworld|504 59 104|583 98 183"' + `
    ', "city5|minecraft:overworld|24 85 304|103 124 383"' + `
    ', "city6|minecraft:overworld|184 59 304|263 98 383"' + `
    ', "city7|minecraft:overworld|368 61 320|447 100 399"]'

$text = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
$count = ([regex]::Matches($text, [regex]::Escape($existing))).Count
Write-Output "occurrences of the untouched user entry: $count"
if ($count -ne 1) { throw "expected exactly 1 occurrence of the user's cityRegions entry, found $count - aborting" }

$backupDir = Join-Path $WorkDir 'backup'
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$backup = Join-Path $backupDir 'tarkovscav-common.toml.orig'
Copy-Item -LiteralPath $Config -Destination $backup -Force
Write-Output "backup written: $backup"

$updated = $text.Replace($existing, $replacement)
[System.IO.File]::WriteAllText($Config, $updated, $utf8)

$after = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
Write-Output "--- BEFORE ---"
Write-Output $existing
Write-Output "--- AFTER ---"
foreach ($line in ($after -split "`r?`n")) {
    if ($line -match 'cityRegions') { Write-Output $line.Trim() }
}
Write-Output ("total bytes now: " + (Get-Item $Config).Length)
