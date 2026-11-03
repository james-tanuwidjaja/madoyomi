<#
.SYNOPSIS
  End-to-end smoke test of the Madoyomi backend API against real extension repos (the Phase 1 exit run).

.DESCRIPTION
  Starts the server jar the way the Tauri shell does (random port, MADOYOMI_TOKEN, --data-dir, piped stdin),
  then drives it over GraphQL (HTTP and graphql-transport-ws) only:

    0. /health without a token, GraphQL 401 without a token, aboutServer with it
    1. add the anime repo (yuzono, ANIME) and the manga repo (Keiyoushi, MANGA)
    2. fetch both extension lists and print the counts
    3. install AnimeGG and MangaPill while an extensionInstallProgress subscription listens
    4. check both are installed and trusted by their repo's key (MangaPill loaded, AnimeGG not: Phase 2)
    5. MangaPill: popular -> details -> chapters -> pages -> page image (image/jpeg from the source and from the cache)
    6. list animeSources (AnimeGG)
    7. close stdin and check the server exits with code 0

  Prints PASS/FAIL per step and a summary. Exits 0 only when every step passed.
  Needs network access to GitHub (the repos) and to the extensions' sites.

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

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'
$Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21'
$Java = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $Java)) { throw "JDK 21 not found at $Jdk" }

$MangaPkg = 'eu.kanade.tachiyomi.extension.en.mangapill'
$MangaSourceId = '8448310129093543312'
$AnimePkg = 'eu.kanade.tachiyomi.animeextension.en.animegg'
$AnimeSourceId = '2247987965040486818'

# --- jar and data dir ---
if (-not $Jar) {
    if (-not $NoBuild) {
        & (Join-Path $PSScriptRoot 'fetch-upstream-assets.ps1')
        $env:JAVA_HOME = $Jdk
        Push-Location $ServerDir
        try {
            & .\gradlew.bat :api:shadowJar --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)" }
        } finally {
            Pop-Location
        }
    }
    $found = Get-ChildItem -LiteralPath (Join-Path $ServerDir 'api\build') -Filter 'Madoyomi-Server-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $found) { throw 'No Madoyomi-Server jar in server\api\build. Run without -NoBuild first.' }
    $Jar = $found.FullName
}
$Jar = (Resolve-Path -LiteralPath $Jar).Path

$tempDataDir = -not $DataDir
if ($tempDataDir) { $DataDir = Join-Path $env:TEMP ("madoyomi-smoke-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$DataDir = [System.IO.Path]::GetFullPath($DataDir)

$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })

Write-Host "jar:        $Jar"
Write-Host "data dir:   $DataDir$(if ($tempDataDir) { ' (temporary)' })"
Write-Host "anime repo: $AnimeRepo"
Write-Host "manga repo: $MangaRepo"
Write-Host ''

# --- step bookkeeping ---
$script:passed = 0
$script:failed = 0
function Step([string]$Name, [scriptblock]$Block) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $summary = (& $Block) -join ' '
        $script:passed++
        Write-Host ("PASS {0} ({1} ms) {2}" -f $Name, $sw.ElapsedMilliseconds, $summary) -ForegroundColor Green
        return $true
    } catch {
        $script:failed++
        Write-Host ("FAIL {0}: {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
        return $false
    }
}
function Skip([string]$Name, [string]$Why) {
    $script:failed++
    Write-Host ("FAIL {0}: skipped ({1})" -f $Name, $Why) -ForegroundColor Red
}
function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

# --- HTTP and GraphQL ---
# HttpWebRequest instead of Invoke-WebRequest: decodes the charset-less JSON as UTF-8, returns error bodies,
# and exposes the Content-Type header of binary responses.
function Invoke-Http([string]$Method, [string]$Url, [string]$Body, [switch]$NoAuth, [int]$TimeoutSec = 300) {
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Method = $Method
    $req.Timeout = $TimeoutSec * 1000
    $req.ReadWriteTimeout = $TimeoutSec * 1000
    if (-not $NoAuth) { $req.Headers['Authorization'] = "Bearer $token" }
    if ($Body) {
        $payload = [Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentType = 'application/json; charset=utf-8'
        $req.ContentLength = $payload.Length
        $stream = $req.GetRequestStream()
        try { $stream.Write($payload, 0, $payload.Length) } finally { $stream.Close() }
    }
    try {
        $resp = $req.GetResponse()
    } catch {
        $ex = $_.Exception
        while ($ex -and -not ($ex -is [System.Net.WebException])) { $ex = $ex.InnerException }
        if (-not $ex -or -not $ex.Response) { throw }
        $resp = $ex.Response
    }
    try {
        $ms = New-Object IO.MemoryStream
        $resp.GetResponseStream().CopyTo($ms)
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; ContentType = $resp.ContentType; Bytes = $ms.ToArray() }
    } finally {
        $resp.Close()
    }
}

