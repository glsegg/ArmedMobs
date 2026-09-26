$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $project 'build/audit-network'
$dependencies = Get-Content (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt')
$classpath = ($dependencies + @(Join-Path $project 'build/classes/java/main')) -join ';'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(
    'Config.java', 'faction/AlertNetwork.java',
    'killfeed/KillFeedNetwork.java', 'killfeed/KillFeedSource.java',
    'killfeed/KillFeed.java', 'world/CaptureHudNetwork.java'
) | ForEach-Object { Join-Path $project ('src/main/java/com/gfl/tarkovscav/' + $_) }
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out @sources (Join-Path $PSScriptRoot 'NetworkRegressionTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Network regression test compilation failed' }
Push-Location $out
try {
    & $java -cp ($out + ';' + $classpath) NetworkRegressionTest
    if ($LASTEXITCODE -ne 0) { throw 'Network regression tests failed' }
} finally { Pop-Location }
