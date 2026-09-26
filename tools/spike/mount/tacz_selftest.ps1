param([string]$AssetPath = '')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $project 'build/audit/mount'
$dependencies = (Get-Content (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt') -Raw) -split '[;\r\n]+' |
    Where-Object { $_ -ne '' -and (Test-Path -LiteralPath $_) }
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$version = ((Get-Content (Join-Path $project 'gradle.properties') | Select-String '^tacz_version=').Line -split '=', 2)[1]
$tacz = Join-Path $gradleCache "caches/forge_gradle/deobf_dependencies/tarkovscav/libs/tacz/${version}_mapped_official_1.20.1/tacz-${version}_mapped_official_1.20.1.jar"
if (-not (Test-Path -LiteralPath $tacz)) { throw "Resolve the TaCZ dev dependency first: $tacz" }
New-Item -ItemType Directory -Force -Path $out | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($tacz)
try {
    $nested = @()
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -like 'META-INF/jarjar/*.jar' }) {
        $target = Join-Path $out $entry.Name
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        $nested += $target
    }
} finally { $archive.Dispose() }
$classpath = ($dependencies + @($tacz) + $nested) -join ';'
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out (Join-Path $PSScriptRoot 'TaczGripFrameTest.java')
if ($LASTEXITCODE -ne 0) { throw 'TaCZ grip tests failed to compile' }
if (-not $AssetPath) { $AssetPath = Join-Path $project 'run/client/tacz/tacz_default_gun/assets/tacz' }
$AssetPath = (Resolve-Path -LiteralPath $AssetPath).Path
Push-Location $out
try {
    & $java -cp ($out + ';' + $classpath) TaczGripFrameTest $AssetPath
    if ($LASTEXITCODE -ne 0) { throw 'TaCZ grip tests failed' }
} finally { Pop-Location }