function Gql([string]$Query, [hashtable]$Vars = @{}, [switch]$NoAuth) {
    $body = @{ query = $Query; variables = $Vars } | ConvertTo-Json -Compress -Depth 10
    $r = Invoke-Http -Method POST -Url "$script:base/api/graphql" -Body $body -NoAuth:$NoAuth
    $text = [Text.Encoding]::UTF8.GetString($r.Bytes)
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Status = $r.Status; Json = $json; Text = $text }
}

function ErrorText($Json) {
    return ($Json.errors | ForEach-Object { "[$($_.extensions.code)] $($_.message.Split("`n")[0])" }) -join ' | '
}

# Runs a query that must succeed and returns its data.
function GqlData([string]$Query, [hashtable]$Vars = @{}) {
    $r = Gql $Query $Vars
    if ($r.Status -ne 200) { throw "HTTP $($r.Status): $($r.Text)" }
    if (-not $r.Json) { throw "not JSON: $($r.Text)" }
    if ($r.Json.errors) { throw (ErrorText $r.Json) }
    return $r.Json.data
}

# --- WebSocket (graphql-transport-ws) ---
# No subprotocol is requested: the server negotiates none, and .NET fails when a requested one isn't echoed.
# One ReceiveAsync is kept pending at a time.
$script:ws = $null
$script:pending = $null
$wsBuf = New-Object byte[] 65536
function WsSend($Obj) {
    $json = $Obj | ConvertTo-Json -Compress -Depth 10
    $data = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object 'ArraySegment[byte]' -ArgumentList @(, $data)
    $script:ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait()
}
function WsRecv([int]$TimeoutMs) {
    $ms = New-Object IO.MemoryStream
    while ($true) {
        if (-not $script:pending) {
            $seg = New-Object 'ArraySegment[byte]' -ArgumentList @(, $wsBuf)
            $script:pending = $script:ws.ReceiveAsync($seg, [Threading.CancellationToken]::None)
        }
        if (-not $script:pending.Wait($TimeoutMs)) { return $null }
        $res = $script:pending.Result
        $script:pending = $null
        $ms.Write($wsBuf, 0, $res.Count)
        if ($res.EndOfMessage) { break }
    }
    return [Text.Encoding]::UTF8.GetString($ms.ToArray())
}
function WsOpen([string]$Subscription) {
    $script:ws = New-Object System.Net.WebSockets.ClientWebSocket
    $script:pending = $null
    $script:ws.ConnectAsync([Uri]"ws://127.0.0.1:$script:port/api/graphql?token=$token", [Threading.CancellationToken]::None).Wait()
    WsSend @{ type = 'connection_init'; payload = @{} }
    $ack = WsRecv 10000
    if (-not $ack -or ($ack | ConvertFrom-Json).type -ne 'connection_ack') { throw "no connection_ack (got: $ack)" }
    WsSend @{ id = '1'; type = 'subscribe'; payload = @{ query = $Subscription } }
}
function WsClose {
    if ($script:ws) {
        try { $script:ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, '', [Threading.CancellationToken]::None).Wait(5000) | Out-Null } catch { }
        $script:ws.Dispose()
    }
    $script:ws = $null
    $script:pending = $null
}

$extFields = 'pkgName type versionName isInstalled hasUpdate isTrusted isLoaded isTorrent signatureHash'

# Installs a listed extension while an extensionInstallProgress subscription listens; returns the states seen.
function InstallWithProgress([string]$Pkg) {
    WsOpen "subscription { extensionInstallProgress(pkgName: `"$Pkg`") { pkgName type state errorCode } }"
    try {
        Start-Sleep -Milliseconds 500 # let the subscription register before the install starts (no replay)
        $d = GqlData 'mutation($id: String!) { updateExtension(input: {id: $id, patch: {install: true}}) { extension { pkgName isInstalled } } }' @{ id = $Pkg }
        Assert ([bool]$d.updateExtension.extension.isInstalled) 'updateExtension(install) returned isInstalled = false'
        $states = @()
        while ($true) {
            $msg = WsRecv 15000
            if ($null -eq $msg) { $states += '(timeout)'; break }
            $m = $msg | ConvertFrom-Json
            if ($m.type -eq 'next') {
                $states += $m.payload.data.extensionInstallProgress.state
                if ($states[-1] -in @('INSTALLED', 'FAILED')) { break }
            } else {
                $states += "<$($m.type)>"
                if ($m.type -in @('error', 'complete')) { break }
            }
        }
        return , $states
    } finally {
        WsClose
    }
}

# --- start the server ---
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = "-jar `"$Jar`" --data-dir `"$DataDir`""
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.EnvironmentVariables['MADOYOMI_TOKEN'] = $token

