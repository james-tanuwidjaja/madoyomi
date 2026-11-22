<#
.SYNOPSIS
  One version for Madoyomi: VERSION at the repo root is the source; this script checks or writes
  the copies in the app, the shell and the server.

.DESCRIPTION
  The copies:
    app\package.json                              "version": "X.Y.Z"
    app\src-tauri\tauri.conf.json                 "version": "X.Y.Z"  (the installers' and the exe's version)
    app\src-tauri\Cargo.toml                      version = "X.Y.Z"   ([package])
    app\src-tauri\Cargo.lock                      the madoyomi package's version
    server\buildSrc\src\main\kotlin\Constants.kt  getTachideskVersion = { "vX.Y.Z" }  (the jar's name, BuildConfig)

  With no switch it checks: it prints each copy's version and fails (exit code 1) when one differs from VERSION or
  can't be found. tools\build-release.ps1 runs the check first.
  -Set X.Y.Z writes VERSION and every copy; -Sync writes the copies from VERSION.

  The version must be numeric X.Y.Z with no pre-release suffix: the MSI wants major and minor <= 255 and the third
  part <= 65535, and the server derives an integer version code from it (AppInfo joins the parts: 0.1.0 is 10).
  Files are rewritten in place (UTF-8 without a BOM, line endings kept); only the version text changes.

  -SelfTest runs the script on a temporary copy of the five files and VERSION: a set, a check, a hand-made mismatch
  that must fail, a sync that repairs it, and versions that must be refused. It never touches the repo.

  Keep this file ASCII; it runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER Set
  The new version (X.Y.Z): written to VERSION and every copy.

.PARAMETER Sync
  Write every copy from VERSION.

.PARAMETER Root
  The repo root to work on. Default: this script's repo.

.PARAMETER SelfTest
  Run the self-test on a temporary copy.

.EXAMPLE
  .\tools\version.ps1
.EXAMPLE
  .\tools\version.ps1 -Set 0.2.0
#>
[CmdletBinding()]
param(
    [string]$Set,
    [switch]$Sync,
    [string]$Root,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
$Root = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Root)

# Each copy: the file and a regex whose group 'v' is the version text (the first match counts).
$Targets = @(
    @{ Name = 'app\package.json'; Pattern = '(?m)^\s*"version"\s*:\s*"(?<v>[^"]*)"' },
    @{ Name = 'app\src-tauri\tauri.conf.json'; Pattern = '(?m)^\s*"version"\s*:\s*"(?<v>[^"]*)"' },
    @{ Name = 'app\src-tauri\Cargo.toml'; Pattern = '(?m)^version\s*=\s*"(?<v>[^"]*)"' },
    @{ Name = 'app\src-tauri\Cargo.lock'; Pattern = '(?m)^name = "madoyomi"\r?\nversion = "(?<v>[^"]*)"' },
    @{ Name = 'server\buildSrc\src\main\kotlin\Constants.kt'; Pattern = '(?m)^val getTachideskVersion = \{ "v(?<v>[^"]*)" \}' }
)

function Test-MadoyomiVersion([string]$Version) {
    $m = [regex]::Match($Version, '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$')
    if (-not $m.Success) { return "'$Version' is not a numeric X.Y.Z version (no suffix, no leading zeros)" }
    if ([long]$m.Groups[1].Value -gt 255 -or [long]$m.Groups[2].Value -gt 255) { return "'$Version': the MSI wants major and minor <= 255" }
    if ([long]$m.Groups[3].Value -gt 65535) { return "'$Version': the MSI wants the third part <= 65535" }
    return $null
}

function Read-Text([string]$Path) { [IO.File]::ReadAllText($Path) }
function Write-Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false))) }

function Get-SourceVersion([string]$RepoRoot) {
    $path = Join-Path $RepoRoot 'VERSION'
    if (-not (Test-Path -LiteralPath $path)) { throw "VERSION is missing in $RepoRoot" }
    $v = (Read-Text $path).Trim()
    $bad = Test-MadoyomiVersion $v
    if ($bad) { throw "VERSION: $bad" }
    return $v
}

# Returns one row per copy: Name, Version (or $null when the pattern isn't found).
function Get-CopyVersions([string]$RepoRoot) {
    foreach ($t in $Targets) {
        $path = Join-Path $RepoRoot $t.Name
        $found = $null
        if (Test-Path -LiteralPath $path) {
            $m = [regex]::Match((Read-Text $path), $t.Pattern)
            if ($m.Success) { $found = $m.Groups['v'].Value }
        }
        [pscustomobject]@{ Name = $t.Name; Version = $found }
    }
}

function Set-CopyVersions([string]$RepoRoot, [string]$Version) {
    foreach ($t in $Targets) {
        $path = Join-Path $RepoRoot $t.Name
        $text = Read-Text $path
        $m = [regex]::Match($text, $t.Pattern)
        if (-not $m.Success) { throw "$($t.Name): no version found to replace" }
        $g = $m.Groups['v']
        if ($g.Value -eq $Version) { Write-Output "  $($t.Name): $Version (unchanged)"; continue }
        Write-Text $path ($text.Substring(0, $g.Index) + $Version + $text.Substring($g.Index + $g.Length))
        Write-Output "  $($t.Name): $($g.Value) -> $Version"
    }
}

