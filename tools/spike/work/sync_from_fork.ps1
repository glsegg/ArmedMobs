# Port the fork branch (EdDYON/Egg-ArmedMobs 4aea44b4) onto the local tree, byte for byte.
#
# The fork's HEAD is "my latest upstream (7c37511f) + their local fixes", so every path in the
# compare(7c37511f...4aea44b4) range is simply their version of that path. Sync = write their blob for
# every added/modified path and delete every removed one. Each file is verified against the blob sha the
# API reported, so a network hiccup or a text-mode write cannot land silently.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spike\work\sync_from_fork.ps1 -WhatIf
param(
    [string]$Owner = 'EdDYON',
    [string]$Repo = 'Egg-ArmedMobs',
    [string]$Base = '7c37511f69ad18b92cf431a86bef4e9969029734',
    [string]$Head = '4aea44b4e0072d994c32230e66cdf7728397d06c',
    [string]$Root = 'D:\deepseek\ArmedMobs',
    [string]$Login = 'glsegg',
    [string]$Token = '',
    [switch]$WhatIf
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Get-CredManToken {
    param([string]$TargetName)
    if (-not ('CredManSync' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CredManSync {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  public struct CREDENTIAL {
    public uint Flags; public uint Type;
    public IntPtr TargetName; public IntPtr Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public uint CredentialBlobSize; public IntPtr CredentialBlob;
    public uint Persist; public uint AttributeCount; public IntPtr Attributes;
    public IntPtr TargetAlias; public IntPtr UserName;
  }
  [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  public static extern bool CredReadW(string target, uint type, uint flags, out IntPtr cred);
  [DllImport("advapi32.dll")] public static extern void CredFree(IntPtr c);
  public static byte[] ReadBlob(string target) {
    IntPtr p;
    if (!CredReadW(target, 1, 0, out p)) return null;
    var c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL));
    var b = new byte[c.CredentialBlobSize];
    if (c.CredentialBlobSize > 0) Marshal.Copy(c.CredentialBlob, b, 0, (int)c.CredentialBlobSize);
    CredFree(p);
    return b;
  }
}
'@
    }
    $blob = [CredManSync]::ReadBlob($TargetName)
    if (-not $blob -or $blob.Length -eq 0) { return $null }
    $text = [Text.Encoding]::UTF8.GetString($blob)
    foreach ($ch in $text.ToCharArray()) { if ([int]$ch -lt 32 -or [int]$ch -gt 126) { return $null } }
    return $text
}

if (-not $Token) { $Token = Get-CredManToken -TargetName "GitHub - https://api.github.com/$Login" }
if (-not $Token) { throw 'no token' }
$headers = @{ 'User-Agent' = 'dsh-sync'; 'Authorization' = "Bearer $Token"; 'Accept' = 'application/vnd.github+json' }
$git = 'C:\Users\admin\AppData\Local\GitHubDesktop\app-3.6.6\resources\app\git\cmd\git.exe'
$api = "https://api.github.com/repos/$Owner/$Repo"
$compare = "$api/compare/$Base...$Head"

$rows = @()
for ($page = 1; $page -le 6; $page++) {
    $resp = Invoke-RestMethod -Method Get -Uri "$compare`?per_page=100&page=$page" -Headers $headers -TimeoutSec 180
    if (-not $resp.files -or $resp.files.Count -eq 0) { break }
    foreach ($f in $resp.files) {
        $rows += [pscustomobject]@{ Status = $f.status; Path = $f.filename; Sha = $f.sha }
    }
    if ($resp.files.Count -lt 100) { break }
}
Write-Host ("changed paths in {0}...{1}: {2}" -f $Base.Substring(0, 7), $Head.Substring(0, 7), $rows.Count)

$written = 0
$deleted = 0
$mismatch = @()
foreach ($row in $rows) {
    $target = Join-Path $Root ($row.Path -replace '/', '\')
    if ($row.Status -eq 'removed') {
        if (Test-Path -LiteralPath $target) {
            if (-not $WhatIf) { Remove-Item -LiteralPath $target -Force }
            Write-Host ("  delete  {0}" -f $row.Path)
            $deleted++
        }
        continue
    }
    $blob = Invoke-RestMethod -Method Get -Uri "$api/git/blobs/$($row.Sha)" -Headers $headers -TimeoutSec 300
    if ($blob.encoding -ne 'base64') { throw "unexpected blob encoding for $($row.Path): $($blob.encoding)" }
    $bytes = [Convert]::FromBase64String(($blob.content -replace '\s', ''))
    if ($WhatIf) {
        Write-Host ("  would write {0} ({1} bytes)" -f $row.Path, $bytes.Length)
        $written++
        continue
    }
    $dir = Split-Path $target -Parent
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllBytes($target, $bytes)
    $local = (& $git -C $Root hash-object -- $target).Trim()
    if ($local -ne $row.Sha) {
        $mismatch += ("{0}: local {1} != remote {2}" -f $row.Path, $local, $row.Sha)
        Write-Host ("  MISMATCH {0}  local {1} remote {2}" -f $row.Path, $local, $row.Sha)
    } else {
        Write-Host ("  ok      {0} ({1} bytes)" -f $row.Path, $bytes.Length)
    }
    $written++
}

Write-Host ''
Write-Host ("written {0}, deleted {1}, mismatched {2}" -f $written, $deleted, $mismatch.Count)
if ($mismatch.Count -gt 0) {
    $mismatch | ForEach-Object { Write-Host ("  " + $_) }
    throw 'the sync landed files whose content does not match the remote blob'
}
Write-Host 'SYNC OK: every touched path is byte-identical to the fork commit'
