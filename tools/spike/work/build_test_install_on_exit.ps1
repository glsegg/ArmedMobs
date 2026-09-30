# Wait for the game to exit, then rebuild, re-run the full suite, and install ONLY if the suite passed.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spike\work\build_test_install_on_exit.ps1 -Pid 7456
#
# Why it is one script and not three steps I run by hand: the machine has ~1.3 GB free while the user's game is
# up (the client holds ~7 GB), and a Gradle build stalls rather than failing under that pressure - measured: the
# daemon produced no log output for ten minutes with twenty seconds of CPU. So the build has to happen AFTER
# the game exits, and it has to be followed by the suite before the jar is allowed anywhere near mods/.
#
# The install step is deliberately last and conditional: a jar that has not passed the suite is never installed
# (that rule exists because swapping a jar under a live game once caused a real ClassNotFoundException crash).
param(
    [Parameter(Mandatory = $true)][int]$Pid,
    [string]$Root = 'D:\deepseek\ArmedMobs',
    [string]$GradleHome = 'D:\deepseek\GirlsFrontline\.gradle-home',
    [string]$Flavor = 'plain',
    [int]$TimeoutHours = 10
)
$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddHours($TimeoutHours)
Write-Host ("waiting for pid {0} to exit (up to {1} h)... {2}" -f $Pid, $TimeoutHours, (Get-Date -Format 'HH:mm:ss'))
while ($true) {
    if (-not (Get-Process -Id $Pid -ErrorAction SilentlyContinue)) { break }
    if ((Get-Date) -gt $deadline) { Write-Host 'TIMEOUT: the game is still running, nothing was built or installed.'; exit 0 }
    Start-Sleep -Seconds 5
}
Write-Host ("game exited at {0}; waiting 15 s so the client and the OS release memory" -f (Get-Date -Format 'HH:mm:ss'))
Start-Sleep -Seconds 15
$os = Get-CimInstance Win32_OperatingSystem
Write-Host ("free memory now: {0} MB" -f [int]($os.FreePhysicalMemory / 1024))

# ---------------------------------------------------------------- 1. build
$env:GRADLE_USER_HOME = $GradleHome
$buildLog = Join-Path $env:TEMP 'on_exit_build.log'
Write-Host '--- gradle build ---'
& (Join-Path $Root 'gradlew.bat') -p $Root -g $GradleHome build --console=plain *>&1 |
    Tee-Object -FilePath $buildLog | Select-String -Pattern 'BUILD|error:' | Select-Object -Last 4 | ForEach-Object { $_.Line.Trim() }
if ($LASTEXITCODE -ne 0) {
    Write-Host 'BUILD FAILED - nothing installed. Tail of the log:' -ForegroundColor Red
    Get-Content $buildLog -Tail 15
    exit 1
}

# ---------------------------------------------------------------- 2. the suite on those jars
$suiteLog = Join-Path $env:TEMP 'on_exit_suite.log'
Write-Host '--- full self-test suite ---'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'tools\spike\selftest.ps1') *>&1 |
    Tee-Object -FilePath $suiteLog | Select-Object -Last 3
$suiteExit = $LASTEXITCODE
$fails = @(Select-String -Path $suiteLog -Pattern 'FAIL' -CaseSensitive)
if ($suiteExit -ne 0 -or $fails.Count -gt 0) {
    Write-Host ("SUITE FAILED (exit {0}, {1} FAIL line(s)) - nothing installed" -f $suiteExit, $fails.Count) -ForegroundColor Red
    $fails | Select-Object -First 12 | ForEach-Object { Write-Host ('  ' + $_.Line.Trim()) }
    exit 1
}
Write-Host 'suite: all self-tests passed'

# ---------------------------------------------------------------- 3. install, and only now
Write-Host ("--- install (flavor {0}) ---" -f $Flavor)
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'tools\spike\install_jar.ps1') -Flavor $Flavor *>&1 |
    Select-Object -Last 8
$installExit = $LASTEXITCODE
if ($installExit -ne 0) { Write-Host ("INSTALL FAILED (exit {0})" -f $installExit) -ForegroundColor Red; exit 1 }
$name = if ($Flavor -eq 'all') { 'armedmobs-0.1.0-all.jar' } else { 'armedmobs-0.1.0.jar' }
$sha = (Get-FileHash (Join-Path $Root "build\libs\$name") -Algorithm SHA256).Hash.ToLower()
Write-Host ''
Write-Host ("DONE: {0} built, suite green, installed. sha256 {1}" -f $name, $sha)
Write-Host 'Tell the user to relaunch the game; the new jar needs a restart to load.'
