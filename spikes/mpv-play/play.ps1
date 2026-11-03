<#
.SYNOPSIS
  Phase 0 / S4 spike: play one video in mpv with its source headers, subtitles and audio tracks,
  and drive mpv over JSON IPC on a named pipe.

.DESCRIPTION
  Input is either a video.json written by the anime harness:
      { "title": "...", "url": "...", "headers": { "Referer": "..." },
        "subtitles": [ { "url": "...", "lang": "..." } ], "audio": [ { "url": "...", "lang": "..." } ] }
  or the same fields given with -Url / -Header / -Sub / -Audio.

  After mpv starts, the script connects to \\.\pipe\<PipeName>, reads `time-pos` several times,
  checks that it goes up, lists the loaded tracks and then quits mpv (unless -Keep is given).

.EXAMPLE
  .\play.ps1 -VideoJson ..\anime-harness\out\video.json
.EXAMPLE
  .\play.ps1 -Url http://127.0.0.1:18080/mp4 -Header 'Referer: https://madoyomi.test/','User-Agent: MadoyomiSpike/1.0' -Sub http://127.0.0.1:18080/subs.srt
#>
[CmdletBinding()]
param(
  [string]$VideoJson,
  [string]$Url,
  [string[]]$Header = @(),
  [string[]]$Sub = @(),
  [string[]]$Audio = @(),
  [string]$Title,
  [string]$PipeName = "madoyomi-mpv-test",
  [int]$Samples = 4,
  [int]$SampleIntervalMs = 1500,
  [string]$Mpv,
  [switch]$Keep,
  [switch]$Hidden # --force-window=no --vo=null --ao=null: for headless checks
)

$ErrorActionPreference = "Stop"

# ---- Collect inputs -------------------------------------------------------------------------
$headers = [ordered]@{}
foreach ($h in $Header) {
  $i = $h.IndexOf(":")
  if ($i -lt 1) { throw "Header must look like 'Name: value', got '$h'" }
  $headers[$h.Substring(0, $i).Trim()] = $h.Substring($i + 1).Trim()
}
$subs = @($Sub)
$audios = @($Audio)

if ($VideoJson) {
  $v = Get-Content -Raw -Encoding UTF8 $VideoJson | ConvertFrom-Json
  if (-not $Url) { $Url = $v.url }
  if (-not $Title) { $Title = $v.title }
  if ($v.headers) { foreach ($p in $v.headers.PSObject.Properties) { $headers[$p.Name] = [string]$p.Value } }
  if ($v.subtitles) { $subs += @($v.subtitles | ForEach-Object { $_.url }) }
  if ($v.audio) { $audios += @($v.audio | ForEach-Object { $_.url }) }
}
if (-not $Url) { throw "Give -VideoJson or -Url" }
if (-not $Title) { $Title = "Madoyomi spike" }

if (-not $Mpv) {
  $cmd = Get-Command mpv.exe -ErrorAction SilentlyContinue
  if (-not $cmd) { $cmd = Get-Command mpv -ErrorAction SilentlyContinue }
  if (-not $cmd) { throw "mpv not found on PATH; pass -Mpv <path>" }
  # Prefer mpv.exe (GUI) next to mpv.com (console wrapper).
  $Mpv = Join-Path (Split-Path $cmd.Source) "mpv.exe"
  if (-not (Test-Path $Mpv)) { $Mpv = $cmd.Source }
}

# ---- Build the command line ------------------------------------------------------------------
# One *-append option per item: no need to escape ',' (header list) or ';' (file lists on Windows).
$pipePath = "\\.\pipe\$PipeName"
$mpvArgs = [System.Collections.Generic.List[string]]::new()
$mpvArgs.Add("--input-ipc-server=$pipePath")
$mpvArgs.Add("--force-media-title=$Title")
$mpvArgs.Add("--msg-level=all=warn")
# Without this, mpv's ytdl hook retries a failed URL through yt-dlp with a browser User-Agent.
$mpvArgs.Add("--ytdl=no")
foreach ($k in $headers.Keys) {
  $val = $headers[$k]
  # User-Agent and Referer are dedicated mpv options; ffmpeg would otherwise send its own UA.
  if ($k -ieq "User-Agent") { $mpvArgs.Add("--user-agent=$val") }
  elseif ($k -ieq "Referer") { $mpvArgs.Add("--referrer=$val") }
  else { $mpvArgs.Add("--http-header-fields-append=${k}: $val") }
}
foreach ($s in $subs) { $mpvArgs.Add("--sub-files-append=$s") }
foreach ($a in $audios) { $mpvArgs.Add("--audio-files-append=$a") }
if ($Hidden) { $mpvArgs.AddRange([string[]]@("--force-window=no", "--vo=null", "--ao=null")) }
$mpvArgs.Add("--")
$mpvArgs.Add($Url)

