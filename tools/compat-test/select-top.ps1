<#
.SYNOPSIS
  Picks the extensions the Phase 2 compat work measures: the top 20 yuzono anime extensions (top20.txt) and a
  Keiyoushi manga sample (manga-sample.txt). Optionally downloads their APKs.

.DESCRIPTION
  Anime (top20.txt), the Phase 2 decision (docs/phases/phase-2-anime-sources.md, section 9): yuzono's APKs ranked by
  jsDelivr hits over the last month (https://data.jsdelivr.com/v1/stats/packages/gh/yuzono/anime-repo@repo/files,
  paginated), summed per package across versions. Torrent extensions are excluded, NSFW is included; each row records
  the content warning from yuzono's index.pb (the JSON index can't tell MIXED from NSFW).

  Manga (manga-sample.txt): Keiyoushi's APKs are GitHub release assets, not served by jsDelivr (its jsDelivr stats only
  list the index files and two icons). They are ranked by the GitHub download_count of each package's *current* APK
  (the asset index.pb points at), divided by the days since that release was published. Keiyoushi rebuilds every
  extension into a few releases, and Mihon's auto-update downloads each new build, so the count approximates the
  installed base. NSFW included, as for anime.

  Both lists are committed snapshots (text). The APKs (-Download) go to tools/compat-test/apks/, which is gitignored.

.PARAMETER Download
  Also download the APKs of both lists into tools/compat-test/apks/ (skips files already present).

.PARAMETER AnimeCount
  How many anime extensions to keep (default 20).

.PARAMETER MangaCount
  How many manga extensions to keep (default 10).

.EXAMPLE
  .\tools\compat-test\select-top.ps1 -Download
