<#
.SYNOPSIS
  Downloads the real extension APKs the server tests use into tools/apks/ (gitignored).

.DESCRIPTION
  Madoyomi never commits APKs. The extension-lifecycle tests (server/core madoyomi.core.extension.*,
  server/ext-host madoyomi.exthost.ApkInspectorTest) look for these files in tools/apks/ and skip
  with a message when they are missing. Each file is downloaded from a pinned URL and its SHA-256 is
  checked. Files already present with the right hash are left alone.

  When a repo drops a pinned file, the download fails: pick a newer APK from the repo, update its
  File/Url/Sha below (the tests read the version from the APK itself, so nothing else changes).

.PARAMETER Force
  Re-download even if a file already exists with the right hash.

.EXAMPLE
  .\tools\fetch-test-apks.ps1
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ApkDir = Join-Path $RepoRoot 'tools/apks'

# yuzono/anime-repo, branch "repo" at a pinned commit; Keiyoushi serves APKs as release assets per tag.
$YuzonoCommit = 'a060bbc418ffe1879c9105ad4ec05d8e21264071'
$Apks = @(
    @{
        File = 'aniyomi-en.animegg-v14.2.apk'
        Url  = "https://raw.githubusercontent.com/yuzono/anime-repo/$YuzonoCommit/apk/aniyomi-en.animegg-v14.2.apk"
        Sha  = '8f1316caa49c917dc93bb0ee114a67d18f1d7917e27c9f76ced7af46594656cf'
    }
    @{
        File = 'aniyomi-en.animepahe-v16.56.apk'
        Url  = "https://raw.githubusercontent.com/yuzono/anime-repo/$YuzonoCommit/apk/aniyomi-en.animepahe-v16.56.apk"
        Sha  = '6d9a6d1ba7c9b41946207d53f1d91ff786129b263721fa2c3323569235d83e41'
    }
    @{
        File = 'tachiyomi-en.mangapill-v1.6.1.apk'
        Url  = 'https://github.com/keiyoushi/extensions/releases/download/eac81a2-0/tachiyomi-en.mangapill-v1.6.1.apk'
        Sha  = '7bf4a03e1b50a1965b01533703ddbff7ebf5849ca0c7f6eaae3386d581289abe'
    }
)

function Get-Sha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path $ApkDir | Out-Null
$fetched = 0
foreach ($a in $Apks) {
    $dest = Join-Path $ApkDir $a.File
    if (-not $Force -and (Test-Path -LiteralPath $dest) -and (Get-Sha256 $dest) -eq $a.Sha) {
        Write-Verbose "ok      $($a.File)"
        continue
    }
    $tmp = "$dest.part"
    try {
        Invoke-WebRequest -Uri $a.Url -OutFile $tmp -UseBasicParsing
    } catch {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
        throw "Could not download $($a.File) from $($a.Url) ($($_.Exception.Message)). The repo may have dropped it: update the pin in $PSCommandPath."
    }
    $got = Get-Sha256 $tmp
    if ($got -ne $a.Sha) {
        Remove-Item -LiteralPath $tmp -Force
        throw "SHA-256 mismatch for $($a.File): expected $($a.Sha), got $got"
    }
    Move-Item -LiteralPath $tmp -Destination $dest -Force
    Write-Host "fetched $($a.File)"
    $fetched++
}
Write-Host "Test APKs: $fetched fetched, $($Apks.Count - $fetched) already present (all $($Apks.Count) verified) in $ApkDir."
