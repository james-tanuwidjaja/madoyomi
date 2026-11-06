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

  Per configurable source, a preference is changed, read back and set back; a SEARCH with one filter off its default
  is sent to the site (both recorded in the report, not part of the pass). After the uninstall, extension class
  loaders still alive are measured again once idle threads have ended (-SettleSec); any left get a heap dump and the
  GC-root path of each (lib\HprofLoaderRoots.java) in the report.

  -Kind Manga runs the Keiyoushi manga sample (manga-sample.txt) through upstream's manga API instead: install ->
  popular -> details + chapters -> pages -> the first page image, into docs/research/manga-compat.md (Phase 2's
  "no regression in manga").

.PARAMETER Kind
  Anime (default: top20.txt, yuzono, docs/research/anime-compat.md) or Manga (manga-sample.txt, Keiyoushi,
  docs/research/manga-compat.md).

.PARAMETER Only
  Run only these extensions (short names such as animepahe, or package names). The report then goes to
  tools\compat-test\out\<kind>-compat-partial.md unless -Report is given.

.PARAMETER SettleSec
  How long to wait after the uninstall before measuring the extension class loaders again (idle source-pool and
  OkHttp threads end after 60 s). 0 skips it.

.PARAMETER NoHeapDump
  Don't take a heap dump when extension class loaders are still alive at the end.

.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -NoBuild
.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -NoBuild -Only animepahe,kisskh
.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -ReportOnly -Results tools\compat-test\out\compat-20261105-120000.json
.EXAMPLE
  .\tools\compat-test\run-compat.ps1 -NoBuild -Kind Manga
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [switch]$NoBuild,
    [string]$DataDir,
    [switch]$KeepDataDir,
    [ValidateSet('Anime', 'Manga')]
    [string]$Kind = 'Anime',
    [string]$AnimeRepo = 'https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json',
    [string]$MangaRepo = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb',
    [string]$List,
    [string[]]$Only,
    [int]$Tries = 3,
    [int]$CallTimeoutSec = 120,
    [int]$StartupTimeoutSec = 180,
    [string]$Report,
    [switch]$ReportOnly,
    [string]$Results,
    [string]$Triage,
    [int]$SettleSec = 75,
    [switch]$NoHeapDump
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\MadoyomiServer.ps1')

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21'
$Java = Join-Path $Jdk 'bin\java.exe'
$Jcmd = Join-Path $Jdk 'bin\jcmd.exe'
$Jstat = Join-Path $Jdk 'bin\jstat.exe'
$OutDir = Join-Path $PSScriptRoot 'out'
$IsManga = $Kind -eq 'Manga'
$KindLower = $Kind.ToLower()
if (-not $List) { $List = Join-Path $PSScriptRoot $(if ($IsManga) { 'manga-sample.txt' } else { 'top20.txt' }) }
if (-not $Triage) { $Triage = Join-Path $PSScriptRoot 'triage.txt' }
if (-not $Report) {
    $Report = if ($Only) { Join-Path $OutDir "$KindLower-compat-partial.md" } else { Join-Path $RepoRoot "docs\research\$KindLower-compat.md" }
}
$Repo = if ($IsManga) { $MangaRepo } else { $AnimeRepo }
# results: compat-<stamp>.json (anime, as in Stage 5) or manga-compat-<stamp>.json
$ResultsPrefix = if ($IsManga) { 'manga-compat-' } else { 'compat-' }
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

# Extension class loaders still alive: a heap dump (out\, gitignored) and the shortest GC-root path to each one
# (lib\HprofLoaderRoots.java). Returns { dump, analysis (the tool's text), holders [{count, holder}] }.
function Get-LoaderRoots {
    $p = $script:proc
    if (-not $p -or $p.HasExited) { return $null }
    $dump = Join-Path $OutDir "heap-$script:stamp.hprof"
    Remove-Item -LiteralPath $dump -ErrorAction SilentlyContinue
    Write-Host "  heap dump: $dump"
    $null = Invoke-Tool $Jcmd "$($p.Id) GC.heap_dump `"$dump`"" 600
    if (-not (Test-Path -LiteralPath $dump)) { return [ordered]@{ dump = $null; analysis = 'heap dump failed'; holders = @() } }
    $tool = Join-Path $PSScriptRoot 'lib\HprofLoaderRoots.java'
    $text = Invoke-Tool $Java "-Xmx4g `"$tool`" `"$dump`"" 900
    if (-not $text) { $text = 'HprofLoaderRoots failed' }
    Write-Utf8 (Join-Path $OutDir "heap-$script:stamp.txt") $text
    $holders = @()
    $inSummary = $false
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^nearest non-JDK holder') { $inSummary = $true; continue }
        if ($inSummary -and $line -match '^\s+(\d+)\s+(.+)$') { $holders += [ordered]@{ count = [int]$Matches[1]; holder = $Matches[2] } }
    }
    return [ordered]@{ dump = (Split-Path $dump -Leaf); analysis = (Split-Path (Join-Path $OutDir "heap-$script:stamp.txt") -Leaf); holders = $holders }
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

