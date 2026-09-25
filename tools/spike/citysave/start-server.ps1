# Starts the isolated headless Forge 1.20.1 city-edit server. Never touches the user's gameDir.
#
# java.exe is launched directly (no cmd wrapper: cmd's /c quote-stripping mangles a quoted
# "C:\Program Files\..." executable path). stdin is an empty file, so the console reader just
# sees EOF and the server keeps running; everything is driven over RCON instead.
param(
    [string]$ServerDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server',
    [string]$Log = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server-console.log',
    [string]$ErrLog = 'D:\deepseek\ArmedMobs\tools\spike\citysave\server-stderr.log',
    [string]$Java = 'C:\Program Files\Java\jdk-21.0.12\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$emptyStdin = Join-Path (Split-Path $Log -Parent) 'empty-stdin.txt'
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
Write-Output "  exe : $Java"
Write-Output "  args: $($arguments -join ' ')"
Write-Output "  cwd : $ServerDir"
Write-Output "  log : $Log"
