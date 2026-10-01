# Finalise the generated reference .docx with Word: insert a REAL table of contents (with page numbers)
# at the bookmark the generator left, update every field, repaginate, save, optionally export a PDF,
# and print the statistics so the result can be checked without opening Word by hand.
#
#   powershell -ExecutionPolicy Bypass -File .\tools\spike\work\docx_finalize.ps1 `
#       -Pdf '.\build\docs\reference.pdf'
param(
    [string]$Docx = '',
    [string]$Pdf = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Docx) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
    $exportDir = Join-Path $repoRoot 'build\docs'
    $Docx = (Get-ChildItem -LiteralPath $exportDir -Filter '*.docx' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $Docx -or -not (Test-Path -LiteralPath $Docx)) {
    throw 'docx not found: run node tools/make_reference_docx.js or pass -Docx <file>'
}
$Docx = (Resolve-Path -LiteralPath $Docx).Path
if ($Pdf) {
    $Pdf = [IO.Path]::GetFullPath($Pdf)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Pdf) | Out-Null
}

$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $doc = $word.Documents.Open($Docx, $false, $false)
    $tocInserted = $false
    if ($doc.Bookmarks.Exists('TOCField')) {
        $range = $doc.Bookmarks.Item('TOCField').Range
        $toc = $doc.TablesOfContents.Add($range, $true, 1, 3)
        $toc.Update()
        $tocInserted = $true
    }
    $doc.Fields.Update() | Out-Null
    $doc.Repaginate()

    $pages = $doc.ComputeStatistics(2)   # wdStatisticPages
    $words = $doc.ComputeStatistics(0)   # wdStatisticWords
    $chars = $doc.ComputeStatistics(3)   # wdStatisticCharacters
    $paras = $doc.ComputeStatistics(4)   # wdStatisticParagraphs
    $tables = $doc.Tables.Count
    $tocs = $doc.TablesOfContents.Count
    $shapes = $doc.InlineShapes.Count

    $doc.Save()
    if ($Pdf) { $doc.ExportAsFixedFormat($Pdf, 17) }
    $doc.Close(0)

    Write-Host ("toc inserted : {0} (tables of contents in document: {1})" -f $tocInserted, $tocs)
    Write-Host ("pages        : {0}" -f $pages)
    Write-Host ("words/chars  : {0} / {1}" -f $words, $chars)
    Write-Host ("paragraphs   : {0}" -f $paras)
    Write-Host ("tables       : {0}" -f $tables)
    if ($Pdf) { Write-Host ("pdf          : {0} ({1} bytes)" -f $Pdf, (Get-Item $Pdf).Length) }
} finally {
    $word.Quit()
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word)
}