# Quote for CreateProcess: wrap in quotes and escape embedded quotes.
$argLine = ($mpvArgs | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join " "
Write-Host "mpv: $Mpv"
Write-Host "args: $argLine"

$proc = Start-Process -FilePath $Mpv -ArgumentList $argLine -PassThru

# ---- JSON IPC over the named pipe ------------------------------------------------------------
$pipe = [System.IO.Pipes.NamedPipeClientStream]::new(".", $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
$deadline = (Get-Date).AddSeconds(15)
while ($true) {
  try { $pipe.Connect(500); break } catch {
    if ($proc.HasExited) { throw "mpv exited early with code $($proc.ExitCode)" }
    if ((Get-Date) -gt $deadline) { throw "could not connect to $pipePath" }
  }
}
$reader = [System.IO.StreamReader]::new($pipe, [System.Text.Encoding]::UTF8)
$writer = [System.IO.StreamWriter]::new($pipe, [System.Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true
$script:reqId = 0

function Invoke-Mpv([object[]]$command) {
  $script:reqId++
  $id = $script:reqId
  $writer.WriteLine((@{ command = $command; request_id = $id } | ConvertTo-Json -Compress -Depth 5))
  while ($true) {
    $line = $reader.ReadLine()
    if ($null -eq $line) { throw "IPC pipe closed" }
    $msg = $line | ConvertFrom-Json
    # Skip async events ({"event":...}); wait for our reply.
    if ($msg.PSObject.Properties["request_id"] -and $msg.request_id -eq $id) { return $msg }
  }
}

$times = @()
try {
  for ($i = 0; $i -lt $Samples; $i++) {
    Start-Sleep -Milliseconds $SampleIntervalMs
    $r = Invoke-Mpv @("get_property", "time-pos")
    $t = if ($r.error -eq "success") { [double]$r.data } else { $null }
    Write-Host ("time-pos[{0}] = {1} ({2})" -f $i, $t, $r.error)
    $times += $t
  }

  $tracks = (Invoke-Mpv @("get_property", "track-list")).data
  foreach ($tr in $tracks) {
    Write-Host ("track: {0,-5} id={1} external={2} codec={3} lang={4} title={5}" -f $tr.type, $tr.id, $tr.external, $tr.codec, $tr.lang, $tr.title)
  }
  $fmt = (Invoke-Mpv @("get_property", "file-format")).data
  $dur = (Invoke-Mpv @("get_property", "duration")).data
  Write-Host "file-format=$fmt duration=$dur"

  $valid = @($times | Where-Object { $null -ne $_ })
  $advancing = $valid.Count -ge 2 -and $valid[-1] -gt $valid[0]
  $extSubs = @($tracks | Where-Object { $_.type -eq "sub" -and $_.external }).Count
  $extAudio = @($tracks | Where-Object { $_.type -eq "audio" -and $_.external }).Count

  Write-Host ""
  Write-Host ("RESULT playback={0} ipc=ok external-subs={1}/{2} external-audio={3}/{4}" -f `
    $(if ($advancing) { "PASS" } else { "FAIL" }), $extSubs, $subs.Count, $extAudio, $audios.Count)
  if (-not $advancing) { $global:LASTEXITCODE = 1 }
}
finally {
  if (-not $Keep) {
    try { [void](Invoke-Mpv @("quit")) } catch { }
    $null = $proc.WaitForExit(5000)
    if (-not $proc.HasExited) { $proc.Kill() }
  }
  $pipe.Dispose()
}
