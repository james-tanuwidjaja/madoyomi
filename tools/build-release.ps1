<#
.SYNOPSIS
  Builds a release of Madoyomi (Phase 7): the server jar, the jlink runtime, the pinned mpv/ffmpeg/yt-dlp, then
  `pnpm tauri build` with the bundle's resources.

.DESCRIPTION
  0. tools\version.ps1: VERSION and its copies (package.json, tauri.conf.json, Cargo.toml/Cargo.lock, the server's
     Constants.kt) must match, or the build stops before anything is built (Phase 7 Stage 2).
  1. The server jar (tools\fetch-upstream-assets.ps1, then .\gradlew.bat :api:shadowJar with tools\bin\jdk-21),
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
  Everything it writes is gitignored (app\src-tauri\resources, target, tools\bin). Don't run it while the app or a
  test server runs on the jar it rebuilds (Windows locks a running jar).
  Keep this file ASCII; it runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER NoJar
  Use the newest server\api\build\Madoyomi-Server-*.jar without building it.

.PARAMETER NoBundle
  Build the release exe (with its resources beside it) but no installers (`tauri build --no-bundle`).

.PARAMETER SkipTauri
  Prepare app\src-tauri\resources only.

.EXAMPLE
  .\tools\build-release.ps1
.EXAMPLE
  .\tools\build-release.ps1 -NoJar -NoBundle
#>
[CmdletBinding()]
param(
    [switch]$NoJar,
    [switch]$NoBundle,
    [switch]$SkipTauri
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
Step 'done'
