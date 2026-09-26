param([string]$GeoPath = '', [string]$AnimationPath = '', [string]$Clip = '__ALL__', [string]$Anchor = 'RightHandLocator')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $project 'build/audit/mount'
$dependencies = (Get-Content (Join-Path $project 'build/classpath/runClient_minecraftClasspath.txt') -Raw) -split '[;\r\n]+' |
    Where-Object { $_ -ne '' -and (Test-Path -LiteralPath $_) }
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$version = ((Get-Content (Join-Path $project 'gradle.properties') | Select-String '^geckolib_bundled_version=').Line -split '=', 2)[1]
$gecko = Join-Path $gradleCache "caches/forge_gradle/deobf_dependencies/software/bernie/geckolib/geckolib-forge-1.20.1/${version}_mapped_official_1.20.1/geckolib-forge-1.20.1-${version}_mapped_official_1.20.1.jar"
if (-not (Test-Path -LiteralPath $gecko)) { throw "Resolve the GeckoLib dev dependency first: $gecko" }
New-Item -ItemType Directory -Force -Path $out | Out-Null
# Extract the actual nested Molang dependency only into disposable test output.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($gecko)
try {
    $entry = $archive.GetEntry('META-INF/jarjar/mclib-20.jar')
    if (-not $entry) { throw 'GeckoLib Molang dependency missing; cannot run a faithful matrix test' }
    $molang = Join-Path $out 'mclib-20.jar'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $molang, $true)
} finally { $archive.Dispose() }
$classpath = ($dependencies + @($gecko, $molang)) -join ';'
# Execute the current production helper, not a stale build/classes copy or a hand-maintained
# facsimile. The extracted methods retain their exact bodies and are compiled against real APIs.
$layer = Get-Content (Join-Path $project 'src/main/java/com/gfl/tarkovscav/client/GunInHandGeoLayer.java') -Raw
function Get-MethodBody([string]$Source, [string]$Signature) {
    $start = $Source.IndexOf($Signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Production method missing: $Signature" }
    $open = $Source.IndexOf('{', $start)
    $depth = 1
    for ($i = $open + 1; $i -lt $Source.Length; $i++) {
        if ($Source[$i] -eq '{') { $depth++ }
        if ($Source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $Source.Substring($open + 1, $i - $open - 1) }
    }
    throw "Unclosed production method: $Signature"
}
$palmBody = Get-MethodBody $layer 'private static void applyPalmGunFrame('
$renderBody = Get-MethodBody $layer 'public void renderForBone('
$tryStart = $renderBody.IndexOf('try {') + 'try {'.Length
$frameEnd = $renderBody.IndexOf('boolean offhand', $tryStart)
if ($tryStart -lt 5 -or $frameEnd -lt $tryStart) { throw 'Production locator block changed; update its extraction explicitly' }
$locatorBody = $renderBody.Substring($tryStart, $frameEnd - $tryStart)
$rig = Get-Content (Join-Path $project 'src/main/java/com/gfl/tarkovscav/client/RigSupport.java') -Raw
$armBody = Get-MethodBody $rig 'private static void applyArm('
$armAnglesBody = Get-MethodBody $rig 'private static float[] armAngles('
$armConstants = ([regex]::Matches($rig, 'private static final float\[\] GEO_ARM_[A-Z_]+\s*=\s*\{[^}]+};') |
    ForEach-Object { $_.Value }) -join "`n"
$bridge = @"
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.state.BoneSnapshot;
import software.bernie.geckolib.util.RenderUtils;
import com.gfl.tarkovscav.client.ArmPose;
public final class ProductionPalmFrame {
    $armConstants
    public static void applyPalmGunFrame(PoseStack poseStack) { $palmBody }
    public static void applyLocator(PoseStack poseStack, GeoBone bone) { $locatorBody }
    public static void applyArm(CoreGeoBone bone, float x, float y, float z) { $armBody }
    public static float[] armAngles(ArmPose pose, boolean longGun) { $armAnglesBody }
}
"@
$bridgePath = Join-Path $out 'ProductionPalmFrame.java'
[IO.File]::WriteAllText($bridgePath, $bridge, [Text.UTF8Encoding]::new($false))
$poseSources = @('client/ArmPose.java', 'gun/GunAiState.java', 'client/RotationSplineCompat.java') |
    ForEach-Object { Join-Path $project ('src/main/java/com/gfl/tarkovscav/' + $_) }
& $javac -proc:none -encoding UTF-8 --release 17 -cp $classpath -d $out @poseSources $bridgePath (Join-Path $PSScriptRoot 'MountMatrixTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Mount matrix tests failed to compile' }
if (-not $GeoPath) { $GeoPath = Join-Path $project 'src/main/resources/assets/tarkovscav/geo/scav.geo.json' }
if (-not $AnimationPath) { $AnimationPath = Join-Path $project 'src/main/resources/assets/tarkovscav/animations/scav.animation.json' }
$GeoPath = (Resolve-Path -LiteralPath $GeoPath).Path
$AnimationPath = (Resolve-Path -LiteralPath $AnimationPath).Path
Push-Location $out
try {
    & $java "-Dmount.project=$project" -cp ($out + ';' + $classpath) MountMatrixTest $GeoPath $AnimationPath $Clip $Anchor
    if ($LASTEXITCODE -ne 0) { throw 'Mount matrix tests failed' }
} finally { Pop-Location }
