# Cuts the family voice clips out of the extracted Tarkov / 优质PMC recordings, using the plan that
# tools/voice_plan.js produced from the pool table (tools/voice_pools.json).
#
#   node tools/voice_plan.js
#   powershell -ExecutionPolicy Bypass -File tools/make_family_voice_clips.ps1
#   powershell -ExecutionPolicy Bypass -File tools/make_family_voice_clips.ps1 -OnlyPool usec_contact -MaxPerPool 2
#
# Per clip, the loudness recipe (README 5l), aimed at the SAME perceived loudness as the original 27 scav
# clips (mean -16.4..-17.1 dB):
#   1. decode to MONO 44100 Hz;
#   2. trim the silence at both ends (silenceremove -45 dB, 0.02 s) and run loudnorm=I=-16.5:TP=-1.0:LRA=11;
#   3. measure mean/peak with volumedetect, then apply ONE gain that lands the MEAN on -16.8 dB, letting
#      alimiter shave whatever pokes above the -0.5 dB ceiling, and re-aim the gain up to three times
#      (the limiter lowers the mean a little as well as the peaks);
#   4. encode Vorbis q4 into src/main/resources/assets/tarkovscav/sounds/voice/<family>_<category>_<n>.ogg
#      and keep the raw source take under assets_source/voice/raw/.
# Why a limiter and not just a lower gain: the first batch capped the peak at -1.0 dB and applied no
# limiter, so a high-crest shout could only reach -21.5 dB mean - 4.5 dB quieter than the scavs, which is
# what the user heard. The gain is backed off if the limiter would have to shave more than -MaxLimiterDb,
# and the amount is reported per clip (limitDb), so nothing is squashed silently.
# A take whose trimmed length is outside [minSeconds, maxSeconds] is SKIPPED and the next candidate is
# tried, so a lip smack never ships as a shout. The result is MERGED into
# tools/spike/work/voice_clips_report.json (so re-rendering one family does not drop the others), which
# tools/voice_emit.js turns into sounds.json, the subtitle keys, the clip manifest and voice_levels.json.
param(
    [string]$PlanFile = 'tools/spike/work/voice_plan.json',
    [string]$OnlyPool = '',
    [string]$OnlyFamily = '',
    [int]$MaxPerPool = 0,
    [double]$TargetMeanDb = -16.8,
    [double]$PeakCeilingDb = -0.5,
    [double]$MaxLimiterDb = 6.0,
    [switch]$DryRun
)

# -OnlyFamily accepts a comma-separated list ("bear,elite"), so one run can cover exactly the families that
# need re-rendering and leave the others byte-identical.
$familyFilter = @($OnlyFamily -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })

$ErrorActionPreference = 'Stop'
$ffmpeg = if (Test-Path 'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe') {
    'D:\deepseek\GirlsFrontline\tools\bin\ffmpeg.exe'
} else { 'ffmpeg' }

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$planPath = Join-Path $projectDir $PlanFile
$workDir = Join-Path $projectDir 'tools\spike\work\voice'
$rawDir = Join-Path $projectDir 'assets_source\voice\raw'
$soundsDir = Join-Path $projectDir 'src\main\resources\assets\tarkovscav\sounds\voice'
$reportPath = Join-Path $projectDir 'tools\spike\work\voice_clips_report.json'
New-Item -ItemType Directory -Force -Path $workDir, $rawDir, $soundsDir | Out-Null

if (-not (Test-Path $planPath)) { throw "plan not found: $planPath (run node tools/voice_plan.js first)" }
# NOTE: read as UTF-8 explicitly (the plan carries Chinese source names from the 优质PMC set, and
# Get-Content would decode them with the ANSI code page and hand ffmpeg a path that does not exist), and
# assign to $planData and NOT to a variable called $plan: a [string] parameter keeps its type constraint,
# so `$plan = ... | ConvertFrom-Json` would silently coerce the plan back into a string.
$planData = [System.IO.File]::ReadAllText($planPath, (New-Object System.Text.UTF8Encoding($false))) |
    ConvertFrom-Json

# The already-rendered clips, so a family-by-family run MERGES instead of dropping the others.
$previousClips = @()
if (Test-Path $reportPath) {
    $previousReport = [System.IO.File]::ReadAllText($reportPath,
        (New-Object System.Text.UTF8Encoding($false))) | ConvertFrom-Json
    $previousClips = @($previousReport.generated)
}