# Prints the copies and returns $true when all equal VERSION.
function Test-Copies([string]$RepoRoot) {
    $want = Get-SourceVersion $RepoRoot
    $ok = $true
    Write-Output "VERSION: $want"
    foreach ($r in Get-CopyVersions $RepoRoot) {
        if ($null -eq $r.Version) { Write-Output "  $($r.Name): NOT FOUND"; $ok = $false }
        elseif ($r.Version -ne $want) { Write-Output "  $($r.Name): $($r.Version) MISMATCH"; $ok = $false }
        else { Write-Output "  $($r.Name): $($r.Version)" }
    }
    return $ok
}

function Invoke-SelfTest {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("madoyomi-version-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    $state = @{ Failures = 0 }
    function Assert([bool]$Cond, [string]$What) {
        if ($Cond) { Write-Output "PASS $What" } else { Write-Output "FAIL $What"; $state.Failures++ }
    }
    try {
        foreach ($rel in @('VERSION') + @($Targets | ForEach-Object { $_.Name })) {
            $to = Join-Path $tmp $rel
            New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
            Copy-Item -LiteralPath (Join-Path $Root $rel) -Destination $to
        }
        $before = @{}
        foreach ($t in $Targets) { $before[$t.Name] = Read-Text (Join-Path $tmp $t.Name) }

        $out = @(Test-Copies $tmp); $okNow = $out[-1]
        Assert ($okNow -eq $true) "the repo's copies match VERSION ($((Read-Text (Join-Path $tmp 'VERSION')).Trim()))"

        Write-Text (Join-Path $tmp 'VERSION') "12.34.5678`n"
        Set-CopyVersions $tmp '12.34.5678' | Out-Null
        $rows = @(Get-CopyVersions $tmp)
        Assert (@($rows | Where-Object { $_.Version -ne '12.34.5678' }).Count -eq 0) 'a set writes every copy'
        $kt = Read-Text (Join-Path $tmp 'server\buildSrc\src\main\kotlin\Constants.kt')
        Assert ($kt.Contains('{ "v12.34.5678" }')) 'the server keeps its v prefix'
        $changed = $true
        foreach ($t in $Targets) {
            $a = $before[$t.Name]; $b = Read-Text (Join-Path $tmp $t.Name)
            # Only the version text may change.
            $old = [regex]::Match($a, $t.Pattern).Groups['v']
            $expect = $a.Substring(0, $old.Index) + '12.34.5678' + $a.Substring($old.Index + $old.Length)
            if ($b -ne $expect) { $changed = $false; Write-Output "  $($t.Name) changed beyond its version" }
        }
        Assert $changed 'nothing but the version changes (line endings and the rest kept)'
        $lock = Read-Text (Join-Path $tmp 'app\src-tauri\Cargo.lock')
        Assert (([regex]::Matches($lock, 'version = "12\.34\.5678"')).Count -eq 1) "Cargo.lock: only madoyomi's entry changes"

        $out = @(Test-Copies $tmp); Assert ($out[-1] -eq $true) 'the check passes after a set'

        $pj = Join-Path $tmp 'app\package.json'
        Write-Text $pj ((Read-Text $pj).Replace('"version": "12.34.5678"', '"version": "12.34.5679"'))
        $out = @(Test-Copies $tmp)
        Assert ($out[-1] -eq $false) 'the check fails on a mismatch'
        Assert ([bool]($out | Where-Object { "$_" -like '*package.json: 12.34.5679 MISMATCH*' })) 'the mismatch is named'

        Set-CopyVersions $tmp (Get-SourceVersion $tmp) | Out-Null
        $out = @(Test-Copies $tmp); Assert ($out[-1] -eq $true) 'a sync repairs it'

        foreach ($bad in '0.1.0-dev', 'v0.1.0', '0.1', '1.2.3.4', '256.0.0', '0.256.0', '0.0.65536', '01.2.3', '') {
            Assert ($null -ne (Test-MadoyomiVersion $bad)) "'$bad' is refused"
        }
        foreach ($good in '0.1.0', '255.255.65535', '1.0.0') {
            Assert ($null -eq (Test-MadoyomiVersion $good)) "'$good' is accepted"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($state.Failures) { Write-Output "SELFTEST FAILED: $($state.Failures)"; exit 1 }
    Write-Output 'SELFTEST PASSED'
}

if ($SelfTest) { Invoke-SelfTest; exit 0 }

if ($Set) {
    $bad = Test-MadoyomiVersion $Set
    if ($bad) { throw $bad }
    Write-Text (Join-Path $Root 'VERSION') "$Set`n"
    Write-Output "VERSION: $Set"
    Set-CopyVersions $Root $Set
    exit 0
}
if ($Sync) {
    $v = Get-SourceVersion $Root
    Write-Output "VERSION: $v"
    Set-CopyVersions $Root $v
    exit 0
}

$results = @(Test-Copies $Root)
$results | Select-Object -SkipLast 1 | ForEach-Object { Write-Output $_ }
if (-not $results[-1]) {
    Write-Output 'Version mismatch: run .\tools\version.ps1 -Sync (or -Set X.Y.Z) and commit the result.'
    exit 1
}
exit 0
