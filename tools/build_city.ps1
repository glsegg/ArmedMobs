# Builds one city preset on a running dev server, block by block, over RCON.
#
#   .\tools\build_city.ps1 [-Variant city_a] [-X 100] [-Y -60] [-Z 100] [-SkipPlatform]
#
# The commands come from tools/spike/work/<variant>_commands.txt (and <variant>_platform.txt), which
# tools/spike/CityStructureGen.java writes from tools/city-layout*.json - so the world, the .nbt
# structure and the sandbox build are always the same city. Regenerate all of them with:
#
#   .\tools\spike\selftest.ps1
#
# (or just: java -cp tools/spike/out CityStructureGen)
#
# The platform file is the flat 64x64 stone slab plus the air clear above it, in the same coordinate
# space as the city: it is sent first, so the city always lands on clean ground. -SkipPlatform is for
# stacking a variant on a platform that is already there.
param(
    [string]$Variant = 'city_small',
    [int]$X = 100,
    [int]$Y = -60,
    [int]$Z = 100,
    [switch]$SkipPlatform,
    [string]$Rcon = "$PSScriptRoot\rcon.js",
    [string]$Password = 'dsh123',
    [int]$Port = 25577
)

$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$node = if (Test-Path 'D:\生图\node.exe') { 'D:\生图\node.exe' } else { 'node' }

# The generator writes every city with its own origin at 0,0,0. Shift every coordinate to the requested
# spot with a block of sed-style regex, so the RCON build can go anywhere in the world.
function Shift-Commands {
    param([string]$In, [string]$Out)
    $raw = Get-Content $In
    $shifted = New-Object System.Collections.Generic.List[string]
    foreach ($line in $raw) {
        # Block entities (a spawner's SpawnData, a chest's LootTable) arrive as `/data merge block x y z {...}`
        # right after the setblock that placed the block, so the same shift has to move their 3 coordinates.
        if ($line -match '^/data\s+merge\s+block\s+(.*)$') {
            $parts = $Matches[1] -split '\s+', 4
            $parts[0] = [string]([int]$parts[0] + $X)
            $parts[1] = [string]([int]$parts[1] + $Y)
            $parts[2] = [string]([int]$parts[2] + $Z)
            $shifted.Add('/data merge block ' + ($parts -join ' '))
            continue
        }
        if ($line -match '^/(setblock|fill)\s+(.*)$') {
            $verb = $Matches[1]
            $rest = $Matches[2]
            # trailing block id (may contain [state]) is left alone: only the first 3 (setblock) or 6
            # (fill) whitespace separated numbers are shifted.
            $parts = $rest -split '\s+'
            $coords = if ($verb -eq 'setblock') { 3 } else { 6 }
            for ($i = 0; $i -lt $coords; $i += 3) {
                $parts[$i] = [string]([int]$parts[$i] + $X)
                $parts[$i + 1] = [string]([int]$parts[$i + 1] + $Y)
                $parts[$i + 2] = [string]([int]$parts[$i + 2] + $Z)
            }
            $shifted.Add("/$verb " + ($parts -join ' '))
        }
    }
    Set-Content -Path $Out -Value $shifted -Encoding ASCII
    return $shifted.Count
}

$commandsFile = Join-Path $projectDir "tools\spike\work\${Variant}_commands.txt"
$platformFile = Join-Path $projectDir "tools\spike\work\${Variant}_platform.txt"
if (-not (Test-Path $commandsFile)) {
    throw "$commandsFile is missing - run .\tools\spike\selftest.ps1 (or java -cp tools/spike/out CityStructureGen)"
}

if (-not $SkipPlatform) {
    if (-not (Test-Path $platformFile)) {
        throw "$platformFile is missing - run the generator again"
    }
    $platformShifted = Join-Path $projectDir "tools\spike\work\${Variant}_platform_shifted.txt"
    $platformCount = Shift-Commands -In $platformFile -Out $platformShifted
    Write-Host "clearing the plot at $X $Y $Z ($platformCount commands)" -ForegroundColor Cyan
    & $node $Rcon --file $Password $platformShifted
    if ($LASTEXITCODE -ne 0) { throw 'the RCON platform build failed' }
}

$shifted = Join-Path $projectDir "tools\spike\work\${Variant}_commands_shifted.txt"
$count = Shift-Commands -In $commandsFile -Out $shifted
Write-Host "building $Variant at $X $Y $Z ($count commands)" -ForegroundColor Cyan

& $node $Rcon --file $Password $shifted
if ($LASTEXITCODE -ne 0) { throw 'the RCON build failed' }

Write-Host "$Variant built. Verify the spawn gate with:" -ForegroundColor Green
Write-Host "  $node tools/rcon.js 127.0.0.1 $Port $Password `"/tarkovscav city add $Variant 40`""
Write-Host "  $node tools/rcon.js 127.0.0.1 $Port $Password `"/tarkovscav city test $X $Y $Z`""
