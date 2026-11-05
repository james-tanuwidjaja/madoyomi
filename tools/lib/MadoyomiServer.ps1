<#
.SYNOPSIS
  Helpers for scripts that drive a Madoyomi-Server jar over its API (smoke-api.ps1; the Phase 2 compat harness).

.DESCRIPTION
  Dot-source this file: . (Join-Path $PSScriptRoot 'lib\MadoyomiServer.ps1')
  It defines functions only. Their state lives in the caller's script scope:
    $script:token, $script:port, $script:base   set by Start-MadoyomiServer
    $script:proc                                the server process
    $script:passed, $script:failed              Step/Skip bookkeeping (Reset-MadoyomiSteps)

  Server:     Resolve-MadoyomiJar, Start-MadoyomiServer, Stop-MadoyomiServer, Get-MadoyomiServerLog
  Steps:      Reset-MadoyomiSteps, Step, Skip, Assert
  HTTP:       Invoke-Http, Gql, GqlData, ErrorText
  WebSocket:  WsOpen, WsSend, WsRecv, WsClose (graphql-transport-ws), InstallWithProgress

  Keep this file ASCII: Windows PowerShell 5.1 reads scripts without a BOM as ANSI.
#>

# --- server ---

# The server jar: $Jar if given, else the newest server\api\build\Madoyomi-Server-*.jar, built first unless -NoBuild.
function Resolve-MadoyomiJar([string]$RepoRoot, [string]$Jar, [switch]$NoBuild) {
    $serverDir = Join-Path $RepoRoot 'server'
    if (-not $Jar) {
        if (-not $NoBuild) {
            # build output goes to the host: in a function, anything else on the pipeline is the return value
            & (Join-Path $RepoRoot 'tools\fetch-upstream-assets.ps1') | Out-Host
            $env:JAVA_HOME = Join-Path $RepoRoot 'tools\bin\jdk-21'
            Push-Location $serverDir
            $eap = $ErrorActionPreference
            # a javac warning on stderr must not abort the script (PowerShell 5.1 with 'Stop')
            $ErrorActionPreference = 'Continue'
            try {
                & .\gradlew.bat :api:shadowJar --console=plain | Out-Host
                if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)" }
            } finally {
                $ErrorActionPreference = $eap
                Pop-Location
            }
        }
        $found = Get-ChildItem -LiteralPath (Join-Path $serverDir 'api\build') -Filter 'Madoyomi-Server-*.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $found) { throw 'No Madoyomi-Server jar in server\api\build. Run without -NoBuild first.' }
        $Jar = $found.FullName
    }
    return (Resolve-Path -LiteralPath $Jar).Path
}

# Starts the jar the way the Tauri shell does: a random token in MADOYOMI_TOKEN, --data-dir, piped stdio (the server
# exits when its stdin closes, and the PowerShell tool's stdin is the null device), then waits for MADOYOMI_PORT.
# Sets $script:token, $script:port, $script:base, $script:proc. Returns the milliseconds until the port line.
function Start-MadoyomiServer([string]$Java, [string]$Jar, [string]$DataDir, [int]$TimeoutSec = 120, [string]$JvmArgs = '') {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $script:token = -join ($bytes | ForEach-Object { $_.ToString('x2') })

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Java
    $psi.Arguments = "$JvmArgs -jar `"$Jar`" --data-dir `"$DataDir`"".Trim()
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables['MADOYOMI_TOKEN'] = $script:token

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $script:proc = [System.Diagnostics.Process]::Start($psi)
    $script:errTask = $script:proc.StandardError.ReadToEndAsync()
    $script:outTask = $null
    $script:port = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while (-not $script:port) {
        $remainingMs = [int][Math]::Max(0, ($deadline - (Get-Date)).TotalMilliseconds)
        $lineTask = $script:proc.StandardOutput.ReadLineAsync()
        if (-not $lineTask.Wait($remainingMs)) { throw "no MADOYOMI_PORT line within $TimeoutSec s" }
        $line = $lineTask.Result
        if ($null -eq $line) { throw "the server exited before printing MADOYOMI_PORT (exit code $($script:proc.ExitCode))" }
        if ($line -match '^MADOYOMI_PORT=(\d+)$') { $script:port = [int]$Matches[1] }
    }
    $script:outTask = $script:proc.StandardOutput.ReadToEndAsync()
    $script:base = "http://127.0.0.1:$script:port"
    return $sw.ElapsedMilliseconds
}

