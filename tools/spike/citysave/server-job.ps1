# Runs the isolated city-edit server in the foreground of a BACKGROUND JOB and waits for it.
#
# Why a job instead of Start-Process + return: a foreground tool call that times out takes its whole
# child process tree with it, which silently killed the server twice. As a tracked background job the
# server keeps running until it is stopped deliberately over RCON.
param(
    [string]$ServerDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server',
    [string]$Log = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server-console.log',
    [string]$ErrLog = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server-stderr.log',
    [string]$Java = 'C:\Program Files\Java\jdk-21.0.12\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$emptyStdin = 'D:\deepseek\ArmedMobs\tools\spike\citysave\empty-stdin.txt'
if (-not (Test-Path $emptyStdin)) { [System.IO.File]::WriteAllText($emptyStdin, '') }

$arguments = @(
    '@user_jvm_args.txt',
    '@libraries/net/minecraftforge/forge/1.20.1-47.4.3/win_args.txt',
    'nogui'
)
$proc = Start-Process -FilePath $Java -ArgumentList $arguments `
    -WorkingDirectory $ServerDir `
    -RedirectStandardInput $emptyStdin `
    -RedirectStandardOutput $Log `
    -RedirectStandardError $ErrLog `
    -WindowStyle Hidden -PassThru
Write-Output "launched java pid $($proc.Id)"
$proc.WaitForExit()
Write-Output "java exited with code $($proc.ExitCode)"
