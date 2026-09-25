# Turns a stretch of a source recording into shipped voice clips: cut on silence, mono, trimmed, loudness
# matched to the clips that are already in the mod.
#
#   .\tools\make_voice_clips.ps1 -Source <file> -StartSeconds 146 -EndSeconds 182 -Prefix contact
#   .\tools\make_voice_clips.ps1 -Source <file> -StartSeconds 451 -EndSeconds 526 -Prefix idle -IndexStart 1
#
# What it does, per segment:
#   1. silencedetect (default -34 dB, 0.20 s) over the requested range gives the sentence boundaries;
#   2. segments shorter than -MinSeconds or longer than -MaxSeconds are dropped (a 12 s monologue is not a
#      shout, and a 0.2 s fragment is a lip smack);
#   3. each kept segment is cut with -PadSeconds of padding, decoded to MONO 44100 Hz and trimmed at both
#      ends (silenceremove, -45 dB);
#   4. its mean and peak volume are measured, and a single gain is applied that lands the mean on
#      -TargetMeanDb WITHOUT pushing the peak above -PeakCeilingDb. The existing fifteen clips measure
#      -16.4 .. -17.1 dB mean and -1.0 .. -6.0 dB peak, so those are the defaults;
#   5. it is written twice: the un-shipped source copy into assets_source/voice/raw/ and the shipped clip
#      into src/main/resources/assets/tarkovscav/sounds/voice/<prefix>_<n>.ogg (Vorbis, q4).
#
# Everything it writes is listed at the end with channels / sample rate / seconds / mean dB, so the result
# can be checked without ears: tools/voice_report.js audits the mono requirement and tools/selftest_voice.js
# the naming, and every clip it produces must also get a sounds.json entry, a lang key and a pool slot.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][int]$StartSeconds,
    [Parameter(Mandatory = $true)][int]$EndSeconds,
    [Parameter(Mandatory = $true)][ValidateSet('contact', 'idle', 'taunt', 'grenade', 'mark', 'death')][string]$Prefix,
    [int]$IndexStart = 1,
    [double]$TargetMeanDb = -16.8,
    [double]$PeakCeilingDb = -1.0,
    [double]$SilenceDb = -34.0,
    [double]$SilenceSeconds = 0.20,
    [double]$MinSeconds = 0.45,
    [double]$MaxSeconds = 8.0,
    [double]$PadSeconds = 0.06,
    [int]$MaxClips = 14,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ffmpeg = if (Test-Path 'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe') {
    'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe'
} else { 'ffmpeg' }

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workDir = Join-Path $projectDir 'tools\spike\work\voice'
$rawDir = Join-Path $projectDir 'assets_source\voice\raw'
$soundsDir = Join-Path $projectDir 'src\main\resources\assets\tarkovscav\sounds\voice'
New-Item -ItemType Directory -Force -Path $workDir, $rawDir, $soundsDir | Out-Null

# ffmpeg writes its report to stderr, and PowerShell turns a native command's stderr into an error record
# that $ErrorActionPreference = 'Stop' would turn into a script abort. Sending stderr to a file keeps it
# as data - and gives the actual ffmpeg reason when something really fails.
$ffmpegLog = Join-Path $workDir 'ffmpeg-last.log'
function Invoke-Ffmpeg {
    param([string[]]$Arguments, [switch]$Capture)
    # PowerShell 5.1 turns a native command's stderr into a terminating error while
    # $ErrorActionPreference is 'Stop', so it is relaxed for the call only.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $ffmpeg @Arguments 2> $ffmpegLog } finally { $ErrorActionPreference = $previous }
    $code = $LASTEXITCODE
    # An ARRAY of lines, not -Raw: $detect below iterates this, and iterating a single string
    # walks its characters.
    $text = if (Test-Path $ffmpegLog) { Get-Content $ffmpegLog } else { @() }
    if (-not $Capture -and $code -ne 0) {
        throw "ffmpeg failed (exit $code): $($Arguments -join ' ')`n$($text -join [Environment]::NewLine)"
    }
    return $text
}
function Measure-Volume {
    param([string]$File)
    $text = Invoke-Ffmpeg -Capture -Arguments @('-hide_banner', '-i', $File, '-af', 'volumedetect', '-f', 'null', 'NUL')
    $mean = [double](($text | Select-String 'mean_volume:\s*(-?[0-9.]+) dB').Matches[0].Groups[1].Value)
    $max = [double](($text | Select-String 'max_volume:\s*(-?[0-9.]+) dB').Matches[0].Groups[1].Value)
    return @{ Mean = $mean; Max = $max }
}

