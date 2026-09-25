# Head-less self-tests for 塔科夫Scav. No Minecraft instance, no client - everything here runs in a second.
# (One gate opens a HIDDEN GL window: RenderStateGuardLiveTest, the only check that measures the driver.)
#
#   .\tools\spike\selftest.ps1
#
# What runs:
#   CityStructureGen  regenerates the city preset (.nbt + RCON command list) from tools/city-layout.json
#   StructureNbtTest  inflates and parses that .nbt and checks it against the layout
#   GunPoolTest       resolves the mod's tier rules against the real TaCZ gun pack in libs/
#   AssetTest         models, geometry/animation/texture paths, lang keys, mods.toml, config docs
#   GrenadeBallisticsTest  runs the SHIPPED grenade solver (one mod source compiled against stubs in
#                     tools/spike/stubs) over the cover cases: flat, 1 block wall, 2/3 block wall, 6-10 blocks
#   RenderStateGuardLiveTest  opens a hidden GLFW window and round-trips the SHIPPED RenderStateGuard
#                     (compiled from src/main against the Minecraft dependency set, plus two-field stubs in
#                     tools/spike/gl/stubs) against the real driver: active texture unit, units 0..2, the
#                     shader-owned unit, the 12 RenderSystem samplers, both stencil faces, the stencil
#                     clear value, depth/blend/cull, GL_NO_ERROR and enter/leave idempotency. SKIPS
#                     (exit 2) when this machine cannot give it a GL context.
#   JarVerify         inflates every entry of the built jar(s), if there are any
#   the Node rig tests (tools/*.js): hidden-bone merge, head pitch on both paths, anchor/pose
#                     resolution, the pose-writer pipeline simulation (writers per bone per frame, the
#                     yaw gain, the torso swing), city-import validation, geo structure, Molang collapse,
#                     anti-stall watchdog + cover-seek speed table, the door open/close rule, gunner
#                     villager reuse, the per-tier AI profiles + the exposed-target / hurt-reaction
#                     rules (selftest_ai_fire.js), and the GL guard's four ported restores
#                     (selftest_texture_state.js section 8)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    foreach ($candidate in @('C:\Program Files\Java\jdk-21.0.12', 'C:\Program Files\Java\jdk-21.0.11')) {
        if (Test-Path (Join-Path $candidate 'bin\javac.exe')) { $env:JAVA_HOME = $candidate; break }
    }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 first.' }

$spikeDir = $PSScriptRoot
$projectDir = (Resolve-Path (Join-Path $spikeDir '..\..')).Path
$out = Join-Path $spikeDir 'out'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'

New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host '--- compiling the spikes ---' -ForegroundColor Cyan
$sources = Get-ChildItem -Path $spikeDir -Filter '*.java' | ForEach-Object { $_.FullName }
# GrenadeBallisticsTest executes the REAL grenade solver, so the spike build also compiles that one mod
# source against the minimal Minecraft stubs in tools/spike/stubs (see that test's javadoc). Nothing else
# from src/main is compiled here - the rest of the mod needs the game on the classpath.
$sources += Get-ChildItem -Path (Join-Path $spikeDir 'stubs') -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName }
$sources += Join-Path $projectDir 'src\main\java\com\gfl\tarkovscav\grenade\GrenadeBallistics.java'
& $javac -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'the spikes do not compile' }

