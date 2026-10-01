$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $project 'build/audit-world'
# ForgeGradle writes this artifact per prepared run: runClient once the dev client has been launched through
# Gradle, runServer after a plain prepareRunServer (which is what `gradlew build` leaves behind on this
# machine). Both carry the mapped Minecraft jars these tests compile against, so take whichever exists -
# the main suite already does exactly this for the live GL test.
$classpathFile = @(
    (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt'),
    (Join-Path $project 'build/classpath/runServer_minecraftClasspath.txt')
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $classpathFile) {
    throw 'No build/classpath/*_minecraftClasspath.txt yet: run .\gradlew.bat prepareRunClient (or build) first.'
}
# UTF-8 on purpose: libs/ holds a CJK-named TaCZ jar and Windows PowerShell 5.1 decodes a file with the ANSI
# code page by default, which turned that path into mojibake - Test-Path then dropped it and javac could not
# find the TaCZ API that Config.java uses. Entries that do not resolve are skipped, like the main suite does.
$dependencies = (Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8) -split '[;\r\n]+' |
    Where-Object { $_ -ne '' -and (Test-Path -LiteralPath $_) }
$classpath = ($dependencies + @(Join-Path $project 'build/classes/java/main')) -join ';'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(
    'world/CityPlacementData.java', 'world/CityStructures.java', 'world/CityGate.java',
    'world/CityBuildings.java', 'world/CityCapture.java', 'world/CityGarrison.java',
    'world/GarrisonData.java', 'worldgen/WastelandSpreadPlacement.java', 'worldgen/CityDistrictAssembler.java'
) | ForEach-Object { Join-Path $project ('src/main/java/com/gfl/tarkovscav/' + $_) }
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out @sources (Join-Path $PSScriptRoot 'WorldRegressionTest.java')
if ($LASTEXITCODE -ne 0) { throw 'World regression test compilation failed' }
& $java "-Dworld.test.project=$project" -cp ($out + ';' + $classpath) WorldRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'World regression tests failed' }
