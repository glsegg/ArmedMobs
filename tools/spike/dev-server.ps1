# Starts, stops or restarts the 塔科夫Scav dev server and waits until RCON answers.
#
#   .\tools\spike\dev-server.ps1 -Restart
#   .\tools\spike\dev-server.ps1            # start it if it is not running
#   .\tools\spike\dev-server.ps1 -Stop
#
# Ports are 25566 (game) and 25577 (RCON), deliberately NOT the defaults: this machine already has
# other Minecraft instances, and "FAILED TO BIND TO PORT" looks exactly like a crashed server.
param(
    [switch]$Restart,
    [switch]$Stop,
    [int]$TimeoutSeconds = 420,
    [string]$Rcon = 'D:\deepseek\ArmedMobs\tools\rcon.js',
    [string]$Launch = 'D:\deepseek\ArmedMobs\tools\spike\run-server.cmd',
    [string]$ConsoleLog = 'D:\deepseek\ArmedMobs\run\server-console.log'
)

$ErrorActionPreference = 'Stop'
$GamePort = 25566
$RconPort = 25577
$Password = 'dsh123'

function Get-Rcon([string]$command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& node $Rcon 127.0.0.1 $RconPort $Password $command 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-Rcon {
    return (Get-Rcon 'list') -match 'players online'
}

function Test-PortBusy([int]$port) {
    $line = netstat -ano | Select-String -Pattern ":$port\s" | Select-Object -First 1
    return [bool]$line
}

function Wait-PortFree([int]$port, [int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (-not (Test-PortBusy $port)) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# A previous run that was killed mid-save leaves a JVM holding the port; nothing else on this machine
# listens on 25566/25577, so killing those PIDs by port is safe and saves a lot of confusion.
function Stop-StrayServer {
    foreach ($port in @($GamePort, $RconPort)) {
        $pids = netstat -ano | Select-String -Pattern ":$port\s+.*LISTENING" |
            ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
        foreach ($processId in $pids) {
            if ($processId -match '^\d+$') {
                Write-Host "killing stray process $processId holding port $port" -ForegroundColor Yellow
                Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

if ($Stop -or $Restart) {
    if (Test-Rcon) {
        Write-Host 'stopping the dev server (RCON stop)' -ForegroundColor Cyan
        Get-Rcon 'stop' | Out-Null
    }
    if (-not (Wait-PortFree $GamePort $TimeoutSeconds)) {
        Write-Host 'the server did not stop in time; killing whatever holds the port' -ForegroundColor Yellow
        Stop-StrayServer
        Wait-PortFree $GamePort 30 | Out-Null
    }
    for ($i = 0; $i -lt 30; $i++) {
        if (-not (Test-Rcon)) { break }
        Start-Sleep -Seconds 1
    }
    Write-Host "server stopped, port $GamePort is free and RCON is gone" -ForegroundColor Cyan
    if ($Stop) { exit 0 }
}

Stop-StrayServer

if (-not (Test-PortBusy $GamePort)) {
    Write-Host "starting $Launch" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path (Split-Path $ConsoleLog -Parent) | Out-Null
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$Launch`" > `"$ConsoleLog`" 2>&1" `
        -WorkingDirectory 'D:\deepseek\ArmedMobs' -WindowStyle Hidden
} else {
    Write-Host "a server is already listening on $GamePort" -ForegroundColor Cyan
}

for ($i = 0; $i -lt $TimeoutSeconds; $i += 5) {
    if (Test-Rcon) {
        Write-Host "server ready after $($i + 5)s" -ForegroundColor Green
        exit 0
    }
    if ($i -gt 0 -and $i % 60 -eq 0) {
        Write-Host "... still waiting ($i s), last console lines:" -ForegroundColor DarkGray
        if (Test-Path $ConsoleLog) { Get-Content $ConsoleLog -Tail 3 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
    }
    Start-Sleep -Seconds 5
}
Write-Host 'server did not answer on RCON in time; last console lines:' -ForegroundColor Red
if (Test-Path $ConsoleLog) { Get-Content $ConsoleLog -Tail 25 }
exit 1
