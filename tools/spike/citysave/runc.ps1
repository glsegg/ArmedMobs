# Runs a file of commands over ONE RCON connection against the isolated city-edit server.
# Every command and its raw server answer is echoed to stdout and appended to commands.log,
# which is the evidence file for the report.
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave',
    [int]$Port = 25585,
    [string]$Password = 'citysave',
    [string]$RconHost = '127.0.0.1',
    [int]$TimeoutMs = 120000
)

$ErrorActionPreference = 'Stop'

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
        if ($n -le 0) { throw 'connection closed while reading header' }
        $got += $n
    }
    $len = [BitConverter]::ToInt32($hdr, 0)
    $data = New-Object byte[] $len
    $got = 0
    while ($got -lt $len) {
        $n = $stream.Read($data, $got, $len - $got)
        if ($n -le 0) { throw 'connection closed while reading body' }
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
Send-Packet $stream 1 3 $Password
$auth = Read-Packet $stream
if ($auth.Id -ne 1) { $client.Close(); Write-Error 'RCON auth refused'; exit 2 }

$log = Join-Path $WorkDir 'commands.log'
$lines = Get-Content -LiteralPath $File -Encoding UTF8
[System.IO.File]::AppendAllText($log, "########## batch: $File ##########`n", (New-Object System.Text.UTF8Encoding($false)))

foreach ($line in $lines) {
    $cmd = $line.Trim()
    if ($cmd.Length -eq 0 -or $cmd.StartsWith('#')) { continue }

    # Vanilla RCON does NOT append an end-of-response packet, so a plain "read until empty" blocks
    # until the socket timeout. Wait generously for the FIRST packet (a /fill can take a while) and
    # only ~1.5s for any follow-up packet, which is how a >4096-char answer arrives in pieces.
    $stream.ReadTimeout = $TimeoutMs
    Send-Packet $stream 2 2 $cmd
    $sb = New-Object System.Text.StringBuilder
    $first = $true
    while ($true) {
        if (-not $first) { $stream.ReadTimeout = 400 }
        try { $p = Read-Packet $stream } catch { break }
        if ($p.Body.Length -gt 0) { [void]$sb.Append($p.Body); $first = $false }
        else { break }
    }
    $out = $sb.ToString().TrimEnd()
    $entry = ">>> $cmd`n$out"
    # flushed immediately, so evidence survives even if this script is killed mid-batch
    [System.IO.File]::AppendAllText($log, $entry + "`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Output $entry
}

$client.Close()
[System.IO.File]::AppendAllText($log, "`n", (New-Object System.Text.UTF8Encoding($false)))
exit 0
