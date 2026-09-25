# Upload the local tree to GitHub through the REST Git Data API (blobs -> tree -> commit -> ref).
#
# What is uploaded: the blobs `git ls-files -s` names, read back through `git cat-file` - i.e. exactly the
# content of the local commit, not the worktree bytes (see the note above the blob loop).
# An empty repository is seeded first, because GitHub rejects blob creation in it with 409.
# The run ends with a verification pass: the remote tree is fetched recursively and diffed against the
# local index path by path, and any missing/extra/differing blob fails the run.
#
# Why not `git push`: on this machine the connection to github.com is intermittently reset
# (`curl 56 Recv failure: Connection was reset`, and a 21 s connect timeout on another attempt), while
# api.github.com answers reliably. The Git Data API touches only api.github.com, so it is the dependable
# path here.
#
# Token resolution order:
#   1. -Token <string>
#   2. Windows Credential Manager, target 'GitHub - https://api.github.com/<login>' (what GitHub Desktop
#      stores). NOTE the CredentialBlob is plain UTF-8 ASCII, NOT UTF-16, so it must be decoded as UTF-8.
#      `git credential fill` is NOT used by default: the configured helper is GCM (`manager`), which pops an
#      interactive browser/dialog when unauthenticated and hangs forever in a non-interactive shell.
#   3. -UseCredentialHelper switches to `git credential fill` (may hang; kept for other machines).
# The secret is never printed.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spike\work\gh_api_push.ps1 `
#       -Owner glsegg -Repo ArmedMobs -Branch main
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [Parameter(Mandatory = $true)][string]$Repo,
    [string]$Branch = 'main',
    [string]$Root = 'D:\deepseek\ArmedMobs',
    [string]$Message = 'Armed Mobs snapshot',
    [string]$Token = '',
    [string]$Login = 'glsegg',
    [string]$Parent = '',
    [switch]$Force,
    [switch]$UseCredentialHelper,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
# Read git's stdout as UTF-8 (the tracked tree has CJK file names). This is about decoding the child
# process, which is a different trap from encoding the request body - both have to be right.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$git = 'C:\Users\admin\AppData\Local\GitHubDesktop\app-3.6.6\resources\app\git\cmd\git.exe'
$api = "https://api.github.com/repos/$Owner/$Repo"
$cachePath = Join-Path $env:TEMP 'ghpush_blob_cache.txt'

# ---------------------------------------------------------------- token (never printed)
function Get-CredManToken {
    param([string]$Target)
    if (-not ('CredManPush' -as [type])) {
        Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CredManPush {
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
    $blob = [CredManPush]::ReadBlob($Target)
    if (-not $blob -or $blob.Length -eq 0) { return $null }
    # a GitHub token is ASCII; a UTF-16 blob would decode to control bytes here
    $s = [Text.Encoding]::UTF8.GetString($blob)
    foreach ($ch in $s.ToCharArray()) { if ([int]$ch -lt 32 -or [int]$ch -gt 126) { return $null } }
    return $s
}

if (-not $Token) {
    if ($UseCredentialHelper) {
        Write-Host 'token source: git credential fill'
        $cred = "protocol=https`nhost=github.com`n`n" | & $git credential fill 2>&1
        $Token = ($cred | Where-Object { $_ -match '^password=' }) -replace '^password=', ''
    } else {
        $target = "GitHub - https://api.github.com/$Login"
        $Token = Get-CredManToken -Target $target
        if ($Token) { Write-Host ("token source: Credential Manager '{0}'" -f $target) }
    }
}
if (-not $Token) { throw 'no token (Credential Manager had none; pass -Token or -UseCredentialHelper)' }

$headers = @{ 'User-Agent' = 'dsh-upload'; 'Authorization' = "Bearer $Token"; 'Accept' = 'application/vnd.github+json' }

function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body)
    $json = $null
    if ($null -ne $Body) { $json = $Body | ConvertTo-Json -Depth 12 -Compress }
    # The body is handed over as UTF-8 BYTES, never as a .NET string: Windows PowerShell 5.1 encodes a
    # string body with the ANSI code page, which silently turned the CJK paths (docs/指令与配置参考.docx
    # and friends) into '?' names on the first attempt at this upload.
    #
    # The assignment is deliberately NOT written as `$bytes = if (...) { GetBytes(...) } else { $null }`:
    # an if-statement's output goes through the pipeline, which ENUMERATES a byte[] into one element per
    # byte, leaving an Object[] that PowerShell then stringifies into "83 111 109 ..." - the request then
    # has no content field at all and GitHub answers 422 "Blob content missing_field". The type check
    # below is the tripwire for exactly that.
    $bytes = $null
    if ($json) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        if ($bytes -isnot [byte[]]) { throw "internal: the request body is $($bytes.GetType().Name), not byte[]" }
    }
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            if ($bytes) {
                return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 120
            }
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 120
        } catch {
            $code = $null
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($code -eq 401 -or $code -eq 403) { throw "GitHub API refused the token (HTTP $code): $($_.Exception.Message)" }
            if ($attempt -eq 4) { throw }
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
}

