$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $project 'build/audit/render-lifecycle'
$dependencies = (Get-Content (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt') -Raw) -split '[;\r\n]+' |
    Where-Object { $_ -ne '' -and (Test-Path -LiteralPath $_) }
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$version = ((Get-Content (Join-Path $project 'gradle.properties') | Select-String '^geckolib_bundled_version=').Line -split '=', 2)[1]
$gecko = Join-Path $gradleCache "caches/forge_gradle/deobf_dependencies/software/bernie/geckolib/geckolib-forge-1.20.1/${version}_mapped_official_1.20.1/geckolib-forge-1.20.1-${version}_mapped_official_1.20.1.jar"
if (-not (Test-Path -LiteralPath $gecko)) { throw "Resolve the GeckoLib dev dependency first: $gecko" }
$classpath = ($dependencies + @($gecko)) -join ';'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @('DeferredItemPass.java', 'RigVisibility.java') |
    ForEach-Object { Join-Path $project "src/main/java/com/gfl/tarkovscav/client/$_" }
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out @sources (Join-Path $PSScriptRoot 'RenderLifecycleTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Render lifecycle tests failed to compile' }
Push-Location $out
try {
    & $java -cp ($out + ';' + $classpath) com.gfl.tarkovscav.client.RenderLifecycleTest
    if ($LASTEXITCODE -ne 0) { throw 'Render lifecycle tests failed' }
} finally {
    Pop-Location
}