$startSw = [Diagnostics.Stopwatch]::StartNew()
$p = [System.Diagnostics.Process]::Start($psi)
$errTask = $p.StandardError.ReadToEndAsync()
$outTask = $null
try {
    $script:port = $null
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
    while (-not $script:port) {
        $remainingMs = [int][Math]::Max(0, ($deadline - (Get-Date)).TotalMilliseconds)
        $lineTask = $p.StandardOutput.ReadLineAsync()
        if (-not $lineTask.Wait($remainingMs)) { throw "no MADOYOMI_PORT line within $StartupTimeoutSec s" }
        $line = $lineTask.Result
        if ($null -eq $line) { throw "the server exited before printing MADOYOMI_PORT (exit code $($p.ExitCode))" }
        if ($line -match '^MADOYOMI_PORT=(\d+)$') { $script:port = [int]$Matches[1] }
    }
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $script:base = "http://127.0.0.1:$script:port"
    Write-Host "MADOYOMI_PORT=$script:port after $($startSw.ElapsedMilliseconds) ms (pid $($p.Id))"
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

    # 3. installs, 4. installed + trusted
    $script:installed = @{}
    foreach ($ext in @(@{ type = 'ANIME'; pkg = $AnimePkg; loaded = $false }, @{ type = 'MANGA'; pkg = $MangaPkg; loaded = $true })) {
        if (-not $script:stores[$ext.type]) { Skip "3  install $($ext.pkg)" "no $($ext.type) repo"; Skip "4  $($ext.pkg) installed and trusted" 'not installed'; continue }
        $ok = Step "3  install $($ext.pkg) (with extensionInstallProgress)" {
            $states = InstallWithProgress $ext.pkg
            Assert ($states.Count -gt 0 -and $states[-1] -eq 'INSTALLED') "states: $($states -join ' -> ')"
            "states: $($states -join ' -> ')"
        }
        if (-not $ok) { Skip "4  $($ext.pkg) installed and trusted" 'install failed'; continue }
        $ok = Step "4  $($ext.pkg) installed and trusted" {
            $x = (GqlData "query(`$pkg: String!) { extension(pkgName: `$pkg) { $extFields } }" @{ pkg = $ext.pkg }).extension
            Assert ([bool]$x.isInstalled) 'isInstalled = false'
            Assert ($x.type -eq $ext.type) "type $($x.type)"
            Assert ([bool]$x.isTrusted) 'isTrusted = false'
            Assert ($x.signatureHash -and $x.signatureHash.ToLower() -eq $script:stores[$ext.type].signingKey.ToLower()) "signatureHash $($x.signatureHash) is not the repo key"
            Assert ([bool]$x.isLoaded -eq $ext.loaded) "isLoaded = $($x.isLoaded), expected $($ext.loaded)"
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
                    $q = Invoke-Http -Method GET -Url "$u${sep}token=$token" -NoAuth -TimeoutSec 120
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

    # 6. anime sources
    if (-not $script:installed['ANIME']) {
        Skip '6  animeSources lists AnimeGG' 'AnimeGG not installed'
    } else {
        Step '6  animeSources lists AnimeGG' {
            $d = GqlData '{ animeSources { totalCount nodes { id name lang baseUrl extension { pkgName isLoaded } } } }'
            $src = @($d.animeSources.nodes) | Where-Object { $_.id -eq $AnimeSourceId } | Select-Object -First 1
            Assert ([bool]$src) "no source $AnimeSourceId (totalCount $($d.animeSources.totalCount))"
            Assert ($src.extension.pkgName -eq $AnimePkg) "extension $($src.extension.pkgName)"
            "totalCount=$($d.animeSources.totalCount) $($src.id) '$($src.name)' $($src.lang) $($src.baseUrl)"
        } | Out-Null
    }

    # 7. stdin close
    Step '7  closing stdin stops the server with exit code 0' {
        $p.StandardInput.Close()
        Assert ($p.WaitForExit(60000)) 'still running 60 s after stdin closed'
        Assert ($p.ExitCode -eq 0) "exit code $($p.ExitCode)"
        'exit code 0'
    } | Out-Null
} catch {
    $script:failed++
    Write-Host "FAIL smoke run aborted: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    WsClose
    if (-not $p.HasExited) {
        try { $p.StandardInput.Close() } catch { }
        if (-not $p.WaitForExit(30000)) { $p.Kill(); $p.WaitForExit() | Out-Null }
    }
}

# --- summary ---
$log = ''
if ($outTask) { $log += $outTask.Result }
$log += $errTask.Result
$interesting = @($log -split "`r?`n" | Where-Object { $_ -match 'ERROR|Refusing to load|failed' })
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