# alimiter takes a LINEAR limit (0.9441 = -0.5 dBFS), not dB.
# The limiter has to aim a little BELOW the ceiling: Vorbis is lossy and its decoded peak lands ~0.3 dB
# above what went in (measured), so limiting exactly at -0.5 shipped files that measured -0.2 and left the
# loop chasing its own overshoot instead of raising the mean.
$vorbisOvershootDb = 0.8
$limiterCeilingDb = $PeakCeilingDb - $vorbisOvershootDb
$limitLinear = [math]::Round([math]::Pow(10.0, $limiterCeilingDb / 20.0), 6)

$ffmpegLog = Join-Path $workDir 'ffmpeg-family-last.log'
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
    $result = Invoke-Ffmpeg -Arguments @('-hide_banner', '-i', $File, '-af', 'volumedetect', '-f', 'null', 'NUL')
    $text = $result.Text
    $means = ($text | Select-String 'mean_volume:\s*(-?[0-9.]+) dB').Matches
    $maxes = ($text | Select-String 'max_volume:\s*(-?[0-9.]+) dB').Matches
    $durations = ($text | Select-String 'Duration:\s*(\d+):(\d+):([0-9.]+)').Matches
    if ($means.Count -eq 0 -or $maxes.Count -eq 0 -or $durations.Count -eq 0) { return $null }
    $seconds = [double]$durations[0].Groups[1].Value * 3600 +
        [double]$durations[0].Groups[2].Value * 60 + [double]$durations[0].Groups[3].Value
    return @{
        Mean = [double]$means[0].Groups[1].Value
        Max = [double]$maxes[0].Groups[1].Value
        Seconds = $seconds
    }
}

$minSeconds = [double]$planData.minSeconds
$maxSeconds = [double]$planData.maxSeconds

$generated = New-Object System.Collections.Generic.List[object]
$skipped = New-Object System.Collections.Generic.List[object]
$poolSummary = New-Object System.Collections.Generic.List[object]