Push-Location $projectDir
try {
    $failed = 0

    # The suite is a set of CHECKS: it must not rewrite the tracked structure presets. CityStructureGen is
    # allowed to regenerate them (that is how a stale .nbt is caught), but with the style flags gated behind
    # the layout the result is byte-identical - and if it ever is not, this snapshot makes it a FAILURE
    # instead of a silent change to a file users' worlds were generated from.
    $trackedStructures = Join-Path $projectDir 'src\main\resources\data\tarkovscav\structures'
    $presetHashesBefore = @{}
    Get-ChildItem $trackedStructures -Filter '*.nbt' -ErrorAction SilentlyContinue | ForEach-Object {
        $presetHashesBefore[$_.Name] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    }

    Write-Host '--- CityStructureGen (regenerate the preset) ---' -ForegroundColor Cyan
    & $java -cp $out CityStructureGen
    if ($LASTEXITCODE -ne 0) {
        $failed++
        Write-Host "  [gate] CityStructureGen FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    }
    # The two layouts whose name does not match tools/city-layout*.json: the 18-building strongpoint and the
    # district template. They are regenerated here too, so the workspace-unchanged check below also proves
    # THEY are deterministic and not stale (the pins in tools/selftest_strongpoint.js cover the four presets).
    foreach ($layout in @('tools/strongpoint-layout.json', 'tools/district-layout.json')) {
        Write-Host "--- CityStructureGen $layout ---" -ForegroundColor Cyan
        & $java -cp $out CityStructureGen $layout | Select-Object -Last 2
        if ($LASTEXITCODE -ne 0) {
            $failed++
            Write-Host "  [gate] CityStructureGen $layout FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
        }
    }

    foreach ($test in @('StructureNbtTest', 'GunPoolTest', 'AssetTest', 'GrenadeBallisticsTest')) {
        Write-Host "--- $test ---" -ForegroundColor Cyan
        & $java -cp $out $test $projectDir
        if ($LASTEXITCODE -ne 0) {
            $failed++
            Write-Host "  [gate] $test FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
        }
    }

    # ------------------------------------------------------------------ the live-GL round trip
    # The only gate that measures the DRIVER instead of the source or the bytecode: a hidden GLFW window,
    # a real GL context, and RenderStateGuard round-tripped against it (active texture unit, units 0..2,
    # the shader-owned unit, all 12 RenderSystem samplers, BOTH stencil faces, the stencil clear value,
    # depth/blend/cull, GL_NO_ERROR and enter/leave idempotency). It compiles the REAL guard source, with
    # the only two mod symbols it reads (Config.logGlState, TarkovScav.LOGGER) supplied by the tiny stubs
    # in tools/spike/gl/stubs, so no Minecraft instance is needed - just the Minecraft dependency set the
    # build already resolved (build\classpath\*_minecraftClasspath.txt, which carries LWJGL/GLFW/OpenGL).
    # Exit code 2 means SKIP: this machine could not give the test a GL context (no display, no natives,
    # no driver). 0 and 2 are the only non-failures, so the suite never fails for a missing context and
    # never silently passes for one either. JAVA_HOME is the one already resolved above.
    Write-Host '--- RenderStateGuardLiveTest (live hidden-window GL round trip) ---' -ForegroundColor Cyan
    $glClasspathFile = @(
        (Join-Path $projectDir 'build\classpath\runClient_minecraftClasspath.txt'),
        (Join-Path $projectDir 'build\classpath\runServer_minecraftClasspath.txt')
    ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $minecraftCp = ''
    if ($glClasspathFile) {
        $minecraftCp = ((Get-Content -LiteralPath $glClasspathFile -Raw) -split '[;\r\n]+' |
            Where-Object { $_ -ne '' -and (Test-Path -LiteralPath $_) }) -join ';'
    }
    if (-not $minecraftCp) {
        Write-Host '  SKIP  no build\classpath\*_minecraftClasspath.txt with usable jars - run .\gradlew.bat build (or prepareRunClient) first' -ForegroundColor DarkGray
    } else {
        $glOut = Join-Path $out 'gl'
        New-Item -ItemType Directory -Force -Path $glOut | Out-Null
        $glSources = @(
            (Join-Path $spikeDir 'gl\RenderStateGuardLiveTest.java'),
            (Join-Path $spikeDir 'gl\stubs\com\gfl\tarkovscav\Config.java'),
            (Join-Path $spikeDir 'gl\stubs\com\gfl\tarkovscav\TarkovScav.java'),
            (Join-Path $projectDir 'src\main\java\com\gfl\tarkovscav\client\RenderStateGuard.java')
        )
        & $javac -proc:none --release 17 -encoding UTF-8 -cp $minecraftCp -d $glOut @glSources
        if ($LASTEXITCODE -ne 0) {
            $failed++
            Write-Host "  [gate] RenderStateGuardLiveTest does not compile (exit $LASTEXITCODE)" -ForegroundColor Red
        } else {
            # Run from the (git-ignored) spike output dir: RenderSystem/log4j picks up Minecraft's log4j2
            # config from client-extra.jar and would otherwise create logs/latest.log in the project root,
            # which is not ignored. The classpath and every path the test uses are absolute.
            Push-Location $out
            try {
                & $java -cp "$glOut;$minecraftCp" RenderStateGuardLiveTest
                $glExit = $LASTEXITCODE
            } finally {
                Pop-Location
            }
            if ($glExit -eq 2) {
                Write-Host '  SKIP  RenderStateGuardLiveTest (the line above says why; a missing GL context is not a failure)' -ForegroundColor DarkGray
            } elseif ($glExit -ne 0) {
                $failed++
                Write-Host "  [gate] RenderStateGuardLiveTest FAILED (exit $glExit)" -ForegroundColor Red
            }
        }
    }

    # ------------------------------------------------------------------ the Node rig tests
    $node = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $node -and (Test-Path 'D:\生图\node.exe')) { $node = 'D:\生图\node.exe' }
    if (-not $node) {
        Write-Host '--- Node rig tests SKIPPED (no node.exe found) ---' -ForegroundColor DarkGray
    } else {
        $geo = 'src/main/resources/assets/tarkovscav/geo/scav.geo.json'
        $anim = 'src/main/resources/assets/tarkovscav/animations/scav.animation.json'

        # Informational scans: their exit code reports FINDINGS (a Molang expression found is a
        # finding, not a build failure - 106 of them are a documented limitation), so they are printed
        # and not counted.
        Write-Host '--- scan_molang (informational: exit code counts findings) ---' -ForegroundColor Cyan
        & $node tools/scan_molang.js $anim | Select-Object -First 3
        Write-Host '--- scan_head_keyframes ---' -ForegroundColor Cyan
        & $node tools/scan_head_keyframes.js $anim | Select-Object -Last 3
        Write-Host '--- scan_transparent_faces (informational: how many faces show the world through them) ---' -ForegroundColor Cyan
        & $node tools/scan_transparent_faces.js | Select-Object -Last 6
        Write-Host '--- scan_molang_variables (informational: the Molang symbol census) ---' -ForegroundColor Cyan
        & $node tools/scan_molang_variables.js | Select-Object -First 4
        Write-Host '--- patch_transparent_faces --dry (informational: the opt-in asset fix, not applied) ---' -ForegroundColor Cyan
        & $node tools/patch_transparent_faces.js --dry | Select-Object -Last 3

        # Gates: these must exit 0.
        $nodeGates = [ordered]@{
            'scan_geo_structure'   = @('tools/scan_geo_structure.js', $geo)
            'selftest_rig_bones'   = @('tools/selftest_rig_bones.js')
            'selftest_render_scale' = @('tools/selftest_render_scale.js')
            'selftest_ricochet'    = @('tools/selftest_ricochet.js')
            'selftest_rig_pose'    = @('tools/selftest_rig_pose.js')
            'selftest_pose_writers' = @('tools/selftest_pose_writers.js')
            'selftest_accessories' = @('tools/selftest_accessories.js')
            'pose_chain'           = @('tools/pose_chain.js')
            'mount_matrix'         = @('tools/mount_matrix.js')
            'selftest_single_pass' = @('tools/selftest_single_pass.js')
            'selftest_clip_mapping' = @('tools/selftest_clip_mapping.js')
            'selftest_antistall'   = @('tools/selftest_antistall.js')
            'selftest_doors'       = @('tools/selftest_doors.js')
            'selftest_gunner_villager' = @('tools/selftest_gunner_villager.js')
            'selftest_entity_registry' = @('tools/selftest_entity_registry.js')
            'selftest_attribute_config' = @('tools/selftest_attribute_config.js')
            'selftest_lean'        = @('tools/selftest_lean.js')
            'selftest_killfeed'    = @('tools/selftest_killfeed.js')
            'selftest_grenades'    = @('tools/selftest_grenades.js')
            'selftest_voice'       = @('tools/selftest_voice.js')
            'selftest_faction'     = @('tools/selftest_faction.js')
            'selftest_rack'        = @('tools/selftest_rack.js')
            'selftest_accuracy'    = @('tools/selftest_accuracy.js')
            'selftest_mods'        = @('tools/selftest_mods.js')
            'selftest_sniper'      = @('tools/selftest_sniper.js')
            'selftest_ai_profiles' = @('tools/selftest_ai_profiles.js')
            'selftest_ai_fire'     = @('tools/selftest_ai_fire.js')
            'selftest_texture_state' = @('tools/selftest_texture_state.js')
            'selftest_encoding'    = @('tools/selftest_encoding.js')
            'selftest_ascii'       = @('tools/ascii_report.js')
            'selftest_branding'    = @('tools/selftest_branding.js')
            'selftest_city_import' = @('tools/selftest_city_import.js')
            'selftest_city_district' = @('tools/selftest_city_district.js')
            'selftest_strongpoint' = @('tools/selftest_strongpoint.js')
            'selftest_datapack'    = @('tools/selftest_datapack.js')
            'selftest_blockstates' = @('tools/selftest_blockstates.js')
            'selftest_blockentities' = @('tools/selftest_blockentities.js')
            'selftest_interior_variation' = @('tools/selftest_interior_variation.js')
            'selftest_loot_modifier' = @('tools/selftest_loot_modifier.js')
            'selftest_wasteland'   = @('tools/selftest_wasteland.js')
            'selftest_garrison'    = @('tools/selftest_garrison.js')
            'selftest_city_faction' = @('tools/selftest_city_faction.js')
            'selftest_command_marks' = @('tools/selftest_command_marks.js')
            'selftest_wiki_doc'    = @('tools/selftest_wiki_doc.js')
            'selftest_shield'      = @('tools/selftest_shield.js')
            'selftest_shield_assets' = @('tools/selftest_shield_assets.js')
            'selftest_ai_cost'     = @('tools/selftest_ai_cost.js')
            'selftest_docx'        = @('tools/selftest_docx.js')
            'resolve_gun_anchor'   = @('tools/resolve_gun_anchor.js')
        }
        foreach ($name in $nodeGates.Keys) {
            Write-Host "--- $name ---" -ForegroundColor Cyan
            $nodeArgs = $nodeGates[$name]
            & $node @nodeArgs | Select-Object -Last 3
            if ($LASTEXITCODE -ne 0) {
                $failed++
                Write-Host "  [gate] $name FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
            }
        }
    }

    $jars = Get-ChildItem -Path (Join-Path $projectDir 'build\libs') -Filter '*-all.jar' -ErrorAction SilentlyContinue
    if ($jars) {
        Write-Host '--- JarVerify ---' -ForegroundColor Cyan
        & $java -cp $out JarVerify ($jars | ForEach-Object { $_.FullName })
        if ($LASTEXITCODE -ne 0) { $failed++ }
    } else {
        Write-Host '--- JarVerify skipped (no build/libs/*-all.jar yet) ---' -ForegroundColor DarkGray
    }

    # ------------------------------------------------------------------ the "checks never write" gate
    # Pairs with the snapshot taken before CityStructureGen ran: a regeneration that changes a tracked
    # preset is a FAILURE here, never a silent edit (README 5p/7b). It doubles as the determinism gate: the
    # six structure NBTs are rebuilt from their layouts on every run, so a byte difference means the
    # generator is not deterministic (or a preset was edited by hand).
    Write-Host '--- workspace-unchanged ---' -ForegroundColor Cyan
    $changedPresets = 0
    foreach ($entry in $presetHashesBefore.GetEnumerator()) {
        $file = Join-Path $trackedStructures $entry.Key
        if (-not (Test-Path $file)) {
            Write-Host "  [gate] $($entry.Key) disappeared during the run" -ForegroundColor Red
            $changedPresets++
            continue
        }
        $now = (Get-FileHash $file -Algorithm SHA256).Hash
        if ($now -ne $entry.Value) {
            $was = $entry.Value.Substring(0, 8)
            $is = $now.Substring(0, 8)
            $msg = "  [gate] " + $entry.Key + " changed during the run: " + $was + " -> " + $is + " - a gate must not rewrite the workspace"
            Write-Host $msg -ForegroundColor Red
            $changedPresets++
        }
    }
    if ($changedPresets -gt 0) {
        $failed++
    } else {
        Write-Host "  PASS  $($presetHashesBefore.Count) tracked structure preset(s) byte-identical after the run"
    }

    if ($failed -gt 0) { throw "$failed self-test(s) failed" }
    Write-Host 'all self-tests passed' -ForegroundColor Green
} finally {
    Pop-Location
}
