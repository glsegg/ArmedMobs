# Emits the spawn hub (signs + chest with the guide book) from guide-content.json.
#
# IMPORTANT: all Chinese text lives in the .json, never in this script. Windows PowerShell 5.1
# decodes a BOM-less .ps1 with the system ANSI code page (GBK here), which silently mangles any
# non-ASCII literal in the script and can even swallow the following quote character. The JSON is
# therefore read with an explicit UTF-8 decoder.
#
# Escaping chain for book pages: a real newline must reach JSON inside the item NBT as the two
# characters \ and n, and Minecraft's command parser rejects \n in a quoted NBT string, so the
# command has to carry \\n.
param(
    [string]$WorkDir = 'D:\deepseek\ArmedMobs\tools\spike\citysave'
)

$ErrorActionPreference = 'Stop'
$contentPath = Join-Path $WorkDir 'guide-content.json'
$json = [System.IO.File]::ReadAllText($contentPath, [System.Text.Encoding]::UTF8)
$content = $json | ConvertFrom-Json

$lines = New-Object System.Collections.Generic.List[string]
function Add([string]$s) { $lines.Add($s) }

function Escape-NbtQuoted([string]$text) {
    # single-quoted NBT strings only treat \' and \\ specially
    return $text.Replace('\', '\\').Replace("'", "\'")
}

Add '# ---- spawn hub: signs with the coordinate table ----'
foreach ($sign in $content.signs) {
    $sx = $sign.x
    $sz = $sign.z
    Add "setblock $sx 64 $sz minecraft:oak_sign[rotation=0]"
    $messages = @()
    for ($i = 0; $i -lt 4; $i++) {
        $text = if ($i -lt $sign.lines.Count) { $sign.lines[$i] } else { '' }
        $messages += "'{""text"":""" + (Escape-NbtQuoted $text) + """}'"
    }
    Add ("data merge block $sx 64 $sz {front_text:{messages:[" + ($messages -join ',') + "]}}")
}

Add '# ---- spawn hub: chest holding the guide as a written book + one structure block ----'
$b = $content.book
Add "setblock $($b.x) $($b.y) $($b.z) minecraft:chest[facing=south]"
Add ("data merge block $($b.x) $($b.y) $($b.z) {Items:[{Slot:0b,id:""minecraft:written_book"",Count:1b,tag:{title:""" + (Escape-NbtQuoted $b.title) + """,author:""" + (Escape-NbtQuoted $b.author) + """,pages:[]}},{Slot:1b,id:""minecraft:structure_block"",Count:1b}]}")

foreach ($page in $b.pages) {
    $text = ($page -join '\n')          # JSON escape for a newline
    $text = Escape-NbtQuoted $text      # then escape it for the command's quoted NBT string
    Add ("data modify block $($b.x) $($b.y) $($b.z) Items[{Slot:0b}].tag.pages append value '{""text"":""" + $text + """}'")
}

$batch = Join-Path $WorkDir 'batches\13b-hub.txt'
[System.IO.File]::WriteAllLines($batch, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "wrote $($lines.Count) commands to $batch"

# self check: the emitted file must contain the real UTF-8 bytes of a known Chinese word
$bytes = [System.IO.File]::ReadAllBytes($batch)
$needle = [System.Text.Encoding]::UTF8.GetBytes([string][char]0x57CE + [string][char]0x5E02 + [string][char]0xFF11)
$found = $false
for ($i = 0; $i -le $bytes.Length - $needle.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $needle.Length; $j++) { if ($bytes[$i + $j] -ne $needle[$j]) { $match = $false; break } }
    if ($match) { $found = $true; break }
}
Write-Output "self-check: file contains UTF-8 bytes of 'city1' in Chinese = $found"
$q = ($lines | Where-Object { $_ -like 'data merge block 34 64 141 {Items*' })
Write-Output ("self-check: chest line double-quote count = " + (($q.ToCharArray() | Where-Object { $_ -eq '"' }).Count) + " (must be even)")