$prefFields = '__typename ... on SwitchPreference { key switchValue: currentValue } ... on CheckBoxPreference { key checkBoxValue: currentValue } ... on ListPreference { key listValue: currentValue entryValues } ... on MultiSelectListPreference { key } ... on EditTextPreference { key }'

function Get-Prefs([string]$SourceId) {
    $n = (Q "query(`$s: LongString!) { animeSources(condition: {id: `$s}) { nodes { preferences { $prefFields } } } }" @{ s = $SourceId }).animeSources.nodes
    return @(@($n)[0].preferences)
}

function Set-Pref([string]$SourceId, [int]$Position, [string]$Body) {
    $null = Q "mutation(`$s: LongString!) { updateAnimeSourcePreference(input: {source: `$s, change: {position: $Position, $Body}}) { source { id } } }" @{ s = $SourceId }
}

# The change body (SourcePreferenceChange without the position) that sets preference $P to $Value.
function PrefBody($P, $Value) {
    switch ($P.__typename) {
        'SwitchPreference' { return "switchState: $(([string]$Value).ToLower())" }
        'CheckBoxPreference' { return "checkBoxState: $(([string]$Value).ToLower())" }
        'ListPreference' { return "listState: $(ConvertTo-Json ([string]$Value))" }
    }
    return $null
}

function PrefValue($P) {
    switch ($P.__typename) {
        'SwitchPreference' { return $P.switchValue }
        'CheckBoxPreference' { return $P.checkBoxValue }
        'ListPreference' { return $P.listValue }
    }
    return $null
}

# Per configurable source: the first switch, check box or list moved off its value through
# updateAnimeSourcePreference, read back from the new instance, then set back.
function Test-Preferences($Sources) {
    $parts = @()
    $roundTrips = 0
    $total = 0
    foreach ($src in $Sources) {
        $before = Get-Prefs ([string]$src.id)
        $total += $before.Count
        $i = -1
        $target = $null
        for ($k = 0; $k -lt $before.Count; $k++) {
            $p = $before[$k]
            $v = PrefValue $p
            $new = $null
            if ($p.__typename -in @('SwitchPreference', 'CheckBoxPreference') -and $null -ne $v) { $new = -not [bool]$v }
            elseif ($p.__typename -eq 'ListPreference') { $new = @($p.entryValues) | Where-Object { $_ -ne $v } | Select-Object -First 1 }
            if ($null -ne $new) { $i = $k; $target = $new; break }
        }
        if ($i -lt 0) { $parts += "$($src.name): $($before.Count) prefs, none to change"; continue }
        $p = $before[$i]
        $old = PrefValue $p
        Set-Pref ([string]$src.id) $i (PrefBody $p $target)
        $read = PrefValue (Get-Prefs ([string]$src.id))[$i]
        if ([string]$read -ne [string]$target) { throw "[MISMATCH] $($src.name) '$($p.key)': set '$target', read back '$read'" }
        $roundTrips++
        if ($null -ne $old) { Set-Pref ([string]$src.id) $i (PrefBody $p $old) }
        $parts += "$($src.name): '$($p.key)' -> '$target'"
    }
    $script:lastPrefRoundTrips = $roundTrips
    return "sources=$($Sources.Count) prefs=$total roundTrips=$roundTrips; $($parts -join '; ')"
}

$leafFilterFields = '__typename ... on SelectFilter { name values selectDefault: default } ... on CheckBoxFilter { name checkBoxDefault: default } ... on TriStateFilter { name triDefault: default }'

