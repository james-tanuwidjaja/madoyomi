<#
.SYNOPSIS
  Builds and runs the Madoyomi backend (server/) the way the Tauri shell starts it, for local development.

.DESCRIPTION
  1. Restores the upstream binary assets and builds the server jar with JDK 21 from tools/bin/jdk-21
     (skip with -NoBuild).
  2. Generates a random API token (or uses -Token), passes it to the server as MADOYOMI_TOKEN and prints it.
  3. Starts the jar with --data-dir (default: tools/dev-data, gitignored), echoes its output, and once
     the MADOYOMI_PORT=<port> line appears prints the base URL and example requests.

  Stop the server with Ctrl+C. It also stops by itself when its stdin closes, which is what happens
  when Tauri exits.

.PARAMETER NoBuild
  Run the most recently built jar without rebuilding.

.PARAMETER DataDir
  Data directory for the server (database, settings, extensions, logs). Default: tools/dev-data.

.PARAMETER Token
  API token to use instead of a random one.

.EXAMPLE
  .\tools\run-server.ps1
.EXAMPLE
  .\tools\run-server.ps1 -NoBuild -DataDir D:\tmp\madoyomi-data
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [string]$DataDir,
    [string]$Token
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'
$Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21'
$Java = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $Java)) { throw "JDK 21 not found at $Jdk" }

if (-not $DataDir) { $DataDir = Join-Path $RepoRoot 'tools\dev-data' }
$DataDir = [System.IO.Path]::GetFullPath($DataDir)

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

# The api module builds the fat jar (see server/api/build.gradle.kts).
$jar = Get-ChildItem -LiteralPath (Join-Path $ServerDir 'api\build') -Filter 'Madoyomi-Server-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw 'No Madoyomi-Server jar in server\api\build. Run without -NoBuild first.' }

if (-not $Token) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $Token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
$env:MADOYOMI_TOKEN = $Token

Write-Host "jar:      $($jar.FullName)"
Write-Host "data dir: $DataDir"
Write-Host "token:    $Token"
Write-Host ''

# stdout is piped through this loop to spot the handshake line; stdin stays the console.
& $Java -jar $jar.FullName --data-dir $DataDir | ForEach-Object {
    Write-Host $_
    if ($_ -match '^MADOYOMI_PORT=(\d+)$') {
        $base = "http://127.0.0.1:$($Matches[1])"
        Write-Host ''
        Write-Host "Madoyomi-Server is listening on $base" -ForegroundColor Green
        Write-Host "  curl.exe $base/health"
        Write-Host "  Invoke-RestMethod $base/api/graphql -Method Post -ContentType application/json -Headers @{ Authorization = 'Bearer $Token' } -Body '{`"query`":`"{ aboutServer { name version } }`"}' | ConvertTo-Json -Depth 5"
        Write-Host ''
    }
}
