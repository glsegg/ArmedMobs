# Cuts ONE effect clip (not a voice pool) out of a source recording with the same recipe the voices use, and
# writes tools/spike/work/effect_clips_report.json for tools/voice_emit.js to fold into sounds.json, the
# subtitle keys, the manifest and the README.
#
#   powershell -ExecutionPolicy Bypass -File tools/make_impact_clip.ps1 `
#       -Source tools/spike/work/voice_src/bili/BV1j1cZeHEAt.m4s -StartSeconds 0 -EndSeconds 2 `
#       -Name grenade_land -Event grenade_land -Sound effect/grenade_land `
#       -Note 'BV1j1cZeHEAt 0:00-0:02'
#
# Recipe (identical to tools/make_family_voice_clips.ps1, README 5l): mono 44100 Hz, silence trimmed at both
# ends, loudnorm=I=-16.5:TP=-1.0:LRA=11, then one gain that lands the mean near -16.8 dB without letting the
# SHIPPED (Vorbis-decoded) peak exceed -1.0 dB.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][double]$StartSeconds,
    [Parameter(Mandatory = $true)][double]$EndSeconds,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Event,
    [Parameter(Mandatory = $true)][string]$Sound,
    [string]$Note = '',
    [string]$SubtitleZh = '',
    [string]$SubtitleEn = '',
    [double]$TargetMeanDb = -16.8,
    [double]$PeakCeilingDb = -0.5,
    [double]$VorbisOvershootDb = 0.4,
    [double]$SilenceThresholdDb = -45.0,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ffmpeg = if (Test-Path 'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe') {
    'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe'
} else { 'ffmpeg' }

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourcePath = if ([System.IO.Path]::IsPathRooted($Source)) { $Source } else { Join-Path $projectDir $Source }
$workDir = Join-Path $projectDir 'tools\spike\work\voice'
$rawDir = Join-Path $projectDir 'assets_source\voice\raw'
$soundsDir = Join-Path $projectDir 'src\main\resources\assets\tarkovscav\sounds'
$reportPath = Join-Path $projectDir 'tools\spike\work\effect_clips_report.json'
New-Item -ItemType Directory -Force -Path $workDir, $rawDir, (Split-Path (Join-Path $soundsDir $Sound) -Parent) | Out-Null
if (-not (Test-Path $sourcePath)) { throw "source not found: $sourcePath" }

$ffmpegLog = Join-Path $workDir 'ffmpeg-impact-last.log'
function Invoke-Ffmpeg {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $ffmpeg @Arguments 2> $ffmpegLog } finally { $ErrorActionPreference = $previous }
    $code = $LASTEXITCODE
    $text = if (Test-Path $ffmpegLog) { Get-Content $ffmpegLog } else { @() }
    return @{ Code = $code; Text = $text }
}
function Measure-Take {
    param([string]$File)
    $text = (Invoke-Ffmpeg -Arguments @('-hide_banner', '-i', $File, '-af', 'volumedetect', '-f', 'null', 'NUL')).Text
    $means = ($text | Select-String 'mean_volume:\s*(-?[0-9.]+) dB').Matches
    $maxes = ($text | Select-String 'max_volume:\s*(-?[0-9.]+) dB').Matches
    $durations = ($text | Select-String 'Duration:\s*(\d+):(\d+):([0-9.]+)').Matches
    if ($means.Count -eq 0 -or $maxes.Count -eq 0 -or $durations.Count -eq 0) { return $null }
    $seconds = [double]$durations[0].Groups[1].Value * 3600 +
        [double]$durations[0].Groups[2].Value * 60 + [double]$durations[0].Groups[3].Value
    return @{ Mean = [double]$means[0].Groups[1].Value; Max = [double]$maxes[0].Groups[1].Value;
        Seconds = $seconds }
}

$temp = Join-Path $workDir ("{0}_trim.wav" -f $Name)
# The internal target is one Vorbis-overshoot below the ceiling, because the codec's decoded peak lands a
# few tenths either side of what went in. loudnorm's TP is set to that internal ceiling too: with TP=-1.0 the
# trimmed take was already pegged at -1.0 and the gain had nowhere to go.
$innerCeilingDb = $PeakCeilingDb - $VorbisOvershootDb
$decode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-ss', $StartSeconds,
    '-t', ($EndSeconds - $StartSeconds), '-i', $sourcePath,
    '-af', ("silenceremove=start_periods=1:start_threshold={0}dB:start_silence=0.02,areverse,silenceremove=start_periods=1:start_threshold={0}dB:start_silence=0.02,areverse,loudnorm=I=-16.5:TP={1}:LRA=11" -f $SilenceThresholdDb, $innerCeilingDb),
    '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', $temp)