# One filter off its default in a SEARCH (empty query): the first select with a choice, else the first check box or
# tri-state (in a group or not). The call must succeed; an empty page is fine.
function Test-FilteredSearch([string]$SourceId) {
    $n = (Q "query(`$s: LongString!) { animeSources(condition: {id: `$s}) { nodes { filters { $leafFilterFields ... on GroupFilter { name filters { $leafFilterFields } } } } } }" @{ s = $SourceId }).animeSources.nodes
    $filters = @(@($n)[0].filters)
    if ($filters.Count -eq 0) { return 'no filters' }
    $change = $null
    $what = $null
    for ($i = 0; $i -lt $filters.Count -and -not $change; $i++) {
        $f = $filters[$i]
        if ($f.__typename -eq 'SelectFilter' -and @($f.values).Count -ge 2) {
            $next = ([int]$f.selectDefault + 1) % @($f.values).Count
            $change = "{position: $i, selectState: $next}"; $what = "select '$($f.name)' = '$(@($f.values)[$next])'"
        }
    }
    for ($i = 0; $i -lt $filters.Count -and -not $change; $i++) {
        $f = $filters[$i]
        if ($f.__typename -eq 'CheckBoxFilter') { $change = "{position: $i, checkBoxState: $((-not [bool]$f.checkBoxDefault).ToString().ToLower())}"; $what = "check box '$($f.name)'" }
        elseif ($f.__typename -eq 'TriStateFilter') { $change = "{position: $i, triState: INCLUDE}"; $what = "tri-state '$($f.name)' = INCLUDE" }
        elseif ($f.__typename -eq 'GroupFilter') {
            $kids = @($f.filters)
            for ($j = 0; $j -lt $kids.Count -and -not $change; $j++) {
                $c = $kids[$j]
                if ($c.__typename -eq 'CheckBoxFilter') { $change = "{position: $i, groupChange: {position: $j, checkBoxState: $((-not [bool]$c.checkBoxDefault).ToString().ToLower())}}"; $what = "'$($f.name)' > check box '$($c.name)'" }
                elseif ($c.__typename -eq 'TriStateFilter') { $change = "{position: $i, groupChange: {position: $j, triState: INCLUDE}}"; $what = "'$($f.name)' > tri-state '$($c.name)' = INCLUDE" }
            }
        }
    }
    if (-not $change) { return "filters=$($filters.Count), none with a state to change" }
    $d = Q "mutation(`$s: LongString!) { fetchSourceAnime(input: {source: `$s, type: SEARCH, page: 1, query: `"`", filters: [$change]}) { hasNextPage animes { id } } }" @{ s = $SourceId }
    return "filters=$($filters.Count) $what -> entries=$(@($d.fetchSourceAnime.animes).Count)"
}

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
        compatIssues = @(); steps = [ordered]@{ install = (New-Step); popular = (New-Step); details = (New-Step); videos = (New-Step); resolve = (New-Step); prefs = (New-Step); filters = (New-Step) }
        prefRoundTrips = 0; configurableSources = 0
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
        $script:lastSrcs = $srcs
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

    # preferences round-trip on every configurable source (recorded, not part of the pass); no network needed
    if ($ok) {
        $configurable = @($script:lastSrcs | Where-Object { $_.isConfigurable })
        if ($configurable.Count -gt 0) {
            $script:lastPrefRoundTrips = 0
            Invoke-Step 'preferences (change, read back, restore)' $x.steps.prefs { Test-Preferences $configurable } | Out-Null
            $x.prefRoundTrips = $script:lastPrefRoundTrips
            $x.configurableSources = $configurable.Count
        }
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
        # a filtered search (recorded, not part of the pass): one filter off its default reaches the site
        if ($ok) {
            $null = Invoke-Step 'filtered search' $x.steps.filters { Test-FilteredSearch $x.sourceId }
            if ($x.steps.filters.info -eq 'no filters') { $x.steps.filters.status = 'SKIP' }
        }
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

# --- the manga chain (-Kind Manga): no regression from Suwayomi ---

# install -> popular -> details + chapters -> pages of a chapter -> the first page image through the server (as
# tools\smoke-api.ps1 does for MangaPill); up to -Tries popular entries.
function Test-MangaExtension($Row) {
    $x = [ordered]@{
        rank = $Row.rank; pkg = $Row.pkg; name = (Short $Row.pkg); listVersion = $Row.version; listLib = $Row.lib
        warning = $Row.warning; lang = $Row.lang
        version = $null; extensionLib = $null; contentWarning = $null; isTrusted = $null; isLoaded = $null
        sources = 0; sourceId = $null; sourceName = $null; sourceLang = $null; baseUrl = $null; supportsLatest = $null; isConfigurable = $null
        popularCount = $null; entriesTried = 0; entryTitle = $null; chapters = $null; chapterName = $null; pages = $null
        pageType = $null; pageBytes = $null; siteProbe = $null
        compatIssues = @(); steps = [ordered]@{ install = (New-Step); popular = (New-Step); details = (New-Step); pages = (New-Step); image = (New-Step) }
        result = 'FAIL'; failedStep = $null; autoCategory = $null; jvmAbort = $false; ms = $null; health = $null
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Write-Host ''
    Write-Host ("#{0} {1} (v{2}, lib {3}, {4})" -f $Row.rank, $Row.pkg, $Row.version, $Row.lib, $Row.warning)

    $ok = Invoke-Step 'install + source' $x.steps.install {
        $states = InstallWithProgress $x.pkg
        if ($states.Count -eq 0 -or $states[-1] -ne 'INSTALLED') { throw "[INSTALL] progress states: $($states -join ' -> ')" }
        $e = (Q 'query($p: String!) { extension(pkgName: $p) { versionName extensionLib contentWarning isLoaded isTrusted source { nodes { id name lang supportsLatest isConfigurable baseUrl } } } }' @{ p = $x.pkg }).extension
        $x.version = $e.versionName; $x.extensionLib = $e.extensionLib; $x.contentWarning = $e.contentWarning
        $x.isLoaded = $e.isLoaded; $x.isTrusted = $e.isTrusted
        if (-not $e.isTrusted) { throw '[UNTRUSTED] installed but not trusted' }
        $srcs = @($e.source.nodes)
        $x.sources = $srcs.Count
        if ($srcs.Count -eq 0) { throw '[NO_SOURCE] the extension has no manga source' }
        $src = $srcs | Where-Object { $_.lang -eq 'en' } | Select-Object -First 1
        if (-not $src) { $src = $srcs[0] }
        $x.sourceId = [string]$src.id; $x.sourceName = $src.name; $x.sourceLang = $src.lang; $x.baseUrl = $src.baseUrl
        $x.supportsLatest = $src.supportsLatest; $x.isConfigurable = $src.isConfigurable
        "v$($x.version) lib $($x.extensionLib) $($x.contentWarning) sources=$($x.sources) '$($x.sourceName)' ($($x.sourceLang)) $($x.baseUrl) configurable=$($x.isConfigurable)"
    }

    $mangas = @()
    if ($ok) {
        $ok = Invoke-Step 'popular' $x.steps.popular {
            $q = 'mutation($s: LongString!) { fetchSourceManga(input: {source: $s, type: POPULAR, page: 1}) { hasNextPage mangas { id title } } }'
            $note = ''
            try { $d = Q $q @{ s = $x.sourceId } } catch {
                $note = " (retried after: $(Clip $_.Exception.Message 120))"
                $d = Q $q @{ s = $x.sourceId }
            }
            $script:lastMangas = @($d.fetchSourceManga.mangas)
            $x.popularCount = $script:lastMangas.Count
            if ($script:lastMangas.Count -eq 0) { throw '[EMPTY] popular page 1 has no entries' }
            "entries=$($x.popularCount) hasNextPage=$($d.fetchSourceManga.hasNextPage)$note"
        }
        if ($ok) { $mangas = @($script:lastMangas) }
    }

    if ($ok) {
        $best = $null
        foreach ($manga in @($mangas | Select-Object -First $Tries)) {
            $x.entriesTried++
            $attempt = [ordered]@{ details = (New-Step); pages = (New-Step); image = (New-Step) }
            $script:chapters = @(); $script:pageUrls = @(); $script:lastPage = $null; $script:chapterName = $null
            $okD = Invoke-Step "entry $($x.entriesTried) details + chapters" $attempt.details {
                $d = Q 'mutation($id: Int!) { fetchMangaAndChapters(input: {id: $id, fetchManga: true, fetchChapters: true}) { manga { id title initialized } chapters { id name sourceOrder } } }' @{ id = [int]$manga.id }
                $script:chapters = @($d.fetchMangaAndChapters.chapters)
                if ($script:chapters.Count -eq 0) { throw '[EMPTY] no chapters' }
                "chapters=$($script:chapters.Count)"
            }
            $okP = $false
            $okI = $false
            if ($okD) {
                $ch = $script:chapters | Sort-Object sourceOrder | Select-Object -First 1
                $script:chapterName = $ch.name
                $okP = Invoke-Step "entry $($x.entriesTried) pages '$($ch.name)'" $attempt.pages {
                    $d = Q 'mutation($id: Int!) { fetchChapterPages(input: {chapterId: $id}) { pages } }' @{ id = [int]$ch.id }
                    $script:pageUrls = @($d.fetchChapterPages.pages)
                    if ($script:pageUrls.Count -eq 0) { throw '[EMPTY] no pages' }
                    "pages=$($script:pageUrls.Count)"
                }
            }
            if ($okP) {
                $okI = Invoke-Step "entry $($x.entriesTried) page image" $attempt.image {
                    $u = $script:pageUrls[0]
                    if ($u -notmatch '^https?://') { $u = "$script:base$u" }
                    $r = Invoke-Http -Method GET -Url $u -TimeoutSec $CallTimeoutSec
                    if ($r.Status -ne 200) { throw "[HTTP_$($r.Status)] page image" }
                    if ($r.ContentType -notmatch '^image/') { throw "[NOT_IMAGE] Content-Type '$($r.ContentType)'" }
                    $script:lastPage = @($r.ContentType, $r.Bytes.Length)
                    "$($r.ContentType) $($r.Bytes.Length) bytes"
                }
            }
            $c = [ordered]@{ title = $manga.title; steps = $attempt; chapters = $script:chapters.Count; chapterName = $script:chapterName; pages = $script:pageUrls.Count; page = $script:lastPage }
            $score = [int]$okD + [int]$okP + [int]$okI
            if (-not $best -or $score -gt $best.score) { $best = [ordered]@{ score = $score; c = $c } }
            if ($okI) { break }
        }
        $c = $best.c
        foreach ($k in @('details', 'pages', 'image')) { $x.steps[$k] = $c.steps[$k] }
        $x.entryTitle = $c.title; $x.chapters = $c.chapters; $x.chapterName = $c.chapterName; $x.pages = $c.pages
        if ($x.steps.image.status -eq 'PASS' -and $c.page) { $x.pageType = $c.page[0]; $x.pageBytes = $c.page[1] }
    }

    if (-not (Test-ServerAlive "$($x.name) (after the chain)")) {
        try {
            $ci = (Q 'query($p: String!) { extension(pkgName: $p) { compatIssues { kind target origin context count } } }' @{ p = $x.pkg }).extension
            $x.compatIssues = @($ci.compatIssues | ForEach-Object { [ordered]@{ kind = $_.kind; target = $_.target; origin = $_.origin; context = $_.context; count = $_.count } })
        } catch { }
    } else {
        $x.jvmAbort = $true
    }
    if ($x.baseUrl) { $x.siteProbe = Invoke-Probe $x.baseUrl @() }

    $failed = @('install', 'popular', 'details', 'pages', 'image') | Where-Object { $x.steps[$_].status -ne 'PASS' } | Select-Object -First 1
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
    # a source that renders in a real WebView, which the sidecar runs without (KCEF is off by default, phase-1 sidecar
    # defaults; the WebView/Cloudflare shim is deferred, Phase 2 section 9)
    if ($msg -match 'CEF is disabled|CefException') { return 'WEBVIEW' }
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

# The runtime compat issues and the process health of a run (shared by both reports); $L appends a line.
function Add-RuntimeSections($L, $R, $exts) {
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
    $rows = @($R.run.baseline) + @($exts | ForEach-Object { $_.health }) + @($R.run.end) + @($R.run.settled)
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
    if ($R.run.leak) {
        $hs = @($R.run.leak.holders)
        & $L "- Extension class loaders still alive at the end: the nearest non-JDK holder on the shortest GC-root path of each, or its root when the path has none (``lib/HprofLoaderRoots.java`` over the heap dump ``out/$($R.run.leak.dump)``; the full paths are in ``out/$($R.run.leak.analysis)``):"
        if ($hs.Count -eq 0) { & $L '  - (no analysis)' }
        foreach ($h in $hs) { & $L "  - $($h.count) x ``$(Cell (Clip $h.holder 200))``" }
    } elseif ($R.run.end) {
        $last = if ($R.run.settled) { $R.run.settled } else { $R.run.end }
        & $L "- Extension class loaders still alive at the end: $($last.extLoaders)."
    }
    & $L "- Exit after stdin closed: $(if ($null -eq $R.run.exitCode) { 'killed (still running after 60 s)' } else { "exit code $($R.run.exitCode)" })."
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
    & $L 'Prefs and Filtered search are recorded but not part of the result (see "Filters and preferences" below).'
    & $L ''
    & $L '| # | Extension | Warning | Lib | Version | Install | Popular | Details + episodes | Videos | Resolve | Result | Class | Prefs | Filtered search |'
    & $L '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($x in $exts) {
        $res = if ($x.result -eq 'PASS') { 'PASS' } else { "**FAIL**" }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} |" -f $x.rank, $x.name, $x.warning, (Cell $x.extensionLib), (Cell $(if ($x.version) { $x.version } else { $x.listVersion })), (StepCell $x.steps.install), (StepCell $x.steps.popular), (StepCell $x.steps.details), (StepCell $x.steps.videos), (StepCell $x.steps.resolve), $res, (Cell $x.category), (StepCell $x.steps.prefs), (StepCell $x.steps.filters))
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
    & $L '## Filters and preferences'
    & $L ''
    $withPrefs = @($exts | Where-Object { $_.steps.prefs.status -ne 'SKIP' -and $null -ne $_.steps.prefs.status })
    $prefOk = @($withPrefs | Where-Object { $_.steps.prefs.status -eq 'PASS' -and $_.prefRoundTrips -gt 0 })
    $prefSources = ($withPrefs | ForEach-Object { [int]$_.configurableSources } | Measure-Object -Sum).Sum
    $prefTrips = ($withPrefs | ForEach-Object { [int]$_.prefRoundTrips } | Measure-Object -Sum).Sum
    $withFilters = @($exts | Where-Object { $_.steps.filters.status -in @('PASS', 'FAIL') })
    $filterOk = @($withFilters | Where-Object { $_.steps.filters.status -eq 'PASS' })
    & $L "- **Preferences:** $($prefOk.Count) of $($withPrefs.Count) configurable extensions round-tripped a preference through ``updateAnimeSourcePreference`` ($prefTrips of $prefSources configurable sources; per source the first switch, check box or list is moved off its value, read back from the re-instantiated source, then set back). Needs no network."
    & $L "- **Filtered search:** $($filterOk.Count) of $($withFilters.Count) extensions with filters answered a ``fetchSourceAnime(SEARCH)`` with one filter off its default (the first select with a choice, else a check box or tri-state; an empty page counts). It goes to the site."
    & $L '- Every filter of kickassanime, aniwave and hanime, and the preferences of AnimePahe, streamingcommunity (4 sources), hanime and kickassanime, also round-trip offline in `AnimeRoundTripApiTest`.'
    & $L ''
    & $L '| Extension | Preferences | Filtered search |'
    & $L '|---|---|---|'
    foreach ($x in $exts) {
        $pc = if ($x.steps.prefs.status -eq 'PASS') { Cell (Clip $x.steps.prefs.info 160) } elseif ($x.steps.prefs.status -eq 'FAIL') { "**FAIL** $(Cell (Clip $x.steps.prefs.message 160))" } else { '-' }
        $fc = if ($x.steps.filters.status -eq 'PASS') { Cell (Clip $x.steps.filters.info 160) } elseif ($x.steps.filters.status -eq 'FAIL') { "**FAIL** $(Cell (Clip $x.steps.filters.message 160))" } elseif ($x.steps.filters.info) { Cell $x.steps.filters.info } else { '-' }
        & $L "| $($x.name) | $pc | $fc |"
    }
    & $L ''
    & $L '## Failures and triage'
    & $L ''
    if ($fail.Count -eq 0) { & $L 'None.' }
    & $L 'Classes: **HOST_GAP** (Madoyomi lacks an API the extension needs: ours to fix), **CLOUDFLARE** (the site answers with a Cloudflare challenge; needs a solver), **SITE_DOWN** (DNS, connection or 5xx at the site), **WEBVIEW** (the source renders in a real WebView, and the sidecar runs with KCEF off; deferred with the WebView shim), **UPSTREAM_BROKEN** (the extension no longer matches its site; broken in Aniyomi too), **SOURCE_ERROR** (the source failed, not yet triaged further), **INTERNAL**, **JVM_ABORT**. The site probe is a plain GET of the source''s base URL from PowerShell with a browser user agent, so it can differ from what OkHttp gets.'
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
    Add-RuntimeSections $L $R $exts
    Write-Utf8 $Path ($sb.ToString())
    Write-Host "report: $Path"
}

function Write-MangaReport($R, [string]$Path) {
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
    $gaps = @($fail | Where-Object { $_.category -in @('HOST_GAP', 'INTERNAL', 'JVM_ABORT') })
    $sb = New-Object Text.StringBuilder
    $L = { param($s) [void]$sb.AppendLine($s) }
    & $L '# Manga extension compatibility (Phase 2: no regression in manga)'
    & $L ''
    & $L "> Generated by ``tools/compat-test/run-compat.ps1 -Kind Manga`` on $($R.run.started) (duration $($R.run.durationMin) min). Do not edit by hand: triage notes go in ``tools/compat-test/triage.txt``, then ``run-compat.ps1 -Kind Manga -ReportOnly -Results <json>`` redraws this file."
    & $L ''
    & $L "- Server: $($R.run.server) (``$($R.run.jarName)``), JVM $($R.run.javaVersion), started in $($R.run.startMs) ms."
    & $L "- Repo: $($R.run.repo) ($($R.run.repoExtensions) manga extensions listed on the run date)."
    & $L "- List: ``tools/compat-test/manga-sample.txt`` ($($R.run.listHeader)), the 10 most-downloaded Keiyoushi extensions; the version column is the one installed."
    & $L "- Chain per extension (as ``tools/smoke-api.ps1`` for MangaPill, upstream Suwayomi's manga API): install (trusted, loaded) -> ``fetchSourceManga`` popular page 1 -> ``fetchMangaAndChapters`` -> ``fetchChapterPages`` of the first chapter -> the first page image through the server (an ``image/*`` answer). Up to $($R.run.tries) popular entries are tried; each GraphQL call has a $($R.run.callTimeoutSec) s timeout."
    & $L ''
    & $L '## Summary'
    & $L ''
    & $L '| | Pass |'
    & $L '|---|---|'
    & $L "| All | **$($pass.Count) of $n** |"
    & $L "| SAFE only | $(@($pass | Where-Object { $_.warning -eq 'SAFE' }).Count) of $(@($exts | Where-Object { $_.warning -eq 'SAFE' }).Count) |"
    & $L ''
    & $L "A regression would be a failure that is Madoyomi's (HOST_GAP, INTERNAL, JVM_ABORT): **$($gaps.Count)**$(if ($gaps.Count) { ": $(($gaps | ForEach-Object { $_.name }) -join ', ')" })."
    $web = @($fail | Where-Object { $_.category -eq 'WEBVIEW' })
    if ($web.Count) { & $L ''; & $L "WEBVIEW failures ($($web.Count): $(($web | ForEach-Object { $_.name }) -join ', ')) need a real WebView, which the sidecar runs without (KCEF is off by default since Phase 1; the WebView shim is deferred with Cloudflare). They are not counted as regressions of the manga API." }
    & $L ''
    & $L '## Results'
    & $L ''
    & $L '| # | Extension | Warning | Lib | Version | Install | Popular | Details + chapters | Pages | Page image | Result | Class |'
    & $L '|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($x in $exts) {
        $res = if ($x.result -eq 'PASS') { 'PASS' } else { '**FAIL**' }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} |" -f $x.rank, $x.name, $x.warning, (Cell $x.extensionLib), (Cell $(if ($x.version) { $x.version } else { $x.listVersion })), (StepCell $x.steps.install), (StepCell $x.steps.popular), (StepCell $x.steps.details), (StepCell $x.steps.pages), (StepCell $x.steps.image), $res, (Cell $x.category))
    }
    & $L ''
    & $L '## Details'
    & $L ''
    & $L 'Entry titles are shown for SAFE extensions only.'
    & $L ''
    & $L '| Extension | Sources | Popular | Entries tried | Entry | Chapters | Pages | Page image | Compat issues | Time |'
    & $L '|---|---|---|---|---|---|---|---|---|---|'
    foreach ($x in $exts) {
        $entry = if ($x.warning -eq 'SAFE') { Cell (Clip $x.entryTitle 40) } elseif ($x.entryTitle) { '(not shown)' } else { '' }
        $img = if ($x.pageType) { "$($x.pageType) $($x.pageBytes) bytes" } else { '' }
        $issues = @($x.compatIssues)
        $ci = if ($issues.Count) { "$($issues.Count): " + ((@($issues | Group-Object { "$($_.origin) $($_.kind)" }) | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join ', ') } else { 'none' }
        & $L ("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} s |" -f $x.name, $x.sources, (Cell $x.popularCount), $x.entriesTried, $entry, (Cell $x.chapters), (Cell $x.pages), (Cell $img), (Cell $ci), [Math]::Round([double]$x.ms / 1000, 1))
    }
    & $L ''
    & $L '## Failures and triage'
    & $L ''
    & $L 'Classes as in `docs/research/anime-compat.md`: HOST_GAP, CLOUDFLARE, SITE_DOWN, WEBVIEW (renders in a real WebView; KCEF is off), UPSTREAM_BROKEN, SOURCE_ERROR, INTERNAL, JVM_ABORT.'
    & $L ''
    if ($fail.Count -eq 0) { & $L 'None.' }
    foreach ($x in $fail) {
        $s = $x.steps.($x.failedStep)
        $sp = $x.siteProbe
        $site = if ($sp) { "site probe ``$($sp.url)``: $(if ($sp.status) { "HTTP $($sp.status)" } else { $sp.error })$(if ($sp.server) { ", server $($sp.server)" })$(if ($sp.cloudflare) { ', Cloudflare challenge' })" } else { 'no site probe' }
        & $L "- **$($x.name)** ($($x.warning)): $($x.category) at $($x.failedStep) (automatic: $($x.autoCategory)). ``[$($s.code)] $(Cell (Clip $s.message 300))``. $site."
        if ($x.triageNote) { & $L "  - Triage: $($x.triageNote)" }
    }
    & $L ''
    Add-RuntimeSections $L $R $exts
    Write-Utf8 $Path ($sb.ToString())
    Write-Host "report: $Path"
}

# --- -ReportOnly ---
if ($ReportOnly) {
    if (-not $Results) {
        $Results = (Get-ChildItem -LiteralPath $OutDir -Filter "$ResultsPrefix*.json" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
    $R = [IO.File]::ReadAllText((Resolve-Path $Results).Path) | ConvertFrom-Json
    if ($R.run.kind -eq 'Manga') { Write-MangaReport $R $Report } else { Write-CompatReport $R $Report }
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
Write-Host "repo:     $Repo"

$started = Get-Date
$run = [ordered]@{
    started = $started.ToString('yyyy-MM-dd HH:mm'); durationMin = $null; jarName = (Split-Path $script:JarPath -Leaf); server = $null
    kind = $Kind; javaVersion = $javaVersion; maxHeapMB = $null; startMs = $null; repo = $Repo; repoExtensions = $null; listHeader = $listHeader
    tries = $Tries; callTimeoutSec = $CallTimeoutSec; baseline = $null; end = $null; settled = $null; leak = $null; aborts = @(); hsErr = @()
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

    $s = (Q "mutation(`$url: String!) { addExtensionStore(input: {indexUrl: `$url, type: $($Kind.ToUpper())}) { extensionStore { indexUrl signingKeyTrusted lastError } } }" @{ url = $Repo }).addExtensionStore.extensionStore
    if (-not $s.signingKeyTrusted -or $s.lastError) { throw "repo not usable: trusted=$($s.signingKeyTrusted) lastError=$($s.lastError)" }
    $exts = @((Q "mutation { fetchExtensions(input: {type: $($Kind.ToUpper())}) { extensions { pkgName } } }").fetchExtensions.extensions)
    $run.repoExtensions = $exts.Count
    Write-Host "repo: $($exts.Count) $KindLower extensions"
    $listed = @{}
    foreach ($e in $exts) { $listed[$e.pkgName] = $true }
    $run.baseline = Get-Health 'baseline (repo added)'

    foreach ($row in $rows) {
        if (-not $listed[$row.pkg]) {
            Write-Host "#$($row.rank) $($row.pkg): not in the repo any more" -ForegroundColor Red
        }
        $extResults += $(if ($IsManga) { Test-MangaExtension $row } else { Test-Extension $row })
        # keep partial results on disk while the run goes on
        $run.aborts = @($script:aborts)
        Write-Utf8 (Join-Path $OutDir "$ResultsPrefix$script:stamp.json") ([ordered]@{ run = $run; extensions = $extResults } | ConvertTo-Json -Depth 12)
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
    if ($run.end.extLoaders -gt 0 -and $SettleSec -gt 0) {
        # idle source-pool and OkHttp threads end after 60 s; measure again once they have
        Write-Host "  $($run.end.extLoaders) extension loaders alive; measuring again after $SettleSec s"
        Start-Sleep -Seconds $SettleSec
        $run.settled = Get-Health "all uninstalled + $SettleSec s"
    }
    $last = if ($run.settled) { $run.settled } else { $run.end }
    if ($last.extLoaders -gt 0 -and -not $NoHeapDump) { $run.leak = Get-LoaderRoots }
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
$jsonPath = Join-Path $OutDir "$ResultsPrefix$script:stamp.json"
Write-Utf8 $jsonPath ([ordered]@{ run = $run; extensions = $extResults; fatal = $fatal } | ConvertTo-Json -Depth 12)
Write-Host "results: $jsonPath"

$R = [IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json
if ($IsManga) { Write-MangaReport $R $Report } else { Write-CompatReport $R $Report }

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