#>
[CmdletBinding()]
param(
    [switch]$Download,
    [int]$AnimeCount = 20,
    [int]$MangaCount = 10
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Protobuf.ps1')

$YuzonoIndex = 'https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.pb'
$KeiyoushiIndex = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb'
$JsDelivrStats = 'https://data.jsdelivr.com/v1/stats/packages/gh/yuzono/anime-repo@repo/files?period=month&limit=100&page={0}'
$GitHubRelease = 'https://api.github.com/repos/keiyoushi/extensions/releases/tags/{0}'
$UserAgent = 'madoyomi-compat-test'
$ApkDir = Join-Path $PSScriptRoot 'apks'
$Today = (Get-Date).ToString('yyyy-MM-dd')

function Get-Bytes([string]$Url) {
    $wc = New-Object Net.WebClient
    $wc.Headers.Add('User-Agent', $UserAgent)
    try { return , $wc.DownloadData($Url) } finally { $wc.Dispose() }
}

function Invoke-Json([string]$Url) {
    $headers = @{ 'User-Agent' = $UserAgent }
    if ($Url -like 'https://api.github.com/*' -and $env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    Invoke-RestMethod -Uri $Url -Headers $headers
}

# "aniyomi-en.animepahe-v16.56.apk" -> "en.animepahe" (the part of the pkg after the kind's prefix)
function Get-ApkKey([string]$FileName) {
    if ($FileName -match '^(?:aniyomi|tachiyomi)-(.+)-v[0-9][0-9.]*\.apk$') { return $Matches[1] }
    return $null
}

# at most 6 languages, then "+N"
function Format-Langs([string[]]$Langs) {
    if ($Langs.Count -le 6) { return $Langs -join ',' }
    return (($Langs | Select-Object -First 6) -join ',') + ",+$($Langs.Count - 6)"
}

function Write-List([string]$Path, [string[]]$Header, $Rows, [string]$HitsLabel) {
    $lines = New-Object System.Collections.Generic.List[string]
    $Header | ForEach-Object { $lines.Add("# $_") }
    $lines.Add("# columns: rank`tpkg`tversion`tlib`twarning`tlang`t$HitsLabel`tapk")
    foreach ($r in $Rows) {
        $lines.Add((@($r.Rank, $r.Pkg, $r.VersionName, $r.Lib, $r.ContentWarning, (Format-Langs $r.Langs), $r.Hits, $r.ApkUrl) -join "`t"))
    }
    $safe = @($Rows | Where-Object { $_.ContentWarning -eq 'SAFE' }).Count
    $lines.Add("# SAFE-only: $safe of $(@($Rows).Count)")
    [IO.File]::WriteAllText($Path, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
    Write-Host "wrote $Path ($(@($Rows).Count) rows, $safe SAFE)"
}

# --- anime: jsDelivr monthly hits per package ---
$anime = @(Read-ExtensionIndex (Get-Bytes $YuzonoIndex) 'Aniyomi')
$byKey = @{}
foreach ($e in $anime) {
    $key = Get-ApkKey ($e.ApkUrl -split '/')[-1]
    if ($key) { $byKey[$key] = $e }
}
$hits = @{}
$page = 1
$statFiles = 0
# files come sorted by hits; the API allows pages 1..100 and lists zero-hit files too, so stop at the first page
# without a hit
while ($page -le 100) {
    # PowerShell 5.1 returns a JSON array as one object: enumerate it
    $files = @(Invoke-Json ($JsDelivrStats -f $page) | ForEach-Object { $_ })
    if (@($files | Where-Object { [long]$_.hits.total -gt 0 }).Count -eq 0) { break }
    foreach ($f in $files) {
        if ($f.name -notlike '/apk/*.apk') { continue }
        $statFiles++
        $key = Get-ApkKey ($f.name -split '/')[-1]
        if (-not $key) { continue }
        $hits[$key] = [long]($hits[$key]) + [long]$f.hits.total
    }
    $page++
}
$ranked = $byKey.GetEnumerator() |
    Where-Object { -not $_.Value.IsTorrent } |
    ForEach-Object { $_.Value | Add-Member -NotePropertyName Hits -NotePropertyValue ([long]$hits[$_.Key]) -PassThru -Force } |
    Sort-Object @{ Expression = 'Hits'; Descending = $true }, @{ Expression = 'Pkg' } |
    Select-Object -First $AnimeCount
$i = 0
$ranked = @($ranked | ForEach-Object { $i++; $_ | Add-Member -NotePropertyName Rank -NotePropertyValue $i -PassThru -Force })
Write-List (Join-Path $PSScriptRoot 'top20.txt') @(
    "Top $AnimeCount yuzono anime extensions by jsDelivr hits, snapshot $Today (tools/compat-test/select-top.ps1).",
    "Method: data.jsdelivr.com stats for gh/yuzono/anime-repo@repo, period=month ($statFiles APK files over $($page - 1) pages),",
    'hits summed per package across versions; torrent extensions excluded; NSFW included (Phase 2 doc, section 9).',
    "Index: $YuzonoIndex ($($anime.Count) extensions); warning = index.pb content warning; apk = the current APK."
) $ranked 'monthly_hits'

# --- manga: GitHub release-asset downloads per day ---
$manga = @(Read-ExtensionIndex (Get-Bytes $KeiyoushiIndex) 'Mihon')
$tags = $manga | ForEach-Object { ($_.ApkUrl -split '/')[-2] } | Sort-Object -Unique
$assets = @{}
foreach ($tag in $tags) {
    $release = Invoke-Json ($GitHubRelease -f $tag)
    $days = [Math]::Max(1.0, ((Get-Date).ToUniversalTime() - ([DateTime]$release.published_at).ToUniversalTime()).TotalDays)
    foreach ($a in $release.assets) { $assets["$tag/$($a.name)"] = [pscustomobject]@{ Count = [long]$a.download_count; Days = $days } }
    if ($release.assets.Count -ge 1000) { Write-Warning "release $tag lists $($release.assets.Count) assets; the API may have truncated it" }
}
$rankedManga = $manga |
    ForEach-Object {
        $parts = $_.ApkUrl -split '/'
        $a = $assets["$($parts[-2])/$($parts[-1])"]
        $perDay = if ($a) { [Math]::Round($a.Count / $a.Days, 1) } else { 0 }
        $_ | Add-Member -NotePropertyName Hits -NotePropertyValue $perDay -PassThru -Force
    } |
    Sort-Object @{ Expression = 'Hits'; Descending = $true }, @{ Expression = 'Pkg' } |
    Select-Object -First $MangaCount
$i = 0
$rankedManga = @($rankedManga | ForEach-Object { $i++; $_ | Add-Member -NotePropertyName Rank -NotePropertyValue $i -PassThru -Force })
Write-List (Join-Path $PSScriptRoot 'manga-sample.txt') @(
    "Keiyoushi manga sample: top $MangaCount by GitHub release-asset downloads per day, snapshot $Today (tools/compat-test/select-top.ps1).",
    "Method: Keiyoushi's APKs are not served by jsDelivr; download_count of each package's current APK asset (from index.pb),",
    "divided by the days since its release was published ($($tags.Count) releases: $($tags -join ', ')). NSFW included.",
    "Index: $KeiyoushiIndex ($($manga.Count) extensions); warning = index.pb content warning; apk = the current APK."
) $rankedManga 'downloads_per_day'

# --- APKs ---
if ($Download) {
    New-Item -ItemType Directory -Force -Path $ApkDir | Out-Null
    $fetched = 0
    foreach ($r in @($ranked) + @($rankedManga)) {
        $dest = Join-Path $ApkDir ($r.ApkUrl -split '/')[-1]
        if (Test-Path -LiteralPath $dest) { continue }
        $bytes = Get-Bytes $r.ApkUrl
        [IO.File]::WriteAllBytes("$dest.part", $bytes)
        Move-Item -LiteralPath "$dest.part" -Destination $dest -Force
        $fetched++
    }
    Write-Host "APKs: $fetched downloaded to $ApkDir"
}