# Fetch a JSON document as raw bytes and decode it as UTF-8 ourselves; Invoke-RestMethod mangles
# non-ASCII path names when it has to guess the response encoding.
function Get-ApiJsonUtf8 {
    param([string]$Uri)
    $resp = Invoke-WebRequest -Uri $Uri -Headers $headers -UseBasicParsing -TimeoutSec 120
    return ([Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json)
}

# ---------------------------------------------------------------- sanity: token identity
$me = Invoke-Api -Method Get -Uri 'https://api.github.com/user'
Write-Host ("authenticated as {0}" -f $me.login)
if ($me.login -ne $Owner) { Write-Host ("WARNING: token belongs to '{0}', target owner is '{1}'" -f $me.login, $Owner) }

# ---------------------------------------------------------------- branch state, and the empty-repo bootstrap
# GitHub answers POST /git/blobs with 409 "Git Repository is empty" until the repository has one commit,
# so a brand new repository has to be seeded through the contents API first (that call is allowed to make
# the first commit) and the commit it returns becomes the parent of everything below.
$parents = @()
$forceRef = [bool]$Force
if ($Parent) {
    # Explicit base: used to replace a bad commit instead of stacking on top of it (the ref then has to be
    # moved with force, because the new commit is not a descendant of the current head).
    $parents = @($Parent)
    $forceRef = $true
    Write-Host ("explicit parent: {0} (the ref will be moved with force)" -f $Parent)
} else {
    try {
        $ref = Invoke-Api -Method Get -Uri "$api/git/ref/heads/$Branch"
        if ($ref.object.sha) {
            $parents = @($ref.object.sha)
            Write-Host ("existing branch head: {0} (will be the parent)" -f $ref.object.sha)
        }
    } catch { Write-Host 'branch does not exist yet (first push)' }
}

if ($parents.Count -eq 0) {
    $bootRel = 'README.md'
    $bootAbs = Join-Path $Root 'README.md'
    if (-not (Test-Path -LiteralPath $bootAbs)) { throw 'cannot bootstrap an empty repository: README.md is missing' }
    $boot = Invoke-Api -Method Put -Uri "$api/contents/$bootRel" -Body @{
        message = 'Armed Mobs: seed the repository'
        content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($bootAbs))
        branch  = $Branch
    }
    $parents = @($boot.commit.sha)
    Write-Host ("bootstrap commit: {0} (empty repositories reject blob creation)" -f $boot.commit.sha)
}

