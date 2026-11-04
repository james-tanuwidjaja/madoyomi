<#
.SYNOPSIS
  Static compat scan over the top 20 anime extensions and the manga sample: writes docs/research/compat-scan.md.

.DESCRIPTION
  1. If top20.txt / manga-sample.txt are missing (or -Select is given), runs select-top.ps1 to rebuild them.
  2. Downloads the listed APKs into tools/compat-test/apks/ (gitignored; files already there are kept).
  3. Runs the server's CompatScanTest (server/core, MADOYOMI_COMPAT_SCAN=1): each APK is converted as an install does
     (dex2jar + Dex2jarRepair), every class is linked, and ExtensionLinkCheck resolves every external class, method and
     field. The frequency tables decide which Android stubs get fixed first (Phase 2 doc, section 10).

  Needs the repo's JDK in tools/bin/jdk-21 (or -JavaHome). Takes a few minutes (Gradle + 30 conversions).

.PARAMETER Select
  Rebuild the lists from the live repos and jsDelivr/GitHub stats first (changes the committed snapshot).

.PARAMETER JavaHome
  JDK used by Gradle (default: tools/bin/jdk-21). Must be an absolute path.

.EXAMPLE
  .\tools\compat-test\scan.ps1
#>
[CmdletBinding()]
param(
    [switch]$Select,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $JavaHome) { $JavaHome = Join-Path $RepoRoot 'tools/bin/jdk-21' }
$JavaHome = (Resolve-Path $JavaHome).Path

$lists = @('top20.txt', 'manga-sample.txt') | ForEach-Object { Join-Path $PSScriptRoot $_ }
if ($Select -or @($lists | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -gt 0) {
    & (Join-Path $PSScriptRoot 'select-top.ps1') -Download
} else {
    # download only: re-read the committed lists
    $apkDir = Join-Path $PSScriptRoot 'apks'
    New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
    $fetched = 0
    foreach ($list in $lists) {
        foreach ($line in [IO.File]::ReadAllLines($list)) {
            if ($line -eq '' -or $line.StartsWith('#')) { continue }
            $url = ($line -split "`t")[7]
            $dest = Join-Path $apkDir ($url -split '/')[-1]
            if (Test-Path -LiteralPath $dest) { continue }
            $wc = New-Object Net.WebClient
            $wc.Headers.Add('User-Agent', 'madoyomi-compat-test')
            try { $wc.DownloadFile($url, "$dest.part") } finally { $wc.Dispose() }
            Move-Item -LiteralPath "$dest.part" -Destination $dest -Force
            $fetched++
        }
    }
    Write-Host "APKs: $fetched downloaded to $apkDir"
}

$env:JAVA_HOME = $JavaHome
$env:MADOYOMI_COMPAT_SCAN = '1'
Push-Location (Join-Path $RepoRoot 'server')
try {
    # Gradle's stderr (compiler warnings) must not stop the script: PowerShell 5.1 turns it into errors
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :core:test --tests 'madoyomi.core.extension.CompatScanTest' --rerun --console=plain
    if ($LASTEXITCODE -ne 0) { throw "CompatScanTest failed (exit $LASTEXITCODE); see server/core/build/test-results/test/" }
} finally {
    Pop-Location
    Remove-Item Env:\MADOYOMI_COMPAT_SCAN -ErrorAction SilentlyContinue
}
Write-Host "Compat scan: $(Join-Path $RepoRoot 'docs/research/compat-scan.md')"