# Closes the server's stdin and waits for it to exit (killing it after $TimeoutSec). Returns the exit code, or $null if
# it had to be killed.
function Stop-MadoyomiServer([int]$TimeoutSec = 60) {
    WsClose
    $p = $script:proc
    if (-not $p) { return $null }
    if ($p.HasExited) { return $p.ExitCode }
    try { $p.StandardInput.Close() } catch { }
    if ($p.WaitForExit($TimeoutSec * 1000)) { return $p.ExitCode }
    $p.Kill()
    $p.WaitForExit() | Out-Null
    return $null
}

# The server's stdout and stderr so far (complete once it has exited).
function Get-MadoyomiServerLog {
    $log = ''
    if ($script:outTask -and $script:outTask.IsCompleted) { $log += $script:outTask.Result }
    if ($script:errTask -and $script:errTask.IsCompleted) { $log += $script:errTask.Result }
    return $log
}

# --- step bookkeeping ---

function Reset-MadoyomiSteps {
    $script:passed = 0
    $script:failed = 0
}

# Runs $Block; prints PASS with its output joined by spaces, or FAIL with the exception message. Returns $true/$false.
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
    if (-not $NoAuth) { $req.Headers['Authorization'] = "Bearer $script:token" }
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

function Gql([string]$Query, [hashtable]$Vars = @{}, [switch]$NoAuth, [int]$TimeoutSec = 300) {
    $body = @{ query = $Query; variables = $Vars } | ConvertTo-Json -Compress -Depth 10
    $r = Invoke-Http -Method POST -Url "$script:base/api/graphql" -Body $body -NoAuth:$NoAuth -TimeoutSec $TimeoutSec
    $text = [Text.Encoding]::UTF8.GetString($r.Bytes)
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Status = $r.Status; Json = $json; Text = $text }
}

# "[CODE] message | [CODE] message" for a GraphQL response's errors.
function ErrorText($Json) {
    return ($Json.errors | ForEach-Object { "[$($_.extensions.code)] $($_.message.Split("`n")[0])" }) -join ' | '
}

# Runs a query that must succeed and returns its data.
function GqlData([string]$Query, [hashtable]$Vars = @{}, [int]$TimeoutSec = 300) {
    $r = Gql $Query $Vars -TimeoutSec $TimeoutSec
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
$script:wsBuf = New-Object byte[] 65536

function WsSend($Obj) {
    $json = $Obj | ConvertTo-Json -Compress -Depth 10
    $data = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object 'ArraySegment[byte]' -ArgumentList @(, $data)
    $script:ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait()
}

# The next message as text, or $null after $TimeoutMs.
function WsRecv([int]$TimeoutMs) {
    $ms = New-Object IO.MemoryStream
    while ($true) {
        if (-not $script:pending) {
            $seg = New-Object 'ArraySegment[byte]' -ArgumentList @(, $script:wsBuf)
            $script:pending = $script:ws.ReceiveAsync($seg, [Threading.CancellationToken]::None)
        }
        if (-not $script:pending.Wait($TimeoutMs)) { return $null }
        $res = $script:pending.Result
        $script:pending = $null
        $ms.Write($script:wsBuf, 0, $res.Count)
        if ($res.EndOfMessage) { break }
    }
    return [Text.Encoding]::UTF8.GetString($ms.ToArray())
}

# Connects (?token=), acks and starts $Subscription as id 1.
function WsOpen([string]$Subscription) {
    $script:ws = New-Object System.Net.WebSockets.ClientWebSocket
    $script:pending = $null
    $script:ws.ConnectAsync([Uri]"ws://127.0.0.1:$script:port/api/graphql?token=$script:token", [Threading.CancellationToken]::None).Wait()
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