# ---------------------------------------------------------------- the file list, from the INDEX
# The blobs come from the index, NOT from the working tree: `core.autocrlf` is on, so 49 of the tracked
# text files sit in the worktree with CRLF while git's blobs hold LF. Publishing worktree bytes would put
# those 49 files in the repository in a form that does not match the local commit (and that a clone with
# autocrlf would smudge into CRLF CRLF), so every blob is read back byte-exactly through `git cat-file`.
Push-Location $Root
try {
    $dirty = & $git status --porcelain
    if ($dirty) {
        Write-Host 'WARNING: working tree has uncommitted changes. This upload follows the INDEX, so anything'
        Write-Host '         not added (and committed) is NOT published:'
        $dirty | Select-Object -First 20 | ForEach-Object { Write-Host ("   " + $_) }
    }
    $indexLines = & $git -c core.quotePath=false ls-files -s
} finally { Pop-Location }

$entries = New-Object System.Collections.Generic.List[object]
foreach ($line in $indexLines) {
    if ($line -match '^(\d{6})\s+([0-9a-f]{40})\s+\d+\t(.+)$') {
        $entries.Add([pscustomobject]@{ mode = $Matches[1]; sha = $Matches[2]; path = $Matches[3] })
    }
}
if ($entries.Count -eq 0) { throw 'git ls-files -s returned nothing' }
Write-Host ("files to upload: {0}" -f $entries.Count)

# ---------------------------------------------------------------- one batched, binary-safe read of them
$shaFile = Join-Path $env:TEMP 'ghpush_shas.txt'
$binFile = Join-Path $env:TEMP 'ghpush_blobs.bin'
$uniqueShas = @($entries | ForEach-Object { $_.sha } | Select-Object -Unique)
Set-Content -Path $shaFile -Value $uniqueShas -Encoding ascii
if (Test-Path $binFile) { Remove-Item $binFile -Force }
# cmd's redirection (not PowerShell's) so the stream stays binary
& $env:ComSpec /c ('"{0}" cat-file --batch < "{1}" > "{2}"' -f $git, $shaFile, $binFile)
if (-not (Test-Path $binFile)) { throw 'git cat-file --batch produced no output' }
$stream = [IO.File]::ReadAllBytes($binFile)
$blobBytes = @{}
$pos = 0
while ($pos -lt $stream.Length) {
    $nl = $pos
    while ($nl -lt $stream.Length -and $stream[$nl] -ne 10) { $nl++ }
    $header = [Text.Encoding]::ASCII.GetString($stream, $pos, $nl - $pos)
    $parts = $header -split ' '
    if ($parts.Count -lt 3 -or $parts[1] -ne 'blob') { throw "unexpected git cat-file header: '$header'" }
    $size = [int]$parts[2]
    $start = $nl + 1
    $buf = New-Object byte[] $size
    [Array]::Copy($stream, $start, $buf, 0, $size)
    $blobBytes[$parts[0]] = $buf
    $pos = $start + $size + 1
}
Write-Host ("index blobs read back: {0} object(s), {1:N1} MB" -f $blobBytes.Count, ($stream.Length / 1MB))
foreach ($e in $entries) { if (-not $blobBytes.ContainsKey($e.sha)) { throw "no bytes read for blob $($e.sha) ($($e.path))" } }

