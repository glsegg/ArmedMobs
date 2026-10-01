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
#
# TWO FLAVORS, AND WHY THE SCRIPT KNOWS ABOUT BOTH (2026-09-30):
#   armedmobs-0.1.0-all.jar  - the jarJar build, GeckoLib 4.8.4 nested inside. Nothing else needed.
#   armedmobs-0.1.0.jar      - the plain build, no nested GeckoLib. Needs a GeckoLib on the mods folder
#                              (the user's instance has geckolib-forge-1.20.1-4.8.2.jar).
# Both declare modId tarkovscav, so having one of each live in mods/ makes Forge refuse to launch. The user
# switched his instance to the PLAIN jar on 2026-09-26, so this script now detects which flavor is live and
# installs that one by default, and parks ANY other armedmobs jar (either flavor) as a .bak-<sha8> file.
param(
    [string]$Jar = '',
    [string]$Flavor = '',
    [string]$Instance = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3\mods',
    [string]$GameDir = 'C:\TESTv3\.minecraft\versions\1.20.1-Forge_47.4.3',
    [string]$WorkspaceCopy = 'D:\deepseek\ArmedMobs\mods',
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe',
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
# ---------------------------------------------------------------- 0. the artifact names (ONE place)
# The two flavors this mod ships, plus the pre-rebrand name that must never stay live beside either.
$plainName = 'armedmobs-0.1.0.jar'
$allName = 'armedmobs-0.1.0-all.jar'
$flavors = [ordered]@{ 'plain' = $plainName; 'all' = $allName }
$legacyNames = @('tarkovscav-0.1.0-all.jar')

# ---------------------------------------------------------------- 0b. which flavor, and which jar file
if (-not $Flavor) {
    # Whatever is live in the instance wins; with nothing live, the plain one (what the user runs today).
    $live = Get-ChildItem -LiteralPath $Instance -Filter 'armedmobs-0.1.0*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.bak-*' }
    if ($live) {
        $Flavor = if ($live[0].Name -eq $allName) { 'all' } else { 'plain' }
        Write-Host ("flavor from the live jar ({0}): {1}" -f $live[0].Name, $Flavor)
    } else {
        $Flavor = 'plain'
        Write-Host 'no armedmobs jar is live; defaulting to the plain flavor'
    }
}
if (-not $flavors.Contains($Flavor)) { throw "unknown flavor '$Flavor' (use plain or all)" }
$name = $flavors[$Flavor]
if (-not $Jar) { $Jar = "D:\deepseek\ArmedMobs\build\libs\$name" }
Write-Host ("installing flavor: {0} ({1})" -f $Flavor, $name)

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

# ---------------------------------------------------------------- 2. park every OTHER armedmobs jar
# Forge scans every top-level *.jar in a mods folder, and two files declaring the same modId abort the
# launch. That covers the pre-rebrand name AND the other flavor of this one (plain vs -all), which is the trap
# the user's own switch to the plain jar laid: installing the -all build beside his plain jar would have made
# the game refuse to start. Everything that is not the file about to be written is parked as .bak-<sha8>.
function Move-Aside-OtherJar([string]$Directory) {
    if (-not (Test-Path $Directory)) { return }
    $pattern = if ($name -eq $allName) { 'armedmobs-0.1.0.jar' } else { 'armedmobs-0.1.0-all.jar' }
    $others = @()
    $others += Get-ChildItem -LiteralPath $Directory -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($legacyName in $legacyNames) {
        $others += Get-ChildItem -LiteralPath $Directory -Filter $legacyName -ErrorAction SilentlyContinue
    }
    foreach ($other in $others) {
        $otherSha = (Get-FileHash $other.FullName -Algorithm SHA256).Hash.ToLower()
        $aside = "$($other.FullName).bak-$($otherSha.Substring(0, 8))"
        Move-Item -LiteralPath $other.FullName -Destination $aside -Force
        Write-Host ''
        Write-Host "PARKED ANOTHER SAME-ID JAR: $($other.Name) -> $([IO.Path]::GetFileName($aside))" -ForegroundColor Yellow
        Write-Host '  it declares the same modId (tarkovscav), so leaving it live would make Forge refuse to' -ForegroundColor Yellow
        Write-Host '  start. Parked as a .bak-<sha8> file, which Forge ignores.' -ForegroundColor Yellow
        Write-Host ''
    }
}

Write-Host '--- parking every other same-id jar (the other flavor + the pre-rebrand name) ---'
Move-Aside-OtherJar $Instance
Move-Aside-OtherJar $WorkspaceCopy

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
# No other same-id jar may survive - not the pre-rebrand name, and not the other flavor.
foreach ($directory in @($Instance, $WorkspaceCopy)) {
    $otherFlavor = if ($name -eq $allName) { $plainName } else { $allName }
    foreach ($otherName in @($otherFlavor) + $legacyNames) {
        $live = Join-Path $directory $otherName
        if (Test-Path $live) {
            $problems += "another same-id jar is still live: $live (two files with modId tarkovscav = launch failure)"
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
Write-Host 'read-back verification: three copies identical, no other same-id jar live, 7z t = Everything is Ok, ModCommands.class present'
exit 0
