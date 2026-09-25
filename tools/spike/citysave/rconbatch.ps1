# Batch RCON driver: ONE connection, ONE auth, then every command in order - and a hard read timeout
# per command so a chatty or slow command can never hang the whole run.
#
#   powershell -File tools/spike/citysave/rconbatch.ps1 -Commands "cmd one","cmd two"
#   powershell -File tools/spike/citysave/rconbatch.ps1 -File tools/spike/citysave/batches/x.txt
#
# The single-command rcon.ps1 is fine for interactive probing; this one exists because a scenario is a
# *sequence* (summon, tag, wait, query) and reconnecting for every step is where it kept getting stuck.
param(
    [string[]]$Commands = @(),
    [string]$File,
    [string]$RconHost = '127.0.0.1',
    [int]$Port = 25585,
    [string]$Password = 'citysave',
    [int]$TimeoutMs = 8000,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

if ($File) {
    $Commands = Get-Content $File | Where-Object { $_.Trim() -ne '' -and -not $_.TrimStart().StartsWith('#') }
}

function Send-Packet($stream, [int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $buf = New-Object byte[] ($len + 4)
    [BitConverter]::GetBytes([int]$len).CopyTo($buf, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($buf, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($buf, 8)
    $bodyBytes.CopyTo($buf, 12)
    $buf[$len + 2] = 0
    $buf[$len + 3] = 0
    $stream.Write($buf, 0, $buf.Length)
    $stream.Flush()
}

function Read-Packet($stream) {
    $hdr = New-Object byte[] 4
    $got = 0
    while ($got -lt 4) {
        $n = $stream.Read($hdr, $got, 4 - $got)
        if ($n -le 0) { throw 'connection closed' }
        $got += $n
    }
    $len = [BitConverter]::ToInt32($hdr, 0)
    if ($len -le 0 -or $len -gt 4MB) { throw "bad packet length $len" }
    $data = New-Object byte[] $len
    $got = 0
    while ($got -lt $len) {
        $n = $stream.Read($data, $got, $len - $got)
        if ($n -le 0) { throw 'connection closed' }
        $got += $n
    }
    return @{
        Id   = [BitConverter]::ToInt32($data, 0)
        Type = [BitConverter]::ToInt32($data, 4)
        Body = [System.Text.Encoding]::UTF8.GetString($data, 8, $len - 10)
    }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($RconHost, $Port)
$client.ReceiveTimeout = $TimeoutMs
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutMs
$stream.WriteTimeout = $TimeoutMs

Send-Packet $stream 1 3 $Password
$auth = Read-Packet $stream
if ($auth.Id -ne 1) {
    $client.Close()
    Write-Error "RCON auth refused (response id $($auth.Id))"
    exit 2
}

$id = 10
foreach ($command in $Commands) {
    Send-Packet $stream $id 2 $command
    $sb = New-Object System.Text.StringBuilder
    while ($true) {
        try {
            $packet = Read-Packet $stream
        } catch {
            [void]$sb.Append("<no response: $($_.Exception.Message)>")
            break
        }
        if ($packet.Body.Length -gt 0) { [void]$sb.Append($packet.Body) }
        else { break }
    }
    if (-not $Quiet) { Write-Output ">>> $command`n$($sb.ToString().TrimEnd())" }
    $id++
    Start-Sleep -Milliseconds 120
}

$client.Close()
exit 0
