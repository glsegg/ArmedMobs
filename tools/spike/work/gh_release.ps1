# Create the GitHub tag + release for Armed Mobs and attach ONE asset, through the REST API only.
#
# Why not the Git Data API uploader: tags and releases live on different endpoints
# (git/refs, releases, uploads.github.com) and the release asset is a binary that must be posted raw.
#
# Idempotent by design:
#   * an existing tag is never moved - if refs/tags/<Tag> points somewhere else, the run FAILS loudly;
#   * an existing release for the tag is reused, not replaced;
#   * an asset that is already there is only verified (size + digest), never re-uploaded.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spike\work\gh_release.ps1 `
#       -Owner glsegg -Repo ArmedMobs -Tag v0.1.0 -Target <commit sha> -Title "..." `
#       -BodyFile <utf8.md> -Asset <path to jar>
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$Title,
    [Parameter(Mandatory = $true)][string]$BodyFile,
    [string]$Asset = '',
    [string]$AssetName = '',
    [string]$Login = 'glsegg',
    [string]$Token = '',
    [switch]$Prerelease,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Get-CredManToken {
    param([string]$TargetName)
    if (-not ('CredManRel' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CredManRel {
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
    $blob = [CredManRel]::ReadBlob($TargetName)
    if (-not $blob -or $blob.Length -eq 0) { return $null }
    # The CredentialBlob is plain UTF-8 (GitHub Desktop stores it that way), NOT UTF-16.
    $text = [Text.Encoding]::UTF8.GetString($blob)
    foreach ($ch in $text.ToCharArray()) { if ([int]$ch -lt 32 -or [int]$ch -gt 126) { return $null } }
    return $text
}

if (-not $Token) {
    # NOTE the name: PowerShell variables are case-insensitive, so a local `$target` here would silently
    # OVERWRITE the -Target sha parameter of this script (that mistake sent the CredMan target name to the
    # tag API and produced "only 38 were supplied").
    $credTarget = "GitHub - https://api.github.com/$Login"
    $Token = Get-CredManToken -TargetName $credTarget
    if ($Token) { Write-Host ("token source: Credential Manager '{0}'" -f $credTarget) }
}
if (-not $Token) { throw 'no token (Credential Manager had none; pass -Token)' }

$headers = @{ 'User-Agent' = 'dsh-release'; 'Authorization' = "Bearer $Token"; 'Accept' = 'application/vnd.github+json' }
$api = "https://api.github.com/repos/$Owner/$Repo"

function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body, [int[]]$AllowCodes = @())
    $bytes = $null
    if ($null -ne $Body) {
        # UTF-8 bytes, never a .NET string: Windows PowerShell 5.1 would encode a string body with the ANSI
        # code page and turn the CJK release notes into '?' characters.
        $bytes = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))
    }
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            if ($bytes) {
                return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -Body $bytes `
                    -ContentType 'application/json; charset=utf-8' -TimeoutSec 180
            }
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 180
        } catch {
            $code = $null
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($AllowCodes -contains $code) { return $null }
            if ($code -eq 401 -or $code -eq 403) { throw "GitHub refused the token (HTTP $code): $($_.Exception.Message)" }
            if ($attempt -eq 4) { throw }
            Write-Host ("  retry {0} after HTTP {1}" -f $attempt, $code)
            Start-Sleep -Seconds (3 * $attempt)
        }
    }
}

# ---------------------------------------------------------------------------------------------
Write-Host "1. the tag"
$ref = Invoke-Api -Method Get -Uri "$api/git/ref/tags/$Tag" -AllowCodes @(404)
if ($ref) {
    if ($ref.object.sha -ne $Target) {
        throw "refs/tags/$Tag already points at $($ref.object.sha), not at $Target - refusing to move a published tag"
    }
    Write-Host ("  tag {0} already exists at {1} (correct target)" -f $Tag, $Target)
} elseif ($VerifyOnly) {
    throw "refs/tags/$Tag does not exist and -VerifyOnly was given"
} else {
    $created = Invoke-Api -Method Post -Uri "$api/git/refs" -Body @{ ref = "refs/tags/$Tag"; sha = $Target }
    Write-Host ("  created refs/tags/{0} -> {1}" -f $Tag, $created.object.sha)
}

# ---------------------------------------------------------------------------------------------
Write-Host "2. the release"
$release = Invoke-Api -Method Get -Uri "$api/releases/tags/$Tag" -AllowCodes @(404)
$body = [IO.File]::ReadAllText($BodyFile, [Text.Encoding]::UTF8)
Write-Host ("  release notes: {0} ({1} chars of UTF-8)" -f (Split-Path $BodyFile -Leaf), $body.Length)
if ($release) {
    Write-Host ("  release {0} already exists (id {1}, {2} asset(s)) - reusing it" -f $release.tag_name, $release.id, $release.assets.Count)
    if (-not $VerifyOnly) {
        $release = Invoke-Api -Method Patch -Uri "$api/releases/$($release.id)" `
            -Body @{ name = $Title; body = $body; prerelease = [bool]$Prerelease; draft = $false }
        Write-Host '  notes and title updated in place'
    }
} elseif ($VerifyOnly) {
    throw "no release for tag $Tag and -VerifyOnly was given"
} else {
    $release = Invoke-Api -Method Post -Uri "$api/releases" -Body @{
        tag_name         = $Tag
        target_commitish = $Target
        name             = $Title
        body             = $body
        draft            = $false
        prerelease       = [bool]$Prerelease
    }
    Write-Host ("  created release id {0}: {1}" -f $release.id, $release.html_url)
}

