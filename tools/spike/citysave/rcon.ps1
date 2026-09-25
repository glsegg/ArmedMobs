param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Command,
    [string]$RconHost = '127.0.0.1',
    [int]$Port = 25585,
    [string]$Password = 'citysave',
    [int]$TimeoutMs = 20000
)

# Minimal Source-RCON client (login + one command), so the city-edit server can be driven and its
# answers captured as raw evidence without ever touching the user's game directory.
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
if ($auth.Id -ne 1) {
    $client.Close()
    Write-Error "RCON auth refused (response id $($auth.Id))"
    exit 2
}

Send-Packet $stream 2 2 $Command
$sb = New-Object System.Text.StringBuilder
while ($true) {
    try { $p = Read-Packet $stream } catch { break }
    if ($p.Body.Length -gt 0) { [void]$sb.Append($p.Body) }
    if ($p.Body.Length -eq 0) { break }
}
$client.Close()
Write-Output $sb.ToString()
exit 0
