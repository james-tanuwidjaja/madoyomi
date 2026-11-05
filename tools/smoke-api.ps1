<#
.SYNOPSIS
  End-to-end smoke test of the Madoyomi backend API against real extension repos (the Phase 1 exit run, plus the
  Phase 2 anime chain).

.DESCRIPTION
  Starts the server jar the way the Tauri shell does (random port, MADOYOMI_TOKEN, --data-dir, piped stdin),
  then drives it over GraphQL (HTTP and graphql-transport-ws) and the binary REST routes only:

    0. /health without a token, GraphQL 401 without a token, aboutServer with it
    1. add the anime repo (yuzono, ANIME) and the manga repo (Keiyoushi, MANGA)
    2. fetch both extension lists and print the counts
    3. install AnimeGG and MangaPill while an extensionInstallProgress subscription listens
    4. check both are installed, trusted by their repo's key and loaded
    5. MangaPill: popular -> details -> chapters -> pages -> page image (image/jpeg from the source and from the cache)
    6. AnimeGG: animeSources (live fields, filters) -> popular (twice, same ids) -> details + episodes -> videos of the
       newest episode -> resolve a video -> thumbnail (from the source and from the cache) -> compatIssues
    7. close stdin and check the server exits with code 0

  Prints PASS/FAIL per step and a summary. Exits 0 only when every step passed.
  Needs network access to GitHub (the repos) and to the extensions' sites.
  The helpers are in tools\lib\MadoyomiServer.ps1.

.PARAMETER Jar
  Server jar to test. Default: build it (:api:shadowJar) and use the newest server\api\build\Madoyomi-Server-*.jar.

.PARAMETER NoBuild
  Use the newest built jar without rebuilding.

.PARAMETER DataDir
  Data directory for the server. Default: a fresh folder under %TEMP%, deleted after a passing run.
  Pass an existing data dir only if you mean to (the run adds repos and installs extensions there).

.PARAMETER KeepDataDir
  Keep the default temporary data dir after a passing run (it is always kept after a failure).

.PARAMETER AnimeRepo
  Anime repo index URL. Default: yuzono's index.min.json.

.PARAMETER MangaRepo
  Manga repo index URL. Default: Keiyoushi's index.pb.

.EXAMPLE
  .\tools\smoke-api.ps1
.EXAMPLE
  .\tools\smoke-api.ps1 -NoBuild -KeepDataDir
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [switch]$NoBuild,
    [string]$DataDir,
    [switch]$KeepDataDir,
    [string]$AnimeRepo = 'https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json',
    [string]$MangaRepo = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb',
    [int]$StartupTimeoutSec = 120
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'lib\MadoyomiServer.ps1')

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21'
$Java = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $Java)) { throw "JDK 21 not found at $Jdk" }

$MangaPkg = 'eu.kanade.tachiyomi.extension.en.mangapill'
$MangaSourceId = '8448310129093543312'
$AnimePkg = 'eu.kanade.tachiyomi.animeextension.en.animegg'
$AnimeSourceId = '2247987965040486818'

# --- jar and data dir ---
$Jar = Resolve-MadoyomiJar -RepoRoot $RepoRoot -Jar $Jar -NoBuild:$NoBuild