# ---- 1. the range, as one mono wav, so the segment times are relative to the requested window.
$rangeWav = Join-Path $workDir "$Prefix`_range_$StartSeconds.wav"
Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $Source, '-ss', $StartSeconds, '-t', ($EndSeconds - $StartSeconds), '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', $rangeWav) | Out-Null
$window = $EndSeconds - $StartSeconds

# ---- 2. the sentence boundaries.
$detect = Invoke-Ffmpeg -Capture -Arguments @('-hide_banner', '-i', $rangeWav, '-af', "silencedetect=n=$($SilenceDb)dB:d=$SilenceSeconds", '-f', 'null', 'NUL')
$starts = @(); $ends = @()
foreach ($line in $detect) {
    if ($line -match 'silence_start:\s*([0-9.]+)') { $starts += [double]$Matches[1] }
    elseif ($line -match 'silence_end:\s*([0-9.]+)') { $ends += [double]$Matches[1] }
}
$segments = New-Object System.Collections.Generic.List[object]
$cursor = 0.0
for ($i = 0; $i -lt $starts.Count; $i++) {
    $from = $cursor
    $to = $starts[$i]
    if ($to - $from -ge $MinSeconds) { $segments.Add(@{ From = $from; To = $to }) }
    $cursor = if ($i -lt $ends.Count) { $ends[$i] } else { $starts[$i] }
}
if ($window - $cursor -ge $MinSeconds) { $segments.Add(@{ From = $cursor; To = $window }) }

$kept = $segments | Where-Object { ($_.To - $_.From) -ge $MinSeconds -and ($_.To - $_.From) -le $MaxSeconds } |
    Select-Object -First $MaxClips
Write-Host ("{0}: {1} speech segment(s) in {2}..{3}s, keeping {4}" -f $Prefix, $segments.Count,
    $StartSeconds, $EndSeconds, $kept.Count) -ForegroundColor Cyan
if ($kept.Count -eq 0) { throw 'no usable segment - check the range or loosen -SilenceDb / -MinSeconds' }

# ---- 3./4./5. cut, trim, normalise, encode.
$index = $IndexStart
$table = @()
foreach ($segment in $kept) {
    $from = [math]::Max(0.0, $segment.From - $PadSeconds)
    $length = ($segment.To - $segment.From) + 2 * $PadSeconds
    $temp = Join-Path $workDir ("{0}_{1}_trim.wav" -f $Prefix, $index)
    Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $rangeWav, '-ss', $from, '-t', $length, '-af', 'silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,loudnorm=I=-16.5:TP=-1.0:LRA=11', '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', $temp) | Out-Null

    $volume = Measure-Volume -File $temp
    # One gain for both constraints: land on the target mean, but never push the peak past the ceiling.
    $gain = [math]::Round([math]::Min($TargetMeanDb - $volume.Mean, $PeakCeilingDb - $volume.Max), 2)
    $name = "{0}_{1}" -f $Prefix, $index
    $shipped = Join-Path $soundsDir "$name.ogg"
    $raw = Join-Path $rawDir "bilibili-$name.ogg"
    if (-not $DryRun) {
        Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp, '-af', "volume=$($gain)dB", '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped) | Out-Null
        Copy-Item $shipped $raw -Force
    }
    $after = if ($DryRun) { @{ Mean = $volume.Mean + $gain; Max = $volume.Max + $gain } } else { Measure-Volume -File $shipped }
    $table += [pscustomobject]@{
        clip    = $name
        seconds = [math]::Round(($segment.To - $segment.From), 2)
        meanDb  = [math]::Round($after.Mean, 1)
        peakDb  = [math]::Round($after.Max, 1)
        gainDb  = $gain
        bytes   = if ($DryRun) { 0 } else { (Get-Item $shipped).Length }
    }
    $index++
}

Remove-Item $rangeWav -Force -ErrorAction SilentlyContinue
$table | Format-Table -AutoSize
Write-Host ("wrote {0} clip(s); mean {1:N1}..{2:N1} dB, peak <= {3:N1} dB (the shipped fifteen are" -f
    $table.Count, ($table | Measure-Object meanDb -Minimum).Minimum, ($table | Measure-Object meanDb -Maximum).Maximum,
    ($table | Measure-Object peakDb -Maximum).Maximum) -ForegroundColor Green
Write-Host '  -16.4 .. -17.1 dB mean and -1.0 .. -6.0 dB peak)' -ForegroundColor Green
Write-Host 'Now: add a sounds.json entry, a lang subtitle in BOTH languages, a pool slot in ModSounds,' -ForegroundColor Yellow
Write-Host 'and bump the counts in tools/selftest_voice.js - it gates all three.' -ForegroundColor Yellow
