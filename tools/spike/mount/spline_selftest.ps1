$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$out = Join-Path $project 'build/audit/mount'
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$cache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$version = ((Get-Content (Join-Path $project 'gradle.properties') | Select-String '^geckolib_bundled_version=').Line -split '=', 2)[1]
$gecko = Join-Path $cache "caches/forge_gradle/deobf_dependencies/software/bernie/geckolib/geckolib-forge-1.20.1/${version}_mapped_official_1.20.1/geckolib-forge-1.20.1-${version}_mapped_official_1.20.1.jar"
$molang = Join-Path $out 'mclib-20.jar'
if (-not (Test-Path -LiteralPath $molang)) { throw 'Run tools/mount_matrix.js first to prepare the actual nested Molang test dependency' }
$dependencies = Get-Content (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt')
$classpath = ($dependencies + @($gecko, $molang)) -join ';'
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out (Join-Path $project 'src/main/java/com/gfl/tarkovscav/client/RotationSplineCompat.java') (Join-Path $PSScriptRoot 'SplineCompatTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Spline compatibility test compilation failed' }
& $java -cp ($out + ';' + $classpath) SplineCompatTest (Join-Path $project 'src/main/resources/assets/tarkovscav/animations/scav.animation.json')
if ($LASTEXITCODE -ne 0) { throw 'Spline compatibility test failed' }
