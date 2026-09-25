# Install the freshly built jar into the user's instance + the workspace copy, with the two hard checks the
# 2026-09-24 crash taught us:
#
#   1. REFUSE to install while a Minecraft client is running against the instance. Replacing the jar under a
#      live game is what made a later lazy class load (command/ModCommands) fail with ClassNotFoundException
#      -> NoClassDefFoundError -> client crash ("Failed to read pack metadata" / "Missing data pack
#      mod:tarkovscav" in the log). The game must be restarted for a new jar anyway.
#   2. READ BACK what was installed: sha256 + size, `7z t` on the archive, and the presence of
#      com/gfl/tarkovscav/command/ModCommands.class (the class whose absence is the symptom of a torn jar).
#
# The artifact is named after the DISPLAY name since the "Armed Mobs" rebrand (armedmobs-0.1.0-all.jar),
# while the mod id deliberately stays tarkovscav. That creates one hazard this script now handles: a
# leftover pre-rebrand tarkovscav-0.1.0-all.jar declares the SAME modId, and Forge aborts the launch when
# mods/ holds two files with one id. Any live old-named jar is therefore moved aside to a .bak-<sha8> name
# (never left as a live *.jar) before the new one is copied in.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\spike\install_jar.ps1
#   ... -Jar <path> -Instance <mods dir> -Force
param(
    [string]$Jar = 'D:\deepseek\ArmedMobs\build\libs\armedmobs-0.1.0-all.jar',
    [string]$Instance = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\mods',
    [string]$GameDir = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3',
    [string]$WorkspaceCopy = 'D:\deepseek\ArmedMobs\mods',
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe',
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
# ---------------------------------------------------------------- 0. the artifact names (ONE place)
# The name this script installs, plus the pre-rebrand names that must never stay live beside it.
$name = 'armedmobs-0.1.0-all.jar'
$legacyNames = @('tarkovscav-0.1.0-all.jar')

# ---------------------------------------------------------------- 1. is a client running?
$clients = @()
foreach ($process in Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue) {
    if ($process.CommandLine -and $process.CommandLine -like "*--gameDir $GameDir*") {
        $clients += $process
    }
}
if ($clients.Count -gt 0 -and -not $Force) {
    Write-Host 'REFUSING TO INSTALL: a Minecraft client is running against this instance.' -ForegroundColor Red
    foreach ($client in $clients) {
        Write-Host ("  pid {0} started {1}" -f $client.ProcessId, $client.CreationDate)
    }
    Write-Host 'Replacing the jar under a live game is what caused the 2026-09-24 ClassNotFoundException'
    Write-Host 'crash, and the new jar needs a restart to load anyway. Ask the user to quit the game,'
    Write-Host 'then run this script again (or -Force to override, which is never recommended).'
    exit 2
}
Write-Host ("client running at install time: {0}" -f $(if ($clients.Count -gt 0) { "YES (forced, pids $($clients.ProcessId -join ','))" } else { 'no' }))

# ---------------------------------------------------------------- 2. move a pre-rebrand jar aside
# Forge scans every top-level *.jar in a mods folder, and two files declaring the same modId abort the
# launch. Both the old and the new name declare tarkovscav (the id intentionally did not change), so a
# live old-named jar has to go - parked as a .bak-<sha8> file that Forge ignores.
function Move-Aside-LegacyJar([string]$Directory) {
    if (-not (Test-Path $Directory)) { return }
    foreach ($legacyName in $legacyNames) {
        $live = Join-Path $Directory $legacyName
        if (-not (Test-Path $live)) { continue }
        $legacySha = (Get-FileHash $live -Algorithm SHA256).Hash.ToLower()
        $aside = "$live.bak-$($legacySha.Substring(0, 8))"
        Move-Item -LiteralPath $live -Destination $aside -Force
        Write-Host ''
        Write-Host "MOVED AN OLD-NAMED JAR ASIDE: $legacyName -> $([IO.Path]::GetFileName($aside))" -ForegroundColor Yellow
        Write-Host '  the old name declares the SAME modId as the new one (the id must not change), so leaving' -ForegroundColor Yellow
        Write-Host '  it as a live *.jar would make Forge refuse to start with a duplicate-mod error.' -ForegroundColor Yellow
        Write-Host "  parked in $Directory as a .bak-<sha8> file that Forge ignores" -ForegroundColor Yellow
        Write-Host ''
    }
}

Write-Host '--- clearing any pre-rebrand jar out of the live mods folders ---'
Move-Aside-LegacyJar $Instance
Move-Aside-LegacyJar $WorkspaceCopy

# ---------------------------------------------------------------- 3. install
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar" }
$sha = (Get-FileHash $Jar -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $Jar).Length
$target = Join-Path $Instance $name
if (Test-Path $target) {
    $oldSha = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    $backup = "$target.bak-$($oldSha.Substring(0, 8))"
    if (-not (Test-Path $backup)) { Copy-Item $target $backup }
    Write-Host "backed up the previous jar as $([IO.Path]::GetFileName($backup))"
}
Copy-Item $Jar $target -Force
Copy-Item $Jar (Join-Path $WorkspaceCopy $name) -Force
Set-Content (Join-Path $WorkspaceCopy 'SHA256SUMS') "$sha  $name`n" -NoNewline -Encoding ascii
Write-Host "installed $name ($size bytes, sha256 $sha)"

# ---------------------------------------------------------------- 4. read back and verify
$problems = @()
foreach ($path in @($Jar, $target, (Join-Path $WorkspaceCopy $name))) {
    $file = Get-Item $path
    $readSha = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
    if ($readSha -ne $sha -or $file.Length -ne $size) {
        $problems += "copy mismatch: $path (size $($file.Length), sha $readSha)"
    }
    Write-Host ("  {0,-95} size={1} sha={2}" -f $path, $file.Length, $readSha)
}
# No live old-named jar may survive: it would be a second file declaring modId tarkovscav.
foreach ($directory in @($Instance, $WorkspaceCopy)) {
    foreach ($legacyName in $legacyNames) {
        $live = Join-Path $directory $legacyName
        if (Test-Path $live) {
            $problems += "an old-named jar is still live: $live (two files with modId tarkovscav = launch failure)"
        }
    }
}
$archive = & $SevenZip t $target 2>&1 | Out-String
if ($archive -notmatch 'Everything is Ok') {
    $problems += "7z t did not report 'Everything is Ok' for $target"
}
$listing = & $SevenZip l $target 2>&1 | Out-String
foreach ($entry in @('com\gfl\tarkovscav\command\ModCommands.class', 'com\gfl\tarkovscav\TarkovScav.class',
        'META-INF\mods.toml', 'com\gfl\tarkovscav\worldgen\CityDistrictAssembler.class')) {
    if ($listing -notmatch [regex]::Escape($entry)) { $problems += "entry missing from the archive: $entry" }
}
if ($problems.Count -gt 0) {
    Write-Host 'READ-BACK VERIFICATION FAILED:' -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host 'read-back verification: three copies identical, no live old-named jar, 7z t = Everything is Ok, ModCommands.class present'
exit 0