# ---------------------------------------------------------------- blobs (a sha already uploaded is skipped)
$cache = @{}
if (Test-Path $cachePath) { foreach ($l in (Get-Content $cachePath)) { if ($l.Trim()) { $cache[$l.Trim()] = $true } } }
$tree = New-Object System.Collections.Generic.List[object]
$done = 0
$reused = 0
$bytes = 0
foreach ($e in $entries) {
    $raw = $blobBytes[$e.sha]
    $bytes += $raw.Length
    if ($cache.ContainsKey($e.sha)) {
        $reused++
    } else {
        $body = @{ content = [Convert]::ToBase64String($raw); encoding = 'base64' }
        try {
            $blob = Invoke-Api -Method Post -Uri "$api/git/blobs" -Body $body
        } catch {
            # Name the file that failed: "Blob content missing_field" says nothing about which blob.
            Write-Host ("BLOB FAILED  {0}" -f $e.path) -ForegroundColor Red
            Write-Host ("  raw={0} B  base64={1} chars  body={2} chars  local sha={3}" -f `
                $raw.Length, $body.content.Length, ($body | ConvertTo-Json -Compress).Length, $e.sha) -ForegroundColor Red
            throw
        }
        if ($blob.sha -ne $e.sha) { throw "GitHub stored $($blob.sha) for $($e.path), local blob is $($e.sha)" }
        $cache[$e.sha] = $true
        if ($done % 25 -eq 0) { $cache.Keys | Set-Content -Path $cachePath -Encoding ascii }
    }
    # the mode comes from the index, so the executable bit is preserved too
    $tree.Add(@{ path = $e.path; mode = $e.mode; type = 'blob'; sha = $e.sha })
    $done += 1
    if ($done % 50 -eq 0) { Write-Host ("  {0}/{1} blobs ({2:N1} MB, {3} reused)" -f $done, $entries.Count, ($bytes / 1MB), $reused) }
}
$cache.Keys | Set-Content -Path $cachePath -Encoding ascii
Write-Host ("blobs resolved: {0} ({1:N1} MB, {2} reused from cache)" -f $done, ($bytes / 1MB), $reused)

if ($VerifyOnly) { Write-Host 'VerifyOnly: stopping before tree creation'; exit 0 }

# ---------------------------------------------------------------- tree -> commit -> ref
$newTree = Invoke-Api -Method Post -Uri "$api/git/trees" -Body @{ tree = $tree }
Write-Host ("tree: {0}" -f $newTree.sha)

$commit = Invoke-Api -Method Post -Uri "$api/git/commits" -Body @{
    message = $Message
    tree    = $newTree.sha
    parents = $parents
}
Write-Host ("commit: {0}" -f $commit.sha)

if ($parents.Count -eq 0) {
    $refNew = Invoke-Api -Method Post -Uri "$api/git/refs" -Body @{ ref = "refs/heads/$Branch"; sha = $commit.sha }
    Write-Host ("ref created: {0}" -f $refNew.ref)
} else {
    $refNew = Invoke-Api -Method Patch -Uri "$api/git/refs/heads/$Branch" -Body @{ sha = $commit.sha; force = $forceRef }
    Write-Host ("ref updated: {0}" -f $refNew.ref)
}

Write-Host ''
Write-Host ("DONE  commit={0}  tree={1}  files={2}  bytes={3}" -f $commit.sha, $newTree.sha, $done, $bytes)
Write-Host ("https://github.com/{0}/{1}/commit/{2}" -f $Owner, $Repo, $commit.sha)

# ---------------------------------------------------------------- verify: remote tree vs local index
$recursive = Get-ApiJsonUtf8 -Uri "$api/git/trees/$($newTree.sha)?recursive=1"
$remote = @{}
foreach ($e in $recursive.tree) { if ($e.type -eq 'blob') { $remote[$e.path] = $e.sha } }
$local = @{}
foreach ($e in $entries) { $local[$e.path] = $e.sha }
$missing = @($local.Keys | Where-Object { -not $remote.ContainsKey($_) })
$extra = @($remote.Keys | Where-Object { -not $local.ContainsKey($_) })
$mismatch = @($local.Keys | Where-Object { $remote.ContainsKey($_) -and $remote[$_] -ne $local[$_] })
Write-Host ''
Write-Host ("VERIFY  local={0} remote={1} missing={2} extra={3} shaMismatch={4}" -f `
    $local.Count, $remote.Count, $missing.Count, $extra.Count, $mismatch.Count)
foreach ($p in ($missing | Select-Object -First 10)) { Write-Host ("  MISSING " + $p) }
foreach ($p in ($extra | Select-Object -First 10)) { Write-Host ("  EXTRA   " + $p) }
foreach ($p in ($mismatch | Select-Object -First 10)) { Write-Host ("  DIFFERS " + $p) }
if ($recursive.truncated) { Write-Host 'VERIFY FAILED (the recursive tree listing was truncated)'; exit 7 }
if ($missing.Count -or $extra.Count -or $mismatch.Count) { Write-Host 'VERIFY FAILED'; exit 7 }
Write-Host 'VERIFY OK (remote tree identical to the local index)'
