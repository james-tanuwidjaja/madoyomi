<#
.SYNOPSIS
  Builds a release of Madoyomi (Phase 7): the server jar, the jlink runtime, the pinned mpv/ffmpeg/yt-dlp, then
  `pnpm tauri build` with the bundle's resources.

.DESCRIPTION
  0. tools\version.ps1: VERSION and its copies (package.json, tauri.conf.json, Cargo.toml/Cargo.lock, the server's
     Constants.kt) must match, or the build stops before anything is built (Phase 7 Stage 2).
  1. The server jar (tools\fetch-upstream-assets.ps1, then .\gradlew.bat :api:shadowJar with tools\bin\jdk-21, or
     MADOYOMI_JDK/JAVA_HOME without it, as in CI),
     copied to app\src-tauri\resources\server\Madoyomi-Server.jar. Its name must carry VERSION
     (Madoyomi-Server-vX.Y.Z.jar), so a -NoJar build can't bundle a jar of another version.
  2. tools\jlink.ps1: the runtime in app\src-tauri\resources\jre.
  3. tools\fetch-binaries.ps1: mpv, ffmpeg and yt-dlp (tools\binaries.json) in app\src-tauri\resources, each
     with its license texts (pinned too).
  4. mpv's config (player\mpv.conf, player\input.conf) is bundled from the repo as it is.
  5. `pnpm tauri build --config src-tauri/tauri.release.conf.json` in app\: the release exe with the resources
     beside it (app\src-tauri\target\release) and the installers (target\release\bundle): NSIS per-user and the
     MSI (the installer settings, which need no binaries, are in tauri.conf.json). The resources (with LICENSE,
     NOTICE and THIRD_PARTY.md) are in a config of their own, so `pnpm tauri dev` and `cargo test` don't need them.
  6. The updater's files (Phase 7 Stage 4): Tauri signs the NSIS installer with the updater's private key
     (bundle.createUpdaterArtifacts in tauri.conf.json) and writes its .sig beside it; this script then writes
     latest.json beside them (bundle\nsis\latest.json): the version, the notes, the date, and the installer's URL
     and signature under windows-x86_64-nsis and windows-x86_64. The key comes from the environment, never from the
     repo: TAURI_SIGNING_PRIVATE_KEY (the key's path or its text) and TAURI_SIGNING_PRIVATE_KEY_PASSWORD. Without
     the key, use -NoUpdater (installers without a .sig and no latest.json).
  Everything it writes is gitignored (app\src-tauri\resources, target, tools\bin). Don't run it while the app or a
  test server runs on the jar it rebuilds (Windows locks a running jar).
  Keep this file ASCII; it runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER NoJar
  Use the newest server\api\build\Madoyomi-Server-*.jar without building it.

.PARAMETER NoBundle
  Build the release exe (with its resources beside it) but no installers (`tauri build --no-bundle`).

.PARAMETER SkipTauri
  Prepare app\src-tauri\resources only.

.PARAMETER NoUpdater
  Build the installers without the updater's signature and latest.json (no signing key needed).

.PARAMETER Notes
  The release notes in latest.json (plain text, shown in the app's update dialog). Default: "Madoyomi X.Y.Z".

.PARAMETER NotesFile
  A UTF-8 text file with the release notes (instead of -Notes).

.PARAMETER DownloadUrl
  The installer's URL in latest.json. Default: the GitHub release's asset,
  https://github.com/james-tanuwidjaja/madoyomi/releases/download/vX.Y.Z/<installer>.

.PARAMETER Config
  Another Tauri config merged after tauri.release.conf.json (for a test build's feed; never for a release).

.EXAMPLE
  .\tools\build-release.ps1
.EXAMPLE
  .\tools\build-release.ps1 -NoJar -NoBundle
.EXAMPLE
  .\tools\build-release.ps1 -NoJar -NotesFile notes.txt
#>
[CmdletBinding()]
param(
    [switch]$NoJar,
    [switch]$NoBundle,
    [switch]$SkipTauri,
    [switch]$NoUpdater,
    [string]$Notes,
    [string]$NotesFile,
    [string]$DownloadUrl,
    [string]$Config
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\MadoyomiServer.ps1')
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Resources = Join-Path $RepoRoot 'app\src-tauri\resources'
$total = [Diagnostics.Stopwatch]::StartNew()

function Step([string]$Name) { Write-Output ("[{0:mm\:ss}] {1}" -f $total.Elapsed, $Name) }

Step 'version'
& (Join-Path $PSScriptRoot 'version.ps1')
if ($LASTEXITCODE -ne 0) { throw 'tools\version.ps1: the versions differ (see above); run .\tools\version.ps1 -Sync' }
$version = (Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'VERSION')).Trim()

$signing = -not ($NoUpdater -or $NoBundle -or $SkipTauri)
if ($signing -and -not $env:TAURI_SIGNING_PRIVATE_KEY) {
    throw 'the updater needs its private key: set TAURI_SIGNING_PRIVATE_KEY (the key file or its text) and TAURI_SIGNING_PRIVATE_KEY_PASSWORD, or build with -NoUpdater'
}
if ($Notes -and $NotesFile) { throw 'give -Notes or -NotesFile, not both' }
if ($NotesFile) { $Notes = (Get-Content -Raw -Encoding UTF8 -LiteralPath $NotesFile).Trim() }
if (-not $Notes) { $Notes = "Madoyomi $version" }
if ($Config) { $Config = (Resolve-Path -LiteralPath $Config).Path }

Step 'server jar'
$jar = Resolve-MadoyomiJar $RepoRoot '' -NoBuild:$NoJar
if ((Split-Path $jar -Leaf) -ne "Madoyomi-Server-v$version.jar") {
    throw "the server jar $jar is not version $version (Madoyomi-Server-v$version.jar): build it (without -NoJar)"
}
$serverDir = Join-Path $Resources 'server'
New-Item -ItemType Directory -Force -Path $serverDir | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $serverDir 'Madoyomi-Server.jar') -Force
Write-Output "  $jar -> $serverDir\Madoyomi-Server.jar"

Step 'jlink runtime'
& (Join-Path $PSScriptRoot 'jlink.ps1') -Jar $jar
if (-not $?) { throw 'tools\jlink.ps1 failed' }

Step 'mpv, ffmpeg, yt-dlp'
& (Join-Path $PSScriptRoot 'fetch-binaries.ps1')
if (-not $?) { throw 'tools\fetch-binaries.ps1 failed' }

foreach ($d in 'jre', 'server', 'mpv', 'ffmpeg', 'yt-dlp') {
    $mb = [math]::Round(((Get-ChildItem -LiteralPath (Join-Path $Resources $d) -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB, 1)
    Write-Output "  resources\$d : $mb MB"
}
if ($SkipTauri) { Step 'done (resources only)'; return }

Step 'pnpm tauri build'
Push-Location (Join-Path $RepoRoot 'app')
try {
    $tauriArgs = 'tauri build --config src-tauri/tauri.release.conf.json'
    # The updater's signed installer and its .sig (the key from the environment).
    if ($signing) { $tauriArgs += ' --config src-tauri/tauri.updater.conf.json' }
    if ($Config) { $tauriArgs += " --config `"$Config`"" }
    if ($NoBundle) { $tauriArgs += ' --no-bundle' }
    cmd /c "pnpm $tauriArgs 2>&1" | ForEach-Object { Write-Output "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "pnpm $tauriArgs failed ($LASTEXITCODE)" }
} finally { Pop-Location }

$release = Join-Path $RepoRoot 'app\src-tauri\target\release'
Write-Output "  exe: $release\madoyomi.exe"
if (-not $NoBundle) {
    # The bundle folder keeps older versions' installers: list this version's.
    Get-ChildItem (Join-Path $release 'bundle') -Recurse -Include "*_${version}_*.exe", "*_${version}_*.msi" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Output ("  installer: {0} ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) }
}

if ($signing) {
    Step 'latest.json'
    $nsis = Join-Path $release 'bundle\nsis'
    $setup = Get-ChildItem -LiteralPath $nsis -Filter "*_${version}_x64-setup.exe" | Select-Object -First 1
    if (-not $setup) { throw "no NSIS installer for $version in $nsis" }
    $sigFile = "$($setup.FullName).sig"
    if (-not (Test-Path -LiteralPath $sigFile)) { throw "no updater signature $sigFile (is bundle.createUpdaterArtifacts on?)" }
    $signature = (Get-Content -Raw -LiteralPath $sigFile).Trim()
    $url = $DownloadUrl
    if (-not $url) { $url = "https://github.com/james-tanuwidjaja/madoyomi/releases/download/v$version/$($setup.Name)" }
    $platform = [ordered]@{ signature = $signature; url = $url }
    $manifest = [ordered]@{
        version = $version
        notes = $Notes
        pub_date = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        # the NSIS install looks for windows-x86_64-nsis first; an MSI install (which only says a version is out)
        # falls back to windows-x86_64
        platforms = [ordered]@{ 'windows-x86_64-nsis' = $platform; 'windows-x86_64' = $platform }
    }
    $latest = Join-Path $nsis 'latest.json'
    [IO.File]::WriteAllText($latest, ($manifest | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    Write-Output "  $latest ($url)"
}
Step 'done'
