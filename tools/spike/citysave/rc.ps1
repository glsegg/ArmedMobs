# Runs one command on the isolated city-edit server over RCON and appends both the command and the
# raw server answer to commands.log, so every claim in the report has a reproducible trace.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Command,
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave',
    [int]$Port = 25585,
    [string]$Password = 'citysave'
)

$ErrorActionPreference = 'Stop'
$rcon = Join-Path $WorkDir 'rcon.ps1'
$log = Join-Path $WorkDir 'commands.log'

$answer = & powershell -NoProfile -ExecutionPolicy Bypass -File $rcon -Command $Command -Port $Port -Password $Password 2>&1 | Out-String
$answer = $answer.TrimEnd()

$entry = ">>> $Command`n$answer`n"
[System.IO.File]::AppendAllText($log, $entry, (New-Object System.Text.UTF8Encoding($false)))
Write-Output $entry
