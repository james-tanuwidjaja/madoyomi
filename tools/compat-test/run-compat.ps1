<#
.SYNOPSIS
  Phase 2 compat harness: runs the top 20 yuzono anime extensions on the real sidecar jar and writes
  docs/research/anime-compat.md.

.DESCRIPTION
  Starts the jar the way the Tauri shell does (tools\lib\MadoyomiServer.ps1) on a fresh data dir, adds the yuzono
  repo, and for each extension of top20.txt:
    install (with extensionInstallProgress) -> the source is usable -> popular page 1
    -> details + episodes (a Seasons entry: its first season) -> videos of the newest episode (then the oldest;
       lazy hosters are loaded until one has videos) -> resolve a video
  A failing entry is followed by the next popular one (up to -Tries). Every GraphQL call has a timeout
  (-CallTimeoutSec). It records per step: PASS/FAIL, time, error code and message; plus the lib version, content
  warning, hoster use (declared hoster list, lazy hosters), compatIssues, a probe of the resolved video URL and of the
  source's base URL (Cloudflare or down?), and an automatic failure class.

  Process health: after the repo add (baseline) and after each extension, the JVM's OS threads, Java threads, working
  set, private bytes and handles, the live heap after a full GC (jcmd GC.run + GC.heap_info), loaded/unloaded classes
  (jstat) and the extension class loaders (jcmd VM.classloader_stats). A JVM that dies is recorded (exit code,
  hs_err file) and restarted on the same data dir. At the end every extension is uninstalled and the state compared
  with the baseline; then stdin is closed and the exit code checked.

  Manual triage lives in tools\compat-test\triage.txt (pkg <TAB> verdict <TAB> note) and is merged into the report,
  which stays generated: -ReportOnly -Results <json> redraws it from a saved run.

  Raw results and the server log go to tools\compat-test\out\ (gitignored). Keep this file ASCII.

.PARAMETER Only
  Run only these extensions (short names such as animepahe, or package names). The report then goes to
  tools\compat-test\out\anime-compat-partial.md unless -Report is given.

.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -NoBuild
.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -NoBuild -Only animepahe,kisskh
.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -ReportOnly -Results tools\compat-test\out\compat-20261105-120000.json
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [switch]$NoBuild,
    [string]$DataDir,
    [switch]$KeepDataDir,
    [string]$AnimeRepo = 'https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json',
    [string]$List,
    [string[]]$Only,
    [int]$Tries = 3,
    [int]$CallTimeoutSec = 120,
    [int]$StartupTimeoutSec = 180,
    [string]$Report,
    [switch]$ReportOnly,
    [string]$Results,
    [string]$Triage
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\MadoyomiServer.ps1')

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21'
$Java = Join-Path $Jdk 'bin\java.exe'
$Jcmd = Join-Path $Jdk 'bin\jcmd.exe'
$Jstat = Join-Path $Jdk 'bin\jstat.exe'
$OutDir = Join-Path $PSScriptRoot 'out'
if (-not $List) { $List = Join-Path $PSScriptRoot 'top20.txt' }
if (-not $Triage) { $Triage = Join-Path $PSScriptRoot 'triage.txt' }
if (-not $Report) {
    $Report = if ($Only) { Join-Path $OutDir 'anime-compat-partial.md' } else { Join-Path $RepoRoot 'docs\research\anime-compat.md' }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$BrowserUA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]'Tls13'
} catch {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

# --- small helpers ---

function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}

function Short([string]$Pkg) { return $Pkg.Split('.')[-1] }

function Clip([string]$s, [int]$n = 240) {
    if ($null -eq $s) { return $null }
    $s = ($s -replace '\s+', ' ').Trim()
    if ($s.Length -gt $n) { return $s.Substring(0, $n) + '...' }
    return $s
}

# Runs a tool (jcmd, jstat) with a timeout; stdout, or $null on failure.
function Invoke-Tool([string]$Exe, [string]$Arguments, [int]$TimeoutSec = 60) {
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Exe
        $psi.Arguments = $Arguments
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEndAsync()
        $null = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($TimeoutSec * 1000)) { try { $p.Kill() } catch { }; return $null }
        $p.WaitForExit()
        if ($p.ExitCode -ne 0) { return $null }
        return $out.Result
    } catch {
        return $null
    }
}

# "[CODE] message details={...}" for each GraphQL error, joined by " | ".
function Format-GqlErrors($Json) {
    return (@($Json.errors) | ForEach-Object {
            $code = $_.extensions.code
            if (-not $code) { $code = 'GRAPHQL' }
            $t = "[$code] $(Clip $_.message 400)"
            if ($_.extensions.details) { $t += ' details=' + (Clip ($_.extensions.details | ConvertTo-Json -Compress -Depth 4) 300) }
            $t
        }) -join ' | '
}

# A GraphQL call with the call timeout; returns data, throws "[CODE] ..." on any error.
function Q([string]$Query, [hashtable]$Vars = @{}) {
    $r = Gql $Query $Vars -TimeoutSec $CallTimeoutSec
    if (-not $r.Json) { throw "[HTTP_$($r.Status)] $(Clip $r.Text)" }
    if ($r.Json.errors) { throw (Format-GqlErrors $r.Json) }
    return $r.Json.data
}

# Code and message of a caught error.
function Get-ErrorInfo($Err) {
    $msg = [string]$Err.Exception.Message
    $ex = $Err.Exception
    while ($ex.InnerException) { $ex = $ex.InnerException }
    if ($msg -match '(?s)^\[([A-Za-z_0-9]+)\] (.*)$') { return @{ code = $Matches[1]; message = (Clip $Matches[2] 600) } }
    if ($ex -is [System.Net.WebException] -and $ex.Status -eq [System.Net.WebExceptionStatus]::Timeout) {
        return @{ code = 'TIMEOUT'; message = "no answer within $CallTimeoutSec s" }
    }
    if ($msg -match 'timed out') { return @{ code = 'TIMEOUT'; message = "no answer within $CallTimeoutSec s" } }
    return @{ code = 'HARNESS'; message = (Clip "$($ex.GetType().Name): $($ex.Message)" 600) }
}

function New-Step { return [ordered]@{ status = 'SKIP'; ms = $null; code = $null; message = $null; info = $null } }