$planLine = "voice plan: {0} pool(s), filter family='{1}' pool='{2}', target mean {3} dB," `
    + " peak ceiling {4} dB, limiter up to {5} dB"
Write-Host ($planLine -f $planData.pools.Count, $OnlyFamily, $OnlyPool, $targetMeanDb, $peakCeilingDb,
    $maxLimiterDb) -ForegroundColor DarkGray
Write-Host ("  alimiter limit = {0} (linear)" -f $limitLinear) -ForegroundColor DarkGray

foreach ($pool in $planData.pools) {
    if ($OnlyPool -and $pool.pool -ne $OnlyPool) { continue }
    if ($familyFilter.Count -gt 0 -and $familyFilter -notcontains $pool.family) { continue }
    $wanted = [int]$pool.wanted
    if ($MaxPerPool -gt 0 -and $MaxPerPool -lt $wanted) { $wanted = $MaxPerPool }
    $index = 0
    $tries = 0
    foreach ($candidate in $pool.candidates) {
        if ($index -ge $wanted) { break }
        $tries++
        $name = '{0}_{1}' -f $pool.pool, ($index + 1)
        $temp = Join-Path $workDir ("{0}_trim.wav" -f $name)
        $decode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $candidate.path,
            '-af', 'silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,loudnorm=I=-16.5:TP=-1.0:LRA=11',
            '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', $temp)
        if ($decode.Code -ne 0) {
            $skipped.Add([pscustomobject]@{ pool = $pool.pool; file = $candidate.file; why = 'decode failed' })
            continue
        }
        $take = Measure-Take -File $temp
        if ($null -eq $take) {
            $skipped.Add([pscustomobject]@{ pool = $pool.pool; file = $candidate.file; why = 'unreadable' })
            continue
        }
        if ($take.Seconds -lt $minSeconds -or $take.Seconds -gt $maxSeconds) {
            $skipped.Add([pscustomobject]@{ pool = $pool.pool; file = $candidate.file;
                why = ('length {0:N2}s' -f $take.Seconds) })
            continue
        }
        # ---- the loudness policy ------------------------------------------------------------------
        # The original 27 scav clips sit at -16.4..-17.1 dB mean, so that is the target for the family
        # clips too. The first batch of these shipped with a -1.0 dB peak ceiling and NO limiter, so a
        # high-crest shout could only reach -21.5 dB mean - 4.5 dB quieter than the scavs, which is what
        # the user heard ("the usec/bear/elite voices are quieter than the scav's").
        #
        # The fix is to aim at the MEAN and control the peaks: raise the level to the target and let
        # alimiter shave whatever pokes above the ceiling. The amount it has to shave is reported per clip
        # (limitDb); asking for more than -MaxLimiterDb is refused, so a clip is never squashed flat just to
        # hit a number, and the clips that hit that ceiling are listed in the summary.
        #
        # The loop, per attempt: encode -> measure. A peak above the ceiling is fixed first (Vorbis
        # overshoots the limiter by a few tenths), then the gain is re-aimed at the mean. It stops as soon
        # as both hold, and one final render is forced if the last measurement was still over the ceiling.
        $gain = [math]::Round($targetMeanDb - $take.Mean, 2)
        $reduction = [math]::Max(0.0, ($take.Max + $gain) - $peakCeilingDb)
        if ($reduction -gt $maxLimiterDb) {
            $gain = [math]::Round($gain - ($reduction - $maxLimiterDb), 2)
        }
        $shipped = Join-Path $soundsDir "$name.ogg"
        $after = $null
        $attempts = 0
        $previousGain = $null
        $previousMean = 0.0
        if ($DryRun) {
            $after = @{ Mean = $take.Mean + $gain; Max = [math]::Min($peakCeilingDb, $take.Max + $gain);
                Seconds = $take.Seconds }
        } else {
            for ($attempt = 0; $attempt -lt 8; $attempt++) {
                $chain = "volume=$($gain)dB,alimiter=level_in=1:level_out=1:limit=$($limitLinear):attack=5:release=50:level=disabled"
                $encode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp,
                    '-af', $chain, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped)
                $attempts = $attempt + 1
                if ($encode.Code -ne 0) { break }
                $after = Measure-Take -File $shipped
                if ($null -eq $after) { break }
                if ($after.Max -gt $peakCeilingDb + 0.15) {
                    # The peak always wins: pull the gain down and re-measure.
                    $gain = [math]::Round($gain - ($after.Max - $peakCeilingDb + 0.15), 2)
                    continue
                }
                $deficit = $targetMeanDb - $after.Mean
                if ([math]::Abs($deficit) -le 0.20) { break }
                # How much of a gain step actually lands on the mean: with the limiter working, one dB of
                # gain buys less than one dB of mean. The slope is measured from the previous attempt (a
                # secant step), which converges in two or three renders instead of crawling.
                $step = $deficit
                if ($null -ne $previousGain -and [math]::Abs($gain - $previousGain) -gt 0.01) {
                    $slope = ($after.Mean - $previousMean) / ($gain - $previousGain)
                    if ($slope -gt 0.05) { $step = $deficit / $slope }
                }
                $step = [math]::Max(-6.0, [math]::Min(6.0, $step))
                $previousGain = $gain
                $previousMean = $after.Mean
                if ($deficit -gt 0.0) {
                    # More loudness means asking the limiter to shave more. Stop at the cap instead of
                    # flattening the clip; the summary names whatever ends up there.
                    $wouldShave = [math]::Max(0.0, ($take.Max + $gain + $step) - $limiterCeilingDb)
                    if ($wouldShave -gt $maxLimiterDb) {
                        $step = [math]::Max(0.0, $maxLimiterDb - [math]::Max(0.0,
                            ($take.Max + $gain) - $limiterCeilingDb))
                        if ($step -le 0.05) { break }
                    }
                }
                $gain = [math]::Round($gain + $step, 2)
            }
            # Final guarantee: whatever the mean converged to, the SHIPPED peak has to respect the policy
            # ceiling (a transient clip can overshoot the limiter by more than the 0.8 dB margin assumed
            # above). It costs a few tenths of a dB of mean and is never skipped.
            for ($fix = 0; $fix -lt 3 -and $null -ne $after -and $after.Max -gt $peakCeilingDb; $fix++) {
                $gain = [math]::Round($gain - ($after.Max - $peakCeilingDb + 0.05), 2)
                $chain = "volume=$($gain)dB,alimiter=level_in=1:level_out=1:limit=$($limitLinear):attack=5:release=50:level=disabled"
                $encode = Invoke-Ffmpeg -Arguments @('-hide_banner', '-loglevel', 'error', '-y', '-i', $temp,
                    '-af', $chain, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', $shipped)
                if ($encode.Code -ne 0) { break }
                $after = Measure-Take -File $shipped
            }
            if ($null -eq $after -or $encode.Code -ne 0) {
                $skipped.Add([pscustomobject]@{ pool = $pool.pool; file = $candidate.file; why = 'encode failed' })
                continue
            }
            Copy-Item $shipped (Join-Path $rawDir ("{0}.ogg" -f $name)) -Force
        }
        # What the limiter had to shave at the final gain, in dB (0 = the clip fit under the limiter).
        $limitDb = [math]::Round([math]::Max(0.0, ($take.Max + $gain) - $limiterCeilingDb), 2)
        $index++
        $generated.Add([pscustomobject]@{
            name = $name
            pool = $pool.pool
            family = $pool.family
            category = $pool.category
            source = $candidate.file
            event = $candidate.event
            seconds = [math]::Round($take.Seconds, 2)
            meanDb = [math]::Round($after.Mean, 1)
            peakDb = [math]::Round($after.Max, 1)
            gainDb = $gain
            limitDb = $limitDb
            bytes = if ($DryRun) { 0 } else { (Get-Item $shipped).Length }
        })
    }
    $poolSummary.Add([pscustomobject]@{ pool = $pool.pool; clips = $index; wanted = $wanted; tried = $tries })
    Write-Host ("{0,-16} {1}/{2} clip(s) from {3} candidate(s)" -f $pool.pool, $index, $wanted, $tries) `
        -ForegroundColor $(if ($index -ge $wanted) { 'Green' } else { 'Yellow' })
}

