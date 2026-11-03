<#
.SYNOPSIS
  Phase 0 / S1 spike: smoke-test Mihon (Keiyoushi) manga extensions on a running Suwayomi-Server over GraphQL.

.DESCRIPTION
  For each package: install (Keiyoushi jarUrl, no dex2jar) -> popular page 1 -> details of the first manga ->
  chapter list -> pages of one chapter -> GET the first page image through Suwayomi's REST binary endpoint.
  Prints one PASS/FAIL line per step. Assumes the Keiyoushi store was already added (addExtensionStore).

.EXAMPLE
  .\smoke.ps1 -Packages eu.kanade.tachiyomi.extension.all.mangadex,eu.kanade.tachiyomi.extension.en.mangapill
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string[]]$Packages,
  [string]$Base = "http://127.0.0.1:4567",
  [string]$Lang = "en",
  [string]$OutDir = "$PSScriptRoot\out"
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $OutDir | Out-Null

function Gql([string]$query, [hashtable]$vars = @{}) {
  $body = @{ query = $query; variables = $vars } | ConvertTo-Json -Depth 20 -Compress
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $r = Invoke-RestMethod -Method Post -Uri "$Base/api/graphql" -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 180
  $script:lastMs = $sw.ElapsedMilliseconds
  if ($r.errors) { throw ($r.errors | ForEach-Object { $_.message }) -join "; " }
  return $r.data
}

function Step([string]$ext, [string]$name, [scriptblock]$block) {
  try {
    $res = & $block
    Write-Host ("PASS [{0}] {1} ({2} ms) {3}" -f $ext, $name, $script:lastMs, $res.summary)
    return $res
  } catch {
    Write-Host ("FAIL [{0}] {1}: {2}" -f $ext, $name, $_.Exception.Message) -ForegroundColor Red
    return $null
  }
}

foreach ($pkg in $Packages) {
  $ext = $pkg.Split(".")[-1]

  $inst = Step $ext "install" {
    $d = Gql 'mutation($id:String!){ updateExtension(input:{id:$id, patch:{install:true}}){ extension{ pkgName versionName isInstalled source{ nodes{ id name lang supportsLatest } } } } }' @{ id = $pkg }
    $e = $d.updateExtension.extension
    $src = @($e.source.nodes | Where-Object { $_.lang -eq $Lang -or $_.lang -eq "all" }) + @($e.source.nodes) | Select-Object -First 1
    @{ source = $src; summary = "v$($e.versionName) installed=$($e.isInstalled) sources=$(@($e.source.nodes).Count) using=$($src.name)/$($src.lang) id=$($src.id)" }
  }
  if (-not $inst) { continue }

  $pop = Step $ext "popular" {
    $d = Gql 'mutation($s:LongString!){ fetchSourceManga(input:{source:$s, type:POPULAR, page:1}){ hasNextPage mangas{ id title url thumbnailUrl } } }' @{ s = [string]$inst.source.id }
    $m = $d.fetchSourceManga
    @{ mangas = $m.mangas; summary = "count=$(@($m.mangas).Count) hasNext=$($m.hasNextPage) first='$($m.mangas[0].title)'" }
  }
  if (-not $pop -or @($pop.mangas).Count -eq 0) { continue }

  # Try the first few popular entries until one has chapters with pages.
  foreach ($manga in @($pop.mangas | Select-Object -First 3)) {
    $det = Step $ext "details '$($manga.title)'" {
      $d = Gql 'mutation($id:Int!){ fetchManga(input:{id:$id}){ manga{ id title author status genre initialized } } }' @{ id = [int]$manga.id }
      $mm = $d.fetchManga.manga
      @{ summary = "author='$($mm.author)' status=$($mm.status) genres=$(@($mm.genre).Count) initialized=$($mm.initialized)" }
    }
    $chs = Step $ext "chapters" {
      $d = Gql 'mutation($id:Int!){ fetchChapters(input:{mangaId:$id}){ chapters{ id name chapterNumber sourceOrder } } }' @{ id = [int]$manga.id }
      $c = @($d.fetchChapters.chapters)
      @{ chapters = $c; summary = "count=$($c.Count) first='$($c[0].name)'" }
    }
    if (-not $chs -or $chs.chapters.Count -eq 0) { continue }

    $ch = $chs.chapters | Sort-Object sourceOrder | Select-Object -First 1
    $pages = Step $ext "pages '$($ch.name)'" {
      $d = Gql 'mutation($id:Int!){ fetchChapterPages(input:{chapterId:$id}){ pages chapter{ pageCount } } }' @{ id = [int]$ch.id }
      $p = @($d.fetchChapterPages.pages)
      @{ pages = $p; summary = "count=$($p.Count) first=$($p[0])" }
    }
    if (-not $pages -or $pages.pages.Count -eq 0) { continue }

    $img = Step $ext "page image" {
      $u = $pages.pages[0]
      if ($u -notmatch '^https?://') { $u = "$Base$u" }
      $file = Join-Path $OutDir "$ext-page0.bin"
      $sw = [Diagnostics.Stopwatch]::StartNew()
      $resp = Invoke-WebRequest -Uri $u -OutFile $file -PassThru -UseBasicParsing -TimeoutSec 120
      $script:lastMs = $sw.ElapsedMilliseconds
      $ct = $resp.Headers["Content-Type"]
      $len = (Get-Item $file).Length
      if ($ct -notmatch '^image/') { throw "Content-Type '$ct' ($len bytes)" }
      @{ summary = "$ct $len bytes" }
    }
    if ($img) { break }
  }
}