# Runs $Block into the step record $Rec (dynamic scope: the block sees the caller's variables). Returns $true on PASS.
function Invoke-Step([string]$Label, $Rec, [scriptblock]$Block) {
    $__sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $__info = (& $Block) -join ' '
        $Rec.status = 'PASS'
        $Rec.info = Clip $__info 400
    } catch {
        $__e = Get-ErrorInfo $_
        $Rec.status = 'FAIL'
        $Rec.code = $__e.code
        $Rec.message = $__e.message
    }
    $Rec.ms = $__sw.ElapsedMilliseconds
    if ($Rec.status -eq 'PASS') {
        Write-Host ("  PASS {0} ({1} ms) {2}" -f $Label, $Rec.ms, $Rec.info) -ForegroundColor Green
    } else {
        Write-Host ("  FAIL {0} ({1} ms) [{2}] {3}" -f $Label, $Rec.ms, $Rec.code, $Rec.message) -ForegroundColor Red
    }
    return ($Rec.status -eq 'PASS')
}

# GET $Url (first bytes) with $Headers; status, a few headers, Cloudflare markers, or the network failure.
function Invoke-Probe([string]$Url, $Headers, [int]$TimeoutSec = 20) {
    $res = [ordered]@{ url = $null; status = $null; contentType = $null; server = $null; cloudflare = $false; down = $false; error = $null }
    try { $res.url = ([Uri]$Url).Host } catch { $res.error = "bad url"; return $res }
    try {
        $req = [System.Net.HttpWebRequest]::Create($Url)
        $req.Method = 'GET'
        $req.Timeout = $TimeoutSec * 1000
        $req.ReadWriteTimeout = $TimeoutSec * 1000
        $req.AllowAutoRedirect = $true
        $req.AutomaticDecompression = [System.Net.DecompressionMethods]::GZip -bor [System.Net.DecompressionMethods]::Deflate
        $req.UserAgent = $BrowserUA
        $req.Accept = '*/*'
        $req.AddRange(0, 65535)
        foreach ($h in @($Headers)) {
            if (-not $h -or -not $h.name) { continue }
            switch ($h.name.ToLower()) {
                'user-agent' { $req.UserAgent = $h.value }
                'referer' { $req.Referer = $h.value }
                'accept' { $req.Accept = $h.value }
                'range' { }
                'host' { }
                'connection' { }
                'content-length' { }
                default { try { $req.Headers[$h.name] = $h.value } catch { } }
            }
        }
        $resp = $null
        try {
            $resp = $req.GetResponse()
        } catch [System.Net.WebException] {
            if (-not $_.Exception.Response) { throw }
            $resp = $_.Exception.Response
        }
        try {
            $res.status = [int]$resp.StatusCode
            $res.contentType = $resp.ContentType
            $res.server = $resp.Headers['Server']
            $cfm = $resp.Headers['cf-mitigated']
            $buf = New-Object byte[] 65536
            $stream = $resp.GetResponseStream()
            $n = 0
            try {
                while ($n -lt $buf.Length) {
                    $k = $stream.Read($buf, $n, $buf.Length - $n)
                    if ($k -le 0) { break }
                    $n += $k
                }
            } catch { }
            $body = [Text.Encoding]::UTF8.GetString($buf, 0, $n)
            $challenge = $body -match 'Just a moment|cf-chl|challenge-platform|cf_chl_opt|Attention Required! \| Cloudflare'
            $res.cloudflare = ($cfm -eq 'challenge') -or (($res.status -in 403, 429, 503) -and $challenge)
            $res.down = ($res.status -ge 520 -and $res.status -le 527) -or ($res.status -in 502, 504) -or ($res.status -eq 503 -and -not $res.cloudflare)
        } finally {
            $resp.Close()
        }
    } catch {
        $ex = $_.Exception
        while ($ex.InnerException -and -not ($ex -is [System.Net.WebException])) { $ex = $ex.InnerException }
        if ($ex -is [System.Net.WebException]) {
            $res.error = "$($ex.Status)"
            $res.down = $ex.Status -in @([System.Net.WebExceptionStatus]::NameResolutionFailure, [System.Net.WebExceptionStatus]::ConnectFailure, [System.Net.WebExceptionStatus]::Timeout)
        } else {
            $res.error = Clip "$($ex.GetType().Name): $($ex.Message)" 160
        }
    }
    return $res
}

# --- process health ---

function Get-Health([string]$At) {
    $h = [ordered]@{ at = $At; osThreads = $null; javaThreads = $null; wsMB = $null; privMB = $null; handles = $null
        heapUsedMB = $null; heapCommittedMB = $null; metaspaceMB = $null; classesLoaded = $null; classesUnloaded = $null
        extLoaders = $null; extLoaderClasses = $null
    }
    $p = $script:proc
    if (-not $p -or $p.HasExited) { return $h }
    $procId = $p.Id
    $null = Invoke-Tool $Jcmd "$procId GC.run" 120
    $heap = Invoke-Tool $Jcmd "$procId GC.heap_info"
    if ($heap) {
        if ($heap -match 'total (\d+)K, used (\d+)K') { $h.heapCommittedMB = [Math]::Round([double]$Matches[1] / 1024, 1); $h.heapUsedMB = [Math]::Round([double]$Matches[2] / 1024, 1) }
        if ($heap -match 'Metaspace\s+used (\d+)K') { $h.metaspaceMB = [Math]::Round([double]$Matches[1] / 1024, 1) }
    }
    $cls = Invoke-Tool $Jstat "-class $procId"
    if ($cls) {
        $lines = @($cls -split "`r?`n" | Where-Object { $_.Trim() })
        if ($lines.Count -ge 2) {
            $t = @($lines[1].Trim() -split '\s+')
            $h.classesLoaded = [int]$t[0]
            $h.classesUnloaded = [int]$t[2]
        }
    }
    $cl = Invoke-Tool $Jcmd "$procId VM.classloader_stats"
    if ($cl) {
        $rows = @($cl -split "`r?`n" | Where-Object { $_ -match '^0x[0-9a-fA-F]+\s' -and $_ -match 'ChildFirstURLClassLoader' })
        $h.extLoaders = $rows.Count
        $sum = 0
        foreach ($r in $rows) { $t = @($r.Trim() -split '\s+'); if ($t.Count -ge 4 -and $t[3] -match '^\d+$') { $sum += [int]$t[3] } }
        $h.extLoaderClasses = $sum
    }
    $th = Invoke-Tool $Jcmd "$procId Thread.print"
    if ($th) { $h.javaThreads = @($th -split "`r?`n" | Where-Object { $_ -match '^"' }).Count }
    $p.Refresh()
    if (-not $p.HasExited) {
        $h.osThreads = $p.Threads.Count
        $h.wsMB = [Math]::Round($p.WorkingSet64 / 1MB, 1)
        $h.privMB = [Math]::Round($p.PrivateMemorySize64 / 1MB, 1)
        $h.handles = $p.HandleCount
    }
    return $h
}

