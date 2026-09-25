$ErrorActionPreference = 'Stop'
$Plan = 'tools/spike/work/voice_plan.json'
$projectDir = 'D:\deepseek\ArmedMobs'
$planPath = Join-Path $projectDir $Plan
$plan = Get-Content $planPath -Raw | ConvertFrom-Json
Write-Host ("afterParse pools=" + $plan.pools.Count)
$OnlyPool = 'elite_grenade'
$n = 0
foreach ($pool in $plan.pools) {
    Write-Host ("see " + $pool.pool)
    if ($OnlyPool -and $pool.pool -ne $OnlyPool) { continue }
    $n++
    Write-Host ("  MATCH " + $pool.pool + " wanted=" + $pool.wanted + " cand=" + $pool.candidates.Count)
}
Write-Host ("matched=" + $n)