# ---------------------------------------------------------------------------------------------
Write-Host "3. the asset"
if ($Asset) {
    if (-not (Test-Path -LiteralPath $Asset)) { throw "asset not found: $Asset" }
    if (-not $AssetName) { $AssetName = Split-Path $Asset -Leaf }
    $local = Get-Item -LiteralPath $Asset
    $sha = (Get-FileHash -LiteralPath $Asset -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host ("  local: {0} ({1} bytes, sha256 {2})" -f $AssetName, $local.Length, $sha)
    $existing = @($release.assets) | Where-Object { $_.name -eq $AssetName }
    if ($existing) {
        Write-Host ("  asset already uploaded ({0} bytes) - verifying, not re-uploading" -f $existing[0].size)
    } elseif ($VerifyOnly) {
        throw "asset $AssetName is missing and -VerifyOnly was given"
    } else {
        # uploads.github.com takes the file itself; -InFile streams it instead of building a byte[] body.
        $upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($AssetName))"
        $done = $null
        for ($attempt = 1; $attempt -le 4; $attempt++) {
            try {
                $done = Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -InFile $Asset `
                    -ContentType 'application/java-archive' -TimeoutSec 900
                break
            } catch {
                if ($attempt -eq 4) { throw }
                Write-Host ("  upload retry {0}: {1}" -f $attempt, $_.Exception.Message)
                Start-Sleep -Seconds (5 * $attempt)
            }
        }
        Write-Host ("  uploaded: id {0}, {1} bytes, state {2}" -f $done.id, $done.size, $done.state)
    }
}

# ---------------------------------------------------------------------------------------------
Write-Host "4. verification"
$check = Invoke-Api -Method Get -Uri "$api/releases/tags/$Tag"
Write-Host ("  release : {0}  tag={1}  published={2}  prerelease={3}" -f $check.html_url, $check.tag_name, ($check.published_at -ne $null), $check.prerelease)
Write-Host ("  notes   : {0} chars" -f $check.body.Length)
foreach ($item in @($check.assets)) {
    Write-Host ("  asset   : {0}  {1} bytes  state={2}  digest={3}" -f $item.name, $item.size, $item.state, $item.digest)
}
if ($Asset) {
    $match = @($check.assets) | Where-Object { $_.name -eq $AssetName }
    if (-not $match) { throw "the asset $AssetName is not on the release after the upload" }
    if ($match[0].size -ne $local.Length) { throw "remote asset size $($match[0].size) != local $($local.Length)" }
    if ($match[0].digest -and $match[0].digest -ne "sha256:$sha") {
        throw "remote digest $($match[0].digest) != local sha256:$sha"
    }
    Write-Host ("  VERIFY OK: {0} is {1} bytes{2}" -f $AssetName, $match[0].size,
        $(if ($match[0].digest) { " and its digest matches the local file" } else { ' (GitHub reported no digest)' }))
}
Write-Host ''
Write-Host ("DONE  tag={0}  target={1}  release={2}" -f $Tag, $Target, $check.html_url)