function Get-HsErrFiles {
    $files = @()
    foreach ($dir in @($script:DataDirFull, $RepoRoot, (Get-Location).Path)) {
        if ($dir -and (Test-Path -LiteralPath $dir)) {
            $files += @(Get-ChildItem -LiteralPath $dir -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
    }
    return @($files | Sort-Object -Unique)
}

$script:serverLogs = @()
$script:aborts = @()

function Save-ServerLog {
    $log = Get-MadoyomiServerLog
    if ($log) {
        $path = Join-Path $OutDir ("server-{0}-{1}.log" -f $script:stamp, $script:serverLogs.Count)
        Write-Utf8 $path $log
        $script:serverLogs += $path
    }
}

function Start-Server {
    $jvmArgs = "-XX:ErrorFile=`"$script:DataDirFull\hs_err_pid%p.log`""
    $ms = Start-MadoyomiServer -Java $Java -Jar $script:JarPath -DataDir $script:DataDirFull -TimeoutSec $StartupTimeoutSec -JvmArgs $jvmArgs
    Write-Host "MADOYOMI_PORT=$script:port after $ms ms (pid $($script:proc.Id))"
    return $ms
}

# If the JVM died, records the abort and restarts it; returns $true if it had died.
function Test-ServerAlive([string]$During) {
    if ($script:proc -and -not $script:proc.HasExited) { return $false }
    $code = if ($script:proc) { $script:proc.ExitCode } else { $null }
    Write-Host "  JVM ABORT during $During (exit code $code)" -ForegroundColor Red
    Start-Sleep -Seconds 2
    Save-ServerLog
    $script:aborts += [ordered]@{ during = $During; exitCode = $code; hsErr = @(Get-HsErrFiles); log = $script:serverLogs[-1] }
    Start-Server | Out-Null
    return $true
}

# --- the per-extension chain ---

$animeFields = 'anime { id title initialized fetchType } episodes { id name episodeNumber sourceOrder } seasons { id title }'
$hosterFields = 'index name lazy state errorCode errorMessage videos { index videoTitle videoUrl initialized }'

function Test-Extension($Row) {
    $x = [ordered]@{
        rank = $Row.rank; pkg = $Row.pkg; name = (Short $Row.pkg); listVersion = $Row.version; listLib = $Row.lib
        warning = $Row.warning; lang = $Row.lang
        version = $null; extensionLib = $null; contentWarning = $null; isTrusted = $null; isLoaded = $null
        sources = 0; sourceId = $null; sourceName = $null; sourceLang = $null; baseUrl = $null; supportsLatest = $null; isConfigurable = $null
        popularCount = $null; hasNextPage = $null; entriesTried = 0; entryTitle = $null; fetchType = $null; seasons = $null
        episodes = $null; episodeName = $null
        hosters = $null; hostersDeclared = $null; hostersLazy = $null; lazyLoaded = 0; hosterErrors = @(); videos = $null
        videoTitle = $null; videoHost = $null; videoProbe = $null; siteProbe = $null
        compatIssues = @(); steps = [ordered]@{ install = (New-Step); popular = (New-Step); details = (New-Step); videos = (New-Step); resolve = (New-Step) }
        result = 'FAIL'; failedStep = $null; autoCategory = $null; jvmAbort = $false; ms = $null; health = $null
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Write-Host ''
    Write-Host ("#{0} {1} (v{2}, lib {3}, {4})" -f $Row.rank, $Row.pkg, $Row.version, $Row.lib, $Row.warning)

    $ok = Invoke-Step 'install + source' $x.steps.install {
        $states = InstallWithProgress $x.pkg
        if ($states.Count -eq 0 -or $states[-1] -ne 'INSTALLED') { throw "[INSTALL] progress states: $($states -join ' -> ')" }
        $e = (Q 'query($p: String!) { extension(pkgName: $p) { versionName extensionLib contentWarning isLoaded isTrusted } }' @{ p = $x.pkg }).extension
        $x.version = $e.versionName; $x.extensionLib = $e.extensionLib; $x.contentWarning = $e.contentWarning
        $x.isLoaded = $e.isLoaded; $x.isTrusted = $e.isTrusted
        if (-not $e.isTrusted) { throw '[UNTRUSTED] installed but not trusted' }
        $srcQuery = 'query($p: String!) { animeSources(condition: {extensionPkgName: $p}) { nodes { id name lang baseUrl supportsLatest isConfigurable } } }'
        $srcs = @((Q $srcQuery @{ p = $x.pkg }).animeSources.nodes)
        $x.sources = $srcs.Count
        if ($srcs.Count -eq 0) { throw '[NO_SOURCE] the extension has no anime source' }
        $src = $srcs | Where-Object { $_.lang -eq 'en' } | Select-Object -First 1
        if (-not $src) { $src = $srcs[0] }
        $x.sourceId = [string]$src.id; $x.sourceName = $src.name; $x.sourceLang = $src.lang; $x.baseUrl = $src.baseUrl
        $x.supportsLatest = $src.supportsLatest; $x.isConfigurable = $src.isConfigurable
        if ($null -eq $src.supportsLatest) {
            # the source can't be used: its filters say why
            $r = Gql 'query($p: String!) { animeSources(condition: {extensionPkgName: $p}) { nodes { id filters { __typename } } } }' @{ p = $x.pkg } -TimeoutSec $CallTimeoutSec
            if ($r.Json.errors) { throw (Format-GqlErrors $r.Json) }
            throw '[NOT_USABLE] supportsLatest is null'
        }
        "v$($x.version) lib $($x.extensionLib) $($x.contentWarning) sources=$($x.sources) '$($x.sourceName)' ($($x.sourceLang)) $($x.baseUrl) latest=$($x.supportsLatest) configurable=$($x.isConfigurable)"
    }

    $animes = @()
    if ($ok) {
        $ok = Invoke-Step 'popular' $x.steps.popular {
            $q = 'mutation($s: LongString!) { fetchSourceAnime(input: {source: $s, type: POPULAR, page: 1}) { hasNextPage animes { id title } } }'
            $d = $null
            $note = ''
            try {
                $d = Q $q @{ s = $x.sourceId }
            } catch {
                # one retry: a transient network error shouldn't decide the result
                $note = " (retried after: $(Clip $_.Exception.Message 120))"
                $d = Q $q @{ s = $x.sourceId }
            }
            $script:lastAnimes = @($d.fetchSourceAnime.animes)
            $x.popularCount = $script:lastAnimes.Count; $x.hasNextPage = $d.fetchSourceAnime.hasNextPage
            if ($script:lastAnimes.Count -eq 0) { throw '[EMPTY] popular page 1 has no entries' }
            "entries=$($x.popularCount) hasNextPage=$($x.hasNextPage)$note"
        }
        if ($ok) { $animes = @($script:lastAnimes) }
    }

    if ($ok) {
        $attempts = @()
        foreach ($a in @($animes | Select-Object -First $Tries)) {
            $x.entriesTried++
            $att = [ordered]@{ title = $a.title; details = (New-Step); videos = (New-Step); resolve = (New-Step); depth = 0; ctx = @{ lazyLoaded = 0 } }
            $ctx = $att.ctx
            $label = "entry $($x.entriesTried)"
            if (Invoke-Step "$label details + episodes" $att.details {
                    $q = "mutation(`$id: Int!) { fetchAnimeAndEpisodes(input: {id: `$id, fetchAnime: true, fetchEpisodes: true}) { $animeFields } }"
                    $d = (Q $q @{ id = [int]$a.id }).fetchAnimeAndEpisodes
                    $ctx.fetchType = $d.anime.fetchType
                    $eps = @($d.episodes)
                    $seasons = @($d.seasons)
                    $ctx.seasons = $seasons.Count
                    $via = ''
                    if ($d.anime.fetchType -eq 'Seasons') {
                        if ($seasons.Count -eq 0) { throw '[EMPTY] a Seasons entry with no seasons' }
                        $d2 = (Q $q @{ id = [int]$seasons[0].id }).fetchAnimeAndEpisodes
                        $eps = @($d2.episodes)
                        $via = " via season 1 of $($seasons.Count)"
                    }
                    if ($eps.Count -eq 0) { throw "[EMPTY] no episodes (fetchType $($d.anime.fetchType)$via)" }
                    $ctx.episodes = $eps
                    "fetchType=$($d.anime.fetchType) episodes=$($eps.Count)$via"
                }) {
                $att.depth = 1
                if (Invoke-Step "$label videos" $att.videos {
                        $sorted = @($ctx.episodes | Sort-Object { [int]$_.sourceOrder } -Descending)
                        $cands = @($sorted[0])
                        if ($sorted.Count -gt 1) { $cands += $sorted[-1] }
                        $lastErr = '[NO_VIDEOS] no episode tried'
                        foreach ($ep in $cands) {
                            $r = Gql "mutation(`$id: Int!) { fetchEpisodeVideos(input: {episodeId: `$id}) { hosters { $hosterFields } } }" @{ id = [int]$ep.id } -TimeoutSec $CallTimeoutSec
                            if (-not $r.Json) { $lastErr = "[HTTP_$($r.Status)] $(Clip $r.Text)"; continue }
                            if ($r.Json.errors) { $lastErr = Format-GqlErrors $r.Json; continue }
                            $hosters = @($r.Json.data.fetchEpisodeVideos.hosters)
                            $ctx.hosters = $hosters.Count
                            $ctx.hostersDeclared = @($hosters | Where-Object { $_.name -ne 'no_hoster_list' }).Count -gt 0
                            $ctx.hostersLazy = @($hosters | Where-Object { $_.lazy }).Count
                            $ctx.hosterErrors = @($hosters | Where-Object { $_.state -eq 'ERROR' } | ForEach-Object { "$($_.name): [$($_.errorCode)] $(Clip $_.errorMessage 120)" })
                            $ready = @($hosters | Where-Object { $_.state -eq 'READY' -and @($_.videos).Count -gt 0 })
                            if ($ready.Count -eq 0) {
                                foreach ($h in @($hosters | Where-Object { $_.state -eq 'IDLE' } | Select-Object -First 5)) {
                                    $r2 = Gql "mutation(`$id: Int!, `$h: Int!) { fetchHosterVideos(input: {episodeId: `$id, hosterIndex: `$h}) { hoster { $hosterFields } } }" @{ id = [int]$ep.id; h = [int]$h.index } -TimeoutSec $CallTimeoutSec
                                    if (-not $r2.Json -or $r2.Json.errors) { $ctx.hosterErrors += "$($h.name) (lazy): $(if ($r2.Json) { Format-GqlErrors $r2.Json } else { "HTTP $($r2.Status)" })"; continue }
                                    $hh = $r2.Json.data.fetchHosterVideos.hoster
                                    $ctx.lazyLoaded++
                                    if ($hh.state -eq 'READY' -and @($hh.videos).Count -gt 0) { $ready = @($hh); break }
                                    $ctx.hosterErrors += "$($hh.name) (lazy): $($hh.state) [$($hh.errorCode)] $(Clip $hh.errorMessage 120)"
                                }
                            }
                            if ($ready.Count -gt 0) {
                                $ctx.episode = $ep
                                $ctx.ready = $ready
                                $ctx.videos = @($ready[0].videos).Count
                                return "episode '$(Clip $ep.name 60)' hosters=$($hosters.Count) declared=$($ctx.hostersDeclared) lazy=$($ctx.hostersLazy) lazyLoaded=$($ctx.lazyLoaded) '$(Clip $ready[0].name 40)' videos=$($ctx.videos)"
                            }
                            $err = @($hosters | Where-Object { $_.state -eq 'ERROR' } | Select-Object -First 1)
                            if ($err.Count -gt 0) {
                                $lastErr = "[$($err[0].errorCode)] hoster '$($err[0].name)': $(Clip $err[0].errorMessage 300) (hosters=$($hosters.Count))"
                            } else {
                                $lastErr = "[NO_VIDEOS] episode '$(Clip $ep.name 60)': hosters=$($hosters.Count), none with videos ($(($hosters | ForEach-Object { "$($_.name)=$($_.state)" }) -join ', '))"
                            }
                        }
                        throw $lastErr
                    }) {
                    $att.depth = 2
                    if (Invoke-Step "$label resolve" $att.resolve {
                            $lastErr = '[UNRESOLVED] no video tried'
                            foreach ($h in $ctx.ready) {
                                foreach ($v in @($h.videos | Select-Object -First 3)) {
                                    $r = Gql 'mutation($id: Int!, $h: Int!, $v: Int!) { resolveEpisodeVideo(input: {episodeId: $id, hosterIndex: $h, videoIndex: $v}) { video { videoTitle videoUrl initialized headers { name value } } } }' @{ id = [int]$ctx.episode.id; h = [int]$h.index; v = [int]$v.index } -TimeoutSec $CallTimeoutSec
                                    if (-not $r.Json) { $lastErr = "[HTTP_$($r.Status)] $(Clip $r.Text)"; continue }
                                    if ($r.Json.errors) { $lastErr = Format-GqlErrors $r.Json; continue }
                                    $vid = $r.Json.data.resolveEpisodeVideo.video
                                    if ($vid.videoUrl -and $vid.initialized -and $vid.videoUrl -ne 'null') {
                                        $ctx.video = $vid
                                        $vh = '?'
                                        try { $vh = ([Uri]$vid.videoUrl).Host } catch { }
                                        return "'$(Clip $vid.videoTitle 60)' on $vh headers=$((@($vid.headers) | ForEach-Object { $_.name }) -join ',')"
                                    }
                                    $lastErr = "[UNRESOLVED] '$(Clip $vid.videoTitle 60)' initialized=$($vid.initialized) url='$(Clip $vid.videoUrl 80)'"
                                }
                            }
                            throw $lastErr
                        }) {
                        $att.depth = 3
                    }
                }
            }
            $attempts += $att
            if (Test-ServerAlive "$($x.name) entry $($x.entriesTried)") { $x.jvmAbort = $true; break }
            if ($att.depth -eq 3) { break }
        }
        # the attempt that got furthest (the first one on a tie)
        $best = $null
        foreach ($att in $attempts) { if (-not $best -or $att.depth -gt $best.depth) { $best = $att } }
        if ($best) {
            $x.steps.details = $best.details; $x.steps.videos = $best.videos; $x.steps.resolve = $best.resolve
            $c = $best.ctx
            $x.entryTitle = $best.title; $x.fetchType = $c.fetchType; $x.seasons = $c.seasons
            if ($c.episodes) { $x.episodes = @($c.episodes).Count }
            if ($c.episode) { $x.episodeName = $c.episode.name }
            $x.hosters = $c.hosters; $x.hostersDeclared = $c.hostersDeclared; $x.hostersLazy = $c.hostersLazy
            $x.lazyLoaded = [int]$c.lazyLoaded; $x.hosterErrors = @($c.hosterErrors); $x.videos = $c.videos
            if ($c.video) {
                $x.videoTitle = $c.video.videoTitle
                try { $x.videoHost = ([Uri]$c.video.videoUrl).Host } catch { }
                $x.videoProbe = Invoke-Probe $c.video.videoUrl $c.video.headers
                Write-Host ("  probe video: HTTP {0} {1} {2}" -f $x.videoProbe.status, $x.videoProbe.contentType, $x.videoProbe.error)
            }
        }
    }

    if (-not (Test-ServerAlive "$($x.name) (after the chain)")) {
        try {
            $ci = (Q 'query($p: String!) { extension(pkgName: $p) { compatIssues { kind target origin context count } } }' @{ p = $x.pkg }).extension
            $x.compatIssues = @($ci.compatIssues | ForEach-Object { [ordered]@{ kind = $_.kind; target = $_.target; origin = $_.origin; context = $_.context; count = $_.count } })
        } catch { }
    } else {
        $x.jvmAbort = $true
    }
    if ($x.baseUrl) {
        $x.siteProbe = Invoke-Probe $x.baseUrl @()
        Write-Host ("  probe site {0}: HTTP {1} server={2} cloudflare={3} down={4} {5}" -f $x.siteProbe.url, $x.siteProbe.status, $x.siteProbe.server, $x.siteProbe.cloudflare, $x.siteProbe.down, $x.siteProbe.error)
    }

    $failed = @('install', 'popular', 'details', 'videos', 'resolve') | Where-Object { $x.steps[$_].status -ne 'PASS' } | Select-Object -First 1
    if (-not $failed) { $x.result = 'PASS' } else { $x.failedStep = $failed }
    $x.autoCategory = Get-AutoCategory $x
    $x.ms = $sw.ElapsedMilliseconds
    $x.health = Get-Health $x.name
    $color = if ($x.result -eq 'PASS') { 'Green' } else { 'Red' }
    Write-Host ("  => {0}{1} ({2} s) threads={3} heap={4} MB loaders={5}" -f $x.result, $(if ($x.failedStep) { " at $($x.failedStep) [$($x.autoCategory)]" } else { '' }), [Math]::Round($x.ms / 1000, 1), $x.health.osThreads, $x.health.heapUsedMB, $x.health.extLoaders) -ForegroundColor $color
    return $x
}

# CLOUDFLARE / SITE_DOWN / HOST_GAP / SOURCE_ERROR / JVM_ABORT / INTERNAL for a failed extension.
function Get-AutoCategory($x) {
    if ($x.result -eq 'PASS') { return $null }
    if ($x.jvmAbort) { return 'JVM_ABORT' }
    $s = $x.steps[$x.failedStep]
    $code = [string]$s.code
    $msg = [string]$s.message
    $runtimeGap = @($x.compatIssues | Where-Object { $_.origin -eq 'RUNTIME' -and $_.kind -ne 'CLASS_NOT_FOUND' }).Count -gt 0
    if ($code -eq 'EXTENSION_INCOMPATIBLE' -or ($code -eq 'LOAD_FAILED' -and $msg -match 'missing') -or $runtimeGap) { return 'HOST_GAP' }
    # the server's own word (SOURCE_FAILED reason CLOUDFLARE since Stage 5), or a 403/503 from a site whose base URL
    # answers PowerShell's probe with a challenge. A probe challenge alone doesn't count: OkHttp may get through
    # where PowerShell doesn't (hentaihaven browses fine behind one)
    $httpCf = $msg -match '"httpCode":\s*(403|503)' -or $msg -match '\bHTTP (403|503)\b'
    if ($msg -match '(?i)cloudflare|cf_clearance|just a moment' -or ($httpCf -and $x.siteProbe -and $x.siteProbe.cloudflare)) { return 'CLOUDFLARE' }
    # a host the extension needs is unreachable (the site, or a video host of a hoster)
    if ($msg -match '(?i)failed to connect|unable to resolve|unknownhost|no address associated|connection refused|timed out') { return 'SITE_DOWN' }
    if ($x.siteProbe -and $x.siteProbe.down -and $x.failedStep -in @('popular', 'details')) { return 'SITE_DOWN' }
    if ($code -in @('SOURCE_FAILED', 'EMPTY', 'NO_VIDEOS', 'UNRESOLVED', 'TIMEOUT')) { return 'SOURCE_ERROR' }
    return 'INTERNAL'
}

# --- the report ---

function Read-Triage([string]$Path) {
    $t = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $t }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line.Trim() -eq '' -or $line.StartsWith('#')) { continue }
        $c = $line -split "`t", 3
        if ($c.Count -lt 2) { continue }
        $t[(Short $c[0].Trim())] = @{ verdict = $c[1].Trim(); note = $(if ($c.Count -ge 3) { $c[2].Trim() } else { '' }) }
    }
    return $t
}

function Cell($v) {
    if ($null -eq $v -or "$v" -eq '') { return '' }
    return ("$v" -replace '\|', '\|' -replace "`r?`n", ' ')
}

function StepCell($s) {
    if (-not $s -or $s.status -eq 'SKIP') { return '-' }
    if ($s.status -eq 'PASS') { return "ok $([Math]::Round([double]$s.ms / 1000, 1))s" }
    return "**FAIL** $($s.code)"
}

function Write-CompatReport($R, [string]$Path) {
    $tri = Read-Triage $Triage
    $exts = @($R.extensions)
    $n = $exts.Count
    foreach ($x in $exts) {
        $t = $tri[$x.name]
        $cat = $x.autoCategory
        $note = ''
        if ($t) { if ($t.verdict -and $t.verdict -ne '-') { $cat = $t.verdict.ToUpper() -replace '-', '_' }; $note = $t.note }
        $x | Add-Member -NotePropertyName category -NotePropertyValue $cat -Force
        $x | Add-Member -NotePropertyName triageNote -NotePropertyValue $note -Force
    }
    $pass = @($exts | Where-Object { $_.result -eq 'PASS' })
    $fail = @($exts | Where-Object { $_.result -ne 'PASS' })
    $safe = @($exts | Where-Object { $_.warning -eq 'SAFE' })
    $safePass = @($safe | Where-Object { $_.result -eq 'PASS' })
    $byCat = @{}
    foreach ($x in $fail) { $byCat[$x.category] = 1 + [int]$byCat[$x.category] }
    $cf = [int]$byCat['CLOUDFLARE']
    $excluded = @($fail | Where-Object { $_.category -in @('UPSTREAM_BROKEN', 'SITE_DOWN') })

    $sb = New-Object Text.StringBuilder
    $L = { param($s) [void]$sb.AppendLine($s) }
    & $L '# Anime extension compatibility (Phase 2 compat harness)'
    & $L ''
    & $L "> Generated by ``tools/compat-test/run-compat.ps1`` on $($R.run.started) (duration $($R.run.durationMin) min). Do not edit by hand: triage notes go in ``tools/compat-test/triage.txt``, then ``run-compat.ps1 -ReportOnly -Results <json>`` redraws this file."
    & $L ''
    & $L "- Server: $($R.run.server) (``$($R.run.jarName)``), JVM $($R.run.javaVersion), max heap $($R.run.maxHeapMB) MB (JVM default), started in $($R.run.startMs) ms."
    & $L "- Repo: $($R.run.repo) ($($R.run.repoExtensions) anime extensions listed on the run date)."
    & $L "- List: ``tools/compat-test/top20.txt`` ($($R.run.listHeader)); the version column is the one installed (the repo's current)."
    & $L "- Chain per extension: install (trusted, loaded) and the source usable -> popular page 1 -> details + episodes (a Seasons entry: its first season) -> videos of the newest episode (then the oldest; lazy hosters loaded until one has videos) -> resolve a video. Up to $($R.run.tries) popular entries are tried; each GraphQL call has a $($R.run.callTimeoutSec) s timeout. An extension passes when one entry gets through the whole chain."
    & $L ''
    & $L '## Summary'
    & $L ''
    & $L "| | Pass |"
    & $L "|---|---|"
    & $L "| All | **$($pass.Count) of $n** |"
    & $L "| SAFE only | $($safePass.Count) of $($safe.Count) |"
    & $L "| MIXED / NSFW | $(@($pass | Where-Object { $_.warning -ne 'SAFE' }).Count) of $(@($exts | Where-Object { $_.warning -ne 'SAFE' }).Count) |"
    & $L "| Excluding upstream-broken and site-down ($($excluded.Count)) | $($pass.Count) of $($n - $excluded.Count) |"
    & $L ''
    if ($fail.Count -gt 0) {
        & $L 'Failures by class (the triage verdict where there is one, else the automatic class):'
        & $L ''
        & $L '| Class | Extensions |'
        & $L '|---|---|'
        foreach ($k in @($byCat.Keys | Sort-Object)) {
            & $L "| $k | $($byCat[$k]): $((@($fail | Where-Object { $_.category -eq $k }) | ForEach-Object { $_.name }) -join ', ') |"
        }
        & $L ''
    }
    & $L "**Cloudflare:** $cf of $n blocked. Without them the result would be $($pass.Count + $cf) of $n$(if ($pass.Count -lt 18 -and $pass.Count + $cf -ge 18) { ': Cloudflare alone keeps the harness below 18/20 (a solver stage decides it).' } elseif ($pass.Count -ge 18) { '; the harness is at 18/20 or above without a solver.' } else { '; Cloudflare alone does not decide 18/20.' })"
    & $L ''
    & $L '## Results'
    & $L ''
    & $L '| # | Extension | Warning | Lib | Version | Install | Popular | Details + episodes | Videos | Resolve | Result | Class |'
    & $L '|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($x in $exts) {
        $res = if ($x.result -eq 'PASS') { 'PASS' } else { "**FAIL**" }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} |" -f $x.rank, $x.name, $x.warning, (Cell $x.extensionLib), (Cell $(if ($x.version) { $x.version } else { $x.listVersion })), (StepCell $x.steps.install), (StepCell $x.steps.popular), (StepCell $x.steps.details), (StepCell $x.steps.videos), (StepCell $x.steps.resolve), $res, (Cell $x.category))
    }
    & $L ''
    & $L '## Details'
    & $L ''
    & $L 'Entry titles are shown for SAFE extensions only. Hosters: total / declared (the extension has hoster methods of its own; `no` = its video list wrapped in Aniyomi''s `no_hoster_list`) / lazy (loaded on request). The probe GETs the first bytes of the resolved URL with its headers from outside the server (informational, not counted).'
    & $L ''
    & $L '| Extension | Sources | Popular | Entries tried | Entry | Episodes | Hosters (total / declared / lazy) | Videos | Video host | Probe | Compat issues | Time |'
    & $L '|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($x in $exts) {
        $entry = if ($x.warning -eq 'SAFE') { Cell (Clip $x.entryTitle 40) } elseif ($x.entryTitle) { '(not shown)' } else { '' }
        $eps = if ($null -ne $x.episodes) { "$($x.episodes)$(if ($x.fetchType -eq 'Seasons') { " (season 1 of $($x.seasons))" })" } else { '' }
        $hos = if ($null -ne $x.hosters) { "$($x.hosters) / $(if ($x.hostersDeclared) { 'yes' } else { 'no' }) / $($x.hostersLazy)$(if ($x.lazyLoaded) { " ($($x.lazyLoaded) loaded)" })" } else { '' }
        $probe = if ($x.videoProbe) { "$(if ($x.videoProbe.status) { "HTTP $($x.videoProbe.status) $(Clip $x.videoProbe.contentType 30)" } else { $x.videoProbe.error })" } else { '' }
        $issues = @($x.compatIssues)
        $ci = if ($issues.Count) { "$($issues.Count): " + ((@($issues | Group-Object { "$($_.origin) $($_.kind)" }) | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join ', ') } else { 'none' }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} s |" -f $x.name, $x.sources, (Cell $x.popularCount), $x.entriesTried, $entry, $eps, $hos, (Cell $x.videos), (Cell $x.videoHost), (Cell $probe), (Cell $ci), [Math]::Round([double]$x.ms / 1000, 1))
    }
    & $L ''
    & $L '## Failures and triage'
    & $L ''
    if ($fail.Count -eq 0) { & $L 'None.' }
    & $L 'Classes: **HOST_GAP** (Madoyomi lacks an API the extension needs: ours to fix), **CLOUDFLARE** (the site answers with a Cloudflare challenge; needs a solver), **SITE_DOWN** (DNS, connection or 5xx at the site), **UPSTREAM_BROKEN** (the extension no longer matches its site; broken in Aniyomi too), **SOURCE_ERROR** (the source failed, not yet triaged further), **INTERNAL**, **JVM_ABORT**. The site probe is a plain GET of the source''s base URL from PowerShell with a browser user agent, so it can differ from what OkHttp gets.'
    & $L ''
    foreach ($x in $fail) {
        $s = $x.steps.($x.failedStep)
        $sp = $x.siteProbe
        $site = if ($sp) { "site probe ``$($sp.url)``: $(if ($sp.status) { "HTTP $($sp.status)" } else { $sp.error })$(if ($sp.server) { ", server $($sp.server)" })$(if ($sp.cloudflare) { ', Cloudflare challenge' })" } else { 'no site probe' }
        & $L "- **$($x.name)** ($($x.warning)): $($x.category) at $($x.failedStep) (automatic: $($x.autoCategory)). ``[$($s.code)] $(Cell (Clip $s.message 300))``. $site."
        if (@($x.hosterErrors).Count -gt 0) { & $L "  - Hoster errors: $((@($x.hosterErrors) | Select-Object -First 4 | ForEach-Object { Cell (Clip $_ 160) }) -join '; ')" }
        if ($x.triageNote) { & $L "  - Triage: $($x.triageNote)" }
    }
    & $L ''
    & $L '## Compat issues seen at runtime'
    & $L ''
    $rt = @($exts | ForEach-Object { $e = $_; @($e.compatIssues) | Where-Object { $_.origin -eq 'RUNTIME' } | ForEach-Object { "| $($e.name) | $($_.kind) | ``$(Cell (Clip $_.target 100))`` | $(Cell (Clip $_.context 60)) | $($_.count) |" } })
    if ($rt.Count -eq 0) { & $L 'None (the static issues of `docs/research/compat-scan.md` are counted in the details table).' } else {
        & $L '| Extension | Kind | Target | Context | Count |'
        & $L '|---|---|---|---|---|'
        $rt | ForEach-Object { & $L $_ }
    }
    & $L ''
    & $L '## Process health'
    & $L ''
    & $L 'One JVM for the whole run (phase-1 section 8). Measured after the repo add (baseline), after each extension and after uninstalling all of them; heap is the live heap after a full GC (`jcmd GC.run`, `GC.heap_info`), loaders are the extension class loaders alive (`ChildFirstURLClassLoader` in `jcmd VM.classloader_stats`).'
    & $L ''
    & $L '| After | OS threads | Java threads | Working set MB | Private MB | Handles | Heap used MB | Metaspace MB | Classes loaded / unloaded | Ext loaders (classes) |'
    & $L '|---|---|---|---|---|---|---|---|---|---|'
    $rows = @($R.run.baseline) + @($exts | ForEach-Object { $_.health }) + @($R.run.end)
    foreach ($h in $rows) {
        if (-not $h) { continue }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} / {9} | {10} ({11}) |" -f $h.at, (Cell $h.osThreads), (Cell $h.javaThreads), (Cell $h.wsMB), (Cell $h.privMB), (Cell $h.handles), (Cell $h.heapUsedMB), (Cell $h.metaspaceMB), (Cell $h.classesLoaded), (Cell $h.classesUnloaded), (Cell $h.extLoaders), (Cell $h.extLoaderClasses))
    }
    & $L ''
    $aborts = @($R.run.aborts)
    & $L "- JVM aborts: $(if ($aborts.Count -eq 0) { 'none' } else { ($aborts | ForEach-Object { "during $($_.during), exit code $($_.exitCode), hs_err: $(if (@($_.hsErr).Count) { (@($_.hsErr) | ForEach-Object { Split-Path $_ -Leaf }) -join ', ' } else { 'none' })" }) -join '; ' })."
    & $L "- hs_err files after the run: $(if (@($R.run.hsErr).Count -eq 0) { 'none' } else { (@($R.run.hsErr) | ForEach-Object { Split-Path $_ -Leaf }) -join ', ' })."
    & $L "- Server log: $($R.run.logOom) OutOfMemoryError, $($R.run.logSoe) StackOverflowError lines."
    & $L "- Uninstall of all $($R.run.uninstalled) installed extensions: $($R.run.uninstallErrors) errors."
    & $L "- Exit after stdin closed: $(if ($null -eq $R.run.exitCode) { 'killed (still running after 60 s)' } else { "exit code $($R.run.exitCode)" })."
    Write-Utf8 $Path ($sb.ToString())
    Write-Host "report: $Path"
}

# --- -ReportOnly ---
if ($ReportOnly) {
    if (-not $Results) {
        $Results = (Get-ChildItem -LiteralPath $OutDir -Filter 'compat-*.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
    $R = [IO.File]::ReadAllText((Resolve-Path $Results).Path) | ConvertFrom-Json
    Write-CompatReport $R $Report
    exit 0
}

# --- the run ---
$rows = @()
$listHeader = ''
foreach ($line in [IO.File]::ReadAllLines($List)) {
    if ($line -match 'snapshot (\d{4}-\d{2}-\d{2})') { $listHeader = "snapshot $($Matches[1])" }
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    $c = $line -split "`t"
    $rows += [pscustomobject]@{ rank = [int]$c[0]; pkg = $c[1]; version = $c[2]; lib = $c[3]; warning = $c[4]; lang = $c[5]; hits = $c[6] }
}
if ($Only) {
    $want = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { Short $_.Trim() })
    $rows = @($rows | Where-Object { (Short $_.pkg) -in $want })
    if ($rows.Count -eq 0) { throw "none of -Only $($Only -join ',') is in $List" }
}

$script:JarPath = Resolve-MadoyomiJar -RepoRoot $RepoRoot -Jar $Jar -NoBuild:$NoBuild
$script:stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tempDataDir = -not $DataDir
if ($tempDataDir) { $DataDir = Join-Path $env:TEMP "madoyomi-compat-$script:stamp" }
$script:DataDirFull = [IO.Path]::GetFullPath($DataDir)
New-Item -ItemType Directory -Force -Path $script:DataDirFull | Out-Null
$eap = $ErrorActionPreference
$ErrorActionPreference = 'Continue' # java -version writes to stderr
$javaVersion = (& $Java -version 2>&1 | Select-Object -First 1 | ForEach-Object { "$_" }) -replace '^.*version "([^"]+)".*$', '$1'
$ErrorActionPreference = $eap

Write-Host "jar:      $script:JarPath"
Write-Host "data dir: $script:DataDirFull"
Write-Host "list:     $List ($($rows.Count) extensions)"
Write-Host "repo:     $AnimeRepo"

$started = Get-Date
$run = [ordered]@{
    started = $started.ToString('yyyy-MM-dd HH:mm'); durationMin = $null; jarName = (Split-Path $script:JarPath -Leaf); server = $null
    javaVersion = $javaVersion; maxHeapMB = $null; startMs = $null; repo = $AnimeRepo; repoExtensions = $null; listHeader = $listHeader
    tries = $Tries; callTimeoutSec = $CallTimeoutSec; baseline = $null; end = $null; aborts = @(); hsErr = @()
    logOom = 0; logSoe = 0; uninstalled = 0; uninstallErrors = 0; exitCode = $null; serverLogs = @(); dataDir = $script:DataDirFull
}
$extResults = @()
$fatal = $null
try {
    $run.startMs = Start-Server
    $about = Q '{ aboutServer { name version } }'
    $run.server = "$($about.aboutServer.name) $($about.aboutServer.version)"
    $flags = Invoke-Tool $Jcmd "$($script:proc.Id) VM.flags"
    if ($flags -match 'MaxHeapSize=(\d+)') { $run.maxHeapMB = [Math]::Round([double]$Matches[1] / 1MB) }

    $s = (Q 'mutation($url: String!) { addExtensionStore(input: {indexUrl: $url, type: ANIME}) { extensionStore { indexUrl signingKeyTrusted lastError } } }' @{ url = $AnimeRepo }).addExtensionStore.extensionStore
    if (-not $s.signingKeyTrusted -or $s.lastError) { throw "repo not usable: trusted=$($s.signingKeyTrusted) lastError=$($s.lastError)" }
    $exts = @((Q 'mutation { fetchExtensions(input: {type: ANIME}) { extensions { pkgName } } }').fetchExtensions.extensions)
    $run.repoExtensions = $exts.Count
    Write-Host "repo: $($exts.Count) anime extensions"
    $listed = @{}
    foreach ($e in $exts) { $listed[$e.pkgName] = $true }
    $run.baseline = Get-Health 'baseline (repo added)'

    foreach ($row in $rows) {
        if (-not $listed[$row.pkg]) {
            Write-Host "#$($row.rank) $($row.pkg): not in the repo any more" -ForegroundColor Red
        }
        $extResults += Test-Extension $row
        # keep partial results on disk while the run goes on
        $run.aborts = @($script:aborts)
        Write-Utf8 (Join-Path $OutDir "compat-$script:stamp.json") ([ordered]@{ run = $run; extensions = $extResults } | ConvertTo-Json -Depth 12)
    }

    Write-Host ''
    Write-Host 'Uninstalling every extension...'
    foreach ($x in $extResults) {
        if ($x.steps.install.status -ne 'PASS' -and -not $x.version) { continue }
        try {
            $null = Q 'mutation($id: String!) { updateExtension(input: {id: $id, patch: {uninstall: true}}) { extension { isInstalled } } }' @{ id = $x.pkg }
            $run.uninstalled++
        } catch {
            $run.uninstallErrors++
            Write-Host "  uninstall $($x.name): $($_.Exception.Message)" -ForegroundColor Red
        }
    }
    Test-ServerAlive 'uninstall' | Out-Null
    $run.end = Get-Health 'all uninstalled'
} catch {
    $fatal = $_.Exception.Message
    Write-Host "FATAL: $fatal" -ForegroundColor Red
} finally {
    $run.exitCode = Stop-MadoyomiServer -TimeoutSec 60
    Save-ServerLog
}

$run.durationMin = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1)
$run.aborts = @($script:aborts)
$run.hsErr = @(Get-HsErrFiles)
$run.serverLogs = @($script:serverLogs)
foreach ($p in $script:serverLogs) {
    $t = [IO.File]::ReadAllText($p)
    $run.logOom += ([regex]::Matches($t, 'OutOfMemoryError')).Count
    $run.logSoe += ([regex]::Matches($t, 'StackOverflowError')).Count
}
$jsonPath = Join-Path $OutDir "compat-$script:stamp.json"
Write-Utf8 $jsonPath ([ordered]@{ run = $run; extensions = $extResults; fatal = $fatal } | ConvertTo-Json -Depth 12)
Write-Host "results: $jsonPath"

$R = [IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json
Write-CompatReport $R $Report

$passCount = @($extResults | Where-Object { $_.result -eq 'PASS' }).Count
Write-Host ''
Write-Host "RESULT: $passCount of $($extResults.Count) passed; JVM aborts: $($script:aborts.Count); exit code $($run.exitCode)"
if ($tempDataDir -and -not $KeepDataDir -and -not $fatal) {
    Remove-Item -LiteralPath $script:DataDirFull -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "data dir kept: $script:DataDirFull"
}
if ($fatal) { exit 2 }
exit 0