$meanValues = $generated | Measure-Object meanDb -Minimum -Maximum
$peakValues = $generated | Measure-Object peakDb -Minimum -Maximum
Write-Host ''
Write-Host ("{0} clip(s); mean {1:N1}..{2:N1} dB, peak <= {3:N1} dB, total {4:N0} bytes" -f
    $generated.Count, $meanValues.Minimum, $meanValues.Maximum, $peakValues.Maximum,
    (($generated | Measure-Object bytes -Sum).Sum)) -ForegroundColor Cyan
# The stat that matters for "does it sound as loud as the scavs": the MEDIAN mean, per family, because one
# quiet outlier must not hide a whole family being 4 dB down.
foreach ($family in ($generated | Group-Object family)) {
    $meansOf = @($family.Group | ForEach-Object { $_.meanDb } | Sort-Object)
    $median = $meansOf[[int][math]::Floor($meansOf.Count / 2)]
    $limitMax = ($family.Group | Measure-Object limitDb -Maximum).Maximum
    Write-Host ("  {0,-6} n={1,-3} mean median {2:N1} dB, worst limit {3:N1} dB" -f
        $family.Name, $meansOf.Count, $median, $limitMax) -ForegroundColor Cyan
}
$squashed = @($generated | Where-Object { $_.limitDb -gt 3.0 })
if ($squashed.Count -gt 0) {
    Write-Host ("{0} clip(s) needed more than 3 dB of limiting (listed, not hidden):" -f $squashed.Count) `
        -ForegroundColor Yellow
    foreach ($clip in $squashed) {
        Write-Host ("  {0,-22} limit {1:N1} dB, mean {2:N1} dB, peak {3:N1} dB" -f
            $clip.name, $clip.limitDb, $clip.meanDb, $clip.peakDb) -ForegroundColor Yellow
    }
}
if ($skipped.Count -gt 0) {
    Write-Host ("{0} candidate(s) skipped:" -f $skipped.Count) -ForegroundColor DarkGray
    $skipped | Group-Object why | ForEach-Object { Write-Host ("  {0} x {1}" -f $_.Count, $_.Name) -ForegroundColor DarkGray }
}

# Merge with the clips that were NOT re-rendered this run (a per-family pass must not drop the others).
$kept = @($previousClips | Where-Object { $generated.name -notcontains $_.name })
$allClips = @($kept + $generated | Sort-Object name)
Write-Host ("report: {0} re-rendered this run, {1} kept from the previous run, {2} total" -f
    $generated.Count, $kept.Count, $allClips.Count) -ForegroundColor Green

$report = [pscustomobject]@{
    generated = $allClips
    reRendered = @($generated | ForEach-Object { $_.name })
    skipped = $skipped
    pools = $poolSummary
    targetMeanDb = $targetMeanDb
    peakCeilingDb = $peakCeilingDb
    maxLimiterDb = $maxLimiterDb
    minSeconds = $minSeconds
    maxSeconds = $maxSeconds
}
if (-not $DryRun) {
    [System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6),
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "wrote tools/spike/work/voice_clips_report.json" -ForegroundColor Cyan
}
Write-Host 'Next: node tools/voice_emit.js (sounds.json + subtitles + manifest + voice_levels.json + README)' -ForegroundColor Yellow