if ($decode.Code -ne 0) { throw "ffmpeg could not cut $Source" }

$take = Measure-Take -File $temp
if ($null -eq $take) { throw 'could not measure the trimmed take' }
# The impact clip is a TRANSIENT: its mean is low by nature (most of the file is the tail), so the only
# meaningful target is the PEAK. It is aimed at the same ceiling the voices use (README 5v/5l): the user
# reported the family voices as too quiet, and this clip sat 0.5 dB below them.
$gain = [math]::Round([math]::Min($TargetMeanDb - $take.Mean, $innerCeilingDb - $take.Max), 2)
$shipped = Join-Path $soundsDir ("{0}.ogg" -f $Sound)
$after = $null
if ($DryRun) {
    $after = @{ Mean = $take.Mean + $gain; Max = $take.Max + $gain }
} else {
    $encode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp,
        '-af', "volume=$($gain)dB", '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped)
    if ($encode.Code -ne 0) { throw 'ffmpeg could not encode the clip' }
    $after = Measure-Take -File $shipped
    # Aim the SHIPPED peak at the ceiling, in both directions: Vorbis does not reproduce the sample peak it
    # was given (this clip's decoded peak moves ~1.4 dB per dB of gain, because the codec's ringing adds to
    # the transient), so the step is damped - a full step overshoots and the loop just oscillates.
    for ($attempt = 0; $attempt -lt 8 -and $null -ne $after; $attempt++) {
        $delta = [math]::Round($PeakCeilingDb - $after.Max, 2)
        Write-Host ("  attempt {0}: gain {1:N2} dB -> mean {2:N1} peak {3:N1}, delta {4:N2}" -f
            ($attempt + 1), $gain, $after.Mean, $after.Max, $delta) -ForegroundColor DarkGray
        if ([math]::Abs($delta) -le 0.15) { break }
        $gain = [math]::Round($gain + $delta * 0.6, 2)
        $encode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp,
            '-af', "volume=$($gain)dB", '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped)
        if ($encode.Code -ne 0) { break }
        $after = Measure-Take -File $shipped
    }
    # Never ship a peak above the ceiling, whatever the loop converged to.
    for ($clamp = 0; $clamp -lt 3 -and $null -ne $after -and $after.Max -gt $PeakCeilingDb; $clamp++) {
        $gain = [math]::Round($gain - ($after.Max - $PeakCeilingDb + 0.05), 2)
        $encode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp,
            '-af', "volume=$($gain)dB", '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped)
        if ($encode.Code -ne 0) { break }
        $after = Measure-Take -File $shipped
    }
    Copy-Item $shipped (Join-Path $rawDir ("bili-{0}.ogg" -f $Name)) -Force
}

$clip = [pscustomobject]@{
    name = $Name
    event = $Event
    sound = $Sound
    category = 'effect'
    source = "$Note"
    seconds = [math]::Round($take.Seconds, 2)
    meanDb = [math]::Round($after.Mean, 1)
    peakDb = [math]::Round($after.Max, 1)
    gainDb = $gain
    bytes = if ($DryRun) { 0 } else { (Get-Item $shipped).Length }
    subtitleZh = $SubtitleZh
    subtitleEn = $SubtitleEn
}
$report = [pscustomobject]@{ generated = @($clip); targetMeanDb = -16.8; peakCeilingDb = -1.0 }
if (-not $DryRun) {
    [System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6),
        (New-Object System.Text.UTF8Encoding($false)))
}
$clip | Format-List
Write-Host ("{0}: {1:N2}s, mean {2:N1} dB, peak {3:N1} dB, gain {4} dB, {5} bytes" -f
    $Name, $clip.seconds, $clip.meanDb, $clip.peakDb, $clip.gainDb, $clip.bytes) -ForegroundColor Green
Write-Host 'Next: node tools/voice_emit.js (folds the effect into sounds.json + subtitles + manifest + README)' -ForegroundColor Yellow