$tempDataDir = -not $DataDir
if ($tempDataDir) { $DataDir = Join-Path $env:TEMP ("madoyomi-smoke-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$DataDir = [System.IO.Path]::GetFullPath($DataDir)

Write-Host "jar:        $Jar"
Write-Host "data dir:   $DataDir$(if ($tempDataDir) { ' (temporary)' })"
Write-Host "anime repo: $AnimeRepo"
Write-Host "manga repo: $MangaRepo"
Write-Host ''

Reset-MadoyomiSteps
$extFields = 'pkgName type versionName isInstalled hasUpdate isTrusted isLoaded isTorrent signatureHash'

# --- run ---
try {
    $startMs = Start-MadoyomiServer -Java $Java -Jar $Jar -DataDir $DataDir -TimeoutSec $StartupTimeoutSec
    Write-Host "MADOYOMI_PORT=$script:port after $startMs ms (pid $($script:proc.Id))"
    Write-Host ''

    # 0. sidecar HTTP
    Step '0a /health without a token' {
        $r = Invoke-Http -Method GET -Url "$script:base/health" -NoAuth
        Assert ($r.Status -eq 200) "HTTP $($r.Status)"
        $h = [Text.Encoding]::UTF8.GetString($r.Bytes) | ConvertFrom-Json
        Assert ($h.status -eq 'ok') "status '$($h.status)'"
        "$($h.name) $($h.version) pid=$($h.pid)"
    } | Out-Null
    Step '0b GraphQL without a token is 401 UNAUTHORIZED' {
        $r = Gql '{ aboutServer { name } }' -NoAuth
        Assert ($r.Status -eq 401) "HTTP $($r.Status): $($r.Text)"
        Assert ($r.Json.code -eq 'UNAUTHORIZED') "body: $($r.Text)"
        "HTTP 401 code=$($r.Json.code)"
    } | Out-Null
    Step '0c aboutServer with the token' {
        $d = GqlData '{ aboutServer { name version } }'
        "$($d.aboutServer.name) $($d.aboutServer.version)"
    } | Out-Null

    # 1. repos
    $storeFields = 'indexUrl name type format signingKey signingKeyTrusted lastRefreshedAt lastError'
    $script:stores = @{}
    foreach ($repo in @(@{ type = 'ANIME'; url = $AnimeRepo }, @{ type = 'MANGA'; url = $MangaRepo })) {
        Step "1  addExtensionStore $($repo.type) $($repo.url)" {
            $d = GqlData "mutation(`$url: String!) { addExtensionStore(input: {indexUrl: `$url, type: $($repo.type)}) { extensionStore { $storeFields } } }" @{ url = $repo.url }
            $s = $d.addExtensionStore.extensionStore
            Assert ($s.type -eq $repo.type) "type $($s.type)"
            Assert ([bool]$s.signingKeyTrusted) 'signingKeyTrusted = false'
            Assert (-not $s.lastError) "lastError: $($s.lastError)"
            Assert ([bool]$s.signingKey) 'no signingKey'
            $script:stores[$repo.type] = $s
            "name='$($s.name)' resolved=$($s.indexUrl) format=$($s.format) key=$($s.signingKey.Substring(0, 8))..."
        } | Out-Null
    }

    # 2. extension lists
    foreach ($t in @('ANIME', 'MANGA')) {
        if (-not $script:stores[$t]) { Skip "2  fetchExtensions $t" "no $t repo"; continue }
        Step "2  fetchExtensions $t" {
            $d = GqlData "mutation { fetchExtensions(input: {type: $t}) { extensions { pkgName type } extensionStores { indexUrl type lastError } } }"
            $exts = @($d.fetchExtensions.extensions)
            $types = ($exts | ForEach-Object { $_.type } | Sort-Object -Unique) -join ','
            Assert ($exts.Count -gt 0) 'no extensions'
            Assert ($types -eq $t) "types: $types"
            $bad = @($d.fetchExtensions.extensionStores | Where-Object { $_.lastError })
            Assert ($bad.Count -eq 0) "lastError: $($bad[0].lastError)"
            $total = (GqlData "{ extensions(condition: {type: $t}) { totalCount } }").extensions.totalCount
            Assert ($total -eq $exts.Count) "extensions(condition: {type: $t}).totalCount = $total, fetchExtensions returned $($exts.Count)"
            "extensions=$($exts.Count) (totalCount $total)"
        } | Out-Null
    }

    # 3. installs, 4. installed + trusted + loaded (anime since Phase 2)
    $script:installed = @{}
    foreach ($ext in @(@{ type = 'ANIME'; pkg = $AnimePkg }, @{ type = 'MANGA'; pkg = $MangaPkg })) {
        if (-not $script:stores[$ext.type]) { Skip "3  install $($ext.pkg)" "no $($ext.type) repo"; Skip "4  $($ext.pkg) installed and trusted" 'not installed'; continue }
        $ok = Step "3  install $($ext.pkg) (with extensionInstallProgress)" {
            $states = InstallWithProgress $ext.pkg
            Assert ($states.Count -gt 0 -and $states[-1] -eq 'INSTALLED') "states: $($states -join ' -> ')"
            "states: $($states -join ' -> ')"
        }
        if (-not $ok) { Skip "4  $($ext.pkg) installed and trusted" 'install failed'; continue }
        $ok = Step "4  $($ext.pkg) installed, trusted and loaded" {
            $x = (GqlData "query(`$pkg: String!) { extension(pkgName: `$pkg) { $extFields } }" @{ pkg = $ext.pkg }).extension
            Assert ([bool]$x.isInstalled) 'isInstalled = false'
            Assert ($x.type -eq $ext.type) "type $($x.type)"
            Assert ([bool]$x.isTrusted) 'isTrusted = false'
            Assert ($x.signatureHash -and $x.signatureHash.ToLower() -eq $script:stores[$ext.type].signingKey.ToLower()) "signatureHash $($x.signatureHash) is not the repo key"
            Assert ([bool]$x.isLoaded) 'isLoaded = false'
            "v$($x.versionName) trusted by the repo key, isLoaded=$($x.isLoaded) isTorrent=$($x.isTorrent)"
        }
        if ($ok) { $script:installed[$ext.type] = $true }
    }
    if ($script:stores.Count -gt 0) {
        Step '4  trustedSignatures lists the repo keys' {
            $sigs = @((GqlData '{ trustedSignatures { hash origin extensionStore { indexUrl type } extensions { pkgName } } }').trustedSignatures)
            foreach ($s in $script:stores.Values) {
                $row = $sigs | Where-Object { $_.hash.ToLower() -eq $s.signingKey.ToLower() } | Select-Object -First 1
                Assert ([bool]$row) "no row for the $($s.type) key"
                Assert ($row.origin -eq 'STORE') "$($s.type) key origin $($row.origin)"
            }
            ($sigs | ForEach-Object { "$($_.origin):$($_.hash.Substring(0, 8))...[$((@($_.extensions) | ForEach-Object { $_.pkgName.Split('.')[-1] }) -join ',')]" }) -join ' '
        } | Out-Null
    }

    # 5. manga chain (no regression from Suwayomi)
    if (-not $script:installed['MANGA']) {
        foreach ($n in @('popular', 'details', 'chapters', 'pages', 'page image')) { Skip "5  MangaPill $n" 'MangaPill not installed' }
    } else {
        $script:mangas = @()
        $ok = Step '5  MangaPill popular' {
            $d = GqlData 'mutation($s: LongString!) { fetchSourceManga(input: {source: $s, type: POPULAR, page: 1}) { hasNextPage mangas { id title } } }' @{ s = $MangaSourceId }
            $script:mangas = @($d.fetchSourceManga.mangas)
            Assert ($script:mangas.Count -gt 0) 'no mangas'
            "mangas=$($script:mangas.Count) hasNextPage=$($d.fetchSourceManga.hasNextPage) first='$($script:mangas[0].title)'"
        }
        if (-not $ok) {
            foreach ($n in @('details', 'chapters', 'pages', 'page image')) { Skip "5  MangaPill $n" 'popular failed' }
        } else {
            # Try the first few popular entries until one gets through the whole chain.
            $chainDone = $false
            foreach ($manga in @($script:mangas | Select-Object -First 3)) {
                $script:chapters = @()
                $script:pages = @()
                $script:details = $null
                $ok = Step "5  MangaPill details + chapters '$($manga.title)' (fetchMangaAndChapters)" {
                    $d = GqlData 'mutation($id: Int!) { fetchMangaAndChapters(input: {id: $id, fetchManga: true, fetchChapters: true}) { manga { id title author status genre initialized } chapters { id name chapterNumber sourceOrder } } }' @{ id = [int]$manga.id }
                    $script:details = $d.fetchMangaAndChapters.manga
                    $script:chapters = @($d.fetchMangaAndChapters.chapters)
                    Assert ([bool]$script:details.initialized) 'manga not initialized'
                    Assert ($script:chapters.Count -gt 0) 'no chapters'
                    "author='$($script:details.author)' status=$($script:details.status) genres=$(@($script:details.genre).Count) chapters=$($script:chapters.Count)"
                }
                if (-not $ok) { continue }
                $ch = $script:chapters | Sort-Object sourceOrder | Select-Object -First 1
                $ok = Step "5  MangaPill pages '$($ch.name)'" {
                    $d = GqlData 'mutation($id: Int!) { fetchChapterPages(input: {chapterId: $id}) { pages chapter { pageCount } } }' @{ id = [int]$ch.id }
                    $script:pages = @($d.fetchChapterPages.pages)
                    Assert ($script:pages.Count -gt 0) 'no pages'
                    "pages=$($script:pages.Count) first=$($script:pages[0])"
                }
                if (-not $ok) { continue }
                # The first GET fetches from the source (Authorization header); the second is served from the page
                # cache (?token=, as an <img> would). Both must be image/jpeg.
                $ok = Step '5  MangaPill page image (source fetch with the header, cached fetch with ?token=)' {
                    $u = $script:pages[0]
                    if ($u -notmatch '^https?://') { $u = "$script:base$u" }
                    $r = Invoke-Http -Method GET -Url $u -TimeoutSec 120
                    Assert ($r.Status -eq 200) "HTTP $($r.Status) (header)"
                    Assert ($r.ContentType -match '^image/jpeg') "Content-Type '$($r.ContentType)' (header)"
                    Assert ($r.Bytes.Length -gt 0) 'empty body'
                    $sep = if ($u.Contains('?')) { '&' } else { '?' }
                    $q = Invoke-Http -Method GET -Url "$u${sep}token=$script:token" -NoAuth -TimeoutSec 120
                    Assert ($q.Status -eq 200) "HTTP $($q.Status) (?token=)"
                    Assert ($q.ContentType -match '^image/jpeg') "Content-Type '$($q.ContentType)' (?token=)"
                    Assert ($q.Bytes.Length -eq $r.Bytes.Length) "cached page is $($q.Bytes.Length) bytes, source fetch was $($r.Bytes.Length)"
                    "source: $($r.ContentType) $($r.Bytes.Length) bytes; cached: $($q.ContentType) $($q.Bytes.Length) bytes"
                }
                if ($ok) { $chainDone = $true; break }
            }
            if (-not $chainDone) { Write-Host '     (none of the first 3 popular mangas got through the chain)' -ForegroundColor Red }
        }
    }

    # 6. anime chain (Phase 2)
    $animeSteps = @('animeSources', 'popular', 'details + episodes', 'videos', 'resolve', 'thumbnail', 'compatIssues')
    if (-not $script:installed['ANIME']) {
        foreach ($n in $animeSteps) { Skip "6  AnimeGG $n" 'AnimeGG not installed' }
    } else {
        $ok = Step '6  AnimeGG animeSources (live fields and filters)' {
            $d = GqlData '{ animeSources { totalCount nodes { id name lang baseUrl iconUrl supportsLatest isConfigurable filters { __typename } extension { pkgName isLoaded } } } }'
            $src = @($d.animeSources.nodes) | Where-Object { $_.id -eq $AnimeSourceId } | Select-Object -First 1
            Assert ([bool]$src) "no source $AnimeSourceId (totalCount $($d.animeSources.totalCount))"
            Assert ($src.extension.pkgName -eq $AnimePkg) "extension $($src.extension.pkgName)"
            Assert ($null -ne $src.supportsLatest) 'supportsLatest is null: the source is not usable'
            "totalCount=$($d.animeSources.totalCount) $($src.id) '$($src.name)' $($src.baseUrl) supportsLatest=$($src.supportsLatest) isConfigurable=$($src.isConfigurable) filters=$(@($src.filters).Count)"
        }
        $script:animes = @()
        if ($ok) {
            $ok = Step '6  AnimeGG popular (fetchSourceAnime, twice: the same ids)' {
                $q = 'mutation($s: LongString!) { fetchSourceAnime(input: {source: $s, type: POPULAR, page: 1}) { hasNextPage animes { id title thumbnailUrl } } }'
                $d = GqlData $q @{ s = $AnimeSourceId }
                $script:animes = @($d.fetchSourceAnime.animes)
                Assert ($script:animes.Count -gt 0) 'no animes'
                $again = @((GqlData $q @{ s = $AnimeSourceId }).fetchSourceAnime.animes)
                $ids = ($script:animes | ForEach-Object { $_.id }) -join ','
                Assert ((($again | ForEach-Object { $_.id }) -join ',') -eq $ids) 'the second call returned other ids'
                "animes=$($script:animes.Count) hasNextPage=$($d.fetchSourceAnime.hasNextPage) first='$($script:animes[0].title)'"
            }
        }
        if (-not $ok) {
            foreach ($n in $animeSteps[2..6]) { Skip "6  AnimeGG $n" 'popular failed' }
        } else {
            # Try the first few popular entries until one gets through details, episodes, videos and resolve.
            $chainDone = $false
            foreach ($anime in @($script:animes | Select-Object -First 3)) {
                $script:episodes = @()
                $script:hoster = $null
                $ok = Step "6  AnimeGG details + episodes '$($anime.title)' (fetchAnimeAndEpisodes)" {
                    $d = GqlData 'mutation($id: Int!) { fetchAnimeAndEpisodes(input: {id: $id, fetchAnime: true, fetchEpisodes: true}) { anime { id title status genre initialized fetchType thumbnailUrl } episodes { id name episodeNumber sourceOrder } seasons { id } } }' @{ id = [int]$anime.id }
                    $a = $d.fetchAnimeAndEpisodes.anime
                    $script:episodes = @($d.fetchAnimeAndEpisodes.episodes)
                    Assert ([bool]$a.initialized) 'anime not initialized'
                    Assert ($script:episodes.Count -gt 0) "no episodes (fetchType $($a.fetchType), seasons $(@($d.fetchAnimeAndEpisodes.seasons).Count))"
                    "status=$($a.status) genres=$(@($a.genre).Count) episodes=$($script:episodes.Count)"
                }
                if (-not $ok) { continue }
                $ep = $script:episodes | Sort-Object sourceOrder -Descending | Select-Object -First 1
                $ok = Step "6  AnimeGG videos '$($ep.name)' (fetchEpisodeVideos)" {
                    $d = GqlData 'mutation($id: Int!) { fetchEpisodeVideos(input: {episodeId: $id}) { hosters { index name state errorCode videos { index videoTitle videoUrl initialized headers { name value } subtitleTracks { lang } } } } }' @{ id = [int]$ep.id }
                    $hosters = @($d.fetchEpisodeVideos.hosters)
                    $script:hoster = $hosters | Where-Object { $_.state -eq 'READY' -and @($_.videos).Count -gt 0 } | Select-Object -First 1
                    Assert ([bool]$script:hoster) "no READY hoster with videos ($(($hosters | ForEach-Object { "$($_.name)=$($_.state)/$($_.errorCode)" }) -join ', '))"
                    $v = $script:hoster.videos[0]
                    Assert (@($v.headers).Count -gt 0) 'the video has no headers'
                    "hosters=$($hosters.Count) '$($script:hoster.name)' videos=$(@($script:hoster.videos).Count) first='$($v.videoTitle)' headers=$((@($v.headers) | ForEach-Object { $_.name }) -join ',')"
                }
                if (-not $ok) { continue }
                $ok = Step '6  AnimeGG resolve video 0 (resolveEpisodeVideo)' {
                    $d = GqlData 'mutation($id: Int!, $h: Int!) { resolveEpisodeVideo(input: {episodeId: $id, hosterIndex: $h, videoIndex: 0}) { video { videoTitle videoUrl initialized } } }' @{ id = [int]$ep.id; h = [int]$script:hoster.index }
                    $v = $d.resolveEpisodeVideo.video
                    Assert ([bool]$v.videoUrl) 'no videoUrl'
                    Assert ([bool]$v.initialized) 'not initialized after resolving'
                    $videoHost = ([Uri]$v.videoUrl).Host
                    "'$($v.videoTitle)' on $videoHost"
                }
                if ($ok) { $chainDone = $true; break }
            }
            if (-not $chainDone) { Write-Host '     (none of the first 3 popular animes got through the chain)' -ForegroundColor Red }

            # The thumbnail of the first popular entry that has one: from the source (header), then the cache (?token=).
            $withThumb = $script:animes | Where-Object { $_.thumbnailUrl } | Select-Object -First 1
            if (-not $withThumb) {
                Skip '6  AnimeGG thumbnail' 'no popular entry has a thumbnail'
            } else {
                Step "6  AnimeGG thumbnail '$($withThumb.title)' (source fetch with the header, cached fetch with ?token=)" {
                    $u = "$script:base$($withThumb.thumbnailUrl)"
                    $r = Invoke-Http -Method GET -Url $u -TimeoutSec 120
                    Assert ($r.Status -eq 200) "HTTP $($r.Status): $([Text.Encoding]::UTF8.GetString($r.Bytes))"
                    Assert ($r.ContentType -match '^image/') "Content-Type '$($r.ContentType)'"
                    $q = Invoke-Http -Method GET -Url "${u}?token=$script:token" -NoAuth -TimeoutSec 120
                    Assert ($q.Status -eq 200) "HTTP $($q.Status) (?token=)"
                    Assert ($q.ContentType -eq $r.ContentType) "cached Content-Type '$($q.ContentType)', first '$($r.ContentType)'"
                    Assert ($q.Bytes.Length -eq $r.Bytes.Length) "cached thumbnail is $($q.Bytes.Length) bytes, source fetch was $($r.Bytes.Length)"
                    "$($r.ContentType) $($r.Bytes.Length) bytes, cached the same"
                } | Out-Null
            }
        }
        Step '6  AnimeGG compatIssues' {
            $x = (GqlData "query(`$pkg: String!) { extension(pkgName: `$pkg) { compatIssues { kind target origin context count } } }" @{ pkg = $AnimePkg }).extension
            $issues = @($x.compatIssues)
            "issues=$($issues.Count)$(if ($issues.Count) { ': ' + (($issues | ForEach-Object { "$($_.origin) $($_.kind) $($_.target)" }) -join '; ') })"
        } | Out-Null
    }

    # 7. stdin close
    Step '7  closing stdin stops the server with exit code 0' {
        $code = Stop-MadoyomiServer -TimeoutSec 60
        Assert ($null -ne $code) 'still running 60 s after stdin closed (killed)'
        Assert ($code -eq 0) "exit code $code"
        'exit code 0'
    } | Out-Null
} catch {
    $script:failed++
    Write-Host "FAIL smoke run aborted: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Stop-MadoyomiServer -TimeoutSec 30 | Out-Null
}

# --- summary ---
$interesting = @((Get-MadoyomiServerLog) -split "`r?`n" | Where-Object { $_ -match 'ERROR|Refusing to load|failed' })
if ($interesting.Count -gt 0) {
    Write-Host ''
    Write-Host "Server log lines with ERROR / Refusing to load / failed ($($interesting.Count)):"
    $interesting | Select-Object -First 30 | ForEach-Object { Write-Host ('  ' + $_.Substring(0, [Math]::Min(300, $_.Length))) }
}

Write-Host ''
if ($script:failed -eq 0) {
    Write-Host "PASSED: $script:passed steps, 0 failed" -ForegroundColor Green
    if ($tempDataDir -and -not $KeepDataDir) {
        Remove-Item -LiteralPath $DataDir -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "data dir kept: $DataDir"
    }
    exit 0
}
Write-Host "FAILED: $script:passed passed, $script:failed failed" -ForegroundColor Red
Write-Host "data dir kept: $DataDir"
exit 1
