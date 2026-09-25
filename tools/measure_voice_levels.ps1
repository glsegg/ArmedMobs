# Measures EVERY shipped clip and writes the loudness table the mod ships and the gate reads.
#
#   powershell -ExecutionPolicy Bypass -File tools/measure_voice_levels.ps1
#
# Outputs:
#   src/main/resources/assets/tarkovscav/voice_levels.json   (shipped: one row per clip + the policy)
#   tools/spike/work/voice_levels.csv                        (the same table, for a report/paste)
#
# Why this exists: "the new voices are quieter than the scav's" is a measurable claim, so the numbers have
# to come from the actual files, not from what the cutter intended. @ref mean_volume / max_volume from
# ffmpeg's volumedetect, per file, plus the family (derived from the name) so the distribution can be
# compared family by family. gainDb/limitDb are merged in from the cutter's reports when they exist, so a
# row also says HOW MUCH limiting that clip needed (README 5l).
param(
    [string]$SoundsDir = 'src\main\resources\assets\tarkovscav\sounds',
    [string]$Json = 'src\main\resources\assets\tarkovscav\voice_levels.json',
    [string]$Csv = 'tools\spike\work\voice_levels.csv',
    [double]$TargetMeanDb = -16.8,
    [double]$PeakCeilingDb = -0.5,
    [double]$MeanWindowDb = 1.5
)

$ErrorActionPreference = 'Stop'
$ffmpeg = if (Test-Path 'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe') {
    'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe'
} else { 'ffmpeg' }

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sounds = Join-Path $projectDir $SoundsDir
$jsonPath = Join-Path $projectDir $Json
$csvPath = Join-Path $projectDir $Csv
$log = Join-Path $projectDir 'tools\spike\work\voice\ffmpeg-measure-last.log'

function Measure-Clip {
    param([string]$File)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $ffmpeg -hide_banner -i $File -af volumedetect -f null NUL 2> $log } finally {
        $ErrorActionPreference = $previous
    }
    $text = Get-Content $log
    $mean = ($text | Select-String 'mean_volume:\s*(-?[0-9.]+) dB').Matches
    $max = ($text | Select-String 'max_volume:\s*(-?[0-9.]+) dB').Matches
    $dur = ($text | Select-String 'Duration:\s*(\d+):(\d+):([0-9.]+)').Matches
    if ($mean.Count -eq 0 -or $max.Count -eq 0 -or $dur.Count -eq 0) { return $null }
    $seconds = [double]$dur[0].Groups[1].Value * 3600 + [double]$dur[0].Groups[2].Value * 60 +
        [double]$dur[0].Groups[3].Value
    return @{ Mean = [double]$mean[0].Groups[1].Value; Peak = [double]$max[0].Groups[1].Value;
        Seconds = $seconds }
}

# The cutter's per-clip gain/limiter numbers, by clip name (they only exist for the family+effect clips).
$gainByName = @{}
$limitByName = @{}
foreach ($report in @('tools\spike\work\voice_clips_report.json', 'tools\spike\work\effect_clips_report.json')) {
    $path = Join-Path $projectDir $report
    if (-not (Test-Path $path)) { continue }
    $parsed = [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false))) |
        ConvertFrom-Json
    foreach ($clip in @($parsed.generated)) {
        $gainByName[$clip.name] = $clip.gainDb
        $limitByName[$clip.name] = $clip.limitDb
    }
}

$files = Get-ChildItem -Path $sounds -Recurse -Filter '*.ogg' | Sort-Object FullName
$rows = New-Object System.Collections.Generic.List[object]
foreach ($file in $files) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
    $rel = $file.FullName.Substring($sounds.Length + 1).Replace('\', '/')
    $family = if ($name -like 'usec_*') { 'usec' }
        elseif ($name -like 'bear_*') { 'bear' }
        elseif ($name -like 'elite_*') { 'elite' }
        elseif ($rel -like 'effect/*') { 'effect' }
        else { 'shared' }
    $measured = Measure-Clip -File $file.FullName
    if ($null -eq $measured) { throw "could not measure $rel" }
    $rows.Add([pscustomobject]@{
        name = $name
        file = $rel
        family = $family
        group = ($rel -replace '/.*$', '')
        meanDb = [math]::Round($measured.Mean, 2)
        peakDb = [math]::Round($measured.Peak, 2)
        seconds = [math]::Round($measured.Seconds, 2)
        bytes = $file.Length
        gainDb = if ($gainByName.ContainsKey($name)) { $gainByName[$name] } else { $null }
        limitDb = if ($limitByName.ContainsKey($name)) { $limitByName[$name] } else { $null }
    })
    Write-Host ("  {0,-24} {1,7:N1} dB mean  {2,6:N1} dB peak  {3}" -f $name, $measured.Mean,
        $measured.Peak, $family) -ForegroundColor DarkGray
}

Write-Host ''
foreach ($family in ($rows | Group-Object family | Sort-Object Name)) {
    $means = @($family.Group | ForEach-Object { $_.meanDb } | Sort-Object)
    $median = $means[[int][math]::Floor($means.Count / 2)]
    $avg = ($means | Measure-Object -Average).Average
    $sd = [math]::Sqrt((($means | ForEach-Object { ($_ - $avg) * ($_ - $avg) }) | Measure-Object -Sum).Sum /
        $means.Count)
    $peak = ($family.Group | Measure-Object peakDb -Maximum).Maximum
    Write-Host ("{0,-7} n={1,-3} mean median {2,6:N2} dB  avg {3,6:N2}  sd {4,5:N2}  peak<= {5,6:N2}  min {6,6:N2}  max {7,6:N2}" -f
        $family.Name, $means.Count, $median, $avg, $sd, $peak, $means[0], $means[$means.Count - 1]) `
        -ForegroundColor Cyan
}

# An ordered hashtable and not [pscustomobject]@{...}: PS 5.1's pscustomobject literal rejects a generic
# List inside the literal ("Argument types do not match"), and ConvertTo-Json handles the hashtable anyway.
$table = [ordered]@{
    generatedBy = 'tools/measure_voice_levels.ps1'
    targetMeanDb = $TargetMeanDb
    peakCeilingDb = $PeakCeilingDb
    meanWindowDb = $MeanWindowDb
    note = 'mean/peak are ffmpeg volumedetect values of the SHIPPED ogg (sample peak, not an oversampled true peak); gainDb/limitDb come from the cutter reports'
    clips = $rows.ToArray()
}
[System.IO.File]::WriteAllText($jsonPath, ($table | ConvertTo-Json -Depth 6),
    (New-Object System.Text.UTF8Encoding($false)))
$csvLines = New-Object System.Collections.Generic.List[string]
$csvLines.Add('name,family,meanDb,peakDb,gainDb,limitDb,seconds,bytes')
foreach ($row in $rows) {
    $csvLines.Add(('{0},{1},{2},{3},{4},{5},{6},{7}' -f $row.name, $row.family, $row.meanDb, $row.peakDb,
        $row.gainDb, $row.limitDb, $row.seconds, $row.bytes))
}
[System.IO.File]::WriteAllText($csvPath, (($csvLines -join "`n") + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))
Write-Host ''
Write-Host ("wrote {0} and {1} ({2} clip(s))" -f $Json, $Csv, $rows.Count) -ForegroundColor Green
